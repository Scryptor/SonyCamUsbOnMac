import Foundation
import Observation
import ZVCore

/// Что сейчас открыто на экране. Меняется и кнопками, и API автоматизации.
@MainActor
@Observable
final class ScreenState {
    var fnOpen = false
    var allSettingsOpen = false
    var selected: SettingID = .shutter
    var group: SettingGroup = .exposure
    var editing: SettingID?
    private(set) var histogramOn: Bool
    /// Строка состояния справа сверху видна всегда, когда видны элементы. Закреплённая — кнопка рядом с гистограммой
    /// горит, так и по умолчанию — остаётся на экране и когда элементы спрятаны: клавишей H или сами.
    private(set) var statusBarPinned: Bool
    /// Скрыто клавишей H — возвращается только ей же.
    private(set) var manualHidden = false
    /// Скрыто само после 3 с без движения мыши — возвращается при любом движении.
    private(set) var autoHidden = false

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var lastActivity = Date()

    /// Подсказка из строки состояния: о подключении или о кадрах.
    enum PillInfo: String {
        case connection, frames, slowMotion
    }

    var pillInfo: PillInfo?

    /// Вкладка панели отладки.
    enum DebugTab: String, CaseIterable, Identifiable {
        case link, preview, camera, properties, log

        var id: String { rawValue }

        var title: String {
            switch self {
            case .link: return "Связь"
            case .preview: return "Превью"
            case .camera: return "Камера"
            case .properties: return "Свойства"
            case .log: return "Журнал"
            }
        }
    }

    /// Панель отладки поверх экрана (⌥⌘D) и её вкладка.
    var debugOpen = false
    var debugTab: DebugTab = .link

    /// Стоп-кадр, к миниатюре которого сейчас летит кадр: миниатюра пока спрятана.
    var flyingShot: UUID?

    /// Где гистограмма: доли свободного места; nil — на месте по умолчанию, слева внизу над лентой снимков.
    private(set) var histogramPlacement: FloatingPlacement?

    /// Фокус касанием: горит — щелчок по кадру ставит туда фокус, пока не включена пипетка (она отбирает щелчок; кнопка при
    /// этом остаётся включённой, но приглушена). Рамки фокуса камеры видны и без него.
    private(set) var touchFocusOn: Bool

    /// Волновая форма: включена ли, какая (яркость или RGB-парад) и где; nil — на месте по умолчанию, справа внизу.
    private(set) var waveformOn: Bool
    private(set) var waveformMode: Waveform.Mode
    private(set) var waveformPlacement: FloatingPlacement?

    /// Где вектороскоп: доли свободного места; nil — на месте по умолчанию, слева вверху под верхней панелью. Включён ли
    /// он и в каком масштабе — в `assist`.
    private(set) var vectorscopePlacement: FloatingPlacement?

    /// Где стоит пипетка: доли всего кадра, каким он виден на экране (зеркальный или перевёрнутый — как есть, перевода в
    /// координаты камеры не нужно; увеличение на доли не влияет). При первом включении — в центре. Помнится, пока работает
    /// программа, и в настройки не пишется; точку видит и поток кадров (`AssistInput`): пипетка считается по ней.
    private(set) var probePoint = CGPoint(x: 0.5, y: 0.5)

    /// Свои LUT: папка в Application Support. Куб выбранного файла уходит потоку кадров вместе с остальной помощью.
    let luts = LUTStore()
    /// Чей куб сейчас у потока кадров (или грузится) и при какой версии папки; nil — своего LUT у потока нет.
    @ObservationIgnored private var fileLUTKey: (name: String, revision: Int)?
    @ObservationIgnored private var fileLUTLoad: Task<Void, Never>?

    /// Наложение спрятано щелчком по миниатюре на панели наложения (или клавишей O): эталон не рисуется, а наложение
    /// остаётся включённым. Не настройка — после включения наложения и нового эталона эталон снова виден.
    private(set) var overlayHidden = false
    /// Где панель наложения: доли свободного места; nil — на месте по умолчанию, справа вверху.
    private(set) var overlayPlacement: FloatingPlacement?

