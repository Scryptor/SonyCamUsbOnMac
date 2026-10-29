package local.zve10viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraEvent
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionException
import local.zve10viewer.session.SilentCameras
import local.zve10viewer.session.VendorCodeVersion
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException

/**
 * Что камера сообщает о себе сверх свойств (спека §7.3–7.4): подписи списков (0x9215), версия кода производителя (0x9216) и данные
 * объектива (0x9223). Спрашиваются только у камер, которые называют эти операции в GetDeviceInfo ([CameraSession] сама ничего
 * не шлёт лишнего); у ZV-E10 класс остаётся пустым и камеру ни о чём не спрашивает. Ничего отсюда не задерживает подключение:
 * запросы идут после того, как сеанс готов, каждый в своей корутине, а ошибки уходят в журнал панели отладки — подписи берутся
 * тогда из таблиц Sony, расстояний нет. То же устройство, что у CameraInfo.swift программы для Mac, и те же правила.
 *
 * Вызывать с главного потока (так делает [CameraController]); [scope] — корутины запросов. Методы, которые что-то спрашивают,
 * возвращают [Job] этих запросов: приложение его не ждёт, тесты — ждут.
 */
class CameraInfo(private val scope: CoroutineScope, private val note: (String) -> Unit) {
    /** Подписи значений от камеры (Creative Look и др.). */
    var strings by mutableStateOf(DisplayStrings())
        private set

    /** Версия кода производителя ×100 (310 — «3.10»); null — нет. */
    var vendorCodeVersion by mutableStateOf<Int?>(null)
        private set

    /** Данные объектива; null — их нет: операции нет, 0xE086 не 1, не вышло прочитать или объектив только что сменили. */
    var lens by mutableStateOf<LensInformation?>(null)
        private set

    /**
     * Чьи это подписи, версия и таблица: id камеры из списка знакомых. Другая камера в следующем сеансе (по Wi-Fi Direct у всех
     * один адрес, тихое переподключение может попасть не на ту) — прежнее ей не принадлежит.
     */
    private var camera: String? = null

    /**
     * Камеры, которые не ответили на необязательную операцию: им новые операции не посылаем до перезапуска программы. Не
     * сбрасывается вместе с остальным — следующий сеанс той же камеры должен про это помнить.
     */
    private val silent = SilentCameras()

    /**
     * Исходы 0xC218, о которых уже записано в журнал: во время перевода событие приходит на каждую запись цели, раз в 100 мс, и
     * журнал в 200 строк оборачивался бы за 20 с, вытесняя строки о подключении.
     */
    private val focusResultsNoted = HashSet<Boolean>()

    /** Растёт, когда сеанс уходит: запоздалый ответ прежнего сеанса ничего не перепишет. */
    private var generation = 0

    /** Растёт с каждым запросом данных объектива: из двух запросов подряд остаётся ответ на последний. */
    private var lensRequest = 0

    /** Сеанс уже опрошен ([begin]) и не оборван: события и свойства принимаются. */
    private var active = false

    /** 0xE086 в прошлых свойствах был 1: данные объектива просят, только когда он стал 1. */
    private var lensEnabled = false

    /** Камеру можно спрашивать о новых операциях: она на них отвечала или ещё не пробовала. */
    private val asking: Boolean get() = active && !silent.skips(camera)

    /** Стоит ли спрашивать [operation] по событию: камеру можно спрашивать и она эту операцию называет (у ZV-E10 её нет). */
    private fun mayAsk(session: CameraSession, operation: Int): Boolean = asking && session.supports(operation)

    /**
     * Камера на связи названа (её DeviceInfo): если это не та, чьи подписи, версия и таблица здесь лежат, они стираются сразу —
     * опрос идёт только через несколько секунд после готовности сеанса, а до него чужие названия подписывали бы значения этой
     * камеры. Тихое переподключение по Wi-Fi Direct (у всех камер один адрес) может попасть не на ту. Камера без id ([camera]
     * null) ничьей не бывает: прежнее не остаётся.
     */
    fun identify(camera: String?) {
        if (camera == null || camera != this.camera) clearData()
        this.camera = camera
    }

