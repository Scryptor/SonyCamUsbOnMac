import Foundation

public enum PTPParseError: Error, Equatable {
    case truncated(offset: Int)
    case badCount(Int, offset: Int)
    case unsupportedType(UInt16)
}

/// Последовательное чтение little-endian данных PTP.
public struct ByteReader {
    private let bytes: [UInt8]
    public private(set) var offset: Int = 0

    public init(_ data: Data) {
        bytes = [UInt8](data)
    }

    public var remaining: Int { bytes.count - offset }

    public mutating func u8() throws -> UInt8 { UInt8(try read(1)) }
    public mutating func u16() throws -> UInt16 { UInt16(try read(2)) }
    public mutating func u32() throws -> UInt32 { UInt32(try read(4)) }
    public mutating func u64() throws -> UInt64 { try read(8) }

    public func peekU16() -> UInt16? {
        remaining >= 2 ? UInt16(littleEndian(at: offset, count: 2)) : nil
    }

    public mutating func skip(_ n: Int) throws {
        try require(n)
        offset += n
    }

    /// Строка PTP: u8 число символов UTF-16 (вместе с завершающим NUL), затем сами символы.
    public mutating func ptpString() throws -> String {
        let count = Int(try u8())
        var units: [UInt16] = []
        units.reserveCapacity(count)
        for _ in 0..<count { units.append(try u16()) }
        while units.last == 0 { units.removeLast() }
        return String(decoding: units, as: UTF16.self)
    }

    /// Массив PTP: u32 число элементов, затем элементы u16.
    public mutating func u16Array() throws -> [UInt16] {
        let start = offset
        let count = Int(try u32())
        guard count * 2 <= remaining else { throw PTPParseError.badCount(count, offset: start) }
        var result: [UInt16] = []
        result.reserveCapacity(count)
        for _ in 0..<count { result.append(try u16()) }
        return result
    }

    private func require(_ n: Int) throws {
        if remaining < n { throw PTPParseError.truncated(offset: offset) }
    }

    private func littleEndian(at position: Int, count: Int) -> UInt64 {
        var value: UInt64 = 0
        for i in 0..<count { value |= UInt64(bytes[position + i]) << (8 * UInt64(i)) }
        return value
    }

    private mutating func read(_ count: Int) throws -> UInt64 {
        try require(count)
        defer { offset += count }
        return littleEndian(at: offset, count: count)
    }
}
