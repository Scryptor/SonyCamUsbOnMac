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

// MARK: - Размах яркости p1–p99

@Test func percentileIsTheSmallestLevelThatReachesTheShare() {
    // 100 точек: одна на 10, 98 на 100, одна на 200
    let spread = levels([10: 1, 100: 98, 200: 1])
    #expect(LumaStats.percentile(levels: spread, percent: 1) == 10)
    // 99-я точка лежит ещё на уровне 100: на 200 ложится только сотая
    #expect(LumaStats.percentile(levels: spread, percent: 99) == 100)
    #expect(LumaStats.percentile(levels: spread, percent: 100) == 200)
    // ровно на границе: 50 из 100 точек набраны на уровне 0 — 50 % уже там, 51 % — на следующем
    let halves = levels([0: 50, 255: 50])
    #expect(LumaStats.percentile(levels: halves, percent: 50) == 0)
    #expect(LumaStats.percentile(levels: halves, percent: 51) == 255)
}

@Test func spreadIsTheWidthBetweenTheFirstAndTheNinetyNinthPercent() {
    #expect(LumaStats.spread(levels: levels([10: 1, 100: 98, 200: 1])) == 90)
    // выбросы по одной точке из тысячи (0,1 %) размах не раздувают: берутся 1-й и 99-й проценты, а не наименьший и наибольший
    #expect(LumaStats.spread(levels: levels([0: 1, 100: 499, 140: 499, 255: 1])) == 40)
    // плоский лог уже: вся картинка в 106 уровнях, после LUT — в 219
    let flat = levels([45: 10, 100: 80, 151: 10])
    let graded = levels([14: 10, 100: 80, 233: 10])
    #expect(LumaStats.spread(levels: flat) == 106)
    #expect(LumaStats.spread(levels: graded) == 219)
    #expect(LumaStats.spread(levels: graded) > LumaStats.spread(levels: flat))
}

@Test func spreadOfOneLevelOrNoPointsIsZero() {
    #expect(LumaStats.spread(levels: levels([128: 5000])) == 0)
    #expect(LumaStats.spread(levels: [Int](repeating: 0, count: 256)) == 0)
    #expect(LumaStats.percentile(levels: [Int](repeating: 0, count: 256), percent: 99) == 0)
    #expect(LumaStats.spread(levels: []) == 0)
}

@Test func spreadAgreesWithAFullSortOfThePoints() {
    // независимая проверка: уровни раскладываются в список точек, и берётся точка с номером ⌈p · n / 100⌉ (с единицы)
    var seed: UInt32 = 7
    func next() -> Int {
        seed = seed &* 1_664_525 &+ 1_013_904_223
        return Int(seed >> 16)
    }
    for round in 0..<20 {
        var counts = [Int](repeating: 0, count: 256)
        let lo = next() % 120, hi = 120 + next() % 136
        for _ in 0..<(300 + round * 37) { counts[lo + next() % (hi - lo + 1)] += 1 }
        let points = counts.enumerated().flatMap { Array(repeating: $0.offset, count: $0.element) }
        func nth(_ percent: Int) -> Int { points[Int((Double(percent) * Double(points.count) / 100).rounded(.up)) - 1] }
        #expect(LumaStats.percentile(levels: counts, percent: 1) == nth(1), "p1, круг \(round)")
        #expect(LumaStats.percentile(levels: counts, percent: 99) == nth(99), "p99, круг \(round)")
        #expect(LumaStats.spread(levels: counts) == nth(99) - nth(1), "размах, круг \(round)")
    }
}
