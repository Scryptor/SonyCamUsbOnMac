package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.ColorProbe
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.view.ProbeLayout
import local.zve10viewer.view.ProbeLayout.Sizes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Пипетка на экране: где метка, где рядом с ней табличка и куда уходит точка, когда метку тащат. Те же проверки, что у программы
 * для Mac (ProbeLayoutTests.swift), и три сверх них: табличка не ложится на колонку кнопок справа, метка за краем экрана не
 * берётся, а кадры 1:1 и 3:2 не ломают края.
 */
class ProbeLayoutTest {
    private val dp = Sizes.dp

    /** Область, где может стоять табличка, и табличка из двух строк. */
    private val area = Rect(0f, 0f, 1000f, 600f)
    private val plate = Size(150f, 40f)

    /** От центра метки до таблички по горизонтали: радиус метки и просвет. */
    private val reach = dp.markerDiameter / 2 + dp.gap

    private fun close(a: Float, b: Float, eps: Float = 1e-5f) = abs(a - b) < eps

    private fun close(a: FramePoint, b: FramePoint, eps: Double = 1e-6) = abs(a.x - b.x) < eps && abs(a.y - b.y) < eps

    private fun origin(x: Float, y: Float, area: Rect = this.area, avoid: Rect? = null, sizes: Sizes = dp, plate: Size = this.plate) =
        ProbeLayout.plateOrigin(Offset(x, y), plate, area, sizes, avoid)

    // метка на экране

    @Test fun constantsAreTheSpecsTwentyDpRingAndItsSurroundings() {
        assertEquals(20f, dp.markerDiameter)
        assertEquals(6f, dp.gap)
        assertEquals(4f, dp.margin)
        assertEquals(16f, dp.grabRadius)
        assertEquals(FramePoint(0.5, 0.5), ColorProbe.CENTER)
    }

    @Test fun markerCenterFollowsTheFrameRect() {
        val rect = Rect(100f, 50f, 900f, 500f)
        assertEquals(Offset(100f, 50f), ProbeLayout.markerCenter(FramePoint(0.0, 0.0), rect))
        assertEquals(Offset(900f, 500f), ProbeLayout.markerCenter(FramePoint(1.0, 1.0), rect))
        assertEquals(Offset(500f, 275f), ProbeLayout.markerCenter(FramePoint(0.5, 0.5), rect))
    }

    @Test fun markerCenterIsOffscreenWhenTheZoomedFrameMovedAway() {
        // кадр увеличен вдвое и сдвинут: левая четверть и верхняя четверть кадра за экраном, метка в них — левее и выше нуля
        val rect = Rect(-400f, -225f, 1200f, 675f)
        assertEquals(Offset(-240f, -135f), ProbeLayout.markerCenter(FramePoint(0.1, 0.1), rect))
        assertEquals(Offset(400f, 225f), ProbeLayout.markerCenter(FramePoint(0.5, 0.5), rect))
    }

    // табличка

    @Test fun plateStandsRightOfTheMarkerCentredOnIt() {
        assertEquals(Offset(300f + reach, 280f), origin(300f, 300f))
    }

    @Test fun plateGoesLeftNearTheRightEdge() {
        // слева: край таблички — за радиус метки и просвет до центра метки; по высоте — по центру метки
        assertEquals(Offset(900f - reach - plate.width, 280f), origin(900f, 300f))
    }

    @Test fun plateSwitchesSidesExactlyWhereItStopsFitting() {
        // справа помещается, пока правый край таблички не заходит за область минус поле
        val edge = area.right - dp.margin - plate.width - reach
        assertEquals(edge + reach, origin(edge, 300f).x, 0f)
        assertEquals(edge + 1 - reach - plate.width, origin(edge + 1, 300f).x, 0f)
    }

    @Test fun plateRisesNearTheBottomEdge() {
        // по высоте не выходит за низ области; сторона та же — справа
        assertEquals(Offset(300f + reach, area.bottom - dp.margin - plate.height), origin(300f, 595f))
    }

    @Test fun plateDropsNearTheTopEdge() {
        assertEquals(Offset(300f + reach, area.top + dp.margin), origin(300f, 3f))
    }

    @Test fun plateInTheBottomRightCornerGoesLeftAndUp() {
        assertEquals(Offset(995f - reach - plate.width, area.bottom - dp.margin - plate.height), origin(995f, 595f))
    }

    @Test fun plateInTheTopLeftCornerStaysRightAndBelowTheEdge() {
        assertEquals(Offset(reach, area.top + dp.margin), origin(0f, 0f))
    }

