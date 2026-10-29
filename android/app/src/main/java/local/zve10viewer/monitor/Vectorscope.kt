package local.zve10viewer.monitor

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Вектороскоп: куда ложатся цвета кадра на плоскости цветоразностных Cb и Cr (Rec. 709). Серый — в середине, насыщенный
 * цвет — к краю в своём направлении, светлая кожа — вдоль линии под углом 123°.
 *
 * Плоскость — сетка 128×128 корзин, Cb идёт вправо, Cr вверх (строка 0 — вверху, там Cr наибольший). Считается по каждой
 * второй точке кадра по обеим осям: при 1024×576 это ≈147 тыс. отсчётов. Край плоскости при ×1 — 0,5: туда ложится
 * полностью насыщенный цвет; при ×2 — 0,25, так видны бледные цвета лога. Что за краем, в счётчики не попадает.
 *
 * Те же числа, что у программы для Mac. Кадр — пиксели Android (0xAARRGGBB, как у Bitmap.getPixels), строка кадра занимает
 * ровно width точек; массив может быть длиннее кадра — хвост не читается.
 */
object Vectorscope {
    /** Корзин по каждой оси: плоскость — 128×128. */
    const val BINS = 128

    /** Линия тона кожи: угол от оси +Cb против часовой стрелки, градусы. */
    const val SKIN_ANGLE = 123.0

    /** Цветоразностные Cb и Cr цвета; оба лежат в −0,5…0,5. */
    data class Chroma(val cb: Double, val cr: Double)

    /** Корзина: x растёт вправо вместе с Cb, y = 0 вверху (Cr наибольший). */
    data class Bin(val x: Int, val y: Int)

    /** Метка цвета на плоскости: имя и Cb, Cr. */
    data class Target(val name: String, val cb: Double, val cr: Double)

    /** Точка на панели: доли её ширины и высоты, y вниз. Середина — (0,5; 0,5). */
    data class PanelPoint(val x: Double, val y: Double)

    /** Метка полосы 75 %, которую видно на панели: сама метка и её место. */
    data class Mark(val target: Target, val at: PanelPoint)

    /** Насколько корзин от корзины метки вокруг неё считаются «попаданием в метку» (окно 5 × 5). */
    const val HIT_RADIUS = 2

    // Rec. 709: Y = 0,2126R + 0,7152G + 0,0722B; Cb = (B − Y)/1,8556; Cr = (R − Y)/1,5748.
    private const val KR = 0.2126
    private const val KG = 0.7152
    private const val KB = 0.0722
    private const val CB_DIVISOR = 1.8556
    private const val CR_DIVISOR = 1.5748

    /** Допуск на шум округления, в корзинах: цвет ровно на краю (у синего Cb = 0,5) не должен выпасть за него. */
    private const val EDGE_TOLERANCE = 1e-9

    // плоскость

    /**
     * Cb и Cr по Rec. 709 для цвета r, g, b (0…1); оба лежат в −0,5…0,5.
     *
     * Это те же `Cb = (B − Y)/1,8556` и `Cr = (R − Y)/1,5748`, но через разности каналов: коэффициенты яркости в сумме дают
     * 1, поэтому `B − Y = 0,2126(B − R) + 0,7152(B − G)`, а `R − Y = 0,7152(R − G) + 0,0722(R − B)`. У серого (R = G = B)
     * разности — ровно нули, а через `Y` в них остался бы шум округления в последнем знаке: серый кадр попадал бы то в одну,
     * то в соседнюю корзину в зависимости от уровня.
     */
    fun chroma(r: Double, g: Double, b: Double): Chroma = Chroma(cbOf(r, g, b), crOf(r, g, b))

    // по отдельности и без объекта: горячий цикл `counts` не должен создавать по объекту на точку
    private fun cbOf(r: Double, g: Double, b: Double) = (KR * (b - r) + KG * (b - g)) / CB_DIVISOR

    private fun crOf(r: Double, g: Double, b: Double) = (KG * (r - g) + KB * (r - b)) / CR_DIVISOR

    /** Корзины на единицу Cb или Cr: плоскость шириной 1/zoom занимает все 128. zoom меньше 1 — как 1. */
    private fun binsPerUnit(zoom: Int): Double = BINS.toDouble() * max(zoom, 1)

    /** Номер корзины (строка · 128 + столбец) для Cb и Cr; −1 — за краем (и для не-числа: сравнения с ним ложны). */
    private fun cell(cb: Double, cr: Double, perUnit: Double): Int {
        val half = (BINS / 2).toDouble()
        val fx = cb * perUnit + half
        val fy = half - cr * perUnit
        val low = -EDGE_TOLERANCE
        val high = BINS + EDGE_TOLERANCE
        if (fx >= low && fx <= high && fy >= low && fy <= high) {
            // toInt() отбрасывает дробь к нулю: чуть меньше нуля — всё ещё корзина 0; ровно на краю — крайняя корзина
            return min(fy.toInt(), BINS - 1) * BINS + min(fx.toInt(), BINS - 1)
        }
        return -1
    }

