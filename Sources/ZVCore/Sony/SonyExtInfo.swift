import Foundation

/// Ответ Sony GetExtDeviceInfo (0x9202): версия протокола, коды свойств и коды команд управления.
public struct SonyExtInfo: Equatable, Sendable {
    public let version: UInt16
    public let propertyCodes: [UInt16]
    public let controlCodes: [UInt16]

    public init(data: Data) throws {
        var r = ByteReader(data)
        version = try r.u16()
        propertyCodes = try r.u16Array()
        controlCodes = r.remaining > 0 ? try r.u16Array() : []
    }
}
