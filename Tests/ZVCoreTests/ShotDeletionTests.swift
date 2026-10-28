import Foundation
import Testing
@testable import ZVCore

@Test func deletedShotGivesItsPlaceToTheOlderOne() {
    // пять снимков, удалили третий: на его место встал бывший четвёртый — его и показать
    #expect(ShotDeletion.neighbor(removing: 2, count: 5) == 2)
    // удалили самый старый — показать соседний новее
    #expect(ShotDeletion.neighbor(removing: 4, count: 5) == 3)
    #expect(ShotDeletion.neighbor(removing: 0, count: 2) == 0)
    #expect(ShotDeletion.neighbor(removing: 1, count: 2) == 0)
    // удалили единственный — показывать нечего
    #expect(ShotDeletion.neighbor(removing: 0, count: 1) == nil)
}

@Test func restoredShotReturnsToItsPlaceByTime() {
    let t = Date(timeIntervalSince1970: 1_000)
    // новые сначала: снятый между вторым и третьим встаёт перед третьим
    let times = [40.0, 30, 10].map { t.addingTimeInterval($0) }
    #expect(ShotDeletion.restoreIndex(takenAt: t.addingTimeInterval(20), in: times) == 2)
    // пока висела «Вернуть», пришли новые снимки — удалённый встаёт после них
    #expect(ShotDeletion.restoreIndex(takenAt: t.addingTimeInterval(35), in: times) == 1)
    // новее всех — первым, старше всех и в пустой ленте — последним
    #expect(ShotDeletion.restoreIndex(takenAt: t.addingTimeInterval(50), in: times) == 0)
    #expect(ShotDeletion.restoreIndex(takenAt: t, in: times) == 3)
    #expect(ShotDeletion.restoreIndex(takenAt: t, in: []) == 0)
}
