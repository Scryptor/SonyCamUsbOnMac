import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

// MARK: - Рамки фокуса в кадре live view

@Test func realLiveViewFrameHasAnEmptyFocusFrameBlock() throws {
    // дамп ZV-E10: блок FocalFrameInfo версии 1.01 есть (смещение 1012, 120 байт), рамок нет — тёмная комната
    let data = try Fixture.data("lv_frame_raw")
    #expect(FocusFrame.parse(liveView: data).isEmpty)
    #expect(LiveViewFrame.jpeg(from: data) != nil)
}

@Test func focusFramesSurviveEncodingAndKeepTheirCentre() {
    let frames = [
        FocusFrame(kind: .focus, rect: CGRect(x: 0.4, y: 0.3, width: 0.1, height: 0.2), focused: true),
        FocusFrame(kind: .tracking, rect: CGRect(x: 0.6, y: 0.5, width: 0.15, height: 0.2), focused: false),
    ]
    let parsed = FocusFrame.parse(info: FocusFrame.encode(frames))
    #expect(parsed.count == 2)
    #expect(parsed[0].kind == .focus && parsed[0].focused)
    #expect(abs(parsed[0].rect.midX - 0.45) < 0.001 && abs(parsed[0].rect.midY - 0.4) < 0.001)
    #expect(abs(parsed[0].rect.width - 0.1) < 0.001)
    #expect(parsed[1].kind == .tracking && !parsed[1].focused)
}

@Test func focusFramesAreReadFromTheLiveViewHeader() throws {
    // как отдаёт камера: смещение и размер JPEG, смещение и размер рамок, потом блоки
    let info = FocusFrame.encode([FocusFrame(kind: .focus, rect: CGRect(x: 0.2, y: 0.2, width: 0.1, height: 0.1), focused: true)])
    let jpeg = Data([0xFF, 0xD8, 0xFF, 0xE0, 1, 2, 3])
    var data = Data()
    for v in [16 + info.count, jpeg.count, 16, info.count] {
        for shift in stride(from: 0, to: 32, by: 8) { data.append(UInt8(v >> shift & 0xFF)) }
    }
    data += info + jpeg
    #expect(LiveViewFrame.jpeg(from: data) == jpeg)
    let frames = FocusFrame.parse(liveView: data)
    #expect(frames.count == 1)
    #expect(abs(frames[0].rect.midX - 0.25) < 0.001)
    // битый размер — рамок нет, а не падение
    var broken = data
    broken[12] = 0xFF
    #expect(FocusFrame.parse(liveView: broken).isEmpty)
}

// MARK: - Фокус касанием

@Test func touchFocusPicksTheCommandByFocusArea() throws {
    let controls = Set(try SonyExtInfo(data: Fixture.data("sony_extdeviceinfo")).controlCodes)
    // у ZV-E10 есть обе команды
    #expect(controls.contains(SonyControl.afAreaPosition) && controls.contains(SonyControl.remoteTouch))
    // «Точка M» — точка AF; «Широкая» — касание экрана камеры; MF — никак
    func method(_ mode: Int64?, _ area: Int64?, _ enabled: Int64?, _ function: Int64?, _ codes: Set<UInt16>) -> TouchFocus.Method? {
        TouchFocus.method(focusMode: mode, focusArea: area, remoteTouchEnabled: enabled, touchFunction: function, controls: codes)
    }
    // у ZV-E10 касание экрана в видео — слежение (0xD283 = 4)
    #expect(method(0x8004, 0x102, 1, 4, controls) == .areaPosition)
    #expect(method(0x8004, 0x2, 1, 4, controls) == .areaPosition)
    #expect(method(0x8004, 0x205, 1, 4, controls) == .areaPosition)
    #expect(method(0x8004, 0x1, 1, 4, controls) == .remoteTouch)
    #expect(method(0x8004, 0x1, 1, 9, controls) == .remoteTouch)
    #expect(method(0x8004, 0x1, 0, 4, controls) == nil)
    #expect(method(1, 0x102, 1, 4, controls) == nil)
    #expect(method(0x8004, 0x102, 1, 4, []) == nil)
    // касание-спуск сделало бы снимок, Touch AE — экспозицию; функция неизвестна — тоже нет
    #expect(method(0x8004, 0x1, 1, 2, controls) == nil)
    #expect(method(0x8004, 0x1, 1, 5, controls) == nil)
    #expect(method(0x8004, 0x1, 1, nil, controls) == nil)
    // точка AF от функции касания не зависит
    #expect(method(0x8004, 0x103, 1, 2, controls) == .areaPosition)
}

