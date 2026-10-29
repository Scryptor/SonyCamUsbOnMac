package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.Chromaticity
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.LutChoice
import local.zve10viewer.monitor.MonitorAssist.LutMode
import local.zve10viewer.monitor.MonitorTool
import local.zve10viewer.monitor.SonyCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Кривые Sony и HLG, охваты, встроенные LUT, куб и «Авто» — те же проверки и числа, что у программы для Mac
 * (LUTTests.swift). Проверка «куб 33³ в пределах 0,01 от точного пути на случайных точках» невыполнима (обрезка каналов
 * матрицей внутри ячеек); здесь, как и там, вместо неё четыре проверки: узлы, серая ось, плоская функция, восемь цветов.
 */
class LutTest {
    // помощники

    private fun near(a: Double, b: Double, eps: Double) = abs(a - b) <= eps

    private fun near(a: List<Double>, b: List<Double>, eps: Double) =
        a.size == b.size && a.indices.all { abs(a[it] - b[it]) <= eps }

    private fun near(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>, eps: Float) =
        abs(a.first - b.first) <= eps && abs(a.second - b.second) <= eps && abs(a.third - b.third) <= eps

    /** Наибольшее расхождение по трём каналам. */
    private fun distance(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>): Float =
        max(abs(a.first - b.first), max(abs(a.second - b.second), abs(a.third - b.third)))

    private fun distance(a: Triple<Float, Float, Float>, b: Triple<Double, Double, Double>): Double =
        max(abs(a.first.toDouble() - b.first), max(abs(a.second.toDouble() - b.second), abs(a.third.toDouble() - b.third)))

    /**
     * Случайные числа без сюрпризов — «seed 1», как в LUTTests.swift: те же точки в каждом прогоне, на каждой машине и
     * в обеих программах (Int в Kotlin считает по модулю 2³², как UInt32 в Swift).
     */
    private class LutRandom {
        private var seed = 1

        /** 0 ≤ x < 1. */
        fun next(): Double {
            seed = seed * 1_664_525 + 1_013_904_223
            return (seed ushr 8).toDouble() / 16_777_216.0
        }
    }

    private val rec709Primaries = listOf(Chromaticity(0.64, 0.33), Chromaticity(0.30, 0.60), Chromaticity(0.15, 0.06))

    private val allGamuts get() = listOf(SonyCurves.sGamut3Cine, SonyCurves.sGamut3, SonyCurves.bt2020)

    // кривые Sony и HLG

    @Test fun slog3MapsGreyBlackAndNinetyPercent() {
        // 18 % серого — код 420, чёрный — код 95, 90 % отражения — код 598
        assertEquals(0.18, SonyCurves.slog3ToLinear(420.0 / 1023), 0.005)
        assertEquals(0.0, SonyCurves.slog3ToLinear(95.0 / 1023), 0.0005)
        assertEquals(0.90, SonyCurves.slog3ToLinear(598.0 / 1023), 0.01)
    }

    @Test fun slog2MapsGrey() {
        assertEquals(0.18, SonyCurves.slog2ToLinear(347.0 / 1023), 0.01)
    }

    @Test fun hlgMapsDiffuseWhiteToOne() {
        // 75 % сигнала HLG — диффузный белый
        assertEquals(1.0, SonyCurves.hlgToLinear(721.0 / 1023), 0.01)
    }

