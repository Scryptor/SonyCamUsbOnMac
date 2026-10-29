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

/// Собирает значения потока в фоне: тест ждёт их с потолком, а не само значение, — сломанный код роняет проверку, а не
/// подвешивает тест (`AsyncStream.next()` потолка не имеет).
private final class Collected<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var received: [Value] = []
    private var ended = false
    private var task: Task<Void, Never>?

    init(_ stream: AsyncStream<Value>) {
        task = Task { [self] in
            for await value in stream { lock.withLock { received.append(value) } }
            lock.withLock { ended = true }
        }
    }

    var values: [Value] { lock.withLock { received } }
    var isEnded: Bool { lock.withLock { ended } }
    func stop() { task?.cancel() }
}

/// Ждёт условия не дольше `seconds`.
private func eventually(_ seconds: Double = 2, _ condition: () async -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}

@Test func lensChangeEventReachesEverySubscriber() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200])])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let first = Collected(await session.events)
    let second = Collected(await session.events)
    defer {
        first.stop()
        second.stop()
    }
    // 0xC203 — обычное изменение свойств, к событиям камеры не относится
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    try await Task.sleep(for: .milliseconds(150))
    #expect(first.values.isEmpty && second.values.isEmpty)
    // 0xC21B — смена объектива: каждому подписчику по одному разу
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { first.values == [.lensChanged] && second.values == [.lensChanged] })
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { first.values == [.lensChanged, .lensChanged] && second.values.count == 2 })
}

@Test func eventsBeforeSubscribingAreNotReplayed() async throws {
    let cam = FakeCamera(props: [])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    // события идут по порядку: когда за ними пришло второе перечитывание свойств (первое — в start), оба уже дошли до сеанса
    let refreshes = { cam.sent.filter { $0.op == SonyOp.getAllExtPropInfo }.count }
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    cam.emit(PTPEvent(code: SonyEvent.displayListChanged, params: [0x13]))
    cam.emit(PTPEvent(code: SonyEvent.focusPositionResult, params: [1]))
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    #expect(await eventually { refreshes() >= 2 })
    let late = Collected(await session.events)
    defer { late.stop() }
    try await Task.sleep(for: .milliseconds(150))
    #expect(late.values.isEmpty)
    // старых событий не будет, новое придёт
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    #expect(await eventually { late.values == [.lensChanged] })
}

@Test func eventsEndWhenTheSessionEnds() async throws {
    let cam = FakeCamera(props: [])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let open = Collected(await session.events)
    defer { open.stop() }
    try await Task.sleep(for: .milliseconds(50))
    #expect(!open.isEnded)
    await session.close()
    #expect(await eventually { open.isEnded })
    // подписка на закрытый сеанс тоже заканчивается сразу
    let after = Collected(await session.events)
    defer { after.stop() }
    #expect(await eventually { after.isEnded })
}

@Test func cameraEventsCarryTheirParametersInOrder() async throws {
    let cam = FakeCamera(props: [])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let events = Collected(await session.events)
    defer { events.stop() }
    // список подписей изменился: тип списка — в параметре события; без параметра — 0, «все списки»
    cam.emit(PTPEvent(code: SonyEvent.displayListChanged, params: [0x13]))
    cam.emit(PTPEvent(code: SonyEvent.displayListChanged))
    // фокус дошёл до цели (параметр 1) или нет
    cam.emit(PTPEvent(code: SonyEvent.focusPositionResult, params: [1]))
    cam.emit(PTPEvent(code: SonyEvent.focusPositionResult, params: [0]))
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    // чужое событие потока не пачкает
    cam.emit(PTPEvent(code: 0xC2FF, params: [7]))
    cam.emit(PTPEvent(code: SonyEvent.displayListChanged, params: [0x15]))
    let expected: [CameraEvent] = [.displayListChanged(type: 0x13), .displayListChanged(type: 0),
                                   .focusPositionResult(reached: true), .focusPositionResult(reached: false), .lensChanged,
                                   .displayListChanged(type: 0x15)]
    #expect(await eventually { events.values.count >= expected.count })
    #expect(events.values == expected)
}

@Test func propertyEventsStillRefreshTheSnapshotAlongsideCameraEvents() async throws {
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200])])
    let session = CameraSession(transport: cam, timing: .test)
    try await session.start()
    let events = Collected(await session.events)
    defer { events.stop() }
    cam.setCurrent(SonyProp.iso, 100)
    cam.emit(PTPEvent(code: SonyEvent.lensInfoChanged))
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    #expect(await eventually { await session.properties[SonyProp.iso]?.current == .int(100) })
    #expect(await eventually { events.values == [.lensChanged] })
}
