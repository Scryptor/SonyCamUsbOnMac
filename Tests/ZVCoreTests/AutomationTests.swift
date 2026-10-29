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

@Test func decodesDebugPanelActions() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    // панель отладки: на вкладке, на той, что была, закрыть
    #expect(try decode(#"{"action":"showDebug","name":"log"}"#) == .showDebug("log"))
    #expect(try decode(#"{"action":"showDebug"}"#) == .showDebug(nil))
    #expect(try decode(#"{"action":"hideDebug"}"#) == .hideDebug)
}

@Test func decodesGalleryHistogramAndZoomActions() throws {
    func decode(_ json: String) throws -> AutomationAction { try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8)) }
    #expect(try decode(#"{"action":"moveHistogram","x":0.5,"y":0.25}"#) == .moveHistogram(x: 0.5, y: 0.25))
    #expect(try decode(#"{"action":"openGallery","index":2}"#) == .openGallery(2))
    #expect(try decode(#"{"action":"openGallery"}"#) == .openGallery(0))
    #expect(try decode(#"{"action":"galleryStep","offset":-1}"#) == .galleryStep(-1))
    #expect(try decode(#"{"action":"closeGallery"}"#) == .closeGallery)
    #expect(try decode(#"{"action":"deleteShot","index":1}"#) == .deleteShot(1))
    #expect(try decode(#"{"action":"deleteShot"}"#) == .deleteShot(nil))
    #expect(try decode(#"{"action":"undoDeleteShot"}"#) == .undoDeleteShot)
    #expect(try decode(#"{"action":"setZoom","factor":4,"x":0.3,"y":0.4}"#) == .setZoom(factor: 4, x: 0.3, y: 0.4))
    #expect(try decode(#"{"action":"setZoom","factor":2}"#) == .setZoom(factor: 2, x: nil, y: nil))
    #expect(try decode(#"{"action":"resetZoom"}"#) == .resetZoom)
    #expect(try decode(#"{"action":"toggleStatusPin"}"#) == .toggleStatusPin)
}

@Test func decodesPresetWindowActions() throws {
    func decode(_ json: String) throws -> AutomationAction { try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8)) }
    #expect(try decode(#"{"action":"openPresetMenu"}"#) == .openPresetMenu)
    #expect(try decode(#"{"action":"closePresetMenu"}"#) == .closePresetMenu)
    #expect(try decode(#"{"action":"openPresets"}"#) == .openPresets(nil))
    #expect(try decode(#"{"action":"openPresets","index":2}"#) == .openPresets(2))
    #expect(try decode(#"{"action":"closePresets"}"#) == .closePresets)
    #expect(try decode(#"{"action":"selectPreset","index":1}"#) == .selectPreset(1))
    #expect(try decode(#"{"action":"setPresetValue","index":0,"setting":"iso","value":800}"#) == .setPresetValue(0, .iso, 800))
    #expect(try decode(#"{"action":"setPresetIncluded","index":0,"setting":"shutter","included":false}"#)
        == .setPresetIncluded(0, .shutter, false))
    #expect(try decode(#"{"action":"movePreset","from":2,"to":0}"#) == .movePreset(from: 2, to: 0))
    #expect(try decode(#"{"action":"duplicatePreset","index":1}"#) == .duplicatePreset(1))
    #expect(try decode(#"{"action":"recapturePreset","index":1}"#) == .recapturePreset(1))
    #expect(try decode(#"{"action":"beginPresetPreview","index":0}"#) == .beginPresetPreview(0))
    #expect(try decode(#"{"action":"endPresetPreview"}"#) == .endPresetPreview)
    #expect(try decode(#"{"action":"savePresetChanges"}"#) == .savePresetChanges)
    #expect(try decode(#"{"action":"openSavePreset"}"#) == .openSavePreset)
    #expect(try decode(#"{"action":"chooseNewPreset"}"#) == .chooseNewPreset)
    #expect(try decode(#"{"action":"closeSavePreset"}"#) == .closeSavePreset)
}

@Test func decodesCameraActions() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    #expect(try decode(#"{"action":"selectCamera","id":"wifi:127.0.0.1:15741"}"#) == .selectCamera("wifi:127.0.0.1:15741"))
    #expect(try decode(#"{"action":"renameCamera","id":"3f2a","name":"Левая"}"#) == .renameCamera("3f2a", "Левая"))
    #expect(try decode(#"{"action":"forgetCamera","id":"3f2a"}"#) == .forgetCamera("3f2a"))
    #expect(try decode(#"{"action":"pairCamera","id":"wifi:127.0.0.1:15742"}"#) == .pairCamera("wifi:127.0.0.1:15742"))
    #expect(try decode(#"{"action":"searchCameras"}"#) == .searchCameras)
    #expect(try decode(#"{"action":"addToStrip","id":"3f2a"}"#) == .addToStrip("3f2a"))
    #expect(try decode(#"{"action":"removeFromStrip","id":"3f2a"}"#) == .removeFromStrip("3f2a"))
    #expect(try decode(#"{"action":"linkCamera","id":"3f2a","linked":true}"#) == .linkCamera("3f2a", true))
    #expect(try decode(#"{"action":"linkCamera","id":"3f2a","linked":false}"#) == .linkCamera("3f2a", false))
    #expect(try decode(#"{"action":"moveInStrip","id":"3f2a","onto":"9c1b"}"#) == .moveInStrip("3f2a", onto: "9c1b"))
    #expect(throws: (any Error).self) { try decode(#"{"action":"selectCamera"}"#) }
}

