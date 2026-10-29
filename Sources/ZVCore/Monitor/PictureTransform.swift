import CoreGraphics
import CoreImage
import CoreImage.CIFilterBuiltins
import Foundation
import ImageIO

/// Преобразование картинки кадра на Mac — одно, сразу после декодирования: зеркало или переворот, потом LUT. Всё, что
/// после (экран, увеличение, мини-карта, лента, полёт стоп-кадра и помощь «с LUT»), берёт уже результат. Снимки, файлы и
/// JPEG из камеры этот тип не видит и не меняет.
///
/// Core Image без управления цветом: рабочее пространство и выход — `NSNull`, поэтому значения проходят через куб как
/// есть: канал `v = Y′/255` попадает на куб ровно так, как его считают встроенные LUT. Цветовой тег у результата тот же,
/// что у кадра: экран переведёт его в свой цвет один раз, как и без преобразования.
///
/// Цена — один рендер в `CGImage`: на кадр 1024×576 около миллисекунды отдельно и 2–4 мс в работающей программе; первый вызов
/// дороже (контекст Core Image, десятки миллисекунд — один-два кадра). Второй рендер — только когда помощь считается «без
/// LUT» (`needPlain`) и поворот есть. Ни поворота, ни LUT — Core Image не трогается вовсе: кадр возвращается тот же.
///
/// Вызывать можно из любого потока: вызовы идут по одному (в кадровом цикле он и так один), всё изменяемое — кеш готовых
/// кубов — под замком, а контекст Core Image общий на программу.
public final class PictureTransform: @unchecked Sendable {
    /// Больше точек на ребре куба `CIColorCube` не берёт (по документации; новые системы заявляют 128, но на 14-й не
    /// проверить); куб больше пересчитывается в 64³ один раз.
    public static let maxCubeSize = 64

    public init() {}

    /// Кадр после зеркала или переворота и LUT.
    ///
    /// - image: кадр как после декодирования
    /// - orientation: зеркало и переворот; `.off` — как есть
    /// - lut: куб или nil — без LUT. «Авто», которое ничего не выбрало, здесь тоже nil: решает уже выбранный куб, а не
    ///   настройка (см. `MonitorAssist.transformsPicture`)
    /// - needPlain: нужна ещё картинка только с поворотом, без LUT — для помощи «без LUT»
    /// - Returns: `display` — что видно на экране (повёрнутый и с LUT). `plain` — повёрнутый без LUT, и только если
    ///   `needPlain` и LUT есть; поворота нет — это сам `image`, второй рендер не делается. Нет LUT — `plain` nil: повёрнутый
    ///   кадр и есть сигнал камеры, это `display`. Ничего не нужно — `display` это сам `image`, а `plain` nil.
    public func apply(_ image: CGImage, orientation: FrameOrientation, lut: ColorLUT?, needPlain: Bool) -> (display: CGImage, plain: CGImage?) {
        if lut == nil && orientation == .off { return (image, nil) }
        lock.lock()
        defer { lock.unlock() }

        let space = Self.outputSpace(for: image)
        guard let bitmap = Self.bitmap(from: image, space: space) else { return (image, nil) }
        var base = CIImage(cgImage: bitmap, options: [.colorSpace: NSNull()])
        if orientation != .off { base = base.oriented(orientation.imageOrientation) }
        let size = (image.width, image.height)

        guard let lut else {
            return (Self.render(base, size: size, space: space) ?? image, nil)
        }
        let prepared = prepared(for: lut)
        let rendered = Self.render(prepared.apply(to: base), size: size, space: space)
        // фильтр остаётся в кеше, но кадр не держит
        prepared.clearInput()
        guard let display = rendered else { return (image, nil) }
        guard needPlain else { return (display, nil) }
        // поворота нет — сигнал и есть кадр камеры
        return (display, orientation == .off ? image : Self.render(base, size: size, space: space))
    }

    // MARK: - Внутренности

    /// Один на программу: создание контекста дорогое, а рисовать он может из любого потока. Вычисления в 32 битах:
    /// в половинной точности домен куба (`(v − min)/(max − min)`) терял бы точность.
    private static let context = CIContext(options: [
        .workingColorSpace: NSNull(),
        .outputColorSpace: NSNull(),
        .workingFormat: CIFormat.RGBAf.rawValue,
        .cacheIntermediates: false,
    ])

