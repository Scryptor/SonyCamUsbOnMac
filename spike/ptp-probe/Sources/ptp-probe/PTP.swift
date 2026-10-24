// SPIKE (throwaway): minimal PTP + Sony extension helpers for the feasibility probe.
// Protocol facts (opcodes, layouts) were read from libgphoto2 camlibs/ptp2; the code is our own.
import Foundation

enum OC {
    static let getDeviceInfo: UInt16 = 0x1001
    static let getObjectInfo: UInt16 = 0x1008
    static let getObject: UInt16 = 0x1009
    static let sonyConnect: UInt16 = 0x9201
    static let sonyGetExtDeviceInfo: UInt16 = 0x9202
    static let sonySetExtDevicePropValue: UInt16 = 0x9205
    static let sonyControlDevice: UInt16 = 0x9207
    static let sonyGetAllExtDevicePropInfo: UInt16 = 0x9209
}

let sonyLiveViewHandle: UInt32 = 0xFFFF_C002
let sonyProtocol300: UInt32 = 0x12C
let sonyPriorityMode: UInt16 = 0xD25A

enum RC {
    static let ok: UInt16 = 0x2001
    static let names: [UInt16: String] = [
        0x2001: "OK", 0x2002: "GeneralError", 0x2003: "SessionNotOpen", 0x2004: "InvalidTransactionID",
        0x2005: "OperationNotSupported", 0x2006: "ParameterNotSupported", 0x2007: "IncompleteTransfer",
        0x2008: "InvalidStorageId", 0x2009: "InvalidObjectHandle", 0x200A: "DevicePropNotSupported",
        0x200B: "InvalidObjectFormatCode", 0x200F: "AccessDenied", 0x2013: "StoreNotAvailable",
        0x2017: "UnknownVendorCode", 0x2019: "DeviceBusy", 0x201B: "InvalidDevicePropFormat",
        0x201C: "InvalidDevicePropValue", 0x201D: "InvalidParameter", 0x201E: "SessionAlreadyOpened",
        0x201F: "TransactionCanceled",
    ]
    static func name(_ code: UInt16) -> String {
        "\(names[code] ?? "?")(0x\(hex16(code)))"
    }
}

func hex16(_ v: UInt16) -> String { String(format: "%04X", v) }
func hex32(_ v: UInt32) -> String { String(format: "%08X", v) }

extension Data {
    mutating func appendLE<T: FixedWidthInteger>(_ value: T) {
        var v = value.littleEndian
        Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
    }

    func hexPreview(_ limit: Int = 48) -> String {
        let shown = prefix(limit).map { String(format: "%02x", $0) }.joined(separator: " ")
        return count > limit ? shown + " … (\(count) bytes)" : shown
    }
}

struct ByteReader {
    enum Failure: Error, CustomStringConvertible {
        case eof(at: Int, need: Int)
        case badCount(Int, at: Int)
        case unsupportedType(UInt16)

        var description: String {
            switch self {
            case let .eof(at, need): return "unexpected end of data at \(at) (need \(need) bytes)"
            case let .badCount(n, at): return "implausible element count \(n) at \(at)"
            case let .unsupportedType(t): return "unsupported data type 0x\(hex16(t))"
            }
        }
    }

    let bytes: [UInt8]
    var pos: Int

    init(_ data: Data, at pos: Int = 0) {
        self.bytes = [UInt8](data)
        self.pos = pos
    }

    var remaining: Int { bytes.count - pos }

    private func need(_ n: Int) throws {
        if remaining < n { throw Failure.eof(at: pos, need: n) }
    }

    private func le(_ n: Int) -> UInt64 {
        var v: UInt64 = 0
        for i in 0..<n { v |= UInt64(bytes[pos + i]) << (8 * UInt64(i)) }
        return v
    }

    mutating func u8() throws -> UInt8 { try need(1); defer { pos += 1 }; return bytes[pos] }
    mutating func u16() throws -> UInt16 { try need(2); defer { pos += 2 }; return UInt16(le(2)) }
    mutating func u32() throws -> UInt32 { try need(4); defer { pos += 4 }; return UInt32(le(4)) }
    mutating func u64() throws -> UInt64 { try need(8); defer { pos += 8 }; return le(8) }
    mutating func skip(_ n: Int) throws { try need(n); pos += n }

    func peekU16() -> UInt16? { remaining >= 2 ? UInt16(le(2)) : nil }

    /// PTP string: uint8 character count (including the terminating NUL), then UTF-16LE code units.
    mutating func string() throws -> String {
        let n = Int(try u8())
        var units: [UInt16] = []
        for _ in 0..<n { units.append(try u16()) }
        while units.last == 0 { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }

    /// PTP array: uint32 element count, then the elements.
    mutating func u16Array() throws -> [UInt16] {
        let n = Int(try u32())
        guard n * 2 <= remaining else { throw Failure.badCount(n, at: pos) }
        var out: [UInt16] = []
        for _ in 0..<n { out.append(try u16()) }
        return out
    }
}

enum PTPValue: Equatable, CustomStringConvertible {
    case signed(Int64)
    case unsigned(UInt64)
    case string(String)
    case array([Int64])
    case unsupported

