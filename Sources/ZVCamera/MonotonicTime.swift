import Foundation

/// Время для слежения за связью: не прыгает при переводе часов и стоит, пока Mac спит (после сна провал видно
/// через 2,5 с, а не «3 минуты без связи» сразу). Сравнивать только между собой.
enum MonotonicTime {
    static func now() -> Date { Date(timeIntervalSinceReferenceDate: ProcessInfo.processInfo.systemUptime) }

    /// Тот же момент по обычным часам — для экрана.
    static func wallClock(_ instant: Date) -> Date { Date().addingTimeInterval(instant.timeIntervalSince(now())) }
}
