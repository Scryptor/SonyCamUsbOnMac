import CoreGraphics
import Foundation
import ImageIO
import Testing
@testable import ZVCore

// MARK: - Помощники

/// Картинка width×height в заданном цветовом пространстве: `colour(x, y)` — цвет точки (строка 0 — верхняя).
private func makeImage(width: Int, height: Int, space: CGColorSpace = CGColorSpace(name: CGColorSpace.sRGB)!,
                       _ colour: (Int, Int) -> (UInt8, UInt8, UInt8)) -> CGImage {
    var rgba = [UInt8](repeating: 255, count: width * height * 4)
    for y in 0..<height {
        for x in 0..<width {
            let (r, g, b) = colour(x, y)
            let i = (y * width + x) * 4
            rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b
        }
    }
    let image: CGImage? = rgba.withUnsafeMutableBytes { buffer in
        CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4, space: space,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)?.makeImage()
    }
    return image!
}

/// Точки картинки подряд (RGBA, по 4 байта, строка 0 — верхняя), как они лежат в её собственном цветовом пространстве:
/// ничего не пересчитывается.
private func readPixels(_ image: CGImage) -> [UInt8] {
    let width = image.width, height = image.height
    var rgba = [UInt8](repeating: 0, count: width * height * 4)
    rgba.withUnsafeMutableBytes { buffer in
        let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8, bytesPerRow: width * 4,
                                space: image.colorSpace ?? CGColorSpaceCreateDeviceRGB(),
                                bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: width, height: height))
    }
    return rgba
}

/// Три канала точки (x, y).
private func pixel(_ rgba: [UInt8], _ x: Int, _ y: Int, width: Int) -> [Int] {
    let i = (y * width + x) * 4
    return [Int(rgba[i]), Int(rgba[i + 1]), Int(rgba[i + 2])]
}

/// Наибольшее расхождение по каналам двух наборов точек; наборы разного размера — `Int.max`. В `#expect` это одно число, а не
/// два массива на тысячи значений.
private func worstDifference(_ a: [UInt8], _ b: [UInt8]) -> Int {
    guard a.count == b.count else { return Int.max }
    var worst = 0
    for i in stride(from: 0, to: a.count, by: 4) {
        for k in 0..<3 { worst = max(worst, abs(Int(a[i + k]) - Int(b[i + k]))) }
    }
    return worst
}

/// Каждая точка своего цвета: по номеру точки, чтобы любое смещение было видно.
private func uniqueImage(width: Int, height: Int, space: CGColorSpace = CGColorSpace(name: CGColorSpace.sRGB)!) -> CGImage {
    makeImage(width: width, height: height, space: space) { x, y in
        (UInt8(truncatingIfNeeded: 20 + x * 31), UInt8(truncatingIfNeeded: 40 + y * 73), UInt8(truncatingIfNeeded: 200 - x * 17 - y * 5))
    }
}

/// Плавный «кадр» для сравнения с точным путём: все цвета понемногу, без резких границ.
private func gradientImage(width: Int, height: Int) -> CGImage {
    makeImage(width: width, height: height) { x, y in
        (UInt8(x * 255 / max(width - 1, 1)), UInt8(y * 255 / max(height - 1, 1)), UInt8((x + y) * 255 / max(width + height - 2, 1)))
    }
}

private let allOrientations: [FrameOrientation] = [.off, .mirror, .rotate180, .mirror180]

/// Куб, который меняет каналы местами и выворачивает один, — на нём видно любую путаницу осей: (r, g, b) → (1 − b, r, g).
private func scrambledCube(size: Int, domainMin: [Float] = [0, 0, 0], domainMax: [Float] = [1, 1, 1]) -> ColorLUT {
    var values: [Float] = []
    let last = Float(size - 1)
    for b in 0..<size {
        for g in 0..<size {
            for r in 0..<size {
                values += [1 - Float(b) / last, Float(r) / last, Float(g) / last]
            }
        }
    }
    return ColorLUT(size: size, values: values, domainMin: domainMin, domainMax: domainMax)
}

