import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

// MARK: - Помощники

private func near(_ a: Double, _ b: Double, _ eps: Double) -> Bool { abs(a - b) <= eps }

/// Случайные числа без сюрпризов — «seed 1», как в `AssistBenchmark`: те же цвета в каждом прогоне и на каждой машине.
private struct Lcg {
    private var seed: UInt32 = 1

    /// 0 ≤ x < 1.
    mutating func next() -> Double {
        seed = seed &* 1_664_525 &+ 1_013_904_223
        return Double(seed >> 8) / 16_777_216
    }
}

/// Кадр одного цвета, без запаса в конце строки.
private func flat(_ r: UInt8, _ g: UInt8, _ b: UInt8, width: Int = 64, height: Int = 36) -> [UInt8] {
    var pixels: [UInt8] = []
    pixels.reserveCapacity(width * height * 4)
    for _ in 0..<(width * height) { pixels += [r, g, b, 255] }
    return pixels
}

/// Самая полная корзина: столбец, строка и сколько в ней; nil — пусто. При равных — первая сверху слева.
private func fullest(_ counts: [UInt32]) -> (x: Int, y: Int, n: UInt32)? {
    guard let top = counts.max(), top > 0, let i = counts.firstIndex(of: top) else { return nil }
    return (i % Vectorscope.bins, i / Vectorscope.bins, top)
}

private func fullestOrFail(_ counts: [UInt32]) -> (x: Int, y: Int, n: UInt32) {
    guard let top = fullest(counts) else {
        Issue.record("в счётчиках пусто")
        return (0, 0, 0)
    }
    return top
}

/// Счётчики, где заполнены только перечисленные корзины.
private func counts(_ filled: [(x: Int, y: Int, n: UInt32)]) -> [UInt32] {
    var counts = [UInt32](repeating: 0, count: Vectorscope.bins * Vectorscope.bins)
    for cell in filled { counts[cell.y * Vectorscope.bins + cell.x] = cell.n }
    return counts
}

/// Точка картинки вектороскопа.
private func pixel(_ image: [UInt8], x: Int, y: Int) -> (r: Int, g: Int, b: Int, a: Int) {
    let i = (y * Vectorscope.bins + x) * 4
    return (Int(image[i]), Int(image[i + 1]), Int(image[i + 2]), Int(image[i + 3]))
}

/// Расстояние от середины плоскости до середины корзины, в корзинах, по каждой оси.
private func offset(_ cell: (x: Int, y: Int, n: UInt32)) -> (dx: Double, dy: Double) {
    let middle = Double(Vectorscope.bins) / 2
    return (abs(Double(cell.x) + 0.5 - middle), abs(Double(cell.y) + 0.5 - middle))
}

/// Цвет корзины по справочным формулам BT.709 в обратную сторону, написанным отдельно от кода: Y = 0,6, Cb и Cr — середина
/// корзины при ×1; R = Y + 1,5748·Cr, B = Y + 1,8556·Cb, G = Y − 0,1873·Cb − 0,4681·Cr; всё обрезано до 0…1.
private func hue(x: Int, y: Int) -> (r: Double, g: Double, b: Double) {
    let cb = (Double(x) + 0.5) / 128 - 0.5
    let cr = 0.5 - (Double(y) + 0.5) / 128
    func clamp(_ v: Double) -> Double { min(max(v, 0), 1) }
    return (clamp(0.6 + 1.5748 * cr), clamp(0.6 - 0.1873 * cb - 0.4681 * cr), clamp(0.6 + 1.8556 * cb))
}

// MARK: - Плоскость

@Test func vectorscopeKeepsItsConstants() {
    // 128×128 корзин; кожа — под углом 123° от оси +Cb
    #expect(Vectorscope.bins == 128)
    #expect(Vectorscope.skinAngle == 123)
}

@Test func chromaOfSeventyFivePercentRed() {
    // красный 75 %: Y = 0,15945; Cb = −Y/1,8556; Cr = (0,75 − Y)/1,5748 = 0,375
    let c = Vectorscope.chroma(r: 0.75, g: 0, b: 0)
    #expect(near(c.cb, -0.0859, 1e-3))
    #expect(near(c.cr, 0.3750, 1e-3))
}

@Test func chromaOfGreyIsZero() {
    let c = Vectorscope.chroma(r: 0.5, g: 0.5, b: 0.5)
    #expect(c.cb == 0)
    #expect(c.cr == 0)
}

@Test func chromaOfEveryGreyLevelIsExactlyZero() {
    // не «почти ноль»: иначе серый кадр дрожал бы между соседними корзинами в зависимости от уровня
    for level in 0...255 {
        let v = Double(level) / 255
        let c = Vectorscope.chroma(r: v, g: v, b: v)
        #expect(c.cb == 0)
        #expect(c.cr == 0)
    }
}

