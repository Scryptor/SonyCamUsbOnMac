import Foundation
import Testing
@testable import ZVCore

private actor Recorder {
    var values: [Int64] = []
    func add(_ v: Int64) { values.append(v) }
}

@Test func rapidSubmitsWriteFirstAndLast() async {
    let rec = Recorder()
    let c = WriteCoalescer { _, value in
        await rec.add(value)
        try? await Task.sleep(for: .milliseconds(30))
    }
    for v: Int64 in [1, 2, 3, 4, 5] { await c.submit(0x5005, v) }
    await c.waitUntilIdle()
    #expect(await rec.values == [1, 5])
}
