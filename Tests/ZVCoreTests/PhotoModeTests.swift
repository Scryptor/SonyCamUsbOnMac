import Foundation
import Testing
@testable import ZVCore

@Test func captureModeFollowsExposureProgram() {
    #expect(CaptureMode(exposureProgram: 0x0007_8053) == .video)
    #expect(CaptureMode(exposureProgram: 0x0009_805C) == .video)   // S&Q M
    #expect(CaptureMode(exposureProgram: 0x1) == .photo)
    #expect(CaptureMode(exposureProgram: 0x0004_8000) == .photo)   // интеллектуальный авто
}

@Test func modeCounterpartKeepsExposureLetter() {
    let settable: [Int64] = [0x10002, 0x20003, 0x30004, 0x1, 0x78050, 0x78051, 0x78052, 0x78053, 0x78054, 0x9805C, 0x48000]
    #expect(CaptureMode.counterpart(of: 0x78053, to: .photo, settable: settable) == 0x1)      // видео M → M
    #expect(CaptureMode.counterpart(of: 0x78051, to: .photo, settable: settable) == 0x20003)  // видео A → A
    #expect(CaptureMode.counterpart(of: 0x9805C, to: .photo, settable: settable) == 0x1)      // S&Q M → M
    #expect(CaptureMode.counterpart(of: 0x1, to: .video, settable: settable) == 0x78053)      // M → видео M
    #expect(CaptureMode.counterpart(of: 0x48000, to: .video, settable: settable) == 0x78050)  // авто → видео P
    #expect(CaptureMode.counterpart(of: 0x1, to: .photo, settable: settable) == nil)          // уже фото
    #expect(CaptureMode.counterpart(of: 0x1, to: .video, settable: [0x1]) == nil)             // камера не даёт
}

@Test func parsesObjectInfoFilenameAndSize() throws {
    let info = try ObjectInfo(data: FakeCamera.objectInfo(filename: "DSC01234.JPG", size: 1_234_567, format: 0x3801))
    #expect(info.filename == "DSC01234.JPG")
    #expect(info.compressedSize == 1_234_567)
    #expect(info.format == 0x3801)
}

private func photoCamera() -> FakeCamera {
    FakeCamera(props: [
        .init(code: SonyProp.focusMode, type: PTPType.uint16, current: 1, values: [1, 0x8004]),  // MF — без ожидания фокуса
        .init(code: SonyProp.objectInMemory, type: PTPType.uint16, getSet: 0, current: 0, values: []),
    ])
}

@Test func capturePressesShutterAndDownloadsEveryObject() async throws {
    let cam = photoCamera()
    cam.capturedFiles = [("DSC00001.ARW", Data([1, 2, 3])), ("DSC00001.JPG", Data([0xFF, 0xD8, 9]))]
    let s = try await readySession(cam)
    let files = try await s.capturePhoto(download: true)
    #expect(files.map(\.filename) == ["DSC00001.ARW", "DSC00001.JPG"])
    #expect(files.last?.data == Data([0xFF, 0xD8, 9]))
    let presses = cam.sent.filter { $0.op == 0x9207 }
    #expect(presses.map { $0.params[0] } == [0xD2C1, 0xD2C2, 0xD2C2, 0xD2C1])
    #expect(presses.map(\.outData) == [Data([2, 0]), Data([2, 0]), Data([1, 0]), Data([1, 0])])
}

@Test func captureWithoutDownloadOnlyPressesShutter() async throws {
    let cam = photoCamera()
    cam.capturedFiles = [("DSC00002.JPG", Data([0xFF, 0xD8]))]
    let s = try await readySession(cam)
    #expect(try await s.capturePhoto(download: false).isEmpty)
    #expect(!cam.sent.contains { $0.op == 0x1009 && $0.params == [0xFFFF_C001] })
}

@Test func captureBeforeReadyThrows() async throws {
    let cam = photoCamera()
    let s = CameraSession(transport: cam, timing: .test)
    try await s.start()
    await #expect(throws: SessionError.notReady) { try await s.capturePhoto(download: true) }
    #expect(!cam.sent.contains { $0.op == 0x9207 })
}

@Test func uniqueNameAddsCounter() {
    let taken: Set<String> = ["DSC01234.JPG", "DSC01234 (2).JPG"]
    #expect(FileNaming.unique("DSC01234.JPG") { taken.contains($0) } == "DSC01234 (3).JPG")
    #expect(FileNaming.unique("DSC09999.ARW") { taken.contains($0) } == "DSC09999.ARW")
}

@Test func frameGrabNameUsesLocalTime() {
    let calendar = Calendar(identifier: .gregorian)
    let date = calendar.date(from: DateComponents(year: 2026, month: 10, day: 24, hour: 20, minute: 31, second: 5))!
    #expect(FileNaming.frameGrab(at: date, calendar: calendar) == "Кадр 2026-10-24 20-31-05.jpg")
}

@Test func decodesPhotoModeActions() throws {
    func decode(_ json: String) throws -> AutomationAction { try JSONDecoder().decode(AutomationAction.self, from: Data(json.utf8)) }
    #expect(try decode(#"{"action":"switchMode","mode":"photo"}"#) == .switchMode(.photo))
    #expect(try decode(#"{"action":"capture","confirm":true}"#) == .capture(confirm: true))
    #expect(try decode(#"{"action":"grabFrame"}"#) == .grabFrame)
    #expect(try decode(#"{"action":"toggleControls"}"#) == .toggleControls)
    #expect(try decode(#"{"action":"dismissReview"}"#) == .dismissReview)
    #expect(try decode(#"{"action":"setPreference","key":"reviewSeconds","value":"5"}"#) == .setPreference("reviewSeconds", "5"))
    #expect(try decode(#"{"action":"openShot","index":0}"#) == .openShot(0))
}

@Test func photoModeLayoutSwapsVideoTilesForPhotoOnes() {
    #expect(SettingCatalog.fnBottomRow(for: .photo) == [.setting(.focusArea), .setting(.metering), .setting(.dro),
                                                        .setting(.fileFormat), .setting(.aspectRatio), .allSettings])
    #expect(SettingCatalog.fnBottomRow(for: .video) == SettingCatalog.fnBottomRow)
    #expect(SettingCatalog.infoLine(for: .photo) == SettingCatalog.infoLine + [.fileFormat])
    #expect(SettingCatalog.infoLine(for: .video) == SettingCatalog.infoLine)
}