@Test func chromaFollowsTheRec709Formulas() {
    // формулы из спецификации, в лоб: Y = 0,2126R + 0,7152G + 0,0722B, Cb = (B − Y)/1,8556, Cr = (R − Y)/1,5748
    var random = Lcg()
    for _ in 0..<500 {
        let r = random.next(), g = random.next(), b = random.next()
        let y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        let c = Vectorscope.chroma(r: r, g: g, b: b)
        #expect(near(c.cb, (b - y) / 1.8556, 1e-12))
        #expect(near(c.cr, (r - y) / 1.5748, 1e-12))
    }
}

@Test func chromaOfFullySaturatedColoursReachTheEdgeOfTheSquare() {
    // красный и синий упираются в ±0,5 по своей оси
    let red = Vectorscope.chroma(r: 1, g: 0, b: 0)
    #expect(near(red.cb, -0.2126 / 1.8556, 1e-12))
    #expect(near(red.cr, 0.5, 1e-12))
    let blue = Vectorscope.chroma(r: 0, g: 0, b: 1)
    #expect(near(blue.cb, 0.5, 1e-12))
    #expect(near(blue.cr, -0.0722 / 1.5748, 1e-12))
}

@Test func targetsAreTheSixSeventyFivePercentBars() {
    // красный, пурпурный, синий, голубой, зелёный, жёлтый — по кругу; числа посчитаны отдельно (Python, те же формулы)
    let expected: [(name: String, cb: Double, cr: Double)] = [
        ("R", -0.08593, 0.37500), ("Mg", 0.28907, 0.34061), ("B", 0.37500, -0.03439),
        ("Cy", 0.08593, -0.37500), ("G", -0.28907, -0.34061), ("Yl", -0.37500, 0.03439),
    ]
    #expect(Vectorscope.targets.map(\.name) == expected.map(\.name))
    for (target, want) in zip(Vectorscope.targets, expected) {
        #expect(near(target.cb, want.cb, 1e-4))
        #expect(near(target.cr, want.cr, 1e-4))
    }
}

@Test func targetsAreComputedByTheSameFormulas() {
    // не таблица чисел, а те же `chroma` от полос 75 %
    let bars: [(Double, Double, Double)] = [(0.75, 0, 0), (0.75, 0, 0.75), (0, 0, 0.75), (0, 0.75, 0.75), (0, 0.75, 0), (0.75, 0.75, 0)]
    #expect(Vectorscope.targets.count == bars.count)
    for (target, bar) in zip(Vectorscope.targets, bars) {
        let c = Vectorscope.chroma(r: bar.0, g: bar.1, b: bar.2)
        #expect(target.cb == c.cb)
        #expect(target.cr == c.cr)
    }
    // дополнительные цвета лежат напротив: R–Cy, Mg–G, B–Yl
    for (a, b) in [(0, 3), (1, 4), (2, 5)] {
        #expect(near(Vectorscope.targets[a].cb, -Vectorscope.targets[b].cb, 1e-12))
        #expect(near(Vectorscope.targets[a].cr, -Vectorscope.targets[b].cr, 1e-12))
    }
}

// MARK: - Корзины

@Test func binPutsTheCentreInTheMiddleOfTheGrid() {
    // 128 корзин — чётное число, середина плоскости лежит на стыке корзин 63 и 64; ровно ноль идёт в 64
    #expect(Vectorscope.bin(cb: 0, cr: 0, zoom: 1)?.x == 64)
    #expect(Vectorscope.bin(cb: 0, cr: 0, zoom: 1)?.y == 64)
    #expect(Vectorscope.bin(cb: 0, cr: 0, zoom: 2)?.x == 64)
    #expect(Vectorscope.bin(cb: 0, cr: 0, zoom: 2)?.y == 64)
}

@Test func binGoesRightWithCbAndUpWithCr() {
    // Cb — вправо, Cr — вверх, y = 0 вверху
    let right = Vectorscope.bin(cb: 0.25, cr: 0, zoom: 1)
    let left = Vectorscope.bin(cb: -0.25, cr: 0, zoom: 1)
    let up = Vectorscope.bin(cb: 0, cr: 0.25, zoom: 1)
    let down = Vectorscope.bin(cb: 0, cr: -0.25, zoom: 1)
    #expect(right?.x == 96 && right?.y == 64)
    #expect(left?.x == 32 && left?.y == 64)
    #expect(up?.x == 64 && up?.y == 32)
    #expect(down?.x == 64 && down?.y == 96)
}

@Test func binCellsAreOneOverBinsWideAndNarrowerWhenZoomed() {
    // шаг корзины при ×1 — 1/128, при ×2 — 1/256; граница между корзинами 64 и 65 лежит ровно на шаге
    #expect(Vectorscope.bin(cb: 1.0 / 128, cr: 0, zoom: 1)?.x == 65)
    #expect(Vectorscope.bin(cb: 1.0 / 128 - 1e-6, cr: 0, zoom: 1)?.x == 64)
    #expect(Vectorscope.bin(cb: 1.0 / 256, cr: 0, zoom: 2)?.x == 65)
    #expect(Vectorscope.bin(cb: 1.0 / 256 - 1e-6, cr: 0, zoom: 2)?.x == 64)
}