    /**
     * Сеанс готов: подписи и версия кода — один раз, данные объектива — если 0xE086 = 1. [camera] — id камеры, [properties] — её
     * последние свойства. Камера, которая раньше не ответила на необязательную операцию, не спрашивается совсем. Возвращает
     * запросы: когда они кончились (или сразу, если спрашивать некого).
     */
    fun begin(session: CameraSession, camera: String?, properties: Map<Int, SonyProperty>): Job {
        generation += 1
        val token = generation
        identify(camera)
        focusResultsNoted.clear()
        lensEnabled = properties[SonyProp.LENS_INFO_ENABLED]?.current?.intValue == 1L
        active = true
        if (!asking) return scope.launch { }
        val names = scope.launch {
            try {
                // все списки; один сломанный список лишил бы остальных — тогда один Creative Look, а там и названия из таблиц
                val load = session.loadDisplayStrings()
                if (generation != token) return@launch
                for (failure in load.failures) {
                    note("подписи камеры (0x9215, тип ${typeName(failure.type)}): ${describe(failure.error)}")
                }
                // что вышло, то и есть у камеры — и когда ничего: прежние подписи (камеры, которой уже нет) не остаются
                strings = load.strings
                if (load.strings.lists.isNotEmpty()) note("подписи камеры (0x9215): " + summary(load.strings))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, SonyOp.GET_DISPLAY_STRING_LIST, "подписи камеры (0x9215)", token)
            }
            // камера промолчала на подписи — вторую необязательную операцию ей не шлём
            if (generation != token || !asking) return@launch
            try {
                val version = session.vendorCodeVersion()
                if (generation != token) return@launch
                vendorCodeVersion = version
                if (version != null) note("версия кода производителя (0x9216): ${VendorCodeVersion.label(version)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, SonyOp.GET_VENDOR_CODE_VERSION, "версия кода производителя (0x9216)", token)
            }
        }
        val table = fetchLens(session)
        return scope.launch { joinAll(names, table) }
    }

    /**
     * Сеанс оборвался, но камеру ждём обратно (тихое восстановление): то, что о ней известно, остаётся на экране, пока не придёт
     * новое, а ответы запросов прежнего сеанса ничего не пишут. (У программы для Mac это `suspend()`; здесь `suspend` — слово
     * Kotlin.)
     */
    fun pause() {
        generation += 1
        lensRequest += 1
        active = false
    }

    /** Камеры больше нет: ничего о ней не помним (кроме того, что она не отвечала, — [silent]). */
    fun reset() {
        pause()
        camera = null
        clearData()
        lensEnabled = false
    }

    /** Камера прислала событие; возвращает запрос, который из него вышел, — или null, если спрашивать нечего. */
    fun handle(event: CameraEvent, session: CameraSession): Job? {
        if (!active) return null
        return when (event) {
            is CameraEvent.DisplayListChanged ->
                if (mayAsk(session, SonyOp.GET_DISPLAY_STRING_LIST)) fetchStrings(event.type, session) else null
            CameraEvent.LensChanged -> {
                // таблица прежнего объектива теперь неверна: пока нет новой (или совсем — камера не отвечает), расстояний нет
                lens = null
                if (mayAsk(session, SonyOp.GET_LENS_INFORMATION)) fetchLens(session) else null
            }
            is CameraEvent.FocusPositionResult -> {
                // по одной строке на исход за сеанс: чтобы было видно, что камера шлёт 0xC218 и с каким параметром, но не по строке
                // на каждую запись цели во время перевода
                if (focusResultsNoted.add(event.reached)) {
                    note("абсолютный фокус (0xC218): " + (if (event.reached) "дошёл до цели" else "до цели не дошёл") +
                        " — дальше такие события в журнал не пишу")
                }
                null
            }
        }
    }

