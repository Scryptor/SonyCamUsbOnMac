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
    var controlsShown: Bool { !controlsHidden || fnOpen || allSettingsOpen || pillInfo != nil }

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
    }

    func toggleFn() {
        if fnOpen { closeOverlays() } else { openFn(nil) }
    }

    func openFn(_ id: SettingID?) {
        if let id { selected = id }
        allSettingsOpen = false
        pillInfo = nil
        fnOpen = true
    }

    func openAllSettings(_ group: SettingGroup?) {
        if let group {
            self.group = group
            editing = nil
        }
        fnOpen = false
        pillInfo = nil
        allSettingsOpen = true
    }

    func closeOverlays() {
        fnOpen = false
        allSettingsOpen = false
        editing = nil
        pillInfo = nil
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
