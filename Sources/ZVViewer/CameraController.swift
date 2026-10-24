import CoreGraphics
import CoreWLAN
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
    /// fps для строки состояния: обновляется дважды в секунду, а не с каждым кадром.
    private(set) var shownFPS = 0
    /// Самая долгая пауза между кадрами за последние 10 с, мс — насколько ровно идёт картинка.
    private(set) var frameGapMs = 0
    /// Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила.
    private(set) var pending: Set<SettingID> = []
    private(set) var isRecording = false
    private(set) var presets: [Preset] = []
    private(set) var isApplyingPreset = false
    /// Короткое сообщение: пресет применён, снимок сохранён и т.п.
    private(set) var statusMessage: String?
    /// Когда началась запись (для таймера «● REC»).
    private(set) var recordingStartedAt: Date?
    private(set) var isCapturing = false
    /// Что показывается поверх видео после снимка или при открытии снимка из ленты.
    private(set) var review: Review?
    /// Снимки и стоп-кадры этой сессии, новые сначала.
    private(set) var shots: [Shot] = []

    struct Shot: Identifiable {
        let id = UUID()
        let urls: [URL]
        let thumbnail: CGImage?
        var title: String { urls.map(\.lastPathComponent).joined(separator: " + ") }
    }

    struct Review {
        var image: CGImage?
        var title: String
        var detail: String
        var urls: [URL]
        var source: String
        /// nil — показывается, пока не закроют (снимок открыт из ленты).
        var endsAt: Date?
    }

    @ObservationIgnored private var reviewTask: Task<Void, Never>?
    @ObservationIgnored private var temperatureTask: Task<Void, Never>?
    @ObservationIgnored private var recordingModeTask: Task<Void, Never>?

    /// Последний кадр как пришёл с камеры (для API автоматизации).
    @ObservationIgnored private(set) var lastJPEG: Data?
    @ObservationIgnored private let discovery = CameraDiscovery()
    @ObservationIgnored private var device: ICCameraDevice?
    /// Как закрыть текущее подключение (USB или Wi-Fi).
    @ObservationIgnored private var closeTransport: (() async -> Void)?
    @ObservationIgnored private let identity = PTPIPIdentity.load()
    @ObservationIgnored private var wifiBusy = false
    @ObservationIgnored private var wifiFailures = 0
    @ObservationIgnored private var session: CameraSession?
    @ObservationIgnored private var coalescer: WriteCoalescer?
    @ObservationIgnored private var targets: [UInt16: Int64] = [:]
    @ObservationIgnored private var tasks: [Task<Void, Never>] = []
    @ObservationIgnored private var frameTimes: [Date] = []
    @ObservationIgnored private var lastFrameAt: Date?
    /// Паузы между кадрами за последние 10 с: когда пришёл кадр и сколько его ждали.
    @ObservationIgnored private var frameGaps: [(at: Date, gap: TimeInterval)] = []
    /// Связь по Wi-Fi — для подсказки с адресами.
    @ObservationIgnored private var wifiTransport: PTPIPTransport?
    @ObservationIgnored private var statusTask: Task<Void, Never>?
    @ObservationIgnored private let presetsKey = "presets"
    @ObservationIgnored private var tracker = ConnectionTracker<ObjectIdentifier>()
    @ObservationIgnored private var devices: [ObjectIdentifier: ICCameraDevice] = [:]

    /// Почему сейчас нет связи (показывается на экране ожидания).
    private(set) var connectionMessage: String?
    /// Камера на связи, но кадры давно не приходят.
    private(set) var isFrameStale = false

    enum TransportKind: String { case usb, wifi }
    /// Чем подключена камера сейчас.
    private(set) var transportKind: TransportKind?
    /// Связь по Wi-Fi напрямую: камера сама раздаёт сеть (режим Wi-Fi Direct).
    private(set) var wifiDirect = false
    /// Идёт сопряжение по Wi-Fi (кнопка «Сопрячь с камерой»).
    private(set) var isPairing = false
    /// Что сейчас с Wi-Fi: «ищу камеру», «камера найдена, нужно сопряжение» и т.п.
    private(set) var wifiStatus: String?

    init() {
        presets = Self.loadPresets(key: presetsKey)
        discovery.start()
        let events = discovery.events
        Task { [weak self] in
            for await event in events {
                switch event {
                case let .appeared(device): self?.handleAppeared(device)
                case let .disappeared(device): self?.handleDisappeared(device)
                }
            }
        }
        // fps и «нет кадров» пересчитываются и без новых кадров
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                self?.tickFrameStats()
                self?.tickShownStats()
            }
        }
        // без кабеля — ищем камеру в сети
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(1))
                await self?.wifiTick()
            }
        }
    }

    func property(_ id: SettingID) -> SonyProperty? { properties[id.code] }

    func isWritable(_ id: SettingID) -> Bool {
        phase == .ready && !isApplyingPreset && !isCapturing && property(id)?.isWritable == true
    }

    /// Как подключена камера — для строки состояния: USB, Wi-Fi или Wi-Fi Direct.
    var transportLabel: String {
        guard transportKind == .wifi else { return "USB" }
        return wifiDirect ? "Wi-Fi Direct" : "Wi-Fi"
    }

    /// Подпись значения настройки. Баланс белого в режиме «Цвет. темп.» — сама температура; температура вне этого
    /// режима — примерная или «авто»: камера отдаёт сохранённую ручную, которая сейчас не действует.
    func label(_ id: SettingID) -> String {
        let whiteBalance = property(.whiteBalance)?.current.intValue
        let kelvin = property(.colorTemperature)?.current.intValue
        switch id {
        case .whiteBalance: return ValueFormatter.whiteBalanceLabel(whiteBalance: whiteBalance, colorTemperature: kelvin)
        case .colorTemperature:
            return WhiteBalanceTemperature.caption(WhiteBalanceTemperature.reading(whiteBalance: whiteBalance, colorTemperature: kelvin))
        case .movieRecordingSetting:
            return RecordingModes.label(format: property(.movieFileFormat)?.current.intValue,
                                        setting: property(.movieRecordingSetting)?.current.intValue)
        default: return ValueFormatter.label(id, property(id))
        }
    }

    // MARK: - Лента значений

    /// Режимы записи обоих форматов — «4K 25p 100M», «HD 100p 100M»…
    var recordingModes: [RecordingMode] {
        guard let format = property(.movieFileFormat), let setting = property(.movieRecordingSetting) else { return [] }
        let formats = SettingOptions.options(.movieFileFormat, format).map(\.value)
        return RecordingModes.options(formats: formats.isEmpty ? format.current.intValue.map { [$0] } ?? [] : formats,
                                      format: format.current.intValue,
                                      allowed: SettingOptions.options(.movieRecordingSetting, setting).map(\.value))
    }

    /// Значения для ленты. У параметров записи — режимы обоих форматов (значение — `RecordingMode.code`).
    func stripOptions(_ id: SettingID) -> [SettingOption] {
        if id == .movieRecordingSetting { return recordingModes.map { SettingOption(value: $0.code, label: $0.label) } }
        return property(id).map { SettingOptions.options(id, $0) } ?? []
    }

    /// Текущее значение в ленте — в той же кодировке, что `stripOptions`.
    func stripCurrent(_ id: SettingID) -> Int64? {
        guard id == .movieRecordingSetting else { return property(id)?.current.intValue }
        guard let format = property(.movieFileFormat)?.current.intValue,
              let setting = property(.movieRecordingSetting)?.current.intValue else { return nil }
        return RecordingMode(format: format, setting: setting).code
    }

    /// Выбор в ленте: обычная настройка — запись значения, режим записи — формат и параметры вместе.
    func choose(_ id: SettingID, _ value: Int64) {
        if id == .movieRecordingSetting, value > 0xFF {
            setRecordingMode(RecordingMode(code: value))
        } else {
            request(id, value)
        }
    }

    /// Формат и параметры записи. Параметры камера принимает только для текущего формата: если формат другой,
    /// сначала он, и ждём, пока камера подтвердит его и откроет нужные параметры.
    func setRecordingMode(_ mode: RecordingMode) {
        recordingModeTask?.cancel()
        if property(.movieFileFormat)?.current.intValue == mode.format {
            request(.movieRecordingSetting, mode.setting)
            return
        }
        guard isWritable(.movieFileFormat) else { return }
        request(.movieFileFormat, mode.format)
        pending.insert(.movieRecordingSetting)
        recordingModeTask = Task { [weak self] in
            for _ in 0..<50 {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, !Task.isCancelled else { return }
                guard self.property(.movieFileFormat)?.current.intValue == mode.format,
                      let setting = self.property(.movieRecordingSetting),
                      SettingOptions.options(.movieRecordingSetting, setting).contains(where: { $0.value == mode.setting }),
                      self.isWritable(.movieRecordingSetting) else { continue }
                if setting.current.intValue == mode.setting {
                    self.pending.remove(.movieRecordingSetting)
                } else {
                    self.request(.movieRecordingSetting, mode.setting)
                }
                return
            }
            self?.pending.remove(.movieRecordingSetting)
        }
    }

    /// Ручная температура. Пишется, только когда камера в режиме «Цвет. темп.», — иначе сначала переключаем режим
    /// и ждём, пока камера подтвердит его.
    func setColorTemperature(_ kelvin: Int64) {
        temperatureTask?.cancel()
        if property(.whiteBalance)?.current.intValue == WhiteBalanceTemperature.manualMode {
            request(.colorTemperature, kelvin)
            return
        }
        guard isWritable(.whiteBalance) else { return }
        request(.whiteBalance, WhiteBalanceTemperature.manualMode)
        temperatureTask = Task { [weak self] in
            for _ in 0..<40 {
                try? await Task.sleep(for: .milliseconds(100))
                guard let self, !Task.isCancelled else { return }
                if self.isWritable(.colorTemperature) {
                    if self.property(.colorTemperature)?.current.intValue != kelvin { self.request(.colorTemperature, kelvin) }
                    return
                }
            }
        }
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
        if id == .movieRecordingSetting {
            let modes = recordingModes
            guard let current = stripCurrent(id), let index = modes.firstIndex(where: { $0.code == current }) else { return }
            let target = min(max(index + offset, 0), modes.count - 1)
            if target != index { setRecordingMode(modes[target]) }
            return
        }
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

    // MARK: - Фото и видео

    var mode: CaptureMode {
        property(.exposureProgram)?.current.intValue.map(CaptureMode.init(exposureProgram:)) ?? .video
    }

    /// Переключает камеру между фото и видео, сохраняя букву режима (M ↔ видео M и т.п.).
    func switchMode(_ target: CaptureMode) {
        guard let p = property(.exposureProgram), let current = p.current.intValue,
              let value = CaptureMode.counterpart(of: current, to: target, settable: p.settableValues) else { return }
        request(.exposureProgram, value)
    }

    /// Пользователь поменял «куда сохранять» — переносим это в камеру (PC Remote → Still Img. Save Dest.).
    func applyDestinationPreference() {
        guard phase == .ready, let session else { return }
        let want = Prefs.destination.cameraValue
        guard properties[SonyProp.stillImageStoreDestination]?.current.intValue != want else { return }
        Task { _ = try? await session.set(SonyProp.stillImageStoreDestination, to: want) }
    }

    func capturePhoto() {
        guard phase == .ready, mode == .photo, !isCapturing, !isApplyingPreset, let session else { return }
        isCapturing = true
        let destination = Prefs.destination
        let folder = Prefs.folderURL
        let cameraFrame = frame
        let reviewOn = Prefs.reviewEnabled
        let source: Prefs.ReviewSource = destination.savesToMac ? Prefs.reviewSource : .camera
        if reviewOn {
            // кадр с камеры есть сразу; загруженный снимок появится после скачивания
            showReview(Review(image: cameraFrame, title: "Снимок", detail: source == .camera ? "" : "Загружаю…",
                              urls: [], source: "Кадр с камеры", endsAt: nil), timed: source == .camera)
        }
        Task { [weak self] in
            guard let self else { return }
            defer { self.isCapturing = false }
            do {
                if self.properties[SonyProp.stillImageStoreDestination]?.current.intValue != destination.cameraValue {
                    _ = try await session.set(SonyProp.stillImageStoreDestination, to: destination.cameraValue)
                }
                let objects = try await session.capturePhoto(download: destination.savesToMac)
                guard destination.savesToMac else {
                    self.showStatus("Снимок сохранён на карту камеры")
                    return
                }
                guard !objects.isEmpty else { throw CaptureFailure.noFile }
                let urls = try await Self.save(objects, to: folder)
                let preview = await Self.previews(of: urls)
                let shot = Shot(urls: urls, thumbnail: preview.thumbnail)
                self.shots.insert(shot, at: 0)
                let megabytes = Double(objects.reduce(0) { $0 + $1.data.count }) / 1_048_576
                let detail = String(format: "%.1f МБ · «%@»", megabytes, FileManager.default.displayName(atPath: folder.path))
                self.showStatus("Сохранено: \(shot.title)")
                if reviewOn, self.review != nil {
                    let downloaded = source == .downloaded && preview.image != nil
                    self.showReview(Review(image: downloaded ? preview.image : cameraFrame, title: shot.title, detail: detail,
                                           urls: urls, source: downloaded ? "Загруженный снимок" : "Кадр с камеры", endsAt: nil),
                                    timed: true)
                }
            } catch {
                self.dismissReview()
                self.showStatus("Снимок не удался: \(error)")
            }
        }
    }

    /// Стоп-кадр из видео: сохраняет текущий кадр live view, запись не прерывается.
    func grabFrame() {
        guard phase == .ready || phase == .settling, let jpeg = lastJPEG else { return }
        let folder = Prefs.folderURL
        let thumbnail = frame
        Task { [weak self] in
            do {
                let name = FileNaming.frameGrab(at: Date())
                let urls = try await Self.save([CapturedObject(filename: name, data: jpeg)], to: folder)
                self?.shots.insert(Shot(urls: urls, thumbnail: thumbnail), at: 0)
                self?.showStatus("Кадр сохранён: \(urls[0].lastPathComponent)")
            } catch {
                self?.showStatus("Не удалось сохранить кадр: \(error)")
            }
        }
    }

    /// Открывает снимок из ленты поверх видео (пока не закроют).
    func openShot(_ index: Int) {
        guard shots.indices.contains(index) else { return }
        let shot = shots[index]
        Task { [weak self] in
            let preview = await Self.previews(of: shot.urls)
            self?.showReview(Review(image: preview.image ?? shot.thumbnail, title: shot.title,
                                    detail: shot.urls.first.map { "«\($0.deletingLastPathComponent().lastPathComponent)»" } ?? "",
                                    urls: shot.urls, source: "Снимок из ленты", endsAt: nil), timed: false)
        }
    }

    func dismissReview() {
        reviewTask?.cancel()
        review = nil
    }

    private func showReview(_ value: Review, timed: Bool) {
        reviewTask?.cancel()
        var value = value
        guard timed else {
            review = value
            return
        }
        let seconds = Prefs.reviewSeconds
        value.endsAt = Date().addingTimeInterval(TimeInterval(seconds))
        review = value
        reviewTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(seconds))
            if !Task.isCancelled { self?.review = nil }
        }
    }

    enum CaptureFailure: Error, CustomStringConvertible {
        case noFile
        var description: String { "камера не отдала файл" }
    }

    /// Пишет файлы в папку, не затирая существующие (DSC01234 (2).JPG и т.д.).
    nonisolated private static func save(_ objects: [CapturedObject], to folder: URL) async throws -> [URL] {
        try await Task.detached(priority: .userInitiated) {
            let fm = FileManager.default
            try fm.createDirectory(at: folder, withIntermediateDirectories: true)
            return try objects.map { object in
                let name = FileNaming.unique(object.filename) { fm.fileExists(atPath: folder.appendingPathComponent($0).path) }
                let url = folder.appendingPathComponent(name)
                try object.data.write(to: url, options: .atomic)
                return url
            }
        }.value
    }

    /// Картинка для просмотра (до 2048 px) и миниатюра для ленты. JPEG предпочтительнее RAW: быстрее.
    nonisolated private static func previews(of urls: [URL]) async -> (image: CGImage?, thumbnail: CGImage?) {
        await Task.detached(priority: .userInitiated) {
            let sorted = urls.sorted { a, _ in ["jpg", "jpeg"].contains(a.pathExtension.lowercased()) }
            for url in sorted {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { continue }
                func thumb(_ size: Int) -> CGImage? {
                    CGImageSourceCreateThumbnailAtIndex(source, 0, [
                        kCGImageSourceCreateThumbnailFromImageAlways: true,
                        kCGImageSourceCreateThumbnailWithTransform: true,
                        kCGImageSourceThumbnailMaxPixelSize: size,
                    ] as CFDictionary)
                }
                if let image = thumb(2048) { return (image, thumb(160)) }
            }
            return (nil, nil)
        }.value
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
        statusMessage = "Применяю «\(preset.name)»…"
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
        statusMessage = text
        statusTask?.cancel()
        statusTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            if !Task.isCancelled { self?.statusMessage = nil }
        }
    }

    // MARK: - Подключение

    private func handleAppeared(_ device: ICCameraDevice) {
        let id = ObjectIdentifier(device)
        devices[id] = device
        perform(tracker.appeared(id))
    }

    private func handleDisappeared(_ device: ICCameraDevice) {
        let id = ObjectIdentifier(device)
        let action = tracker.disappeared(id)
        devices[id] = nil
        perform(action)
    }

    private func perform(_ action: ConnectionTracker<ObjectIdentifier>.Action) {
        switch action {
        case .none:
            break
        case let .connect(id):
            if let device = devices[id] { connect(device) }
        case let .disconnect(next):
            teardown()
            connectionMessage = nil
            if let next, let device = devices[next] { connect(device) }
        case let .retry(id, delay):
            teardown()
            connectionMessage = "Не удалось подключиться к камере. Повтор через \(delay.components.seconds) с…"
            tasks.append(Task { [weak self] in
                try? await Task.sleep(for: delay)
                guard !Task.isCancelled, let self, self.tracker.current == id, let device = self.devices[id] else { return }
                self.connect(device)
            })
        }
    }

    private func connect(_ device: ICCameraDevice) {
        // кабель важнее сети: если шёл сеанс по Wi-Fi, закрываем его
        if transportKind == .wifi { teardown() }
        let id = ObjectIdentifier(device)
        self.device = device
        // транспорт становится делегатом устройства ещё до открытия сессии: колбэки ICC идут живому объекту
        let transport = ICCameraTransport(device: device)
        runSession(transport: transport, kind: .usb, close: { await transport.close() },
                   prepare: { try await device.openSession() },
                   onReady: { [weak self] in self?.tracker.connected(id) },
                   onFailure: { [weak self] in
                       // отменённая попытка (камеру отключили, начали заново) — не повторяем
                       guard let self else { return }
                       self.perform(self.tracker.failed(id))
                   })
    }

    /// Сеанс одинаков для USB и Wi-Fi: различаются подготовка транспорта и что делать при обрыве.
    private func runSession(transport: PTPTransport, kind: TransportKind, close: @escaping () async -> Void,
                            prepare: @escaping () async throws -> Void,
                            onReady: @escaping () -> Void, onFailure: @escaping () -> Void) {
        phase = .connecting
        transportKind = kind
        let session = CameraSession(transport: transport)
        closeTransport = close
        self.session = session
        coalescer = WriteCoalescer { [weak self] code, value in
            let result = try? await session.set(code, to: value)
            await self?.writeFinished(code, value, result)
        }
        tasks.append(Task { [weak self] in
            do {
                try await prepare()
                try await session.start()
                guard let self, !Task.isCancelled else { return }
                self.deviceInfo = await session.deviceInfo
                self.phase = .settling
                self.startStreams(session, onEnded: onFailure)
                try await session.waitUntilReady()
                guard !Task.isCancelled else { return }
                self.phase = .ready
                self.connectionMessage = nil
                onReady()
            } catch is CancellationError {
                return
            } catch SessionError.notPCRemote {
                guard let self, !Task.isCancelled else { return }
                self.deviceInfo = await session.deviceInfo
                self.phase = .notPCRemote
            } catch {
                guard self != nil, !Task.isCancelled else { return }
                onFailure()
            }
        })
    }

    // MARK: - Wi-Fi

    /// Начать сопряжение: программа ищет камеру и стучится, пока на камере включено Pairing.
    func startPairing() {
        isPairing = true
        wifiStatus = "Ищу камеру в сети…"
    }

    func cancelPairing() {
        isPairing = false
        wifiStatus = nil
    }

    /// Забыть камеру: программа перестанет подключаться к ней сама (сопряжение в камере остаётся).
    func forgetWiFiCamera() {
        Prefs.wifiHost = nil
        Prefs.wifiCameraName = nil
        if transportKind == .wifi { teardown() }
    }

    /// Раз в секунду: если камеры на кабеле нет и связи нет — ищем камеру в сети и подключаемся.
    private func wifiTick() async {
        guard (Prefs.wifiEnabled || isPairing), phase == .searching, tracker.attached.isEmpty, !wifiBusy else { return }
        wifiBusy = true
        defer { wifiBusy = false }
        // сначала — последний адрес камеры: так подключение после перерыва занимает секунду, а не поиск
        let last = isPairing ? nil : Prefs.wifiHost
        if let last, await tryWiFi(last) { return }
        if isPairing, wifiStatus == nil { wifiStatus = "Ищу камеру в сети…" }
        let found = await WiFiDiscovery.search()
        for host in found.map(\.host) where host != last {
            guard phase == .searching, tracker.attached.isEmpty else { return }
            if await tryWiFi(host) { return }
        }
        if found.isEmpty, isPairing { wifiStatus = "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi" }
    }

    /// Одна попытка подключиться к камере по адресу. true — сеанс начат.
    private func tryWiFi(_ host: String) async -> Bool {
        let transport = PTPIPTransport(host: host, identity: identity)
        if isPairing { wifiStatus = "Камера найдена (\(host)). Включите на камере PC Remote Function → Pairing и подтвердите сопряжение" }
        do {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            let name = try await transport.connect(initTimeout: isPairing ? .seconds(60) : .seconds(5))
            guard phase == .searching, tracker.attached.isEmpty else {
                await transport.close()
                return false
            }
            Prefs.wifiHost = host
            Prefs.wifiCameraName = name
            wifiDirect = PTPIP.isCameraAccessPoint(host)
            wifiFailures = 0
            isPairing = false
            wifiStatus = nil
            connectionMessage = nil
            wifiTransport = transport
            runSession(transport: transport, kind: .wifi, close: { await transport.close() }, prepare: {},
                       onReady: {}, onFailure: { [weak self] in self?.wifiLost() })
            return true
        } catch PTPIPError.rejected {
            await transport.close()
            wifiStatus = isPairing
                ? "Камера найдена (\(host)). Включите на камере PC Remote Function → Pairing"
                : "В сети есть камера (\(host)), но программа с ней не сопряжена — нажмите «Сопрячь с камерой»"
            return false
        } catch {
            await transport.close()
            // камера в сети, но не отвечает: обычно она ещё держит прошлое, оборванное подключение
            wifiFailures += 1
            if wifiFailures >= 3, !isPairing {
                wifiStatus = "Камера найдена (\(host)), но пока не отвечает — видимо, держит прошлое подключение. "
                    + "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
            }
            return false
        }
    }

    private func wifiLost() {
        teardown()
        connectionMessage = "Связь по Wi-Fi пропала. Ищу камеру…"
    }

    private func startStreams(_ session: CameraSession, onEnded: @escaping () -> Void) {
        tasks.append(Task { [weak self] in
            for await snapshot in session.snapshots {
                self?.apply(snapshot)
            }
            // поток закончился сам — сеанс оборвался; дальше решает, кто начал сеанс (USB или Wi-Fi)
            guard self != nil, !Task.isCancelled else { return }
            onEnded()
        })
        // следующий кадр запрашиваем, пока текущий декодируется: по Wi-Fi запрос идёт 25–35 мс.
        // Не успеваем показать — показываем самый свежий
        let (frames, sink) = AsyncStream.makeStream(of: Data.self, bufferingPolicy: .bufferingNewest(1))
        tasks.append(Task.detached(priority: .userInitiated) {
            defer { sink.finish() }
            var failures = 0
            while !Task.isCancelled {
                do {
                    sink.yield(try await session.nextLiveViewFrame())
                    failures = 0
                } catch TransportError.disconnected {
                    break
                } catch is CancellationError {
                    break
                } catch {
                    // временный сбой (таймаут, неожиданный ответ) — не бросаем live view, пробуем дальше
                    failures += 1
                    try? await Task.sleep(for: .milliseconds(min(100 * failures, 1000)))
                }
            }
        })
        tasks.append(Task.detached(priority: .userInitiated) { [weak self] in
            for await jpeg in frames {
                guard let image = Self.decode(jpeg) else { continue }
                let bins = Self.histogram(of: image)
                guard !Task.isCancelled else { break }
                await self?.show(image, jpeg: jpeg, histogram: bins)
            }
        })
    }

    private func apply(_ snapshot: [UInt16: SonyProperty]) {
        properties = snapshot
        let recording = snapshot[SonyProp.movieRecordingState]?.current.intValue == 1
        if recording, recordingStartedAt == nil { recordingStartedAt = Date() }
        if !recording { recordingStartedAt = nil }
        isRecording = recording
        for (code, target) in targets where snapshot[code]?.current.intValue == target {
            clearTarget(code)
        }
    }

    private func show(_ image: CGImage, jpeg: Data, histogram: [Int]) {
        frame = image
        lastJPEG = jpeg
        self.histogram = histogram
        let now = Date()
        if let lastFrameAt { frameGaps.append((now, now.timeIntervalSince(lastFrameAt))) }
        lastFrameAt = now
        frameTimes.append(now)
        tickFrameStats()
    }

    /// Числа для строки состояния и подсказки о кадрах; пересчитываются дважды в секунду.
    private func tickShownStats() {
        let now = Date()
        frameGaps.removeAll { now.timeIntervalSince($0.at) > 10 }
        // кадры перестали приходить — пауза растёт и сейчас
        let open = frame == nil ? 0 : lastFrameAt.map { now.timeIntervalSince($0) } ?? 0
        let gap = Int((max(frameGaps.map(\.gap).max() ?? 0, open) * 1000).rounded())
        let shown = Int(fps.rounded())
        if shownFPS != shown { shownFPS = shown }
        if frameGapMs != gap { frameGapMs = gap }
    }

    struct ConnectionDetails {
        var cameraHost: String?
        var localHost: String?
        var signalDBm: Int?
    }

    /// Для подсказки о подключении: адреса камеры и Mac, сигнал Wi-Fi.
    func connectionDetails() async -> ConnectionDetails {
        guard let wifi = wifiTransport else { return ConnectionDetails() }
        let rssi = CWWiFiClient.shared().interface()?.rssiValue() ?? 0
        return ConnectionDetails(cameraHost: wifi.host, localHost: await wifi.localHost(), signalDBm: rssi == 0 ? nil : rssi)
    }

    /// fps за последние 2 с и признак «кадры не приходят» (больше 1,5 с).
    private func tickFrameStats() {
        let now = Date()
        frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
        fps = Double(frameTimes.count) / 2
        let live = phase == .settling || phase == .ready
        isFrameStale = live && frame != nil && (lastFrameAt.map { now.timeIntervalSince($0) > 1.5 } ?? false)
    }

    private func writeFinished(_ code: UInt16, _ value: Int64, _ result: SetResult?) {
        // ждём только последнее запрошенное значение; промежуточные пропускаем
        if targets[code] == value { clearTarget(code) }
    }

    private func clearTarget(_ code: UInt16) {
        targets[code] = nil
        if let id = SettingID.allCases.first(where: { $0.code == code }) { pending.remove(id) }
    }

    /// Останавливает всё, что связано с текущей камерой, и закрывает сессию.
    private func teardown() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        let close = closeTransport
        Task { await close?() }
        device = nil
        closeTransport = nil
        transportKind = nil
        wifiDirect = false
        wifiTransport = nil
        session = nil
        coalescer = nil
        targets = [:]
        pending = []
        frame = nil
        lastJPEG = nil
        lastFrameAt = nil
        frameTimes = []
        frameGaps = []
        histogram = []
        fps = 0
        shownFPS = 0
        frameGapMs = 0
        isFrameStale = false
        properties = [:]
        deviceInfo = nil
        isRecording = false
        recordingStartedAt = nil
        isApplyingPreset = false
        isCapturing = false
        reviewTask?.cancel()
        review = nil
        phase = .searching
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
