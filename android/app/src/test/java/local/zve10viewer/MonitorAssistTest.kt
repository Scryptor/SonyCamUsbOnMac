package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.AssistRaster
import local.zve10viewer.monitor.AssistRaster.FalseColorBand
import local.zve10viewer.monitor.FrameMarkers
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorAssist.Zebra
import local.zve10viewer.monitor.MonitorTool
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Помощь в кадре — те же проверки, что у программы для Mac (MonitorAssistTests.swift). */
class MonitorAssistTest {
    // настройки

    @Test fun startsOffAndSurvivesJson() {
        assertFalse(MonitorAssist().isActive)
        val assist = MonitorAssist().with(MonitorTool.zebra, "95")!!.with(MonitorTool.frameMarker, "9:16")!!
        val decoded = MonitorAssist.fromJson(assist.toJson())
        assertEquals(assist, decoded)
        assertTrue(decoded.needsRaster)
        assertTrue(decoded.hasMarkers)
        // старые настройки без новых инструментов — выключены; JSON программы для Mac читается так же
        val partial = MonitorAssist.fromJson("""{"zebra":"100"}""")
        assertEquals(MonitorAssist.Zebra.above100, partial.zebra)
        assertEquals(MonitorAssist.Peaking.off, partial.peaking)
        assertEquals(MonitorAssist.PeakingColor.red, partial.peakingColor)
        val mac = MonitorAssist.fromJson("""{"falseColor":true,"center":false,"peaking":"high","lastOn":{"zebra":"70"}}""")
        assertTrue(mac.falseColor)
        assertEquals(MonitorAssist.Peaking.high, mac.peaking)
        assertEquals(mapOf("zebra" to "70"), mac.lastOn)
        assertEquals(MonitorAssist(), MonitorAssist.fromJson("не JSON"))
        assertEquals(MonitorAssist(), MonitorAssist.fromJson(null))
    }

    @Test fun setsByApiValues() {
        val assist = MonitorAssist().with(MonitorTool.peaking, "high")!!.with(MonitorTool.falseColor, "on")!!
            .with(MonitorTool.safeZone, "80")!!
        assertNull(assist.with(MonitorTool.zebra, "50"))
        assertNull(assist.with(MonitorTool.center, "maybe"))
        assertEquals("high", assist.value(MonitorTool.peaking))
        assertEquals("Высокий", assist.label(MonitorTool.peaking))
        assertEquals("Выкл", assist.label(MonitorTool.zebra))
        assertEquals("80 %", assist.label(MonitorTool.safeZone))
        assertTrue(assist.falseColor)
    }

    @Test fun toggleRemembersLastValue() {
        var assist = MonitorAssist().toggled(MonitorTool.zebra)
        // в первый раз — первое значение после «Выкл»
        assertEquals(MonitorAssist.Zebra.above100, assist.zebra)
        assist = assist.with(MonitorTool.zebra, "70")!!.toggled(MonitorTool.zebra)
        assertEquals(MonitorAssist.Zebra.off, assist.zebra)
        assertEquals(MonitorAssist.Zebra.skin70, assist.toggled(MonitorTool.zebra).zebra)
        // у цвета пикинга «выкл» нет — по кругу
        assertEquals(MonitorAssist.PeakingColor.yellow, assist.toggled(MonitorTool.peakingColor).peakingColor)
        assertEquals(MonitorAssist.PeakingColor.red,
            assist.with(MonitorTool.peakingColor, "blue")!!.toggled(MonitorTool.peakingColor).peakingColor)
    }

    @Test fun turnsEverythingOffButKeepsMemory() {
        val assist = MonitorAssist().with(MonitorTool.peaking, "low")!!.with(MonitorTool.peakingColor, "white")!!
            .with(MonitorTool.grid, "thirds")!!.with(MonitorTool.center, "on")!!.allOff()
        assertFalse(assist.isActive)
        assertEquals(MonitorAssist.PeakingColor.white, assist.peakingColor)
        assertEquals(MonitorAssist.Peaking.low, assist.toggled(MonitorTool.peaking).peaking)
    }

    // зебра и false color

    @Test fun zebraThresholdsFollowTheWhiteLevel() {
        assertTrue(AssistRaster.isZebra(233, Zebra.above100, 234))
        assertFalse(AssistRaster.isZebra(232, Zebra.above100, 234))
        assertTrue(AssistRaster.isZebra(223, Zebra.above95, 234))
        assertFalse(AssistRaster.isZebra(222, Zebra.above95, 234))
        // кожа — 70 ± 5 %
        assertFalse(AssistRaster.isZebra(152, Zebra.skin70, 234))
        assertTrue(AssistRaster.isZebra(153, Zebra.skin70, 234))
        assertTrue(AssistRaster.isZebra(175, Zebra.skin70, 234))
        assertFalse(AssistRaster.isZebra(176, Zebra.skin70, 234))
        // фото: белое — 255
        assertTrue(AssistRaster.isZebra(254, Zebra.above100, 255))
        assertFalse(AssistRaster.isZebra(240, Zebra.above100, 255))
        assertFalse(AssistRaster.isZebra(255, Zebra.off, 255))
    }

