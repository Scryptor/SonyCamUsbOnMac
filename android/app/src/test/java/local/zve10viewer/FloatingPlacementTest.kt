package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.view.FloatingPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    // не ложиться на кнопки справа и ленту камер слева (то же правило, что `FloatingPlacement.clear` на Mac)

    /** Лента камер: плитки 160×90 столбцом слева под верхней панелью, промежуток 8. */
    private fun strip(cameras: Int, top: Float = 68f) = Rect(16f, top, 176f, top + cameras * 90f + (cameras - 1) * 8f)

    private val column = Rect(1180f, 250f, 1240f, 550f)
    private val scope = Size(176f, 176f)

    @Test fun clearLeavesAPanelThatTouchesNothingAlone() {
        val origin = Offset(400f, 300f)
        assertEquals(origin, FloatingPlacement.clear(origin, PANEL, column, SCREEN))
        // край к краю — не наложение
        val touching = Offset(column.left - PANEL.width, 300f)
        assertEquals(touching, FloatingPlacement.clear(touching, PANEL, column, SCREEN))
    }

    @Test fun clearKeepsTheOldWayWithTheRightColumn() {
        // прежнее правило колонки кнопок: левее неё на 12, на той же высоте
        assertEquals(Offset(1180f - 12f - 320f, 300f), FloatingPlacement.clear(Offset(1000f, 300f), PANEL, column, SCREEN))
        // места слева нет (панель шире просвета) — у отступа, но не за экран
        val narrow = FloatingPlacement.clear(Offset(20f, 300f), Size(760f, 170f), Rect(720f, 250f, 780f, 550f), Size(800f, 600f))
        assertEquals(FloatingPlacement.MARGIN.toFloat(), narrow.x, 0f)
        assertEquals(300f, narrow.y, 0f)
    }

    @Test fun clearPutsAPanelRightOfTheStripAtTheDefaultSpot() {
        // вектороскоп по умолчанию стоит у левого верха: при двух камерах там лента
        val moved = FloatingPlacement.clear(Offset(16f, 64f), scope, strip(2), SCREEN)
        assertEquals(Offset(16f + 160f + 12f, 64f), moved)
        assertFalse(Rect(moved, scope).overlaps(strip(2)))
    }

    @Test fun clearMovesAPanelDroppedOnTheStripOutOfIt() {
        // место из прошлых запусков или перетащили на ленту: правее неё, высота та же
        assertEquals(Offset(188f, 150f), FloatingPlacement.clear(Offset(60f, 150f), scope, strip(3), SCREEN))
    }

    @Test fun clearLeavesAPanelBelowOrBesideTheStrip() {
        val twoCameras = strip(2) // 68 … 256
        val below = Offset(16f, twoCameras.bottom + 20f)
        assertEquals(below, FloatingPlacement.clear(below, scope, twoCameras, SCREEN))
        val beside = Offset(400f, 64f)
        assertEquals(beside, FloatingPlacement.clear(beside, scope, twoCameras, SCREEN))
    }

    @Test fun clearStaysOnScreenInTheSmallestWindowWithTheLongestStrip() {
        // 800×450 — меньше окна нет; четыре камеры — самая длинная лента
        val small = Size(800f, 450f)
        val longest = strip(4)
        for (origin in listOf(Offset(16f, 64f), Offset(16f, 16f), Offset(100f, 200f), Offset(16f, 258f))) {
            val moved = FloatingPlacement.clear(origin, scope, longest, small)
            assertTrue("$origin", moved.x >= FloatingPlacement.MARGIN && moved.x + scope.width <= small.width - FloatingPlacement.MARGIN)
            assertEquals(origin.y, moved.y, 0f)
            assertFalse("$origin", Rect(moved, scope).overlaps(longest))
        }
    }

    @Test fun clearByTheStripThenByTheColumnLandsBetweenThem() {
        // оба препятствия сразу (панель посередине, лента слева, колонка справа): один раз по ленте, потом по колонке — в просвете
        val first = FloatingPlacement.clear(Offset(16f, 300f), scope, strip(4), SCREEN)
        val final = FloatingPlacement.clear(first, scope, column, SCREEN)
        assertEquals(Offset(188f, 300f), final)
        assertFalse(Rect(final, scope).overlaps(strip(4)))
        assertFalse(Rect(final, scope).overlaps(column))
    }

    @Test fun clearWithNoRoomOnTheFarSideStaysOnScreen() {
        // препятствие слева, а справа места нет: не за экран — у правого отступа
        val tiny = Size(300f, 400f)
        val moved = FloatingPlacement.clear(Offset(16f, 64f), scope, Rect(16f, 68f, 176f, 256f), tiny)
        assertEquals(108f, moved.x, 0f)
        assertEquals(tiny.width - 176f - FloatingPlacement.MARGIN.toFloat(), moved.x, 0f)
    }

    @Test fun clearWorksInPixelsWhenTheGapAndMarginAreScaled() {
        // на планшете считают в пикселях: зазор и отступ приходят в тех же единицах, что экран
        val density = 2f
        val view = Size(2560f, 1600f)
        val panel = Size(176f * density, 176f * density)
        val bar = Rect(18f * density, 84f * density, 178f * density, 264f * density)
        val moved = FloatingPlacement.clear(Offset(18f * density, 84f * density), panel, bar, view, gap = 12f * density,
            margin = 16f * density)
        assertEquals(Offset(178f * density + 12f * density, 84f * density), moved)
        // и у самого края — отступ тоже в пикселях
        val crowded = FloatingPlacement.clear(Offset(18f * density, 84f * density), panel, Rect(0f, 0f, 400f, 400f), Size(500f, 500f),
            gap = 12f * density, margin = 16f * density)
        assertEquals(500f - panel.width - 16f * density, crowded.x, 0f)
    }

    @Test fun clearAtTheTabletsDefaultSpotNextToItsOwnStrip() {
        // на планшете плитки ленты 200×112 dp, промежуток 8, лента начинается там же, где вектороскоп: (18; 84)
        val view = Size(1280f, 800f)
        for (cameras in 2..4) {
            val tabletStrip = Rect(18f, 84f, 218f, 84f + cameras * 112f + (cameras - 1) * 8f)
            val moved = FloatingPlacement.clear(Offset(18f, 84f), scope, tabletStrip, view)
            assertEquals("$cameras", Offset(230f, 84f), moved)
            assertFalse(Rect(moved, scope).overlaps(tabletStrip))
        }
        // одна камера — ленты нет, панель остаётся на своём месте
        val alone = Offset(18f, 84f)
        assertEquals(alone, FloatingPlacement.clear(alone, scope, Rect(Offset.Zero, Size.Zero), view))
    }
}