    /**
     * Корзина для Cb и Cr: x растёт вправо вместе с Cb, y = 0 вверху (Cr наибольший). null — за краем: при ×1 это |Cb| или
     * |Cr| больше 0,5, при ×2 — больше 0,25. zoom меньше 1 считается за 1.
     */
    fun bin(cb: Double, cr: Double, zoom: Int): Bin? {
        val index = cell(cb, cr, binsPerUnit(zoom))
        return if (index < 0) null else Bin(index % BINS, index / BINS)
    }

    /** Метки цветных полос 75 %: красный, пурпурный, синий, голубой, зелёный, жёлтый — по тем же формулам, что точки кадра. */
    val targets: List<Target> = listOf(
        bar("R", 0.75, 0.0, 0.0), bar("Mg", 0.75, 0.0, 0.75), bar("B", 0.0, 0.0, 0.75),
        bar("Cy", 0.0, 0.75, 0.75), bar("G", 0.0, 0.75, 0.0), bar("Yl", 0.75, 0.75, 0.0),
    )

    private fun bar(name: String, r: Double, g: Double, b: Double): Target {
        val c = chroma(r, g, b)
        return Target(name, c.cb, c.cr)
    }

    // панель

    /**
     * Где точка плоскости (Cb, Cr) лежит на панели: доли её ширины и высоты, y вниз. Плоскость — квадрат |Cb|, |Cr| ≤ 0,5/zoom
     * на всю панель, середина — серый. Это те же числа, что у [bin]: метка на сетке и корзина с таким цветом — одно место.
     * Что за краем плоскости (метки 75 % при ×2), выходит за 0…1. zoom меньше 1 считается за 1.
     */
    fun plane(cb: Double, cr: Double, zoom: Int): PanelPoint {
        val perUnit = max(zoom, 1).toDouble()
        return PanelPoint(0.5 + cb * perUnit, 0.5 - cr * perUnit)
    }

    /**
     * Конец линии тона кожи на круге панели (радиус 0,5): из середины под углом [SKIN_ANGLE] от +Cb против часовой стрелки.
     * Круг — край плоскости при любом масштабе, поэтому линия от масштаба не зависит.
     */
    val skinLineEnd: PanelPoint = run {
        val angle = SKIN_ANGLE * PI / 180
        PanelPoint(0.5 + 0.5 * cos(angle), 0.5 - 0.5 * sin(angle))
    }

    /**
     * Метки полос 75 %, которые лежат на панели при этом масштабе, — с местом. При ×2 их нет: полосы 75 % дальше края 0,25, и
     * метка там была бы вне панели.
     */
    fun visibleTargets(zoom: Int): List<Mark> = targets.map { Mark(it, plane(it.cb, it.cr, zoom)) }
        .filter { it.at.x in 0.0..1.0 && it.at.y in 0.0..1.0 }

    // подсчёт по кадру

    /** Значение канала 0…255 → 0…1, один раз на программу, а не делением на каждую точку. */
    private val unit = DoubleArray(256) { it / 255.0 }

    /**
     * Сколько отсчётов кадра попало в каждую корзину: `BINS²` чисел, строки сверху вниз (индекс = y · 128 + x). Берётся каждая
     * вторая точка кадра по обеим осям — с чётным x и чётным y. argb — пиксели Android (0xAARRGGBB), width × height, строка
     * ровно width точек; альфа не читается. Кадр, которого нет или который короче своих размеров, даёт нули.
     */
    fun counts(argb: IntArray, width: Int, height: Int, zoom: Int): IntArray {
        val counts = IntArray(BINS * BINS)
        // произведение — в Long: у нелепых размеров оно переполнило бы Int и проверка длины пропустила бы такой кадр
        if (width <= 0 || height <= 0 || argb.size.toLong() < width.toLong() * height) return counts
        val perUnit = binsPerUnit(zoom)
        val level = unit
        var y = 0
        while (y < height) {
            var i = y * width
            var x = 0
            while (x < width) {
                val p = argb[i]
                val r = level[(p shr 16) and 0xFF]
                val g = level[(p shr 8) and 0xFF]
                val b = level[p and 0xFF]
                val index = cell(cbOf(r, g, b), crOf(r, g, b), perUnit)
                if (index >= 0) counts[index]++
                // следующая точка через одну
                i += 2
                x += 2
            }
            y += 2
        }
        return counts
    }

