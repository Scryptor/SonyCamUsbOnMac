package local.zve10viewer.ptp

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Пакет PTP/IP: u32 длина (вместе с заголовком), u32 тип, затем содержимое. */
data class PtpIpPacket(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?) = other is PtpIpPacket && other.type == type && other.payload.contentEquals(payload)
    override fun hashCode() = type * 31 + payload.contentHashCode()
}

/**
 * Пакет прямо в буфере читателя, без копии: содержимое — `bytes[offset, offset + length)`.
 * Действует, пока у читателя не попросили следующий пакет или не дочитали поток.
 */
class PtpIpPacketView {
    var type = 0
        internal set
    var bytes: ByteArray = ByteArray(0)
        internal set
    var offset = 0
        internal set
    var length = 0
        internal set

    fun toPacket() = PtpIpPacket(type, bytes.copyOfRange(offset, offset + length))
}

sealed class PtpIpInitResult {
    data class Accepted(val connectionNumber: Long, val cameraName: String) : PtpIpInitResult()
    /** 1 — программа не сопряжена с камерой, 2 — камера занята, 3 — без причины (CIPA DC-X005). */
    data class Rejected(val reason: Long) : PtpIpInitResult()
}

class PtpIpRejected(val reason: Long) : Exception("камера отклонила подключение (причина $reason)")

/** Камера не принимает соединение на порту PTP/IP: PC Remote выключен или камера в другом режиме. */
class PtpIpRefused : Exception("камера не принимает подключение")

/** PTP поверх TCP (CIPA DC-005): два соединения на порт 15740 — команды с данными и события. */
object PtpIp {
    const val PORT = 15740
    /** Адрес камеры, когда она сама раздаёт сеть (Wi-Fi Direct): у камер Sony он всегда такой. */
    const val CAMERA_ACCESS_POINT_HOST = "192.168.122.1"

    /** Камера раздаёт сеть сама — связь напрямую, без роутера. */
    fun isCameraAccessPoint(host: String): Boolean = host == CAMERA_ACCESS_POINT_HOST

    const val INIT_COMMAND_REQUEST = 1
    const val INIT_COMMAND_ACK = 2
    const val INIT_EVENT_REQUEST = 3
    const val INIT_EVENT_ACK = 4
    const val INIT_FAIL = 5
    const val OPERATION_REQUEST = 6
    const val OPERATION_RESPONSE = 7
    const val EVENT = 8
    const val START_DATA = 9
    const val DATA = 10
    const val END_DATA = 12
    const val PROBE_REQUEST = 13
    const val PROBE_RESPONSE = 14

    /** Причины Init Fail. Только первая значит, что камера программу не знает. */
    const val INIT_FAIL_NOT_PAIRED = 1L
    const val INIT_FAIL_BUSY = 2L

