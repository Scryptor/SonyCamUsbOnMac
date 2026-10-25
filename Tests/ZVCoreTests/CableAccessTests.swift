import Foundation
import Testing
@testable import ZVCore

/// Прогоняет отметки (секунда, на шине, видна программе) и возвращает, показана ли подсказка после каждой.
private func hints(_ ticks: [(TimeInterval, Bool, Bool)]) -> [Bool] {
    var watch = CableAccessWatch()
    let t0 = Date(timeIntervalSince1970: 1000)
    return ticks.map { watch.update(onBus: $0.1, visible: $0.2, now: t0 + $0.0) }
}

@Test func cableAccessHintWaitsBeforeShowing() {
    // камера на шине USB, а macOS её программе не отдаёт (ждёт «Разрешить» в окне доступа) — подсказка через 5 с
    #expect(hints([(0, true, false), (4.9, true, false), (5, true, false)]) == [false, false, true])
}

@Test func cableAccessHintGoesAwayWhenTheCameraShowsUp() {
    // доступ дали — камера появилась у программы; кабель вынули и вставили снова — отсчёт заново
    let shown = hints([(0, true, false), (6, true, false), (7, true, true), (8, false, false), (9, true, false), (14, true, false)])
    #expect(shown == [false, true, false, false, false, true])
}
