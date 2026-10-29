import Foundation
import Testing
@testable import ZVCore

// Уровни превью камеры и вход LUT: значение канала превью `v = Y′/255` → код камеры/1023 = `a + b·v`. Числа — из проверки
// на ZV-E10 (scripts/check_log_preview.py, docs/protocol.md, «Превью в S-Log и HLG»).

private func near(_ a: Double, _ b: Double, _ eps: Double) -> Bool { abs(a - b) <= eps }

/// Значение превью (0…255) для кода камеры.
private func previewLevel(code: Double) -> Double { PreviewLevels.preview(code / 1023) * 255 }

/// Код камеры для значения превью (0…255).
private func cameraCode(level: Double) -> Double { PreviewLevels.code(level / 255) * 1023 }

/// S-Log3: отражение → код 0…1023 (обратное к `SonyCurves.slog3ToLinear`).
private func slog3Code(_ l: Double) -> Double {
    l >= 0.01125 ? 420 + 261.5 * log10((l + 0.01) / 0.19) : 95 + l * (171.2102946929 - 95) / 0.01125
}

@Test func previewStretchesCodesSixtyFourToTenNineteen() {
    #expect(near(PreviewLevels.offset, 64.0 / 1023, 1e-12))
    #expect(near(PreviewLevels.gain, 955.0 / 1023, 1e-12))
    #expect(near(PreviewLevels.code(0) * 1023, 64, 1e-9))
    #expect(near(PreviewLevels.code(1) * 1023, 1019, 1e-9))
    for v in stride(from: 0.0, through: 1.0, by: 0.125) {
        #expect(near(PreviewLevels.preview(PreviewLevels.code(v)), v, 1e-12))
    }
}

@Test func knownCameraLevelsLandWhereThePreviewShowsThem() {
    // белое видео (код 940, 100 %) — 234: так превью видео и выглядит; чёрное (код 64) — 0, белое фото (код 1019) — 255
    #expect(near(previewLevel(code: 940), 234, 0.5))
    #expect(near(previewLevel(code: 64), 0, 1e-9))
    #expect(near(previewLevel(code: 1019), 255, 1e-9))
    // S-Log3: чёрное (код 95) — 8, а не 24; 18 % серого (код 420) — 95, а не 105; S-Log2: чёрное (код 90) — 7
    #expect(near(previewLevel(code: 95), 8.3, 0.1))
    #expect(near(previewLevel(code: 420), 95.1, 0.1))
    #expect(near(previewLevel(code: 90.28), 7.0, 0.1))
}

/// S-Log2 и S-Log3 одной сцены при той же экспозиции, ZV-E10: медианы блоков 8×8 по корзинам S-Log2 (значение превью S-Log2
/// → значение превью S-Log3 у тех же блоков). Видео 1024×576 и фото 1024×1024, комната с торшером, ISO 640, 1/50, f/3.5.
private let measuredSLog2ToSLog3: [(mode: String, slog2: Double, slog3: Double)] = [
    ("видео", 11.0, 16.5), ("видео", 15.0, 25.8), ("видео", 22.3, 38.5), ("видео", 33.9, 54.7), ("видео", 55.1, 77.4),
    ("видео", 84.6, 101.8), ("видео", 121.3, 129.5), ("видео", 163.9, 160.6),
    ("фото", 11.2, 16.7), ("фото", 15.0, 25.0), ("фото", 22.2, 38.5), ("фото", 34.6, 55.8), ("фото", 53.9, 76.3),
    ("фото", 84.5, 102.5), ("фото", 126.4, 134.4), ("фото", 163.7, 160.9),
]