/// Что должна дать точка, если считать точным путём: канал v/255 → куб → ближайший уровень 0…255.
private func expected(_ lut: ColorLUT, _ rgb: [Int]) -> [Int] {
    let out = lut.apply(Float(rgb[0]) / 255, Float(rgb[1]) / 255, Float(rgb[2]) / 255)
    return [Int((out.0 * 255).rounded()), Int((out.1 * 255).rounded()), Int((out.2 * 255).rounded())]
}

// MARK: - Короткий путь

@Test func nothingToDoReturnsTheSameImage() {
    let image = uniqueImage(width: 7, height: 3)
    let result = PictureTransform().apply(image, orientation: .off, lut: nil, needPlain: true)
    #expect(result.display === image)
    #expect(result.plain == nil)
}

// MARK: - Поворот

@Test func orientationMovesAKnownPixelToTheRightPlace() {
    // 7×3: ни квадрат, ни 16:9 — перепутанные строки и столбцы сразу видны
    let width = 7, height = 3
    let image = uniqueImage(width: width, height: height)
    let source = readPixels(image)
    let transform = PictureTransform()
    for orientation in allOrientations {
        let result = transform.apply(image, orientation: orientation, lut: nil, needPlain: false)
        #expect(result.display.width == width && result.display.height == height)
        #expect(result.plain == nil)
        let out = readPixels(result.display)
        for y in 0..<height {
            for x in 0..<width {
                let fromX = orientation.flipsX ? width - 1 - x : x
                let fromY = orientation.flipsY ? height - 1 - y : y
                #expect(pixel(out, x, y, width: width) == pixel(source, fromX, fromY, width: width),
                        "\(orientation) (\(x), \(y)) ← (\(fromX), \(fromY))")
            }
        }
    }
}

@Test func mirrorPutsTheTopLeftPixelTopRightAndUpsideDownPutsItBottomRight() {
    // то же словами: красный угол вверху слева
    let width = 7, height = 3
    let image = makeImage(width: width, height: height) { x, y in x == 0 && y == 0 ? (255, 0, 0) : (10, 10, 10) }
    let transform = PictureTransform()
    let marks: [(FrameOrientation, Int, Int)] = [(.off, 0, 0), (.mirror, 6, 0), (.rotate180, 6, 2), (.mirror180, 0, 2)]
    for (orientation, x, y) in marks {
        let out = readPixels(transform.apply(image, orientation: orientation, lut: nil, needPlain: false).display)
        #expect(pixel(out, x, y, width: width) == [255, 0, 0], "\(orientation)")
        var reds = 0
        for i in stride(from: 0, to: out.count, by: 4) where out[i] == 255 { reds += 1 }
        #expect(reds == 1, "\(orientation): красная точка одна")
    }
}

// MARK: - LUT

@Test func identityCubeAndNoRotationKeepEveryPixel() {
    let image = uniqueImage(width: 33, height: 17)
    let source = readPixels(image)
    // 2³: две точки на ось — линейная интерполяция «в лоб», значения проходят как есть
    let exact = PictureTransform().apply(image, orientation: .off, lut: .identity(size: 2), needPlain: false)
    #expect(worstDifference(readPixels(exact.display), source) == 0, "тождественный куб 2³")
    // 33³: вес интерполяции в видеокарте не точен до последнего бита — не больше одного уровня из 255
    let wide = PictureTransform().apply(image, orientation: .off, lut: .identity(size: 33), needPlain: false)
    #expect(worstDifference(readPixels(wide.display), source) <= 1, "тождественный куб 33³")
}

@Test func identityCubeKeepsAllTwoHundredFiftySixLevelsOfEveryChannel() {
    // все уровни каждого канала: полоса 256×3, в каждой строке меняется один канал
    let image = makeImage(width: 256, height: 3) { x, y in
        (y == 0 ? UInt8(x) : 90, y == 1 ? UInt8(x) : 90, y == 2 ? UInt8(x) : 90)
    }
    let result = PictureTransform().apply(image, orientation: .off, lut: .identity(size: 2), needPlain: false)
    #expect(worstDifference(readPixels(result.display), readPixels(image)) == 0)
}

