import Foundation
import Testing
@testable import ZVCore

// MARK: - Помощники

private func near(_ a: Double, _ b: Double, _ eps: Double) -> Bool { abs(a - b) <= eps }

private func near(_ a: [Double], _ b: [Double], _ eps: Double) -> Bool {
    a.count == b.count && zip(a, b).allSatisfy { abs($0 - $1) <= eps }
}

private func near(_ a: (Float, Float, Float), _ b: (Float, Float, Float), _ eps: Float) -> Bool {
    abs(a.0 - b.0) <= eps && abs(a.1 - b.1) <= eps && abs(a.2 - b.2) <= eps
}

/// Наибольшее расхождение по трём каналам.
private func distance(_ a: (Float, Float, Float), _ b: (Float, Float, Float)) -> Float {
    let dr = abs(a.0 - b.0), dg = abs(a.1 - b.1), db = abs(a.2 - b.2)
    return max(dr, max(dg, db))
}

private func distance(_ a: (Float, Float, Float), _ b: (Double, Double, Double)) -> Double {
    let dr = abs(Double(a.0) - b.0), dg = abs(Double(a.1) - b.1), db = abs(Double(a.2) - b.2)
    return max(dr, max(dg, db))
}

/// Случайные числа без сюрпризов — «seed 1», как в `AssistBenchmark`: те же точки в каждом прогоне и на каждой машине.
private struct LUTRandom {
    private var seed: UInt32 = 1

    /// 0 ≤ x < 1.
    mutating func next() -> Double {
        seed = seed &* 1_664_525 &+ 1_013_904_223
        return Double(seed >> 8) / 16_777_216
    }
}

private let rec709Primaries: [(x: Double, y: Double)] = [(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)]

// MARK: - Кривые Sony и HLG

@Test func slog3MapsGreyBlackAndNinetyPercent() {
    // 18 % серого — код 420, чёрный — код 95, 90 % отражения — код 598
    #expect(near(SonyCurves.slog3ToLinear(420.0 / 1023), 0.18, 0.005))
    #expect(near(SonyCurves.slog3ToLinear(95.0 / 1023), 0, 0.0005))
    #expect(near(SonyCurves.slog3ToLinear(598.0 / 1023), 0.90, 0.01))
}

@Test func slog2MapsGrey() {
    #expect(near(SonyCurves.slog2ToLinear(347.0 / 1023), 0.18, 0.01))
}

@Test func hlgMapsDiffuseWhiteToOne() {
    // 75 % сигнала HLG — диффузный белый
    #expect(near(SonyCurves.hlgToLinear(721.0 / 1023), 1.0, 0.01))
}

@Test func hlgBelowLegalBlackIsBlack() {
    // E′ < 0 в формуле возводится в квадрат — без обрезки «ниже чёрного» дало бы свет, а не темноту
    for code in [0.0, 16, 32, 63, 64] {
        #expect(near(SonyCurves.hlgToLinear(code / 1023), 0, 1e-12))
    }
}

@Test func curvesAreContinuousAtTheirJoints() {
    let eps = 1e-9
    // S-Log3: линейный участок переходит в степенной на коде 171,2102946929
    let slog3 = 171.2102946929 / 1023
    #expect(near(SonyCurves.slog3ToLinear(slog3 - eps), SonyCurves.slog3ToLinear(slog3 + eps), 1e-6))
    // S-Log2: стык на y = 0,030001222851889303
    let slog2 = (0.030001222851889303 * 876 + 64) / 1023
    #expect(near(SonyCurves.slog2ToLinear(slog2 - eps), SonyCurves.slog2ToLinear(slog2 + eps), 1e-6))
    // HLG: стык на E′ = 0,5, это код 502
    let hlg = 502.0 / 1023
    #expect(near(SonyCurves.hlgToLinear(hlg - eps), SonyCurves.hlgToLinear(hlg + eps), 1e-6))
}

@Test func curvesNeverGoDownWithTheCode() {
    let curves: [(String, (Double) -> Double)] = [
        ("S-Log3", SonyCurves.slog3ToLinear), ("S-Log2", SonyCurves.slog2ToLinear), ("HLG", SonyCurves.hlgToLinear),
    ]
    for (name, curve) in curves {
        var previous = curve(0)
        var goesDown = false
        for code in 1...1023 {
            let value = curve(Double(code) / 1023)
            if value < previous { goesDown = true }
            previous = value
        }
        #expect(!goesDown, "\(name) не должна убывать")
    }
}

