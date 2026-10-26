import CoreGraphics
import Foundation
import ImageIO
import Observation
import ZVCamera
import ZVCore

/// Очередь из одного места: следующий ждёт, пока предыдущий не отпустит.
private actor WriteGate {
    private var busy = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func acquire() async {
        guard busy else {
            busy = true
            return
        }
        // место передаёт release — busy так и остаётся true
        await withCheckedContinuation { waiters.append($0) }
    }

    func release() {
        if waiters.isEmpty { busy = false } else { waiters.removeFirst().resume() }
    }
}

/// Камера в ленте: свой открытый сеанс по Wi-Fi, превью низкого качества около 5 к/с, запись, заряд. Сеанс передаётся
/// главному экрану (`handOver`) и обратно (`adopt`) — без переподключения.
@MainActor
@Observable
final class SideCamera: Identifiable {
    /// Открытый сеанс со всем, что о камере уже известно, — то, что передаётся между главным экраном и лентой.
    struct Handed {
        let session: CameraSession
        let transport: PTPIPTransport
        let endpoint: CameraEndpoint
        let deviceInfo: DeviceInfo?
        let properties: [UInt16: SonyProperty]
        let recordingStartedAt: Date?
    }

    /// id знакомой камеры.
    let id: String
    private(set) var endpoint: CameraEndpoint
    private(set) var frame: CGImage?
    private(set) var properties: [UInt16: SonyProperty] = [:]
    /// Сеанс жив; false — «нет связи», лента стучится снова.
    private(set) var connected = false
    /// Идёт попытка подключиться.
    private(set) var connecting = false
    private(set) var recordingStartedAt: Date?
    /// Кадров в секунду за последние 2 с — для API и отладки.
    private(set) var fps = 0
    @ObservationIgnored private var session: CameraSession?
    @ObservationIgnored private var transport: PTPIPTransport?
    @ObservationIgnored private var deviceInfo: DeviceInfo?
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []
    @ObservationIgnored private var frameTimes: [Date] = []
    /// Записи от главной (группа): по свойству одна за раз, только последнее значение.
    @ObservationIgnored private var coalescer: WriteCoalescer?
    /// На всю камеру — одна запись за раз (и пресет целиком); нажатия записи — по одному, своей очередью.
    @ObservationIgnored private var writeGate: WriteGate?
    @ObservationIgnored private var recordGate: WriteGate?
    @ObservationIgnored private var requested: [UInt16: Int64] = [:]
    /// Последнее значение не прижилось: камера не дала его выбрать или не подтвердила.
    @ObservationIgnored var onWriteFailed: ((SettingID, Int64) -> Void)?

    /// Пауза между кадрами в ленте: около 5 к/с — плитке хватает, канал свободен для главной.
    static let frameInterval: Duration = .milliseconds(200)

    init(id: String, endpoint: CameraEndpoint) {
        self.id = id
        self.endpoint = endpoint
    }

    var recording: Bool { properties[SonyProp.movieRecordingState]?.current.intValue == 1 }
    var battery: Int64? { properties[SonyProp.batteryLevel]?.current.intValue }

    /// Подключиться самой — камера в ленте, а её нет на связи. Тихо: неудача — просто false, сопряжение не трогаем.
    func connect(identity: PTPIPIdentity) async -> Bool {
        guard !connected, !connecting else { return connected }
        connecting = true
        defer { connecting = false }
        let transport = PTPIPTransport(host: endpoint.host, port: endpoint.port, identity: identity)
        do {
            _ = try await transport.connect(connectTimeout: .seconds(3), initTimeout: .seconds(5))
            let session = CameraSession(transport: transport)
            try await session.start()
            try await session.waitUntilReady()
            adopt(Handed(session: session, transport: transport, endpoint: endpoint, deviceInfo: await session.deviceInfo,
                         properties: await session.properties, recordingStartedAt: nil))
            return true
        } catch {
            await transport.closeAbortively()
            return false
        }
    }

    /// Взять открытый сеанс: превью — низкого качества, около 5 к/с.
    func adopt(_ handed: Handed) {
        stopTasks()
        session = handed.session
        transport = handed.transport
        endpoint = handed.endpoint
        deviceInfo = handed.deviceInfo
        properties = handed.properties
        recordingStartedAt = handed.recordingStartedAt ?? (recording ? Date() : nil)
        connected = true
        let session = handed.session
        requested = [:]
        // по одной записи за раз и на всю камеру: формат и параметры записи, баланс белого и температуру камера,
        // занятая прошлой записью, пропускает
        let gate = WriteGate()
        writeGate = gate
        recordGate = WriteGate()
        coalescer = WriteCoalescer { [weak self] code, value in
            await gate.acquire()
            let result = await Self.put(code, value, on: session)
            await gate.release()
            await self?.writeFinished(code, value, result)
        }
        Task { try? await session.setQuietly(SonyProp.liveViewImageQuality, to: LiveViewQuality.low.rawValue, type: PTPType.uint8) }
        tasks.append(Task { [weak self] in
            for await snapshot in session.snapshotStream() { self?.apply(snapshot) }
            // поток закончился сам — сеанс оборвался
            guard let self, !Task.isCancelled else { return }
            self.lost()
        })
        tasks.append(Task.detached(priority: .utility) { [weak self] in
            while !Task.isCancelled {
                do {
                    let jpeg = try await session.nextLiveViewFrame()
                    if let image = SideCamera.thumbnail(jpeg) { await self?.show(image) }
                } catch TransportError.disconnected {
                    break
                } catch is CancellationError {
                    break
                } catch {}
                try? await Task.sleep(for: SideCamera.frameInterval)
            }
        })
    }