@Test func cubeIsReadWithRedFastestThenGreenThenBlue() {
    // (r, g, b) → (1 − b, r, g): любая перестановка осей даёт другой цвет
    let lut = scrambledCube(size: 5)
    let image = makeImage(width: 5, height: 5) { x, y in (UInt8(x * 255 / 4), UInt8(y * 255 / 4), 128) }
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    let source = readPixels(image)
    for y in 0..<5 {
        for x in 0..<5 {
            let want = expected(lut, pixel(source, x, y, width: 5))
            let got = pixel(out, x, y, width: 5)
            #expect(zip(want, got).allSatisfy { abs($0 - $1) <= 1 }, "(\(x), \(y)): \(got), ждали \(want)")
        }
    }
}

@Test func builtinCubeIsCloseToTheExactPath() {
    // S-Log3 Cine: куб 33³ и точный путь расходятся не больше чем на пару уровней
    let lut = BuiltinLUT.slog3cine.make(size: 33)
    let image = gradientImage(width: 128, height: 64)
    let source = readPixels(image)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    var worst = 0
    for y in 0..<64 {
        for x in 0..<128 {
            let want = expected(lut, pixel(source, x, y, width: 128))
            let got = pixel(out, x, y, width: 128)
            worst = max(worst, zip(want, got).map { abs($0 - $1) }.max()!)
        }
    }
    #expect(worst <= 2, "наибольшее расхождение \(worst) уровней")
}

@Test func builtinCubeForThePreviewReadsTheCameraCode() {
    // превью ZV-E10 в S-Log3: чёрное (код 95) — 8, 18 % серого (код 420) — 95, 90 % отражения (код 598) — 143 из 255.
    // Куб для превью (домен в значениях превью) делает из них экран: 0, 104 (0,409) и 230 (0,90)
    let levels: [UInt8] = [8, 95, 143]
    let image = makeImage(width: 3, height: 1) { x, _ in (levels[x], levels[x], levels[x]) }
    let lut = PreviewLevels.forPreview(BuiltinLUT.slog3cine.cube)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    for (x, want) in [0, 104, 230].enumerated() {
        let got = pixel(out, x, 0, width: 3)
        #expect(got.allSatisfy { abs($0 - want) <= 3 }, "уровень \(levels[x]): \(got), ждали \(want)")
        // и Core Image даёт то же, что точный путь куба с доменом превью
        #expect(zip(expected(lut, [Int(levels[x]), Int(levels[x]), Int(levels[x])]), got).allSatisfy { abs($0 - $1) <= 1 })
    }
}

@Test func identityCubeForThePreviewLiftsBlackToTheCameraCode() {
    // свой куб получает тот же вход, что встроенные: тождественный показывает код камеры/1023 — 0 → 16, 255 → 254
    let image = makeImage(width: 256, height: 1) { x, _ in (UInt8(x), UInt8(x), UInt8(x)) }
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: PreviewLevels.forPreview(.identity(size: 2)),
                                                  needPlain: false).display)
    var worst = 0
    for x in 0..<256 {
        let want = Int((PreviewLevels.code(Double(x) / 255) * 255).rounded())
        worst = max(worst, pixel(out, x, 0, width: 256).map { abs($0 - want) }.max()!)
    }
    #expect(worst <= 1, "наибольшее расхождение \(worst)")
    #expect(pixel(out, 0, 0, width: 256) == [16, 16, 16] && pixel(out, 255, 0, width: 256) == [254, 254, 254])
}

@Test func domainOfTheCubeScalesTheInputBeforeIt() {
    // домен уже 0…1 и разный по каналам: вход приводится к нему, что за ним — обрезается
    let lut = scrambledCube(size: 9, domainMin: [0.25, 0.1, 0.0], domainMax: [0.75, 0.9, 0.5])
    let image = makeImage(width: 16, height: 16) { x, y in (UInt8(x * 17), UInt8(y * 17), UInt8((x + y) * 8)) }
    let source = readPixels(image)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    for y in 0..<16 {
        for x in 0..<16 {
            let want = expected(lut, pixel(source, x, y, width: 16))
            let got = pixel(out, x, y, width: 16)
            #expect(zip(want, got).allSatisfy { abs($0 - $1) <= 1 }, "(\(x), \(y)): \(got), ждали \(want)")
        }
    }
}