// MARK: - Мягкое плечо и кодирование BT.709

@Test func shoulderKeepsEverythingUpToTheKneeAndSoftensAbove() {
    #expect(SonyCurves.shoulder(0.5) == 0.5)
    #expect(SonyCurves.shoulder(0.6) == 0.6)
    #expect(near(SonyCurves.shoulder(0.9), 0.811, 0.005))
    // ниже нуля света нет
    #expect(SonyCurves.shoulder(0) == 0)
    #expect(SonyCurves.shoulder(-3) == 0)
}

@Test func shoulderIsContinuousAndMonotonicOnZeroToForty() {
    let step = 0.001
    var previous = SonyCurves.shoulder(0)
    var goesDown = false
    var jumps = false
    var x = step
    while x <= 40 {
        let y = SonyCurves.shoulder(x)
        if y < previous { goesDown = true }
        // наклон не больше 1, так что за шаг кривая прибавляет не больше шага: скачка нет
        if y - previous > step + 1e-12 { jumps = true }
        previous = y
        x += step
    }
    #expect(!goesDown)
    #expect(!jumps)
}

@Test func shoulderNeverReachesPastOne() {
    #expect(SonyCurves.shoulder(40) <= 1)
    #expect(SonyCurves.shoulder(5) > 0.99)
}

@Test func bt709EncodesGreyAndClipsToZeroOne() {
    #expect(near(SonyCurves.bt709(0.18), 0.409, 0.005))
    // линейный участок у чёрного: 4,5·0,01
    #expect(near(SonyCurves.bt709(0.01), 0.045, 1e-12))
    #expect(near(SonyCurves.bt709(1), 1, 1e-12))
    #expect(SonyCurves.bt709(0) == 0)
    #expect(SonyCurves.bt709(-0.5) == 0)
    #expect(SonyCurves.bt709(3) == 1)
}

// MARK: - Матрицы охватов

@Test func gamutMatricesMatchTheSpecFirstRows() {
    let cine = SonyCurves.matrixTo709(primaries: SonyCurves.sGamut3Cine)
    let sGamut3 = SonyCurves.matrixTo709(primaries: SonyCurves.sGamut3)
    let bt2020 = SonyCurves.matrixTo709(primaries: SonyCurves.bt2020)
    #expect(near(cine[0], [1.626947, -0.540139, -0.086809], 1e-5))
    #expect(near(sGamut3[0], [1.877915, -0.794169, -0.083746], 1e-5))
    #expect(near(bt2020[0], [1.660491, -0.587641, -0.072850], 1e-5))
}

@Test func gamutMatricesKeepGreyGrey() {
    // сумма строки — 1: (1; 1; 1) переходит в (1; 1; 1)
    for primaries in [SonyCurves.sGamut3Cine, SonyCurves.sGamut3, SonyCurves.bt2020] {
        let matrix = SonyCurves.matrixTo709(primaries: primaries)
        #expect(matrix.count == 3)
        for row in matrix {
            #expect(row.count == 3)
            #expect(near(row.reduce(0, +), 1, 1e-9))
        }
    }
}

@Test func matrixFromRec709ToRec709IsIdentity() {
    // проверка самой алгебры, без чисел Sony
    let matrix = SonyCurves.matrixTo709(primaries: rec709Primaries)
    for (i, row) in matrix.enumerated() {
        for (j, value) in row.enumerated() {
            #expect(near(value, i == j ? 1 : 0, 1e-9))
        }
    }
}

@Test func primariesAreRedGreenBlue() {
    #expect(SonyCurves.sGamut3Cine.count == 3)
    #expect(SonyCurves.sGamut3.count == 3)
    #expect(SonyCurves.bt2020.count == 3)
    // красный правее зелёного и синего, зелёный выше всех
    for primaries in [SonyCurves.sGamut3Cine, SonyCurves.sGamut3, SonyCurves.bt2020] {
        #expect(primaries[0].x > primaries[1].x && primaries[0].x > primaries[2].x)
        #expect(primaries[1].y > primaries[0].y && primaries[1].y > primaries[2].y)
    }
}

// MARK: - Встроенные LUT: названия

