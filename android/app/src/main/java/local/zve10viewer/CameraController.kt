package local.zve10viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpRejected
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionException
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.RecordingMode
import local.zve10viewer.settings.RecordingModes
import local.zve10viewer.settings.SettingOption
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOptions
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.settings.WhiteBalanceTemperature
import local.zve10viewer.settings.WriteCoalescer
import local.zve10viewer.settings.apply
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.usb.UsbPtpTransport
import local.zve10viewer.wifi.PtpIpIdentity
import local.zve10viewer.wifi.PtpIpTransport
import local.zve10viewer.wifi.WiFiDiscovery
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/** Состояние приложения: поиск камеры, разрешение USB, сеанс, live view, настройки, съёмка и пресеты. */
class CameraController(private val context: Context, private val scope: CoroutineScope) {
    enum class Phase { searching, permission, connecting, settling, ready, notPcRemote }

    /** Снимок или стоп-кадр этой сессии. */
    class Shot(val files: List<SavedFile>, val thumbnail: ImageBitmap?, val preview: ByteArray?) {
        val title: String get() = files.joinToString(" + ") { it.name }
    }

    /** Что показывается поверх видео после снимка или при открытии снимка из ленты. */
    data class Review(
        val image: ImageBitmap?, val title: String, val detail: String,
        val files: List<SavedFile>, val source: String,
        /** null — показывается, пока не закроют (снимок открыт из ленты). */
        val endsAt: Long?,
    )

    val prefs = Prefs(context)
    val saver = MediaSaver(context)

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
    /** fps для строки состояния: обновляется дважды в секунду, а не с каждым кадром. */
    var shownFps by mutableStateOf(0)
        private set
    /** Самая долгая пауза между кадрами за последние 10 с, мс — насколько ровно идёт картинка. */
    var frameGapMs by mutableStateOf(0)
        private set
    /** Кадры давно не приходят. */
    var isFrameStale by mutableStateOf(false)
        private set
    /** Гистограмма яркости последнего кадра (64 столбца), пока она включена. */
    var histogram by mutableStateOf<IntArray?>(null)
        private set
    var histogramOn by mutableStateOf(prefs.histogramOn)
        private set
    /** Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила. */
    var pending by mutableStateOf<Set<SettingID>>(emptySet())
        private set
    var presets by mutableStateOf(prefs.presets)
        private set
    var isApplyingPreset by mutableStateOf(false)
        private set
    var isCapturing by mutableStateOf(false)
        private set
    /** Короткое сообщение: пресет применён, снимок сохранён и т.п. */
    var statusMessage by mutableStateOf<String?>(null)
        private set
    /** Когда началась запись (для таймера «● REC»). */
    var recordingStartedAt by mutableStateOf<Long?>(null)
        private set
    var review by mutableStateOf<Review?>(null)
        private set
    /** Снимки и стоп-кадры этой сессии, новые сначала. */
    var shots by mutableStateOf<List<Shot>>(emptyList())
        private set
    /** Заряд самого планшета: в полноэкранном режиме системную строку не видно. */
    var tabletBattery by mutableStateOf<Int?>(null)
        private set
    var tabletCharging by mutableStateOf(false)
        private set

    enum class TransportKind { usb, wifi }
    /** Чем подключена камера сейчас. */
    var transportKind by mutableStateOf<TransportKind?>(null)
        private set
    /** Связь по Wi-Fi напрямую: камера сама раздаёт сеть (режим Wi-Fi Direct). */
    var wifiDirect by mutableStateOf(false)
        private set
    /** Идёт сопряжение по Wi-Fi (кнопка «Сопрячь с камерой»). */
    var isPairing by mutableStateOf(false)
        private set
    /** Что сейчас с Wi-Fi: «ищу камеру», «камера найдена, нужно сопряжение» и т.п. */
    var wifiStatus by mutableStateOf<String?>(null)
        private set

    @Volatile var lastJpeg: ByteArray? = null
        private set

