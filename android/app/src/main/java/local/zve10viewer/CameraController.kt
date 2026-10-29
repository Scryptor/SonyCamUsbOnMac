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
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import local.zve10viewer.debug.DebugFormat
import local.zve10viewer.debug.DebugReport
import local.zve10viewer.debug.DebugRow
import local.zve10viewer.debug.DebugTab
import local.zve10viewer.debug.FrameWindow
import local.zve10viewer.debug.LinkJournal
import local.zve10viewer.debug.RttWindow
import local.zve10viewer.debug.clockTime
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpRefused
import local.zve10viewer.ptp.PtpIpRejected
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.ptp.Ssdp
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.CapturePhase
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.LiveViewQualityPolicy
import local.zve10viewer.session.LiveViewSample
import local.zve10viewer.session.PreviewQualityMode
import local.zve10viewer.session.RecordingToggle
import local.zve10viewer.session.SessionException
import local.zve10viewer.settings.CameraBook
import local.zve10viewer.settings.CameraEntry
import local.zve10viewer.settings.CameraLink
import local.zve10viewer.settings.CameraSighting
import local.zve10viewer.settings.CameraState
import local.zve10viewer.settings.CameraStrip
import local.zve10viewer.settings.KnownCamera
import local.zve10viewer.settings.CurrentCamera
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.GroupFailures
import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.LumaStats
import local.zve10viewer.monitor.AssistExtras
import local.zve10viewer.monitor.AssistRaster
import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.CameraProfile
import local.zve10viewer.monitor.FrameSurface
import local.zve10viewer.monitor.LookWatch
import local.zve10viewer.monitor.LutCache
import local.zve10viewer.monitor.LutTexts
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorTool
import local.zve10viewer.monitor.PictureHelp
import local.zve10viewer.monitor.PicturePath
import local.zve10viewer.monitor.Vectorscope
import local.zve10viewer.monitor.Waveform
import local.zve10viewer.monitor.FocusDrive
import local.zve10viewer.monitor.FocusFrame
import local.zve10viewer.monitor.PowerZoom
import local.zve10viewer.monitor.TouchFocus
import local.zve10viewer.sony.SonyControl
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.PresetChange
import local.zve10viewer.settings.PresetLibrary
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
import local.zve10viewer.settings.WiFiAddress
import local.zve10viewer.settings.WriteCoalescer
import local.zve10viewer.settings.apply
import local.zve10viewer.settings.restore
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.usb.UsbPtpTransport
import local.zve10viewer.view.FloatingPlacement
import local.zve10viewer.view.ShotDeletion
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

    /**
     * Снимок или стоп-кадр этой сессии. В памяти — только миниатюра (и кадр с камеры у RAW без JPEG): сам снимок
     * галерея читает из файла, а не держит целиком (~10 МБ на кадр).
     */
    class Shot(
        val id: Long,
        val files: List<SavedFile>,
        /** Миниатюра для ленты — до 160 точек по длинной стороне. */
        val thumbnail: ImageBitmap?,
        /** Когда камера сняла (спуск отработан) или сохранён стоп-кадр. */
        val takenAt: Long,
        /** Размер картинки в точках — JPEG или стоп-кадра; null — неизвестен (RAW без JPEG). */
        val pixelSize: IntSize?,
        /** Сколько весят файлы вместе, байт. */
        val bytes: Long,
        val isFrameGrab: Boolean,
        /** RAW без JPEG: кадр с камеры в момент снимка — его и показывает галерея. */
        val cameraFrame: ImageBitmap? = null,
    ) {
        val title: String get() = files.joinToString(" + ") { it.name }
        /** JPEG снимка — его показывают и открывают; null — только RAW. */
        val jpeg: SavedFile? get() = files.firstOrNull { it.mime == "image/jpeg" }
    }

    /**
     * Только что удалённый снимок: пока видна «Вернуть», его можно вернуть. trashed — файлы в корзине планшета; нет
     * (Android 10 или система не дала) — они ещё на месте и удалятся, когда плашка закроется.
     */
    class DeletedShot(val shot: Shot, val trashed: Boolean)

    /** Что показывается поверх видео после снимка. */
    data class Review(
        val image: ImageBitmap?, val title: String, val detail: String,
        val files: List<SavedFile>, val source: String,
        /** null — показывается, пока не закроют (снимок открыт из ленты). */
        val endsAt: Long?,
        /** Снимок ещё качается: вместо detail — та же подпись, что у кнопки спуска («Загрузка 42 %»). */
        val live: Boolean = false,
        /** Какого снимка просмотр: скачанный файл подменяет картинку только в просмотре своего снимка. */
        val capture: Long = 0,
        /** С какого момента идёт отсчёт показа — для кольца обратного отсчёта. */
        val shownAt: Long = 0,
    )

    /** Какая кнопка ответила: для волны, «мотания» и вибрации. */
    enum class FeedbackTarget { shutter, record, grab }

    /** События для отклика на экране — от действия камеры и программы, а не от пальца. */
    sealed class FeedbackEvent {
        /** Действие принято (кнопку отпустили, и оно пошло в камеру): волна вокруг кнопки. */
        data class Accepted(val target: FeedbackTarget) : FeedbackEvent()
        /** Камера отработала спуск: шторка. */
        data object ShutterFired : FeedbackEvent()
        /** Стоп-кадр сохранён и встал в ленту: вспышка и полёт кадра. image — весь кадр (копия); null — копии нет. */
        data class FrameGrabbed(val image: ImageBitmap?, val shotId: Long) : FeedbackEvent()
        /** Снимок скачан и встал в ленту. */
        data class ShotAdded(val shotId: Long) : FeedbackEvent()
        data object RecordingStarted : FeedbackEvent()
        data object RecordingStopped : FeedbackEvent()
        /** Отказ: камера не начала (не остановила) запись, снимок или стоп-кадр не удался. */
        data class Failed(val target: FeedbackTarget) : FeedbackEvent()
    }

    private val feedbackEvents = MutableSharedFlow<FeedbackEvent>(extraBufferCapacity = 32)
    /** Отклик для экрана: волна, шторка, полёт кадра, «мотание», вибрация. */
    val feedback: SharedFlow<FeedbackEvent> = feedbackEvents.asSharedFlow()

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
    /** Инструменты монитора: зебра, false color, пикинг, маркеры — помнятся между запусками. */
    var assist by mutableStateOf(MonitorAssist.fromJson(prefs.monitorAssist))
        private set
    /** Зебра, false color и пикинг по последнему кадру — растр над видимой его частью; null — эти инструменты выключены. */
    var assistOverlay by mutableStateOf<AssistOverlay?>(null)
        private set

    /** Растр помощи в кадре: картинка над частью кадра (доли) и сколько точек под зеброй и пикингом, %. */
    class AssistOverlay(val image: ImageBitmap, val left: Float, val top: Float, val width: Float, val height: Float,
                        val zebraShare: Double, val peakingShare: Double)

    /** Что видно на экране: часть кадра (доли) и увеличение — от этого растр помощи. Ставит экран. */
    data class AssistView(val x: Double, val y: Double, val width: Double, val height: Double, val factor: Double)

    @Volatile var assistView = AssistView(0.0, 0.0, 1.0, 1.0, 1.0)
    /** Инструменты и фото / видео — для потока кадров (белое в превью: в видео 234, в фото 255). */
    @Volatile private var assistForFrames = assist
    @Volatile private var assistVideo = true

    /**
     * Кубы LUT на картинке: встроенные — один на вид, свои — из `filesDir/luts/` по имени и дате файла, читаются в фоне. Один
     * экземпляр на LUT: проход картинки узнаёт куб по ссылке и строит таблицу один раз на экземпляр.
     */
    private val luts = LutCache(java.io.File(context.filesDir, "luts"), { task -> scope.launch(Dispatchers.IO) { task.run() } }, ::journal)

    /** Свои LUT: список, добавление, замена, удаление. Поверх того же [luts]: добавленный файл сразу виден кадровому циклу. */
    val lutStore = LutStore(luts)

    /** Имена своих LUT (по алфавиту) для меню «Монитор»; обновляются после добавления и удаления и при открытии полосы LUT. */
    var lutNames by mutableStateOf(lutStore.names)
        private set

    /** Профиль изображения главной камеры — по нему «Авто» выбирает LUT. Ставит каждый снимок её свойств, читает поток кадров. */
    @Volatile private var cameraProfile = CameraProfile.none

    /**
     * Встроенный LUT, который сейчас на картинке: выбранный рукой, а у «Авто» — по профилю изображения главной камеры
     * (следует за ним, как только камера его сменит); null — встроенного нет («Выкл», «Свой», «Авто» без профиля лога).
     */
    val resolvedLut: BuiltinLut?
        get() = luts.resolved(assist, CameraProfile(value(SonyProp.PICTURE_PROFILE), value(SonyProp.PP_GAMMA), value(SonyProp.PP_COLOR_MODE)))

    /** Волновая форма: включена ли, какая (яркость или RGB-парад) и где; место null — по умолчанию, справа внизу. */
    var waveformOn by mutableStateOf(prefs.waveformOn)
        private set
    var waveformMode by mutableStateOf(prefs.waveformMode)
        private set
    var waveformPlacement by mutableStateOf(prefs.waveformPlacement)
        private set
    /** Волновая форма последнего кадра; null — выключена. */
    var waveform by mutableStateOf<WaveformTrace?>(null)
        private set
    @Volatile private var waveformForFrames: Waveform.Mode? = if (waveformOn) waveformMode else null

    /** Волновая форма кадра: картинка следа, что по ней видно и белый уровень превью (на нём — линия 100 %). */
    class WaveformTrace(val image: ImageBitmap, val counts: Waveform.Counts, val white: Int)

    /** Где вектороскоп (доли свободного места); null — на месте по умолчанию, слева сверху под верхней панелью. */
    var vectorscopePlacement by mutableStateOf(prefs.vectorscopePlacement)
        private set
    /** Вектороскоп последнего кадра; null — выключен или кадров ещё нет. */
    var vectorscope by mutableStateOf<VectorscopeTrace?>(null)
        private set

    /**
     * Вектороскоп кадра: картинка 128×128 (сетку рисует панель), масштаб, при котором она посчитана, и сами счётчики корзин —
     * по ним в logcat пишется, где пик и сколько отсчётов у шести меток (`Vectorscope.report`): проверка без снимка экрана.
     */
    class VectorscopeTrace(val image: ImageBitmap, val zoom: Int, val counts: IntArray)

    /** Эталонный кадр для наложения: текущий кадр или снимок из галереи. Хранится в файле — переживает перезапуск. */
    var reference by mutableStateOf<Reference?>(null)
        private set

    class Reference(val image: ImageBitmap, val source: String)

    /**
     * Наложение спрятано касанием миниатюры на панели наложения: эталон не рисуется, а наложение остаётся включённым. Не
     * настройка — после включения наложения и нового эталона эталон снова виден.
     */
    var overlayHidden by mutableStateOf(false)
        private set
    /** Где панель наложения: доли свободного места; null — на месте по умолчанию, справа вверху. */
    var overlayPlacement by mutableStateOf(prefs.overlayPlacement)
        private set

    /** Команды 0x9207, которые знает камера на связи: по ним видно, есть ли фокус касанием, зум, Near/Far. */
    var controlCodes by mutableStateOf<Set<Int>>(emptySet())
        private set
    /** Рамки фокуса последнего кадра: где камера фокусирует. */
    var focusFrames by mutableStateOf<List<FocusFrame>>(emptyList())
        private set
    /** Куда коснулись для фокуса (доли кадра) и когда, мс, — для отметки на кадре. */
    var touchMark by mutableStateOf<Pair<androidx.compose.ui.geometry.Offset, Long>?>(null)
        private set
    /** Зум едет: 1 — к теле, −1 — к широкому; 0 — стоит. */
    var zoomDirection by mutableStateOf(0)
        private set
    /** Фокус касанием: горит — касание кадра ставит туда фокус. Рамки фокуса камеры видны и без него. */
    var touchFocusOn by mutableStateOf(prefs.touchFocusOn)
        private set
    /** Единиц Near/Far, которые ещё надо отправить: шкалу тянут быстрее, чем камера отвечает. */
    private var focusUnits = 0
    private var focusDriving = false
    /** Растёт, когда сеанс уходит: цикл шагов фокуса прошлого сеанса останавливается и новому не мешает. */
    private var focusGeneration = 0
    /** Команды объектива — по одной и по порядку: «стоп» зума не обгонит «пуск» (Mutex отдаёт замок по очереди). */
    private val lensMutex = kotlinx.coroutines.sync.Mutex()
    /** Красная рамка во время записи — как её рисовать (настройка ⚙). */
    var tallyStyle by mutableStateOf(prefs.tallyStyle)
        private set
    /** Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила. */
    var pending by mutableStateOf<Set<SettingID>>(emptySet())
        private set
    /** Все пресеты — и фото, и видео — одним списком, как хранятся. */
    private var library by mutableStateOf(prefs.presets)
    /**
     * Чьи пресеты на экране: режима, в котором камера (S&Q — видео); без связи — в котором она была в последний раз
     * (запоминается; до первой камеры — фото).
     */
    var presetMode by mutableStateOf(prefs.presetMode)
        private set
    /** Пресеты режима presetMode. Номер пресета везде — на кнопке, в списке, в окне — место в этом списке (с 0). */
    val presets: List<Preset> by derivedStateOf { library.filter { it.mode == presetMode } }
    /**
     * Опора текущего пресета, в каждом режиме своя: номер последнего, который применили, создали, сохранили в него или
     * обновили «Взять с камеры». Живёт, пока открыта программа; «Посмотреть» её не меняет.
     */
    private var presetBase by mutableStateOf<Map<CaptureMode, Int>>(emptyMap())
    var isApplyingPreset by mutableStateOf(false)
    /** Быстрый просмотр: пока держат «Посмотреть», этот пресет стоит на камере; отпустили — камера возвращается как была. */
    var previewingPreset by mutableStateOf<Int?>(null)
        private set
    /** Кнопку отпустили — камера возвращается как была. */
    var previewReturning by mutableStateOf(false)
        private set
    private var previewJob: Job? = null
    /** «Хватит» для пресета, который ещё пишется: читает его корутина записи. */
    @Volatile private var previewStopped = false
    private var previewRestore: Map<SettingID, Long> = emptyMap()
        private set
    var isCapturing by mutableStateOf(false)
        private set
    /** Этап снимка для кнопки спуска: фокусировка, спуск, загрузка с долей; null — не снимаем. */
    var capturePhase by mutableStateOf<CapturePhase?>(null)
        private set
    /** Короткое сообщение: пресет применён, снимок сохранён и т.п. */
    var statusMessage by mutableStateOf<String?>(null)
        private set
    /** Отказ записи — подпись слева от кнопки записи, на месте «Запуск записи…»: на кнопку в этот миг и смотрят. */
    var recordingNotice by mutableStateOf<String?>(null)
        private set
    /** Когда началась запись (для таймера «● REC»). */
    var recordingStartedAt by mutableStateOf<Long?>(null)
        private set
    /** Чего ждём от камеры после нажатия записи: запуска или остановки; null — ничего. */
    var recordingRequest by mutableStateOf<RecordingToggle.Request?>(null)
        private set
    private var recordingToggle = RecordingToggle()
    /** Нажатие записи, которое ещё идёт в камеру. */
    private var recordingCall: Job? = null
    /** Код, которым камера отклонила последнее нажатие записи, если запись так и не сменилась, — для сообщения. */
    private var recordingRefusal: Int? = null
    var review by mutableStateOf<Review?>(null)
        private set
    /** Снимки и стоп-кадры этой сессии, новые сначала. */
    var shots by mutableStateOf<List<Shot>>(emptyList())
        private set
    /** Удалённый снимок, который ещё можно вернуть; null — плашки «Вернуть» нет. */
    var deletedShot by mutableStateOf<DeletedShot?>(null)
        private set
    private var deletedShotJob: Job? = null
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
    /** Идёт сопряжение по Wi-Fi («Сопрячь новую камеру…» в списке камер). */
    var isPairing by mutableStateOf(false)
        private set
    /** Что сейчас с Wi-Fi: «ищу камеру», «камера найдена, нужно сопряжение» и т.п. */
    var wifiStatus by mutableStateOf<String?>(null)
        private set
    /** Камеры, которые программа знает, и та, к которой подключались последней. */
    var cameraBook by mutableStateOf(CameraBook.load(prefs.cameras, prefs.preferredCamera, prefs.wifiCameraName, prefs.wifiHost))
        private set
    /** Кого видно сейчас: по кабелю — устройства на шине, по Wi-Fi — ответы на поиск (30 с после последнего). */
    private var usbSightings by mutableStateOf<List<CameraSighting>>(emptyList())
    private var wifiSightings by mutableStateOf<Map<WiFiAddress, CameraSighting>>(emptyMap())
    /** С какой камерой сеанс (подключение, тихое восстановление) и какая это камера из знакомых. */
    private var currentLink by mutableStateOf<CameraLink?>(null)
    /** id главной в книге камер; null — сеанса нет или DeviceInfo ещё не пришёл. По нему лента ставит плитку главной на место. */
    var currentCameraId by mutableStateOf<String?>(null)
        private set
    /**
     * Камера, которую выбрали в списке (или «Сопрячь» у неё): подключаемся к ней, а не как обычно, — по Wi-Fi даже когда на
     * кабеле есть другая. Подключились или попытка не удалась — выбора больше нет, дальше как обычно.
     */
    private var choice by mutableStateOf<CameraLink?>(null)
    private var choiceName: String? = null
    /** Сопряжение только с этой камерой («Сопрячь» в списке); null — с любой («Сопрячь новую камеру…»). */
    private var pairingTarget: WiFiAddress? = null
    /** Поиск в сети, пока открыт список камер. */
    private var searchJob: Job? = null
    /** Камеры ленты, кроме главной: у каждой свой сеанс (или «нет связи» с последним кадром), по порядку ленты. */
    var sideCameras by mutableStateOf<List<SideCamera>>(emptyList())
        private set
    /** Корутины открытого сеанса главной (события камеры): живут и после передачи сеанса в ленту. */
    private var sessionScope: CoroutineScope? = null
    /** К каким камерам ленты сейчас стучимся и когда стучались в последний раз. */
    private val sideConnecting = HashMap<String, Job>()
    private val sideTried = HashMap<String, Long>()
    /** Что не удалось на связанных камерах за текущую серию — одной строкой через секунду. */
    private var groupFailures = GroupFailures()
    private var groupStatusJob: Job? = null
    /** Адрес, к которому сейчас стучится главная: камеру по нему в ленту не тянем. */
    private var mainAttempt: WiFiAddress? = null
    /** Провал связи по Wi-Fi: ждём ответа, а ничего не приходит. Сеанс держим, кадр остаётся на экране. */
    var linkOutage by mutableStateOf(false)
        private set
    /** Сеанс по Wi-Fi оборвался и тихо восстанавливается: кадр, свойства и таймер записи на месте, кнопки неактивны. */
    var isReconnecting by mutableStateOf(false)
        private set
    /** Каким качеством идут кадры (по их ширине); null — кадров нет. */
    var liveViewQuality by mutableStateOf<LiveViewQuality?>(null)
        private set
    /** Качество превью по Wi-Fi: авто или вручную — выбор в подсказке к счётчику кадров и в настройках. */
    var previewQualityMode by mutableStateOf(prefs.previewQualityMode)
        private set

    /**
     * Картинка на экране увеличена (≥ 1,5×): адаптивное качество держит полное превью до последнего (снижает при
     * прогнозе меньше 6 к/с, возвращает с 10). Ставит экран; экрана с видео нет — false.
     */
    var sharpPreview = false

    /** Отладка (только debug-сборка): команды экрану по adb-broadcast — увеличение и галерея без мультитача. */
    sealed class DebugCommand {
        /** factor ≤ 1 — без увеличения; x, y — точка кадра (доли) в центре экрана; bicubic — шейдер вкл./выкл. */
        data class Zoom(val factor: Double, val x: Double, val y: Double, val bicubic: Boolean?) : DebugCommand()
        /**
         * Галерея на снимке с этим местом в ленте (0 — самый новый); меньше нуля — закрыть. delete — удалить снимок с этим
         * местом, а без места — открытый в галерее; undo — «Вернуть».
         */
        data class Gallery(val index: Int, val delete: Boolean = false, val undo: Boolean = false) : DebugCommand()
        /**
         * Монитор: tool + value — значение инструмента, tool без value — включить или выключить его, off — выключить всё,
         * menu — открыть меню (на инструменте tool) или закрыть; waveform, reference и shot — волновая форма и эталон.
         */
        data class Assist(val tool: String?, val value: String?, val off: Boolean, val menu: Boolean?,
                          /** Волновая форма: «luma», «parade» или «off». */
                          val waveform: String? = null,
                          /** Эталон для наложения — текущий кадр. */
                          val reference: Boolean = false,
                          /** Эталон — снимок с этим местом в ленте. */
                          val shot: Int? = null,
                          /** Как касание миниатюры эталона: включить наложение, спрятать или показать эталон. */
                          val overlayToggle: Boolean = false,
                          /** LUT и вектороскоп: `lut`, `lutFile`, `lutScopes`, `vectorscope`, `vectorscopeZoom` — применяются после остального. */
                          val extras: AssistExtras.Request = AssistExtras.Request()) : DebugCommand()
    }

    private val debugCommandFlow = MutableSharedFlow<DebugCommand>(extraBufferCapacity = 8)
    val debugCommands: SharedFlow<DebugCommand> = debugCommandFlow.asSharedFlow()

    fun debugCommand(command: DebugCommand) {
        debugCommandFlow.tryEmit(command)
    }

    /** Отладка (только debug-сборка): прокси стенда или виртуальная камера — только этот адрес, без памяти и поиска. */
    var debugHost: WiFiAddress? = null
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
    private var recordingNoticeJob: Job? = null
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
    private class Reconnect(val address: WiFiAddress, val cause: LinkLoss, val startedAt: Long) {
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
    /** Номер следующего снимка в ленте: галерея держит снимок по нему, а не по месту в ленте. */
    private var nextShotId = 1L
    /** Номер нажатия спуска: просмотр подменяет картинку только у своего снимка. */
    private var captureSerial = 0L

    // для панели отладки: сеанс — вместе с тихими переподключениями, счёт с начала нового сеанса
    private var sessionStartedAt: Long? = null
    /** Провалы связи на прошлых соединениях этого сеанса (у текущего соединения — свой счёт в его состоянии связи). */
    private var outagesBefore = 0
    private var longestOutageMs = 0L
    private var framesTotal = 0L
    private var qualitySwitches = 0
    private var lastFrameBytes = 0
    private val rttWindow = RttWindow()
    private val frameWindow = FrameWindow()
    /** С запуска программы, как у программы для Mac. */
    private var reconnects = 0
    private var lastRecoveryMs: Long? = null
    /** Последний обрыв: почему и когда — виден и на экране ожидания. */
    private var lastLoss: Pair<String, Long>? = null
    /** Что нашёл последний поиск в сети: в журнал — только когда это меняется, а не каждые 5 с. */
    private var lastFound: List<WiFiAddress>? = null

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
                    if (property(SettingID.colorTemperature)?.current?.intValue != kelvin) {
                        request(SettingID.colorTemperature, kelvin)
                    } else {
                        // на главной уже такая — связанным её всё равно ставим
                        linkedSides.forEach { it.write(SettingID.colorTemperature, kelvin) }
                    }
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
        // связанные камеры ленты — то же значение
        linkedSides.forEach { it.write(id, value) }
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

    /**
     * Запись: нажатие ждёт подтверждения камеры — свойства MovieRecordingState или ответа вызова — до 6 с; кнопка
     * тем временем показывает «Запуск записи…» / «Остановка…». Второе нажатие, пока ждём, не считается.
     */
    fun toggleRecording() {
        val session = session ?: return
        if (phase != Phase.ready) return
        // прошлый вызов ещё не вернулся (связь пропала, отказ по времени уже был): второе нажатие дошло бы следом за
        // первым, когда связь вернётся, — запуск и сразу остановка, лишний ролик на карте
        if (recordingCall?.isActive == true) {
            feedback(FeedbackEvent.Failed(FeedbackTarget.record))
            showRecordingNotice("Камера ещё не ответила на прошлое нажатие")
            return
        }
        val toggle = recordingToggle
        if (!toggle.press(isRecording, System.currentTimeMillis())) return
        recordingRequest = toggle.request
        recordingRefusal = null
        recordingNoticeJob?.cancel()
        recordingNotice = null
        feedback(FeedbackEvent.Accepted(FeedbackTarget.record))
        // связанные — к тому же состоянию, что будет на главной, и сразу, а не после неё
        val target = !isRecording
        journal("запись: нажатие — ${if (target) "запуск" else "остановка"}")
        linkedSides.forEach { side ->
            scope.launch { if (!side.setRecording(target)) groupFailed(side, GroupFailures.RECORDING) }
        }
        recordingCall = scope.launch {
            val pressed = System.currentTimeMillis()
            var refusal: Int? = null
            val took = { "${System.currentTimeMillis() - pressed} мс" }
            val recording = try {
                session.toggleRecording().also { journal("запись: камера приняла нажатие, ${if (it) "пишет" else "не пишет"} (${took()})") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionException.RecordButtonRefused) {
                journal("запись: камера ответила 0x%04X на «%s», %s (%s)".format(e.code, if (e.release) "отпустить" else "нажать",
                    if (e.recording) "пишет" else "не пишет", took()))
                // отклонила, а запись всё же сменилась — это и есть исход; не сменилась — отказ с её кодом
                if (e.recording == target) {
                    e.recording
                } else {
                    refusal = e.code
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "запись: камера не приняла нажатие", e)
                journal("запись: нажатие не прошло: ${e.message} (${took()})")
                null
            }
            // сеанс сменился, пока ждали, — это нажатие уже ни к чему не относится
            if (toggle === recordingToggle) {
                recordingRefusal = refusal
                recordingOutcome(toggle.completed(recording, System.currentTimeMillis()))
            }
        }
    }

    /** Чем кончился запрос записи или сама запись: событие для экрана, при отказе — сообщение. */
    private fun recordingOutcome(outcome: RecordingToggle.Outcome?) {
        recordingRequest = recordingToggle.request
        when (outcome) {
            null -> return
            RecordingToggle.Outcome.Started -> feedback(FeedbackEvent.RecordingStarted)
            RecordingToggle.Outcome.Stopped -> feedback(FeedbackEvent.RecordingStopped)
            is RecordingToggle.Outcome.Failed, RecordingToggle.Outcome.Aborted -> feedback(FeedbackEvent.Failed(FeedbackTarget.record))
            RecordingToggle.Outcome.StoppedByCamera -> Unit
        }
        RecordingToggle.message(outcome, recordingRefusal)?.let { message ->
            if (RecordingToggle.showsAtButton(outcome)) showRecordingNotice(message) else showStatus(message)
            journal("запись: $message")
        }
    }

    /** Подпись у кнопки записи — 5 с, как сообщение в строке состояния. */
    private fun showRecordingNotice(text: String) {
        recordingNotice = text
        recordingNoticeJob?.cancel()
        recordingNoticeJob = scope.launch {
            delay(5000)
            recordingNotice = null
        }
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

    /** Строка состояния видна вместе с элементами; закреплённая (так по умолчанию) остаётся и когда они скрыты. */
    var statusBarPinned by mutableStateOf(prefs.statusBarPinned)
        private set

    fun toggleStatusBarPin() {
        statusBarPinned = !statusBarPinned
        prefs.statusBarPinned = statusBarPinned
    }

    /** Где гистограмма (доли свободного места); null — на месте по умолчанию. */
    var histogramPlacement by mutableStateOf(prefs.histogramPlacement)
        private set

    fun placeHistogram(placement: FloatingPlacement) {
        histogramPlacement = placement
        prefs.histogramPlacement = placement
    }

    fun applyTallyStyle(style: TallyStyle) {
        tallyStyle = style
        prefs.tallyStyle = style
    }

    /** Значение инструмента монитора; false — у него такого нет. */
    fun setAssist(tool: MonitorTool, value: String): Boolean {
        saveAssist(assist.with(tool, value) ?: return false)
        return true
    }

    /** Включить или выключить инструмент: включается тем значением, что было в прошлый раз. */
    fun toggleAssist(tool: MonitorTool) = saveAssist(assist.toggled(tool))

    fun assistOff() = saveAssist(assist.allOff())

    private fun saveAssist(next: MonitorAssist) {
        // включили или выключили наложение — «спрятано» больше не действует
        if ((next.overlay == MonitorAssist.Overlay.off) != (assist.overlay == MonitorAssist.Overlay.off)) overlayHidden = false
        assist = next
        assistForFrames = next
        warmLut()
        prefs.monitorAssist = next.toJson()
        // растровые выключили — старый растр не показываем, даже если новых кадров нет; так же вектороскоп
        if (!next.needsRaster) assistOverlay = null
        if (!next.vectorscope) vectorscope = null
        // наложение включили — эталон нужен: прошлый из файла или текущий кадр
        if (next.overlay != MonitorAssist.Overlay.off) ensureReference()
    }

    /** Встроенный куб нужен — сделать его заранее, в фоне, а не на первом кадре с LUT («строится при первом выборе»). */
    private fun warmLut() {
        luts.resolved(assist, cameraProfile)?.let(luts::warm)
    }

    fun toggleTouchFocus() {
        touchFocusOn = !touchFocusOn
        prefs.touchFocusOn = touchFocusOn
    }

    private fun value(code: Int): Long? = properties[code]?.current?.intValue

    /** Как сейчас ставится фокус касанием; null — никак (ручной фокус, камера не умеет). */
    val touchFocusMethod: TouchFocus.Method?
        get() = TouchFocus.method(value(SonyProp.FOCUS_MODE), value(SonyProp.FOCUS_AREA), value(SonyProp.REMOTE_TOUCH_ENABLED),
            value(SonyProp.TOUCH_FUNCTION), controlCodes)

    /** Камера вообще умеет фокус касанием (кнопка есть, даже если сейчас ручной фокус). */
    val touchFocusSupported: Boolean
        get() = SonyControl.AF_AREA_POSITION in controlCodes || SonyControl.REMOTE_TOUCH in controlCodes

    /** Слежение от касания можно снять. */
    val touchCancelAvailable: Boolean
        get() = value(SonyProp.REMOTE_TOUCH_CANCEL_ENABLED) == 1L && SonyControl.REMOTE_TOUCH_CANCEL in controlCodes

    val zoomAvailable: Boolean get() = PowerZoom.isAvailable(value(SonyProp.ZOOM_ENABLED), controlCodes)

    /** Шкала зума; null — камера её не отдаёт. */
    val zoomBar: PowerZoom.Bar?
        get() = value(SonyProp.ZOOM_BAR)?.let(PowerZoom.Bar::of)?.takeIf { it.boxes > 0 }

    /** Масштаб зума подписью («×1,2»); на ×1,0 — null: подпись не нужна. */
    val zoomScaleLabel: String?
        get() = value(SonyProp.ZOOM_SCALE)?.takeIf { it > 1000 }?.let(PowerZoom::label)

    val focusDriveAvailable: Boolean get() = FocusDrive.isAvailable(value(SonyProp.NEAR_FAR_ENABLED), controlCodes)

    /** Где фокус, 0…100 (0 — ближе всего); null — камера не отдаёт. */
    val focalPosition: Int?
        get() = properties[SonyProp.FOCAL_POSITION]?.takeIf { it.enabled != 0 }?.current?.intValue?.toInt()

    /** Что сделает касание кадра сейчас — строка «Сейчас» в подсказке к фокусу касанием. */
    val touchFocusNow: String
        get() = TouchFocus.helpNow(touchFocusMethod, ValueFormatter.label(SettingID.focusArea, property(SettingID.focusArea)),
            value(SonyProp.TOUCH_FUNCTION), value(SonyProp.FOCUS_MODE), value(SonyProp.ZOOM_SCALE))

    /** Почему фокус касанием сейчас не ставится — для подсказки к кнопке и строки состояния. */
    val touchRefusal: String
        get() = TouchFocus.refusal(value(SonyProp.FOCUS_MODE), value(SonyProp.ZOOM_SCALE))

    /** Фокус в точку кадра (доли 0…1): точка AF или касание экрана камеры. false — сейчас нельзя. */
    fun touchFocus(x: Double, y: Double): Boolean {
        val session = session ?: return false
        if (phase != Phase.ready) return false
        val method = touchFocusMethod ?: run {
            showStatus(touchRefusal)
            return false
        }
        touchMark = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat()) to System.currentTimeMillis()
        val code = if (method == TouchFocus.Method.areaPosition) SonyControl.AF_AREA_POSITION else SonyControl.REMOTE_TOUCH
        val encoded = TouchFocus.encode(x, y)
        journal("фокус касанием: ${if (method == TouchFocus.Method.areaPosition) "точка AF" else "касание экрана камеры"} " +
            "(${encoded shr 16}, ${encoded and 0xFFFF})")
        scope.launch {
            val reply = lensMutex.withLock { runCatching { session.control(code, encoded, PtpType.UINT32) }.getOrNull() }
            if (reply != PtpResponseCode.OK) showStatus("Камера не поставила фокус в эту точку")
        }
        return true
    }

    /** Снять слежение, начатое касанием. */
    fun cancelTouch() {
        val session = session ?: return
        if (phase != Phase.ready || !touchCancelAvailable) return
        touchMark = null
        scope.launch { lensMutex.withLock { runCatching { session.pressButton(SonyControl.REMOTE_TOUCH_CANCEL) } } }
    }

    /**
     * Зум: 1 — к теле, −1 — к широкому, пока не придёт 0. Повтор того же направления ничего не шлёт. Стоп без сеанса
     * (связь пропала, пока держали «T») только сбрасывает кнопку: слать некому.
     */
    fun zoom(direction: Int) {
        val dir = direction.coerceIn(-1, 1)
        if (dir == zoomDirection) return
        val session = session
        if (dir != 0 && (session == null || phase != Phase.ready || !zoomAvailable)) return
        zoomDirection = dir
        if (session == null) return
        journal("зум: " + if (dir > 0) "к теле" else if (dir < 0) "к широкому" else "стоп")
        scope.launch { lensMutex.withLock { runCatching { session.control(SonyControl.ZOOM_OPERATION, dir.toLong(), PtpType.INT8) } } }
    }

    /**
     * Сеанс уходит (обрыв, другая камера, лента): зум — стоп этому сеансу, кнопка отпущена, очередь шагов фокуса пуста,
     * отметка касания снята. Иначе после переподключения «T» горела бы, а шаги прошлого сеанса ушли бы новому.
     */
    private fun releaseLens() {
        val session = session
        if (zoomDirection != 0 && session != null) {
            scope.launch { lensMutex.withLock { runCatching { session.control(SonyControl.ZOOM_OPERATION, 0, PtpType.INT8) } } }
        }
        zoomDirection = 0
        focusUnits = 0
        focusGeneration++
        focusDriving = false
        touchMark = null
    }

    /** Перефокусировка на столько единиц Near/Far (отрицательное — ближе): по одной команде за раз, остальное копится. */
    fun focusStep(units: Int) {
        val session = session ?: return
        if (units == 0 || phase != Phase.ready || !focusDriveAvailable) return
        focusUnits += units
        if (focusDriving) return
        focusDriving = true
        val generation = focusGeneration
        scope.launch {
            try {
                while (focusUnits != 0 && focusGeneration == generation) {
                    val next = FocusDrive.commands(focusUnits).firstOrNull() ?: break
                    focusUnits -= next.toInt()
                    runCatching { session.control(SonyControl.NEAR_FAR, next, PtpType.INT16) }
                }
            } finally {
                // сеанс ушёл, пока шла команда, — флаг уже сброшен, и новый цикл мог начаться: его не трогаем
                if (focusGeneration == generation) focusDriving = false
            }
        }
    }

    fun toggleWaveform() {
        waveformOn = !waveformOn
        prefs.waveformOn = waveformOn
        waveformForFrames = if (waveformOn) waveformMode else null
        if (!waveformOn) waveform = null
    }

    fun chooseWaveformMode(mode: Waveform.Mode) {
        waveformMode = mode
        prefs.waveformMode = mode
        if (waveformOn) waveformForFrames = mode
    }

    fun placeWaveform(placement: FloatingPlacement) {
        waveformPlacement = placement
        prefs.waveformPlacement = placement
    }

    fun placeVectorscope(placement: FloatingPlacement) {
        vectorscopePlacement = placement
        prefs.vectorscopePlacement = placement
    }

    /** Касание панели вектороскопа: масштаб ×1 ↔ ×2. */
    fun toggleVectorscopeZoom() = saveAssist(assist.copy(vectorscopeZoom = if (assist.vectorscopeZoom == 1) 2 else 1))

    /** Переключатель «Помощь — по картинке с LUT». */
    fun setLutScopes(on: Boolean) = saveAssist(assist.copy(lutScopes = on))

    /** Выбрать файл из списка своих LUT: режим «Свой» и этот файл. */
    fun selectLutFile(name: String) = saveAssist(assist.lutFileSelected(name))

    /** Список своих LUT заново с диска: папку могли тронуть руками. Вызывается, когда меню «Монитор» открывается на LUT. */
    fun refreshLuts() {
        val fresh = lutStore.names
        if (fresh != lutNames) lutNames = fresh
    }

    /** Убрать свой LUT из папки; выбранный выключается («Выкл»), файл забывается. */
    fun deleteLut(name: String) {
        lutStore.delete(name)
        refreshLuts()
        saveAssist(assist.lutFileDeleted(name))
    }

    /**
     * «Добавить LUT…»: файл из системного выбора проверяется разбором, копируется в папку программы и сразу выбирается («Свой» и
     * этот файл). Не вышло (не 3D LUT, не те строки, уже 12, не прочитался) — причина в строке состояния, выбор не меняется.
     */
    fun importLut(uri: Uri) {
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                lutStore.importFile(displayName(uri) ?: "LUT") { context.contentResolver.openInputStream(uri) }
            }
            // и при отказе список читается заново: замена могла оборваться на середине
            refreshLuts()
            when (outcome) {
                is LutStore.Outcome.Added -> {
                    selectLutFile(outcome.name)
                    showStatus(LutTexts.added(outcome.name))
                }
                is LutStore.Outcome.Refused -> showStatus(outcome.failure.message)
            }
        }
    }

    /** Имя файла по адресу от системного выбора файла; null — провайдер его не назвал. */
    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    /**
     * Отладка (DEBUG_ASSIST, только debug-сборка): extras про LUT и вектороскоп — `lut`, `lutFile`, `lutScopes`, `vectorscope`,
     * `vectorscopeZoom` (см. [AssistExtras]). Что не понято, не применяется и называется в logcat; что стало — одной строкой
     * ZVAssist сразу, не дожидаясь кадров (с камерой или без).
     */
    fun applyAssistExtras(request: AssistExtras.Request) {
        if (request.isEmpty) return
        val applied = AssistExtras.apply(assist, request)
        if (applied.rejected.isNotEmpty()) Log.w("ZVAssist", "DEBUG_ASSIST: не понято ${applied.rejected.joinToString()} в $request")
        if (applied.assist != assist) saveAssist(applied.assist)
        Log.i("ZVAssist", AssistExtras.describe(applied.assist))
    }

    /** Файл эталона — в данных программы: снимок, из которого его взяли, можно и удалить. */
    private val referenceFile get() = java.io.File(context.filesDir, "reference.jpg")

    /** Эталон для наложения есть или будет: в памяти, иначе из файла прошлого раза, иначе — текущий кадр. */
    /** Эталон для наложения, включённого с прошлого запуска, уже запрошен с первым кадром. */
    private var referenceRequested = false

    fun ensureReference() {
        if (reference != null) return
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                referenceFile.takeIf { it.exists() }?.readBytes()?.let { MediaSaver.decode(it, REFERENCE_SIDE) }
            }
            if (reference != null) return@launch
            if (saved != null) reference = Reference(saved.asImageBitmap(), prefs.referenceSource ?: "эталон")
            else takeReferenceFromFrame()
        }
    }

    /** Эталон — текущий кадр превью. */
    fun takeReferenceFromFrame(): Boolean {
        val sample = lastSample ?: return false
        val source = "кадр " + java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date())
        scope.launch {
            val jpeg = withContext(Dispatchers.Default) { sample.jpeg() }
            val image = withContext(Dispatchers.Default) { MediaSaver.decode(jpeg, REFERENCE_SIDE) } ?: return@launch
            reference = Reference(image.asImageBitmap(), source)
            prefs.referenceSource = source
            withContext(Dispatchers.IO) { runCatching { referenceFile.writeBytes(jpeg) } }
            showStatus("Эталон для наложения — текущий кадр")
        }
        return true
    }

    /** Эталон — снимок из галереи: JPEG (у RAW+JPEG — он), уменьшенный до 2048 точек по длинной стороне. */
    fun takeReference(shot: Shot) {
        val file = shot.jpeg
        scope.launch {
            val image = file?.let { withContext(Dispatchers.IO) { saver.decode(it.uri, REFERENCE_SIDE, hardware = false) } }
            if (image == null) {
                showStatus("Этот снимок не открылся как эталон")
                return@launch
            }
            reference = Reference(image.asImageBitmap(), file.name)
            prefs.referenceSource = file.name
            withContext(Dispatchers.IO) {
                runCatching { referenceFile.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            }
            showStatus("Эталон для наложения — ${file.name}")
        }
    }

    /** Эталон — картинка из файла (системный выбор файла): уменьшенная до 2048 точек с учётом поворота из EXIF. */
    fun takeReference(uri: Uri) {
        scope.launch {
            val image = withContext(Dispatchers.IO) { saver.decode(uri, REFERENCE_SIDE, hardware = false) }
            if (image == null) {
                showStatus("Эта картинка не открылась как эталон")
                return@launch
            }
            val name = withContext(Dispatchers.IO) { displayName(uri) } ?: "картинка"
            reference = Reference(image.asImageBitmap(), name)
            prefs.referenceSource = name
            withContext(Dispatchers.IO) {
                runCatching { referenceFile.outputStream().use { image.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
            }
            showStatus("Эталон для наложения — $name")
        }
    }

    /** Касание миниатюры эталона: наложение выключено — включить, иначе спрятать или показать эталон. */
    fun toggleOverlayVisible() {
        if (assist.overlay == MonitorAssist.Overlay.off) toggleAssist(MonitorTool.overlay) else overlayHidden = !overlayHidden
    }

    /** Взяли новый эталон (кадр, файл, снимок из галереи) — его и показать: наложение включается, спрятанное — видно. */
    fun showOverlay() {
        if (assist.overlay == MonitorAssist.Overlay.off) toggleAssist(MonitorTool.overlay)
        overlayHidden = false
    }

    /** Ползунок на панели наложения: непрозрачность 0…1, с шагом 5 %. */
    fun setOverlayOpacity(opacity: Float) {
        setAssist(MonitorTool.overlay, MonitorAssist.Overlay.steps(opacity).value)
    }

    fun placeOverlay(placement: FloatingPlacement) {
        overlayPlacement = placement
        prefs.overlayPlacement = placement
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

    /**
     * Снимок. Этапы — для кнопки спуска: фокусировка → спуск отработан (шторка; просмотр — с кадром этой секунды) →
     * загрузка файла с долей скачанного. Файлов у снимка — по формату (RAW+JPEG — два).
     */
    fun capturePhoto() {
        val session = session ?: return
        if (phase != Phase.ready || mode != CaptureMode.photo || isCapturing || isApplyingPreset) return
        isCapturing = true
        capturePhase = CapturePhase.Focusing
        feedback(FeedbackEvent.Accepted(FeedbackTarget.shutter))
        val destination = prefs.destination
        val reviewOn = prefs.reviewEnabled
        val source = if (destination.savesToDevice) prefs.reviewSource else Prefs.ReviewSource.camera
        // RAW+JPEG (0xD253 = 2) — два файла, иначе один: пришли все — спуск сразу свободен
        val fileCount = if (properties[SonyProp.FILE_FORMAT]?.current?.intValue == 2L) 2 else 1
        val transport = transport
        val capture = ++captureSerial
        scope.launch {
            // кадр с камеры в момент снимка — для просмотра и для RAW без JPEG
            var cameraFrame: ImageBitmap? = null
            var takenAt = System.currentTimeMillis()
            var progress: Job? = null
            try {
                if (properties[SonyProp.STILL_IMAGE_STORE_DESTINATION]?.current?.intValue != destination.cameraValue) {
                    session.set(SonyProp.STILL_IMAGE_STORE_DESTINATION, destination.cameraValue)
                }
                val objects = session.capturePhoto(destination.savesToDevice, fileCount) { step ->
                    // транспорт не считает принятые байты — доли не будет: «Загрузка…» и крутящаяся дуга, а не «0 %»
                    capturePhase = if (step is CapturePhase.Downloading && transport?.dataProgress == null) step.copy(expected = null) else step
                    when (step) {
                        CapturePhase.Exposing -> {
                            takenAt = System.currentTimeMillis()
                            // копия: кадр на экране — из пула, его перезапишут следующие кадры
                            cameraFrame = frameCopy()
                            feedback(FeedbackEvent.ShutterFired)
                            // просмотр — с кадром той секунды, когда камера снимала, а не момента нажатия, и отсчёт
                            // идёт с этой секунды; загруженный снимок подменит кадр, когда скачается, не продлевая показ
                            if (reviewOn) {
                                showReview(Review(cameraFrame, "Снимок", "", emptyList(), "Кадр с камеры", null, live = true,
                                    capture = capture), timed = true)
                            }
                        }
                        is CapturePhase.Downloading -> if (progress == null) progress = scope.launch { followDownload(transport) }
                        CapturePhase.Focusing -> Unit
                    }
                }
                if (!destination.savesToDevice) {
                    showStatus("Снимок сохранён на карту камеры")
                    return@launch
                }
                if (objects.isEmpty()) throw IllegalStateException("камера не отдала файл")
                val files = withContext(Dispatchers.IO) { objects.map { saver.save(it.filename, it.data) } }
                val jpeg = objects.firstOrNull { it.filename.endsWith(".JPG", ignoreCase = true) }?.data
                val frame = cameraFrame
                val small = withContext(Dispatchers.Default) {
                    (jpeg?.let { MediaSaver.decode(it, THUMBNAIL_SIDE) } ?: frame?.let { MediaSaver.thumbnail(it.asAndroidBitmap(), THUMBNAIL_SIDE) })
                        ?.asImageBitmap()
                }
                val shot = Shot(nextShotId++, files, small, takenAt, jpeg?.let(MediaSaver::pixelSize),
                    objects.sumOf { it.data.size.toLong() }, isFrameGrab = false, cameraFrame = if (jpeg == null) frame else null)
                shots = listOf(shot) + shots
                feedback(FeedbackEvent.ShotAdded(shot.id))
                showStatus("Сохранено: ${shot.title}")
                // просмотр этого снимка ещё на экране — в нём подменяем картинку и подпись, время показа не трогаем
                if (review?.capture == capture) {
                    // большой снимок декодируем, только если его и показываем
                    val large = if (source == Prefs.ReviewSource.downloaded && jpeg != null) {
                        withContext(Dispatchers.Default) { MediaSaver.decode(jpeg, 2048)?.asImageBitmap() }
                    } else {
                        null
                    }
                    review?.takeIf { it.capture == capture }?.let { current ->
                        review = current.copy(image = large ?: current.image, title = shot.title,
                            detail = "%.1f МБ · «%s»".format(shot.bytes / 1_048_576.0, saver.folderTitle), files = files,
                            source = if (large != null) "Загруженный снимок" else "Кадр с камеры", live = false)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "снимок не удался", e)
                if (review?.capture == capture) dismissReview()
                feedback(FeedbackEvent.Failed(FeedbackTarget.shutter))
                showStatus("Снимок не удался: ${e.message}")
            } finally {
                progress?.cancel()
                isCapturing = false
                capturePhase = null
            }
        }
    }

    /**
     * Пока качается файл снимка — 10 раз в секунду доля из счётчика транспорта. Берём только показание той же длины, что
     * у файла по ObjectInfo: между файлами в очередь успевают проскочить кадры live view.
     */
    private suspend fun followDownload(transport: PtpTransport?) {
        val counter = transport?.dataProgress ?: return
        while (true) {
            delay(100)
            val step = capturePhase as? CapturePhase.Downloading ?: return
            val reading = counter.current ?: continue
            if (step.expected == null || reading.expected != step.expected) continue
            if (reading.received != step.received) capturePhase = step.copy(received = reading.received)
        }
    }

    /** Стоп-кадр из видео: сохраняет текущий кадр live view, запись не прерывается. */
    fun grabFrame() {
        val sample = lastSample ?: return
        // копия: кадр на экране — из пула; она же летит в ленту
        val image = frameCopy()
        feedback(FeedbackEvent.Accepted(FeedbackTarget.grab))
        scope.launch {
            try {
                val jpeg = withContext(Dispatchers.Default) { sample.jpeg() }
                val file = withContext(Dispatchers.IO) { saver.save(FileNaming.frameGrab(), jpeg) }
                val small = withContext(Dispatchers.Default) {
                    image?.let { MediaSaver.thumbnail(it.asAndroidBitmap(), THUMBNAIL_SIDE).asImageBitmap() }
                }
                val shot = Shot(nextShotId++, listOf(file), small, System.currentTimeMillis(), MediaSaver.pixelSize(jpeg),
                    jpeg.size.toLong(), isFrameGrab = true)
                shots = listOf(shot) + shots
                feedback(FeedbackEvent.FrameGrabbed(image, shot.id))
                showStatus("Кадр сохранён: ${file.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                feedback(FeedbackEvent.Failed(FeedbackTarget.grab))
                showStatus("Не удалось сохранить кадр: ${e.message}")
            }
        }
    }

    /** «Поделиться» снимком — системное окно выбора программы; у RAW+JPEG — оба файла. */
    fun share(files: List<SavedFile>) {
        if (files.isEmpty()) return
        val intent = if (files.size == 1) {
            Intent(Intent.ACTION_SEND).setType(files[0].mime).putExtra(Intent.EXTRA_STREAM, files[0].uri)
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*")
                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { it.uri }))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(chooser) }.onFailure { showStatus("Не удалось поделиться: ${it.message}") }
    }

    private fun feedback(event: FeedbackEvent) {
        feedbackEvents.tryEmit(event)
    }

    /**
     * Удаляет снимок из ленты: файлы (у RAW + JPEG — оба) уходят в корзину планшета — система держит их 30 дней; 6 с
     * видна «Вернуть». Корзины нет (Android 10) или система не дала — файлы удаляются насовсем, когда плашка закроется.
     * false — такого снимка нет.
     */
    fun deleteShot(id: Long): Boolean {
        val shot = shots.firstOrNull { it.id == id } ?: return false
        finishDeletion()
        val trashed = saver.trash(shot.files, true)
        shots = shots.filter { it.id != id }
        if (review?.files?.isNotEmpty() == true && review?.files == shot.files) dismissReview()
        deletedShot = DeletedShot(shot, trashed)
        deletedShotJob = scope.launch {
            delay(ShotDeletion.UNDO_MILLIS)
            deletedShotJob = null
            finishDeletion()
        }
        journal("удалён снимок ${shot.title}: " + if (trashed) "в корзине" else "удалится, когда закроется «Вернуть»")
        return true
    }

    /** «Вернуть»: файлы — из корзины, снимок — в ленту туда, где был по времени. Отдаёт его id; null — возвращать нечего. */
    fun undoDeleteShot(): Long? {
        val deleted = deletedShot ?: return null
        deletedShotJob?.cancel()
        deletedShotJob = null
        deletedShot = null
        if (deleted.trashed && !saver.trash(deleted.shot.files, false)) {
            showStatus("Не удалось вернуть ${deleted.shot.title} из корзины")
            return null
        }
        val at = ShotDeletion.restoreIndex(deleted.shot.takenAt, shots.map { it.takenAt })
        shots = shots.toMutableList().also { it.add(at, deleted.shot) }
        journal("снимок ${deleted.shot.title} вернули")
        return deleted.shot.id
    }

    /** Плашка «Вернуть» закрылась — время вышло, новое удаление, программа ушла с экрана: что не в корзине — удалить. */
    private fun finishDeletion() {
        val deleted = deletedShot ?: return
        deletedShotJob?.cancel()
        deletedShotJob = null
        deletedShot = null
        if (!deleted.trashed) scope.launch(Dispatchers.IO) { saver.delete(deleted.shot.files) }
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
        val now = System.currentTimeMillis()
        review = value.copy(endsAt = now + seconds * 1000L, shownAt = now)
        reviewJob = scope.launch {
            delay(seconds * 1000L)
            review = null
        }
    }

    // MARK: - Пресеты

    /** Какой пресет сейчас стоит на камере: всё, что он применяет, равно текущему; таких несколько — первый. */
    val activePresetIndex: Int?
        get() = presets.indexOfFirst { PresetCatalog.matches(it, properties) }.takeIf { it >= 0 }

    /**
     * Текущий пресет — с ним сейчас работают: опора, если стоит на камере; иначе первый, что стоит; иначе опора, хоть на
     * камере её и поменяли. null — никакого.
     */
    val currentPresetIndex: Int?
        get() {
            val base = presetBase[presetMode]?.takeIf { it in presets.indices }
            if (base != null && PresetCatalog.matches(presets[base], properties)) return base
            return activePresetIndex ?: base
        }

    /** Что на камере сейчас не так, как в текущем пресете; пусто — не изменён или камера не на связи. */
    val currentPresetChanges: List<PresetChange>
        get() {
            if (phase != Phase.ready) return emptyList()
            val preset = currentPresetIndex?.let(presets::getOrNull) ?: return emptyList()
            return PresetCatalog.changes(preset, properties)
        }

    /** Текущий пресет изменён: на камере поменяли то, что он ставит, — есть что сохранить в него. */
    val currentPresetModified: Boolean get() = currentPresetChanges.isNotEmpty()

    /** Имя, которое получит новый пресет: «Пресет N». */
    val newPresetName: String get() = PresetCatalog.newName(presets)

    /** Место в общем списке пресета с номером index в списке режима; нет такого — null. */
    private fun slot(index: Int): Int? = PresetLibrary.indices(presetMode, library).getOrNull(index)

    /** Опора текущего пресета в режиме на экране; null — нет опоры. */
    private fun setBase(index: Int?) {
        presetBase = if (index == null) presetBase - presetMode else presetBase + (presetMode to index)
    }

    /**
     * Текущие настройки камеры — новым пресетом в конец списка режима; он же теперь текущий. Возвращает его номер (с 0);
     * камера не готова — null.
     */
    fun savePreset(name: String? = null): Int? {
        if (phase != Phase.ready) return null
        val title = name?.trim()?.takeIf { it.isNotEmpty() } ?: newPresetName
        library = library + PresetCatalog.capture(title, properties, presetMode)
        prefs.presets = library
        setBase(presets.lastIndex)
        showStatus("Новый пресет «$title»")
        return presets.lastIndex
    }

    /** «Сохранить в «Вечер»»: текущий пресет — как сейчас настроена камера (то же, что «Взять с камеры» для него). */
    fun savePresetChanges() {
        val index = currentPresetIndex ?: return
        val preset = presets.getOrNull(index) ?: return
        if (phase != Phase.ready) return
        replacePreset(index, PresetCatalog.recapture(preset, properties))
        setBase(index)
        showStatus("Сохранено в «${preset.name}»")
    }

    fun applyPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        val session = session ?: return
        if (phase != Phase.ready || isApplyingPreset) return
        if (PresetCatalog.effective(preset).isEmpty()) return showStatus("«${preset.name}» ничего не меняет")
        setBase(index)
        isApplyingPreset = true
        statusJob?.cancel()
        statusMessage = "Применяю «${preset.name}»…"
        val values = PresetCatalog.effective(preset)
        linkedSides.forEach { side ->
            scope.launch {
                side.apply(preset)?.skipped?.forEach { id ->
                    groupFailed(side, values[id]?.let { GroupFailures.label(id, it) } ?: id.shortTitle)
                }
            }
        }
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
        val preset = presets.getOrNull(index) ?: return
        if (trimmed.isEmpty()) return
        replacePreset(index, preset.copy(name = trimmed))
    }

    fun deletePreset(index: Int) {
        val slot = slot(index) ?: return
        library = library.toMutableList().also { it.removeAt(slot) }
        prefs.presets = library
        setBase(presetBase[presetMode]?.let { PresetLibrary.afterDelete(it, index) })
    }

    /** Начать быстрый просмотр: запомнить, как камера настроена сейчас, и поставить пресет. Отпустили кнопку — endPresetPreview. */
    fun beginPresetPreview(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        val session = session ?: return
        if (phase != Phase.ready || isApplyingPreset) return
        if (PresetCatalog.effective(preset).isEmpty()) return showStatus("«${preset.name}» ничего не меняет")
        previewRestore = PresetCatalog.restoreValues(preset, properties)
        previewingPreset = index
        isApplyingPreset = true
        // что идёт просмотр, говорит плашка над картинкой — строка под панелью её не повторяет
        statusJob?.cancel()
        statusMessage = null
        previewStopped = false
        previewJob = scope.launch { session.apply(preset) { !previewStopped } }
    }

    /** Кнопку отпустили: недописанное не пишем, всё, что пресет поменял, возвращаем как было. */
    fun endPresetPreview() {
        if (previewingPreset == null || previewReturning) return
        previewReturning = true
        statusMessage = "Возвращаю как было…"
        previewStopped = true
        val applying = previewJob
        val values = previewRestore
        val session = session
        scope.launch {
            applying?.join()
            val result = session?.restore(values)
            if (!previewReturning) return@launch
            endPreviewWithoutCamera()
            isApplyingPreset = false
            val skipped = result?.skipped.orEmpty()
            showStatus(if (skipped.isEmpty()) "Вернул как было" else "Вернул как было, кроме: " + skipped.joinToString(", ") { it.title })
        }
    }

    /** Просмотр кончился — или связь пропала, и вернуть уже не с чем. */
    private fun endPreviewWithoutCamera() {
        previewStopped = true
        previewJob = null
        previewRestore = emptyMap()
        previewingPreset = null
        previewReturning = false
    }

    /** Значение настройки в пресете — камера при этом не меняется. Включена ли настройка — не трогает. */
    fun setPresetValue(index: Int, id: SettingID, value: Long) {
        val preset = presets.getOrNull(index) ?: return
        if (id !in PresetCatalog.order) return
        replacePreset(index, preset.copy(values = preset.values + (id to value)))
    }

    /**
     * Менять ли пресету настройку. Выключенная помнит значение; включили, а значения не было, — берётся текущее с камеры
     * (у температуры — сохранённая камерой ручная, иначе 5600 K). Взять неоткуда — false, пресет как был.
     */
    fun setPresetIncluded(index: Int, id: SettingID, included: Boolean): Boolean {
        val preset = presets.getOrNull(index) ?: return false
        replacePreset(index, PresetCatalog.including(preset, id, included, properties) ?: return false)
        return true
    }

    /** Переставляет пресет с места from на место to списка режима (номера с 0); пресеты другого режима не трогает. */
    fun movePreset(from: Int, to: Int) {
        library = PresetLibrary.moved(library, presetMode, from, to) ?: return
        prefs.presets = library
        presetBase[presetMode]?.let { setBase(PresetLibrary.afterMove(it, from, to)) }
    }

    /** Копия пресета («Вечер — копия») — следом за ним, в том же режиме. Возвращает её номер. */
    fun duplicatePreset(index: Int): Int? {
        val preset = presets.getOrNull(index) ?: return null
        val slot = slot(index) ?: return null
        library = library.toMutableList().also { it.add(slot + 1, preset.copy(name = PresetCatalog.copyName(preset.name))) }
        prefs.presets = library
        presetBase[presetMode]?.let { setBase(PresetLibrary.afterInsert(it, index + 1)) }
        return index + 1
    }

    /**
     * «Взять с камеры»: всё, что камера сейчас даёт менять, — в пресет текущими значениями; выключенное остаётся выключенным.
     * Он же теперь текущий.
     */
    fun recapturePreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        if (phase != Phase.ready) return
        replacePreset(index, PresetCatalog.recapture(preset, properties))
        setBase(index)
        showStatus("«${preset.name}»: значения взяты с камеры")
    }

    /** Всё сохраняется сразу: кнопки «Сохранить» у пресетов нет. */
    private fun replacePreset(index: Int, preset: Preset) {
        val slot = slot(index) ?: return
        library = library.toMutableList().also { it[slot] = preset }
        prefs.presets = library
    }

    private fun showStatus(text: String) {
        statusMessage = text
        statusJob?.cancel()
        statusJob = scope.launch {
            delay(5000)
            statusMessage = null
        }
    }

    // MARK: - Камеры

    /** Список камер: текущая (или та, к которой подключаемся), видимые, знакомые невидимые. */
    val cameraEntries: List<CameraEntry>
        get() {
            // сопряжение с камерой из списка: она «сопрягаю…», а не текущая
            val target = pairingTarget?.takeIf { isPairing }
            // камера на кабеле не в PC Remote — не «на связи»: её можно выбрать снова
            val link = currentLink?.takeIf { phase != Phase.notPcRemote }
                ?: choice?.takeIf { transportKind == null && (target == null || it != CameraLink.WiFi(target)) }
            val current = link?.let {
                val live = it == currentLink
                CurrentCamera(it, if (live) currentCameraId else null, live && phase == Phase.ready, if (live) deviceInfo?.model else null)
            }
            val entries = cameraBook.entries(usbSightings + wifiSightings.values, current, System.currentTimeMillis(),
                sideCameras.filter { it.connected }.map { it.id }.toSet())
            return if (target == null) entries
            else entries.map { if (it.state != CameraState.current && it.address == target) it.copy(pairing = true) else it }
        }

    /** Имя текущей камеры для строки состояния: как назвали; незнакомая — модель. */
    val cameraName: String get() = currentCameraId?.let(cameraBook::find)?.name ?: deviceInfo?.model ?: "Камера"

    /**
     * Что ищем в сети, пока камеры нет, — для экрана ожидания, без модели: программа не только для одной камеры.
     * Сопряжённых нет — null.
     */
    val wifiSearchLabel: String?
        get() = when (cameraBook.cameras.count { it.paired }) {
            0 -> null
            1 -> "Ищу камеру в сети…"
            else -> "Ищу камеры в сети…"
        }

    /**
     * Камеру выбрали в списке: текущая — ничего; в ленте и на связи — главная сразу; несопряжённая — сопряжение с ней;
     * иначе программа подключается к выбранной (невидимую знакомую — один раз по последнему адресу), а текущая по Wi-Fi
     * уходит в ленту, по кабелю — отключается (во время записи — нельзя).
     */
    fun selectCamera(id: String) {
        sideCameras.firstOrNull { it.id == id && it.connected }?.let { return promote(it) }
        val entry = cameraEntries.firstOrNull { it.id == id } ?: return
        if (entry.state == CameraState.current) return
        // к ней как раз стучится лента — та попытка не нужна: подключаемся главной
        sideConnecting.remove(id)?.cancel()
        if (entry.state == CameraState.needsPairing) return pairCamera(id)
        val link = entry.link ?: entry.known?.address?.let { CameraLink.WiFi(it) } ?: return
        if (!freeForSwitch()) return
        isPairing = false
        pairingTarget = null
        wifiStatus = null
        choose(link, entry.name)
    }

    /** «Сопрячь» в строке камеры: сопряжение только с ней — она же и выбрана. */
    fun pairCamera(id: String) {
        val entry = cameraEntries.firstOrNull { it.id == id } ?: return
        val address = (entry.link as? CameraLink.WiFi)?.address ?: entry.known?.address ?: return
        if (!freeForSwitch()) return
        pairingTarget = address
        isPairing = true
        wifiStatus = "Камера найдена ($address) — подтвердите сопряжение на ней"
        choose(CameraLink.WiFi(address), entry.name)
    }

    fun renameCamera(id: String, name: String) = updateBook(cameraBook.renamed(id, name))

    /** «Забыть»: у текущей нельзя — она на связи. */
    fun forgetCamera(id: String) {
        if (id == currentCameraId) return
        updateBook(cameraBook.forgotten(id))
    }

    /** Список камер на экране: поиск в сети сейчас (экран зовёт сразу и раз в 10 с, пока список открыт). */
    fun searchCameras() {
        if (searchJob?.isActive == true || !foreground) return
        val network = wifiNetwork
        searchJob = scope.launch {
            noteSearch(withContext(Dispatchers.IO) { WiFiDiscovery.search(network = network) })
        }
    }

    /**
     * Перед переключением камеры. Главная по Wi-Fi на связи — её сеанс уходит в ленту, и во время записи тоже: камера
     * остаётся на связи. Иначе во время записи — нельзя (сеанс по кабелю закрылся бы); связь есть — закрываем.
     */
    private fun freeForSwitch(): Boolean {
        val released = releaseMainSession()
        if (released != null) {
            toStrip(released)
        } else {
            if (recordingStartedAt != null) {
                showStatus("Идёт запись — остановите её, чтобы переключить камеру")
                return false
            }
            closeConnectingSocket()
            if (transportKind != null || reconnect != null || device != null) teardown()
        }
        closeConnectingSocket()
        failures = 0
        retryAt = 0
        return true
    }

    /** Сеанс главной по Wi-Fi, готовый, — отдать (в ленту), не закрывая; главной больше нет. Иначе null. */
    private fun releaseMainSession(): OpenCamera? {
        val wifi = transport as? PtpIpTransport ?: return null
        val session = session ?: return null
        val owned = sessionScope ?: return null
        val id = currentCameraId ?: return null
        val address = (currentLink as? CameraLink.WiFi)?.address ?: return null
        if (transportKind != TransportKind.wifi || phase != Phase.ready || reconnect != null) return null
        val open = OpenCamera(id, address, wifi, session, owned, recordingStartedAt)
        teardown(keepOpen = true)
        return open
    }

    // MARK: - Лента камер

    /** Плитки ленты: главная и камеры ленты; ленты нет, пока камера ленты не появилась ни разу. */
    val stripVisible: Boolean get() = transportKind != null && sideCameras.isNotEmpty()

    /** Сеанс главной по Wi-Fi — в ленту: камера на связи, низкое превью, в книге — «в ленте». */
    private fun toStrip(open: OpenCamera) {
        val name = cameraBook.find(open.id)?.name ?: "Камера"
        val side = sideCameras.firstOrNull { it.id == open.id } ?: newSide(open.id, name)
        side.name = name
        side.attach(open)
        updateBook(cameraBook.withStrip(open.id, true))
        journal("«$name» — в ленту (${open.address})")
    }

    private fun newSide(id: String, name: String): SideCamera {
        val side = SideCamera(id, name, scope, liveViewWanted)
        side.onLost = { lost ->
            journal("«${lost.name}» в ленте — нет связи")
            sideTried[lost.id] = System.currentTimeMillis()
        }
        side.onWriteFailed = { failed, setting, value -> groupFailed(failed, GroupFailures.label(setting, value)) }
        sideCameras = sideCameras + side
        return side
    }

    private fun removeSide(side: SideCamera, close: Boolean) {
        if (close) side.close() else side.detach()
        sideCameras = sideCameras.filter { it !== side }
    }

    /**
     * Камера ленты — главная сразу: её открытый сеанс переходит на большой экран, прежняя главная — в ленту. Группа не
     * меняется: камера из неё ведёт группу, не из неё — группа ждёт.
     */
    private fun promote(side: SideCamera) {
        if (!side.connected || !freeForSwitch()) return
        // freeForSwitch могла убрать её (сеанс прежней главной лёг на её же место) — берём сеанс сейчас
        val open = side.detach() ?: return
        sideCameras = sideCameras.filter { it !== side }
        isPairing = false
        pairingTarget = null
        wifiStatus = null
        if (choice is CameraLink.WiFi) choice = null
        adoptSession(open)
    }

    /** «В ленту»: программа подключит камеру сама и будет держать на связи рядом с главной. */
    fun addToStrip(id: String) {
        updateBook(cameraBook.withStrip(id, true))
        sideTried.remove(id)
        tickStrip()
    }

    /** «Убрать из ленты»: сеанс с ней закрывается, сама программа к ней больше не стучится. */
    fun removeFromStrip(id: String) {
        updateBook(cameraBook.withStrip(id, false))
        sideConnecting.remove(id)?.cancel()
        sideCameras.firstOrNull { it.id == id }?.let { removeSide(it, close = true) }
        journal("«${cameraBook.find(id)?.name ?: id}» — убрана из ленты")
    }

    /** Плитку ленты id перетащили на место плитки onto: порядок ленты — в книге камер, запоминается сразу. */
    fun moveInStrip(id: String, onto: String) {
        val book = cameraBook.moved(id, onto)
        if (book == cameraBook) return
        updateBook(book)
        val after = book.cameras.indexOfFirst { it.id == id } > book.cameras.indexOfFirst { it.id == onto }
        journal("«${book.find(id)?.name ?: id}» — в ленте ${if (after) "после" else "перед"} «${book.find(onto)?.name ?: onto}»")
    }

    /** Кто станет главной, когда главной нет: верхняя камера ленты на связи. */
    private fun successor(): SideCamera? =
        CameraStrip.successor(cameraBook, sideCameras.filter { it.connected }.map { it.id })
            ?.let { id -> sideCameras.firstOrNull { it.id == id } }

    // MARK: - Группа камер

    /** Камеры группы в ленте на связи — когда главная тоже в группе: повторяют за ней настройки, пресеты и запись. */
    private val linkedSides: List<SideCamera>
        get() {
            val followers = cameraBook.followers(currentCameraId, sideCameras.map { it.id }).toSet()
            return sideCameras.filter { it.connected && it.id in followers }
        }

    /** Главная в группе: камеры группы повторяют за ней. */
    val mainInGroup: Boolean get() = currentCameraId?.let { cameraBook.find(it)?.linked } == true

    /** Цепочка на плитке ленты: связать камеру с главной (главная тоже входит в группу) или отвязать. */
    fun setLinked(id: String, linked: Boolean) {
        updateBook(cameraBook.withLinked(id, linked, currentCameraId))
        journal("«${cameraBook.find(id)?.name ?: id}» — ${if (linked) "связана с главной" else "отвязана от главной"}")
    }

    /** На связанной не прижилось — в строку состояния раз на серию: «Не удалось на «C»: ISO 51200». */
    private fun groupFailed(side: SideCamera, what: String) {
        groupFailures = groupFailures.plus(cameraBook.find(side.id)?.name ?: side.name, what)
        groupStatusJob?.cancel()
        groupStatusJob = scope.launch {
            delay(1000)
            groupFailures.text?.let { text ->
                Log.i(TAG, text)
                showStatus(text)
            }
            groupFailures = GroupFailures()
        }
    }

    /** Плитка ленты: касание — сделать главной. */
    fun makeMain(id: String) {
        sideCameras.firstOrNull { it.id == id }?.let { side ->
            if (side.connected) promote(side) else selectCamera(id)
        }
    }

    /** Все камеры ленты — закрыть (выход, минута в фоне, «Забыть сопряжение»). */
    private fun closeSides() {
        sideConnecting.values.forEach { it.cancel() }
        sideConnecting.clear()
        sideCameras.forEach { it.close() }
        sideCameras = emptyList()
    }

    /**
     * Раз в полсекунды, пока главная на связи и программа на экране: к камерам ленты без связи стучимся тихо, к каждой —
     * раз в 10 с; всего на связи не больше четырёх.
     */
    private fun tickStrip() {
        // Wi-Fi выключен в настройках — к камерам ленты не стучимся
        if (!foreground || phase != Phase.ready || transportKind == null || !prefs.wifiEnabled) return
        val busy = sideCameras.filter { it.connected }.map { it.id }.toSet() + sideConnecting.keys
        val busyAddresses = setOfNotNull((currentLink as? CameraLink.WiFi)?.address, mainAttempt, reconnect?.address,
            (choice as? CameraLink.WiFi)?.address, pairingTarget)
        val now = System.currentTimeMillis()
        val due = CameraStrip.due(cameraBook, currentCameraId, busy, busyAddresses, sideTried, wifiSightings.values.toList(), now)
        for ((camera, address) in due) {
            sideTried[camera.id] = now
            sideConnecting[camera.id] = scope.launch { connectSide(camera, address) }
        }
    }

    /**
     * Одна тихая попытка к камере ленты: соединение, рукопожатие, готовность — и в ленту, с низким превью. Отказ
     * сопряжение не снимает и строк состояния не пишет.
     */
    private suspend fun connectSide(camera: KnownCamera, address: WiFiAddress) {
        val me = identity ?: PtpIpIdentity.load(context).also { identity = it }
        val network = wifiNetwork
        val linkCheck = debugLinkCheckUrl
        val created = AtomicReference<PtpIpTransport?>()
        var open: OpenCamera? = null
        try {
            val (transport, _) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(address.host, me, network, connectTimeoutMs = 3000, initTimeoutMs = 5000, linkCheckUrl = linkCheck,
                    port = address.port).also { created.set(it.first) }
            }
            created.set(null)
            val owned = sessionScopeOf()
            val session = CameraSession(transport)
            open = OpenCamera(camera.id, address, transport, session, owned)
            session.start(owned)
            session.waitUntilReady()
            // пока подключались: камеру убрали из ленты, она стала главной, программа ушла с экрана
            val known = cameraBook.find(camera.id)
            if (known?.inStrip != true || camera.id == currentCameraId || !foreground) {
                open.close()
                return
            }
            // по этому адресу оказалась другая камера — не наша
            val print = session.deviceInfo?.serial?.trim()?.takeIf { it.isNotEmpty() }?.let(CameraBook::serialFingerprint)
            if (print != null && !known.knows(print)) {
                journal("лента: по адресу $address не «${known.name}» — закрываю")
                open.close()
                return
            }
            val side = sideCameras.firstOrNull { it.id == camera.id } ?: newSide(camera.id, known.name)
            side.name = known.name
            side.attach(open)
            open = null
            journal("«${known.name}» — в ленте ($address)")
        } catch (e: CancellationException) {
            created.getAndSet(null)?.close()
            open?.close(abort = true)
            throw e
        } catch (e: Exception) {
            created.getAndSet(null)?.close()
            open?.close(abort = true)
            Log.d("ZVStrip", "${camera.name} ($address): нет связи — ${e.message ?: e.javaClass.simpleName}")
        } finally {
            sideConnecting.remove(camera.id)
        }
    }

    private fun choose(link: CameraLink, name: String) {
        journal("выбрана камера «$name» (${(link as? CameraLink.WiFi)?.address ?: "по кабелю"})")
        choice = link
        choiceName = name
        message = "Подключаю «$name»…"
        showStatus("Подключаю «$name»…")
        when (link) {
            is CameraLink.Usb -> findCamera()
            is CameraLink.WiFi -> wifiWake.trySend(Unit)
        }
    }

    /** Ответы на поиск — в список: камера по Wi-Fi видна 30 с после последнего ответа. */
    private fun noteSearch(found: List<Ssdp.Response>) {
        if (found.isEmpty()) return
        val now = System.currentTimeMillis()
        wifiSightings = wifiSightings + found.associate { r ->
            val address = WiFiAddress(r.host, r.port)
            address to CameraSighting(CameraLink.WiFi(address), null, CameraBook.usnFingerprint(r.usn), now)
        }
    }

    /** Раз в полсекунды: кто на шине USB и кто по Wi-Fi давно не отвечал на поиск. */
    private fun tickCameras() {
        val now = System.currentTimeMillis()
        val usb = usbManager.deviceList.values.filter(::isSonyCamera)
            .map { CameraSighting(CameraLink.Usb(it.deviceName), it.productName, usbFingerprint(it), now) }
        if (usb.map { it.link to it.fingerprint } != usbSightings.map { it.link to it.fingerprint }) usbSightings = usb
        if (wifiSightings.values.any { now - it.seenAt >= CameraBook.WIFI_VISIBLE_MS }) {
            wifiSightings = wifiSightings.filterValues { now - it.seenAt < CameraBook.WIFI_VISIBLE_MS }
        }
    }

    /** Отпечаток серийного номера USB — только с доступом к камере: без него Android номер не отдаёт. */
    private fun usbFingerprint(camera: UsbDevice): String? =
        if (!usbManager.hasPermission(camera)) null
        else runCatching { camera.serialNumber }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let(CameraBook::serialFingerprint)

    /** Камера подключилась (пришёл DeviceInfo): запомнить её, адрес и сопряжение, сделать предпочтённой. */
    private fun noteCameraConnected(info: DeviceInfo?) {
        val link = currentLink ?: return
        val prints = when (link) {
            is CameraLink.Usb -> listOfNotNull(device?.let(::usbFingerprint))
            is CameraLink.WiFi -> listOfNotNull(wifiSightings[link.address]?.fingerprint)
        }
        val (book, id) = cameraBook.connected(info?.serial, info?.model ?: "Камера", link, prints, System.currentTimeMillis())
        updateBook(book)
        currentCameraId = id
        // она же была в ленте (без связи или вторым сеансом) — теперь главная
        sideCameras.firstOrNull { it.id == id }?.let { removeSide(it, close = true) }
        sideConnecting.remove(id)?.cancel()
    }

    private fun updateBook(book: CameraBook) {
        if (book == cameraBook) return
        cameraBook = book
        prefs.cameras = CameraBook.toJson(book.cameras)
        prefs.preferredCamera = book.preferred
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
        }.onFailure { journal("не слежу за сетью Wi-Fi: ${it.message}") }
        // подключение камеры отслеживаем опросом: широковещательное ATTACHED приходит не всем
        scope.launch {
            while (isActive) {
                // по Wi-Fi идёт сеанс — кабель не трогаем: камера в режиме Wi-Fi на кабеле (например, на зарядке) видна
                // как накопитель и PC Remote по USB не даст; кабельную камеру подхватим, когда Wi-Fi закончится
                // камеру по Wi-Fi выбрали в списке или сопрягают — кабель не перебивает
                if (foreground && device == null && transportKind == null && System.currentTimeMillis() >= retryAt &&
                    choice !is CameraLink.WiFi && !isPairing) findCamera()
                tickCameras()
                tickStrip()
                tickFrameStats()
                tickShownStats()
                tickTabletBattery()
                tickQuality()
                tickSummary()
                // камера так и не ответила на нажатие записи — отказ через 6 с
                recordingOutcome(recordingToggle.tick(System.currentTimeMillis()))
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
        closeSides()
        teardown()
    }

    /** Программа снова на экране: live view продолжается, а закрытый в фоне сеанс открывается заново. */
    fun onForeground() {
        if (!foreground) journal("программа снова на экране")
        foreground = true
        backgroundJob?.cancel()
        quietFrom = System.currentTimeMillis()
        liveViewWanted.value = true
        if (device == null && transportKind == null && System.currentTimeMillis() >= retryAt && choice !is CameraLink.WiFi && !isPairing) {
            findCamera()
        }
        wifiWake.trySend(Unit)
    }

    /** Программа ушла с экрана: live view на паузе (сеанс держат пробы), через минуту — обычное закрытие. */
    fun onBackground() {
        journal("программа ушла с экрана — live view на паузе")
        finishDeletion()
        foreground = false
        liveViewWanted.value = false
        backgroundJob?.cancel()
        backgroundJob = scope.launch {
            delay(BACKGROUND_CLOSE_MS)
            if (session != null || reconnect != null || sideCameras.isNotEmpty()) {
                journal("минута в фоне — закрываю сеанс, на экране подключусь снова")
                closeSides()
                teardown()
                message = null
            }
        }
    }

    /** Отладка (только debug-сборка): прокси стенда или виртуальная камера между программой и камерой. */
    fun setDebugTarget(host: WiFiAddress?, linkCheckUrl: String?) {
        debugHost = host
        debugLinkCheckUrl = linkCheckUrl
        journal("отладка: камера — ${host ?: "как обычно"}, проверка связи — ${linkCheckUrl ?: "ping"}")
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
        journal("сеть Wi-Fi появилась")
        val wifi = transport as? PtpIpTransport
        val bound = wifi?.network
        if (wifi != null && bound != null && bound != network && bound !in wifiNetworks) {
            // сокеты привязаны к пропавшей сети — им уже не ожить: сбрасываем, сеанс восстановится по новой
            journal("сеть Wi-Fi сменилась — переподключаюсь по новой")
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
        journal("сеть Wi-Fi пропала")
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

    /** Камера на кабеле: выбранная в списке, иначе предпочтённая (если её узнать по серийному номеру USB), иначе первая. */
    private fun findCamera() {
        val cameras = usbManager.deviceList.values.filter(::isSonyCamera)
        val chosen = (choice as? CameraLink.Usb)?.let { c -> cameras.firstOrNull { it.deviceName == c.device } }
        // выбранной уже нет на шине — дальше как обычно
        if (choice is CameraLink.Usb && chosen == null) choice = null
        val preferred = cameraBook.preferred?.let(cameraBook::find)?.let { known ->
            cameras.firstOrNull { camera -> usbFingerprint(camera)?.let(known::knows) == true }
        }
        val camera = chosen ?: preferred ?: cameras.firstOrNull() ?: return
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
                        journal("USB: доступ к камере не разрешили")
                        // выбирали её в списке — выбора больше нет: «подключаю…» не висит
                        if (choice is CameraLink.Usb) choice = null
                        device = null
                        phase = Phase.searching
                        retryAt = Long.MAX_VALUE
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    // отключили не ту камеру, с которой работаем (или связь идёт по Wi-Fi) — ничего не трогаем
                    val detached = deviceFrom(intent) ?: return
                    if (device != null && detached.deviceName == device?.deviceName) {
                        journal("USB: камеру отключили")
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
        currentLink = CameraLink.Usb(camera.deviceName)
        currentCameraId = null
        if (choice is CameraLink.Usb) choice = null
        journal("USB: подключаюсь к камере…")
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

    /** Записи настроек в камеру сеанса: одна на свойство за раз, ждём только последнее значение. */
    private fun writer(session: CameraSession) = WriteCoalescer(scope) { code, value ->
        val result = runCatching { session.set(code, value) }
        Log.d(TAG, "запись 0x${code.toString(16)} = ${value.toString(16)}: ${result.getOrNull() ?: result.exceptionOrNull()}")
        // ждём только последнее запрошенное значение; промежуточные пропускаем
        if (targets[code] == value) clearTarget(code)
    }

    /** Свои корутины у каждого открытого сеанса: закрыли сеанс — их нет, отдали в ленту — живут дальше. */
    private fun sessionScopeOf() = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))

    /**
     * Камера из ленты — главная: её открытый сеанс, без переподключения; таймер записи — её же; качество превью — снова
     * по каналу (с низкого — сначала высокое).
     */
    private fun adoptSession(open: OpenCamera) {
        phase = Phase.connecting
        transportKind = TransportKind.wifi
        isPairing = false
        wifiStatus = null
        message = null
        val transport = open.transport
        val session = open.session
        this.transport = transport
        this.session = session
        sessionScope = open.scope
        sessionFrames = 0
        qualityPolicy = LiveViewQualityPolicy(now = System.currentTimeMillis())
        startSessionStats()
        holdWifiLock(true)
        wifiDirect = PtpIp.isCameraAccessPoint(open.address.host)
        currentLink = CameraLink.WiFi(open.address)
        currentCameraId = open.id
        deviceInfo = session.deviceInfo
        recordingStartedAt = open.recordingSince
        coalescer = writer(session)
        noteCameraConnected(session.deviceInfo)
        startStreams(session) { if (this.transport === transport) wifiSessionStopped(null) }
        phase = Phase.ready
        failures = 0
        wifiPolicy.connected()
        wifiPolicy.sessionReady()
        startQuality(session)
        journal("«$cameraName» из ленты — главная, без переподключения (${open.address})")
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
        // новый сеанс, а не тихое восстановление прежнего — счёт для панели отладки с начала
        if (reconnect == null) startSessionStats()
        if (kind == TransportKind.wifi) holdWifiLock(true)
        coalescer = writer(session)
        // события камеры — в своём scope сеанса: сеанс можно отдать в ленту, не закрыв
        val owned = sessionScopeOf()
        sessionScope = owned
        // об одном обрыве могут сообщить и рукопожатие, и поток свойств, а сообщение — запоздать: слушаем только свой
        // транспорт, иначе поздний вызов убил бы уже восстановленный сеанс
        val failed: (Exception) -> Unit = { e -> if (this.transport === transport) onFailure(e) }
        val ended: () -> Unit = { if (this.transport === transport) onEnded() }
        jobs += scope.launch {
            try {
                session.start(owned)
                deviceInfo = session.deviceInfo
                noteCameraConnected(session.deviceInfo)
                phase = Phase.settling
                journal("рукопожатие прошло: ${session.deviceInfo?.model ?: "камера"}, прошивка ${session.deviceInfo?.firmware ?: "?"} — " +
                    "пошёл live view")
                startStreams(session, ended)
                session.waitUntilReady()
                phase = Phase.ready
                journal("сеанс готов: настройки принимаются")
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

    /**
     * «Сопрячь новую камеру…»: программа ищет камеру и стучится, пока на камере включено Pairing, — сначала к
     * несопряжённым. Главная по Wi-Fi уходит в ленту, по кабелю — отключается (freeForSwitch); во время записи на
     * кабеле — нельзя.
     */
    fun startPairing() {
        if (!freeForSwitch()) return
        pairingTarget = null
        if (choice is CameraLink.WiFi) choice = null
        isPairing = true
        wifiStatus = "Ищу камеру в сети…"
        wifiWake.trySend(Unit)
    }

    fun cancelPairing() {
        isPairing = false
        // «Сопрячь» у одной камеры — это и её выбор: отменили — дальше как обычно
        pairingTarget?.let { if (choice == CameraLink.WiFi(it)) choice = null }
        pairingTarget = null
        wifiStatus = null
        // идущая попытка ждёт подтверждения на камере до минуты — обрываем её
        closeConnectingSocket()
    }

    /**
     * «Забыть сопряжение»: новый GUID — ни одна камера программу больше не знает и сама подключаться не будет, пока не
     * сопрячь заново; имена камер в списке остаются.
     */
    fun forgetPairing() {
        prefs.wifiHost = null
        prefs.wifiCameraName = null
        identity = PtpIpIdentity.reset(context)
        updateBook(cameraBook.unpairedAll())
        wifiPolicy.connected()
        wifiPolicy.sessionReady()
        wifiStatus = null
        if (choice is CameraLink.WiFi) choice = null
        pairingTarget = null
        closeConnectingSocket()
        closeSides()
        if (transportKind == TransportKind.wifi) teardown()
    }

    private fun closeConnectingSocket() {
        connectingSocket?.let { runCatching { it.close() } }
    }

    /** Нужна ли ещё связь по Wi-Fi: сеанса нет (камера на кабеле не в PC Remote — тоже), Wi-Fi включён или идёт сопряжение. */
    private val wifiWanted: Boolean
        get() {
            // камеру по Wi-Fi выбрали в списке или сопрягают — нужна, даже когда на кабеле есть другая
            if (choice is CameraLink.WiFi || isPairing) {
                return transportKind == null || (phase == Phase.notPcRemote && transportKind == TransportKind.usb)
            }
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

    /**
     * Раз в секунду (и сразу, когда появилась сеть или выбрали камеру): если связи нет — ищем камеру в сети и подключаемся.
     * Выбранная в списке — только она; иначе предпочтённая по последнему адресу, потом найденные сопряжённые.
     */
    private suspend fun wifiTick() {
        // в фоне не подключаемся: сеанс, закрытый через минуту в фоне, откроется, когда программа снова на экране
        if (!foreground) return
        val chosen = (choice as? CameraLink.WiFi)?.address
        if (!wifiWanted || wifiBusy) return
        if (chosen == null && !isPairing) {
            successor()?.let { return promote(it) }
        }
        // выбранную в списке камеру, как и сопряжение, пауза после обрыва не держит
        if (chosen == null && !wifiPolicy.mayAttempt(prefs.wifiEnabled, isPairing, System.currentTimeMillis())) return
        wifiBusy = true
        try {
            if (chosen != null) {
                tryChosen(chosen)
                return
            }
            // отладка: прокси стенда или виртуальная камера — только этот адрес, без памяти и поиска
            debugHost?.let { address ->
                // как в прежних версиях: сопряжена хоть одна камера — стучимся (виртуальная примет любой GUID)
                if (wifiPolicy.maySendInit(cameraBook.isPaired(address) || cameraBook.cameras.any { it.paired }, isPairing)) {
                    tryWiFi(address, discovered = false, remember = false)
                } else if (wifiStatus == null) {
                    wifiStatus = "Отладка: камера за $address — «Сопрячь» у неё в списке камер, и она будет подключаться по Wi-Fi"
                }
                return
            }
            // сначала — предпочтённая камера по последнему адресу: так подключение после перерыва занимает секунду, а не поиск
            val last = if (isPairing) null else cameraBook.firstAddress()
            var lastFailed = false
            if (last != null) {
                when (tryWiFi(last, discovered = false)) {
                    WiFiAttempt.connected, WiFiAttempt.dropped -> return
                    WiFiAttempt.rejected -> Unit
                    WiFiAttempt.failed -> lastFailed = true
                }
            }
            // пока стучались, камеру выбрали в списке — ею займётся следующий такт
            if (!wifiWanted || choice is CameraLink.WiFi) return
            if (isPairing && wifiStatus == null) wifiStatus = "Ищу камеру в сети…"
            val network = wifiNetwork
            val responses = withContext(Dispatchers.IO) { WiFiDiscovery.search(network = network) }
            noteSearch(responses)
            val found = responses.map { WiFiAddress(it.host, it.port) to CameraBook.usnFingerprint(it.usn) }
            val addresses = found.map { it.first }
            if (addresses != lastFound) {
                journal(if (addresses.isEmpty()) "поиск в сети: камер не видно" else "поиск в сети: камера на ${addresses.joinToString(", ")}")
                lastFound = addresses
            }
            if (!wifiWanted || choice is CameraLink.WiFi) return
            if (found.isEmpty()) {
                wifiPolicy.nothingFound()
                wifiStatus = if (isPairing) "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi" else null
                return
            }
            // сопряжение — сначала несопряжённые (новая камера), потом остальные, как прежде; иначе — только сопряжённые:
            // камера в режиме Pairing показала бы чужой запрос
            val candidates = if (isPairing) {
                found.sortedBy { (address, print) -> cameraBook.isPaired(address, print) }.map { it.first }
            } else {
                found.filter { (address, print) -> cameraBook.isPaired(address, print) }.map { it.first }
            }
            if (candidates.isEmpty()) {
                if (wifiStatus == null) {
                    wifiStatus = "В сети есть камера (${addresses[0]}) — «Сопрячь» у неё в списке камер, и она будет подключаться по Wi-Fi"
                }
                return
            }
            // камера видна по последнему адресу, но не ответила — это неудача к найденной камере
            if (last != null && lastFailed && last in addresses) {
                wifiPolicy.attemptFailed(discovered = true)
                showStaleHintIfNeeded(last)
            }
            for (address in candidates) {
                if (address == last) continue
                if (!wifiWanted || choice is CameraLink.WiFi) return
                when (tryWiFi(address, discovered = true)) {
                    WiFiAttempt.connected, WiFiAttempt.dropped -> return
                    WiFiAttempt.rejected, WiFiAttempt.failed -> Unit
                }
            }
        } finally {
            wifiBusy = false
        }
    }

    /**
     * Одна попытка к камере, выбранной в списке. Ждём подтверждения сопряжения на ней — пробуем на следующем такте;
     * иначе выбора больше нет, дальше как обычно.
     */
    private suspend fun tryChosen(address: WiFiAddress) {
        when (tryWiFi(address, discovered = false)) {
            WiFiAttempt.connected, WiFiAttempt.dropped -> return
            WiFiAttempt.rejected, WiFiAttempt.failed -> Unit
        }
        if (isPairing && pairingTarget == address) return
        if (choice != CameraLink.WiFi(address)) return
        choice = null
        val name = choiceName ?: address.toString()
        journal("выбранная камера «$name» не подключилась — дальше как обычно")
        message = "«$name» не отвечает"
    }

    private fun showStaleHintIfNeeded(address: WiFiAddress) {
        if (!wifiPolicy.showsStaleHint || isPairing) return
        wifiStatus = "Камера найдена ($address), но пока не отвечает — видимо, держит прошлое подключение. " +
            "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
    }

    /**
     * Одна попытка подключиться к камере по адресу. discovered — адрес найден поиском сейчас, а не взят из памяти;
     * remember — запомнить адрес для прежних версий (адрес прокси отладки не запоминаем).
     */
    private suspend fun tryWiFi(address: WiFiAddress, discovered: Boolean, remember: Boolean = true): WiFiAttempt {
        // эта камера уже на связи в ленте — второе соединение не нужно: она главная сразу
        sideCameras.firstOrNull { it.connected && it.address == address }?.let { side ->
            promote(side)
            return WiFiAttempt.connected
        }
        mainAttempt = address
        try {
            return tryWiFiOnce(address, discovered, remember)
        } finally {
            mainAttempt = null
        }
    }

    private suspend fun tryWiFiOnce(address: WiFiAddress, discovered: Boolean, remember: Boolean): WiFiAttempt {
        val pairing = isPairing
        if (pairing) wifiStatus = "Камера найдена ($address) — подтвердите сопряжение на ней"
        val me = identity ?: PtpIpIdentity.load(context).also { identity = it }
        // если корутину отменят, пока ждём камеру, готовое соединение иначе потерялось бы незакрытым
        val created = AtomicReference<PtpIpTransport?>()
        val network = wifiNetwork
        val linkCheck = debugLinkCheckUrl
        val started = System.currentTimeMillis()
        journal("подключаюсь к $address" + (if (pairing) " (сопряжение)" else "") + "…")
        return try {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            val (transport, name) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(address.host, me, network, initTimeoutMs = if (pairing) 60_000 else 5000, linkCheckUrl = linkCheck,
                    port = address.port, onSocket = { connectingSocket = it }).also { created.set(it.first) }
            }
            created.set(null)
            // камера приняла программу — значит, сопряжены, даже если связь уже не нужна. Прежние версии помнили одну камеру
            // без порта: её адрес пишем и для них
            if (remember && address.port == PtpIp.PORT) prefs.wifiHost = address.host
            prefs.wifiCameraName = name
            // пока ждали, связь стала не нужна: подключили кабель, выключили Wi-Fi, отменили сопряжение, выбрали в списке
            // другую камеру
            val other = (choice as? CameraLink.WiFi)?.let { it.address != address } ?: false
            if (!wifiWanted || pairing != isPairing || other) {
                transport.close()
                return WiFiAttempt.dropped
            }
            // камера на кабеле не в PC Remote — та попытка не нужна, закрываем её до сеанса по Wi-Fi
            if (phase == Phase.notPcRemote) teardown()
            wifiDirect = PtpIp.isCameraAccessPoint(address.host)
            wifiPolicy.connected()
            message = null
            journal("подключился к $address ($name) за ${System.currentTimeMillis() - started} мс")
            currentLink = CameraLink.WiFi(address)
            currentCameraId = null
            // выбранная камера на связи — дальше её ведёт сеанс, а не выбор
            if (choice == CameraLink.WiFi(address)) choice = null
            pairingTarget = null
            runSession(transport, TransportKind.wifi, onFailure = { wifiSessionStopped(it) }, onEnded = { wifiSessionStopped(null) })
            WiFiAttempt.connected
        } catch (e: PtpIpRejected) {
            if (!wifiWanted) return WiFiAttempt.dropped
            if (!WiFiPolicy.forgetsPairing(e.reason)) {
                // камера занята (например, ещё держит прошлое подключение) или отказала без причины — это проходит,
                // сопряжение не трогаем
                journal("$address: камера отказала (Init Fail ${e.reason}) — занята, повторю")
                wifiPolicy.attemptFailed(discovered)
                showStaleHintIfNeeded(address)
                return WiFiAttempt.failed
            }
            journal("$address: камера отказала (Init Fail ${e.reason})" + if (pairing) "" else " — программа с ней не сопряжена")
            if (pairing) {
                wifiStatus = "Камера найдена ($address), но не в режиме Pairing — включите его на камере"
            } else {
                // камера нас больше не знает (её сбросили или сопрягали заново) — перестаём стучаться
                forgetPairingWith(address)
                wifiStatus = "В сети есть камера ($address), но планшет с ней не сопряжён — «Сопрячь» у неё в списке камер"
            }
            WiFiAttempt.rejected
        } catch (e: CancellationException) {
            created.getAndSet(null)?.close()
            throw e
        } catch (e: Exception) {
            // отменили или стало не нужно — это не неудача
            if (!wifiWanted || pairing != isPairing) return WiFiAttempt.dropped
            journal("$address: не подключилось — ${e.message ?: e.javaClass.simpleName}")
            wifiPolicy.attemptFailed(discovered)
            showStaleHintIfNeeded(address)
            WiFiAttempt.failed
        } finally {
            connectingSocket = null
        }
    }

    /** Камера по этому адресу ответила «не знаю программу»: она больше не сопряжена. */
    private fun forgetPairingWith(address: WiFiAddress) {
        updateBook(cameraBook.pairingLost(address, wifiSightings[address]?.fingerprint))
        if (prefs.wifiHost == address.host) prefs.wifiCameraName = null
    }

    /**
     * Сеанс по Wi-Fi оборвался: повтор с растущей паузой; камеру на кабеле, если она есть, подхватит опрос USB. В ленте
     * есть камеры на связи — верхняя из них главная сразу; пропавшая, если была в ленте, остаётся там «без связи».
     */
    private fun wifiLost() {
        val lostId = currentCameraId
        val lastFrame = frame
        teardown()
        val successor = successor()
        if (lostId != null && cameraBook.find(lostId)?.inStrip == true && successor != null) {
            val name = cameraBook.find(lostId)?.name ?: "Камера"
            (sideCameras.firstOrNull { it.id == lostId } ?: newSide(lostId, name)).showLast(lastFrame)
            sideTried[lostId] = System.currentTimeMillis()
        }
        if (successor != null) {
            journal("главная пропала — главной становится «${successor.name}» из ленты")
            promote(successor)
            return
        }
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
        lastLoss = (error?.message ?: loss.title) to System.currentTimeMillis()
        when {
            reconnect != null -> {
                journal("переподключение: сеанс не поднялся — ${error?.message ?: loss.title}")
                stopSession(abort = true)
                phase = Phase.connecting
                continueReconnect()
            }
            wifi != null && sessionFrames > 0 && loss.reconnects && prefs.wifiEnabled -> beginReconnect(WiFiAddress(wifi.host, wifi.port), loss)
            else -> {
                journal("сеанс по Wi-Fi кончился: ${error?.message ?: loss.title} — жду камеру с поиском в сети")
                wifiLost()
            }
        }
    }

    /**
     * Тихое восстановление: кадр, свойства, таймер записи и лента остаются на экране, кнопки неактивны (фаза
     * connecting). Старые соединения — RST (камера освобождает сеанс сразу), попытки — по тому же адресу, без поиска:
     * сразу, затем через 0,5, 1, 2, 3, 5, 5… с, с короткими тайм-аутами.
     */
    private fun beginReconnect(address: WiFiAddress, cause: LinkLoss) {
        stopSession(abort = true)
        phase = Phase.connecting
        isReconnecting = true
        val episode = Reconnect(address, cause, System.currentTimeMillis())
        reconnect = episode
        reconnects++
        while (reconnectWake.tryReceive().isSuccess) Unit
        journal("сеанс по Wi-Fi оборвался (${cause.title}) — переподключаюсь к $address")
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
        journal("подключаюсь к ${episode.address} (восстановление, попытка ${episode.attempts})…")
        try {
            val (transport, _) = withContext(Dispatchers.IO) {
                PtpIpTransport.connect(episode.address.host, me, network, connectTimeoutMs = 2000, initTimeoutMs = 3000,
                    linkCheckUrl = linkCheck, port = episode.address.port, onSocket = { connectingSocket = it }).also { created.set(it.first) }
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
                forgetPairingWith(episode.address)
                wifiStatus = "В сети есть камера (${episode.address}), но планшет с ней не сопряжён — «Сопрячь» у неё в списке камер"
            } else {
                journal("${episode.address}: камера отказала (Init Fail ${e.reason}) — занята, повторю")
                continueReconnect()
            }
        } catch (e: Exception) {
            journal("${episode.address}: не подключилось — ${e.message ?: e.javaClass.simpleName}")
            continueReconnect()
        } finally {
            connectingSocket = null
        }
    }

    /** Тихо не вышло — обычное ожидание: экран «Подключите камеру», поиск в сети, паузы 2, 4, 8, 10 с. */
    private fun reconnectFailed(episode: Reconnect, why: String) {
        if (reconnect !== episode) return
        journal("восстановить сеанс не вышло: $why, попыток ${episode.attempts} — жду камеру как обычно")
        wifiLost()
    }

    /** Сеанс восстановлен: пришёл первый кадр нового сеанса (или он готов, а кадров нет). */
    private fun finishReconnect(now: Long, firstFrame: Boolean) {
        val episode = reconnect ?: return
        reconnect = null
        isReconnecting = false
        lastRecoveryMs = now - episode.startedAt
        journal("сеанс восстановлен за %d мс %s: попыток %d, причина — %s".format(now - episode.startedAt,
            if (firstFrame) "до первого кадра" else "до готовности (кадров ещё нет)", episode.attempts, episode.cause.title))
    }

    /** Повтор через 2, 4, 8, 10, 10… с, пока камера подключена. */
    private fun retryLater(text: String) {
        lastLoss = text to System.currentTimeMillis()
        teardown()
        failures++
        val seconds = minOf(1L shl failures, 10L)
        message = "$text. Повтор через $seconds с…"
        retryAt = System.currentTimeMillis() + seconds * 1000
    }

    private fun startStreams(session: CameraSession, onEnded: () -> Unit) {
        // какие команды знает камера — из её ответа на рукопожатие
        controlCodes = session.controlCodes
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
            // свои Bitmap и свой проход картинки у каждого сеанса: прошлый, может быть, ещё дорисовывает свой последний кадр
            val pool = FramePool()
            val picture = PicturePath()
            val surface = BitmapSurface()
            val watch = LookWatch()
            val pictureLevels = IntArray(256)
            var sampler: HistogramSampler? = null
            var assistSampler: AssistSampler? = null
            var reportedAt = 0L
            // сколько стоит проход (чтение кадра, зеркало с LUT, запись) с прошлой строки в logcat
            var passNanos = 0L
            var passWorst = 0L
            var passFrames = 0
            try {
                for (sample in frames) {
                    val bitmap = pool.decode(sample.data, sample.jpegOffset, sample.jpegLength) ?: continue
                    // одни настройки на весь кадр: картинка, уровни, белый и помощь видят одно и то же, и подсветка не
                    // отстаёт от картинки
                    val tools = assistForFrames
                    val waveMode = waveformForFrames
                    val video = assistVideo
                    val profile = cameraProfile
                    // итоговый куб, а не настройка: «Авто» у камеры без профиля лога куба не даёт, и тогда кадр не трогают
                    val lut = luts.cube(tools, profile)
                    if (watch.changed(lut, tools.orientation)) {
                        // смена куба или поворота — строка сразу, а не через две секунды: по ней меряется «LUT сменился за ≤ 1 с»
                        Log.i("ZVAssist", watch.accept(lut, luts.appliedName(tools, profile, lut != null), tools.orientation))
                    }
                    val analyzing = tools.needsPixels || waveMode != null
                    val now = System.currentTimeMillis()
                    // строка в logcat раз в 2 с: в отладочной сборке всегда (проверка «с LUT и без» читает её и при выключенных
                    // инструментах), в обычной — только пока что-то из монитора включено
                    val report = now - reportedAt >= 2000 && (BuildConfig.DEBUG || tools.isActive || histogramOn || waveMode != null)
                    val helpOn = histogramOn || analyzing || report
                    // зеркало, переворот и LUT — один раз, сразу после декодирования, в тот же Bitmap, что пойдёт на экран
                    surface.bitmap = bitmap
                    val passStarted = System.nanoTime()
                    val step = picture.apply(surface, tools.orientation, lut, PictureHelp.needsSignal(lut, tools.lutScopes, helpOn))
                    if (step == PicturePath.Step.Done) {
                        val spent = System.nanoTime() - passStarted
                        passNanos += spent
                        if (spent > passWorst) passWorst = spent
                        passFrames += 1
                    }
                    if (step == PicturePath.Step.Stopped) {
                        // проход закрыт или споткнулся о Bitmap: кадры больше не показываем, но и не падаем, и не крутимся.
                        // В журнал (его видно в панели отладки) — чтобы по отчёту «картинка встала» была ясна причина
                        Log.w(TAG, "проход картинки остановлен — кадры больше не идут", picture.failure)
                        journal("проход картинки остановлен, кадры больше не идут: ${picture.stopReason}")
                        frames.cancel()
                        break
                    }
                    // точки для помощи: картинка с LUT или повёрнутый сигнал, уже разложенные проходом; не тронули — сам кадр
                    val pixels = if (step == PicturePath.Step.Done) picture.help else null
                    val width = bitmap.width
                    val height = bitmap.height
                    // уровни — по той же картинке, что у помощи: белый уровень у всех один
                    val levels = when {
                        !helpOn -> null
                        pixels != null -> LumaHistogram.sampledLevels(pixels, width, height, pictureLevels)
                        // не тронутый кадр — уменьшенной копией, как раньше и как на Mac
                        else -> (sampler ?: HistogramSampler().also { sampler = it }).levels(bitmap)
                    }
                    val white = if (levels != null) PictureHelp.whiteLevel(levels, video, lut != null, tools.lutScopes) else 255
                    val analysis = if (analyzing) {
                        val assistant = assistSampler ?: AssistSampler().also { assistSampler = it }
                        assistant.analyze(pixels ?: assistant.pixelsOf(bitmap), width, height, white, tools, assistView, waveMode)
                    } else null
                    if (report && levels != null) {
                        reportedAt = now
                        val passAverageMs = if (passFrames > 0) passNanos / passFrames / 1e6 else Double.NaN
                        val passWorstMs = if (passFrames > 0) passWorst / 1e6 else Double.NaN
                        noteLook(tools, profile, lut != null, white, levels, width, height, passAverageMs, passWorstMs)
                        passNanos = 0L
                        passWorst = 0L
                        passFrames = 0
                    }
                    withContext(Dispatchers.Main) {
                        show(session, bitmap, sample, if (histogramOn) levels else null, white, analysis)
                    }
                }
            } finally {
                // потоки прохода уходят вместе с циклом кадров
                picture.close()
            }
        }
    }

    /**
     * Что на картинке: LUT, поворот, белый, размах яркости и цена прохода — в logcat (тег ZVAssist) раз в 2 с, для проверок
     * по adb.
     */
    private fun noteLook(tools: MonitorAssist, profile: CameraProfile, lutOn: Boolean, white: Int, levels: IntArray, width: Int,
                         height: Int, passAverageMs: Double, passWorstMs: Double) {
        Log.i("ZVAssist", PictureHelp.report(luts.appliedName(tools, profile, lutOn), tools.lut.value, tools.orientation,
            tools.lutScopes, white, levels, width, height, passAverageMs, passWorstMs, tools.vectorscope, tools.vectorscopeZoom))
    }

    /** Связь по Wi-Fi: провал — для экрана, время ответа на пробы — для качества превью и журнала. */
    private suspend fun watchLink(wifi: PtpIpTransport) {
        var samples = 0
        wifi.link.collect { link ->
            linkOutage = link.outage
            longestOutageMs = maxOf(longestOutageMs, link.longestOutageMs)
            val rtt = link.rttMs
            if (rtt != null && link.rttSamples != samples) {
                samples = link.rttSamples
                linkRttMs = rtt
                val now = System.currentTimeMillis()
                qualityPolicy.rtt(now, rtt.toDouble())
                rttWindow.add(now, rtt)
            }
        }
    }

    private fun apply(snapshot: Map<Int, SonyProperty>) {
        properties = snapshot
        assistVideo = mode != CaptureMode.photo
        // «Авто» в LUT идёт за профилем изображения главной камеры: со следующего кадра
        val profile = CameraProfile(snapshot[SonyProp.PICTURE_PROFILE]?.current?.intValue,
            snapshot[SonyProp.PP_GAMMA]?.current?.intValue, snapshot[SonyProp.PP_COLOR_MODE]?.current?.intValue)
        if (profile != cameraProfile) {
            cameraProfile = profile
            warmLut()
        }
        // пресеты на экране — режима камеры; запоминаем его: без связи показываем тот же список
        snapshot[SettingID.exposureProgram.code]?.current?.intValue?.let(CaptureMode::of)?.let { mode ->
            if (mode != presetMode) {
                presetMode = mode
                prefs.presetMode = mode
            }
        }
        val recording = snapshot[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue == 1L
        if (recording != (recordingStartedAt != null)) journal(if (recording) "запись: камера пишет" else "запись: камера не пишет")
        if (recording && recordingStartedAt == null) recordingStartedAt = System.currentTimeMillis()
        if (!recording) recordingStartedAt = null
        // подтверждение нажатия записи и конец записи — по свойству; начало записи с кнопки самой камеры исходом не считается
        if (snapshot.containsKey(SonyProp.MOVIE_RECORDING_STATE)) {
            recordingOutcome(recordingToggle.observe(recording, System.currentTimeMillis()))
        }
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

    /** Фокус и зум в журнале logcat (тег ZVLens), когда что-то поменялось, — для проверок по adb. */
    private var lensLogged = ""

    private fun noteLens() {
        val frames = focusFrames.joinToString(" ") {
            "%s(%.3f %.3f%s)".format(java.util.Locale.ROOT, it.kind.name, it.rect.center.x, it.rect.center.y, if (it.focused) " ✓" else "")
        }
        val line = "касание: ${touchFocusMethod?.name ?: "нет"}${if (touchFocusOn) " вкл" else ""}, снять: $touchCancelAvailable, " +
            "рамки: [$frames], зум: ${if (zoomAvailable) "есть" else "нет"} $zoomDirection ${zoomBar?.let { "${it.box}/${it.boxes} ${it.position}" } ?: "—"}, " +
            "шкала фокуса: ${if (focusDriveAvailable) "есть" else "нет"} ${focalPosition ?: "—"}; " +
            // сами свойства: режим и зона фокуса, функция касания и можно ли касаться и снимать, Near/Far, зум
            listOf(SonyProp.FOCUS_MODE, SonyProp.FOCUS_AREA, SonyProp.TOUCH_FUNCTION, SonyProp.REMOTE_TOUCH_ENABLED,
                SonyProp.REMOTE_TOUCH_CANCEL_ENABLED, SonyProp.NEAR_FAR_ENABLED, SonyProp.ZOOM_ENABLED, SonyProp.ZOOM_SCALE)
                .joinToString(" ") { code -> "%X=%s".format(code, value(code)?.let { java.lang.Long.toHexString(it) } ?: "—") }
        if (line != lensLogged) {
            lensLogged = line
            Log.i("ZVLens", line)
        }
    }

    /** Растр помощи в журнале logcat (тег ZVAssist) раз в 2 с: размер, часть кадра, зебра и пикинг — для проверок по adb. */
    private var assistLoggedAt = 0L

    private fun noteAssist(overlay: AssistOverlay?) {
        val now = System.currentTimeMillis()
        if (overlay == null || now - assistLoggedAt < 2000) return
        assistLoggedAt = now
        Log.i("ZVAssist", "растр %d×%d, часть кадра %.3f %.3f %.3f×%.3f, зебра %.2f%%, пикинг %.2f%%".format(java.util.Locale.ROOT,
            overlay.image.width, overlay.image.height, overlay.left, overlay.top, overlay.width, overlay.height,
            overlay.zebraShare, overlay.peakingShare))
    }

    /**
     * Вектороскоп в журнале logcat (тег ZVAssist) раз в 2 с, пока он включён: масштаб, где пик и сколько отсчётов у шести меток.
     * По строке проверяется вектороскоп без снимка экрана: на цветных полосах 75 % при ×1 у всех шести меток отсчёты есть.
     */
    private var vectorscopeLoggedAt = 0L

    private fun noteVectorscope(trace: VectorscopeTrace?) {
        val now = System.currentTimeMillis()
        if (trace == null || now - vectorscopeLoggedAt < 2000) return
        vectorscopeLoggedAt = now
        Log.i("ZVAssist", Vectorscope.report(trace.counts, trace.zoom))
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

    /** [white] — белый уровень, по которому поток кадров считал помощь: гистограмма, статистика и помощь видят одну шкалу. */
    private fun show(from: CameraSession, bitmap: Bitmap, sample: LiveViewSample, levels: IntArray?, white: Int, analysis: FrameAnalysis?) {
        // кадр прошлого сеанса, дошедший после обрыва, — не тот, что считается «восстановили»
        if (from !== session) return
        measure(sample)
        frameBitmap = bitmap
        if (focusFrames != sample.focusFrames) focusFrames = sample.focusFrames
        noteLens()
        assistOverlay = if (assist.needsRaster) analysis?.overlay else null
        waveform = if (waveformOn) analysis?.waveform else null
        vectorscope = if (assist.vectorscope) analysis?.vectorscope else null
        noteAssist(analysis?.overlay)
        noteVectorscope(vectorscope)
        // новая обёртка на каждый кадр: Bitmap из пула тот же, и с прежней обёрткой Compose не перерисовал бы картинку
        frame = bitmap.asImageBitmap()
        lastSample = sample
        // наложение включено ещё с прошлого запуска — эталон подгружается с первым кадром: из файла, иначе этот кадр
        if (reference == null && !referenceRequested && assist.overlay != MonitorAssist.Overlay.off) {
            referenceRequested = true
            ensureReference()
        }
        val now = System.currentTimeMillis()
        noteQuality(now, bitmap, sample)
        sessionFrames++
        summaryFrames++
        summaryBytes += sample.bytes
        framesTotal++
        lastFrameBytes = sample.bytes
        frameWindow.add(now, sample.bytes, sample.fetchMs, sample.waits)
        if (reconnect != null) finishReconnect(now, firstFrame = true)
        if (levels != null) {
            // в видео белое в live view — 234, в фото — 255, у картинки с LUT — 255: шкала гистограммы — до белого, того же, что
            // у помощи (его определил поток кадров)
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
            if (liveViewQuality != null) {
                qualitySwitches++
                journal("кадры ${bitmap.width}×${bitmap.height} — качество превью ${quality.title}")
            }
            liveViewQuality = quality
        }
        if (transportKind != TransportKind.wifi) return
        qualityPolicy.frame(now, sample.bytes, sample.fetchMs, quality)
        sample.shortReplyMs?.let { qualityPolicy.rtt(now, it) }
    }

    // MARK: - Качество превью по Wi-Fi

    /**
     * Начало сеанса по Wi-Fi: качество — выбранное вручную, в авто — высокое (камера могла остаться на низком); дальше авто
     * ведёт канал.
     */
    private fun startQuality(session: CameraSession) {
        // свойства — прямо из сеанса: копия у контроллера обновится только на следующем шаге главного потока
        val current = LiveViewQuality.of(session.properties.value[SonyProp.LIVE_VIEW_IMAGE_QUALITY]?.current?.intValue)
            ?: liveViewQuality ?: return
        val wanted = previewQualityMode.fixed ?: LiveViewQuality.high
        if (current == wanted) return
        journal("качество превью: камера на ${current.title} — начинаю с ${wanted.title}")
        qualityPolicy.requested(System.currentTimeMillis(), wanted)
        writeQuality(session, wanted)
    }

    /** Дважды в секунду: решение политики (или, если настройка выключена, всегда высокое) — в камеру. По USB не трогаем. */
    private fun tickQuality() {
        val session = session ?: return
        if (transportKind != TransportKind.wifi || phase != Phase.ready || !foreground) return
        // одна запись за раз
        if (qualityJob?.isActive == true) return
        val now = System.currentTimeMillis()
        val policy = qualityPolicy
        val fixed = previewQualityMode.fixed
        val target = if (fixed == null) {
            policy.decide(now, linkOutage, sharp = sharpPreview)
        } else if (policy.level != fixed && !policy.isSwitching(now)) {
            // выбрано вручную — держим его
            fixed.also { policy.requested(now, it) }
        } else {
            null
        }
        target ?: return
        val fps = policy.predictedHighFps(now, 5000)
        val throughput = policy.throughput(now, 5000)
        journal("качество превью: %s → %s (прогноз на высоком %s к/с, поток %s КБ/с, RTT %.0f мс, выдержка %d с)".format(
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
                journal("качество превью ${target.title} не записалось: ${e.message}")
            }
        }
    }

    /** Выбор в подсказке к счётчику кадров или в настройках: камера переходит на ближайшем шаге tickQuality. */
    fun applyPreviewQuality(mode: PreviewQualityMode) {
        if (mode == previewQualityMode) return
        previewQualityMode = mode
        prefs.previewQualityMode = mode
        journal("качество превью: ${mode.name}")
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
        return ConnectionDetails((wifi?.let { WiFiAddress(it.host, it.port) } ?: reconnect?.address)?.toString(), wifi?.localHost, signal)
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
    private fun stopSession(abort: Boolean, keepOpen: Boolean = false) {
        releaseLens()
        jobs.forEach { it.cancel() }
        jobs.clear()
        qualityJob?.cancel()
        // сеанс уходит в ленту — открытым: его события слушает свой scope
        if (!keepOpen) {
            session?.close()
            sessionScope?.cancel()
        }
        sessionScope = null
        when (val current = transport) {
            is PtpIpTransport -> {
                // провалы этого соединения — в счёт сеанса: следующее соединение считает свои с нуля
                val link = current.link.value
                outagesBefore += link.outages
                longestOutageMs = maxOf(longestOutageMs, link.longestOutageMs)
                // обычное закрытие идёт в своём потоке; новое соединение транспорт откроет только после него
                if (!keepOpen) {
                    if (abort) current.abort(LinkLoss.closed) else current.close()
                }
            }
            else -> if (!keepOpen) current?.close()
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

    /** Останавливает всё, что связано с текущей камерой, и закрывает соединение; keepOpen — сеанс уходит в ленту открытым. */
    private fun teardown(keepOpen: Boolean = false) {
        reconnectJob?.cancel()
        reconnectJob = null
        reconnect = null
        isReconnecting = false
        stopSession(abort = false, keepOpen = keepOpen)
        sessionStartedAt = null
        holdWifiLock(false)
        transportKind = null
        wifiDirect = false
        device = null
        currentLink = null
        currentCameraId = null
        deviceInfo = null
        properties = emptyMap()
        // камеры нет — «Авто» не по чему выбирать
        cameraProfile = CameraProfile.none
        frame = null
        frameBitmap = null
        lastSample = null
        liveViewQuality = null
        histogram = null
        histogramStats = null
        assistOverlay = null
        waveform = null
        vectorscope = null
        controlCodes = emptySet()
        focusFrames = emptyList()
        touchMark = null
        zoomDirection = 0
        focusUnits = 0
        frameTimes.clear()
        frameGaps.clear()
        lastFrameAt = 0L
        fps = 0.0
        shownFps = 0
        frameGapMs = 0
        frameNotice = FrameNotice.none
        recordingStartedAt = null
        recordingToggle = RecordingToggle()
        recordingRequest = null
        recordingCall = null
        isApplyingPreset = false
        endPreviewWithoutCamera()
        isCapturing = false
        capturePhase = null
        dismissReview()
        phase = Phase.searching
    }

    // MARK: - Панель отладки

    /** Событие связи: в logcat (тег ZVLink) и в журнал панели отладки. */
    private fun journal(text: String) = LinkJournal.event(text)

    /** Начался новый сеанс (не тихое восстановление): счёт для панели отладки — с нуля. */
    private fun startSessionStats() {
        sessionStartedAt = System.currentTimeMillis()
        outagesBefore = 0
        longestOutageMs = 0
        framesTotal = 0
        qualitySwitches = 0
        lastFrameBytes = 0
        rttWindow.clear()
        frameWindow.clear()
    }

    /**
     * Всё для панели отладки: строки вкладок, дважды в секунду. Серийного номера камеры, MAC-адресов и GUID здесь нет:
     * их не показываем и не копируем.
     */
    fun debugReport(): DebugReport = DebugReport(mapOf(
        DebugTab.link to debugLink(),
        DebugTab.preview to debugPreview(),
        DebugTab.camera to debugCamera(),
        DebugTab.properties to properties.keys.sorted().mapNotNull { properties[it]?.let(DebugFormat::propertyRow) },
        DebugTab.log to LinkJournal.events.newestFirst().map { DebugRow(clockTime(it.at), it.text) },
    ))

    /** Все вкладки текстом — для «Скопировать». */
    fun debugText(): String {
        val time = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        return debugReport().text("Sony Cam Viewer ${BuildConfig.VERSION_NAME}, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}, $time")
    }

    private fun debugLink(): List<DebugRow> {
        val now = System.currentTimeMillis()
        val rows = ArrayList<DebugRow>()
        val wifi = transportKind == TransportKind.wifi
        rows += DebugRow("Подключение", when (transportKind) {
            null -> "нет"
            TransportKind.usb -> "USB"
            TransportKind.wifi -> if (wifiDirect) "Wi-Fi Direct" else "Wi-Fi"
        })
        rows += DebugRow("Состояние", when {
            isReconnecting -> "переподключаюсь · " + DebugFormat.clock(now - noticeSince)
            frameNotice == FrameNotice.interrupted -> "связь прервалась · " + DebugFormat.clock(now - noticeSince)
            frameNotice == FrameNotice.weakSignal -> "слабый сигнал: кадры запаздывают"
            phase == Phase.searching -> "ищу камеру"
            phase == Phase.permission -> "жду разрешения на доступ к USB"
            phase == Phase.notPcRemote -> "камера не в PC Remote"
            else -> "связь есть"
        })
        val details = connectionDetails()
        ((transport as? PtpIpTransport)?.let { WiFiAddress(it.host, it.port) } ?: reconnect?.address)?.let {
            rows += DebugRow("Камера", "${it.host}:${it.port}")
        }
        details.localHost?.let { rows += DebugRow("Планшет", it) }
        rows += DebugRow("Wi-Fi планшета", wifiSignal())
        rttWindow.stats(now)?.let { rows += DebugRow("Отклик камеры", "${it.lastMs} мс · мин ${it.minMs} · среднее ${it.averageMs} за минуту") }
        frameWindow.throughput(now)?.let { if (frame != null) rows += DebugRow("Поток превью", DebugFormat.mbit(it)) }
        sessionStartedAt?.let { rows += DebugRow("Сеанс идёт", DebugFormat.clock(now - it)) }
        if (wifi) {
            val outages = outagesBefore + ((transport as? PtpIpTransport)?.link?.value?.outages ?: 0)
            rows += DebugRow("Провалов связи", if (outages == 0) "0" else "$outages, самый долгий ${DebugFormat.seconds(longestOutageMs)}")
        }
        rows += DebugRow("Восстановлений", if (reconnects == 0) "0 с запуска программы"
            else "$reconnects, последнее — ${lastRecoveryMs?.let(DebugFormat::seconds) ?: "—"}")
        lastLoss?.let { (why, at) -> rows += DebugRow("Последний обрыв", "$why, ${DebugFormat.clock(now - at)} назад") }
        (wifiStatus ?: message)?.let { rows += DebugRow("Сообщение", it) }
        rows += DebugRow("Wi-Fi в программе", if (prefs.wifiEnabled) "включён" else "выключен")
        val paired = cameraBook.cameras.filter { it.paired }
        rows += DebugRow("Сопряжены", if (paired.isEmpty()) "нет" else paired.joinToString("; ") { c -> c.name + (c.address?.let { ", была на $it" } ?: "") })
        if (debugHost != null || debugLinkCheckUrl != null) {
            rows += DebugRow("Отладка", "камера — ${debugHost ?: "как обычно"}, проверка связи — ${debugLinkCheckUrl ?: "ping"}")
        }
        return rows
    }

    private fun debugPreview(): List<DebugRow> {
        if (frame == null && framesTotal == 0L) return emptyList()
        val now = System.currentTimeMillis()
        val rows = ArrayList<DebugRow>()
        rows += DebugRow("Кадров/с", DebugFormat.decimal(fps))
        frameBitmap?.let { rows += DebugRow("Кадр", "${it.width}×${it.height}, ${lastFrameBytes / 1024} КБ") }
        if (transportKind == TransportKind.wifi) {
            val quality = if (liveViewQuality == LiveViewQuality.low) {
                if (previewQualityMode == PreviewQualityMode.auto) "низкое — сигнала не хватает" else "низкое"
            } else "высокое"
            val mode = when (previewQualityMode) {
                PreviewQualityMode.auto -> "авто"
                PreviewQualityMode.high -> "вручную — высокое"
                PreviewQualityMode.low -> "вручную — низкое"
            }
            rows += DebugRow("Качество", "$quality ($mode)")
            qualityPolicy.predictedHighFps(now, 5000)?.let { rows += DebugRow("Прогноз на высоком", DebugFormat.decimal(it) + " к/с") }
            qualityPolicy.upAttemptInMs(now)?.let { rows += DebugRow("Вернуть высокое", "попытка через ${(it + 999) / 1000} с") }
        }
        frameWindow.gaps(now)?.let { rows += DebugRow("Паузы между кадрами", "p50 ${it.p50} · p95 ${it.p95} · max ${it.max} мс (10 с)") }
        frameWindow.fetch(now)?.let {
            rows += DebugRow("Запрос кадра", "p50 ${it.p50} · p95 ${it.p95} мс (5 с)")
            rows += DebugRow("«Кадр не готов»", DebugFormat.decimal(it.waitsPerFrame) + " на кадр")
        }
        rows += DebugRow("Кадров за сеанс", "$framesTotal")
        if (transportKind == TransportKind.wifi) rows += DebugRow("Смен качества", "$qualitySwitches")
        return rows
    }

    private fun debugCamera(): List<DebugRow> {
        val rows = arrayListOf(DebugRow("Фаза", when (phase) {
            Phase.searching -> "ищу камеру"
            Phase.permission -> "жду разрешения на доступ к USB"
            Phase.connecting -> if (isReconnecting) "переподключение" else "подключение"
            Phase.settling -> "первые секунды: настройки ещё не принимаются"
            Phase.ready -> "готова"
            Phase.notPcRemote -> "камера не в режиме PC Remote"
        }))
        // серийный номер камеры не показываем
        val info = deviceInfo ?: return rows
        rows += DebugRow("Модель", info.model)
        rows += DebugRow("Прошивка", info.firmware)
        if (properties.isEmpty()) return rows
        rows += DebugRow("Режим съёмки", ValueFormatter.label(SettingID.exposureProgram, property(SettingID.exposureProgram)))
        rows += DebugRow("Запись", recordingStartedAt?.let { "идёт " + DebugFormat.clock(System.currentTimeMillis() - it) } ?: "нет")
        rows += DebugRow("Батарея", ValueFormatter.label(SettingID.battery, property(SettingID.battery)))
        rows += DebugRow("Перегрев", ValueFormatter.label(SettingID.overheat, property(SettingID.overheat)))
        rows += DebugRow("Карта", ValueFormatter.label(SettingID.cardRemaining, property(SettingID.cardRemaining)))
        val destination = properties[SonyProp.STILL_IMAGE_STORE_DESTINATION]?.current?.intValue
        rows += DebugRow("Снимки сохраняет", destination?.let {
            when (it) {
                0x01L -> "только на планшет"
                0x10L -> "только на карту"
                0x11L -> "на планшет и на карту"
                else -> "код $it"
            }
        } ?: "—")
        val priority = properties[SonyProp.PRIORITY_MODE]?.current?.intValue
        rows += DebugRow("Приоритет управления", priority?.let { if (it == 1L) "у программы (1)" else "у камеры ($it)" } ?: "—")
        val quality = properties[SonyProp.LIVE_VIEW_IMAGE_QUALITY]?.current?.intValue
        rows += DebugRow("Качество превью в камере", quality?.let {
            when (LiveViewQuality.of(it)) {
                LiveViewQuality.low -> "низкое (1)"
                LiveViewQuality.high -> "высокое (2)"
                null -> "$it"
            }
        } ?: "—")
        rows += DebugRow("Свойств", "${properties.size}")
        return rows
    }

    /**
     * Сигнал Wi-Fi планшета — что даёт система: уровень, скорость канала, частота. Без разрешения на местоположение:
     * имя сети и MAC не нужны и не читаются. Шума Android не даёт.
     */
    private fun wifiSignal(): String = runCatching {
        val network = (transport as? PtpIpTransport)?.network ?: wifiNetwork ?: connectivity.activeNetwork
        val caps = connectivity.getNetworkCapabilities(network)?.takeIf { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) }
            ?: return@runCatching "—"
        // с Android 12 сведения о Wi-Fi — у самой сети; раньше — только у WifiManager
        val info = caps.transportInfo as? WifiInfo
            ?: if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) @Suppress("DEPRECATION") wifiManager.connectionInfo else null
        val parts = ArrayList<String>()
        val dbm = info?.rssi?.takeIf { it in -126..-1 }
            ?: caps.signalStrength.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }
        dbm?.let { parts += "${DebugFormat.minus(it)} дБм" }
        val rx = info?.rxLinkSpeedMbps?.takeIf { it > 0 }
        val tx = info?.txLinkSpeedMbps?.takeIf { it > 0 }
        if (rx != null || tx != null) {
            parts += "приём ${rx ?: "—"} / передача ${tx ?: "—"} Мбит/с"
        } else {
            info?.linkSpeed?.takeIf { it > 0 }?.let { parts += "$it Мбит/с" }
        }
        info?.frequency?.takeIf { it > 0 }?.let { mhz ->
            parts += DebugFormat.wifiChannel(mhz)?.let { "$mhz МГц, канал $it" } ?: "$mhz МГц"
        }
        if (parts.isEmpty()) "—" else parts.joinToString(", ")
    }.getOrDefault("—")

    companion object {
        private const val TAG = "ZVController"
        /** Связь, качество превью, переподключения — отдельным тегом: `adb logcat -s ZVLink`. */
        private const val LINK = "ZVLink"
        private const val ACTION_USB_PERMISSION = "local.zve10viewer.USB_PERMISSION"
        /** Миниатюра снимка в ленте — не больше стольких точек по длинной стороне. */
        private const val THUMBNAIL_SIDE = 160
        /** Эталон для наложения — не больше стольких точек по длинной стороне: больше экрану не нужно. */
        private const val REFERENCE_SIDE = 2048
        /** Столько в фоне — и сеанс закрываем обычным образом; на экране подключимся снова. */
        private const val BACKGROUND_CLOSE_MS = 60_000L
        /** Прощальная запись при закрытии: вернуть камере высокое качество превью, если снижали мы. */
        internal val RESTORE_HIGH_QUALITY = PtpIpTransport.Farewell(SonyOp.SET_EXT_PROP_VALUE,
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

/** Что посчитано по точкам кадра: растр помощи, волновая форма и вектороскоп. */
private class FrameAnalysis(
    val overlay: CameraController.AssistOverlay?,
    val waveform: CameraController.WaveformTrace?,
    val vectorscope: CameraController.VectorscopeTrace?,
)

/**
 * Bitmap кадра для прохода картинки (`PicturePath`): точки читаются и пишутся целиком. Один на цикл кадров, Bitmap
 * подставляется на каждый кадр — объекта на кадр нет.
 */
private class BitmapSurface : FrameSurface {
    lateinit var bitmap: Bitmap
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    override fun read(into: IntArray) = bitmap.getPixels(into, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

    override fun write(from: IntArray) = bitmap.setPixels(from, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
}

/**
 * Растр помощи в кадре (зебра, false color, пикинг) и волновая форма по кадру: растр — яркость всего кадра, видимая часть —
 * в размер растра. Точки кадра приходят готовыми — их разложил проход картинки (с поворотом и LUT) или [pixelsOf] (кадр как
 * есть): второго `getPixels` нет. Растры и следы — в Bitmap по три по кругу, как кадры. Массивы — одни на весь сеанс. Вызывать
 * из одной корутины.
 */
private class AssistSampler {
    private var pixels = IntArray(0)
    private var out = IntArray(0)
    private val bitmaps = arrayOfNulls<Bitmap>(3)
    private var next = 0
    private val traces = arrayOfNulls<Bitmap>(3)
    private var nextTrace = 0
    private val scopes = arrayOfNulls<Bitmap>(3)
    private var nextScope = 0

    /** Точки кадра как есть, когда проход картинки их не раскладывал. Массив один на сеанс и может быть больше кадра. */
    fun pixelsOf(frame: Bitmap): IntArray {
        val w = frame.width
        val h = frame.height
        if (pixels.size < w * h) pixels = IntArray(w * h)
        frame.getPixels(pixels, 0, w, 0, 0, w, h)
        return pixels
    }

    /**
     * @param argb точки кадра, по которым считается помощь: первые [w] · [h] чисел
     * @param white белый уровень шкалы — тот же, что у гистограммы (`PictureHelp.whiteLevel`)
     */
    fun analyze(argb: IntArray, w: Int, h: Int, white: Int, assist: MonitorAssist, view: CameraController.AssistView,
                waveform: Waveform.Mode?): FrameAnalysis {
        val overlay = if (assist.needsRaster) render(AssistRaster.luma(argb, w * h), w, h, white, assist, view) else null
        val trace = waveform?.let { mode ->
            val counts = Waveform.counts(argb, w, h, mode)
            val slot = nextTrace
            nextTrace = (nextTrace + 1) % traces.size
            val bitmap = traces[slot]?.takeIf { it.width == counts.columns }
                ?: Bitmap.createBitmap(counts.columns, Waveform.ROWS, Bitmap.Config.ARGB_8888).also { traces[slot] = it }
            bitmap.setPixels(Waveform.image(counts), 0, counts.columns, 0, 0, counts.columns, Waveform.ROWS)
            CameraController.WaveformTrace(bitmap.asImageBitmap(), counts, white)
        }
        return FrameAnalysis(overlay, trace, if (assist.vectorscope) vectorscope(argb, w, h, assist.vectorscopeZoom) else null)
    }

    /** Вектороскоп: счётчики корзин → картинка 128×128 в Bitmap из кольца (как у следов волновой формы). */
    private fun vectorscope(argb: IntArray, w: Int, h: Int, zoom: Int): CameraController.VectorscopeTrace {
        val counts = Vectorscope.counts(argb, w, h, zoom)
        val slot = nextScope
        nextScope = (nextScope + 1) % scopes.size
        val bitmap = scopes[slot] ?: Bitmap.createBitmap(Vectorscope.BINS, Vectorscope.BINS, Bitmap.Config.ARGB_8888).also { scopes[slot] = it }
        bitmap.setPixels(Vectorscope.image(counts), 0, Vectorscope.BINS, 0, 0, Vectorscope.BINS, Vectorscope.BINS)
        return CameraController.VectorscopeTrace(bitmap.asImageBitmap(), zoom, counts)
    }

    private fun render(luma: IntArray, w: Int, h: Int, white: Int, assist: MonitorAssist,
                       view: CameraController.AssistView): CameraController.AssistOverlay? {
        val plan = AssistRaster.plan(w, h, view.x, view.y, view.width, view.height, view.factor)
        val part = AssistRaster.resample(luma, w, h, plan)
        if (out.size < plan.width * plan.height) out = IntArray(plan.width * plan.height)
        val raster = AssistRaster.render(part, plan.width, plan.height, plan.scale, white, assist, out) ?: return null
        val slot = next
        next = (next + 1) % bitmaps.size
        val bitmap = bitmaps[slot]?.takeIf { it.width == raster.width && it.height == raster.height }
            ?: Bitmap.createBitmap(raster.width, raster.height, Bitmap.Config.ARGB_8888).also { bitmaps[slot] = it }
        bitmap.setPixels(raster.pixels, 0, raster.width, 0, 0, raster.width, raster.height)
        val r = plan.region
        return CameraController.AssistOverlay(bitmap.asImageBitmap(), (r.x / w).toFloat(), (r.y / h).toFloat(),
            (r.width / w).toFloat(), (r.height / h).toFloat(), raster.zebraShare, raster.peakingShare)
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