    /// Где правая колонка кнопок (зум, режим, запись): гистограмма и волновая форма на неё не ложатся — встают левее.
    /// Колонку спрятали вместе с элементами — место остаётся прежним: панели не прыгают туда и обратно.
    var rightColumnRect: CGRect?

    /// Где лента камер слева (камер на связи две и больше): вектороскоп на неё не ложится — встаёт правее. Как и колонка кнопок,
    /// помнится, когда элементы спрятаны: панель не прыгает туда и обратно. Лента пропала — смотрят на `sideCameras`, не на это.
    var stripRect: CGRect?

    /// Открытая галерея: какой снимок на экране; nil — закрыта. По id, а не по номеру: новый снимок её не сбивает.
    var galleryShot: UUID?
    /// Снимок в галерее увеличен: стрелки не листают.
    var galleryZoomed = false

    /// Цифровое увеличение live view — только на экране, камера о нём не знает.
    var zoom: ViewZoom = .identity

    /// Помощь в кадре — инструменты монитора (зебра, false color, пикинг, маркеры); помнится между запусками.
    private(set) var assist: MonitorAssist
    /// Меню «Монитор» внизу — на месте строки значений, как меню Fn, — и выбранная в нём плитка.
    private(set) var assistOpen = false
    var assistSelected: MonitorTool = .zebra

    /// Список пресетов под кнопкой вверху слева.
    var presetMenuOpen = false
    /// Окно «Пресеты»: открыто ли и какой пресет в нём выбран.
    var presetsWindowOpen = false
    var presetsSelected: Int?
    /// Просьбы открыть и закрыть окно пресетов — из API и из списка: окна открывает и закрывает вид.
    var presetsWindowRequest = 0
    var presetsWindowCloseRequest = 0
    /// Окошко «Новый пресет» с полем имени.
    var presetNaming = false
    /// Окошко «Сохранить настройки»: текущий пресет изменён — сохранить в него или новым.
    var presetSaving = false
    /// Новый пресет из окна: поле имени — сразу в фокус.
    var presetsFocusName = false
    /// «Посмотреть» держат: окно пресетов прозрачно — не мешает смотреть на картинку.
    var presetPeeking = false

    /// «☆+»: текущий пресет изменён — спросить, куда сохранять; иначе сразу новый пресет.
    func pressSavePreset(currentModified: Bool) {
        presetMenuOpen = false
        if currentModified { presetSaving = true } else { presetNaming = true }
    }

    /// «Новый пресет…» в окошке «Сохранить настройки»: окошко имени — когда то уже уехало. Второе окошко поверх
    /// закрывающегося первого AppKit показывает ненадёжно.
    func chooseNewPreset() {
        presetSaving = false
        Task { @MainActor [weak self] in
            try? await Task.sleep(for: .milliseconds(350))
            self?.presetNaming = true
        }
    }

    func openPresetsWindow(selecting index: Int?) {
        presetMenuOpen = false
        if let index { presetsSelected = index }
        presetsWindowRequest += 1
    }

    /// Пресет переставили: выбранный в окне остаётся тем же пресетом.
    func followPresetMove(from: Int, to: Int) {
        guard let selected = presetsSelected else { return }
        if selected == from {
            presetsSelected = to
        } else if from < selected, to >= selected {
            presetsSelected = selected - 1
        } else if from > selected, to <= selected {
            presetsSelected = selected + 1
        }
    }

    /// Пресет удалили: выбран тот же, а на месте удалённого — соседний.
    func followPresetDelete(_ index: Int, remaining count: Int) {
        guard let selected = presetsSelected else { return }
        if count == 0 {
            presetsSelected = nil
        } else if selected > index {
            presetsSelected = selected - 1
        } else if selected == index {
            presetsSelected = min(index, count - 1)
        }
    }

    /// Кнопка «Монитор»: открыть меню инструментов или закрыть его.
    func toggleAssistMenu() {
        if assistOpen { closeOverlays() } else { openAssist(nil) }
    }

