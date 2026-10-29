package local.zve10viewer

import local.zve10viewer.monitor.AssistRaster
import local.zve10viewer.monitor.AssistRaster.FalseColorBand
import local.zve10viewer.monitor.ColorProbe
import local.zve10viewer.settings.LumaHistogram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

/**
 * Пипетка — те же проверки, что у программы для Mac (ColorProbeTests.swift). Кадр — ARGB, как у Bitmap.getPixels, плотно
 * (строка занимает ровно width точек); точка — в точках кадра, как на Mac.
 */
class ColorProbeTest {
    // помощники

    /** Случайные числа без сюрпризов — «seed 1», как в тестах программы для Mac. Int переполняется по модулю 2³², как UInt32. */
    private class Lcg {
        private var seed = 1

        /** 0 ≤ x < 1. */
        fun next(): Double {
            seed = seed * 1_664_525 + 1_013_904_223
            return (seed ushr 8) / 16_777_216.0
        }
    }

    private class Frame(val argb: IntArray, val width: Int, val height: Int)

    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun red(p: Int) = (p shr 16) and 0xFF
    private fun green(p: Int) = (p shr 8) and 0xFF
    private fun blue(p: Int) = p and 0xFF

    /**
     * Кадр width×height: `colour(x, y)` — цвет точки (ARGB). Массив длиннее кадра на `tail` точек, и в хвосте лежит яркий
     * пурпур: чтение за последней точкой кадра сразу видно. Без хвоста массив кончается ровно на последней точке.
     */
    private fun frame(width: Int, height: Int, tail: Int = 0, colour: (Int, Int) -> Int): Frame {
        val argb = IntArray(width * height + tail) { px(255, 0, 255) }
        for (y in 0 until height) for (x in 0 until width) argb[y * width + x] = colour(x, y)
        return Frame(argb, width, height)
    }

    /** Кадр одного цвета. */
    private fun flat(r: Int, g: Int, b: Int, width: Int = 16, height: Int = 16) = frame(width, height) { _, _ -> px(r, g, b) }

    /** Серый кадр: на любой яркости R = G = B, и целая яркость по Rec. 709 равна самому уровню. */
    private fun grey(level: Int, width: Int = 16, height: Int = 16) = flat(level, level, level, width, height)

    private fun probe(f: Frame, x: Double, y: Double, white: Int = 255) = ColorProbe.read(f.argb, f.width, f.height, x, y, white)

    private class Expected(val percent: Int, val r: Int, val g: Int, val b: Int)

    /** Округление от нуля при .5, для эталона. */
    private fun half(v: Double) = Math.floor(v + 0.5).toInt()

    /**
     * Эталон в лоб: среднее по прямоугольнику точек. Яркость каждой точки — целая, по Rec. 709 (коэффициенты ×1024), как у
     * гистограммы; процент — от белого, округлён.
     */
    private fun reference(f: Frame, xs: IntRange, ys: IntRange, white: Int): Expected {
        var sumR = 0
        var sumG = 0
        var sumB = 0
        var sumLuma = 0
        var n = 0
        for (y in ys) {
            for (x in xs) {
                val p = f.argb[y * f.width + x]
                val r = red(p)
                val g = green(p)
                val b = blue(p)
                sumR += r
                sumG += g
                sumB += b
                sumLuma += (218 * r + 732 * g + 74 * b) shr 10
                n++
            }
        }
        val count = n.toDouble()
        return Expected(half(sumLuma / count / white * 100), half(sumR / count), half(sumG / count), half(sumB / count))
    }

    /** Квадрат 5×5 вокруг точки, обрезанный краем кадра. */
    private fun box(f: Frame, x: Int, y: Int): Pair<IntRange, IntRange> =
        Pair(max(x - 2, 0)..min(x + 2, f.width - 1), max(y - 2, 0)..min(y + 2, f.height - 1))

    // среднее по квадрату

