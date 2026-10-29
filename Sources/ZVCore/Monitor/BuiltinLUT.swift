import Foundation

/// Встроенные LUT для превью S-Log и HLG: код камеры → привычная картинка Rec.709. Считаются сами, по открытым формулам
/// (`SonyCurves`), а не берутся готовыми файлами. Значения — те же, что у `MonitorAssist.LUTMode`.
public enum BuiltinLUT: String, CaseIterable, Sendable {
    /// S-Log3 · S-Gamut3.Cine
    case slog3cine
    /// S-Log3 · S-Gamut3
    case slog3
    /// S-Log2 · S-Gamut (основные цвета те же, что у S-Gamut3)
    case slog2
    /// HLG · BT.2020
    case hlg

    /// Подпись в плитке «LUT» и в «Авто: …».
    public var label: String {
        switch self {
        case .slog3cine: return "S-Log3 Cine"
        case .slog3: return "S-Log3"
        case .slog2: return "S-Log2"
        case .hlg: return "HLG"
        }
    }

    /// Точный путь для одного цвета. Вход — `v = Y′/255` каждого канала превью (код камеры/1023), выход — экран
    /// Rec.709, 0…1:
    /// 1. кривая → отражение сцены `L` по каналам;
    /// 2. матрица охвата → Rec.709;
    /// 3. мягкое плечо (до колена 0,6 как есть, света меньше нуля нет);
    /// 4. кодирование BT.709 с обрезкой 0…1.
    public func transform(_ r: Double, _ g: Double, _ b: Double) -> (Double, Double, Double) {
        let lr = linear(r), lg = linear(g), lb = linear(b)
        let m = matrix
        return (
            Self.encode(m[0] * lr + m[1] * lg + m[2] * lb),
            Self.encode(m[3] * lr + m[4] * lg + m[5] * lb),
            Self.encode(m[6] * lr + m[7] * lg + m[8] * lb)
        )
    }

    /// Куб `size³` из точного пути (по умолчанию 33³): по осям — `0, 1/(size − 1), … 1`.
    public func make(size: Int = 33) -> ColorLUT {
        let size = max(size, 2)
        var values: [Float] = []
        values.reserveCapacity(size * size * size * 3)
        let last = Double(size - 1)
        for bi in 0..<size {
            for gi in 0..<size {
                for ri in 0..<size {
                    let out = transform(Double(ri) / last, Double(gi) / last, Double(bi) / last)
                    values.append(Float(out.0))
                    values.append(Float(out.1))
                    values.append(Float(out.2))
                }
            }
        }
        return ColorLUT(size: size, values: values)
    }

    /// Куб 33³ для экрана: строится при первом обращении и дальше берётся из памяти — один на каждый вид, на всю программу.
    /// Тот же массив чисел каждый раз, так что сравнение кубов (`==`) и передача в кадровый цикл стоят почти ничего.
    public var cube: ColorLUT {
        Self.cubes.lock()
        defer { Self.cubes.unlock() }
        if let made = Self.made[self] { return made }
        let made = make(size: Self.screenCubeSize)
        Self.made[self] = made
        return made
    }

    /// Точек на ребре куба для экрана: 33.
    public static let screenCubeSize = 33

    // MARK: - Внутренности

    private static let cubes = NSLock()
    nonisolated(unsafe) private static var made: [BuiltinLUT: ColorLUT] = [:]

    private func linear(_ v: Double) -> Double {
        switch self {
        case .slog3cine, .slog3: return SonyCurves.slog3ToLinear(v)
        case .slog2: return SonyCurves.slog2ToLinear(v)
        case .hlg: return SonyCurves.hlgToLinear(v)
        }
    }

    private static func encode(_ l: Double) -> Double { SonyCurves.bt709(SonyCurves.shoulder(l)) }

    /// Матрица охвата → Rec.709 по строкам, девять чисел. Считается один раз.
    private var matrix: [Double] {
        switch self {
        case .slog3cine: return Self.cineMatrix
        case .slog3, .slog2: return Self.sGamut3Matrix
        case .hlg: return Self.bt2020Matrix
        }
    }

    private static let cineMatrix = SonyCurves.matrixTo709(primaries: SonyCurves.sGamut3Cine).flatMap { $0 }
    private static let sGamut3Matrix = SonyCurves.matrixTo709(primaries: SonyCurves.sGamut3).flatMap { $0 }
    private static let bt2020Matrix = SonyCurves.matrixTo709(primaries: SonyCurves.bt2020).flatMap { $0 }
}
