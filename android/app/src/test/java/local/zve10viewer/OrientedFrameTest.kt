package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.TouchFocus
import local.zve10viewer.view.OrientedFrame
import local.zve10viewer.view.ViewZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Зеркало и переворот в координатах экрана: куда на экране ложатся рамки фокуса и метка касания, во что превращается касание и
 * как с этим живёт увеличение. Числа те же, что проверял Mac на поддельной камере: зеркало (0,3; 0,5) → камера (0,7; 0,5), 180° (0,2;
 * 0,1) → (0,8; 0,9), зеркало + 180° (0,2; 0,1) → (0,2; 0,9), зеркало + ×2 (0,4; 0,4) → (0,6; 0,4).
 */
class OrientedFrameTest {
    private val eps = 1e-5

    private fun close(a: Float, b: Float, eps: Float = 1e-3f) = abs(a - b) < eps

    private fun assertClose(message: String, expected: FramePoint, actual: FramePoint?, eps: Double = this.eps) {
        assertNotNull("$message: $actual", actual)
        assertTrue("$message: ждали $expected, вышло $actual", abs(expected.x - actual!!.x) < eps && abs(expected.y - actual.y) < eps)
    }

    private fun assertClose(message: String, expected: Offset, actual: Offset, eps: Float = 1e-3f) {
        assertTrue("$message: ждали $expected, вышло $actual", close(expected.x, actual.x, eps) && close(expected.y, actual.y, eps))
    }

    private fun assertClose(message: String, expected: Rect, actual: Rect, eps: Float = 1e-3f) {
        assertTrue("$message: ждали $expected, вышло $actual",
            close(expected.left, actual.left, eps) && close(expected.top, actual.top, eps) &&
                close(expected.right, actual.right, eps) && close(expected.bottom, actual.bottom, eps))
    }

    /** Экран планшета и кадр 16:9 1024×576: вписан по ширине, сверху и снизу по 40 точек. */
    private val view = Size(1280f, 800f)
    private val frame = Size(1024f, 576f)

    // перевод касания: экранные доли → доли кадра камеры

    @Test fun mirrorSendsTheTouchToTheOtherSideOfTheCameraFrame() {
        // числа проверки на планшете: зеркало + DEBUG_LENS x=0,3 y=0,5 → точка AF камеры 0,7
        assertClose("зеркало", FramePoint(0.7, 0.5), TouchFocus.cameraPoint(0.3, 0.5, FrameOrientation.mirror))
    }

    @Test fun everyOrientationMapsTheTouchLikeTheTable() {
        // таблица спецификации: зеркало x → 1 − x; 180° x → 1 − x, y → 1 − y; зеркало + 180° y → 1 − y
        assertClose("off", FramePoint(0.2, 0.1), TouchFocus.cameraPoint(0.2, 0.1, FrameOrientation.off))
        assertClose("зеркало", FramePoint(0.8, 0.1), TouchFocus.cameraPoint(0.2, 0.1, FrameOrientation.mirror))
        assertClose("180", FramePoint(0.8, 0.9), TouchFocus.cameraPoint(0.2, 0.1, FrameOrientation.rotate180))
        assertClose("зеркало + 180", FramePoint(0.2, 0.9), TouchFocus.cameraPoint(0.2, 0.1, FrameOrientation.mirror180))
    }

    @Test fun theTouchIsKeptInsideTheFrameAndNotANumberIsRefused() {
        // как действие API на Mac: доли за кадром — к краю; не число — отказ
        // x = −0,3 → 0 → зеркало → 1; y = 1,7 → 1, зеркало y не трогает
        assertClose("за краем", FramePoint(1.0, 1.0), TouchFocus.cameraPoint(-0.3, 1.7, FrameOrientation.mirror))
        assertNull(TouchFocus.cameraPoint(Double.NaN, 0.5, FrameOrientation.off))
        assertNull(TouchFocus.cameraPoint(0.5, Double.POSITIVE_INFINITY, FrameOrientation.mirror))
    }

    @Test fun theConversionIsItsOwnInverseSoAScreenSpotReturnsToTheSameSpot() {
        // касание → камера → экран: рамка, поставленная камерой в ответ на касание, ложится под палец
        for (o in FrameOrientation.entries) {
            for ((x, y) in listOf(0.3 to 0.5, 0.2 to 0.1, 0.0 to 0.0, 1.0 to 1.0, 0.123 to 0.987)) {
                val camera = TouchFocus.cameraPoint(x, y, o)!!
                assertClose("$o $x,$y", FramePoint(x, y), o.fromCamera(camera))
            }
        }
    }

    // рамки и метка: доли кадра камеры → точки экрана

    private val rect = Rect(0f, 40f, 1280f, 760f)

