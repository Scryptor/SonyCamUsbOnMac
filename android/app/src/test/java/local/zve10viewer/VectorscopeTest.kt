package local.zve10viewer

import local.zve10viewer.monitor.Vectorscope
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln

/**
 * Вектороскоп — те же проверки, что у программы для Mac (VectorscopeTests.swift). Кадр — ARGB, как у Bitmap.getPixels,
 * а не RGBA-байты; картинка — ARGB для Bitmap.setPixels, то есть цвет не умножен на альфу.
 */
class VectorscopeTest {
    // помощники

    private fun near(a: Double, b: Double, eps: Double) = abs(a - b) <= eps

    /**
     * Случайные числа без сюрпризов — «seed 1», как в тестах программы для Mac: те же цвета в каждом прогоне и на каждой
     * платформе. Int переполняется по модулю 2³², как UInt32 в Swift.
     */
    private class Lcg {
        private var seed = 1

        /** 0 ≤ x < 1. */
        fun next(): Double {
            seed = seed * 1_664_525 + 1_013_904_223
            return (seed ushr 8) / 16_777_216.0
        }
    }

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun alpha(p: Int) = p ushr 24
    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /** Кадр одного цвета. */
    private fun flat(r: Int, g: Int, b: Int, width: Int = 64, height: Int = 36) = IntArray(width * height) { argb(r, g, b) }

    /** Корзина и сколько в ней. */
    private class Cell(val x: Int, val y: Int, val n: Int)

    /** Самая полная корзина; null — пусто. При равных — первая сверху слева. */
    private fun fullest(counts: IntArray): Cell? {
        var top = 0
        var at = -1
        for (i in counts.indices) if (counts[i] > top) {
            top = counts[i]
            at = i
        }
        return if (at < 0) null else Cell(at % Vectorscope.BINS, at / Vectorscope.BINS, top)
    }

    private fun fullestOrFail(counts: IntArray): Cell = fullest(counts) ?: throw AssertionError("в счётчиках пусто")

    /** Счётчики, где заполнены только перечисленные корзины. */
    private fun countsOf(vararg filled: Cell): IntArray {
        val counts = IntArray(Vectorscope.BINS * Vectorscope.BINS)
        for (cell in filled) counts[cell.y * Vectorscope.BINS + cell.x] = cell.n
        return counts
    }

    /** Расстояние от середины плоскости до середины корзины, в корзинах, по каждой оси. */
    private fun offset(cell: Cell): Pair<Double, Double> {
        val middle = Vectorscope.BINS / 2.0
        return Pair(abs(cell.x + 0.5 - middle), abs(cell.y + 0.5 - middle))
    }

    /**
     * Цвет корзины по справочным формулам BT.709 в обратную сторону, написанным отдельно от кода: Y = 0,6, Cb и Cr — середина
     * корзины при ×1; R = Y + 1,5748·Cr, B = Y + 1,8556·Cb, G = Y − 0,1873·Cb − 0,4681·Cr; всё обрезано до 0…1.
     */
    private fun hue(x: Int, y: Int): Triple<Double, Double, Double> {
        val cb = (x + 0.5) / 128 - 0.5
        val cr = 0.5 - (y + 0.5) / 128
        fun clamp(v: Double) = minOf(maxOf(v, 0.0), 1.0)
        return Triple(clamp(0.6 + 1.5748 * cr), clamp(0.6 - 0.1873 * cb - 0.4681 * cr), clamp(0.6 + 1.8556 * cb))
    }

    /** Целое от 0 до 255 из доли, от нуля при .5 — как в справочных числах. */
    private fun level(v: Double) = Math.floor(v * 255 + 0.5).toInt()

    @Test fun lcgIsTheSameSequenceAsOnTheMac() {
        // первые числа «seed 1» посчитаны на Swift (UInt32): иначе «те же цвета» на двух платформах были бы разными
        val random = Lcg()
        val want = listOf(0.23645550012588501, 0.36927062273025513, 0.50424200296401978, 0.70488321781158447, 0.050543606281280518)
        for (v in want) assertEquals(v, random.next(), 0.0)
    }

    // плоскость

    @Test fun vectorscopeKeepsItsConstants() {
        // 128×128 корзин; кожа — под углом 123° от оси +Cb
        assertEquals(128, Vectorscope.BINS)
        assertEquals(123.0, Vectorscope.SKIN_ANGLE, 0.0)
    }

    @Test fun chromaOfSeventyFivePercentRed() {
        // красный 75 %: Y = 0,15945; Cb = −Y/1,8556; Cr = (0,75 − Y)/1,5748 = 0,375
        val c = Vectorscope.chroma(0.75, 0.0, 0.0)
        assertEquals(-0.0859, c.cb, 1e-3)
        assertEquals(0.3750, c.cr, 1e-3)
    }

    @Test fun chromaOfGreyIsZero() {
        val c = Vectorscope.chroma(0.5, 0.5, 0.5)
        assertTrue(c.cb == 0.0)
        assertTrue(c.cr == 0.0)
    }

    @Test fun chromaOfEveryGreyLevelIsExactlyZero() {
        // не «почти ноль»: иначе серый кадр дрожал бы между соседними корзинами в зависимости от уровня
        for (level in 0..255) {
            val v = level / 255.0
            val c = Vectorscope.chroma(v, v, v)
            assertTrue("уровень $level: Cb = ${c.cb}", c.cb == 0.0)
            assertTrue("уровень $level: Cr = ${c.cr}", c.cr == 0.0)
        }
    }

