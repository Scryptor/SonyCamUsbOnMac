package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import local.zve10viewer.view.FloatingPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private val SCREEN = Size(1280f, 800f)
private val PANEL = Size(320f, 170f)

class FloatingPlacementTest {
    @Test fun originSpansTheFreeSpaceWithinMargins() {
        assertEquals(Offset(16f, 614f), FloatingPlacement(0.0, 1.0).origin(PANEL, SCREEN))
        assertEquals(Offset(944f, 16f), FloatingPlacement(1.0, 0.0).origin(PANEL, SCREEN))
    }

    @Test fun dropNearAnEdgeSnapsToTheMargin() {
        assertEquals(0.0, FloatingPlacement.dropped(Offset(30f, 300f), PANEL, SCREEN).x, 1e-9)
        // до правого отступа (944) — 14: прилипает справа
        assertEquals(1.0, FloatingPlacement.dropped(Offset(930f, 300f), PANEL, SCREEN).x, 1e-9)
    }

    @Test fun dropInTheMiddleKeepsTheShareOfFreeSpace() {
        val placement = FloatingPlacement.dropped(Offset(500f, 300f), PANEL, SCREEN)
        assertEquals(484.0 / 928, placement.x, 1e-9)
        assertEquals(284.0 / 598, placement.y, 1e-9)
    }

    @Test fun dropOffScreenComesBackInside() {
        assertEquals(FloatingPlacement(0.0, 1.0), FloatingPlacement.dropped(Offset(-50f, 900f), PANEL, SCREEN))
    }

    @Test fun tooSmallView() {
        // место запомнили на большом экране, а окно меньше панели: у левого верхнего отступа
        assertEquals(Offset(16f, 16f), FloatingPlacement(0.7, 0.9).origin(PANEL, Size(300f, 150f)))
    }

    @Test fun dragStaysOnScreen() {
        assertEquals(Offset(0f, 630f), FloatingPlacement.dragOrigin(Offset(-40f, 2000f), PANEL, SCREEN))
        assertEquals(Offset(100f, 200f), FloatingPlacement.dragOrigin(Offset(100f, 200f), PANEL, SCREEN))
    }

    @Test fun storedValuesRoundTrip() {
        val placement = FloatingPlacement(0.25, 0.75)
        assertEquals(placement, FloatingPlacement.restored(placement.x.toFloat(), placement.y.toFloat()))
        // испорченное — как не сохранено; за пределами — в пределы
        assertNull(FloatingPlacement.restored(Float.NaN, 0.5f))
        assertEquals(FloatingPlacement(1.0, 0.0), FloatingPlacement.restored(3f, -1f))
    }
}