@Test func binEdgeIsHalfAtZoomOneAndAQuarterAtZoomTwo() {
    // ровно на краю — крайняя корзина (полностью насыщенный красный и синий лежат ровно на ±0,5)
    let corner = Vectorscope.bin(cb: 0.5, cr: 0.5, zoom: 1)
    #expect(corner?.x == 127 && corner?.y == 0)
    let opposite = Vectorscope.bin(cb: -0.5, cr: -0.5, zoom: 1)
    #expect(opposite?.x == 0 && opposite?.y == 127)
    let zoomed = Vectorscope.bin(cb: 0.25, cr: 0.25, zoom: 2)
    #expect(zoomed?.x == 127 && zoomed?.y == 0)
    // за краем — nil
    #expect(Vectorscope.bin(cb: 0.51, cr: 0, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: 0, cr: -0.51, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: 0.26, cr: 0, zoom: 2) == nil)
    #expect(Vectorscope.bin(cb: 0, cr: 0.3, zoom: 2) == nil)
    #expect(Vectorscope.bin(cb: -0.3, cr: 0, zoom: 2) == nil)
}

@Test func binEdgeAbsorbsRoundingNoiseButNotARealExcess() {
    // шум округления в последнем знаке — это всё ещё край (полностью насыщенный цвет не должен выпасть), настоящий выход — нет
    #expect(Vectorscope.bin(cb: 0.5 + 1e-12, cr: 0, zoom: 1)?.x == 127)
    #expect(Vectorscope.bin(cb: -0.5 - 1e-12, cr: 0, zoom: 1)?.x == 0)
    #expect(Vectorscope.bin(cb: 0, cr: 0.5 + 1e-12, zoom: 1)?.y == 0)
    #expect(Vectorscope.bin(cb: 0, cr: -0.5 - 1e-12, zoom: 1)?.y == 127)
    #expect(Vectorscope.bin(cb: 0.25 + 1e-13, cr: 0, zoom: 2)?.x == 127)
    #expect(Vectorscope.bin(cb: 0.5 + 1e-6, cr: 0, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: 0.25 + 1e-6, cr: 0, zoom: 2) == nil)
}

@Test func binOfNothingIsNil() {
    #expect(Vectorscope.bin(cb: .nan, cr: 0, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: 0, cr: .nan, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: .infinity, cr: 0, zoom: 1) == nil)
    #expect(Vectorscope.bin(cb: 0, cr: -.infinity, zoom: 2) == nil)
}

@Test func zoomBelowOneIsReadAsOne() {
    #expect(Vectorscope.bin(cb: 0.2, cr: -0.1, zoom: 0)?.x == Vectorscope.bin(cb: 0.2, cr: -0.1, zoom: 1)?.x)
    #expect(Vectorscope.bin(cb: 0.2, cr: -0.1, zoom: -3)?.y == Vectorscope.bin(cb: 0.2, cr: -0.1, zoom: 1)?.y)
    let red = flat(191, 0, 0)
    #expect(Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 0)
        == Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 1))
}

// MARK: - Подсчёт по кадру

@Test func solidSeventyFivePercentRedLandsAtTheRedMark() {
    let red = flat(191, 0, 0)
    let counts = Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 1)
    #expect(counts.count == 128 * 128)
    let top = fullestOrFail(counts)
    guard let mark = Vectorscope.bin(cb: Vectorscope.targets[0].cb, cr: Vectorscope.targets[0].cr, zoom: 1) else {
        Issue.record("метка R за краем")
        return
    }
    #expect(abs(top.x - mark.x) <= 1)
    #expect(abs(top.y - mark.y) <= 1)
    // весь кадр одного цвета — одна корзина на все отсчёты
    #expect(top.n == 32 * 18)
    #expect(counts.filter { $0 > 0 }.count == 1)
}

@Test func greyFrameLandsInTheCentreBin() {
    let top = fullestOrFail(Vectorscope.counts(rgba: flat(128, 128, 128), width: 64, height: 36, bytesPerRow: 256, zoom: 1))
    #expect(abs(top.x - 64) <= 1)
    #expect(abs(top.y - 64) <= 1)
}

@Test func everyGreyLevelLandsInTheSameCentreBin() {
    // от чёрного до белого и при ×1, и при ×2 — одна и та же корзина, а не две-четыре соседние
    for zoom in 1...2 {
        for level in 0...255 {
            let v = UInt8(level)
            let top = fullestOrFail(Vectorscope.counts(rgba: flat(v, v, v, width: 2, height: 2), width: 2, height: 2,
                                                       bytesPerRow: 8, zoom: zoom))
            #expect(top.x == 64)
            #expect(top.y == 64)
            #expect(top.n == 1)
        }
    }
}

