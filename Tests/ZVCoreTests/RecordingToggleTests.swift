import Foundation
import Testing
@testable import ZVCore

private let t0 = Date(timeIntervalSinceReferenceDate: 5000)

private func at(_ seconds: TimeInterval) -> Date { t0.addingTimeInterval(seconds) }

@Test func recordingStartConfirmedByCamera() {
    var toggle = RecordingToggle()
    let pressed = toggle.press(recording: false, at: t0)
    #expect(pressed)
    #expect(toggle.request == .starting)
    let early = toggle.observe(recording: false, at: at(0.2))
    #expect(early == nil)
    let confirmed = toggle.observe(recording: true, at: at(0.8))
    #expect(confirmed == .started)
    #expect(toggle.request == nil)
}

@Test func recordingSecondPressIgnoredWhilePending() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let again = toggle.press(recording: false, at: at(0.2))
    #expect(!again)
    #expect(toggle.request == .starting)
}

@Test func recordingTimeoutFails() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let before = toggle.tick(now: at(5.9))
    #expect(before == nil)
    let after = toggle.tick(now: at(6.1))
    #expect(after == .failed(.starting))
    #expect(toggle.request == nil)
}

@Test func recordingCompletedWithoutChangeKeepsWaiting() {
    // сессия ждёт подтверждения 2,5 с, камера бывает медленнее
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let returned = toggle.completed(recording: false, at: at(2.5))
    #expect(returned == nil)
    #expect(toggle.request == .starting)
    let confirmed = toggle.observe(recording: true, at: at(3))
    #expect(confirmed == .started)

    var silent = RecordingToggle()
    _ = silent.press(recording: false, at: t0)
    _ = silent.completed(recording: false, at: at(2.5))
    let timedOut = silent.tick(now: at(6.1))
    #expect(timedOut == .failed(.starting))
}

@Test func recordingCompletedAfterObserveIsSilent() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    let confirmed = toggle.observe(recording: true, at: at(0.8))
    #expect(confirmed == .started)
    let returned = toggle.completed(recording: true, at: at(0.9))
    #expect(returned == nil)
}

@Test func recordingStopConfirmed() {
    var toggle = RecordingToggle()
    let pressed = toggle.press(recording: true, at: t0)
    #expect(pressed)
    #expect(toggle.request == .stopping)
    let still = toggle.observe(recording: true, at: at(0.2))
    #expect(still == nil)
    let returned = toggle.completed(recording: false, at: at(0.9))
    #expect(returned == .stopped)
}

@Test func recordingErrorFails() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: true, at: t0)
    let returned = toggle.completed(recording: nil, at: at(2.5))
    #expect(returned == .failed(.stopping))
    #expect(toggle.request == nil)
}

@Test func recordingAbortedRightAfterStart() {
    // камера начала по нажатию и сама бросила в первые секунды — отказ, а не молча погасшая рамка
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    _ = toggle.observe(recording: false, at: at(0.1))
    let started = toggle.observe(recording: true, at: at(0.8))
    #expect(started == .started)
    let still = toggle.observe(recording: true, at: at(1))
    #expect(still == nil)
    let aborted = toggle.observe(recording: false, at: at(1.2))
    #expect(aborted == .aborted)
    let again = toggle.observe(recording: false, at: at(1.5))
    #expect(again == nil)
}

@Test func recordingStoppedByCameraLater() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    _ = toggle.observe(recording: true, at: at(0.8))
    let stopped = toggle.observe(recording: false, at: at(10))
    #expect(stopped == .stoppedByCamera)
}

@Test func recordingStoppedFromProgramIsOnlyStopped() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    _ = toggle.observe(recording: true, at: at(0.8))
    _ = toggle.press(recording: true, at: at(1.5))
    let stopped = toggle.observe(recording: false, at: at(2))
    #expect(stopped == .stopped)
    let after = toggle.observe(recording: false, at: at(2.2))
    #expect(after == nil)
}

@Test func recordingStopConfirmedByCallIsNotStoppedByCamera() {
    // вызов подтвердил остановку раньше свойств — конец записи, пришедший потом, уже наш
    var toggle = RecordingToggle()
    _ = toggle.observe(recording: true, at: t0)
    _ = toggle.press(recording: true, at: at(1))
    let stopped = toggle.completed(recording: false, at: at(1.8))
    #expect(stopped == .stopped)
    let after = toggle.observe(recording: false, at: at(1.9))
    #expect(after == nil)
}

@Test func recordingCameraButtonEndsAsStoppedByCamera() {
    // запись с кнопки самой камеры: начало — без исхода (рамка по свойству), конец — сообщение
    var toggle = RecordingToggle()
    let first = toggle.observe(recording: false, at: t0)
    #expect(first == nil)
    let began = toggle.observe(recording: true, at: at(1))
    #expect(began == nil)
    let still = toggle.observe(recording: true, at: at(5))
    #expect(still == nil)
    let ended = toggle.observe(recording: false, at: at(6))
    #expect(ended == .stoppedByCamera)
}

@Test func recordingStartFromCallThenDroppedIsAborted() {
    // свойство «пишет» до экрана не дошло, а вызов его видел: обрыв всё равно замечаем
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    _ = toggle.observe(recording: false, at: at(0.1))
    let started = toggle.completed(recording: true, at: at(0.9))
    #expect(started == .started)
    let aborted = toggle.observe(recording: false, at: at(1.1))
    #expect(aborted == .aborted)
}

@Test func recordingLateCallAfterAbortIsSilent() {
    var toggle = RecordingToggle()
    _ = toggle.press(recording: false, at: t0)
    _ = toggle.observe(recording: true, at: at(0.8))
    _ = toggle.observe(recording: false, at: at(1.2))
    let late = toggle.completed(recording: true, at: at(2.5))
    #expect(late == nil)
    let after = toggle.observe(recording: false, at: at(2.6))
    #expect(after == nil)
}

@Test func recordingMessages() {
    #expect(RecordingToggle.message(.started, refusal: nil) == nil)
    #expect(RecordingToggle.message(.stopped, refusal: nil) == nil)
    #expect(RecordingToggle.message(.failed(.starting), refusal: nil) == "Камера не начала запись")
    #expect(RecordingToggle.message(.failed(.stopping), refusal: nil) == "Камера не остановила запись")
    #expect(RecordingToggle.message(.failed(.starting), refusal: 0x2019) == "Камера занята — запись не началась")
    #expect(RecordingToggle.message(.failed(.stopping), refusal: 0x2019) == "Камера занята — запись не остановилась")
    #expect(RecordingToggle.message(.failed(.starting), refusal: 0x2002) == "Камера не приняла нажатие — запись не началась")
    #expect(RecordingToggle.message(.aborted, refusal: nil) == "Камера начала запись и сразу остановила")
    #expect(RecordingToggle.message(.stoppedByCamera, refusal: nil) == "Запись остановлена на камере")
}
