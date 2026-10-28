import Foundation
import Testing
@testable import ZVCore

/// Канал: сколько байт в секунду пропускает и сколько идёт короткий ответ.
private struct Link {
    var bytesPerSecond: Double
    var rtt: TimeInterval = 0.005
}

/// Имитация камеры и канала: кадры идут, пока позволяет канал (не чаще 25 к/с); решение политики камера
/// исполняет через 0,3 с. Возвращает моменты и направления переключений.
private func simulate(_ policy: inout LiveViewQualityPolicy, from start: Date, seconds: TimeInterval,
                      link: (TimeInterval) -> Link, stallAt: Set<Int> = [], outage: Range<TimeInterval>? = nil,
                      sharp: Bool = false)
    -> [(t: TimeInterval, to: LiveViewQuality)] {
    var switches: [(TimeInterval, LiveViewQuality)] = []
    var cameraLevel = policy.level
    var pending: (LiveViewQuality, TimeInterval)?
    var t: TimeInterval = 0
    var frame = 0
    while t < seconds {
        let l = link(t)
        let bytes = cameraLevel == .high ? 72 * 1024 : 34 * 1024
        var fetch = l.rtt + Double(bytes) / l.bytesPerSecond
        if stallAt.contains(frame) { fetch += 3 }
        t += max(fetch, 0.04)
        frame += 1
        let now = start.addingTimeInterval(t)
        if let (level, at) = pending, t >= at {
            cameraLevel = level
            pending = nil
        }
        policy.noteRTT(l.rtt, at: now)
        policy.record(bytes: bytes, seconds: fetch, level: cameraLevel, at: now)
        let inOutage = outage.map { $0.contains(t) } ?? false
        if let target = policy.decide(now: now, outage: inOutage, sharp: sharp) {
            switches.append((t, target))
            pending = (target, t + 0.3)
        }
    }
    return switches
}

private let start = Date(timeIntervalSinceReferenceDate: 1000)
private let good = Link(bytesPerSecond: 1.8e6)
private let weak = Link(bytesPerSecond: 0.5e6)

@Test func qualityStaysHighOnAGoodLink() {
    var policy = LiveViewQualityPolicy(now: start)
    #expect(simulate(&policy, from: start, seconds: 60, link: { _ in good }).isEmpty)
    #expect(policy.level == .high)
    #expect((policy.predictedHighFPS(now: start.addingTimeInterval(60), window: 5) ?? 0) > 20)
}

@Test func qualityDropsOnAWeakLinkAndStaysThere() {
    var policy = LiveViewQualityPolicy(now: start)
    let switches = simulate(&policy, from: start, seconds: 60, link: { _ in weak })
    #expect(switches.map(\.to) == [.low])
    #expect(switches[0].t >= 5 && switches[0].t < 6)
    #expect(policy.level == .low)
}

@Test func qualityComesBackWhenTheLinkRecovers() {
    var policy = LiveViewQualityPolicy(now: start)
    let switches = simulate(&policy, from: start, seconds: 60, link: { $0 < 10 ? weak : good })
    #expect(switches.map(\.to) == [.low, .high])
    // вниз — через 5 с, вверх — не раньше выдержки 15 с и 10 с на низком
    #expect(switches[1].t >= switches[0].t + 15)
    #expect(policy.level == .high)
}

@Test func qualityHoldDoublesWhenGoingUpFailsAndResetsWhenStable() {
    var policy = LiveViewQualityPolicy(now: start)
    // хорошо только в окне, когда политика пробует вернуть высокое, и сразу опять плохо
    var switches = simulate(&policy, from: start, seconds: 40, link: { $0 < 6 || $0 > 28 ? weak : good })
    #expect(switches.map(\.to).prefix(3) == [.low, .high, .low])
    #expect(policy.hold == 30)
    // дальше канал хороший: вернётся и через 2 мин устойчивой работы выдержка снова 15 с
    let later = start.addingTimeInterval(40)
    switches = simulate(&policy, from: later, seconds: 220, link: { _ in good })
    #expect(switches.map(\.to) == [.high])
    #expect(policy.hold == 15)
}

@Test func qualityIgnoresASingleStallButReactsToRepeatedOnes() {
    var single = LiveViewQualityPolicy(now: start)
    #expect(simulate(&single, from: start, seconds: 30, link: { _ in good }, stallAt: [200]).isEmpty)
    var repeated = LiveViewQualityPolicy(now: start)
    let switches = simulate(&repeated, from: start, seconds: 30, link: { _ in good }, stallAt: [150, 200, 250])
    #expect(switches.map(\.to) == [.low])
}

