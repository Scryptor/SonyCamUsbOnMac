package local.zve10viewer.wifi

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpInitResult
import local.zve10viewer.ptp.PtpIpPacket
import local.zve10viewer.ptp.PtpIpPacketReader
import local.zve10viewer.ptp.PtpIpRejected
import local.zve10viewer.ptp.PtpIpTransaction
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.Ssdp
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.sony.SonyOp
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/** Кем программа представляется камере. GUID создаётся один раз: камера помнит сопряжение по нему. */
data class PtpIpIdentity(val guid: ByteArray, val name: String) {
    companion object {
        fun load(context: Context): PtpIpIdentity {
            val prefs = context.getSharedPreferences("zv", Context.MODE_PRIVATE)
            val stored = prefs.getString("ptpipGUID", null)?.chunked(2)?.mapNotNull { it.toIntOrNull(16)?.toByte() }
            if (stored != null && stored.size == 16) return PtpIpIdentity(stored.toByteArray(), NAME)
            return reset(context)
        }

        /** Новый GUID: прежнее сопряжение в камере к программе больше не относится — нужно сопрячь заново. */
        fun reset(context: Context): PtpIpIdentity {
            val guid = ByteArray(16) { (0..255).random().toByte() }
            context.getSharedPreferences("zv", Context.MODE_PRIVATE).edit()
                .putString("ptpipGUID", guid.joinToString("") { "%02x".format(it) }).apply()
            return PtpIpIdentity(guid, NAME)
        }

        // латиницей: имя показывается на экране камеры при сопряжении
        private const val NAME = "ZV-E10 Viewer Android"
    }
}

/**
 * PTP поверх Wi-Fi (режимы камеры «Wi-Fi Access Point» и «Wi-Fi Direct»): канал команд и канал событий.
 * Сессию PTP открывает сеанс (как и по USB на Android); транзакции идут строго по одной.
 */
