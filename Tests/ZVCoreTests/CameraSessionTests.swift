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

/// Считает значения потока в фоне: тест ждёт счёт с потолком, а не само значение, — сломанный код роняет проверку, а не
/// подвешивает тест.
private final class Counted: @unchecked Sendable {
    private let lock = NSLock()
    private var count = 0
    private var ended = false
    private var task: Task<Void, Never>?

    init(_ stream: AsyncStream<Void>) {
        task = Task { [self] in
            for await _ in stream { lock.withLock { count += 1 } }
            lock.withLock { ended = true }
        }
    }

    var values: Int { lock.withLock { count } }
    var isEnded: Bool { lock.withLock { ended } }
    func stop() { task?.cancel() }
}

/// Ждёт условия не дольше `seconds`.
private func eventually(_ seconds: Double = 2, _ condition: () -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end {
        if condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return condition()
}

@Test func lensChangeEventReachesEverySubscriber() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200])])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let first = Counted(await session.lensChanges())
    let second = Counted(await session.lensChanges())
    defer {
        first.stop()
        second.stop()
    }
    // 0xC203 — обычное изменение свойств, к смене объектива не относится
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    try await Task.sleep(for: .milliseconds(150))
    #expect(first.values == 0 && second.values == 0)
    // 0xC21B — смена объектива: каждому подписчику по одному разу
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { first.values == 1 && second.values == 1 })
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { first.values == 2 && second.values == 2 })
}

@Test func lensChangeBeforeSubscribingIsNotReplayed() async throws {
    let cam = FakeCamera(props: [])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    // событие шло по свойствам: ждём его по перечитыванию, чтобы оно точно дошло до сеанса
    var updates = session.snapshots.makeAsyncIterator()
    _ = await updates.next()
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    _ = await updates.next()
    let late = Counted(await session.lensChanges())
    defer { late.stop() }
    try await Task.sleep(for: .milliseconds(150))
    #expect(late.values == 0)
    // старого события не будет, новое придёт
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { late.values == 1 })
}

@Test func lensChangeStreamEndsWhenTheSessionEnds() async throws {
    let cam = FakeCamera(props: [])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let open = Counted(await session.lensChanges())
    defer { open.stop() }
    try await Task.sleep(for: .milliseconds(50))
    #expect(!open.isEnded)
    await session.close()
    #expect(await eventually { open.isEnded })
    // подписка на закрытый сеанс тоже заканчивается сразу
    let after = Counted(await session.lensChanges())
    defer { after.stop() }
    #expect(await eventually { after.isEnded })
}
