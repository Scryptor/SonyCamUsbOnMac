import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

// MARK: - Помощники

/// Случайные числа без сюрпризов — «seed 1», как в `AssistBenchmark`.
private struct Lcg {
    private var seed: UInt32 = 1

    /// 0 ≤ x < 1.
    mutating func next() -> Double {
        seed = seed &* 1_664_525 &+ 1_013_904_223
        return Double(seed >> 8) / 16_777_216
    }
}

private struct Frame {
    let rgba: [UInt8]
    let width: Int
    let height: Int
    let bytesPerRow: Int
}

/// Кадр width×height: `colour(x, y)` — цвет точки. Массив кончается ровно на последней точке последней строки, а в хвосте
/// каждой строки (padding байт сверх ширины) лежит мусор: выход за край и неверный шаг строки сразу видны.
private func frame(width: Int, height: Int, padding: Int = 0, _ colour: (Int, Int) -> (UInt8, UInt8, UInt8)) -> Frame {
    let bytesPerRow = width * 4 + padding
    var rgba = [UInt8](repeating: 0xEE, count: (height - 1) * bytesPerRow + width * 4)
    for y in 0..<height {
        for x in 0..<width {
            let (r, g, b) = colour(x, y)
            let i = y * bytesPerRow + x * 4
            rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b; rgba[i + 3] = 255
        }
    }
    return Frame(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow)
}

/// Кадр одного цвета.
private func flat(_ r: UInt8, _ g: UInt8, _ b: UInt8, width: Int = 16, height: Int = 16) -> Frame {
    frame(width: width, height: height) { _, _ in (r, g, b) }
}

/// Серый кадр: на любой яркости R = G = B, и целая яркость по Rec. 709 равна самому уровню.
private func grey(_ level: Int, width: Int = 16, height: Int = 16) -> Frame {
    flat(UInt8(level), UInt8(level), UInt8(level), width: width, height: height)
}

private func probe(_ f: Frame, x: Double, y: Double, white: Int = 255) -> ColorProbe.Reading {
    ColorProbe.read(rgba: f.rgba, width: f.width, height: f.height, bytesPerRow: f.bytesPerRow,
                    at: CGPoint(x: x, y: y), white: white)
}

/// Эталон в лоб: среднее по прямоугольнику точек. Яркость каждой точки — целая, по Rec. 709 (коэффициенты ×1024), как у
/// гистограммы; процент — от белого, округлён.
private func reference(_ f: Frame, xs: ClosedRange<Int>, ys: ClosedRange<Int>, white: Int) -> (percent: Int, r: Int, g: Int, b: Int) {
    var sumR = 0, sumG = 0, sumB = 0, sumLuma = 0, n = 0
    for y in ys {
        for x in xs {
            let i = y * f.bytesPerRow + x * 4
            let r = Int(f.rgba[i]), g = Int(f.rgba[i + 1]), b = Int(f.rgba[i + 2])
            sumR += r; sumG += g; sumB += b
            sumLuma += (218 * r + 732 * g + 74 * b) >> 10
            n += 1
        }
    }
    let count = Double(n)
    return (Int((Double(sumLuma) / count / Double(white) * 100).rounded()), Int((Double(sumR) / count).rounded()),
            Int((Double(sumG) / count).rounded()), Int((Double(sumB) / count).rounded()))
}

/// Квадрат 5×5 вокруг точки, обрезанный краем кадра.
private func box(_ f: Frame, _ x: Int, _ y: Int) -> (xs: ClosedRange<Int>, ys: ClosedRange<Int>) {
    (max(x - 2, 0)...min(x + 2, f.width - 1), max(y - 2, 0)...min(y + 2, f.height - 1))
}

// MARK: - Среднее по квадрату

