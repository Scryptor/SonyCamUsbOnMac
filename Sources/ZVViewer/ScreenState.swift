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
    /// Скрыто клавишей H — возвращается только ей же.
    private(set) var manualHidden = false
    /// Скрыто само после 3 с без движения мыши — возвращается при любом движении.
    private(set) var autoHidden = false

    @ObservationIgnored private let defaults = UserDefaults.standard
    @ObservationIgnored private var lastActivity = Date()

    var controlsHidden: Bool { manualHidden || autoHidden }

    func noteActivity() {
        lastActivity = Date()
        if autoHidden { autoHidden = false }
    }

    func toggleControls() {
        manualHidden.toggle()
        autoHidden = false
        lastActivity = Date()
    }

    /// busy — открыто меню или просмотр снимка: тогда не прячем.
    func tickAutoHide(enabled: Bool, busy: Bool) {
        if busy || !enabled {
            autoHidden = false
            return
        }
        if !autoHidden, Date().timeIntervalSince(lastActivity) > 3 { autoHidden = true }
    }

    init() {
        histogramOn = defaults.bool(forKey: "histogramOn")
    }

    func toggleFn() {
        if fnOpen { closeOverlays() } else { openFn(nil) }
    }

    func openFn(_ id: SettingID?) {
        if let id { selected = id }
        allSettingsOpen = false
        fnOpen = true
    }

    func openAllSettings(_ group: SettingGroup?) {
        if let group {
            self.group = group
            editing = nil
        }
        fnOpen = false
        allSettingsOpen = true
    }

    func closeOverlays() {
        fnOpen = false
        allSettingsOpen = false
        editing = nil
    }

    func toggleHistogram() {
        histogramOn.toggle()
        defaults.set(histogramOn, forKey: "histogramOn")
    }
}
