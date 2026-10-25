import Foundation
import os

/// Поток для нескольких слушателей: каждый подписывается сам и сразу получает последнее значение; отписка одного не
/// закрывает поток другим. Так открытый сеанс камеры можно передать от главного экрана ленте камер и обратно.
public final class Broadcast<Value: Sendable>: Sendable {
    private struct State {
        var latest: Value?
        var subscribers: [UUID: AsyncStream<Value>.Continuation] = [:]
        var finished = false
    }

    private let state = OSAllocatedUnfairLock(initialState: State())
    private let buffering: AsyncStream<Value>.Continuation.BufferingPolicy

    public init(buffering: AsyncStream<Value>.Continuation.BufferingPolicy = .unbounded) {
        self.buffering = buffering
    }

    /// Новая подписка: последнее значение — сразу, дальше — каждое новое; поток закончен — подписка тоже.
    public func subscribe() -> AsyncStream<Value> {
        let (stream, continuation) = AsyncStream.makeStream(of: Value.self, bufferingPolicy: buffering)
        let id = UUID()
        continuation.onTermination = { [weak self] _ in
            self?.state.withLock { _ = $0.subscribers.removeValue(forKey: id) }
        }
        let open = state.withLock { state -> Bool in
            guard !state.finished else { return false }
            state.subscribers[id] = continuation
            // последнее значение — под тем же замком, что и новые: новое его не обгонит
            if let latest = state.latest { continuation.yield(latest) }
            return true
        }
        // закрываем вне замка: onTermination сам берёт замок
        if !open { continuation.finish() }
        return stream
    }

    public func yield(_ value: Value) {
        state.withLock { state in
            guard !state.finished else { return }
            state.latest = value
            for subscriber in state.subscribers.values { subscriber.yield(value) }
        }
    }

    public func finish() {
        let subscribers = state.withLock { state -> [AsyncStream<Value>.Continuation] in
            state.finished = true
            defer { state.subscribers = [:] }
            return Array(state.subscribers.values)
        }
        for subscriber in subscribers { subscriber.finish() }
    }

    public var subscriberCount: Int { state.withLock { $0.subscribers.count } }
}
