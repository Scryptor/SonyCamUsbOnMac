import Foundation

/// Растр помощи в кадре: по яркости видимой части кадра — картинка поверх него (RGBA, альфа уже умножена) с false color,
/// зеброй и пикингом. Проценты — доли белого превью, как у гистограммы: в видео белое — 234, в фото — 255.
///
/// При цифровом увеличении видимая часть кадра пересчитывается бикубически (Catmull-Rom, как картинка на экране) во столько
/// раз крупнее, во сколько увеличено: полосы зебры остаются одной ширины на экране, а пикинг — тонкой линией по середине
/// края, а не квадратами по 8 точек.
public enum AssistRaster {
    /// Часть кадра, в точках кадра.
    public struct Region: Equatable, Sendable {
        public var x: Double
        public var y: Double
        public var width: Double
        public var height: Double

        public init(x: Double, y: Double, width: Double, height: Double) {
            self.x = x
            self.y = y
            self.width = width
            self.height = height
        }
    }

    /// Что считать: часть кадра и размер растра.
    public struct Plan: Equatable, Sendable {
        public let region: Region
        public let width: Int
        public let height: Int

        /// Во сколько раз растр крупнее кадра.
        public var scale: Double { region.width > 0 ? Double(width) / region.width : 1 }
    }

    /// Растр помощи и сколько точек под зеброй и пикингом — проценты растра.
    public struct Output: Sendable {
        public let pixels: [UInt8]
        public let width: Int
        public let height: Int
        public let zebraShare: Double
        public let peakingShare: Double
    }

    /// Растр больше кадра не больше чем в столько раз по числу точек: дольше считать, а глазу не нужно.
    static let maxPixelsRatio = 1.3

    /// Видимая часть кадра (доли, как `ViewZoom.visibleRegion`) с запасом 10 % на сдвиг — и размер растра для неё:
    /// во столько раз крупнее, во сколько увеличено.
    public static func plan(frameWidth: Int, frameHeight: Int, visible: (x: Double, y: Double, width: Double, height: Double),
                            factor: Double) -> Plan {
        let w = Double(frameWidth), h = Double(frameHeight)
        guard factor > 1.0001, frameWidth > 0, frameHeight > 0 else {
            return Plan(region: Region(x: 0, y: 0, width: w, height: h), width: frameWidth, height: frameHeight)
        }
        let mx = visible.width * 0.1, my = visible.height * 0.1
        let x0 = max(0, visible.x - mx), y0 = max(0, visible.y - my)
        let x1 = min(1, visible.x + visible.width + mx), y1 = min(1, visible.y + visible.height + my)
        let region = Region(x: x0 * w, y: y0 * h, width: max(1, (x1 - x0) * w), height: max(1, (y1 - y0) * h))
        var k = factor
        let pixels = region.width * region.height * k * k
        let limit = w * h * maxPixelsRatio
        if pixels > limit { k *= (limit / pixels).squareRoot() }
        k = max(k, 1)
        return Plan(region: region, width: max(1, Int((region.width * k).rounded())), height: max(1, Int((region.height * k).rounded())))
    }

    /// Яркость по Rec. 709, как у гистограммы, — по байту на точку. rgba — по 4 байта (R, G, B, A).
    public static func luma(rgba: UnsafeRawBufferPointer, width: Int, height: Int, bytesPerRow: Int) -> [UInt8] {
        guard width > 0, height > 0, rgba.count >= (height - 1) * bytesPerRow + width * 4 else { return [] }
        var out = [UInt8](repeating: 0, count: width * height)
        out.withUnsafeMutableBufferPointer { dst in
            for y in 0..<height {
                var i = y * bytesPerRow
                var o = y * width
                for _ in 0..<width {
                    let v = (218 * Int(rgba[i]) + 732 * Int(rgba[i + 1]) + 74 * Int(rgba[i + 2])) >> 10
                    dst[o] = UInt8(min(v, 255))
                    i += 4
                    o += 1
                }
            }
        }
        return out
    }

    public static func luma(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int) -> [UInt8] {
        rgba.withUnsafeBytes { luma(rgba: $0, width: width, height: height, bytesPerRow: bytesPerRow) }
    }