    @Test fun chromaFollowsTheRec709Formulas() {
        // формулы из спецификации, в лоб: Y = 0,2126R + 0,7152G + 0,0722B, Cb = (B − Y)/1,8556, Cr = (R − Y)/1,5748
        val random = Lcg()
        repeat(500) {
            val r = random.next()
            val g = random.next()
            val b = random.next()
            val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
            val c = Vectorscope.chroma(r, g, b)
            assertEquals((b - y) / 1.8556, c.cb, 1e-12)
            assertEquals((r - y) / 1.5748, c.cr, 1e-12)
        }
    }

    @Test fun chromaOfFullySaturatedColoursReachTheEdgeOfTheSquare() {
        // красный и синий упираются в ±0,5 по своей оси
        val red = Vectorscope.chroma(1.0, 0.0, 0.0)
        assertEquals(-0.2126 / 1.8556, red.cb, 1e-12)
        assertEquals(0.5, red.cr, 1e-12)
        val blue = Vectorscope.chroma(0.0, 0.0, 1.0)
        assertEquals(0.5, blue.cb, 1e-12)
        assertEquals(-0.0722 / 1.5748, blue.cr, 1e-12)
    }

    @Test fun targetsAreTheSixSeventyFivePercentBars() {
        // красный, пурпурный, синий, голубой, зелёный, жёлтый — по кругу; числа посчитаны отдельно (Python, те же формулы)
        val names = listOf("R", "Mg", "B", "Cy", "G", "Yl")
        val cbs = listOf(-0.08593, 0.28907, 0.37500, 0.08593, -0.28907, -0.37500)
        val crs = listOf(0.37500, 0.34061, -0.03439, -0.37500, -0.34061, 0.03439)
        assertEquals(names, Vectorscope.targets.map { it.name })
        for ((i, target) in Vectorscope.targets.withIndex()) {
            assertEquals(cbs[i], target.cb, 1e-4)
            assertEquals(crs[i], target.cr, 1e-4)
        }
    }

    @Test fun targetsAreComputedByTheSameFormulas() {
        // не таблица чисел, а те же `chroma` от полос 75 %
        val bars = listOf(
            Triple(0.75, 0.0, 0.0), Triple(0.75, 0.0, 0.75), Triple(0.0, 0.0, 0.75),
            Triple(0.0, 0.75, 0.75), Triple(0.0, 0.75, 0.0), Triple(0.75, 0.75, 0.0),
        )
        assertEquals(bars.size, Vectorscope.targets.size)
        for ((target, bar) in Vectorscope.targets.zip(bars)) {
            val c = Vectorscope.chroma(bar.first, bar.second, bar.third)
            assertTrue(target.cb == c.cb)
            assertTrue(target.cr == c.cr)
        }
        // дополнительные цвета лежат напротив: R–Cy, Mg–G, B–Yl
        for ((a, b) in listOf(0 to 3, 1 to 4, 2 to 5)) {
            assertEquals(Vectorscope.targets[a].cb, -Vectorscope.targets[b].cb, 1e-12)
            assertEquals(Vectorscope.targets[a].cr, -Vectorscope.targets[b].cr, 1e-12)
        }
    }

    // корзины

    @Test fun binPutsTheCentreInTheMiddleOfTheGrid() {
        // 128 корзин — чётное число, середина плоскости лежит на стыке корзин 63 и 64; ровно ноль идёт в 64
        assertEquals(Vectorscope.Bin(64, 64), Vectorscope.bin(0.0, 0.0, 1))
        assertEquals(Vectorscope.Bin(64, 64), Vectorscope.bin(0.0, 0.0, 2))
    }

    @Test fun binGoesRightWithCbAndUpWithCr() {
        // Cb — вправо, Cr — вверх, y = 0 вверху
        assertEquals(Vectorscope.Bin(96, 64), Vectorscope.bin(0.25, 0.0, 1))
        assertEquals(Vectorscope.Bin(32, 64), Vectorscope.bin(-0.25, 0.0, 1))
        assertEquals(Vectorscope.Bin(64, 32), Vectorscope.bin(0.0, 0.25, 1))
        assertEquals(Vectorscope.Bin(64, 96), Vectorscope.bin(0.0, -0.25, 1))
    }

    @Test fun binCellsAreOneOverBinsWideAndNarrowerWhenZoomed() {
        // шаг корзины при ×1 — 1/128, при ×2 — 1/256; граница между корзинами 64 и 65 лежит ровно на шаге
        assertEquals(65, Vectorscope.bin(1.0 / 128, 0.0, 1)?.x)
        assertEquals(64, Vectorscope.bin(1.0 / 128 - 1e-6, 0.0, 1)?.x)
        assertEquals(65, Vectorscope.bin(1.0 / 256, 0.0, 2)?.x)
        assertEquals(64, Vectorscope.bin(1.0 / 256 - 1e-6, 0.0, 2)?.x)
    }

