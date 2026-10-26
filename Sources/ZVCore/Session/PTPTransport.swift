import Foundation

public struct PTPEvent: Equatable, Sendable {
    public let code: UInt16
    public let params: [UInt32]

    public init(code: UInt16, params: [UInt32] = []) {
        self.code = code
        self.params = params
    }
}

/// Канал до камеры: отправка PTP-операций и поток событий от камеры.
public protocol PTPTransport: AnyObject, Sendable {
    /// outData — только само значение, без контейнера: транспорт оборачивает его сам.
    func send(_ operation: UInt16, _ params: [UInt32], outData: Data?) async throws -> PTPReply
    var events: AsyncStream<PTPEvent> { get }
    /// Сколько пришло в текущей фазе данных — для доли загрузки снимка; nil — транспорт этого не знает.
    var dataProgress: DataProgress? { get }
}

extension PTPTransport {
    public var dataProgress: DataProgress? { nil }
}

/// Счётчик принятых байт текущей фазы данных (от Start Data до ответа). Транспорт добавляет всё, что пришло, —
/// считается только между begin и finish. Потокобезопасен: читается с экрана во время загрузки снимка.
public final class DataProgress: @unchecked Sendable {
    private let lock = NSLock()
    private var active = false
    private var received = 0
    private var expected: Int?

    public init() {}

    public func begin(expected: Int?) {
        lock.withLock {
            active = true
            received = 0
            self.expected = expected
        }
    }

    public func add(_ bytes: Int) {
        lock.withLock { if active { received += bytes } }
    }

    public func finish() {
        lock.withLock { active = false }
    }

    /// nil — данные сейчас не идут.
    public var current: (received: Int, expected: Int?)? {
        lock.withLock { active ? (received, expected) : nil }
    }
}

public enum TransportError: Error, Equatable {
    case disconnected
    case timeout(operation: UInt16)
    case failed(String)
}

public enum SessionError: Error, Equatable {
    case notPCRemote
    case noExtendedInfo
    case operationFailed(operation: UInt16, code: UInt16)
    /// Камера отклонила нажатие (release: false) или отпускание кнопки записи; recording — идёт ли запись после
    /// ожидания: камера бывает занята, а запись всё же начинает.
    case recordButtonRefused(release: Bool, code: UInt16, recording: Bool)
    case notReady
    case notWritable(UInt16)
    case valueNotAllowed(UInt16, Int64)
}

public struct SessionTiming: Sendable {
    public var settle: Duration
    public var confirmTimeout: Duration
    public var pollInterval: Duration
    /// Сколько ждать, пока снимок появится в памяти камеры.
    public var captureTimeout: Duration

    public init(settle: Duration, confirmTimeout: Duration, pollInterval: Duration, captureTimeout: Duration = .seconds(10)) {
        self.settle = settle
        self.confirmTimeout = confirmTimeout
        self.pollInterval = pollInterval
        self.captureTimeout = captureTimeout
    }

    /// Камере нужно ~3 с после рукопожатия, прежде чем она начнёт применять настройки.
    public static let standard = SessionTiming(settle: .seconds(3), confirmTimeout: .milliseconds(2500),
                                               pollInterval: .milliseconds(150))
}
