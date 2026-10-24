package local.zve10viewer.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Коды типов данных PTP. Массив — тот же код с битом 0x4000. */
object PtpType {
    const val INT8 = 0x0001
    const val UINT8 = 0x0002
    const val INT16 = 0x0003
    const val UINT16 = 0x0004
    const val INT32 = 0x0005
    const val UINT32 = 0x0006
    const val INT64 = 0x0007
    const val UINT64 = 0x0008
    const val STRING = 0xFFFF
    const val ARRAY_FLAG = 0x4000
}

object PtpResponseCode {
    const val OK = 0x2001
    const val INVALID_OBJECT_HANDLE = 0x2009
    const val ACCESS_DENIED = 0x200F
    const val DEVICE_BUSY = 0x2019
    const val SESSION_ALREADY_OPEN = 0x201E
}

class PtpParseException(message: String) : Exception(message)

/** Последовательное чтение little-endian данных PTP. */
class ByteReader(private val bytes: ByteArray) {
    var offset = 0
        private set

    val remaining: Int get() = bytes.size - offset

    fun u8(): Int = read(1).toInt()
    fun u16(): Int = read(2).toInt()
    fun u32(): Long = read(4)
    fun u64(): Long = read(8)

    fun peekU16(): Int? = if (remaining >= 2) littleEndian(offset, 2).toInt() else null

    fun skip(n: Int) {
        need(n)
        offset += n
    }

    /** Строка PTP: u8 число символов UTF-16 (вместе с завершающим NUL), затем сами символы. */
    fun ptpString(): String {
        val count = u8()
        val units = CharArray(count) { u16().toChar() }
        return String(units).trimEnd('\u0000')
    }

    /** Массив PTP: u32 число элементов, затем элементы u16. */
    fun u16Array(): List<Int> {
        val start = offset
        val count = u32()
        if (count * 2 > remaining) throw PtpParseException("bad count $count at $start")
        return List(count.toInt()) { u16() }
    }

    private fun read(n: Int): Long {
        need(n)
        val value = littleEndian(offset, n)
        offset += n
        return value
    }

    private fun littleEndian(at: Int, count: Int): Long {
        var value = 0L
        for (i in 0 until count) value = value or ((bytes[at + i].toLong() and 0xFF) shl (8 * i))
        return value
    }

    private fun need(n: Int) {
        if (n < 0 || remaining < n) throw PtpParseException("truncated at $offset")
    }
}

/** Значение свойства PTP. Программе нужны в основном целые числа. */
sealed class PtpValue {
    data class Num(val value: Long) : PtpValue()
    data class Text(val value: String) : PtpValue()
    data class Items(val values: List<Long>) : PtpValue()
    data object Unsupported : PtpValue()

    val intValue: Long? get() = (this as? Num)?.value

    companion object {
        fun read(r: ByteReader, type: Int): PtpValue = when {
            type == PtpType.INT8 -> Num(r.u8().toByte().toLong())
            type == PtpType.UINT8 -> Num(r.u8().toLong())
            type == PtpType.INT16 -> Num(r.u16().toShort().toLong())
            type == PtpType.UINT16 -> Num(r.u16().toLong())
            type == PtpType.INT32 -> Num(r.u32().toInt().toLong())
            type == PtpType.UINT32 -> Num(r.u32())
            type == PtpType.INT64 || type == PtpType.UINT64 -> Num(r.u64())
            // 128-битные целые камера почти не использует — пропускаем
            type == 0x0009 || type == 0x000A -> {
                r.skip(16)
                Unsupported
            }
            type == PtpType.STRING -> Text(r.ptpString())
            (type and PtpType.ARRAY_FLAG) != 0 -> {
                val start = r.offset
                val count = r.u32()
                if (count > r.remaining) throw PtpParseException("bad count $count at $start")
                val element = type and PtpType.ARRAY_FLAG.inv()
                Items(List(count.toInt()) { read(r, element).intValue }.filterNotNull())
            }
            else -> throw PtpParseException("unsupported type $type")
        }

        /** Только само значение нужного размера, без контейнера. */
        fun encode(value: Long, type: Int): ByteArray {
            val size = when (type) {
                PtpType.INT8, PtpType.UINT8 -> 1
                PtpType.INT16, PtpType.UINT16 -> 2
                PtpType.INT32, PtpType.UINT32 -> 4
                PtpType.INT64, PtpType.UINT64 -> 8
                else -> 0
            }
            return ByteArray(size) { i -> (value ushr (8 * i)).toByte() }
        }
    }
}

data class PtpReply(val code: Int, val params: List<Long> = emptyList(), val data: ByteArray = ByteArray(0)) {
    val isOK: Boolean get() = code == PtpResponseCode.OK
}

data class PtpEvent(val code: Int, val params: List<Long> = emptyList())

/** Контейнеры PTP поверх USB (класс Still Image): длина, тип, код, transaction id, затем параметры или данные. */
object PtpContainer {
    const val COMMAND = 1
    const val DATA = 2
    const val RESPONSE = 3
    const val EVENT = 4
    const val HEADER = 12

    fun command(operation: Int, transactionId: Int, params: List<Long>): ByteArray {
        val buffer = header(HEADER + 4 * params.size, COMMAND, operation, transactionId)
        params.forEach { buffer.putInt(it.toInt()) }
        return buffer.array()
    }

    fun data(operation: Int, transactionId: Int, payload: ByteArray): ByteArray {
        val buffer = header(HEADER + payload.size, DATA, operation, transactionId)
        buffer.put(payload)
        return buffer.array()
    }

    private fun header(length: Int, type: Int, code: Int, transactionId: Int): ByteBuffer =
        ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(length)
            putShort(type.toShort())
            putShort(code.toShort())
            putInt(transactionId)
        }
}

class TransportException(message: String, val disconnected: Boolean = false) : Exception(message)

/** Канал до камеры: отправка PTP-операций и поток событий от камеры. */
interface PtpTransport {
    /**
     * outData — только само значение, без контейнера: транспорт оборачивает его сам.
     * timeoutMs — сколько ждать ответа; null — сколько принято у транспорта.
     */
    fun send(operation: Int, params: List<Long> = emptyList(), outData: ByteArray? = null, timeoutMs: Int? = null): PtpReply
    val events: kotlinx.coroutines.flow.Flow<PtpEvent>
    fun close()
}
