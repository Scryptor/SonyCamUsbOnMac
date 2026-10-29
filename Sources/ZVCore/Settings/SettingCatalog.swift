import Foundation

public enum FnSlot: Hashable, Sendable {
    case setting(SettingID)
    case allSettings
}

/// Раскладка меню Fn и строки значений.
public enum SettingCatalog {
    public static let fnTopRow: [FnSlot] = [
        .setting(.shutter), .setting(.aperture), .setting(.iso),
        .setting(.whiteBalance), .setting(.exposureComp), .setting(.focusMode),
    ]
    /// Видеорежим: вместо DRO — режим записи (формат, частота, битрейт одним выбором); DRO — во «Всех настройках».
    public static let fnBottomRow: [FnSlot] = [
        .setting(.focusArea), .setting(.metering), .setting(.pictureProfile),
        .setting(.creativeStyle), .setting(.movieRecordingSetting), .allSettings,
    ]
    public static let infoLine: [SettingID] = [.shutter, .aperture, .iso, .whiteBalance, .exposureComp, .focusMode]

    /// В фоторежиме видео-настройки (профиль, стиль) уступают место формату и соотношению сторон снимка.
    public static func fnBottomRow(for mode: CaptureMode) -> [FnSlot] {
        switch mode {
        case .video: return fnBottomRow
        case .photo: return [.setting(.focusArea), .setting(.metering), .setting(.dro),
                             .setting(.fileFormat), .setting(.aspectRatio), .allSettings]
        }
    }

    /// В конце строки значений — формат снимка в фоторежиме и режим записи в видеорежиме.
    public static func infoLine(for mode: CaptureMode) -> [SettingID] {
        infoLine + [mode == .photo ? .fileFormat : .movieRecordingSetting]
    }

    /// Какая настройка на самом деле стоит на месте `id` у этой камеры (`available` — коды её свойств). Новые камеры Sony
    /// вместо Creative Style 0xD240 имеют Creative Look 0xD0FA: на место «Творческого стиля» в Fn и во «Всех настройках»
    /// встаёт он. У камеры, у которой есть и стиль, место за стилем. Остальные настройки — как есть.
    /// В группах и рядах Fn Creative Look не значится: «Все настройки» показывают строку группы и без свойства камеры.
    public static func resolved(_ id: SettingID, available: Set<UInt16>) -> SettingID {
        guard id == .creativeStyle, !available.contains(SonyProp.creativeStyle),
              available.contains(SonyProp.creativeLook) else { return id }
        return .creativeLook
    }

    /// Какая настройка стоит у этой камеры на месте `id` в Fn и во «Всех настройках», когда `id` — Creative Style или Creative
    /// Look: у них одно место на двоих, и выбор, сделанный на камере с другим из них (в ленте значений, в открытой строке),
    /// переезжает вместе с камерой — иначе лента сказала бы «камера сейчас не даёт менять…», а плитка не горела бы. Камеры нет
    /// (свойств нет) — место за Creative Style, как в `resolved`. Остальные настройки — как есть.
    public static func slot(of id: SettingID, available: Set<UInt16>) -> SettingID {
        id == .creativeStyle || id == .creativeLook ? resolved(.creativeStyle, available: available) : id
    }
}