@Test func builtinLUTsKeepTheirOrderRawValuesAndLabels() {
    #expect(BuiltinLUT.allCases == [.slog3cine, .slog3, .slog2, .hlg])
    #expect(BuiltinLUT.allCases.map(\.rawValue) == ["slog3cine", "slog3", "slog2", "hlg"])
    #expect(BuiltinLUT.allCases.map(\.label) == ["S-Log3 Cine", "S-Log3", "S-Log2", "HLG"])
}

@Test func builtinLUTsAreTheMonitorTileOptions() {
    // те же значения и подписи, что в плитке «LUT»
    let options = MonitorTool.lut.options
    for lut in BuiltinLUT.allCases {
        #expect(MonitorAssist.LUTMode(rawValue: lut.rawValue) != nil)
        #expect(options.first { $0.value == lut.rawValue }?.label == lut.label)
    }
}

// MARK: - Встроенные LUT: контрольные точки спецификации

private func grey(_ lut: BuiltinLUT, code: Double) -> (Double, Double, Double) {
    let v = code / 1023
    return lut.transform(v, v, v)
}

@Test func builtinSLog3ControlPoints() {
    for lut in [BuiltinLUT.slog3cine, .slog3] {
        // код 420 — 0,409 на всех каналах (104 из 255)
        let mid = grey(lut, code: 420)
        #expect(near(mid.0, 0.409, 0.005))
        #expect(near(mid.1, 0.409, 0.005))
        #expect(near(mid.2, 0.409, 0.005))
        // код 95 — чёрный
        let black = grey(lut, code: 95)
        #expect(near(black.0, 0, 1e-9) && near(black.1, 0, 1e-9) && near(black.2, 0, 1e-9))
        // 90 % отражения — 0,90
        let light = grey(lut, code: 598)
        #expect(near(light.0, 0.90, 0.01) && near(light.1, 0.90, 0.01) && near(light.2, 0.90, 0.01))
    }
}

@Test func builtinSLog2AndHLGControlPoints() {
    let slog2 = grey(.slog2, code: 347)
    #expect(near(slog2.0, 0.409, 0.01) && near(slog2.1, 0.409, 0.01) && near(slog2.2, 0.409, 0.01))
    // код 721 (E′ = 0,75) — 0,924
    let hlg = grey(.hlg, code: 721)
    #expect(near(hlg.0, 0.924, 0.01) && near(hlg.1, 0.924, 0.01) && near(hlg.2, 0.924, 0.01))
}

/// Цвет на входе и то, что должно выйти.
private struct ColourCase {
    let lut: BuiltinLUT
    let input: (Double, Double, Double)
    let expected: (Double, Double, Double)
}

@Test func builtinColoursMatchAnIndependentCalculation() {
    // Не серые цвета: на них видны порядок каналов, ориентация матрицы и обрезка. Числа посчитаны отдельной программой
    // (numpy) по формулам спецификации, а не этим кодом; они же годятся для проверки Kotlin.
    let cases = [
        ColourCase(lut: .slog3cine, input: (0.55, 0.45, 0.40), expected: (0.904786107, 0.449988142, 0.326711064)),
        ColourCase(lut: .slog3, input: (0.55, 0.45, 0.40), expected: (0.926613357, 0.443314084, 0.347649793)),
        ColourCase(lut: .slog2, input: (0.55, 0.45, 0.40), expected: (0.948135884, 0.592248229, 0.492662053)),
        ColourCase(lut: .hlg, input: (0.70, 0.60, 0.55), expected: (0.961372557, 0.703826603, 0.627341558)),
        // после матрицы зелёный уходит под ноль и обрезается
        ColourCase(lut: .slog3cine, input: (0.60, 0.30, 0.35), expected: (0.985393493, 0, 0.225136934)),
        ColourCase(lut: .slog3, input: (0.60, 0.30, 0.35), expected: (0.992117385, 0, 0.256647822)),
        ColourCase(lut: .slog2, input: (0.60, 0.30, 0.35), expected: (0.993188767, 0, 0.410526726)),
        ColourCase(lut: .hlg, input: (0.80, 0.40, 0.45), expected: (0.999255595, 0, 0.475195176)),
    ]
    for c in cases {
        let out = c.lut.transform(c.input.0, c.input.1, c.input.2)
        let worst = max(abs(out.0 - c.expected.0), max(abs(out.1 - c.expected.1), abs(out.2 - c.expected.2)))
        #expect(worst < 1e-6, "\(c.lut) \(c.input)")
    }
}