@Test func probeAveragesTheFiveByFiveSquare() {
    // 16×16; в квадрате 5×5 вокруг (8; 8) — 100 и 200 вперемешку (красный и зелёный наоборот, синий ровный), вокруг —
    // яркий пурпур: любой лишний или пропущенный ряд сдвинет среднее
    let f = frame(width: 16, height: 16) { x, y in
        guard (6...10).contains(x), (6...10).contains(y) else { return (255, 0, 255) }
        let even = (x + y) % 2 == 0
        let red: UInt8 = even ? 100 : 200
        let green: UInt8 = even ? 200 : 100
        return (red, green, 150)
    }
    let reading = probe(f, x: 8, y: 8)
    let want = reference(f, xs: 6...10, ys: 6...10, white: 255)
    // 13 точек одного рода и 12 другого: красный (13·100 + 12·200)/25 = 148, зелёный 152, синий 150
    #expect(abs(reading.r - 148) <= 1)
    #expect(abs(reading.g - 152) <= 1)
    #expect(abs(reading.b - 150) <= 1)
    #expect(abs(reading.r - want.r) <= 1)
    #expect(abs(reading.g - want.g) <= 1)
    #expect(abs(reading.b - want.b) <= 1)
    // яркость каждой точки — целая (175 или 124), среднее 150,52 — 59 % от 255
    #expect(abs(reading.percent - want.percent) <= 1)
    #expect(reading.percent == 59)
}

@Test func probeReadsTheChannelsInOrderAndRoundsHalfUp() {
    let ordered = probe(flat(10, 20, 30), x: 8, y: 8)
    #expect(ordered.r == 10)
    #expect(ordered.g == 20)
    #expect(ordered.b == 30)
    // 2×1: красный 1 и 2, у края кадра квадрат — это обе точки; среднее 1,5 — до 2
    let pair = frame(width: 2, height: 1) { x, _ in (UInt8(x + 1), 0, 0) }
    #expect(probe(pair, x: 0, y: 0).r == 2)
    #expect(probe(pair, x: 1, y: 0).r == 2)
}

@Test func probeSquareIsExactlyFiveAcrossNotMore() {
    // за квадратом — другой цвет: ровно две точки в каждую сторону от точки, не три и не одна
    let f = frame(width: 16, height: 16) { x, y in
        let inside = abs(x - 8) <= 2 && abs(y - 8) <= 2
        return inside ? (60, 60, 60) : (250, 250, 250)
    }
    #expect(probe(f, x: 8, y: 8).r == 60)
    // сдвинули на точку — в квадрат попал внешний ряд и столбец: 16 тёмных и 9 светлых → (16·60 + 9·250)/25 = 128,4
    #expect(probe(f, x: 9, y: 9).r == 128)
}

@Test func probeTakesThePixelUnderThePoint() {
    // точка кадра — та, в которую попала координата: 8,0 и 8,99 — точка 8, 9,0 — уже 9
    let f = frame(width: 16, height: 16) { x, _ in (UInt8(x * 10), 0, 0) }
    let at8 = probe(f, x: 8, y: 8)
    #expect(probe(f, x: 8.99, y: 8.5) == at8)
    #expect(probe(f, x: 9, y: 8) != at8)
    #expect(probe(f, x: 7.99, y: 8) != at8)
}

// MARK: - Процент и зона

@Test func probePercentIsBrightnessOfTheWhite() {
    // яркость 117: в видео белое — 234, 117 — ровно половина; в фото белое — 255, 117 — 45,9 %
    #expect(probe(grey(117), x: 8, y: 8, white: 234).percent == 50)
    #expect(probe(grey(117), x: 8, y: 8, white: 255).percent == 46)
    #expect(probe(grey(0), x: 8, y: 8, white: 255).percent == 0)
    #expect(probe(grey(255), x: 8, y: 8, white: 255).percent == 100)
}

@Test func probePercentOfBrighterThanWhiteIsOverAHundred() {
    // в видео белое на 234: уровень 255 — выше белого превью
    let reading = probe(grey(255), x: 8, y: 8, white: 234)
    #expect(reading.percent == 109)
    #expect(reading.zone == .clipped)
}

@Test func probeNamesTheFalseColourZone() {
    // серый 41 % (уровень 105 — 18 % серого в S-Log3): зона «18 % серого»
    let middle = probe(grey(105), x: 8, y: 8)
    #expect(middle.percent == 41)
    #expect(middle.zone == .middleGray)
    #expect(middle.lines.0 == "41 % · 18 % серого")
    // 20 % лежит между зонами «тени» и «18 % серого»: подписи нет
    let between = probe(grey(51), x: 8, y: 8)
    #expect(between.percent == 20)
    #expect(between.zone == nil)
    #expect(between.lines.0 == "20 %")
}

