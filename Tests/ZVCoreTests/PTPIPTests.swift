import Foundation
import Testing
@testable import ZVCore

private func le32(_ v: UInt32) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }
private func le16(_ v: UInt16) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }
private func le64(_ v: UInt64) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }

/// Склейка кусков байтов без длинных цепочек «+» (их компилятор Swift проверяет очень долго).
private func bytes(_ parts: [UInt8]...) -> [UInt8] { parts.flatMap { $0 } }

private func packet(_ type: UInt32, _ payload: [UInt8]) -> Data {
    Data(bytes(le32(UInt32(8 + payload.count)), le32(type), payload))
}

@Test func initCommandRequestCarriesGuidNameAndVersion() {
    let guid = Data((1...16).map { UInt8($0) })
    let request = [UInt8](PTPIP.initCommandRequest(guid: guid, name: "ZV"))
    // длина, тип 1, GUID, «ZV» в UTF-16LE с завершающим нулём, версия 1.0
    let expected = bytes(le32(34), le32(1), (1...16).map { UInt8($0) }, [0x5A, 0, 0x56, 0, 0, 0], le32(0x0001_0000))
    #expect(request == expected)
}

@Test func operationRequestLayout() {
    let request = [UInt8](PTPIP.operationRequest(0x9201, transactionID: 5, params: [1, 0, 0], dataOut: false))
    let expected = bytes(le32(30), le32(6), le32(1), le16(0x9201), le32(5), le32(1), le32(0), le32(0))
    #expect(request == expected)
    let write = [UInt8](PTPIP.operationRequest(0x9205, transactionID: 6, params: [0xD21E], dataOut: true))
    #expect(Array(write[8..<12]) == le32(2))
}

@Test func dataOutPacketsWrapTheValue() {
    let start = bytes(le32(20), le32(9), le32(6), le64(4))
    #expect([UInt8](PTPIP.startData(transactionID: 6, length: 4)) == start)
    let end = bytes(le32(16), le32(12), le32(6), [0x20, 3, 0, 0])
    #expect([UInt8](PTPIP.endData(transactionID: 6, payload: Data([0x20, 3, 0, 0]))) == end)
}

@Test func readerAssemblesPacketsFromChunks() throws {
    var reader = PTPIPPacketReader()
    let stream = packet(7, bytes(le16(0x2001), le32(1))) + packet(8, bytes(le16(0xC203), le32(0)))
    reader.feed(stream.prefix(5))
    #expect(try reader.next() == nil)
    reader.feed(stream.dropFirst(5).prefix(10))
    #expect(try reader.next() == PTPIPPacket(type: 7, payload: Data(bytes(le16(0x2001), le32(1)))))
    #expect(try reader.next() == nil)
    reader.feed(stream.dropFirst(15))
    #expect(try reader.next()?.type == 8)
    #expect(try reader.next() == nil)
}

@Test func readerRejectsBrokenLength() {
    var reader = PTPIPPacketReader()
    reader.feed(Data(bytes(le32(4), le32(7))))
    #expect(throws: PTPIPError.self) { try reader.next() }
}

@Test func initReplyIsAcceptedOrRejected() throws {
    let name: [UInt8] = Array("ZV-E10".utf16).flatMap { le16($0) } + [0, 0]
    let ack = PTPIPPacket(type: 2, payload: Data(bytes(le32(77), [UInt8](repeating: 0xAB, count: 16), name, le32(0x0001_0000))))
    #expect(try PTPIP.parseInitReply(ack) == .accepted(connectionNumber: 77, cameraName: "ZV-E10"))
    // не сопряжено: камера отвечает Init Fail с причиной 1
    #expect(try PTPIP.parseInitReply(PTPIPPacket(type: 5, payload: Data(le32(1)))) == .rejected(reason: 1))
}

