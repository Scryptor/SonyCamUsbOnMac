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
    /// Строка состояния: закрепить или открепить — закреплённая остаётся на экране, когда элементы спрятаны.
    case toggleStatusPin
    /// Пресеты: список под кнопкой; окно «Пресеты» — открыть (на пресете), закрыть, выбрать в нём пресет.
    case openPresetMenu
    case closePresetMenu
    case openPresets(Int?)
    case closePresets
    case selectPreset(Int)
    /// Правка пресета — камера не меняется: значение настройки, менять ли её, место в списке, копия, «Взять с камеры».
    case setPresetValue(Int, SettingID, Int64)
    case setPresetIncluded(Int, SettingID, Bool)
    case movePreset(from: Int, to: Int)
    case duplicatePreset(Int)
    case recapturePreset(Int)
    /// Быстрый просмотр: как нажать и отпустить «Посмотреть».
    case beginPresetPreview(Int)
    case endPresetPreview
    /// Сохранение: «Сохранить в «…»» для текущего пресета; «☆+»; «Новый пресет…» в окошке «Сохранить настройки»;
    /// закрыть окошки сохранения.
    case savePresetChanges
    case openSavePreset
    case chooseNewPreset
    case closeSavePreset
    /// Камеры: выбрать (подключиться), переименовать, забыть, сопрячь одну, поискать в сети сейчас.
    case selectCamera(String)
    case renameCamera(String, String)
    case forgetCamera(String)
    case pairCamera(String)
    case searchCameras
    /// Лента камер: держать камеру на связи рядом с главной или убрать её.
    case addToStrip(String)
    case removeFromStrip(String)
    /// Группа: связать камеру ленты с главной (повторяет настройки, пресеты и запись) или отвязать.
    case linkCamera(String, Bool)
    /// Плитку ленты `id` перетащили на плитку `onto`: она встаёт на её место.
    case moveInStrip(String, onto: String)
    /// Монитор: меню инструментов (на инструменте), закрыть его; значение инструмента, включить или выключить его,
    /// выключить всё.
    case openAssist(String?)
    case closeAssist
    case setAssist(String, String)
    case toggleAssist(String)
    case assistOff

    private enum Keys: String, CodingKey {
        case action, key, setting, offset, value, group, name, index, confirm, mode, x, y, factor, included, from, to, id, linked, onto
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
        case "toggleStatusPin": self = .toggleStatusPin
        case "openPresetMenu": self = .openPresetMenu
        case "closePresetMenu": self = .closePresetMenu
        case "openPresets": self = .openPresets(try c.decodeIfPresent(Int.self, forKey: .index))
        case "closePresets": self = .closePresets
        case "selectPreset": self = .selectPreset(try c.decode(Int.self, forKey: .index))
        case "setPresetValue":
            self = .setPresetValue(try c.decode(Int.self, forKey: .index), try c.decode(SettingID.self, forKey: .setting),
                                   try c.decode(Int64.self, forKey: .value))
        case "setPresetIncluded":
            self = .setPresetIncluded(try c.decode(Int.self, forKey: .index), try c.decode(SettingID.self, forKey: .setting),
                                      try c.decode(Bool.self, forKey: .included))
        case "movePreset": self = .movePreset(from: try c.decode(Int.self, forKey: .from), to: try c.decode(Int.self, forKey: .to))
        case "duplicatePreset": self = .duplicatePreset(try c.decode(Int.self, forKey: .index))
        case "recapturePreset": self = .recapturePreset(try c.decode(Int.self, forKey: .index))
        case "beginPresetPreview": self = .beginPresetPreview(try c.decode(Int.self, forKey: .index))
        case "endPresetPreview": self = .endPresetPreview
        case "savePresetChanges": self = .savePresetChanges
        case "openSavePreset": self = .openSavePreset
        case "chooseNewPreset": self = .chooseNewPreset
        case "closeSavePreset": self = .closeSavePreset
        case "selectCamera": self = .selectCamera(try c.decode(String.self, forKey: .id))
        case "renameCamera": self = .renameCamera(try c.decode(String.self, forKey: .id), try c.decode(String.self, forKey: .name))
        case "forgetCamera": self = .forgetCamera(try c.decode(String.self, forKey: .id))
        case "pairCamera": self = .pairCamera(try c.decode(String.self, forKey: .id))
        case "searchCameras": self = .searchCameras
        case "addToStrip": self = .addToStrip(try c.decode(String.self, forKey: .id))
        case "removeFromStrip": self = .removeFromStrip(try c.decode(String.self, forKey: .id))
        case "linkCamera": self = .linkCamera(try c.decode(String.self, forKey: .id), try c.decodeIfPresent(Bool.self, forKey: .included) ?? (try c.decode(Bool.self, forKey: .linked)))
        case "moveInStrip": self = .moveInStrip(try c.decode(String.self, forKey: .id), onto: try c.decode(String.self, forKey: .onto))
        case "openAssist": self = .openAssist(try c.decodeIfPresent(String.self, forKey: .name))
        case "closeAssist": self = .closeAssist
        case "setAssist":
            // значение — строкой («95», «9:16», «on»), но и числом: {"value": 95}
            let value = try (try? c.decode(String.self, forKey: .value)) ?? String(c.decode(Int.self, forKey: .value))
            self = .setAssist(try c.decode(String.self, forKey: .name), value)
        case "toggleAssist": self = .toggleAssist(try c.decode(String.self, forKey: .name))
        case "assistOff": self = .assistOff
        default:
            throw DecodingError.dataCorruptedError(forKey: .action, in: c, debugDescription: "неизвестная команда \(action)")
        }
    }
}