@Test func sLog2AndSLog3OfTheSameSceneAgreeOnlyWithThePreviewStretch() {
    // через растяжение превью S-Log2 и S-Log3 дают одну и ту же сцену: S-Log3, предсказанный по S-Log2, — в пределах 1,5 уровня
    for pair in measuredSLog2ToSLog3 {
        let light = SonyCurves.slog2ToLinear(PreviewLevels.code(pair.slog2 / 255))
        let predicted = previewLevel(code: slog3Code(light))
        #expect(near(predicted, pair.slog3, 1.5), "\(pair.mode) \(pair.slog2) → \(predicted), измерено \(pair.slog3)")
    }
    // прежнее предположение «v = код/1023» в тенях промахивается на 5 уровней и больше: оно и давало чёрную картинку с LUT
    let shadows = measuredSLog2ToSLog3.filter { $0.slog2 < 30 }
    for pair in shadows {
        let light = SonyCurves.slog2ToLinear(pair.slog2 / 255)
        let predicted = slog3Code(light) / 1023 * 255
        #expect(abs(predicted - pair.slog3) >= 5, "\(pair.mode) \(pair.slog2): прежнее предположение дало бы \(predicted)")
    }
}

@Test func forPreviewKeepsTheNumbersAndMovesOnlyTheDomain() {
    let cube = ColorLUT.identity(size: 5)
    let adapted = PreviewLevels.forPreview(cube)
    #expect(adapted.size == cube.size)
    let sameStorage = cube.values.withUnsafeBufferPointer { a in adapted.values.withUnsafeBufferPointer { b in a.baseAddress == b.baseAddress } }
    #expect(sameStorage, "числа куба — тот же массив, а не копия")
    for channel in 0..<3 {
        #expect(near(Double(adapted.domainMin[channel]), -64.0 / 955, 1e-6))
        #expect(near(Double(adapted.domainMax[channel]), 959.0 / 955, 1e-6))
    }
}

@Test func identityCubeForThePreviewShowsTheCameraCode() {
    // тождественный куб видит код камеры/1023: превью 0 — 64/1023, 1 — 1019/1023
    let adapted = PreviewLevels.forPreview(.identity(size: 2))
    for v in [0.0, 0.25, 0.5, 0.75, 1.0] {
        let out = adapted.apply(Float(v), Float(v), Float(v))
        #expect(near(Double(out.0), PreviewLevels.code(v), 1e-5) && near(Double(out.1), PreviewLevels.code(v), 1e-5)
                && near(Double(out.2), PreviewLevels.code(v), 1e-5), "\(v) → \(out)")
    }
}

@Test func ownCubeDomainIsKeptInCameraCodes() {
    // свой .cube с доменом 0,1…0,9 по коду камеры: вход превью v попадает в него как a + b·v
    let cube = ColorLUT(size: 2, values: ColorLUT.identity(size: 2).values, domainMin: [0.1, 0.1, 0.1], domainMax: [0.9, 0.9, 0.9])
    let adapted = PreviewLevels.forPreview(cube)
    for v in [0.0, 0.2, 0.5, 0.8, 1.0] {
        let want = cube.apply(Float(PreviewLevels.code(v)), Float(PreviewLevels.code(v)), Float(PreviewLevels.code(v)))
        let got = adapted.apply(Float(v), Float(v), Float(v))
        #expect(abs(got.0 - want.0) <= 1e-5 && abs(got.1 - want.1) <= 1e-5 && abs(got.2 - want.2) <= 1e-5, "\(v): \(got), ждали \(want)")
    }
}

@Test func builtinLUTsTurnPreviewLevelsIntoTheControlPoints() {
    // контрольные точки спецификации — теперь со стороны превью: значение превью → куб для превью → экран
    func out(_ lut: BuiltinLUT, code: Double) -> Double {
        let v = Float(PreviewLevels.preview(code / 1023))
        return Double(PreviewLevels.forPreview(lut.cube).apply(v, v, v).1)
    }
    for lut in [BuiltinLUT.slog3cine, .slog3] {
        #expect(near(out(lut, code: 420), 0.409, 0.01), "\(lut): серый")
        #expect(near(out(lut, code: 95), 0, 0.01), "\(lut): чёрное")
        #expect(near(out(lut, code: 598), 0.90, 0.015), "\(lut): 90 %")
    }
    #expect(near(out(.slog2, code: 347), 0.409, 0.015))
    #expect(near(out(.hlg, code: 721), 0.924, 0.015))
}
