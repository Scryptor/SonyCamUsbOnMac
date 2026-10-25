import Foundation
import Testing
@testable import ZVCore

@Test func broadcastGivesEachListenerTheLatestValueAndKeepsGoingAfterOneLeaves() async {
    let broadcast = Broadcast<Int>()
    broadcast.yield(1)
    var first = broadcast.subscribe().makeAsyncIterator()
    #expect(await first.next() == 1)                  // последнее — сразу
    let leaving = Task { for await _ in broadcast.subscribe() {} }
    try? await Task.sleep(for: .milliseconds(20))
    #expect(broadcast.subscriberCount == 2)
    leaving.cancel()                                  // отписался один — поток не закрыт
    try? await Task.sleep(for: .milliseconds(20))
    #expect(broadcast.subscriberCount == 1)
    broadcast.yield(2)
    #expect(await first.next() == 2)
    var late = broadcast.subscribe().makeAsyncIterator()
    #expect(await late.next() == 2)
    broadcast.finish()
    #expect(await first.next() == nil)
    var afterEnd = broadcast.subscribe().makeAsyncIterator()
    #expect(await afterEnd.next() == nil)             // закончился — новые подписки тоже пустые
}

@Test func sessionSnapshotsReachSeveralListeners() async throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let cam = FakeCamera(props: [.init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200])])
    let session = try await readySession(cam)
    var main = session.snapshotStream().makeAsyncIterator()
    var strip = session.snapshotStream().makeAsyncIterator()
    #expect(await main.next() != nil)
    #expect(await strip.next() != nil)
    _ = props
}
