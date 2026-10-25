import CoreGraphics
import CoreWLAN
import Foundation
import ImageCaptureCore
import ImageIO
import Network
import Observation
import os
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
    /// Доли теней, полутонов и светов, провалы, пересвет и средняя яркость по тому же кадру.
    private(set) var histogramStats: LumaStats?
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
    /// Где сейчас снимок: фокусировка, спуск, загрузка (доля — по Wi-Fi); nil — не снимаем.
    private(set) var capturePhase: CapturePhase?
    /// Запуск или остановка записи с кнопки, пока камера не подтвердила.
    private(set) var recordingToggle = RecordingToggle()
    /// Отклик на кнопки для экрана: последние события, новые в конце.
    private(set) var feedback: [FeedbackEvent] = []

    struct Shot: Identifiable {
        var id = UUID()
        let urls: [URL]
        let thumbnail: CGImage?
        var takenAt = Date()
        /// Размер снимка в точках (по миниатюре и картинке для просмотра); nil — не открылся.
        var pixelSize: CGSize?
        var title: String { urls.map(\.lastPathComponent).joined(separator: " + ") }
        var isFrameGrab: Bool { urls.count == 1 && urls[0].lastPathComponent.hasPrefix("Кадр ") }
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
    /// Опрос доли загрузки снимка, 10 раз в секунду.
    @ObservationIgnored private var progressTask: Task<Void, Never>?
    @ObservationIgnored private var temperatureTask: Task<Void, Never>?
    @ObservationIgnored private var recordingModeTask: Task<Void, Never>?
    /// Ведёт камеру к выбранному режиму записи; от его цели считаются быстрые шаги колесом.
    @ObservationIgnored private var recordingDriver = RecordingModeDriver()

    /// Последний кадр как пришёл с камеры (для API автоматизации).
    @ObservationIgnored private(set) var lastJPEG: Data?
    @ObservationIgnored private let discovery = CameraDiscovery()
    @ObservationIgnored private var device: ICCameraDevice?
    /// Как закрыть текущее подключение (USB или Wi-Fi).
    @ObservationIgnored private var closeTransport: (() async -> Void)?
    @ObservationIgnored private var identity = PTPIPIdentity.load()
    @ObservationIgnored private var wifiBusy = false
    @ObservationIgnored private var wifiPolicy = WiFiPolicy()
    /// Идущая попытка подключиться по Wi-Fi — чтобы оборвать её при отмене сопряжения или «Забыть».
    @ObservationIgnored private var connectingTransport: PTPIPTransport?
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
    /// Что показать поверх видео о связи: слабый сигнал, провал, переподключение.
    private(set) var linkIndicator: LinkIndicator = .none
    enum LinkIndicator: Equatable {
        case none
        /// кадры запаздывают дольше секунды
        case weak
        /// кадров давно нет или связь пропала — с этого момента
        case interrupted(since: Date)
        /// сеанс оборвался, программа тихо подключается заново — с этого момента
        case reconnecting(since: Date)
    }
    /// Каким качеством идёт превью по Wi-Fi (по ширине кадра); nil — по USB или кадров ещё нет.
    private(set) var previewQuality: LiveViewQuality?
    /// Идёт тихое восстановление оборвавшегося сеанса: кадр и настройки на экране, кнопки неактивны.
    private(set) var reconnectingSince: Date?
    /// Связь по Wi-Fi пропала, транспорт ждёт её — с этого момента.
    @ObservationIgnored private var linkOutageSince: Date?
    @ObservationIgnored private var linkRTT: TimeInterval?
    @ObservationIgnored private var qualityPolicy = LiveViewQualityPolicy(now: Date())
    @ObservationIgnored private var qualityWriting = false
    /// Когда в последний раз просили камеру вернуть высокое при выключенной адаптации: кадры меняются не сразу.
    @ObservationIgnored private var forcedHighAt: Date?
    @ObservationIgnored private var reconnectTask: Task<Void, Never>?
    @ObservationIgnored private var reconnectAttempt = 0
    /// Пока идёт сеанс, macOS не усыпляет программу (App Nap душил превью до 6–12 к/с), систему и экран.
    @ObservationIgnored private var sessionActivity: NSObjectProtocol?
    /// Для API автоматизации: сколько раз сеанс восстанавливался и сколько это заняло в последний раз.
    @ObservationIgnored private(set) var reconnects = 0
    @ObservationIgnored private(set) var lastRecoverySeconds: Double?
    /// Кадры за последние 5 с: сколько байт, сколько шёл запрос и сколько раз перед ним кадр был не готов.
    @ObservationIgnored private var recentFrames: [(at: Date, bytes: Int, seconds: TimeInterval, waits: Int)] = []
    @ObservationIgnored private let log = Logger(subsystem: "local.zve10viewer", category: "link")

    struct DebugEvent: Identifiable {
        let id = UUID()
        let at: Date
        let text: String
    }

    /// Журнал для панели отладки: последние 200 событий связи, новые в конце.
    private(set) var debugEvents: [DebugEvent] = []
    /// Для панели отладки — с начала сеанса (тихое восстановление сеанс не прерывает).
    @ObservationIgnored private(set) var sessionStartedAt: Date?
    @ObservationIgnored private(set) var outages = 0
    @ObservationIgnored private(set) var longestOutage: TimeInterval = 0
    @ObservationIgnored private(set) var qualitySwitches = 0
    @ObservationIgnored private(set) var framesTotal = 0
    /// С запуска программы: последний обрыв по Wi-Fi и его причина.
    @ObservationIgnored private(set) var lastLoss: (reason: LinkLoss?, at: Date)?
    /// Отклик камеры на пробы за последнюю минуту.
    @ObservationIgnored private var rttHistory: [(at: Date, seconds: TimeInterval)] = []

    enum TransportKind: String { case usb, wifi }
    /// Чем подключена камера сейчас.
    private(set) var transportKind: TransportKind?
    /// Связь по Wi-Fi напрямую: камера сама раздаёт сеть (режим Wi-Fi Direct).
    private(set) var wifiDirect = false
    /// Идёт сопряжение по Wi-Fi (кнопка «Сопрячь с камерой»).
    private(set) var isPairing = false
    /// Что сейчас с Wi-Fi: «ищу камеру», «камера найдена, нужно сопряжение» и т.п.
    private(set) var wifiStatus: String?
    /// Камера на кабеле видна на шине USB, а macOS её программе не отдаёт — ждёт «Разрешить» в окне доступа.
    private(set) var cableAccessHint = false
    @ObservationIgnored private var cableAccessWatch = CableAccessWatch()

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
                if let self { self.finishRecordingRequest(self.recordingToggle.tick(now: Date())) }
                self?.tickCableAccess()
                self?.adaptQuality()
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
                                        setting: property(.movieRecordingSetting)?.current.intValue,
                                        allowed: allowedRecordingSettings)
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

    /// Значения для ленты. У параметров записи — режимы обоих форматов (значение — `RecordingMode.code`);
    /// камера без свойства формата — её собственные параметры, как раньше.
    func stripOptions(_ id: SettingID) -> [SettingOption] {
        if id == .movieRecordingSetting, !recordingModes.isEmpty {
            return recordingModes.map { SettingOption(value: $0.code, label: $0.label) }
        }
        return property(id).map { SettingOptions.options(id, $0) } ?? []
    }

    /// Текущее значение в ленте — в той же кодировке, что `stripOptions`.
    func stripCurrent(_ id: SettingID) -> Int64? {
        guard id == .movieRecordingSetting, let mode = currentRecordingMode else { return property(id)?.current.intValue }
        return mode.code
    }

    private var currentRecordingMode: RecordingMode? {
        guard let format = property(.movieFileFormat)?.current.intValue,
              let setting = property(.movieRecordingSetting)?.current.intValue else { return nil }
        return RecordingMode(format: format, setting: setting)
    }

    /// Параметры записи, которые камера разрешает для текущего формата.
    private var allowedRecordingSettings: [Int64] {
        property(.movieRecordingSetting).map { SettingOptions.options(.movieRecordingSetting, $0).map(\.value) } ?? []
    }

    /// Выбор в ленте: обычная настройка — запись значения, режим записи — формат и параметры вместе.
    func choose(_ id: SettingID, _ value: Int64) {
        if id == .movieRecordingSetting, value > 0xFF {
            setRecordingMode(RecordingMode(code: value))
        } else if id == .movieFileFormat,
                  let mode = RecordingModes.closest(to: recordingDriver.target ?? currentRecordingMode, format: value, in: recordingModes) {
            // один формат без параметров камера оставила бы с параметрами прежнего — берём ближайший режим
            setRecordingMode(mode)
        } else {
            request(id, value)
        }
    }

    /// Формат и параметры записи. Камера отвечает «готово», но пропускает запись, пришедшую, пока она применяет прошлую,
    /// поэтому режим доводится по шагам: по одной записи, после каждой — смотрим, что на камере. Новый выбор, пока идёт
    /// прошлый, подхватывается на следующем шаге.
    func setRecordingMode(_ mode: RecordingMode) {
        guard isWritable(.movieRecordingSetting) || isWritable(.movieFileFormat) else { return }
        recordingDriver.choose(mode)
        pending.insert(.movieRecordingSetting)
        guard recordingModeTask == nil else { return }
        recordingModeTask = Task { [weak self] in await self?.driveRecordingMode() }
    }

    private func driveRecordingMode() async {
        defer {
            // отменили при отключении — teardown уже всё сбросил (и, может быть, начат новый сеанс)
            if !Task.isCancelled {
                recordingModeTask = nil
                recordingDriver.cancel()
                if targets[SonyProp.movieRecordingSetting] == nil { pending.remove(.movieRecordingSetting) }
            }
        }
        while !Task.isCancelled {
            // прошлая запись ещё ждёт подтверждения камеры
            if targets[SonyProp.movieFileFormat] != nil || targets[SonyProp.movieRecordingSetting] != nil {
                try? await Task.sleep(for: .milliseconds(100))
                continue
            }
            guard let step = recordingDriver.next(format: property(.movieFileFormat)?.current.intValue,
                                                   setting: property(.movieRecordingSetting)?.current.intValue,
                                                   allowed: allowedRecordingSettings) else { return }
            switch step {
            case .done:
                return
            case .wait:
                try? await Task.sleep(for: .milliseconds(100))
            case let .writeFormat(format):
                guard isWritable(.movieFileFormat) else {
                    try? await Task.sleep(for: .milliseconds(300))
                    continue
                }
                request(.movieFileFormat, format)
                await waitForWrite(SonyProp.movieFileFormat)
                // формат камера подтверждает раньше, чем готова принять следующую запись
                try? await Task.sleep(for: .milliseconds(500))
            case let .writeSetting(setting):
                guard isWritable(.movieRecordingSetting) else {
                    try? await Task.sleep(for: .milliseconds(300))
                    continue
                }
                request(.movieRecordingSetting, setting)
                await waitForWrite(SonyProp.movieRecordingSetting)
            }
            // запись параметров снимает «ждём» — пока режим не доведён, он ещё в пути
            if recordingDriver.target != nil { pending.insert(.movieRecordingSetting) }
        }
    }

    /// Ждёт, пока запись свойства закончится: камера подтвердила её или время подтверждения вышло.
    private func waitForWrite(_ code: UInt16) async {
        for _ in 0..<60 {
            guard targets[code] != nil else { return }
            try? await Task.sleep(for: .milliseconds(100))
        }
    }

    /// Ручная температура. Пишется, только когда камера в режиме «Цвет. темп.», — иначе сначала переключаем режим
    /// и ждём, пока камера подтвердит его.
    @discardableResult
    func setColorTemperature(_ kelvin: Int64) -> Bool {
        temperatureTask?.cancel()
        // проверяем до переключения режима: иначе режим сменится, а температура не запишется
        // свойства температуры может не быть, пока камера не в «Цвет. темп.», — тогда проверяем по обычному диапазону
        guard WhiteBalanceTemperature.accepts(kelvin, range: property(.colorTemperature)?.range) else { return false }
        let previous = property(.whiteBalance)?.current.intValue
        if previous == WhiteBalanceTemperature.manualMode {
            request(.colorTemperature, kelvin)
            return true
        }
        guard isWritable(.whiteBalance),
              property(.whiteBalance)?.settableValues.contains(WhiteBalanceTemperature.manualMode) ?? false else { return false }
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
            // температура так и не стала доступной — возвращаем прежний режим баланса белого
            if let previous, let self { self.request(.whiteBalance, previous) }
        }
        return true
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
        if id == .movieRecordingSetting, !recordingModes.isEmpty {
            // от режима, к которому камера ещё идёт: иначе быстрые шаги сливаются в один; дошла или сдалась —
            // от того, что на камере
            let current = currentRecordingMode
            let base = recordingDriver.target ?? current
            let next = RecordingModes.neighbour(of: base, in: recordingModes, offset: offset)
                ?? (base != current ? RecordingModes.neighbour(of: current, in: recordingModes, offset: offset) : nil)
            if let next { setRecordingMode(next) }
            return
        }
        guard let property = property(id),
              let target = SettingOptions.neighbour(id, property, offset: offset, from: targets[id.code]) else { return }
        request(id, target)
    }

    func toggleRecording() {
        guard phase == .ready, let session, recordingToggle.press(recording: isRecording, at: Date()) else { return }
        emit(.accepted(.record))
        Task { [weak self] in
            let recording = try? await session.toggleRecording()
            guard let self else { return }
            if let recording { self.isRecording = recording }
            self.finishRecordingRequest(self.recordingToggle.completed(recording: recording))
        }
    }

    /// Камера подтвердила запуск или остановку записи — или не подтвердила за 6 с.
    private func finishRecordingRequest(_ outcome: RecordingToggle.Outcome?) {
        switch outcome {
        case .started: emit(.recordingStarted)
        case .stopped: emit(.recordingStopped)
        case .failed(.starting):
            emit(.failed(.record))
            showStatus("Камера не начала запись")
        case .failed(.stopping):
            emit(.failed(.record))
            showStatus("Камера не остановила запись")
        case nil: break
        }
    }

    private func emit(_ kind: FeedbackEvent.Kind) {
        feedback.append(FeedbackEvent(kind: kind))
        if feedback.count > 8 { feedback.removeFirst(feedback.count - 8) }
    }

    // MARK: - Фото и видео

    var mode: CaptureMode {
        property(.exposureProgram)?.current.intValue.map(CaptureMode.init(exposureProgram:)) ?? .video
    }

    /// Переключает камеру между фото и видео, сохраняя букву режима (M ↔ видео M и т.п.).
    /// Фото, видео или S&Q — по режиму съёмки камеры.
    var shootingMode: ShootingMode {
        property(.exposureProgram)?.current.intValue.map(ShootingMode.init(exposureProgram:)) ?? .video
    }

    func switchMode(_ target: ShootingMode) {
        guard let p = property(.exposureProgram), let current = p.current.intValue,
              let value = ShootingMode.counterpart(of: current, to: target, settable: p.settableValues) else { return }
        request(.exposureProgram, value)
    }

    /// В режиме S&Q: частота съёмки (если её видно по выдержке) и частота ролика.
    var slowMotion: (capture: Int?, record: Int?)? {
        guard shootingMode == .slowQuick else { return nil }
        let setting = property(.movieRecordingSetting)?.current.intValue
        let pal = setting.map(RecordingModes.isPALSetting) ?? true
        let capture = property(.shutter).flatMap { SlowQuick.captureFrameRate(shutterValues: $0.settableValues, pal: pal) }
        return (capture, setting.flatMap(RecordingModes.frameRate))
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
        emit(.accepted(.shutter))
        let destination = Prefs.destination
        let folder = Prefs.folderURL
        let reviewOn = Prefs.reviewEnabled
        let source: Prefs.ReviewSource = destination.savesToMac ? Prefs.reviewSource : .camera
        // сколько файлов ждать — по формату снимка: RAW+JPEG — два
        let files = properties[SonyProp.fileFormat]?.current.intValue.map { $0 == 2 ? 2 : 1 }
        let progress = wifiTransport?.dataProgress
        let (phases, sink) = AsyncStream<CapturePhase>.makeStream()
        Task { [weak self] in
            for await phase in phases { self?.captureMoved(to: phase, review: reviewOn ? source : nil, progress: progress) }
        }
        Task { [weak self] in
            guard let self else { return }
            defer {
                sink.finish()
                self.isCapturing = false
                self.capturePhase = nil
            }
            do {
                if self.properties[SonyProp.stillImageStoreDestination]?.current.intValue != destination.cameraValue {
                    _ = try await session.set(SonyProp.stillImageStoreDestination, to: destination.cameraValue)
                }
                let objects = try await session.capturePhoto(download: destination.savesToMac, files: files) { sink.yield($0) }
                let cameraFrame = self.review?.image ?? self.frame
                guard destination.savesToMac else {
                    self.showStatus("Снимок сохранён на карту камеры")
                    return
                }
                guard !objects.isEmpty else { throw CaptureFailure.noFile }
                let urls = try await Self.save(objects, to: folder)
                let preview = await Self.previews(of: urls)
                let shot = Shot(urls: urls, thumbnail: preview.thumbnail ?? cameraFrame,
                                pixelSize: preview.size)
                self.shots.insert(shot, at: 0)
                self.emit(.shotAdded(shot.id))
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
                self.emit(.failed(.shutter))
                self.showStatus("Снимок не удался: \(error)")
            }
        }
    }

    /// Снимок перешёл на следующий этап. Спуск отработан — шторка и просмотр (кадр той секунды, когда камера снимала);
    /// качается файл — по Wi-Fi видно, сколько пришло.
    private func captureMoved(to phase: CapturePhase, review source: Prefs.ReviewSource?, progress: DataProgress?) {
        guard isCapturing else { return }
        capturePhase = phase
        switch phase {
        case .focusing:
            break
        case .exposing:
            emit(.shutterFired)
            if let source {
                // загруженный снимок появится после скачивания
                showReview(Review(image: frame, title: "Снимок", detail: source == .camera ? "" : "Загружаю…",
                                  urls: [], source: "Кадр с камеры", endsAt: nil), timed: source == .camera)
            }
        case .downloading:
            guard let progress, progressTask == nil else { return }
            progressTask = Task { [weak self] in
                while !Task.isCancelled {
                    try? await Task.sleep(for: .milliseconds(100))
                    guard let self, case let .downloading(file, files, _, expected) = self.capturePhase else { break }
                    // счётчик — того же файла: между файлами успевает пройти кадр live view
                    if let current = progress.current, current.expected == expected {
                        self.capturePhase = .downloading(file: file, files: files, received: current.received, expected: expected)
                    }
                }
                self?.progressTask = nil
            }
        }
    }

    /// Стоп-кадр из видео: сохраняет текущий кадр live view, запись не прерывается.
    func grabFrame() {
        guard phase == .ready || phase == .settling, let jpeg = lastJPEG else { return }
        let folder = Prefs.folderURL
        let thumbnail = frame
        emit(.accepted(.grab))
        Task { [weak self] in
            do {
                let name = FileNaming.frameGrab(at: Date())
                let urls = try await Self.save([CapturedObject(filename: name, data: jpeg)], to: folder)
                let shot = Shot(urls: urls, thumbnail: thumbnail,
                                pixelSize: thumbnail.map { CGSize(width: $0.width, height: $0.height) })
                self?.shots.insert(shot, at: 0)
                self?.emit(.frameGrabbed(shot.id))
                self?.showStatus("Кадр сохранён: \(urls[0].lastPathComponent)")
            } catch {
                self?.emit(.failed(.grab))
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

    /// Картинка для просмотра (до 2048 px), миниатюра для ленты и размер снимка. JPEG предпочтительнее RAW: быстрее.
    nonisolated private static func previews(of urls: [URL]) async -> (image: CGImage?, thumbnail: CGImage?, size: CGSize?) {
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
                let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any]
                let width = properties?[kCGImagePropertyPixelWidth] as? Int, height = properties?[kCGImagePropertyPixelHeight] as? Int
                let size = width.flatMap { w in height.map { CGSize(width: w, height: $0) } }
                if let image = thumb(2048) { return (image, thumb(160), size) }
            }
            return (nil, nil, nil)
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
        // кабель вынули, а связь идёт по Wi-Fi — её не трогаем
        guard transportKind != .wifi else { return }
        perform(action)
    }

    /// Wi-Fi закончился, а камера на кабеле ждёт — подключаем её.
    private func connectAttachedIfAny() {
        guard let id = tracker.current, tracker.attached.contains(id), let device = devices[id] else { return }
        connect(device)
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
        // по Wi-Fi уже идёт сеанс: не рвём его — камера в режиме Wi-Fi видна на кабеле как накопитель (например, на
        // зарядке) и PC Remote по USB всё равно не даст; кабельную камеру подключим, когда Wi-Fi закончится
        guard transportKind != .wifi else { return }
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
        // сеанс начался — сопряжение, если его не завершили, больше не нужно
        isPairing = false
        wifiStatus = nil
        qualityPolicy = LiveViewQualityPolicy(now: Date())
        qualityWriting = false
        recentFrames = []
        if sessionStartedAt == nil { sessionStartedAt = Date() }
        if sessionActivity == nil {
            sessionActivity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .latencyCritical, .idleDisplaySleepDisabled], reason: "Live view с камеры")
        }
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
                // сеанс готов — восстановление кончилось, даже если кадров пока нет (иначе карточка висела бы)
                self.finishReconnect()
                self.startQuality(session)
                onReady()
            } catch is CancellationError {
                return
            } catch SessionError.notPCRemote {
                guard let self, !Task.isCancelled else { return }
                // по Wi-Fi это обрыв: повтор с паузой, а не экран «не в PC Remote» поверх открытого соединения
                if kind == .wifi {
                    onFailure()
                    return
                }
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
        // идущая попытка ждёт подтверждения на камере до минуты — обрываем её
        closeConnectingTransport()
    }

    /// Забыть камеру: новый GUID, и сопряжение в камере к программе больше не относится — сама она подключаться
    /// не будет, пока не сопрячь заново.
    func forgetWiFiCamera() {
        Prefs.wifiHost = nil
        Prefs.wifiCameraName = nil
        identity = PTPIPIdentity.reset()
        wifiPolicy = WiFiPolicy()
        wifiStatus = nil
        closeConnectingTransport()
        if transportKind == .wifi { teardown() }
    }

    private func closeConnectingTransport() {
        guard let transport = connectingTransport else { return }
        Task { await transport.close() }
    }

    /// Нужна ли ещё связь по Wi-Fi: сеанса нет (камера на кабеле не в PC Remote — тоже), Wi-Fi включён или идёт сопряжение.
    private var wifiWanted: Bool {
        // камера на кабеле подключается или ждёт повтора — кабель важнее сети;
        // на кабеле, но не в PC Remote по USB (например, на зарядке в режиме Wi-Fi) — можно по сети
        let free = (phase == .searching && transportKind == nil && tracker.attached.isEmpty)
            || (phase == .notPCRemote && transportKind == .usb)
        return free && (Prefs.wifiEnabled || isPairing)
    }

    /// Чем кончилась попытка подключиться по Wi-Fi.
    private enum WiFiAttempt {
        case connected
        /// камера не знает программу (не сопряжена)
        case rejected
        /// не ответила или оборвала
        case failed
        /// пока ждали, связь стала не нужна или попытку отменили
        case dropped
    }

    /// Раз в секунду: если связи нет — ищем камеру в сети и подключаемся.
    private func wifiTick() async {
        guard wifiWanted, reconnectingSince == nil, !wifiBusy,
              wifiPolicy.mayAttempt(enabled: Prefs.wifiEnabled, pairing: isPairing, now: Date()) else { return }
        wifiBusy = true
        defer { wifiBusy = false }
        // тесты через прокси «плохой сети» (scripts/badnet.py): только этот адрес, без поиска камеры в сети
        if AutomationServer.isRequested, let forced = ProcessInfo.processInfo.environment["ZV_PTPIP_HOST"] {
            if wifiPolicy.maySendInit(paired: Prefs.wifiCameraName != nil, pairing: isPairing) {
                _ = await tryWiFi(forced, discovered: false)
            }
            return
        }
        // сначала — последний адрес камеры: так подключение после перерыва занимает секунду, а не поиск
        let last = isPairing || Prefs.wifiCameraName == nil ? nil : Prefs.wifiHost
        var lastFailed = false
        if let last {
            switch await tryWiFi(last, discovered: false) {
            case .connected, .dropped: return
            case .rejected: break
            case .failed: lastFailed = true
            }
        }
        guard wifiWanted else { return }
        if isPairing, wifiStatus == nil { wifiStatus = "Ищу камеру в сети…" }
        let found = await WiFiDiscovery.search().map(\.host)
        guard wifiWanted else { return }
        if found.isEmpty {
            wifiPolicy.nothingFound()
            wifiStatus = isPairing ? "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi" : nil
            return
        }
        // сопряжение могло только что оказаться недействительным (камера отказала) — смотрим заново
        let paired = Prefs.wifiCameraName != nil
        // не сопряжены — не стучимся (камера в режиме Pairing показала бы чужой запрос), только подсказываем
        guard wifiPolicy.maySendInit(paired: paired, pairing: isPairing) else {
            if wifiStatus == nil {
                wifiStatus = "В сети есть камера (\(found[0])) — нажмите «Сопрячь с камерой…», чтобы подключаться по Wi-Fi"
            }
            return
        }
        // камера видна по последнему адресу, но не ответила — это неудача к найденной камере
        if let last, lastFailed, found.contains(last) {
            wifiPolicy.attemptFailed(discovered: true)
            showStaleHintIfNeeded(last)
        }
        for host in found where host != last {
            guard wifiWanted else { return }
            switch await tryWiFi(host, discovered: true) {
            case .connected, .dropped: return
            case .rejected, .failed: continue
            }
        }
    }

    private func showStaleHintIfNeeded(_ host: String) {
        guard wifiPolicy.showsStaleHint, !isPairing else { return }
        wifiStatus = "Камера найдена (\(host)), но пока не отвечает — видимо, держит прошлое подключение. "
            + "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
    }

    /// Нужна ли попытка по Wi-Fi: обычный поиск или тихое восстановление оборвавшегося сеанса.
    private var wifiAttemptWanted: Bool { wifiWanted || reconnectingSince != nil }

    /// Одна попытка подключиться к камере по адресу. discovered — адрес найден поиском сейчас, а не взят из памяти;
    /// quick — восстановление сеанса: камера только что была на связи, долго ждать ответа незачем.
    private func tryWiFi(_ host: String, discovered: Bool, quick: Bool = false) async -> WiFiAttempt {
        let transport = PTPIPTransport(host: host, identity: identity, reachable: Self.testLinkCheck)
        let pairing = isPairing
        connectingTransport = transport
        defer { if connectingTransport === transport { connectingTransport = nil } }
        if pairing { wifiStatus = "Камера найдена (\(host)). Включите на камере PC Remote Function → Pairing и подтвердите сопряжение" }
        let started = Date()
        note("подключаюсь к \(host)\(quick ? " (восстановление)" : "")…")
        do {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            let name = try await transport.connect(connectTimeout: quick ? .seconds(2) : .seconds(5),
                                                   initTimeout: pairing ? .seconds(60) : quick ? .seconds(3) : .seconds(5))
            // камера приняла программу — значит, сопряжены, даже если связь уже не нужна
            Prefs.wifiHost = host
            Prefs.wifiCameraName = name
            note(String(format: "подключился к \(host) (\(name)) за %.0f мс", Date().timeIntervalSince(started) * 1000))
            // пока ждали, связь стала не нужна: подключили кабель, выключили Wi-Fi, отменили сопряжение
            guard wifiAttemptWanted, pairing == isPairing else {
                await transport.close()
                return .dropped
            }
            // камера на кабеле не в PC Remote — та попытка не нужна, закрываем её до сеанса по Wi-Fi
            if phase == .notPCRemote { teardown() }
            wifiDirect = PTPIP.isCameraAccessPoint(host)
            wifiPolicy.connected()
            connectionMessage = nil
            wifiTransport = transport
            runSession(transport: transport, kind: .wifi, close: { await transport.close() }, prepare: {},
                       onReady: { [weak self] in self?.wifiPolicy.sessionReady() },
                       onFailure: { [weak self] in
                           Task { [weak self] in
                               let loss = await transport.loss
                               self?.wifiLost(loss, transport: transport, host: host)
                           }
                       })
            tasks.append(Task { [weak self] in
                for await status in transport.linkStatus { self?.linkChanged(status) }
            })
            return .connected
        } catch PTPIPError.rejected(let reason) where !pairing && !WiFiPolicy.forgetsPairing(initFailReason: reason) {
            // камера занята (например, ещё держит прошлое подключение) — сопряжение не трогаем, повторим
            await transport.close()
            guard wifiAttemptWanted else { return .dropped }
            note("\(host): камера отказала (Init Fail \(reason)) — занята, повторю")
            if reconnectingSince == nil {
                wifiPolicy.attemptFailed(discovered: discovered)
                showStaleHintIfNeeded(host)
            }
            return .failed
        } catch PTPIPError.rejected(let reason) {
            await transport.close()
            note("\(host): камера отказала (Init Fail \(reason))\(pairing ? "" : " — программа с ней не сопряжена")")
            guard wifiAttemptWanted else { return .dropped }
            if pairing {
                wifiStatus = "Камера найдена (\(host)). Включите на камере PC Remote Function → Pairing"
            } else {
                // камера нас больше не знает (её сбросили или сопрягали заново) — перестаём стучаться
                Prefs.wifiCameraName = nil
                wifiStatus = "В сети есть камера (\(host)), но программа с ней не сопряжена — нажмите «Сопрячь с камерой»"
            }
            return .rejected
        } catch {
            await transport.close()
            note("\(host): не подключилось — \(Self.describe(error))")
            // отменили или стало не нужно — это не неудача
            guard wifiAttemptWanted, pairing == isPairing else { return .dropped }
            if reconnectingSince == nil {
                wifiPolicy.attemptFailed(discovered: discovered)
                showStaleHintIfNeeded(host)
            }
            return .failed
        }
    }

    /// Сеанс по Wi-Fi оборвался. Картинка уже шла и камера, скорее всего, рядом — восстанавливаем тихо: кадр и
    /// настройки остаются на экране, попытки — сразу и по тому же адресу. Иначе — обычное ожидание с поиском.
    private func wifiLost(_ loss: LinkLoss?, transport: PTPIPTransport, host: String) {
        // об одном обрыве сообщают и рукопожатие, и поток свойств; сеанс уже могли восстановить — старый транспорт не трогает новый
        guard transportKind == .wifi, wifiTransport === transport else { return }
        note("сеанс по Wi-Fi оборвался: \(loss.map(Self.lossTitle) ?? "сеанс закончился")")
        lastLoss = (loss, Date())
        let quiet = (frame != nil || reconnectingSince != nil) && (loss?.reconnectsAtOnce ?? true) && Prefs.wifiEnabled
        // попытки подключаются, а сеанс снова и снова падает до первого кадра — тоже не дольше минуты
        let expired = reconnectingSince.map { Date().timeIntervalSince($0) >= WiFiPolicy.reconnectWindow } ?? false
        guard quiet, !expired else {
            giveUpReconnect()
            return
        }
        if reconnectingSince == nil {
            reconnectingSince = Date()
            reconnectAttempt = 0
        }
        suspendSession()
        scheduleReconnect(host)
    }

    private func scheduleReconnect(_ host: String) {
        let delay = WiFiPolicy.reconnectDelay(attempt: reconnectAttempt)
        reconnectAttempt += 1
        reconnectTask?.cancel()
        reconnectTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(delay))
            guard let self, !Task.isCancelled, self.reconnectingSince != nil else { return }
            // пока ждали, Wi-Fi выключили в настройках или вышла минута — восстанавливать незачем
            let expired = self.reconnectingSince.map { Date().timeIntervalSince($0) >= WiFiPolicy.reconnectWindow } ?? true
            guard Prefs.wifiEnabled, !expired else {
                self.giveUpReconnect()
                return
            }
            switch await self.tryWiFi(host, discovered: false, quick: true) {
            case .connected:
                return
            case .rejected:
                self.giveUpReconnect()
            case .failed, .dropped:
                guard self.reconnectingSince != nil else { return }
                if let since = self.reconnectingSince, Date().timeIntervalSince(since) < WiFiPolicy.reconnectWindow {
                    self.scheduleReconnect(host)
                } else {
                    self.giveUpReconnect()
                }
            }
        }
    }

    /// Сеанс после обрыва снова работает: первый кадр или готовность — что раньше.
    private func finishReconnect() {
        guard let since = reconnectingSince else { return }
        let seconds = Date().timeIntervalSince(since)
        reconnects += 1
        lastRecoverySeconds = seconds
        reconnectingSince = nil
        note(String(format: "сеанс восстановлен за %.1f с", seconds))
    }

    /// Тихо восстановить сеанс не вышло (или и не стоило): обычное ожидание — экран «Подключите камеру»,
    /// поиск в сети с растущей паузой, а если камера ждёт на кабеле — она.
    private func giveUpReconnect() {
        if reconnectingSince != nil { note("восстановить сеанс не вышло — жду камеру как обычно") }
        teardown()
        let seconds = wifiPolicy.sessionLost(now: Date())
        connectionMessage = "Связь по Wi-Fi пропала. Повтор через \(seconds) с…"
        connectAttachedIfAny()
    }

    /// Сеанс оборвался, но камеру ждём обратно: закрыть всё, что связано с соединением, а картинку, настройки,
    /// таймер записи и ленту снимков оставить на экране.
    private func suspendSession() {
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        // старое соединение — сбросом (RST): камера, застрявшая на передаче, освобождается сразу и примет новое
        if let old = wifiTransport {
            Task { await old.closeAbortively() }
        } else {
            let close = closeTransport
            Task { await close?() }
        }
        closeTransport = nil
        wifiTransport = nil
        recordingModeTask?.cancel()
        recordingModeTask = nil
        recordingDriver.cancel()
        session = nil
        coalescer = nil
        targets = [:]
        pending = []
        isApplyingPreset = false
        isCapturing = false
        linkOutageSince = nil
        phase = .connecting
    }

    /// Тесты через прокси «плохой сети» (`ZV_LINK_CHECK_URL`, только с API): ping камеры попал бы в сам прокси и
    /// отвечал бы всегда, даже когда прокси «роняет» Wi-Fi, — о сети спрашиваем датчик прокси (`GET /link`, 200 — есть).
    private static let testLinkCheck: PTPIPTransport.Reachability? = {
        guard AutomationServer.isRequested,
              let url = ProcessInfo.processInfo.environment["ZV_LINK_CHECK_URL"].flatMap(URL.init(string:)),
              let host = url.host, let port = url.port.flatMap({ NWEndpoint.Port(rawValue: UInt16($0)) }) else { return nil }
        let path = url.path.isEmpty ? "/" : url.path
        return { await LinkSensor.isUp(host: host, port: port, path: path) }
    }()

    /// Транспорт сообщил, что со связью: провал и время ответа камеры.
    private func linkChanged(_ status: LinkStatus) {
        if linkOutageSince != status.outageSince {
            if let since = status.outageSince {
                outages += 1
                note(String(format: "провал связи: ни байта %.1f с — сеанс держу, пингую камеру", -since.timeIntervalSinceNow))
            } else if let since = linkOutageSince {
                longestOutage = max(longestOutage, -since.timeIntervalSinceNow)
                note(String(format: "связь вернулась: провал %.1f с", -since.timeIntervalSinceNow))
            }
        }
        linkOutageSince = status.outageSince
        if let rtt = status.rtt {
            linkRTT = rtt
            qualityPolicy.noteRTT(rtt, at: Date())
            rttHistory.append((Date(), rtt))
            rttHistory.removeAll { -$0.at.timeIntervalSinceNow > 60 }
        }
        tickFrameStats()
    }

    // MARK: - Качество превью

    /// Сеанс по Wi-Fi готов: качество — высокое; дальше его ведёт политика по каналу.
    private func startQuality(_ session: CameraSession) {
        guard transportKind == .wifi,
              properties[SonyProp.liveViewImageQuality]?.current.intValue == LiveViewQuality.low.rawValue else { return }
        writeQuality(.high, session)
    }

    /// Раз в полсекунды: снизить качество превью, если канал его не тянет, или вернуть, когда тянет снова.
    private func adaptQuality() {
        guard transportKind == .wifi, phase == .ready, reconnectingSince == nil, let session, !qualityWriting,
              let current = previewQuality else { return }
        let wanted: LiveViewQuality?
        if Prefs.wifiAdaptiveQuality {
            wanted = qualityPolicy.decide(now: Date(), outage: linkOutageSince != nil)
        } else if current == .low, forcedHighAt.map({ Date().timeIntervalSince($0) > 5 }) ?? true {
            forcedHighAt = Date()
            wanted = .high
        } else {
            wanted = nil
        }
        guard let wanted, wanted != current else { return }
        let predicted = qualityPolicy.predictedHighFPS(now: Date(), window: 5)
        note("качество превью: \(current == .high ? "высокое" : "низкое") → \(wanted == .high ? "высокое" : "низкое")"
            + (predicted.map { String(format: " (прогноз на высоком %.1f к/с", $0) } ?? " (")
            + (throughput.map { String(format: ", поток %.0f КБ/с)", $0 / 1024) } ?? ")"))
        qualitySwitches += 1
        writeQuality(wanted, session)
    }

    private func writeQuality(_ quality: LiveViewQuality, _ session: CameraSession) {
        qualityWriting = true
        Task { [weak self] in
            // без ожидания подтверждения: его видно по ширине кадров, а лишние перечитывания свойств отнимают канал
            try? await session.setQuietly(SonyProp.liveViewImageQuality, to: quality.rawValue, type: PTPType.uint8)
            self?.qualityWriting = false
        }
    }

    /// Пропускная способность по кадрам за последние 5 с, байт/с.
    var throughput: Double? {
        let now = Date()
        let recent = recentFrames.filter { now.timeIntervalSince($0.at) <= 5 && $0.seconds <= 1.5 }
        let busy = recent.reduce(0.0) { $0 + $1.seconds }
        guard busy > 0 else { return nil }
        return Double(recent.reduce(0) { $0 + $1.bytes }) / busy
    }

    var linkRTTMs: Int? { linkRTT.map { Int(($0 * 1000).rounded()) } }

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
        let (frames, sink) = AsyncStream.makeStream(of: LiveViewSample.self, bufferingPolicy: .bufferingNewest(1))
        tasks.append(Task.detached(priority: .userInitiated) {
            defer { sink.finish() }
            var failures = 0
            while !Task.isCancelled {
                do {
                    sink.yield(try await session.nextLiveViewSample())
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
            for await sample in frames {
                guard let image = Self.decode(sample.jpeg) else { continue }
                let levels = Self.histogramLevels(of: image)
                guard !Task.isCancelled else { break }
                await self?.show(image, sample: sample, levels: levels)
            }
        })
    }

    private func apply(_ snapshot: [UInt16: SonyProperty]) {
        properties = snapshot
        let recording = snapshot[SonyProp.movieRecordingState]?.current.intValue == 1
        if recording, recordingStartedAt == nil { recordingStartedAt = Date() }
        if !recording { recordingStartedAt = nil }
        isRecording = recording
        finishRecordingRequest(recordingToggle.observe(recording: recording))
        for (code, target) in targets where snapshot[code]?.current.intValue == target {
            clearTarget(code)
        }
    }

    private func show(_ image: CGImage, sample: LiveViewSample, levels: [Int]) {
        frame = image
        lastJPEG = sample.jpeg
        let now = Date()
        // первый кадр нового сеанса после обрыва — сеанс восстановлен
        if session != nil { finishReconnect() }
        recentFrames.append((now, sample.bytes, sample.seconds, sample.waits))
        framesTotal += 1
        recentFrames.removeAll { now.timeIntervalSince($0.at) > 5 }
        if transportKind == .wifi, let level = LiveViewQuality(frameWidth: image.width) {
            if previewQuality != level { previewQuality = level }
            if let short = sample.shortReplySeconds { qualityPolicy.noteRTT(short, at: now) }
            qualityPolicy.record(bytes: sample.bytes, seconds: sample.seconds, level: level, at: now)
        }
        // в видео белое в live view — 234, в фото — 255: шкала гистограммы — до белого
        let white = LumaStats.whiteLevel(levels: levels, video: mode != .photo)
        histogram = levels.isEmpty ? [] : LumaHistogram.bins(levels: levels, whiteLevel: white)
        histogramStats = LumaStats(levels: levels, whiteLevel: white)
        if let lastFrameAt { frameGaps.append((now, now.timeIntervalSince(lastFrameAt))) }
        lastFrameAt = now
        frameTimes.append(now)
        tickFrameStats()
    }

    /// Пока ищем камеру: она на шине USB, а программе не видна — подсказка про доступ к съёмным томам.
    private func tickCableAccess() {
        let searching = phase == .searching && transportKind == nil
        let shown = cableAccessWatch.update(onBus: searching && USBBus.hasSonyCamera(),
                                            visible: !tracker.attached.isEmpty, now: Date())
        if cableAccessHint != shown { cableAccessHint = shown }
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
        var noiseDBm: Int?
        var rateMbps: Double?
        var channel: String?
    }

    /// Для подсказки о подключении и панели отладки: адреса камеры и Mac, сигнал Wi-Fi Mac (без разрешения на
    /// геолокацию: имя сети macOS отдаёт только с ним, а уровень, шум, скорость и канал — так).
    func connectionDetails() async -> ConnectionDetails {
        let interface = CWWiFiClient.shared().interface()
        let rssi = interface?.rssiValue() ?? 0
        let noise = interface?.noiseMeasurement() ?? 0
        let rate = interface?.transmitRate() ?? 0
        let channel = interface?.wlanChannel().map { channel -> String in
            let band: String
            switch channel.channelBand {
            case .band2GHz: band = "2,4 ГГц"
            case .band5GHz: band = "5 ГГц"
            case .band6GHz: band = "6 ГГц"
            default: band = ""
            }
            return band.isEmpty ? "\(channel.channelNumber)" : "\(channel.channelNumber) (\(band))"
        }
        var details = ConnectionDetails(signalDBm: rssi == 0 ? nil : rssi, noiseDBm: noise == 0 ? nil : noise,
                                        rateMbps: rate > 0 ? rate : nil, channel: channel)
        if let wifi = wifiTransport {
            details.cameraHost = wifi.host
            details.localHost = await wifi.localHost()
        }
        return details
    }

    /// fps за последние 2 с и признак «кадры не приходят» (больше 1,5 с).
    private func tickFrameStats() {
        let now = Date()
        frameTimes.removeAll { now.timeIntervalSince($0) > 2 }
        fps = Double(frameTimes.count) / 2
        let live = phase == .settling || phase == .ready
        let waited = lastFrameAt.map { now.timeIntervalSince($0) } ?? 0
        let indicator: LinkIndicator
        if let since = reconnectingSince {
            indicator = .reconnecting(since: since)
        } else if !live || frame == nil {
            indicator = .none
        } else if let since = linkOutageSince {
            indicator = .interrupted(since: since)
        } else if waited >= 4, let last = lastFrameAt {
            indicator = .interrupted(since: last)
        } else if waited >= 1.2 {
            indicator = .weak
        } else {
            indicator = .none
        }
        if linkIndicator != indicator { linkIndicator = indicator }
        isFrameStale = indicator != .none
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
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectingSince = nil
        let close = closeTransport
        // качество превью снижали из-за слабого сигнала — камеру оставляем на высоком
        let restore = transportKind == .wifi && previewQuality == .low ? session : nil
        Task {
            if let restore {
                try? await restore.setQuietly(SonyProp.liveViewImageQuality, to: LiveViewQuality.high.rawValue, type: PTPType.uint8)
            }
            await close?()
        }
        if let activity = sessionActivity {
            ProcessInfo.processInfo.endActivity(activity)
            sessionActivity = nil
        }
        previewQuality = nil
        linkOutageSince = nil
        linkIndicator = .none
        recentFrames = []
        sessionStartedAt = nil
        outages = 0
        longestOutage = 0
        qualitySwitches = 0
        framesTotal = 0
        rttHistory = []
        device = nil
        closeTransport = nil
        transportKind = nil
        wifiDirect = false
        wifiTransport = nil
        recordingModeTask?.cancel()
        recordingModeTask = nil
        recordingDriver.cancel()
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
        histogramStats = nil
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
        capturePhase = nil
        progressTask?.cancel()
        progressTask = nil
        recordingToggle = RecordingToggle()
        reviewTask?.cancel()
        review = nil
        phase = .searching
    }

    /// Событие — в журнал панели отладки и в системный журнал (`log show --predicate 'subsystem == "local.zve10viewer"'`).
    private func note(_ text: String) {
        debugEvents.append(DebugEvent(at: Date(), text: text))
        if debugEvents.count > 200 { debugEvents.removeFirst(debugEvents.count - 200) }
        log.info("\(text, privacy: .public)")
    }

    static func lossTitle(_ loss: LinkLoss) -> String {
        switch loss {
        case .broken: return "соединение оборвалось"
        case .resetAfterOutage: return "связь вернулась, а TCP молчал — сброшено"
        case .cameraSilent: return "камера не отвечала на команды"
        case .noLink: return "связи не было 3 минуты"
        }
    }

    private static func describe(_ error: Error) -> String {
        switch error {
        case TransportError.timeout: return "нет ответа"
        case TransportError.disconnected: return "соединение закрыто"
        case let TransportError.failed(text): return text
        default: return "\(error)"
        }
    }

    // MARK: - Числа для панели отладки

    /// Отклик камеры на пробы за минуту: последний, минимум и среднее, мс.
    var rttStats: (last: Int, min: Int, average: Int)? {
        let recent = rttHistory.filter { -$0.at.timeIntervalSinceNow <= 60 }.map(\.seconds)
        guard let last = recent.last, let low = recent.min() else { return nil }
        let ms = { (s: Double) in Int((s * 1000).rounded()) }
        return (ms(last), ms(low), ms(recent.reduce(0, +) / Double(recent.count)))
    }

    /// Паузы между кадрами за 10 с, мс: p50, p95 и самая долгая.
    var frameGapStats: (p50: Int, p95: Int, max: Int)? {
        let gaps = frameGaps.map { $0.gap * 1000 }
        guard let p50 = Percentile.of(gaps, 0.5), let p95 = Percentile.of(gaps, 0.95), let top = gaps.max() else { return nil }
        return (Int(p50.rounded()), Int(p95.rounded()), Int(top.rounded()))
    }

    /// Время запроса кадра за 5 с (от отправки до ответа), мс, и сколько раз перед кадром он был не готов.
    var fetchStats: (p50: Int, p95: Int, waitsPerFrame: Double)? {
        let now = Date()
        let recent = recentFrames.filter { now.timeIntervalSince($0.at) <= 5 }
        let ms = recent.map { $0.seconds * 1000 }
        guard let p50 = Percentile.of(ms, 0.5), let p95 = Percentile.of(ms, 0.95) else { return nil }
        return (Int(p50.rounded()), Int(p95.rounded()), Double(recent.reduce(0) { $0 + $1.waits }) / Double(recent.count))
    }

    /// Прогноз частоты превью на высоком качестве и через сколько попытка вернуть высокое.
    var qualityForecast: (highFPS: Double?, upInSeconds: TimeInterval?) {
        let now = Date()
        return (qualityPolicy.predictedHighFPS(now: now, window: 5), qualityPolicy.secondsUntilUpAttempt(now: now))
    }

    /// Последний кадр с камеры, байт.
    var lastFrameBytes: Int? { recentFrames.last?.bytes }

    nonisolated private static func decode(_ jpeg: Data) -> CGImage? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
    }

    /// Уровни яркости по уменьшенной копии кадра. 256×144 без сглаживания: при усреднении мелкие пересвеченные
    /// места растворились бы в соседях и пересвет было бы не видно.
    nonisolated private static func histogramLevels(of image: CGImage) -> [Int] {
        let width = 256, height = 144, bytesPerRow = width * 4
        var pixels = [UInt8](repeating: 0, count: bytesPerRow * height)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        return drawn ? LumaHistogram.levels(rgba: pixels, width: width, height: height, bytesPerRow: bytesPerRow) : []
    }
}

