package local.zve10viewer.session

import local.zve10viewer.monitor.FocusFrame
import local.zve10viewer.sony.SonyExtInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.settings.SettingOptions
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.sony.LiveViewFrame
import local.zve10viewer.sony.ObjectInfo
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyEvent
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyPropertyParser
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext

sealed class SessionException(message: String) : Exception(message) {
    class NotPcRemote : SessionException("камера не в режиме PC Remote")
    class NoExtendedInfo : SessionException("камера не отдала список команд Sony")
    class OperationFailed(val operation: Int, val code: Int) :
        SessionException("операция 0x%04X: ответ 0x%04X".format(operation, code))
    /**
     * Камера отклонила нажатие (release = false) или отпускание кнопки записи; recording — идёт ли запись после
     * ожидания: камера бывает занята, а запись всё же начинает.
     */
    class RecordButtonRefused(val release: Boolean, val code: Int, val recording: Boolean) :
        SessionException("кнопка записи: «%s» — ответ 0x%04X".format(if (release) "отпустить" else "нажать", code))
    class NotReady : SessionException("камера ещё не готова")
    class NotWritable(val code: Int) : SessionException("свойство 0x%04X сейчас не меняется".format(code))
    class ValueNotAllowed(val code: Int, val value: Long) : SessionException("значение $value не допускается")
}

data class SessionTiming(
    /** Камере нужно ~3 с после рукопожатия, прежде чем она начнёт применять настройки. */
    val settleMs: Long = 3000,
    val confirmTimeoutMs: Long = 2500,
    val pollMs: Long = 150,
    /** Сколько ждать, пока снимок появится в памяти камеры. */
    val captureTimeoutMs: Long = 10_000,
)

sealed class SetResult {
    data object Confirmed : SetResult()
    data class NotApplied(val actual: Long?) : SetResult()
}

data class CapturedObject(val filename: String, val data: ByteArray)

/** Кадр live view и как он шёл — по этим отсчётам видно, сколько тянет канал. JPEG лежит прямо в ответе камеры. */
class LiveViewSample(
    /** Ответ камеры целиком; JPEG в нём — с jpegOffset длиной jpegLength. */
    val data: ByteArray,
    val jpegOffset: Int,
    val jpegLength: Int,
    /** Сколько шёл запрос, вернувший кадр: от отправки до ответа, без ожидания своей очереди, мс. */
    val fetchMs: Double,
    /** Самый короткий ответ «кадр не готов» перед этим кадром — оценка времени отклика камеры, мс. */
    val shortReplyMs: Double?,
    /** Сколько раз камера ответила «кадр не готов» перед этим кадром. */
    val waits: Int,
    /** Рамки фокуса этого кадра: где камера фокусирует. */
    val focusFrames: List<FocusFrame> = emptyList(),
) {
    /** Сколько байт пришло в ответе камеры. */
    val bytes: Int get() = data.size

    /** Сам JPEG отдельным массивом (копия) — для сохранения кадра. */
    fun jpeg(): ByteArray = data.copyOfRange(jpegOffset, jpegOffset + jpegLength)
}

/**
 * Перечитывания свойств по событиям 0xC203: одно в работе и не больше одного в очереди. Пачка событий — одно-два
 * перечитывания, а не по одному на событие: каждое — ~2,8 КБ и лишний обмен, который на слабом Wi-Fi отнимает
 * кадры у live view.
 */
class RefreshCoalescer(scope: CoroutineScope, private val refresh: suspend () -> Unit) {
    private val requests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (request in requests) refresh()
        }
    }

    fun request() {
        requests.trySend(Unit)
    }
}

