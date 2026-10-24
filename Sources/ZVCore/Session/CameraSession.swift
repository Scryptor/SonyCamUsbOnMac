import Foundation

/// Сеанс работы с камерой Sony в режиме PC Remote.
public actor CameraSession {
    private let transport: PTPTransport
    let timing: SessionTiming
    public private(set) var deviceInfo: DeviceInfo?
    public private(set) var properties: [UInt16: SonyProperty] = [:]
    public private(set) var isReady = false
    /// Каждое перечитывание свойств; поток завершается при разрыве связи.
    public nonisolated let snapshots: AsyncStream<[UInt16: SonyProperty]>
    private let snapshotContinuation: AsyncStream<[UInt16: SonyProperty]>.Continuation
    private var eventTask: Task<Void, Never>?

    public init(transport: PTPTransport, timing: SessionTiming = .standard) {
        self.transport = transport
        self.timing = timing
        (snapshots, snapshotContinuation) = AsyncStream.makeStream()
    }

    /// Рукопожатие Sony, передача приоритета компьютеру и первое чтение свойств.
    public func start() async throws {
        let info = try DeviceInfo(data: try await call(SonyOp.getDeviceInfo).data)
        deviceInfo = info
        guard info.isPCRemote else { throw SessionError.notPCRemote }
        try await call(SonyOp.connect, [1, 0, 0])
        try await call(SonyOp.connect, [2, 0, 0])
        var hasExtInfo = false
        for attempt in 1...20 {
            if try await !call(SonyOp.getExtDeviceInfo, [Sony.protocolVersion, 1]).data.isEmpty {
                hasExtInfo = true
                break
            }
            if attempt < 20 { try await Task.sleep(for: .milliseconds(100)) }
        }
        guard hasExtInfo else { throw SessionError.noExtendedInfo }
        try await call(SonyOp.connect, [3, 0, 0])
        try await call(SonyOp.setExtPropValue, [UInt32(SonyProp.priorityMode)],
                      outData: PTPValue.encode(1, type: PTPType.int8))
        try await refresh()
        listenForEvents()
    }

    /// Пауза, пока камера «просыпается», затем свежие свойства — после этого можно менять настройки.
    public func waitUntilReady() async throws {
        try await Task.sleep(for: timing.settle)
        try await refresh()
        isReady = true
    }

    public func refresh() async throws {
        let reply = try await call(SonyOp.getAllExtPropInfo)
        properties = try SonyPropertyParser.parse(reply.data)
        snapshotContinuation.yield(properties)
    }

    // MARK: - Внутреннее

    private func listenForEvents() {
        let events = transport.events
        eventTask = Task { [weak self] in
            for await event in events where event.code == Sony.propertyChangedEvent {
                try? await self?.refresh()
            }
            // поток событий закончился — камера отключена
            await self?.close()
        }
    }

    func close() {
        isReady = false
        eventTask?.cancel()
        snapshotContinuation.finish()
    }

    /// Отправка без проверки кода ответа; разрыв связи закрывает сеанс.
    func rawSend(_ operation: UInt16, _ params: [UInt32] = [], outData: Data? = nil) async throws -> PTPReply {
        do {
            return try await transport.send(operation, params, outData: outData)
        } catch TransportError.disconnected {
            close()
            throw TransportError.disconnected
        }
    }

    @discardableResult
    func call(_ operation: UInt16, _ params: [UInt32] = [], outData: Data? = nil) async throws -> PTPReply {
        let reply = try await rawSend(operation, params, outData: outData)
        guard reply.isOK else { throw SessionError.operationFailed(operation: operation, code: reply.code) }
        return reply
    }
}