    /** Приветствие: GUID программы, её имя (его камера показывает при сопряжении) и версия протокола 1.0. */
    fun initCommandRequest(guid: ByteArray, name: String): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(guid)
        name.forEach { body.write(le16(it.code)) }
        body.write(le16(0))
        body.write(le32(0x0001_0000))
        return packet(INIT_COMMAND_REQUEST, body.toByteArray())
    }

    fun initEventRequest(connectionNumber: Long): ByteArray = packet(INIT_EVENT_REQUEST, le32(connectionNumber))

    /** dataOut — у операции есть данные от программы к камере (запись значения). */
    fun operationRequest(operation: Int, transactionId: Long, params: List<Long>, dataOut: Boolean): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(le32(if (dataOut) 2 else 1))
        body.write(le16(operation))
        body.write(le32(transactionId))
        params.forEach { body.write(le32(it)) }
        return packet(OPERATION_REQUEST, body.toByteArray())
    }

    fun startData(transactionId: Long, length: Long): ByteArray =
        packet(START_DATA, le32(transactionId) + ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(length).array())

    fun endData(transactionId: Long, payload: ByteArray): ByteArray = packet(END_DATA, le32(transactionId) + payload)

    val probeResponse: ByteArray get() = packet(PROBE_RESPONSE, ByteArray(0))

    /** Проба «ты здесь?» по событийному соединению: камера отвечает ProbeResponse за 5–50 мс. */
    val probeRequest: ByteArray get() = packet(PROBE_REQUEST, ByteArray(0))

    fun parseInitReply(packet: PtpIpPacket): PtpIpInitResult {
        val r = ByteReader(packet.payload)
        return when (packet.type) {
            INIT_COMMAND_ACK -> {
                val connection = r.u32()
                r.skip(16)  // GUID камеры
                val name = StringBuilder()
                while (r.remaining >= 2) {
                    val unit = r.u16()
                    if (unit == 0) break
                    name.append(unit.toChar())
                }
                PtpIpInitResult.Accepted(connection, name.toString())
            }
            INIT_FAIL -> PtpIpInitResult.Rejected(if (r.remaining >= 4) r.u32() else 0)
            else -> throw PtpParseException("неожиданный пакет ${packet.type}")
        }
    }

    fun parseEvent(packet: PtpIpPacket): PtpEvent? {
        if (packet.type != EVENT) return null
        val r = ByteReader(packet.payload)
        if (r.remaining < 6) return null
        val code = r.u16()
        r.u32()
        val params = ArrayList<Long>()
        while (r.remaining >= 4) params += r.u32()
        return PtpEvent(code, params)
    }

    fun packet(type: Int, payload: ByteArray): ByteArray = le32(8L + payload.size) + le32(type.toLong()) + payload

    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
    private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }
    private fun le32(v: Int) = le32(v.toLong())
}

/**
 * Поток TCP приходит кусками: копим байты и отдаём целые пакеты.
 * Буфер растёт удвоением и не пересобирается на каждом куске: камера шлёт файл пакетами по 2 МБ,
 * а TCP отдаёт их по 1–60 КБ. Из сокета читаем прямо в этот буфер, а пакеты отдаём без копии (`nextView`).
 */
class PtpIpPacketReader {
    private var buffer = ByteArray(INITIAL)
    private var start = 0
    private var end = 0
    private val view = PtpIpPacketView()

    fun feed(data: ByteArray, count: Int = data.size) {
        makeRoom(count)
        data.copyInto(buffer, end, 0, count)
        end += count
    }

    /**
     * Читает из потока сразу в буфер, без промежуточного массива. Возвращает, сколько байт пришло;
     * -1 — поток закончился. Блокирует, как и само чтение.
     */
    fun readFrom(input: InputStream): Int {
        makeRoom(MIN_READ)
        val n = input.read(buffer, end, buffer.size - end)
        if (n > 0) end += n
        return n
    }

    /** Следующий целый пакет (копия); null — пакет ещё не дочитан. */
    fun next(): PtpIpPacket? = nextView()?.toPacket()

    /** Следующий целый пакет прямо в буфере; null — пакет ещё не дочитан. Действует до следующего вызова. */
    fun nextView(): PtpIpPacketView? {
        if (end - start < 8) return null
        val length = u32(start)
        val type = u32(start + 4).toInt()
        // длина меньше заголовка или больше любого файла камеры — поток сбился, дальше его не разобрать
        if (length < 8 || length > MAX_PACKET) throw PtpParseException("неверная длина пакета $length")
        if (end - start < length) return null
        view.type = type
        view.bytes = buffer
        view.offset = start + 8
        view.length = length.toInt() - 8
        start += length.toInt()
        if (start == end) {
            start = 0
            end = 0
            // после большого файла не держим мегабайты впустую; отданный пакет ещё смотрит в старый буфер — он живёт
            if (buffer.size > 4 * 1024 * 1024) buffer = ByteArray(INITIAL)
        }
        return view
    }

    /** Место ещё под count байт в конце: сдвигаем недочитанное в начало или растим буфер удвоением. */
    private fun makeRoom(count: Int) {
        if (end + count <= buffer.size) return
        val used = end - start
        if (used + count <= buffer.size) {
            buffer.copyInto(buffer, 0, start, end)
        } else {
            val grown = ByteArray(maxOf(buffer.size * 2, used + count))
            buffer.copyInto(grown, 0, start, end)
            buffer = grown
        }
        start = 0
        end = used
    }

