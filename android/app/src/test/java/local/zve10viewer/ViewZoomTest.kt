package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.view.ViewZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val VIEW = Size(1280f, 800f)
private val FRAME = Size(1024f, 576f)

/** Точка кадра (доли) под точкой экрана. */
private fun framePoint(rect: Rect, p: Offset) =
    Pair((p.x.toDouble() - rect.left) / rect.width, (p.y.toDouble() - rect.top) / rect.height)

class ViewZoomTest {
    @Test fun identityFitsLetterboxed() {
        assertEquals(Rect(0f, 40f, 1280f, 760f), ViewZoom.identity.imageRect(VIEW, FRAME))
        assertFalse(ViewZoom.identity.isZoomed)
    }

    @Test fun zoomKeepsPointUnderFingers() {
        val p = Offset(300f, 200f)
        val before = framePoint(ViewZoom.identity.imageRect(VIEW, FRAME), p)
        val zoom = ViewZoom.identity.zoomed(2.0, p, VIEW, FRAME, elastic = false)
        val after = framePoint(zoom.imageRect(VIEW, FRAME), p)
        assertEquals(before.first, after.first, 1e-9)
        assertEquals(before.second, after.second, 1e-9)
        assertEquals(2.0, zoom.factor, 1e-9)
        assertEquals(0.3672, zoom.centerX, 1e-4)
        assertEquals(0.3611, zoom.centerY, 1e-4)
        assertTrue(zoom.isZoomed)
    }

    @Test fun panStopsAtImageEdge() {
        val zoom = ViewZoom(2.0, 0.5, 0.5).panned(Offset(5000f, 0f), VIEW, FRAME)
        assertEquals(0f, zoom.imageRect(VIEW, FRAME).left)
        // по вертикали сдвига не было — центр на месте
        assertEquals(0.5, zoom.centerY, 1e-9)
    }

    @Test fun letterboxAxisStaysCentered() {
        // при 1,05× кадр по высоте меньше экрана: полосы сверху и снизу поровну, как ни сдвигай
        val zoom = ViewZoom(1.05, 0.5, 0.2).panned(Offset(0f, 300f), VIEW, FRAME)
        assertEquals(400f, zoom.imageRect(VIEW, FRAME).center.y, 1e-3f)
    }

    @Test fun settledSnapsBelowThreshold() {
        assertEquals(ViewZoom.identity, ViewZoom(1.04, 0.3, 0.6).settled(VIEW, FRAME))
        assertEquals(ViewZoom.identity, ViewZoom(0.9, 0.5, 0.5).settled(VIEW, FRAME))
    }

    @Test fun settledClampsToMax() {
        val zoom = ViewZoom(8.5, 0.5, 0.5).settled(VIEW, FRAME)
        assertEquals(8.0, zoom.factor, 1e-9)
        assertEquals(0.5, zoom.centerX, 1e-9)
    }

    @Test fun elasticResistsBeyondLimits() {
        val center = Offset(640f, 400f)
        val elastic = ViewZoom(8.0, 0.5, 0.5).zoomed(1.5, center, VIEW, FRAME, elastic = true)
        assertTrue("${elastic.factor}", elastic.factor > 8.0 && elastic.factor <= 8.6)
        assertEquals(8.0, ViewZoom(8.0, 0.5, 0.5).zoomed(1.5, center, VIEW, FRAME, elastic = false).factor, 1e-9)
        // внизу так же: не меньше 0,87 и с сопротивлением
        val low = ViewZoom.identity.zoomed(0.5, center, VIEW, FRAME, elastic = true)
        assertTrue("${low.factor}", low.factor >= 0.87 && low.factor < 1.0)
        assertEquals(1.0, ViewZoom.identity.zoomed(0.5, center, VIEW, FRAME, elastic = false).factor, 1e-9)
        // обратно в пределы — без сопротивления
        assertEquals(8.6 / 1.5, ViewZoom(8.6, 0.5, 0.5).zoomed(1 / 1.5, center, VIEW, FRAME, elastic = true).factor, 1e-9)
    }

    @Test fun visibleRegionCentered() {
        val region = ViewZoom(2.0, 0.5, 0.5).visibleRegion(VIEW, FRAME)
        assertEquals(0.25f, region.left, 1e-4f)
        assertEquals(0.2222f, region.top, 1e-4f)
        assertEquals(0.5f, region.width, 1e-4f)
        assertEquals(0.5556f, region.height, 1e-4f)
        // без увеличения — весь кадр
        assertEquals(Rect(0f, 0f, 1f, 1f), ViewZoom.identity.visibleRegion(VIEW, FRAME))
    }

    @Test fun survivesResize() {
        val zoom = ViewZoom(3.0, 0.8, 0.3)
        val portrait = Size(800f, 1280f)
        val rect = zoom.imageRect(portrait, FRAME)
        // по ширине кадр больше экрана (2400 > 800), по высоте тоже (1350 > 1280): полос нет ни с одной стороны
        assertTrue(rect.left <= 0f && rect.right >= portrait.width)
        assertTrue(rect.top <= 0f && rect.bottom >= portrait.height)
        val region = zoom.visibleRegion(portrait, FRAME)
        assertTrue(region.contains(Offset(0.8f, 0.3f)))
        // повернули обратно — то же место кадра, что было до поворота: в центре экрана снова (0,8; 0,3)
        val back = zoom.visibleRegion(VIEW, FRAME)
        assertEquals(0.8f, back.center.x, 1e-6f)
        assertEquals(0.3f, back.center.y, 1e-6f)
    }

    @Test fun qualityChangeKeepsRegion() {
        val zoom = ViewZoom(3.0, 0.7, 0.4)
        val high = zoom.visibleRegion(VIEW, Size(1024f, 576f))
        val low = zoom.visibleRegion(VIEW, Size(640f, 360f))
        assertEquals(high.left, low.left, 1e-6f)
        assertEquals(high.top, low.top, 1e-6f)
        assertEquals(high.right, low.right, 1e-6f)
        assertEquals(high.bottom, low.bottom, 1e-6f)
    }

    @Test fun centeredClampsToTheFrame() {
        val zoom = ViewZoom(2.0, 0.5, 0.5).centered(Offset(0.95f, 0.1f), VIEW, FRAME)
        assertEquals(0.75, zoom.centerX, 1e-9)
        assertEquals(400.0 / 1440, zoom.centerY, 1e-9)
    }

    @Test fun stepLadder() {
        assertEquals(1.5, ViewZoom.step(1.0, up = true), 1e-9)
        assertEquals(3.0, ViewZoom.step(2.4, up = true), 1e-9)
        assertEquals(2.0, ViewZoom.step(2.4, up = false), 1e-9)
        assertEquals(8.0, ViewZoom.step(8.0, up = true), 1e-9)
        assertEquals(1.0, ViewZoom.step(1.0, up = false), 1e-9)
    }

    @Test fun labels() {
        assertEquals("2×", ViewZoom(2.0, 0.5, 0.5).label)
        assertEquals("2,4×", ViewZoom(2.4, 0.5, 0.5).label)
        assertEquals("1,5×", ViewZoom(1.46, 0.5, 0.5).label)
        assertEquals("2×", ViewZoom(1.98, 0.5, 0.5).label)
    }
}