    /// Куб, который уходит в `CIColorCube`: тот же, если в нём не больше 64 точек на ребре (у `.cube` бывает и 65), иначе — 64³,
    /// пересчитанный по тем же точкам: узлы нового куба — в тех же долях 0…1 входа, значения между узлами старого — трилинейно,
    /// как у `ColorLUT.apply`. На обычных цветовых грейдах разница меньше уровня; у резких изломов (цвета за охватом,
    /// обрезанные в 0) — несколько уровней. Домен в новый куб не входит: его приводит к 0…1 `domainScale` перед кубом.
    static func coreImageCube(for lut: ColorLUT) -> ColorLUT {
        guard lut.size > maxCubeSize else { return lut }
        let size = maxCubeSize
        let plain = ColorLUT(size: lut.size, values: lut.values)
        var values: [Float] = []
        values.reserveCapacity(size * size * size * 3)
        let last = Float(size - 1)
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    let out = plain.apply(Float(r) / last, Float(g) / last, Float(b) / last)
                    values.append(out.0)
                    values.append(out.1)
                    values.append(out.2)
                }
            }
        }
        return ColorLUT(size: size, values: values)
    }

    private let lock = NSLock()

    /// Готовые к кадру фильтры по кубам — самые свежие первыми. Куб в списке узнаётся по значению: тот же массив чисел —
    /// сравнение мгновенное; другой куб разойдётся в первых же числах.
    private var cache: [Prepared] = []
    private static let cacheLimit = 3

    private func prepared(for lut: ColorLUT) -> Prepared {
        if let index = cache.firstIndex(where: { $0.lut == lut }) {
            if index != 0 { cache.insert(cache.remove(at: index), at: 0) }
            return cache[0]
        }
        let made = Prepared(lut)
        cache.insert(made, at: 0)
        if cache.count > Self.cacheLimit { cache.removeLast(cache.count - Self.cacheLimit) }
        return made
    }

    /// Куб, приготовленный для Core Image: данные куба и, если домен не 0…1, приведение входа к нему перед ним.
    private final class Prepared {
        /// Для чего приготовлено — по нему куб узнаётся в списке.
        let lut: ColorLUT
        private let cube: CIFilter
        private let scale: CIFilter?

        init(_ lut: ColorLUT) {
            self.lut = lut
            let source = PictureTransform.coreImageCube(for: lut)
            let cube = CIFilter.colorCube()
            cube.cubeDimension = Float(source.size)
            cube.cubeData = source.rgbaFloat32()
            self.cube = cube
            scale = Self.domainScale(lut)
        }

        /// Забыть последний кадр: фильтр живёт между кадрами, а держать им картинку незачем.
        func clearInput() {
            scale?.setValue(nil, forKey: kCIInputImageKey)
            cube.setValue(nil, forKey: kCIInputImageKey)
        }

        /// Картинка после куба.
        func apply(to image: CIImage) -> CIImage {
            var input = image
            if let scale {
                scale.setValue(input, forKey: kCIInputImageKey)
                input = scale.outputImage ?? input
            }
            cube.setValue(input, forKey: kCIInputImageKey)
            return cube.outputImage ?? input
        }

        /// `(v − min)/(max − min)` по каналам перед кубом; nil — домен 0…1, делать нечего. Канал без ширины (max ≤ min)
        /// идёт в 0, как в `ColorLUT.apply`. Что за доменом, обрезает сам `CIColorCube`: его вход — 0…1.
        private static func domainScale(_ lut: ColorLUT) -> CIFilter? {
            guard lut.domainMin != [0, 0, 0] || lut.domainMax != [1, 1, 1] else { return nil }
            var gain = [CGFloat](repeating: 0, count: 3), bias = [CGFloat](repeating: 0, count: 3)
            for channel in 0..<3 {
                let span = lut.domainMax[channel] - lut.domainMin[channel]
                // `>`, а не `max/min`: NaN и «не больше нуля» дают 0, как в `ColorLUT.apply`
                guard span > 0 else { continue }
                let g = 1 / Double(span), b = -Double(lut.domainMin[channel]) / Double(span)
                guard g.isFinite, b.isFinite else { continue }
                gain[channel] = CGFloat(g)
                bias[channel] = CGFloat(b)
            }
            let matrix = CIFilter.colorMatrix()
            matrix.rVector = CIVector(x: gain[0], y: 0, z: 0, w: 0)
            matrix.gVector = CIVector(x: 0, y: gain[1], z: 0, w: 0)
            matrix.bVector = CIVector(x: 0, y: 0, z: gain[2], w: 0)
            matrix.aVector = CIVector(x: 0, y: 0, z: 0, w: 1)
            matrix.biasVector = CIVector(x: bias[0], y: bias[1], z: bias[2], w: 0)
            return matrix
        }
    }

    /// Цветовой тег результата — тот же, что у кадра. Кадр не RGB (серый, CMYK) или без тега — sRGB: Core Image в RGBA8 серого
    /// не напишет, а экран всё равно покажет его как RGB.
    private static func outputSpace(for image: CGImage) -> CGColorSpace {
        if let space = image.colorSpace, space.model == .rgb { return space }
        return CGColorSpace(name: CGColorSpace.sRGB) ?? CGColorSpaceCreateDeviceRGB()
    }

    /// Кадр точками в памяти. Кадр после `ImageIO` помнит свой JPEG, и Core Image разжал бы его сам, другим сглаживанием
    /// цвета (в среднем на 0,2 уровня, на краях цвета до 25): картинка с преобразованием отличалась бы от той же без него.
    /// Перерисовка в то же пространство ничего не пересчитывает и стоит доли миллисекунды.
    private static func bitmap(from image: CGImage, space: CGColorSpace) -> CGImage? {
        guard let context = CGContext(data: nil, width: image.width, height: image.height, bitsPerComponent: 8, bytesPerRow: 0,
                                      space: space, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return nil }
        context.interpolationQuality = .none
        context.draw(image, in: CGRect(x: 0, y: 0, width: image.width, height: image.height))
        return context.makeImage()
    }

    /// Рендер в `CGImage` 8 бит; nil — Core Image отказал или вернул не тот размер (тогда кадр остаётся таким, как был).
    private static func render(_ image: CIImage, size: (Int, Int), space: CGColorSpace) -> CGImage? {
        let extent = image.extent
        guard extent.width == CGFloat(size.0), extent.height == CGFloat(size.1) else { return nil }
        return context.createCGImage(image, from: extent, format: .RGBA8, colorSpace: space)
    }
}

private extension FrameOrientation {
    /// То же в терминах EXIF для `CIImage.oriented`: зеркало — по горизонтали, 180° — поворот, зеркало + 180° — по вертикали.
    var imageOrientation: CGImagePropertyOrientation {
        switch self {
        case .off: return .up
        case .mirror: return .upMirrored
        case .rotate180: return .down
        case .mirror180: return .downMirrored
        }
    }
}
