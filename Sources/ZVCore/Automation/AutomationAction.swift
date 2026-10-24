import Foundation

/// Команды API автоматизации: тело POST /action вида {"action": "...", ...}.
public enum AutomationAction: Equatable, Sendable, Decodable {
    case key(String)
    case openFn(SettingID?)
    case closeOverlays
    case select(SettingID)
    case step(Int)
    case request(SettingID, Int64)
    case openAllSettings(SettingGroup?)
    case editRow(SettingID?)
    case toggleHistogram
    case savePreset(String?)
    case applyPreset(Int)
    case renamePreset(Int, String)
    case deletePreset(Int)
    /// Запись ролика на карту — только с явным подтверждением.
    case toggleRecording(confirm: Bool)
    case switchMode(CaptureMode)
    /// Снимок пишет файл (на карту и/или на Mac) — только с явным подтверждением.
    case capture(confirm: Bool)
    case grabFrame
    case toggleControls
    case dismissReview
    case setPreference(String, String)
    case openShot(Int)

    private enum Keys: String, CodingKey {
        case action, key, setting, offset, value, group, name, index, confirm, mode
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: Keys.self)
        let action = try c.decode(String.self, forKey: .action)
        switch action {
        case "key": self = .key(try c.decode(String.self, forKey: .key))
        case "openFn": self = .openFn(try c.decodeIfPresent(SettingID.self, forKey: .setting))
        case "closeOverlays": self = .closeOverlays
        case "select": self = .select(try c.decode(SettingID.self, forKey: .setting))
        case "step": self = .step(try c.decode(Int.self, forKey: .offset))
        case "request": self = .request(try c.decode(SettingID.self, forKey: .setting), try c.decode(Int64.self, forKey: .value))
        case "openAllSettings": self = .openAllSettings(try c.decodeIfPresent(SettingGroup.self, forKey: .group))
        case "editRow": self = .editRow(try c.decodeIfPresent(SettingID.self, forKey: .setting))
        case "toggleHistogram": self = .toggleHistogram
        case "savePreset": self = .savePreset(try c.decodeIfPresent(String.self, forKey: .name))
        case "applyPreset": self = .applyPreset(try c.decode(Int.self, forKey: .index))
        case "renamePreset": self = .renamePreset(try c.decode(Int.self, forKey: .index), try c.decode(String.self, forKey: .name))
        case "deletePreset": self = .deletePreset(try c.decode(Int.self, forKey: .index))
        case "toggleRecording": self = .toggleRecording(confirm: try c.decodeIfPresent(Bool.self, forKey: .confirm) ?? false)
        case "switchMode": self = .switchMode(try c.decode(CaptureMode.self, forKey: .mode))
        case "capture": self = .capture(confirm: try c.decodeIfPresent(Bool.self, forKey: .confirm) ?? false)
        case "grabFrame": self = .grabFrame
        case "toggleControls": self = .toggleControls
        case "dismissReview": self = .dismissReview
        case "setPreference": self = .setPreference(try c.decode(String.self, forKey: .key), try c.decode(String.self, forKey: .value))
        case "openShot": self = .openShot(try c.decode(Int.self, forKey: .index))
        default:
            throw DecodingError.dataCorruptedError(forKey: .action, in: c, debugDescription: "неизвестная команда \(action)")
        }
    }
}
