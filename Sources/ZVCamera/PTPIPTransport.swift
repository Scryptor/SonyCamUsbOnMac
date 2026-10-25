import Foundation
import Network
import ZVCore

/// Одно TCP-соединение PTP/IP: пакеты складываются в очередь по мере прихода, ожидание — с тайм-аутом.
/// Ничего не теряется, даже если ожидание оборвалось по тайм-ауту: пакет останется в очереди.
final class PTPIPChannel: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "zv.ptpip.channel", qos: .userInitiated)
    private let lock = NSLock()
    private var reader = PTPIPPacketReader()
    private var packets: [PTPIPPacket] = []
    private var waiter: (id: Int, since: Date, continuation: CheckedContinuation<PTPIPPacket, Error>)?
    private var waiterSeq = 0
    private var failure: Error?
    private var lastRx = Date()
    /// Связь пропала: ожидание пакета не истекает, пока она не вернётся или транспорт не закроют.
    private var paused = false

    private init(connection: NWConnection) {
        self.connection = connection
    }

    /// Когда по соединению в последний раз пришли байты.
    var lastReceived: Date {
        lock.lock()
        defer { lock.unlock() }
        return lastRx
    }

    func setPaused(_ value: Bool) {
        lock.lock()
        paused = value
        lock.unlock()
    }

    /// Адрес этого Mac в сети, через который идёт соединение.
    var localHost: String? {
        guard case let .hostPort(host, _) = connection.currentPath?.localEndpoint else { return nil }
        // у IPv6 бывает «%en0» — интерфейс в адресе не нужен
        return "\(host)".split(separator: "%").first.map(String.init)
    }

    /// Открывает соединение и ждёт готовности не дольше timeout.
    static func open(host: String, port: UInt16, timeout: Duration) async throws -> PTPIPChannel {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        // Связь по Wi-Fi может пропасть на десятки секунд: камера рядом и ждёт, а TCP сам довезёт данные, когда
        // связь вернётся. Когда рвать, решает слежение за связью в транспорте; keepalive и предел повторов — запасной
        // предел около 3 минут (раньше keepalive 5/2/3 рвал соединение уже через 11 с провала)
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 10
        tcp.keepaliveInterval = 5
        tcp.keepaliveCount = 36
        tcp.connectionDropTime = 190
        // подтверждать сегменты сразу, а не пачками: камера держит короткий ответ, пока не подтверждён предыдущий
        tcp.disableAckStretching = true
        let parameters = NWParameters(tls: nil, tcp: tcp)
        // запросы и подтверждения — в очередь «видео» Wi-Fi (WMM): их не задерживает другой трафик Mac
        parameters.serviceClass = .interactiveVideo
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!, using: parameters)
        let channel = PTPIPChannel(connection: connection)
        try await channel.start(timeout: timeout)
        return channel
    }

    private func start(timeout: Duration) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            let once = Once()
            connection.stateUpdateHandler = { [weak self] state in
                switch state {
                case .ready:
                    if once.claim() {
                        done.resume()
                        self?.receive()
                    }
                case .waiting:
                    // путь к камере временно недоступен (macOS переспрашивает доступ к локальной сети,
                    // Wi-Fi переподключается): соединение само вернётся в ready или завершится ошибкой —
                    // до подключения ждём до тайм-аута, после — просто ждём
                    break
                case .failed(let error):
                    if once.claim() { done.resume(throwing: TransportError.failed("\(error)")) }
                    self?.fail(TransportError.disconnected)
                case .cancelled:
                    if once.claim() { done.resume(throwing: TransportError.disconnected) }
                    self?.fail(TransportError.disconnected)
                default:
                    break
                }
            }
            connection.start(queue: queue)
            queue.asyncAfter(deadline: .now() + timeout.seconds) { [weak self] in
                if once.claim() {
                    self?.connection.cancel()
                    done.resume(throwing: TransportError.timeout(operation: 0))
                }
            }
        }
    }

    func write(_ data: Data) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            connection.send(content: data, completion: .contentProcessed { error in
                if error != nil { done.resume(throwing: TransportError.disconnected) } else { done.resume() }
            })
        }
    }

    /// Следующий пакет. timeout — сколько можно не получать ни байта (а не ждать целый пакет: файл в 2 МБ
    /// по слабому Wi-Fi идёт дольше, но непрерывно); nil — ждать сколько угодно.
    func nextPacket(timeout: Duration?) async throws -> PTPIPPacket {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<PTPIPPacket, Error>) in
            lock.lock()
            if !packets.isEmpty {
                let packet = packets.removeFirst()
                lock.unlock()
                continuation.resume(returning: packet)
                return
            }
            if let failure {
                lock.unlock()
                continuation.resume(throwing: failure)
                return
            }
            waiterSeq += 1
            let id = waiterSeq
            waiter = (id, Date(), continuation)
            lock.unlock()
            if let timeout { check(id, after: timeout.seconds, idle: timeout.seconds) }
        }
    }

    /// Обычное закрытие (FIN): камера освобождает сеанс, как только его получит.
    func cancel() {
        connection.cancel()
        fail(TransportError.disconnected)
    }

    /// Сброс (RST): камера освобождает сеанс сразу, даже если застряла на передаче.
    func abort() {
        connection.forceCancel()
        fail(TransportError.disconnected)
    }

    private func check(_ id: Int, after delay: TimeInterval, idle: TimeInterval) {
        queue.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.expire(id, idle: idle)
        }
    }

    private func expire(_ id: Int, idle: TimeInterval) {
        lock.lock()
        guard let current = waiter, current.id == id else {
            lock.unlock()
            return
        }
        let quiet = Date().timeIntervalSince(max(lastRx, current.since))
        if paused || quiet < idle {
            let pause = paused
            lock.unlock()
            check(id, after: pause ? 0.25 : idle - quiet, idle: idle)
            return
        }
        waiter = nil
        lock.unlock()
        current.continuation.resume(throwing: TransportError.timeout(operation: 0))
    }

    private func receive() {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            guard let self else { return }
            if let data, !data.isEmpty { self.deliver(data) }
            if error != nil || isComplete {
                self.fail(TransportError.disconnected)
            } else {
                self.receive()
            }
        }
    }

    private func deliver(_ data: Data) {
        lock.lock()
        lastRx = Date()
        reader.feed(data)
        var ready: [(CheckedContinuation<PTPIPPacket, Error>, PTPIPPacket)] = []
        var garbled = false
        while true {
            let packet: PTPIPPacket?
            do { packet = try reader.next() } catch {
                garbled = true
                break
            }
            guard let packet else { break }
            if let current = waiter {
                waiter = nil
                ready.append((current.continuation, packet))
            } else {
                packets.append(packet)
            }
        }
        lock.unlock()
        for (continuation, packet) in ready { continuation.resume(returning: packet) }
        // поток сбился (длина пакета меньше заголовка) — дальше его не разобрать: соединение рвём
        if garbled { abort() }
    }

    private func fail(_ error: Error) {
        lock.lock()
        if failure == nil { failure = error }
        let current = waiter
        waiter = nil
        lock.unlock()
        current?.continuation.resume(throwing: error)
    }
}