    @Test fun focusFrameFollowsTheMirrorToTheOtherSideOfTheScreen() {
        // рамка камеры на 0,6…0,8 по ширине и 0,4…0,5 по высоте
        val box = Rect(0.6f, 0.4f, 0.8f, 0.5f)
        assertClose("off", Rect(768f, 328f, 1024f, 400f), OrientedFrame.toScreen(box, rect, FrameOrientation.off))
        assertClose("зеркало", Rect(256f, 328f, 512f, 400f), OrientedFrame.toScreen(box, rect, FrameOrientation.mirror))
        assertClose("180", Rect(256f, 400f, 512f, 472f), OrientedFrame.toScreen(box, rect, FrameOrientation.rotate180))
        assertClose("зеркало + 180", Rect(768f, 400f, 1024f, 472f), OrientedFrame.toScreen(box, rect, FrameOrientation.mirror180))
    }

    @Test fun focusFrameKeepsItsSizeUnderEveryOrientation() {
        val box = Rect(0.1f, 0.2f, 0.4f, 0.3f)
        for (o in FrameOrientation.entries) {
            val on = OrientedFrame.toScreen(box, rect, o)
            assertTrue("$o ширина", close(on.width, 0.3f * rect.width))
            assertTrue("$o высота", close(on.height, 0.1f * rect.height))
        }
    }

    @Test fun touchMarkIsDrawnWhereTheTouchWasSeen() {
        // метка хранится в долях камеры, а рисуется через fromCamera: касание на экране в (0,3; 0,5) — камера (0,7; 0,5) — метка снова там
        val camera = TouchFocus.cameraPoint(0.3, 0.5, FrameOrientation.mirror)!!
        val mark = OrientedFrame.toScreen(Offset(camera.x.toFloat(), camera.y.toFloat()), rect, FrameOrientation.mirror)
        assertClose("метка", Offset(0.3f * 1280f, 40f + 0.5f * 720f), mark)
    }

    @Test fun frameAndMarkFollowTheZoomedFrameOnTheScreen() {
        // увеличение ×2 с центром в (0,5; 0,5): кадр 2560×1440, начало (−640; −320)
        val zoomed = ViewZoom(2.0, 0.5, 0.5).imageRect(view, frame)
        assertClose("кадр ×2", Rect(-640f, -320f, 1920f, 1120f), zoomed)
        val mark = OrientedFrame.toScreen(Offset(0.7f, 0.5f), zoomed, FrameOrientation.mirror)
        // камера 0,7 — на экране 0,3 кадра: −640 + 0,3 · 2560 = 128
        assertClose("метка ×2", Offset(128f, 400f), mark)
        val box = OrientedFrame.toScreen(Rect(0.6f, 0.4f, 0.8f, 0.5f), zoomed, FrameOrientation.mirror)
        assertClose("рамка ×2", Rect(-640f + 0.2f * 2560f, -320f + 0.4f * 1440f, -640f + 0.4f * 2560f, -320f + 0.5f * 1440f), box)
    }

    @Test fun framesRoundTripThroughTheScreenForEveryOrientationAndEveryZoom() {
        val zooms = listOf(ViewZoom.identity, ViewZoom(2.0, 0.5, 0.5), ViewZoom(2.0, 0.25, 0.3), ViewZoom(4.0, 0.8, 0.2), ViewZoom(8.0, 0.9, 0.9))
        for (zoom in zooms) {
            val shown = zoom.imageRect(view, frame)
            for (o in FrameOrientation.entries) {
                for ((cx, cy) in listOf(0.7f to 0.5f, 0.1f to 0.9f, 0.5f to 0.5f, 0f to 0f, 1f to 1f)) {
                    // центр рамки камеры → место на экране → доли кадра на экране → снова доли камеры
                    val at = OrientedFrame.toScreen(Offset(cx, cy), shown, o)
                    val fraction = OrientedFrame.fraction(at, shown)
                    if (fraction == null) {
                        // самый нижний / правый край кадра — не внутри (границы полуоткрытые)
                        assertTrue("$zoom $o $cx,$cy", cx == 1f || cy == 1f || cx == 0f || cy == 0f)
                        continue
                    }
                    assertClose("$zoom $o $cx,$cy", FramePoint(cx.toDouble(), cy.toDouble()),
                        TouchFocus.cameraPoint(fraction.x.toDouble(), fraction.y.toDouble(), o), 1e-4)
                }
            }
        }
    }

    // касание → доли кадра на экране

    @Test fun fractionOfATouchInsideTheFrame() {
        val r = Rect(100f, 50f, 900f, 500f)
        assertEquals(Offset(0f, 0f), OrientedFrame.fraction(Offset(100f, 50f), r))
        assertEquals(Offset(0.5f, 0.5f), OrientedFrame.fraction(Offset(500f, 275f), r))
        assertEquals(Offset(0.25f, 0.75f), OrientedFrame.fraction(Offset(300f, 387.5f), r))
    }