    @Test fun curvesMatchTheNumbersOfTheMacProgram() {
        // Числа напечатаны кодом Mac (SonyCurves.swift), 17 знаков. Коды стоят по обе стороны каждого стыка кривой (S-Log3 —
        // 171,21; S-Log2 — 90,28; HLG — 502). Kotlin считает pow и exp по-своему: допуск — последний знак, а не побитно
        fun check(name: String, curve: (Double) -> Double, points: List<Pair<Int, Double>>) {
            for ((code, expected) in points) {
                assertEquals("$name, код $code", expected, curve(code / 1023.0), 1e-13 * max(1.0, abs(expected)))
            }
        }
        check(
            "S-Log3", SonyCurves::slog3ToLinear,
            listOf(
                96 to 0.00014761785196256547, 150 to 0.0081189818579411009, 171 to 0.011218956749154976, 172 to 0.011398278715192155,
                300 to 0.056048443514718861, 420 to 0.17999999999999999, 598 to 0.90083967040902113, 1023 to 38.420934337202539,
            ),
        )
        check(
            "S-Log2", SonyCurves::slog2ToLinear,
            listOf(
                64 to -0.0076299884182030719, 90 to -8.1601321428879966e-05, 91 to 0.00020917904516243744, 92 to 0.00050166167511338927,
                200 to 0.04527988941715462, 347 to 0.17952755490710892, 800 to 3.5146534589336103, 1023 to 13.758274097347462,
            ),
        )
        check(
            "HLG", SonyCurves::hlgToLinear,
            listOf(
                64 to 0.0, 100 to 0.0021267752281444955, 300 to 0.091398821841617142, 501 to 0.31338590937000477,
                502 to 0.31482181085505601, 503 to 0.31626395072231012, 721 to 1.0009919145689465, 1023 to 6.3545542110700595,
            ),
        )
        for ((x, expected) in listOf(
            0.1 to 0.10000000000000001, 0.55 to 0.55000000000000004, 0.6 to 0.59999999999999998, 0.61 to 0.60987603518866695,
            0.9 to 0.81105337890359419, 1.0 to 0.85284822353142298, 2.0 to 0.98792104663107261, 10.0 to 0.99999999997510347,
        )) {
            assertEquals("плечо $x", expected, SonyCurves.shoulder(x), 1e-14)
        }
        for ((x, expected) in listOf(
            0.001 to 0.0045000000000000005, 0.0179 to 0.080549999999999997, 0.018 to 0.081247944035140462,
            0.0181 to 0.081697877417346548, 0.18 to 0.40900772886415038, 0.5 to 0.7055150899221212, 0.99 to 0.99504082915652847,
            1.0 to 1.0,
        )) {
            assertEquals("BT.709 $x", expected, SonyCurves.bt709(x), 1e-14)
        }
    }

    @Test fun hlgBelowLegalBlackIsBlack() {
        // E′ < 0 в формуле возводится в квадрат — без обрезки «ниже чёрного» дало бы свет, а не темноту
        for (code in listOf(0.0, 16.0, 32.0, 63.0, 64.0)) {
            assertEquals("код $code", 0.0, SonyCurves.hlgToLinear(code / 1023), 1e-12)
        }
    }

    @Test fun curvesAreContinuousAtTheirJoints() {
        val eps = 1e-9
        // S-Log3: линейный участок переходит в степенной на коде 171,2102946929
        val slog3 = 171.2102946929 / 1023
        assertEquals(SonyCurves.slog3ToLinear(slog3 - eps), SonyCurves.slog3ToLinear(slog3 + eps), 1e-6)
        // S-Log2: стык на y = 0,030001222851889303
        val slog2 = (0.030001222851889303 * 876 + 64) / 1023
        assertEquals(SonyCurves.slog2ToLinear(slog2 - eps), SonyCurves.slog2ToLinear(slog2 + eps), 1e-6)
        // HLG: стык на E′ = 0,5, это код 502
        val hlg = 502.0 / 1023
        assertEquals(SonyCurves.hlgToLinear(hlg - eps), SonyCurves.hlgToLinear(hlg + eps), 1e-6)
    }

    @Test fun curvesNeverGoDownWithTheCode() {
        val curves = listOf(
            "S-Log3" to SonyCurves::slog3ToLinear, "S-Log2" to SonyCurves::slog2ToLinear, "HLG" to SonyCurves::hlgToLinear,
        )
        for ((name, curve) in curves) {
            var previous = curve(0.0)
            var goesDown = false
            for (code in 1..1023) {
                val value = curve(code.toDouble() / 1023)
                if (value < previous) goesDown = true
                previous = value
            }
            assertFalse("$name не должна убывать", goesDown)
        }
    }

    // мягкое плечо и кодирование BT.709

    @Test fun shoulderKeepsEverythingUpToTheKneeAndSoftensAbove() {
        assertEquals(0.5, SonyCurves.shoulder(0.5), 0.0)
        assertEquals(0.6, SonyCurves.shoulder(0.6), 0.0)
        assertEquals(0.811, SonyCurves.shoulder(0.9), 0.005)
        // ниже нуля света нет
        assertEquals(0.0, SonyCurves.shoulder(0.0), 0.0)
        assertEquals(0.0, SonyCurves.shoulder(-3.0), 0.0)
    }

    @Test fun shoulderIsContinuousAndMonotonicOnZeroToForty() {
        val step = 0.001
        var previous = SonyCurves.shoulder(0.0)
        var goesDown = false
        var jumps = false
        var x = step
        while (x <= 40) {
            val y = SonyCurves.shoulder(x)
            if (y < previous) goesDown = true
            // наклон не больше 1, так что за шаг кривая прибавляет не больше шага: скачка нет
            if (y - previous > step + 1e-12) jumps = true
            previous = y
            x += step
        }
        assertFalse(goesDown)
        assertFalse(jumps)
    }

    @Test fun shoulderNeverReachesPastOne() {
        assertTrue(SonyCurves.shoulder(40.0) <= 1)
        assertTrue(SonyCurves.shoulder(5.0) > 0.99)
    }

