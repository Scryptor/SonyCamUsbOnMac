import Foundation
import Testing
@testable import ZVCore

@Test func parsesGetWithQueryAndHeaders() throws {
    let raw = "GET /state?x=1 HTTP/1.1\r\nHost: localhost\r\nX-ZV-Token: abc\r\n\r\n"
    guard case let .complete(request, consumed) = HTTPRequest.parse(Data(raw.utf8)) else {
        Issue.record("не разобран")
        return
    }
    #expect(request.method == "GET" && request.path == "/state" && request.query["x"] == "1")
    #expect(request.headers["x-zv-token"] == "abc")
    #expect(consumed == raw.utf8.count)
}

@Test func waitsForFullBodyAndRejectsGarbage() {
    let partial = "POST /action HTTP/1.1\r\nContent-Length: 10\r\n\r\n{\"a\":"
    #expect(HTTPRequest.parse(Data(partial.utf8)) == .incomplete)
    #expect(HTTPRequest.parse(Data("NOT HTTP\r\n\r\n".utf8)) == .invalid)
    let full = "POST /action HTTP/1.1\r\nContent-Length: 2\r\n\r\n{}"
    guard case let .complete(request, _) = HTTPRequest.parse(Data(full.utf8)) else {
        Issue.record("не разобран")
        return
    }
    #expect(request.body == Data("{}".utf8))
}

@Test func encodesResponse() {
    let text = String(decoding: HTTPResponse.encode(status: 200, contentType: "application/json", body: Data("{}".utf8)), as: UTF8.self)
    #expect(text.hasPrefix("HTTP/1.1 200 OK\r\n"))
    #expect(text.contains("Content-Length: 2\r\n"))
    #expect(text.hasSuffix("\r\n\r\n{}"))
}

@Test func decodesActions() throws {
    let d = JSONDecoder()
    #expect(try d.decode(AutomationAction.self, from: Data(#"{"action":"step","offset":-1}"#.utf8)) == .step(-1))
    #expect(try d.decode(AutomationAction.self, from: Data(#"{"action":"request","setting":"iso","value":640}"#.utf8)) == .request(.iso, 640))
    #expect(try d.decode(AutomationAction.self, from: Data(#"{"action":"openAllSettings","group":"color"}"#.utf8)) == .openAllSettings(.color))
    #expect(try d.decode(AutomationAction.self, from: Data(#"{"action":"openFn"}"#.utf8)) == .openFn(nil))
    #expect(try d.decode(AutomationAction.self, from: Data(#"{"action":"toggleRecording","confirm":true}"#.utf8)) == .toggleRecording(confirm: true))
    #expect(throws: (any Error).self) { try d.decode(AutomationAction.self, from: Data(#"{"action":"nope"}"#.utf8)) }
}

@Test func decodesWiFiActions() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    #expect(try decode(#"{"action":"pairWiFi"}"#) == .pairWiFi)
    #expect(try decode(#"{"action":"cancelPairing"}"#) == .cancelPairing)
    #expect(try decode(#"{"action":"forgetWiFi"}"#) == .forgetWiFi)
}

@Test func decodesShowInfo() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    // подсказки строки состояния: о подключении, о кадрах; без имени — закрыть
    #expect(try decode(#"{"action":"showInfo","name":"connection"}"#) == .showInfo("connection"))
    #expect(try decode(#"{"action":"showInfo","name":"frames"}"#) == .showInfo("frames"))
    #expect(try decode(#"{"action":"showInfo"}"#) == .showInfo(nil))
}