@Test func zoomTwoDoublesTheDistanceOfAFiftyPercentRedFromTheCentre() {
    // красный 50 % — (127; 0; 0): Cb ≈ −0,057, Cr ≈ 0,249 — внутри края ×2 (0,25)
    let red = flat(127, 0, 0)
    let one = offset(fullestOrFail(Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 1)))
    let two = offset(fullestOrFail(Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 2)))
    #expect(abs(two.dx - 2 * one.dx) <= 1)
    #expect(abs(two.dy - 2 * one.dy) <= 1)
    // и это не центр: тест не проходит впустую
    #expect(one.dx > 5)
    #expect(one.dy > 20)
}

@Test func fullRedIsDroppedAtZoomTwoButKeptAtZoomOne() {
    // красный 100 %: Cr = 0,5 — на краю ×1 и вдвое за краем ×2
    let red = flat(255, 0, 0)
    let zoomed = Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 2)
    #expect(zoomed.count == 128 * 128)
    #expect(zoomed.reduce(0, +) == 0)
    let plain = Vectorscope.counts(rgba: red, width: 64, height: 36, bytesPerRow: 256, zoom: 1)
    #expect(plain.reduce(0, +) == 32 * 18)
    // верхний ряд плоскости: Cr на самом краю
    #expect(fullestOrFail(plain).y == 0)
}

@Test func fullySaturatedPrimariesAreNeverLostAtZoomOne() {
    // красный, синий и зелёный 100 % и все их смеси ложатся на край — а не за него
    for r in [0, 255] {
        for g in [0, 255] {
            for b in [0, 255] {
                let counts = Vectorscope.counts(rgba: flat(UInt8(r), UInt8(g), UInt8(b), width: 2, height: 2), width: 2,
                                                height: 2, bytesPerRow: 8, zoom: 1)
                #expect(counts.reduce(0, +) == 1)
            }
        }
    }
}

@Test func countsSumIsEverySecondPixelInBothAxes() {
    // 64×36 — 32 столбца и 18 строк отсчётов
    let counts = Vectorscope.counts(rgba: flat(40, 90, 200), width: 64, height: 36, bytesPerRow: 256, zoom: 1)
    #expect(counts.reduce(0, +) == 32 * 18)
    // нечётные размеры: отсчёты в 0, 2, 4… — на единицу больше половины
    let odd = Vectorscope.counts(rgba: flat(40, 90, 200, width: 5, height: 3), width: 5, height: 3, bytesPerRow: 20, zoom: 1)
    #expect(odd.reduce(0, +) == 3 * 2)
    let single = Vectorscope.counts(rgba: flat(40, 90, 200, width: 1, height: 1), width: 1, height: 1, bytesPerRow: 4, zoom: 1)
    #expect(single.reduce(0, +) == 1)
    let wide = Vectorscope.counts(rgba: flat(40, 90, 200, width: 1024, height: 576), width: 1024, height: 576,
                                  bytesPerRow: 4096, zoom: 1)
    #expect(wide.reduce(0, +) == 512 * 288)
}

@Test func countsTakeOnlyEvenColumnsAndRows() {
    // 8×8: красные — точки, где x или y нечётный; синие — где оба чётные. В счётчиках должны быть только синие (16 штук)
    var rgba: [UInt8] = []
    for y in 0..<8 {
        for x in 0..<8 {
            let blue = x % 2 == 0 && y % 2 == 0
            rgba += blue ? [0, 0, 255, 255] : [255, 0, 0, 255]
        }
    }
    let counts = Vectorscope.counts(rgba: rgba, width: 8, height: 8, bytesPerRow: 32, zoom: 1)
    #expect(counts.reduce(0, +) == 16)
    let top = fullestOrFail(counts)
    #expect(top.n == 16)
    guard let blueBin = Vectorscope.bin(cb: 0.5, cr: -0.0722 / 1.5748, zoom: 1) else {
        Issue.record("синий за краем")
        return
    }
    #expect(top.x == blueBin.x)
    #expect(top.y == blueBin.y)
}

@Test func countsSkipTheRowPadding() {
    // строка 4 точки в 20 байтах: в хвосте каждой строки — ярко-зелёный мусор, он не должен попасть в счётчики
    let width = 4, height = 4, bytesPerRow = 20
    var rgba = [UInt8](repeating: 0, count: (height - 1) * bytesPerRow + width * 4)
    for i in stride(from: 0, to: rgba.count, by: 4) { rgba[i + 1] = 255; rgba[i + 3] = 255 }
    for y in 0..<height {
        for x in 0..<width {
            let i = y * bytesPerRow + x * 4
            rgba[i] = 191; rgba[i + 1] = 0; rgba[i + 2] = 0; rgba[i + 3] = 255
        }
    }
    let counts = Vectorscope.counts(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow, zoom: 1)
    #expect(counts.reduce(0, +) == 4)
    #expect(counts.filter { $0 > 0 }.count == 1)
    guard let mark = Vectorscope.bin(cb: Vectorscope.targets[0].cb, cr: Vectorscope.targets[0].cr, zoom: 1) else {
        Issue.record("метка R за краем")
        return
    }
    let top = fullestOrFail(counts)
    #expect(abs(top.x - mark.x) <= 1)
    #expect(abs(top.y - mark.y) <= 1)
}

