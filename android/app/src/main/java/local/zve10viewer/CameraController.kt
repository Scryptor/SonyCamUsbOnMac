package local.zve10viewer

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpRefused
import local.zve10viewer.ptp.PtpIpRejected
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.LiveViewQualityPolicy
import local.zve10viewer.session.LiveViewSample
import local.zve10viewer.session.SessionException
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.LumaStats
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.RecordingMode
import local.zve10viewer.settings.RecordingModeDriver
import local.zve10viewer.settings.RecordingModes
import local.zve10viewer.settings.RecordingStep
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
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.usb.UsbPtpTransport
import local.zve10viewer.wifi.LinkLoss
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
    /** Что показать поверх видео, когда кадры не идут. */
    enum class FrameNotice {
        none,
        /** Кадров нет 1,2–4 с по Wi-Fi: плашка «Слабый сигнал Wi-Fi», кадр не тускнеет. */
        weakSignal,
        /** Провал связи или кадров нет 4 с: кадр приглушён, «Связь с камерой прервалась · 0:07». */
        interrupted,
        /** Сеанс оборвался и восстанавливается: «Переподключаюсь к камере…». */
        reconnecting,
    }
    var frameNotice by mutableStateOf(FrameNotice.none)
        private set
    /** С какого момента не идут кадры: для «· 0:07» и совета через 10 с. */
    var noticeSince by mutableStateOf(0L)
        private set
    /** Гистограмма яркости последнего кадра (64 столбца), пока она включена. */
    var histogram by mutableStateOf<IntArray?>(null)
        private set
    /** Доли теней, полутонов и светов, провалы, пересвет и средняя яркость по тому же кадру. */
    var histogramStats by mutableStateOf<LumaStats?>(null)
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
    /** Провал связи по Wi-Fi: ждём ответа, а ничего не приходит. Сеанс держим, кадр остаётся на экране. */
    var linkOutage by mutableStateOf(false)
        private set
    /** Сеанс по Wi-Fi оборвался и тихо восстанавливается: кадр, свойства и таймер записи на месте, кнопки неактивны. */
    var isReconnecting by mutableStateOf(false)
        private set
    /** Каким качеством идут кадры (по их ширине); null — кадров нет. */
    var liveViewQuality by mutableStateOf<LiveViewQuality?>(null)
        private set
    /** Снижать качество превью при слабом Wi-Fi (настройка). */
    var adaptiveQuality by mutableStateOf(prefs.adaptiveLiveViewQuality)
        private set

    /** Отладка (только debug-сборка): прокси стенда вместо камеры — только этот адрес, без памяти и поиска. */
    var debugHost: String? = null
        private set
    /** Отладка: связь во время провала проверять HTTP-запросом сюда вместо ping (ping ответил бы сам прокси). */
    var debugLinkCheckUrl: String? = null
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
    /** Ведёт камеру к выбранному режиму записи; от его цели считаются быстрые шаги колесом. */
    private val recordingDriver = RecordingModeDriver()
    private var statusJob: Job? = null
    private val frameTimes = ArrayDeque<Long>()
    private var lastFrameAt = 0L
    /** Паузы между кадрами за последние 10 с: (когда пришёл кадр, пауза перед ним). */
    private val frameGaps = ArrayDeque<Pair<Long, Long>>()
    private var failures = 0
    private var retryAt = 0L
    private var batteryCheckedAt = 0L

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifiManager = context.getSystemService(WifiManager::class.java)
    private var wifiLock: WifiManager.WifiLock? = null
    /** Сети Wi-Fi, которые сейчас есть; последняя появившаяся — та, через которую идём к камере. */
    private val wifiNetworks = LinkedHashSet<Network>()
    private val wifiNetwork: Network? get() = wifiNetworks.lastOrNull()
    /** Будит поиск по Wi-Fi раньше секунды: программа снова на экране, появилась сеть. */
    private val wifiWake = Channel<Unit>(Channel.CONFLATED)
    /** Программа на экране (между onStart и onStop). */
    private var foreground = false
    private var backgroundJob: Job? = null
    /** Live view идёт, только пока программа на экране: в фоне кадры не запрашиваем, сеанс держат пробы. */
    private val liveViewWanted = MutableStateFlow(true)
    /** С какого момента снова ждём кадров (после фона или снимка): паузу до этого не показываем как обрыв. */
    private var quietFrom = 0L

    /** Тихое восстановление оборвавшегося сеанса по Wi-Fi. */
    private class Reconnect(val host: String, val cause: LinkLoss, val startedAt: Long) {
        var attempts = 0
    }
    private var reconnect: Reconnect? = null
    private var reconnectJob: Job? = null
    /** Будит паузу между попытками восстановления: появилась сеть Wi-Fi. */
    private val reconnectWake = Channel<Unit>(Channel.CONFLATED)

    private var qualityPolicy = LiveViewQualityPolicy(now = System.currentTimeMillis())
    /** Запись качества превью: одна за раз. */
    private var qualityJob: Job? = null
    /** Кадров в этом сеансе: оборвался сеанс, который уже показывал картинку, — восстанавливаем тихо. */
    private var sessionFrames = 0
    /** Последний кадр: JPEG прямо в ответе камеры (для стоп-кадра) и его Bitmap из пула (для копий). */
    private var lastSample: LiveViewSample? = null
    private var frameBitmap: Bitmap? = null
    /** Время ответа камеры на пробы, мс — для журнала. */
    private var linkRttMs: Long? = null
    private var summaryAt = 0L
    private var summaryFrames = 0
    private var summaryBytes = 0L

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
                property(SettingID.movieRecordingSetting)?.current?.intValue, allowedRecordingSettings)
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

    /** Параметры записи, которые камера разрешает для текущего формата. */
    private val allowedRecordingSettings: List<Long>
        get() = property(SettingID.movieRecordingSetting)
            ?.let { setting -> SettingOptions.options(SettingID.movieRecordingSetting, setting).map { it.value } } ?: emptyList()

    /** Выбор в ленте: обычная настройка — запись значения, режим записи — формат и параметры вместе. */
    fun choose(id: SettingID, value: Long) {
        if (id == SettingID.movieRecordingSetting && value > 0xFF) return setRecordingMode(RecordingMode.of(value))
        if (id == SettingID.movieFileFormat) {
            // один формат без параметров камера оставила бы с параметрами прежнего — берём ближайший режим
            val mode = RecordingModes.closest(recordingDriver.target ?: currentRecordingMode, value, recordingModes)
            if (mode != null) return setRecordingMode(mode)
        }
        request(id, value)
    }

    /**
     * Формат и параметры записи. Камера отвечает «готово», но пропускает запись, пришедшую, пока она применяет прошлую,
     * поэтому режим доводится по шагам: по одной записи, после каждой — смотрим, что на камере. Новый выбор, пока идёт
     * прошлый, подхватывается на следующем шаге.
     */
    fun setRecordingMode(mode: RecordingMode) {
        if (!isWritable(SettingID.movieRecordingSetting) && !isWritable(SettingID.movieFileFormat)) return
        recordingDriver.choose(mode)
        pending = pending + SettingID.movieRecordingSetting
        if (recordingModeJob?.isActive == true) return
        recordingModeJob = scope.launch { driveRecordingMode() }
    }

    private suspend fun driveRecordingMode() {
        try {
            loop@ while (true) {
                // прошлая запись ещё ждёт подтверждения камеры
                if (targets[SettingID.movieFileFormat.code] != null || targets[SettingID.movieRecordingSetting.code] != null) {
                    delay(100)
                    continue@loop
                }
                val step = recordingDriver.next(property(SettingID.movieFileFormat)?.current?.intValue,
                    property(SettingID.movieRecordingSetting)?.current?.intValue, allowedRecordingSettings)
                Log.d(TAG, "режим записи: формат ${property(SettingID.movieFileFormat)?.current?.intValue}, " +
                    "параметры ${property(SettingID.movieRecordingSetting)?.current?.intValue?.toString(16)}, " +
                    "можно ${allowedRecordingSettings.map { it.toString(16) }}, " +
                    "пишется ${isWritable(SettingID.movieRecordingSetting)} → $step")
                step ?: return
                when (step) {
                    RecordingStep.Done -> return
                    RecordingStep.Wait -> delay(100)
                    is RecordingStep.WriteFormat -> {
                        if (!isWritable(SettingID.movieFileFormat)) {
                            delay(300)
                            continue@loop
                        }
                        request(SettingID.movieFileFormat, step.format)
                        waitForWrite(SettingID.movieFileFormat.code)
                        // формат камера подтверждает раньше, чем готова принять следующую запись
                        delay(500)
                    }
                    is RecordingStep.WriteSetting -> {
                        if (!isWritable(SettingID.movieRecordingSetting)) {
                            delay(300)
                            continue@loop
                        }
                        request(SettingID.movieRecordingSetting, step.setting)
                        waitForWrite(SettingID.movieRecordingSetting.code)
                    }
                }
                // запись параметров снимает «ждём» — пока режим не доведён, он ещё в пути
                if (recordingDriver.target != null) pending = pending + SettingID.movieRecordingSetting
            }
        } finally {
            // отменили при отключении — teardown уже всё сбросил (и, может быть, начат новый сеанс)
            if (currentCoroutineContext().isActive) {
                recordingDriver.cancel()
                if (targets[SettingID.movieRecordingSetting.code] == null) pending = pending - SettingID.movieRecordingSetting
            }
        }
    }

    /** Ждёт, пока запись свойства закончится: камера подтвердила её или время подтверждения вышло. */
    private suspend fun waitForWrite(code: Int) {
        repeat(60) {
            if (targets[code] == null) return
            delay(100)
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
            // от режима, к которому камера ещё идёт: иначе быстрые шаги сливаются в один; дошла или сдалась —
            // от того, что на камере
            val current = currentRecordingMode
            val base = recordingDriver.target ?: current
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
        if (!histogramOn) {
            histogram = null
            histogramStats = null
        }
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
        // копия: кадр на экране — из пула, его перезапишут следующие кадры
        val cameraFrame = frameCopy()
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
        val sample = lastSample ?: return
        val thumbnail = frameCopy()
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { sample.jpeg() }
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
        // сеть Wi-Fi: к ней привязываем сокеты; пропала — провал связи сразу, появилась — пробуем сразу.
        // Без INTERNET: сеть камеры в режиме Wi-Fi Direct интернета не даёт
        runCatching {
            val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivity.registerNetworkCallback(request, networkCallback, Handler(Looper.getMainLooper()))
        }.onFailure { Log.w(LINK, "не слежу за сетью Wi-Fi: ${it.message}") }
        // подключение камеры отслеживаем опросом: широковещательное ATTACHED приходит не всем
        scope.launch {
            while (isActive) {
                // по Wi-Fi идёт сеанс — кабель не трогаем: камера в режиме Wi-Fi на кабеле (например, на зарядке) видна
                // как накопитель и PC Remote по USB не даст; кабельную камеру подхватим, когда Wi-Fi закончится
                if (foreground && device == null && transportKind == null && System.currentTimeMillis() >= retryAt) findCamera()
                tickFrameStats()
                tickShownStats()
                tickTabletBattery()
                tickQuality()
                tickSummary()
                delay(500)
            }
        }
        // плашка и карточка поверх видео: 1,2 с без кадров — уже видно, полсекунды точности мало
        scope.launch {
            while (isActive) {
                tickFrameNotice()
                delay(250)
            }
        }
        // без кабеля — ищем камеру в сети
        scope.launch {
            while (isActive) {
                withTimeoutOrNull(1000) { wifiWake.receive() }
                wifiTick()
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        closeConnectingSocket()
        teardown()
    }

    /** Программа снова на экране: live view продолжается, а закрытый в фоне сеанс открывается заново. */
    fun onForeground() {
        foreground = true
        backgroundJob?.cancel()
        quietFrom = System.currentTimeMillis()
        liveViewWanted.value = true
        if (device == null && transportKind == null && System.currentTimeMillis() >= retryAt) findCamera()
        wifiWake.trySend(Unit)
    }

    /** Программа ушла с экрана: live view на паузе (сеанс держат пробы), через минуту — обычное закрытие. */
    fun onBackground() {
        foreground = false
        liveViewWanted.value = false
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_CLOSE_MS)
            if (session != null || reconnect != null) {
                Log.i(LINK, "минута в фоне — закрываю сеанс, на экране подключусь снова")
                teardown()
                message = null
            }
        }
    }

    /** Отладка (только debug-сборка): прокси стенда между программой и камерой. */
    fun setDebugTarget(host: String?, linkCheckUrl: String?) {
        debugHost = host
        debugLinkCheckUrl = linkCheckUrl
        Log.i(LINK, "отладка: камера — ${host ?: "как обычно"}, проверка связи — ${linkCheckUrl ?: "ping"}")
    }

    // MARK: - Сеть Wi-Fi

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = wifiNetworkAvailable(network)
        override fun onLost(network: Network) = wifiNetworkLost(network)
    }

    /** Появилась сеть Wi-Fi: к ней привязываем новые сокеты; ждём связи — пробуем сразу, не дожидаясь паузы. */
    private fun wifiNetworkAvailable(network: Network) {
        wifiNetworks.remove(network)
        wifiNetworks.add(network)
        val wifi = transport as? PtpIpTransport
        val bound = wifi?.network
        if (wifi != null && bound != null && bound != network && bound !in wifiNetworks) {
            // сокеты привязаны к пропавшей сети — им уже не ожить: сбрасываем, сеанс восстановится по новой
            Log.i(LINK, "сеть Wi-Fi сменилась — переподключаюсь по новой")
            wifi.abort()
            return
        }
        if (reconnect != null) {
            reconnectWake.trySend(Unit)
        } else if (transportKind == null) {
            wifiPolicy.networkAvailable()
            wifiWake.trySend(Unit)
        }
    }

    /** Пропала сеть Wi-Fi, через которую идёт сеанс, — провал связи сразу, не дожидаясь тишины. */
    private fun wifiNetworkLost(network: Network) {
        wifiNetworks.remove(network)
        val wifi = transport as? PtpIpTransport ?: return
        if (wifi.network == network || (wifi.network == null && wifiNetworks.isEmpty())) wifi.networkLost()
    }

    /**
     * Wi-Fi без энергосбережения на время сеанса (WIFI_MODE_FULL_LOW_LATENCY): точка не копит кадры для «дремлющего»
     * планшета. Система держит его, только пока программа на экране и экран включён.
     */
    private fun holdWifiLock(on: Boolean) {
        runCatching {
            if (on) {
                val lock = wifiLock ?: wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "zv:live-view")
                    .apply { setReferenceCounted(false) }.also { wifiLock = it }
                if (!lock.isHeld) lock.acquire()
            } else {
                wifiLock?.takeIf { it.isHeld }?.release()
            }
        }.onFailure { Log.w(LINK, "Wi-Fi lock: ${it.message}") }
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
        sessionFrames = 0
        qualityPolicy = LiveViewQualityPolicy(now = System.currentTimeMillis())
        if (kind == TransportKind.wifi) holdWifiLock(true)
        coalescer = WriteCoalescer(scope) { code, value ->
            val result = runCatching { session.set(code, value) }
            Log.d(TAG, "запись 0x${code.toString(16)} = ${value.toString(16)}: ${result.getOrNull() ?: result.exceptionOrNull()}")
            // ждём только последнее запрошенное значение; промежуточные пропускаем
            if (targets[code] == value) clearTarget(code)
        }
        // об одном обрыве могут сообщить и рукопожатие, и поток свойств, а сообщение — запоздать: слушаем только свой
        // транспорт, иначе поздний вызов убил бы уже восстановленный сеанс
        val failed: (Exception) -> Unit = { e -> if (this.transport === transport) onFailure(e) }
        val ended: () -> Unit = { if (this.transport === transport) onEnded() }
        jobs += scope.launch {
            try {
                session.start(this)
                deviceInfo = session.deviceInfo
                phase = Phase.settling
                startStreams(session, ended)
                session.waitUntilReady()
                phase = Phase.ready
                failures = 0
                if (kind == TransportKind.wifi) {
                    wifiPolicy.sessionReady()
                    // кадров так и нет, а сеанс готов — «переподключаюсь» больше не показываем
                    finishReconnect(System.currentTimeMillis(), firstFrame = false)
                    startQuality(session)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionException.NotPcRemote) {
                // по Wi-Fi это обрыв: повтор с паузой, а не экран «не в PC Remote» поверх открытого соединения
                if (kind == TransportKind.wifi) {
                    failed(e)
                    return@launch
                }
                deviceInfo = session.deviceInfo
                phase = Phase.notPcRemote
            } catch (e: Exception) {
                Log.w(TAG, "подключение не удалось", e)
                failed(e)
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

    /** Раз в секунду (и сразу, когда появилась сеть): если связи нет — ищем камеру в сети и подключаемся. */
    private suspend fun wifiTick() {
        // в фоне не подключаемся: сеанс, закрытый через минуту в фоне, откроется, когда программа снова на экране
        if (!foreground) return
        if (!wifiWanted || wifiBusy || !wifiPolicy.mayAttempt(prefs.wifiEnabled, isPairing, System.currentTimeMillis())) return
        wifiBusy = true
        try {
            // отладка: прокси стенда вместо камеры — только этот адрес, без памяти и поиска
            debugHost?.let { host ->
                if (wifiPolicy.maySendInit(prefs.wifiCameraName != null, isPairing)) {
                    tryWiFi(host, discovered = false, remember = false)
                } else if (wifiStatus == null) {
                    wifiStatus = "Отладка: камера за $host — нажмите «Сопрячь с камерой», чтобы подключаться по Wi-Fi"
                }
                return
            }
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
            val network = wifiNetwork
            val found = withContext(Dispatchers.IO) { WiFiDiscovery.search(network = network) }.map { it.host }
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

    /**
     * Одна попытка подключиться к камере по адресу. discovered — адрес найден поиском сейчас, а не взят из памяти;
     * remember — запомнить адрес (адрес прокси отладки не запоминаем).
     */
    private suspend fun tryWiFi(host: String, discovered: Boolean, remember: Boolean = true): WiFiAttempt {
        val pairing = isPairing
        if (pairing) wifiStatus = "Камера найдена ($host). Включите на камере PC Remote Function → Pairing и подтвердите сопряжение"
        val me = identity ?: PtpIpIdentity.load(context).also { identity = it }
        // если корутину отменят, пока ждём камеру, готовое соединение иначе потерялось бы незакрытым
        val created = AtomicReference<PtpIpTransport?>()
        val network = wifiNetwork
        val linkCheck = debugLinkCheckUrl
        return try {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            val (transport, name) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(host, me, network, initTimeoutMs = if (pairing) 60_000 else 5000, linkCheckUrl = linkCheck,
                    onSocket = { connectingSocket = it }).also { created.set(it.first) }
            }
            created.set(null)
            // камера приняла программу — значит, сопряжены, даже если связь уже не нужна
            if (remember) prefs.wifiHost = host
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
            Log.i(LINK, "подключился к $host по Wi-Fi")
            runSession(transport, TransportKind.wifi, onFailure = { wifiSessionStopped(it) }, onEnded = { wifiSessionStopped(null) })
            WiFiAttempt.connected
        } catch (e: PtpIpRejected) {
            if (!wifiWanted) return WiFiAttempt.dropped
            if (!WiFiPolicy.forgetsPairing(e.reason)) {
                // камера занята (например, ещё держит прошлое подключение) или отказала без причины — это проходит,
                // сопряжение не трогаем
                Log.i(LINK, "камера $host отказала (причина ${e.reason}) — повторю")
                wifiPolicy.attemptFailed(discovered)
                showStaleHintIfNeeded(host)
                return WiFiAttempt.failed
            }
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
            Log.i(LINK, "подключение к $host не удалось: ${e.message}")
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

    // MARK: - Тихое восстановление сеанса по Wi-Fi

    /**
     * Сеанс по Wi-Fi кончился сам: рукопожатие не прошло (error) или транспорт закрылся (обрыв, сброс после провала,
     * камера замолчала). Сеанс уже показывал картинку — восстанавливаем тихо; идёт восстановление — это неудачная
     * попытка; иначе — обычное ожидание.
     */
    private fun wifiSessionStopped(error: Exception?) {
        val wifi = transport as? PtpIpTransport
        val loss = wifi?.link?.value?.loss ?: LinkLoss.broken
        when {
            reconnect != null -> {
                Log.i(LINK, "переподключение: сеанс не поднялся — ${error?.message ?: loss.title}")
                stopSession(abort = true)
                phase = Phase.connecting
                continueReconnect()
            }
            wifi != null && sessionFrames > 0 && loss.reconnects && prefs.wifiEnabled -> beginReconnect(wifi.host, loss)
            else -> {
                Log.i(LINK, "сеанс по Wi-Fi кончился: ${error?.message ?: loss.title} — жду камеру с поиском в сети")
                wifiLost()
            }
        }
    }

    /**
     * Тихое восстановление: кадр, свойства, таймер записи и лента остаются на экране, кнопки неактивны (фаза
     * connecting). Старые соединения — RST (камера освобождает сеанс сразу), попытки — по тому же адресу, без поиска:
     * сразу, затем через 0,5, 1, 2, 3, 5, 5… с, с короткими тайм-аутами.
     */
    private fun beginReconnect(host: String, cause: LinkLoss) {
        stopSession(abort = true)
        phase = Phase.connecting
        isReconnecting = true
        val episode = Reconnect(host, cause, System.currentTimeMillis())
        reconnect = episode
        while (reconnectWake.tryReceive().isSuccess) Unit
        Log.i(LINK, "связь оборвалась (${cause.title}) — переподключаюсь к $host")
        continueReconnect()
    }

    /** Следующая попытка по расписанию; refused — камера отказала в соединении (PC Remote выключили). */
    private fun continueReconnect(refused: Boolean = false) {
        val episode = reconnect ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val pause = if (refused) 0 else WiFiPolicy.reconnectDelayMs(episode.attempts)
            // появилась сеть Wi-Fi — паузу не ждём
            if (pause > 0) withTimeoutOrNull(pause) { reconnectWake.receive() }
            val now = System.currentTimeMillis()
            when {
                !prefs.wifiEnabled -> reconnectFailed(episode, "Wi-Fi выключен в настройках")
                WiFiPolicy.reconnectGivesUp(episode.startedAt, now, refused) -> reconnectFailed(episode,
                    if (refused) "камера не принимает подключение" else "не вышло за ${(now - episode.startedAt) / 1000} с")
                else -> {
                    episode.attempts++
                    attemptReconnect(episode)
                }
            }
        }
    }

    /** Одна попытка восстановления: TCP 2 с, приветствие 3 с; дальше — обычное рукопожатие и успокоение 3 с. */
    private suspend fun attemptReconnect(episode: Reconnect) {
        val me = identity ?: PtpIpIdentity.load(context).also { identity = it }
        val created = AtomicReference<PtpIpTransport?>()
        val network = wifiNetwork
        val linkCheck = debugLinkCheckUrl
        try {
            val (transport, _) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(episode.host, me, network, connectTimeoutMs = 2000, initTimeoutMs = 3000,
                    linkCheckUrl = linkCheck, onSocket = { connectingSocket = it }).also { created.set(it.first) }
            }
            created.set(null)
            // пока ждали, восстановление отменили (выход, «Забыть», Wi-Fi выключили)
            if (reconnect !== episode) {
                transport.close()
                return
            }
            wifiPolicy.connected()
            runSession(transport, TransportKind.wifi, onFailure = { wifiSessionStopped(it) }, onEnded = { wifiSessionStopped(null) })
        } catch (e: CancellationException) {
            created.getAndSet(null)?.close()
            throw e
        } catch (e: PtpIpRefused) {
            // PC Remote выключили — ждать по тому же адресу нечего
            continueReconnect(refused = true)
        } catch (e: PtpIpRejected) {
            if (WiFiPolicy.forgetsPairing(e.reason)) {
                reconnectFailed(episode, "камера больше не знает планшет")
                prefs.wifiCameraName = null
                wifiStatus = "В сети есть камера (${episode.host}), но планшет с ней не сопряжён — нажмите «Сопрячь с камерой»"
            } else {
                Log.i(LINK, "переподключение: камера занята (причина ${e.reason}) — повторю")
                continueReconnect()
            }
        } catch (e: Exception) {
            Log.i(LINK, "переподключение: попытка ${episode.attempts} не удалась — ${e.message}")
            continueReconnect()
        } finally {
            connectingSocket = null
        }
    }

    /** Тихо не вышло — обычное ожидание: экран «Подключите камеру», поиск в сети, паузы 2, 4, 8, 10 с. */
    private fun reconnectFailed(episode: Reconnect, why: String) {
        if (reconnect !== episode) return
        Log.i(LINK, "переподключение бросил: $why, попыток ${episode.attempts}")
        wifiLost()
    }

    /** Сеанс восстановлен: пришёл первый кадр нового сеанса (или он готов, а кадров нет). */
    private fun finishReconnect(now: Long, firstFrame: Boolean) {
        val episode = reconnect ?: return
        reconnect = null
        isReconnecting = false
        Log.i(LINK, "переподключился за %d мс %s: попыток %d, причина — %s".format(now - episode.startedAt,
            if (firstFrame) "до первого кадра" else "до готовности (кадров ещё нет)", episode.attempts, episode.cause.title))
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
        (transport as? PtpIpTransport)?.let { wifi -> jobs += scope.launch { watchLink(wifi) } }
        // следующий кадр запрашиваем, пока текущий декодируется: по Wi-Fi запрос идёт 25–35 мс,
        // и подряд с декодированием выходило 21 кадр/с вместо 25. Не успеваем показать — показываем свежий.
        val frames = Channel<LiveViewSample>(Channel.CONFLATED)
        jobs += scope.launch(Dispatchers.IO) {
            var errors = 0
            var lastArrival = 0L
            try {
                while (isActive) {
                    // в фоне кадры не запрашиваем: сеанс держат пробы слежения за связью
                    if (!liveViewWanted.value) {
                        liveViewWanted.first { it }
                        lastArrival = 0L
                    }
                    try {
                        val t0 = System.nanoTime()
                        val sample = session.nextLiveViewFrame()
                        val t1 = System.nanoTime()
                        errors = 0
                        if (lastArrival != 0L && t1 - lastArrival > 100_000_000L) {
                            Log.i("ZVFrames", "пауза %.0f мс: запрос %.0f мс (ожиданий %d)".format(
                                (t1 - lastArrival) / 1e6, (t1 - t0) / 1e6, sample.waits))
                        }
                        lastArrival = t1
                        frames.send(sample)
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
            // свои Bitmap у каждого сеанса: прошлый, может быть, ещё дорисовывает свой последний кадр
            val pool = FramePool()
            var sampler: HistogramSampler? = null
            for (sample in frames) {
                val bitmap = pool.decode(sample.data, sample.jpegOffset, sample.jpegLength) ?: continue
                val levels = if (histogramOn) (sampler ?: HistogramSampler().also { sampler = it }).levels(bitmap) else null
                withContext(Dispatchers.Main) { show(session, bitmap, sample, levels) }
            }
        }
    }

    /** Связь по Wi-Fi: провал — для экрана, время ответа на пробы — для качества превью и журнала. */
    private suspend fun watchLink(wifi: PtpIpTransport) {
        var samples = 0
        wifi.link.collect { link ->
            linkOutage = link.outage
            val rtt = link.rttMs
            if (rtt != null && link.rttSamples != samples) {
                samples = link.rttSamples
                linkRttMs = rtt
                qualityPolicy.rtt(System.currentTimeMillis(), rtt.toDouble())
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

    private fun measure(sample: LiveViewSample) {
        val now = System.nanoTime()
        if (frameLog.isEmpty()) frameLogStart = now
        frameLog += now to fingerprint(sample)
        if (now - frameLogStart < 3_000_000_000L) return
        val gaps = frameLog.zipWithNext { a, b -> (b.first - a.first) / 1_000_000.0 }.sorted()
        val repeats = frameLog.zipWithNext().count { (a, b) -> a.second == b.second }
        if (gaps.isNotEmpty()) {
            Log.i("ZVFrames", "кадров %d за 3 с, повторов %d, интервал: медиана %.0f мс, 90%% %.0f мс, макс %.0f мс".format(
                frameLog.size, repeats, gaps[gaps.size / 2], gaps[(gaps.size * 9) / 10], gaps.last()))
        }
        frameLog.clear()
    }

    /** Отпечаток кадра для подсчёта повторов: каждый 16-й байт JPEG — повтор не пропустит, а дешевле полного хеша. */
    private fun fingerprint(sample: LiveViewSample): Int {
        var hash = sample.jpegLength
        val end = sample.jpegOffset + sample.jpegLength
        var i = sample.jpegOffset
        while (i < end) {
            hash = hash * 31 + sample.data[i]
            i += 16
        }
        return hash
    }

    private fun show(from: CameraSession, bitmap: Bitmap, sample: LiveViewSample, levels: IntArray?) {
        // кадр прошлого сеанса, дошедший после обрыва, — не тот, что считается «восстановили»
        if (from !== session) return
        measure(sample)
        frameBitmap = bitmap
        // новая обёртка на каждый кадр: Bitmap из пула тот же, и с прежней обёрткой Compose не перерисовал бы картинку
        frame = bitmap.asImageBitmap()
        lastSample = sample
        val now = System.currentTimeMillis()
        noteQuality(now, bitmap, sample)
        sessionFrames++
        summaryFrames++
        summaryBytes += sample.bytes
        if (reconnect != null) finishReconnect(now, firstFrame = true)
        if (levels != null) {
            // в видео белое в live view — 234, в фото — 255: шкала гистограммы — до белого
            val white = LumaStats.whiteLevel(levels, video = mode != CaptureMode.photo)
            histogram = LumaHistogram.binsOf(levels, whiteLevel = white)
            histogramStats = LumaStats.of(levels, white)
        }
        if (lastFrameAt != 0L) frameGaps.addLast(now to now - lastFrameAt)
        lastFrameAt = now
        frameTimes.addLast(now)
        tickFrameStats()
    }

    /** Качество кадра — по его ширине; по Wi-Fi кадр — отсчёт для политики качества. */
    private fun noteQuality(now: Long, bitmap: Bitmap, sample: LiveViewSample) {
        val quality = LiveViewQuality.ofWidth(bitmap.width) ?: return
        if (quality != liveViewQuality) {
            if (liveViewQuality != null) Log.i(LINK, "кадры ${bitmap.width}×${bitmap.height} — качество превью ${quality.title}")
            liveViewQuality = quality
        }
        if (transportKind != TransportKind.wifi) return
        qualityPolicy.frame(now, sample.bytes, sample.fetchMs, quality)
        sample.shortReplyMs?.let { qualityPolicy.rtt(now, it) }
    }

    // MARK: - Качество превью по Wi-Fi

    /** Начало сеанса по Wi-Fi: камера на низком качестве превью — сначала высокое, дальше решает канал. */
    private fun startQuality(session: CameraSession) {
        // свойства — прямо из сеанса: копия у контроллера обновится только на следующем шаге главного потока
        val current = LiveViewQuality.of(session.properties.value[SonyProp.LIVE_VIEW_IMAGE_QUALITY]?.current?.intValue)
            ?: liveViewQuality
        if (current != LiveViewQuality.low) return
        Log.i(LINK, "качество превью: камера на низком — начинаю с высокого")
        qualityPolicy.requested(System.currentTimeMillis(), LiveViewQuality.high)
        writeQuality(session, LiveViewQuality.high)
    }

    /** Дважды в секунду: решение политики (или, если настройка выключена, всегда высокое) — в камеру. По USB не трогаем. */
    private fun tickQuality() {
        val session = session ?: return
        if (transportKind != TransportKind.wifi || phase != Phase.ready || !foreground) return
        // одна запись за раз
        if (qualityJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val policy = qualityPolicy
        val target = if (adaptiveQuality) {
            policy.decide(now, linkOutage)
        } else if (policy.level == LiveViewQuality.low && !policy.isSwitching(now)) {
            LiveViewQuality.high.also { policy.requested(now, it) }
        } else {
            null
        }
        target ?: return
        val fps = policy.predictedHighFps(now, 5000)
        val throughput = policy.throughput(now, 5000)
        Log.i(LINK, "качество превью: %s → %s (прогноз на высоком %s к/с, поток %s КБ/с, RTT %.0f мс, выдержка %d с)".format(
            policy.level?.title ?: "—", target.title, fps?.let { "%.1f".format(it) } ?: "—",
            throughput?.let { "%.0f".format(it * 1000 / 1024) } ?: "—", policy.rttMs(now), policy.holdMs / 1000))
        writeQuality(session, target)
    }

    /**
     * Запись 0xD26A без подтверждения чтением — смену подтверждает ширина кадров. Снижали мы — при обычном закрытии
     * транспорт вернёт камере высокое: иначе и следующий сеанс, и другие программы получили бы низкое.
     */
    private fun writeQuality(session: CameraSession, target: LiveViewQuality) {
        val wifi = transport as? PtpIpTransport
        if (target == LiveViewQuality.low) wifi?.farewell = RESTORE_HIGH_QUALITY
        qualityJob = scope.launch {
            try {
                session.setLiveViewQuality(target)
                if (target == LiveViewQuality.high) wifi?.farewell = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LINK, "качество превью ${target.title} не записалось: ${e.message}")
            }
        }
    }

    fun applyAdaptiveQuality(on: Boolean) {
        adaptiveQuality = on
        prefs.adaptiveLiveViewQuality = on
        // выключили — на ближайшем шаге tickQuality вернёт высокое
    }

    /** Копия кадра на экране: сам он — из пула, следующие кадры его перезапишут. */
    private fun frameCopy(): ImageBitmap? =
        frameBitmap?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false)?.asImageBitmap() }.getOrNull() }

    /**
     * Что показать поверх видео. Кадров нет 1,2 с по Wi-Fi — плашка «Слабый сигнал Wi-Fi»; провал связи или 4 с без
     * кадров — «Связь с камерой прервалась»; восстановление — «Переподключаюсь к камере…».
     */
    private fun tickFrameNotice() {
        val now = System.currentTimeMillis()
        // в фоне и пока качается снимок кадров и не ждём: паузу до этого обрывом не считаем
        if (!foreground || isCapturing) quietFrom = now
        val since = maxOf(lastFrameAt, quietFrom)
        val quiet = now - since
        val live = phase == Phase.settling || phase == Phase.ready
        val notice = when {
            isReconnecting -> FrameNotice.reconnecting
            frame == null || !live || lastFrameAt == 0L -> FrameNotice.none
            linkOutage -> FrameNotice.interrupted
            quiet >= 4000 -> FrameNotice.interrupted
            quiet >= 1200 && transportKind == TransportKind.wifi -> FrameNotice.weakSignal
            else -> FrameNotice.none
        }
        noticeSince = since
        if (notice == frameNotice) return
        // конец переподключения пишет finishReconnect
        if (notice != FrameNotice.none || frameNotice != FrameNotice.reconnecting) {
            val shown = when (notice) {
                FrameNotice.none -> "кадры снова идут"
                FrameNotice.weakSignal -> "«Слабый сигнал Wi-Fi»"
                FrameNotice.interrupted -> "«Связь с камерой прервалась»"
                FrameNotice.reconnecting -> "«Переподключаюсь к камере…»"
            }
            Log.i(LINK, "на экране: $shown, кадров нет %.1f с".format(quiet / 1000.0))
        }
        frameNotice = notice
    }

    /** Раз в 5 с — строка в журнал: частота, кадр, поток, время ответа, качество, связь. */
    private fun tickSummary() {
        val now = System.currentTimeMillis()
        if (summaryAt == 0L) summaryAt = now
        val elapsed = now - summaryAt
        if (elapsed < 5000) return
        val live = session != null && (phase == Phase.settling || phase == Phase.ready)
        if (live || isReconnecting) {
            val seconds = elapsed / 1000.0
            val link = when {
                isReconnecting -> "переподключение"
                transportKind != TransportKind.wifi -> "USB"
                linkOutage -> "провал"
                else -> "есть"
            }
            Log.i(LINK, "%.1f к/с, кадр %.0f КБ, поток %.0f КБ/с, RTT %s, качество %s, связь %s".format(
                summaryFrames / seconds, if (summaryFrames > 0) summaryBytes / 1024.0 / summaryFrames else 0.0,
                summaryBytes / 1024.0 / seconds, linkRttMs?.let { "$it мс" } ?: "—", liveViewQuality?.title ?: "—", link))
        }
        summaryAt = now
        summaryFrames = 0
        summaryBytes = 0
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
            // сеть камеры, а не сеть по умолчанию: на телефоне с сотовой связью это разные сети
            val caps = connectivity.getNetworkCapabilities(wifi?.network ?: wifiNetwork ?: connectivity.activeNetwork)
            caps?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }?.signalStrength
                ?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        }.getOrNull()
        return ConnectionDetails(wifi?.host ?: reconnect?.host, wifi?.localHost, signal)
    }

    /** fps за последние 2 с. */
    private fun tickFrameStats() {
        val now = System.currentTimeMillis()
        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2000) frameTimes.removeFirst()
        fps = frameTimes.size / 2.0
    }

    private fun tickTabletBattery() {
        val now = System.currentTimeMillis()
        if (now - batteryCheckedAt < 15_000) return
        batteryCheckedAt = now
        tabletBattery = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        tabletCharging = batteryManager.isCharging
    }

    /**
     * Останавливает сеанс: задачи, сеанс, соединение (abort — сбросом RST, иначе обычным закрытием с CloseSession).
     * Картинку, свойства, таймер записи и ленту на экране не трогает — их сбрасывает teardown.
     */
    private fun stopSession(abort: Boolean) {
        jobs.forEach { it.cancel() }
        jobs.clear()
        qualityJob?.cancel()
        session?.close()
        when (val current = transport) {
            // обычное закрытие идёт в своём потоке; новое соединение транспорт откроет только после него
            is PtpIpTransport -> if (abort) current.abort(LinkLoss.closed) else current.close()
            else -> current?.close()
        }
        recordingModeJob?.cancel()
        recordingDriver.cancel()
        session = null
        transport = null
        coalescer = null
        targets.clear()
        pending = emptySet()
        linkOutage = false
    }

    /** Останавливает всё, что связано с текущей камерой, и закрывает соединение. */
    private fun teardown() {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnect = null
        isReconnecting = false
        stopSession(abort = false)
        holdWifiLock(false)
        transportKind = null
        wifiDirect = false
        device = null
        deviceInfo = null
        properties = emptyMap()
        frame = null
        frameBitmap = null
        lastSample = null
        liveViewQuality = null
        histogram = null
        histogramStats = null
        frameTimes.clear()
        frameGaps.clear()
        lastFrameAt = 0L
        fps = 0.0
        shownFps = 0
        frameGapMs = 0
        frameNotice = FrameNotice.none
        recordingStartedAt = null
        isApplyingPreset = false
        isCapturing = false
        dismissReview()
        phase = Phase.searching
    }

    companion object {
        private const val TAG = "ZVController"
        /** Связь, качество превью, переподключения — отдельным тегом: `adb logcat -s ZVLink`. */
        private const val LINK = "ZVLink"
        private const val ACTION_USB_PERMISSION = "local.zve10viewer.USB_PERMISSION"
        /** Столько в фоне — и сеанс закрываем обычным образом; на экране подключимся снова. */
        private const val BACKGROUND_CLOSE_MS = 60_000L
        /** Прощальная запись при закрытии: вернуть камере высокое качество превью, если снижали мы. */
        private val RESTORE_HIGH_QUALITY = PtpIpTransport.Farewell(SonyOp.SET_EXT_PROP_VALUE,
            listOf(SonyProp.LIVE_VIEW_IMAGE_QUALITY.toLong()), PtpValue.encode(LiveViewQuality.high.cameraValue, PtpType.UINT8))
    }
}

/**
 * Bitmap для кадров live view: три под размер кадра по кругу — в один декодируем, второй на экране, третий мог ещё
 * не уйти в отрисовку. Без этого каждый кадр — новые 2,4 МБ (1024×576), ~60 МБ/с мусора при 25 к/с. Сменилось
 * качество — Bitmap заменяются новыми по одному. JPEG декодируется прямо из ответа камеры, без копии.
 * Вызывать из одной корутины.
 */
private class FramePool {
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    private val options = BitmapFactory.Options().apply { inMutable = true }

    fun decode(data: ByteArray, offset: Int, length: Int): Bitmap? {
        BitmapFactory.decodeByteArray(data, offset, length, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        val slot = next
        next = (next + 1) % bitmaps.size
        options.inBitmap = bitmaps[slot]?.takeIf { it.width == width && it.height == height }
        val bitmap = try {
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } catch (e: IllegalArgumentException) {
            // в этот Bitmap кадр не лёг — декодируем в новый
            options.inBitmap = null
            BitmapFactory.decodeByteArray(data, offset, length, options)
        } ?: return null
        options.inBitmap = null
        bitmaps[slot] = bitmap
        return bitmap
    }
}

/**
 * Уровни яркости по уменьшенной копии кадра. 256×144 без сглаживания: при усреднении мелкие пересвеченные места
 * растворились бы в соседях и пересвет было бы не видно. Копия и массив — одни на весь сеанс, а не новые на каждый кадр.
 */
private class HistogramSampler {
    private val small = Bitmap.createBitmap(256, 144, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(small)
    private val target = Rect(0, 0, 256, 144)
    private val paint = Paint().apply { isFilterBitmap = false }
    private val pixels = IntArray(256 * 144)

    fun levels(bitmap: Bitmap): IntArray {
        canvas.drawBitmap(bitmap, null, target, paint)
        small.getPixels(pixels, 0, 256, 0, 0, 256, 144)
        return LumaHistogram.levels(pixels)
    }
}