    @Test fun falseColorBandsCoverTheScale() {
        assertEquals(FalseColorBand.crushed, AssistRaster.falseColorBand(0, 234))
        assertEquals(FalseColorBand.shadows, AssistRaster.falseColorBand(10, 234))
        assertEquals(FalseColorBand.middleGray, AssistRaster.falseColorBand(100, 234))
        assertEquals(FalseColorBand.skin, AssistRaster.falseColorBand(130, 234))
        assertNull(AssistRaster.falseColorBand(70, 234))
        assertEquals(FalseColorBand.nearWhite, AssistRaster.falseColorBand(225, 234))
        // между «почти белым» и пересветом щели нет
        assertEquals(FalseColorBand.nearWhite, AssistRaster.falseColorBand(253, 255))
        assertEquals(FalseColorBand.nearWhite, AssistRaster.falseColorBand(232, 234))
        assertEquals(FalseColorBand.clipped, AssistRaster.falseColorBand(233, 234))
        assertEquals(FalseColorBand.clipped, AssistRaster.falseColorBand(255, 234))
    }

    /** Кадр: слева — уровень left, справа — right, граница после столбца edge. */
    private fun step(width: Int, height: Int, edge: Int, left: Int, right: Int) =
        IntArray(width * height) { if (it % width > edge) right else left }

    @Test fun renderPaintsZebraStripesOnlyWhereBright() {
        val luma = step(16, 8, 7, 100, 234)
        val out = AssistRaster.render(luma, 16, 8, 1.0, 234, MonitorAssist().with(MonitorTool.zebra, "100")!!)!!
        assertEquals(50.0, out.zebraShare, 1e-9)
        assertEquals(0.0, out.peakingShare, 1e-9)
        fun alpha(x: Int, y: Int) = (out.pixels[y * 16 + x] ushr 24)
        assertTrue((0 until 8).all { x -> (0 until 8).all { alpha(x, it) == 0 } })
        // справа — полосы наискось: чёрные на 60 % и прозрачные
        assertEquals(153, alpha(8, 0))
        assertEquals(0, alpha(12, 0))
        assertEquals(0, alpha(11, 1))
        assertEquals(153, alpha(10, 1))
    }

    @Test fun renderFalseColorIsOpaqueEverywhere() {
        val luma = step(8, 4, 3, 0, 234)
        val out = AssistRaster.render(luma, 8, 4, 1.0, 234, MonitorAssist().with(MonitorTool.falseColor, "on")!!)!!
        assertTrue(out.pixels.all { (it ushr 24) == 255 })
        // провал — фиолетовый, пересвет — красный
        assertEquals(0xFF9628C8.toInt(), out.pixels[0])
        assertEquals(0xFFFF1E1E.toInt(), out.pixels[7])
        assertNull(AssistRaster.render(luma, 8, 4, 1.0, 234, MonitorAssist()))
    }

    // пикинг

    @Test fun peakingMarksASharpEdgeAsAThinLine() {
        val mask = AssistRaster.peakingMask(step(20, 10, 9, 40, 200), 20, 10, 30.0, 1.0)
        for (y in 1 until 8) {
            assertEquals(listOf(9), (0 until 20).filter { mask[y * 20 + it] })
        }
    }

    @Test fun peakingIgnoresSoftGradientsAndFlatAreas() {
        val ramp = IntArray(30 * 6) { minOf(250, (it % 30) * 10) }
        assertTrue(AssistRaster.peakingMask(ramp, 30, 6, 20.0, 1.0).none { it })
        assertTrue(AssistRaster.peakingMask(IntArray(100) { 128 }, 10, 10, 20.0, 1.0).none { it })
    }

    @Test fun peakingStaysThinWhenTheViewIsZoomed() {
        // тот же резкий край, увеличенный вчетверо: линия в одну точку, а не полоса в 4
        val luma = step(40, 20, 19, 40, 200)
        val plan = AssistRaster.Plan(AssistRaster.Region(10.0, 5.0, 20.0, 10.0), 80, 40)
        val big = AssistRaster.resample(luma, 40, 20, plan)
        val mask = AssistRaster.peakingMask(big, 80, 40, 60.0, plan.scale)
        for (y in 2 until 38) {
            val hits = (0 until 80).filter { mask[y * 80 + it] }
            assertEquals(1, hits.size)
            assertTrue(hits.all { it in 38..40 })
        }
        // без поправки порога на увеличение край в растре не прошёл бы
        assertTrue(AssistRaster.peakingMask(big, 80, 40, 60.0, 1.0).none { it })
        assertTrue(AssistRaster.peakingMask(luma, 40, 20, 60.0, 1.0).any { it })
    }

