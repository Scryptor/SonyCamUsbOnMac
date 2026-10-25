import Foundation
import Testing
@testable import ZVCore

/// Прогоняет монитор по тикам 250 мс до `until`, возвращает действия с моментами.
private func run(_ monitor: inout LinkMonitor, from start: Date, until seconds: TimeInterval,
                 ping: ((TimeInterval) -> Bool?)? = nil) -> [(t: TimeInterval, action: LinkMonitor.Action)] {
    var result: [(TimeInterval, LinkMonitor.Action)] = []
    var t: TimeInterval = 0.25
    while t <= seconds + 1e-9 {
        let now = start.addingTimeInterval(t)
        for action in monitor.tick(now: now) {
            result.append((t, action))
            if action == .ping, let answer = ping?(t) { monitor.pinged(answer, at: now) }
        }
        t += 0.25
    }
    return result
}

@Test func linkMonitorSendsHeartbeatProbes() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    let actions = run(&monitor, from: start, until: 4.5)
    // проба сразу, пока ответа нет — следующей нет; камера не отвечала ни разу — это не провал
    #expect(actions.filter { $0.action == .sendProbe }.map(\.t) == [0.25])
    #expect(!monitor.isOutage)
    // ответ через 4 с — проба пережила провал, это не время отклика
    monitor.probeAnswered(at: start.addingTimeInterval(4.6))
    #expect(monitor.rtt == nil)
    _ = monitor.tick(now: start.addingTimeInterval(6.75))
    monitor.probeAnswered(at: start.addingTimeInterval(6.8))
    #expect(monitor.rtt.map { abs($0 - 0.05) < 0.001 } == true)
}

@Test func linkMonitorRidesThroughAShortStall() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    // 2 с без байтов — ещё не провал
    #expect(!run(&monitor, from: start, until: 2).contains { $0.action == .ping })
    monitor.commandReceived(at: start.addingTimeInterval(2.1))
    #expect(!monitor.isOutage)
}

@Test func linkMonitorResetsWhenLinkIsBackButTCPStaysSilent() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    // ping не отвечает 10 с, потом отвечает — через 3 с тишины по TCP соединения сбрасываем
    let actions = run(&monitor, from: start, until: 20, ping: { $0 >= 10 })
    #expect(monitor.outageSince == start)
    let pings = actions.filter { $0.action == .ping }.map(\.t)
    #expect(pings.first == 2.5)
    // и после первого ответа пингуем дальше — до самого сброса
    #expect(pings.contains(11.5) && pings.allSatisfy { $0 <= 13.5 })
    let close = actions.first { if case .close = $0.action { return true } else { return false } }
    #expect(close?.action == .close(.resetAfterOutage))
    #expect(close.map { $0.t >= 13 && $0.t <= 13.75 } == true)
}

@Test func linkMonitorEndsOutageWhenBytesArrive() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    _ = run(&monitor, from: start, until: 6, ping: { _ in false })
    #expect(monitor.isOutage)
    // TCP сам довёз данные — провал кончился, ничего не сбрасываем
    monitor.commandReceived(at: start.addingTimeInterval(6.1))
    #expect(!monitor.isOutage)
    #expect(monitor.linkBackAt == nil)
}

@Test func linkMonitorGivesUpAfterThreeMinutes() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    let actions = run(&monitor, from: start, until: 181, ping: { _ in false })
    #expect(actions.last?.action == .close(.noLink))
    #expect(actions.last.map { $0.t >= 180 } == true)
}

@Test func linkMonitorNoticesSilentCameraWhileProbesAnswer() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    var closed: (TimeInterval, LinkMonitor.Action)?
    var t: TimeInterval = 0.25
    while t <= 20, closed == nil {
        let now = start.addingTimeInterval(t)
        for action in monitor.tick(now: now) {
            // камера отвечает на пробы сразу, а на операцию — нет
            if action == .sendProbe { monitor.probeAnswered(at: now.addingTimeInterval(0.02)) }
            if case .close = action { closed = (t, action) }
        }
        t += 0.25
    }
    #expect(closed?.1 == .close(.cameraSilent))
    #expect(closed.map { $0.0 >= 15 && $0.0 <= 15.5 } == true)
}

@Test func linkMonitorTreatsUnansweredProbeAsOutageOnlyIfProbesWork() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    // камера отвечала на пробы — неотвеченная проба в простое значит провал
    _ = monitor.tick(now: start.addingTimeInterval(0.25))
    monitor.probeAnswered(at: start.addingTimeInterval(0.3))
    let actions = run(&monitor, from: start, until: 8, ping: { _ in false })
    #expect(monitor.isOutage)
    #expect(actions.contains { $0.action == .ping })
}

@Test func linkLossReconnectsAtOnceExceptAfterLongOutage() {
    #expect(LinkLoss.broken.reconnectsAtOnce)
    #expect(LinkLoss.resetAfterOutage.reconnectsAtOnce)
    #expect(LinkLoss.cameraSilent.reconnectsAtOnce)
    #expect(!LinkLoss.noLink.reconnectsAtOnce)
}

@Test func wifiPolicyReconnectsAtOnceThenBacksOffGently() {
    #expect((0..<8).map(WiFiPolicy.reconnectDelay(attempt:)) == [0, 0.5, 1, 2, 3, 5, 5, 5])
}

@Test func wifiPolicyForgetsPairingOnlyWhenCameraDoesNotKnowUs() {
    #expect(WiFiPolicy.forgetsPairing(initFailReason: 1))
    #expect(!WiFiPolicy.forgetsPairing(initFailReason: 2))
    #expect(!WiFiPolicy.forgetsPairing(initFailReason: 3))
}

@Test func linkMonitorKeepsCountingSilenceAcrossRetriedOperations() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    var closed: TimeInterval?
    var t: TimeInterval = 0.25
    monitor.operationStarted(at: start)
    while t <= 20, closed == nil {
        let now = start.addingTimeInterval(t)
        // live view после тайм-аута в 8 с спрашивает снова — отсчёт от самой старой операции без ответа
        if t == 8 || t == 16 { monitor.operationStarted(at: now) }
        for action in monitor.tick(now: now) {
            if action == .sendProbe { monitor.probeAnswered(at: now.addingTimeInterval(0.02)) }
            if action == .close(.cameraSilent) { closed = t }
        }
        t += 0.25
    }
    #expect(closed.map { $0 >= 15 && $0 <= 15.5 } == true)
}

@Test func linkMonitorStartsSilentCameraClockAfterAnOutage() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    _ = run(&monitor, from: start, until: 20, ping: { _ in false })
    #expect(monitor.isOutage)
    // провал кончился ответом на пробу — «камера не отвечает» считается с этого момента, а не с начала операции
    monitor.eventReceived(at: start.addingTimeInterval(20.1))
    #expect(!monitor.isOutage)
    #expect(monitor.tick(now: start.addingTimeInterval(21)).allSatisfy { $0 != .close(.cameraSilent) })
}

@Test func linkMonitorCancelsTheResetWhenTheLinkDropsAgain() {
    let start = Date(timeIntervalSinceReferenceDate: 1000)
    var monitor = LinkMonitor(now: start)
    monitor.operationStarted(at: start)
    // ping ответил один раз (на 4,5 с) и снова молчит: RST ушёл бы в пустоту — не сбрасываем
    let actions = run(&monitor, from: start, until: 20, ping: { $0 == 4.5 })
    #expect(!actions.contains { $0.action == .close(.resetAfterOutage) })
    #expect(monitor.isOutage)
}
