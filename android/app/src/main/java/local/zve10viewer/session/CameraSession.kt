package local.zve10viewer.session

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import local.zve10viewer.sony.LiveViewFrame
import local.zve10viewer.sony.ObjectInfo
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyPropertyParser
import kotlin.coroutines.coroutineContext

sealed class SessionException(message: String) : Exception(message) {
    class NotPcRemote : SessionException("камера не в режиме PC Remote")
    class NoExtendedInfo : SessionException("камера не отдала список команд Sony")
    class OperationFailed(val operation: Int, val code: Int) :
        SessionException("операция 0x%04X: ответ 0x%04X".format(operation, code))
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

/** Сеанс работы с камерой Sony в режиме PC Remote. */
class CameraSession(private val transport: PtpTransport, private val timing: SessionTiming = SessionTiming()) {
    @Volatile var deviceInfo: DeviceInfo? = null
        private set
    @Volatile var isReady = false
        private set

    private val _properties = MutableStateFlow<Map<Int, SonyProperty>>(emptyMap())
    /** Последнее чтение свойств камеры. */
    val properties: StateFlow<Map<Int, SonyProperty>> = _properties

    /** Завершается, когда сеанс закрыт (камеру отключили или связь оборвалась). */
    val ended = CompletableDeferred<Unit>()
    private var eventJob: Job? = null

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
            if (call(SonyOp.GET_EXT_DEVICE_INFO, listOf(Sony.PROTOCOL_VERSION, 1L)).data.isNotEmpty()) {
                hasExtInfo = true
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

    /** Нажимает и отпускает кнопку записи; возвращает, идёт ли запись после этого. */
    suspend fun toggleRecording(): Boolean {
        if (!isReady) throw SessionException.NotReady()
        val before = _properties.value[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue
        val button = listOf(SonyProp.MOVIE_REC_BUTTON.toLong())
        call(SonyOp.CONTROL_DEVICE, button, PRESS)
        call(SonyOp.CONTROL_DEVICE, button, RELEASE)
        val after = waitFor(SonyProp.MOVIE_RECORDING_STATE) { it != before }
        return after == 1L
    }

    /** Следующий кадр live view (JPEG). Пока кадр не готов, камера отвечает AccessDenied — ждём и повторяем. */
    /** Сколько раз подряд камера ответила «кадр не готов» перед последним кадром (для замеров). */
    @Volatile var lastLiveViewWaits = 0
        private set

    suspend fun nextLiveViewFrame(): ByteArray {
        var waits = 0
        while (true) {
            coroutineContext.ensureActive()
            val reply = raw(SonyOp.GET_OBJECT, listOf(Sony.LIVE_VIEW_HANDLE))
            when (reply.code) {
                PtpResponseCode.OK -> {
                    LiveViewFrame.jpeg(reply.data)?.let {
                        lastLiveViewWaits = waits
                        return it
                    }
                    delay(20)
                }
                // кадр ещё не готов или камера занята (например, переключает режим) — ждём и повторяем
                // ждём недолго: кадр у камеры готов раз в 40 мс, и длинная пауза сбивает ровность видео
                PtpResponseCode.ACCESS_DENIED, PtpResponseCode.DEVICE_BUSY -> {
                    waits++
                    delay(5)
                }
                PtpResponseCode.INVALID_OBJECT_HANDLE -> delay(50)
                else -> throw SessionException.OperationFailed(SonyOp.GET_OBJECT, reply.code)
            }
        }
    }

    /**
     * Снимок: полунажатие (автофокус), спуск, отпускание. При download — забирает все файлы снимка
     * (RAW+JPEG — два файла) из памяти камеры по адресу 0xFFFFC001.
     */
    suspend fun capturePhoto(download: Boolean): List<CapturedObject> {
        if (!isReady) throw SessionException.NotReady()
        val half = listOf(SonyProp.SHUTTER_HALF_PRESS.toLong())
        val full = listOf(SonyProp.SHUTTER_FULL_PRESS.toLong())
        call(SonyOp.CONTROL_DEVICE, half, PRESS)
        try {
            if (_properties.value[SonyProp.FOCUS_MODE]?.current?.intValue != 1L) {
                // в автофокусе даём камере до секунды навестись (FocusFound 2 или 3 — готово)
                waitFor(SonyProp.FOCUS_FOUND, 1000) { it == 2L || it == 3L }
            }
            call(SonyOp.CONTROL_DEVICE, full, PRESS)
            call(SonyOp.CONTROL_DEVICE, full, RELEASE)
            call(SonyOp.CONTROL_DEVICE, half, RELEASE)
        } catch (e: Exception) {
            // спуск не должен остаться нажатым: отпускаем обе кнопки, ошибки отпускания уже не важны
            runCatching { call(SonyOp.CONTROL_DEVICE, full, RELEASE) }
            runCatching { call(SonyOp.CONTROL_DEVICE, half, RELEASE) }
            throw e
        }
        if (!download) return emptyList()

        val files = ArrayList<CapturedObject>()
        var deadline = System.currentTimeMillis() + timing.captureTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            refresh()
            if ((_properties.value[SonyProp.OBJECT_IN_MEMORY]?.current?.intValue ?: 0) > 0x8000) {
                val info = ObjectInfo.parse(call(SonyOp.GET_OBJECT_INFO, listOf(Sony.CAPTURED_OBJECT_HANDLE)).data)
                // защита от повторной выдачи того же файла, пока счётчик в камере не обновился
                if (files.any { it.filename == info.filename }) break
                val data = call(SonyOp.GET_OBJECT, listOf(Sony.CAPTURED_OBJECT_HANDLE)).data
                files.add(CapturedObject(info.filename, data))
                // после файла ждём ещё немного: при RAW+JPEG второй появляется следом
                deadline = System.currentTimeMillis() + timing.confirmTimeoutMs
                continue
            }
            delay(timing.pollMs)
        }
        return files
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
            transport.events.collect { event ->
                if (event.code == Sony.PROPERTY_CHANGED_EVENT) {
                    val t0 = System.nanoTime()
                    runCatching { refresh() }
                    android.util.Log.d("ZVFrames", "событие 0xC203 → свойства перечитаны за %.0f мс".format((System.nanoTime() - t0) / 1e6))
                } else {
                    android.util.Log.d("ZVFrames", "событие 0x%04X".format(event.code))
                }
            }
            // поток событий закончился — камера отключена
            close()
        }
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
    }
}