/// PTP поверх Wi-Fi (режимы камеры «Wi-Fi Access Point» и «Wi-Fi Direct»).
/// Как и ImageCaptureCore для USB, сам открывает сессию PTP, а транзакции идут строго по одной.
/// Пауза связи — не обрыв: сеанс держится, пока камера может вернуться, а рвётся по решению `LinkMonitor`.
public actor PTPIPTransport: PTPTransport {
    public nonisolated let events: AsyncStream<PTPEvent>
    private nonisolated let eventContinuation: AsyncStream<PTPEvent>.Continuation
    /// Что со связью: провал и время ответа камеры — для экрана.
    public nonisolated let linkStatus: AsyncStream<LinkStatus>
    private nonisolated let statusContinuation: AsyncStream<LinkStatus>.Continuation
    public nonisolated let host: String
    private let identity: PTPIPIdentity
    private let timeout: Duration
    private var command: PTPIPChannel?
    private var event: PTPIPChannel?
    private var nextTransactionID: UInt32 = 1
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var closed = false
    private var eventTask: Task<Void, Never>?
    private var watchTask: Task<Void, Never>?
    private var monitor = LinkMonitor(now: Date())
    private var seenCommandRx = Date.distantPast
    private var seenEventRx = Date.distantPast
    private var lastStatus = LinkStatus()
    /// Почему транспорт закрылся сам; nil — открыт или его закрыла программа.
    public private(set) var loss: LinkLoss?
    /// Есть ли сеть до камеры — спрашивается во время провала связи.
    public typealias Reachability = @Sendable () async -> Bool
    private let reachable: Reachability

    /// reachable — как проверить сеть до камеры во время провала; по умолчанию ICMP ping камеры.
    public init(host: String, identity: PTPIPIdentity, timeout: Duration = .seconds(8), reachable: Reachability? = nil) {
        self.host = host
        self.identity = identity
        self.timeout = timeout
        self.reachable = reachable ?? { await CameraPing.ping(host, timeout: 0.8) }
        (events, eventContinuation) = AsyncStream.makeStream()
        (linkStatus, statusContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(1))
    }

    /// Приветствие, событийный канал, OpenSession. Возвращает имя камеры.
    /// initTimeout — сколько ждать ответа на приветствие (при сопряжении камера ждёт подтверждения).
    @discardableResult
    public func connect(connectTimeout: Duration = .seconds(5), initTimeout: Duration = .seconds(5)) async throws -> String {
        guard !closed else { throw TransportError.disconnected }
        let command = try await PTPIPChannel.open(host: host, port: PTPIP.port, timeout: connectTimeout)
        // канал — в свойство сразу: close() во время ожидания (отмена сопряжения, «Забыть») должен его оборвать
        guard adopt(command, as: .command) else { throw TransportError.disconnected }
        do {
            try await command.write(PTPIP.initCommandRequest(guid: identity.guid, name: identity.name))
            let reply = try await command.nextPacket(timeout: initTimeout)
            guard case let .accepted(connectionNumber, cameraName) = try PTPIP.parseInitReply(reply) else {
                if case let .rejected(reason) = try PTPIP.parseInitReply(reply) { throw PTPIPError.rejected(reason: reason) }
                throw PTPIPError.unexpectedPacket(reply.type)
            }
            let event = try await PTPIPChannel.open(host: host, port: PTPIP.port, timeout: connectTimeout)
            guard adopt(event, as: .event) else { throw TransportError.disconnected }
            try await event.write(PTPIP.initEventRequest(connectionNumber: connectionNumber))
            let ack = try await event.nextPacket(timeout: .seconds(5))
            guard ack.type == PTPIP.PacketType.initEventAck else { throw PTPIPError.unexpectedPacket(ack.type) }
            listenForEvents(event)
            try await openSession()
            startWatching()
            return cameraName
        } catch {
            // каналы уже в свойствах: shutDown закроет оба (если транспорт уже закрыли — они закрыты тогда же)
            shutDown(abortive: false)
            throw error
        }
    }

    private enum Slot { case command, event }

    /// Запомнить открытый канал. false — транспорт закрыли, пока канал открывался: канал закрывается сразу.
    private func adopt(_ channel: PTPIPChannel, as slot: Slot) -> Bool {
        guard !closed else {
            channel.cancel()
            return false
        }
        switch slot {
        case .command: command = channel
        case .event: event = channel
        }
        return true
    }

    /// Адрес этого Mac в сети, через который идёт связь с камерой.
    public func localHost() -> String? {
        command?.localHost
    }

    public func send(_ operation: UInt16, _ params: [UInt32], outData: Data?) async throws -> PTPReply {
        try await transact(operation, params, outData: outData, timeout: timeout)
    }

    /// Обычное закрытие. Связь есть — сначала CloseSession (так просит Sony), но не дольше полсекунды:
    /// застрявшая транзакция закрытие не держит. Потом FIN — камера свободна сразу.
    public func close() async {
        guard !closed else { return }
        if watchTask != nil, !monitor.isOutage {
            await withTaskGroup(of: Void.self) { group in
                group.addTask { _ = try? await self.transact(0x1003, [], outData: nil, timeout: .milliseconds(300)) }
                group.addTask { try? await Task.sleep(for: .milliseconds(500)) }
                await group.next()
                group.cancelAll()
                shutDown(abortive: false)
            }
        }
        shutDown(abortive: false)
    }

    /// Сразу после сопряжения камера иногда молчит на первую команду: ждём недолго и повторяем.
    private func openSession() async throws {
        for attempt in 1...4 {
            do {
                let reply = try await transact(0x1002, [1], outData: nil, timeout: .milliseconds(1500))
                if reply.isOK || reply.code == 0x201E { return }
                throw SessionError.operationFailed(operation: 0x1002, code: reply.code)
            } catch TransportError.timeout where attempt < 4 {
                continue
            }
        }
    }

    private func transact(_ operation: UInt16, _ params: [UInt32], outData: Data?, timeout: Duration) async throws -> PTPReply {
        await acquire()
        defer { release() }
        guard let command, !closed else { throw TransportError.disconnected }
        let tid = nextTransactionID
        nextTransactionID &+= 1
        monitor.operationStarted(at: Date())
        defer { monitor.operationFinished() }
        do {
            try await command.write(PTPIP.operationRequest(operation, transactionID: tid, params: params, dataOut: outData != nil))
            if let outData {
                try await command.write(PTPIP.startData(transactionID: tid, length: UInt64(outData.count)))
                try await command.write(PTPIP.endData(transactionID: tid, payload: outData))
            }
            var transaction = PTPIPTransaction(transactionID: tid)
            while true {
                // тайм-аут — на тишину; пока связь пропала, ожидание не истекает: обрыв решает слежение за связью
                let packet = try await command.nextPacket(timeout: timeout)
                if packet.type == PTPIP.PacketType.probeRequest {
                    try await command.write(PTPIP.probeResponse)
                    continue
                }
                if let reply = transaction.consume(packet) { return reply }
            }
        } catch TransportError.timeout {
            // ответа нет, а связь есть — это решает вызывающий (live view спросит снова); сеанс не рвём
            throw TransportError.timeout(operation: operation)
        } catch TransportError.disconnected {
            abort(.broken)
            throw TransportError.disconnected
        }
    }

    private func listenForEvents(_ event: PTPIPChannel) {
        let continuation = eventContinuation
        eventTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let packet = try? await event.nextPacket(timeout: nil) else { break }
                switch packet.type {
                case PTPIP.PacketType.probeRequest:
                    try? await event.write(PTPIP.probeResponse)
                case PTPIP.PacketType.probeResponse:
                    await self?.probeAnswered()
                default:
                    if let ptpEvent = PTPIP.parseEvent(packet) { continuation.yield(ptpEvent) }
                }
            }
            // событийное соединение оборвалось — сеанс без него не живёт
            await self?.abort(.broken)
        }
    }

    // MARK: - Слежение за связью

    private func startWatching() {
        monitor = LinkMonitor(now: Date())
        watchTask = Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(250))
                guard let self, await self.watch() else { break }
            }
        }
    }

    /// Раз в 250 мс: новые байты, проба, ping, решение об обрыве. false — транспорт закрыт.
    private func watch() -> Bool {
        guard !closed, let command, let event else { return false }
        let commandRx = command.lastReceived, eventRx = event.lastReceived
        if commandRx > seenCommandRx {
            seenCommandRx = commandRx
            monitor.commandReceived(at: commandRx)
        }
        if eventRx > seenEventRx {
            seenEventRx = eventRx
            monitor.eventReceived(at: eventRx)
        }
        for action in monitor.tick(now: Date()) {
            switch action {
            case .sendProbe:
                Task { try? await event.write(PTPIP.probeRequest) }
            case .ping:
                let check = reachable
                Task { [weak self] in
                    let answered = await check()
                    await self?.pinged(answered)
                }
            case let .close(reason):
                abort(reason)
                return false
            }
        }
        command.setPaused(monitor.isOutage)
        publishStatus()
        return true
    }

    private func probeAnswered() {
        monitor.probeAnswered(at: Date())
        publishStatus()
    }

    private func pinged(_ answered: Bool) {
        monitor.pinged(answered, at: Date())
    }

    private func publishStatus() {
        let status = monitor.status
        guard status != lastStatus else { return }
        lastStatus = status
        statusContinuation.yield(status)
    }

    /// Транспорт закрывается сам. Связь вернулась или соединение сломано — RST: камера освобождает сеанс сразу,
    /// даже если застряла на передаче. Связи нет — обычное закрытие: FIN повторяется и дойдёт, если связь вернётся.
    private func abort(_ reason: LinkLoss) {
        guard !closed else { return }
        loss = reason
        shutDown(abortive: reason != .noLink)
    }

    private func shutDown(abortive: Bool) {
        guard !closed else { return }
        closed = true
        watchTask?.cancel()
        eventTask?.cancel()
        if abortive {
            command?.abort()
            event?.abort()
        } else {
            command?.cancel()
            event?.cancel()
        }
        eventContinuation.finish()
        statusContinuation.finish()
    }

    private func acquire() async {
        if !busy {
            busy = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            busy = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}

/// Кем программа представляется камере. GUID создаётся один раз и общий у программы и zv-probe:
/// камера помнит сопряжение по нему.
public struct PTPIPIdentity: Sendable {
    public let guid: Data
    public let name: String

    public init(guid: Data, name: String) {
        self.guid = guid
        self.name = name
    }

    private static let key = "ptpipGUID"

    public static func load(defaults: UserDefaults = UserDefaults(suiteName: "local.zve10viewer") ?? .standard) -> PTPIPIdentity {
        if let hex = defaults.string(forKey: key), let guid = Data(hex: hex), guid.count == 16 {
            return PTPIPIdentity(guid: guid, name: "ZV-E10 Viewer")
        }
        return reset(defaults: defaults)
    }

    /// Новый GUID: прежнее сопряжение в камере к программе больше не относится — нужно сопрячь заново.
    public static func reset(defaults: UserDefaults = UserDefaults(suiteName: "local.zve10viewer") ?? .standard) -> PTPIPIdentity {
        let guid = Data((0..<16).map { _ in UInt8.random(in: 0...255) })
        defaults.set(guid.map { String(format: "%02x", $0) }.joined(), forKey: key)
        return PTPIPIdentity(guid: guid, name: "ZV-E10 Viewer")
    }
}

/// Поиск камер Sony в сети по SSDP (служба DigitalImaging).
public enum WiFiDiscovery {
    /// Ответы, собранные за `seconds`; одна камера — один ответ.
    /// Камера отвечает с задержкой до ~3 с, а многоадресный UDP по Wi-Fi теряется — запрос повторяем всё окно.
    public static func search(seconds: Double = 4) async -> [SSDP.Response] {
        // поиск держит поток несколько секунд — на своей очереди, а не на общем пуле Swift concurrency
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .userInitiated).async {
                continuation.resume(returning: searchBlocking(seconds: seconds))
            }
        }
    }

    private static func searchBlocking(seconds: Double) -> [SSDP.Response] {
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_UDP)
        guard fd >= 0 else { return [] }
        defer { close(fd) }
        var timeout = timeval(tv_sec: 0, tv_usec: 200_000)
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, socklen_t(MemoryLayout<timeval>.size))
        var target = sockaddr_in()
        target.sin_family = sa_family_t(AF_INET)
        target.sin_port = SSDP.port.bigEndian
        inet_pton(AF_INET, SSDP.multicastHost, &target.sin_addr)
        let request = SSDP.searchRequest()
        var found: [String: SSDP.Response] = [:]
        let deadline = Date().addingTimeInterval(seconds)
        var lastSent = Date.distantPast
        var buffer = [UInt8](repeating: 0, count: 4096)
        while Date() < deadline {
            if Date().timeIntervalSince(lastSent) > 0.7 {
                request.withUnsafeBytes { bytes in
                    withUnsafePointer(to: &target) {
                        $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                            _ = sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                        }
                    }
                }
                lastSent = Date()
            }
            let n = recv(fd, &buffer, buffer.count, 0)
            guard n > 0 else { continue }
            if let response = SSDP.parse(String(decoding: buffer[0..<n], as: UTF8.self)) {
                found[response.usn] = response
            }
        }
        return Array(found.values)
    }
}

private extension Duration {
    var seconds: Double { Double(components.seconds) + Double(components.attoseconds) / 1e18 }
}

private extension Data {
    init?(hex: String) {
        var bytes: [UInt8] = []
        var index = hex.startIndex
        while index < hex.endIndex {
            let next = hex.index(index, offsetBy: 2, limitedBy: hex.endIndex) ?? hex.endIndex
            guard let byte = UInt8(hex[index..<next], radix: 16) else { return nil }
            bytes.append(byte)
            index = next
        }
        self.init(bytes)
    }
}