    // картинка и пик

    /**
     * Картинка точек для Bitmap.setPixels: ARGB, `BINS²` точек, строки сверху вниз, как корзины. Пустая корзина — прозрачный
     * ноль (альфа 0), самая полная — непрозрачная (альфа 255), между ними яркость `log(1 + n) / log(1 + max)` — в альфе.
     * Цвет — оттенок самой корзины (см. `hue`): видно, какой цвет где. В отличие от Mac, цвет НЕ умножен на альфу:
     * Bitmap.setPixels ждёт именно такие числа и умножает сам. Счётчики не того размера или пустые дают прозрачную картинку.
     *
     * Цвет зависит только от места корзины на панели, не от масштаба: при ×2 оттенок тот же, насыщенность условная.
     */
    fun image(counts: IntArray): IntArray {
        val pixels = IntArray(BINS * BINS)
        if (counts.size != BINS * BINS) return pixels
        var most = 0
        for (n in counts) if (n > most) most = n
        if (most <= 0) return pixels
        val top = ln(1.0 + most)
        for (index in counts.indices) {
            val n = counts[index]
            if (n <= 0) continue
            // самая полная корзина — ровно 1: x / x
            val brightness = min(ln(1.0 + n) / top, 1.0)
            pixels[index] = (roundHalfUp(brightness * 255) shl 24) or hue(index % BINS, index / BINS)
        }
        return pixels
    }

    /**
     * Цвет точек корзины (0xRRGGBB): яркость 0,6 и Cb/Cr середины корзины (как при ×1), обратно в RGB по тем же формулам
     * Rec. 709, каждый канал обрезан до 0…1. Зелёный считается без обрезки красного и синего — обратное преобразование точное.
     */
    private fun hue(x: Int, y: Int): Int {
        val cb = (x + 0.5) / BINS - 0.5
        val cr = 0.5 - (y + 0.5) / BINS
        val luma = 0.6
        val r = luma + CR_DIVISOR * cr
        val b = luma + CB_DIVISOR * cb
        val g = (luma - KR * r - KB * b) / KG
        return (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    private fun channel(v: Double): Int = roundHalfUp(min(max(v, 0.0), 1.0) * 255)

    /**
     * Середина самой полной корзины, в единицах Cb и Cr (при ×2 — с поправкой на масштаб). Если полных несколько — первая
     * сверху слева. null — счётчики пусты или не того размера.
     */
    fun peak(counts: IntArray, zoom: Int): Chroma? {
        if (counts.size != BINS * BINS) return null
        var best = -1
        var most = 0
        for (index in counts.indices) {
            if (counts[index] > most) {
                best = index
                most = counts[index]
            }
        }
        if (best < 0) return null
        val scale = max(zoom, 1).toDouble()
        val n = BINS.toDouble()
        return Chroma(((best % BINS + 0.5) / n - 0.5) / scale, (0.5 - (best / BINS + 0.5) / n) / scale)
    }

    /**
     * Сколько отсчётов лежит в пределах [HIT_RADIUS] корзин от каждой из шести меток (в порядке [targets]); метка за краем
     * плоскости (при ×2) и счётчики не того размера дают 0. Окно обрезается по сетке. По этим числам проверяют вектороскоп без
     * снимка экрана: на цветных полосах 75 % при ×1 у всех шести метки отсчётов больше нуля.
     */
    fun targetHits(counts: IntArray, zoom: Int): IntArray {
        val hits = IntArray(targets.size)
        if (counts.size != BINS * BINS) return hits
        for ((i, target) in targets.withIndex()) {
            val bin = bin(target.cb, target.cr, zoom) ?: continue
            var sum = 0
            for (y in max(0, bin.y - HIT_RADIUS)..min(BINS - 1, bin.y + HIT_RADIUS)) {
                for (x in max(0, bin.x - HIT_RADIUS)..min(BINS - 1, bin.x + HIT_RADIUS)) sum += counts[y * BINS + x]
            }
            hits[i] = sum
        }
        return hits
    }

    /**
     * Строка для logcat (тег ZVAssist): масштаб, где самая полная корзина и сколько отсчётов у каждой метки. Раз в две секунды,
     * пока вектороскоп включён.
     */
    fun report(counts: IntArray, zoom: Int): String {
        val peak = peak(counts, zoom)
        val hits = targetHits(counts, zoom)
        val where = if (peak == null) "пик —" else String.format(Locale.ROOT, "пик Cb=%.3f Cr=%.3f", peak.cb, peak.cr)
        val marks = targets.indices.joinToString(" ") { "${targets[it].name}=${hits[it]}" }
        return "вектороскоп ×${max(zoom, 1)}: $where, метки $marks"
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
