package local.zve10viewer

import local.zve10viewer.monitor.FocusFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Значки меток на линейке шкалы фокуса: где остриё и где плашка с буквой. Числа в точках экрана; плашка шире 15 точек, ширина
 * линейки — 360 (как на планшете при 1 точка = 1 пиксель).
 */
class FocusFlagsTest {
    private val width = 360f
    private val badge = 15f

    private fun layout(vararg marks: Pair<Char, Double>, width: Float = this.width) = FocusFlags.layout(marks.toList(), width, badge)

    @Test fun aSingleFlagStandsOverItsPlace() {
        val flags = layout('A' to 0.5)
        assertEquals(1, flags.size)
        assertEquals('A', flags[0].mark)
        assertEquals(180f, flags[0].tip, 1e-4f)
        assertEquals(180f, flags[0].center, 1e-4f)
    }

    @Test fun flagsFarApartStandOverTheirPlaces() {
        val flags = layout('A' to 0.2, 'B' to 0.8)
        assertEquals(listOf(72f, 288f), flags.map { it.tip })
        assertEquals(listOf(72f, 288f), flags.map { it.center })
    }

    @Test fun twoFlagsCloserThanABadgeMoveApartAroundTheirMiddle() {
        // 180 и 183,6: плашки встают рядом, пара — вокруг середины; острия остаются там, где метки
        val flags = layout('A' to 0.5, 'B' to 0.51)
        assertEquals(180f, flags[0].tip, 1e-3f)
        assertEquals(183.6f, flags[1].tip, 1e-3f)
        assertTrue("A левее B", flags[0].center < flags[1].center)
        // между центрами — плашка и зазор: буквы не закрывают друг друга
        assertTrue(flags[1].center - flags[0].center >= badge)
        assertEquals(181.8f, (flags[0].center + flags[1].center) / 2, 1e-3f)
    }

    @Test fun flagsExactlyABadgeApartAreNotMovedAndOneTenthLessAre() {
        // ширина 256: доли k/256 точны в двоичной дроби, остриё ровно в 64 и 79 — расстояние ровно 15, не «почти»
        val apart = layout('A' to 0.25, 'B' to 0.25 + 15.0 / 256.0, width = 256f)
        assertEquals(listOf(64f, 79f), apart.map { it.tip })
        assertEquals(apart.map { it.tip }, apart.map { it.center })
        val closer = layout('A' to 0.25, 'B' to 0.25 + 13.5 / 256.0, width = 256f)
        assertTrue(closer.map { it.tip } != closer.map { it.center })
    }

    @Test fun theLeftFlagIsTheOneWithTheLeftMarkWhateverItsLetter() {
        // B стоит левее A: плашка B — левее плашки A, как и остриё
        val flags = layout('A' to 0.51, 'B' to 0.5)
        val b = flags.first { it.mark == 'B' }
        val a = flags.first { it.mark == 'A' }
        assertTrue(b.tip < a.tip)
        assertTrue(b.center < a.center)
    }

    @Test fun marksAtTheSamePlaceKeepTheirOrderAFirst() {
        val flags = layout('A' to 0.4, 'B' to 0.4)
        assertEquals(listOf('A', 'B'), flags.map { it.mark })
        assertTrue(flags[0].center < flags[1].center)
        assertEquals(flags[0].tip, flags[1].tip, 0f)
    }

    @Test fun aFlagAtTheEdgeStaysInsideTheRuler() {
        assertEquals(badge / 2, layout('A' to 0.0)[0].center, 1e-4f)
        assertEquals(width - badge / 2, layout('B' to 1.0)[0].center, 1e-4f)
        // остриё при этом — на самом краю
        assertEquals(0f, layout('A' to 0.0)[0].tip, 0f)
        assertEquals(width, layout('B' to 1.0)[0].tip, 0f)
    }

    @Test fun twoFlagsAtTheEdgeDoNotLieOnEachOther() {
        for (edge in listOf(0.0, 1.0)) {
            val flags = layout('A' to edge, 'B' to edge)
            assertTrue("$edge: плашки не наложились", abs(flags[1].center - flags[0].center) >= badge)
            for (flag in flags) {
                assertTrue("$edge: плашка внутри линейки", flag.center - badge / 2 >= -1e-4f && flag.center + badge / 2 <= width + 1e-4f)
            }
        }
    }

    @Test fun aRulerNarrowerThanABadgeDoesNotThrow() {
        val flags = layout('A' to 0.5, 'B' to 0.5, width = 10f)
        assertEquals(2, flags.size)
    }

    @Test fun noMarksNoFlags() {
        assertTrue(layout().isEmpty())
    }
}