    private val usbManager = context.getSystemService(UsbManager::class.java)
    private val batteryManager = context.getSystemService(BatteryManager::class.java)
    private var device: UsbDevice? = null
    private var transport: PtpTransport? = null
    private val identity by lazy { PtpIpIdentity.load(context) }
    private var wifiBusy = false
    private var wifiFailures = 0
    private var session: CameraSession? = null
    private var coalescer: WriteCoalescer? = null
    private val targets = HashMap<Int, Long>()
    private val jobs = mutableListOf<Job>()
    private var reviewJob: Job? = null
    private var temperatureJob: Job? = null
    private var recordingModeJob: Job? = null
    private var statusJob: Job? = null
    private val frameTimes = ArrayDeque<Long>()
    private var lastFrameAt = 0L
    /** Паузы между кадрами за последние 10 с: (когда пришёл кадр, пауза перед ним). */
    private val frameGaps = ArrayDeque<Pair<Long, Long>>()
    private var failures = 0
    private var retryAt = 0L
    private var batteryCheckedAt = 0L

    fun property(id: SettingID): SonyProperty? = properties[id.code]

    fun isWritable(id: SettingID): Boolean =
        phase == Phase.ready && !isApplyingPreset && !isCapturing && property(id)?.isWritable == true

    val mode: CaptureMode
        get() = property(SettingID.exposureProgram)?.current?.intValue?.let(CaptureMode::of) ?: CaptureMode.video

    val isRecording: Boolean get() = recordingStartedAt != null

    // MARK: - Изменение настроек

    /**
     * Подпись значения настройки. Баланс белого в режиме «Цвет. темп.» — сама температура; температура вне этого
     * режима — примерная или «авто»: камера отдаёт сохранённую ручную, которая сейчас не действует.
     */
    fun label(id: SettingID): String {
        val whiteBalance = property(SettingID.whiteBalance)?.current?.intValue
        val kelvin = property(SettingID.colorTemperature)?.current?.intValue
        return when (id) {
            SettingID.whiteBalance -> ValueFormatter.whiteBalanceLabel(whiteBalance, kelvin)
            SettingID.colorTemperature -> WhiteBalanceTemperature.caption(WhiteBalanceTemperature.reading(whiteBalance, kelvin))
            SettingID.movieRecordingSetting -> RecordingModes.label(property(SettingID.movieFileFormat)?.current?.intValue,
                property(SettingID.movieRecordingSetting)?.current?.intValue)
            else -> ValueFormatter.label(id, property(id))
        }
    }

    /** Режимы записи обоих форматов — «4K 25p 100M», «HD 100p 100M»… */
    val recordingModes: List<RecordingMode>
        get() {
            val format = property(SettingID.movieFileFormat) ?: return emptyList()
            val setting = property(SettingID.movieRecordingSetting) ?: return emptyList()
            val formats = SettingOptions.options(SettingID.movieFileFormat, format).map { it.value }
            return RecordingModes.options(formats.ifEmpty { listOfNotNull(format.current.intValue) }, format.current.intValue,
                SettingOptions.options(SettingID.movieRecordingSetting, setting).map { it.value })
        }

    /** Значения для ленты. У параметров записи — режимы обоих форматов (значение — `RecordingMode.code`). */
    fun stripOptions(id: SettingID): List<SettingOption> =
        if (id == SettingID.movieRecordingSetting) recordingModes.map { SettingOption(it.code, it.label) }
        else property(id)?.let { SettingOptions.options(id, it) } ?: emptyList()

    /** Текущее значение в ленте — в той же кодировке, что `stripOptions`. */
    fun stripCurrent(id: SettingID): Long? {
        if (id != SettingID.movieRecordingSetting) return property(id)?.current?.intValue
        val format = property(SettingID.movieFileFormat)?.current?.intValue ?: return null
        val setting = property(SettingID.movieRecordingSetting)?.current?.intValue ?: return null
        return RecordingMode(format, setting).code
    }

    /** Выбор в ленте: обычная настройка — запись значения, режим записи — формат и параметры вместе. */
    fun choose(id: SettingID, value: Long) {
        if (id == SettingID.movieRecordingSetting && value > 0xFF) setRecordingMode(RecordingMode.of(value)) else request(id, value)
    }