@Test func transactionCollectsDataAndSkipsOtherTransactions() {
    var t = PTPIPTransaction(transactionID: 5)
    #expect(t.consume(PTPIPPacket(type: 9, payload: Data(bytes(le32(5), le64(3))))) == nil)
    // остаток оборванной транзакции 4 — мимо
    #expect(t.consume(PTPIPPacket(type: 12, payload: Data(bytes(le32(4), [9, 9])))) == nil)
    #expect(t.consume(PTPIPPacket(type: 7, payload: Data(bytes(le16(0x2019), le32(4))))) == nil)
    #expect(t.consume(PTPIPPacket(type: 10, payload: Data(bytes(le32(5), [1, 2])))) == nil)
    #expect(t.consume(PTPIPPacket(type: 12, payload: Data(bytes(le32(5), [3])))) == nil)
    let reply = t.consume(PTPIPPacket(type: 7, payload: Data(bytes(le16(0x2001), le32(5), le32(7)))))
    #expect(reply == PTPReply(code: 0x2001, params: [7], data: Data([1, 2, 3])))
}

@Test func responseWithoutDataPhase() {
    var t = PTPIPTransaction(transactionID: 1)
    #expect(t.consume(PTPIPPacket(type: 7, payload: Data(bytes(le16(0x2001), le32(1))))) == PTPReply(code: 0x2001))
}

@Test func eventPacketBecomesPTPEvent() {
    let event = PTPIP.parseEvent(PTPIPPacket(type: 8, payload: Data(bytes(le16(0xC203), le32(0), le32(0xD21E)))))
    #expect(event == PTPEvent(code: 0xC203, params: [0xD21E]))
    #expect(PTPIP.parseEvent(PTPIPPacket(type: 13, payload: Data())) == nil)
}

@Test func ssdpFindsTheSonyCamera() {
    #expect(String(decoding: SSDP.searchRequest(), as: UTF8.self).contains("ST: urn:schemas-sony-com:service:DigitalImaging:1"))
    // ответ ZV-E10 из домашней сети (MAC в USN заменён нулями)
    let reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT: \r\nLOCATION: http://192.168.1.194:64321/dd.xml\r\n"
        + "SERVER: UPnP/1.0 SonyImagingDevice/1.0\r\nST: urn:schemas-sony-com:service:DigitalImaging:1\r\n"
        + "USN: uuid:00000000-0005-0010-8000-000000000000::urn:schemas-sony-com:service:DigitalImaging:1\r\n\r\n"
    #expect(SSDP.parse(reply) == SSDP.Response(host: "192.168.1.194", usn: "uuid:00000000-0005-0010-8000-000000000000"))
    #expect(SSDP.parse("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:1900/igd.xml\r\nST: upnp:rootdevice\r\n\r\n") == nil)
}

@Test func cameraAccessPointIsRecognisedByItsAddress() {
    // в режиме Wi-Fi Direct камера сама раздаёт сеть и всегда берёт себе 192.168.122.1
    #expect(PTPIP.isCameraAccessPoint("192.168.122.1"))
    #expect(!PTPIP.isCameraAccessPoint("192.168.1.194"))
    #expect(!PTPIP.isCameraAccessPoint("192.168.122.10"))
}

@Test func readerRejectsAnAbsurdLength() {
    // сбившийся поток: «длина» в гигабайт — не ждать её вечно, а считать поток сломанным
    var reader = PTPIPPacketReader()
    var bytes = Data()
    bytes.append(contentsOf: [0x00, 0x00, 0x00, 0x40, 0x07, 0x00, 0x00, 0x00])
    reader.feed(bytes)
    #expect(throws: PTPIPError.self) { try reader.next() }
}

@Test func transactionKnowsExpectedLengthFromStartData() {
    var transaction = PTPIPTransaction(transactionID: 7)
    #expect(transaction.expected == nil)
    var start = Data()
    start.appendLE(UInt32(7))
    start.appendLE(UInt64(9_000_000))
    #expect(transaction.consume(PTPIPPacket(type: PTPIP.PacketType.startData, payload: start)) == nil)
    #expect(transaction.expected == 9_000_000)
    // старт чужой транзакции — не наш размер
    var other = PTPIPTransaction(transactionID: 8)
    _ = other.consume(PTPIPPacket(type: PTPIP.PacketType.startData, payload: start))
    #expect(other.expected == nil)
}

@Test func dataProgressCountsOnlyDuringDataPhase() {
    let progress = DataProgress()
    progress.add(500)
    #expect(progress.current == nil)
    progress.begin(expected: 100)
    progress.add(40)
    #expect(progress.current?.received == 40)
    #expect(progress.current?.expected == 100)
    progress.finish()
    #expect(progress.current == nil)
}
