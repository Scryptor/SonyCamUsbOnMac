import Foundation
import Testing
@testable import ZVCore

private let t0 = Date(timeIntervalSinceReferenceDate: 5000)

@Test func recordingStartConfirmedByCamera() {
    var toggle = RecordingToggle()
    let pressed = toggle.press(recording: false, at: t0)
    #expect(pressed)
    #expect(toggle.request == .starting)
    let early = toggle.observe(recording: false)
    #expect(early == nil)
    let confirmed = toggle.observe(recording: true)
    #expect(confirmed == .started)
    #expect(toggle.request == nil)
}

@Test func recordingSecondPressIgnoredWhilePending() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let again = toggle.press(recording: false, at: t0.addingTimeInterval(0.2))
    #expect(!again)
    #expect(toggle.request == .starting)
}

@Test func recordingTimeoutFails() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let before = toggle.tick(now: t0.addingTimeInterval(5.9))
    #expect(before == nil)
    let after = toggle.tick(now: t0.addingTimeInterval(6.1))
    #expect(after == .failed(.starting))
    #expect(toggle.request == nil)
}

@Test func recordingCompletedWithoutChangeKeepsWaiting() {
    // сессия ждёт подтверждения 2,5 с, камера бывает медленнее
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let returned = toggle.completed(recording: false)
    #expect(returned == nil)
    #expect(toggle.request == .starting)
    let confirmed = toggle.observe(recording: true)
    #expect(confirmed == .started)

    var silent = RecordingToggle()
    _ = silent.press(recording: false, at: t0)
    _ = silent.completed(recording: false)
    let timedOut = silent.tick(now: t0.addingTimeInterval(6.1))
    #expect(timedOut == .failed(.starting))
}

@Test func recordingCompletedAfterObserveIsSilent() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let confirmed = toggle.observe(recording: true)
    #expect(confirmed == .started)
    let returned = toggle.completed(recording: true)
    #expect(returned == nil)
}

@Test func recordingStopConfirmed() {
    var toggle = RecordingToggle()
    let pressed = toggle.press(recording: true, at: t0)
    #expect(pressed)
    #expect(toggle.request == .stopping)
    let still = toggle.observe(recording: true)
    #expect(still == nil)
    let returned = toggle.completed(recording: false)
    #expect(returned == .stopped)
}

@Test func recordingErrorFails() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: true, at: t0)
    let returned = toggle.completed(recording: nil)
    #expect(returned == .failed(.stopping))
    #expect(toggle.request == nil)
}
