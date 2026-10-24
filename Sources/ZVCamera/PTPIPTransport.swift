import Foundation
import Network
import ZVCore

/// Одно TCP-соединение PTP/IP: пакеты складываются в очередь по мере прихода, ожидание — с тайм-аутом.
/// Ничего не теряется, даже если ожидание оборвалось по тайм-ауту: пакет останется в очереди.
final class PTPIPChannel: @unchecked Sendable {
    private let connection: NWConnection
    private let queue = DispatchQueue(label: "zv.ptpip.channel")
    private let lock = NSLock()
    private var reader = PTPIPPacketReader()
    private var packets: [PTPIPPacket] = []
    private var waiter: (id: Int, continuation: CheckedContinuation<PTPIPPacket, Error>)?
    private var waiterSeq = 0
    private var failure: Error?

    private init(connection: NWConnection) {
        self.connection = connection
    }

    /// Открывает соединение и ждёт готовности не дольше timeout.
    static func open(host: String, port: UInt16, timeout: Duration) async throws -> PTPIPChannel {
        let tcp = NWProtocolTCP.Options()
        tcp.noDelay = true
        // камера может пропасть без закрытия соединения (выключили, ушла из сети) — keepalive это заметит
        tcp.enableKeepalive = true
        tcp.keepaliveIdle = 5
        tcp.keepaliveInterval = 2
        tcp.keepaliveCount = 3
        let connection = NWConnection(host: NWEndpoint.Host(host), port: NWEndpoint.Port(rawValue: port)!,
                                      using: NWParameters(tls: nil, tcp: tcp))
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

    /// Следующий пакет; nil в timeout — ждать сколько угодно.
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
            waiter = (id, continuation)
            lock.unlock()
            if let timeout {
                queue.asyncAfter(deadline: .now() + timeout.seconds) { [weak self] in
                    self?.expire(id)
                }
            }
        }
    }

    func cancel() {
        connection.cancel()
        fail(TransportError.disconnected)
    }

    private func expire(_ id: Int) {
        lock.lock()
        guard let current = waiter, current.id == id else {
            lock.unlock()
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
        reader.feed(data)
        var ready: [(CheckedContinuation<PTPIPPacket, Error>, PTPIPPacket)] = []
        while let packet = try? reader.next() {
            if let current = waiter {
                waiter = nil
                ready.append((current.continuation, packet))
            } else {
                packets.append(packet)
            }
        }
        lock.unlock()
        for (continuation, packet) in ready { continuation.resume(returning: packet) }
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
public actor PTPIPTransport: PTPTransport {
    public nonisolated let events: AsyncStream<PTPEvent>
    private nonisolated let eventContinuation: AsyncStream<PTPEvent>.Continuation
    public nonisolated let host: String
    private let identity: PTPIPIdentity
    private let timeout: Duration
    private var command: PTPIPChannel?
    private var event: PTPIPChannel?
    private var nextTransactionID: UInt32 = 1
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var timeoutsInRow = 0
    private var closed = false
    private var eventTask: Task<Void, Never>?

    public init(host: String, identity: PTPIPIdentity, timeout: Duration = .seconds(8)) {
        self.host = host
        self.identity = identity
        self.timeout = timeout
        (events, eventContinuation) = AsyncStream.makeStream()
    }

    /// Приветствие, событийный канал, OpenSession. Возвращает имя камеры.
    /// initTimeout — сколько ждать ответа на приветствие (при сопряжении камера ждёт подтверждения).
    @discardableResult
    public func connect(initTimeout: Duration = .seconds(5)) async throws -> String {
        let command = try await PTPIPChannel.open(host: host, port: PTPIP.port, timeout: .seconds(5))
        do {
            try await command.write(PTPIP.initCommandRequest(guid: identity.guid, name: identity.name))
            let reply = try await command.nextPacket(timeout: initTimeout)
            guard case let .accepted(connectionNumber, cameraName) = try PTPIP.parseInitReply(reply) else {
                if case let .rejected(reason) = try PTPIP.parseInitReply(reply) { throw PTPIPError.rejected(reason: reason) }
                throw PTPIPError.unexpectedPacket(reply.type)
            }
            let event = try await PTPIPChannel.open(host: host, port: PTPIP.port, timeout: .seconds(5))
            try await event.write(PTPIP.initEventRequest(connectionNumber: connectionNumber))
            let ack = try await event.nextPacket(timeout: .seconds(5))
            guard ack.type == PTPIP.PacketType.initEventAck else { throw PTPIPError.unexpectedPacket(ack.type) }
            self.command = command
            self.event = event
            listenForEvents(event)
            try await openSession()
            return cameraName
        } catch {
            command.cancel()
            close()
            throw error
        }
    }

    public func send(_ operation: UInt16, _ params: [UInt32], outData: Data?) async throws -> PTPReply {
        try await transact(operation, params, outData: outData, timeout: timeout)
    }

    public func close() {
        guard !closed else { return }
        closed = true
        eventTask?.cancel()
        command?.cancel()
        event?.cancel()
        eventContinuation.finish()
    }

    /// Сразу после сопряжения камера иногда молчит на первую команду: ждём недолго и повторяем.
    private func openSession() async throws {
        for attempt in 1...4 {
            do {
                let reply = try await transact(0x1002, [1], outData: nil, timeout: .milliseconds(1500))
                if reply.isOK || reply.code == 0x201E { return }
                throw SessionError.operationFailed(operation: 0x1002, code: reply.code)
            } catch TransportError.timeout where attempt < 4 {
                timeoutsInRow = 0
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
        do {
            try await command.write(PTPIP.operationRequest(operation, transactionID: tid, params: params, dataOut: outData != nil))
            if let outData {
                try await command.write(PTPIP.startData(transactionID: tid, length: UInt64(outData.count)))
                try await command.write(PTPIP.endData(transactionID: tid, payload: outData))
            }
            var transaction = PTPIPTransaction(transactionID: tid)
            while true {
                // тайм-аут — на паузу между пакетами: большой RAW по Wi-Fi идёт дольше, но непрерывно
                let packet = try await command.nextPacket(timeout: timeout)
                if packet.type == PTPIP.PacketType.probeRequest {
                    try await command.write(PTPIP.probeResponse)
                    continue
                }
                if let reply = transaction.consume(packet) {
                    timeoutsInRow = 0
                    return reply
                }
            }
        } catch TransportError.timeout {
            // две паузы подряд — связи нет: закрываем, программа переподключится
            timeoutsInRow += 1
            if timeoutsInRow >= 2 {
                close()
                throw TransportError.disconnected
            }
            throw TransportError.timeout(operation: operation)
        } catch TransportError.disconnected {
            close()
            throw TransportError.disconnected
        }
    }

    private func listenForEvents(_ event: PTPIPChannel) {
        let continuation = eventContinuation
        eventTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let packet = try? await event.nextPacket(timeout: nil) else { break }
                if packet.type == PTPIP.PacketType.probeRequest {
                    try? await event.write(PTPIP.probeResponse)
                } else if let ptpEvent = PTPIP.parseEvent(packet) {
                    continuation.yield(ptpEvent)
                }
            }
            // событийный канал закрылся — камера пропала
            await self?.close()
        }
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

    public static func load(defaults: UserDefaults = UserDefaults(suiteName: "local.zve10viewer") ?? .standard) -> PTPIPIdentity {
        let key = "ptpipGUID"
        if let hex = defaults.string(forKey: key), let guid = Data(hex: hex), guid.count == 16 {
            return PTPIPIdentity(guid: guid, name: "ZV-E10 Viewer")
        }
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
        await Task.detached(priority: .userInitiated) { searchBlocking(seconds: seconds) }.value
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