@Test func decodesMonitorAssistActions() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    #expect(try decode(#"{"action":"openAssist"}"#) == .openAssist(nil))
    #expect(try decode(#"{"action":"openAssist","name":"peaking"}"#) == .openAssist("peaking"))
    #expect(try decode(#"{"action":"closeAssist"}"#) == .closeAssist)
    #expect(try decode(#"{"action":"setAssist","name":"zebra","value":"95"}"#) == .setAssist("zebra", "95"))
    // число вместо строки — то же
    #expect(try decode(#"{"action":"setAssist","name":"zebra","value":95}"#) == .setAssist("zebra", "95"))
    #expect(try decode(#"{"action":"toggleAssist","name":"falseColor"}"#) == .toggleAssist("falseColor"))
    #expect(try decode(#"{"action":"assistOff"}"#) == .assistOff)
    #expect(throws: (any Error).self) { try decode(#"{"action":"setAssist","name":"zebra"}"#) }
    #expect(try decode(#"{"action":"toggleWaveform"}"#) == .toggleWaveform)
    #expect(try decode(#"{"action":"waveformMode","mode":"parade"}"#) == .waveformMode("parade"))
    #expect(try decode(#"{"action":"moveWaveform","x":1,"y":0}"#) == .moveWaveform(x: 1, y: 0))
    #expect(try decode(#"{"action":"referenceFromFrame"}"#) == .referenceFromFrame)
    #expect(try decode(#"{"action":"referenceFromShot","index":1}"#) == .referenceFromShot(1))
    #expect(try decode(#"{"action":"referenceFromFile","path":"~/Pictures/scene.png"}"#) == .referenceFromFile("~/Pictures/scene.png"))
    #expect(try decode(#"{"action":"toggleOverlay"}"#) == .toggleOverlay)
    #expect(try decode(#"{"action":"moveOverlayPanel","x":0,"y":0.5}"#) == .moveOverlayPanel(x: 0, y: 0.5))
    #expect(try decode(#"{"action":"toggleTouchFocus"}"#) == .toggleTouchFocus)
    #expect(try decode(#"{"action":"touchFocus","x":0.25,"y":0.5}"#) == .touchFocus(x: 0.25, y: 0.5))
    #expect(try decode(#"{"action":"cancelTouch"}"#) == .cancelTouch)
    #expect(try decode(#"{"action":"zoomStart","name":"tele"}"#) == .zoomStart("tele"))
    #expect(try decode(#"{"action":"zoomStop"}"#) == .zoomStop)
    #expect(try decode(#"{"action":"focusStep","offset":-7}"#) == .focusStep(-7))
}

@Test func decodesFocusMarkActions() throws {
    func decode(_ json: String) throws -> AutomationAction {
        try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8))
    }
    #expect(try decode(#"{"action":"focusMark","mark":"A","op":"save"}"#) == .focusMark(mark: "A", op: "save"))
    #expect(try decode(#"{"action":"focusMark","mark":"B","op":"go"}"#) == .focusMark(mark: "B", op: "go"))
    #expect(try decode(#"{"action":"focusMark","mark":"A","op":"clear"}"#) == .focusMark(mark: "A", op: "clear"))
    // что метка и действие бывают только такие, решает программа (409 с подсказкой), а не разбор тела
    #expect(try decode(#"{"action":"focusMark","mark":"C","op":"jump"}"#) == .focusMark(mark: "C", op: "jump"))
    #expect(throws: (any Error).self) { try decode(#"{"action":"focusMark","mark":"A"}"#) }
    #expect(throws: (any Error).self) { try decode(#"{"action":"focusMark","op":"go"}"#) }
    #expect(try decode(#"{"action":"focusPullTime","seconds":4}"#) == .focusPullTime(4))
    #expect(try decode(#"{"action":"focusPullTime","seconds":0}"#) == .focusPullTime(0))
    // любое число разбирается; допустимо ли оно (0, 1, 2, 4, 8), решает программа
    #expect(try decode(#"{"action":"focusPullTime","seconds":2.5}"#) == .focusPullTime(2.5))
    #expect(throws: (any Error).self) { try decode(#"{"action":"focusPullTime"}"#) }
    #expect(throws: (any Error).self) { try decode(#"{"action":"focusPullTime","seconds":"4"}"#) }
    #expect(try decode(#"{"action":"focusPullStop"}"#) == .focusPullStop)
}