    /// Часть кадра в размер растра: бикубически (Catmull-Rom). Растр того же размера, что целая часть кадра, — просто копия.
    public static func resample(_ luma: [UInt8], width: Int, height: Int, plan: Plan) -> [UInt8] {
        let r = plan.region
        let ow = plan.width, oh = plan.height
        guard width > 0, height > 0, luma.count >= width * height, ow > 0, oh > 0 else { return [] }
        if ow == Int(r.width), oh == Int(r.height), r.x == r.x.rounded(), r.y == r.y.rounded(), r.width == r.width.rounded(),
           r.height == r.height.rounded() {
            let x0 = Int(r.x), y0 = Int(r.y)
            if x0 == 0, y0 == 0, ow == width, oh == height { return luma }
            var out = [UInt8](repeating: 0, count: ow * oh)
            for y in 0..<oh {
                let from = (y0 + y) * width + x0
                out.replaceSubrange((y * ow)..<(y * ow + ow), with: luma[from..<(from + ow)])
            }
            return out
        }
        // по столбцам: откуда брать и с какими весами
        let xs = taps(count: ow, start: r.x, step: r.width / Double(ow), limit: width)
        let ys = taps(count: oh, start: r.y, step: r.height / Double(oh), limit: height)
        // строки кадра, которые нужны
        let rowLo = ys.index.min() ?? 0, rowHi = (ys.index.max() ?? 0) + 3
        let rows = rowHi - rowLo + 1
        var tmp = [Float](repeating: 0, count: rows * ow)
        var out = [UInt8](repeating: 0, count: ow * oh)
        luma.withUnsafeBufferPointer { src in
            tmp.withUnsafeMutableBufferPointer { t in
                for row in 0..<rows {
                    let sy = min(max(rowLo + row, 0), height - 1)
                    let line = sy * width
                    for ox in 0..<ow {
                        let base = xs.index[ox], w = ox * 4
                        var sum: Float = 0
                        for j in 0..<4 {
                            let sx = min(max(base + j, 0), width - 1)
                            sum += xs.weight[w + j] * Float(src[line + sx])
                        }
                        t[row * ow + ox] = sum
                    }
                }
            }
        }
        tmp.withUnsafeBufferPointer { t in
            out.withUnsafeMutableBufferPointer { dst in
                for oy in 0..<oh {
                    let base = ys.index[oy] - rowLo, w = oy * 4
                    for ox in 0..<ow {
                        var sum: Float = 0
                        for j in 0..<4 { sum += ys.weight[w + j] * t[(base + j) * ow + ox] }
                        dst[oy * ow + ox] = UInt8(min(max(sum.rounded(), 0), 255))
                    }
                }
            }
        }
        return out
    }

    /// Для каждой точки растра — первая из четырёх точек кадра (с -1) и их веса Catmull-Rom.
    private static func taps(count: Int, start: Double, step: Double, limit: Int) -> (index: [Int], weight: [Float]) {
        var index = [Int](repeating: 0, count: count)
        var weight = [Float](repeating: 0, count: count * 4)
        for i in 0..<count {
            let x = start + (Double(i) + 0.5) * step - 0.5
            let f = x.rounded(.down)
            let t = Float(x - f)
            index[i] = Int(f) - 1
            let t2 = t * t, t3 = t2 * t
            weight[i * 4] = (-t3 + 2 * t2 - t) / 2
            weight[i * 4 + 1] = (3 * t3 - 5 * t2 + 2) / 2
            weight[i * 4 + 2] = (-3 * t3 + 4 * t2 + t) / 2
            weight[i * 4 + 3] = (t3 - t2) / 2
        }
        return (index, weight)
    }

    // MARK: - Зебра и false color

    /// Попадает ли уровень яркости под зебру при белом `white`.
    public static func isZebra(_ level: Int, _ zebra: MonitorAssist.Zebra, white: Int) -> Bool {
        let p = Double(level) * 100 / Double(max(white, 1))
        switch zebra {
        case .off: return false
        // пересвет — как у гистограммы: белый уровень и на единицу ниже (края после сжатия JPEG)
        case .above100: return level >= white - 1
        case .above95: return p >= 95
        case .skin70: return p >= 65 && p <= 75
        }
    }

