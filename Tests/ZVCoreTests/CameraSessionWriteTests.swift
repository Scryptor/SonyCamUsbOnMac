import Foundation
import Testing
@testable import ZVCore

private let wbValues: [Int64] = [2, 4, 0x8011, 0x8010]

private func wb(_ current: Int64 = 2) -> FakeCamera.Prop {
    .init(code: SonyProp.whiteBalance, type: PTPType.uint16, current: current, values: wbValues)
}

@Test func setSendsRawValueAndConfirms() async throws {
    let cam = FakeCamera(props: [wb()])
    let s = try await readySession(cam)
    #expect(try await s.set(SonyProp.whiteBalance, to: 4) == .confirmed)
    let write = try #require(cam.sent.last { $0.op == 0x9205 && $0.params == [0x5005] })
    #expect(write.outData == Data([0x04, 0x00]))
}

@Test func setReportsNotAppliedWhenCameraIgnoresWrite() async throws {
    let cam = FakeCamera(props: [wb()])
    cam.applyWrites = false
    let s = try await readySession(cam)
    #expect(try await s.set(SonyProp.whiteBalance, to: 4) == .notApplied(actual: 2))
}

@Test func setRejectsValueOutsideList() async throws {
    let cam = FakeCamera(props: [wb()])
    let s = try await readySession(cam)
    await #expect(throws: SessionError.valueNotAllowed(0x5005, 14)) { try await s.set(SonyProp.whiteBalance, to: 0x000E) }
    #expect(!cam.sent.contains { $0.op == 0x9205 && $0.params == [0x5005] })
}

@Test func setRejectsDisplayOnlyProperty() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.exposureComp, type: PTPType.int16, enabled: 2, current: 0, values: [])])
    let s = try await readySession(cam)
    await #expect(throws: SessionError.notWritable(0x5010)) { try await s.set(SonyProp.exposureComp, to: 300) }
}

@Test func setBeforeReadyThrowsAndSendsNothing() async throws {
    let cam = FakeCamera(props: [wb()])
    let s = CameraSession(transport: cam, timing: .test)
    try await s.start()
    await #expect(throws: SessionError.notReady) { try await s.set(SonyProp.whiteBalance, to: 4) }
    #expect(!cam.sent.contains { $0.op == 0x9205 && $0.params == [0x5005] })
}

private func recordingState() -> FakeCamera.Prop {
    .init(code: SonyProp.movieRecordingState, type: PTPType.uint8, getSet: 0, enabled: 2, current: 0, values: [0, 1, 2])
}

/// Что ушло камере кнопкой записи: 2 — «нажать», 1 — «отпустить».
private func recordButton(_ cam: FakeCamera) -> [UInt8] {
    cam.sent.filter { $0.op == 0x9207 && $0.params == [0xD2C8] }.compactMap { $0.outData?.first }
}

@Test func toggleRecordingStartsWithPressOnly() async throws {
    // 0xD2C8 — «как удержание кнопки»: «отпустить» сразу за «нажать» камера иногда успевает выполнить — и запись
    // обрывается через полсекунды после старта
    let cam = FakeCamera(props: [recordingState()])
    let s = try await readySession(cam)
    #expect(try await s.toggleRecording())
    #expect(recordButton(cam) == [2])
}

@Test func toggleRecordingStopsOurRecordingWithReleaseOnly() async throws {
    let cam = FakeCamera(props: [recordingState()])
    let s = try await readySession(cam)
    _ = try await s.toggleRecording()
    let recording = try await s.toggleRecording()
    #expect(!recording)
    #expect(recordButton(cam) == [2, 1])
}

@Test func toggleRecordingStopsCameraStartedRecordingWithPressAndRelease() async throws {
    // запись начали кнопкой на камере: наша кнопка не нажата — нажать и отпустить, как раньше
    let cam = FakeCamera(props: [recordingState()])
    let s = try await readySession(cam)
    cam.setCurrent(SonyProp.movieRecordingState, 1)
    try await s.refresh()
    let recording = try await s.toggleRecording()
    #expect(!recording)
    #expect(recordButton(cam) == [2, 1])
}

@Test func toggleRecordingReleasesBeforeStartingAgain() async throws {
    // начали мы, а остановила камера сама: кнопка у нас ещё «нажата» — сначала отпустить, потом нажать
    let cam = FakeCamera(props: [recordingState()])
    let s = try await readySession(cam)
    _ = try await s.toggleRecording()
    cam.setCurrent(SonyProp.movieRecordingState, 0)
    try await s.refresh()
    let recording = try await s.toggleRecording()
    #expect(recording)
    #expect(recordButton(cam) == [2, 1, 2])
}

@Test func toggleRecordingRefusedPressReportsCodeAndState() async throws {
    // камера ответила «занята» и ничего не сделала: ошибка — с кодом и состоянием после ожидания
    let cam = FakeCamera(props: [recordingState()])
    cam.busyOnNextPress = SonyProp.movieRecButton
    let s = try await readySession(cam)
    await #expect(throws: SessionError.recordButtonRefused(release: false, code: 0x2019, recording: false)) {
        try await s.toggleRecording()
    }
    #expect(recordButton(cam) == [2])
}

@Test func toggleRecordingRefusedReleaseKeepsRecording() async throws {
    // «отпустить» на остановке отклонено — камера пишет дальше, и следующая остановка снова отпускает
    let cam = FakeCamera(props: [recordingState()])
    let s = try await readySession(cam)
    _ = try await s.toggleRecording()
    cam.busyOnNextRelease = SonyProp.movieRecButton
    await #expect(throws: SessionError.recordButtonRefused(release: true, code: 0x2019, recording: true)) {
        try await s.toggleRecording()
    }
    let recording = try await s.toggleRecording()
    #expect(!recording)
    #expect(recordButton(cam) == [2, 1, 1])
}

@Test func liveViewRetriesUntilFrame() async throws {
    let cam = FakeCamera(props: [])
    cam.liveViewReplies = [PTPReply(code: 0x200F), PTPReply(code: 0x2009),
                           PTPReply(code: 0x2001, data: try Fixture.data("lv_frame_raw"))]
    let s = try await readySession(cam)
    #expect(try await s.nextLiveViewFrame().count == 20736)
    #expect(cam.sent.filter { $0.op == 0x1009 }.count == 3)
}

@Test func disconnectFinishesSnapshotsAndFailsSet() async throws {
    let cam = FakeCamera(props: [wb()])
    let s = try await readySession(cam)
    cam.disconnectOnNext = 0x9205
    await #expect(throws: TransportError.disconnected) { try await s.set(SonyProp.whiteBalance, to: 4) }
    #expect(await s.isReady == false)
    for await _ in s.snapshots {}  // поток завершён — цикл заканчивается
}