    @Test fun plateKeepsToTheAreaNotTheScreenOrigin() {
        // кадр уже окна: область смещена — у её правого края табличка уходит влево, у нижнего поднимается
        val letterbox = Rect(120f, 40f, 820f, 440f)
        assertEquals(815f - reach - plate.width, origin(815f, 200f, letterbox).x, 0f)
        assertEquals(letterbox.bottom - dp.margin - plate.height, origin(300f, 438f, letterbox).y, 0f)
        assertEquals(120f + reach, origin(120f, 200f, letterbox).x, 0f)
    }

    @Test fun plateStaysInsideTheAreaAndOffTheMarkerEverywhere() {
        // по сетке точек от угла до угла, включая сами края: табличка целиком в области (с полем) и не лежит на метке
        val r = dp.markerDiameter / 2
        val inner = Rect(area.left + dp.margin, area.top + dp.margin, area.right - dp.margin, area.bottom - dp.margin)
        var x = 0f
        while (x <= area.width) {
            var y = 0f
            while (y <= area.height) {
                val o = origin(x, y)
                val box = Rect(o, plate)
                assertTrue("$x,$y", box.left >= inner.left - 1e-4f && box.right <= inner.right + 1e-4f)
                assertTrue("$x,$y", box.top >= inner.top - 1e-4f && box.bottom <= inner.bottom + 1e-4f)
                assertFalse("$x,$y", box.overlaps(Rect(x - r, y - r, x + r, y + r)))
                y += 23f
            }
            x += 37f
        }
    }

    @Test fun plateInAnAreaNarrowerThanItselfStaysAtTheLeftEdge() {
        // табличке негде поместиться: левый край области, ни NaN, ни выхода влево
        val narrow = Rect(0f, 0f, 100f, 300f)
        for (x in listOf(0f, 50f, 100f)) {
            val o = origin(x, 150f, narrow)
            assertEquals("$x", narrow.left + dp.margin, o.x, 0f)
            assertTrue("$x", o.y.isFinite() && o.y == 130f)
        }
    }

    @Test fun whenNeitherSideFitsTheSideWithMoreRoomWinsAndThePlateStaysInTheArea() {
        // область 300 при табличке 150: у метки посередине ни справа, ни слева табличка целиком не помещается; берётся сторона,
        // где в области больше места, и табличка сдвигается внутрь области (поле 4 с обоих краёв — x от 4 до 146)
        val middle = Rect(0f, 0f, 300f, 600f)
        // метка левее середины: больше места справа — табличка вправо, до правого поля
        assertEquals(146f, origin(140f, 300f, middle).x, 0f)
        // правее середины: больше места слева — табличка влево, до левого поля
        assertEquals(4f, origin(160f, 300f, middle).x, 0f)
        // ровно посередине — как у Mac, справа
        assertEquals(146f, origin(150f, 300f, middle).x, 0f)
    }

    @Test fun plateInAnAreaShorterThanItselfStaysAtTheTopEdge() {
        val flat = Rect(0f, 0f, 1000f, 30f)
        assertEquals(flat.top + dp.margin, origin(500f, 15f, flat).y, 0f)
    }

    @Test fun plateWithNoAreaOrNoMarkerDoesNotProduceNaN() {
        val o = origin(Float.NaN, 5f)
        assertTrue(o.x.isFinite() && o.y.isFinite())
        val empty = origin(5f, 5f, Rect.Zero)
        assertTrue(empty.x.isFinite() && empty.y.isFinite())
        val infinite = origin(Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)
        assertTrue(infinite.x.isFinite() && infinite.y.isFinite())
    }

    // табличка не ложится на колонку кнопок справа

    /** Экран планшета в dp: 1280×800, колонка кнопок справа — высокая, по середине высоты (зум, режим, фокус, спуск). */
    private val screen = Rect(0f, 0f, 1280f, 800f)
    private val column = Rect(1186f, 110f, 1258f, 690f)

    @Test fun theMarkerNextToTheColumnGetsItsPlateOnTheLeft() {
        // метка в 180 dp от края: справа табличка вошла бы в область (1116…1266), но легла бы на колонку
        val o = origin(1100f, 400f, screen, avoid = column)
        assertEquals(1100f - reach - plate.width, o.x, 0f)
        assertEquals(380f, o.y, 0f)
        // и то же место без колонки — справа, как у Mac
        assertEquals(1100f + reach, origin(1100f, 400f, screen).x, 0f)
    }

