package local.zve10viewer.monitor

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Основной цвет охвата в координатах xy. */
data class Chromaticity(val x: Double, val y: Double)

/**
 * Кривые Sony и HLG, охваты и тон — всё, из чего считаются встроенные LUT. Формулы открытые (технические описания
 * S-Log2, S-Log3 и S-Gamut от Sony, BT.2100 и BT.709 от ITU); готовых LUT из других программ тут нет. Числа и порядок
 * действий — как у программы для Mac.
 *
 * Вход кривых — `v = Y′/255` канала превью, то есть код камеры `код/1023` (предположение спецификации: 18 % серого
 * в S-Log3 — код 420). Выход кривых — отражение сцены `L`: 18 % серого — 0,18, диффузный белый — 1.
 */
object SonyCurves {
    // кривая → отражение сцены

    /** S-Log3 → отражение сцены. Ниже кода 171,2102946929 — прямая, выше — степень десяти; код 95 — ноль. */
    fun slog3ToLinear(v: Double): Double {
        val c = v * 1023
        if (c >= SLOG3_JOINT) return 10.0.pow((c - 420) / 261.5) * 0.19 - 0.01
        return (c - 95) * 0.01125 / (SLOG3_JOINT - 95)
    }

    /** S-Log2 → отражение сцены (в кривой отражение считается от 90 % белого — отсюда `0,9·219/155`). */
    fun slog2ToLinear(v: Double): Double {
        val y = (v * 1023 - 64) / 876
        val x = if (y >= SLOG2_JOINT) 10.0.pow((y - 0.646596) / 0.432699) - 0.037584 else (y - SLOG2_JOINT) / 5
        return x * 0.9 * 219 / 155
    }

    /**
     * HLG (BT.2100) → отражение сцены; 75 % сигнала — диффузный белый, `L = 1`. Ниже чёрного (код 64) — ноль: квадрат
     * отрицательного сигнала был бы светом.
     */
    fun hlgToLinear(v: Double): Double {
        val signal = max((v * 1023 - 64) / 876, 0.0)
        val scene = if (signal <= 0.5) signal * signal / 3 else (exp((signal - 0.55991073) / 0.17883277) + 0.28466892) / 12
        return scene / 0.2647
    }

    // тон

    /** Мягкое плечо: до колена 0,6 свет как есть, выше он сжимается к 1 без излома (наклон в колене — 1). Света меньше нуля нет. */
    fun shoulder(l: Double): Double {
        if (!(l > 0)) return 0.0
        if (l <= KNEE_LEVEL) return l
        val room = 1 - KNEE_LEVEL
        return KNEE_LEVEL + room * (1 - exp(-(l - KNEE_LEVEL) / room))
    }

    /** Кодирование BT.709 (OETF): линейный свет → сигнал 0…1; выше 1 и ниже 0 обрезается. */
    fun bt709(l: Double): Double {
        if (!(l > 0)) return 0.0
        val v = if (l < 0.018) 4.5 * l else 1.099 * l.pow(0.45) - 0.099
        return min(v, 1.0)
    }

    // охваты

    /** Основные цвета охватов (красный, зелёный, синий) в координатах xy. Белая точка у всех — D65. */
    val sGamut3Cine: List<Chromaticity> = listOf(Chromaticity(0.766, 0.275), Chromaticity(0.225, 0.800), Chromaticity(0.089, -0.087))

    /** S-Gamut3 и S-Gamut — одни и те же основные цвета. */
    val sGamut3: List<Chromaticity> = listOf(Chromaticity(0.730, 0.280), Chromaticity(0.140, 0.855), Chromaticity(0.100, -0.050))

    val bt2020: List<Chromaticity> = listOf(Chromaticity(0.708, 0.292), Chromaticity(0.170, 0.797), Chromaticity(0.131, 0.046))

    /** Основные цвета Rec.709, к которому ведут все матрицы. */
    internal val rec709: List<Chromaticity> = listOf(Chromaticity(0.64, 0.33), Chromaticity(0.30, 0.60), Chromaticity(0.15, 0.06))
    internal val d65 = Chromaticity(0.3127, 0.3290)

    /**
     * Матрица 3×3 (по строкам): линейный RGB охвата с этими основными цветами → линейный RGB Rec.709. Белая точка D65 у
     * обоих, хроматической адаптации нет — строки в сумме дают 1 и серый остаётся серым.
     */
    fun matrixTo709(primaries: List<Chromaticity>): List<List<Double>> {
        require(primaries.size == 3) { "нужны три основных цвета: красный, зелёный, синий" }
        val matrix = multiply(inverse(rgbToXyz(rec709)), rgbToXyz(primaries))
        return matrix.map { row -> row.toList() }
    }

    // внутренности

    private const val SLOG3_JOINT = 171.2102946929
    private const val SLOG2_JOINT = 0.030001222851889303
    private const val KNEE_LEVEL = 0.6

    /**
     * Матрица «линейный RGB охвата → XYZ»: столбцы — основные цвета при Y = 1, каждый с таким весом, чтобы (1; 1; 1) дало
     * белую точку.
     */
    private fun rgbToXyz(primaries: List<Chromaticity>): Array<DoubleArray> {
        fun xyz(p: Chromaticity) = doubleArrayOf(p.x / p.y, 1.0, (1 - p.x - p.y) / p.y)
        val columns = primaries.map { xyz(it) }
        val basis = Array(3) { row -> DoubleArray(3) { column -> columns[column][row] } }
        val weights = multiply(inverse(basis), xyz(d65))
        return Array(3) { row -> DoubleArray(3) { column -> basis[row][column] * weights[column] } }
    }

    private fun multiply(a: Array<DoubleArray>, b: Array<DoubleArray>): Array<DoubleArray> =
        Array(3) { i -> DoubleArray(3) { j -> a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j] } }

    private fun multiply(a: Array<DoubleArray>, v: DoubleArray): DoubleArray =
        DoubleArray(3) { i -> a[i][0] * v[0] + a[i][1] * v[1] + a[i][2] * v[2] }

    /** Обратная матрица 3×3 через алгебраические дополнения. */
    private fun inverse(m: Array<DoubleArray>): Array<DoubleArray> {
        val (a, b, c) = m[0]
        val (d, e, f) = m[1]
        val (g, h, i) = m[2]
        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        require(abs(det) > 1e-12) { "основные цвета на одной прямой: матрицы нет" }
        return arrayOf(
            doubleArrayOf((e * i - f * h) / det, (c * h - b * i) / det, (b * f - c * e) / det),
            doubleArrayOf((f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det),
            doubleArrayOf((d * h - e * g) / det, (b * g - a * h) / det, (a * e - b * d) / det),
        )
    }
}
