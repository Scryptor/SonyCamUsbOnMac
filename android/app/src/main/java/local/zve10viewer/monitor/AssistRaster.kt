package local.zve10viewer.monitor

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Растр помощи в кадре: по яркости видимой части кадра — картинка поверх него (ARGB, как Bitmap.setPixels) с false color,
 * зеброй и пикингом. Проценты — доли белого превью, как у гистограммы: в видео белое — 234, в фото — 255.
 *
 * При цифровом увеличении видимая часть кадра пересчитывается бикубически (Catmull-Rom) во столько раз крупнее, во сколько
 * увеличено: полосы зебры остаются одной ширины на экране, а пикинг — тонкой линией по середине края, а не квадратами по
 * 8 точек. Те же формулы и пороги, что у программы для Mac.
 */
object AssistRaster {
    /** Часть кадра, в точках кадра. */
    data class Region(val x: Double, val y: Double, val width: Double, val height: Double)

    /** Что считать: часть кадра и размер растра. */
    data class Plan(val region: Region, val width: Int, val height: Int) {
        /** Во сколько раз растр крупнее кадра. */
        val scale: Double get() = if (region.width > 0) width / region.width else 1.0
    }

    /** Растр помощи и сколько точек под зеброй и пикингом — проценты растра. */
    class Output(val pixels: IntArray, val width: Int, val height: Int, val zebraShare: Double, val peakingShare: Double)

    /** Растр больше кадра не больше чем в столько раз по числу точек: дольше считать, а глазу не нужно. */
    const val MAX_PIXELS_RATIO = 1.3

    /**
     * Видимая часть кадра (доли, как `ViewZoom.visibleRegion`) с запасом 10 % на сдвиг — и размер растра для неё: во столько
     * раз крупнее, во сколько увеличено.
     */
    fun plan(frameWidth: Int, frameHeight: Int, x: Double, y: Double, width: Double, height: Double, factor: Double): Plan {
        val w = frameWidth.toDouble()
        val h = frameHeight.toDouble()
        if (factor <= 1.0001 || frameWidth <= 0 || frameHeight <= 0) return Plan(Region(0.0, 0.0, w, h), frameWidth, frameHeight)
        val mx = width * 0.1
        val my = height * 0.1
        val x0 = max(0.0, x - mx)
        val y0 = max(0.0, y - my)
        val x1 = min(1.0, x + width + mx)
        val y1 = min(1.0, y + height + my)
        val region = Region(x0 * w, y0 * h, max(1.0, (x1 - x0) * w), max(1.0, (y1 - y0) * h))
        var k = factor
        val pixels = region.width * region.height * k * k
        val limit = w * h * MAX_PIXELS_RATIO
        if (pixels > limit) k *= sqrt(limit / pixels)
        k = max(k, 1.0)
        return Plan(region, max(1, (region.width * k).roundToInt()), max(1, (region.height * k).roundToInt()))
    }

    /** Яркость по Rec. 709, как у гистограммы, — по числу 0…255 на точку. argb — пиксели Android (0xAARRGGBB). */
    fun luma(argb: IntArray, count: Int = argb.size): IntArray {
        val out = IntArray(count)
        for (i in 0 until count) {
            val p = argb[i]
            out[i] = min((218 * ((p shr 16) and 0xFF) + 732 * ((p shr 8) and 0xFF) + 74 * (p and 0xFF)) shr 10, 255)
        }
        return out
    }

