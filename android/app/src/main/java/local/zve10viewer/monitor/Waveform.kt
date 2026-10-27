package local.zve10viewer.monitor

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Волновая форма — как у мониторов: по горизонтали — столбцы кадра слева направо, по вертикали — яркость точек этого
 * столбца, снизу чёрное, сверху белое. Яркость (Rec. 709, как у гистограммы) или RGB-парад — три такие картинки рядом, для
 * красного, зелёного и синего. Считается по всему кадру превью. Те же числа, что у программы для Mac.
 */
object Waveform {
    enum class Mode(val title: String) { luma("Яркость"), parade("RGB") }

    /** Строк по яркости: по два уровня из 256 на строку. */
    const val ROWS = 128
    /** Столбцов у яркости; у парада — по столько же на канал, сложенных втрое уже. */
    const val LUMA_COLUMNS = 256
    const val PARADE_COLUMNS = 128

    /**
     * Сколько точек кадра попало в каждую клетку: строки снизу вверх (0 — чёрное), столбцы слева направо; у парада —
     * сначала красный, потом зелёный и синий. samplesPerColumn — сколько точек кадра на столбец одного канала.
     */
    class Counts(val mode: Mode, val columns: Int, val counts: IntArray, val samplesPerColumn: Int) {
        fun count(column: Int, row: Int) = counts[row * columns + column]

        /** Самый тёмный и самый яркий уровень (0…255), где есть точки; null — пусто. */
        val levelRange: Pair<Int, Int>?
            get() {
                var low = -1
                var high = -1
                for (row in 0 until ROWS) {
                    val start = row * columns
                    if ((start until start + columns).any { counts[it] > 0 }) {
                        if (low < 0) low = row
                        high = row
                    }
                }
                return if (low < 0) null else Pair(low * 256 / ROWS, (high + 1) * 256 / ROWS - 1)
            }
    }

    /** Подсчёт по кадру. argb — пиксели Android (0xAARRGGBB), width × height. */
    fun counts(argb: IntArray, width: Int, height: Int, mode: Mode): Counts {
        val perChannel = if (mode == Mode.luma) LUMA_COLUMNS else PARADE_COLUMNS
        val columns = if (mode == Mode.luma) LUMA_COLUMNS else PARADE_COLUMNS * 3
        val counts = IntArray(columns * ROWS)
        if (width <= 0 || height <= 0 || argb.size < width * height) return Counts(mode, columns, counts, 1)
        val column = IntArray(width) { min(it * perChannel / width, perChannel - 1) }
        for (y in 0 until height) {
            var i = y * width
            for (x in 0 until width) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                val col = column[x]
                if (mode == Mode.luma) {
                    val v = min((218 * r + 732 * g + 74 * b) shr 10, 255)
                    counts[(v shr 1) * columns + col]++
                } else {
                    counts[(r shr 1) * columns + col]++
                    counts[(g shr 1) * columns + perChannel + col]++
                    counts[(b shr 1) * columns + 2 * perChannel + col]++
                }
                i++
            }
        }
        return Counts(mode, columns, counts, max(1, width * height / perChannel))
    }

    /** Яркость следа в клетке, 0…1: корень из доли точек столбца, полная при 5 % столбца; одна точка — уже заметна. */
    fun intensity(count: Int, samplesPerColumn: Int): Double =
        if (count <= 0) 0.0 else max(0.18, min(1.0, sqrt(count / (samplesPerColumn * 0.05))))

    /** Картинка следа для Bitmap.setPixels: ARGB, строки сверху вниз — сверху белое. Яркость — белым, парад — своим цветом. */
    fun image(counts: Counts): IntArray {
        val columns = counts.columns
        val pixels = IntArray(columns * ROWS)
        val perChannel = if (counts.mode == Mode.luma) columns else PARADE_COLUMNS
        val tints = if (counts.mode == Mode.luma) listOf(Triple(0.92, 1.0, 0.92))
        else listOf(Triple(1.0, 0.32, 0.3), Triple(0.35, 1.0, 0.4), Triple(0.35, 0.55, 1.0))
        for (row in 0 until ROWS) {
            val y = ROWS - 1 - row
            for (x in 0 until columns) {
                val a = intensity(counts.count(x, row), counts.samplesPerColumn)
                if (a <= 0) continue
                // цвет не умножен на прозрачность: Bitmap.setPixels ждёт именно такой
                val (r, g, b) = tints[min(x / perChannel, tints.size - 1)]
                pixels[y * columns + x] = ((a * 255).roundToInt() shl 24) or ((r * 255).roundToInt() shl 16) or
                    ((g * 255).roundToInt() shl 8) or (b * 255).roundToInt()
            }
        }
        return pixels
    }
}