    var intValue: Int64? {
        switch self {
        case let .signed(v): return v
        case let .unsigned(v): return Int64(bitPattern: v)
        default: return nil
        }
    }

    var description: String {
        switch self {
        case let .signed(v): return "\(v)"
        case let .unsigned(v): return "\(v) (0x\(String(v, radix: 16, uppercase: true)))"
        case let .string(s): return "\"\(s)\""
        case let .array(a): return "[\(a.prefix(16).map(String.init).joined(separator: ","))\(a.count > 16 ? ",…" : "")]"
        case .unsupported: return "<unsupported>"
        }
    }
}

func readValue(_ r: inout ByteReader, type: UInt16) throws -> PTPValue {
    switch type {
    case 0x0001: return .signed(Int64(Int8(bitPattern: try r.u8())))
    case 0x0002: return .unsigned(UInt64(try r.u8()))
    case 0x0003: return .signed(Int64(Int16(bitPattern: try r.u16())))
    case 0x0004: return .unsigned(UInt64(try r.u16()))
    case 0x0005: return .signed(Int64(Int32(bitPattern: try r.u32())))
    case 0x0006: return .unsigned(UInt64(try r.u32()))
    case 0x0007: return .signed(Int64(bitPattern: try r.u64()))
    case 0x0008: return .unsigned(try r.u64())
    case 0x0009, 0x000A:
        try r.skip(16)
        return .unsupported
    case 0xFFFF:
        return .string(try r.string())
    case let t where t & 0x4000 != 0:
        let n = Int(try r.u32())
        guard n <= r.remaining else { throw ByteReader.Failure.badCount(n, at: r.pos) }
        var out: [Int64] = []
        for _ in 0..<n {
            if let v = try readValue(&r, type: t & ~0x4000).intValue { out.append(v) }
        }
        return .array(out)
    default:
        throw ByteReader.Failure.unsupportedType(type)
    }
}

func encodeValue(_ v: Int64, type: UInt16) -> Data {
    var d = Data()
    switch type {
    case 0x0001, 0x0002: d.append(UInt8(truncatingIfNeeded: v))
    case 0x0003, 0x0004: d.appendLE(UInt16(truncatingIfNeeded: v))
    case 0x0005, 0x0006: d.appendLE(UInt32(truncatingIfNeeded: v))
    case 0x0007, 0x0008: d.appendLE(UInt64(bitPattern: v))
    default: break
    }
    return d
}

func typeName(_ t: UInt16) -> String {
    let base: [UInt16: String] = [1: "i8", 2: "u8", 3: "i16", 4: "u16", 5: "i32", 6: "u32", 7: "i64", 8: "u64", 0xFFFF: "str"]
    if let n = base[t] { return n }
    if t & 0x4000 != 0, let n = base[t & ~0x4000] { return "a" + n }
    return "0x\(hex16(t))"
}

// MARK: - Containers

/// USB PTP command block: length, type=1, code, transaction id, params.
func makeCommand(_ code: UInt16, _ params: [UInt32], transactionID: UInt32 = 0) -> Data {
    var d = Data()
    d.appendLE(UInt32(12 + 4 * params.count))
    d.appendLE(UInt16(1))
    d.appendLE(code)
    d.appendLE(transactionID)
    for p in params { d.appendLE(p) }
    return d
}

/// USB PTP data block: length, type=2, code, transaction id, payload.
func makeDataContainer(_ code: UInt16, payload: Data, transactionID: UInt32) -> Data {
    var d = Data()
    d.appendLE(UInt32(12 + payload.count))
    d.appendLE(UInt16(2))
    d.appendLE(code)
    d.appendLE(transactionID)
    d.append(payload)
    return d
}

struct PTPResponse {
    let code: UInt16
    let transactionID: UInt32?
    let params: [UInt32]
    let raw: Data

    /// Accepts a USB response container (length, type=3, code, tid, params).
    init(_ raw: Data) {
        self.raw = raw
        var r = ByteReader(raw)
        guard raw.count >= 8, (try? r.u32()) != nil, let _ = try? r.u16(), let code = try? r.u16() else {
            self.code = raw.count >= 2 ? UInt16(raw[raw.startIndex]) | UInt16(raw[raw.startIndex + 1]) << 8 : 0
            self.transactionID = nil
            self.params = []
            return
        }
        self.code = code
        self.transactionID = try? r.u32()
        var params: [UInt32] = []
        while let p = try? r.u32() { params.append(p) }
        self.params = params
    }
}

/// Strips a USB data container header if ImageCaptureCore left it in place.
func stripDataHeader(_ data: Data, code: UInt16) -> (payload: Data, hadHeader: Bool) {
    guard data.count >= 12 else { return (data, false) }
    var r = ByteReader(data)
    guard let len = try? r.u32(), let type = try? r.u16(), let c = try? r.u16() else { return (data, false) }
    if Int(len) == data.count, type == 2, c == code {
        return (Data(data.dropFirst(12)), true)
    }
    return (data, false)
}

// MARK: - Datasets

struct DeviceInfo {
    var standardVersion: UInt16 = 0
    var vendorExtensionID: UInt32 = 0
    var vendorExtensionVersion: UInt16 = 0
    var vendorExtensionDesc = ""
    var functionalMode: UInt16 = 0
    var operations: [UInt16] = []
    var events: [UInt16] = []
    var properties: [UInt16] = []
    var manufacturer = ""
    var model = ""
    var deviceVersion = ""
    var serial = ""

