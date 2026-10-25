package local.zve10viewer.wifi

import android.content.Context
import android.net.Network
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import local.zve10viewer.debug.LinkJournal
import local.zve10viewer.ptp.DataProgress
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpInitResult
import local.zve10viewer.ptp.PtpIpPacket
import local.zve10viewer.ptp.PtpIpPacketReader
import local.zve10viewer.ptp.PtpIpRefused
import local.zve10viewer.ptp.PtpIpRejected
import local.zve10viewer.ptp.PtpIpTransaction
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.Ssdp
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.sony.SonyOp
import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
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

/** Что со связью по Wi-Fi — для контроллера и экрана. */
data class PtpIpLink(
    /** Провал: ждём ответа, а ничего не приходит. Сеанс держим, тайм-ауты операций не идут. */
    val outage: Boolean = false,
    /** Время ответа камеры на пробу, мс; null — ещё не мерили. */
    val rttMs: Long? = null,
    /** Номер замера: одинаковые числа подряд — всё равно новые замеры. */
    val rttSamples: Int = 0,
    /** Почему транспорт закрылся; null — открыт. */
    val loss: LinkLoss? = null,
    /** Сколько провалов связи было на этом соединении и самый долгий из кончившихся, мс — для панели отладки. */
    val outages: Int = 0,
    val longestOutageMs: Long = 0,
)

/**
 * PTP поверх Wi-Fi (режимы камеры «Wi-Fi Access Point» и «Wi-Fi Direct»): канал команд и канал событий.
 * Сессию PTP открывает сеанс (как и по USB на Android); транзакции идут строго по одной и в порядке очереди.
 * Пауза связи — не обрыв: сеанс держится, пока камера может вернуться, а рвётся по решению слежения за связью
 * (`LinkSupervisor`): раз в 250 мс — байты по обоим соединениям, проба по событийному, ping камеры во время провала.
 */
