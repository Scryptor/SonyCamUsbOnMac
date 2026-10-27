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
        let options = assistSelected.options.map(\.value)
        guard let i = options.firstIndex(of: assist.value(assistSelected)) else { return }
        setAssist(assistSelected, options[min(max(i + offset, 0), options.count - 1)])
    }

    func turnOffAssist() {
        var next = assist
        next.turnOffAll()
        saveAssist(next)
    }

    private func saveAssist(_ next: MonitorAssist) {
        assist = next
        if let data = try? JSONEncoder().encode(next) { defaults.set(data, forKey: "monitorAssist") }
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