@Test(arguments: BuiltinLUT.allCases)
func builtinBlackStaysBlackAndWhiteStaysWhite(_ lut: BuiltinLUT) {
    let black = lut.transform(0, 0, 0)
    #expect(black.0 == 0 && black.1 == 0 && black.2 == 0)
    // код 1023 — далеко за диффузным белым: плечо прижимает к 1
    let white = lut.transform(1, 1, 1)
    #expect(near(white.0, 1, 1e-5) && near(white.1, 1, 1e-5) && near(white.2, 1, 1e-5))
}

@Test(arguments: BuiltinLUT.allCases)
func builtinKeepsGreyGreyAndGoesUpWithTheCode(_ lut: BuiltinLUT) {
    var previous = -1.0
    var goesDown = false
    var colouredGrey = false
    for code in stride(from: 0, through: 1023, by: 3) {
        let out = grey(lut, code: Double(code))
        // сумма строки матрицы — 1, поэтому серый на входе — серый на выходе
        if abs(out.0 - out.1) > 1e-9 || abs(out.1 - out.2) > 1e-9 { colouredGrey = true }
        if out.0 < previous - 1e-12 { goesDown = true }
        previous = out.0
    }
    #expect(!colouredGrey)
    #expect(!goesDown)
}

@Test(arguments: BuiltinLUT.allCases)
func builtinOutputStaysInsideZeroOne(_ lut: BuiltinLUT) {
    // насыщенные цвета выходят за охват 709: каналы в минусе обрезаются, а не уходят под ноль
    var random = LUTRandom()
    var outside = false
    for _ in 0..<2000 {
        let out = lut.transform(random.next(), random.next(), random.next())
        if out.0 < 0 || out.0 > 1 || out.1 < 0 || out.1 > 1 || out.2 < 0 || out.2 > 1 { outside = true }
    }
    #expect(!outside)
}

@Test func builtinGamutsTurnAPrimaryDifferently() {
    // одни и те же коды S-Log3 в разных охватах — разные цвета; серый при этом общий
    let cine = BuiltinLUT.slog3cine.transform(0.55, 0.4, 0.4)
    let sGamut3 = BuiltinLUT.slog3.transform(0.55, 0.4, 0.4)
    #expect(abs(cine.0 - sGamut3.0) > 0.01)
}

// MARK: - Куб 33³

@Test func identityCubeReturnsItsInputExactly() {
    let cube = ColorLUT.identity(size: 2)
    let out = cube.apply(0.3, 0.6, 0.9)
    #expect(out == (0.3, 0.6, 0.9))
}

@Test func identityCubeHasTheRightShape() {
    let cube = ColorLUT.identity(size: 5)
    #expect(cube.size == 5)
    #expect(cube.values.count == 5 * 5 * 5 * 3)
    #expect(cube.domainMin == [0, 0, 0])
    #expect(cube.domainMax == [1, 1, 1])
    #expect(cube == ColorLUT.identity(size: 5))
    #expect(cube != ColorLUT.identity(size: 6))
}

@Test func identityCubeOfAnySizeFollowsItsInput() {
    var random = LUTRandom()
    for size in [2, 3, 17, 33, 65] {
        let cube = ColorLUT.identity(size: size)
        var worst: Float = 0
        for _ in 0..<200 {
            let r = Float(random.next()), g = Float(random.next()), b = Float(random.next())
            worst = max(worst, distance(cube.apply(r, g, b), (r, g, b)))
        }
        #expect(worst < 1e-6, "размер \(size)")
    }
}

@Test func cubeStoresRedFirstThenGreenThenBlue() {
    let cube = ColorLUT.identity(size: 2)
    // индекс = (синий·2 + зелёный)·2 + красный; в тождественном кубе углы — это сами индексы
    #expect(Array(cube.values[0..<3]) == [0, 0, 0])
    #expect(Array(cube.values[3..<6]) == [1, 0, 0])
    #expect(Array(cube.values[6..<9]) == [0, 1, 0])
    #expect(Array(cube.values[12..<15]) == [0, 0, 1])
    #expect(Array(cube.values[21..<24]) == [1, 1, 1])
}