/** Сеанс работы с камерой Sony в режиме PC Remote. */
class CameraSession(
    private val transport: PtpTransport,
    private val timing: SessionTiming = SessionTiming(),
    /** Монотонные часы, нс: по ним считается возраст снимка свойств ([snapshotAgeMs]); в тестах — виртуальные. */
    private val clock: () -> Long = System::nanoTime,
) {
    @Volatile var deviceInfo: DeviceInfo? = null
        private set
    @Volatile var isReady = false
        private set
    /** Команды 0x9207, которые камера назвала в GetExtDeviceInfo: по ним видно, умеет ли она фокус касанием, зум и т. п. */
    @Volatile var controlCodes: Set<Int> = emptySet()
        private set

    private val _properties = MutableStateFlow<Map<Int, SonyProperty>>(emptyMap())
    /** Последнее чтение свойств камеры. */
    val properties: StateFlow<Map<Int, SonyProperty>> = _properties

    /** Когда свойства перечитаны в последний раз (по [clock]); [Long.MIN_VALUE] — ни разу. */
    @Volatile private var refreshedAt = Long.MIN_VALUE

    /**
     * Сколько миллисекунд назад свойства перечитаны (событием камеры или по запросу); [Long.MAX_VALUE] — ни разу. Одинаковое чтение
     * не меняет [properties] (StateFlow не повторяет равное), поэтому возраст снимка — не по их выдаче, а по самому обмену.
     */
    fun snapshotAgeMs(): Long =
        if (refreshedAt == Long.MIN_VALUE) Long.MAX_VALUE else maxOf(0L, (clock() - refreshedAt) / 1_000_000)

    private val cameraEvents = MutableSharedFlow<CameraEvent>(extraBufferCapacity = EVENT_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * События новых камер Sony — список подписей изменился (0xC20F, с типом списка), объектив сменили (0xC21B), фокус дошёл до
     * цели (0xC218) — приходят тем же путём, что изменение свойств (0xC203), но слушателям, а не в [properties]: по одному значению
     * на событие каждому. Событие, пришедшее до подписки, не повторяется. Буфер большой, чтобы не потерять тип списка у 0xC20F,
     * пока слушатель занят; поток не кончается вместе с сеансом — слушатель живёт в корутине сеанса и уходит с ней. Нужен тем,
     * кто хранит что-то по объективу и подписям, — например, метки фокуса и названия Creative Look.
     */
    val events: SharedFlow<CameraEvent> = cameraEvents.asSharedFlow()

    /** Завершается, когда сеанс закрыт (камеру отключили или связь оборвалась). */
    val ended = CompletableDeferred<Unit>()
    private var eventJob: Job? = null
    /** Кнопка записи у нас «нажата»: мы начали запись и ещё не отпустили (0xD2C8 — удержание, см. toggleRecording). */
    private var recordButtonHeld = false

    /** Рукопожатие Sony, передача приоритета компьютеру и первое чтение свойств. */
    suspend fun start(scope: CoroutineScope) {
        // на Mac сессию PTP открывал ImageCaptureCore, здесь — сами, и первой командой:
        // внутри сессии у каждой команды свой номер транзакции, и чужие ответы легко отсеять
        // после перезапуска программы камера какое-то время молчит: ждём недолго и повторяем,
        // а не держим пользователя на полном тайм-ауте
        var open: PtpReply? = null
        for (attempt in 1..4) {
            open = try {
                raw(SonyOp.OPEN_SESSION, listOf(1L), timeoutMs = 1500)
            } catch (e: TransportException) {
                if (e.disconnected || attempt == 4) throw e
                null
            }
            if (open != null) break
        }
        open!!
        if (open.code != PtpResponseCode.OK && open.code != PtpResponseCode.SESSION_ALREADY_OPEN) {
            throw SessionException.OperationFailed(SonyOp.OPEN_SESSION, open.code)
        }
        val info = DeviceInfo.parse(call(SonyOp.GET_DEVICE_INFO).data)
        deviceInfo = info
        if (!info.isPcRemote) throw SessionException.NotPcRemote()
        call(SonyOp.CONNECT, listOf(1L, 0L, 0L))
        call(SonyOp.CONNECT, listOf(2L, 0L, 0L))
        var hasExtInfo = false
        for (attempt in 1..20) {
            val ext = call(SonyOp.GET_EXT_DEVICE_INFO, listOf(Sony.PROTOCOL_VERSION, 1L)).data
            if (ext.isNotEmpty()) {
                hasExtInfo = true
                controlCodes = SonyExtInfo.parse(ext)?.controlCodes?.toSet().orEmpty()
                break
            }
            if (attempt < 20) delay(100)
        }
        if (!hasExtInfo) throw SessionException.NoExtendedInfo()
        call(SonyOp.CONNECT, listOf(3L, 0L, 0L))
        call(SonyOp.SET_EXT_PROP_VALUE, listOf(SonyProp.PRIORITY_MODE.toLong()), PtpValue.encode(1, PtpType.INT8))
        refresh()
        listenForEvents(scope)
    }

    /** Пауза, пока камера «просыпается», затем свежие свойства — после этого можно менять настройки. */
    suspend fun waitUntilReady() {
        delay(timing.settleMs)
        refresh()
        isReady = true
    }

    suspend fun refresh() {
        val reply = call(SonyOp.GET_ALL_EXT_PROP_INFO)
        _properties.value = SonyPropertyParser.parse(reply.data)
        refreshedAt = clock()
    }

    // MARK: - Другие камеры Sony: подписи, версия кода, данные объектива

    /**
     * Назвала ли камера операцию в GetDeviceInfo. Новые операции Sony (0x9215, 0x9216, 0x9223) зовём только если да: у ZV-E10 их
     * нет, и она на них отвечает отказом. Сами вызовы ниже спрашивают об этом сами; снаружи это нужно тому, кто не хочет и
     * заводить запрос ради пустого ответа ([CameraInfo]).
     */
    fun supports(operation: Int): Boolean = deviceInfo?.supports(operation) == true

    /**
     * Подписи значений от самой камеры (0x9215): список типа [type], 0 — все списки. Камера, которая 0x9215 не называет, не
     * спрашивается совсем — ответ пуст. Ответ, который не разобрался, — ошибка: лучше без подписей, чем с половиной.
     */
    suspend fun displayStrings(type: Long): DisplayStrings {
        if (!supports(SonyOp.GET_DISPLAY_STRING_LIST)) return DisplayStrings()
        return DisplayStrings.parse(call(SonyOp.GET_DISPLAY_STRING_LIST, listOf(type)).data)
    }

    /**
     * Подписи при подключении: сначала все списки (тип 0). Один список с неизвестным типом данных PTP обрывает разбор всего
     * набора — тогда просим один Creative Look (0x13), единственное, что программа показывает; не вышло и это — подписей нет, и
     * названия берутся из таблиц Sony. Ошибки не бросаются, а возвращаются: подписи не должны мешать подключению. Исключение —
     * обрыв связи, отмена и тайм-аут: после них пробовать другой тип незачем. Тайм-аут значит, что камера не отвечает: второй
     * запрос ушёл бы в ту же тишину, а слежение за связью («камера не отвечает» через 15 с без ответа на операцию) закрыло бы на
     * нём сеанс. Звать ли подписи снова в следующем сеансе, решает вызывающий ([SilentCameras]).
     */
    suspend fun loadDisplayStrings(): DisplayStringsLoad {
        if (!supports(SonyOp.GET_DISPLAY_STRING_LIST)) return DisplayStringsLoad()
        val failures = ArrayList<DisplayStringsLoad.Failure>()
        for (type in listOf(0L, DisplayStrings.CREATIVE_LOOK_TYPE)) {
            try {
                return DisplayStringsLoad(displayStrings(type), failures)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (cameraIsGone(e)) throw e
                failures += DisplayStringsLoad.Failure(type, e)
            }
        }
        return DisplayStringsLoad(failures = failures)
    }

    /** Связи с камерой нет или камера не отвечает: пробовать то же или другое дальше бессмысленно. */
    private fun cameraIsGone(error: Exception): Boolean = error is TransportException && (error.disconnected || error.timeout)

    /**
     * Версия кода производителя (0x9216), ×100: 310 — «3.10». Камера отдаёт её в параметре ответа. null — камера операцию не
     * называет или версии в ответе нет.
     */
    suspend fun vendorCodeVersion(): Int? {
        if (!supports(SonyOp.GET_VENDOR_CODE_VERSION)) return null
        return call(SonyOp.GET_VENDOR_CODE_VERSION).params.firstOrNull()?.toInt()
    }

    /**
     * Данные объектива (0x9223) в метрах. Спрашиваем, только если камера называет операцию и 0xE086 = 1 (данные объектива
     * готовы): без объектива с электроникой их нет. null — условий нет. Поэтому звать надо после того, как свойства перечитаны:
     * 0xE086 берётся из последнего чтения.
     */
    suspend fun lensInformation(): LensInformation? {
        if (!supports(SonyOp.GET_LENS_INFORMATION)) return null
        if (_properties.value[SonyProp.LENS_INFO_ENABLED]?.current?.intValue != 1L) return null
        return LensInformation.parse(call(SonyOp.GET_LENS_INFORMATION, listOf(METERS_PARAMETER)).data)
    }

    /** Записывает значение и ждёт, пока камера подтвердит его чтением. */
    suspend fun set(code: Int, value: Long): SetResult {
        if (!isReady) throw SessionException.NotReady()
        val property = _properties.value[code]
        if (property == null || !property.isWritable) throw SessionException.NotWritable(code)
        if (!SettingOptions.allows(property, value)) throw SessionException.ValueNotAllowed(code, value)
        call(SonyOp.SET_EXT_PROP_VALUE, listOf(code.toLong()), PtpValue.encode(value, property.dataType))
        val actual = waitFor(code) { it == value }
        return if (actual == value) SetResult.Confirmed else SetResult.NotApplied(actual)
    }

    /**
     * Кнопка записи; возвращает, идёт ли запись после этого. 0xD2C8 у Sony — «как удержание кнопки»: нажатие начинает
     * запись, отпускание останавливает. Поэтому запуск — только «нажать», остановка нашей записи — только «отпустить»:
     * «отпустить» сразу за «нажать» камера иногда успевает выполнить, и запись обрывается через полсекунды после старта.
     * Запись, начатую кнопкой на камере, останавливаем «нажать и отпустить»; кнопку, оставшуюся «нажатой» после записи,
     * которую камера остановила сама, перед новым запуском отпускаем. Отклонила камера шаг — RecordButtonRefused с
     * кодом и тем, идёт ли запись после ожидания.
     */
    suspend fun toggleRecording(): Boolean {
        if (!isReady) throw SessionException.NotReady()
        val before = _properties.value[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue
        val steps = when {
            before == 1L && recordButtonHeld -> listOf(RELEASE to true)
            before == 1L -> listOf(PRESS to false, RELEASE to true)
            recordButtonHeld -> listOf(RELEASE to true, PRESS to false)
            else -> listOf(PRESS to false)
        }
        val button = listOf(SonyProp.MOVIE_REC_BUTTON.toLong())
        for ((value, release) in steps) {
            val reply = raw(SonyOp.CONTROL_DEVICE, button, value)
            // камера отклонила (бывает «занята» 0x2019), а запись при этом может и пойти — ждём, потом ошибка
            if (!reply.isOK) {
                val after = waitFor(SonyProp.MOVIE_RECORDING_STATE) { it != before }
                throw SessionException.RecordButtonRefused(release, reply.code, recording = after == 1L)
            }
            recordButtonHeld = !release
        }
        val after = waitFor(SonyProp.MOVIE_RECORDING_STATE) { it != before }
        return after == 1L
    }

    /** Сколько раз подряд камера ответила «кадр не готов» перед последним кадром (для замеров). */
    @Volatile var lastLiveViewWaits = 0
        private set

    /**
     * Следующий кадр live view и как он шёл. Пока кадр не готов, камера отвечает AccessDenied — ждём и повторяем;
     * короткий ответ заодно показывает время отклика камеры.
     */
    suspend fun nextLiveViewFrame(): LiveViewSample {
        var waits = 0
        var shortest: Long? = null
        while (true) {
            coroutineContext.ensureActive()
            val reply = raw(SonyOp.GET_OBJECT, listOf(Sony.LIVE_VIEW_HANDLE))
            when (reply.code) {
                PtpResponseCode.OK -> {
                    LiveViewFrame.locate(reply.data)?.let { span ->
                        lastLiveViewWaits = waits
                        return LiveViewSample(reply.data, span.offset, span.length, reply.elapsedNanos / 1e6,
                            shortest?.let { it / 1e6 }, waits, FocusFrame.fromLiveView(reply.data))
                    }
                    delay(20)
                }
                // кадр ещё не готов или камера занята (например, переключает режим) — ждём и повторяем
                // ждём недолго: кадр у камеры готов раз в 40 мс, и длинная пауза сбивает ровность видео
                PtpResponseCode.ACCESS_DENIED, PtpResponseCode.DEVICE_BUSY -> {
                    waits++
                    if (reply.elapsedNanos > 0) shortest = minOf(shortest ?: reply.elapsedNanos, reply.elapsedNanos)
                    delay(5)
                }
                PtpResponseCode.INVALID_OBJECT_HANDLE -> delay(50)
                else -> throw SessionException.OperationFailed(SonyOp.GET_OBJECT, reply.code)
            }
        }
    }

    /**
     * Качество превью (0xD26A): записываем без проверки чтением — смену подтверждает ширина следующих кадров,
     * а лишние перечитывания свойств на слабом Wi-Fi отнимали бы кадры.
     */
    suspend fun setLiveViewQuality(quality: LiveViewQuality) {
        if (!isReady) throw SessionException.NotReady()
        call(SonyOp.SET_EXT_PROP_VALUE, listOf(SonyProp.LIVE_VIEW_IMAGE_QUALITY.toLong()),
            PtpValue.encode(quality.cameraValue, PtpType.UINT8))
    }

    /**
     * Снимок: полунажатие (автофокус), спуск, отпускание. При download — забирает файлы снимка из памяти камеры по
     * адресу 0xFFFFC001; files — сколько их (RAW+JPEG — два, по свойству 0xD253 FileFormat), и ещё столько, сколько
     * лежало в памяти камеры до спуска. Пришли все — сразу
     * возвращаемся: спуск свободен, а не ждёт ещё 2,5 с второго файла, которого не будет. onPhase — этапы для экрана:
     * фокусировка (после полунажатия), спуск отработан (после S2), качается файл N из files (его размер — из ObjectInfo).
     */
    suspend fun capturePhoto(download: Boolean, files: Int, onPhase: (CapturePhase) -> Unit = {}): List<CapturedObject> {
        if (!isReady) throw SessionException.NotReady()
        // в памяти камеры уже лежат объекты (прошлый снимок не забрали: тайм-аут, обрыв, сменили формат) — забираем и
        // их: это настоящие снимки, а без этого каждый следующий снимок отставал бы на один
        val leftover = maxOf(0L, (_properties.value[SonyProp.OBJECT_IN_MEMORY]?.current?.intValue ?: 0L) - 0x8000).toInt()
        val wanted = files + leftover
        val half = listOf(SonyProp.SHUTTER_HALF_PRESS.toLong())
        val full = listOf(SonyProp.SHUTTER_FULL_PRESS.toLong())
        call(SonyOp.CONTROL_DEVICE, half, PRESS)
        onPhase(CapturePhase.Focusing)
        try {
            if (_properties.value[SonyProp.FOCUS_MODE]?.current?.intValue != 1L) {
                // в автофокусе даём камере до секунды навестись (FocusFound 2 или 3 — готово)
                waitFor(SonyProp.FOCUS_FOUND, 1000) { it == 2L || it == 3L }
            }
            call(SonyOp.CONTROL_DEVICE, full, PRESS)
            onPhase(CapturePhase.Exposing)
            call(SonyOp.CONTROL_DEVICE, full, RELEASE)
            call(SonyOp.CONTROL_DEVICE, half, RELEASE)
        } catch (e: Exception) {
            // спуск не должен остаться нажатым: отпускаем обе кнопки, ошибки отпускания уже не важны
            runCatching { call(SonyOp.CONTROL_DEVICE, full, RELEASE) }
            runCatching { call(SonyOp.CONTROL_DEVICE, half, RELEASE) }
            throw e
        }
        if (!download) return emptyList()

        val result = ArrayList<CapturedObject>()
        var deadline = System.currentTimeMillis() + timing.captureTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            refresh()
            if ((_properties.value[SonyProp.OBJECT_IN_MEMORY]?.current?.intValue ?: 0) > 0x8000) {
                val info = ObjectInfo.parse(call(SonyOp.GET_OBJECT_INFO, listOf(Sony.CAPTURED_OBJECT_HANDLE)).data)
                // защита от повторной выдачи того же файла, пока счётчик в камере не обновился
                if (result.any { it.filename == info.filename }) break
                onPhase(CapturePhase.Downloading(result.size + 1, maxOf(wanted, result.size + 1), 0,
                    info.compressedSize.takeIf { it > 0 }))
                val data = call(SonyOp.GET_OBJECT, listOf(Sony.CAPTURED_OBJECT_HANDLE)).data
                result.add(CapturedObject(info.filename, data))
                if (result.size >= wanted) break
                // файлов меньше, чем должно быть: при RAW+JPEG второй появляется следом — ждём его ещё немного
                deadline = System.currentTimeMillis() + timing.confirmTimeoutMs
                continue
            }
            delay(timing.pollMs)
        }
        return result
    }

    /** Перечитывает свойства, пока условие не выполнится или не выйдет время подтверждения. */
    suspend fun waitFor(code: Int, timeoutMs: Long = timing.confirmTimeoutMs, done: (Long?) -> Boolean): Long? {
        val deadline = System.currentTimeMillis() + timeoutMs
        var current = _properties.value[code]?.current?.intValue
        while (System.currentTimeMillis() < deadline) {
            delay(timing.pollMs)
            refresh()
            current = _properties.value[code]?.current?.intValue
            if (done(current)) break
        }
        return current
    }

    fun close() {
        isReady = false
        eventJob?.cancel()
        ended.complete(Unit)
    }

    private fun listenForEvents(scope: CoroutineScope) {
        eventJob = scope.launch {
            // перечитывает в своей корутине: пока идёт одно, события только ставят в очередь не больше одного
            val refresher = RefreshCoalescer(this) {
                val t0 = System.nanoTime()
                runCatching { refresh() }
                android.util.Log.d("ZVFrames", "событие 0xC203 → свойства перечитаны за %.0f мс".format((System.nanoTime() - t0) / 1e6))
            }
            transport.events.collect { event ->
                when (event.code) {
                    Sony.PROPERTY_CHANGED_EVENT -> refresher.request()
                    SonyEvent.DISPLAY_LIST_CHANGED -> {
                        cameraEvents.tryEmit(CameraEvent.DisplayListChanged(event.params.firstOrNull() ?: 0L))
                        android.util.Log.d("ZVFrames", "событие 0x%04X".format(event.code))
                    }
                    SonyEvent.LENS_INFO_CHANGED -> {
                        cameraEvents.tryEmit(CameraEvent.LensChanged)
                        android.util.Log.d("ZVFrames", "событие 0x%04X".format(event.code))
                    }
                    // приходит с каждой записью цели во время перевода, раз в 100 мс: в logcat его не пишем (в журнал программы —
                    // раз за исход, это делает CameraInfo)
                    SonyEvent.FOCUS_POSITION_RESULT -> cameraEvents.tryEmit(CameraEvent.FocusPositionResult(event.params.firstOrNull() == 1L))
                    else -> android.util.Log.d("ZVFrames", "событие 0x%04X".format(event.code))
                }
            }
            // поток событий закончился — камера отключена
            close()
        }
    }

    /** Команда управления 0x9207 со значением своего типа (точка AF, зум, Near/Far); отдаёт код ответа камеры. */
    suspend fun control(code: Int, value: Long, type: Int): Int =
        raw(SonyOp.CONTROL_DEVICE, listOf(code.toLong()), PtpValue.encode(value, type)).code

    /**
     * Записать число в свойство без ожидания подтверждения и без проверок — для хода фокуса (0xE042, раз в 100 мс): значение своего
     * типа; отдаёт код ответа камеры, а не бросает на отказе — отказ называет вызывающий. Обрыв связи закрывает сеанс, как везде.
     */
    suspend fun setQuietly(code: Int, value: Long, type: Int): Int =
        raw(SonyOp.SET_EXT_PROP_VALUE, listOf(code.toLong()), PtpValue.encode(value, type)).code

    /** Кнопка 0x9207: нажать и отпустить (снять касание и т. п.); отдаёт код ответа на отпускание. */
    suspend fun pressButton(code: Int): Int {
        control(code, 2, PtpType.UINT16)
        return control(code, 1, PtpType.UINT16)
    }

    /** Отправка без проверки кода ответа; разрыв связи закрывает сеанс. */
    private suspend fun raw(operation: Int, params: List<Long> = emptyList(), outData: ByteArray? = null, timeoutMs: Int? = null): PtpReply =
        withContext(Dispatchers.IO) {
            try {
                transport.send(operation, params, outData, timeoutMs)
            } catch (e: TransportException) {
                if (e.disconnected) close()
                throw e
            }
        }

    private suspend fun call(operation: Int, params: List<Long> = emptyList(), outData: ByteArray? = null): PtpReply {
        val reply = raw(operation, params, outData)
        if (!reply.isOK) throw SessionException.OperationFailed(operation, reply.code)
        return reply
    }

    private companion object {
        val PRESS = PtpValue.encode(2, PtpType.UINT16)
        val RELEASE = PtpValue.encode(1, PtpType.UINT16)
        /** Параметр 0x9223: 1 — футы, 2 — метры. Показываем метры. */
        const val METERS_PARAMETER = 2L
        /** Сколько событий камеры ждёт медленного слушателя; потерять тип списка у 0xC20F хуже, чем занять память. */
        const val EVENT_BUFFER = 256
    }
}

/** Чем кончился запрос подписей при подключении ([CameraSession.loadDisplayStrings]). */
class DisplayStringsLoad(
    /** Что удалось получить. */
    val strings: DisplayStrings = DisplayStrings(),
    /** Что не вышло, по порядку попыток — для журнала. */
    val failures: List<Failure> = emptyList(),
) {
    /** Какой тип списка просили и что ответила камера. */
    class Failure(val type: Long, val error: Exception)
}
