package local.zve10viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
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
            while (isActive) {
                try {
                    val jpeg = session.nextLiveViewFrame()
                    errors = 0
                    val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap() ?: continue
                    withContext(Dispatchers.Main) { show(bitmap, jpeg) }
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

    private fun show(bitmap: ImageBitmap, jpeg: ByteArray) {
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
