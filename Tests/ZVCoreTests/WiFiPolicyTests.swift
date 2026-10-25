import Foundation
import Testing
@testable import ZVCore

@Test func wifiPolicyAttemptsOnlyWhenEnabledOrPairing() {
    let policy = WiFiPolicy()
    let now = Date()
    #expect(!policy.mayAttempt(enabled: false, pairing: false, now: now))
    #expect(policy.mayAttempt(enabled: true, pairing: false, now: now))
    #expect(policy.mayAttempt(enabled: false, pairing: true, now: now))
}

@Test func wifiPolicySendsInitOnlyToAPairedCameraOrWhilePairing() {
    // без сопряжения не стучимся: камера в режиме Pairing показала бы запрос не от того устройства
    let policy = WiFiPolicy()
    #expect(!policy.maySendInit(paired: false, pairing: false))
    #expect(policy.maySendInit(paired: true, pairing: false))
    #expect(policy.maySendInit(paired: false, pairing: true))
}

@Test func wifiPolicyBacksOffAfterLostSessions() {
    var policy = WiFiPolicy()
    let start = Date()
    // сеанс оборвался — пауза 2, 4, 8, 10, 10 с, как у USB
    #expect([1, 2, 3, 4, 5].map { _ in policy.sessionLost(now: start) } == [2, 4, 8, 10, 10])
    #expect(!policy.mayAttempt(enabled: true, pairing: false, now: start.addingTimeInterval(9)))
    #expect(policy.mayAttempt(enabled: true, pairing: false, now: start.addingTimeInterval(10)))
    // сопряжение ждать не заставляем
    #expect(policy.mayAttempt(enabled: true, pairing: true, now: start))
    // сеанс дошёл до готовности — счёт с начала
    policy.sessionReady()
    #expect(policy.sessionLost(now: start) == 2)
}

@Test func wifiPolicyCountsOnlyFailuresToADiscoveredCamera() {
    var policy = WiFiPolicy()
    // адрес из памяти мог устареть (камера выключена, новый адрес) — это не «держит прошлое подключение»
    for _ in 0..<5 { policy.attemptFailed(discovered: false) }
    #expect(!policy.showsStaleHint)
    for _ in 0..<3 { policy.attemptFailed(discovered: true) }
    #expect(policy.showsStaleHint)
    // камеру в сети больше не видно — подсказка уходит
    policy.nothingFound()
    #expect(!policy.showsStaleHint)
    for _ in 0..<3 { policy.attemptFailed(discovered: true) }
    policy.connected()
    #expect(!policy.showsStaleHint)
}
