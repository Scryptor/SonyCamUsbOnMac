import Foundation

/// Гистограмма яркости кадра: сколько точек приходится на каждый уровень яркости.
public enum LumaHistogram {
    /// Сколько точек на каждом из 256 уровней яркости. rgba — пиксели по 4 байта (R, G, B, A).
    public static func levels(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int) -> [Int] {
        var levels = [Int](repeating: 0, count: 256)
        guard width > 0, height > 0, rgba.count >= (height - 1) * bytesPerRow + width * 4 else { return levels }
        for y in 0..<height {
            var i = y * bytesPerRow
            for _ in 0..<width {
                // яркость по Rec. 709 в целых числах (коэффициенты ×1024)
                let luma = (218 * Int(rgba[i]) + 732 * Int(rgba[i + 1]) + 74 * Int(rgba[i + 2])) >> 10
                levels[min(luma, 255)] += 1
                i += 4
            }
        }
        return levels
    }

    /// Столбцы для графика: уровни от чёрного до белого (`whiteLevel`), сложенные в count групп; что ярче белого —
    /// в последний столбец. Так пересвет всегда у правого края.
    public static func bins(levels: [Int], count: Int = 64, whiteLevel: Int = 255) -> [Int] {
        var bins = [Int](repeating: 0, count: max(count, 0))
        guard count > 0, whiteLevel > 0 else { return bins }
        for (level, points) in levels.enumerated() {
            bins[min(level * count / (whiteLevel + 1), count - 1)] += points
        }
        return bins
    }

    /// rgba — пиксели по 4 байта (R, G, B, A); count — число столбцов гистограммы.
    public static func bins(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, count: Int = 64) -> [Int] {
        bins(levels: levels(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow), count: count)
    }
}

/// Что видно по гистограмме: как точки делятся между тенями, полутонами и светами, сколько их ушло в чёрное
/// (провалы) и в белое (пересвет), и средняя яркость кадра. Всё — в процентах, шкала — от чёрного до белого.
public struct LumaStats: Equatable, Sendable {
    /// Точек в тенях — первой трети шкалы.
    public let shadows: Double
    /// Точек в полутонах — средней трети.
    public let midtones: Double
    /// Точек в светах — последней трети.
    public let highlights: Double
    /// Почти чёрных точек (уровни 0–4): детали в тенях потеряны.
    public let clippedShadows: Double
    /// Точек на белом уровне: пересвет.
    public let clippedHighlights: Double
    /// Средняя яркость, % шкалы.
    public let mean: Double

    /// levels — 256 уровней, как отдаёт `LumaHistogram.levels`; whiteLevel — уровень белого (`whiteLevel(levels:video:)`).
    /// nil, если точек нет.
    public init?(levels: [Int], whiteLevel: Int = 255) {
        let total = levels.reduce(0, +)
        guard total > 0, whiteLevel > 0 else { return nil }
        func share(_ include: (Int) -> Bool) -> Double {
            let points = levels.indices.reduce(0) { include($1) ? $0 + levels[$1] : $0 }
            return Double(points) * 100 / Double(total)
        }
        let third = Double(whiteLevel + 1) / 3
        shadows = share { Double($0) < third }
        midtones = share { Double($0) >= third && Double($0) < 2 * third }
        highlights = share { Double($0) >= 2 * third }
        clippedShadows = share { $0 <= 4 }
        // пересвеченное место ложится столбиком ровно на белый уровень; на уровень ниже — его края после сжатия JPEG
        clippedHighlights = share { $0 >= whiteLevel - 1 }
        let weighted = levels.enumerated().reduce(0) { $0 + min($1.offset, whiteLevel) * $1.element }
        mean = Double(weighted) / Double(total) / Double(whiteLevel) * 100
    }

    /// Уровень белого в картинке live view. В фото — 255. В видео камера отдаёт белое на 234 (пересвеченное окно
    /// ложится столбиком ровно на 234), но если ярче 240 больше 0,1 % точек — у этого профиля шкала полная: 255.
    public static func whiteLevel(levels: [Int], video: Bool) -> Int {
        guard video else { return 255 }
        let total = levels.reduce(0, +)
        let above = levels.indices.reduce(0) { $1 > 240 ? $0 + levels[$1] : $0 }
        return total > 0 && Double(above) / Double(total) > 0.001 ? 255 : 234
    }

    /// С какой доли точек на краю шкалы выделяем провалы или пересвет.
    public static func isClipped(_ percent: Double) -> Bool { percent >= 1 }

    /// Доля зоны — целые проценты: «38%».
    public static func share(_ percent: Double) -> String { "\(Int(percent.rounded()))%" }

    /// Доля на краю — с десятой долей, пока она меньше 10 %: «0,4%», «13%».
    public static func clipping(_ percent: Double) -> String {
        if percent == 0 || percent >= 9.95 { return share(percent) }
        return String(format: "%.1f%%", percent).replacingOccurrences(of: ".", with: ",")
    }
}
