import Foundation

/// Стандартный набор PTP DeviceInfo (ответ на GetDeviceInfo).
public struct DeviceInfo: Equatable, Sendable {
    public let vendorExtensionID: UInt32
    public let operations: [UInt16]
    public let events: [UInt16]
    public let manufacturer: String
    public let model: String
    public let firmware: String
    public let serial: String

    public init(data: Data) throws {
        var r = ByteReader(data)
        _ = try r.u16()                 // версия стандарта
        vendorExtensionID = try r.u32()
        _ = try r.u16()                 // версия расширения
        _ = try r.ptpString()           // описание расширения
        _ = try r.u16()                 // functional mode
        operations = try r.u16Array()
        events = try r.u16Array()
        _ = try r.u16Array()            // стандартные свойства
        _ = try r.u16Array()            // форматы съёмки
        _ = try r.u16Array()            // форматы изображений
        manufacturer = try r.ptpString()
        model = try r.ptpString()
        firmware = try r.ptpString()
        serial = try r.ptpString()
    }

    /// Команды Sony есть только в режиме PC Remote.
    public var isPCRemote: Bool { operations.contains(SonyOp.connect) }

    /// Назвала ли камера операцию в GetDeviceInfo. Новые операции Sony (0x9215, 0x9216, 0x9223) зовём только если да:
    /// у ZV-E10 их нет.
    public func supports(_ op: UInt16) -> Bool { operations.contains(op) }
}
