import Foundation
import Testing
@testable import ZVCore

// Тесты к замечаниям финального обзора.

@Test func parserSurvivesEmptyQueryPairs() {
    guard case let .complete(request, _) = HTTPRequest.parse(Data("GET /state?=&==&a HTTP/1.1\r\n\r\n".utf8)) else {
        Issue.record("не разобран")
        return
    }
    #expect(request.path == "/state")
    #expect(request.query["a"] == "")
}

@Test func parserRejectsOversizedBody() {
    #expect(HTTPRequest.parse(Data("POST /action HTTP/1.1\r\nContent-Length: 1000000\r\n\r\n".utf8)) == .invalid)
}

@Test func applySkipsDependentSettingsWhenBaseWasNotApplied() async throws {
    let cam = FakeCamera(props: [
        .init(code: SonyProp.whiteBalance, type: PTPType.uint16, current: 2, values: [2, 4]),
        .init(code: SonyProp.abShift, type: PTPType.uint8, current: 192, values: [188, 192, 196]),
        .init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [200, 400]),
        .init(code: SonyProp.exposureComp, type: PTPType.int16, current: 0, values: [0, 300]),
    ])
    let s = try await readySession(cam)
    // вспышка (7) в этом режиме недоступна — сдвиг не должен лечь в хранилище текущего режима ББ;
    // ISO 12345 недопустимо — экспокоррекцию тоже не трогаем
    let result = await s.apply(Preset(name: "Вспышка", values: [.whiteBalance: 7, .wbShiftAB: 196, .iso: 12345, .exposureComp: 300]))
    #expect(result.skipped == [.whiteBalance, .wbShiftAB, .iso, .exposureComp])
    #expect(!cam.sent.contains { $0.op == 0x9205 && [UInt32(SonyProp.abShift), UInt32(SonyProp.exposureComp)].contains($0.params[0]) })
}

@Test func liveViewWaitsOutBusyCamera() async throws {
    let cam = FakeCamera(props: [])
    cam.liveViewReplies = [PTPReply(code: 0x2019), PTPReply(code: 0x2001, data: try Fixture.data("lv_frame_raw"))]
    let s = try await readySession(cam)
    #expect(try await s.nextLiveViewFrame().count == 20736)
}

@Test func scrollStepperCountsOnlyVerticalWheelInsideTarget() {
    var stepper = ScrollStepper()
    #expect(stepper.feed(dx: 0, dy: -45, precise: true, momentum: false, inside: true) == 2)   // остаток −5
    #expect(stepper.feed(dx: 0, dy: -15, precise: true, momentum: false, inside: true) == 1)
    #expect(stepper.feed(dx: 30, dy: 2, precise: true, momentum: false, inside: true) == 0)    // горизонтально
    #expect(stepper.feed(dx: 0, dy: -60, precise: true, momentum: true, inside: true) == 0)    // инерция
    #expect(stepper.feed(dx: 0, dy: -60, precise: true, momentum: false, inside: false) == 0)  // не над лентой
    #expect(stepper.feed(dx: 0, dy: 1, precise: false, momentum: false, inside: true) == -1)   // колесо мыши: 1 строка
}

@Test func shiftPadWritesOnlyTheDominantAxis() {
    #expect(ShiftPad.axes(dx: 30, dy: 3) == ShiftPad.Axes(ab: true, gm: false))
    #expect(ShiftPad.axes(dx: 2, dy: -25) == ShiftPad.Axes(ab: false, gm: true))
    #expect(ShiftPad.axes(dx: 20, dy: 18) == ShiftPad.Axes(ab: true, gm: true))
    #expect(ShiftPad.axes(dx: 1, dy: 1) == ShiftPad.Axes(ab: false, gm: false))   // клик без движения
}

@Test func trackerConnectsOnceAndRetriesOnlyWhileAttached() {
    var t = ConnectionTracker<String>()
    #expect(t.appeared("A") == .connect("A"))
    #expect(t.appeared("A") == .none)
    #expect(t.failed("A") == .retry("A", after: .seconds(2)))
    #expect(t.failed("A") == .retry("A", after: .seconds(4)))
    #expect(t.disappeared("A") == .disconnect(thenConnect: nil))
    #expect(t.failed("A") == .none)   // отменённая попытка к уже отключённой камере
}

@Test func trackerPrefersNewCameraAndCapsBackoff() {
    var t = ConnectionTracker<String>()
    _ = t.appeared("A")
    #expect(t.appeared("B") == .none)                            // пока работает A, вторую не трогаем
    #expect(t.disappeared("A") == .disconnect(thenConnect: "B")) // A ушла — берём B
    for expected in [2, 4, 8, 10, 10] { #expect(t.failed("B") == .retry("B", after: .seconds(expected))) }
    t.connected("B")
    #expect(t.failed("B") == .retry("B", after: .seconds(2)))     // после успеха отсчёт заново
}
