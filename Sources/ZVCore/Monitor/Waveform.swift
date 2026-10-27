import Foundation

/// Волновая форма — как у мониторов: по горизонтали — столбцы кадра слева направо, по вертикали — яркость точек этого
/// столбца, снизу чёрное, сверху белое. Яркость (Rec. 709, как у гистограммы) или RGB-парад — три такие картинки рядом,
/// для красного, зелёного и синего. Считается по всему кадру превью: ровно на чём стоит экспозиция и где пересвет.
public enum Waveform {
    public enum Mode: String, Codable, CaseIterable, Sendable {
        case luma, parade

        public var title: String {
            switch self {
            case .luma: return "Яркость"
            case .parade: return "RGB"
            }
        }
    }

    /// Строк по яркости: по два уровня из 256 на строку.
    public static let rows = 128
    /// Столбцов у яркости; у парада — по столько же на канал, сложенных втрое уже.
    public static let lumaColumns = 256
    public static let paradeColumns = 128

    /// Сколько точек кадра попало в каждую клетку: строки снизу вверх (0 — чёрное), столбцы слева направо;
    /// у парада — сначала красный, потом зелёный и синий, по `paradeColumns` столбцов.
    public struct Counts: Equatable, Sendable {
        public let mode: Mode
        public let columns: Int
        public let counts: [UInt32]
        /// Сколько точек кадра приходится на один столбец одного канала — от этого яркость следа.
        public let samplesPerColumn: Int

        public func count(column: Int, row: Int) -> UInt32 { counts[row * columns + column] }

        /// Самая яркая и самая тёмная строка, где есть точки, — уровни 0…255; nil — пусто.
        public var levelRange: (low: Int, high: Int)? {
            var low: Int?, high: Int?
            for row in 0..<Waveform.rows {
                let start = row * columns
                if counts[start..<(start + columns)].contains(where: { $0 > 0 }) {
                    if low == nil { low = row }
                    high = row
                }
            }
            guard let low, let high else { return nil }
            return (low * 256 / Waveform.rows, (high + 1) * 256 / Waveform.rows - 1)
        }
    }

    /// Подсчёт по кадру. rgba — по 4 байта на точку (R, G, B, A).
    public static func counts(rgba: UnsafeRawBufferPointer, width: Int, height: Int, bytesPerRow: Int, mode: Mode) -> Counts {
        let perChannel = mode == .luma ? lumaColumns : paradeColumns
        let columns = mode == .luma ? lumaColumns : paradeColumns * 3
        var counts = [UInt32](repeating: 0, count: columns * rows)
        guard width > 0, height > 0, rgba.count >= (height - 1) * bytesPerRow + width * 4 else {
            return Counts(mode: mode, columns: columns, counts: counts, samplesPerColumn: 1)
        }
        // в какой столбец идёт каждый столбец кадра
        let column = (0..<width).map { min($0 * perChannel / width, perChannel - 1) }
        counts.withUnsafeMutableBufferPointer { c in
            for y in 0..<height {
                var i = y * bytesPerRow
                for x in 0..<width {
                    let r = Int(rgba[i]), g = Int(rgba[i + 1]), b = Int(rgba[i + 2])
                    let col = column[x]
                    switch mode {
                    case .luma:
                        let v = min((218 * r + 732 * g + 74 * b) >> 10, 255)
                        c[(v >> 1) * columns + col] += 1
                    case .parade:
                        c[(r >> 1) * columns + col] += 1
                        c[(g >> 1) * columns + perChannel + col] += 1
                        c[(b >> 1) * columns + 2 * perChannel + col] += 1
                    }
                    i += 4
                }
            }
        }
        return Counts(mode: mode, columns: columns, counts: counts, samplesPerColumn: max(1, width * height / perChannel))
    }

    public static func counts(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, mode: Mode) -> Counts {
        rgba.withUnsafeBytes { counts(rgba: $0, width: width, height: height, bytesPerRow: bytesPerRow, mode: mode) }
    }

    /// Яркость следа в клетке, 0…1: корень из доли точек столбца, полная при 5 % столбца; одна точка — уже заметна.
    public static func intensity(_ count: UInt32, samplesPerColumn: Int) -> Double {
        guard count > 0 else { return 0 }
        return max(0.18, min(1, (Double(count) / (Double(samplesPerColumn) * 0.05)).squareRoot()))
    }

    /// Картинка следа: RGBA (альфа умножена), строки сверху вниз — сверху белое. Яркость — белым, парад — своим цветом.
    public static func image(_ counts: Counts) -> [UInt8] {
        let columns = counts.columns
        var pixels = [UInt8](repeating: 0, count: columns * rows * 4)
        let perChannel = counts.mode == .luma ? columns : paradeColumns
        let tints: [(Double, Double, Double)] = counts.mode == .luma ? [(0.92, 1, 0.92)]
            : [(1, 0.32, 0.3), (0.35, 1, 0.4), (0.35, 0.55, 1)]
        for row in 0..<rows {
            let y = rows - 1 - row
            for x in 0..<columns {
                let a = intensity(counts.count(column: x, row: row), samplesPerColumn: counts.samplesPerColumn)
                guard a > 0 else { continue }
                let (r, g, b) = tints[min(x / perChannel, tints.count - 1)]
                let i = (y * columns + x) * 4
                pixels[i] = UInt8((r * a * 255).rounded())
                pixels[i + 1] = UInt8((g * a * 255).rounded())
                pixels[i + 2] = UInt8((b * a * 255).rounded())
                pixels[i + 3] = UInt8((a * 255).rounded())
            }
        }
        return pixels
    }
}