@Test func cubeInterpolatesAnAffineMapExactlyInEveryDirection() {
    // трилинейность точна на плоских функциях: проверяет веса по осям, порядок осей и домен
    let size = 5
    func affine(_ r: Float, _ g: Float, _ b: Float) -> (Float, Float, Float) {
        (0.05 + 0.1 * r + 0.3 * g + 0.5 * b, 0.9 - 0.2 * r + 0.05 * g - 0.4 * b, 0.1 + 0.6 * r - 0.1 * g + 0.2 * b)
    }
    var values: [Float] = []
    for bi in 0..<size {
        for gi in 0..<size {
            for ri in 0..<size {
                let out = affine(Float(ri) / 4, Float(gi) / 4, Float(bi) / 4)
                values += [out.0, out.1, out.2]
            }
        }
    }
    let domainMin: [Float] = [-1, 0.2, 0], domainMax: [Float] = [3, 0.8, 0.5]
    let cube = ColorLUT(size: size, values: values, domainMin: domainMin, domainMax: domainMax)
    var random = LUTRandom()
    var worst: Float = 0
    for _ in 0..<500 {
        // и за пределами домена: вход обрезается по нему
        let r = Float(random.next()) * 4.4 - 1.2, g = Float(random.next()) * 0.9 + 0.1, b = Float(random.next()) * 0.7 - 0.1
        func unit(_ v: Float, _ i: Int) -> Float { min(max((v - domainMin[i]) / (domainMax[i] - domainMin[i]), 0), 1) }
        let expected = affine(unit(r, 0), unit(g, 1), unit(b, 2))
        worst = max(worst, distance(cube.apply(r, g, b), expected))
    }
    #expect(worst < 1e-5)
}

@Test func cubeClampsInputAndSurvivesNotANumber() {
    let cube = ColorLUT.identity(size: 33)
    #expect(cube.apply(-1, 2, 0.5) == (0, 1, 0.5))
    // NaN и бесконечность не роняют и не дают «мусор»
    let odd = cube.apply(.nan, .infinity, -.infinity)
    #expect(odd == (0, 1, 0))
}

@Test func cubeSurvivesADegenerateDomain() {
    // домен без ширины (красный) или перевёрнутый (зелёный) — файл плохой, но деления на ноль и «мусора» быть не должно:
    // такой канал стоит в начале домена, а нормальный (синий) считается как всегда
    let values = ColorLUT.identity(size: 3).values
    let cube = ColorLUT(size: 3, values: values, domainMin: [1, 1, 1], domainMax: [1, 0, 2])
    #expect(cube.apply(0.5, 0.5, 1.5) == (0, 0, 0.5))
    #expect(cube.apply(7, 7, 7) == (0, 0, 1))
}

@Test func cubeClampsOutputToZeroOne() {
    // .cube бывает с выходом чуть за 0…1: на экране выход — 0…1
    var values = ColorLUT.identity(size: 2).values
    values[0] = -0.2
    values[23] = 1.4
    let cube = ColorLUT(size: 2, values: values)
    #expect(cube.apply(0, 0, 0) == (0, 0, 0))
    #expect(cube.apply(1, 1, 1) == (1, 1, 1))
    // в данные для Core Image тоже уходит 0…1
    let floats = cube.rgbaFloat32().withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    #expect(floats.count == 32)
    #expect(floats[0] == 0)
    #expect(floats[7 * 4 + 2] == 1)
}

@Test func rgbaDataHasSixteenBytesPerCell() {
    let cube = BuiltinLUT.slog3.make(size: 33)
    #expect(cube.size == 33)
    #expect(cube.rgbaFloat32().count == 33 * 33 * 33 * 16)
    #expect(ColorLUT.identity(size: 2).rgbaFloat32().count == 8 * 16)
}

@Test func rgbaDataIsRedFastestWithAlphaOne() {
    // порядок CIColorCube: красный быстрее всех, потом зелёный, потом синий; альфа 1
    let cube = ColorLUT.identity(size: 2)
    let data = cube.rgbaFloat32()
    let floats = data.withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    #expect(floats.count == 32)
    var cells: [[Float]] = []
    for cell in 0..<8 { cells.append(Array(floats[(cell * 4)..<(cell * 4 + 4)])) }
    #expect(cells[0] == [0, 0, 0, 1])
    #expect(cells[1] == [1, 0, 0, 1])
    #expect(cells[2] == [0, 1, 0, 1])
    #expect(cells[3] == [1, 1, 0, 1])
    #expect(cells[4] == [0, 0, 1, 1])
    #expect(cells[7] == [1, 1, 1, 1])
}

