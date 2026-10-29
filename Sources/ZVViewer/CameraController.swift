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
    /// Зебра, false color и пикинг по тому же кадру — растр поверх видимой его части; nil — не включены.
    private(set) var assistOverlay: AssistOverlay?
    /// Что считать для помощи в кадре: инструменты и видимая часть кадра. Читается из потока кадров.
    @ObservationIgnored let assistInput = AssistInput()
    /// Волновая форма последнего кадра; nil — выключена.
    private(set) var waveform: WaveformTrace?
    /// Вектороскоп последнего кадра; nil — выключен.
    private(set) var vectorscope: VectorscopeTrace?
    /// Что показала пипетка по последнему кадру; nil — выключена.
    private(set) var probeReading: ColorProbe.Reading?
    /// Белый уровень и средняя яркость (0…255) кадра, по которому считается помощь, — для API и отладки; кадра нет — nil.
    @ObservationIgnored private(set) var whiteLevel: Int?
    @ObservationIgnored private(set) var meanLuma: Double?
    /// Размах яркости p99 − p1 по уровням того же кадра (см. `LumaStats.spread`) — для API: в S-Log3 средняя яркость с LUT почти
    /// не меняется, а размах растёт.
    @ObservationIgnored private(set) var lumaSpread: Int?
    /// Зеркало, переворот и LUT картинки: один объект на программу, считает в потоке кадров.
    @ObservationIgnored private let pictureTransform = PictureTransform()
    /// Зеркало и переворот картинки на экране — как выбрано в «Мониторе» (экран кладёт выбор в `assistInput` сразу, при
    /// изменении). По нему экранные доли кадра (касание для фокуса) становятся долями кадра камеры.
    var orientation: FrameOrientation { assistInput.current.assist.orientation }
    /// Встроенный LUT, который сейчас на картинке: выбранный в «Мониторе» рукой, а у «Авто» — по профилю изображения главной
    /// камеры (следует за ним с ближайшего кадра); nil — встроенного нет («Выкл», «Свой», «Авто» без профиля).
    var resolvedLUT: BuiltinLUT? { assistInput.current.builtinLUT }
    /// Какой LUT выберет «Авто» по профилю изображения главной камеры — для подписи на плитке «Авто: S-Log3». Считается по
    /// `properties`, за которыми следит SwiftUI: `resolvedLUT` лежит в замке потока кадров, и вид, который его прочитал,
    /// не узнал бы о смене профиля.
    var autoLUT: BuiltinLUT? {
        LUTChoice.resolve(.auto, pictureProfile: properties[SonyProp.pictureProfile]?.current.intValue,
                          ppGamma: properties[SonyProp.ppGamma]?.current.intValue,
                          colorMode: properties[SonyProp.ppColorMode]?.current.intValue)
    }
    /// Эталонный кадр для наложения: текущий кадр или снимок из галереи. Хранится в файле — переживает перезапуск.
    private(set) var reference: ReferenceFrame?
    /// Команды 0x9207, которые знает камера на связи: по ним видно, есть ли фокус касанием, зум, Near/Far.
    private(set) var controlCodes: Set<UInt16> = []
    /// Рамки фокуса последнего кадра: где камера фокусирует.
    private(set) var focusFrames: [FocusFrame] = []
    /// Куда коснулись для фокуса и когда — для отметки на кадре. Точка — в долях кадра камеры, а не экрана (как рамки фокуса):
    /// на экран её переводит `FrameOrientation.fromCamera`.
    private(set) var touchMark: (point: CGPoint, at: Date)?
    /// Зум едет: 1 — к теле, −1 — к широкому; 0 — стоит.
    private(set) var zoomDirection = 0
    /// Единиц Near/Far, которые ещё надо отправить: шкалу тянут быстрее, чем камера отвечает.
    @ObservationIgnored private var focusUnits = 0
    @ObservationIgnored private var focusDriving = false
    /// Растёт, когда сеанс уходит: цикл шагов фокуса прошлого сеанса останавливается и новому не мешает.
    @ObservationIgnored private var focusGeneration = 0
    /// Последняя команда объектива: следующая ждёт её — «стоп» зума не обгонит «пуск».
    @ObservationIgnored private var lensTail: Task<Void, Never>?
    /// Что камера сообщает о себе сверх свойств: подписи списков, версия кода, данные объектива (0x9215, 0x9216, 0x9223) —
    /// только новые камеры Sony. Экраны видят это через `names(for:)`, `focusDistance` и другие в CameraController+OtherCameras.
    let info = CameraInfo()
    /// Что у камеры на связи стоит на месте «Творческого стиля»: он сам или Creative Look (новые камеры Sony). Меняется редко —
    /// следить за ним удобнее, чем за всеми свойствами: выбор в Fn переезжает вместе с камерой (`ScreenState.followSlots`).
    private(set) var creativeSlot: SettingID = .creativeStyle
    /// Метки фокуса A и B всех камер: у каждой свои, до выхода из программы (в настройки не пишутся).
    private(set) var focusMarkBook = FocusMarkBook()
    /// К какой метке («A» или «B») сейчас едет фокус; nil — не едет.
    private(set) var focusPullMark: Character?
    /// Доля пути к ней, 0…1; nil — фокус не едет.
    private(set) var focusPullProgress: Double?
    /// Чем кончился последний перевод — подпись у шкалы на 3 с («Фокус у метки A»); nil — подписи нет.
    private(set) var focusPullCaption: String?
    /// Сколько переводов кончилось у каждой метки: когда число растёт, кнопка метки мигает.
    private(set) var focusPullEnds: [Character: Int] = [:]
    /// Идущий перевод и таймер подписи. Перевод идёт на главном акторе одной задачей, так что `FocusPull` никто не трогает
    /// из двух мест.
    @ObservationIgnored private var focusPullTask: Task<Void, Never>?
    @ObservationIgnored private var focusPullCaptionTask: Task<Void, Never>?
    /// Когда пришли последние свойства камеры: у абсолютного фокуса, пока они свежие, положение перечитывать не нужно.
    @ObservationIgnored private var lastSnapshotAt = Date.distantPast
    private(set) var fps: Double = 0
    /// fps для строки состояния: обновляется дважды в секунду, а не с каждым кадром.
    private(set) var shownFPS = 0
    /// Самая долгая пауза между кадрами за последние 10 с, мс — насколько ровно идёт картинка.
    private(set) var frameGapMs = 0
    /// Настройки, для которых запрошено новое значение, но камера его ещё не подтвердила.
    private(set) var pending: Set<SettingID> = []
    private(set) var isRecording = false
    /// Все пресеты — и фото, и видео; на экране — только `presets`.
    private var library: [Preset] = []
    /// Пресеты режима камеры. Номер в этом списке — номер на экране и в окне, клавиша 1–9 и номер в API.
    var presets: [Preset] { library.filter { $0.mode == presetMode } }
    /// Чьи пресеты показаны: режим камеры (S&Q — видео); без связи — тот, в котором она была.
    private(set) var presetMode: CaptureMode = .photo
    /// Опора текущего пресета — в каждом режиме своя: последний применённый, созданный или сохранённый с камеры.
    /// Номер в списке режима; живёт, пока открыта программа.
    private var presetBase: [CaptureMode: Int] = [:]
    /// Растёт, когда пресеты в списке сдвигаются: новый, удалили, переставили, копия. Под тем же номером тогда может
    /// оказаться другой пресет — окно по нему пересоздаёт поле имени.
    private(set) var presetListVersion = 0
    private(set) var isApplyingPreset = false
    /// Быстрый просмотр: пока держат «Посмотреть», этот пресет стоит на камере; отпустили — камера возвращается как была.
    private(set) var previewingPreset: Int?
    /// Кнопку отпустили — камера возвращается как была.
    private(set) var previewReturning = false
    /// Короткое сообщение: пресет применён, снимок сохранён и т.п.
    private(set) var statusMessage: String?
    /// Отказ записи — подпись слева от кнопки записи, на месте «Запуск записи…»: на кнопку в этот миг и смотрят.
    private(set) var recordingNotice: String?
    /// Когда началась запись (для таймера «● REC»).
    private(set) var recordingStartedAt: Date?
    private(set) var isCapturing = false
    /// Что показывается поверх видео после снимка или при открытии снимка из ленты.
    private(set) var review: Review?
    /// Снимки и стоп-кадры этой сессии, новые сначала.
    private(set) var shots: [Shot] = []
    /// Удалённый снимок, который ещё можно вернуть; nil — плашки «Вернуть» нет.
    private(set) var deletedShot: DeletedShot?
    @ObservationIgnored private var deletedShotTask: Task<Void, Never>?
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

    /// Только что удалённый снимок: файлы в Корзине; пока видна «Вернуть», их можно вернуть на место.
    struct DeletedShot {
        let shot: Shot
        /// Откуда и куда в Корзину ушёл каждый файл.
        let moves: [(from: URL, to: URL)]
    }

    struct Review {
        var image: CGImage?
        var title: String
        var detail: String
        var urls: [URL]
        var source: String
        /// Когда закроется сам; nil — пока не закроют.
        var endsAt: Date?
        /// Сколько всего показывается — для кольца обратного отсчёта.
        var duration: TimeInterval?
        /// Какой съёмке принадлежит: скачанный снимок подменяет кадр только в своём просмотре.
        var capture: UUID?
    }

    @ObservationIgnored private var reviewTask: Task<Void, Never>?
    /// Во сколько раз увеличена картинка на экране — от этого зависит, когда снижать качество превью.
    @ObservationIgnored var viewZoomFactor = 1.0
    /// Вызов кнопки записи ещё не вернулся.
    @ObservationIgnored private var recordCallInFlight = false
    /// Код, которым камера отклонила последнее нажатие записи, если запись так и не сменилась, — для сообщения.
    @ObservationIgnored private var recordingRefusal: UInt16?
    /// Есть ли кадр: меняется, только когда кадры появились или пропали (меню «Вид» не перестраивается 25 раз в секунду).
    private(set) var hasFrame = false
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
    @ObservationIgnored private var recordingNoticeTask: Task<Void, Never>?
    @ObservationIgnored private let presetsKey = "presets"
    @ObservationIgnored private let presetModeKey = "presetMode"
    @ObservationIgnored private var previewApply: Task<Void, Never>?
    @ObservationIgnored private var previewStop: PresetStop?
    @ObservationIgnored private var previewRestore: [SettingID: Int64] = [:]
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
    /// Когда в последний раз просили камеру выбранное вручную качество: кадры меняются не сразу.
    @ObservationIgnored private var forcedQualityAt: Date?
    /// Качество превью по Wi-Fi: авто или вручную — выбор в подсказке к счётчику кадров и в настройках.
    private(set) var previewQualityMode = Prefs.previewQuality
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
    /// Идёт сопряжение по Wi-Fi («Сопрячь новую камеру…» или «Сопрячь» у камеры в списке).
    private(set) var isPairing = false
    /// Что сейчас с Wi-Fi: «ищу камеру», «камера найдена, нужно сопряжение» и т.п.
    private(set) var wifiStatus: String?
    /// Камера на кабеле видна на шине USB, а macOS её программе не отдаёт — ждёт «Разрешить» в окне доступа.
    private(set) var cableAccessHint = false
    /// Знакомые камеры (имена, где были в сети, сопряжение) и к какой подключались последней — в настройках.
    private(set) var roster = CameraRoster()
    /// Кого видно сейчас: камеры на шине USB и ответившие на поиск в сети.
    private(set) var usbSightings: [CameraSighting] = []
    private(set) var wifiSightings: [CameraEndpoint: CameraSighting] = [:]
    /// С какой камерой сеанс (или попытка): как подключена и чья запись (после DeviceInfo).
    private(set) var currentLink: CameraSighting.Link?
    private(set) var currentCameraID: String?
    /// Камеру по Wi-Fi выбрали в списке: подключаемся к ней, даже если на кабеле есть другая.
    private(set) var choosing: CameraSighting.Link?
    /// Сопряжение только с этой камерой («Сопрячь» в списке); nil — с любой новой.
    private(set) var pairingTarget: CameraEndpoint?
    /// Камеры в ленте рядом с главной — по Wi-Fi, каждая со своим открытым сеансом.
    private(set) var sideCameras: [SideCamera] = []
    /// Когда последний раз стучались к камере ленты, которой нет на связи.
    @ObservationIgnored private var stripAttempts: [String: Date] = [:]
    /// К каким камерам ленты без плитки сейчас стучимся.
    @ObservationIgnored private var stripConnecting: Set<String> = []
    /// Что не удалось на связанных камерах за текущую серию — одной строкой через секунду.
    @ObservationIgnored private var groupFailures = GroupFailures()
    @ObservationIgnored private var groupStatusTask: Task<Void, Never>?
    @ObservationIgnored private var wifiUSN: [CameraEndpoint: String] = [:]
    @ObservationIgnored private var usbKeys: [String: ObjectIdentifier] = [:]
    @ObservationIgnored private var currentUSBSerial: String?
    /// Сколько списков камер на экране: пока хоть один открыт, камеры ищутся в сети раз в 10 с.
    @ObservationIgnored private var cameraListViewers = 0
    @ObservationIgnored private var cameraSearchTask: Task<Void, Never>?
    @ObservationIgnored private let camerasKey = "cameras"
    @ObservationIgnored private let preferredCameraKey = "preferredCamera"
    @ObservationIgnored private var cableAccessWatch = CableAccessWatch()

    init() {
        info.note = { [weak self] text in self?.note(text) }
        library = Self.loadPresets(key: presetsKey)
        roster = CameraRoster.load(cameras: UserDefaults.standard.data(forKey: camerasKey),
                                   preferred: UserDefaults.standard.string(forKey: preferredCameraKey),
                                   legacyName: Prefs.wifiCameraName, legacyHost: Prefs.wifiHost)
        presetMode = UserDefaults.standard.string(forKey: presetModeKey).flatMap(CaptureMode.init(rawValue:)) ?? .photo
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
        // камеры ленты держим на связи
        Task { [weak self] in
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(2))
                self?.stripTick()
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
        default: return ValueFormatter.label(id, property(id), names: names(for: id))
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
        return property(id).map { SettingOptions.options(id, $0, names: names(for: id)) } ?? []
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
                    if self.property(.colorTemperature)?.current.intValue != kelvin {
                        self.request(.colorTemperature, kelvin)
                    } else {
                        // на главной уже такая — связанным её всё равно ставим
                        for side in self.linkedSides { side.write(.colorTemperature, kelvin) }
                    }
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
        // связанные камеры ленты — то же значение
        for side in linkedSides { side.write(id, value) }
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
        guard phase == .ready, let session else { return }
        // прошлое нажатие ещё не дошло (провал связи): второе дошло бы следом и остановило бы запись
        guard !recordCallInFlight else {
            emit(.failed(.record))
            showRecordingNotice("Камера ещё не ответила на прошлое нажатие")
            return
        }
        guard recordingToggle.press(recording: isRecording, at: Date()) else { return }
        recordCallInFlight = true
        recordingRefusal = nil
        clearRecordingNotice()
        emit(.accepted(.record))
        // связанные — к тому же состоянию, что будет на главной, и сразу, а не после неё
        let target = !isRecording
        note("запись: нажатие — \(target ? "запуск" : "остановка")")
        for side in linkedSides {
            Task { [weak self] in
                if await !side.setRecording(target) { self?.groupFailed(side, "запись") }
            }
        }
        Task { [weak self] in
            let pressed = Date()
            var recording: Bool?
            var refusal: UInt16?
            let report: String
            do {
                let result = try await session.toggleRecording()
                recording = result
                report = "камера приняла нажатие, \(result ? "пишет" : "не пишет")"
            } catch let SessionError.recordButtonRefused(release, code, after) {
                // отклонила, а запись всё же сменилась — это и есть исход; не сменилась — отказ с её кодом
                if after == target { recording = after } else { refusal = code }
                report = String(format: "камера ответила 0x%04X на «%@», %@", code, release ? "отпустить" : "нажать",
                                after ? "пишет" : "не пишет")
            } catch {
                report = "нажатие не прошло: \(error)"
            }
            guard let self else { return }
            self.note("запись: \(report) (\(Int(Date().timeIntervalSince(pressed) * 1000)) мс)")
            self.recordCallInFlight = false
            if let recording { self.isRecording = recording }
            self.recordingRefusal = refusal
            self.finishRecordingRequest(self.recordingToggle.completed(recording: recording, at: Date()))
        }
    }

    /// Камера подтвердила запуск или остановку записи — или не подтвердила за 6 с, или запись кончилась сама.
    private func finishRecordingRequest(_ outcome: RecordingToggle.Outcome?) {
        guard let outcome else { return }
        switch outcome {
        case .started: emit(.recordingStarted)
        case .stopped: emit(.recordingStopped)
        case .failed, .aborted: emit(.failed(.record))
        case .stoppedByCamera: break
        }
        if let message = RecordingToggle.message(outcome, refusal: recordingRefusal) {
            if RecordingToggle.showsAtButton(outcome) { showRecordingNotice(message) } else { showStatus(message) }
            note("запись: \(message)")
        }
    }

    /// Подпись у кнопки записи — 5 с, как сообщение в строке состояния.
    private func showRecordingNotice(_ text: String) {
        recordingNotice = text
        recordingNoticeTask?.cancel()
        recordingNoticeTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(5))
            if !Task.isCancelled { self?.recordingNotice = nil }
        }
    }

    private func clearRecordingNotice() {
        recordingNoticeTask?.cancel()
        recordingNotice = nil
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
        let capture = UUID()
        let (phases, sink) = AsyncStream<CapturePhase>.makeStream()
        Task { [weak self] in
            for await phase in phases {
                self?.captureMoved(to: phase, review: reviewOn ? capture : nil, progress: progress)
            }
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
                // просмотр этой съёмки ещё на экране — скачанный снимок встаёт на место кадра, время не сбрасывается
                if reviewOn, var current = self.review, current.capture == capture {
                    let downloaded = source == .downloaded && preview.image != nil
                    current.image = downloaded ? preview.image : current.image
                    current.title = shot.title
                    current.detail = detail
                    current.urls = urls
                    current.source = downloaded ? "Загруженный снимок" : "Кадр с камеры"
                    self.review = current
                }
            } catch {
                self.dismissReview()
                self.emit(.failed(.shutter))
                self.showStatus("Снимок не удался: \(error)")
            }
        }
    }

    /// Снимок перешёл на следующий этап. Спуск отработан — шторка и просмотр (кадр той секунды, когда камера снимала);
    /// просмотр идёт заданное время с этого момента, скачанный снимок потом встанет на место кадра. Качается файл —
    /// по Wi-Fi видно, сколько пришло.
    private func captureMoved(to phase: CapturePhase, review capture: UUID?, progress: DataProgress?) {
        guard isCapturing else { return }
        capturePhase = phase
        switch phase {
        case .focusing:
            break
        case .exposing:
            emit(.shutterFired)
            if let capture {
                showReview(Review(image: frame, title: "Снимок", detail: "", urls: [], source: "Кадр с камеры",
                                  endsAt: nil, capture: capture), timed: true)
            }
        case let .downloading(file, files, received, _):
            // по USB доли не видно (ImageCaptureCore её не даёт) — без размера дуга крутится, подпись «Загрузка…»
            guard let progress else {
                capturePhase = .downloading(file: file, files: files, received: received, expected: nil)
                return
            }
            guard progressTask == nil else { return }
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

    /// Удаляет снимок из ленты: файлы (у RAW + JPEG — оба) уходят в Корзину, оттуда их можно достать и потом; 6 с видна
    /// «Вернуть». false — не вышло (файл занят, у папки нет Корзины): снимок остаётся, что не так — в строке состояния.
    @discardableResult
    func deleteShot(_ id: UUID) -> Bool {
        guard let index = shots.firstIndex(where: { $0.id == id }) else { return false }
        let shot = shots[index]
        var moves: [(from: URL, to: URL)] = []
        do {
            for url in shot.urls {
                var trashed: NSURL?
                try FileManager.default.trashItem(at: url, resultingItemURL: &trashed)
                moves.append((url, (trashed as URL?) ?? url))
            }
        } catch {
            // половину снимка в Корзине не оставляем: что успело уйти — назад
            for move in moves { try? FileManager.default.moveItem(at: move.to, to: move.from) }
            showStatus("Не удалось удалить \(shot.title): \(error.localizedDescription)")
            return false
        }
        shots.remove(at: index)
        if let review, !review.urls.isEmpty, review.urls == shot.urls { dismissReview() }
        deletedShot = DeletedShot(shot: shot, moves: moves)
        deletedShotTask?.cancel()
        deletedShotTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(ShotDeletion.undoSeconds))
            guard !Task.isCancelled else { return }
            self?.deletedShot = nil
        }
        return true
    }

    /// «Вернуть»: файлы из Корзины — на прежнее место, снимок — в ленту туда, где был по времени. Отдаёт его id;
    /// nil — возвращать нечего или ни один файл не вернулся.
    @discardableResult
    func undoDeleteShot() -> UUID? {
        guard let deleted = deletedShot else { return nil }
        deletedShotTask?.cancel()
        deletedShot = nil
        var failed: [String] = []
        for move in deleted.moves where move.to != move.from {
            do {
                try FileManager.default.moveItem(at: move.to, to: move.from)
            } catch {
                failed.append(move.from.lastPathComponent)
            }
        }
        if !failed.isEmpty { showStatus("Не удалось вернуть из Корзины: \(failed.joined(separator: ", "))") }
        guard failed.count < deleted.moves.count else { return nil }
        shots.insert(deleted.shot, at: ShotDeletion.restoreIndex(takenAt: deleted.shot.takenAt, in: shots.map(\.takenAt)))
        return deleted.shot.id
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
        value.duration = TimeInterval(seconds)
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

    /// Имя нового пресета, если его не назвали.
    var nextPresetName: String { PresetCatalog.newName(presets) }

    /// Какой пресет сейчас стоит на камере: первый, у которого всё, что он ставит, совпадает; nil — никакой.
    var activePresetIndex: Int? { presets.firstIndex { PresetCatalog.matches($0, properties) } }

    /// Текущий пресет: опора, если стоит на камере; иначе первый, что стоит; иначе опора. nil — никакого.
    var currentPresetIndex: Int? {
        let list = presets
        let base = presetBase[presetMode].flatMap { list.indices.contains($0) ? $0 : nil }
        if let base, PresetCatalog.matches(list[base], properties) { return base }
        return list.firstIndex { PresetCatalog.matches($0, properties) } ?? base
    }

    /// Чем камера сейчас отличается от текущего пресета; пусто — не изменён или камера не на связи.
    var currentPresetChanges: [PresetChange] {
        guard phase == .ready, let index = currentPresetIndex else { return [] }
        return PresetCatalog.changes(presets[index], properties, strings: info.strings)
    }

    /// Камера сменила режим — показываем его пресеты и запоминаем: без связи список — того, в котором она была.
    private func followPresetMode() {
        guard let value = properties[SonyProp.exposureProgram]?.current.intValue else { return }
        let mode = CaptureMode(exposureProgram: value)
        guard mode != presetMode else { return }
        presetMode = mode
        UserDefaults.standard.set(mode.rawValue, forKey: presetModeKey)
    }

    /// Сколько пресетов у режима — для API.
    func presetCount(_ mode: CaptureMode) -> Int { library.filter { $0.mode == mode }.count }

    /// Место в общем списке пресета с номером `index` в списке режима.
    private func slot(_ index: Int) -> Int? {
        let slots = PresetLibrary.indices(of: presetMode, in: library)
        return slots.indices.contains(index) ? slots[index] : nil
    }

    /// Сохраняет текущие настройки камеры новым пресетом в конец списка режима; возвращает его номер.
    @discardableResult
    func savePreset(name: String? = nil) -> Int? {
        guard phase == .ready else { return nil }
        let title = name?.trimmingCharacters(in: .whitespaces).isEmpty == false ? name! : nextPresetName
        library.append(PresetCatalog.capture(name: title, mode: presetMode, from: properties))
        storePresets()
        presetListVersion += 1
        let index = presets.count - 1
        presetBase[presetMode] = index
        showStatus("Новый пресет «\(title)»")
        return index
    }

    /// «Сохранить в «…»»: текущий пресет — как сейчас настроена камера.
    func savePresetChanges() {
        guard let index = currentPresetIndex, recapture(index) else { return }
        showStatus("Сохранено в «\(presets[index].name)»")
    }

    func applyPreset(_ index: Int) {
        guard presets.indices.contains(index), phase == .ready, !isApplyingPreset, let session else { return }
        let preset = presets[index]
        // всё выключено — писать нечего, а «применено 0 из 0» ничего не объясняет
        guard !PresetCatalog.effective(preset).isEmpty else { return showStatus("«\(preset.name)» ничего не меняет") }
        presetBase[presetMode] = index
        isApplyingPreset = true
        statusMessage = "Применяю «\(preset.name)»…"
        for side in linkedSides {
            Task { [weak self] in
                for id in await side.apply(preset) {
                    self?.groupFailed(side, preset.values[id].map { GroupFailures.label(id, $0, names: self?.names(for: id)) } ?? id.shortTitle)
                }
            }
        }
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
        guard let slot = slot(index), !trimmed.isEmpty else { return }
        library[slot].name = trimmed
        storePresets()
    }

    func deletePreset(_ index: Int) {
        guard let slot = slot(index) else { return }
        library.remove(at: slot)
        storePresets()
        presetListVersion += 1
        if let base = presetBase[presetMode] { presetBase[presetMode] = base == index ? nil : base > index ? base - 1 : base }
    }

    /// Начать быстрый просмотр: запомнить, как камера настроена сейчас, и поставить пресет. Отпустили кнопку —
    /// `endPresetPreview`.
    func beginPresetPreview(_ index: Int) {
        guard presets.indices.contains(index), phase == .ready, !isApplyingPreset, let session else { return }
        let preset = presets[index]
        guard !PresetCatalog.effective(preset).isEmpty else { return showStatus("«\(preset.name)» ничего не меняет") }
        previewRestore = PresetCatalog.restoreValues(preset, from: properties)
        previewingPreset = index
        isApplyingPreset = true
        // что идёт просмотр, говорит плашка над картинкой — строка под панелью её не повторяет
        statusTask?.cancel()
        statusMessage = nil
        let stop = PresetStop()
        previewStop = stop
        previewApply = Task { _ = await session.apply(preset, while: { !stop.isStopped }) }
    }

    /// Кнопку отпустили: недописанное не пишем, всё, что пресет поменял, возвращаем как было.
    func endPresetPreview() {
        guard previewingPreset != nil, !previewReturning else { return }
        previewReturning = true
        statusMessage = "Возвращаю как было…"
        let applying = previewApply
        previewStop?.stop()
        let values = previewRestore
        let session = session
        Task { [weak self] in
            await applying?.value
            let result = await session?.restore(values)
            guard let self, self.previewReturning else { return }
            self.endPreviewWithoutCamera()
            self.isApplyingPreset = false
            if let skipped = result?.skipped, !skipped.isEmpty {
                self.showStatus("Вернул как было, кроме: " + skipped.map(\.title).joined(separator: ", "))
            } else {
                self.showStatus("Вернул как было")
            }
        }
    }

    /// Просмотр кончился — или связь пропала, и вернуть уже не с чем.
    private func endPreviewWithoutCamera() {
        previewStop?.stop()
        previewStop = nil
        previewApply?.cancel()
        previewApply = nil
        previewRestore = [:]
        previewingPreset = nil
        previewReturning = false
    }

    /// Значение настройки в пресете — камера при этом не меняется.
    func setPresetValue(_ index: Int, _ id: SettingID, _ value: Int64) {
        guard let slot = slot(index), PresetCatalog.order.contains(id) else { return }
        library[slot].values[id] = value
        storePresets()
    }

    /// Можно ли включить настройку: значение уже есть в пресете или его есть откуда взять.
    func canIncludeInPreset(_ index: Int, _ id: SettingID) -> Bool {
        guard let slot = slot(index) else { return false }
        return library[slot].values[id] != nil || presetDefault(id) != nil
    }

    /// Включить или выключить настройку в пресете. Выключенная помнит значение; включают без значения — текущее с камеры.
    func setPresetIncluded(_ index: Int, _ id: SettingID, _ included: Bool) {
        guard let slot = slot(index), PresetCatalog.order.contains(id) else { return }
        if included {
            if library[slot].values[id] == nil {
                guard let value = presetDefault(id) else { return }
                library[slot].values[id] = value
            }
            library[slot].skip.remove(id)
        } else {
            library[slot].skip.insert(id)
        }
        storePresets()
    }

    /// Чем заполнить настройку, которой в пресете не было: текущим значением камеры. Температуру камера отдаёт в любом
    /// режиме — сохранённую ручную; без связи — 5600 K.
    private func presetDefault(_ id: SettingID) -> Int64? {
        if let value = property(id)?.current.intValue { return value }
        return id == .colorTemperature ? 5600 : nil
    }

    /// Переставляет пресет на место `to` своего списка — номер и клавиша 1–9 меняются вместе с местом.
    func movePreset(from: Int, to: Int) {
        guard from != to, let moved = PresetLibrary.moved(library, mode: presetMode, from: from, to: to) else { return }
        library = moved
        storePresets()
        presetListVersion += 1
        guard let base = presetBase[presetMode] else { return }
        if base == from {
            presetBase[presetMode] = to
        } else if from < base, to >= base {
            presetBase[presetMode] = base - 1
        } else if from > base, to <= base {
            presetBase[presetMode] = base + 1
        }
    }

    /// Копия пресета сразу за ним, в том же режиме; возвращает её номер.
    @discardableResult
    func duplicatePreset(_ index: Int) -> Int? {
        guard let slot = slot(index) else { return nil }
        var copy = library[slot]
        copy.name += " — копия"
        library.insert(copy, at: slot + 1)
        storePresets()
        presetListVersion += 1
        if let base = presetBase[presetMode], base > index { presetBase[presetMode] = base + 1 }
        return index + 1
    }

    /// «Взять с камеры»: всё, что камера сейчас даёт менять, — в пресет её текущими значениями.
    func recapturePreset(_ index: Int) {
        guard recapture(index) else { return }
        showStatus("«\(presets[index].name)»: значения взяты с камеры")
    }

    /// Пресет — текущими значениями камеры; он же теперь текущий. Возвращает, вышло ли.
    private func recapture(_ index: Int) -> Bool {
        guard phase == .ready, let slot = slot(index) else { return false }
        library[slot] = PresetCatalog.recapture(library[slot], from: properties)
        storePresets()
        presetBase[presetMode] = index
        return true
    }

    private func storePresets() {
        if let data = try? JSONEncoder().encode(library) { UserDefaults.standard.set(data, forKey: presetsKey) }
    }

    private static func loadPresets(key: String) -> [Preset] {
        UserDefaults.standard.data(forKey: key).map(PresetLibrary.decode) ?? []
    }

    /// Короткое сообщение в строке состояния сверху — на пять секунд.
    func showStatus(_ text: String) {
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
        usbKeys[Self.usbKey(device)] = id
        refreshUSBSightings()
        perform(tracker.appeared(id))
    }

    private func handleDisappeared(_ device: ICCameraDevice) {
        let id = ObjectIdentifier(device)
        let action = tracker.disappeared(id)
        devices[id] = nil
        usbKeys[Self.usbKey(device)] = nil
        refreshUSBSightings()
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
        // по Wi-Fi уже идёт сеанс (или его выбрали в списке): не рвём его — камера в режиме Wi-Fi видна на кабеле как
        // накопитель (например, на зарядке) и PC Remote по USB всё равно не даст; кабельную камеру подключим, когда
        // Wi-Fi закончится
        guard transportKind != .wifi, choosing == nil else { return }
        let id = ObjectIdentifier(device)
        self.device = device
        currentLink = .usb(Self.usbKey(device))
        currentCameraID = nil
        currentUSBSerial = device.serialNumberString
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
                self.noteCameraIdentity()
                self.info.identify(self.currentCameraID)
                self.phase = .settling
                self.startStreams(session, onEnded: onFailure)
                try await session.waitUntilReady()
                guard !Task.isCancelled else { return }
                self.phase = .ready
                self.connectionMessage = nil
                // сеанс готов — восстановление кончилось, даже если кадров пока нет (иначе карточка висела бы)
                self.finishReconnect()
                self.startQuality(session)
                // подписи и данные объектива новых камер — после того, как сеанс готов, и не задерживая его
                self.info.begin(session, camera: self.currentCameraID, properties: self.properties)
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

    // MARK: - Камеры

    /// Список камер: текущая, видимые, знакомые.
    var cameras: [CameraEntry] {
        roster.entries(sightings: usbSightings + Array(wifiSightings.values),
                       current: currentLink.map { ($0, currentCameraID, phase == .ready) },
                       strip: sideCameras.map { ($0.id, $0.connected) }, now: Date())
            .map { entry in
                var entry = entry
                entry.pairing = isPairing && pairingTarget != nil && entry.endpoint == pairingTarget && entry.state != .current
                return entry
            }
    }

    /// Как называть камеру, с которой сеанс: своё имя, иначе модель.
    var currentCameraName: String { roster.camera(currentCameraID)?.name ?? deviceInfo?.model ?? "Камера" }

    /// Выбрали камеру в списке: текущая по Wi-Fi уходит в ленту, по кабелю — отключается, и подключаемся к выбранной.
    /// Во время записи — только с главной по Wi-Fi (`canSwitchCamera`): с кабелем камера осталась бы писать без программы.
    func selectCamera(_ id: String) {
        guard let entry = cameras.first(where: { $0.id == id }), entry.state != .current else { return }
        // главная по кабелю закрылась бы посреди записи; по Wi-Fi уйдёт в ленту и продолжит писать
        guard !isRecording || mainCanGoToStrip else { return showStatus("Идёт запись — остановите её, чтобы переключить камеру") }
        if entry.state == .side {
            if let side = sideCameras.first(where: { $0.id == id && $0.connected }) { return promote(side) }
            // без связи — подключить главной по последнему адресу; попытки ленты к ней больше не нужны
            closeSide(id)
        }
        if entry.state == .needsPairing { return pairCamera(id) }
        if case let .usb(key)? = entry.link {
            guard let device = usbKeys[key] else { return }
            choosing = nil
            pairingTarget = nil
            isPairing = false
            showStatus("Подключаю «\(entry.name)»…")
            perform(tracker.choose(device))
            return
        }
        guard let endpoint = entry.endpoint else { return }
        connectChosen(endpoint, name: entry.name, pairing: false)
    }

    /// «Сопрячь» в списке: сопряжение только с этой камерой.
    func pairCamera(_ id: String) {
        guard let entry = cameras.first(where: { $0.id == id }), let endpoint = entry.endpoint else { return }
        guard !isRecording || mainCanGoToStrip else { return showStatus("Идёт запись — остановите её, чтобы переключить камеру") }
        connectChosen(endpoint, name: entry.name, pairing: true)
    }

    /// К выбранной камере по Wi-Fi: сеанс с текущей закрывается, стучимся сразу — не ждём секундного тика.
    private func connectChosen(_ endpoint: CameraEndpoint, name: String, pairing: Bool) {
        choosing = .wifi(endpoint)
        pairingTarget = pairing ? endpoint : nil
        isPairing = pairing
        moveMainAside()
        connectionMessage = nil
        wifiPolicy = WiFiPolicy()
        if pairing {
            wifiStatus = "Камера найдена (\(endpoint)) — подтвердите сопряжение на ней"
        } else {
            showStatus("Подключаю «\(name)»…")
        }
        Task { [weak self] in
            guard let self else { return }
            // секундный тик мог как раз стучаться — ждём его
            while self.wifiBusy { try? await Task.sleep(for: .milliseconds(100)) }
            guard self.choosing == .wifi(endpoint), self.transportKind == nil else { return }
            self.wifiBusy = true
            let result = await self.tryWiFi(endpoint, discovered: true)
            self.wifiBusy = false
            guard self.choosing == .wifi(endpoint) || result == .connected else { return }
            if result != .connected, !pairing {
                // не ответила — дальше как обычно: кабель, последняя камера по Wi-Fi
                self.choosing = nil
                self.showStatus("«\(name)» не отвечает")
                self.connectAttachedIfAny()
            }
        }
    }

    func renameCamera(_ id: String, to name: String) {
        roster.rename(id, to: name)
        storeRoster()
    }

    /// Забыть камеру: она пропадает из списка (если не на связи — тогда она остаётся текущей).
    func forgetCamera(_ id: String) {
        guard id != currentCameraID else { return }
        closeSide(id)
        roster.forget(id)
        storeRoster()
    }

    // MARK: - Лента камер

    /// В ленту: программа будет держать камеру на связи рядом с главной.
    func addToStrip(_ id: String) {
        roster.setInStrip(id, true)
        storeRoster()
        stripAttempts[id] = nil
        stripTick()
    }

    /// Из ленты: сеанс с ней закрывается, сама программа к ней больше не подключается.
    func removeFromStrip(_ id: String) {
        roster.setInStrip(id, false)
        storeRoster()
        closeSide(id)
    }

    private func closeSide(_ id: String) {
        sideCameras.filter { $0.id == id }.forEach { $0.close() }
        sideCameras.removeAll { $0.id == id }
    }

    /// Можно ли переключиться на другую камеру сейчас: во время записи — только если главная уйдёт в ленту, а не
    /// закроется.
    var canSwitchCamera: Bool { !isRecording || mainCanGoToStrip }

    /// Главную можно увести в ленту, не закрывая её сеанс: по Wi-Fi, готова, знакома.
    private var mainCanGoToStrip: Bool {
        transportKind == .wifi && phase == .ready && currentCameraID != nil && wifiTransport != nil
    }

    /// Перед подключением к другой камере: главная по Wi-Fi — в ленту, по кабелю — отключить.
    private func moveMainAside() {
        if !releaseMainToStrip(), transportKind != nil { teardown() }
    }

    /// Главная по Wi-Fi уходит в ленту: её сеанс не закрывается, а переходит плитке.
    @discardableResult
    private func releaseMainToStrip() -> Bool {
        guard mainCanGoToStrip, let session, let transport = wifiTransport, case let .wifi(endpoint)? = currentLink,
              let id = currentCameraID else { return false }
        let handed = SideCamera.Handed(session: session, transport: transport, endpoint: endpoint, deviceInfo: deviceInfo,
                                       properties: properties, recordingStartedAt: recordingStartedAt)
        teardown(closing: false)
        roster.setInStrip(id, true)
        storeRoster()
        let side = sideCamera(id, endpoint)
        side.adopt(handed)
        note("«\(roster.camera(id)?.name ?? "камера")» — в ленту")
        return true
    }

    /// Камера из ленты — главная, сразу: её открытый сеанс переходит на большой экран. Прежняя главная по Wi-Fi —
    /// в ленту, по кабелю — отключается. Группа не меняется: камера из неё ведёт группу, не из неё — группа ждёт.
    private func promote(_ side: SideCamera) {
        guard let handed = side.handOver() else { return }
        sideCameras.removeAll { $0 === side }
        choosing = nil
        pairingTarget = nil
        isPairing = false
        reconnectTask?.cancel()
        reconnectTask = nil
        moveMainAside()
        adoptMain(handed)
    }

    // MARK: - Группа камер

    /// Камеры группы в ленте на связи — когда главная тоже в группе: повторяют за ней настройки, пресеты и запись.
    var linkedSides: [SideCamera] {
        let followers = Set(roster.followers(of: currentCameraID, among: sideCameras.map(\.id)))
        return sideCameras.filter { $0.connected && followers.contains($0.id) }
    }

    /// Главная в группе: камеры группы повторяют за ней.
    var mainInGroup: Bool { roster.camera(currentCameraID)?.linked == true }

    /// Цепочка на плитке: связать камеру ленты с главной (главная тоже входит в группу) или отвязать.
    func linkCamera(_ id: String, _ linked: Bool) {
        roster.setLinked(id, linked, main: currentCameraID)
        storeRoster()
    }

    // MARK: - Порядок плиток

    /// Плитки ленты сверху вниз — главная и камеры ленты в порядке знакомых камер: выбор главной места не меняет.
    var stripTiles: [String] {
        roster.stripOrder((currentCameraID.map { [$0] } ?? []) + sideCameras.map(\.id))
    }

    /// Верхняя камера ленты на связи: она становится главной, когда главной нет.
    private var topConnectedSide: SideCamera? {
        roster.stripOrder(sideCameras.map(\.id)).lazy.compactMap { id in self.sideCameras.first { $0.id == id && $0.connected } }.first
    }

    /// Плитку перетащили на другую: встаёт на её место, порядок запоминается.
    func moveInStrip(_ id: String, onto target: String) {
        roster.move(id, onto: target)
        storeRoster()
    }

    /// На связанной не прижилось — в строку состояния раз на серию: «Не удалось на «C»: ISO 51200».
    private func groupFailed(_ side: SideCamera, _ what: String) {
        groupFailures.add(camera: roster.camera(side.id)?.name ?? "камера", what: what)
        groupStatusTask?.cancel()
        groupStatusTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard let self, !Task.isCancelled else { return }
            if let text = self.groupFailures.text { self.showStatus(text) }
            self.groupFailures = GroupFailures()
        }
    }

    /// Главный экран берёт открытый сеанс из ленты: без рукопожатия и ожидания — камера уже готова.
    private func adoptMain(_ handed: SideCamera.Handed) {
        let session = handed.session
        let transport = handed.transport
        let endpoint = handed.endpoint
        phase = .settling
        transportKind = .wifi
        wifiStatus = nil
        connectionMessage = nil
        qualityPolicy = LiveViewQualityPolicy(now: Date())
        qualityWriting = false
        recentFrames = []
        if sessionStartedAt == nil { sessionStartedAt = Date() }
        if sessionActivity == nil {
            sessionActivity = ProcessInfo.processInfo.beginActivity(
                options: [.userInitiated, .latencyCritical, .idleDisplaySleepDisabled], reason: "Live view с камеры")
        }
        self.session = session
        closeTransport = { await transport.close() }
        wifiTransport = transport
        wifiDirect = PTPIP.isCameraAccessPoint(endpoint.host)
        currentLink = .wifi(endpoint)
        coalescer = WriteCoalescer { [weak self] code, value in
            let result = try? await session.set(code, to: value)
            await self?.writeFinished(code, value, result)
        }
        deviceInfo = handed.deviceInfo
        // таймер записи — с того же места: камера продолжала писать в ленте
        recordingStartedAt = handed.recordingStartedAt
        apply(handed.properties)
        noteCameraIdentity()
        info.identify(currentCameraID)
        startStreams(session, onEnded: { [weak self] in
            Task { [weak self] in
                let loss = await transport.loss
                self?.wifiLost(loss, transport: transport, host: endpoint)
            }
        })
        tasks.append(Task { [weak self] in
            for await status in transport.linkStatus { self?.linkChanged(status) }
        })
        phase = .ready
        info.begin(session, camera: currentCameraID, properties: properties)
        wifiPolicy.connected()
        wifiPolicy.sessionReady()
        // в ленте превью было низкого качества — на главной сразу высокое, дальше решает политика
        writeQuality(.high, session)
        note("главная — «\(currentCameraName)», из ленты")
    }

    private func sideCamera(_ id: String, _ endpoint: CameraEndpoint) -> SideCamera {
        if let side = sideCameras.first(where: { $0.id == id }) { return side }
        let side = newSideCamera(id, endpoint)
        sideCameras.append(side)
        return side
    }

    private func newSideCamera(_ id: String, _ endpoint: CameraEndpoint) -> SideCamera {
        let side = SideCamera(id: id, endpoint: endpoint)
        side.onWriteFailed = { [weak self, weak side] setting, value in
            guard let self, let side else { return }
            self.groupFailed(side, GroupFailures.label(setting, value, names: self.names(for: setting)))
        }
        return side
    }

    /// Раз в 2 с: камеры ленты, которых нет на связи, — стучимся (к каждой не чаще раза в 10 с); убранные из ленты
    /// и не поместившиеся — закрываем.
    private func stripTick() {
        let members = CameraStripPlan.members(roster, main: currentCameraID)
        let ids = Set(members.map(\.id))
        for side in sideCameras where !ids.contains(side.id) { side.close() }
        sideCameras.removeAll { !ids.contains($0.id) }
        // пока ищем новую камеру или подключаем выбранную — не мешаем; Wi-Fi выключен в настройках — не стучимся
        guard !isPairing, choosing == nil, Prefs.wifiEnabled else { return }
        let busy = Set(sideCameras.filter { $0.connected || $0.connecting }.map(\.id)).union(stripConnecting)
        for camera in CameraStripPlan.toConnect(roster, main: currentCameraID, connected: busy, lastAttempt: stripAttempts,
                                                now: Date()) {
            guard let endpoint = camera.endpoint, currentLink != .wifi(endpoint) else { continue }
            stripAttempts[camera.id] = Date()
            // плитка — у камеры, что уже была на связи в этом запуске; новая появится, когда ответит
            let known = sideCameras.first { $0.id == camera.id }
            let side = known ?? newSideCamera(camera.id, endpoint)
            if known == nil { stripConnecting.insert(camera.id) }
            Task { [weak self] in
                guard let self else { return }
                let connected = await side.connect(identity: self.identity)
                self.stripConnecting.remove(camera.id)
                guard connected else { return }
                // пока стучались, камеру убрали из ленты или она стала главной — сеанс не нужен
                guard CameraStripPlan.members(self.roster, main: self.currentCameraID).contains(where: { $0.id == camera.id })
                else { return side.close() }
                if !self.sideCameras.contains(where: { $0 === side }) { self.sideCameras.append(side) }
                self.note("в ленте — «\(camera.name)»")
            }
        }
    }


    /// Список камер показан: ищем камеры в сети сразу и раз в 10 с, пока он открыт.
    func cameraListAppeared() {
        cameraListViewers += 1
        guard cameraSearchTask == nil else { return }
        cameraSearchTask = Task { [weak self] in
            while let self, !Task.isCancelled, self.cameraListViewers > 0 {
                await self.searchCameras()
                try? await Task.sleep(for: .seconds(10))
            }
            self?.cameraSearchTask = nil
        }
    }

    func cameraListDisappeared() {
        cameraListViewers = max(0, cameraListViewers - 1)
        if cameraListViewers == 0 {
            cameraSearchTask?.cancel()
            cameraSearchTask = nil
        }
    }

    /// Поиск камер в сети сейчас.
    func searchCameras() async {
        noteSightings(await WiFiDiscovery.search(seconds: 3))
    }

    /// Ответы на поиск: камера видна `CameraRoster.wifiVisibility` после последнего ответа.
    private func noteSightings(_ responses: [SSDP.Response]) {
        let now = Date()
        var sightings = wifiSightings.filter { now.timeIntervalSince($0.value.seenAt) <= CameraRoster.wifiVisibility }
        for response in responses {
            sightings[response.endpoint] = CameraSighting(link: .wifi(response.endpoint),
                                                          fingerprint: CameraFingerprint.usn(response.usn), seenAt: now)
            wifiUSN[response.endpoint] = response.usn
        }
        if sightings != wifiSightings { wifiSightings = sightings }
    }

    private func refreshUSBSightings() {
        let now = Date()
        usbSightings = devices.values.map { device in
            CameraSighting(link: .usb(Self.usbKey(device)), model: device.name,
                           fingerprint: device.serialNumberString.flatMap(CameraFingerprint.serial), seenAt: now)
        }
        .sorted { "\($0.link)" < "\($1.link)" }
    }

    private static func usbKey(_ device: ICCameraDevice) -> String {
        device.uuidString ?? String(describing: ObjectIdentifier(device))
    }

    /// Камера назвала себя (DeviceInfo): запомнить её, она же — предпочтённая.
    private func noteCameraIdentity() {
        guard let link = currentLink else { return }
        var usn: String?
        if case let .wifi(endpoint) = link { usn = wifiUSN[endpoint] }
        currentCameraID = roster.connected(serial: deviceInfo?.serial, model: deviceInfo?.model ?? "Камера", link: link,
                                           usbSerial: currentUSBSerial, usn: usn, now: Date())
        storeRoster()
    }

    private func storeRoster() {
        if let data = roster.encoded() { UserDefaults.standard.set(data, forKey: camerasKey) }
        if let preferred = roster.preferred {
            UserDefaults.standard.set(preferred, forKey: preferredCameraKey)
        } else {
            UserDefaults.standard.removeObject(forKey: preferredCameraKey)
        }
    }

    // MARK: - Wi-Fi

    /// Начать сопряжение: программа ищет камеру и стучится, пока на камере включено Pairing.
    func startPairing() {
        pairingTarget = nil
        // новую ищем без главной: главная по Wi-Fi уходит в ленту
        moveMainAside()
        isPairing = true
        wifiStatus = "Ищу камеру в сети…"
    }

    func cancelPairing() {
        isPairing = false
        pairingTarget = nil
        choosing = nil
        wifiStatus = nil
        // идущая попытка ждёт подтверждения на камере до минуты — обрываем её
        closeConnectingTransport()
    }

    /// Забыть камеру: новый GUID, и сопряжение в камере к программе больше не относится — сама она подключаться
    /// не будет, пока не сопрячь заново.
    func forgetWiFiCamera() {
        Prefs.wifiHost = nil
        Prefs.wifiCameraName = nil
        roster.unpairAll()
        storeRoster()
        sideCameras.forEach { $0.close() }
        sideCameras.removeAll()
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
        // камера на кабеле подключается или ждёт повтора — кабель важнее сети, если камеру по Wi-Fi не выбрали в
        // списке; на кабеле, но не в PC Remote по USB (например, на зарядке в режиме Wi-Fi) — можно по сети
        let chosen = choosing != nil || pairingTarget != nil
        let free = (phase == .searching && transportKind == nil && (tracker.attached.isEmpty || chosen))
            || (phase == .notPCRemote && transportKind == .usb)
        return free && (Prefs.wifiEnabled || isPairing || chosen)
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
              wifiPolicy.mayAttempt(enabled: Prefs.wifiEnabled || choosing != nil, pairing: isPairing, now: Date()) else { return }
        wifiBusy = true
        defer { wifiBusy = false }
        // тесты через прокси «плохой сети» (scripts/badnet.py): только этот адрес, без поиска камеры в сети
        if AutomationServer.isRequested,
           let forced = ProcessInfo.processInfo.environment["ZV_PTPIP_HOST"].flatMap(CameraEndpoint.init) {
            if wifiPolicy.maySendInit(paired: roster.isPaired(forced) || Prefs.wifiCameraName != nil, pairing: isPairing) {
                _ = await tryWiFi(forced, discovered: false)
            }
            return
        }
        // сначала — выбранная в списке, та, что сопрягаем, или к которой подключались последней: так подключение после
        // перерыва занимает секунду, а не поиск
        var chosen: CameraEndpoint?
        if case let .wifi(endpoint)? = choosing { chosen = endpoint }
        let last = pairingTarget ?? chosen ?? (isPairing ? nil : roster.firstEndpoint)
        var lastFailed = false
        // камера уже на связи в ленте — второе подключение ей не нужно: она главная сразу
        if let last, let side = sideCameras.first(where: { $0.connected && $0.endpoint == last }) { return promote(side) }
        if let last {
            switch await tryWiFi(last, discovered: false) {
            case .connected, .dropped: return
            case .rejected: break
            case .failed: lastFailed = true
            }
        }
        // выбранная в списке камера или сопряжение с одной — только она
        guard wifiWanted, pairingTarget == nil, chosen == nil else { return }
        // последняя не ответила, а в ленте есть камеры на связи — главной становится верхняя
        if !isPairing, let side = topConnectedSide { return promote(side) }
        if isPairing, wifiStatus == nil { wifiStatus = "Ищу камеру в сети…" }
        let responses = await WiFiDiscovery.search()
        noteSightings(responses)
        let inStrip = Set(sideCameras.map(\.endpoint))
        let found = responses.map(\.endpoint).filter { !inStrip.contains($0) }
        guard wifiWanted else { return }
        if found.isEmpty {
            wifiPolicy.nothingFound()
            wifiStatus = isPairing ? "Камеру в сети не вижу. Проверьте, что она подключена к той же сети Wi-Fi" : nil
            return
        }
        // сопряжение могло только что оказаться недействительным (камера отказала) — смотрим заново
        let paired = found.filter { roster.isPaired($0, usn: wifiUSN[$0]) }
        // ни с одной не сопряжены — не стучимся (камера в режиме Pairing показала бы чужой запрос), только подсказываем
        guard wifiPolicy.maySendInit(paired: !paired.isEmpty, pairing: isPairing) else {
            if wifiStatus == nil {
                wifiStatus = "В сети есть камера (\(found[0])) — «Сопрячь» у неё в списке камер, и она будет подключаться по Wi-Fi"
            }
            return
        }
        // камера видна по последнему адресу, но не ответила — это неудача к найденной камере
        if let last, lastFailed, found.contains(last) {
            wifiPolicy.attemptFailed(discovered: true)
            showStaleHintIfNeeded(last)
        }
        // при сопряжении — сначала новые камеры, потом знакомые; без него — только сопряжённые
        let order = isPairing ? found.filter { !paired.contains($0) } + paired : paired
        for endpoint in order where endpoint != last {
            guard wifiWanted else { return }
            switch await tryWiFi(endpoint, discovered: true) {
            case .connected, .dropped: return
            case .rejected, .failed: continue
            }
        }
    }

    private func showStaleHintIfNeeded(_ host: CameraEndpoint) {
        guard wifiPolicy.showsStaleHint, !isPairing else { return }
        wifiStatus = "Камера найдена (\(host)), но пока не отвечает — видимо, держит прошлое подключение. "
            + "Обычно это проходит за 1–2 минуты; если нет — выключите и снова включите на камере PC Remote"
    }

    /// Нужна ли попытка по Wi-Fi: обычный поиск или тихое восстановление оборвавшегося сеанса.
    private var wifiAttemptWanted: Bool { wifiWanted || reconnectingSince != nil }

    /// Одна попытка подключиться к камере по адресу. discovered — адрес найден поиском сейчас, а не взят из памяти;
    /// quick — восстановление сеанса: камера только что была на связи, долго ждать ответа незачем.
    private func tryWiFi(_ host: CameraEndpoint, discovered: Bool, quick: Bool = false) async -> WiFiAttempt {
        let transport = PTPIPTransport(host: host.host, port: host.port, identity: identity, reachable: Self.testLinkCheck)
        let pairing = isPairing
        connectingTransport = transport
        defer { if connectingTransport === transport { connectingTransport = nil } }
        if pairing { wifiStatus = "Камера найдена (\(host)) — подтвердите сопряжение на ней" }
        let started = Date()
        note("подключаюсь к \(host)\(quick ? " (восстановление)" : "")…")
        do {
            // при сопряжении камера ждёт подтверждения на экране — ответа ждём дольше
            let name = try await transport.connect(connectTimeout: quick ? .seconds(2) : .seconds(5),
                                                   initTimeout: pairing ? .seconds(60) : quick ? .seconds(3) : .seconds(5))
            // камера приняла программу — значит, сопряжены, даже если связь уже не нужна; прежние версии читают
            // свою память о камере — пишем и её
            Prefs.wifiHost = host.host
            Prefs.wifiCameraName = name
            note(String(format: "подключился к \(host) (\(name)) за %.0f мс", Date().timeIntervalSince(started) * 1000))
            // пока ждали, связь стала не нужна: подключили кабель, выключили Wi-Fi, отменили сопряжение
            guard wifiAttemptWanted, pairing == isPairing else {
                await transport.close()
                return .dropped
            }
            // камера на кабеле не в PC Remote — та попытка не нужна, закрываем её до сеанса по Wi-Fi
            if phase == .notPCRemote { teardown() }
            wifiDirect = PTPIP.isCameraAccessPoint(host.host)
            currentLink = .wifi(host)
            currentCameraID = nil
            choosing = nil
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
                wifiStatus = "Камера найдена (\(host)), но не в режиме Pairing — включите его на камере"
            } else {
                // камера нас больше не знает (её сбросили или сопрягали заново) — перестаём стучаться
                roster.unpair(host)
                storeRoster()
                if Prefs.wifiHost == host.host { Prefs.wifiCameraName = nil }
                wifiStatus = "В сети есть камера (\(host)), но программа с ней не сопряжена — «Сопрячь» у неё в списке камер"
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
    private func wifiLost(_ loss: LinkLoss?, transport: PTPIPTransport, host: CameraEndpoint) {
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

    private func scheduleReconnect(_ host: CameraEndpoint) {
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
        let lost = currentCameraID.flatMap(roster.camera)
        teardown()
        // в ленте есть камеры на связи — главной становится верхняя; пропавшая, если она в ленте, — плиткой «нет связи»
        if let side = topConnectedSide {
            if let lost, lost.inStrip, let endpoint = lost.endpoint { _ = sideCamera(lost.id, endpoint) }
            promote(side)
            return
        }
        let seconds = wifiPolicy.sessionLost(now: Date())
        connectionMessage = "Связь по Wi-Fi пропала. Повтор через \(seconds) с…"
        connectAttachedIfAny()
    }

    /// Сеанс оборвался, но камеру ждём обратно: закрыть всё, что связано с соединением, а картинку, настройки,
    /// таймер записи и ленту снимков оставить на экране.
    private func suspendSession() {
        releaseLens()
        info.suspend()
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
        endPreviewWithoutCamera()
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

    /// Сеанс по Wi-Fi готов: качество — выбранное вручную, в авто — высокое; дальше авто ведёт политика по каналу.
    private func startQuality(_ session: CameraSession) {
        guard transportKind == .wifi, let current = properties[SonyProp.liveViewImageQuality]?.current.intValue else { return }
        let wanted = previewQualityMode.fixed ?? .high
        if current != wanted.rawValue { writeQuality(wanted, session) }
    }

    /// Выбор в подсказке к счётчику кадров или в настройках: камера переходит на ближайшем шаге (раз в полсекунды).
    func setPreviewQuality(_ mode: PreviewQualityMode) {
        guard mode != previewQualityMode else { return }
        previewQualityMode = mode
        Prefs.previewQuality = mode
        forcedQualityAt = nil
        note("качество превью: \(mode.rawValue)")
    }

    /// Раз в полсекунды: снизить качество превью, если канал его не тянет, или вернуть, когда тянет снова.
    private func adaptQuality() {
        guard transportKind == .wifi, phase == .ready, reconnectingSince == nil, let session, !qualityWriting,
              let current = previewQuality else { return }
        let wanted: LiveViewQuality?
        if let fixed = previewQualityMode.fixed {
            // выбрано вручную: держим его; камера переходит не сразу — просим не чаще раза в 5 с
            if current != fixed, forcedQualityAt.map({ Date().timeIntervalSince($0) > 5 }) ?? true {
                forcedQualityAt = Date()
                wanted = fixed
            } else {
                wanted = nil
            }
        } else {
            // картинка увеличена на экране — чёткость важнее плавности: высокое держим дольше
            wanted = qualityPolicy.decide(now: Date(), outage: linkOutageSince != nil, sharp: viewZoomFactor >= 1.5)
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
        // какие команды знает камера — из её ответа на рукопожатие
        tasks.append(Task { [weak self] in
            let codes = await session.controlCodes
            self?.controlCodes = codes
        })
        // события новых камер: смена объектива (метки фокуса прежнего ни к чему), список подписей изменился, фокус у цели
        tasks.append(Task { [weak self] in
            for await event in await session.events { self?.handle(event, session: session) }
        })
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
        let assistInput = self.assistInput
        let transform = pictureTransform
        tasks.append(Task.detached(priority: .userInitiated) { [weak self] in
            for await sample in frames {
                guard let decoded = Self.decode(sample.jpeg) else { continue }
                // одно состояние помощи на весь кадр: картинка, уровни, белый и помощь видят одни и те же настройки
                let input = assistInput.current
                let lut = input.lut
                // зеркало и LUT — один раз, сразу после декодирования; ни того, ни другого — кадр остаётся как есть
                let picture = transform.apply(decoded, orientation: input.assist.orientation, lut: lut,
                                              needPlain: lut != nil && !input.assist.lutScopes)
                // помощь — по картинке с LUT или, если она «без LUT», по сигналу камеры, только повёрнутому
                let analysed = picture.plain ?? picture.display
                let levels = Self.histogramLevels(of: analysed)
                // помощь в кадре и волновая форма — по тому же кадру и вместе с ним: подсветка не отстаёт от картинки
                let tools = FrameTools.analyze(analysed, levels: levels, input: input)
                guard !Task.isCancelled else { break }
                await self?.show(picture.display, sample: sample, levels: levels, tools: tools)
            }
        })
    }

    private func apply(_ snapshot: [UInt16: SonyProperty]) {
        properties = snapshot
        lastSnapshotAt = Date()
        let slot = SettingCatalog.resolved(.creativeStyle, available: Set(snapshot.keys))
        if slot != creativeSlot { creativeSlot = slot }
        // 0xE086 стал 1 (или перестал быть 1) — данные объектива появились (или пропали)
        if let session { info.propertiesChanged(snapshot, session: session) }
        // вышли из ручного фокуса (Near/Far закрылся): перевод к метке кончился, фокусом занимается камера
        if focusPullTask != nil, !focusDriveAvailable { cancelFocusPull(stopCamera: false) }
        followPresetMode()
        assistInput.setVideo(mode != .photo)
        // «Авто» в LUT идёт за профилем изображения главной камеры: со следующего кадра
        assistInput.setCamera(pictureProfile: snapshot[SonyProp.pictureProfile]?.current.intValue,
                              gamma: snapshot[SonyProp.ppGamma]?.current.intValue,
                              colorMode: snapshot[SonyProp.ppColorMode]?.current.intValue)
        let recording = snapshot[SonyProp.movieRecordingState]?.current.intValue == 1
        if recording != (recordingStartedAt != nil) { note(recording ? "запись: камера пишет" : "запись: камера не пишет") }
        if recording, recordingStartedAt == nil { recordingStartedAt = Date() }
        if !recording { recordingStartedAt = nil }
        isRecording = recording
        finishRecordingRequest(recordingToggle.observe(recording: recording, at: Date()))
        for (code, target) in targets where snapshot[code]?.current.intValue == target {
            clearTarget(code)
        }
    }

    private func show(_ image: CGImage, sample: LiveViewSample, levels: [Int], tools: FrameTools.Result) {
        frame = image
        if focusFrames != sample.focusFrames { focusFrames = sample.focusFrames }
        assistOverlay = tools.overlay
        waveform = tools.waveform
        if let pixels = tools.vectorscope {
            vectorscope = VectorscopeTrace(pixels: pixels, zoom: tools.vectorscopeZoom, peak: tools.vectorscopePeak,
                                           targets: tools.vectorscopeTargets)
        } else if vectorscope != nil {
            vectorscope = nil
        }
        if probeReading != tools.probe { probeReading = tools.probe }
        meanLuma = tools.meanLuma
        lumaSpread = tools.lumaSpread
        if !hasFrame { hasFrame = true }
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
        // в видео белое в live view — 234, в фото — 255, у картинки с LUT — 255: шкала гистограммы — до белого, того же, что у
        // помощи (его определил поток кадров)
        let white = tools.white
        whiteLevel = white
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
    /// closing: false — сеанс уходит в ленту: всё, что связано с главным экраном, сбрасывается, а соединение — нет.
    private func teardown(closing: Bool = true) {
        releaseLens()
        info.reset()
        tasks.forEach { $0.cancel() }
        tasks.removeAll()
        reconnectTask?.cancel()
        reconnectTask = nil
        reconnectingSince = nil
        let close = closeTransport
        // качество превью снижали из-за слабого сигнала — камеру оставляем на высоком
        let restore = transportKind == .wifi && previewQuality == .low ? session : nil
        if closing {
            Task {
                if let restore {
                    try? await restore.setQuietly(SonyProp.liveViewImageQuality, to: LiveViewQuality.high.rawValue, type: PTPType.uint8)
                }
                await close?()
            }
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
        currentLink = nil
        currentCameraID = nil
        currentUSBSerial = nil
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
        hasFrame = false
        recordCallInFlight = false
        lastJPEG = nil
        lastFrameAt = nil
        frameTimes = []
        frameGaps = []
        histogram = []
        histogramStats = nil
        assistOverlay = nil
        waveform = nil
        vectorscope = nil
        probeReading = nil
        whiteLevel = nil
        meanLuma = nil
        lumaSpread = nil
        controlCodes = []
        focusFrames = []
        touchMark = nil
        zoomDirection = 0
        focusUnits = 0
        fps = 0
        shownFPS = 0
        frameGapMs = 0
        isFrameStale = false
        properties = [:]
        creativeSlot = .creativeStyle
        // камеры нет — «Авто» не по чему выбирать
        assistInput.setCamera(pictureProfile: nil, gamma: nil, colorMode: nil)
        deviceInfo = nil
        isRecording = false
        recordingStartedAt = nil
        isApplyingPreset = false
        endPreviewWithoutCamera()
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

    /// Инструменты выключили — растр по старому кадру не показываем, даже если новых кадров нет.
    func clearAssistOverlay() {
        assistOverlay = nil
    }

    // MARK: - Фокус касанием, зум, шкала фокуса

    private func value(_ code: UInt16) -> Int64? { properties[code]?.current.intValue }

    /// Как сейчас ставится фокус касанием; nil — никак (ручной фокус, камера не умеет).
    var touchFocusMethod: TouchFocus.Method? {
        TouchFocus.method(focusMode: value(SonyProp.focusMode), focusArea: value(SonyProp.focusArea),
                          remoteTouchEnabled: value(SonyProp.remoteTouchEnabled), touchFunction: value(SonyProp.touchFunction),
                          controls: controlCodes)
    }

    /// Камера вообще умеет фокус касанием (кнопка есть, даже если сейчас ручной фокус).
    var touchFocusSupported: Bool {
        controlCodes.contains(SonyControl.afAreaPosition) || controlCodes.contains(SonyControl.remoteTouch)
    }

    /// Слежение от касания можно снять.
    var touchCancelAvailable: Bool {
        value(SonyProp.remoteTouchCancelEnabled) == 1 && controlCodes.contains(SonyControl.remoteTouchCancel)
    }

    var zoomAvailable: Bool { PowerZoom.isAvailable(zoomEnabled: value(SonyProp.zoomEnabled), controls: controlCodes) }

    /// Шкала зума; nil — камера её не отдаёт.
    var zoomBar: PowerZoom.Bar? {
        guard let raw = value(SonyProp.zoomBar), PowerZoom.Bar(raw).boxes > 0 else { return nil }
        return PowerZoom.Bar(raw)
    }

    /// Масштаб зума подписью («×1,2»); на ×1,0 — nil: подпись не нужна.
    var zoomScaleLabel: String? {
        guard let scale = value(SonyProp.zoomScale), scale > 1000 else { return nil }
        return PowerZoom.label(scale: scale)
    }

    var focusDriveAvailable: Bool {
        FocusDrive.isAvailable(nearFarEnabled: value(SonyProp.nearFarEnabled), controls: controlCodes)
    }

    /// Где фокус, 0…100 (0 — ближе всего); nil — камера не отдаёт.
    var focalPosition: Int? {
        guard let property = properties[SonyProp.focalPosition], property.enabled != 0,
              let v = property.current.intValue else { return nil }
        return Int(v)
    }

    /// Что сделает касание кадра сейчас — строка «Сейчас» в подсказке к фокусу касанием.
    var touchFocusNow: String {
        TouchFocus.helpNow(method: touchFocusMethod, area: ValueFormatter.label(.focusArea, property(.focusArea)),
                           touchFunction: value(SonyProp.touchFunction), focusMode: value(SonyProp.focusMode),
                           zoomScale: value(SonyProp.zoomScale))
    }

    /// Почему фокус касанием сейчас не ставится — для подсказки к кнопке и строки состояния.
    var touchRefusal: String {
        TouchFocus.refusal(focusMode: value(SonyProp.focusMode), zoomScale: value(SonyProp.zoomScale))
    }

    /// Фокус в точку кадра (доли 0…1, каким его видно на экране): точка AF или касание экрана камеры. false — сейчас нельзя.
    /// Точка экранная — с зеркалом и переворотом картинки она не та, что у камеры: в долях кадра камеры её переводит
    /// `orientation.toCamera`, здесь, в одном месте, — и для щелчка по кадру, и для действия API `touchFocus`. Дальше всё —
    /// в долях камеры: команда, отметка касания (`touchMark`) и рамки фокуса.
    @discardableResult
    func touchFocus(at point: CGPoint) -> Bool {
        guard phase == .ready, let session else { return false }
        guard let method = touchFocusMethod else {
            showStatus(touchRefusal)
            return false
        }
        let point = orientation.toCamera(point)
        let mark = Date()
        touchMark = (point, mark)
        // отметка гаснет за секунду — потом её нет, и слой с рамками не перерисовывается 30 раз в секунду
        Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(1100))
            if self?.touchMark?.at == mark { self?.touchMark = nil }
        }
        let code = method == .areaPosition ? SonyControl.afAreaPosition : SonyControl.remoteTouch
        let encoded = TouchFocus.encode(x: Double(point.x), y: Double(point.y))
        note("фокус касанием: \(method == .areaPosition ? "точка AF" : "касание экрана камеры") (\(encoded >> 16), \(encoded & 0xFFFF))")
        lensCommand { [weak self] in
            let code = try? await session.control(code, value: encoded, type: PTPType.uint32)
            if code != PTPResponseCode.ok { await self?.showStatus("Камера не поставила фокус в эту точку") }
        }
        return true
    }

    /// Команды объектива — по одной и по порядку.
    private func lensCommand(_ body: @escaping @Sendable () async -> Void) {
        let previous = lensTail
        lensTail = Task {
            await previous?.value
            await body()
        }
    }

    /// Снять слежение, начатое касанием.
    func cancelTouch() {
        guard phase == .ready, let session, touchCancelAvailable else { return }
        touchMark = nil
        lensCommand { _ = try? await session.pressButton(SonyControl.remoteTouchCancel) }
    }

    /// Зум: 1 — к теле, −1 — к широкому, пока не придёт 0. Повтор того же направления ничего не шлёт. Стоп без сеанса
    /// (связь пропала, пока держали «T») только отпускает кнопку: слать некому.
    func zoom(_ direction: Int) {
        let direction = max(-1, min(1, direction))
        guard direction != zoomDirection else { return }
        guard direction == 0 || (session != nil && phase == .ready && zoomAvailable) else { return }
        zoomDirection = direction
        guard let session else { return }
        note("зум: " + (direction > 0 ? "к теле" : direction < 0 ? "к широкому" : "стоп"))
        lensCommand { _ = try? await session.control(SonyControl.zoomOperation, value: Int64(direction), type: PTPType.int8) }
    }

    /// Сеанс уходит (обрыв, другая камера, лента): зум — стоп этому сеансу, кнопка отпущена, очередь шагов фокуса пуста,
    /// отметка касания снята. Иначе после переподключения «T» горела бы, а шаги прошлого сеанса ушли бы новому.
    private func releaseLens() {
        if zoomDirection != 0, let session {
            lensCommand { _ = try? await session.control(SonyControl.zoomOperation, value: 0, type: PTPType.int8) }
        }
        zoomDirection = 0
        focusUnits = 0
        focusGeneration += 1
        focusDriving = false
        touchMark = nil
        // перевод фокуса к метке — тоже команды этого сеанса; подпись прошлого перевода новой камере не принадлежит
        cancelFocusPull(stopCamera: false)
        focusPullCaptionTask?.cancel()
        focusPullCaptionTask = nil
        focusPullCaption = nil
    }

    /// Перефокусировка на столько единиц Near/Far (отрицательное — ближе): по одной команде за раз, остальное копится.
    func focusStep(_ units: Int) {
        guard units != 0, phase == .ready, focusDriveAvailable, let session else { return }
        // ручное движение — перевод к метке кончился; у абсолютного фокуса камере перед ним велено встать там, где стоит
        let stopped = cancelFocusPull(stopCamera: true)
        focusUnits += units
        guard !focusDriving else { return }
        focusDriving = true
        let generation = focusGeneration
        Task { [weak self] in
            // «встать» уходит раньше первого шага: иначе камера вернула бы фокус туда, где он был до шага
            await stopped?.value
            while let self, self.focusGeneration == generation, self.focusUnits != 0 {
                guard let next = FocusDrive.commands(units: self.focusUnits).first else { break }
                self.focusUnits -= Int(next)
                _ = try? await session.control(SonyControl.nearFar, value: next, type: PTPType.int16)
            }
            // сеанс ушёл, пока шла команда, — флаг уже сброшен, и новый цикл мог начаться: его не трогаем
            if let self, self.focusGeneration == generation { self.focusDriving = false }
        }
    }

    // MARK: - Метки фокуса A и B

    /// Как у этой камеры переводить фокус между метками: абсолютно (0xE042, положение 0xE043) или шагами Near/Far (положение
    /// 0xD24C). nil — положения фокуса камера не отдаёт, и метку запомнить нечем.
    var focusPullMode: FocusPull.Mode? { FocusPosition.mode(properties) }

    /// Где фокус сейчас — в единицах режима перевода (0…65535 или 0…100).
    var focusPosition: Double? { focusPullMode.flatMap { FocusPosition.current(properties, mode: $0) } }

    /// Метки есть: шкала фокуса на экране (ручной фокус) и камера отдаёт положение.
    var focusMarksAvailable: Bool { focusDriveAvailable && focusPullMode != nil }

    /// Метки и время перевода камеры на связи — в единицах её режима; пустые, пока камеры нет.
    var focusMarks: FocusMarks {
        guard let camera = currentCameraID else { return FocusMarks() }
        guard let mode = focusPullMode else { return FocusMarks(duration: focusMarkBook.duration(for: camera)) }
        return focusMarkBook.marks(for: camera, mode: mode)
    }

    /// Где метка на линейке, доля 0…1; nil — метка пуста.
    func focusMarkFraction(_ mark: Character) -> Double? {
        guard let mode = focusPullMode, let position = focusMarks[mark] else { return nil }
        return FocusPosition.fraction(of: position, mode: mode)
    }

    /// Касание кнопки метки: пуста — запомнить, где фокус сейчас; задана — перевести фокус к ней; фокус едет к ней — стоп.
    func focusMarkTapped(_ mark: Character) {
        guard focusMarksAvailable else { return }
        switch FocusMarkBook.tap(on: mark, marks: focusMarks, moving: focusPullMark) {
        case .save: focusMarkSave(mark)
        case .go: focusMarkGo(mark)
        case .stop: focusPullStop()
        }
    }

    /// «Запомнить здесь»: положение фокуса сейчас — в метку. Фокус едет к этой метке — перевод кончается: цель сменилась.
    /// false — запомнить нечего (камера не готова, положения нет).
    @discardableResult
    func focusMarkSave(_ mark: Character) -> Bool {
        // пока камера не готова (тихое переподключение), свойства прежние: положение устарело и метка легла бы не туда
        guard phase == .ready, let camera = currentCameraID, let mode = focusPullMode, let position = focusPosition,
              FocusMarkBook.letters.contains(mark) else { return false }
        if focusPullMark == mark { focusPullStop() }
        focusMarkBook.save(mark, at: position, for: camera, mode: mode)
        note("метки фокуса: \(mark) = \(Int(position.rounded()))")
        return true
    }

    /// «Стереть»; перевод к стёртой метке кончается.
    func focusMarkClear(_ mark: Character) {
        guard let camera = currentCameraID, let mode = focusPullMode else { return }
        if focusPullMark == mark { focusPullStop() }
        focusMarkBook.clear(mark, for: camera, mode: mode)
    }

    /// Время перевода по кругу: 0 → 1 → 2 → 4 → 8 → 0 секунд. Идущий перевод едет с прежним.
    func focusPullCycleTime() {
        guard let camera = currentCameraID else { return }
        focusMarkBook.cycleDuration(for: camera)
    }

    /// Время перевода из списка (0, 1, 2, 4, 8); false — другое число.
    @discardableResult
    func setFocusPullTime(_ seconds: Double) -> Bool {
        guard let camera = currentCameraID else { return false }
        return focusMarkBook.setDuration(seconds, for: camera)
    }

    /// Остановить идущий перевод. Шаги Near/Far просто прекращаются, а абсолютному фокусу камера дойдёт до последней
    /// записанной цели сама, поэтому ей велено встать там, где фокус стоит.
    func focusPullStop() {
        cancelFocusPull(stopCamera: true)
    }

    /// Перевести фокус к метке за время `focusMarks.duration`. Идущий перевод (к другой метке) уступает новому. false — нельзя:
    /// не ручной фокус, метка пуста или положения нет.
    @discardableResult
    func focusMarkGo(_ mark: Character) -> Bool {
        guard phase == .ready, focusDriveAvailable, let session, let mode = focusPullMode, let start = focusPosition,
              let target = focusMarks[mark] else { return false }
        // перевод вытесняет ручное движение, как ручной шаг — перевод: шаги шкалы, которые ещё не ушли, не доедут
        focusUnits = 0
        // фокус уже у метки: ехать некуда, и ждать для этого всё выбранное время (до 8 с) незачем. Если он при этом ехал к
        // другой метке и проезжал эту, абсолютному фокусу велено встать здесь
        let there = abs(target - start) <= FocusPosition.tolerance(mode)
        cancelFocusPull(stopCamera: there)
        focusPullCaptionTask?.cancel()
        focusPullCaption = nil
        if there {
            note("метки фокуса: \(mark) — фокус уже там")
            endFocusPull(.reached, mark: mark, seconds: 0)
            return true
        }
        let duration = focusMarks.duration
        focusPullMark = mark
        focusPullProgress = 0
        note("метки фокуса: перевод к \(mark), \(Int(start.rounded())) → \(Int(target.rounded())) за \(Int(duration)) с, "
             + (mode == .absolute ? "абсолютный фокус" : "шаги Near/Far"))
        let generation = focusGeneration
        focusPullTask = Task { [weak self] in
            await self?.runFocusPull(session: session, mode: mode, mark: mark, from: start, to: target, duration: duration,
                                     generation: generation)
        }
        return true
    }

    /// Цикл перевода. Тик — 100 мс, считая от начала прошлого: на каждом `FocusPull.next` получает положение фокуса и время, а то,
    /// что он скажет, уходит камере. Команда с ответом и свежим положением может занять тик и больше — следующий тик тогда
    /// наступает сразу, а не через такт. Задача одна и на главном акторе: автомат никто не видит, кроме неё.
    private func runFocusPull(session: CameraSession, mode: FocusPull.Mode, mark: Character, from start: Double, to target: Double,
                              duration: Double, generation: Int) async {
        var pull = FocusPull(mode: mode, from: start, to: target, duration: duration)
        let clock = ContinuousClock()
        let began = clock.now
        var position = start
        while true {
            let tickStart = clock.now
            guard !Task.isCancelled, focusGeneration == generation else { return }
            // вышли из ручного фокуса, пока ждали тик: дальше фокусом занимается камера
            guard focusDriveAvailable else {
                cancelFocusPull(stopCamera: false)
                return
            }
            // у абсолютного фокуса положение приходит событиями камеры; если свойств давно нет — спросить самим
            if mode == .absolute, Date().timeIntervalSince(lastSnapshotAt) > 0.25 {
                await refreshWithin(session, .milliseconds(300))
            }
            if let fresh = FocusPosition.current(await session.properties, mode: mode) { position = fresh }
            guard !Task.isCancelled, focusGeneration == generation else { return }
            let elapsed = Self.seconds(began.duration(to: clock.now))
            let action = pull.next(elapsed: elapsed, position: position)
            focusPullProgress = pull.progress
            do {
                switch action {
                case let .finish(outcome):
                    endFocusPull(outcome, mark: mark, seconds: elapsed)
                    return
                case .wait:
                    break
                case let .write(value):
                    try await session.setQuietly(SonyProp.focusPositionSetting, to: Int64(value), type: PTPType.uint16)
                case let .nearFar(steps):
                    try await session.control(SonyControl.nearFar, value: steps, type: PTPType.int16)
                    // свежее положение — не дольше 300 мс; не пришло, автомат сам подождёт и посчитает по сроку
                    await refreshWithin(session, .milliseconds(300))
                }
            } catch TransportError.disconnected {
                // связь оборвалась на этой команде; если перевод к этому времени уже заменили новым, чужой не трогаем
                if !Task.isCancelled, focusGeneration == generation { cancelFocusPull(stopCamera: false) }
                return
            } catch {
                // камера отказала в этой команде — перевод идёт дальше, без движения его кончит срок
            }
            try? await Task.sleep(until: max(tickStart + .milliseconds(100), clock.now), clock: clock)
        }
    }

    /// Перечитать свойства камеры и подождать не дольше `limit`. Запрос не отменяется, если время вышло: отмена посреди обмена
    /// сбила бы его с камерой — просто не ждём ответа.
    private func refreshWithin(_ session: CameraSession, _ limit: Duration) async {
        await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
            let first = OSAllocatedUnfairLock(initialState: true)
            let finish: @Sendable () -> Void = {
                if first.withLock({ isFirst -> Bool in defer { isFirst = false }; return isFirst }) { done.resume() }
            }
            Task {
                try? await session.refresh()
                finish()
            }
            Task {
                try? await Task.sleep(for: limit)
                finish()
            }
        }
    }

    /// Перевод кончился сам: подпись на 3 с, кнопка мигает. Остановленный снаружи (`cancelled`) подписи не получает.
    private func endFocusPull(_ outcome: FocusPull.Outcome, mark: Character, seconds: Double) {
        focusPullTask = nil
        focusPullMark = nil
        focusPullProgress = nil
        guard let caption = FocusPull.caption(outcome, mark: String(mark)) else { return }
        focusPullCaption = caption
        focusPullEnds[mark, default: 0] += 1
        note("метки фокуса: перевод к \(mark) кончился за " + String(format: "%.1f", seconds) + " с — \(caption)")
        focusPullCaptionTask?.cancel()
        focusPullCaptionTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { self?.focusPullCaption = nil }
        }
    }

    /// Прекратить идущий перевод — без подписи. `stopCamera`: у абсолютного фокуса камера едет к последней записанной цели и
    /// сама не остановится — ей пишется положение, в котором фокус стоит сейчас (после очередного чтения свойств).
    /// Отдаёт задачу этой записи, если она есть, — ручной шаг ждёт её, чтобы не получить фокус назад.
    @discardableResult
    private func cancelFocusPull(stopCamera: Bool) -> Task<Void, Never>? {
        guard let task = focusPullTask else { return nil }
        task.cancel()
        focusPullTask = nil
        focusPullMark = nil
        focusPullProgress = nil
        note("метки фокуса: перевод остановлен")
        guard stopCamera, focusPullMode == .absolute, let session else { return nil }
        lensCommand {
            try? await session.refresh()
            guard let here = FocusPosition.current(await session.properties, mode: .absolute) else { return }
            try? await session.setQuietly(SonyProp.focusPositionSetting, to: Int64(here), type: PTPType.uint16)
        }
        return lensTail
    }

    /// Камера сообщила о смене объектива (0xC21B): метки стёрты, перевод к ним кончился.
    private func lensChanged() {
        guard let camera = currentCameraID else { return }
        cancelFocusPull(stopCamera: false)
        focusMarkBook.lensChanged(for: camera)
        note("объектив сменился (0xC21B): метки фокуса стёрты")
    }

    /// Событие новых камер Sony: смена объектива стирает метки фокуса; подписи и данные объектива перечитываются.
    private func handle(_ event: CameraEvent, session: CameraSession) {
        if case .lensChanged = event { lensChanged() }
        info.handle(event, session: session)
    }

    private static func seconds(_ duration: Duration) -> Double {
        Double(duration.components.seconds) + Double(duration.components.attoseconds) / 1e18
    }

    /// Волновую форму выключили — и её тоже.
    func clearWaveform() {
        waveform = nil
    }

    /// Эталон для наложения есть или будет: в памяти, иначе из файла прошлого раза, иначе — текущий кадр.
    func ensureReference() {
        guard reference == nil else { return }
        if let saved = ReferenceFrame.load() {
            reference = saved
        } else {
            takeReferenceFromFrame()
        }
    }

    /// Эталон — текущий кадр превью.
    @discardableResult
    func takeReferenceFromFrame() -> Bool {
        guard let jpeg = lastJPEG, let image = Self.decode(jpeg) else { return false }
        let source = "кадр \(Self.clock.string(from: Date()))"
        reference = ReferenceFrame(image: image, source: source)
        ReferenceFrame.save(jpeg: jpeg, source: source)
        showStatus("Эталон для наложения — текущий кадр")
        return true
    }

    /// Эталон — снимок из галереи: JPEG (у RAW+JPEG — он), уменьшенный до 2048 точек по длинной стороне.
    func takeReference(from shot: Shot) {
        let urls = shot.urls
        Task { [weak self] in
            guard let made = await ReferenceFrame.make(from: urls) else {
                self?.showStatus("Этот снимок не открылся как эталон")
                return
            }
            self?.reference = made
            self?.showStatus("Эталон для наложения — \(made.source)")
        }
    }

    /// Эталон — картинка из файла (окно выбора или перетащили на окно): любой формат, что понимает macOS, уменьшенная до
    /// 2048 точек с учётом поворота из EXIF и сохранённая как эталон — исходный файл потом не нужен.
    func takeReference(fromFile url: URL) {
        Task { [weak self] in
            guard let made = await ReferenceFrame.make(from: [url]) else {
                self?.showStatus("«\(url.lastPathComponent)» не открылся как картинка")
                return
            }
            self?.reference = made
            self?.showStatus("Эталон для наложения — \(made.source)")
        }
    }

    private static let clock: DateFormatter = {
        let f = DateFormatter()
        f.dateFormat = "HH:mm:ss"
        return f
    }()

    /// Последний кадр с камеры, байт.
    var lastFrameBytes: Int? { recentFrames.last?.bytes }

    nonisolated static func decode(_ jpeg: Data) -> CGImage? {
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

/// «Хватит» для пресета, который ещё пишется: начатую запись камера доделает, следующие не начнутся. Не отмена задачи:
/// отмена оборвала бы ожидание подтверждения, и «как было» сравнивалось бы с устаревшим значением камеры.
final class PresetStop: Sendable {
    private let stopped = OSAllocatedUnfairLock(initialState: false)

    func stop() { stopped.withLock { $0 = true } }

    var isStopped: Bool { stopped.withLock { $0 } }
}
