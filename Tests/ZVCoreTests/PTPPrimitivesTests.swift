import Foundation
import Testing
@testable import ZVCore

@Test func readsLittleEndianIntegers() throws {
    var r = ByteReader(Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07]))
    #expect(try r.u8() == 0x01)
    #expect(try r.u16() == 0x0302)
    #expect(try r.u32() == 0x0706_0504)
    #expect(r.remaining == 0)
    #expect(throws: PTPParseError.truncated(offset: 7)) { try r.u8() }
}

@Test func readsPTPStringAndArray() throws {
    // строка "ZV" (3 символа с NUL, UTF-16LE), затем массив u16 из двух элементов
    var r = ByteReader(Data([3, 0x5A, 0, 0x56, 0, 0, 0, 2, 0, 0, 0, 0x01, 0x92, 0x02, 0x92]))
    #expect(try r.ptpString() == "ZV")
    #expect(try r.u16Array() == [0x9201, 0x9202])
}

@Test func decodesAndEncodesValuesByType() throws {
    var r = ByteReader(Data([0xFF, 0x18, 0xFC, 0x32, 0x00, 0x01, 0x00]))
    #expect(try PTPValue.read(&r, type: PTPType.int8) == .int(-1))
    #expect(try PTPValue.read(&r, type: PTPType.int16) == .int(-1000))
    #expect(try PTPValue.read(&r, type: PTPType.uint32) == .int(0x0001_0032))
    #expect(PTPValue.encode(4, type: PTPType.uint16) == Data([0x04, 0x00]))
    #expect(PTPValue.encode(1, type: PTPType.int8) == Data([0x01]))
    #expect(PTPValue.encode(0x0001_0032, type: PTPType.uint32) == Data([0x32, 0x00, 0x01, 0x00]))
}

@Test func encodesCommandContainer() {
    #expect(PTPCommand.encode(0x9201, [1, 0, 0]) == Data([24, 0, 0, 0, 1, 0, 0x01, 0x92, 0, 0, 0, 0,
                                                           1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]))
}

@Test func parsesReplyAndStripsDataHeader() {
    let response = Data([12, 0, 0, 0, 3, 0, 0x01, 0x20, 2, 0, 0, 0])
    let wrapped = Data([14, 0, 0, 0, 2, 0, 0x09, 0x92, 2, 0, 0, 0, 0xAB, 0xCD])
    let reply = PTPReply.fromContainers(response: response, data: wrapped, operation: 0x9209)
    #expect(reply.isOK)
    #expect(reply.data == Data([0xAB, 0xCD]))
    #expect(PTPReply.fromContainers(response: response, data: Data([0xAB]), operation: 0x9209).data == Data([0xAB]))
}
