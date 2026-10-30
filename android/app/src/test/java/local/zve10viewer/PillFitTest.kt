package local.zve10viewer

import local.zve10viewer.view.PillFit
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ALWAYS = Int.MAX_VALUE

class PillFitTest {
    // REC, точка, камера, кадры, режим (1), карта (2), камера: заряд (5), планшет (3)
    private val widths = listOf(110, 10, 150, 120, 80, 150, 120, 140)
    private val order = listOf(ALWAYS, ALWAYS, ALWAYS, ALWAYS, 1, 2, 5, 3)
    private val gap = 16

    private fun total(shown: List<Boolean>) =
        widths.filterIndexed { i, _ -> shown[i] }.sum() + gap * (shown.count { it } - 1)

    @Test fun everythingFitsEverythingShown() {
        val shown = PillFit.shown(widths, order, gap, 10_000)
        assertEquals(List(8) { true }, shown)
        assertEquals(widths.sum() + gap * 7, total(shown))
    }

    @Test fun lowestOrderHidesFirst() {
        val all = widths.sum() + gap * 7
        // не хватает 1 точки — прячется только режим (1)
        assertEquals(listOf(true, true, true, true, false, true, true, true), PillFit.shown(widths, order, gap, all - 1))
        // не хватает ещё на карту — прячется карта (2), потом планшет (3)
        val noMode = all - 80 - gap
        assertEquals(listOf(true, true, true, true, false, false, true, true), PillFit.shown(widths, order, gap, noMode - 1))
        val noCard = noMode - 150 - gap
        assertEquals(listOf(true, true, true, true, false, false, true, false), PillFit.shown(widths, order, gap, noCard - 1))
    }

    @Test fun whatIsShownFitsTheRoom() {
        for (room in 400..1200 step 7) {
            val shown = PillFit.shown(widths, order, gap, room)
            val always = widths.take(4).sum() + gap * 3
            if (room >= always) assertEquals("место $room", true, total(shown) <= room)
        }
    }

    @Test fun alwaysItemsStayEvenWithoutRoom() {
        assertEquals(listOf(true, true, true, true, false, false, false, false), PillFit.shown(widths, order, gap, 50))
    }

    @Test fun nothingToShowIsFine() {
        assertEquals(emptyList<Boolean>(), PillFit.shown(emptyList(), emptyList(), gap, 100))
    }
}