@Test func probeZoneIsTheOneFalseColourPaintsForEveryLevel() {
    // плоский участок любого уровня получает ту зону, которой false color красит его на экране, — при обоих белых
    for white in [234, 255] {
        for level in 0...255 {
            let reading = ColorProbe.read(rgba: [UInt8(level), UInt8(level), UInt8(level), 255], width: 1, height: 1,
                                          bytesPerRow: 4, at: CGPoint(x: 0, y: 0), white: white)
            #expect(reading.zone == AssistRaster.falseColorBand(level, white: white))
            #expect(reading.percent == Int((Double(level) * 100 / Double(white)).rounded()))
        }
    }
}

@Test func probeAveragesTheLumaOfEachPointNotTheLumaOfTheAverageColour() {
    // красные и зелёные точки вперемешку: яркость каждой — целая (61 и 181), усредняются они: (13·61 + 12·181)/25 = 118,6.
    // Яркость среднего цвета (135; 125; 10) была бы 118 — на процент меньше
    let f = frame(width: 16, height: 16) { x, y in
        guard (6...10).contains(x), (6...10).contains(y) else { return (0, 0, 0) }
        return (x + y) % 2 == 0 ? (250, 10, 10) : (10, 250, 10)
    }
    #expect(probe(f, x: 8, y: 8, white: 255).percent == 47)
    #expect(probe(f, x: 8, y: 8, white: 234).percent == 51)
    let mean = probe(f, x: 8, y: 8)
    #expect(mean.r == 135 && mean.g == 125 && mean.b == 10)
}

@Test func probeZoneIsOfTheLevelNearestToTheAverage() {
    // уровни 96 и 97 (37,6 % и 38,0 % от 255; зона «18 % серого» начинается с 38 %), среднее 96,5 — ближайший уровень 97
    #expect(AssistRaster.falseColorBand(96, white: 255) == nil)
    #expect(AssistRaster.falseColorBand(97, white: 255) == .middleGray)
    let pair = frame(width: 2, height: 1) { x, _ in
        let v = UInt8(96 + x)
        return (v, v, v)
    }
    let between = probe(pair, x: 0, y: 0)
    #expect(between.percent == 38)
    #expect(between.zone == .middleGray)
    // а плоский участок уровня 96 тоже «38 %», но false color его в «18 % серого» не красит — и подписи зоны нет
    let flat96 = probe(grey(96), x: 8, y: 8)
    #expect(flat96.percent == 38)
    #expect(flat96.zone == nil)
    #expect(flat96.lines.0 == "38 %")
}

@Test func probeZoneFollowsTheAverageNotTheCentrePoint() {
    // левые две колонки квадрата чёрные, правые три — серый 105 (41 %, «18 % серого»); сама точка (8; 8) — серая
    let f = frame(width: 16, height: 16) { x, _ in x < 8 ? (0, 0, 0) : (105, 105, 105) }
    #expect(probe(grey(105), x: 8, y: 8).zone == .middleGray)
    let reading = probe(f, x: 8, y: 8)
    // 15 точек по 105 и 10 по 0: среднее 63 — 25 %, между зонами: подпись — по среднему, а не по точке под меткой
    #expect(reading.r == 63)
    #expect(reading.percent == 25)
    #expect(reading.zone == nil)
}

@Test func probePercentAgreesWithTheHistogramLuma() {
    // та же яркость, что у гистограммы: для любого цвета уровень из `LumaHistogram` даёт тот же процент
    var random = Lcg()
    for _ in 0..<300 {
        let colour = [UInt8(random.next() * 256), UInt8(random.next() * 256), UInt8(random.next() * 256), 255]
        let levels = LumaHistogram.levels(rgba: colour, width: 1, height: 1, bytesPerRow: 4)
        guard let level = levels.firstIndex(of: 1) else {
            Issue.record("гистограмма пуста")
            continue
        }
        for white in [234, 255] {
            let reading = ColorProbe.read(rgba: colour, width: 1, height: 1, bytesPerRow: 4, at: CGPoint(x: 0, y: 0), white: white)
            #expect(reading.percent == Int((Double(level) * 100 / Double(white)).rounded()))
            #expect(reading.zone == AssistRaster.falseColorBand(level, white: white))
        }
    }
}

// MARK: - Подписи

