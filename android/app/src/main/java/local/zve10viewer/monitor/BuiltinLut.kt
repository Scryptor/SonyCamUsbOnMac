package local.zve10viewer.monitor

/**
 * Встроенные LUT для превью S-Log и HLG: код камеры → привычная картинка Rec.709. Считаются сами, по открытым формулам
 * (`SonyCurves`), а не берутся готовыми файлами. Значения — те же, что у `MonitorAssist.LutMode`.
 *
 * @property value значение в настройках и API
 * @property label подпись в плитке «LUT» и в «Авто: …»
 */
enum class BuiltinLut(val value: String, val label: String) {
    /** S-Log3 · S-Gamut3.Cine */
    slog3cine("slog3cine", "S-Log3 Cine"),

    /** S-Log3 · S-Gamut3 */
    slog3("slog3", "S-Log3"),

    /** S-Log2 · S-Gamut (основные цвета те же, что у S-Gamut3) */
    slog2("slog2", "S-Log2"),

    /** HLG · BT.2020 */
    hlg("hlg", "HLG");

    /**
     * Точный путь для одного цвета. Вход — `v = Y′/255` каждого канала превью (код камеры/1023), выход — экран Rec.709,
     * 0…1:
     * 1. кривая → отражение сцены `L` по каналам;
     * 2. матрица охвата → Rec.709;
     * 3. мягкое плечо (до колена 0,6 как есть, света меньше нуля нет);
     * 4. кодирование BT.709 с обрезкой 0…1.
     */
    fun transform(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val out = DoubleArray(3)
        transformInto(r, g, b, out)
        return Triple(out[0], out[1], out[2])
    }

    /** Куб `size³` из точного пути (по умолчанию 33³): по осям — `0, 1/(size − 1), … 1`. */
    fun make(size: Int = 33): ColorLut {
        val n = maxOf(size, 2)
        val values = FloatArray(n * n * n * 3)
        val last = (n - 1).toDouble()
        val out = DoubleArray(3)
        var i = 0
        for (bi in 0 until n) {
            for (gi in 0 until n) {
                for (ri in 0 until n) {
                    transformInto(ri / last, gi / last, bi / last, out)
                    values[i++] = out[0].toFloat()
                    values[i++] = out[1].toFloat()
                    values[i++] = out[2].toFloat()
                }
            }
        }
        return ColorLut(n, values)
    }

    // внутренности

    private fun transformInto(r: Double, g: Double, b: Double, out: DoubleArray) {
        val lr = linear(r)
        val lg = linear(g)
        val lb = linear(b)
        val m = matrix
        out[0] = encode(m[0] * lr + m[1] * lg + m[2] * lb)
        out[1] = encode(m[3] * lr + m[4] * lg + m[5] * lb)
        out[2] = encode(m[6] * lr + m[7] * lg + m[8] * lb)
    }

    private fun linear(v: Double): Double = when (this) {
        slog3cine, slog3 -> SonyCurves.slog3ToLinear(v)
        slog2 -> SonyCurves.slog2ToLinear(v)
        hlg -> SonyCurves.hlgToLinear(v)
    }

    /** Матрица охвата → Rec.709 по строкам, девять чисел. Считается один раз. */
    private val matrix: DoubleArray
        get() = when (this) {
            slog3cine -> cineMatrix
            slog3, slog2 -> sGamut3Matrix
            hlg -> bt2020Matrix
        }
}

private fun encode(l: Double): Double = SonyCurves.bt709(SonyCurves.shoulder(l))

private fun flatMatrix(primaries: List<Chromaticity>): DoubleArray =
    SonyCurves.matrixTo709(primaries).flatten().toDoubleArray()

private val cineMatrix = flatMatrix(SonyCurves.sGamut3Cine)
private val sGamut3Matrix = flatMatrix(SonyCurves.sGamut3)
private val bt2020Matrix = flatMatrix(SonyCurves.bt2020)