    /// Полосы false color: доли белого превью. Похоже на ARRI: фиолетовый — провал, синий — глубокие тени, зелёный —
    /// средне-серый (18 % серого), розовый — светлая кожа (на ступень светлее серого), жёлтый — почти белое, красный —
    /// пересвет. Что не попало в полосу — серым, своей яркостью.
    public enum FalseColorBand: String, CaseIterable, Sendable {
        case crushed, shadows, middleGray, skin, nearWhite, clipped

        public var title: String {
            switch self {
            case .crushed: return "провал"
            case .shadows: return "тени"
            case .middleGray: return "18 % серого"
            case .skin: return "кожа"
            case .nearWhite: return "почти белое"
            case .clipped: return "пересвет"
            }
        }

        /// Границы, % белого: [от, до). «Почти белое» и пересвет делит белый уровень: пересвет — он сам и на единицу ниже.
        public var range: (from: Double, to: Double) {
            switch self {
            case .crushed: return (0, 2.5)
            case .shadows: return (2.5, 8)
            case .middleGray: return (38, 45)
            case .skin: return (52, 60)
            case .nearWhite: return (93, 99)
            case .clipped: return (99, 1000)
            }
        }

        public var rgb: (UInt8, UInt8, UInt8) {
            switch self {
            case .crushed: return (150, 40, 200)
            case .shadows: return (30, 90, 255)
            case .middleGray: return (40, 200, 70)
            case .skin: return (255, 125, 185)
            case .nearWhite: return (255, 220, 0)
            case .clipped: return (255, 30, 30)
            }
        }
    }

    /// В какую полосу false color попадает уровень; nil — серым.
    public static func falseColorBand(_ level: Int, white: Int) -> FalseColorBand? {
        // пересвет — как у гистограммы и зебры 100 %; «почти белое» — всё, что ниже него, от 93 %
        if level >= white - 1 { return .clipped }
        let p = Double(level) * 100 / Double(max(white, 1))
        if p >= FalseColorBand.nearWhite.range.from { return .nearWhite }
        return FalseColorBand.allCases.first { p >= $0.range.from && p < $0.range.to }
    }

    public static func peakingRGB(_ color: MonitorAssist.PeakingColor) -> (UInt8, UInt8, UInt8) {
        switch color {
        case .red: return (255, 40, 40)
        case .yellow: return (255, 230, 0)
        case .white: return (255, 255, 255)
        case .blue: return (40, 150, 255)
        }
    }

    // MARK: - Пикинг

    /// Порог пикинга: насколько резко должна меняться яркость на соседних точках кадра (уровни из 255), чтобы край
    /// подсветился. Чем выше уровень пикинга, тем ниже порог.
    public static func peakingThreshold(_ peaking: MonitorAssist.Peaking) -> Double {
        switch peaking {
        case .off: return .infinity
        case .low: return 44
        case .mid: return 30
        case .high: return 20
        }
    }

    /// Резкие края: перепад яркости между соседними точками (корень из суммы квадратов по x и по y) не меньше порога и
    /// не меньше, чем у соседей поперёк края, — край остаётся линией в одну точку. scale — во сколько раз растр крупнее
    /// кадра: перепад на точку растра во столько же раз меньше, порог тоже. Отдаёт маску: 1 — край.
    public static func peakingMask(_ luma: [UInt8], width: Int, height: Int, threshold: Double, scale: Double) -> [UInt8] {
        var mask = [UInt8](repeating: 0, count: width * height)
        guard width > 2, height > 2, luma.count >= width * height, threshold.isFinite else { return mask }
        let t = threshold * zoomedSlope(scale) / max(scale, 1)
        let t2 = Int32((t * t).rounded(.up))
        var mag = [Int32](repeating: 0, count: width * height)
        var dirs = [UInt8](repeating: 0, count: width * height)
        luma.withUnsafeBufferPointer { p in
            mag.withUnsafeMutableBufferPointer { m in
                dirs.withUnsafeMutableBufferPointer { d in
                    for y in 0..<(height - 1) {
                        var i = y * width
                        for _ in 0..<(width - 1) {
                            let c = Int32(p[i])
                            let dx = Int32(p[i + 1]) - c, dy = Int32(p[i + width]) - c
                            m[i] = dx * dx + dy * dy
                            // поперёк края: 0 — по x, 1 — по y, 2 — по диагонали «\», 3 — по диагонали «/»
                            let ax = abs(dx), ay = abs(dy)
                            d[i] = ax > 2 * ay ? 0 : ay > 2 * ax ? 1 : (dx > 0) == (dy > 0) ? 2 : 3
                            i += 1
                        }
                    }
                }
            }
        }
        mag.withUnsafeBufferPointer { m in
            dirs.withUnsafeBufferPointer { d in
                mask.withUnsafeMutableBufferPointer { out in
                    for y in 1..<(height - 2) {
                        var i = y * width + 1
                        for _ in 1..<(width - 2) {
                            let v = m[i]
                            if v >= t2 {
                                let a: Int32, b: Int32
                                switch d[i] {
                                case 0: a = m[i - 1]; b = m[i + 1]
                                case 1: a = m[i - width]; b = m[i + width]
                                case 2: a = m[i - width - 1]; b = m[i + width + 1]
                                default: a = m[i - width + 1]; b = m[i + width - 1]
                                }
                                if v >= a, v >= b { out[i] = 1 }
                            }
                            i += 1
                        }
                    }
                }
            }
        }
        return mask
    }