@Test func probeLinesAreTheTwoLabelLines() {
    // пример из спецификации
    let skin = ColorProbe.Reading(percent: 47, r: 182, g: 140, b: 121, zone: .skin)
    #expect(skin.lines.0 == "47 % · кожа")
    #expect(skin.lines.1 == "R 182 · G 140 · B 121")
    // без зоны — только процент
    let none = ColorProbe.Reading(percent: 47, r: 182, g: 140, b: 121, zone: nil)
    #expect(none.lines.0 == "47 %")
    #expect(none.lines.1 == "R 182 · G 140 · B 121")
}

@Test func probeLinesNameEveryZone() {
    let titles = ["провал", "тени", "18 % серого", "кожа", "почти белое", "пересвет"]
    #expect(AssistRaster.FalseColorBand.allCases.map(\.title) == titles)
    for band in AssistRaster.FalseColorBand.allCases {
        #expect(ColorProbe.Reading(percent: 12, r: 0, g: 0, b: 0, zone: band).lines.0 == "12 % · \(band.title)")
    }
}

@Test func probeLinesOfARealSkinColour() {
    // светлая кожа (182; 140; 121): яркость 147 — 58 % от 255, в зоне «кожа» (52–60 %)
    let reading = probe(flat(182, 140, 121), x: 8, y: 8)
    #expect(reading.percent == 58)
    #expect(reading.zone == .skin)
    #expect(reading.lines.0 == "58 % · кожа")
    #expect(reading.lines.1 == "R 182 · G 140 · B 121")
}

@Test func probeLinesOfBlackAndWhite() {
    #expect(probe(grey(0), x: 8, y: 8).lines.0 == "0 % · провал")
    #expect(probe(grey(255), x: 8, y: 8).lines.0 == "100 % · пересвет")
    #expect(probe(grey(255), x: 8, y: 8).lines.1 == "R 255 · G 255 · B 255")
}

@Test func probeReadingsCompareByValue() {
    let a = probe(flat(10, 20, 30), x: 3, y: 3)
    #expect(a == probe(flat(10, 20, 30), x: 12, y: 5))
    #expect(a != probe(flat(10, 20, 31), x: 3, y: 3))
    #expect(ColorProbe.Reading(percent: 1, r: 2, g: 3, b: 4, zone: .shadows) != ColorProbe.Reading(percent: 1, r: 2, g: 3, b: 4, zone: nil))
}

// MARK: - Край кадра

/// Кадр 1024×1024 (фото 1:1), цвет зависит от места: красный — от столбца, зелёный — от строки, синий ровный. Среднее
/// обрезанного квадрата отличается и от сдвинутого внутрь, и от квадрата с лишней строкой.
private func positional() -> Frame {
    frame(width: 1024, height: 1024) { x, y in (UInt8((x * 10) & 255), UInt8((y * 10) & 255), 7) }
}

@Test func probeClipsTheSquareAtTheEdgeOfTheFrame() {
    // точка (1; 1): квадрат 5×5 обрезан до столбцов и строк 0…3
    let f = positional()
    let reading = probe(f, x: 1, y: 1)
    // красный по столбцам 0, 10, 20, 30 → 15; зелёный так же; синий 7
    #expect(reading.r == 15)
    #expect(reading.g == 15)
    #expect(reading.b == 7)
    let want = reference(f, xs: 0...3, ys: 0...3, white: 255)
    #expect(reading.r == want.r && reading.g == want.g && reading.b == want.b)
    #expect(reading.percent == want.percent)
}

@Test func probeClipsTheSquareAtEveryEdgeAndCorner() {
    let f = positional()
    let points: [(Int, Int)] = [
        (0, 0), (1, 0), (0, 1), (2, 2), (1023, 1023), (1022, 1), (2, 1021), (1023, 0), (0, 1023),
        (512, 0), (512, 1023), (0, 512), (1023, 512), (1021, 1021), (3, 3),
    ]
    for (x, y) in points {
        let (xs, ys) = box(f, x, y)
        let want = reference(f, xs: xs, ys: ys, white: 234)
        let got = probe(f, x: Double(x), y: Double(y), white: 234)
        #expect(got.r == want.r && got.g == want.g && got.b == want.b)
        #expect(got.percent == want.percent)
    }
}