@Test func qualityMakesNoDecisionsDuringAnOutage() {
    var policy = LiveViewQualityPolicy(now: start)
    #expect(simulate(&policy, from: start, seconds: 30, link: { _ in weak }, outage: 0..<60).isEmpty)
}

@Test func qualityLevelFollowsFrameWidth() {
    #expect(LiveViewQuality(frameWidth: 1024) == .high)
    #expect(LiveViewQuality(frameWidth: 640) == .low)
    #expect(LiveViewQuality(frameWidth: 0) == nil)
}

@Test func percentileOfFrameGaps() {
    #expect(Percentile.of([], 0.5) == nil)
    #expect(Percentile.of([40, 41, 39, 300], 0.5) == 40)
    #expect(Percentile.of([40, 41, 39, 300], 1) == 300)
    #expect(Percentile.of(Array(repeating: 40, count: 99) + [500], 0.95) == 40)
}

@Test func qualityTellsWhenItWillTryHighAgain() {
    var policy = LiveViewQualityPolicy(now: start)
    #expect(policy.secondsUntilUpAttempt(now: start) == nil)
    _ = simulate(&policy, from: start, seconds: 8, link: { _ in weak })
    #expect(policy.level == .low)
    let wait = policy.secondsUntilUpAttempt(now: start.addingTimeInterval(8)) ?? -1
    #expect(wait > 9 && wait <= 15)
}

/// Картинка увеличена: чёткость важнее плавности — высокое держим до 6 к/с, а не до 12.
@Test func qualityZoomedViewKeepsHighDownToSixFPS() {
    let eight = Link(bytesPerSecond: 0.62e6)
    var plain = LiveViewQualityPolicy(now: start)
    #expect(simulate(&plain, from: start, seconds: 30, link: { _ in eight }).map(\.to) == [.low])
    var sharp = LiveViewQualityPolicy(now: start)
    #expect(simulate(&sharp, from: start, seconds: 30, link: { _ in eight }, sharp: true).isEmpty)
    var hopeless = LiveViewQualityPolicy(now: start)
    #expect(simulate(&hopeless, from: start, seconds: 30, link: { _ in Link(bytesPerSecond: 0.33e6) }, sharp: true).map(\.to) == [.low])
}

/// …и возвращаем высокое с 10 к/с, а не с 18.
@Test func qualityZoomedViewReturnsToHighFromTenFPS() {
    let link: (TimeInterval) -> Link = { $0 < 10 ? Link(bytesPerSecond: 0.33e6) : Link(bytesPerSecond: 0.85e6) }
    var plain = LiveViewQualityPolicy(now: start)
    #expect(simulate(&plain, from: start, seconds: 60, link: link).map(\.to) == [.low])
    var sharp = LiveViewQualityPolicy(now: start)
    #expect(simulate(&sharp, from: start, seconds: 60, link: link, sharp: true).map(\.to) == [.low, .high])
}

@Test func previewQualityModeRestoresAndLabelsSizes() {
    // новое значение — как есть; без него — прежний переключатель: выключенный — всегда высокое, иначе авто
    #expect(PreviewQualityMode.restored("low", legacyAdaptive: true) == .low)
    #expect(PreviewQualityMode.restored(nil, legacyAdaptive: false) == .high)
    #expect(PreviewQualityMode.restored(nil, legacyAdaptive: true) == .auto)
    #expect(PreviewQualityMode.restored(nil, legacyAdaptive: nil) == .auto)
    #expect(PreviewQualityMode.restored("bogus", legacyAdaptive: false) == .high)
    #expect(PreviewQualityMode.auto.fixed == nil && PreviewQualityMode.low.fixed == .low && PreviewQualityMode.high.fixed == .high)
    // подписи — по форме кадра: видео 16:9, фото 3:2 и 1:1
    #expect(LiveViewQuality.high.sizeLabel(aspect: 1024.0 / 576) == "1024×576")
    #expect(LiveViewQuality.low.sizeLabel(aspect: 1024.0 / 576) == "640×360")
    #expect(LiveViewQuality.high.sizeLabel(aspect: 1.5) == "1024×683")
    #expect(LiveViewQuality.low.sizeLabel(aspect: 1) == "640×640")
    #expect(LiveViewQuality.high.sizeLabel(aspect: 0) == "1024×576")
}

