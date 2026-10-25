import CoreGraphics
import Foundation
import ImageIO
import Observation
import ZVCamera
import ZVCore

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

    /// Закрыть сеанс: убрали из ленты, забыли камеру, программа закрывается. Сбросом — камера освободится сразу.
    func close() {
        stopTasks()
        connected = false
        let transport = transport
        session = nil
        self.transport = nil
        Task { await transport?.closeAbortively() }
    }

    private func stopTasks() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
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
