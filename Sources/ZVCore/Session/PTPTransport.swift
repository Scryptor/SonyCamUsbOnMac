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
    case notReady
    case notWritable(UInt16)
    case valueNotAllowed(UInt16, Int64)
}

public struct SessionTiming: Sendable {
    public var settle: Duration
    public var confirmTimeout: Duration
    public var pollInterval: Duration

    public init(settle: Duration, confirmTimeout: Duration, pollInterval: Duration) {
        self.settle = settle
        self.confirmTimeout = confirmTimeout
        self.pollInterval = pollInterval
    }

    /// Камере нужно ~3 с после рукопожатия, прежде чем она начнёт применять настройки.
    public static let standard = SessionTiming(settle: .seconds(3), confirmTimeout: .milliseconds(2500),
                                               pollInterval: .milliseconds(150))
}
