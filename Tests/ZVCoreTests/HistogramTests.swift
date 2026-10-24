import Foundation
import Testing
@testable import ZVCore

private func image(_ value: UInt8, width: Int = 4, height: Int = 2) -> [UInt8] {
    var pixels: [UInt8] = []
    for _ in 0..<(width * height) { pixels += [value, value, value, 255] }
    return pixels
}

@Test func histogramPutsBlackGrayWhiteIntoExpectedBins() {
    #expect(LumaHistogram.bins(rgba: image(0), width: 4, height: 2, bytesPerRow: 16, count: 64)[0] == 8)
    #expect(LumaHistogram.bins(rgba: image(128), width: 4, height: 2, bytesPerRow: 16, count: 64)[32] == 8)
    #expect(LumaHistogram.bins(rgba: image(255), width: 4, height: 2, bytesPerRow: 16, count: 64)[63] == 8)
}

@Test func histogramCountsEveryPixelOnce() {
    let bins = LumaHistogram.bins(rgba: image(77, width: 10, height: 3), width: 10, height: 3, bytesPerRow: 40, count: 64)
    #expect(bins.count == 64)
    #expect(bins.reduce(0, +) == 30)
}