    @Test fun theMarkerAboveOrBelowTheColumnKeepsItsPlateOnTheRight() {
        // над колонкой (y < 110 − 12 − 40) и под ней места справа хватает, и колонка не мешает: правило прежнее
        assertEquals(1100f + reach, origin(1100f, 40f, screen, avoid = column).x, 0f)
        assertEquals(1100f + reach, origin(1100f, 770f, screen, avoid = column).x, 0f)
        // у самого правого края над колонкой места справа нет, и табличка уходит влево, как у Mac, — колонка тут ни при чём
        assertEquals(1250f - reach - plate.width, origin(1250f, 40f, screen, avoid = column).x, 0f)
    }

    @Test fun theMarkerOnTheColumnPushesThePlateBesideIt() {
        // метка прямо под колонкой: табличка не ложится ни справа, ни слева от метки — встаёт левее колонки, не на метку
        for (x in listOf(1190f, 1200f, 1222f, 1250f, 1275f)) {
            val o = origin(x, 400f, screen, avoid = column)
            val box = Rect(o, plate)
            assertFalse("$x", box.overlaps(column))
            assertEquals("$x", column.left - dp.columnGap, box.right, 1e-4f)
            assertFalse("$x", box.overlaps(Rect(x - 10, 390f, x + 10, 410f)))
        }
    }

    @Test fun thePlateNeverLiesOnTheColumnNorOnTheMarkerWhileItFitsTheArea() {
        // по сетке от угла до угла (в том числе по самой колонке): внутри области, не на колонке (с просветом) и не на метке
        val r = dp.markerDiameter / 2
        val inner = Rect(screen.left + dp.margin, screen.top + dp.margin, screen.right - dp.margin, screen.bottom - dp.margin)
        val keepOff = column.inflate(dp.columnGap)
        var x = 0f
        while (x <= screen.width) {
            var y = 0f
            while (y <= screen.height) {
                val box = Rect(origin(x, y, screen, avoid = column), plate)
                assertTrue("$x,$y", box.left >= inner.left - 1e-4f && box.right <= inner.right + 1e-4f)
                assertTrue("$x,$y", box.top >= inner.top - 1e-4f && box.bottom <= inner.bottom + 1e-4f)
                assertFalse("колонка $x,$y", box.overlaps(keepOff))
                assertFalse("метка $x,$y", box.overlaps(Rect(x - r, y - r, x + r, y + r)))
                y += 11f
            }
            x += 13f
        }
    }

    @Test fun theColumnRuleScalesWithTheDensity() {
        // те же места в пикселях при плотности 2: ответ тот же, умноженный на 2
        val sizes = dp.scaled(2f)
        val px = Rect(0f, 0f, 2560f, 1600f)
        val columnPx = Rect(2372f, 220f, 2516f, 1380f)
        val platePx = Size(300f, 80f)
        for ((x, y) in listOf(100f to 100f, 1100f to 400f, 2200f to 800f, 2400f to 800f, 2500f to 100f, 2540f to 1590f, 2000f to 1580f)) {
            val a = ProbeLayout.plateOrigin(Offset(x, y), platePx, px, sizes, columnPx)
            val b = ProbeLayout.plateOrigin(Offset(x / 2, y / 2), Size(150f, 40f), screen, dp, column)
            assertEquals("$x,$y", b.x * 2, a.x, 1e-3f)
            assertEquals("$x,$y", b.y * 2, a.y, 1e-3f)
        }
    }

    @Test fun anObstacleAtTheLeftPushesThePlateToItsRight() {
        // общее правило, а не «справа»: препятствие у левого края (лента камер) — табличка правее него
        val strip = Rect(16f, 80f, 216f, 400f)
        val o = origin(100f, 200f, screen, avoid = strip)
        assertEquals(strip.right + dp.columnGap, o.x, 1e-4f)
        assertFalse(Rect(o, plate).overlaps(strip))
    }

    @Test fun anObstacleThatLeavesNoRoomDoesNotPushThePlateOutOfTheArea() {
        // область уже таблички и колонки вместе: табличка остаётся в области (у её левого края), колонку не обещаем
        val tight = Rect(0f, 0f, 300f, 600f)
        val obstacle = Rect(120f, 0f, 300f, 600f)
        val o = origin(150f, 300f, tight, avoid = obstacle)
        assertTrue(o.x >= tight.left + dp.margin - 1e-4f)
        assertTrue(o.x + plate.width <= tight.right - dp.margin + 1e-4f)
        assertTrue(o.x.isFinite() && o.y.isFinite())
    }

