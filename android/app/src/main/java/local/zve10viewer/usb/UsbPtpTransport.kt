package local.zve10viewer.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
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
import kotlin.concurrent.thread

/**
 * PTP по USB (класс Still Image) через Android USB host: команда, данные и ответ — по bulk-каналам,
 * события — по interrupt-каналу. Номера транзакций и разбор контейнеров — здесь же.
 */
class UsbPtpTransport(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val timeoutMs: Int = 8000,
) : PtpTransport {
    private val bulkIn: UsbEndpoint
    private val bulkOut: UsbEndpoint
    private val interruptIn: UsbEndpoint?
    private val chunk = ByteArray(CHUNK)
    private var nextTransactionId = 1
    private var sessionOpen = false
    @Volatile private var closed = false
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
        startEventReader()
    }

    @Synchronized
    override fun send(operation: Int, params: List<Long>, outData: ByteArray?): PtpReply {
        if (closed) throw TransportException("связь закрыта", disconnected = true)
        // вне сессии (GetDeviceInfo, OpenSession) transaction id — 0, внутри — по порядку с 1
        val transactionId = if (sessionOpen) nextTransactionId++ else 0
        write(PtpContainer.command(operation, transactionId, params))
        if (outData != null) write(PtpContainer.data(operation, transactionId, outData))
        var data = ByteArray(0)
        while (true) {
            val container = readContainer()
            when (container.type) {
                PtpContainer.DATA -> data = container.payload
                PtpContainer.RESPONSE -> {
                    val reply = PtpReply(container.code, container.params(), data)
                    if (operation == SonyOp.OPEN_SESSION && (reply.isOK || reply.code == 0x201E)) {
                        sessionOpen = true
                        nextTransactionId = 1
                    }
                    return reply
                }
                else -> Log.w(TAG, "неожиданный контейнер типа ${container.type}")
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { connection.releaseInterface(usbInterface) }
        runCatching { connection.close() }
        eventChannel.close()
    }

    private fun write(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = connection.bulkTransfer(bulkOut, bytes, offset, bytes.size - offset, timeoutMs)
            if (n < 0) fail("запись в камеру не прошла")
            offset += n
        }
    }

    private class Container(val type: Int, val code: Int, val bytes: ByteArray) {
        val payload: ByteArray get() = bytes.copyOfRange(PtpContainer.HEADER, bytes.size)
        fun params(): List<Long> = (PtpContainer.HEADER until bytes.size - 3 step 4).map { le32(bytes, it) }
    }

    /** Читает один контейнер целиком: первый пакет несёт длину, остальное дочитывается кусками. */
    private fun readContainer(): Container {
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
        return Container(type, code, bytes)
    }

    private fun startEventReader() {
        val endpoint = interruptIn ?: return
        thread(name = "ptp-events", isDaemon = true) {
            val buffer = ByteArray(endpoint.maxPacketSize.coerceAtLeast(64))
            while (!closed) {
                val n = connection.bulkTransfer(endpoint, buffer, buffer.size, 500)
                if (n >= PtpContainer.HEADER && le16(buffer, 4) == PtpContainer.EVENT) {
                    val params = (PtpContainer.HEADER until n - 3 step 4).map { le32(buffer, it) }
                    eventChannel.trySend(PtpEvent(le16(buffer, 6), params))
                }
            }
        }
    }

    private fun fail(message: String): Nothing {
        throw TransportException(message, disconnected = closed)
    }

    companion object {
        private const val TAG = "ZVTransport"
        private const val CHUNK = 256 * 1024

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

        private fun le32(b: ByteArray, at: Int): Long =
            (le16(b, at).toLong()) or (le16(b, at + 2).toLong() shl 16)
    }
}
