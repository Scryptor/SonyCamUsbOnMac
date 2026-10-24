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
    public static let fnBottomRow: [FnSlot] = [
        .setting(.focusArea), .setting(.metering), .setting(.pictureProfile),
        .setting(.creativeStyle), .setting(.dro), .allSettings,
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

    public static func infoLine(for mode: CaptureMode) -> [SettingID] {
        mode == .photo ? infoLine + [.fileFormat] : infoLine
    }
}