@Test func degenerateDomainChannelMapsToTheStartOfTheCube() {
    // у зелёного канала домен нулевой ширины: как в ColorLUT.apply, он идёт в начало куба при любом входе
    let lut = scrambledCube(size: 5, domainMin: [0, 0.5, 0], domainMax: [1, 0.5, 1])
    let image = makeImage(width: 8, height: 8) { x, y in (UInt8(x * 36), UInt8(y * 36), UInt8(x * 20)) }
    let source = readPixels(image)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    for y in 0..<8 {
        for x in 0..<8 {
            let want = expected(lut, pixel(source, x, y, width: 8))
            let got = pixel(out, x, y, width: 8)
            #expect(zip(want, got).allSatisfy { abs($0 - $1) <= 1 }, "(\(x), \(y)): \(got), ждали \(want)")
        }
    }
}

/// Гладкий куб без изломов: S-образный контраст и немного смешения каналов — как обычный цветовой грейд.
private func gradeCube(size: Int) -> ColorLUT {
    func curve(_ x: Float) -> Float { x * x * (3 - 2 * x) * 0.6 + x * 0.4 }
    var values: [Float] = []
    let last = Float(size - 1)
    for b in 0..<size {
        for g in 0..<size {
            for r in 0..<size {
                let (fr, fg, fb) = (Float(r) / last, Float(g) / last, Float(b) / last)
                values += [curve(0.88 * fr + 0.08 * fg + 0.04 * fb), curve(0.05 * fr + 0.9 * fg + 0.05 * fb),
                           curve(0.03 * fr + 0.07 * fg + 0.9 * fb) * 0.95 + 0.02]
            }
        }
    }
    return ColorLUT(size: size, values: values)
}

@Test func cubeBiggerThanSixtyFourIsAcceptedAndStaysClose() {
    // .cube бывает до 65³, а Core Image берёт не больше 64: куб пересчитывается один раз, картинка остаётся той же
    let lut = gradeCube(size: 65)
    #expect(lut.size == 65)
    let image = gradientImage(width: 96, height: 48)
    let source = readPixels(image)
    let transform = PictureTransform()
    let result = transform.apply(image, orientation: .off, lut: lut, needPlain: false)
    #expect(result.display !== image)
    let out = readPixels(result.display)
    var worst = 0
    for y in 0..<48 {
        for x in 0..<96 {
            let want = expected(lut, pixel(source, x, y, width: 96))
            worst = max(worst, zip(want, pixel(out, x, y, width: 96)).map { abs($0 - $1) }.max()!)
        }
    }
    #expect(worst <= 2, "наибольшее расхождение \(worst) уровней")
    // тот же куб ещё раз — тот же результат
    #expect(worstDifference(readPixels(transform.apply(image, orientation: .off, lut: lut, needPlain: false).display), out) == 0)
}

@Test func realLogCubeOfSixtyFiveStaysCloseOnAverage() {
    // у S-Log3 есть изломы (цвета за охватом обрезаются в 0): между узлами 65³ и 64³ там расхождение больше, но в среднем —
    // доли уровня
    let lut = BuiltinLUT.slog3.make(size: 65)
    let image = gradientImage(width: 96, height: 48)
    let source = readPixels(image)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    var total = 0, count = 0
    for y in 0..<48 {
        for x in 0..<96 {
            let want = expected(lut, pixel(source, x, y, width: 96))
            for (a, b) in zip(want, pixel(out, x, y, width: 96)) {
                total += abs(a - b)
                count += 1
            }
        }
    }
    #expect(Double(total) / Double(count) < 1, "в среднем \(Double(total) / Double(count)) уровня")
}

