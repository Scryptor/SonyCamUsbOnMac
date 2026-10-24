import CoreGraphics
import Foundation
import ImageCaptureCore
import ImageIO
import Observation
import ZVCamera
import ZVCore

/// Состояние приложения: поиск камеры, сеанс, live view, изменения настроек и пресеты.
@MainActor
@Observable
final class CameraController {
    enum Phase: String, Equatable {
        case searching, notPCRemote, connecting, settling, ready
    }

    private(set) var phase: Phase = .searching
    private(set) var deviceInfo: DeviceInfo?
    private(set) var properties: [UInt16: SonyProperty] = [:]
    private(set) var frame: CGImage?
    /// Гистограмма яркости последнего кадра (64 столбца).
    private(set) var histogram: [Int] = []
    private(set) var fps: Double = 0
    /// Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила.
    private(set) var pending: Set<SettingID> = []
    private(set) var isRecording = false
    private(set) var presets: [Preset] = []
    private(set) var isApplyingPreset = false
    /// Короткое сообщение о результате применения пресета.
    private(set) var presetStatus: String?

    /// Последний кадр как пришёл с камеры (для API автоматизации).
    @ObservationIgnored private(set) var lastJPEG: Data?
    @ObservationIgnored private let discovery = CameraDiscovery()
    @ObservationIgnored private var device: ICCameraDevice?
    @ObservationIgnored private var transport: ICCameraTransport?
    @ObservationIgnored private var session: CameraSession?
    @ObservationIgnored private var coalescer: WriteCoalescer?
    @ObservationIgnored private var targets: [UInt16: Int64] = [:]
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []
    @ObservationIgnored private var frameTimes: [Date] = []
    @ObservationIgnored private var statusTask: Task<Void, Never>?
    @ObservationIgnored private let presetsKey = "presets"

    init() {
        presets = Self.loadPresets(key: presetsKey)
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

    func isWritable(_ id: SettingID) -> Bool {
        phase == .ready && !isApplyingPreset && property(id)?.isWritable == true
    }

    func request(_ id: SettingID, _ value: Int64) {
        guard isWritable(id), let coalescer else { return }
        targets[id.code] = value
        pending.insert(id)
        Task { await coalescer.submit(id.code, value) }
    }

    /// Шаг по списку значений (стрелки, колесо): считаем от последнего запроса, а не от подтверждённого значения,
    /// иначе быстрые шаги упирались бы в ожидание камеры.
    func requestStep(_ id: SettingID, offset: Int) {
        guard let property = property(id),
              let target = SettingOptions.neighbour(id, property, offset: offset, from: targets[id.code]) else { return }
        request(id, target)
    }

    func toggleRecording() {
        guard phase == .ready, let session else { return }
        Task { [weak self] in
            if let recording = try? await session.toggleRecording() { self?.isRecording = recording }
        }
    }

    // MARK: - Пресеты

    func savePreset(name: String? = nil) {
        guard phase == .ready else { return }
        let title = name?.trimmingCharacters(in: .whitespaces).isEmpty == false ? name! : "Пресет \(presets.count + 1)"
        presets.append(PresetCatalog.capture(name: title, from: properties))
        storePresets()
        showStatus("Сохранено: «\(title)»")
    }

    func applyPreset(_ index: Int) {
        guard presets.indices.contains(index), phase == .ready, !isApplyingPreset, let session else { return }
        let preset = presets[index]
        isApplyingPreset = true
        presetStatus = "Применяю «\(preset.name)»…"
        Task { [weak self] in
            let result = await session.apply(preset)
            guard let self else { return }
            self.isApplyingPreset = false
            let total = result.applied.count + result.skipped.count
            var text = "«\(preset.name)»: применено \(result.applied.count) из \(total)"
            if !result.skipped.isEmpty {
                text += ". Не удалось: " + result.skipped.map(\.title).joined(separator: ", ")
            }
            self.showStatus(text)
        }
    }

    func renamePreset(_ index: Int, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard presets.indices.contains(index), !trimmed.isEmpty else { return }
        presets[index].name = trimmed
        storePresets()
    }

    func deletePreset(_ index: Int) {
        guard presets.indices.contains(index) else { return }
        presets.remove(at: index)
        storePresets()
    }

    private func storePresets() {
        if let data = try? JSONEncoder().encode(presets) { UserDefaults.standard.set(data, forKey: presetsKey) }
    }

    private static func loadPresets(key: String) -> [Preset] {
        guard let data = UserDefaults.standard.data(forKey: key) else { return [] }
        return (try? JSONDecoder().decode([Preset].self, from: data)) ?? []
    }

    private func showStatus(_ text: String) {
        presetStatus = text
        statusTask?.cancel()
        statusTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            if !Task.isCancelled { self?.presetStatus = nil }
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
                let bins = Self.histogram(of: image)
                await self?.show(image, jpeg: jpeg, histogram: bins)
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

    private func show(_ image: CGImage, jpeg: Data, histogram: [Int]) {
        frame = image
        lastJPEG = jpeg
        self.histogram = histogram
        let now = Date()
        frameTimes.append(now)
        frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
        fps = Double(frameTimes.count) / 2
    }

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
        lastJPEG = nil
        histogram = []
        fps = 0
        properties = [:]
        deviceInfo = nil
        isRecording = false
        isApplyingPreset = false
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

    /// Гистограмма по уменьшенной копии кадра (128×72 достаточно для графика).
    nonisolated private static func histogram(of image: CGImage) -> [Int] {
        let width = 128, height = 72, bytesPerRow = width * 4
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * height)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .low
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? LumaHistogram.bins(rgba: pixels, width: width, height: height, bytesPerRow: bytesPerRow) : []
    }
}