    init(_ data: Data) throws {
        var r = ByteReader(data)
        standardVersion = try r.u16()
        vendorExtensionID = try r.u32()
        vendorExtensionVersion = try r.u16()
        vendorExtensionDesc = try r.string()
        functionalMode = try r.u16()
        operations = try r.u16Array()
        events = try r.u16Array()
        properties = try r.u16Array()
        _ = try r.u16Array() // capture formats
        _ = try r.u16Array() // image formats
        manufacturer = try r.string()
        model = try r.string()
        deviceVersion = try r.string()
        serial = try r.string()
    }
}

struct SonyExtInfo {
    let version: UInt16
    let properties: [UInt16]
    let controls: [UInt16]

    init(_ data: Data) throws {
        var r = ByteReader(data)
        version = try r.u16()
        properties = try r.u16Array()
        controls = (try? r.u16Array()) ?? []
    }
}

struct SonyProperty {
    let code: UInt16
    let dataType: UInt16
    let getSet: UInt8
    let isEnabled: UInt8
    let defaultValue: PTPValue
    let currentValue: PTPValue
    let formFlag: UInt8
    let range: (min: PTPValue, max: PTPValue, step: PTPValue)?
    let enumValues: [PTPValue]
    let enumValues2: [PTPValue]?

    var name: String { propertyNames[code] ?? "?" }
    var writable: Bool { getSet == 1 && isEnabled == 1 }
}

/// Sony GetAllExtDevicePropInfo (0x9209): uint32 count, uint32 reserved, then Sony property descriptors:
/// code u16, type u16, getset u8, isEnabled u8, default, current, form flag u8, form,
/// and on some bodies a second enumeration list (detected the same way libgphoto2 does).
func parseSonyProperties(_ data: Data) -> (props: [SonyProperty], declaredCount: UInt32, error: String?) {
    var r = ByteReader(data)
    guard let count = try? r.u32(), (try? r.u32()) != nil else { return ([], 0, "too short: \(data.count) bytes") }
    var props: [SonyProperty] = []
    while r.remaining > 0 {
        let start = r.pos
        do {
            let code = try r.u16()
            let type = try r.u16()
            let getSet = try r.u8()
            let enabled = try r.u8()
            let def = try readValue(&r, type: type)
            let cur = (type == 0xFFFF && r.remaining == 0) ? def : try readValue(&r, type: type)
            var formFlag: UInt8 = 0
            var range: (min: PTPValue, max: PTPValue, step: PTPValue)?
            var enums: [PTPValue] = []
            var enums2: [PTPValue]?
            if r.remaining > 0 {
                formFlag = try r.u8()
                switch formFlag {
                case 1:
                    let lo = try readValue(&r, type: type)
                    let hi = try readValue(&r, type: type)
                    let st = try readValue(&r, type: type)
                    range = (lo, hi, st)
                case 2:
                    let n = Int(try r.u16())
                    for _ in 0..<n { enums.append(try readValue(&r, type: type)) }
                default:
                    break
                }
                if formFlag == 2, let peek = r.peekU16(), peek < 0x200 {
                    let n = Int(try r.u16())
                    var list: [PTPValue] = []
                    for _ in 0..<n { list.append(try readValue(&r, type: type)) }
                    enums2 = list
                }
            }
            props.append(SonyProperty(code: code, dataType: type, getSet: getSet, isEnabled: enabled,
                                      defaultValue: def, currentValue: cur, formFlag: formFlag,
                                      range: range, enumValues: enums, enumValues2: enums2))
        } catch {
            return (props, count, "parse error at offset \(start): \(error)")
        }
    }
    return (props, count, nil)
}

/// Human-readable value for the few properties whose encoding is certain; everything else stays raw.
func pretty(_ code: UInt16, _ value: PTPValue) -> String? {
    guard let v = value.intValue else { return nil }
    switch code {
    case 0x5007: return String(format: "f/%.1f", Double(v) / 100)
    case 0x5010: return String(format: "%+.1f EV", Double(v) / 1000)
    case 0xD20D:
        let u = UInt32(truncatingIfNeeded: v)
        if u == 0 { return "BULB" }
        let num = u >> 16, den = u & 0xFFFF
        if den == 1 { return "\(num)\"" }
        if num == 1 { return "1/\(den)" }
        return "\(num)/\(den)"
    case 0xD21E:
        let iso = UInt32(truncatingIfNeeded: v) & 0x00FF_FFFF
        return iso == 0x00FF_FFFF ? "ISO AUTO" : "ISO \(iso)"
    default:
        return nil
    }
}
