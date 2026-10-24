package local.zve10viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.os.BatteryManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionException
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.SettingID
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.usb.UsbPtpTransport
import kotlin.coroutines.cancellation.CancellationException

/** Состояние приложения: поиск камеры, разрешение USB, сеанс и live view. */
class CameraController(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { searching, permission, connecting, settling, ready, notPcRemote }

    var phase by mutableStateOf(Phase.searching)
        private set
    /** Почему сейчас нет связи (показывается на экране ожидания). */
    var message by mutableStateOf<String?>(null)
        private set
    var deviceInfo by mutableStateOf<DeviceInfo?>(null)
        private set
    var properties by mutableStateOf<Map<Int, SonyProperty>>(emptyMap())
        private set
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var fps by mutableStateOf(0.0)
        private set
    /** Кадры давно не приходят. */
    var isFrameStale by mutableStateOf(false)
        private set
    /** Заряд самого планшета: в полноэкранном режиме системную строку не видно. */
    var tabletBattery by mutableStateOf<Int?>(null)
        private set
    var tabletCharging by mutableStateOf(false)
        private set
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private var batteryCheckedAt = 0L

    @Volatile var lastJpeg: ByteArray? = null
        private set

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private var device: UsbDevice? = null
    private var transport: UsbPtpTransport? = null
    private var session: CameraSession? = null
    private val jobs = mutableListOf<Job>()
    private val frameTimes = ArrayDeque<Long>()
    private var lastFrameAt = 0L
    private var failures = 0
    private var retryAt = 0L

    fun property(id: SettingID): SonyProperty? = properties[id.code]

    val mode: CaptureMode
        get() = property(SettingID.exposureProgram)?.current?.intValue?.let(CaptureMode::of) ?: CaptureMode.video

    val isRecording: Boolean
        get() = properties[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue == 1L

    fun start() {
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        // подключение камеры отслеживаем опросом: широковещательное ATTACHED приходит не всем
        scope.launch {
            while (isActive) {
                if (device == null && System.currentTimeMillis() >= retryAt) findCamera()
                tickFrameStats()
                tickTabletBattery()
                delay(500)
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        teardown()
    }

    private fun findCamera() {
        val camera = usbManager.deviceList.values.firstOrNull { it.vendorId == Sony.VENDOR_ID } ?: return
        device = camera
        if (usbManager.hasPermission(camera)) {
            connect(camera)
        } else {
            phase = Phase.permission
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            usbManager.requestPermission(camera, PendingIntent.getBroadcast(context, 0, intent, flags))
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val target = deviceFrom(intent)
            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    val camera = device ?: return
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        connect(camera)
                    } else {
                        message = "Нет разрешения на доступ к камере. Отключите и снова подключите кабель."
                        device = null
                        phase = Phase.searching
                        retryAt = Long.MAX_VALUE
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (target?.deviceName == device?.deviceName) {
                        teardown()
                        message = null
                        failures = 0
                        retryAt = 0
                    }
                }
            }
        }
    }

    private fun deviceFrom(intent: Intent): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun connect(camera: UsbDevice) {
        phase = Phase.connecting
        message = null
        val usbInterface = (0 until camera.interfaceCount).map(camera::getInterface)
            .firstOrNull { it.interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE } ?: camera.getInterface(0)
        val connection = usbManager.openDevice(camera)
        if (connection == null || !connection.claimInterface(usbInterface, true)) {
            connection?.close()
            retryLater("Не удалось открыть камеру")
            return
        }
        val transport = UsbPtpTransport(connection, usbInterface)
        val session = CameraSession(transport)
        this.transport = transport
        this.session = session
        jobs += scope.launch {
            try {
                session.start(this)
                deviceInfo = session.deviceInfo
                phase = Phase.settling
                startStreams(session)
                session.waitUntilReady()
                phase = Phase.ready
                failures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionException.NotPcRemote) {
                deviceInfo = session.deviceInfo
                phase = Phase.notPcRemote
            } catch (e: Exception) {
                Log.w(TAG, "подключение не удалось", e)
                retryLater("Не удалось подключиться к камере: ${e.message}")
            }
        }
    }

    /** Повтор через 2, 4, 8, 10, 10… с, пока камера подключена. */
    private fun retryLater(text: String) {
        teardown()
        failures++
        val seconds = minOf(1L shl failures, 10L)
        message = "$text. Повтор через $seconds с…"
        retryAt = System.currentTimeMillis() + seconds * 1000
    }

    private fun startStreams(session: CameraSession) {
        jobs += scope.launch {
            session.properties.collect { properties = it }
        }
        jobs += scope.launch {
            session.ended.await()
            if (device != null) retryLater("Связь с камерой оборвалась")
        }
        jobs += scope.launch(Dispatchers.Default) {
            var errors = 0
            var lastArrival = 0L
            while (isActive) {
                try {
                    val t0 = System.nanoTime()
                    val jpeg = session.nextLiveViewFrame()
                    val t1 = System.nanoTime()
                    errors = 0
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: continue
                    val t2 = System.nanoTime()
                    withContext(Dispatchers.Main) { show(bitmap, jpeg) }
                    val t3 = System.nanoTime()
                    if (lastArrival != 0L && t1 - lastArrival > 100_000_000L) {
                        Log.i("ZVFrames", "пауза %.0f мс: запрос %.0f мс (ожиданий %d), декодирование %.0f мс, показ %.0f мс".format(
                            (t1 - lastArrival) / 1e6, (t1 - t0) / 1e6, session.lastLiveViewWaits, (t2 - t1) / 1e6, (t3 - t2) / 1e6))
                    }
                    lastArrival = t1
                } catch (e: CancellationException) {
                    throw e
                } catch (e: TransportException) {
                    if (e.disconnected) break
                    errors++
                    delay(minOf(100L * errors, 1000L))
                } catch (e: Exception) {
                    // временный сбой (таймаут, неожиданный ответ) — не бросаем live view, пробуем дальше
                    errors++
                    delay(minOf(100L * errors, 1000L))
                }
            }
        }
    }

    /** Замер ровности видео: интервалы между кадрами и повторы одного и того же кадра. Пишется в журнал раз в 3 с. */
    private val frameLog = ArrayList<Pair<Long, Int>>()
    private var frameLogStart = 0L

    private fun measure(jpeg: ByteArray) {
        val now = System.nanoTime()
        if (frameLog.isEmpty()) frameLogStart = now
        frameLog += now to jpeg.contentHashCode()
        if (now - frameLogStart < 3_000_000_000L) return
        val gaps = frameLog.zipWithNext { a, b -> (b.first - a.first) / 1_000_000.0 }.sorted()
        val repeats = frameLog.zipWithNext().count { (a, b) -> a.second == b.second }
        if (gaps.isNotEmpty()) {
            Log.i("ZVFrames", "кадров %d за 3 с, повторов %d, интервал: медиана %.0f мс, 90%% %.0f мс, макс %.0f мс".format(
                frameLog.size, repeats, gaps[gaps.size / 2], gaps[(gaps.size * 9) / 10], gaps.last()))
        }
        frameLog.clear()
    }

    private fun show(bitmap: ImageBitmap, jpeg: ByteArray) {
        measure(jpeg)
        frame = bitmap
        lastJpeg = jpeg
        val now = System.currentTimeMillis()
        lastFrameAt = now
        frameTimes.addLast(now)
        tickFrameStats()
    }

    /** fps за последние 2 с и признак «кадры не приходят» (больше 1,5 с). */
    private fun tickFrameStats() {
        val now = System.currentTimeMillis()
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2000) frameTimes.removeFirst()
        fps = frameTimes.size / 2.0
        val live = phase == Phase.settling || phase == Phase.ready
        isFrameStale = live && frame != null && now - lastFrameAt > 1500
    }

    private fun tickTabletBattery() {
        val now = System.currentTimeMillis()
        if (now - batteryCheckedAt < 15_000) return
        batteryCheckedAt = now
        tabletBattery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        tabletCharging = batteryManager.isCharging
    }

    /** Останавливает всё, что связано с текущей камерой, и закрывает соединение. */
    private fun teardown() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        session?.close()
        transport?.close()
        session = null
        transport = null
        device = null
        deviceInfo = null
        properties = emptyMap()
        frame = null
        lastJpeg = null
        frameTimes.clear()
        fps = 0.0
        isFrameStale = false
        phase = Phase.searching
    }

    companion object {
        private const val TAG = "ZVController"
        private const val ACTION_USB_PERMISSION = "local.zve10viewer.USB_PERMISSION"
    }
}
