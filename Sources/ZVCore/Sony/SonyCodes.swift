import Foundation

/// Коды операций PTP, которые нужны для работы с Sony.
public enum SonyOp {
    public static let getDeviceInfo: UInt16 = 0x1001
    public static let getObjectInfo: UInt16 = 0x1008
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
    public static let focusFound: UInt16 = 0xD213
    public static let objectInMemory: UInt16 = 0xD215
    public static let batteryLevel: UInt16 = 0xD218
    public static let abShift: UInt16 = 0xD21C
    public static let movieRecordingState: UInt16 = 0xD21D
    public static let iso: UInt16 = 0xD21E
    /// Куда сохранять снимок: 0x01 — только компьютер, 0x10 — только карта, 0x11 — оба.
    public static let stillImageStoreDestination: UInt16 = 0xD222
    public static let focusArea: UInt16 = 0xD22C
    public static let pictureProfile: UInt16 = 0xD23F
    public static let creativeStyle: UInt16 = 0xD240
    public static let movieFileFormat: UInt16 = 0xD241
    public static let movieRecordingSetting: UInt16 = 0xD242
    public static let cardRemainingShots: UInt16 = 0xD249
    public static let cardRemainingTime: UInt16 = 0xD24A
    public static let overheat: UInt16 = 0xD251
    public static let jpegQuality: UInt16 = 0xD252
    public static let fileFormat: UInt16 = 0xD253
    public static let priorityMode: UInt16 = 0xD25A
    /// Качество превью: 1 — 640×360 (~34 КБ на кадр), 2 — 1024×576 (~72 КБ). По Wi-Fi меняет размер кадра.
    public static let liveViewImageQuality: UInt16 = 0xD26A
    public static let shutterHalfPress: UInt16 = 0xD2C1
    public static let shutterFullPress: UInt16 = 0xD2C2
    public static let movieRecButton: UInt16 = 0xD2C8

    // фокус и зум с экрана (Sony Camera Control PTP 3 Reference)
    /// Near/Far — доступна ли перефокусировка ближе/дальше (0x01 — да; у ZV-E10 — в MF).
    public static let nearFarEnabled: UInt16 = 0xD235
    /// Положение фокуса 0…100 (UINT8), только чтение.
    public static let focalPosition: UInt16 = 0xD24C
    /// Можно ли управлять зумом (0x01 — да): объектив с приводом или Clear Image Zoom.
    public static let zoomEnabled: UInt16 = 0xD25B
    /// Масштаб зума ×1000 (UINT32): 1000 — ×1,0.
    public static let zoomScale: UInt16 = 0xD25C
    /// Шкала зума (UINT32): биты 31–24 — сколько отрезков, 23–16 — в каком, 15–0 — где в нём (0…100).
    public static let zoomBar: UInt16 = 0xD25D
    /// Что делает касание экрана камеры: 0x03 — фокус, 0x04 — слежение (и с Touch AE — 0x08…0x0B).
    public static let touchFunction: UInt16 = 0xD283
    /// Можно ли «коснуться» экрана камеры командой (0x01 — да) и снять это касание.
    public static let remoteTouchEnabled: UInt16 = 0xD284
    public static let remoteTouchCancelEnabled: UInt16 = 0xD285
}

/// Команды управления 0x9207 (Control Device) — кроме кнопок спуска и записи; тип значения — свой у каждой.
public enum SonyControl {
    /// Ближе (−) / дальше (+) на 1…7 шагов, INT16; только при Near/Far Enable 0xD235 = 1.
    public static let nearFar: UInt16 = 0xD2D1
    /// Точка AF: (x << 16) | y в сетке 640×480, UINT32; двигает рамку в зонах «Точка» и «Зона».
    public static let afAreaPosition: UInt16 = 0xD2DC
    /// Зум, INT8: 1 — к теле, −1 — к широкому, 0 — стоп; едет, пока не придёт стоп.
    public static let zoomOperation: UInt16 = 0xD2DD
    /// Касание экрана камеры в точке (x << 16) | y сетки 640×480, UINT32: фокус или слежение — как настроено в камере.
    public static let remoteTouch: UInt16 = 0xD2E4
    /// Снять касание (слежение), кнопка UINT16: нажать и отпустить.
    public static let remoteTouchCancel: UInt16 = 0xD2E5
}

public enum Sony {
    public static let vendorID = 0x054C
    public static let protocolVersion: UInt32 = 0x12C
    public static let liveViewHandle: UInt32 = 0xFFFF_C002
    /// Снимок в памяти камеры (когда 0xD215 > 0x8000).
    public static let capturedObjectHandle: UInt32 = 0xFFFF_C001
    public static let propertyChangedEvent: UInt16 = 0xC203
}