    private fun u32(at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((buffer[at + i].toLong() and 0xFF) shl (8 * i)) }

    private companion object {
        const val INITIAL = 64 * 1024
        /** Сколько места держать под одно чтение из сокета. */
        const val MIN_READ = 64 * 1024
        const val MAX_PACKET = 256L * 1024 * 1024
    }
}

/**
 * Ответ на одну операцию: данные (Start Data → Data… → End Data) и ответ камеры.
 * Пакеты чужих транзакций — остатки оборванного обмена — пропускаются.
 * Данные собираются в буфер ровно того размера, что объявила камера: кадр отдаётся без лишней копии.
 */
class PtpIpTransaction(private val transactionId: Long) {
    private var data = EMPTY
    private var filled = 0

    /** Возвращает ответ, когда он пришёл; до этого — null. */
    fun consume(packet: PtpIpPacket): PtpReply? = consume(packet.type, packet.payload, 0, packet.payload.size)

    fun consume(packet: PtpIpPacketView): PtpReply? = consume(packet.type, packet.bytes, packet.offset, packet.length)

    private fun consume(type: Int, bytes: ByteArray, offset: Int, length: Int): PtpReply? = when (type) {
        PtpIp.START_DATA -> {
            // длина известна заранее — буфер сразу нужного размера, без пересборок
            if (length >= 12 && u32(bytes, offset) == transactionId) {
                val total = u32(bytes, offset + 4) or (u32(bytes, offset + 8) shl 32)
                if (total in 1..MAX_PRESIZE && filled == 0) data = ByteArray(total.toInt())
            }
            null
        }
        PtpIp.DATA, PtpIp.END_DATA -> {
            if (length >= 4 && u32(bytes, offset) == transactionId) append(bytes, offset + 4, length - 4)
            null
        }
        PtpIp.OPERATION_RESPONSE -> {
            if (length < 6 || u32(bytes, offset + 2) != transactionId) {
                null
            } else {
                val code = (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)
                val params = ArrayList<Long>()
                var at = offset + 6
                while (at + 4 <= offset + length) {
                    params += u32(bytes, at)
                    at += 4
                }
                // камера прислала ровно объявленное — отдаём сам буфер
                PtpReply(code, params, if (filled == data.size) data else data.copyOf(filled))
            }
        }
        else -> null
    }

    private fun append(bytes: ByteArray, offset: Int, count: Int) {
        if (filled + count > data.size) data = data.copyOf(maxOf(data.size * 2, filled + count))
        bytes.copyInto(data, filled, offset, offset + count)
        filled += count
    }

    private companion object {
        const val MAX_PRESIZE = 256L * 1024 * 1024
        val EMPTY = ByteArray(0)

        fun u32(b: ByteArray, at: Int): Long =
            (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
    }
}

/** Поиск камеры в сети: камеры Sony в режиме PC Remote по Wi-Fi отвечают на SSDP M-SEARCH службой DigitalImaging. */
object Ssdp {
    const val MULTICAST_HOST = "239.255.255.250"
    const val PORT = 1900
    const val SEARCH_TARGET = "urn:schemas-sony-com:service:DigitalImaging:1"

    /** host — адрес камеры в сети, usn — постоянный идентификатор камеры. */
    data class Response(val host: String, val usn: String)

    fun searchRequest(): ByteArray = ("M-SEARCH * HTTP/1.1\r\nHOST: $MULTICAST_HOST:$PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n" +
        "ST: $SEARCH_TARGET\r\n\r\n").toByteArray()

    /** Ответ камеры Sony со службой DigitalImaging; всё остальное в сети — null. */
    fun parse(text: String): Response? {
        val headers = text.split("\r\n").mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        if (headers["ST"] != SEARCH_TARGET && headers["NT"] != SEARCH_TARGET) return null
        val host = headers["LOCATION"]?.let { runCatching { URI(it).host }.getOrNull() } ?: return null
        val usn = headers["USN"]?.substringBefore("::") ?: host
        return Response(host, usn)
    }
}
