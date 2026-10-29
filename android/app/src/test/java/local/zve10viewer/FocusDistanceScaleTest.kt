package local.zve10viewer

import local.zve10viewer.monitor.FocusDistanceScale
import local.zve10viewer.monitor.FocusPosition
import local.zve10viewer.monitor.labelAtFraction
import local.zve10viewer.monitor.metersAtFraction
import local.zve10viewer.monitor.positionScale
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Расстояния на шкале фокуса (спека §7.4): расстояние для места на линейке, подписи под делениями без наложений, метка
 * положения. Те же проверки и числа, что в FocusDistanceScaleTests для Mac.
 */
class FocusDistanceScaleTest {
    /** Данные объектива новой камеры (стенд, профиль newer): 12 пар, положения 0…65535, от 0,25 м до «∞». */
    private fun newerLens(): LensInformation = LensInformation.parse(CameraBytes.fixture("lens_information"))

    /** Таблицы из пар «положение — метры»: LensInformation(tables: [[(0, 0.3), (50, 1)]]) на Mac. */
    private fun lens(vararg tables: List<Pair<Double, Double>>): LensInformation =
        LensInformation(tables.map { table -> table.map { (position, meters) -> LensInformation.Point(position, meters) } })

    /** Ширина подписи в тестах: шесть точек на знак — как у мелкого шрифта на линейке. */
    private fun width(text: String): Double = text.length * 6.0

    /** Что занимает подпись на линейке шириной [ruler]. */
    private fun spans(ticks: List<FocusDistanceScale.Tick>, ruler: Double) =
        ticks.map { FocusDistanceScale.span(it.fraction, width(it.label), ruler) }

    private fun labels(ticks: List<FocusDistanceScale.Tick>) = ticks.map { it.label }

    // MARK: - Расстояние для места на линейке

    @Test fun distanceAtAPlaceOnTheRulerIsReadInTheScaleOfTheTable() {
        val lens = newerLens()
        assertTrue(lens.absoluteScale)
        assertEquals(65535.0, lens.positionScale, 0.0)
        // 29790 из 65535 — пятая точка таблицы, 1 м
        assertEquals(0.25, lens.metersAtFraction(0.0)!!, 0.0)
        assertEquals(1_000_000.0, lens.metersAtFraction(1.0)!!, 0.0)
        val one = lens.metersAtFraction(29790.0 / 65535)!!
        assertTrue(abs(one - 1) < 1e-9)
        assertEquals("1 м", lens.labelAtFraction(29790.0 / 65535))
        assertEquals("0,25 м", lens.labelAtFraction(0.0))
        assertEquals("∞", lens.labelAtFraction(1.0))
        // таблица в шкале 0xD24C (наибольшее положение не больше 100): доля умножается на 100, а не на 65535
        val steps = lens(listOf(0.0 to 0.3, 50.0 to 1.0, 100.0 to 1_000_000.0))
        assertTrue(!steps.absoluteScale)
        assertEquals(100.0, steps.positionScale, 0.0)
        assertEquals(1.0, steps.metersAtFraction(0.5)!!, 0.0)
        assertEquals("1 м", steps.labelAtFraction(0.5))
        assertEquals("0,65 м", steps.labelAtFraction(0.25))
    }

    @Test fun distanceAtAFractionOutsideTheRulerOrWithoutAnswerIsHandledSafely() {
        val lens = newerLens()
        // за краями линейки — ближний или дальний край таблицы
        assertEquals(0.25, lens.metersAtFraction(-0.2)!!, 0.0)
        assertEquals(1_000_000.0, lens.metersAtFraction(1.7)!!, 0.0)
        // не число — расстояния нет, и подписи нет
        assertNull(lens.metersAtFraction(Double.NaN))
        assertNull(lens.labelAtFraction(Double.NaN))
        // таблиц больше одной (у зумов — на каждое фокусное расстояние) — расстояний на шкале нет
        val two = lens(listOf(0.0 to 0.25, 65535.0 to 100_000.0), listOf(0.0 to 0.3, 65535.0 to 50_000.0))
        assertNull(two.metersAtFraction(0.5))
        assertNull(two.labelAtFraction(0.5))
        // таблицы нет вовсе
        assertNull(LensInformation(emptyList()).labelAtFraction(0.5))
    }

    // MARK: - Подписи под делениями