    /// Во сколько раз крутизна края в пересчитанном растре больше перепада на точку кадра, делённого на увеличение:
    /// Catmull-Rom в середине ступеньки даёт наклон 1,25. Без поправки при увеличении подсвечивалось бы больше краёв, чем
    /// без него. До 2× поправка растёт плавно.
    static func zoomedSlope(_ scale: Double) -> Double {
        scale <= 1 ? 1 : 1 + 0.25 * min(scale - 1, 1)
    }

    // MARK: - Растр целиком

    /// Растр помощи по яркости видимой части кадра (уже в размере растра). scale — во сколько раз он крупнее кадра;
    /// white — белый уровень превью. nil — считать нечего.
    public static func render(luma: [UInt8], width: Int, height: Int, scale: Double, white: Int,
                              assist: MonitorAssist) -> Output? {
        guard assist.needsRaster, width > 0, height > 0, luma.count >= width * height else { return nil }
        // по уровню яркости: цвет false color (или прозрачно) и зебра
        var base = [UInt32](repeating: 0, count: 256)
        var zebra = [Bool](repeating: false, count: 256)
        for level in 0..<256 {
            if assist.falseColor {
                let (r, g, b) = falseColorBand(level, white: white)?.rgb ?? {
                    let v = UInt8(min(255, level * 255 / max(white, 1)))
                    return (v, v, v)
                }()
                base[level] = UInt32(r) | UInt32(g) << 8 | UInt32(b) << 16 | 0xFF00_0000
            }
            zebra[level] = isZebra(level, assist.zebra, white: white)
        }
        let peaks = assist.peaking == .off ? nil
            : peakingMask(luma, width: width, height: height, threshold: peakingThreshold(assist.peaking), scale: scale)
        let (pr, pg, pb) = peakingRGB(assist.peakingColor)
        let peakColor = UInt32(pr) | UInt32(pg) << 8 | UInt32(pb) << 16 | 0xFF00_0000
        var zebraCount = 0, peakCount = 0
        var pixels = [UInt32](repeating: 0, count: width * height)
        luma.withUnsafeBufferPointer { l in
            pixels.withUnsafeMutableBufferPointer { out in
                for y in 0..<height {
                    var i = y * width
                    for x in 0..<width {
                        let level = Int(l[i])
                        var px = base[level]
                        if zebra[level] {
                            zebraCount += 1
                            // полосы наискось, по 4 точки растра: чёрные на 60 % поверх того, что под ними
                            if (x + y) & 7 < 4 {
                                let keep: UInt32 = 102
                                let r = (px & 0xFF) * keep / 255, g = (px >> 8 & 0xFF) * keep / 255
                                let b = (px >> 16 & 0xFF) * keep / 255
                                let a = 153 + (px >> 24) * keep / 255
                                px = r | g << 8 | b << 16 | a << 24
                            }
                        }
                        if let peaks, peaks[i] != 0 {
                            peakCount += 1
                            px = peakColor
                        }
                        out[i] = px
                        i += 1
                    }
                }
            }
        }
        let total = Double(width * height)
        let bytes = pixels.withUnsafeBytes { Array($0) }
        return Output(pixels: bytes, width: width, height: height, zebraShare: Double(zebraCount) * 100 / total,
                      peakingShare: Double(peakCount) * 100 / total)
    }
}