@Test func touchFocusMapsTheFrameTo640x480() {
    #expect(TouchFocus.encode(x: 0, y: 0) == 0)
    #expect(TouchFocus.encode(x: 1, y: 1) == (639 << 16 | 479))
    #expect(TouchFocus.encode(x: 0.5, y: 0.5) == (320 << 16 | 240))
    // за краем — край
    #expect(TouchFocus.encode(x: 1.4, y: -0.2) == 639 << 16)
    let back = TouchFocus.decode(TouchFocus.encode(x: 0.25, y: 0.75))
    #expect(abs(back.x - 0.25) < 0.002 && abs(back.y - 0.75) < 0.002)
}

// MARK: - Зум и шкала фокуса

@Test func zoomBarFromTheRealCamera() {
    // ZV-E10 с китовым объективом на широком конце: один отрезок, в нём — в начале
    let bar = PowerZoom.Bar(0x0100_0000)
    #expect(bar.boxes == 1 && bar.box == 0 && bar.position == 0)
    #expect(bar.fraction == 0)
    #expect(PowerZoom.Bar(0x0201_0032).fraction == 0.75)
    #expect(PowerZoom.Bar(0).fraction == nil)
    #expect(PowerZoom.label(scale: 1000) == "×1,0")
    #expect(PowerZoom.label(scale: 1500) == "×1,5")
    #expect(PowerZoom.label(scale: nil) == nil)
    #expect(PowerZoom.isAvailable(zoomEnabled: 1, controls: [SonyControl.zoomOperation]))
    #expect(!PowerZoom.isAvailable(zoomEnabled: 0, controls: [SonyControl.zoomOperation]))
}

@Test func focusDriveSplitsUnitsIntoCommands() {
    #expect(FocusDrive.commands(units: 17) == [7, 7, 3])
    #expect(FocusDrive.commands(units: -2) == [-1, -1])
    #expect(FocusDrive.commands(units: 0).isEmpty)
    #expect(FocusDrive.value(.large, nearer: true) == -7)
    #expect(FocusDrive.value(.small, nearer: false) == 1)
    #expect(FocusDrive.steps(forDrag: -0.1) == -28)
    #expect(FocusDrive.steps(forDrag: 0.001) == 0)
    // в AF-C (как в дампе) Near/Far закрыт
    #expect(!FocusDrive.isAvailable(nearFarEnabled: 0, controls: [SonyControl.nearFar]))
    #expect(FocusDrive.isAvailable(nearFarEnabled: 1, controls: [SonyControl.nearFar]))
}

// MARK: - Команды сеанса

@Test func sessionKnowsControlCodesAndSendsTypedControls() async throws {
    let cam = FakeCamera(props: [])
    let session = try await readySession(cam)
    #expect(await session.controlCodes.contains(SonyControl.zoomOperation))
    // зум к широкому — INT8 −1, точка AF — UINT32, Near/Far — INT16
    try await session.control(SonyControl.zoomOperation, value: -1, type: PTPType.int8)
    try await session.control(SonyControl.afAreaPosition, value: TouchFocus.encode(x: 0.5, y: 0.5), type: PTPType.uint32)
    try await session.control(SonyControl.nearFar, value: -3, type: PTPType.int16)
    try await session.pressButton(SonyControl.remoteTouchCancel)
    let controls = cam.sent.filter { $0.op == SonyOp.controlDevice }
    #expect(controls.map { $0.params } == [[0xD2DD], [0xD2DC], [0xD2D1], [0xD2E5], [0xD2E5]])
    #expect(controls[0].outData == Data([0xFF]))
    #expect(controls[1].outData == Data([0xF0, 0x00, 0x40, 0x01]))
    #expect(controls[2].outData == Data([0xFD, 0xFF]))
    #expect(controls[3].outData == Data([0x02, 0x00]) && controls[4].outData == Data([0x01, 0x00]))
}