    @Test fun bt709EncodesGreyAndClipsToZeroOne() {
        assertEquals(0.409, SonyCurves.bt709(0.18), 0.005)
        // линейный участок у чёрного: 4,5·0,01
        assertEquals(0.045, SonyCurves.bt709(0.01), 1e-12)
        assertEquals(1.0, SonyCurves.bt709(1.0), 1e-12)
        assertEquals(0.0, SonyCurves.bt709(0.0), 0.0)
        assertEquals(0.0, SonyCurves.bt709(-0.5), 0.0)
        assertEquals(1.0, SonyCurves.bt709(3.0), 0.0)
    }

    // матрицы охватов

    @Test fun gamutMatricesMatchTheSpecFirstRows() {
        val cine = SonyCurves.matrixTo709(SonyCurves.sGamut3Cine)
        val sGamut3 = SonyCurves.matrixTo709(SonyCurves.sGamut3)
        val bt2020 = SonyCurves.matrixTo709(SonyCurves.bt2020)
        assertTrue(near(cine[0], listOf(1.626947, -0.540139, -0.086809), 1e-5))
        assertTrue(near(sGamut3[0], listOf(1.877915, -0.794169, -0.083746), 1e-5))
        assertTrue(near(bt2020[0], listOf(1.660491, -0.587641, -0.072850), 1e-5))
    }

    @Test fun gamutMatricesKeepGreyGrey() {
        // сумма строки — 1: (1; 1; 1) переходит в (1; 1; 1)
        for (primaries in allGamuts) {
            val matrix = SonyCurves.matrixTo709(primaries)
            assertEquals(3, matrix.size)
            for (row in matrix) {
                assertEquals(3, row.size)
                assertEquals(1.0, row.sum(), 1e-9)
            }
        }
    }

    @Test fun matrixFromRec709ToRec709IsIdentity() {
        // проверка самой алгебры, без чисел Sony
        val matrix = SonyCurves.matrixTo709(rec709Primaries)
        for ((i, row) in matrix.withIndex()) {
            for ((j, value) in row.withIndex()) {
                assertEquals("[$i][$j]", if (i == j) 1.0 else 0.0, value, 1e-9)
            }
        }
    }

    @Test fun primariesAreRedGreenBlue() {
        assertEquals(3, SonyCurves.sGamut3Cine.size)
        assertEquals(3, SonyCurves.sGamut3.size)
        assertEquals(3, SonyCurves.bt2020.size)
        // красный правее зелёного и синего, зелёный выше всех
        for (primaries in allGamuts) {
            assertTrue(primaries[0].x > primaries[1].x && primaries[0].x > primaries[2].x)
            assertTrue(primaries[1].y > primaries[0].y && primaries[1].y > primaries[2].y)
        }
    }

    @Test fun matrixNeedsThreePrimaries() {
        // ошибка программиста, а не данных: основных цветов не три или все на одной прямой
        assertThrowsIllegalArgument { SonyCurves.matrixTo709(rec709Primaries.take(2)) }
        assertThrowsIllegalArgument { SonyCurves.matrixTo709(listOf(Chromaticity(0.1, 0.1), Chromaticity(0.2, 0.2), Chromaticity(0.3, 0.3))) }
    }

    private fun assertThrowsIllegalArgument(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Ожидалось IllegalArgumentException")
    }

    // встроенные LUT: названия

    @Test fun builtinLutsKeepTheirOrderRawValuesAndLabels() {
        assertEquals(listOf(BuiltinLut.slog3cine, BuiltinLut.slog3, BuiltinLut.slog2, BuiltinLut.hlg), BuiltinLut.entries)
        assertEquals(listOf("slog3cine", "slog3", "slog2", "hlg"), BuiltinLut.entries.map { it.value })
        assertEquals(listOf("S-Log3 Cine", "S-Log3", "S-Log2", "HLG"), BuiltinLut.entries.map { it.label })
    }

    @Test fun builtinLutsAreTheMonitorTileOptions() {
        // те же значения и подписи, что в плитке «LUT»
        val options = MonitorTool.lut.options
        for (lut in BuiltinLut.entries) {
            assertNotNull(LutMode.entries.firstOrNull { it.value == lut.value })
            assertEquals(lut.label, options.first { it.first == lut.value }.second)
        }
    }

    // встроенные LUT: контрольные точки спецификации

    private fun grey(lut: BuiltinLut, code: Double): Triple<Double, Double, Double> {
        val v = code / 1023
        return lut.transform(v, v, v)
    }