    /**
     * Формат и параметры записи. Параметры камера принимает только для текущего формата: если формат другой,
     * сначала он, и ждём, пока камера подтвердит его и откроет нужные параметры.
     */
    fun setRecordingMode(mode: RecordingMode) {
        recordingModeJob?.cancel()
        if (property(SettingID.movieFileFormat)?.current?.intValue == mode.format) {
            request(SettingID.movieRecordingSetting, mode.setting)
            return
        }
        if (!isWritable(SettingID.movieFileFormat)) return
        request(SettingID.movieFileFormat, mode.format)
        pending = pending + SettingID.movieRecordingSetting
        recordingModeJob = scope.launch {
            repeat(50) {
                delay(100)
                val setting = property(SettingID.movieRecordingSetting)
                val ready = property(SettingID.movieFileFormat)?.current?.intValue == mode.format && setting != null &&
                    SettingOptions.options(SettingID.movieRecordingSetting, setting).any { it.value == mode.setting } &&
                    isWritable(SettingID.movieRecordingSetting)
                if (ready) {
                    if (setting?.current?.intValue == mode.setting) pending = pending - SettingID.movieRecordingSetting
                    else request(SettingID.movieRecordingSetting, mode.setting)
                    return@launch
                }
            }
            pending = pending - SettingID.movieRecordingSetting
        }
    }

    /**
     * Ручная температура. Пишется, только когда камера в режиме «Цвет. темп.», — иначе сначала переключаем режим
     * и ждём, пока камера подтвердит его.
     */
    fun setColorTemperature(kelvin: Long) {
        temperatureJob?.cancel()
        if (property(SettingID.whiteBalance)?.current?.intValue == WhiteBalanceTemperature.MANUAL_MODE) {
            request(SettingID.colorTemperature, kelvin)
            return
        }
        if (!isWritable(SettingID.whiteBalance)) return
        request(SettingID.whiteBalance, WhiteBalanceTemperature.MANUAL_MODE)
        temperatureJob = scope.launch {
            repeat(40) {
                delay(100)
                if (isWritable(SettingID.colorTemperature)) {
                    if (property(SettingID.colorTemperature)?.current?.intValue != kelvin) request(SettingID.colorTemperature, kelvin)
                    return@launch
                }
            }
        }
    }

    fun request(id: SettingID, value: Long) {
        val coalescer = coalescer ?: return
        if (!isWritable(id)) return
        targets[id.code] = value
        pending = pending + id
        coalescer.submit(id.code, value)
    }

    /** Шаг по списку значений: считаем от последнего запроса, иначе быстрые шаги упирались бы в ожидание камеры. */
    fun requestStep(id: SettingID, offset: Int) {
        if (id == SettingID.movieRecordingSetting) {
            val modes = recordingModes
            val index = modes.indexOfFirst { it.code == stripCurrent(id) }
            if (index < 0) return
            val target = (index + offset).coerceIn(0, modes.size - 1)
            if (target != index) setRecordingMode(modes[target])
            return
        }
        val property = property(id) ?: return
        val target = SettingOptions.neighbour(property, offset, targets[id.code]) ?: return
        request(id, target)
    }

    fun toggleRecording() {
        val session = session ?: return
        if (phase != Phase.ready) return
        scope.launch { runCatching { session.toggleRecording() } }
    }

    /** Переключает камеру между фото и видео, сохраняя букву режима (M ↔ видео M и т.п.). */
    fun switchMode(target: CaptureMode) {
        val p = property(SettingID.exposureProgram) ?: return
        val current = p.current.intValue ?: return
        val value = CaptureMode.counterpart(current, target, p.settableValues) ?: return
        request(SettingID.exposureProgram, value)
    }

    fun toggleHistogram() {
        histogramOn = !histogramOn
        prefs.histogramOn = histogramOn
        if (!histogramOn) histogram = null
    }