    /** Часть кадра в размер растра: бикубически (Catmull-Rom). Растр того же размера, что целая часть кадра, — просто копия. */
    fun resample(luma: IntArray, width: Int, height: Int, plan: Plan): IntArray {
        val r = plan.region
        val ow = plan.width
        val oh = plan.height
        if (width <= 0 || height <= 0 || luma.size < width * height || ow <= 0 || oh <= 0) return IntArray(0)
        val integral = r.x == floor(r.x) && r.y == floor(r.y) && r.width == floor(r.width) && r.height == floor(r.height)
        if (integral && ow == r.width.toInt() && oh == r.height.toInt()) {
            val x0 = r.x.toInt()
            val y0 = r.y.toInt()
            if (x0 == 0 && y0 == 0 && ow == width && oh == height) return luma
            val out = IntArray(ow * oh)
            for (y in 0 until oh) System.arraycopy(luma, (y0 + y) * width + x0, out, y * ow, ow)
            return out
        }
        // по столбцам и строкам: откуда брать и с какими весами
        val xs = Taps(ow, r.x, r.width / ow)
        val ys = Taps(oh, r.y, r.height / oh)
        val rowLo = ys.index.minOrNull() ?: 0
        val rows = (ys.index.maxOrNull() ?: 0) + 3 - rowLo + 1
        val tmp = FloatArray(rows * ow)
        for (row in 0 until rows) {
            val line = (rowLo + row).coerceIn(0, height - 1) * width
            for (ox in 0 until ow) {
                val base = xs.index[ox]
                val w = ox * 4
                var sum = 0f
                for (j in 0 until 4) sum += xs.weight[w + j] * luma[line + (base + j).coerceIn(0, width - 1)]
                tmp[row * ow + ox] = sum
            }
        }
        val out = IntArray(ow * oh)
        for (oy in 0 until oh) {
            val base = ys.index[oy] - rowLo
            val w = oy * 4
            for (ox in 0 until ow) {
                var sum = 0f
                for (j in 0 until 4) sum += ys.weight[w + j] * tmp[(base + j) * ow + ox]
                out[oy * ow + ox] = sum.roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    /** Для каждой точки растра — первая из четырёх точек кадра (с −1) и их веса Catmull-Rom. */
    private class Taps(count: Int, start: Double, step: Double) {
        val index = IntArray(count)
        val weight = FloatArray(count * 4)

        init {
            for (i in 0 until count) {
                val x = start + (i + 0.5) * step - 0.5
                val f = floor(x)
                val t = (x - f).toFloat()
                index[i] = f.toInt() - 1
                val t2 = t * t
                val t3 = t2 * t
                weight[i * 4] = (-t3 + 2 * t2 - t) / 2
                weight[i * 4 + 1] = (3 * t3 - 5 * t2 + 2) / 2
                weight[i * 4 + 2] = (-3 * t3 + 4 * t2 + t) / 2
                weight[i * 4 + 3] = (t3 - t2) / 2
            }
        }
    }

    // зебра и false color

    /** Попадает ли уровень яркости под зебру при белом `white`. */
    fun isZebra(level: Int, zebra: MonitorAssist.Zebra, white: Int): Boolean {
        val p = level * 100.0 / max(white, 1)
        return when (zebra) {
            MonitorAssist.Zebra.off -> false
            // пересвет — как у гистограммы: белый уровень и на единицу ниже (края после сжатия JPEG)
            MonitorAssist.Zebra.above100 -> level >= white - 1
            MonitorAssist.Zebra.above95 -> p >= 95
            MonitorAssist.Zebra.skin70 -> p in 65.0..75.0
        }
    }

    /**
     * Полосы false color: доли белого превью. Похоже на ARRI: фиолетовый — провал, синий — глубокие тени, зелёный —
     * средне-серый (18 % серого), розовый — светлая кожа (на ступень светлее серого), жёлтый — почти белое, красный —
     * пересвет. Что не попало в полосу — серым, своей яркостью. from…to — % белого, [от, до).
     */
    enum class FalseColorBand(val title: String, val from: Double, val to: Double, val rgb: Int) {
        crushed("провал", 0.0, 2.5, 0x9628C8),
        shadows("тени", 2.5, 8.0, 0x1E5AFF),
        middleGray("18 % серого", 38.0, 45.0, 0x28C846),
        skin("кожа", 52.0, 60.0, 0xFF7DB9),
        nearWhite("почти белое", 93.0, 99.0, 0xFFDC00),
        clipped("пересвет", 99.0, 1000.0, 0xFF1E1E),
    }

    /** В какую полосу false color попадает уровень; null — серым. */
    fun falseColorBand(level: Int, white: Int): FalseColorBand? {
        // пересвет — как у гистограммы и зебры 100 %; «почти белое» — всё, что ниже него, от 93 %
        if (level >= white - 1) return FalseColorBand.clipped
        val p = level * 100.0 / max(white, 1)
        if (p >= FalseColorBand.nearWhite.from) return FalseColorBand.nearWhite
        return FalseColorBand.entries.firstOrNull { p >= it.from && p < it.to }
    }

    fun peakingRgb(color: MonitorAssist.PeakingColor): Int = when (color) {
        MonitorAssist.PeakingColor.red -> 0xFF2828
        MonitorAssist.PeakingColor.yellow -> 0xFFE600
        MonitorAssist.PeakingColor.white -> 0xFFFFFF
        MonitorAssist.PeakingColor.blue -> 0x2896FF
    }

    // пикинг

    /**
     * Порог пикинга: насколько резко должна меняться яркость на соседних точках кадра (уровни из 255), чтобы край
     * подсветился. Чем выше уровень пикинга, тем ниже порог.
     */
    fun peakingThreshold(peaking: MonitorAssist.Peaking): Double = when (peaking) {
        MonitorAssist.Peaking.off -> Double.POSITIVE_INFINITY
        MonitorAssist.Peaking.low -> 44.0
        MonitorAssist.Peaking.mid -> 30.0
        MonitorAssist.Peaking.high -> 20.0
    }

    /**
     * Во сколько раз крутизна края в пересчитанном растре больше перепада на точку кадра, делённого на увеличение:
     * Catmull-Rom в середине ступеньки даёт наклон 1,25. Без поправки при увеличении подсвечивалось бы больше краёв, чем
     * без него. До 2× поправка растёт плавно.
     */
    fun zoomedSlope(scale: Double): Double = if (scale <= 1) 1.0 else 1 + 0.25 * min(scale - 1, 1.0)

    /**
     * Резкие края: перепад яркости между соседними точками (корень из суммы квадратов по x и по y) не меньше порога и не
     * меньше, чем у соседей поперёк края, — край остаётся линией в одну точку. scale — во сколько раз растр крупнее кадра:
     * перепад на точку растра во столько же раз меньше, порог тоже. Отдаёт маску: true — край.
     */
    fun peakingMask(luma: IntArray, width: Int, height: Int, threshold: Double, scale: Double): BooleanArray {
        val mask = BooleanArray(width * height)
        if (width <= 2 || height <= 2 || luma.size < width * height || threshold.isInfinite()) return mask
        val t = threshold * zoomedSlope(scale) / max(scale, 1.0)
        val t2 = ceil(t * t).toInt()
        val mag = IntArray(width * height)
        // поперёк края: 0 — по x, 1 — по y, 2 — по диагонали «\», 3 — по диагонали «/»
        val dirs = ByteArray(width * height)
        for (y in 0 until height - 1) {
            var i = y * width
            for (x in 0 until width - 1) {
                val c = luma[i]
                val dx = luma[i + 1] - c
                val dy = luma[i + width] - c
                mag[i] = dx * dx + dy * dy
                val ax = abs(dx)
                val ay = abs(dy)
                dirs[i] = (if (ax > 2 * ay) 0 else if (ay > 2 * ax) 1 else if ((dx > 0) == (dy > 0)) 2 else 3).toByte()
                i++
            }
        }
        for (y in 1 until height - 2) {
            var i = y * width + 1
            for (x in 1 until width - 2) {
                val v = mag[i]
                if (v >= t2) {
                    val a: Int
                    val b: Int
                    when (dirs[i].toInt()) {
                        0 -> { a = mag[i - 1]; b = mag[i + 1] }
                        1 -> { a = mag[i - width]; b = mag[i + width] }
                        2 -> { a = mag[i - width - 1]; b = mag[i + width + 1] }
                        else -> { a = mag[i - width + 1]; b = mag[i + width - 1] }
                    }
                    if (v >= a && v >= b) mask[i] = true
                }
                i++
            }
        }
        return mask
    }

    // растр целиком

    /**
     * Растр помощи по яркости видимой части кадра (уже в размере растра). scale — во сколько раз он крупнее кадра;
     * white — белый уровень превью. out — куда писать (размер width × height), если есть; null — считать нечего.
     */
    fun render(luma: IntArray, width: Int, height: Int, scale: Double, white: Int, assist: MonitorAssist,
               out: IntArray? = null): Output? {
        if (!assist.needsRaster || width <= 0 || height <= 0 || luma.size < width * height) return null
        // по уровню яркости: цвет false color (или прозрачно) и зебра
        val base = IntArray(256)
        val zebra = BooleanArray(256)
        for (level in 0 until 256) {
            if (assist.falseColor) {
                val rgb = falseColorBand(level, white)?.rgb ?: run {
                    val v = min(255, level * 255 / max(white, 1))
                    (v shl 16) or (v shl 8) or v
                }
                base[level] = rgb or 0xFF000000.toInt()
            }
            zebra[level] = isZebra(level, assist.zebra, white)
        }
        val peaks = if (assist.peaking == MonitorAssist.Peaking.off) null
        else peakingMask(luma, width, height, peakingThreshold(assist.peaking), scale)
        val peakColor = peakingRgb(assist.peakingColor) or 0xFF000000.toInt()
        val pixels = out?.takeIf { it.size >= width * height } ?: IntArray(width * height)
        var zebraCount = 0
        var peakCount = 0
        for (y in 0 until height) {
            var i = y * width
            for (x in 0 until width) {
                val level = luma[i]
                var px = base[level]
                if (zebra[level]) {
                    zebraCount++
                    // полосы наискось, по 4 точки растра: чёрные на 60 % поверх того, что под ними
                    if (((x + y) and 7) < 4) {
                        px = if (px == 0) 0x99000000.toInt() else {
                            val r = ((px shr 16) and 0xFF) * 102 / 255
                            val g = ((px shr 8) and 0xFF) * 102 / 255
                            val b = (px and 0xFF) * 102 / 255
                            0xFF000000.toInt() or (r shl 16) or (g shl 8) or b
                        }
                    }
                }
                if (peaks != null && peaks[i]) {
                    peakCount++
                    px = peakColor
                }
                pixels[i] = px
                i++
            }
        }
        val total = (width * height).toDouble()
        return Output(pixels, width, height, zebraCount * 100.0 / total, peakCount * 100.0 / total)
    }
}
