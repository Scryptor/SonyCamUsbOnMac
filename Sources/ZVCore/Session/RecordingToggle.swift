import Foundation

/// Запрос на запуск или остановку записи с кнопки программы: от нажатия до подтверждения камерой
/// (MovieRecordingState 0xD21D). Пока он идёт, на кнопке — индикатор; повторное нажатие не принимается.
/// Без таймеров — для тестов: время передаётся снаружи.
public struct RecordingToggle: Equatable, Sendable {
    public enum Request: String, Sendable {
        case starting, stopping
    }

    public enum Outcome: Equatable, Sendable {
        case started, stopped
        case failed(Request)
    }

    /// Камера не подтвердила за это время — отказ.
    public static let timeout: TimeInterval = 6

    public private(set) var request: Request?
    private var since: Date?

    public init() {}

    /// Нажали кнопку записи. false — прошлый запрос ещё идёт.
    public mutating func press(recording: Bool, at now: Date) -> Bool {
        guard request == nil else { return false }
        request = recording ? .stopping : .starting
        since = now
        return true
    }

    /// Камера сообщила, идёт ли запись (свойства перечитаны). Возвращает исход, если запрос подтверждён.
    public mutating func observe(recording: Bool) -> Outcome? {
        switch (request, recording) {
        case (.starting, true): return finish(.started)
        case (.stopping, false): return finish(.stopped)
        default: return nil
        }
    }

    /// Вызов сессии вернулся: идёт ли запись, nil — вызов не удался. Состояние не то — ждём дальше: сессия ждёт
    /// подтверждения 2,5 с, а камера бывает медленнее; решит событие от камеры или тайм-аут.
    public mutating func completed(recording: Bool?) -> Outcome? {
        guard let request else { return nil }
        guard let recording else { return finish(.failed(request)) }
        return observe(recording: recording)
    }

    /// Проверка по времени.
    public mutating func tick(now: Date) -> Outcome? {
        guard let request, let since, now.timeIntervalSince(since) > Self.timeout else { return nil }
        return finish(.failed(request))
    }

    private mutating func finish(_ outcome: Outcome) -> Outcome {
        request = nil
        since = nil
        return outcome
    }
}