    /** Пользователь поменял «куда сохранять» — переносим это в камеру (PC Remote → Still Img. Save Dest.). */
    fun applyDestinationPreference() {
        val session = session ?: return
        if (phase != Phase.ready) return
        val want = prefs.destination.cameraValue
        if (properties[SonyProp.STILL_IMAGE_STORE_DESTINATION]?.current?.intValue == want) return
        scope.launch { runCatching { session.set(SonyProp.STILL_IMAGE_STORE_DESTINATION, want) } }
    }

    // MARK: - Фото

    fun capturePhoto() {
        val session = session ?: return
        if (phase != Phase.ready || mode != CaptureMode.photo || isCapturing || isApplyingPreset) return
        isCapturing = true
        val destination = prefs.destination
        val cameraFrame = frame
        val reviewOn = prefs.reviewEnabled
        val source = if (destination.savesToDevice) prefs.reviewSource else Prefs.ReviewSource.camera
        if (reviewOn) {
            // кадр с камеры есть сразу; загруженный снимок появится после скачивания
            showReview(Review(cameraFrame, "Снимок", if (source == Prefs.ReviewSource.camera) "" else "Загружаю…",
                emptyList(), "Кадр с камеры", null), timed = source == Prefs.ReviewSource.camera)
        }
        scope.launch {
            try {
                if (properties[SonyProp.STILL_IMAGE_STORE_DESTINATION]?.current?.intValue != destination.cameraValue) {
                    session.set(SonyProp.STILL_IMAGE_STORE_DESTINATION, destination.cameraValue)
                }
                val objects = session.capturePhoto(destination.savesToDevice)
                if (!destination.savesToDevice) {
                    showStatus("Снимок сохранён на карту камеры")
                    return@launch
                }
                if (objects.isEmpty()) throw IllegalStateException("камера не отдала файл")
                val files = withContext(Dispatchers.IO) { objects.map { saver.save(it.filename, it.data) } }
                val jpeg = objects.firstOrNull { it.filename.endsWith(".JPG", ignoreCase = true) }?.data
                val large = withContext(Dispatchers.Default) { jpeg?.let { MediaSaver.decode(it, 2048)?.asImageBitmap() } }
                val small = withContext(Dispatchers.Default) { jpeg?.let { MediaSaver.decode(it, 160)?.asImageBitmap() } }
                val shot = Shot(files, small ?: cameraFrame, jpeg)
                shots = listOf(shot) + shots
                val megabytes = objects.sumOf { it.data.size } / 1_048_576.0
                showStatus("Сохранено: ${shot.title}")
                if (reviewOn && review != null) {
                    val downloaded = source == Prefs.ReviewSource.downloaded && large != null
                    showReview(Review(if (downloaded) large else cameraFrame, shot.title,
                        "%.1f МБ · «%s»".format(megabytes, saver.folderTitle), files,
                        if (downloaded) "Загруженный снимок" else "Кадр с камеры", null), timed = true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "снимок не удался", e)
                dismissReview()
                showStatus("Снимок не удался: ${e.message}")
            } finally {
                isCapturing = false
            }
        }
    }

    /** Стоп-кадр из видео: сохраняет текущий кадр live view, запись не прерывается. */
    fun grabFrame() {
        val jpeg = lastJpeg ?: return
        val thumbnail = frame
        scope.launch {
            try {
                val file = withContext(Dispatchers.IO) { saver.save(FileNaming.frameGrab(), jpeg) }
                shots = listOf(Shot(listOf(file), thumbnail, jpeg)) + shots
                showStatus("Кадр сохранён: ${file.name}")
            } catch (e: Exception) {
                showStatus("Не удалось сохранить кадр: ${e.message}")
            }
        }
    }

    /** Открывает снимок из ленты поверх видео (пока не закроют). */
    fun openShot(index: Int) {
        val shot = shots.getOrNull(index) ?: return
        scope.launch {
            val image = withContext(Dispatchers.Default) { shot.preview?.let { MediaSaver.decode(it, 2048)?.asImageBitmap() } }
            showReview(Review(image ?: shot.thumbnail, shot.title, "«${saver.folderTitle}»", shot.files, "Снимок из ленты", null),
                timed = false)
        }
    }

    /** Открывает файл в программе для просмотра на планшете. */
    fun openExternally(file: SavedFile) {
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(file.uri, file.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.onFailure { showStatus("Нет программы, чтобы открыть ${file.name}") }
    }

    fun dismissReview() {
        reviewJob?.cancel()
        review = null
    }

    private fun showReview(value: Review, timed: Boolean) {
        reviewJob?.cancel()
        if (!timed) {
            review = value
            return
        }
        val seconds = prefs.reviewSeconds
        review = value.copy(endsAt = System.currentTimeMillis() + seconds * 1000L)
        reviewJob = scope.launch {
            delay(seconds * 1000L)
            review = null
        }
    }

    // MARK: - Пресеты

    fun savePreset(name: String? = null) {
        if (phase != Phase.ready) return
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: "Пресет ${presets.size + 1}"
        presets = presets + PresetCatalog.capture(title, properties)
        prefs.presets = presets
        showStatus("Сохранено: «$title»")
    }

    fun applyPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        val session = session ?: return
        if (phase != Phase.ready || isApplyingPreset) return
        isApplyingPreset = true
        statusJob?.cancel()
        statusMessage = "Применяю «${preset.name}»…"
        scope.launch {
            val result = session.apply(preset)
            isApplyingPreset = false
            val total = result.applied.size + result.skipped.size
            var text = "«${preset.name}»: применено ${result.applied.size} из $total"
            if (result.skipped.isNotEmpty()) text += ". Не удалось: " + result.skipped.joinToString(", ") { it.title }
            showStatus(text)
        }
    }

    fun renamePreset(index: Int, name: String) {
        val trimmed = name.trim()
        if (index !in presets.indices || trimmed.isEmpty()) return
        presets = presets.toMutableList().also { it[index] = it[index].copy(name = trimmed) }
        prefs.presets = presets
    }

    fun deletePreset(index: Int) {
        if (index !in presets.indices) return
        presets = presets.toMutableList().also { it.removeAt(index) }
        prefs.presets = presets
    }

    private fun showStatus(text: String) {
        statusMessage = text
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(5000)
            statusMessage = null
        }
    }