@Test func cubeHandedToCoreImageNeverExceedsSixtyFour() {
    // что именно уходит в Core Image — проверка не зависит от того, берёт ли эта система куб побольше
    let small = gradeCube(size: 33)
    #expect(PictureTransform.coreImageCube(for: small) == small)
    let edge = gradeCube(size: 64)
    #expect(PictureTransform.coreImageCube(for: edge) == edge)
    let big = gradeCube(size: 65)
    let resampled = PictureTransform.coreImageCube(for: big)
    #expect(resampled.size == 64)
    #expect(resampled.values.count == 64 * 64 * 64 * 3)
    #expect(resampled.domainMin == [0, 0, 0] && resampled.domainMax == [1, 1, 1], "домен — не в кубе, а перед ним")
    // по всему входу новый куб даёт то же, что старый, с точностью до долей уровня
    var worst: Float = 0
    for i in 0...64 {
        for (r, g, b) in [(Float(i) / 64, Float(i) / 64, Float(i) / 64), (Float(i) / 64, 0.3, 0.8), (0.1, Float(i) / 64, 0.5), (0.9, 0.2, Float(i) / 64)] {
            let a = big.apply(r, g, b), c = resampled.apply(r, g, b)
            worst = max(worst, abs(a.0 - c.0), abs(a.1 - c.1), abs(a.2 - c.2))
        }
    }
    #expect(worst * 255 < 1, "наибольшее расхождение \(worst * 255) уровня")
    // куб с доменом: домен тот же, что был (о нём заботится приведение входа), значения — те же узлы
    let wide = ColorLUT(size: 65, values: big.values, domainMin: [0.1, 0.2, 0.3], domainMax: [0.9, 0.8, 0.7])
    let narrowed = PictureTransform.coreImageCube(for: wide)
    #expect(narrowed.values == resampled.values)
}

@Test func cubeOfSixtyFourIsNotResampled() {
    // 64 точки Core Image берёт как есть
    let lut = ColorLUT.identity(size: 64)
    let image = uniqueImage(width: 20, height: 10)
    let out = readPixels(PictureTransform().apply(image, orientation: .off, lut: lut, needPlain: false).display)
    #expect(worstDifference(out, readPixels(image)) <= 1)
}

// MARK: - Две картинки: что видно и сигнал

@Test func plainPictureIsOrientedWithoutTheCube() {
    let width = 7, height = 3
    let image = uniqueImage(width: width, height: height)
    let source = readPixels(image)
    let lut = scrambledCube(size: 9)
    let transform = PictureTransform()
    for orientation in allOrientations {
        let result = transform.apply(image, orientation: orientation, lut: lut, needPlain: true)
        guard let plain = result.plain else {
            Issue.record("\(orientation): без LUT-картинки нет")
            continue
        }
        let shown = readPixels(result.display)
        let signal = readPixels(plain)
        for y in 0..<height {
            for x in 0..<width {
                let fromX = orientation.flipsX ? width - 1 - x : x
                let fromY = orientation.flipsY ? height - 1 - y : y
                let camera = pixel(source, fromX, fromY, width: width)
                #expect(pixel(signal, x, y, width: width) == camera, "\(orientation): сигнал в (\(x), \(y))")
                let want = expected(lut, camera)
                #expect(zip(want, pixel(shown, x, y, width: width)).allSatisfy { abs($0 - $1) <= 1 },
                        "\(orientation): картинка в (\(x), \(y))")
            }
        }
    }
}

@Test func plainPictureWithoutRotationIsTheSourceItself() {
    // поворота нет — сигнал и есть кадр камеры: второго рендера не нужно
    let image = uniqueImage(width: 9, height: 4)
    let result = PictureTransform().apply(image, orientation: .off, lut: scrambledCube(size: 5), needPlain: true)
    #expect(result.plain === image)
    #expect(result.display !== image)
}

@Test func plainPictureIsNotMadeWhenNobodyAsks() {
    let image = uniqueImage(width: 9, height: 4)
    let transform = PictureTransform()
    #expect(transform.apply(image, orientation: .mirror, lut: scrambledCube(size: 5), needPlain: false).plain == nil)
    // LUT нет — повёрнутая картинка и есть сигнал: отдельной не бывает
    #expect(transform.apply(image, orientation: .mirror, lut: nil, needPlain: true).plain == nil)
}

// MARK: - Кадр за кадром

