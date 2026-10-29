import Foundation

/// Кривые Sony и HLG, охваты и тон — всё, из чего считаются встроенные LUT. Формулы открытые (технические описания
/// S-Log2, S-Log3 и S-Gamut от Sony, BT.2100 и BT.709 от ITU); готовых LUT из других программ тут нет.
///
/// Вход кривых — `v = Y′/255` канала превью, то есть код камеры `код/1023` (предположение спецификации: 18 % серого
/// в S-Log3 — код 420). Выход кривых — отражение сцены `L`: 18 % серого — 0,18, диффузный белый — 1.
public enum SonyCurves {
    // MARK: Кривая → отражение сцены

    /// S-Log3 → отражение сцены. Ниже кода 171,2102946929 — прямая, выше — степень десяти; код 95 — ноль.
    public static func slog3ToLinear(_ v: Double) -> Double {
        let c = v * 1023
        if c >= slog3Joint { return pow(10, (c - 420) / 261.5) * 0.19 - 0.01 }
        return (c - 95) * 0.01125 / (slog3Joint - 95)
    }

    /// S-Log2 → отражение сцены (в кривой отражение считается от 90 % белого — отсюда `0,9·219/155`).
    public static func slog2ToLinear(_ v: Double) -> Double {
        let y = (v * 1023 - 64) / 876
        let x = y >= slog2Joint ? pow(10, (y - 0.646596) / 0.432699) - 0.037584 : (y - slog2Joint) / 5
        return x * 0.9 * 219 / 155
    }

    /// HLG (BT.2100) → отражение сцены; 75 % сигнала — диффузный белый, `L = 1`. Ниже чёрного (код 64) — ноль:
    /// квадрат отрицательного сигнала был бы светом.
    public static func hlgToLinear(_ v: Double) -> Double {
        let signal = max((v * 1023 - 64) / 876, 0)
        let scene = signal <= 0.5
            ? signal * signal / 3
            : (exp((signal - 0.55991073) / 0.17883277) + 0.28466892) / 12
        return scene / 0.2647
    }

    // MARK: Тон

    /// Мягкое плечо: до колена 0,6 свет как есть, выше он сжимается к 1 без излома (наклон в колене — 1). Света
    /// меньше нуля нет.
    public static func shoulder(_ l: Double) -> Double {
        guard l > 0 else { return 0 }
        if l <= kneeLevel { return l }
        let room = 1 - kneeLevel
        return kneeLevel + room * (1 - exp(-(l - kneeLevel) / room))
    }

    /// Кодирование BT.709 (OETF): линейный свет → сигнал 0…1; выше 1 и ниже 0 обрезается.
    public static func bt709(_ l: Double) -> Double {
        guard l > 0 else { return 0 }
        let v = l < 0.018 ? 4.5 * l : 1.099 * pow(l, 0.45) - 0.099
        return min(v, 1)
    }

    // MARK: Охваты

    /// Основные цвета охватов (красный, зелёный, синий) в координатах xy. Белая точка у всех — D65.
    public static let sGamut3Cine: [(x: Double, y: Double)] = [(0.766, 0.275), (0.225, 0.800), (0.089, -0.087)]
    /// S-Gamut3 и S-Gamut — одни и те же основные цвета.
    public static let sGamut3: [(x: Double, y: Double)] = [(0.730, 0.280), (0.140, 0.855), (0.100, -0.050)]
    public static let bt2020: [(x: Double, y: Double)] = [(0.708, 0.292), (0.170, 0.797), (0.131, 0.046)]

    /// Основные цвета Rec.709, к которому ведут все матрицы.
    static let rec709: [(x: Double, y: Double)] = [(0.64, 0.33), (0.30, 0.60), (0.15, 0.06)]
    static let d65: (x: Double, y: Double) = (0.3127, 0.3290)

    /// Матрица 3×3 (по строкам): линейный RGB охвата с этими основными цветами → линейный RGB Rec.709. Белая точка
    /// D65 у обоих, хроматической адаптации нет — строки в сумме дают 1 и серый остаётся серым.
    public static func matrixTo709(primaries: [(x: Double, y: Double)]) -> [[Double]] {
        precondition(primaries.count == 3, "нужны три основных цвета: красный, зелёный, синий")
        return multiply(inverse(rgbToXYZ(rec709)), rgbToXYZ(primaries))
    }

    // MARK: Внутренности

    private static let slog3Joint = 171.2102946929
    private static let slog2Joint = 0.030001222851889303
    private static let kneeLevel = 0.6

    /// Матрица «линейный RGB охвата → XYZ»: столбцы — основные цвета при Y = 1, каждый с таким весом, чтобы (1; 1; 1)
    /// дало белую точку.
    private static func rgbToXYZ(_ primaries: [(x: Double, y: Double)]) -> [[Double]] {
        func xyz(_ p: (x: Double, y: Double)) -> [Double] { [p.x / p.y, 1, (1 - p.x - p.y) / p.y] }
        let columns = primaries.map(xyz)
        let basis = (0..<3).map { row in (0..<3).map { column in columns[column][row] } }
        let weights = multiply(inverse(basis), xyz(d65))
        return (0..<3).map { row in (0..<3).map { column in basis[row][column] * weights[column] } }
    }

    private static func multiply(_ a: [[Double]], _ b: [[Double]]) -> [[Double]] {
        (0..<3).map { i in (0..<3).map { j in a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }
    }

    private static func multiply(_ a: [[Double]], _ v: [Double]) -> [Double] {
        (0..<3).map { i in a[i][0] * v[0] + a[i][1] * v[1] + a[i][2] * v[2] }
    }

    /// Обратная матрица 3×3 через алгебраические дополнения.
    private static func inverse(_ m: [[Double]]) -> [[Double]] {
        let (a, b, c) = (m[0][0], m[0][1], m[0][2])
        let (d, e, f) = (m[1][0], m[1][1], m[1][2])
        let (g, h, i) = (m[2][0], m[2][1], m[2][2])
        let det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        precondition(abs(det) > 1e-12, "основные цвета на одной прямой: матрицы нет")
        return [
            [(e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det],
            [(f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det],
            [(d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det],
        ]
    }
}
