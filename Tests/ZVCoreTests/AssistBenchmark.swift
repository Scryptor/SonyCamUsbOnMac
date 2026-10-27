import Foundation
import Testing
@testable import ZVCore

/// Сколько стоит растр помощи на кадре 1024×576: ZV_BENCH=1 swift test -c release -Xswiftc -enable-testing --filter assistBenchmark
@Test(.enabled(if: ProcessInfo.processInfo.environment["ZV_BENCH"] != nil))
func assistBenchmark() {
    let w = 1024, h = 576
    var rgba = [UInt8](repeating: 0, count: w * h * 4)
    var seed: UInt32 = 1
    for i in stride(from: 0, to: rgba.count, by: 4) {
        seed = seed &* 1_664_525 &+ 1_013_904_223
        let v = UInt8(min(255, (i / 4 % w) / 4 + Int(seed >> 28)))
        rgba[i] = v; rgba[i + 1] = v; rgba[i + 2] = v; rgba[i + 3] = 255
    }
    var all = MonitorAssist()
    all.set(.zebra, "95"); all.set(.falseColor, "on"); all.set(.peaking, "mid")
    func time(_ name: String, _ n: Int = 20, _ body: () -> Void) {
        let start = Date()
        for _ in 0..<n { body() }
        print(String(format: "%@: %.2f мс", name, Date().timeIntervalSince(start) * 1000 / Double(n)))
    }
    var luma: [UInt8] = []
    time("luma") { luma = AssistRaster.luma(rgba: rgba, width: w, height: h, bytesPerRow: w * 4) }
    let full = AssistRaster.plan(frameWidth: w, frameHeight: h, visible: (0, 0, 1, 1), factor: 1)
    let eight = AssistRaster.plan(frameWidth: w, frameHeight: h, visible: (0.4375, 0.4375, 0.125, 0.125), factor: 8)
    var part: [UInt8] = []
    time("resample 1×") { part = AssistRaster.resample(luma, width: w, height: h, plan: full) }
    time("resample 8×") { _ = AssistRaster.resample(luma, width: w, height: h, plan: eight) }
    time("peaking mask") { _ = AssistRaster.peakingMask(part, width: w, height: h, threshold: 30, scale: 1) }
    time("render all") { _ = AssistRaster.render(luma: part, width: w, height: h, scale: 1, white: 234, assist: all) }
    var zebra = MonitorAssist(); zebra.set(.zebra, "95")
    time("render zebra") { _ = AssistRaster.render(luma: part, width: w, height: h, scale: 1, white: 234, assist: zebra) }
}
