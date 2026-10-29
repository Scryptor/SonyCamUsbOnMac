import CoreGraphics
import Foundation

/// Пипетка: яркость и цвет в одном месте кадра — среднее по квадрату 5×5 точек вокруг метки. Квадрат у края кадра
/// обрезается краем (в углу это 3×3), метка за кадром упирается в ближайшую точку кадра.
public enum ColorProbe {
    /// Что показала пипетка.
    public struct Reading: Equatable, Sendable {
        /// Яркость (Rec. 709, как у гистограммы) в процентах от белого превью, округлена до целого: белое — 234 в видео,
        /// 255 в фото и с LUT. Что ярче белого превью — больше 100.
        public let percent: Int
        /// Среднее красное, зелёное и синее по квадрату, 0…255.
        public let r: Int
        public let g: Int
        public let b: Int
        /// Зона false color для этой яркости — по той же таблице, что легенда и сама подсветка; nil — между зонами.
        public let zone: AssistRaster.FalseColorBand?

        public init(percent: Int, r: Int, g: Int, b: Int, zone: AssistRaster.FalseColorBand?) {
            self.percent = percent
            self.r = r
            self.g = g
            self.b = b
            self.zone = zone
        }

        /// Две строки таблички у метки: «47 % · кожа» (без зоны — «47 %») и «R 182 · G 140 · B 121».
        public var lines: (String, String) {
            let first = zone.map { "\(percent) % · \($0.title)" } ?? "\(percent) %"
            return (first, "R \(r) · G \(g) · B \(b)")
        }
    }

    /// Сколько точек в каждую сторону от метки: 2 — квадрат 5×5.
    private static let reach = 2

    /// Что под меткой. rgba — по 4 байта на точку (R, G, B, A), строка кадра занимает bytesPerRow байт; point — точка кадра
    /// (в точках, не в долях): точка, в которую попала координата, а не-число — точка 0. white — белый уровень превью
    /// (234 в видео, 255 в фото и с LUT). Кадр, которого нет или который короче своей разметки, даёт нули.
    ///
    /// Яркость каждой точки — целая, `(218R + 732G + 74B) >> 10`, как у гистограммы и растра помощи; процент — среднее
    /// яркости от белого, округлённое. Зона — та, которой false color красит этот уровень: берётся целый уровень,
    /// ближайший к средней яркости, и тот же `AssistRaster.falseColorBand`. То есть по неокруглённому проценту уровня, а не
    /// по целому числу на табличке: у плоского участка зона всегда совпадает с цветом подсветки, а «38 %» без зоны и
    /// «38 % · 18 % серого» — разные уровни по обе стороны границы зоны.
    public static func read(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, at point: CGPoint, white: Int) -> Reading {
        guard width > 0, height > 0, bytesPerRow >= width * 4,
              rgba.count >= (height - 1) * bytesPerRow + width * 4 else {
            return Reading(percent: 0, r: 0, g: 0, b: 0, zone: nil)
        }
        let cx = pixel(Double(point.x), count: width), cy = pixel(Double(point.y), count: height)
        let x0 = max(cx - reach, 0), x1 = min(cx + reach, width - 1)
        let y0 = max(cy - reach, 0), y1 = min(cy + reach, height - 1)
        var sumR = 0, sumG = 0, sumB = 0, sumLuma = 0
        rgba.withUnsafeBufferPointer { src in
            for y in y0...y1 {
                var i = y * bytesPerRow + x0 * 4
                for _ in x0...x1 {
                    let r = Int(src[i]), g = Int(src[i + 1]), b = Int(src[i + 2])
                    sumR += r
                    sumG += g
                    sumB += b
                    sumLuma += (218 * r + 732 * g + 74 * b) >> 10
                    i += 4
                }
            }
        }
        let count = Double((x1 - x0 + 1) * (y1 - y0 + 1))
        let luma = Double(sumLuma) / count
        // белого нет — как белое 1 (так же считает и подсветка), а не деление на ноль
        let percent = Int((luma / Double(max(white, 1)) * 100).rounded())
        return Reading(percent: percent,
                       r: Int((Double(sumR) / count).rounded()),
                       g: Int((Double(sumG) / count).rounded()),
                       b: Int((Double(sumB) / count).rounded()),
                       zone: AssistRaster.falseColorBand(Int(luma.rounded()), white: white))
    }

    /// Точка кадра, в которую попала координата (точка i занимает i…<i + 1). За кадром — ближайшая точка кадра, не-число — 0.
    private static func pixel(_ value: Double, count: Int) -> Int {
        guard !value.isNaN else { return 0 }
        return Int(min(max(value.rounded(.down), 0), Double(count - 1)))
    }
}