/// Эталон в лоб, без хитростей: каждая вторая точка по обеим осям, её цвет → Cb и Cr → корзина (общедоступными `chroma` и
/// `bin`, которые проверены отдельно).
private func naiveCounts(_ rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, zoom: Int) -> [UInt32] {
    var counts = [UInt32](repeating: 0, count: 128 * 128)
    for y in stride(from: 0, to: height, by: 2) {
        for x in stride(from: 0, to: width, by: 2) {
            let i = y * bytesPerRow + x * 4
            let c = Vectorscope.chroma(r: Double(rgba[i]) / 255, g: Double(rgba[i + 1]) / 255, b: Double(rgba[i + 2]) / 255)
            if let cell = Vectorscope.bin(cb: c.cb, cr: c.cr, zoom: zoom) { counts[cell.y * 128 + cell.x] += 1 }
        }
    }
    return counts
}

@Test func countsMatchANaiveWalkOverRandomFrames() {
    // случайные цвета на кадрах разной формы, с запасом в конце строки (мусор 0xEE) и без: шаг строки, чётные точки,
    // порядок каналов и края — всё сразу; кадр кончается ровно на своей последней точке
    var random = Lcg()
    for (w, h, padding) in [(37, 23, 12), (64, 36, 0), (1, 1, 0), (2, 3, 8), (5, 40, 4), (130, 7, 0), (16, 16, 16)] {
        let bytesPerRow = w * 4 + padding
        var rgba = [UInt8](repeating: 0xEE, count: (h - 1) * bytesPerRow + w * 4)
        for y in 0..<h {
            for x in 0..<w {
                let i = y * bytesPerRow + x * 4
                rgba[i] = UInt8(random.next() * 256)
                rgba[i + 1] = UInt8(random.next() * 256)
                rgba[i + 2] = UInt8(random.next() * 256)
                rgba[i + 3] = 255
            }
        }
        for zoom in 1...2 {
            let got = Vectorscope.counts(rgba: rgba, width: w, height: h, bytesPerRow: bytesPerRow, zoom: zoom)
            let same = got == naiveCounts(rgba, width: w, height: h, bytesPerRow: bytesPerRow, zoom: zoom)
            #expect(same, "кадр \(w)×\(h), запас \(padding), ×\(zoom)")
        }
    }
}

@Test func countsOfBrokenFramesAreEmptyAndSafe() {
    let rgba = flat(191, 0, 0, width: 4, height: 4)
    let empty = [UInt32](repeating: 0, count: 128 * 128)
    #expect(Vectorscope.counts(rgba: rgba, width: 0, height: 4, bytesPerRow: 16, zoom: 1) == empty)
    #expect(Vectorscope.counts(rgba: rgba, width: 4, height: 0, bytesPerRow: 16, zoom: 1) == empty)
    #expect(Vectorscope.counts(rgba: rgba, width: -4, height: 4, bytesPerRow: 16, zoom: 1) == empty)
    // массив короче кадра
    #expect(Vectorscope.counts(rgba: rgba, width: 4, height: 5, bytesPerRow: 16, zoom: 1) == empty)
    #expect(Vectorscope.counts(rgba: [], width: 4, height: 4, bytesPerRow: 16, zoom: 1) == empty)
    // шаг строки меньше самой строки или отрицательный
    #expect(Vectorscope.counts(rgba: rgba, width: 4, height: 4, bytesPerRow: 8, zoom: 1) == empty)
    #expect(Vectorscope.counts(rgba: rgba, width: 4, height: 4, bytesPerRow: -16, zoom: 1) == empty)
}

@Test func countsPutEveryColourWhereBinOfChromaPutsIt() {
    // горячий цикл считает теми же формулами, что и общедоступные `chroma` и `bin`: корзина совпадает для любого цвета,
    // и за краем тоже (там счётчики пусты)
    var random = Lcg()
    var colours: [(UInt8, UInt8, UInt8)] = [(255, 0, 0), (0, 255, 0), (0, 0, 255), (255, 255, 0), (0, 255, 255), (255, 0, 255)]
    for _ in 0..<300 { colours.append((UInt8(random.next() * 256), UInt8(random.next() * 256), UInt8(random.next() * 256))) }
    for (r, g, b) in colours {
        let c = Vectorscope.chroma(r: Double(r) / 255, g: Double(g) / 255, b: Double(b) / 255)
        for zoom in 1...2 {
            let counts = Vectorscope.counts(rgba: flat(r, g, b, width: 1, height: 1), width: 1, height: 1, bytesPerRow: 4,
                                            zoom: zoom)
            if let want = Vectorscope.bin(cb: c.cb, cr: c.cr, zoom: zoom) {
                #expect(counts[want.y * 128 + want.x] == 1)
                #expect(counts.reduce(0, +) == 1)
            } else {
                #expect(counts.reduce(0, +) == 0)
            }
        }
    }
}