    @Test fun theMarkerIsNeverCoveredJustToKeepOffTheColumn() {
        // область 400 в ширину, колонка у правого края: метка в 150 от левого края — слева таблички нет места, справа колонка;
        // рядом с колонкой табличка легла бы на метку — тогда она остаётся у метки (справа) и ложится на колонку, а не на метку
        val narrow = Rect(0f, 0f, 400f, 600f)
        val obstacle = Rect(300f, 0f, 400f, 600f)
        val marker = Offset(150f, 300f)
        val o = ProbeLayout.plateOrigin(marker, plate, narrow, dp, obstacle)
        assertFalse(Rect(o, plate).overlaps(Rect(140f, 290f, 160f, 310f)))
        assertTrue(o.x >= narrow.left + dp.margin && o.x + plate.width <= narrow.right - dp.margin)
    }

    // кадры другой формы: 1:1 и 3:2 в экране 1280×800

    @Test fun squareFrameKeepsThePlateInsideThePicture() {
        // 1024×1024 вписан в 800×800 посередине: область — картинка; у её правого края табличка уходит влево
        val picture = Rect(240f, 0f, 1040f, 800f)
        val atEdge = ProbeLayout.markerCenter(FramePoint(1.0, 0.5), picture)
        assertEquals(Offset(1040f, 400f), atEdge)
        val o = ProbeLayout.plateOrigin(atEdge, plate, picture, dp, column)
        assertEquals(1040f - reach - plate.width, o.x, 0f)
        // у левого — справа от метки
        val atLeft = ProbeLayout.markerCenter(FramePoint(0.0, 0.5), picture)
        assertEquals(240f + reach, ProbeLayout.plateOrigin(atLeft, plate, picture, dp, column).x, 0f)
    }

    @Test fun threeByTwoFrameBeingCutByTheColumnStillAvoidsIt() {
        // 1024×680 вписан в 1204,7×800: правая часть картинки лежит под колонкой; метка на ней — табличка левее колонки
        val picture = Rect(37.65f, 0f, 1242.35f, 800f)
        for (fx in listOf(0.90, 0.93, 0.96, 0.99, 1.0)) {
            val marker = ProbeLayout.markerCenter(FramePoint(fx, 0.5), picture)
            val box = Rect(ProbeLayout.plateOrigin(marker, plate, picture, dp, column), plate)
            assertFalse("$fx", box.overlaps(column.inflate(dp.columnGap)))
            assertTrue("$fx", box.right <= picture.right - dp.margin + 1e-3f)
        }
    }

    // перетаскивание

    @Test fun dragMovesThePointByTheTranslationInFrameFractions() {
        val rect = Rect(100f, 50f, 900f, 500f)
        val moved = ProbeLayout.dragged(FramePoint(0.5, 0.5), Offset(80f, 45f), rect, Rect(0f, 0f, 1f, 1f))
        assertTrue("$moved", close(moved, FramePoint(0.6, 0.6)))
    }

    @Test fun dragOnAZoomedFrameMovesByLessInTheFrame() {
        // вдвое увеличено: те же 100 точек — вдвое меньшая доля кадра
        val rect = Rect(-400f, -225f, 1200f, 675f)
        val visible = Rect(0.25f, 0.25f, 0.75f, 0.75f)
        val moved = ProbeLayout.dragged(FramePoint(0.5, 0.5), Offset(100f, -90f), rect, visible)
        assertTrue("$moved", close(moved, FramePoint(0.5625, 0.4)))
    }

    @Test fun dragStopsAtTheVisibleEdgesNotAtTheFrameEdges() {
        val rect = Rect(-400f, -225f, 1200f, 675f)
        val visible = Rect(0.25f, 0.25f, 0.75f, 0.75f)
        val far = ProbeLayout.dragged(FramePoint(0.5, 0.5), Offset(5000f, 5000f), rect, visible)
        assertTrue("$far", close(far, FramePoint(0.75, 0.75)))
        val back = ProbeLayout.dragged(FramePoint(0.5, 0.5), Offset(-5000f, -5000f), rect, visible)
        assertTrue("$back", close(back, FramePoint(0.25, 0.25)))
    }

    @Test fun dragWithoutFrameOrWithJunkKeepsTheStart() {
        val whole = Rect(0f, 0f, 1f, 1f)
        val start = FramePoint(0.3, 0.7)
        assertEquals(start, ProbeLayout.dragged(start, Offset(10f, 10f), Rect.Zero, whole))
        val rect = Rect(0f, 0f, 800f, 450f)
        assertEquals(start, ProbeLayout.dragged(start, Offset(Float.NaN, 0f), rect, whole))
        assertEquals(start, ProbeLayout.dragged(start, Offset(0f, Float.POSITIVE_INFINITY), rect, whole))
    }

