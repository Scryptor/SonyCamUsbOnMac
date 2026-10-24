import Foundation

/// Коды операций PTP, которые нужны для работы с Sony.
public enum SonyOp {
    public static let getDeviceInfo: UInt16 = 0x1001
    public static let getObject: UInt16 = 0x1009
    public static let connect: UInt16 = 0x9201
    public static let getExtDeviceInfo: UInt16 = 0x9202
    public static let setExtPropValue: UInt16 = 0x9205
    public static let controlDevice: UInt16 = 0x9207
    public static let getAllExtPropInfo: UInt16 = 0x9209
}

/// Коды свойств камеры (стандартные PTP 0x50xx и расширения Sony 0xD2xx).
public enum SonyProp {
    public static let whiteBalance: UInt16 = 0x5005
    public static let fNumber: UInt16 = 0x5007
    public static let focusMode: UInt16 = 0x500A
    public static let metering: UInt16 = 0x500B
    public static let exposureProgram: UInt16 = 0x500E
    public static let exposureComp: UInt16 = 0x5010
    public static let dro: UInt16 = 0xD201
    public static let imageSize: UInt16 = 0xD203
    public static let shutter: UInt16 = 0xD20D
    public static let colorTemp: UInt16 = 0xD20F
    public static let gmShift: UInt16 = 0xD210
    public static let aspectRatio: UInt16 = 0xD211
    public static let batteryLevel: UInt16 = 0xD218
    public static let abShift: UInt16 = 0xD21C
    public static let movieRecordingState: UInt16 = 0xD21D
    public static let iso: UInt16 = 0xD21E
    public static let focusArea: UInt16 = 0xD22C
    public static let pictureProfile: UInt16 = 0xD23F
    public static let creativeStyle: UInt16 = 0xD240
    public static let movieFileFormat: UInt16 = 0xD241
    public static let movieRecordingSetting: UInt16 = 0xD242
    public static let cardRemainingTime: UInt16 = 0xD24A
    public static let overheat: UInt16 = 0xD251
    public static let jpegQuality: UInt16 = 0xD252
    public static let fileFormat: UInt16 = 0xD253
    public static let priorityMode: UInt16 = 0xD25A
    public static let movieRecButton: UInt16 = 0xD2C8
}

public enum Sony {
    public static let vendorID = 0x054C
    public static let protocolVersion: UInt32 = 0x12C
    public static let liveViewHandle: UInt32 = 0xFFFF_C002
    public static let propertyChangedEvent: UInt16 = 0xC203
}