    @Test fun binEdgeIsHalfAtZoomOneAndAQuarterAtZoomTwo() {
        // ровно на краю — крайняя корзина (полностью насыщенный красный и синий лежат ровно на ±0,5)
        assertEquals(Vectorscope.Bin(127, 0), Vectorscope.bin(0.5, 0.5, 1))
        assertEquals(Vectorscope.Bin(0, 127), Vectorscope.bin(-0.5, -0.5, 1))
        assertEquals(Vectorscope.Bin(127, 0), Vectorscope.bin(0.25, 0.25, 2))
        // за краем — null
        assertNull(Vectorscope.bin(0.51, 0.0, 1))
        assertNull(Vectorscope.bin(0.0, -0.51, 1))
        assertNull(Vectorscope.bin(0.26, 0.0, 2))
        assertNull(Vectorscope.bin(0.0, 0.3, 2))
        assertNull(Vectorscope.bin(-0.3, 0.0, 2))
    }

    @Test fun binEdgeAbsorbsRoundingNoiseButNotARealExcess() {
        // шум округления в последнем знаке — это всё ещё край (полностью насыщенный цвет не должен выпасть), настоящий выход — нет
        assertEquals(127, Vectorscope.bin(0.5 + 1e-12, 0.0, 1)?.x)
        assertEquals(0, Vectorscope.bin(-0.5 - 1e-12, 0.0, 1)?.x)
        assertEquals(0, Vectorscope.bin(0.0, 0.5 + 1e-12, 1)?.y)
        assertEquals(127, Vectorscope.bin(0.0, -0.5 - 1e-12, 1)?.y)
        assertEquals(127, Vectorscope.bin(0.25 + 1e-13, 0.0, 2)?.x)
        assertNull(Vectorscope.bin(0.5 + 1e-6, 0.0, 1))
        assertNull(Vectorscope.bin(0.25 + 1e-6, 0.0, 2))
    }

    @Test fun binOfNothingIsNull() {
        assertNull(Vectorscope.bin(Double.NaN, 0.0, 1))
        assertNull(Vectorscope.bin(0.0, Double.NaN, 1))
        assertNull(Vectorscope.bin(Double.POSITIVE_INFINITY, 0.0, 1))
        assertNull(Vectorscope.bin(0.0, Double.NEGATIVE_INFINITY, 2))
    }

    @Test fun zoomBelowOneIsReadAsOne() {
        assertEquals(Vectorscope.bin(0.2, -0.1, 1), Vectorscope.bin(0.2, -0.1, 0))
        assertEquals(Vectorscope.bin(0.2, -0.1, 1), Vectorscope.bin(0.2, -0.1, -3))
        val red = flat(191, 0, 0)
        assertArrayEquals(Vectorscope.counts(red, 64, 36, 1), Vectorscope.counts(red, 64, 36, 0))
        assertArrayEquals(Vectorscope.counts(red, 64, 36, 1), Vectorscope.counts(red, 64, 36, -2))
        assertEquals(Vectorscope.peak(countsOf(Cell(10, 20, 9)), 1), Vectorscope.peak(countsOf(Cell(10, 20, 9)), 0))
    }

    // подсчёт по кадру

    @Test fun solidSeventyFivePercentRedLandsAtTheRedMark() {
        val counts = Vectorscope.counts(flat(191, 0, 0), 64, 36, 1)
        assertEquals(128 * 128, counts.size)
        val top = fullestOrFail(counts)
        val red = Vectorscope.targets[0]
        val mark = Vectorscope.bin(red.cb, red.cr, 1) ?: throw AssertionError("метка R за краем")
        assertTrue(abs(top.x - mark.x) <= 1)
        assertTrue(abs(top.y - mark.y) <= 1)
        // весь кадр одного цвета — одна корзина на все отсчёты
        assertEquals(32 * 18, top.n)
        assertEquals(1, counts.count { it > 0 })
    }

    @Test fun greyFrameLandsInTheCentreBin() {
        val top = fullestOrFail(Vectorscope.counts(flat(128, 128, 128), 64, 36, 1))
        assertTrue(abs(top.x - 64) <= 1)
        assertTrue(abs(top.y - 64) <= 1)
    }

    @Test fun everyGreyLevelLandsInTheSameCentreBin() {
        // от чёрного до белого и при ×1, и при ×2 — одна и та же корзина, а не две-четыре соседние
        for (zoom in 1..2) {
            for (level in 0..255) {
                val top = fullestOrFail(Vectorscope.counts(flat(level, level, level, 2, 2), 2, 2, zoom))
                assertEquals(64, top.x)
                assertEquals(64, top.y)
                assertEquals(1, top.n)
            }
        }
    }

    @Test fun zoomTwoDoublesTheDistanceOfAFiftyPercentRedFromTheCentre() {
        // красный 50 % — (127; 0; 0): Cb ≈ −0,057, Cr ≈ 0,249 — внутри края ×2 (0,25); 128 был бы уже за краем
        val red = flat(127, 0, 0)
        val one = offset(fullestOrFail(Vectorscope.counts(red, 64, 36, 1)))
        val two = offset(fullestOrFail(Vectorscope.counts(red, 64, 36, 2)))
        assertTrue(abs(two.first - 2 * one.first) <= 1)
        assertTrue(abs(two.second - 2 * one.second) <= 1)
        // и это не центр: тест не проходит впустую
        assertTrue(one.first > 5)
        assertTrue(one.second > 20)
    }

    @Test fun fullRedIsDroppedAtZoomTwoButKeptAtZoomOne() {
        // красный 100 %: Cr = 0,5 — на краю ×1 и вдвое за краем ×2
        val red = flat(255, 0, 0)
        val zoomed = Vectorscope.counts(red, 64, 36, 2)
        assertEquals(128 * 128, zoomed.size)
        assertEquals(0, zoomed.sum())
        val plain = Vectorscope.counts(red, 64, 36, 1)
        assertEquals(32 * 18, plain.sum())
        // верхний ряд плоскости: Cr на самом краю
        assertEquals(0, fullestOrFail(plain).y)
    }

