package local.zve10viewer.settings

import kotlin.math.roundToInt

/** Гистограмма яркости кадра: сколько точек приходится на каждый уровень яркости. */
object LumaHistogram {
    /** Сколько точек на каждом из 256 уровней яркости. argb — пиксели в формате Android (0xAARRGGBB). */
    fun levels(argb: IntArray): IntArray {
        val levels = IntArray(256)
        for (p in argb) {
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // яркость по Rec. 709 в целых числах (коэффициенты ×1024)
            levels[minOf((218 * r + 732 * g + 74 * b) shr 10, 255)]++
        }
        return levels
    }

    /**
     * Уровни яркости по сетке columns × rows точек кадра из массива, который уже есть: из каждой клетки берётся точка в её
     * середине, без сглаживания — как у уменьшенной копии кадра, которую рисует гистограмма по Bitmap. Так помощь, когда кадр
     * уже разложен в точки (после прохода картинки), не тянет его в Bitmap второй раз. Без новых массивов: уровни ложатся в
     * [into] (256 чисел, сначала обнуляются) и он же возвращается.
     *
     * @param argb точки кадра по строкам, `0xAARRGGBB`; массив может быть больше кадра, хвост не читается
     */
    fun sampledLevels(argb: IntArray, width: Int, height: Int, into: IntArray, columns: Int = 256, rows: Int = 144): IntArray {
        java.util.Arrays.fill(into, 0, 256, 0)
        if (width <= 0 || height <= 0 || columns <= 0 || rows <= 0) return into
        for (j in 0 until rows) {
            val row = ((2 * j + 1) * height / (2 * rows)) * width
            for (i in 0 until columns) {
                val p = argb[row + (2 * i + 1) * width / (2 * columns)]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                into[minOf((218 * r + 732 * g + 74 * b) shr 10, 255)]++
            }
        }
        return into
    }

    /**
     * Столбцы для графика: уровни от чёрного до белого (`whiteLevel`), сложенные в count групп; что ярче белого —
     * в последний столбец. Так пересвет всегда у правого края.
     */
    fun binsOf(levels: IntArray, count: Int = 64, whiteLevel: Int = 255): IntArray {
        val bins = IntArray(maxOf(count, 0))
        if (count <= 0 || whiteLevel <= 0) return bins
        levels.forEachIndexed { level, points -> bins[minOf(level * count / (whiteLevel + 1), count - 1)] += points }
        return bins
    }

    /** argb — пиксели в формате Android (0xAARRGGBB); count — число столбцов гистограммы. */
    fun bins(argb: IntArray, count: Int = 64): IntArray = binsOf(levels(argb), count)
}

/**
 * Что видно по гистограмме: как точки делятся между тенями, полутонами и светами, сколько их ушло в чёрное
 * (провалы) и в белое (пересвет), и средняя яркость кадра. Всё — в процентах, шкала — от чёрного до белого.
 */
data class LumaStats(
    /** Точек в тенях — первой трети шкалы. */
    val shadows: Double,
    /** Точек в полутонах — средней трети. */
    val midtones: Double,
    /** Точек в светах — последней трети. */
    val highlights: Double,
    /** Почти чёрных точек (уровни 0–4): детали в тенях потеряны. */
    val clippedShadows: Double,
    /** Точек на белом уровне: пересвет. */
    val clippedHighlights: Double,
    /** Средняя яркость, % шкалы. */
    val mean: Double,
) {
    companion object {
        /** levels — 256 уровней, как отдаёт `LumaHistogram.levels`; whiteLevel — уровень белого. null, если точек нет. */
        fun of(levels: IntArray, whiteLevel: Int = 255): LumaStats? {
            val total = levels.sum()
            if (total <= 0 || whiteLevel <= 0) return null
            fun share(include: (Int) -> Boolean): Double {
                var points = 0
                levels.forEachIndexed { level, count -> if (include(level)) points += count }
                return points * 100.0 / total
            }
            val third = (whiteLevel + 1) / 3.0
            var weighted = 0L
            levels.forEachIndexed { level, count -> weighted += minOf(level, whiteLevel).toLong() * count }
            return LumaStats(
                shadows = share { it < third },
                midtones = share { it >= third && it < 2 * third },
                highlights = share { it >= 2 * third },
                clippedShadows = share { it <= 4 },
                // пересвеченное место ложится столбиком ровно на белый уровень; на уровень ниже — его края после сжатия JPEG
                clippedHighlights = share { it >= whiteLevel - 1 },
                mean = weighted.toDouble() / total / whiteLevel * 100,
            )
        }

        /**
         * Уровень белого в картинке live view. В фото — 255. В видео камера отдаёт белое на 234 (пересвеченное окно
         * ложится столбиком ровно на 234), но если ярче 240 больше 0,1 % точек — у этого профиля шкала полная: 255.
         */
        fun whiteLevel(levels: IntArray, video: Boolean): Int {
            if (!video) return 255
            val total = levels.sum()
            var above = 0
            for (level in 241..255) above += levels[level]
            return if (total > 0 && above.toDouble() / total > 0.001) 255 else 234
        }

        /** С какой доли точек на краю шкалы выделяем провалы или пересвет. */
        fun isClipped(percent: Double) = percent >= 1

        /** Доля зоны — целые проценты: «38%». */
        fun share(percent: Double) = "${percent.roundToInt()}%"

        /** Доля на краю — с десятой долей, пока она меньше 10 %: «0,4%», «13%». */
        fun clipping(percent: Double): String =
            if (percent == 0.0 || percent >= 9.95) share(percent) else String.format(java.util.Locale.ROOT, "%.1f%%", percent).replace('.', ',')
    }
}