/// Датчик связи тестового прокси: GET по TCP без URLSession (обычный HTTP к IP-адресу ATS бы не пустил).
private enum LinkSensor {
    static func isUp(host: String, port: NWEndpoint.Port, path: String) async -> Bool {
        let connection = NWConnection(host: NWEndpoint.Host(host), port: port, using: .tcp)
        return await withCheckedContinuation { (done: CheckedContinuation<Bool, Never>) in
            let once = Once()
            func finish(_ up: Bool) {
                guard once.claim() else { return }
                connection.cancel()
                done.resume(returning: up)
            }
            connection.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    connection.send(content: Data("GET \(path) HTTP/1.0\r\n\r\n".utf8), completion: .contentProcessed { _ in })
                    connection.receive(minimumIncompleteLength: 12, maximumLength: 64) { data, _, _, _ in
                        finish(data.map { String(decoding: $0, as: UTF8.self).hasPrefix("HTTP/1.1 200") } ?? false)
                    }
                case .failed, .cancelled:
                    finish(false)
                default:
                    break
                }
            }
            connection.start(queue: .global(qos: .utility))
            DispatchQueue.global().asyncAfter(deadline: .now() + 0.8) { finish(false) }
        }
    }

    private final class Once: @unchecked Sendable {
        private let lock = NSLock()
        private var used = false
        func claim() -> Bool {
            lock.lock()
            defer { lock.unlock() }
            if used { return false }
            used = true
            return true
        }
    }
}