// MARK: - Картинка

@Test func imageMakesEmptyBinsClearAndTheFullestOpaque() {
    let counts = Vectorscope.counts(rgba: flat(191, 0, 0), width: 64, height: 36, bytesPerRow: 256, zoom: 1)
    let image = Vectorscope.image(counts: counts)
    #expect(image.count == 128 * 128 * 4)
    let top = fullestOrFail(counts)
    #expect(pixel(image, x: top.x, y: top.y).a == 255)
    // у пустых — альфа 0, и цвет тоже: картинка с умноженной альфой
    var visible = 0
    for y in 0..<128 {
        for x in 0..<128 where !(x == top.x && y == top.y) {
            let p = pixel(image, x: x, y: y)
            if p.a != 0 || p.r != 0 || p.g != 0 || p.b != 0 { visible += 1 }
        }
    }
    #expect(visible == 0)
}

@Test func imageBrightnessIsLogarithmicInTheCount() {
    // яркость корзины — log(1 + n) / log(1 + max): 1 из 99 — 15 %, 9 из 99 — ровно половина, самая полная — 100 %
    let image = Vectorscope.image(counts: counts([(10, 10, 1), (20, 20, 99), (30, 30, 9)]))
    let one = pixel(image, x: 10, y: 10).a
    let nine = pixel(image, x: 30, y: 30).a
    let full = pixel(image, x: 20, y: 20).a
    #expect(abs(one - Int((255 * log(2.0) / log(100.0)).rounded())) <= 1)
    #expect(abs(nine - 128) <= 1)
    #expect(full == 255)
    #expect(one < nine && nine < full)
}

@Test func imageColourIsTheHueOfTheBinItself() {
    // у самой полной корзины яркость 1: её цвет — Y 0,6 с Cb и Cr этой корзины, обратно в RGB
    let places = [(64, 64), (100, 64), (64, 5), (10, 120), (0, 0), (127, 127), (30, 90), (110, 20)]
    for (x, y) in places {
        let p = pixel(Vectorscope.image(counts: counts([(x, y, 7)])), x: x, y: y)
        let want = hue(x: x, y: y)
        #expect(p.a == 255)
        #expect(abs(p.r - Int((want.r * 255).rounded())) <= 1)
        #expect(abs(p.g - Int((want.g * 255).rounded())) <= 1)
        #expect(abs(p.b - Int((want.b * 255).rounded())) <= 1)
    }
}

@Test func imageHuesPointTheRightWay() {
    // середина — серый; вправо по Cb синее, вверх по Cr краснее, влево и вниз — наоборот
    let centre = pixel(Vectorscope.image(counts: counts([(64, 64, 3)])), x: 64, y: 64)
    #expect(abs(centre.r - 153) <= 3 && abs(centre.g - 153) <= 3 && abs(centre.b - 153) <= 3)
    let blue = pixel(Vectorscope.image(counts: counts([(120, 64, 3)])), x: 120, y: 64)
    #expect(blue.b > blue.r + 40)
    let red = pixel(Vectorscope.image(counts: counts([(64, 8, 3)])), x: 64, y: 8)
    #expect(red.r > red.b + 40 && red.r > red.g + 40)
    // у края красный обрезан до 255, а не переполнен
    #expect(red.r == 255)
    let green = pixel(Vectorscope.image(counts: counts([(20, 100, 3)])), x: 20, y: 100)
    #expect(green.g > green.r && green.g > green.b)
}

@Test func imageKeepsTheAlphaPremultiplied() {
    // как у волновой формы: цвет уже умножен на альфу, поэтому ни один канал не больше альфы
    var random = Lcg()
    var filled: [(x: Int, y: Int, n: UInt32)] = []
    for _ in 0..<400 { filled.append((Int(random.next() * 128), Int(random.next() * 128), UInt32(random.next() * 5000) + 1)) }
    let image = Vectorscope.image(counts: counts(filled))
    var transparent = 0, bright = 0
    for y in 0..<128 {
        for x in 0..<128 {
            let p = pixel(image, x: x, y: y)
            #expect(p.r <= p.a && p.g <= p.a && p.b <= p.a)
            if p.a == 0 { transparent += 1 }
            if p.a == 255 { bright += 1 }
        }
    }
    #expect(transparent > 128 * 128 - 400 - 1)
    #expect(bright >= 1)
}