    private fun assertAll(expected: Double, eps: Double, c: Triple<Double, Double, Double>, message: String = "") {
        assertEquals(message, expected, c.first, eps)
        assertEquals(message, expected, c.second, eps)
        assertEquals(message, expected, c.third, eps)
    }

    @Test fun builtinSLog3ControlPoints() {
        for (lut in listOf(BuiltinLut.slog3cine, BuiltinLut.slog3)) {
            // код 420 — 0,409 на всех каналах (104 из 255)
            assertAll(0.409, 0.005, grey(lut, 420.0), "${lut.label} 420")
            // код 95 — чёрный
            assertAll(0.0, 1e-9, grey(lut, 95.0), "${lut.label} 95")
            // 90 % отражения — 0,90
            assertAll(0.90, 0.01, grey(lut, 598.0), "${lut.label} 598")
        }
    }

    @Test fun builtinSLog2AndHlgControlPoints() {
        assertAll(0.409, 0.01, grey(BuiltinLut.slog2, 347.0))
        // код 721 (E′ = 0,75) — 0,924
        assertAll(0.924, 0.01, grey(BuiltinLut.hlg, 721.0))
    }

    /** Цвет на входе и то, что должно выйти. */
    private class ColourCase(val lut: BuiltinLut, val input: Triple<Double, Double, Double>, val expected: Triple<Double, Double, Double>)

    @Test fun builtinColoursMatchAnIndependentCalculation() {
        // Не серые цвета: на них видны порядок каналов, ориентация матрицы и обрезка. Числа посчитаны отдельной программой
        // (numpy) по формулам спецификации, а не этим кодом; те же числа проверяет и программа для Mac.
        val cases = listOf(
            ColourCase(BuiltinLut.slog3cine, Triple(0.55, 0.45, 0.40), Triple(0.904786107, 0.449988142, 0.326711064)),
            ColourCase(BuiltinLut.slog3, Triple(0.55, 0.45, 0.40), Triple(0.926613357, 0.443314084, 0.347649793)),
            ColourCase(BuiltinLut.slog2, Triple(0.55, 0.45, 0.40), Triple(0.948135884, 0.592248229, 0.492662053)),
            ColourCase(BuiltinLut.hlg, Triple(0.70, 0.60, 0.55), Triple(0.961372557, 0.703826603, 0.627341558)),
            // после матрицы зелёный уходит под ноль и обрезается
            ColourCase(BuiltinLut.slog3cine, Triple(0.60, 0.30, 0.35), Triple(0.985393493, 0.0, 0.225136934)),
            ColourCase(BuiltinLut.slog3, Triple(0.60, 0.30, 0.35), Triple(0.992117385, 0.0, 0.256647822)),
            ColourCase(BuiltinLut.slog2, Triple(0.60, 0.30, 0.35), Triple(0.993188767, 0.0, 0.410526726)),
            ColourCase(BuiltinLut.hlg, Triple(0.80, 0.40, 0.45), Triple(0.999255595, 0.0, 0.475195176)),
        )
        for (c in cases) {
            val out = c.lut.transform(c.input.first, c.input.second, c.input.third)
            val worst = max(abs(out.first - c.expected.first), max(abs(out.second - c.expected.second), abs(out.third - c.expected.third)))
            assertTrue("${c.lut} ${c.input}: расхождение $worst", worst < 1e-6)
        }
    }

    @Test fun builtinBlackStaysBlackAndWhiteStaysWhite() {
        for (lut in BuiltinLut.entries) {
            val black = lut.transform(0.0, 0.0, 0.0)
            assertTrue("${lut.label}: $black", black.first == 0.0 && black.second == 0.0 && black.third == 0.0)
            // код 1023 — далеко за диффузным белым: плечо прижимает к 1
            assertAll(1.0, 1e-5, lut.transform(1.0, 1.0, 1.0), lut.label)
        }
    }

    @Test fun builtinKeepsGreyGreyAndGoesUpWithTheCode() {
        for (lut in BuiltinLut.entries) {
            var previous = -1.0
            var goesDown = false
            var colouredGrey = false
            for (code in 0..1023 step 3) {
                val out = grey(lut, code.toDouble())
                // сумма строки матрицы — 1, поэтому серый на входе — серый на выходе
                if (abs(out.first - out.second) > 1e-9 || abs(out.second - out.third) > 1e-9) colouredGrey = true
                if (out.first < previous - 1e-12) goesDown = true
                previous = out.first
            }
            assertFalse(lut.label, colouredGrey)
            assertFalse(lut.label, goesDown)
        }
    }

