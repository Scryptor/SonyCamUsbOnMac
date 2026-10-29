package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.view.ProbeDrag
import local.zve10viewer.view.ProbeDrag.Step
import local.zve10viewer.view.ProbeDragging
import local.zve10viewer.view.ProbeLayout
import local.zve10viewer.view.ViewZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Метку пипетки тащит тот же жест, что двигает увеличенную картинку: что делает палец, начавший на метке, и куда метка идёт за
 * ним — при любом увеличении. Сам разбор жестов Compose в тестах на JVM не запустить; здесь — всё, что в нём решается.
 */
class ProbeDragTest {
    private val slop = 8f

    // что делает палец

    @Test fun aFingerThatDidNotStartOnTheMarkerIsNeverTheMarkers() {
        val drag = ProbeDrag(grabbed = false, slop)
        assertEquals(Step.notMarker, drag.step(Offset.Zero, 1))
        assertEquals(Step.notMarker, drag.step(Offset(500f, 300f), 1))
        assertEquals(Step.notMarker, drag.step(Offset(500f, 300f), 2))
    }

    @Test fun aFingerOnTheMarkerHoldsItWhileItStaysWithinTheSlop() {
        val drag = ProbeDrag(grabbed = true, slop)
        assertEquals(Step.holding, drag.step(Offset.Zero, 1))
        assertEquals(Step.holding, drag.step(Offset(3f, 4f), 1))
        // ровно на пределе — ещё дрожь пальца, а не жест
        assertEquals(Step.holding, drag.step(Offset(8f, 0f), 1))
    }

    @Test fun pastTheSlopTheFingerDragsTheMarkerAndKeepsDraggingEvenWhenItComesBack() {
        val drag = ProbeDrag(grabbed = true, slop)
        assertEquals(Step.dragging, drag.step(Offset(8.1f, 0f), 1))
        assertEquals(Step.dragging, drag.step(Offset(200f, 90f), 1))
        // вернулся к точке касания — всё равно тащит: метка идёт за ним, а не застревает
        assertEquals(Step.dragging, drag.step(Offset(1f, 1f), 1))
        assertEquals(Step.dragging, drag.step(Offset.Zero, 1))
    }

    @Test fun aSecondFingerReleasesTheMarkerForGoodEvenIfItIsLiftedAgain() {
        val drag = ProbeDrag(grabbed = true, slop)
        assertEquals(Step.dragging, drag.step(Offset(100f, 0f), 1))
        assertEquals(Step.notMarker, drag.step(Offset(120f, 0f), 2))
        // второй палец убрали — жест уже увеличение, метка назад не берётся
        assertEquals(Step.notMarker, drag.step(Offset(140f, 0f), 1))
        // то же, если второй палец лёг до сдвига
        val early = ProbeDrag(grabbed = true, slop)
        assertEquals(Step.holding, early.step(Offset(1f, 1f), 1))
        assertEquals(Step.notMarker, early.step(Offset(1f, 1f), 2))
        assertEquals(Step.notMarker, early.step(Offset(300f, 1f), 1))
    }

    // куда метка идёт за пальцем

    private val view = Size(1280f, 800f)
    private val frame = Size(1024f, 576f)
    private val sizes = ProbeLayout.Sizes.dp.scaled(2f)

    /** Метка стоит в [at]; увеличение — [zoom]; жест строится как в приложении. */
    private class Rig(start: FramePoint, var zoom: ViewZoom, var frame: Size?) {
        var point = start
        val placed = ArrayList<FramePoint>()
    }

    private fun gestureOf(rig: Rig, sizes: ProbeLayout.Sizes) = ProbeDragging.gesture(
        point = { rig.point }, place = { rig.point = it; rig.placed += it }, zoom = { rig.zoom }, frame = { rig.frame },
        view = view, sizes = sizes,
    )

    private fun close(a: FramePoint, b: FramePoint, eps: Double = 1e-6) = abs(a.x - b.x) < eps && abs(a.y - b.y) < eps

    @Test fun theMarkerIsGrabbedWhereItStandsAndNotElsewhere() {
        val rig = Rig(FramePoint(0.5, 0.5), ViewZoom.identity, frame)
        val gesture = gestureOf(rig, sizes)
        // кадр 1280×720 с отступом 40 сверху: метка в (640; 400); захват 16 dp = 32 точки при плотности 2
        assertTrue(gesture.grabs(Offset(640f, 400f)))
        assertTrue(gesture.grabs(Offset(640f + 31f, 400f)))
        assertFalse(gesture.grabs(Offset(640f + 33f, 400f)))
        assertFalse(gesture.grabs(Offset(100f, 100f)))
    }