@Test func imageOfEmptyOrWrongSizedCountsIsClear() {
    #expect(Vectorscope.image(counts: [UInt32](repeating: 0, count: 128 * 128)) == [UInt8](repeating: 0, count: 128 * 128 * 4))
    #expect(Vectorscope.image(counts: []) == [UInt8](repeating: 0, count: 128 * 128 * 4))
    #expect(Vectorscope.image(counts: [UInt32](repeating: 5, count: 10)) == [UInt8](repeating: 0, count: 128 * 128 * 4))
}

// MARK: - Пик

@Test func peakOfSolidSeventyFivePercentRedIsTheRedMark() {
    let counts = Vectorscope.counts(rgba: flat(191, 0, 0), width: 64, height: 36, bytesPerRow: 256, zoom: 1)
    guard let peak = Vectorscope.peak(counts: counts, zoom: 1) else {
        Issue.record("пика нет")
        return
    }
    #expect(near(peak.cb, Vectorscope.targets[0].cb, 0.01))
    #expect(near(peak.cr, Vectorscope.targets[0].cr, 0.01))
}

@Test func peakOfEmptyCountsIsNil() {
    #expect(Vectorscope.peak(counts: [UInt32](repeating: 0, count: 128 * 128), zoom: 1) == nil)
    #expect(Vectorscope.peak(counts: [UInt32](repeating: 0, count: 128 * 128), zoom: 2) == nil)
    #expect(Vectorscope.peak(counts: [], zoom: 1) == nil)
    // счётчики не того размера — не вектороскоп
    #expect(Vectorscope.peak(counts: [UInt32](repeating: 4, count: 100), zoom: 1) == nil)
}

@Test func peakIsTheCentreOfTheFullestBin() {
    // корзина (10; 20) полнее всех: середина — на пол-корзины внутрь, Cr вверх от y
    let filled = counts([(10, 20, 9), (100, 30, 8), (64, 64, 2)])
    guard let one = Vectorscope.peak(counts: filled, zoom: 1), let two = Vectorscope.peak(counts: filled, zoom: 2) else {
        Issue.record("пика нет")
        return
    }
    #expect(near(one.cb, (10.5 / 128 - 0.5), 1e-12))
    #expect(near(one.cr, (0.5 - 20.5 / 128), 1e-12))
    // при ×2 та же корзина — вдвое ближе к середине
    #expect(near(two.cb, one.cb / 2, 1e-12))
    #expect(near(two.cr, one.cr / 2, 1e-12))
}

@Test func peakOfEqualBinsIsTheFirstFromTheTopLeft() {
    let filled = counts([(90, 10, 5), (20, 10, 5), (20, 80, 5)])
    guard let peak = Vectorscope.peak(counts: filled, zoom: 1) else {
        Issue.record("пика нет")
        return
    }
    #expect(near(peak.cb, 20.5 / 128 - 0.5, 1e-12))
    #expect(near(peak.cr, 0.5 - 10.5 / 128, 1e-12))
}

@Test func peakFollowsTheZoomAndLandsOnTheColour() {
    // блёклый красный (160; 120; 120) лежит внутри края ×2; середина корзины — не дальше её половины от цвета
    let muted = flat(160, 120, 120)
    let c = Vectorscope.chroma(r: 160.0 / 255, g: 120.0 / 255, b: 120.0 / 255)
    for zoom in 1...2 {
        let counts = Vectorscope.counts(rgba: muted, width: 64, height: 36, bytesPerRow: 256, zoom: zoom)
        guard let peak = Vectorscope.peak(counts: counts, zoom: zoom) else {
            Issue.record("пика нет при ×\(zoom)")
            continue
        }
        let half = 0.5 / Double(Vectorscope.bins) / Double(zoom)
        #expect(near(peak.cb, c.cb, half + 1e-9))
        #expect(near(peak.cr, c.cr, half + 1e-9))
    }
}

// MARK: - Сетка панели

@Test func planePutsGreyInTheMiddleOfThePanelAndCrUp() {
    let middle = Vectorscope.plane(cb: 0, cr: 0, zoom: 1)
    #expect(near(Double(middle.x), 0.5, 1e-12) && near(Double(middle.y), 0.5, 1e-12))
    // Cb — вправо, Cr — вверх (у экрана y растёт вниз)
    let right = Vectorscope.plane(cb: 0.25, cr: 0, zoom: 1)
    #expect(near(Double(right.x), 0.75, 1e-12) && near(Double(right.y), 0.5, 1e-12))
    let up = Vectorscope.plane(cb: 0, cr: 0.25, zoom: 1)
    #expect(near(Double(up.x), 0.5, 1e-12) && near(Double(up.y), 0.25, 1e-12))
    // край плоскости: ×1 — 0,5, ×2 — 0,25
    #expect(near(Double(Vectorscope.plane(cb: 0.5, cr: 0, zoom: 1).x), 1, 1e-12))
    #expect(near(Double(Vectorscope.plane(cb: 0.25, cr: 0, zoom: 2).x), 1, 1e-12))
    #expect(near(Double(Vectorscope.plane(cb: 0, cr: -0.25, zoom: 2).y), 1, 1e-12))
}