@Test func rgbaDataCarriesTheCubeValues() {
    let cube = BuiltinLUT.hlg.make(size: 9)
    let floats = cube.rgbaFloat32().withUnsafeBytes { Array($0.bindMemory(to: Float.self)) }
    var different = false
    for cell in 0..<(9 * 9 * 9) {
        for k in 0..<3 where floats[cell * 4 + k] != cube.values[cell * 3 + k] { different = true }
        if floats[cell * 4 + 3] != 1 { different = true }
    }
    #expect(!different)
}

@Test(arguments: BuiltinLUT.allCases)
func makeStoresTheExactPathAtEveryNode(_ lut: BuiltinLUT) {
    // в узлах куб равен точному пути (до округления до Float); попутно проверяется порядок осей
    let size = 33
    let cube = lut.make(size: size)
    var random = LUTRandom()
    var worst: Float = 0
    for _ in 0..<1000 {
        let ri = Int(random.next() * Double(size)), gi = Int(random.next() * Double(size)), bi = Int(random.next() * Double(size))
        let r = Double(ri) / Double(size - 1), g = Double(gi) / Double(size - 1), b = Double(bi) / Double(size - 1)
        let exact = lut.transform(r, g, b)
        let got = cube.apply(Float(r), Float(g), Float(b))
        worst = max(worst, Float(distance(got, exact)))
    }
    #expect(worst < 1e-5)
}

@Test(arguments: BuiltinLUT.allCases)
func makeFollowsTheExactPathAlongTheGreyAxis(_ lut: BuiltinLUT) {
    // 1000 случайных серых между узлами. Допуск 0,03, а не 0,01: куб 33³ — это трилинейная выборка очень крутой
    // кривой (степень ×1,32 на ячейку у S-Log3), а на сером в трилинейность входят и узлы вне оси, где мешает
    // обрезка каналов матрицей; худшая ошибка на оси — 0,025 (S-Log3 у 90 % отражения)
    let cube = lut.make(size: 33)
    var random = LUTRandom()
    var worst = 0.0
    for _ in 0..<1000 {
        let v = random.next()
        let exact = lut.transform(v, v, v)
        let got = cube.apply(Float(v), Float(v), Float(v))
        worst = max(worst, distance(got, exact))
    }
    #expect(worst < 0.03)
}

@Test func makeSmallestCubeHoldsTheCornersOnly() {
    let lut = BuiltinLUT.slog3
    let cube = lut.make(size: 2)
    #expect(cube.size == 2)
    #expect(cube.values.count == 8 * 3)
    let corner = lut.transform(1, 0, 1)
    #expect(near(cube.apply(1, 0, 1), (Float(corner.0), Float(corner.1), Float(corner.2)), 1e-6))
}

@Test func makeDefaultsToThirtyThreeAndNeverGoesBelowTwo() {
    #expect(BuiltinLUT.hlg.make().size == 33)
    #expect(BuiltinLUT.hlg.make(size: 65).size == 65)
    #expect(BuiltinLUT.hlg.make(size: 1).size == 2)
    #expect(BuiltinLUT.hlg.make(size: -5).size == 2)
}

// MARK: - «Авто»

@Test func autoByPictureProfileNumberWithoutGamma() {
    // ZV-E10 не отдаёт 0xD0E1: профиль 0xD23F с заводскими гаммами Sony
    #expect(LUTChoice.resolve(.auto, pictureProfile: 8, ppGamma: nil, colorMode: nil) == .slog3cine)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 9, ppGamma: nil, colorMode: nil) == .slog3)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 7, ppGamma: nil, colorMode: nil) == .slog2)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 10, ppGamma: nil, colorMode: nil) == .hlg)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 1, ppGamma: nil, colorMode: nil) == nil)
}

@Test func autoIgnoresOtherProfilesAndMissingProfile() {
    for profile in [0, 2, 3, 4, 5, 6, 11, 12, 255, -1] as [Int64] {
        #expect(LUTChoice.resolve(.auto, pictureProfile: profile, ppGamma: nil, colorMode: nil) == nil, "PP \(profile)")
    }
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: nil, colorMode: nil) == nil)
}

@Test func autoByGammaAndColourMode() {
    // камера сама говорит гамму (0xD0E1) и охват (0xD0E9)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0009) == .slog3)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0008) == .slog3cine)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0302, colorMode: nil) == .slog2)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0403, colorMode: nil) == .hlg)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0201, colorMode: nil) == nil)
}