@Test func probeClipsPointsOutsideTheFrameToTheNearestPixel() {
    let f = positional()
    #expect(probe(f, x: -10, y: -10) == probe(f, x: 0, y: 0))
    #expect(probe(f, x: -0.5, y: 3) == probe(f, x: 0, y: 3))
    #expect(probe(f, x: 5000, y: 5000) == probe(f, x: 1023, y: 1023))
    #expect(probe(f, x: 1024, y: 7) == probe(f, x: 1023, y: 7))
    #expect(probe(f, x: .infinity, y: -.infinity) == probe(f, x: 1023, y: 0))
    #expect(probe(f, x: 1e300, y: -1e300) == probe(f, x: 1023, y: 0))
    // не число — как 0, и не падаем
    #expect(probe(f, x: .nan, y: .nan) == probe(f, x: 0, y: 0))
}

@Test func probeWorksOnNonSixteenByNineFrames() {
    // 3:2 (1024×680) и 640×360: низ и правый край любого кадра
    for (w, h) in [(1024, 680), (640, 360), (1, 1), (3, 2)] {
        let f = frame(width: w, height: h) { x, y in (UInt8(x & 255), UInt8(y & 255), 90) }
        for (x, y) in [(w - 1, h - 1), (w - 1, 0), (0, h - 1), (w / 2, h / 2), (w - 2, h - 2)] where x >= 0 && y >= 0 {
            let (xs, ys) = box(f, x, y)
            let want = reference(f, xs: xs, ys: ys, white: 255)
            let got = probe(f, x: Double(x), y: Double(y))
            #expect(got.r == want.r && got.g == want.g && got.b == want.b)
            #expect(got.percent == want.percent)
        }
    }
}

@Test func probeSkipsTheRowPadding() {
    // строка 16 точек в 80 байтах; цвет зависит от места, а в хвосте строки — мусор 0xEE: неверный шаг строки или чтение
    // хвоста сразу сдвинут среднее
    let f = frame(width: 16, height: 16, padding: 16) { x, y in (UInt8(x * 10), UInt8(y * 10), 90) }
    #expect(f.bytesPerRow == 80)
    for (x, y) in [(8, 8), (15, 15), (15, 0), (0, 15), (14, 1), (2, 13), (7, 3)] {
        let (xs, ys) = box(f, x, y)
        let want = reference(f, xs: xs, ys: ys, white: 255)
        let got = probe(f, x: Double(x), y: Double(y))
        #expect(got.r == want.r && got.g == want.g && got.b == want.b)
        #expect(got.percent == want.percent)
    }
}

@Test func probeOfBrokenFramesIsEmptyAndSafe() {
    let empty = ColorProbe.Reading(percent: 0, r: 0, g: 0, b: 0, zone: nil)
    let rgba = flat(200, 100, 50, width: 4, height: 4).rgba
    func broken(width: Int, height: Int, bytesPerRow: Int, rgba: [UInt8]) -> ColorProbe.Reading {
        ColorProbe.read(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow, at: CGPoint(x: 1, y: 1), white: 255)
    }
    #expect(broken(width: 0, height: 4, bytesPerRow: 16, rgba: rgba) == empty)
    #expect(broken(width: 4, height: 0, bytesPerRow: 16, rgba: rgba) == empty)
    #expect(broken(width: -4, height: 4, bytesPerRow: 16, rgba: rgba) == empty)
    // массив короче кадра
    #expect(broken(width: 4, height: 5, bytesPerRow: 16, rgba: rgba) == empty)
    #expect(broken(width: 4, height: 4, bytesPerRow: 16, rgba: []) == empty)
    // шаг строки меньше самой строки или отрицательный
    #expect(broken(width: 4, height: 4, bytesPerRow: 8, rgba: rgba) == empty)
    #expect(broken(width: 4, height: 4, bytesPerRow: -16, rgba: rgba) == empty)
}

@Test func probeSurvivesAWhiteOfZero() {
    // белого нет — делить на ноль нельзя; считаем как белое 1, процент большой, но число
    let reading = probe(grey(100), x: 8, y: 8, white: 0)
    #expect(reading.percent == 10_000)
    #expect(reading.zone == .clipped)
    let negative = probe(grey(100), x: 8, y: 8, white: -5)
    #expect(negative.percent == 10_000)
}
