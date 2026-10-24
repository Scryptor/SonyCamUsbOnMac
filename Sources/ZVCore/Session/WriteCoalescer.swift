import Foundation

/// Не даёт засыпать камеру записями: по каждому свойству одна запись за раз,
/// а всё, что пришло за это время, схлопывается в последнее значение.
public actor WriteCoalescer {
    private let write: @Sendable (UInt16, Int64) async -> Void
    private var inFlight: Set<UInt16> = []
    private var latest: [UInt16: Int64] = [:]
    private var idleWaiters: [CheckedContinuation<Void, Never>] = []

    public init(write: @escaping @Sendable (UInt16, Int64) async -> Void) {
        self.write = write
    }

    public func submit(_ code: UInt16, _ value: Int64) {
        if inFlight.contains(code) {
            latest[code] = value
            return
        }
        inFlight.insert(code)
        Task { await self.run(code, value) }
    }

    public func isPending(_ code: UInt16) -> Bool { inFlight.contains(code) }

    public func waitUntilIdle() async {
        if inFlight.isEmpty { return }
        await withCheckedContinuation { idleWaiters.append($0) }
    }

    private func run(_ code: UInt16, _ first: Int64) async {
        var value = first
        while true {
            await write(code, value)
            guard let next = latest.removeValue(forKey: code) else { break }
            value = next
        }
        inFlight.remove(code)
        if inFlight.isEmpty {
            idleWaiters.forEach { $0.resume() }
            idleWaiters.removeAll()
        }
    }
}
