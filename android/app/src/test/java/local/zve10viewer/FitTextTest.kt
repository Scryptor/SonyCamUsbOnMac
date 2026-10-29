package local.zve10viewer

import local.zve10viewer.ui.fitFontSize
import local.zve10viewer.ui.middleEllipsis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Текст в меню «Монитор», который не должен ни обрезаться, ни сливаться: размер шрифта подбирается по настоящему замеру
 * (`fitFontSize` получает функцию «ширина при таком размере»), а длинное имя файла теряет середину (`middleEllipsis`), как на
 * Mac, — у `look_v1` и `look_v2` концы разные.
 */
class FitTextTest {
    /** Ширина растёт с размером: буквы масштабируются, разрядка — нет (в теме Material это 0,5 sp на букву). */
    private fun affine(perSp: Float, spacing: Float) = { size: Float -> perSp * size + spacing }

    // fitFontSize

    @Test fun keepsTheBaseSizeWhenTheTextFits() {
        val measure = affine(6f, 0f)
        assertEquals(19f, fitFontSize(120f, 19f, 12f, measure), 0f) // 114
        assertEquals(19f, fitFontSize(114f, 19f, 12f, measure), 0f) // ровно
        assertEquals(19f, fitFontSize(120f, 19f, 12f) { 0f }, 0f) // пустая строка
    }

    @Test fun shrinksToTheLargestSizeThatFits() {
        val measure = affine(10f, 0f)
        val size = fitFontSize(150f, 19f, 12f, measure)
        assertTrue("$size", measure(size) <= 150f)
        assertTrue("$size", measure(size + 0.25f) > 150f)
        assertEquals(15f, size, 0.25f)
    }

    @Test fun letterSpacingDoesNotShrinkWithTheFont() {
        // «Зеркало 180°»: 12 букв по 0,5 sp разрядки — 6 sp, которые от размера не зависят. Первая версия делила пропорционально
        // и попадала в многоточие: на этих числах так и выходит
        val measure = affine(10f, 6f)
        val available = 150f
        val proportional = 19f * available / measure(19f)
        assertTrue("пропорция даёт ${measure(proportional)} при $available", measure(proportional) > available)
        val size = fitFontSize(available, 19f, 12f, measure)
        assertTrue("$size", measure(size) <= available)
        assertTrue("$size", measure(size + 0.25f) > available)
        assertTrue(size < 19f && size > 12f)
    }

    @Test fun neverBelowTheFloorEvenWhenNothingFits() {
        assertEquals(12f, fitFontSize(120f, 19f, 12f, affine(100f, 0f)), 0f)
        assertEquals(12f, fitFontSize(0f, 19f, 12f, affine(10f, 6f)), 0f)
        assertEquals(12f, fitFontSize(-5f, 19f, 12f, affine(10f, 6f)), 0f)
    }

    @Test fun theFloorItselfIsEnoughWhenItJustFits() {
        val measure = affine(10f, 0f)
        val size = fitFontSize(120f, 19f, 12f, measure) // 120 = 10 × 12
        assertEquals(12f, size, 0.25f)
        assertTrue(measure(size) <= 120f)
    }

    @Test fun hintedWidthsAreRoundedUpAndStillFit() {
        // метрики со ступенями: ширина — целое число точек, округлённое вверх
        val measure = { size: Float -> ceil(7.3f * size + 5.2f) }
        for (available in listOf(120f, 140f, 150f, 161f, 170f)) {
            val size = fitFontSize(available, 19f, 12f, measure)
            assertTrue("$available → $size", measure(size) <= available)
            if (size < 19f) assertTrue("$available → $size", measure(size + 0.25f) > available)
        }
    }

    @Test fun anyAffineMetricEndsOnASizeThatFitsAndIsAlmostTheLargest() {
        val random = Random(21)
        repeat(500) {
            val perSp = 3f + random.nextFloat() * 12f
            val spacing = random.nextFloat() * 12f
            val available = 40f + random.nextFloat() * 200f
            val measure = affine(perSp, spacing)
            val size = fitFontSize(available, 19f, 12f, measure)
            assertTrue("$size", size in 12f..19f)
            if (size > 12f) assertTrue("$perSp $spacing $available → $size", measure(size) <= available)
            if (size < 19f && size > 12f) assertTrue("$perSp $spacing $available → $size", measure(size + 0.25f) > available)
        }
    }

    @Test fun titlesUseTheSameRuleWithTheirOwnSizes() {
        // заголовок плитки: 14 sp, не меньше 10; в узкой плитке (≈84 dp, места под текст 72) «Цвет пикинга» сжимается и умещается
        val measure = affine(6f, 6f) // 12 букв, 0,5 sp разрядки на каждую
        val size = fitFontSize(72f, 14f, 10f, measure)
        assertTrue("$size", measure(size) <= 72f)
        assertEquals(11f, size, 0.25f)
    }