    @Test fun tickLabelsOfTheNewerCameraLensAreRoundDistancesWithBothEnds() {
        val lens = newerLens()
        val ticks = FocusDistanceScale.ticks(lens, width = 300.0, labelWidth = ::width)
        // ближний край — наименьшее расстояние, дальний — «∞»; между ними — круглые расстояния, сколько поместится
        assertEquals(listOf("0,25 м", "0,5 м", "1 м", "2 м", "5 м", "∞"), labels(ticks))
        assertEquals(0.0, ticks.first().fraction, 0.0)
        assertEquals(1.0, ticks.last().fraction, 0.0)
        // подпись стоит там, где на шкале и правда это расстояние
        for (tick in ticks) assertEquals("$tick", tick.label, lens.labelAtFraction(tick.fraction))
        // по порядку слева направо
        assertEquals(ticks.map { it.fraction }, ticks.map { it.fraction }.sorted())
    }

    @Test fun tickLabelsNeverTouchEachOtherOrLeaveTheRuler() {
        val lens = newerLens()
        var ruler = 60.0
        while (ruler <= 420.0) {
            val ticks = FocusDistanceScale.ticks(lens, width = ruler, labelWidth = ::width)
            val taken = spans(ticks, ruler)
            for ((index, span) in taken.withIndex()) {
                assertTrue("ширина $ruler: ${ticks[index]}", span.start >= 0 && span.endInclusive <= ruler)
                if (index > 0) assertTrue("ширина $ruler: ${ticks[index]}", span.start - taken[index - 1].endInclusive >= 12 - 1e-9)
            }
            ruler += 12.0
        }
    }

    @Test fun narrowerRulerKeepsTheEndsAndDropsTheRest() {
        val lens = newerLens()
        val narrow = FocusDistanceScale.ticks(lens, width = 120.0, labelWidth = ::width)
        assertEquals("0,25 м", narrow.first().label)
        assertEquals("∞", narrow.last().label)
        // чем шире линейка, тем больше подписей
        val counts = listOf(120.0, 300.0, 600.0).map { FocusDistanceScale.ticks(lens, width = it, labelWidth = ::width).size }
        assertTrue("$counts", counts[0] < counts[1] && counts[1] < counts[2])
        // линейки нет — подписать нечего
        assertTrue(FocusDistanceScale.ticks(lens, width = 0.0, labelWidth = ::width).isEmpty())
    }

    @Test fun reservedPlacesKeepTheirNeighboursAway() {
        val lens = newerLens()
        val all = FocusDistanceScale.ticks(lens, width = 300.0, labelWidth = ::width)
        val oneMeter = all.first { it.label == "1 м" }
        // у метки положения стоит своя подпись — деления под ней не подписываются
        val current = FocusDistanceScale.span(oneMeter.fraction, width("1,2 м"), 300.0)
        val rest = FocusDistanceScale.ticks(lens, width = 300.0, reserved = listOf(current), labelWidth = ::width)
        assertTrue("1 м" !in labels(rest))
        for (span in spans(rest, 300.0)) {
            assertTrue(span.endInclusive + 12 <= current.start || span.start >= current.endInclusive + 12)
        }
        // остальные на своих местах
        assertTrue("0,5 м" in labels(rest) && "∞" in labels(rest))
    }

    @Test fun tickLabelsOfALensThatEndsBeforeInfinity() {
        // макрообъектив: от 0,2 м до 0,9 м — оба края подписаны, между ними круглые расстояния
        val macro = lens(listOf(0.0 to 0.2, 100.0 to 0.9))
        val ticks = FocusDistanceScale.ticks(macro, width = 300.0, labelWidth = ::width)
        assertEquals("0,2 м", ticks.first().label)
        assertEquals("0,9 м", ticks.last().label)
        assertTrue("0,5 м" in labels(ticks))
        // «1 м» и «2 м» за пределами таблицы — их на этой шкале нет
        assertTrue("1 м" !in labels(ticks) && "2 м" !in labels(ticks))
        // телеобъектив от 1,2 м: «0,5 м» и «1 м» ниже ближнего края — тоже нет
        val tele = lens(listOf(0.0 to 1.2, 50.0 to 5.0, 100.0 to 100_000.0))
        val far = FocusDistanceScale.ticks(tele, width = 300.0, labelWidth = ::width)
        // «2 м» лежит вплотную к ближнему краю и уступает ему место; от 5 м до «∞» таблица прыгает, и «10 м», «20 м», «50 м» стоят
        // на одном месте с «5 м» — остаётся самое важное из них
        assertEquals(listOf("1,2 м", "3 м", "5 м", "∞"), labels(far))
    }

    @Test fun tickLabelsNeedExactlyOneTable() {
        val two = lens(listOf(0.0 to 0.25, 65535.0 to 100_000.0), listOf(0.0 to 0.3, 65535.0 to 50_000.0))
        assertTrue(FocusDistanceScale.ticks(two, width = 300.0, labelWidth = ::width).isEmpty())
        assertTrue(FocusDistanceScale.ticks(LensInformation(emptyList()), width = 300.0, labelWidth = ::width).isEmpty())
        // одна пара — одно деление
        val single = lens(listOf(50.0 to 2.0))
        assertEquals(listOf("2 м"), labels(FocusDistanceScale.ticks(single, width = 300.0, labelWidth = ::width)))
    }

