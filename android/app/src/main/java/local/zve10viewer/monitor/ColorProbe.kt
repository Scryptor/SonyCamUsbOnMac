package local.zve10viewer.monitor

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Пипетка: яркость и цвет в одном месте кадра — среднее по квадрату 5×5 точек вокруг метки. Квадрат у края кадра
 * обрезается краем (в углу это 3×3), метка за кадром упирается в ближайшую точку кадра. Те же числа, что у программы для Mac.
 */
object ColorProbe {
    /** Где пипетка стоит при первом включении: в центре кадра, каким он виден. Место помнится до выхода из программы. */
    val CENTER = FramePoint(0.5, 0.5)

    /** Что показала пипетка. */
    data class Reading(
        /**
         * Яркость (Rec. 709, как у гистограммы) в процентах от белого превью, округлена до целого: белое — 234 в видео, 255
         * в фото и с LUT. Что ярче белого превью — больше 100.
         */
        val percent: Int,
        /** Среднее красное, зелёное и синее по квадрату, 0…255. */
        val r: Int,
        val g: Int,
        val b: Int,
        /** Зона false color для этой яркости — по той же таблице, что легенда и сама подсветка; null — между зонами. */
        val zone: AssistRaster.FalseColorBand?,
    ) {
        /** Две строки таблички у метки: «47 % · кожа» (без зоны — «47 %») и «R 182 · G 140 · B 121». */
        val lines: Pair<String, String>
            get() = Pair(if (zone != null) "$percent % · ${zone.title}" else "$percent %", "R $r · G $g · B $b")
    }

    /** Сколько точек в каждую сторону от метки: 2 — квадрат 5×5. */
    private const val REACH = 2

    /**
     * Что под меткой. argb — пиксели Android (0xAARRGGBB), width × height, строка ровно width точек; массив может быть
     * длиннее кадра. x, y — точка кадра в точках кадра (не в долях): точка, в которую попала координата, а не-число — точка 0;
     * из долей кадра — доля · width и доля · height. white — белый уровень превью (234 в видео, 255 в фото и с LUT). Кадр,
     * которого нет или который короче своих размеров, даёт нули.
     *
     * Яркость каждой точки — целая, `(218R + 732G + 74B) >> 10`, как у гистограммы и растра помощи; процент — среднее
     * яркости от белого, округлённое. Зона — та, которой false color красит этот уровень: берётся целый уровень, ближайший к
     * средней яркости, и тот же `AssistRaster.falseColorBand`. То есть по неокруглённому проценту уровня, а не по целому
     * числу на табличке: у плоского участка зона всегда совпадает с цветом подсветки, а «38 %» без зоны и «38 % · 18 %
     * серого» — разные уровни по обе стороны границы зоны.
     */
    fun read(argb: IntArray, width: Int, height: Int, x: Double, y: Double, white: Int): Reading {
        // произведение — в Long: у нелепых размеров оно переполнило бы Int и проверка длины пропустила бы такой кадр
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return Reading(0, 0, 0, 0, null)
        val cx = pixel(x, width)
        val cy = pixel(y, height)
        val x0 = max(cx - REACH, 0)
        val x1 = min(cx + REACH, width - 1)
        val y0 = max(cy - REACH, 0)
        val y1 = min(cy + REACH, height - 1)
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var sumLuma = 0
        for (row in y0..y1) {
            var i = row * width + x0
            for (col in x0..x1) {
                val p = argb[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                sumR += r
                sumG += g
                sumB += b
                sumLuma += (218 * r + 732 * g + 74 * b) shr 10
                i++
            }
        }
        val count = ((x1 - x0 + 1) * (y1 - y0 + 1)).toDouble()
        val luma = sumLuma / count
        // белого нет — как белое 1 (так же считает и подсветка), а не деление на ноль
        val percent = roundHalfUp(luma / max(white, 1) * 100)
        return Reading(
            percent, roundHalfUp(sumR / count), roundHalfUp(sumG / count), roundHalfUp(sumB / count),
            AssistRaster.falseColorBand(roundHalfUp(luma), white),
        )
    }

    /**
     * То же для метки, заданной долями кадра, **каким он виден на экране**: кадр помощи уже повёрнутый (зеркало, переворот), так что
     * доли те же, что на экране, и перевода в камеру не нужно. [at] за кадром — к краю.
     */
    fun readAt(argb: IntArray, width: Int, height: Int, at: FramePoint, white: Int): Reading =
        read(argb, width, height, at.x * width, at.y * height, white)

    /** Точка кадра, в которую попала координата (точка i занимает i…<i + 1). За кадром — ближайшая точка кадра, не-число — 0. */
    private fun pixel(value: Double, count: Int): Int {
        if (value.isNaN()) return 0
        return min(max(floor(value), 0.0), (count - 1).toDouble()).toInt()
    }

    /**
     * Ближайшее целое к x ≥ 0, при .5 — вверх, как `rounded()` в Swift: так же округляют и на Mac. kotlin.math.round
     * округлил бы 2,5 к чётному — до 2.
     */
    private fun roundHalfUp(x: Double): Int {
        val down = floor(x)
        return (if (x - down >= 0.5) down + 1 else down).toInt()
    }
}
