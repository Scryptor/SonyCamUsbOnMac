package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import local.zve10viewer.monitor.Vectorscope
import local.zve10viewer.ui.VectorscopeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Сетка панели вектороскопа: куда на панели ложится точка плоскости (Cb, Cr), метки полос 75 %, линия тона кожи и что пишет
 * отладка. Те же числа и те же проверки, что у программы для Mac (`VectorscopeTests`, «Сетка панели»).
 */
class VectorscopePanelTest {
    private fun near(a: Double, b: Double, eps: Double) = abs(a - b) <= eps

    private fun argb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    // место точки на панели

    @Test fun planePutsGreyInTheMiddleOfThePanelAndCrUp() {
        val middle = Vectorscope.plane(0.0, 0.0, 1)
        assertTrue(near(middle.x, 0.5, 1e-12) && near(middle.y, 0.5, 1e-12))
        // Cb — вправо, Cr — вверх (у экрана y растёт вниз)
        val right = Vectorscope.plane(0.25, 0.0, 1)
        assertTrue(near(right.x, 0.75, 1e-12) && near(right.y, 0.5, 1e-12))
        val up = Vectorscope.plane(0.0, 0.25, 1)
        assertTrue(near(up.x, 0.5, 1e-12) && near(up.y, 0.25, 1e-12))
        // край плоскости: ×1 — 0,5, ×2 — 0,25
        assertEquals(1.0, Vectorscope.plane(0.5, 0.0, 1).x, 1e-12)
        assertEquals(1.0, Vectorscope.plane(0.25, 0.0, 2).x, 1e-12)
        assertEquals(1.0, Vectorscope.plane(0.0, -0.25, 2).y, 1e-12)
    }

    @Test fun planeAgreesWithTheBinsThePointsFallInto() {
        // метка на панели и корзина с таким цветом — одно и то же место, при любом масштабе
        val n = Vectorscope.BINS.toDouble()
        for (zoom in listOf(1, 2)) {
            for (target in Vectorscope.targets) {
                val bin = Vectorscope.bin(target.cb, target.cr, zoom) ?: continue
                val point = Vectorscope.plane(target.cb, target.cr, zoom)
                assertEquals("${target.name} ×$zoom", bin.x, (point.x * n).toInt())
                assertEquals("${target.name} ×$zoom", bin.y, (point.y * n).toInt())
            }
        }
        // при ×1 все шесть меток внутри панели, при ×2 — ни одной (75 % полосы лежат дальше 0,25)
        fun inside(p: Vectorscope.PanelPoint) = p.x in 0.0..1.0 && p.y in 0.0..1.0
        assertTrue(Vectorscope.targets.all { inside(Vectorscope.plane(it.cb, it.cr, 1)) })
        assertTrue(Vectorscope.targets.none { inside(Vectorscope.plane(it.cb, it.cr, 2)) })
    }

    @Test fun zoomBelowOneIsReadAsOneOnThePanelToo() {
        assertEquals(Vectorscope.plane(0.1, -0.2, 1), Vectorscope.plane(0.1, -0.2, 0))
        assertEquals(Vectorscope.plane(0.1, -0.2, 1), Vectorscope.plane(0.1, -0.2, -3))
    }

    @Test fun targetMarksSitWhereTheColoursAre() {
        // красный выше всех, голубой ниже всех; синий правее всех, жёлтый левее всех
        val points = Vectorscope.targets.associate { it.name to Vectorscope.plane(it.cb, it.cr, 1) }
        assertTrue(points.getValue("R").y < 0.2 && points.getValue("Cy").y > 0.8)
        assertTrue(points.getValue("B").x > 0.8 && points.getValue("Yl").x < 0.2)
        // красный чуть левее середины (у него Cb < 0), пурпурный правее (Cb > 0), оба над серединой
        assertTrue(points.getValue("R").x < 0.5 && points.getValue("Mg").x > 0.5)
        assertTrue(points.getValue("R").y < 0.5 && points.getValue("Mg").y < 0.5)
    }

    @Test fun skinLineRunsFromTheCentreAt123DegreesCounterClockwise() {
        val end = Vectorscope.skinLineEnd
        // конец — на круге панели (радиус 0,5 от середины)
        val dx = end.x - 0.5
        val dy = end.y - 0.5
        assertEquals(0.5, sqrt(dx * dx + dy * dy), 1e-12)
        // угол от +Cb против часовой стрелки: y экрана — вниз, поэтому «вверх» — это минус dy
        var degrees = Math.toDegrees(atan2(-dy, dx))
        if (degrees < 0) degrees += 360.0
        assertEquals(123.0, degrees, 1e-9)
        assertTrue(end.x < 0.5 && end.y < 0.5)
        assertEquals(123.0, Vectorscope.SKIN_ANGLE, 0.0)
    }

    // метки, которые видно