    @Test fun aTouchOutsideTheFrameHasNoFraction() {
        val r = Rect(100f, 50f, 900f, 500f)
        assertNull(OrientedFrame.fraction(Offset(99.9f, 100f), r))
        assertNull(OrientedFrame.fraction(Offset(300f, 49f), r))
        // правый и нижний край — снаружи, как у Rect.contains
        assertNull(OrientedFrame.fraction(Offset(900f, 100f), r))
        assertNull(OrientedFrame.fraction(Offset(300f, 500f), r))
        assertNull(OrientedFrame.fraction(Offset(300f, 100f), Rect.Zero))
        assertNull(OrientedFrame.fraction(Offset(Float.NaN, 100f), r))
    }

    // весь путь: касание в точках экрана → камера, при любом увеличении

    /** Доли кадра на экране под касанием, посчитанные по видимой части кадра — другим путём, чем `imageRect`. */
    private fun expectedScreenFraction(zoom: ViewZoom, at: Offset): FramePoint {
        val visible = zoom.visibleRegion(view, frame)
        val x = visible.left + at.x / view.width * visible.width
        // кадр вписан по ширине: без увеличения сверху и снизу по 40 точек, и видимая часть по высоте — весь кадр
        val y = if (zoom.isZoomed) visible.top + at.y / view.height * visible.height else (at.y - 40f) / 720f
        return FramePoint(x.toDouble(), y.toDouble())
    }

    private fun cameraPointOfTouch(at: Offset, zoom: ViewZoom, orientation: FrameOrientation): FramePoint? {
        // та же цепочка, что в приложении: увеличение → место кадра → доли на экране → доли камеры по настройке
        val shown = zoom.imageRect(view, frame)
        val fraction = OrientedFrame.fraction(at, shown) ?: return null
        return TouchFocus.cameraPoint(fraction.x.toDouble(), fraction.y.toDouble(), orientation)
    }

    @Test fun touchPathWithoutZoomForEveryOrientation() {
        val zoom = ViewZoom.identity
        // 30 % ширины, середина по высоте
        val at = Offset(0.3f * 1280f, 400f)
        for (o in FrameOrientation.entries) {
            val screen = expectedScreenFraction(zoom, at)
            assertClose("$o", o.toCamera(screen), cameraPointOfTouch(at, zoom, o), 1e-4)
        }
        assertClose("зеркало", FramePoint(0.7, 0.5), cameraPointOfTouch(at, zoom, FrameOrientation.mirror), 1e-4)
    }

    @Test fun touchPathWithMirrorAndTwoTimesZoom() {
        // Mac: зеркало + ×2 — касание в экранных долях кадра (0,45; …) ложится на камеру зеркально. Здесь — в точках экрана:
        // увеличение ×2 в середине, касание в (0,4 · 1280; 0,4 · 800)
        val zoom = ViewZoom(2.0, 0.5, 0.5)
        val at = Offset(0.4f * 1280f, 0.4f * 800f)
        val screen = expectedScreenFraction(zoom, at)
        // по ширине видна половина кадра, от 0,25 до 0,75: касание на 0,4 экрана — 0,25 + 0,4 · 0,5 = 0,45 кадра; по высоте кадр
        // 16:9 в экране 16:10 при ×2 виден от 2/9 до 7/9 — касание на 0,4 экрана это 2/9 + 0,4 · 5/9 = 4/9
        assertClose("доли экрана", FramePoint(0.45, 4.0 / 9.0), screen, 1e-4)
        assertClose("камера", FramePoint(0.55, 4.0 / 9.0), cameraPointOfTouch(at, zoom, FrameOrientation.mirror), 1e-4)
        assertClose("камера 180", FramePoint(0.55, 5.0 / 9.0), cameraPointOfTouch(at, zoom, FrameOrientation.rotate180), 1e-4)
    }

    @Test fun touchPathWithEveryOrientationAndEveryPannedZoom() {
        val zooms = listOf(ViewZoom(2.0, 0.5, 0.5), ViewZoom(2.0, 0.0, 0.0), ViewZoom(2.0, 1.0, 1.0), ViewZoom(3.0, 0.3, 0.7),
            ViewZoom(8.0, 0.9, 0.2), ViewZoom(1.5, 0.5, 0.5))
        val touches = listOf(Offset(10f, 10f), Offset(640f, 400f), Offset(1270f, 790f), Offset(320f, 600f), Offset(1000f, 100f))
        for (zoom in zooms) {
            for (o in FrameOrientation.entries) {
                for (at in touches) {
                    val screen = expectedScreenFraction(zoom, at)
                    assertClose("$zoom $o $at", o.toCamera(screen), cameraPointOfTouch(at, zoom, o), 1e-4)
                }
            }
        }
    }