class PtpIpTransport private constructor(
    val host: String,
    private val command: Socket,
    private val event: Socket,
    private val commandReader: PtpIpPacketReader,
    private val eventReader: PtpIpPacketReader,
    private val defaultTimeoutMs: Int,
    /** Сеть Wi-Fi, к которой привязаны сокеты; null — сеть по умолчанию. */
    val network: Network?,
    /** Отладка через прокси стенда: связь проверять HTTP-запросом сюда, а не ping (ping отвечал бы сам прокси). */
    private val linkCheckUrl: String?,
) : PtpTransport {
    /** Что отправить последним перед CloseSession при обычном закрытии. */
    class Farewell(val operation: Int, val params: List<Long>, val data: ByteArray?)

    /** Адрес планшета в сети, через который идёт связь с камерой. */
    val localHost: String? get() = command.localAddress?.hostAddress
    private val cameraAddress: InetAddress? = command.inetAddress
    /** Пинговать камеру во время провала — через тот же интерфейс, что и соединение. */
    private val localInterface: NetworkInterface? = runCatching { NetworkInterface.getByInetAddress(command.localAddress) }.getOrNull()
    private val commandFd: ParcelFileDescriptor? = runCatching { ParcelFileDescriptor.fromSocket(command) }.getOrNull()
    private val eventChannel = Channel<PtpEvent>(Channel.UNLIMITED)
    override val events: Flow<PtpEvent> = eventChannel.receiveAsFlow()
    /**
     * Транзакции по одной и в порядке очереди (честный замок): команда пользователя встаёт за текущим кадром,
     * а не ждёт, пока live view раз за разом снова берёт монитор.
     */
    private val lock = ReentrantLock(true)
    private var nextTransactionId = 1L
    @Volatile private var sessionOpen = false
    private val closed = AtomicBoolean(false)
    private val socketsClosed = AtomicBoolean(false)
    private val socketsClosedLatch = CountDownLatch(1)
    @Volatile private var loss: LinkLoss? = null
    /** С какого момента идёт провал (начало тишины), счёт провалов и самый долгий — для панели отладки. */
    @Volatile private var outageFrom: Long? = null
    @Volatile private var outages = 0
    @Volatile private var longestOutageMs = 0L
    private val supervisor = LinkSupervisor(TIMING, now())
    // когда пришли последние байты — пишут читающие потоки, смотрит слежение: горячий путь, без замка
    @Volatile private var lastRx = now()
    @Volatile private var commandRx = now()
    private val eventWrites = Any()
    private val _link = MutableStateFlow(PtpIpLink())
    val link: StateFlow<PtpIpLink> = _link

    /** Отправить последним перед CloseSession, например вернуть камере высокое качество превью; null — ничего. */
    @Volatile var farewell: Farewell? = null

    /** Принятые байты фазы данных от Start Data до ответа — для доли загрузки снимка. */
    override val dataProgress = DataProgress()

    init {
        thread(name = "ptpip-events", isDaemon = true) { readEvents() }
        thread(name = "ptpip-link", isDaemon = true) { watch() }
    }

    override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply {
        lock.lock()
        try {
            if (closed.get()) throw TransportException(closedMessage(), disconnected = true)
            return transact(operation, params, outData, (timeoutMs ?: defaultTimeoutMs).toLong())
        } finally {
            lock.unlock()
        }
    }

    /** Одна транзакция, под замком. timeoutMs — сколько можно не получать ни байта, а не ждать весь ответ. */
    private fun transact(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Long): PtpReply {
        val tid = nextTransactionId++
        val started = System.nanoTime()
        var progressAt = now()
        synchronized(supervisor) { supervisor.operationStarted(progressAt) }
        try {
            val out = command.getOutputStream()
            out.write(PtpIp.operationRequest(operation, tid, params, dataOut = outData != null))
            if (outData != null) {
                out.write(PtpIp.startData(tid, outData.size.toLong()))
                out.write(PtpIp.endData(tid, outData))
            }
            out.flush()
            val input = command.getInputStream()
            val transaction = PtpIpTransaction(tid, dataProgress)
            while (true) {
                val packet = commandReader.nextView()
                if (packet == null) {
                    quickAck()
                    // читаем кусками по 250 мс: между ними видно, не пропала ли связь и не вышло ли время
                    val n = try {
                        commandReader.readFrom(input)
                    } catch (e: SocketTimeoutException) {
                        0
                    }
                    val t = now()
                    when {
                        n < 0 -> throw IOException("камера закрыла соединение")
                        n > 0 -> {
                            lastRx = t
                            commandRx = t
                            progressAt = t
                        }
                        // тайм-аут — на тишину: большой RAW по Wi-Fi идёт дольше, но непрерывно. Пока связи нет, он
                        // не идёт, а сам по себе сеанс не закрывает — это решает слежение за связью
                        synchronized(supervisor) { supervisor.expired(t, progressAt, timeoutMs) } ->
                            throw TransportException("камера не ответила вовремя")
                    }
                    continue
                }
                if (packet.type == PtpIp.PROBE_REQUEST) {
                    out.write(PtpIp.probeResponse)
                    continue
                }
                val reply = transaction.consume(packet) ?: continue
                synchronized(supervisor) { supervisor.operationAnswered() }
                if (operation == SonyOp.OPEN_SESSION && (reply.isOK || reply.code == PtpResponseCode.SESSION_ALREADY_OPEN)) {
                    sessionOpen = true
                }
                reply.elapsedNanos = System.nanoTime() - started
                return reply
            }
        } catch (e: PtpParseException) {
            // поток сбился — дальше его не разобрать: это обрыв, а не повод крутиться на той же ошибке
            shutDown(LinkLoss.broken, rst = true, why = e.message)
            throw TransportException(closedMessage(), disconnected = true)
        } catch (e: IOException) {
            // если транспорт уже закрыло слежение (или программа), причина у него своя
            shutDown(LinkLoss.broken, rst = true, why = e.message)
            throw TransportException(closedMessage(), disconnected = true)
        } finally {
            // и при тайм-ауте или обрыве: недокачанное не должно висеть на кнопке спуска
            dataProgress.finish()
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

    /**
     * Обычное закрытие. Связь есть — сначала прощальная запись (если её задали) и CloseSession (так просит Sony),
     * но не дольше полсекунды: застрявшая транзакция закрытие не держит. Потом FIN — камера свободна сразу.
     * Не блокирует: всё это идёт в своём потоке, а следующий `connect` дождётся конца (до секунды).
     */
    override fun close() {
        // связи нет — FIN ядро повторит само; сессии нет — и закрывать нечего
        val graceful = sessionOpen && !synchronized(supervisor) { supervisor.isOutage }
        // ещё до закрытия: новый connect, начатый прямо сейчас, тоже должен его дождаться
        if (graceful) closing.set(this)
        if (!closed.get()) endOutageForStats()
        if (!markClosed(LinkLoss.closed)) return
        if (!graceful) {
            closeSockets(rst = false)
            return
        }
        thread(name = "ptpip-close", isDaemon = true) {
            val deadline = now() + CLOSE_BUDGET_MS
            try {
                if (lock.tryLock(CLOSE_BUDGET_MS, TimeUnit.MILLISECONDS)) {
                    try {
                        farewell?.let { runCatching { transact(it.operation, it.params, it.data, maxOf(deadline - now(), 100)) } }
                        runCatching { transact(SonyOp.CLOSE_SESSION, emptyList(), null, maxOf(deadline - now(), 100)) }
                    } finally {
                        lock.unlock()
                    }
                }
            } finally {
                closeSockets(rst = false)
            }
        }
    }

    /** Сброс по решению программы (например, сокеты привязаны к пропавшей сети): RST, дальше — как при обрыве. */
    fun abort(reason: LinkLoss = LinkLoss.broken) = shutDown(reason, rst = true, why = "по решению программы")

    /** Дождаться, пока сокеты закрыты: обычное закрытие идёт в своём потоке до полсекунды. */
    private fun awaitClosed(timeoutMs: Long): Boolean = socketsClosedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    /** Сеть Wi-Fi, к которой привязаны сокеты, пропала: провал сразу, не дожидаясь тишины. */
    fun networkLost() {
        synchronized(supervisor) {
            // байты, пришедшие ещё до пропажи сети, этот провал не кончают
            feedReceived()
            supervisor.networkLost(now())
        }
        LinkJournal.event("сеть Wi-Fi пропала — провал связи сразу")
        publish()
    }

    /** Транспорт закрывается сам: решение слежения или ошибка. rst — сбросом: камера освобождает сеанс сразу. */
    private fun shutDown(reason: LinkLoss, rst: Boolean, why: String? = null) {
        if (closed.get()) return
        endOutageForStats()
        if (!markClosed(reason)) return
        LinkJournal.event("соединение закрыто: ${reason.title}" + (why?.let { " ($it)" } ?: "") + if (rst) ", RST" else ", FIN")
        closeSockets(rst)
    }

    /** Закрываем посреди провала — он кончился вместе с соединением: в самый долгий (для панели отладки). */
    private fun endOutageForStats() {
        outageFrom?.let { longestOutageMs = maxOf(longestOutageMs, now() - it) }
    }

    /** Новых транзакций больше нет. false — транспорт уже закрыт (причина остаётся первой). */
    private fun markClosed(reason: LinkLoss): Boolean {
        if (!closed.compareAndSet(false, true)) return false
        loss = reason
        publish()
        return true
    }

    private fun closeSockets(rst: Boolean) {
        if (!socketsClosed.compareAndSet(false, true)) return
        if (rst) {
            // SO_LINGER 0: close() шлёт RST вместо FIN
            runCatching { command.setSoLinger(true, 0) }
            runCatching { event.setSoLinger(true, 0) }
        }
        // сначала копия дескриптора для QUICKACK: сокет закрывается вместе с последним
        runCatching { commandFd?.close() }
        runCatching { command.close() }
        runCatching { event.close() }
        eventChannel.close()
        socketsClosedLatch.countDown()
        closing.compareAndSet(this, null)
    }

    private fun closedMessage(): String = when (val reason = loss) {
        null, LinkLoss.closed -> "связь закрыта"
        else -> "связь по Wi-Fi: ${reason.title}"
    }

    private fun readEvents() {
        try {
            val input = event.getInputStream()
            while (!closed.get()) {
                val packet = eventReader.nextView()
                if (packet == null) {
                    val n = eventReader.readFrom(input)
                    if (n < 0) throw IOException("камера закрыла событийное соединение")
                    if (n > 0) lastRx = now()
                    continue
                }
                when (packet.type) {
                    PtpIp.PROBE_REQUEST -> writeEvent(PtpIp.probeResponse)
                    PtpIp.PROBE_RESPONSE -> {
                        synchronized(supervisor) { supervisor.probeAnswered(now()) }
                        publish()
                    }
                    else -> PtpIp.parseEvent(packet.toPacket())?.let { eventChannel.trySend(it) }
                }
            }
        } catch (e: Exception) {
            // событийное соединение оборвалось — сеанс без него не живёт
            if (!closed.get()) shutDown(LinkLoss.broken, rst = true, why = "события: ${e.message}")
        }
    }

    private fun writeEvent(bytes: ByteArray) {
        try {
            synchronized(eventWrites) { event.getOutputStream().write(bytes) }
        } catch (e: IOException) {
            if (!closed.get()) shutDown(LinkLoss.broken, rst = true, why = "отправка по событийному: ${e.message}")
        }
    }

    /** Раз в 250 мс: новые байты, проба, ping, решение об обрыве. */
    private fun watch() {
        while (!closed.get()) {
            try {
                Thread.sleep(TIMING.tickMs)
            } catch (e: InterruptedException) {
                return
            }
            if (closed.get()) return
            val (actions, since) = synchronized(supervisor) {
                feedReceived()
                supervisor.tick(now()) to supervisor.outageSince
            }
            val from = outageFrom
            if (since != from) {
                val now = now()
                if (from != null) {
                    longestOutageMs = maxOf(longestOutageMs, now - from)
                    if (since == null) LinkJournal.event("связь вернулась: провал %.1f с".format((now - from) / 1000.0))
                }
                if (since != null) {
                    outages++
                    LinkJournal.event("провал связи: ни байта %.1f с — сеанс держу, пингую камеру".format((now - since) / 1000.0))
                }
                outageFrom = since
            }
            for (action in actions) {
                when (action) {
                    LinkAction.SendProbe -> writeEvent(PtpIp.probeRequest)
                    LinkAction.Ping -> ping()
                    is LinkAction.Close -> shutDown(action.reason, action.rst)
                }
            }
            publish()
        }
    }

    /** Передать слежению, когда пришли последние байты. Под замком слежения. */
    private fun feedReceived() {
        supervisor.received(commandRx, command = true)
        supervisor.received(lastRx, command = false)
    }

    /** Ping камеры в своём потоке (до 0,8 с): отвечает ли она вообще, пока TCP молчит. */
    private fun ping() {
        thread(name = "ptpip-ping", isDaemon = true) {
            val url = linkCheckUrl
            val answered = if (url != null) httpCheck(url) else icmpPing()
            synchronized(supervisor) { supervisor.pinged(answered, now()) }
        }
    }

    private fun icmpPing(): Boolean {
        val address = cameraAddress ?: return false
        return runCatching {
            val netif = localInterface
            if (netif != null) address.isReachable(netif, 0, TIMING.pingTimeoutMs) else address.isReachable(TIMING.pingTimeoutMs)
        }.getOrDefault(false)
    }

    /** Связь есть, если по адресу отладки ответили 200 — через ту же сеть Wi-Fi, что и соединения. */
    private fun httpCheck(url: String): Boolean = runCatching {
        val target = URL(url)
        val connection = (network?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
        try {
            connection.connectTimeout = TIMING.pingTimeoutMs
            connection.readTimeout = TIMING.pingTimeoutMs
            connection.useCaches = false
            connection.instanceFollowRedirects = false
            connection.responseCode == HttpURLConnection.HTTP_OK
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private fun publish() {
        synchronized(_link) {
            val next = synchronized(supervisor) {
                PtpIpLink(supervisor.isOutage, supervisor.rttMs, supervisor.rttSamples, loss, outages, longestOutageMs)
            }
            if (_link.value != next) _link.value = next
        }
    }

    companion object {
        private val TIMING = LinkTiming()
        /** Чтение в транзакции — кусками по столько: между ними видно, не пропала ли связь и не вышло ли время. */
        private const val POLL_MS = 250
        /** Обычное закрытие: прощальная запись и CloseSession — не дольше этого. */
        private const val CLOSE_BUDGET_MS = 500L
        // Linux; в OsConstants их нет
        private const val TCP_KEEPIDLE = 4
        private const val TCP_KEEPINTVL = 5
        private const val TCP_KEEPCNT = 6
        private const val TCP_QUICKACK = 12
        private const val TCP_USER_TIMEOUT = 18
        /** DSCP CS5: в Wi-Fi (WMM) запросы и подтверждения идут очередью «видео» и не ждут прочий трафик планшета. */
        private const val TRAFFIC_CLASS_VIDEO = 0xA0

        /**
         * Соединение, которое сейчас закрывается обычным образом (CloseSession, FIN — до полсекунды в своём потоке).
         * Камера держит одно подключение: новое — только после того, как старое закрыто, иначе она его отвергнет.
         */
        private val closing = AtomicReference<PtpIpTransport?>()

        /** Время для слежения — монотонное: перевод часов не должен изображать провал связи. */
        private fun now(): Long = SystemClock.elapsedRealtime()

        /**
         * Приветствие и событийный канал. Возвращает транспорт и имя камеры.
         * initTimeoutMs — сколько ждать ответа на приветствие (при сопряжении камера ждёт подтверждения).
         * network — сеть Wi-Fi, к которой привязать сокеты (null — сеть по умолчанию).
         * linkCheckUrl — только для отладки через прокси: чем проверять связь во время провала вместо ping.
         * onSocket — сокет команд ещё до соединения: закрыв его, можно оборвать ожидание (до минуты при сопряжении).
         */
        fun connect(host: String, identity: PtpIpIdentity, network: Network? = null, connectTimeoutMs: Int = 5000,
                    initTimeoutMs: Int = 5000, timeoutMs: Int = 8000, linkCheckUrl: String? = null,
                    onSocket: (Socket) -> Unit = {}): Pair<PtpIpTransport, String> {
            closing.get()?.awaitClosed(1000)
            val command = open(host, network, connectTimeoutMs, onSocket)
            try {
                command.soTimeout = initTimeoutMs
                command.getOutputStream().write(PtpIp.initCommandRequest(identity.guid, identity.name))
                val commandReader = PtpIpPacketReader()
                when (val reply = PtpIp.parseInitReply(readPacket(command, commandReader))) {
                    is PtpIpInitResult.Rejected -> throw PtpIpRejected(reply.reason)
                    is PtpIpInitResult.Accepted -> {
                        val event = open(host, network, connectTimeoutMs)
                        try {
                            event.soTimeout = minOf(initTimeoutMs, 5000)
                            event.getOutputStream().write(PtpIp.initEventRequest(reply.connectionNumber))
                            val eventReader = PtpIpPacketReader()
                            val ack = readPacket(event, eventReader)
                            // Init Fail бывает и на событийном соединении: камере некуда принять второй канал
                            if (ack.type == PtpIp.INIT_FAIL) {
                                (PtpIp.parseInitReply(ack) as? PtpIpInitResult.Rejected)?.let { throw PtpIpRejected(it.reason) }
                            }
                            if (ack.type != PtpIp.INIT_EVENT_ACK) throw TransportException("камера не открыла канал событий")
                            event.soTimeout = 0
                            command.soTimeout = POLL_MS
                            val transport = PtpIpTransport(host, command, event, commandReader, eventReader, timeoutMs, network, linkCheckUrl)
                            return transport to reply.cameraName
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

        private fun open(host: String, network: Network?, timeoutMs: Int, onSocket: (Socket) -> Unit = {}): Socket {
            val socket = Socket()
            try {
                socket.tcpNoDelay = true
                // камера может пропасть без закрытия соединения (выключили, ушла из сети); таймеры — в tune
                socket.keepAlive = true
                runCatching { socket.trafficClass = TRAFFIC_CLASS_VIDEO }
                // только через Wi-Fi: если сеть по умолчанию другая (сотовая, VPN), иначе до камеры не достучаться
                network?.bindSocket(socket)
                // сокет отдаём ещё до соединения: закрыть его можно и пока идёт TCP-подключение
                onSocket(socket)
                socket.connect(InetSocketAddress(host, PtpIp.PORT), timeoutMs)
                tune(socket)
                return socket
            } catch (e: ConnectException) {
                runCatching { socket.close() }
                if (isRefused(e)) throw PtpIpRefused()
                throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                throw e
            }
        }

        /**
         * Пауза связи — не обрыв. Когда рвать, решает слежение за связью; keepalive (10 с, затем 36 раз по 5 с) и предел
         * повторов (TCP_USER_TIMEOUT 190 с) — запасной предел около 3 минут. Без этого Linux ждал бы 2 ч до первой
         * проверки и ~15 мин повторов.
         */
        private fun tune(socket: Socket) {
            runCatching {
                ParcelFileDescriptor.fromSocket(socket).use { pfd ->
                    val fd = pfd.fileDescriptor
                    runCatching { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPIDLE, 10) }
                    runCatching { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPINTVL, 5) }
                    runCatching { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_KEEPCNT, 36) }
                    runCatching { Os.setsockoptInt(fd, OsConstants.IPPROTO_TCP, TCP_USER_TIMEOUT, 190_000) }
                }
            }
        }

        /** ECONNREFUSED: на порту PTP/IP никто не слушает — у камеры выключен PC Remote. */
        private fun isRefused(e: ConnectException): Boolean =
            (e.cause as? ErrnoException)?.errno == OsConstants.ECONNREFUSED || e.message?.contains("ECONNREFUSED") == true

        private fun readPacket(socket: Socket, reader: PtpIpPacketReader): PtpIpPacket {
            val input = socket.getInputStream()
            while (true) {
                reader.next()?.let { return it }
                if (reader.readFrom(input) < 0) throw IOException("камера закрыла соединение")
            }
        }
    }
}

/** Поиск камер Sony в сети по SSDP (служба DigitalImaging). */
object WiFiDiscovery {
    /**
     * Ответы, собранные за `seconds`; одна камера — один ответ. Блокирует — вызывать не в главном потоке.
     * Камера отвечает с задержкой до ~3 с, а многоадресный UDP по Wi-Fi теряется — запрос повторяем всё окно.
     * network — искать только в этой сети Wi-Fi, как и соединяться.
     */
    fun search(seconds: Double = 4.0, network: Network? = null): List<Ssdp.Response> {
        val found = LinkedHashMap<String, Ssdp.Response>()
        runCatching {
            DatagramSocket().use { socket ->
                network?.let { runCatching { it.bindSocket(socket) } }
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