    @Test fun fullySaturatedPrimariesAreNeverLostAtZoomOne() {
        // красный, синий и зелёный 100 % и все их смеси ложатся на край — а не за него
        for (r in listOf(0, 255)) for (g in listOf(0, 255)) for (b in listOf(0, 255)) {
            assertEquals("($r; $g; $b)", 1, Vectorscope.counts(flat(r, g, b, 2, 2), 2, 2, 1).sum())
        }
    }

    @Test fun countsSumIsEverySecondPixelInBothAxes() {
        // 64×36 — 32 столбца и 18 строк отсчётов
        assertEquals(32 * 18, Vectorscope.counts(flat(40, 90, 200), 64, 36, 1).sum())
        // нечётные размеры: отсчёты в 0, 2, 4… — на единицу больше половины
        assertEquals(3 * 2, Vectorscope.counts(flat(40, 90, 200, 5, 3), 5, 3, 1).sum())
        assertEquals(1, Vectorscope.counts(flat(40, 90, 200, 1, 1), 1, 1, 1).sum())
        assertEquals(512 * 288, Vectorscope.counts(flat(40, 90, 200, 1024, 576), 1024, 576, 1).sum())
        // кадры не 16:9: фото 1:1 и 3:2, 640×360 по Wi-Fi
        assertEquals(512 * 512, Vectorscope.counts(flat(40, 90, 200, 1024, 1024), 1024, 1024, 1).sum())
        assertEquals(512 * 340, Vectorscope.counts(flat(40, 90, 200, 1024, 680), 1024, 680, 1).sum())
        assertEquals(320 * 180, Vectorscope.counts(flat(40, 90, 200, 640, 360), 640, 360, 2).sum())
    }

    @Test fun countsTakeOnlyEvenColumnsAndRows() {
        // 8×8: красные — точки, где x или y нечётный; синие — где оба чётные. В счётчиках должны быть только синие (16 штук)
        val pixels = IntArray(8 * 8) { i ->
            val x = i % 8
            val y = i / 8
            if (x % 2 == 0 && y % 2 == 0) argb(0, 0, 255) else argb(255, 0, 0)
        }
        val counts = Vectorscope.counts(pixels, 8, 8, 1)
        assertEquals(16, counts.sum())
        val top = fullestOrFail(counts)
        assertEquals(16, top.n)
        val blueBin = Vectorscope.bin(0.5, -0.0722 / 1.5748, 1) ?: throw AssertionError("синий за краем")
        assertEquals(blueBin.x, top.x)
        assertEquals(blueBin.y, top.y)
    }

    @Test fun countsIgnoreWhatLiesBeyondTheFrame() {
        // массив длиннее кадра (буфер переиспользуют между кадрами): в хвосте — ярко-зелёный мусор, в счётчики он не попадает
        val width = 4
        val height = 4
        val pixels = IntArray(width * height + 40) { argb(0, 255, 0) }
        for (i in 0 until width * height) pixels[i] = argb(191, 0, 0)
        val counts = Vectorscope.counts(pixels, width, height, 1)
        assertEquals(4, counts.sum())
        assertEquals(1, counts.count { it > 0 })
        val red = Vectorscope.targets[0]
        val mark = Vectorscope.bin(red.cb, red.cr, 1) ?: throw AssertionError("метка R за краем")
        val top = fullestOrFail(counts)
        assertTrue(abs(top.x - mark.x) <= 1)
        assertTrue(abs(top.y - mark.y) <= 1)
        // и ровно кадр, без хвоста, считается так же
        assertArrayEquals(counts, Vectorscope.counts(IntArray(width * height) { argb(191, 0, 0) }, width, height, 1))
    }