    /**
     * Пришли свежие свойства: 0xE086 стал 1 — данные объектива готовы, спрашиваем (возвращает этот запрос); стал не 1 —
     * расстояний больше нет.
     */
    fun propertiesChanged(properties: Map<Int, SonyProperty>, session: CameraSession): Job? {
        if (!active) return null
        val enabled = properties[SonyProp.LENS_INFO_ENABLED]?.current?.intValue == 1L
        if (enabled == lensEnabled) return null
        lensEnabled = enabled
        if (enabled) return if (mayAsk(session, SonyOp.GET_LENS_INFORMATION)) fetchLens(session) else null
        lensRequest += 1
        lens = null
        note("данные объектива: 0xE086 больше не 1, расстояний нет")
        return null
    }

    private fun clearData() {
        strings = DisplayStrings()
        vendorCodeVersion = null
        lens = null
    }

    // MARK: - Запросы

    private fun fetchStrings(type: Long, session: CameraSession): Job {
        val token = generation
        return scope.launch {
            try {
                val fresh = session.displayStrings(type)
                if (generation != token) return@launch
                strings = strings.replace(type, fresh)
                note("подписи камеры (0xC20F, тип ${typeName(type)}): " + if (fresh.lists.isEmpty()) "списка больше нет" else summary(fresh))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(e, SonyOp.GET_DISPLAY_STRING_LIST, "подписи камеры (0x9215, тип ${typeName(type)})", token)
            }
        }
    }

    private fun fetchLens(session: CameraSession): Job {
        lensRequest += 1
        val request = lensRequest
        val token = generation
        return scope.launch {
            try {
                val table = session.lensInformation()
                if (generation != token || lensRequest != request) return@launch
                lens = table
                if (table != null) note("данные объектива (0x9223): " + summary(table))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation != token || lensRequest != request) return@launch
                // лучше без расстояния, чем с неверным
                lens = null
                failed(e, SonyOp.GET_LENS_INFORMATION, "данные объектива (0x9223)", token)
            }
        }
    }

    /**
     * Операция не вышла. Тайм-аут — камера не отвечает: она записывается ([SilentCameras]), в этом сеансе и в следующих ей новых
     * операций больше не шлём, в журнал одна строка. Камеру без id записать нельзя — тайм-аут называется, как любой отказ.
     * Остальные ошибки — строка про саму операцию.
     */
    private fun failed(error: Exception, operation: Int, what: String, token: Int) {
        if (generation != token) return
        if ((error as? TransportException)?.timeout != true) {
            note("$what: ${describe(error)}")
        } else if (silent.note(error, camera)) {
            note(String.format(Locale.ROOT, "камера не ответила на операцию 0x%04X за время ожидания — новые операции Sony (0x9215, " +
                "0x9216, 0x9223) ей до перезапуска программы не посылаю: подписи Creative Look — из таблиц Sony, расстояний нет", operation))
        } else if (camera == null) {
            note("$what: ${describe(error)}")
        }
    }

    // MARK: - Подписи

    private companion object {
        /** «0x13». */
        fun typeName(type: Long): String = "0x" + type.toString(16).uppercase(Locale.ROOT)

        /** «0x13 — 18 шт., 0x15 — 2 шт.». */
        fun summary(strings: DisplayStrings): String =
            strings.lists.keys.sorted().joinToString(", ") { type -> "${typeName(type)} — ${strings.lists[type]?.size ?: 0} шт." }

        /** «таблиц 1, пар 12, шкала 0…65535». */
        fun summary(lens: LensInformation): String =
            "таблиц ${lens.tableCount}, пар ${lens.tables.joinToString(" + ") { it.size.toString() }}, " +
                "шкала 0…${if (lens.absoluteScale) "65535" else "100"}"

        fun describe(error: Exception): String = when {
            error is SessionException.OperationFailed -> String.format(Locale.ROOT, "камера ответила 0x%04X", error.code)
            error is PtpParseException -> "ответ не разобран (${error.message})"
            error is TransportException && error.disconnected -> "соединение закрыто"
            error is TransportException && error.timeout -> "нет ответа"
            error is TransportException -> error.message ?: "ошибка обмена"
            else -> error.toString()
        }
    }
}