    @Test fun builtinOutputStaysInsideZeroOne() {
        // насыщенные цвета выходят за охват 709: каналы в минусе обрезаются, а не уходят под ноль
        for (lut in BuiltinLut.entries) {
            val random = LutRandom()
            var outside = false
            for (i in 0 until 2000) {
                val out = lut.transform(random.next(), random.next(), random.next())
                if (out.first < 0 || out.first > 1 || out.second < 0 || out.second > 1 || out.third < 0 || out.third > 1) outside = true
            }
            assertFalse(lut.label, outside)
        }
    }

    @Test fun builtinGamutsTurnAPrimaryDifferently() {
        // одни и те же коды S-Log3 в разных охватах — разные цвета; серый при этом общий
        val cine = BuiltinLut.slog3cine.transform(0.55, 0.4, 0.4)
        val sGamut3 = BuiltinLut.slog3.transform(0.55, 0.4, 0.4)
        assertTrue(abs(cine.first - sGamut3.first) > 0.01)
    }

    // куб 33³

    @Test fun identityCubeReturnsItsInputExactly() {
        val cube = ColorLut.identity(2)
        assertEquals(Triple(0.3f, 0.6f, 0.9f), cube.apply(0.3f, 0.6f, 0.9f))
    }

    @Test fun identityCubeHasTheRightShape() {
        val cube = ColorLut.identity(5)
        assertEquals(5, cube.size)
        assertEquals(5 * 5 * 5 * 3, cube.values.size)
        assertEquals(listOf(0f, 0f, 0f), cube.domainMin.toList())
        assertEquals(listOf(1f, 1f, 1f), cube.domainMax.toList())
        assertEquals(ColorLut.identity(5), cube)
        assertEquals(ColorLut.identity(5).hashCode(), cube.hashCode())
        assertNotEquals(ColorLut.identity(6), cube)
    }

    @Test fun cubeEqualityLooksAtEveryNumber() {
        val identity = ColorLut.identity(3)
        val changed = identity.values.copyOf().also { it[40] += 0.25f }
        assertNotEquals(identity, ColorLut(3, changed))
        // и домен входит в равенство
        assertNotEquals(identity, ColorLut(3, identity.values.copyOf(), floatArrayOf(0f, 0f, 0f), floatArrayOf(2f, 1f, 1f)))
        assertEquals(identity, ColorLut(3, identity.values.copyOf()))
    }