    // MARK: - Подключение

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
                tickShownStats()
                tickTabletBattery()
                delay(500)
            }
        }
        // без кабеля — ищем камеру в сети
        scope.launch {
            while (isActive) {
                delay(1000)
                wifiTick()
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
                    if (deviceFrom(intent)?.deviceName == device?.deviceName) {
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
        // кабель важнее сети: если шёл сеанс по Wi-Fi, закрываем его
        if (transportKind == TransportKind.wifi) {
            teardown()
            device = camera
        }
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
        val transport = try {
            UsbPtpTransport(connection, usbInterface)
        } catch (e: Exception) {
            connection.close()
            retryLater("Не удалось открыть камеру: ${e.message}")
            return
        }
        runSession(transport, TransportKind.usb,
            onFailure = { e -> retryLater("Не удалось подключиться к камере: ${e.message}") },
            onEnded = { if (device != null) retryLater("Связь с камерой оборвалась") })
    }

    /** Сеанс одинаков для USB и Wi-Fi: различаются транспорт и что делать при обрыве. */
    private fun runSession(transport: PtpTransport, kind: TransportKind, onFailure: (Exception) -> Unit, onEnded: () -> Unit) {
        phase = Phase.connecting
        transportKind = kind
        val session = CameraSession(transport)
        this.transport = transport
        this.session = session
        coalescer = WriteCoalescer(scope) { code, value ->
            runCatching { session.set(code, value) }
            // ждём только последнее запрошенное значение; промежуточные пропускаем
            if (targets[code] == value) clearTarget(code)
        }
        jobs += scope.launch {
            try {
                session.start(this)
                deviceInfo = session.deviceInfo
                phase = Phase.settling
                startStreams(session, onEnded)
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
                onFailure(e)
            }
        }
    }

    // MARK: - Wi-Fi

    /** Начать сопряжение: программа ищет камеру и стучится, пока на камере включено Pairing. */
    fun startPairing() {
        isPairing = true
        wifiStatus = "Ищу камеру в сети…"
    }

    fun cancelPairing() {
        isPairing = false
        wifiStatus = null
    }

    /** Забыть камеру: программа перестанет подключаться к ней сама (сопряжение в камере остаётся). */
    fun forgetWiFiCamera() {
        prefs.wifiHost = null
        prefs.wifiCameraName = null
        if (transportKind == TransportKind.wifi) teardown()
    }

    /** Раз в секунду: если камеры на кабеле нет и связи нет — ищем камеру в сети и подключаемся. */
    private suspend fun wifiTick() {
        if (!(prefs.wifiEnabled || isPairing) || phase != Phase.searching || device != null || wifiBusy) return
        if (usbManager.deviceList.values.any { it.vendorId == Sony.VENDOR_ID }) return
        wifiBusy = true
        try {
            // сначала — последний адрес камеры: так подключение после перерыва занимает секунду, а не поиск
            val last = if (isPairing) null else prefs.wifiHost
            if (last != null && tryWiFi(last)) return
            if (isPairing && wifiStatus == null) wifiStatus = "Ищу камеру в сети…"
            val found = withContext(Dispatchers.IO) { WiFiDiscovery.search() }
            for (response in found) {
                if (response.host == last) continue
                if (phase != Phase.searching || device != null) return
                if (tryWiFi(response.host)) return
            }
            if (found.isEmpty() && isPairing) wifiStatus = "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi"
        } finally {
            wifiBusy = false
        }
    }

    /** Одна попытка подключиться к камере по адресу. true — сеанс начат. */
    private suspend fun tryWiFi(host: String): Boolean {
        if (isPairing) wifiStatus = "Камера найдена ($host). Включите на камере PC Remote Function → Pairing и подтвердите сопряжение"
        return try {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            val pairing = isPairing
            val (transport, name) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(host, identity, initTimeoutMs = if (pairing) 60_000 else 5000)
            }
            if (phase != Phase.searching || device != null) {
                transport.close()
                return false
            }
            prefs.wifiHost = host
            wifiDirect = PtpIp.isCameraAccessPoint(host)
            prefs.wifiCameraName = name
            wifiFailures = 0
            isPairing = false
            wifiStatus = null
            message = null
            runSession(transport, TransportKind.wifi, onFailure = { wifiLost() }, onEnded = { wifiLost() })
            true
        } catch (e: PtpIpRejected) {
            wifiStatus = if (isPairing) "Камера найдена ($host). Включите на камере PC Remote Function → Pairing"
            else "В сети есть камера ($host), но планшет с ней не сопряжён — нажмите «Сопрячь с камерой»"
            false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // камера в сети, но не отвечает: обычно она ещё держит прошлое, оборванное подключение
            wifiFailures++
            if (wifiFailures >= 3 && !isPairing) {
                wifiStatus = "Камера найдена ($host), но пока не отвечает — видимо, держит прошлое подключение. " +
                    "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
            }
            false
        }
    }

    private fun wifiLost() {
        teardown()
        message = "Связь по Wi-Fi пропала. Ищу камеру…"
    }

    /** Повтор через 2, 4, 8, 10, 10… с, пока камера подключена. */
    private fun retryLater(text: String) {
        teardown()
        failures++
        val seconds = minOf(1L shl failures, 10L)
        message = "$text. Повтор через $seconds с…"
        retryAt = System.currentTimeMillis() + seconds * 1000
    }

    private fun startStreams(session: CameraSession, onEnded: () -> Unit) {
        jobs += scope.launch {
            session.properties.collect { apply(it) }
        }
        jobs += scope.launch {
            session.ended.await()
            onEnded()
        }
        // следующий кадр запрашиваем, пока текущий декодируется: по Wi-Fi запрос идёт 25–35 мс,
        // и подряд с декодированием выходило 21 кадр/с вместо 25. Не успеваем показать — показываем свежий.
        val frames = Channel<ByteArray>(Channel.CONFLATED)
        jobs += scope.launch(Dispatchers.IO) {
            var errors = 0
            var lastArrival = 0L
            try {
                while (isActive) {
                    try {
                        val t0 = System.nanoTime()
                        val jpeg = session.nextLiveViewFrame()
                        val t1 = System.nanoTime()
                        errors = 0
                        if (lastArrival != 0L && t1 - lastArrival > 100_000_000L) {
                            Log.i("ZVFrames", "пауза %.0f мс: запрос %.0f мс (ожиданий %d)".format(
                                (t1 - lastArrival) / 1e6, (t1 - t0) / 1e6, session.lastLiveViewWaits))
                        }
                        lastArrival = t1
                        frames.send(jpeg)
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
            } finally {
                frames.close()
            }
        }
        jobs += scope.launch(Dispatchers.Default) {
            for (jpeg in frames) {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                val bins = if (histogramOn) histogramOf(bitmap) else null
                withContext(Dispatchers.Main) { show(bitmap.asImageBitmap(), jpeg, bins) }
            }
        }
    }

    private fun apply(snapshot: Map<Int, SonyProperty>) {
        properties = snapshot
        val recording = snapshot[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue == 1L
        if (recording && recordingStartedAt == null) recordingStartedAt = System.currentTimeMillis()
        if (!recording) recordingStartedAt = null
        for ((code, target) in targets.toMap()) {
            if (snapshot[code]?.current?.intValue == target) clearTarget(code)
        }
    }

    private fun clearTarget(code: Int) {
        targets.remove(code)
        SettingID.entries.firstOrNull { it.code == code }?.let { pending = pending - it }
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

    private fun show(bitmap: ImageBitmap, jpeg: ByteArray, bins: IntArray?) {
        measure(jpeg)
        frame = bitmap
        lastJpeg = jpeg
        if (bins != null) histogram = bins
        val now = System.currentTimeMillis()
        if (lastFrameAt != 0L) frameGaps.addLast(now to now - lastFrameAt)
        lastFrameAt = now
        frameTimes.addLast(now)
        tickFrameStats()
    }

    /** Числа для строки состояния и подсказки о кадрах; пересчитываются дважды в секунду. */
    private fun tickShownStats() {
        val now = System.currentTimeMillis()
        while (frameGaps.isNotEmpty() && now - frameGaps.first().first > 10_000) frameGaps.removeFirst()
        shownFps = fps.roundToInt()
        // кадры перестали приходить — пауза растёт и сейчас
        val open = if (lastFrameAt != 0L && frame != null) now - lastFrameAt else 0L
        frameGapMs = maxOf(frameGaps.maxOfOrNull { it.second } ?: 0L, open).toInt()
    }

    data class ConnectionDetails(val cameraHost: String?, val localHost: String?, val signalDbm: Int?)

    /** Для подсказки о подключении: адреса камеры и планшета, сигнал Wi-Fi планшета. */
    fun connectionDetails(): ConnectionDetails {
        val wifi = transport as? PtpIpTransport
        val signal = runCatching {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
            caps?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.signalStrength
                ?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        }.getOrNull()
        return ConnectionDetails(wifi?.host, wifi?.localHost, signal)
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
        transportKind = null
        wifiDirect = false
        session = null
        transport = null
        coalescer = null
        targets.clear()
        pending = emptySet()
        device = null
        deviceInfo = null
        properties = emptyMap()
        frame = null
        lastJpeg = null
        histogram = null
        frameTimes.clear()
        frameGaps.clear()
        lastFrameAt = 0L
        fps = 0.0
        shownFps = 0
        frameGapMs = 0
        isFrameStale = false
        recordingStartedAt = null
        isApplyingPreset = false
        isCapturing = false
        dismissReview()
        phase = Phase.searching
    }

    companion object {
        private const val TAG = "ZVController"
        private const val ACTION_USB_PERMISSION = "local.zve10viewer.USB_PERMISSION"

        /** Гистограмма по уменьшенной копии кадра (128×72 достаточно для графика). */
        private fun histogramOf(bitmap: Bitmap): IntArray {
            val small = Bitmap.createScaledBitmap(bitmap, 128, 72, false)
            val pixels = IntArray(128 * 72)
            small.getPixels(pixels, 0, 128, 0, 0, 128, 72)
            return LumaHistogram.bins(pixels)
        }
    }
}