    func openAssist(_ tool: MonitorTool?) {
        if let tool { assistSelected = tool }
        fnOpen = false
        allSettingsOpen = false
        editing = nil
        pillInfo = nil
        presetMenuOpen = false
        assistOpen = true
    }

    /// Значение инструмента; false — у него такого нет.
    @discardableResult
    func setAssist(_ tool: MonitorTool, _ value: String) -> Bool {
        var next = assist
        guard next.set(tool, value) else { return false }
        saveAssist(next)
        return true
    }

    /// Включить или выключить инструмент: включается тем значением, что было в прошлый раз.
    func toggleAssist(_ tool: MonitorTool) {
        var next = assist
        next.toggle(tool)
        saveAssist(next)
    }

    /// Плитка в меню «Монитор»: первое касание выбирает её, касание выбранной — включает или выключает.
    func tapAssistTile(_ tool: MonitorTool) {
        if assistSelected == tool { toggleAssist(tool) } else { assistSelected = tool }
    }

    /// Соседнее значение выбранного инструмента — стрелки и колесо в меню «Монитор».
    func stepAssist(_ offset: Int) {
        if let value = assist.stepped(assistSelected, by: offset) { setAssist(assistSelected, value) }
    }

    /// Клавиша O и щелчок по миниатюре эталона: наложение выключено — включить, иначе спрятать или показать эталон.
    func toggleOverlayVisible() {
        if assist.overlay == .off { toggleAssist(.overlay) } else { overlayHidden.toggle() }
    }

    /// Взяли новый эталон (кадр, файл, снимок из галереи) — его и показать: наложение включается, спрятанное — видно.
    func showOverlay() {
        if assist.overlay == .off { toggleAssist(.overlay) }
        overlayHidden = false
    }

    /// Ползунок на панели наложения: непрозрачность 0…1, с шагом 5 %.
    func setOverlayOpacity(_ opacity: Double) {
        setAssist(.overlay, MonitorAssist.Overlay.steps(opacity).rawValue)
    }

    func placeOverlay(_ placement: FloatingPlacement) {
        overlayPlacement = placement
        if let data = try? JSONEncoder().encode(placement) { defaults.set(data, forKey: "overlayPlacement") }
    }

    func turnOffAssist() {
        var next = assist
        next.turnOffAll()
        saveAssist(next)
    }

    /// Щелчок по панели вектороскопа: масштаб ×1 ↔ ×2.
    func toggleVectorscopeZoom() {
        var next = assist
        next.vectorscopeZoom = next.vectorscopeZoom == 1 ? 2 : 1
        saveAssist(next)
    }

    func placeVectorscope(_ placement: FloatingPlacement) {
        vectorscopePlacement = placement
        if let data = try? JSONEncoder().encode(placement) { defaults.set(data, forKey: "vectorscopePlacement") }
    }

    /// Переключатель «Помощь — по картинке с LUT».
    func setLUTScopes(_ on: Bool) {
        var next = assist
        next.lutScopes = on
        saveAssist(next)
    }

    /// Выбрать файл из списка своих LUT: режим «Свой» и этот файл.
    func selectLUTFile(_ name: String) {
        var next = assist
        next.selectLUTFile(name)
        saveAssist(next)
    }

    /// Добавить `.cube` в свои LUT и выбрать его. Бросает `LUTLibrary.Failure` — с текстом для строки состояния.
    func importLUT(_ url: URL) throws -> String {
        let name = try luts.importFile(url)
        selectLUTFile(name)
        return name
    }

    /// Удалить свой LUT: выбранный выключается, LUT гаснет.
    func deleteLUT(_ name: String) {
        luts.delete(name)
        var next = assist
        next.lutFileDeleted(name)
        saveAssist(next)
    }

    private func saveAssist(_ next: MonitorAssist) {
        // включили или выключили наложение — «спрятано» больше не действует
        if (next.overlay == .off) != (assist.overlay == .off) { overlayHidden = false }
        assist = next
        if let data = try? JSONEncoder().encode(next) { defaults.set(data, forKey: "monitorAssist") }
        pushToFrames()
    }