    @Test fun cubeNeedsItsShape() {
        // size ≥ 2, size³·3 чисел, домен — по числу на канал: иначе ошибка программиста, а не данных
        assertThrowsIllegalArgument { ColorLut(1, FloatArray(3)) }
        assertThrowsIllegalArgument { ColorLut(2, FloatArray(8 * 3 - 1)) }
        assertThrowsIllegalArgument { ColorLut(2, FloatArray(8 * 3 + 1)) }
        assertThrowsIllegalArgument { ColorLut(2, FloatArray(8 * 3), floatArrayOf(0f, 0f), floatArrayOf(1f, 1f, 1f)) }
        assertThrowsIllegalArgument { ColorLut(2, FloatArray(8 * 3), floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 1f, 1f, 1f)) }
        assertThrowsIllegalArgument { ColorLut.identity(1) }
        // размер, у которого size³ не влезает в Int, не должен «совпасть» по переполнению
        assertThrowsIllegalArgument { ColorLut(2_000_000, FloatArray(24)) }
    }

    @Test fun identityCubeOfAnySizeFollowsItsInput() {
        val random = LutRandom()
        for (size in listOf(2, 3, 17, 33, 65)) {
            val cube = ColorLut.identity(size)
            var worst = 0f
            for (i in 0 until 200) {
                val r = random.next().toFloat()
                val g = random.next().toFloat()
                val b = random.next().toFloat()
                worst = max(worst, distance(cube.apply(r, g, b), Triple(r, g, b)))
            }
            assertTrue("размер $size: $worst", worst < 1e-6)
        }
    }

    @Test fun cubeOfEqualNodesReturnsThemExactly() {
        // a + (b − a)·t, а не a·(1 − t) + b·t: у равных углов получается ровно их значение, без шума округления
        val random = LutRandom()
        for (value in listOf(0.3f, 0.7f, 0.1f, 0.9f, 1f / 3)) {
            val cube = ColorLut(3, FloatArray(27 * 3) { value })
            for (i in 0 until 300) {
                val got = cube.apply(random.next().toFloat(), random.next().toFloat(), random.next().toFloat())
                assertEquals("узлы $value", Triple(value, value, value), got)
            }
        }
    }

    @Test fun cubeStoresRedFirstThenGreenThenBlue() {
        val cube = ColorLut.identity(2)
        // индекс = (синий·2 + зелёный)·2 + красный; в тождественном кубе углы — это сами индексы
        assertEquals(listOf(0f, 0f, 0f), cube.values.slice(0 until 3))
        assertEquals(listOf(1f, 0f, 0f), cube.values.slice(3 until 6))
        assertEquals(listOf(0f, 1f, 0f), cube.values.slice(6 until 9))
        assertEquals(listOf(0f, 0f, 1f), cube.values.slice(12 until 15))
        assertEquals(listOf(1f, 1f, 1f), cube.values.slice(21 until 24))
    }

    @Test fun cubeInterpolatesAnAffineMapExactlyInEveryDirection() {
        // трилинейность точна на плоских функциях: проверяет веса по осям, порядок осей и домен
        val size = 5
        fun affine(r: Float, g: Float, b: Float) = Triple(
            0.05f + 0.1f * r + 0.3f * g + 0.5f * b, 0.9f - 0.2f * r + 0.05f * g - 0.4f * b, 0.1f + 0.6f * r - 0.1f * g + 0.2f * b,
        )
        val values = ArrayList<Float>()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val out = affine(ri / 4f, gi / 4f, bi / 4f)
                    values += listOf(out.first, out.second, out.third)
                }
            }
        }
        val domainMin = floatArrayOf(-1f, 0.2f, 0f)
        val domainMax = floatArrayOf(3f, 0.8f, 0.5f)
        val cube = ColorLut(size, values.toFloatArray(), domainMin, domainMax)
        val random = LutRandom()
        var worst = 0f
        for (i in 0 until 500) {
            // и за пределами домена: вход обрезается по нему
            val r = random.next().toFloat() * 4.4f - 1.2f
            val g = random.next().toFloat() * 0.9f + 0.1f
            val b = random.next().toFloat() * 0.7f - 0.1f
            fun unit(v: Float, i: Int) = min(max((v - domainMin[i]) / (domainMax[i] - domainMin[i]), 0f), 1f)
            val expected = affine(unit(r, 0), unit(g, 1), unit(b, 2))
            worst = max(worst, distance(cube.apply(r, g, b), expected))
        }
        assertTrue("расхождение $worst", worst < 1e-5)
    }

    @Test fun cubeClampsInputAndSurvivesNotANumber() {
        val cube = ColorLut.identity(33)
        assertEquals(Triple(0f, 1f, 0.5f), cube.apply(-1f, 2f, 0.5f))
        // NaN и бесконечность не роняют и не дают «мусор»
        assertEquals(Triple(0f, 1f, 0f), cube.apply(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY))
    }

    @Test fun cubeSurvivesADegenerateDomain() {
        // домен без ширины (красный) или перевёрнутый (зелёный) — файл плохой, но деления на ноль и «мусора» быть не должно:
        // такой канал стоит в начале домена, а нормальный (синий) считается как всегда
        val values = ColorLut.identity(3).values
        val cube = ColorLut(3, values, floatArrayOf(1f, 1f, 1f), floatArrayOf(1f, 0f, 2f))
        assertEquals(Triple(0f, 0f, 0.5f), cube.apply(0.5f, 0.5f, 1.5f))
        assertEquals(Triple(0f, 0f, 1f), cube.apply(7f, 7f, 7f))
    }

    @Test fun cubeClampsOutputToZeroOne() {
        // .cube бывает с выходом чуть за 0…1: на экране выход — 0…1
        val values = ColorLut.identity(2).values
        values[0] = -0.2f
        values[23] = 1.4f
        val cube = ColorLut(2, values)
        assertEquals(Triple(0f, 0f, 0f), cube.apply(0f, 0f, 0f))
        assertEquals(Triple(1f, 1f, 1f), cube.apply(1f, 1f, 1f))
    }

    @Test fun makeStoresTheExactPathAtEveryNode() {
        // в узлах куб равен точному пути (до округления до Float); попутно проверяется порядок осей
        val size = 33
        for (lut in BuiltinLut.entries) {
            val cube = lut.make(size)
            val random = LutRandom()
            var worst = 0.0
            for (i in 0 until 1000) {
                val ri = (random.next() * size).toInt()
                val gi = (random.next() * size).toInt()
                val bi = (random.next() * size).toInt()
                val r = ri.toDouble() / (size - 1)
                val g = gi.toDouble() / (size - 1)
                val b = bi.toDouble() / (size - 1)
                val exact = lut.transform(r, g, b)
                val got = cube.apply(r.toFloat(), g.toFloat(), b.toFloat())
                worst = max(worst, distance(got, exact))
            }
            assertTrue("${lut.label}: $worst", worst < 1e-5)
        }
    }

    @Test fun makeFollowsTheExactPathAlongTheGreyAxis() {
        // 1000 случайных серых между узлами. Допуск 0,03, а не 0,01: куб 33³ — это трилинейная выборка очень крутой
        // кривой (степень ×1,32 на ячейку у S-Log3), а на сером в трилинейность входят и узлы вне оси, где мешает
        // обрезка каналов матрицей; худшая ошибка на оси — 0,025 (S-Log3 у 90 % отражения)
        for (lut in BuiltinLut.entries) {
            val cube = lut.make(33)
            val random = LutRandom()
            var worst = 0.0
            for (i in 0 until 1000) {
                val v = random.next()
                val exact = lut.transform(v, v, v)
                val got = cube.apply(v.toFloat(), v.toFloat(), v.toFloat())
                worst = max(worst, distance(got, exact))
            }
            assertTrue("${lut.label}: $worst", worst < 0.03)
        }
    }

    @Test fun makeSmallestCubeHoldsTheCornersOnly() {
        val lut = BuiltinLut.slog3
        val cube = lut.make(2)
        assertEquals(2, cube.size)
        assertEquals(8 * 3, cube.values.size)
        val corner = lut.transform(1.0, 0.0, 1.0)
        assertTrue(near(cube.apply(1f, 0f, 1f), Triple(corner.first.toFloat(), corner.second.toFloat(), corner.third.toFloat()), 1e-6f))
    }

    @Test fun makeDefaultsToThirtyThreeAndNeverGoesBelowTwo() {
        assertEquals(33, BuiltinLut.hlg.make().size)
        assertEquals(65, BuiltinLut.hlg.make(65).size)
        assertEquals(2, BuiltinLut.hlg.make(1).size)
        assertEquals(2, BuiltinLut.hlg.make(-5).size)
    }

    // «Авто»

    private fun auto(pp: Long?, gamma: Long?, colour: Long?) = LutChoice.resolve(LutMode.auto, pp, gamma, colour)

    @Test fun autoByPictureProfileNumberWithoutGamma() {
        // ZV-E10 не отдаёт 0xD0E1: профиль 0xD23F с заводскими гаммами Sony
        assertEquals(BuiltinLut.slog3cine, auto(8, null, null))
        assertEquals(BuiltinLut.slog3, auto(9, null, null))
        assertEquals(BuiltinLut.slog2, auto(7, null, null))
        assertEquals(BuiltinLut.hlg, auto(10, null, null))
        assertNull(auto(1, null, null))
    }

    @Test fun autoIgnoresOtherProfilesAndMissingProfile() {
        for (profile in listOf<Long>(0, 2, 3, 4, 5, 6, 11, 12, 255, -1)) {
            assertNull("PP $profile", auto(profile, null, null))
        }
        assertNull(auto(null, null, null))
    }

    @Test fun autoByGammaAndColourMode() {
        // камера сама говорит гамму (0xD0E1) и охват (0xD0E9)
        assertEquals(BuiltinLut.slog3, auto(null, 0x0303, 0x0009))
        assertEquals(BuiltinLut.slog3cine, auto(null, 0x0303, 0x0008))
        assertEquals(BuiltinLut.slog2, auto(null, 0x0302, null))
        assertEquals(BuiltinLut.hlg, auto(null, 0x0403, null))
        assertNull(auto(null, 0x0201, null))
    }

    @Test fun autoSLog3WithoutAKnownGamutFallsBackToCine() {
        // «иначе S-Gamut3.Cine»: охват не пришёл или это не S-Gamut3 и не S-Gamut
        assertEquals(BuiltinLut.slog3cine, auto(null, 0x0303, null))
        assertEquals(BuiltinLut.slog3cine, auto(null, 0x0303, 0x0008))
        for (colour in listOf<Long>(0x0001, 0x0002, 0x0003, 0x0004, 0x0005, 0x0006, 0x0007, 0x000A, 0x000B, 0x000D, 0x00FF)) {
            assertEquals("режим $colour", BuiltinLut.slog3cine, auto(null, 0x0303, colour))
        }
    }

    @Test fun autoSLog3WithSGamutTakesTheSGamut3Lut() {
        // S-Gamut (0x000C) и S-Gamut3 (0x0009) — одни и те же основные цвета, так что и LUT один
        assertEquals(BuiltinLut.slog3, auto(null, 0x0303, 0x000C))
        assertEquals(BuiltinLut.slog3, auto(null, 0x0303, 0x0009))
        // номер профиля при названной гамме не нужен
        assertEquals(BuiltinLut.slog3, auto(8, 0x0303, 0x000C))
        // S-Log2 от охвата не зависит
        assertEquals(BuiltinLut.slog2, auto(null, 0x0302, 0x000C))
        // а «камера сама приводит к 709» гасит и этот выбор
        assertNull(LutChoice.resolve(LutMode.auto, null, 0x0303, 0x000C, cameraConverts = true))
    }

    @Test fun autoGammaOtherThanLogIsNoLut() {
        // обычные гаммы, Cine, S-Cinetone, ITU709 — своего LUT не просят
        for (gamma in listOf<Long>(0x0001, 0x0002, 0x0003, 0x0101, 0x0102, 0x0103, 0x0104, 0x0201, 0x0202)) {
            assertNull("гамма $gamma", auto(null, gamma, 0x0009))
        }
    }

    @Test fun autoTakesEveryHlgVariant() {
        for (gamma in listOf<Long>(0x0401, 0x0402, 0x0403, 0x0404)) {
            assertEquals("гамма $gamma", BuiltinLut.hlg, auto(null, gamma, null))
        }
        // а соседние коды — нет
        assertNull(auto(null, 0x0400, null))
        assertNull(auto(null, 0x0405, null))
    }

    @Test fun autoGammaBeatsPictureProfileNumber() {
        // гамму камера называет сама — номер профиля тогда не нужен, даже если он говорит другое
        assertNull(auto(8, 0x0201, null))
        assertEquals(BuiltinLut.slog2, auto(1, 0x0302, null))
        assertEquals(BuiltinLut.slog3, auto(10, 0x0303, 0x0009))
    }

    @Test fun explicitModesIgnoreTheProfile() {
        class Profile(val pp: Long?, val gamma: Long?, val colour: Long?)
        val profiles = listOf(
            Profile(null, null, null), Profile(8, null, null), Profile(10, null, null), Profile(1, 0x0201, 0x0009),
            Profile(9, 0x0401, 0x0008),
        )
        for (p in profiles) {
            assertEquals(BuiltinLut.slog3cine, LutChoice.resolve(LutMode.slog3cine, p.pp, p.gamma, p.colour))
            assertEquals(BuiltinLut.slog3, LutChoice.resolve(LutMode.slog3, p.pp, p.gamma, p.colour))
            assertEquals(BuiltinLut.slog2, LutChoice.resolve(LutMode.slog2, p.pp, p.gamma, p.colour))
            assertEquals(BuiltinLut.hlg, LutChoice.resolve(LutMode.hlg, p.pp, p.gamma, p.colour))
        }
    }

    @Test fun offAndFileResolveToNoBuiltin() {
        // свой файл — не встроенный LUT; выключено — нет ничего, какой бы ни был профиль
        assertNull(LutChoice.resolve(LutMode.file, 8, null, null))
        assertNull(LutChoice.resolve(LutMode.file, null, 0x0303, 0x0009))
        assertNull(LutChoice.resolve(LutMode.off, 8, null, null))
        assertNull(LutChoice.resolve(LutMode.off, null, 0x0303, 0x0009))
    }

    @Test fun everyModeIsHandled() {
        // новый вариант LutMode без ветки в resolve не должен пройти молча
        for (mode in LutMode.entries) {
            val resolved = LutChoice.resolve(mode, 8, null, null)
            when (mode) {
                LutMode.off, LutMode.file -> assertNull(mode.value, resolved)
                LutMode.auto -> assertEquals(BuiltinLut.slog3cine, resolved)
                else -> assertEquals(mode.value, resolved?.value)
            }
        }
    }

    @Test fun autoLabelNamesTheResolvedLut() {
        assertEquals("Авто: S-Log3", LutChoice.autoLabel(BuiltinLut.slog3))
        assertEquals("Авто: S-Log3 Cine", LutChoice.autoLabel(BuiltinLut.slog3cine))
        assertEquals("Авто: S-Log2", LutChoice.autoLabel(BuiltinLut.slog2))
        assertEquals("Авто: HLG", LutChoice.autoLabel(BuiltinLut.hlg))
        assertEquals("Авто: нет", LutChoice.autoLabel(null))
    }

    @Test fun cameraDoesNotConvertThePreviewUntilTheDeviceCheck() {
        // пока камерой не проверено, превью считается логарифмическим и «Авто» включает LUT
        assertFalse(LutChoice.cameraConverts)
    }

    @Test fun autoSwitchesOffWhenTheCameraConvertsByItself() {
        // true из проверки на камере: «Авто» ничего не включает, а выбор рукой остаётся
        assertNull(LutChoice.resolve(LutMode.auto, 8, null, null, cameraConverts = true))
        assertNull(LutChoice.resolve(LutMode.auto, null, 0x0303, 0x0009, cameraConverts = true))
        assertEquals(BuiltinLut.hlg, LutChoice.resolve(LutMode.hlg, 8, null, null, cameraConverts = true))
        assertEquals(BuiltinLut.slog3cine, LutChoice.resolve(LutMode.auto, 8, null, null, cameraConverts = false))
    }
}
