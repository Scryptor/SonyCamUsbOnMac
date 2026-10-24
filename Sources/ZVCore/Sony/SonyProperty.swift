import Foundation

public struct PropertyRange: Equatable, Sendable {
    public let min: Int64
    public let max: Int64
    public let step: Int64

    public init(min: Int64, max: Int64, step: Int64) {
        self.min = min
        self.max = max
        self.step = step
    }
}

/// Описание одного свойства камеры из GetAllExtDevicePropInfo.
public struct SonyProperty: Equatable, Sendable {
    public let code: UInt16
    public let dataType: UInt16
    public let getSet: UInt8
    /// 0 — недоступно, 1 — доступно, 2 — только показ.
    public let enabled: UInt8
    public let current: PTPValue
    public let range: PropertyRange?
    /// Значения, которые камера разрешает установить сейчас.
    public let settableValues: [Int64]
    /// Полный список значений (второй список камеры, если он есть).
    public let allValues: [Int64]

    public init(code: UInt16, dataType: UInt16, getSet: UInt8, enabled: UInt8, current: PTPValue,
                range: PropertyRange?, settableValues: [Int64], allValues: [Int64]) {
        self.code = code
        self.dataType = dataType
        self.getSet = getSet
        self.enabled = enabled
        self.current = current
        self.range = range
        self.settableValues = settableValues
        self.allValues = allValues
    }

    public var isWritable: Bool { getSet == 1 && enabled == 1 }
}

public enum SonyPropertyParser {
    /// Разбирает ответ 0x9209: u32 количество, u32 резерв, затем описатели свойств.
    public static func parse(_ data: Data) throws -> [UInt16: SonyProperty] {
        var r = ByteReader(data)
        _ = try r.u32()
        _ = try r.u32()
        var result: [UInt16: SonyProperty] = [:]
        while r.remaining > 0 {
            let property = try descriptor(&r)
            result[property.code] = property
        }
        return result
    }

    private static func descriptor(_ r: inout ByteReader) throws -> SonyProperty {
        let code = try r.u16()
        let type = try r.u16()
        let getSet = try r.u8()
        let enabled = try r.u8()
        let defaultValue = try PTPValue.read(&r, type: type)
        // строковое свойство в самом конце буфера может прийти без текущего значения
        let current = (type == PTPType.string && r.remaining == 0) ? defaultValue : try PTPValue.read(&r, type: type)

        var range: PropertyRange?
        var first: [Int64] = []
        var second: [Int64]?
        if r.remaining > 0 {
            let formFlag = try r.u8()
            switch formFlag {
            case 1:
                let lo = try PTPValue.read(&r, type: type).intValue ?? 0
                let hi = try PTPValue.read(&r, type: type).intValue ?? 0
                let step = try PTPValue.read(&r, type: type).intValue ?? 0
                range = PropertyRange(min: lo, max: hi, step: step)
            case 2:
                first = try values(&r, type: type)
                // у части свойств следом идёт второй список; иначе дальше уже код следующего свойства (0x5xxx/0xDxxx)
                if let next = r.peekU16(), next < 0x200 {
                    second = try values(&r, type: type)
                }
            default:
                break
            }
        }
        return SonyProperty(code: code, dataType: type, getSet: getSet, enabled: enabled, current: current,
                            range: range, settableValues: first, allValues: second ?? first)
    }

    private static func values(_ r: inout ByteReader, type: UInt16) throws -> [Int64] {
        let count = Int(try r.u16())
        var list: [Int64] = []
        list.reserveCapacity(count)
        for _ in 0..<count {
            if let v = try PTPValue.read(&r, type: type).intValue { list.append(v) }
        }
        return list
    }
}