    @Test fun tickLabelsOfATableWithOddDistancesStayTruthful() {
        // расстояние то растёт, то падает: подпись всё равно ставится только там, где таблица и правда даёт это расстояние
        val odd = lens(listOf(0.0 to 0.5, 30.0 to 3.0, 60.0 to 1.0, 100.0 to 20.0))
        val ticks = FocusDistanceScale.ticks(odd, width = 300.0, labelWidth = ::width)
        assertTrue(ticks.isNotEmpty())
        for (tick in ticks) {
            val shown = odd.labelAtFraction(tick.fraction)
            assertEquals("$tick — на этом месте таблица даёт ${shown ?: "ничего"}", tick.label, shown)
        }
    }

    @Test fun labelPlaceStaysInsideTheRulerWhereverTheTickIs() {
        // у краёв подпись сдвигается внутрь линейки, а не выходит за неё
        assertEquals(0.0..36.0, FocusDistanceScale.span(0.0, 36.0, 300.0))
        assertEquals(264.0..300.0, FocusDistanceScale.span(1.0, 36.0, 300.0))
        assertEquals(132.0..168.0, FocusDistanceScale.span(0.5, 36.0, 300.0))
        // подпись шире линейки занимает всю линейку
        assertEquals(0.0..300.0, FocusDistanceScale.span(0.3, 500.0, 300.0))
    }

    // MARK: - Метка положения

    private fun reading(code: Int, value: Long, type: Int = PtpType.UINT16, enabled: Int = 1) =
        SonyProperty(code, type, 0, enabled, PtpValue.Num(value), null, emptyList(), emptyList())

    @Test fun markerPositionPrefersTheAbsoluteReadingAndFallsBackToTheSteps() {
        // 0xE043 (0…65535) есть — метка по нему, даже если 0xD24C (0…100) говорит другое
        val both = mapOf(SonyProp.FOCUS_POSITION_CURRENT to reading(SonyProp.FOCUS_POSITION_CURRENT, 32768),
            SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 10, PtpType.UINT8))
        assertEquals(32768.0 / 65535, FocusPosition.fraction(both)!!, 1e-12)
        // 0xE043 закрыт (камера положения не отдаёт) — 0xD24C
        val closed = mapOf(SonyProp.FOCUS_POSITION_CURRENT to reading(SonyProp.FOCUS_POSITION_CURRENT, 32768, enabled = 0),
            SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 40, PtpType.UINT8))
        assertEquals(0.4, FocusPosition.fraction(closed)!!, 0.0)
        // у ZV-E10 только 0xD24C
        assertEquals(1.0, FocusPosition.fraction(mapOf(SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 100, PtpType.UINT8)))!!, 0.0)
        assertEquals(0.0, FocusPosition.fraction(mapOf(SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 0, PtpType.UINT8)))!!, 0.0)
        // положения нет вовсе
        assertNull(FocusPosition.fraction(emptyMap()))
        assertNull(FocusPosition.fraction(mapOf(SonyProp.ISO to reading(SonyProp.ISO, 100, PtpType.UINT32))))
        // за шкалой — на краю линейки
        assertEquals(1.0, FocusPosition.fraction(mapOf(SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 180, PtpType.UINT8)))!!, 0.0)
    }

    @Test fun distanceAtTheMarkerFollowsTheLensTableInItsOwnScale() {
        // камера с 0xE043 и таблицей в шкале 0xE043: доля из положения, расстояние — из таблицы
        val lens = newerLens()
        val absolute = mapOf(SonyProp.FOCUS_POSITION_CURRENT to reading(SonyProp.FOCUS_POSITION_CURRENT, 29790))
        assertEquals("1 м", lens.labelAtFraction(FocusPosition.fraction(absolute)!!))
        // камера только с 0xD24C, а таблица в шкале 0xE043: доля одна и та же, шкалы разные — расстояние то же
        val steps = mapOf(SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 100, PtpType.UINT8))
        assertEquals("∞", lens.labelAtFraction(FocusPosition.fraction(steps)!!))
        val middle = mapOf(SonyProp.FOCAL_POSITION to reading(SonyProp.FOCAL_POSITION, 50, PtpType.UINT8))
        // середина шкалы — 32767,5 из 65535, между 1 м (29790) и 1,5 м (35748): 1,25 м, на шкале «1,2 м»
        assertNotNull(FocusPosition.fraction(middle))
        assertEquals("1,2 м", lens.labelAtFraction(FocusPosition.fraction(middle)!!))
    }
}