@Test func planeAgreesWithTheBinsThePointsFallInto() {
    // метка на панели и корзина с таким цветом — одно и то же место, при любом масштабе
    let n = Double(Vectorscope.bins)
    for zoom in [1, 2] {
        for target in Vectorscope.targets {
            guard let bin = Vectorscope.bin(cb: target.cb, cr: target.cr, zoom: zoom) else { continue }
            let point = Vectorscope.plane(cb: target.cb, cr: target.cr, zoom: zoom)
            #expect(Int(Double(point.x) * n) == bin.x && Int(Double(point.y) * n) == bin.y, "\(target.name) ×\(zoom)")
        }
    }
    // при ×1 все шесть меток внутри панели, при ×2 — ни одной (75 % полосы лежат дальше 0,25)
    let inside: (CGPoint) -> Bool = { (0...1).contains(Double($0.x)) && (0...1).contains(Double($0.y)) }
    #expect(Vectorscope.targets.allSatisfy { inside(Vectorscope.plane(cb: $0.cb, cr: $0.cr, zoom: 1)) })
    #expect(Vectorscope.targets.allSatisfy { !inside(Vectorscope.plane(cb: $0.cb, cr: $0.cr, zoom: 2)) })
}

@Test func targetMarksSitWhereTheColoursAre() {
    // красный выше всех, голубой ниже всех; синий правее всех, жёлтый левее всех
    let points = Dictionary(uniqueKeysWithValues: Vectorscope.targets.map {
        ($0.name, Vectorscope.plane(cb: $0.cb, cr: $0.cr, zoom: 1))
    })
    #expect(points["R"]!.y < 0.2 && points["Cy"]!.y > 0.8)
    #expect(points["B"]!.x > 0.8 && points["Yl"]!.x < 0.2)
    // красный чуть левее середины (у него Cb < 0), пурпурный правее (Cb > 0), оба над серединой
    #expect(points["R"]!.x < 0.5 && points["Mg"]!.x > 0.5)
    #expect(points["R"]!.y < 0.5 && points["Mg"]!.y < 0.5)
}

@Test func skinLineRunsFromTheCentreAt123DegreesCounterClockwise() {
    let end = Vectorscope.skinLineEnd
    // конец — на круге панели (радиус 0,5 от середины)
    let dx = Double(end.x) - 0.5, dy = Double(end.y) - 0.5
    #expect(near((dx * dx + dy * dy).squareRoot(), 0.5, 1e-12))
    // угол от +Cb против часовой стрелки: y экрана — вниз, поэтому «вверх» — это минус dy
    var degrees = atan2(-dy, dx) * 180 / .pi
    if degrees < 0 { degrees += 360 }
    #expect(near(degrees, 123, 1e-9))
    #expect(end.x < 0.5 && end.y < 0.5)
    #expect(Vectorscope.skinAngle == 123)
}

// MARK: - Цена

/// Сколько стоит вектороскоп на кадре 1024×576: ZV_BENCH=1 swift test -c release -Xswiftc -enable-testing --filter vectorscopeBenchmark
@Test(.enabled(if: ProcessInfo.processInfo.environment["ZV_BENCH"] != nil))
func vectorscopeBenchmark() {
    let w = 1024, h = 576
    // цвет по всей плоскости: шум по трём каналам, чтобы отсчёты разошлись по корзинам
    var random = Lcg()
    var rgba = [UInt8](repeating: 255, count: w * h * 4)
    for i in stride(from: 0, to: rgba.count, by: 4) {
        rgba[i] = UInt8(random.next() * 256)
        rgba[i + 1] = UInt8(random.next() * 256)
        rgba[i + 2] = UInt8(random.next() * 256)
    }
    func time(_ name: String, _ n: Int = 50, _ body: () -> Void) {
        let start = Date()
        for _ in 0..<n { body() }
        print(String(format: "%@: %.3f мс", name, Date().timeIntervalSince(start) * 1000 / Double(n)))
    }
    var counts: [UInt32] = []
    time("counts ×1") { counts = Vectorscope.counts(rgba: rgba, width: w, height: h, bytesPerRow: w * 4, zoom: 1) }
    time("counts ×2") { _ = Vectorscope.counts(rgba: rgba, width: w, height: h, bytesPerRow: w * 4, zoom: 2) }
    time("image") { _ = Vectorscope.image(counts: counts) }
    time("peak") { _ = Vectorscope.peak(counts: counts, zoom: 1) }
    time("probe") { _ = ColorProbe.read(rgba: rgba, width: w, height: h, bytesPerRow: w * 4, at: CGPoint(x: 512, y: 288), white: 255) }
}