    @Test fun allSixMarksAreDrawnAtOneAndNoneAtTwo() {
        val atOne = Vectorscope.visibleTargets(1)
        assertEquals(listOf("R", "Mg", "B", "Cy", "G", "Yl"), atOne.map { it.target.name })
        // при ×2 75 % полосы лежат за краем 0,25: метка там была бы вне панели, её не рисуют
        assertEquals(emptyList<Vectorscope.Mark>(), Vectorscope.visibleTargets(2))
        assertEquals(atOne, Vectorscope.visibleTargets(0))
    }

    @Test fun visibleMarksCarryTheirPlaceOnThePanel() {
        for (mark in Vectorscope.visibleTargets(1)) {
            assertEquals(Vectorscope.plane(mark.target.cb, mark.target.cr, 1), mark.at)
        }
    }

    @Test fun marksAreInsideTheCircleButSaturatedPrimariesAreNot() {
        // метки 75 % лежат внутри круга панели
        for (mark in Vectorscope.visibleTargets(1)) {
            val dx = mark.at.x - 0.5
            val dy = mark.at.y - 0.5
            assertTrue(mark.target.name, sqrt(dx * dx + dy * dy) < 0.5)
        }
        // а насыщенные G/Mg (0,596) и R/Cy (0,513) — за кругом, но внутри квадрата плоскости: панель не обрезает картинку по кругу
        for ((r, g, b) in listOf(Triple(0.0, 1.0, 0.0), Triple(1.0, 0.0, 1.0), Triple(1.0, 0.0, 0.0), Triple(0.0, 1.0, 1.0))) {
            val chroma = Vectorscope.chroma(r, g, b)
            val at = Vectorscope.plane(chroma.cb, chroma.cr, 1)
            val distance = sqrt((at.x - 0.5) * (at.x - 0.5) + (at.y - 0.5) * (at.y - 0.5))
            assertTrue("($r, $g, $b) $distance", distance > 0.5)
            assertTrue("($r, $g, $b)", at.x in 0.0..1.0 && at.y in 0.0..1.0)
        }
    }

    // отсчёты у меток — для проверки без снимка экрана

    /** Кадр из шести полос 75 % подряд и белой с чёрной в середине, как у `bars.jpg`. */
    private fun bars(width: Int = 64, height: Int = 36): IntArray {
        val level = 191
        val colours = listOf(
            argb(level, 0, 0), argb(level, 0, level), argb(0, 0, level), argb(0, level, level), argb(0, level, 0),
            argb(level, level, 0), argb(255, 255, 255), argb(0, 0, 0),
        )
        return IntArray(width * height) { colours[(it % width) * colours.size / width] }
    }