    /// Куда передавать инструменты и волновую форму для потока кадров — сразу при изменении, а не через обновление вида:
    /// так поток кадров всегда считает то, что включено сейчас.
    @ObservationIgnored private var frameInput: AssistInput?

    func attach(_ input: AssistInput) {
        frameInput = input
        pushToFrames()
    }

    private func pushToFrames() {
        frameInput?.setAssist(assist)
        frameInput?.setWaveform(waveformOn ? waveformMode : nil)
        frameInput?.setProbe(probePoint)
        pushFileLUT()
    }

    /// Поставить пипетку в точку кадра (доли, каким он виден): щелчок по кадру, пока пипетка включена, и перетаскивание её
    /// метки. Не число — не ставится; за кадр — к его краю.
    func placeProbe(_ point: CGPoint) {
        guard let point = ProbeLayout.clamped(point) else { return }
        probePoint = point
        frameInput?.setProbe(point)
    }

    /// Куб своего LUT — потоку кадров, пока выбрано «Свой» и файл; иначе поток остаётся без него. Куб из памяти ложится сразу, а
    /// большой `.cube` читается в фоне (до секунды): пока его нет, картинка идёт без LUT, а не со старым. Тот же файл при той
    /// же версии папки второй раз не берётся — ни при смене зебры, ни при масштабе вектороскопа.
    private func pushFileLUT() {
        guard let input = frameInput else { return }
        guard assist.lut == .file, let name = assist.lutFile, !name.isEmpty else {
            fileLUTLoad?.cancel()
            fileLUTLoad = nil
            fileLUTKey = nil
            input.setFileLUT(nil)
            return
        }
        let key = (name: name, revision: luts.revision)
        if let current = fileLUTKey, current == key { return }
        fileLUTKey = key
        fileLUTLoad?.cancel()
        fileLUTLoad = nil
        if let cached = luts.cachedLUT(named: name) {
            input.setFileLUT(cached)
            return
        }
        input.setFileLUT(nil)
        fileLUTLoad = Task { [weak self, luts] in
            let lut = await luts.loadInBackground(name)
            guard !Task.isCancelled, let self, let current = self.fileLUTKey, current == key else { return }
            self.frameInput?.setFileLUT(lut)
        }
    }

    func toggleTouchFocus() {
        touchFocusOn.toggle()
        defaults.set(touchFocusOn, forKey: "touchFocusOn")
        // включили — как это работает, пока не отметили «Больше не показывать»; выключили — подсказка не нужна
        touchFocusHelpOpen = touchFocusOn && !touchFocusHelpHidden
    }

    /// Подсказка «Фокус касанием»: при включении кнопки (пока не отмечено «Больше не показывать») и по правому щелчку на ней.
    var touchFocusHelpOpen = false

    /// «Больше не показывать» в подсказке к фокусу касанием.
    var touchFocusHelpHidden: Bool {
        get { access(keyPath: \.touchFocusHelpOpen); return defaults.bool(forKey: "touchFocusHelpHidden") }
        set { withMutation(keyPath: \.touchFocusHelpOpen) { defaults.set(newValue, forKey: "touchFocusHelpHidden") } }
    }

    func toggleWaveform() {
        waveformOn.toggle()
        defaults.set(waveformOn, forKey: "waveformOn")
        pushToFrames()
    }

    func setWaveformMode(_ mode: Waveform.Mode) {
        waveformMode = mode
        defaults.set(mode.rawValue, forKey: "waveformMode")
        pushToFrames()
    }

    func placeWaveform(_ placement: FloatingPlacement) {
        waveformPlacement = placement
        if let data = try? JSONEncoder().encode(placement) { defaults.set(data, forKey: "waveformPlacement") }
    }

    func placeHistogram(_ placement: FloatingPlacement) {
        histogramPlacement = placement
        if let data = try? JSONEncoder().encode(placement) { defaults.set(data, forKey: "histogramPlacement") }
    }