@Test func changingTheCubeBetweenFramesChangesTheResult() {
    let image = gradientImage(width: 32, height: 16)
    let source = readPixels(image)
    let a = BuiltinLUT.slog3cine.make(size: 17)
    let b = BuiltinLUT.hlg.make(size: 17)
    let transform = PictureTransform()
    let first = readPixels(transform.apply(image, orientation: .off, lut: a, needPlain: false).display)
    let second = readPixels(transform.apply(image, orientation: .off, lut: b, needPlain: false).display)
    let third = readPixels(transform.apply(image, orientation: .off, lut: a, needPlain: false).display)
    #expect(worstDifference(first, second) > 8)
    #expect(worstDifference(first, third) == 0)
    for (name, lut, out) in [("slog3cine", a, first), ("hlg", b, second)] {
        for y in stride(from: 0, to: 16, by: 5) {
            for x in stride(from: 0, to: 32, by: 5) {
                let want = expected(lut, pixel(source, x, y, width: 32))
                #expect(zip(want, pixel(out, x, y, width: 32)).allSatisfy { abs($0 - $1) <= 2 }, "\(name) (\(x), \(y))")
            }
        }
    }
    // LUT сняли — кадр как есть
    let off = transform.apply(image, orientation: .off, lut: nil, needPlain: false)
    #expect(off.display === image)
}

@Test func frameSizeMayChangeBetweenFrames() {
    // 16:9, потом 1:1, потом 640×360 по Wi-Fi, потом 3:2 — одним объектом и одним кубом
    let lut = BuiltinLUT.slog2.make(size: 17)
    let transform = PictureTransform()
    for (width, height) in [(64, 36), (40, 40), (64, 36), (60, 40), (1, 1), (3, 1)] {
        for orientation in allOrientations {
            let image = uniqueImage(width: width, height: height)
            let source = readPixels(image)
            let result = transform.apply(image, orientation: orientation, lut: lut, needPlain: true)
            #expect(result.display.width == width && result.display.height == height, "\(width)×\(height) \(orientation)")
            guard let plain = result.plain ?? (orientation == .off ? image : nil) else {
                Issue.record("\(width)×\(height) \(orientation): сигнала нет")
                continue
            }
            let signal = readPixels(plain)
            let shown = readPixels(result.display)
            for (x, y) in [(0, 0), (width - 1, 0), (0, height - 1), (width - 1, height - 1), (width / 2, height / 2)] {
                let fromX = orientation.flipsX ? width - 1 - x : x
                let fromY = orientation.flipsY ? height - 1 - y : y
                let camera = pixel(source, fromX, fromY, width: width)
                #expect(pixel(signal, x, y, width: width) == camera, "\(width)×\(height) \(orientation) сигнал (\(x), \(y))")
                #expect(zip(expected(lut, camera), pixel(shown, x, y, width: width)).allSatisfy { abs($0 - $1) <= 2 },
                        "\(width)×\(height) \(orientation) картинка (\(x), \(y))")
            }
        }
    }
}

// MARK: - Цвет

