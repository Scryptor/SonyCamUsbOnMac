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
import local.zve10viewer.settings.ShootingMode
import local.zve10viewer.settings.SlowQuick
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
import local.zve10viewer.wifi.WiFiPolicy
import java.util.concurrent.atomic.AtomicReference
import java.net.Socket
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
    private var identity: PtpIpIdentity? = null
    private val wifiPolicy = WiFiPolicy()
    /** Сокет идущей попытки по Wi-Fi — чтобы оборвать её при отмене сопряжения, «Забыть» или выходе. */
    @Volatile private var connectingSocket: Socket? = null
    private var wifiBusy = false
    private var session: CameraSession? = null
    private var coalescer: WriteCoalescer? = null
    private val targets = HashMap<Int, Long>()
    private val jobs = mutableListOf<Job>()
    private var reviewJob: Job? = null
    private var temperatureJob: Job? = null
    private var recordingModeJob: Job? = null
    /** Последний запрошенный режим записи — от него считаются быстрые шаги. */
    private var recordingModeTarget: RecordingMode? = null
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

    /**
     * Значения для ленты. У параметров записи — режимы обоих форматов (значение — `RecordingMode.code`);
     * камера без свойства формата — её собственные параметры, как раньше.
     */
    fun stripOptions(id: SettingID): List<SettingOption> {
        if (id == SettingID.movieRecordingSetting) {
            val modes = recordingModes
            if (modes.isNotEmpty()) return modes.map { SettingOption(it.code, it.label) }
        }
        return property(id)?.let { SettingOptions.options(id, it) } ?: emptyList()
    }

    /** Текущее значение в ленте — в той же кодировке, что `stripOptions`. */
    fun stripCurrent(id: SettingID): Long? {
        if (id != SettingID.movieRecordingSetting) return property(id)?.current?.intValue
        return currentRecordingMode?.code ?: property(id)?.current?.intValue
    }

    private val currentRecordingMode: RecordingMode?
        get() {
            val format = property(SettingID.movieFileFormat)?.current?.intValue ?: return null
            val setting = property(SettingID.movieRecordingSetting)?.current?.intValue ?: return null
            return RecordingMode(format, setting)
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
        val plan = RecordingModes.plan(mode, property(SettingID.movieFileFormat)?.current?.intValue,
            targets[SettingID.movieFileFormat.code])
        when (plan) {
            RecordingModes.Plan.settingOnly -> {
                if (!isWritable(SettingID.movieRecordingSetting)) return
                recordingModeTarget = mode
                request(SettingID.movieRecordingSetting, mode.setting)
                return
            }
            RecordingModes.Plan.formatThenSetting -> {
                if (!isWritable(SettingID.movieFileFormat)) return
                request(SettingID.movieFileFormat, mode.format)
            }
            RecordingModes.Plan.waitForFormat -> Unit
        }
        recordingModeTarget = mode
        pending = pending + SettingID.movieRecordingSetting
        recordingModeJob = scope.launch {
            repeat(50) {
                delay(100)
                val setting = property(SettingID.movieRecordingSetting)
                // формат подтверждён, и ни одна запись формата не в пути (иначе параметры уйдут раньше неё)
                val ready = targets[SettingID.movieFileFormat.code] == null &&
                    property(SettingID.movieFileFormat)?.current?.intValue == mode.format && setting != null &&
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
    fun setColorTemperature(kelvin: Long): Boolean {
        temperatureJob?.cancel()
        // проверяем до переключения режима: иначе режим сменится, а температура не запишется
        // свойства температуры может не быть, пока камера не в «Цвет. темп.», — тогда проверяем по обычному диапазону
        if (!WhiteBalanceTemperature.accepts(kelvin, property(SettingID.colorTemperature)?.range)) return false
        val previous = property(SettingID.whiteBalance)?.current?.intValue
        if (previous == WhiteBalanceTemperature.MANUAL_MODE) {
            request(SettingID.colorTemperature, kelvin)
            return true
        }
        if (!isWritable(SettingID.whiteBalance) ||
            property(SettingID.whiteBalance)?.settableValues?.contains(WhiteBalanceTemperature.MANUAL_MODE) != true) return false
        request(SettingID.whiteBalance, WhiteBalanceTemperature.MANUAL_MODE)
        temperatureJob = scope.launch {
            repeat(40) {
                delay(100)
                if (isWritable(SettingID.colorTemperature)) {
                    if (property(SettingID.colorTemperature)?.current?.intValue != kelvin) request(SettingID.colorTemperature, kelvin)
                    return@launch
                }
            }
            // температура так и не стала доступной — возвращаем прежний режим баланса белого
            previous?.let { request(SettingID.whiteBalance, it) }
        }
        return true
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
        val modes = recordingModes
        if (id == SettingID.movieRecordingSetting && modes.isNotEmpty()) {
            // от последнего запроса, пока его запись в пути: иначе быстрые шаги сливаются в один;
            // запись закончилась (подтверждена или отклонена) — от того, что на камере
            val current = currentRecordingMode
            val inFlight = SettingID.movieRecordingSetting in pending || SettingID.movieFileFormat in pending
            val base = if (inFlight) recordingModeTarget ?: current else current
            val next = RecordingModes.neighbour(base, modes, offset)
                ?: if (base != current) RecordingModes.neighbour(current, modes, offset) else null
            next?.let { setRecordingMode(it) }
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
    /** Фото, видео или S&Q — по режиму съёмки камеры. */
    val shootingMode: ShootingMode
        get() = property(SettingID.exposureProgram)?.current?.intValue?.let(ShootingMode::of) ?: ShootingMode.video

    fun switchMode(target: ShootingMode) {
        val p = property(SettingID.exposureProgram) ?: return
        val current = p.current.intValue ?: return
        val value = ShootingMode.counterpart(current, target, p.settableValues) ?: return
        request(SettingID.exposureProgram, value)
    }

    /** В режиме S&Q: частота съёмки (если её видно по выдержке) и частота ролика; не S&Q — null. */
    data class SlowMotion(val capture: Int?, val record: Int?)

    val slowMotion: SlowMotion?
        get() {
            if (shootingMode != ShootingMode.slowQuick) return null
            val setting = property(SettingID.movieRecordingSetting)?.current?.intValue
            val pal = setting?.let(RecordingModes::isPalSetting) ?: true
            val capture = property(SettingID.shutter)?.let { SlowQuick.captureFrameRate(it.settableValues, pal) }
            return SlowMotion(capture, setting?.let(RecordingModes::frameRate))
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
                // по Wi-Fi идёт сеанс — кабель не трогаем: камера в режиме Wi-Fi на кабеле (например, на зарядке) видна
                // как накопитель и PC Remote по USB не даст; кабельную камеру подхватим, когда Wi-Fi закончится
                if (device == null && transportKind == null && System.currentTimeMillis() >= retryAt) findCamera()
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
        closeConnectingSocket()
        teardown()
    }

    /** Камера Sony на кабеле: свой производитель и интерфейс фотокамеры (у геймпада Sony его нет). */
    private fun isSonyCamera(device: UsbDevice): Boolean = device.vendorId == Sony.VENDOR_ID &&
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

    private fun findCamera() {
        val camera = usbManager.deviceList.values.firstOrNull(::isSonyCamera) ?: return
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
                    // отключили не ту камеру, с которой работаем (или связь идёт по Wi-Fi) — ничего не трогаем
                    val detached = deviceFrom(intent) ?: return
                    if (device != null && detached.deviceName == device?.deviceName) {
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
        // сеанс начался — сопряжение, если его не завершили, больше не нужно
        isPairing = false
        wifiStatus = null
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
                if (kind == TransportKind.wifi) wifiPolicy.sessionReady()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionException.NotPcRemote) {
                // по Wi-Fi это обрыв: повтор с паузой, а не экран «не в PC Remote» поверх открытого соединения
                if (kind == TransportKind.wifi) {
                    onFailure(e)
                    return@launch
                }
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
        // идущая попытка ждёт подтверждения на камере до минуты — обрываем её
        closeConnectingSocket()
    }

    /**
     * Забыть камеру: новый GUID, и сопряжение в камере к программе больше не относится — сама она подключаться
     * не будет, пока не сопрячь заново.
     */
    fun forgetWiFiCamera() {
        prefs.wifiHost = null
        prefs.wifiCameraName = null
        identity = PtpIpIdentity.reset(context)
        wifiPolicy.connected()
        wifiPolicy.sessionReady()
        wifiStatus = null
        closeConnectingSocket()
        if (transportKind == TransportKind.wifi) teardown()
    }

    private fun closeConnectingSocket() {
        connectingSocket?.let { runCatching { it.close() } }
    }

    /** Нужна ли ещё связь по Wi-Fi: сеанса нет (камера на кабеле не в PC Remote — тоже), Wi-Fi включён или идёт сопряжение. */
    private val wifiWanted: Boolean
        get() {
            // камера на кабеле подключается или ждёт повтора — кабель важнее сети; доступ к ней запретили
            // или она не в PC Remote по USB (например, на зарядке в режиме Wi-Fi) — можно по сети
            val cableWaiting = retryAt != Long.MAX_VALUE && usbManager.deviceList.values.any(::isSonyCamera)
            val free = (phase == Phase.searching && transportKind == null && device == null && !cableWaiting) ||
                (phase == Phase.notPcRemote && transportKind == TransportKind.usb)
            return free && (prefs.wifiEnabled || isPairing)
        }

    /** Чем кончилась попытка подключиться по Wi-Fi. */
    private enum class WiFiAttempt {
        connected,
        /** камера не знает программу (не сопряжена) */
        rejected,
        /** не ответила или оборвала */
        failed,
        /** пока ждали, связь стала не нужна или попытку отменили */
        dropped,
    }

    /** Раз в секунду: если связи нет — ищем камеру в сети и подключаемся. */
    private suspend fun wifiTick() {
        if (!wifiWanted || wifiBusy || !wifiPolicy.mayAttempt(prefs.wifiEnabled, isPairing, System.currentTimeMillis())) return
        wifiBusy = true
        try {
            // сначала — последний адрес камеры: так подключение после перерыва занимает секунду, а не поиск
            val last = if (isPairing || prefs.wifiCameraName == null) null else prefs.wifiHost
            var lastFailed = false
            if (last != null) {
                when (tryWiFi(last, discovered = false)) {
                    WiFiAttempt.connected, WiFiAttempt.dropped -> return
                    WiFiAttempt.rejected -> Unit
                    WiFiAttempt.failed -> lastFailed = true
                }
            }
            if (!wifiWanted) return
            if (isPairing && wifiStatus == null) wifiStatus = "Ищу камеру в сети…"
            val found = withContext(Dispatchers.IO) { WiFiDiscovery.search() }.map { it.host }
            if (!wifiWanted) return
            if (found.isEmpty()) {
                wifiPolicy.nothingFound()
                wifiStatus = if (isPairing) "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi" else null
                return
            }
            // сопряжение могло только что оказаться недействительным (камера отказала) — смотрим заново
            val paired = prefs.wifiCameraName != null
            // не сопряжены — не стучимся (камера в режиме Pairing показала бы чужой запрос), только подсказываем
            if (!wifiPolicy.maySendInit(paired, isPairing)) {
                if (wifiStatus == null) {
                    wifiStatus = "В сети есть камера (${found[0]}) — нажмите «Сопрячь с камерой», чтобы подключаться по Wi-Fi"
                }
                return
            }
            // камера видна по последнему адресу, но не ответила — это неудача к найденной камере
            if (last != null && lastFailed && last in found) {
                wifiPolicy.attemptFailed(discovered = true)
                showStaleHintIfNeeded(last)
            }
            for (host in found) {
                if (host == last) continue
                if (!wifiWanted) return
                when (tryWiFi(host, discovered = true)) {
                    WiFiAttempt.connected, WiFiAttempt.dropped -> return
                    WiFiAttempt.rejected, WiFiAttempt.failed -> Unit
                }
            }
        } finally {
            wifiBusy = false
        }
    }

    private fun showStaleHintIfNeeded(host: String) {
        if (!wifiPolicy.showsStaleHint || isPairing) return
        wifiStatus = "Камера найдена ($host), но пока не отвечает — видимо, держит прошлое подключение. " +
            "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
    }

    /** Одна попытка подключиться к камере по адресу. discovered — адрес найден поиском сейчас, а не взят из памяти. */
    private suspend fun tryWiFi(host: String, discovered: Boolean): WiFiAttempt {
        val pairing = isPairing
        if (pairing) wifiStatus = "Камера найдена ($host). Включите на камере PC Remote Function → Pairing и подтвердите сопряжение"
        val me = identity ?: PtpIpIdentity.load(context).also { identity = it }
        // если корутину отменят, пока ждём камеру, готовое соединение иначе потерялось бы незакрытым
        val created = AtomicReference<PtpIpTransport?>()
        return try {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            val (transport, name) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(host, me, initTimeoutMs = if (pairing) 60_000 else 5000, onSocket = { connectingSocket = it })
                    .also { created.set(it.first) }
            }
            created.set(null)
            // камера приняла программу — значит, сопряжены, даже если связь уже не нужна
            prefs.wifiHost = host
            prefs.wifiCameraName = name
            // пока ждали, связь стала не нужна: подключили кабель, выключили Wi-Fi, отменили сопряжение
            if (!wifiWanted || pairing != isPairing) {
                transport.close()
                return WiFiAttempt.dropped
            }
            // камера на кабеле не в PC Remote — та попытка не нужна, закрываем её до сеанса по Wi-Fi
            if (phase == Phase.notPcRemote) teardown()
            wifiDirect = PtpIp.isCameraAccessPoint(host)
            wifiPolicy.connected()
            message = null
            runSession(transport, TransportKind.wifi, onFailure = { wifiLost() }, onEnded = { wifiLost() })
            WiFiAttempt.connected
        } catch (e: PtpIpRejected) {
            if (!wifiWanted) return WiFiAttempt.dropped
            if (pairing) {
                wifiStatus = "Камера найдена ($host). Включите на камере PC Remote Function → Pairing"
            } else {
                // камера нас больше не знает (её сбросили или сопрягали заново) — перестаём стучаться
                prefs.wifiCameraName = null
                wifiStatus = "В сети есть камера ($host), но планшет с ней не сопряжён — нажмите «Сопрячь с камерой»"
            }
            WiFiAttempt.rejected
        } catch (e: CancellationException) {
            created.getAndSet(null)?.close()
            throw e
        } catch (e: Exception) {
            // отменили или стало не нужно — это не неудача
            if (!wifiWanted || pairing != isPairing) return WiFiAttempt.dropped
            wifiPolicy.attemptFailed(discovered)
            showStaleHintIfNeeded(host)
            WiFiAttempt.failed
        } finally {
            connectingSocket = null
        }
    }

    /** Сеанс по Wi-Fi оборвался: повтор с растущей паузой; камеру на кабеле, если она есть, подхватит опрос USB. */
    private fun wifiLost() {
        teardown()
        val seconds = wifiPolicy.sessionLost(System.currentTimeMillis())
        message = "Связь по Wi-Fi пропала. Повтор через $seconds с…"
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
        recordingModeJob?.cancel()
        recordingModeTarget = null
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
