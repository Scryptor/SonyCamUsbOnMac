import Foundation

/// Какую камеру подключать и когда повторять попытку. Без ImageCaptureCore, чтобы проверять тестами.
public struct ConnectionTracker<ID: Hashable> {
    public enum Action: Equatable {
        case none
        case connect(ID)
        case disconnect(thenConnect: ID?)
        case retry(ID, after: Duration)
    }

    public private(set) var attached: [ID] = []
    public private(set) var current: ID?
    private var failures = 0

    public init() {}

    public mutating func appeared(_ id: ID) -> Action {
        if !attached.contains(id) { attached.append(id) }
        // уже работаем с этой или с другой подключённой камерой
        if let current, attached.contains(current) { return .none }
        current = id
        failures = 0
        return .connect(id)
    }

    public mutating func disappeared(_ id: ID) -> Action {
        attached.removeAll { $0 == id }
        guard current == id else { return .none }
        failures = 0
        current = attached.first
        return .disconnect(thenConnect: current)
    }

    public mutating func connected(_ id: ID) {
        if current == id { failures = 0 }
    }

    /// Попытка сорвалась (не отмена). Повторяем, только пока камера на месте: 2, 4, 8, 10, 10… с.
    public mutating func failed(_ id: ID) -> Action {
        guard current == id, attached.contains(id) else { return .none }
        failures += 1
        return .retry(id, after: .seconds(min(1 << failures, 10)))
    }
}
