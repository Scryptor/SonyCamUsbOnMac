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
}