    @Test fun theTouchOutsideThePictureHasNoCameraPoint() {
        // чёрные полосы над кадром и под ним без увеличения
        assertNull(cameraPointOfTouch(Offset(640f, 20f), ViewZoom.identity, FrameOrientation.mirror))
        assertNull(cameraPointOfTouch(Offset(640f, 780f), ViewZoom.identity, FrameOrientation.off))
    }

    @Test fun otherFrameShapesKeepTheSameFractions() {
        // 1:1 (1024×1024) и 3:2 (1024×680) в экране 1280×800: касание в середину правой половины картинки — доли 0,75 и зеркало даёт 0,25
        for (shape in listOf(Size(1024f, 1024f), Size(1024f, 680f), Size(1024f, 576f))) {
            val shown = ViewZoom.identity.imageRect(view, shape)
            val at = Offset(shown.left + 0.75f * shown.width, shown.top + 0.5f * shown.height)
            val fraction = OrientedFrame.fraction(at, shown)!!
            assertClose("$shape", FramePoint(0.75, 0.5), FramePoint(fraction.x.toDouble(), fraction.y.toDouble()), 1e-4)
            assertClose("$shape зеркало", FramePoint(0.25, 0.5),
                TouchFocus.cameraPoint(fraction.x.toDouble(), fraction.y.toDouble(), FrameOrientation.mirror), 1e-4)
        }
    }

    // эталон наложения: рисуется с тем же поворотом

    @Test fun referenceIsFlippedLikeThePicture() {
        assertEquals(Offset(1f, 1f), OrientedFrame.flip(FrameOrientation.off))
        assertEquals(Offset(-1f, 1f), OrientedFrame.flip(FrameOrientation.mirror))
        assertEquals(Offset(-1f, -1f), OrientedFrame.flip(FrameOrientation.rotate180))
        assertEquals(Offset(1f, -1f), OrientedFrame.flip(FrameOrientation.mirror180))
    }

    @Test fun referenceFillsTheFrameByTheLongerSideAndIsCentred() {
        val r = Rect(0f, 40f, 1280f, 760f)
        // та же форма, что у кадра: ровно кадр
        assertClose("16:9", r, OrientedFrame.referenceBox(Size(2048f, 1152f), r))
        // 3:2 по ширине кадра: выше кадра, середина та же; лишнее обрезается по краю кадра
        val tall = OrientedFrame.referenceBox(Size(1536f, 1024f), r)
        assertClose("3:2 середина", r.center, tall.center)
        assertTrue("3:2 ширина", close(tall.width, 1280f))
        assertTrue("3:2 выше кадра", tall.height > r.height)
        // 1:1 — тоже по ширине
        val square = OrientedFrame.referenceBox(Size(1000f, 1000f), r)
        assertClose("1:1", Rect(0f, 40f - 280f, 1280f, 760f + 280f), square)
        assertEquals(Rect.Zero, OrientedFrame.referenceBox(Size(0f, 10f), r))
        assertEquals(Rect.Zero, OrientedFrame.referenceBox(Size(100f, 100f), Rect.Zero))
    }

    @Test fun aReferenceTakenFromTheCameraLiesOnTheFlippedLivePictureForEveryOrientation() {
        // эталон — кадр камеры как есть. Точка эталона (u, v) после поворота вокруг середины кадра должна лечь туда, где живая
        // повёрнутая картинка показывает ту же точку камеры: на fromCamera(u, v). Проверка геометрией рисования: dst → flip → экран.
        val zooms = listOf(ViewZoom.identity, ViewZoom(2.0, 0.3, 0.6), ViewZoom(4.0, 0.9, 0.1))
        for (zoom in zooms) {
            val shown = zoom.imageRect(view, frame)
            val reference = Size(2048f, 1152f)
            val box = OrientedFrame.referenceBox(reference, shown)
            for (o in FrameOrientation.entries) {
                val flip = OrientedFrame.flip(o)
                for ((u, v) in listOf(0.7f to 0.5f, 0.1f to 0.9f, 0.5f to 0.5f, 0.25f to 0.25f)) {
                    val drawn = Offset(box.left + u * box.width, box.top + v * box.height)
                    val pivot = shown.center
                    val onScreen = Offset(pivot.x + flip.x * (drawn.x - pivot.x), pivot.y + flip.y * (drawn.y - pivot.y))
                    assertClose("$zoom $o $u,$v", OrientedFrame.toScreen(Offset(u, v), shown, o), onScreen, 5e-3f)
                }
            }
        }
    }
}