    @Test fun barsHitAllSixMarksAtOneAndNoneAtTwo() {
        val one = Vectorscope.targetHits(Vectorscope.counts(bars(), 64, 36, 1), 1)
        assertEquals(6, one.size)
        assertTrue(one.toList().toString(), one.all { it > 0 })
        // при ×2 метки за краем плоскости, а полосы вместе с ними: ни одного отсчёта у меток
        val two = Vectorscope.targetHits(Vectorscope.counts(bars(), 64, 36, 2), 2)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), two.toList())
    }

    @Test fun hitsCountTheBinsWithinTwoOfTheMark() {
        val counts = IntArray(Vectorscope.BINS * Vectorscope.BINS)
        val red = Vectorscope.targets.first { it.name == "R" }
        val bin = checkNotNull(Vectorscope.bin(red.cb, red.cr, 1))
        fun put(dx: Int, dy: Int, n: Int) {
            counts[(bin.y + dy) * Vectorscope.BINS + bin.x + dx] += n
        }
        put(0, 0, 5)
        put(2, -2, 3) // на краю окна ±2 — считается
        put(3, 0, 100) // за окном — нет
        put(0, -3, 100)
        val hits = Vectorscope.targetHits(counts, 1)
        assertEquals(8, hits[0])
        assertEquals(listOf(0, 0, 0, 0, 0), hits.drop(1))
    }

    @Test fun hitsOfEmptyOrWrongSizedCountsAreZero() {
        assertEquals(listOf(0, 0, 0, 0, 0, 0), Vectorscope.targetHits(IntArray(Vectorscope.BINS * Vectorscope.BINS), 1).toList())
        assertEquals(listOf(0, 0, 0, 0, 0, 0), Vectorscope.targetHits(IntArray(5), 1).toList())
    }

    @Test fun hitsOfAUniformGridAreTheWholeWindow() {
        // все метки при ×1 внутри сетки: окно ±2 корзин — это 5 × 5 = 25 отсчётов
        val counts = IntArray(Vectorscope.BINS * Vectorscope.BINS) { 1 }
        assertEquals(listOf(25, 25, 25, 25, 25, 25), Vectorscope.targetHits(counts, 1).toList())
        // при ×2 меток нет в сетке вовсе
        assertEquals(listOf(0, 0, 0, 0, 0, 0), Vectorscope.targetHits(counts, 2).toList())
    }

    @Test fun reportLineNamesZoomPeakAndHits() {
        val counts = Vectorscope.counts(bars(), 64, 36, 1)
        val line = Vectorscope.report(counts, 1)
        assertTrue(line, line.startsWith("вектороскоп ×1:"))
        for (name in listOf("R=", "Mg=", "B=", "Cy=", "G=", "Yl=")) assertTrue("$name в $line", name in line)
        // середина самая полная: туда ложатся белая и чёрная полосы; пик — середина корзины, поэтому не ровно ноль
        assertTrue(line, "пик Cb=0.004 Cr=-0.004" in line)
        // пустые счётчики: пика нет, метки нулевые
        val empty = Vectorscope.report(IntArray(Vectorscope.BINS * Vectorscope.BINS), 2)
        assertEquals("вектороскоп ×2: пик —, метки R=0 Mg=0 B=0 Cy=0 G=0 Yl=0", empty)
    }

    @Test fun peakOfBarsIsTheCentre() {
        val peak = Vectorscope.peak(Vectorscope.counts(bars(), 64, 36, 1), 1)
        assertNotNull(peak)
        assertTrue(near(peak!!.cb, 0.0, 0.01) && near(peak.cr, 0.0, 0.01))
        assertNull(Vectorscope.peak(IntArray(Vectorscope.BINS * Vectorscope.BINS), 1))
    }

    // геометрия панели: плоскость, места меток, буквы

    @Test fun panelIs176AndThePlaneIsTheSquareTheCircleIsInscribedIn() {
        assertEquals(176f, VectorscopeGeometry.SIDE_DP, 0f)
        val plane = VectorscopeGeometry.plane(VectorscopeGeometry.SIDE_DP, VectorscopeGeometry.INSET_DP)
        assertEquals(Rect(4f, 4f, 172f, 172f), plane)
        assertEquals(plane.width, plane.height, 0f)
        // середина плоскости — середина панели: серый лежит ровно посередине
        assertEquals(Offset(88f, 88f), plane.center)
    }

    @Test fun fractionsOfThePlaneLandOnThePlaneNotOnTheCircle() {
        val plane = VectorscopeGeometry.plane(176f, 4f)
        assertEquals(plane.center, VectorscopeGeometry.point(Vectorscope.PanelPoint(0.5, 0.5), plane))
        assertEquals(plane.topLeft, VectorscopeGeometry.point(Vectorscope.PanelPoint(0.0, 0.0), plane))
        assertEquals(plane.bottomRight, VectorscopeGeometry.point(Vectorscope.PanelPoint(1.0, 1.0), plane))
        // насыщенный зелёный (за кругом) остаётся на плоскости, а не срезается кругом: угол квадрата ближе к краю панели
        val green = Vectorscope.chroma(0.0, 1.0, 0.0)
        val at = VectorscopeGeometry.point(Vectorscope.plane(green.cb, green.cr, 1), plane)
        assertTrue(at.x in plane.left..plane.right && at.y in plane.top..plane.bottom)
        val fromMiddle = Math.hypot((at.x - plane.center.x).toDouble(), (at.y - plane.center.y).toDouble())
        assertTrue("$fromMiddle", fromMiddle > plane.width / 2.0)
    }

    @Test fun everyMarkAtOneStandsInsideThePanelWithItsLetterOutsideTheMark() {
        val plane = VectorscopeGeometry.plane(176f, 4f)
        for (mark in Vectorscope.visibleTargets(1)) {
            val at = VectorscopeGeometry.point(mark.at, plane)
            assertTrue(mark.target.name, at.x in 0f..176f && at.y in 0f..176f)
            val letter = VectorscopeGeometry.labelCentre(at, plane.center, VectorscopeGeometry.LABEL_OUT_X_DP, VectorscopeGeometry.LABEL_OUT_Y_DP)
            // буква дальше от середины, чем метка, и остаётся на панели
            fun far(p: Offset) = Math.hypot((p.x - plane.center.x).toDouble(), (p.y - plane.center.y).toDouble())
            assertTrue(mark.target.name, far(letter) > far(at))
            assertTrue("${mark.target.name} $letter", letter.x in 0f..176f && letter.y in 0f..176f)
        }
    }

    @Test fun labelStandsAlongTheRayFromTheMiddle() {
        val centre = Offset(88f, 88f)
        val mark = Offset(120f, 40f) // вверху справа
        val letter = VectorscopeGeometry.labelCentre(mark, centre, 10f, 8f)
        assertTrue(letter.x > mark.x && letter.y < mark.y)
        // по лучу: сдвиг пропорционален направлению от середины к метке
        val dx = mark.x - centre.x
        val dy = mark.y - centre.y
        val length = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        assertEquals(dx / length * 10f, letter.x - mark.x, 1e-4f)
        assertEquals(dy / length * 8f, letter.y - mark.y, 1e-4f)
        // метка у самой середины: делить на ноль нечего, буква на месте
        assertEquals(centre, VectorscopeGeometry.labelCentre(centre, centre, 10f, 8f))
    }
}
