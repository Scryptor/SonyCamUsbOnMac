import CoreGraphics
import Foundation
import ImageCaptureCore
import ImageIO
import Observation
import ZVCamera
import ZVCore

/// Состояние приложения: поиск камеры, сеанс, live view и изменения настроек.
@MainActor
@Observable
final class CameraController {
    enum Phase: Equatable {
        case searching, notPCRemote, connecting, settling, ready
    }

    private(set) var phase: Phase = .searching
    private(set) var deviceInfo: DeviceInfo?
    private(set) var properties: [UInt16: SonyProperty] = [:]
    private(set) var frame: CGImage?
    /// Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила.
    private(set) var pending: Set<SettingID> = []
    private(set) var isRecording = false

    @ObservationIgnored private let discovery = CameraDiscovery()
    @ObservationIgnored private var device: ICCameraDevice?
    @ObservationIgnored private var transport: ICCameraTransport?
    @ObservationIgnored private var session: CameraSession?
    @ObservationIgnored private var coalescer: WriteCoalescer?
    @ObservationIgnored private var targets: [UInt16: Int64] = [:]
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []

    init() {
        discovery.start()
        let events = discovery.events
        Task { [weak self] in
            for await event in events {
                switch event {
                case let .appeared(device):
                    self?.connect(device)
                case let .disappeared(device):
                    if device === self?.device { self?.disconnect() }
                }
            }
        }
    }

    func property(_ id: SettingID) -> SonyProperty? { properties[id.code] }

    func request(_ id: SettingID, _ value: Int64) {
        guard phase == .ready, let coalescer else { return }
        targets[id.code] = value
        pending.insert(id)
        Task { await coalescer.submit(id.code, value) }
    }

    func toggleRecording() {
        guard phase == .ready, let session else { return }
        Task { [weak self] in
            if let recording = try? await session.toggleRecording() { self?.isRecording = recording }
        }
    }

    // MARK: - Подключение

    private func connect(_ device: ICCameraDevice) {
        guard self.device == nil else { return }
        self.device = device
        phase = .connecting
        tasks.append(Task { [weak self] in
            do {
                try await device.openSession()
                let transport = ICCameraTransport(device: device)
                let session = CameraSession(transport: transport)
                guard let self else { return }
                self.transport = transport
                self.session = session
                self.coalescer = WriteCoalescer { [weak self] code, value in
                    let result = try? await session.set(code, to: value)
                    await self?.writeFinished(code, value, result)
                }
                try await session.start()
                self.deviceInfo = await session.deviceInfo
                self.phase = .settling
                self.startStreams(session)
                try await session.waitUntilReady()
                self.phase = .ready
            } catch SessionError.notPCRemote {
                guard let self else { return }
                self.deviceInfo = await self.session?.deviceInfo
                self.phase = .notPCRemote
            } catch {
                self?.retry(device)
            }
        })
    }

    private func startStreams(_ session: CameraSession) {
        tasks.append(Task { [weak self] in
            for await snapshot in session.snapshots {
                self?.apply(snapshot)
            }
            // поток закончился — камера отключилась или сеанс оборвался
            self?.disconnect()
        })
        tasks.append(Task.detached(priority: .userInitiated) { [weak self] in
            while !Task.isCancelled {
                guard let jpeg = try? await session.nextLiveViewFrame() else { break }
                guard let image = Self.decode(jpeg) else { continue }
                await self?.show(image)
            }
        })
    }

    private func apply(_ snapshot: [UInt16: SonyProperty]) {
        properties = snapshot
        isRecording = snapshot[SonyProp.movieRecordingState]?.current.intValue == 1
        for (code, target) in targets where snapshot[code]?.current.intValue == target {
            clearTarget(code)
        }
    }

    private func show(_ image: CGImage) { frame = image }

    private func writeFinished(_ code: UInt16, _ value: Int64, _ result: SetResult?) {
        // ждём только последнее запрошенное значение; промежуточные пропускаем
        if targets[code] == value { clearTarget(code) }
    }

    private func clearTarget(_ code: UInt16) {
        targets[code] = nil
        if let id = SettingID.allCases.first(where: { $0.code == code }) { pending.remove(id) }
    }

    private func disconnect() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        let transport = self.transport
        Task { await transport?.close() }
        device = nil
        self.transport = nil
        session = nil
        coalescer = nil
        targets = [:]
        pending = []
        frame = nil
        properties = [:]
        deviceInfo = nil
        isRecording = false
        phase = .searching
    }

    /// Камера на месте, но сеанс не поднялся (например, была занята) — пробуем ещё раз через 2 с.
    private func retry(_ device: ICCameraDevice) {
        disconnect()
        Task { [weak self] in
            try? await Task.sleep(for: .seconds(2))
            self?.connect(device)
        }
    }

    nonisolated private static func decode(_ jpeg: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }
}
