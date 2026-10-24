package local.zve10viewer.ptp

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Пакет PTP/IP: u32 длина (вместе с заголовком), u32 тип, затем содержимое. */
data class PtpIpPacket(val type: Int, val payload: ByteArray) {
    override fun equals(other: Any?) = other is PtpIpPacket && other.type == type && other.payload.contentEquals(payload)
    override fun hashCode() = type * 31 + payload.contentHashCode()
}

sealed class PtpIpInitResult {
    data class Accepted(val connectionNumber: Long, val cameraName: String) : PtpIpInitResult()
    /** 1 — программа не сопряжена с камерой. */
    data class Rejected(val reason: Long) : PtpIpInitResult()
}

class PtpIpRejected(val reason: Long) : Exception("камера отклонила подключение (причина $reason)")

/** PTP поверх TCP (CIPA DC-005): два соединения на порт 15740 — команды с данными и события. */
object PtpIp {
    const val PORT = 15740

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
 * а TCP отдаёт их по 1–60 КБ.
 */
class PtpIpPacketReader {
    private var buffer = ByteArray(INITIAL)
    private var start = 0
    private var end = 0

    fun feed(data: ByteArray, count: Int = data.size) {
        if (end + count > buffer.size) {
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
        data.copyInto(buffer, end, 0, count)
        end += count
    }

    /** Следующий целый пакет; null — пакет ещё не дочитан. */
    fun next(): PtpIpPacket? {
        if (end - start < 8) return null
        val length = u32(start)
        val type = u32(start + 4).toInt()
        if (length < 8) throw PtpParseException("неверная длина пакета $length")
        if (end - start < length) return null
        val packet = PtpIpPacket(type, buffer.copyOfRange(start + 8, start + length.toInt()))
        start += length.toInt()
        if (start == end) {
            start = 0
            end = 0
            // после большого файла не держим мегабайты впустую
            if (buffer.size > 4 * 1024 * 1024) buffer = ByteArray(INITIAL)
        }
        return packet
    }

    private fun u32(at: Int): Long =
        (0 until 4).fold(0L) { acc, i -> acc or ((buffer[at + i].toLong() and 0xFF) shl (8 * i)) }

    private companion object {
        const val INITIAL = 64 * 1024
    }
}

/**
 * Ответ на одну операцию: данные (Start Data → Data… → End Data) и ответ камеры.
 * Пакеты чужих транзакций — остатки оборванного обмена — пропускаются.
 */
class PtpIpTransaction(private val transactionId: Long) {
    private var data = ByteArrayOutputStream()

    /** Возвращает ответ, когда он пришёл; до этого — null. */
    fun consume(packet: PtpIpPacket): PtpReply? {
        val r = ByteReader(packet.payload)
        return when (packet.type) {
            PtpIp.START_DATA -> {
                // длина известна заранее — буфер сразу нужного размера, без пересборок
                if (r.remaining >= 12 && r.u32() == transactionId) {
                    val total = r.u64()
                    if (total in 1..MAX_PRESIZE && data.size() == 0) data = ByteArrayOutputStream(total.toInt())
                }
                null
            }
            PtpIp.DATA, PtpIp.END_DATA -> {
                if (r.remaining >= 4 && r.u32() == transactionId) data.write(packet.payload, 4, packet.payload.size - 4)
                null
            }
            PtpIp.OPERATION_RESPONSE -> {
                if (r.remaining < 6) return null
                val code = r.u16()
                if (r.u32() != transactionId) return null
                val params = ArrayList<Long>()
                while (r.remaining >= 4) params += r.u32()
                PtpReply(code, params, data.toByteArray())
            }
            else -> null
        }
    }

    private companion object {
        const val MAX_PRESIZE = 256L * 1024 * 1024
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