    // MARK: - Группа: повтор за главной

    /// То же значение, что на главной. Уже такое — не пишем.
    func write(_ id: SettingID, _ value: Int64) {
        guard connected, let coalescer else { return }
        // температура — ещё и в режиме «Цвет. темп.»: без него та же цифра на камере ничего не значит
        let same = properties[id.code]?.current.intValue == value
            && (id != .colorTemperature || properties[SonyProp.whiteBalance]?.current.intValue == WhiteBalanceTemperature.manualMode)
        if same, requested[id.code] == nil { return }
        requested[id.code] = value
        Task { await coalescer.submit(id.code, value) }
    }

    /// Одна запись. Температура — сначала «Цвет. темп.» в балансе белого, если камера не в нём: как ползунок на главной.
    private nonisolated static func put(_ code: UInt16, _ value: Int64, on session: CameraSession) async -> SetResult? {
        if code == SonyProp.colorTemp {
            let manual = WhiteBalanceTemperature.manualMode
            if await session.properties[SonyProp.whiteBalance]?.current.intValue != manual {
                guard (try? await session.set(SonyProp.whiteBalance, to: manual)) == .confirmed else { return nil }
            }
            // температуру камера открывает не сразу после смены режима
            for _ in 0..<40 {
                if await session.properties[code]?.isWritable == true { break }
                try? await Task.sleep(for: .milliseconds(100))
            }
        }
        let result = try? await session.set(code, to: value)
        // формат камера подтверждает раньше, чем готова принять следующую запись — как на главной
        if code == SonyProp.movieFileFormat { try? await Task.sleep(for: .milliseconds(500)) }
        return result
    }

    private func writeFinished(_ code: UInt16, _ value: Int64, _ result: SetResult?) {
        // промежуточные значения (быстрые шаги) не считаем: судим только последнее
        guard requested[code] == value else { return }
        requested[code] = nil
        guard result != .confirmed, let id = SettingID.allCases.first(where: { $0.code == code }) else { return }
        onWriteFailed?(id, value)
    }

    /// Запись — к тому же состоянию, что на главной: нажимаем, только если иначе. Нажатия — по одному: «стоп», пока
    /// камера не ответила на «пуск», смотрит уже на её ответ. Возвращает, вышло ли.
    func setRecording(_ on: Bool) async -> Bool {
        guard connected, let session, let recordGate else { return false }
        await recordGate.acquire()
        var done = false
        // пока ждали очереди, сеанс могли отдать главному экрану — его запись не трогаем
        if connected, self.session === session {
            if await session.properties[SonyProp.movieRecordingState]?.current.intValue == (on ? 1 : 0) {
                done = true
            } else {
                do {
                    done = try await session.toggleRecording() == on
                } catch let SessionError.recordButtonRefused(_, _, recording) {
                    // камера отклонила нажатие, а запись всё же сменилась — дело сделано
                    done = recording == on
                } catch {
                    done = false
                }
            }
        }
        await recordGate.release()
        return done
    }

    /// Пресет — и на эту камеру, в очереди записей. Возвращает, что не применилось.
    func apply(_ preset: Preset) async -> [SettingID] {
        guard connected, let session, let writeGate else { return [] }
        await writeGate.acquire()
        var skipped: [SettingID] = []
        if connected, self.session === session { skipped = await session.apply(preset).skipped }
        await writeGate.release()
        return skipped
    }

    /// Отдать сеанс главному экрану: слушать перестаём, соединение не закрываем.
    func handOver() -> Handed? {
        guard connected, let session, let transport else { return nil }
        stopTasks()
        connected = false
        self.session = nil
        self.transport = nil
        return Handed(session: session, transport: transport, endpoint: endpoint, deviceInfo: deviceInfo,
                      properties: properties, recordingStartedAt: recordingStartedAt)
    }

    /// Закрыть сеанс: убрали из ленты, забыли камеру, программа закрывается. Камеру оставляем на высоком качестве
    /// превью — в ленте оно было низким, — потом сброс: камера освободится сразу.
    func close() {
        stopTasks()
        connected = false
        let transport = transport
        let session = session
        self.session = nil
        self.transport = nil
        Task {
            try? await session?.setQuietly(SonyProp.liveViewImageQuality, to: LiveViewQuality.high.rawValue, type: PTPType.uint8)
            await transport?.closeAbortively()
        }
    }

    private func stopTasks() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        coalescer = nil
        writeGate = nil
        recordGate = nil
        requested = [:]
    }

    private func lost() {
        stopTasks()
        connected = false
        session = nil
        let transport = transport
        self.transport = nil
        Task { await transport?.closeAbortively() }
    }

    private func apply(_ snapshot: [UInt16: SonyProperty]) {
        properties = snapshot
        if recording, recordingStartedAt == nil { recordingStartedAt = Date() }
        if !recording { recordingStartedAt = nil }
    }

    private func show(_ image: CGImage) {
        frame = image
        let now = Date()
        frameTimes.append(now)
        frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
        fps = Int((Double(frameTimes.count) / 2).rounded())
    }

    /// Кадр для плитки — сразу маленьким: декодировать весь кадр ради 160×90 незачем.
    nonisolated static func thumbnail(_ jpeg: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                        kCGImageSourceThumbnailMaxPixelSize: 320]
        return CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary)
    }
}
