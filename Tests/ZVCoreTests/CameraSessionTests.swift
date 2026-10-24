import Foundation
import Testing
@testable import ZVCore

@Test func handshakeSendsSonySequenceInOrder() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [0xFF_FFFF, 100, 200])])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    #expect(cam.sent.map(\.op) == [0x1001, 0x9201, 0x9201, 0x9202, 0x9201, 0x9205, 0x9209])
    #expect(cam.sent[1].params == [1, 0, 0] && cam.sent[2].params == [2, 0, 0] && cam.sent[4].params == [3, 0, 0])
    #expect(cam.sent[3].params == [0x12C, 1])
    #expect(cam.sent[5].params == [0xD25A] && cam.sent[5].outData == Data([0x01]))
    #expect(await session.properties[SonyProp.iso]?.current == .int(200))
    #expect(await session.isReady == false)
    try await session.waitUntilReady()
    #expect(await session.isReady)
}

@Test func notPCRemoteStopsAfterDeviceInfo() async {
    let cam = FakeCamera(props: [], deviceInfo: FakeCamera.deviceInfo(operations: [0x1001, 0x1002]))
    await #expect(throws: SessionError.notPCRemote) {
        try await CameraSession(transport: cam, timing: .test).start()
    }
    #expect(cam.sent.map(\.op) == [0x1001])
}

@Test func extInfoIsRetriedUntilItHasData() async throws {
    let cam = FakeCamera(props: [])
    cam.extInfoEmptyReplies = 2
    try await CameraSession(transport: cam, timing: .test).start()
    #expect(cam.sent.filter { $0.op == 0x9202 }.count == 3)
}

@Test func propertyChangedEventPublishesSnapshot() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200])])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    var updates = session.snapshots.makeAsyncIterator()
    _ = await updates.next()
    cam.setCurrent(SonyProp.iso, 100)
    cam.emit(PTPEvent(code: 0xC203))
    #expect(await updates.next()?[SonyProp.iso]?.current == .int(100))
}