    @Test fun probeAveragesTheFiveByFiveSquare() {
        // 16×16; в квадрате 5×5 вокруг (8; 8) — 100 и 200 вперемешку (красный и зелёный наоборот, синий ровный), вокруг —
        // яркий пурпур: любой лишний или пропущенный ряд сдвинет среднее
        val f = frame(16, 16) { x, y ->
            if (x !in 6..10 || y !in 6..10) {
                px(255, 0, 255)
            } else {
                val even = (x + y) % 2 == 0
                px(if (even) 100 else 200, if (even) 200 else 100, 150)
            }
        }
        val reading = probe(f, 8.0, 8.0)
        val want = reference(f, 6..10, 6..10, 255)
        // 13 точек одного рода и 12 другого: красный (13·100 + 12·200)/25 = 148, зелёный 152, синий 150
        assertEquals(148.0, reading.r.toDouble(), 1.0)
        assertEquals(152.0, reading.g.toDouble(), 1.0)
        assertEquals(150.0, reading.b.toDouble(), 1.0)
        assertEquals(want.r.toDouble(), reading.r.toDouble(), 1.0)
        assertEquals(want.g.toDouble(), reading.g.toDouble(), 1.0)
        assertEquals(want.b.toDouble(), reading.b.toDouble(), 1.0)
        // яркость каждой точки — целая (175 или 124), среднее 150,52 — 59 % от 255
        assertEquals(want.percent.toDouble(), reading.percent.toDouble(), 1.0)
        assertEquals(59, reading.percent)
    }

    @Test fun probeReadsTheChannelsInOrderAndRoundsHalfAwayFromZero() {
        val ordered = probe(flat(10, 20, 30), 8.0, 8.0)
        assertEquals(10, ordered.r)
        assertEquals(20, ordered.g)
        assertEquals(30, ordered.b)
        // 2×1: красный 1 и 2, у края кадра квадрат — это обе точки; среднее 1,5 — до 2
        val pair = frame(2, 1) { x, _ -> px(x + 1, 0, 0) }
        assertEquals(2, probe(pair, 0.0, 0.0).r)
        assertEquals(2, probe(pair, 1.0, 0.0).r)
        // от нуля при .5, а не к чётному: 2,5 — до 3, 0,5 — до 1 (к чётному вышло бы 2 и 0)
        val twoThree = frame(2, 1) { x, _ -> px(2 + x, 2 + x, 2 + x) }
        val tie = probe(twoThree, 0.0, 0.0)
        assertEquals(3, tie.r)
        assertEquals(3, tie.g)
        assertEquals(3, tie.b)
        val zeroOne = frame(2, 1) { x, _ -> px(x, x, x) }
        assertEquals(1, probe(zeroOne, 0.0, 0.0).r)
    }

    @Test fun probePercentRoundsHalfAwayFromZero() {
        // белое 8: уровень 1 — ровно 12,5 %, уровень 5 — 62,5 %; к чётному вышло бы 12 и 62
        assertEquals(13, probe(grey(1), 8.0, 8.0, 8).percent)
        assertEquals(38, probe(grey(3), 8.0, 8.0, 8).percent)
        assertEquals(63, probe(grey(5), 8.0, 8.0, 8).percent)
    }

    @Test fun probeSquareIsExactlyFiveAcrossNotMore() {
        // за квадратом — другой цвет: ровно две точки в каждую сторону от точки, не три и не одна
        val f = frame(16, 16) { x, y -> if (Math.abs(x - 8) <= 2 && Math.abs(y - 8) <= 2) px(60, 60, 60) else px(250, 250, 250) }
        assertEquals(60, probe(f, 8.0, 8.0).r)
        // сдвинули на точку — в квадрат попал внешний ряд и столбец: 16 тёмных и 9 светлых → (16·60 + 9·250)/25 = 128,4
        assertEquals(128, probe(f, 9.0, 9.0).r)
    }

    @Test fun probeTakesThePixelUnderThePoint() {
        // точка кадра — та, в которую попала координата: 8,0 и 8,99 — точка 8, 9,0 — уже 9
        val f = frame(16, 16) { x, _ -> px(x * 10, 0, 0) }
        val at8 = probe(f, 8.0, 8.0)
        assertEquals(at8, probe(f, 8.99, 8.5))
        assertNotEquals(at8, probe(f, 9.0, 8.0))
        assertNotEquals(at8, probe(f, 7.99, 8.0))
    }

    // процент и зона

    @Test fun probePercentIsBrightnessOfTheWhite() {
        // яркость 117: в видео белое — 234, 117 — ровно половина; в фото белое — 255, 117 — 45,9 %
        assertEquals(50, probe(grey(117), 8.0, 8.0, 234).percent)
        assertEquals(46, probe(grey(117), 8.0, 8.0, 255).percent)
        assertEquals(0, probe(grey(0), 8.0, 8.0, 255).percent)
        assertEquals(100, probe(grey(255), 8.0, 8.0, 255).percent)
    }