    /**
     * Эталон в лоб, без хитростей: каждая вторая точка по обеим осям, её цвет → Cb и Cr → корзина (общедоступными `chroma` и
     * `bin`, которые проверены отдельно).
     */
    private fun naiveCounts(argb: IntArray, width: Int, height: Int, zoom: Int): IntArray {
        val counts = IntArray(128 * 128)
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                val p = argb[y * width + x]
                val c = Vectorscope.chroma(red(p) / 255.0, green(p) / 255.0, blue(p) / 255.0)
                val cell = Vectorscope.bin(c.cb, c.cr, zoom)
                if (cell != null) counts[cell.y * 128 + cell.x]++
            }
        }
        return counts
    }

    @Test fun countsMatchANaiveWalkOverRandomFrames() {
        // случайные цвета на кадрах разной формы, с хвостом-мусором в массиве и без: порядок каналов, чётные точки и края —
        // всё сразу
        val random = Lcg()
        for ((w, h, tail) in listOf(
            Triple(37, 23, 12), Triple(64, 36, 0), Triple(1, 1, 0), Triple(2, 3, 8), Triple(5, 40, 4), Triple(130, 7, 0),
            Triple(16, 16, 16),
        )) {
            val pixels = IntArray(w * h + tail) { argb(238, 0, 238) }
            for (i in 0 until w * h) {
                pixels[i] = argb((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt())
            }
            for (zoom in 1..2) {
                val got = Vectorscope.counts(pixels, w, h, zoom)
                assertArrayEquals("кадр $w×$h, хвост $tail, ×$zoom", naiveCounts(pixels, w, h, zoom), got)
            }
        }
    }

    @Test fun countsIgnoreTheAlphaOfThePixels() {
        // Bitmap.getPixels отдаёт альфу 255, но цвет — это только R, G, B
        val opaque = IntArray(16) { argb(40, 90, 200) }
        val clear = IntArray(16) { argb(40, 90, 200) and 0x00FFFFFF }
        val half = IntArray(16) { (0x80 shl 24) or (argb(40, 90, 200) and 0x00FFFFFF) }
        assertArrayEquals(Vectorscope.counts(opaque, 4, 4, 1), Vectorscope.counts(clear, 4, 4, 1))
        assertArrayEquals(Vectorscope.counts(opaque, 4, 4, 1), Vectorscope.counts(half, 4, 4, 1))
    }

    @Test fun countsOfBrokenFramesAreEmptyAndSafe() {
        val pixels = flat(191, 0, 0, 4, 4)
        val empty = IntArray(128 * 128)
        assertArrayEquals(empty, Vectorscope.counts(pixels, 0, 4, 1))
        assertArrayEquals(empty, Vectorscope.counts(pixels, 4, 0, 1))
        assertArrayEquals(empty, Vectorscope.counts(pixels, -4, 4, 1))
        assertArrayEquals(empty, Vectorscope.counts(pixels, 4, -4, 1))
        // массив короче кадра
        assertArrayEquals(empty, Vectorscope.counts(pixels, 4, 5, 1))
        assertArrayEquals(empty, Vectorscope.counts(IntArray(0), 4, 4, 1))
        // размеры, у которых произведение переполняет Int
        assertArrayEquals(empty, Vectorscope.counts(pixels, 65_536, 65_536, 1))
        assertArrayEquals(empty, Vectorscope.counts(pixels, Int.MAX_VALUE, 2, 2))
    }

    @Test fun countsPutEveryColourWhereBinOfChromaPutsIt() {
        // горячий цикл считает теми же формулами, что и общедоступные `chroma` и `bin`: корзина совпадает для любого цвета,
        // и за краем тоже (там счётчики пусты)
        val random = Lcg()
        val colours = mutableListOf(
            Triple(255, 0, 0), Triple(0, 255, 0), Triple(0, 0, 255), Triple(255, 255, 0), Triple(0, 255, 255), Triple(255, 0, 255),
        )
        repeat(300) { colours.add(Triple((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt())) }
        for ((r, g, b) in colours) {
            val c = Vectorscope.chroma(r / 255.0, g / 255.0, b / 255.0)
            for (zoom in 1..2) {
                val counts = Vectorscope.counts(flat(r, g, b, 1, 1), 1, 1, zoom)
                val want = Vectorscope.bin(c.cb, c.cr, zoom)
                if (want != null) {
                    assertEquals("($r; $g; $b) ×$zoom", 1, counts[want.y * 128 + want.x])
                    assertEquals(1, counts.sum())
                } else {
                    assertEquals("($r; $g; $b) ×$zoom", 0, counts.sum())
                }
            }
        }
    }

    /**
     * Кадры для сверки с Mac: те же цвета из «seed 1», в том же порядке обращений к генератору, что в Swift. A — шум по всему
     * кубу, B — блёклые цвета около серого (внутри края ×2), C — шесть полос 75 % с небольшим шумом.
     */
    private fun macFrame(kind: Char, width: Int, height: Int): IntArray {
        val random = Lcg()
        val clusters = listOf(
            Triple(191, 0, 0), Triple(191, 0, 191), Triple(0, 0, 191), Triple(0, 191, 191), Triple(0, 191, 0), Triple(191, 191, 0),
        )
        return IntArray(width * height) {
            when (kind) {
                'A' -> argb((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt())
                'B' -> argb(90 + (random.next() * 70).toInt(), 90 + (random.next() * 70).toInt(), 90 + (random.next() * 70).toInt())
                else -> {
                    val c = clusters[(random.next() * 6).toInt()]
                    val r = minOf(c.first + (random.next() * 8).toInt(), 255)
                    val g = minOf(c.second + (random.next() * 8).toInt(), 255)
                    val b = minOf(c.third + (random.next() * 8).toInt(), 255)
                    argb(r, g, b)
                }
            }
        }
    }

    private class MacCounts(val total: Int, val nonEmpty: Int, val sum: Long, val cb: Double?, val cr: Double?)

    @Test fun countsAndPeakMatchTheMacOnTheSameFrames() {
        // числа посчитаны программой для Mac (Vectorscope.swift) на этих же кадрах 64×36: всего отсчётов, непустых корзин,
        // сумма (индекс + 1) · счётчик и пик — двоичные дроби, поэтому сравниваются точно
        val want = mapOf(
            Pair('A', 1) to MacCounts(576, 554, 4_960_017, -0.12109375, 0.30078125),
            Pair('A', 2) to MacCounts(345, 338, 2_838_533, 0.130859375, 0.162109375),
            Pair('B', 1) to MacCounts(576, 382, 4_784_908, -0.01171875, 0.00390625),
            Pair('B', 2) to MacCounts(576, 508, 4_850_679, 0.009765625, -0.021484375),
            Pair('C', 1) to MacCounts(576, 86, 4_617_153, -0.08984375, 0.37109375),
            // полосы 75 % лежат дальше 0,25 от середины: при ×2 за краем, счётчики пусты, пика нет
            Pair('C', 2) to MacCounts(0, 0, 0, null, null),
        )
        for ((key, mac) in want) {
            val (kind, zoom) = key
            val counts = Vectorscope.counts(macFrame(kind, 64, 36), 64, 36, zoom)
            var nonEmpty = 0
            var sum = 0L
            for (i in counts.indices) if (counts[i] > 0) {
                nonEmpty++
                sum += (i + 1).toLong() * counts[i]
            }
            val label = "кадр $kind, ×$zoom"
            assertEquals(label, mac.total, counts.sum())
            assertEquals(label, mac.nonEmpty, nonEmpty)
            assertEquals(label, mac.sum, sum)
            val peak = Vectorscope.peak(counts, zoom)
            if (mac.cb == null || mac.cr == null) {
                assertNull(label, peak)
            } else {
                assertNotNull(label, peak)
                assertEquals(label, mac.cb, peak!!.cb, 0.0)
                assertEquals(label, mac.cr, peak.cr, 0.0)
            }
        }
    }

    // картинка

    @Test fun imageMakesEmptyBinsClearAndTheFullestOpaque() {
        val counts = Vectorscope.counts(flat(191, 0, 0), 64, 36, 1)
        val image = Vectorscope.image(counts)
        assertEquals(128 * 128, image.size)
        val top = fullestOrFail(counts)
        val at = top.y * 128 + top.x
        assertEquals(255, alpha(image[at]))
        // у пустых — альфа 0, и цвет тоже: пустая корзина целиком прозрачный ноль
        var visible = 0
        for (i in image.indices) if (i != at && image[i] != 0) visible++
        assertEquals(0, visible)
    }

    @Test fun imageBrightnessIsLogarithmicInTheCount() {
        // яркость корзины — log(1 + n) / log(1 + max): 1 из 99 — 15 %, 9 из 99 — ровно половина, самая полная — 100 %
        val image = Vectorscope.image(countsOf(Cell(10, 10, 1), Cell(20, 20, 99), Cell(30, 30, 9)))
        val one = alpha(image[10 * 128 + 10])
        val nine = alpha(image[30 * 128 + 30])
        val full = alpha(image[20 * 128 + 20])
        // 255 · log(2)/log(100) = 38,4: далеко от «.5», поэтому ровно 38
        assertEquals(38, one)
        assertTrue("9 из 99: $nine", abs(nine - 128) <= 1)
        assertEquals(255, full)
        assertTrue(one < nine && nine < full)
    }

    @Test fun imageColourIsTheHueOfTheBinItself() {
        // у самой полной корзины яркость 1: её цвет — Y 0,6 с Cb и Cr этой корзины, обратно в RGB
        val places = listOf(64 to 64, 100 to 64, 64 to 5, 10 to 120, 0 to 0, 127 to 127, 30 to 90, 110 to 20)
        for ((x, y) in places) {
            val p = Vectorscope.image(countsOf(Cell(x, y, 7)))[y * 128 + x]
            val want = hue(x, y)
            assertEquals(255, alpha(p))
            assertTrue("($x; $y) R", abs(red(p) - level(want.first)) <= 1)
            assertTrue("($x; $y) G", abs(green(p) - level(want.second)) <= 1)
            assertTrue("($x; $y) B", abs(blue(p) - level(want.third)) <= 1)
        }
    }

    @Test fun imageHuesPointTheRightWay() {
        // середина — серый; вправо по Cb синее, вверх по Cr краснее, влево и вниз — наоборот
        val centre = Vectorscope.image(countsOf(Cell(64, 64, 3)))[64 * 128 + 64]
        assertTrue(abs(red(centre) - 153) <= 3 && abs(green(centre) - 153) <= 3 && abs(blue(centre) - 153) <= 3)
        val right = Vectorscope.image(countsOf(Cell(120, 64, 3)))[64 * 128 + 120]
        assertTrue(blue(right) > red(right) + 40)
        val up = Vectorscope.image(countsOf(Cell(64, 8, 3)))[8 * 128 + 64]
        assertTrue(red(up) > blue(up) + 40 && red(up) > green(up) + 40)
        // у края красный обрезан до 255, а не переполнен
        assertEquals(255, red(up))
        val down = Vectorscope.image(countsOf(Cell(20, 100, 3)))[100 * 128 + 20]
        assertTrue(green(down) > red(down) && green(down) > blue(down))
    }

    @Test fun imageKeepsTheColourStraightForBitmapSetPixels() {
        // Bitmap.setPixels ждёт цвет, НЕ умноженный на альфу (в отличие от RGBA с предумножением у программы для Mac): у
        // тусклой корзины каналы — те же, что у самой яркой в том же месте, а прозрачность — только в альфе
        val bright = Vectorscope.image(countsOf(Cell(64, 8, 3)))[8 * 128 + 64]
        val dim = Vectorscope.image(countsOf(Cell(64, 8, 3), Cell(90, 90, 99)))[8 * 128 + 64]
        assertEquals(255, alpha(bright))
        assertTrue("альфа тусклой: ${alpha(dim)}", alpha(dim) in 1..254)
        assertEquals(bright and 0x00FFFFFF, dim and 0x00FFFFFF)
        // красный у края корзины — полный, хотя альфа меньше: с предумножением он не мог бы быть больше альфы
        assertEquals(255, red(dim))
        assertTrue(red(dim) > alpha(dim))
    }

    @Test fun imageEveryFilledBinHasTheColourOfItsPlaceAndTheLogAlpha() {
        // случайные счётчики: цвет каждой непустой корзины — как у самой яркой на том же месте (все корзины поровну дают
        // яркость 1), альфа — по формуле, пустые — целиком ноль
        val random = Lcg()
        val bright = Vectorscope.image(IntArray(128 * 128) { 5 })
        assertTrue(bright.all { alpha(it) == 255 })
        val counts = IntArray(128 * 128)
        repeat(400) { counts[(random.next() * 128 * 128).toInt()] = (random.next() * 5000).toInt() + 1 }
        val image = Vectorscope.image(counts)
        val top = ln(1.0 + counts.max())
        var filled = 0
        for (i in counts.indices) {
            if (counts[i] == 0) {
                assertEquals(0, image[i])
                continue
            }
            filled++
            assertEquals("цвет корзины $i", bright[i] and 0x00FFFFFF, image[i] and 0x00FFFFFF)
            val exact = minOf(ln(1.0 + counts[i]) / top, 1.0) * 255
            val want = Math.floor(exact + 0.5).toInt()
            if (abs(exact - Math.floor(exact) - 0.5) < 1e-6) {
                // почти точное «.5»: последний знак логарифма у разных математических библиотек разный, округление уходит на 1
                assertTrue("альфа корзины $i: ${alpha(image[i])}, ждали $want", abs(alpha(image[i]) - want) <= 1)
            } else {
                assertEquals("альфа корзины $i", want, alpha(image[i]))
            }
        }
        assertTrue(filled in 300..400)
        assertTrue(image.any { alpha(it) == 255 })
    }

    @Test fun imageHueTableMatchesTheMac() {
        // все корзины поровну: яркость 1, альфа 255, а цвет — ровно оттенок корзины (логарифма тут нет, число одно на всех
        // платформах). Посчитано программой для Mac (Vectorscope.swift): сумма (индекс + 1) · 0xRRGGBB по всем 16384 корзинам
        // и цвета нескольких корзин
        val image = Vectorscope.image(IntArray(128 * 128) { 5 })
        var sum = 0L
        for (i in image.indices) {
            assertEquals(255, alpha(image[i]))
            sum += (i + 1).toLong() * (image[i] and 0xFFFFFF)
        }
        assertEquals(793_649_804_949_120L, sum)
        val samples = mapOf(
            0 to Triple(255, 117, 0), 63 to Triple(255, 94, 151), 127 to Triple(255, 70, 255), 5000 to Triple(230, 151, 0),
            8128 to Triple(155, 152, 155), 12345 to Triple(51, 186, 129), 16256 to Triple(0, 236, 0), 16383 to Triple(0, 189, 255),
        )
        for ((i, c) in samples) assertEquals("корзина $i", c, Triple(red(image[i]), green(image[i]), blue(image[i])))
    }

    @Test fun imageOfEmptyOrWrongSizedCountsIsClear() {
        assertArrayEquals(IntArray(128 * 128), Vectorscope.image(IntArray(128 * 128)))
        assertArrayEquals(IntArray(128 * 128), Vectorscope.image(IntArray(0)))
        assertArrayEquals(IntArray(128 * 128), Vectorscope.image(IntArray(10) { 5 }))
        assertArrayEquals(IntArray(128 * 128), Vectorscope.image(IntArray(128 * 128 + 1) { 5 }))
    }

    // пик

    @Test fun peakOfSolidSeventyFivePercentRedIsTheRedMark() {
        val counts = Vectorscope.counts(flat(191, 0, 0), 64, 36, 1)
        val peak = Vectorscope.peak(counts, 1) ?: throw AssertionError("пика нет")
        assertEquals(Vectorscope.targets[0].cb, peak.cb, 0.01)
        assertEquals(Vectorscope.targets[0].cr, peak.cr, 0.01)
    }

    @Test fun peakOfEmptyCountsIsNull() {
        assertNull(Vectorscope.peak(IntArray(128 * 128), 1))
        assertNull(Vectorscope.peak(IntArray(128 * 128), 2))
        assertNull(Vectorscope.peak(IntArray(0), 1))
        // счётчики не того размера — не вектороскоп
        assertNull(Vectorscope.peak(IntArray(100) { 4 }, 1))
    }

    @Test fun peakIsTheCentreOfTheFullestBin() {
        // корзина (10; 20) полнее всех: середина — на пол-корзины внутрь, Cr вверх от y
        val filled = countsOf(Cell(10, 20, 9), Cell(100, 30, 8), Cell(64, 64, 2))
        val one = Vectorscope.peak(filled, 1) ?: throw AssertionError("пика нет")
        val two = Vectorscope.peak(filled, 2) ?: throw AssertionError("пика нет")
        assertEquals(10.5 / 128 - 0.5, one.cb, 1e-12)
        assertEquals(0.5 - 20.5 / 128, one.cr, 1e-12)
        // при ×2 та же корзина — вдвое ближе к середине
        assertEquals(one.cb / 2, two.cb, 1e-12)
        assertEquals(one.cr / 2, two.cr, 1e-12)
    }

    @Test fun peakOfEqualBinsIsTheFirstFromTheTopLeft() {
        val filled = countsOf(Cell(90, 10, 5), Cell(20, 10, 5), Cell(20, 80, 5))
        val peak = Vectorscope.peak(filled, 1) ?: throw AssertionError("пика нет")
        assertEquals(20.5 / 128 - 0.5, peak.cb, 1e-12)
        assertEquals(0.5 - 10.5 / 128, peak.cr, 1e-12)
    }

    @Test fun peakFollowsTheZoomAndLandsOnTheColour() {
        // блёклый красный (160; 120; 120) лежит внутри края ×2; середина корзины — не дальше её половины от цвета
        val muted = flat(160, 120, 120)
        val c = Vectorscope.chroma(160.0 / 255, 120.0 / 255, 120.0 / 255)
        for (zoom in 1..2) {
            val counts = Vectorscope.counts(muted, 64, 36, zoom)
            val peak = Vectorscope.peak(counts, zoom) ?: throw AssertionError("пика нет при ×$zoom")
            val half = 0.5 / Vectorscope.BINS / zoom
            assertTrue(near(peak.cb, c.cb, half + 1e-9))
            assertTrue(near(peak.cr, c.cr, half + 1e-9))
        }
    }

    // цена

    /** Байты, выделенные этим потоком (HotSpot: com.sun.management.ThreadMXBean, через отражение, как в PictureTransformTest). */
    private class AllocationCounter private constructor(
        private val bean: Any,
        private val read: java.lang.reflect.Method,
        private val id: Long,
    ) {
        fun total(): Long = read.invoke(bean, id) as Long

        companion object {
            fun create(): AllocationCounter? = try {
                val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
                val sun = Class.forName("com.sun.management.ThreadMXBean")
                if (bean == null || !sun.isInstance(bean) || sun.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) {
                    null
                } else {
                    AllocationCounter(bean, sun.getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType), Thread.currentThread().id)
                }
            } catch (e: ReflectiveOperationException) {
                null
            }
        }
    }

    /** Сколько байт выделил `block`; сам замер (бокс числа) вычтен, он одинаков у любого блока. */
    private fun AllocationCounter.spent(block: () -> Unit): Long {
        repeat(50) { total() } // прогрев самого замера: отражение сначала дороже
        val before = total()
        val idle = total() - before
        val start = total()
        block()
        return total() - start - idle
    }

    @Test fun countsImageAndPeakAllocateNothingPerPixel() {
        // на точку и на корзину — ни одного объекта: выделяется только результат, и столько же на большом кадре, сколько на крошечном
        val counter = AllocationCounter.create()
        assumeTrue("нет счётчика выделений", counter != null)
        val random = Lcg()
        val big = IntArray(1024 * 576) { argb((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt()) }
        val small = IntArray(4) { big[it] }
        val full = IntArray(128 * 128) { it % 7 + 1 }
        val one = countsOf(Cell(5, 5, 3))
        repeat(300) { // прогрев: JIT и таблица уровней
            Vectorscope.counts(big, 1024, 576, 1)
            Vectorscope.counts(small, 2, 2, 2)
            Vectorscope.image(full)
            Vectorscope.image(one)
            Vectorscope.peak(full, 1)
        }
        val runs = 20
        val bigSpent = counter!!.spent { repeat(runs) { Vectorscope.counts(big, 1024, 576, 1) } }
        val smallSpent = counter.spent { repeat(runs) { Vectorscope.counts(small, 2, 2, 1) } }
        println("VectorscopeTest: counts на кадре 1024×576 — ${bigSpent / runs} байт на вызов, на 2×2 — ${smallSpent / runs}")
        assertTrue("счётчики: $bigSpent против $smallSpent", abs(bigSpent - smallSpent) / runs < 1024)
        val fullSpent = counter.spent { repeat(runs) { Vectorscope.image(full) } }
        val oneSpent = counter.spent { repeat(runs) { Vectorscope.image(one) } }
        println("VectorscopeTest: image при 16384 непустых корзинах — ${fullSpent / runs} байт на вызов, при одной — ${oneSpent / runs}")
        assertTrue("картинка: $fullSpent против $oneSpent", abs(fullSpent - oneSpent) / runs < 1024)
        val peakSpent = counter.spent { repeat(runs) { Vectorscope.peak(full, 1) } }
        assertTrue("пик: $peakSpent", peakSpent / runs < 256)
    }

    /**
     * Сколько стоит вектороскоп на кадре 1024×576. Только на JVM, на планшете числа другие — их сравнивают между правками:
     * ZV_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*VectorscopeTest' -i
     */
    @Test fun benchmark1024x576() {
        assumeTrue("ZV_BENCH не задан", System.getenv("ZV_BENCH") != null)
        val w = 1024
        val h = 576
        // цвет по всей плоскости: шум по трём каналам, чтобы отсчёты разошлись по корзинам
        val random = Lcg()
        val pixels = IntArray(w * h) { argb((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt()) }
        fun time(name: String, n: Int = 50, body: () -> Unit) {
            repeat(n) { body() } // прогрев
            val start = System.nanoTime()
            repeat(n) { body() }
            println(String.format(Locale.ROOT, "VectorscopeTest: %s: %.3f мс", name, (System.nanoTime() - start) / 1e6 / n))
        }
        var counts = IntArray(0)
        time("counts ×1") { counts = Vectorscope.counts(pixels, w, h, 1) }
        time("counts ×2") { Vectorscope.counts(pixels, w, h, 2) }
        time("image") { Vectorscope.image(counts) }
        time("peak") { Vectorscope.peak(counts, 1) }
        assertNotNull(Vectorscope.peak(counts, 1))
    }
}