    // часть кадра

    @Test fun resampleCopiesWhenNotZoomed() {
        val luma = IntArray(6 * 4) { it }
        val full = AssistRaster.plan(6, 4, 0.0, 0.0, 1.0, 1.0, 1.0)
        assertEquals(AssistRaster.Region(0.0, 0.0, 6.0, 4.0), full.region)
        assertArrayEquals(luma, AssistRaster.resample(luma, 6, 4, full))
        val crop = AssistRaster.Plan(AssistRaster.Region(2.0, 1.0, 3.0, 2.0), 3, 2)
        assertArrayEquals(intArrayOf(8, 9, 10, 14, 15, 16), AssistRaster.resample(luma, 6, 4, crop))
    }

    @Test fun resampleKeepsFlatAreasFlat() {
        val plan = AssistRaster.Plan(AssistRaster.Region(3.3, 2.7, 10.5, 6.0), 63, 36)
        assertTrue(AssistRaster.resample(IntArray(32 * 18) { 77 }, 32, 18, plan).all { it == 77 })
    }

    @Test fun planScalesTheVisiblePartButNotBeyondTheFrame() {
        val plan = AssistRaster.plan(1024, 576, 0.375, 0.375, 0.25, 0.25, 4.0)
        assertEquals(0.35 * 1024, plan.region.x, 0.001)
        assertEquals(0.3 * 1024, plan.region.width, 0.001)
        assertTrue(plan.width * plan.height <= 1024 * 576 * AssistRaster.MAX_PIXELS_RATIO + 2048)
        assertTrue(plan.scale > 3.5 && plan.scale <= 4)
        val eight = AssistRaster.plan(1024, 576, 0.4375, 0.4375, 0.125, 0.125, 8.0)
        assertTrue(eight.scale > 7 && eight.scale <= 8)
    }

    @Test fun lumaMatchesTheHistogramFormula() {
        val argb = intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFC8C8C8.toInt())
        assertArrayEquals(intArrayOf(54, 182, 18, 200), AssistRaster.luma(argb))
    }

    // маркеры

    private fun near(a: Float, b: Double) = abs(a - b) < 0.0001

    @Test fun frameMarkersFitInsideTheFrame() {
        val frame = Size(1024f, 576f)
        val vertical = FrameMarkers.markerRect(9.0 / 16, frame)!!
        assertTrue(near(vertical.width, (9.0 / 16) / (16.0 / 9)))
        assertEquals(1f, vertical.height)
        assertTrue(near(vertical.center.x, 0.5))
        val scope = FrameMarkers.markerRect(2.39, frame)!!
        assertEquals(1f, scope.width)
        assertTrue(near(scope.height, (16.0 / 9) / 2.39))
        assertNull(FrameMarkers.markerRect(16.0 / 9, frame))
        assertTrue(near(FrameMarkers.markerRect(9.0 / 16, Size(1024f, 1024f))!!.width, 0.5625))
    }

    @Test fun safeZoneAndGridFollowTheMarker() {
        var assist = MonitorAssist().with(MonitorTool.frameMarker, "1:1")!!.with(MonitorTool.safeZone, "90")!!
            .with(MonitorTool.grid, "thirds")!!.with(MonitorTool.center, "on")!!
        val layout = FrameMarkers.layout(assist, Size(1600f, 900f))
        val marker = layout.marker!!
        val safe = layout.safe!!
        assertTrue(near(safe.width, marker.width * 0.9))
        assertTrue(near(safe.center.x, 0.5))
        assertEquals(4, layout.grid.size)
        assertTrue(layout.grid.all { it.from.x >= marker.left - 0.0001f && it.to.x <= marker.right + 0.0001f })
        assertEquals(Offset(0.5f, 0.5f), layout.center)
        assist = assist.with(MonitorTool.frameMarker, "off")!!
        val plain = FrameMarkers.layout(assist, Size(1600f, 900f)).safe!!
        assertTrue(near(plain.left, 0.05) && near(plain.top, 0.05) && near(plain.width, 0.9))
    }

    @Test fun squareGridHasSquareCells() {
        val frame = Size(1600f, 900f)
        val lines = FrameMarkers.grid(MonitorAssist.Grid.squares, Rect(0f, 0f, 1f, 1f), frame)
        val verticals = lines.filter { it.from.x == it.to.x }.map { it.from.x }.sorted()
        val horizontals = lines.filter { it.from.y == it.to.y }.map { it.from.y }.sorted()
        assertEquals(3, horizontals.size)
        val stepX = (verticals[1] - verticals[0]) * frame.width
        val stepY = (horizontals[1] - horizontals[0]) * frame.height
        assertTrue(abs(stepX - stepY) < 0.01)
        assertTrue(0.5f in verticals)
        assertNotNull(lines)
    }
}