    @Test fun probePercentOfBrighterThanWhiteIsOverAHundred() {
        // в видео белое на 234: уровень 255 — выше белого превью
        val reading = probe(grey(255), 8.0, 8.0, 234)
        assertEquals(109, reading.percent)
        assertEquals(FalseColorBand.clipped, reading.zone)
    }

    @Test fun probeNamesTheFalseColourZone() {
        // серый 41 % (уровень 105 — 18 % серого в S-Log3): зона «18 % серого»
        val middle = probe(grey(105), 8.0, 8.0)
        assertEquals(41, middle.percent)
        assertEquals(FalseColorBand.middleGray, middle.zone)
        assertEquals("41 % · 18 % серого", middle.lines.first)
        // 20 % лежит между зонами «тени» и «18 % серого»: подписи нет
        val between = probe(grey(51), 8.0, 8.0)
        assertEquals(20, between.percent)
        assertNull(between.zone)
        assertEquals("20 %", between.lines.first)
    }

    @Test fun probeZoneIsTheOneFalseColourPaintsForEveryLevel() {
        // плоский участок любого уровня получает ту зону, которой false color красит его на экране, — при обоих белых
        for (white in listOf(234, 255)) {
            for (level in 0..255) {
                val reading = ColorProbe.read(intArrayOf(px(level, level, level)), 1, 1, 0.0, 0.0, white)
                assertEquals("уровень $level, белое $white", AssistRaster.falseColorBand(level, white), reading.zone)
                assertEquals("уровень $level, белое $white", half(level * 100.0 / white), reading.percent)
            }
        }
    }

    @Test fun probeAveragesTheLumaOfEachPointNotTheLumaOfTheAverageColour() {
        // красные и зелёные точки вперемешку: яркость каждой — целая (61 и 181), усредняются они: (13·61 + 12·181)/25 = 118,6.
        // Яркость среднего цвета (135; 125; 10) была бы 118 — на процент меньше
        val f = frame(16, 16) { x, y ->
            if (x !in 6..10 || y !in 6..10) px(0, 0, 0) else if ((x + y) % 2 == 0) px(250, 10, 10) else px(10, 250, 10)
        }
        assertEquals(47, probe(f, 8.0, 8.0, 255).percent)
        assertEquals(51, probe(f, 8.0, 8.0, 234).percent)
        val mean = probe(f, 8.0, 8.0)
        assertTrue(mean.r == 135 && mean.g == 125 && mean.b == 10)
    }

    @Test fun probeZoneIsOfTheLevelNearestToTheAverage() {
        // уровни 96 и 97 (37,6 % и 38,0 % от 255; зона «18 % серого» начинается с 38 %), среднее 96,5 — ближайший уровень 97
        assertNull(AssistRaster.falseColorBand(96, 255))
        assertEquals(FalseColorBand.middleGray, AssistRaster.falseColorBand(97, 255))
        val pair = frame(2, 1) { x, _ -> px(96 + x, 96 + x, 96 + x) }
        val between = probe(pair, 0.0, 0.0)
        assertEquals(38, between.percent)
        assertEquals(FalseColorBand.middleGray, between.zone)
        // а плоский участок уровня 96 тоже «38 %», но false color его в «18 % серого» не красит — и подписи зоны нет
        val flat96 = probe(grey(96), 8.0, 8.0)
        assertEquals(38, flat96.percent)
        assertNull(flat96.zone)
        assertEquals("38 %", flat96.lines.first)
    }

    @Test fun probeZoneFollowsTheAverageNotTheCentrePoint() {
        // левые две колонки квадрата чёрные, правые три — серый 105 (41 %, «18 % серого»); сама точка (8; 8) — серая
        val f = frame(16, 16) { x, _ -> if (x < 8) px(0, 0, 0) else px(105, 105, 105) }
        assertEquals(FalseColorBand.middleGray, probe(grey(105), 8.0, 8.0).zone)
        val reading = probe(f, 8.0, 8.0)
        // 15 точек по 105 и 10 по 0: среднее 63 — 25 %, между зонами: подпись — по среднему, а не по точке под меткой
        assertEquals(63, reading.r)
        assertEquals(25, reading.percent)
        assertNull(reading.zone)
    }

