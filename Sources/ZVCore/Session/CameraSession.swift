import Foundation

public enum SetResult: Equatable, Sendable {
    case confirmed
    case notApplied(actual: Int64?)
}

/// Сеанс работы с камерой Sony в режиме PC Remote.
public actor CameraSession {
    private let transport: PTPTransport
    let timing: SessionTiming
    public private(set) var deviceInfo: DeviceInfo?
    public private(set) var properties: [UInt16: SonyProperty] = [:]
    public private(set) var isReady = false
    /// Команды 0x9207, которые камера назвала в GetExtDeviceInfo: по ним видно, умеет ли она фокус касанием, зум и т. п.
    public private(set) var controlCodes: Set<UInt16> = []
    /// Каждое перечитывание свойств — сколько угодно слушателям; поток завершается при разрыве связи.
    private nonisolated let snapshotBroadcast = Broadcast<[UInt16: SonyProperty]>()
    private var eventTask: Task<Void, Never>?
    private var refreshRunning = false
    private var refreshWanted = false
    /// Кнопка записи у нас «нажата»: мы начали запись и ещё не отпустили (0xD2C8 — удержание, см. toggleRecording).
    private var recordButtonHeld = false

    public init(transport: PTPTransport, timing: SessionTiming = .standard) {
        self.transport = transport
        self.timing = timing
    }

    /// Своя подписка на свойства: последнее перечитывание — сразу, дальше — каждое новое. Отписка (отмена задачи, что
    /// её слушает) сеанс не закрывает: его можно передать другому — главному экрану или ленте камер.
    public nonisolated func snapshotStream() -> AsyncStream<[UInt16: SonyProperty]> { snapshotBroadcast.subscribe() }

    /// То же, что `snapshotStream()`: каждое обращение — своя подписка.
    public nonisolated var snapshots: AsyncStream<[UInt16: SonyProperty]> { snapshotStream() }

    /// Рукопожатие Sony, передача приоритета компьютеру и первое чтение свойств.
    public func start() async throws {
        let info = try DeviceInfo(data: try await call(SonyOp.getDeviceInfo).data)
        deviceInfo = info
        guard info.isPCRemote else { throw SessionError.notPCRemote }
        try await call(SonyOp.connect, [1, 0, 0])
        try await call(SonyOp.connect, [2, 0, 0])
        var hasExtInfo = false
        for attempt in 1...20 {
            let ext = try await call(SonyOp.getExtDeviceInfo, [Sony.protocolVersion, 1]).data
            if !ext.isEmpty {
                hasExtInfo = true
                controlCodes = Set((try? SonyExtInfo(data: ext))?.controlCodes ?? [])
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
        snapshotBroadcast.yield(properties)
    }

    // MARK: - Изменение настроек, запись, live view

    /// Записывает значение и ждёт, пока камера подтвердит его чтением.
    public func set(_ code: UInt16, to value: Int64) async throws -> SetResult {
        guard isReady else { throw SessionError.notReady }
        guard let property = properties[code], property.isWritable else { throw SessionError.notWritable(code) }
        guard Self.allows(property, value) else { throw SessionError.valueNotAllowed(code, value) }
        try await call(SonyOp.setExtPropValue, [UInt32(code)], outData: PTPValue.encode(value, type: property.dataType))
        let actual = try await waitFor(code) { $0 == value }
        return actual == value ? .confirmed : .notApplied(actual: actual)
    }

    /// Кнопка записи; возвращает, идёт ли запись после этого. 0xD2C8 у Sony — «как удержание кнопки»: нажатие
    /// начинает запись, отпускание останавливает. Поэтому запуск — только «нажать», остановка нашей записи — только
    /// «отпустить»: «отпустить» сразу за «нажать» камера иногда успевает выполнить, и запись обрывается через
    /// полсекунды после старта. Запись, начатую кнопкой на камере, останавливаем «нажать и отпустить»; кнопку, оставшуюся
    /// «нажатой» после записи, которую камера остановила сама, перед новым запуском отпускаем. Отклонила камера шаг
    /// (бывает «занята» 0x2019) — всё равно ждём, сменится ли состояние: запись при этом может и пойти; потом —
    /// ошибка с кодом и тем, что вышло.
    public func toggleRecording() async throws -> Bool {
        guard isReady else { throw SessionError.notReady }
        let before = properties[SonyProp.movieRecordingState]?.current.intValue
        let steps: [(value: Int64, release: Bool)]
        switch (before == 1, recordButtonHeld) {
        case (true, true): steps = [(1, true)]
        case (true, false): steps = [(2, false), (1, true)]
        case (false, true): steps = [(1, true), (2, false)]
        case (false, false): steps = [(2, false)]
        }
        let button = [UInt32(SonyProp.movieRecButton)]
        for step in steps {
            let reply = try await rawSend(SonyOp.controlDevice, button, outData: PTPValue.encode(step.value, type: PTPType.uint16))
            guard reply.isOK else {
                let after = try await waitFor(SonyProp.movieRecordingState) { $0 != before }
                throw SessionError.recordButtonRefused(release: step.release, code: reply.code, recording: after == 1)
            }
            recordButtonHeld = !step.release
        }
        let after = try await waitFor(SonyProp.movieRecordingState) { $0 != before }
        return after == 1
    }

    /// Следующий кадр live view (JPEG). Пока кадр не готов, камера отвечает AccessDenied — ждём и повторяем.
    public func nextLiveViewFrame() async throws -> Data {
        try await nextLiveViewSample().jpeg
    }

    /// Кадр live view и как он шёл: по этим отсчётам видно, сколько тянет канал.
    public func nextLiveViewSample() async throws -> LiveViewSample {
        let clock = ContinuousClock()
        var shortest: Duration?
        var waits = 0
        while true {
            try Task.checkCancellation()
            let sent = clock.now
            let reply = try await rawSend(SonyOp.getObject, [Sony.liveViewHandle])
            // время самого обмена — без ожидания очереди за другой транзакцией (скачивание снимка идёт секунды,
            // и кадр за ним — не признак слабого канала); транспорт без своих замеров — по часам сеанса
            let took = reply.elapsed.map { Duration.seconds($0) } ?? (clock.now - sent)
            switch reply.code {
            case PTPResponseCode.ok:
                if let jpeg = LiveViewFrame.jpeg(from: reply.data) {
                    return LiveViewSample(jpeg: jpeg, bytes: reply.data.count, seconds: took.seconds,
                                          shortReplySeconds: shortest?.seconds, waits: waits,
                                          focusFrames: FocusFrame.parse(liveView: reply.data))
                }
                try await Task.sleep(for: .milliseconds(20))
            case PTPResponseCode.accessDenied, PTPResponseCode.deviceBusy:
                // кадр ещё не готов или камера занята (например, переключает режим) — ждём и повторяем;
                // короткий ответ заодно показывает время отклика камеры
                shortest = min(shortest ?? took, took)
                waits += 1
                try await Task.sleep(for: .milliseconds(20))
            case PTPResponseCode.invalidObjectHandle:
                try await Task.sleep(for: .milliseconds(50))
            default:
                throw SessionError.operationFailed(operation: SonyOp.getObject, code: reply.code)
            }
        }
    }

    /// Команда управления 0x9207 со значением своего типа (точка AF, зум, Near/Far); отдаёт код ответа камеры.
    @discardableResult
    public func control(_ code: UInt16, value: Int64, type: UInt16) async throws -> UInt16 {
        try await rawSend(SonyOp.controlDevice, [UInt32(code)], outData: PTPValue.encode(value, type: type)).code
    }

    /// Кнопка 0x9207: нажать и отпустить (снять касание и т. п.); отдаёт код ответа на отпускание.
    @discardableResult
    public func pressButton(_ code: UInt16) async throws -> UInt16 {
        try await control(code, value: 2, type: PTPType.uint16)
        return try await control(code, value: 1, type: PTPType.uint16)
    }

    /// Записать значение без ожидания подтверждения и без проверок — для служебных свойств (качество превью).
    public func setQuietly(_ code: UInt16, to value: Int64, type: UInt16) async throws {
        try await call(SonyOp.setExtPropValue, [UInt32(code)], outData: PTPValue.encode(value, type: type))
    }

    static func allows(_ property: SonyProperty, _ value: Int64) -> Bool {
        if !property.settableValues.isEmpty { return property.settableValues.contains(value) }
        guard let range = property.range, range.step > 0 else { return false }
        return value >= range.min && value <= range.max && (value - range.min) % range.step == 0
    }

    /// Перечитывает свойства, пока условие не выполнится или не выйдет время подтверждения.
    func waitFor(_ code: UInt16, timeout: Duration? = nil, until done: (Int64?) -> Bool) async throws -> Int64? {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: timeout ?? timing.confirmTimeout)
        var current = properties[code]?.current.intValue
        while clock.now < deadline {
            try await Task.sleep(for: timing.pollInterval)
            try await refresh()
            current = properties[code]?.current.intValue
            if done(current) { break }
        }
        return current
    }

    // MARK: - Внутреннее

    private func listenForEvents() {
        let events = transport.events
        eventTask = Task { [weak self] in
            for await event in events where event.code == Sony.propertyChangedEvent {
                await self?.propertiesChanged()
            }
            // поток событий закончился — камера отключена
            await self?.close()
        }
    }

    /// Камера сообщила об изменении свойств. Пачка событий — одно перечитывание, а не по одному на событие:
    /// каждое — ~2,8 КБ и лишний обмен, который на слабом Wi-Fi отнимает кадры у live view.
    private func propertiesChanged() {
        refreshWanted = true
        guard !refreshRunning else { return }
        refreshRunning = true
        Task { await self.refreshWhileWanted() }
    }

    private func refreshWhileWanted() async {
        while refreshWanted {
            refreshWanted = false
            try? await refresh()
        }
        refreshRunning = false
    }

    func close() {
        isReady = false
        eventTask?.cancel()
        snapshotBroadcast.finish()
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
