import Foundation

/// Коды типов данных PTP. Массив — тот же код с битом 0x4000.
public enum PTPType {
    public static let int8: UInt16 = 0x0001
    public static let uint8: UInt16 = 0x0002
    public static let int16: UInt16 = 0x0003
    public static let uint16: UInt16 = 0x0004
    public static let int32: UInt16 = 0x0005
    public static let uint32: UInt16 = 0x0006
    public static let int64: UInt16 = 0x0007
    public static let uint64: UInt16 = 0x0008
    public static let string: UInt16 = 0xFFFF
    static let arrayFlag: UInt16 = 0x4000
}

public enum PTPValue: Equatable, Sendable {
    case int(Int64)
    case string(String)
    case array([Int64])
    case unsupported

    public var intValue: Int64? {
        if case let .int(v) = self { return v }
        return nil
    }

    public static func read(_ r: inout ByteReader, type: UInt16) throws -> PTPValue {
        switch type {
        case PTPType.int8: return .int(Int64(Int8(bitPattern: try r.u8())))
        case PTPType.uint8: return .int(Int64(try r.u8()))
        case PTPType.int16: return .int(Int64(Int16(bitPattern: try r.u16())))
        case PTPType.uint16: return .int(Int64(try r.u16()))
        case PTPType.int32: return .int(Int64(Int32(bitPattern: try r.u32())))
        case PTPType.uint32: return .int(Int64(try r.u32()))
        case PTPType.int64, PTPType.uint64: return .int(Int64(bitPattern: try r.u64()))
        case 0x0009, 0x000A:
            // 128-битные целые камера почти не использует — пропускаем
            try r.skip(16)
            return .unsupported
        case PTPType.string:
            return .string(try r.ptpString())
        case let t where t & PTPType.arrayFlag != 0:
            let start = r.offset
            let count = Int(try r.u32())
            guard count <= r.remaining else { throw PTPParseError.badCount(count, offset: start) }
            var items: [Int64] = []
            for _ in 0..<count {
                if let v = try read(&r, type: t & ~PTPType.arrayFlag).intValue { items.append(v) }
            }
            return .array(items)
        default:
            throw PTPParseError.unsupportedType(type)
        }
    }

    /// Только само значение нужного размера, без контейнера.
    public static func encode(_ v: Int64, type: UInt16) -> Data {
        var data = Data()
        switch type {
        case PTPType.int8, PTPType.uint8: data.appendLE(UInt8(truncatingIfNeeded: v))
        case PTPType.int16, PTPType.uint16: data.appendLE(UInt16(truncatingIfNeeded: v))
        case PTPType.int32, PTPType.uint32: data.appendLE(UInt32(truncatingIfNeeded: v))
        case PTPType.int64, PTPType.uint64: data.appendLE(UInt64(bitPattern: v))
        default: break
        }
        return data
    }
}

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }
}