@Test func autoSLog3WithoutAKnownGamutFallsBackToCine() {
    // «иначе S-Gamut3.Cine»: охват не пришёл или не из двух S-Gamut3
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: nil) == .slog3cine)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0003) == .slog3cine)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x000C) == .slog3cine)
}

@Test func autoGammaOtherThanLogIsNoLUT() {
    // обычные гаммы, Cine, S-Cinetone, ITU709 — своего LUT не просят
    for gamma in [0x0001, 0x0002, 0x0003, 0x0101, 0x0102, 0x0103, 0x0104, 0x0201, 0x0202] as [Int64] {
        #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: gamma, colorMode: 0x0009) == nil, "гамма \(gamma)")
    }
}

@Test func autoTakesEveryHLGVariant() {
    for gamma in [0x0401, 0x0402, 0x0403, 0x0404] as [Int64] {
        #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: gamma, colorMode: nil) == .hlg, "гамма \(gamma)")
    }
}

@Test func autoGammaBeatsPictureProfileNumber() {
    // гамму камера называет сама — номер профиля тогда не нужен, даже если он говорит другое
    #expect(LUTChoice.resolve(.auto, pictureProfile: 8, ppGamma: 0x0201, colorMode: nil) == nil)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 1, ppGamma: 0x0302, colorMode: nil) == .slog2)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 10, ppGamma: 0x0303, colorMode: 0x0009) == .slog3)
}

@Test func explicitModesIgnoreTheProfile() {
    let profiles: [(pp: Int64?, gamma: Int64?, colour: Int64?)] = [
        (nil, nil, nil), (8, nil, nil), (10, nil, nil), (1, 0x0201, 0x0009), (9, 0x0401, 0x0008),
    ]
    for p in profiles {
        #expect(LUTChoice.resolve(.slog3cine, pictureProfile: p.pp, ppGamma: p.gamma, colorMode: p.colour) == .slog3cine)
        #expect(LUTChoice.resolve(.slog3, pictureProfile: p.pp, ppGamma: p.gamma, colorMode: p.colour) == .slog3)
        #expect(LUTChoice.resolve(.slog2, pictureProfile: p.pp, ppGamma: p.gamma, colorMode: p.colour) == .slog2)
        #expect(LUTChoice.resolve(.hlg, pictureProfile: p.pp, ppGamma: p.gamma, colorMode: p.colour) == .hlg)
    }
}

@Test func offAndFileResolveToNoBuiltin() {
    // свой файл — не встроенный LUT; выключено — нет ничего, какой бы ни был профиль
    #expect(LUTChoice.resolve(.file, pictureProfile: 8, ppGamma: nil, colorMode: nil) == nil)
    #expect(LUTChoice.resolve(.file, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0009) == nil)
    #expect(LUTChoice.resolve(.off, pictureProfile: 8, ppGamma: nil, colorMode: nil) == nil)
    #expect(LUTChoice.resolve(.off, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0009) == nil)
}

@Test func autoLabelNamesTheResolvedLUT() {
    #expect(LUTChoice.autoLabel(.slog3) == "Авто: S-Log3")
    #expect(LUTChoice.autoLabel(.slog3cine) == "Авто: S-Log3 Cine")
    #expect(LUTChoice.autoLabel(.slog2) == "Авто: S-Log2")
    #expect(LUTChoice.autoLabel(.hlg) == "Авто: HLG")
    #expect(LUTChoice.autoLabel(nil) == "Авто: нет")
}

@Test func cameraDoesNotConvertThePreviewUntilTheDeviceCheck() {
    // пока камерой не проверено, превью считается логарифмическим и «Авто» включает LUT
    #expect(LUTChoice.cameraConverts == false)
}

@Test func autoSwitchesOffWhenTheCameraConvertsByItself() {
    // true из проверки на камере: «Авто» ничего не включает, а выбор рукой остаётся
    #expect(LUTChoice.resolve(.auto, pictureProfile: 8, ppGamma: nil, colorMode: nil, cameraConverts: true) == nil)
    #expect(LUTChoice.resolve(.auto, pictureProfile: nil, ppGamma: 0x0303, colorMode: 0x0009, cameraConverts: true) == nil)
    #expect(LUTChoice.resolve(.hlg, pictureProfile: 8, ppGamma: nil, colorMode: nil, cameraConverts: true) == .hlg)
    #expect(LUTChoice.resolve(.auto, pictureProfile: 8, ppGamma: nil, colorMode: nil, cameraConverts: false) == .slog3cine)
}
