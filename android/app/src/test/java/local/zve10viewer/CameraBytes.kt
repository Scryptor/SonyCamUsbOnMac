package local.zve10viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.sony.SonyProp

/**
 * Байты и камеры для тестов других камер Sony (0x9215, 0x9216, 0x9223): наборы данных Sony, DeviceInfo со своим списком
 * операций и «новая камера» на [FakeCamera] — та же, что у программы для Mac в OtherCamerasTests (`newerCamera`).
 */
internal object CameraBytes {
    fun fixture(name: String): ByteArray =
        CameraBytes::class.java.getResource("/fixtures/$name.bin")!!.readBytes()

    fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    fun le32(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** Набор Sony: u32 смещение до данных, u32 размер данных, сами данные. */
    fun dataset(body: ByteArray, offset: Long = 8, size: Long? = null, padding: Int = 0): ByteArray =
        le32(offset) + le32(size ?: body.size.toLong()) + ByteArray(padding) { 0xEE.toByte() } + body

    /** Список набора 0x9215: тип списка, тип данных PTP, пары «значение — строка». */
    class StringList(val type: Long, val dataType: Int, val strings: List<Pair<Long, String>>)

    /** Данные набора 0x9215: у строки — размер с нулём и UTF-8 с нулём. */
    fun stringLists(lists: List<StringList>): ByteArray {
        var body = le16(lists.size) + byteArrayOf(0, 0)
        for (list in lists) {
            body += le32(list.type) + le16(list.dataType) + le16(list.strings.size)
            for ((value, text) in list.strings) {
                val raw = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                body += PtpValue.encode(value, list.dataType) + le16(raw.size) + raw
            }
        }
        return body
    }

    /** Минимальный DeviceInfo с заданным списком операций (как FakeCamera.deviceInfo на Mac). */
    fun deviceInfo(operations: List<Int>): ByteArray {
        fun ptpString(s: String): ByteArray {
            if (s.isEmpty()) return byteArrayOf(0)
            var d = byteArrayOf((s.length + 1).toByte())
            for (c in s + "\u0000") d += le16(c.code)
            return d
        }
        var d = le16(100) + le32(0x11) + le16(100) + ptpString("") + le16(0)
        for (list in listOf(operations, emptyList(), emptyList(), emptyList(), emptyList())) {
            d += le32(list.size.toLong())
            for (code in list) d += le16(code)
        }
        for (s in listOf("Sony Corporation", "ILCE-7M4", "2.00", "0")) d += ptpString(s)
        return d
    }

    /** Ответ камеры на 0x9215 с одним списком Creative Look: «ST» и «FL». */
    fun creativeLookOnly(): ByteArray =
        dataset(stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "ST", 6L to "FL")))))

    /** Пустой набор 0x9215 (ListTypeNum = 0): так камера отвечает на тип списка, которого у неё нет. */
    fun noLists(): ByteArray = dataset(stringLists(emptyList()))
}

/** Три операции новых камер Sony: подписи списков, версия кода, данные объектива. */
internal val NEW_OPERATIONS = listOf(0x9215, 0x9216, 0x9223)

/**
 * Новая камера для тестов сеанса: в GetDeviceInfo — три новые операции, на них она отвечает теми же байтами, что в фикстурах
 * (их отдаёт и поддельная камера в профиле newer); версия кода 3.10. [lensEnabled] — значение 0xE086; null — свойства нет.
 */
internal fun newerCamera(lensEnabled: Long? = 1, operations: List<Int> = listOf(0x9201, 0x9209) + NEW_OPERATIONS): FakeCamera {
    val camera = FakeCamera()
    if (lensEnabled != null) camera.props += FakeCamera.Prop(SonyProp.LENS_INFO_ENABLED, PtpType.UINT8, lensEnabled, listOf(0, 1), getSet = 0)
    camera.operations = operations
    val all = CameraBytes.fixture("display_strings")
    val looks = CameraBytes.creativeLookOnly()
    val lens = CameraBytes.fixture("lens_information")
    camera.answers[0x9215] = { params ->
        // версия списков 1.00 — в параметре ответа; другой тип списка — пустой набор
        PtpReply(PtpResponseCode.OK, listOf(100), if (params == listOf(0L)) all else if (params == listOf(0x13L)) looks else CameraBytes.noLists())
    }
    camera.answers[0x9216] = { PtpReply(PtpResponseCode.OK, listOf(0x0136L)) }
    // только метры (параметр 2), как просит программа; футы камера этого стенда не отдаёт
    camera.answers[0x9223] = { params -> if (params == listOf(2L)) PtpReply(PtpResponseCode.OK, data = lens) else PtpReply(0x2006) }
    return camera
}

/** Камера, которая шлёт события, когда ей скажут: события нужны сеансу, а поток никогда не кончается (сеанс не закрывается сам). */
internal class CameraWithEvents(val camera: FakeCamera = FakeCamera()) : PtpTransport by camera {
    val flow = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 64)
    override val events: Flow<PtpEvent> = flow
}

/**
 * Ждёт условия не дольше [seconds] — по настоящим часам, а не тестовым: обмен с камерой идёт в потоке ввода-вывода, и виртуальное
 * время за него не подождёт. Сломанный код роняет проверку, а не подвешивает тест.
 */
internal suspend fun eventually(seconds: Long = 5, condition: () -> Boolean): Boolean = withContext(Dispatchers.Default) {
    val end = System.nanoTime() + seconds * 1_000_000_000L
    while (System.nanoTime() < end) {
        if (condition()) return@withContext true
        delay(5)
    }
    condition()
}