class PtpIpTransport private constructor(
    val host: String,
    private val command: Socket,
    private val event: Socket,
    private val defaultTimeoutMs: Int,
) : PtpTransport {
    /** Адрес планшета в сети, через который идёт связь с камерой. */
    val localHost: String? get() = command.localAddress?.hostAddress
    private val commandReader = PtpIpPacketReader()
    private val commandBuffer = ByteArray(READ_BUFFER)
    private val commandFd: ParcelFileDescriptor? = runCatching { ParcelFileDescriptor.fromSocket(command) }.getOrNull()
    private val eventChannel = Channel<PtpEvent>(Channel.UNLIMITED)
    override val events: Flow<PtpEvent> = eventChannel.receiveAsFlow()
    private var nextTransactionId = 1L
    private var timeoutsInRow = 0
    @Volatile private var closed = false

    init {
        thread(name = "ptpip-events", isDaemon = true) { readEvents() }
    }

    @Synchronized
    override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply {
        if (closed) throw TransportException("связь закрыта", disconnected = true)
        val tid = nextTransactionId++
        // тайм-аут — на паузу между пакетами: большой RAW по Wi-Fi идёт дольше, но непрерывно
        command.soTimeout = timeoutMs ?: defaultTimeoutMs
        try {
            val out = command.getOutputStream()
            out.write(PtpIp.operationRequest(operation, tid, params, dataOut = outData != null))
            if (outData != null) {
                out.write(PtpIp.startData(tid, outData.size.toLong()))
                out.write(PtpIp.endData(tid, outData))
            }
            out.flush()
            val transaction = PtpIpTransaction(tid)
            while (true) {
                val packet = readPacket(command, commandReader, commandBuffer) { quickAck() }
                if (packet.type == PtpIp.PROBE_REQUEST) {
                    out.write(PtpIp.probeResponse)
                    continue
                }
                transaction.consume(packet)?.let {
                    timeoutsInRow = 0
                    return it
                }
            }
        } catch (e: SocketTimeoutException) {
            // OpenSession сеанс сам повторяет с коротким тайм-аутом — его паузы не считаем обрывом
            if (operation != SonyOp.OPEN_SESSION) timeoutsInRow++
            if (timeoutsInRow >= 2) {
                close()
                throw TransportException("камера не отвечает", disconnected = true)
            }
            throw TransportException("камера не ответила вовремя")
        } catch (e: IOException) {
            close()
            throw TransportException("связь по Wi-Fi оборвалась", disconnected = true)
        }
    }

    /**
     * Подтверждать приём сразу (TCP_QUICKACK, Linux сбрасывает его после каждого чтения). Камера держит короткий
     * ответ «кадр не готов», пока не подтверждён предыдущий пакет, а Android откладывает подтверждение на ~40 мс:
     * без этого каждый такой ответ шёл ~60 мс вместо ~25, и по Wi-Fi выходило 16–19 кадров/с вместо 25.
     */
    private fun quickAck() {
        val fd = commandFd ?: return
        runCatching { Os.setsockoptInt(fd.fileDescriptor, OsConstants.IPPROTO_TCP, TCP_QUICKACK, 1) }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { commandFd?.close() }
        runCatching { command.close() }
        runCatching { event.close() }
        eventChannel.close()
    }

    private fun readEvents() {
        val reader = PtpIpPacketReader()
        val buffer = ByteArray(READ_BUFFER)
        try {
            while (!closed) {
                val packet = readPacket(event, reader, buffer)
                if (packet.type == PtpIp.PROBE_REQUEST) {
                    event.getOutputStream().write(PtpIp.probeResponse)
                } else {
                    PtpIp.parseEvent(packet)?.let { eventChannel.trySend(it) }
                }
            }
        } catch (e: Exception) {
            // событийный канал закрылся — камера пропала
        }
        close()
    }

    companion object {
        /**
         * Приветствие и событийный канал. Возвращает транспорт и имя камеры.
         * initTimeoutMs — сколько ждать ответа на приветствие (при сопряжении камера ждёт подтверждения).
         */
        /** onSocket — сокет команд сразу после соединения: закрыв его, можно оборвать ожидание (до минуты при сопряжении). */
        fun connect(host: String, identity: PtpIpIdentity, initTimeoutMs: Int = 5000, timeoutMs: Int = 8000,
                    onSocket: (Socket) -> Unit = {}): Pair<PtpIpTransport, String> {
            // сокет отдаём ещё до соединения: закрыть его можно и пока идёт TCP-подключение
            val command = open(host, onSocket)
            try {
                command.soTimeout = initTimeoutMs
                command.getOutputStream().write(PtpIp.initCommandRequest(identity.guid, identity.name))
                val buffer = ByteArray(READ_BUFFER)
                when (val reply = PtpIp.parseInitReply(readPacket(command, PtpIpPacketReader(), buffer))) {
                    is PtpIpInitResult.Rejected -> throw PtpIpRejected(reply.reason)
                    is PtpIpInitResult.Accepted -> {
                        val event = open(host)
                        try {
                            event.soTimeout = 5000
                            event.getOutputStream().write(PtpIp.initEventRequest(reply.connectionNumber))
                            val ack = readPacket(event, PtpIpPacketReader(), buffer)
                            if (ack.type != PtpIp.INIT_EVENT_ACK) throw TransportException("камера не открыла канал событий")
                            event.soTimeout = 0
                            return PtpIpTransport(host, command, event, timeoutMs) to reply.cameraName
                        } catch (e: Exception) {
                            runCatching { event.close() }
                            throw e
                        }
                    }
                }
            } catch (e: SocketTimeoutException) {
                runCatching { command.close() }
                throw TransportException("камера не ответила на приветствие")
            } catch (e: Exception) {
                runCatching { command.close() }
                throw e
            }
        }

        private fun open(host: String, onSocket: (Socket) -> Unit = {}): Socket = Socket().apply {
            tcpNoDelay = true
            // камера может пропасть без закрытия соединения (выключили, ушла из сети)
            keepAlive = true
            onSocket(this)
            connect(InetSocketAddress(host, PtpIp.PORT), 5000)
        }

        private const val READ_BUFFER = 64 * 1024
        /** Linux, нет в OsConstants. */
        private const val TCP_QUICKACK = 12

        private fun readPacket(socket: Socket, reader: PtpIpPacketReader, buffer: ByteArray, beforeRead: () -> Unit = {}): PtpIpPacket {
            while (true) {
                reader.next()?.let { return it }
                beforeRead()
                val n = socket.getInputStream().read(buffer)
                if (n < 0) throw IOException("камера закрыла соединение")
                reader.feed(buffer, n)
            }
        }
    }
}

/** Поиск камер Sony в сети по SSDP (служба DigitalImaging). */
object WiFiDiscovery {
    /**
     * Ответы, собранные за `seconds`; одна камера — один ответ. Блокирует — вызывать не в главном потоке.
     * Камера отвечает с задержкой до ~3 с, а многоадресный UDP по Wi-Fi теряется — запрос повторяем всё окно.
     */
    fun search(seconds: Double = 4.0): List<Ssdp.Response> {
        val found = LinkedHashMap<String, Ssdp.Response>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 200
                val target = InetSocketAddress(Ssdp.MULTICAST_HOST, Ssdp.PORT)
                val request = Ssdp.searchRequest()
                val buffer = ByteArray(4096)
                val deadline = System.currentTimeMillis() + (seconds * 1000).toLong()
                var lastSent = 0L
                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() - lastSent > 700) {
                        socket.send(DatagramPacket(request, request.size, target))
                        lastSent = System.currentTimeMillis()
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        Ssdp.parse(String(packet.data, 0, packet.length))?.let { found[it.usn] = it }
                    } catch (e: SocketTimeoutException) {
                        // ждём дальше
                    }
                }
            }
        }
        return found.values.toList()
    }
}