    @Test fun dragOnASquareAndOnAThreeByTwoFrameStaysInsideTheFrame() {
        // кадры другой формы: доли считаются по размеру кадра на экране, края те же 0 и 1
        for (rect in listOf(Rect(240f, 0f, 1040f, 800f), Rect(37.65f, 0f, 1242.35f, 800f))) {
            val whole = Rect(0f, 0f, 1f, 1f)
            assertEquals(FramePoint(1.0, 1.0), ProbeLayout.dragged(FramePoint(0.9, 0.9), Offset(3000f, 3000f), rect, whole))
            assertEquals(FramePoint(0.0, 0.0), ProbeLayout.dragged(FramePoint(0.1, 0.1), Offset(-3000f, -3000f), rect, whole))
            val half = ProbeLayout.dragged(FramePoint(0.5, 0.5), Offset(rect.width / 4, rect.height / 4), rect, whole)
            assertTrue("$rect $half", close(half, FramePoint(0.75, 0.75)))
        }
    }

    // кого берёт жест

    @Test fun gestureStartedOnTheMarkerGrabsIt() {
        val marker = Offset(400f, 300f)
        assertTrue(ProbeLayout.grabs(marker, marker))
        assertTrue(ProbeLayout.grabs(Offset(400f + dp.grabRadius, 300f), marker))
        assertTrue(ProbeLayout.grabs(Offset(410f, 310f), marker))
        // чуть дальше радиуса — уже не метка: такой жест двигает картинку, а касание ставит пипетку на новое место
        assertFalse(ProbeLayout.grabs(Offset(400f + dp.grabRadius + 0.5f, 300f), marker))
        assertFalse(ProbeLayout.grabs(Offset(412f, 312f), marker))
        assertFalse(ProbeLayout.grabs(Offset(100f, 100f), marker))
    }

    @Test fun theMarkerIsGrabbedEvenWhenItIsLargerThanTheRingAroundIt() {
        // берётся чуть шире кольца (10 dp): пальцем в кольцо попасть труднее, чем в круг побольше
        assertTrue(dp.grabRadius > dp.markerDiameter / 2)
    }

    @Test fun nothingGrabsAMarkerThatIsNotANumber() {
        assertFalse(ProbeLayout.grabs(Offset(5f, 5f), Offset(Float.NaN, 5f)))
        assertFalse(ProbeLayout.grabs(Offset(Float.POSITIVE_INFINITY, 5f), Offset(5f, 5f)))
    }

    @Test fun aMarkerOutsideTheScreenIsNotGrabbed() {
        // увеличение сдвинули: метка за краем экрана, невидима, и жест у края экрана её не хватает (у Mac это был недочёт)
        val screenRect = Rect(0f, 0f, 1280f, 800f)
        val hidden = Offset(-5f, 400f)
        assertFalse(ProbeLayout.grabs(Offset(5f, 400f), hidden, within = screenRect))
        assertTrue(ProbeLayout.grabs(Offset(5f, 400f), hidden))
        // на экране — берётся, и на самом краю видимой части тоже
        assertTrue(ProbeLayout.grabs(Offset(5f, 400f), Offset(0f, 400f), within = screenRect))
        assertTrue(ProbeLayout.grabs(Offset(1275f, 400f), Offset(1279f, 400f), within = screenRect))
        assertFalse(ProbeLayout.grabs(Offset(1275f, 400f), Offset(1280f, 400f), within = screenRect))
    }

    // точка кадра

    @Test fun probePointKeepsPointsInsideTheFrame() {
        assertEquals(FramePoint(0.25, 0.75), ProbeLayout.clamped(FramePoint(0.25, 0.75)))
        assertEquals(FramePoint(0.0, 1.0), ProbeLayout.clamped(FramePoint(-0.4, 1.8)))
        assertEquals(FramePoint(0.0, 1.0), ProbeLayout.clamped(FramePoint(0.0, 1.0)))
    }

    @Test fun probePointRefusesNotANumber() {
        assertNull(ProbeLayout.clamped(FramePoint(Double.NaN, 0.5)))
        assertNull(ProbeLayout.clamped(FramePoint(0.5, Double.POSITIVE_INFINITY)))
        assertNotNull(ProbeLayout.clamped(FramePoint(1.0, 0.0)))
    }
}
