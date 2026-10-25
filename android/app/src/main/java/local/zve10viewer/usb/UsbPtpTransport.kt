package local.zve10viewer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import local.zve10viewer.ptp.PtpContainer
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.sony.SonyOp
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * PTP по USB (класс Still Image) через Android USB host: команда, данные и ответ — по bulk-каналам,
 * события — по interrupt-каналу. Номера транзакций и разбор контейнеров — здесь же.
 */
class UsbPtpTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val defaultTimeoutMs: Int = 8000,
) : PtpTransport {
    private val bulkIn: UsbEndpoint
    private val bulkOut: UsbEndpoint
    private val interruptIn: UsbEndpoint?
    private val chunk = ByteArray(CHUNK)
    private var nextTransactionId = 1
    private var sessionOpen = false
    @Volatile private var closed = false
    private var eventRequest: UsbRequest? = null
    private val eventChannel = Channel<PtpEvent>(Channel.UNLIMITED)
    override val events: Flow<PtpEvent> = eventChannel.receiveAsFlow()

    init {
        var input: UsbEndpoint? = null
        var output: UsbEndpoint? = null
        var interrupt: UsbEndpoint? = null
        for (i in 0 until usbInterface.endpointCount) {
            val ep = usbInterface.getEndpoint(i)
            when {
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_IN -> input = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT -> output = ep
                ep.type == UsbConstants.USB_ENDPOINT_XFER_INT && ep.direction == UsbConstants.USB_DIR_IN -> interrupt = ep
            }
        }
        bulkIn = input ?: throw TransportException("у камеры нет bulk IN")
        bulkOut = output ?: throw TransportException("у камеры нет bulk OUT")
        interruptIn = interrupt
        reset()
        startEventReader()
    }

    /**
     * Сброс по стандарту PTP поверх USB: запрос Device Reset (0x66), снятие «залипания» с каналов
     * и вычитывание остатков. Нужен, если прошлое подключение оборвалось посреди ответа камеры:
     * иначе камера ждёт, пока у неё заберут старые данные, и не принимает новые команды.
     */
    private fun reset() {
        connection.controlTransfer(0x21, 0x66, 0, usbInterface.id, null, 0, 1000)
        for (ep in listOf(bulkIn, bulkOut)) {
            // CLEAR_FEATURE(ENDPOINT_HALT) для конкретного канала
            connection.controlTransfer(0x02, 0x01, 0, ep.address, null, 0, 1000)
        }
        // Get Device Status (0x67): после сброса камера какое-то время отвечает «занята»
        val status = ByteArray(32)
        val started = System.currentTimeMillis()
        var code = -1
        for (attempt in 0 until 20) {
            val n = connection.controlTransfer(0xA1, 0x67, 0, usbInterface.id, status, status.size, 500)
            code = if (n >= 4) le16(status, 2) else -1
            if (code == 0x2001) break
            Thread.sleep(100)
        }
        Log.i(TAG, "сброс: состояние камеры 0x%04X через %d мс".format(code, System.currentTimeMillis() - started))
        // остатки старых ответов: читаем, пока два раза подряд ничего не придёт
        var quiet = 0
        repeat(64) {
            if (connection.bulkTransfer(bulkIn, chunk, chunk.size, 150) <= 0) {
                if (++quiet >= 2) return
            } else {
                quiet = 0
            }
        }
    }

    @Synchronized
    override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply {
        if (closed) throw TransportException("связь закрыта", disconnected = true)
        val timeout = timeoutMs ?: defaultTimeoutMs
        // вне сессии (GetDeviceInfo, OpenSession) transaction id — 0, внутри — по порядку с 1
        val transactionId = if (sessionOpen) nextTransactionId++ else 0
        val started = System.nanoTime()
        write(PtpContainer.command(operation, transactionId, params), timeout)
        if (outData != null) write(PtpContainer.data(operation, transactionId, outData), timeout)
        var data = ByteArray(0)
        while (true) {
            val container = readContainer(timeout)
            // остатки прошлых обменов (оборванное подключение) отсеиваем по номеру транзакции и коду
            if (container.transactionId != transactionId ||
                (container.type == PtpContainer.DATA && container.code != operation)) {
                Log.w(TAG, "пропускаю чужой контейнер: тип ${container.type}, транзакция ${container.transactionId}")
                continue
            }
            when (container.type) {
                PtpContainer.DATA -> data = container.payload
                PtpContainer.RESPONSE -> {
                    val reply = PtpReply(container.code, container.params(), data)
                    if (operation == SonyOp.OPEN_SESSION && (reply.isOK || reply.code == 0x201E)) {
                        sessionOpen = true
                        nextTransactionId = 1
                    }
                    reply.elapsedNanos = System.nanoTime() - started
                    return reply
                }
                else -> Log.w(TAG, "неожиданный контейнер типа ${container.type}")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        eventRequest?.let { runCatching { it.cancel() } }
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        eventChannel.close()
    }

    private fun write(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val n = connection.bulkTransfer(bulkOut, bytes, offset, bytes.size - offset, timeoutMs)
            if (n < 0) fail("запись в камеру не прошла")
            offset += n
        }
    }

    private class Container(val type: Int, val code: Int, val transactionId: Int, val bytes: ByteArray) {
        val payload: ByteArray get() = bytes.copyOfRange(PtpContainer.HEADER, bytes.size)
        fun params(): List<Long> = (PtpContainer.HEADER until bytes.size - 3 step 4).map { le32(bytes, it) }
    }

    /** Читает один контейнер целиком: первый пакет несёт длину, остальное дочитывается кусками. */
    private fun readContainer(timeoutMs: Int): Container {
        var n: Int
        var empty = 0
        while (true) {
            n = connection.bulkTransfer(bulkIn, chunk, chunk.size, timeoutMs)
            if (n < 0) fail("камера не ответила")
            // пакет нулевой длины завершает данные, кратные размеру пакета, — пропускаем
            if (n == 0) {
                if (++empty > 4) fail("камера присылает пустые пакеты")
                continue
            }
            break
        }
        if (n < PtpContainer.HEADER) fail("слишком короткий контейнер: $n байт")
        val length = le32(chunk, 0).toInt()
        val type = le16(chunk, 4)
        val code = le16(chunk, 6)
        if (length < PtpContainer.HEADER) fail("неверная длина контейнера: $length")
        val bytes = ByteArray(length)
        System.arraycopy(chunk, 0, bytes, 0, minOf(n, length))
        var received = n
        while (received < length) {
            val m = connection.bulkTransfer(bulkIn, chunk, chunk.size, timeoutMs)
            if (m < 0) fail("данные от камеры оборвались на $received из $length байт")
            System.arraycopy(chunk, 0, bytes, received, minOf(m, length - received))
            received += m
        }
        return Container(type, code, le32(bytes, 8).toInt(), bytes)
    }

    /**
     * События ждём асинхронным запросом без тайм-аута. Синхронное чтение с тайм-аутом на этом канале
     * каждые ~1,5 с тормозило ответ камеры на запрос кадра на 150–200 мс — видео дёргалось.
     */
    private fun startEventReader() {
        val endpoint = interruptIn ?: return
        val request = UsbRequest()
        if (!request.initialize(connection, endpoint)) return
        eventRequest = request
        thread(name = "ptp-events", isDaemon = true) {
            val buffer = ByteBuffer.allocate(endpoint.maxPacketSize.coerceAtLeast(64)).order(ByteOrder.LITTLE_ENDIAN)
            while (!closed) {
                buffer.clear()
                @Suppress("DEPRECATION")
                if (!request.queue(buffer, buffer.capacity())) break
                val done = runCatching { connection.requestWait() }.getOrNull() ?: break
                if (done !== request || closed) continue
                val n = buffer.position()
                val bytes = ByteArray(n).also { buffer.rewind(); buffer.get(it) }
                if (n >= PtpContainer.HEADER && le16(bytes, 4) == PtpContainer.EVENT) {
                    val params = (PtpContainer.HEADER until n - 3 step 4).map { le32(bytes, it) }
                    eventChannel.trySend(PtpEvent(le16(bytes, 6), params))
                }
            }
        }
    }

    private fun fail(message: String): Nothing {
        throw TransportException(message, disconnected = closed)
    }

    companion object {
        private const val TAG = "ZVTransport"
        /** Больше 16 КБ за одно чтение ядро Android на части устройств не пропускает. */
        private const val CHUNK = 16 * 1024

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, at: Int): Long =
            (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)
    }
}
