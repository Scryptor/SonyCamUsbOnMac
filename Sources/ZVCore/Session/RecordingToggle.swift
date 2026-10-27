import Foundation

/// Запрос на запуск или остановку записи с кнопки программы: от нажатия до подтверждения камерой
/// (MovieRecordingState 0xD21D). Пока он идёт, на кнопке — индикатор; повторное нажатие не принимается.
/// Заодно следит за концом записи: камера бывает занята и, начав запись по нажатию, тут же её бросает — это отказ,
/// а не молча погасшая рамка; запись, кончившаяся без нажатия в программе, — «остановлена на камере».
/// Без таймеров — для тестов: время передаётся снаружи.
public struct RecordingToggle: Equatable, Sendable {
    public enum Request: String, Sendable {
        case starting, stopping
    }

    public enum Outcome: Equatable, Sendable {
        case started, stopped
        case failed(Request)
        /// Камера начала запись по нажатию и сама бросила её в первые секунды.
        case aborted
        /// Запись кончилась без нажатия в программе: кнопкой на камере или сама камера.
        case stoppedByCamera
    }

    /// Камера не подтвердила за это время — отказ.
    public static let timeout: TimeInterval = 6
    /// Запись, кончившаяся раньше этого после запуска с кнопки программы, — камера её «сразу бросила».
    public static let abortWindow: TimeInterval = 3

    public private(set) var request: Request?
    private var since: Date?
    /// Что камера сообщала последним; nil — ещё ничего.
    private var recording: Bool?
    /// Когда подтвердился запуск с кнопки программы; nil — запись не наша или её нет.
    private var startedAt: Date?

    public init() {}

    /// Нажали кнопку записи. false — прошлый запрос ещё идёт.
    public mutating func press(recording: Bool, at now: Date) -> Bool {
        guard request == nil else { return false }
        request = recording ? .stopping : .starting
        since = now
        return true
    }

    /// Камера сообщила, идёт ли запись (свойства перечитаны). Возвращает исход: подтверждение запроса или конец записи.
    public mutating func observe(recording: Bool, at now: Date) -> Outcome? {
        let ended = !recording && (self.recording == true || startedAt != nil)
        self.recording = recording
        if let outcome = confirm(recording: recording, at: now) { return outcome }
        guard ended else { return nil }
        let quick = startedAt.map { now.timeIntervalSince($0) < Self.abortWindow } ?? false
        startedAt = nil
        return quick ? .aborted : .stoppedByCamera
    }

    /// Вызов сессии вернулся: идёт ли запись, nil — вызов не удался. Состояние не то — ждём дальше: сессия ждёт
    /// подтверждения 2,5 с, а камера бывает медленнее; решит событие от камеры или тайм-аут. Конец записи здесь не
    /// ищем: ответ вызова может быть старше свойств, которые уже пришли.
    public mutating func completed(recording: Bool?, at now: Date) -> Outcome? {
        guard let request else { return nil }
        guard let recording else { return finish(.failed(request)) }
        return confirm(recording: recording, at: now)
    }

    /// Проверка по времени.
    public mutating func tick(now: Date) -> Outcome? {
        guard let request, let since, now.timeIntervalSince(since) > Self.timeout else { return nil }
        return finish(.failed(request))
    }

    /// Что сказать (у кнопки или в строке состояния — showsAtButton); nil — исход виден и так (кнопка, рамка записи).
    /// refusal — код, которым камера отклонила нажатие.
    public static func message(_ outcome: Outcome, refusal: UInt16?) -> String? {
        switch outcome {
        case .started, .stopped: return nil
        case let .failed(request):
            guard let refusal else { return request == .starting ? "Камера не начала запись" : "Камера не остановила запись" }
            let result = request == .starting ? "запись не началась" : "запись не остановилась"
            return refusal == PTPResponseCode.deviceBusy ? "Камера занята — \(result)" : "Камера не приняла нажатие — \(result)"
        case .aborted: return "Камера начала запись и сразу остановила"
        case .stoppedByCamera: return "Запись остановлена на камере"
        }
    }

    /// Подтверждает запрос; тогда и запоминает состояние — чтобы конец нашей же остановки не сочли концом «на камере».
    /// Сообщение — рядом с кнопкой записи, на месте «Запуск записи…»: отказ — ответ на нажатие, и смотрят в этот миг
    /// на кнопку. Конец записи на камере бывает когда угодно — его место в строке состояния.
    public static func showsAtButton(_ outcome: Outcome) -> Bool {
        switch outcome {
        case .failed, .aborted: return true
        case .started, .stopped, .stoppedByCamera: return false
        }
    }

    private mutating func confirm(recording: Bool, at now: Date) -> Outcome? {
        switch (request, recording) {
        case (.starting, true):
            self.recording = true
            startedAt = now
            return finish(.started)
        case (.stopping, false):
            self.recording = false
            startedAt = nil
            return finish(.stopped)
        default:
            return nil
        }
    }

    private mutating func finish(_ outcome: Outcome) -> Outcome {
        request = nil
        since = nil
        return outcome
    }
}