    /// ⌘= / ⌘−: следующая ступень увеличения вокруг того же центра.
    func zoomStep(up: Bool) {
        let factor = ViewZoom.step(from: zoom.factor, up: up)
        zoom = factor <= 1 ? .identity : ViewZoom(factor: factor, center: zoom.center)
    }

    func openGallery(_ shot: UUID) {
        closeOverlays()
        galleryShot = shot
    }

    func toggleDebug() {
        debugOpen.toggle()
    }

    func openDebug(_ tab: DebugTab?) {
        if let tab { debugTab = tab }
        pillInfo = nil
        debugOpen = true
    }

    var controlsHidden: Bool { manualHidden || autoHidden }

    /// Элементы на экране: не спрятаны, или открыто то, с чем они не прячутся, — меню, панель, подсказка.
    var controlsShown: Bool {
        !controlsHidden || fnOpen || assistOpen || allSettingsOpen || pillInfo != nil || presetMenuOpen || presetNaming
            || presetSaving
    }

    func togglePillInfo(_ info: PillInfo) {
        let open = pillInfo != info
        closeOverlays()
        if open { pillInfo = info }
    }

    func noteActivity() {
        lastActivity = Date()
        if autoHidden { autoHidden = false }
    }

    func toggleControls() {
        manualHidden.toggle()
        autoHidden = false
        lastActivity = Date()
    }

    /// busy — открыто меню или просмотр снимка: тогда не прячем. delay — сколько секунд ждать без движения мыши.
    func tickAutoHide(enabled: Bool, delay: TimeInterval, busy: Bool) {
        if busy || !enabled {
            autoHidden = false
            return
        }
        if !autoHidden, Date().timeIntervalSince(lastActivity) > delay { autoHidden = true }
    }

    init() {
        histogramOn = defaults.bool(forKey: "histogramOn")
        statusBarPinned = defaults.object(forKey: "statusBarPinned") as? Bool ?? true
        histogramPlacement = defaults.data(forKey: "histogramPlacement").flatMap { try? JSONDecoder().decode(FloatingPlacement.self, from: $0) }
        assist = defaults.data(forKey: "monitorAssist").flatMap { try? JSONDecoder().decode(MonitorAssist.self, from: $0) } ?? MonitorAssist()
        waveformOn = defaults.bool(forKey: "waveformOn")
        touchFocusOn = defaults.bool(forKey: "touchFocusOn")
        waveformMode = defaults.string(forKey: "waveformMode").flatMap(Waveform.Mode.init(rawValue:)) ?? .luma
        waveformPlacement = defaults.data(forKey: "waveformPlacement").flatMap { try? JSONDecoder().decode(FloatingPlacement.self, from: $0) }
        overlayPlacement = defaults.data(forKey: "overlayPlacement").flatMap { try? JSONDecoder().decode(FloatingPlacement.self, from: $0) }
        vectorscopePlacement = defaults.data(forKey: "vectorscopePlacement").flatMap { try? JSONDecoder().decode(FloatingPlacement.self, from: $0) }
    }

    func toggleFn() {
        if fnOpen { closeOverlays() } else { openFn(nil) }
    }

    func openFn(_ id: SettingID?) {
        if let id { selected = id }
        allSettingsOpen = false
        assistOpen = false
        pillInfo = nil
        presetMenuOpen = false
        fnOpen = true
    }

    func openAllSettings(_ group: SettingGroup?) {
        if let group {
            self.group = group
            editing = nil
        }
        fnOpen = false
        assistOpen = false
        pillInfo = nil
        presetMenuOpen = false
        allSettingsOpen = true
    }

    func closeOverlays() {
        fnOpen = false
        assistOpen = false
        allSettingsOpen = false
        editing = nil
        pillInfo = nil
        presetMenuOpen = false
        touchFocusHelpOpen = false
    }

    func toggleStatusPin() {
        statusBarPinned.toggle()
        defaults.set(statusBarPinned, forKey: "statusBarPinned")
    }

    func toggleHistogram() {
        histogramOn.toggle()
        defaults.set(histogramOn, forKey: "histogramOn")
    }
}