    @Test fun theMarkerFollowsTheFingerByTheSameDistanceOnTheScreen() {
        val rig = Rig(FramePoint(0.5, 0.5), ViewZoom.identity, frame)
        val gesture = gestureOf(rig, sizes)
        assertTrue(gesture.grabs(Offset(640f, 400f)))
        // палец ушёл на (+128; +72): в долях кадра 1280×720 это (+0,1; +0,1)
        gesture.drag(Offset(128f, 72f))
        assertTrue("${rig.point}", close(rig.point, FramePoint(0.6, 0.6)))
        // и дальше — от места касания, а не от последнего шага: палец ушёл на (−128; 0) от касания
        gesture.drag(Offset(-128f, 0f))
        assertTrue("${rig.point}", close(rig.point, FramePoint(0.4, 0.5)))
    }

    @Test fun withTwoTimesZoomTheMarkerIsWhereTheZoomedFrameSaysAndMovesByHalfAsMuchOfTheFrame() {
        val zoom = ViewZoom(2.0, 0.5, 0.5)
        val rig = Rig(FramePoint(0.6, 0.5), zoom, frame)
        val gesture = gestureOf(rig, sizes)
        // кадр при ×2 — 2560 точек в ширину, начало −640: метка 0,6 стоит в x = 896; без увеличения она стояла бы в 768
        assertTrue(gesture.grabs(Offset(896f, 400f)))
        assertFalse(gesture.grabs(Offset(768f, 400f)))
        gesture.drag(Offset(128f, 0f))
        // 128 точек из 2560 — 0,05 кадра
        assertTrue("${rig.point}", close(rig.point, FramePoint(0.65, 0.5)))
    }

    @Test fun theMarkerStopsAtTheEdgeOfTheVisiblePartNotOfTheFrame() {
        val zoom = ViewZoom(2.0, 0.5, 0.5)
        val rig = Rig(FramePoint(0.5, 0.5), zoom, frame)
        val gesture = gestureOf(rig, sizes)
        assertTrue(gesture.grabs(Offset(640f, 400f)))
        gesture.drag(Offset(5000f, 5000f))
        val visible = zoom.visibleRegion(view, frame)
        assertTrue("${rig.point}", close(rig.point, FramePoint(visible.right.toDouble(), visible.bottom.toDouble())))
        assertTrue(rig.point.x < 1.0 && rig.point.y < 1.0)
    }

    @Test fun aMarkerPushedOffTheScreenByTheZoomCannotBeGrabbedAtTheEdge() {
        // увеличение ×2 по центру: кадр начинается в x = −640. Метка 0,24609375 стоит в x = −10 — на 10 точек за левым краем
        val rig = Rig(FramePoint(0.24609375, 0.5), ViewZoom(2.0, 0.5, 0.5), frame)
        val gesture = gestureOf(rig, sizes)
        // палец у самого края в 15 точках от метки — по радиусу захвата (32) он бы её взял, но метки на экране нет
        assertFalse(gesture.grabs(Offset(5f, 400f)))
        assertFalse(gesture.grabs(Offset(0f, 400f)))
        // а метка в x = 5, на экране, — берётся: 0,251953125 · 2560 − 640 = 5
        val seen = Rig(FramePoint(0.251953125, 0.5), ViewZoom(2.0, 0.5, 0.5), frame)
        assertTrue(gestureOf(seen, sizes).grabs(Offset(5f, 400f)))
    }

    @Test fun theMarkerMovedByAnotherMeansBetweenTouchesIsGrabbedWhereItIsNow() {
        val rig = Rig(FramePoint(0.5, 0.5), ViewZoom.identity, frame)
        val first = gestureOf(rig, sizes)
        assertTrue(first.grabs(Offset(640f, 400f)))
        first.drag(Offset(128f, 0f))
        // свой жест на каждое касание: следующий берёт метку там, где она стоит
        val second = gestureOf(rig, sizes)
        assertFalse(second.grabs(Offset(640f, 400f)))
        assertTrue(second.grabs(Offset(768f, 400f)))
        second.drag(Offset(0f, 72f))
        assertTrue("${rig.point}", close(rig.point, FramePoint(0.6, 0.6)))
    }

    @Test fun withNoFrameNothingIsGrabbedAndNothingMoves() {
        val rig = Rig(FramePoint(0.5, 0.5), ViewZoom.identity, null)
        val gesture = gestureOf(rig, sizes)
        assertFalse(gesture.grabs(Offset(640f, 400f)))
        gesture.drag(Offset(100f, 100f))
        assertTrue(rig.placed.isEmpty())
    }

    @Test fun everyStepOfADragStaysInsideTheFrame() {
        // тащат по кругу далеко за кадр: метка всё время в 0…1
        val rig = Rig(FramePoint(0.5, 0.5), ViewZoom.identity, frame)
        val gesture = gestureOf(rig, sizes)
        assertTrue(gesture.grabs(Offset(640f, 400f)))
        for (i in 0 until 360 step 15) {
            val a = Math.toRadians(i.toDouble())
            gesture.drag(Offset((3000 * Math.cos(a)).toFloat(), (3000 * Math.sin(a)).toFloat()))
        }
        assertNotNull(rig.placed.firstOrNull())
        for (p in rig.placed) assertTrue("$p", p.x in 0.0..1.0 && p.y in 0.0..1.0)
    }
}