    @Test fun measuresAFewTimesOnly() {
        var calls = 0
        fitFontSize(150f, 19f, 12f) { size ->
            calls += 1
            affine(10f, 6f)(size)
        }
        assertTrue("замеров: $calls", calls <= 10)
    }

    @Test fun measuresTwiceWhenEvenTheFloorDoesNotFit() {
        // длинное имя файла на плитке не умещается и на самом малом размере: лишних замеров не нужно
        var calls = 0
        val size = fitFontSize(50f, 19f, 12f) { sized ->
            calls += 1
            affine(10f, 6f)(sized)
        }
        assertEquals(12f, size, 0f)
        assertEquals(2, calls)
    }

    @Test fun anUnreadableWidthCountsAsFitting() {
        assertEquals(19f, fitFontSize(100f, 19f, 12f) { Float.NaN }, 0f)
        assertEquals(19f, fitFontSize(Float.POSITIVE_INFINITY, 19f, 12f, affine(10f, 6f)), 0f)
    }

    @Test fun aFloorAtOrAboveTheBaseChangesNothing() {
        assertEquals(14f, fitFontSize(10f, 14f, 14f, affine(10f, 0f)), 0f)
        assertEquals(14f, fitFontSize(10f, 14f, 20f, affine(10f, 0f)), 0f)
    }

    // middleEllipsis

    private fun upTo(letters: Int) = { text: String -> text.length <= letters }

    @Test fun shortNamesAreKept() {
        assertEquals("Warm", middleEllipsis("Warm", upTo(10)))
        assertEquals("Warm", middleEllipsis("Warm", upTo(4)))
        assertEquals("", middleEllipsis("", upTo(0)))
    }

    @Test fun longNamesLoseTheirMiddleAndKeepBothEnds() {
        // 30 букв, места на 12: «Kodak » (6) + многоточие + «n two» (5)
        assertEquals("Kodak …n two", middleEllipsis("Kodak 2383 Print Emulation two", upTo(12)))
    }

    @Test fun theEndsOfSimilarNamesStayDifferent() {
        val first = middleEllipsis("look_v1", upTo(5))
        val second = middleEllipsis("look_v2", upTo(5))
        assertEquals("lo…v1", first)
        assertEquals("lo…v2", second)
        assertNotEquals(first, second)
        // и с очень длинным общим началом
        val long1 = middleEllipsis("Kodak_2383_Print_Emulation_look_v1", upTo(14))
        val long2 = middleEllipsis("Kodak_2383_Print_Emulation_look_v2", upTo(14))
        assertEquals("Kodak_2…ook_v1", long1)
        assertEquals("Kodak_2…ook_v2", long2)
    }

    @Test fun theHeadGetsTheExtraLetterWhenTheCountIsOdd() {
        assertEquals("abc…yz", middleEllipsis("abcdefghijklmnopqrstuvwxyz", upTo(6)))
        assertEquals("ab…yz", middleEllipsis("abcdefghijklmnopqrstuvwxyz", upTo(5)))
    }

    @Test fun withNoRoomOnlyTheEllipsisIsLeft() {
        assertEquals("…", middleEllipsis("anything long", upTo(1)))
        assertEquals("…", middleEllipsis("anything long", upTo(0)))
    }

    @Test fun theResultFillsTheRoomAndKeepsTheEndsOfTheName() {
        val random = Random(5)
        repeat(300) {
            val name = String(CharArray(1 + random.nextInt(60)) { 'a' + random.nextInt(26) })
            val limit = 1 + random.nextInt(40)
            val shown = middleEllipsis(name, upTo(limit))
            if (name.length <= limit) {
                assertEquals(name, shown)
            } else {
                assertEquals("$name / $limit → $shown", limit, shown.length)
                val (head, tail) = shown.split('…')
                assertTrue("$name / $limit → $shown", name.startsWith(head) && name.endsWith(tail))
                assertTrue("$name / $limit → $shown", abs(head.length - tail.length) <= 1)
            }
        }
    }

    @Test fun wideLettersAreMeasuredNotCounted() {
        // «ширина» — не число букв: широкие W занимают по три единицы, остальные — по одной
        fun width(text: String): Int = text.map { if (it == 'W') 3 else 1 }.sum()
        val shown = middleEllipsis("WWWWWWWWWW_tail") { width(it) <= 12 }
        assertEquals("WWW…il", shown)
        assertTrue(width(shown) <= 12)
    }

    @Test fun surrogatePairsAreNotSplit() {
        val name = "ab😀😀cd😀😀ef"
        for (limit in 1..name.length) {
            val shown = middleEllipsis(name, upTo(limit))
            assertTrue("$limit: $shown", shown.length <= maxOf(limit, 1))
            for (i in shown.indices) {
                if (shown[i].isHighSurrogate()) assertTrue("$limit: $shown", i + 1 < shown.length && shown[i + 1].isLowSurrogate())
                if (shown[i].isLowSurrogate()) assertTrue("$limit: $shown", i > 0 && shown[i - 1].isHighSurrogate())
            }
        }
    }
}