@Test func outputKeepsTheColourSpaceOfTheSource() {
    let transform = PictureTransform()
    let lut = scrambledCube(size: 5)
    for name in [CGColorSpace.sRGB, CGColorSpace.displayP3, CGColorSpace.adobeRGB1998] {
        let space = CGColorSpace(name: name)!
        let image = uniqueImage(width: 6, height: 4, space: space)
        for (orientation, cube) in [(FrameOrientation.mirror, nil), (.off, lut), (.rotate180, lut)] as [(FrameOrientation, ColorLUT?)] {
            let result = transform.apply(image, orientation: orientation, lut: cube, needPlain: true)
            #expect(result.display.colorSpace?.name == space.name, "\(name): \(orientation) \(cube == nil ? "без" : "с") LUT")
            if let plain = result.plain { #expect(plain.colorSpace?.name == space.name) }
        }
    }
}

@Test func valuesPassThroughWithoutColourConversion() {
    // в Display P3 значения те же, что в sRGB: канал не пересчитывается между пространствами
    let transform = PictureTransform()
    let p3 = uniqueImage(width: 12, height: 5, space: CGColorSpace(name: CGColorSpace.displayP3)!)
    let source = readPixels(p3)
    #expect(worstDifference(readPixels(transform.apply(p3, orientation: .off, lut: .identity(size: 2), needPlain: false).display), source) == 0)
    let mirrored = readPixels(transform.apply(p3, orientation: .mirror, lut: nil, needPlain: false).display)
    #expect(pixel(mirrored, 0, 0, width: 12) == pixel(source, 11, 0, width: 12))
}

@Test func frameDecodedFromJpegIsNotDecodedAgainWithAnotherSmoothing() throws {
    // кадр камеры — JPEG: ImageIO помнит его, и Core Image разжал бы тот же JPEG сам, по-другому сглаживая цвет (на краях цвета
    // разница в десятки уровней). Картинка с преобразованием не должна отличаться от той же без него
    let stripes = makeImage(width: 96, height: 64) { x, y in ((x / 3 + y / 5) % 2 == 0) ? (230, 20, 40) : (20, 60, 220) }
    let data = NSMutableData()
    let destination = try #require(CGImageDestinationCreateWithData(data, "public.jpeg" as CFString, 1, nil))
    CGImageDestinationAddImage(destination, stripes, [kCGImageDestinationLossyCompressionQuality: 0.7] as CFDictionary)
    #expect(CGImageDestinationFinalize(destination))
    let source = try #require(CGImageSourceCreateWithData(data, nil))
    let decoded = try #require(CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary))
    let expected = readPixels(decoded)
    let transform = PictureTransform()
    let same = transform.apply(decoded, orientation: .off, lut: .identity(size: 2), needPlain: false).display
    #expect(worstDifference(readPixels(same), expected) == 0)
    // и поворот — ровно те же точки, только на новых местах
    let mirrored = readPixels(transform.apply(decoded, orientation: .mirror, lut: nil, needPlain: false).display)
    var worst = 0
    for y in 0..<64 {
        for x in 0..<96 {
            let a = pixel(mirrored, x, y, width: 96), b = pixel(expected, 95 - x, y, width: 96)
            worst = max(worst, zip(a, b).map { abs($0 - $1) }.max()!)
        }
    }
    #expect(worst == 0, "наибольшее расхождение \(worst) уровней")
}

@Test func greyImageStillComesOutAsPicture() {
    // кадр камеры серым не бывает, но файл `--picture` — может: не терять кадр
    let grey = CGColorSpaceCreateDeviceGray()
    var bytes = [UInt8](repeating: 0, count: 8 * 4)
    for i in 0..<bytes.count { bytes[i] = UInt8(i * 8) }
    let image: CGImage = bytes.withUnsafeMutableBytes { buffer in
        CGContext(data: buffer.baseAddress, width: 8, height: 4, bitsPerComponent: 8, bytesPerRow: 8, space: grey,
                  bitmapInfo: CGImageAlphaInfo.none.rawValue)!.makeImage()!
    }
    let result = PictureTransform().apply(image, orientation: .mirror, lut: .identity(size: 2), needPlain: true)
    #expect(result.display.width == 8 && result.display.height == 4)
}

// MARK: - Потоки

@Test func severalThreadsMayCallApplyAtOnce() async {
    // кадровый цикл один, но объект общий: одновременные вызовы не ломают ни друг друга, ни кеш кубов
    let transform = PictureTransform()
    let image = uniqueImage(width: 64, height: 36)
    let cubes = [BuiltinLUT.slog3cine.make(size: 17), BuiltinLUT.hlg.make(size: 17), ColorLUT.identity(size: 2)]
    let source = readPixels(image)
    await withTaskGroup(of: Bool.self) { group in
        for worker in 0..<4 {
            group.addTask {
                for round in 0..<12 {
                    let lut = cubes[(worker + round) % cubes.count]
                    let result = transform.apply(image, orientation: allOrientations[(worker + round) % 4], lut: lut, needPlain: round % 2 == 0)
                    if result.display.width != 64 || result.display.height != 36 { return false }
                }
                return true
            }
        }
        for await ok in group { #expect(ok) }
    }
    // и после этого всё по-прежнему
    #expect(worstDifference(readPixels(transform.apply(image, orientation: .off, lut: .identity(size: 2), needPlain: false).display), source) == 0)
}
