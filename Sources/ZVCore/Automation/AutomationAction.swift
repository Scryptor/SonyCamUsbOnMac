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
    case switchMode(ShootingMode)
    /// Снимок пишет файл (на карту и/или на Mac) — только с явным подтверждением.
    case capture(confirm: Bool)
    case grabFrame
    case toggleControls
    case dismissReview
    case setPreference(String, String)
    case openShot(Int)
    /// Wi-Fi: начать сопряжение, отменить его, забыть камеру.
    case pairWiFi
    case cancelPairing
    case forgetWiFi
    /// Подсказка под строкой состояния: "connection", "frames"; nil — закрыть.
    case showInfo(String?)
    /// Панель отладки на вкладке: "link", "preview", "camera", "properties", "log"; nil — та, что была.
    case showDebug(String?)
    case hideDebug
    /// Гистограмма — в это место: доли свободного места по ширине и высоте.
    case moveHistogram(x: Double, y: Double)
    /// Галерея снимков сеанса: открыть на снимке, листать, закрыть.
    case openGallery(Int)
    case galleryStep(Int)
    case closeGallery
    /// Цифровое увеличение: множитель и точка кадра (доли) в центре экрана; без точки — центр как был.
    case setZoom(factor: Double, x: Double?, y: Double?)
    case resetZoom
    /// Строка состояния: показать или спрятать; закрепить или открепить.
    case toggleStatusBar
    case toggleStatusPin

    private enum Keys: String, CodingKey {
        case action, key, setting, offset, value, group, name, index, confirm, mode, x, y, factor
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
        case "switchMode": self = .switchMode(try c.decode(ShootingMode.self, forKey: .mode))
        case "capture": self = .capture(confirm: try c.decodeIfPresent(Bool.self, forKey: .confirm) ?? false)
        case "grabFrame": self = .grabFrame
        case "toggleControls": self = .toggleControls
        case "dismissReview": self = .dismissReview
        case "setPreference": self = .setPreference(try c.decode(String.self, forKey: .key), try c.decode(String.self, forKey: .value))
        case "openShot": self = .openShot(try c.decode(Int.self, forKey: .index))
        case "pairWiFi": self = .pairWiFi
        case "cancelPairing": self = .cancelPairing
        case "forgetWiFi": self = .forgetWiFi
        case "showInfo": self = .showInfo(try c.decodeIfPresent(String.self, forKey: .name))
        case "showDebug": self = .showDebug(try c.decodeIfPresent(String.self, forKey: .name))
        case "hideDebug": self = .hideDebug
        case "moveHistogram": self = .moveHistogram(x: try c.decode(Double.self, forKey: .x), y: try c.decode(Double.self, forKey: .y))
        case "openGallery": self = .openGallery(try c.decodeIfPresent(Int.self, forKey: .index) ?? 0)
        case "galleryStep": self = .galleryStep(try c.decode(Int.self, forKey: .offset))
        case "closeGallery": self = .closeGallery
        case "setZoom":
            self = .setZoom(factor: try c.decode(Double.self, forKey: .factor), x: try c.decodeIfPresent(Double.self, forKey: .x),
                            y: try c.decodeIfPresent(Double.self, forKey: .y))
        case "resetZoom": self = .resetZoom
        case "toggleStatusBar": self = .toggleStatusBar
        case "toggleStatusPin": self = .toggleStatusPin
        default:
            throw DecodingError.dataCorruptedError(forKey: .action, in: c, debugDescription: "неизвестная команда \(action)")
        }
    }
}
