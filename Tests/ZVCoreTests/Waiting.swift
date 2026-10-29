import Foundation

/// Собирает значения потока в фоне: тест ждёт их с потолком, а не само значение, — сломанный код роняет проверку, а не
/// подвешивает тест (`AsyncStream.next()` потолка не имеет).
final class Collected<Value: Sendable>: @unchecked Sendable {
    private let lock = NSLock()
    private var received: [Value] = []
    private var ended = false
    private var task: Task<Void, Never>?

    init(_ stream: AsyncStream<Value>) {
        task = Task { [self] in
            for await value in stream { lock.withLock { received.append(value) } }
            lock.withLock { ended = true }
        }
    }

    var values: [Value] { lock.withLock { received } }
    var isEnded: Bool { lock.withLock { ended } }
    func stop() { task?.cancel() }
}

/// Ждёт условия не дольше `seconds`.
func eventually(_ seconds: Double = 2, _ condition: () async -> Bool) async -> Bool {
    let end = Date().addingTimeInterval(seconds)
    while Date() < end {
        if await condition() { return true }
        try? await Task.sleep(for: .milliseconds(5))
    }
    return await condition()
}
