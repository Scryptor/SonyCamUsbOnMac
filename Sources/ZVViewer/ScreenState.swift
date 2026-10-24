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

    @ObservationIgnored private let defaults = UserDefaults.standard

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
