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

private func levels(_ counts: [Int: Int]) -> [Int] {
    var levels = [Int](repeating: 0, count: 256)
    for (level, count) in counts { levels[level] = count }
    return levels
}

@Test func histogramLevelsGroupIntoBins() {
    let levels = LumaHistogram.levels(rgba: image(128, width: 10, height: 3), width: 10, height: 3, bytesPerRow: 40)
    #expect(levels.count == 256)
    #expect(levels[128] == 30)
    // столбцы графика — те же уровни, сложенные по четыре
    #expect(LumaHistogram.bins(levels: levels, count: 64)[32] == 30)
    #expect(LumaHistogram.bins(levels: levels, count: 64) == LumaHistogram.bins(rgba: image(128, width: 10, height: 3), width: 10, height: 3, bytesPerRow: 40))
}

@Test func histogramStatsSplitTheRangeIntoThirds() {
    // границы третей: 85 — ещё тени, 86 — уже полутона, 170 — полутона, 171 — света
    let stats = LumaStats(levels: levels([40: 20, 85: 10, 86: 30, 170: 20, 171: 15, 200: 5]))
    #expect(stats?.shadows == 30)
    #expect(stats?.midtones == 50)
    #expect(stats?.highlights == 20)
}

@Test func histogramStatsCountNearlyBlackAndNearlyWhite() {
    // почти чёрные — уровни 0–4; пересвет — на самом белом уровне (в фото 255, с запасом в один уровень)
    let stats = LumaStats(levels: levels([0: 2, 4: 1, 5: 7, 128: 80, 253: 6, 254: 3, 255: 1]))
    #expect(stats?.clippedShadows == 3)
    #expect(stats?.clippedHighlights == 4)
}

@Test func videoLiveViewIsWhiteAt234() {
    // в видео live view белое — 234: пересвеченное окно ложится столбиком ровно на 234
    let video = levels([20: 30, 120: 40, 233: 3, 234: 7, 200: 20])
    #expect(LumaStats.whiteLevel(levels: video, video: true) == 234)
    #expect(LumaStats.whiteLevel(levels: video, video: false) == 255)
    // ярче 240 больше 0,1 % точек — шкала у этого профиля полная
    #expect(LumaStats.whiteLevel(levels: levels([120: 998, 250: 2]), video: true) == 255)
    let stats = LumaStats(levels: video, whiteLevel: 234)
    #expect(stats?.clippedHighlights == 10)
    // трети и средняя — от шкалы до белого: 0–78, 79–156, 157–234
    #expect(LumaStats(levels: levels([78: 1, 79: 1, 156: 1, 157: 1]), whiteLevel: 234).map { [$0.shadows, $0.midtones, $0.highlights] } == [25, 50, 25])
    #expect(LumaStats(levels: levels([0: 1, 234: 1]), whiteLevel: 234)?.mean == 50)
    // столбцы тоже до белого: пересвет — у правого края
    let bins = LumaHistogram.bins(levels: video, count: 64, whiteLevel: 234)
    #expect(bins[63] == 10)
    #expect(bins.reduce(0, +) == 100)
}

@Test func histogramStatsMeanBrightness() {
    #expect(LumaStats(levels: levels([0: 1, 255: 1]))?.mean == 50)
    #expect(LumaStats(levels: levels([51: 4]))?.mean == 20)
    #expect(LumaStats(levels: [Int](repeating: 0, count: 256)) == nil)
}

@Test func histogramPercentLabels() {
    // доли зон — целые проценты, края — с десятой долей, пока их меньше 10 %
    #expect(LumaStats.share(38.4) == "38%")
    #expect(LumaStats.share(0.2) == "0%")
    #expect(LumaStats.clipping(0.44) == "0,4%")
    #expect(LumaStats.clipping(0) == "0%")
    #expect(LumaStats.clipping(12.6) == "13%")
    #expect(LumaStats.isClipped(1.0) && !LumaStats.isClipped(0.9))
}