    @Test fun probePercentAgreesWithTheHistogramLuma() {
        // та же яркость, что у гистограммы: для любого цвета уровень из `LumaHistogram` даёт тот же процент
        val random = Lcg()
        repeat(300) {
            val colour = px((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt())
            val levels = LumaHistogram.levels(intArrayOf(colour))
            val level = levels.indexOfFirst { it == 1 }
            assertTrue("гистограмма пуста", level >= 0)
            for (white in listOf(234, 255)) {
                val reading = ColorProbe.read(intArrayOf(colour), 1, 1, 0.0, 0.0, white)
                assertEquals(half(level * 100.0 / white), reading.percent)
                assertEquals(AssistRaster.falseColorBand(level, white), reading.zone)
            }
        }
    }

    @Test fun probeIgnoresTheAlphaOfThePixels() {
        val opaque = intArrayOf(px(182, 140, 121))
        val clear = intArrayOf(px(182, 140, 121) and 0x00FFFFFF)
        assertEquals(ColorProbe.read(opaque, 1, 1, 0.0, 0.0, 255), ColorProbe.read(clear, 1, 1, 0.0, 0.0, 255))
    }

    // подписи

    @Test fun probeLinesAreTheTwoLabelLines() {
        // пример из спецификации
        val skin = ColorProbe.Reading(47, 182, 140, 121, FalseColorBand.skin)
        assertEquals("47 % · кожа", skin.lines.first)
        assertEquals("R 182 · G 140 · B 121", skin.lines.second)
        // без зоны — только процент
        val none = ColorProbe.Reading(47, 182, 140, 121, null)
        assertEquals("47 %", none.lines.first)
        assertEquals("R 182 · G 140 · B 121", none.lines.second)
    }

    @Test fun probeLinesUseOnlyPlainSpacesAndTheMiddleDot() {
        // побайтно как на Mac: обычные пробелы (U+0020) и точка по центру (U+00B7), без неразрывных пробелов и «•» / «∙»
        val skin = ColorProbe.Reading(47, 182, 140, 121, FalseColorBand.skin)
        assertEquals(
            listOf(0x34, 0x37, 0x20, 0x25, 0x20, 0xB7, 0x20, 0x43A, 0x43E, 0x436, 0x430),
            skin.lines.first.map { it.code },
        )
        assertEquals(
            listOf(0x52, 0x20, 0x31, 0x38, 0x32, 0x20, 0xB7, 0x20, 0x47, 0x20, 0x31, 0x34, 0x30, 0x20, 0xB7, 0x20, 0x42, 0x20, 0x31, 0x32, 0x31),
            skin.lines.second.map { it.code },
        )
        assertEquals(listOf(0x34, 0x37, 0x20, 0x25), ColorProbe.Reading(47, 0, 0, 0, null).lines.first.map { it.code })
    }

    @Test fun probeLinesNameEveryZone() {
        val titles = listOf("провал", "тени", "18 % серого", "кожа", "почти белое", "пересвет")
        assertEquals(titles, FalseColorBand.entries.map { it.title })
        for (band in FalseColorBand.entries) {
            assertEquals("12 % · ${band.title}", ColorProbe.Reading(12, 0, 0, 0, band).lines.first)
        }
    }

    @Test fun probeLinesOfARealSkinColour() {
        // светлая кожа (182; 140; 121): яркость 147 — 58 % от 255, в зоне «кожа» (52–60 %)
        val reading = probe(flat(182, 140, 121), 8.0, 8.0)
        assertEquals(58, reading.percent)
        assertEquals(FalseColorBand.skin, reading.zone)
        assertEquals("58 % · кожа", reading.lines.first)
        assertEquals("R 182 · G 140 · B 121", reading.lines.second)
    }

    @Test fun probeLinesOfBlackAndWhite() {
        assertEquals("0 % · провал", probe(grey(0), 8.0, 8.0).lines.first)
        assertEquals("100 % · пересвет", probe(grey(255), 8.0, 8.0).lines.first)
        assertEquals("R 255 · G 255 · B 255", probe(grey(255), 8.0, 8.0).lines.second)
    }

    @Test fun probeReadingsCompareByValue() {
        val a = probe(flat(10, 20, 30), 3.0, 3.0)
        assertEquals(a, probe(flat(10, 20, 30), 12.0, 5.0))
        assertNotEquals(a, probe(flat(10, 20, 31), 3.0, 3.0))
        assertNotEquals(ColorProbe.Reading(1, 2, 3, 4, FalseColorBand.shadows), ColorProbe.Reading(1, 2, 3, 4, null))
    }

    @Test fun probeMatchesTheMacOnTheSameNoiseFrame() {
        // кадр — шум «seed 1» 40×30 (цвета по порядку r, g, b на точку); строки посчитаны программой для Mac (ColorProbe.swift)
        // на тех же точках и тех же белых: процент | R | G | B | зона | две строки таблички
        val random = Lcg()
        val f = Frame(
            IntArray(40 * 30) { px((random.next() * 256).toInt(), (random.next() * 256).toInt(), (random.next() * 256).toInt()) },
            40, 30,
        )
        val want = listOf(
            Triple(0.0, 0.0, 234) to "39|149|71|133|18 % серого|39 % · 18 % серого|R 149 · G 71 · B 133",
            Triple(0.0, 0.0, 255) to "36|149|71|133|-|36 %|R 149 · G 71 · B 133",
            Triple(39.0, 29.0, 234) to "36|103|72|154|-|36 %|R 103 · G 72 · B 154",
            Triple(39.0, 29.0, 255) to "33|103|72|154|-|33 %|R 103 · G 72 · B 154",
            Triple(20.0, 15.0, 234) to "48|119|114|94|-|48 %|R 119 · G 114 · B 94",
            Triple(20.0, 15.0, 255) to "44|119|114|94|18 % серого|44 % · 18 % серого|R 119 · G 114 · B 94",
            Triple(3.5, 4.5, 234) to "64|141|153|140|-|64 %|R 141 · G 153 · B 140",
            Triple(3.5, 4.5, 255) to "58|141|153|140|кожа|58 % · кожа|R 141 · G 153 · B 140",
            Triple(-3.0, -3.0, 234) to "39|149|71|133|18 % серого|39 % · 18 % серого|R 149 · G 71 · B 133",
            Triple(1.0, 1.0, 234) to "51|152|109|119|-|51 %|R 152 · G 109 · B 119",
            Triple(1.0, 1.0, 255) to "46|152|109|119|-|46 %|R 152 · G 109 · B 119",
            Triple(38.0, 2.0, 255) to "57|137|149|135|кожа|57 % · кожа|R 137 · G 149 · B 135",
            Triple(12.99, 7.01, 234) to "57|146|134|106|кожа|57 % · кожа|R 146 · G 134 · B 106",
            Triple(12.99, 7.01, 255) to "53|146|134|106|кожа|53 % · кожа|R 146 · G 134 · B 106",
            Triple(Double.NaN, 5.0, 234) to "60|107|151|125|кожа|60 % · кожа|R 107 · G 151 · B 125",
            Triple(Double.NaN, 5.0, 255) to "55|107|151|125|кожа|55 % · кожа|R 107 · G 151 · B 125",
        )
        for ((at, line) in want) {
            val r = probe(f, at.first, at.second, at.third)
            val got = "${r.percent}|${r.r}|${r.g}|${r.b}|${r.zone?.title ?: "-"}|${r.lines.first}|${r.lines.second}"
            assertEquals("точка (${at.first}; ${at.second}), белое ${at.third}", line, got)
        }
    }

    // край кадра

    /**
     * Кадр 1024×1024 (фото 1:1), цвет зависит от места: красный — от столбца, зелёный — от строки, синий ровный. Среднее
     * обрезанного квадрата отличается и от сдвинутого внутрь, и от квадрата с лишней строкой.
     */
    private fun positional() = frame(1024, 1024) { x, y -> px((x * 10) and 255, (y * 10) and 255, 7) }

    @Test fun probeClipsTheSquareAtTheEdgeOfTheFrame() {
        // точка (1; 1): квадрат 5×5 обрезан до столбцов и строк 0…3
        val f = positional()
        val reading = probe(f, 1.0, 1.0)
        // красный по столбцам 0, 10, 20, 30 → 15; зелёный так же; синий 7
        assertEquals(15, reading.r)
        assertEquals(15, reading.g)
        assertEquals(7, reading.b)
        val want = reference(f, 0..3, 0..3, 255)
        assertTrue(reading.r == want.r && reading.g == want.g && reading.b == want.b)
        assertEquals(want.percent, reading.percent)
    }

    @Test fun probeClipsTheSquareAtEveryEdgeAndCorner() {
        val f = positional()
        val points = listOf(
            0 to 0, 1 to 0, 0 to 1, 2 to 2, 1023 to 1023, 1022 to 1, 2 to 1021, 1023 to 0, 0 to 1023,
            512 to 0, 512 to 1023, 0 to 512, 1023 to 512, 1021 to 1021, 3 to 3,
        )
        for ((x, y) in points) {
            val (xs, ys) = box(f, x, y)
            val want = reference(f, xs, ys, 234)
            val got = probe(f, x.toDouble(), y.toDouble(), 234)
            assertTrue("($x; $y)", got.r == want.r && got.g == want.g && got.b == want.b)
            assertEquals("($x; $y)", want.percent, got.percent)
        }
    }

    @Test fun probeClipsPointsOutsideTheFrameToTheNearestPixel() {
        val f = positional()
        assertEquals(probe(f, 0.0, 0.0), probe(f, -10.0, -10.0))
        assertEquals(probe(f, 0.0, 3.0), probe(f, -0.5, 3.0))
        assertEquals(probe(f, 1023.0, 1023.0), probe(f, 5000.0, 5000.0))
        assertEquals(probe(f, 1023.0, 7.0), probe(f, 1024.0, 7.0))
        assertEquals(probe(f, 1023.0, 0.0), probe(f, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY))
        assertEquals(probe(f, 1023.0, 0.0), probe(f, 1e300, -1e300))
        // не число — как 0, и не падаем
        assertEquals(probe(f, 0.0, 0.0), probe(f, Double.NaN, Double.NaN))
    }

    @Test fun probeWorksOnNonSixteenByNineFrames() {
        // 3:2 (1024×680) и 640×360: низ и правый край любого кадра
        for ((w, h) in listOf(1024 to 680, 640 to 360, 1 to 1, 3 to 2)) {
            val f = frame(w, h) { x, y -> px(x and 255, y and 255, 90) }
            for ((x, y) in listOf(w - 1 to h - 1, w - 1 to 0, 0 to h - 1, w / 2 to h / 2, w - 2 to h - 2)) {
                if (x < 0 || y < 0) continue
                val (xs, ys) = box(f, x, y)
                val want = reference(f, xs, ys, 255)
                val got = probe(f, x.toDouble(), y.toDouble())
                assertTrue("$w×$h ($x; $y)", got.r == want.r && got.g == want.g && got.b == want.b)
                assertEquals("$w×$h ($x; $y)", want.percent, got.percent)
            }
        }
    }

    @Test fun probeUsesTheFrameWidthAsTheRowStrideAndIgnoresTheTail() {
        // цвет зависит от места, а за последней точкой кадра в массиве — пурпур: неверный шаг строки или чтение хвоста сразу
        // сдвинут среднее
        val f = frame(16, 16, tail = 40) { x, y -> px(x * 10, y * 10, 90) }
        assertEquals(16 * 16 + 40, f.argb.size)
        for ((x, y) in listOf(8 to 8, 15 to 15, 15 to 0, 0 to 15, 14 to 1, 2 to 13, 7 to 3)) {
            val (xs, ys) = box(f, x, y)
            val want = reference(f, xs, ys, 255)
            val got = probe(f, x.toDouble(), y.toDouble())
            assertTrue("($x; $y)", got.r == want.r && got.g == want.g && got.b == want.b)
            assertEquals("($x; $y)", want.percent, got.percent)
        }
    }

    @Test fun probeOfBrokenFramesIsEmptyAndSafe() {
        val empty = ColorProbe.Reading(0, 0, 0, 0, null)
        val pixels = flat(200, 100, 50, 4, 4).argb
        fun broken(width: Int, height: Int, argb: IntArray) = ColorProbe.read(argb, width, height, 1.0, 1.0, 255)
        assertEquals(empty, broken(0, 4, pixels))
        assertEquals(empty, broken(4, 0, pixels))
        assertEquals(empty, broken(-4, 4, pixels))
        assertEquals(empty, broken(4, -4, pixels))
        // массив короче кадра
        assertEquals(empty, broken(4, 5, pixels))
        assertEquals(empty, broken(4, 4, IntArray(0)))
        // размеры, у которых произведение переполняет Int
        assertEquals(empty, broken(65_536, 65_536, pixels))
        assertEquals(empty, broken(Int.MAX_VALUE, 2, pixels))
    }

    @Test fun probeSurvivesAWhiteOfZero() {
        // белого нет — делить на ноль нельзя; считаем как белое 1, процент большой, но число
        val reading = probe(grey(100), 8.0, 8.0, 0)
        assertEquals(10_000, reading.percent)
        assertEquals(FalseColorBand.clipped, reading.zone)
        assertEquals(10_000, probe(grey(100), 8.0, 8.0, -5).percent)
    }
}
