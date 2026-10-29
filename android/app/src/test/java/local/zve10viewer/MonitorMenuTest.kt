package local.zve10viewer

import local.zve10viewer.monitor.AssistExtras
import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.ColorProbe
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.LutChoice
import local.zve10viewer.monitor.LutTexts
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorAssist.LutMode
import local.zve10viewer.monitor.MonitorTool
import local.zve10viewer.ui.assistTileRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Меню «Монитор»: четырнадцать плиток по семь в ряду, подписи плитки LUT, тексты полосы LUT и отладочные extras. Подписи —
 * те же, что у программы для Mac (`MonitorAssistViews.swift`, `global-constraints.md`).
 */
class MonitorMenuTest {
    // плитки

    @Test fun menuIsFourteenTilesInTwoRowsOfSeven() {
        val rows = assistTileRows()
        assertEquals(listOf(7, 7), rows.map { it.size })
        assertEquals(14, rows.sumOf { it.size })
        assertEquals(listOf("zebra", "falseColor", "peaking", "peakingColor", "lut", "vectorscope", "probe"), rows[0].map { it?.name })
        assertEquals(listOf("grid", "center", "frameMarker", "safeZone", "overlay", "orientation", null), rows[1].map { it?.name })
        // «Всё — выключить» — последняя, не инструмент
        assertNull(rows.last().last())
        assertEquals(13, rows.flatten().filterNotNull().size)
        assertEquals(MonitorTool.entries, rows.flatten().filterNotNull())
    }

    @Test fun tileTitlesAreTheOnesTheChecksLookFor() {
        val rows = assistTileRows()
        assertEquals(listOf("Зебра", "False color", "Пикинг", "Цвет пикинга", "LUT", "Вектороскоп", "Пипетка"), rows[0].map { it?.title })
        assertEquals(listOf("Сетка", "Центр", "Рамка", "Безоп. зона", "Наложение", "Картинка"), rows[1].take(6).map { it?.title })
    }

    @Test fun newTilesHaveTheirOptionsAndLabels() {
        assertEquals(listOf("Выкл", "Авто", "S-Log3 Cine", "S-Log3", "S-Log2", "HLG", "Свой"), MonitorTool.lut.options.map { it.second })
        assertEquals(listOf("Выкл", "Вкл"), MonitorTool.vectorscope.options.map { it.second })
        assertEquals(listOf("Выкл", "Вкл"), MonitorTool.probe.options.map { it.second })
        assertEquals(listOf("Как есть", "Зеркально", "Вверх ногами", "Зеркально + 180°"), MonitorTool.orientation.options.map { it.second })
        // значение на самой плитке «Картинка» короче, чем в полосе вариантов
        val labels = FrameOrientation.entries.map { MonitorAssist().with(MonitorTool.orientation, it.value)!!.label(MonitorTool.orientation) }
        assertEquals(listOf("—", "Зеркало", "180°", "Зеркало 180°"), labels)
        assertEquals("Выкл", MonitorAssist().label(MonitorTool.vectorscope))
        assertEquals("Вкл", MonitorAssist().with(MonitorTool.probe, "on")!!.label(MonitorTool.probe))
    }

    // подпись плитки LUT

    private val names = listOf("Тёплый закат", "Mono")

    @Test fun lutTileFollowsTheModeAndTheCameraProfile() {
        val off = MonitorAssist()
        assertEquals("Выкл", LutChoice.tileValue(off, null, names))
        // «Авто» говорит, какой LUT выбрала камера: «Авто: S-Log3» / «Авто: нет»
        val auto = off.with(MonitorTool.lut, "auto")!!
        assertEquals("Авто: S-Log3", LutChoice.tileValue(auto, BuiltinLut.slog3, names))
        assertEquals("Авто: S-Log3 Cine", LutChoice.tileValue(auto, BuiltinLut.slog3cine, names))
        assertEquals("Авто: S-Log2", LutChoice.tileValue(auto, BuiltinLut.slog2, names))
        assertEquals("Авто: HLG", LutChoice.tileValue(auto, BuiltinLut.hlg, names))
        assertEquals("Авто: нет", LutChoice.tileValue(auto, null, names))
        // выбор рукой — как в полосе
        assertEquals("S-Log2", LutChoice.tileValue(off.with(MonitorTool.lut, "slog2")!!, BuiltinLut.slog2, names))
        assertEquals("HLG", LutChoice.tileValue(off.with(MonitorTool.lut, "hlg")!!, BuiltinLut.hlg, names))
    }

    @Test fun ownLutTileShowsTheFileNameWhileTheFileIsInTheFolder() {
        val chosen = MonitorAssist().lutFileSelected("Тёплый закат")
        assertEquals("Тёплый закат", LutChoice.tileValue(chosen, null, names))
        // файл пропал из папки (убрали руками): плитка снова «Свой», а не имя несуществующего файла
        assertEquals("Свой", LutChoice.tileValue(chosen, null, listOf("Mono")))
        assertEquals("Свой", LutChoice.tileValue(chosen, null, emptyList()))
        // файл не выбран
        assertEquals("Свой", LutChoice.tileValue(MonitorAssist().with(MonitorTool.lut, "file")!!, null, names))
        assertEquals("Свой", LutChoice.tileValue(MonitorAssist(lut = LutMode.file, lutFile = ""), null, names))
    }

    @Test fun lutTileOutsideTheFileModeIgnoresTheRememberedFile() {
        val assist = MonitorAssist().lutFileSelected("Mono").with(MonitorTool.lut, "slog3")!!
        assertEquals("S-Log3", LutChoice.tileValue(assist, BuiltinLut.slog3, names))
        assertEquals("Выкл", LutChoice.tileValue(assist.with(MonitorTool.lut, "off")!!, null, names))
    }

    // тексты полосы LUT

    @Test fun stripTextsAreTheOnesOfTheMac() {
        assertEquals("Помощь — по картинке с LUT", LutTexts.SCOPES)
        assertEquals("Добавить LUT…", LutTexts.ADD)
        assertEquals("Удалить", LutTexts.DELETE)
        assertEquals("LUT «Тёплый закат» добавлен", LutTexts.added("Тёплый закат"))
        assertTrue(LutTexts.EMPTY.contains("Добавить LUT…"))
        // справка к переключателю называет всю помощь и белый 255
        for (word in listOf("Гистограмма", "волновая форма", "вектороскоп", "зебра", "false color", "пикинг", "пипетка", "255")) {
            assertTrue(word, LutTexts.SCOPES_HELP.contains(word))
        }
        assertTrue(LutTexts.FILES_HELP.contains("12"))
    }

    // отладочные extras DEBUG_ASSIST

    private fun apply(assist: MonitorAssist = MonitorAssist(), lut: Any? = null, lutFile: Any? = null, lutScopes: Any? = null,
                      vectorscope: Any? = null, zoom: Any? = null, orientation: Any? = null, probe: Any? = null, probeX: Any? = null,
                      probeY: Any? = null, at: FramePoint = ColorProbe.CENTER) =
        AssistExtras.apply(assist, AssistExtras.Request(lut, lutFile, lutScopes, vectorscope, zoom, orientation, probe, probeX, probeY), at)

    @Test fun noExtrasChangeNothing() {
        val assist = MonitorAssist().with(MonitorTool.zebra, "95")!!
        val result = apply(assist)
        assertSame(assist, result.assist)
        assertTrue(result.rejected.isEmpty())
        assertEquals(ColorProbe.CENTER, result.probe)
        assertTrue(AssistExtras.Request().isEmpty)
        assertFalse(AssistExtras.Request(lut = "auto").isEmpty)
        // каждый extra делает запрос непустым
        assertFalse(AssistExtras.Request(orientation = "mirror").isEmpty)
        assertFalse(AssistExtras.Request(probe = true).isEmpty)
        assertFalse(AssistExtras.Request(probeX = 0.5).isEmpty)
        assertFalse(AssistExtras.Request(probeY = 0.5).isEmpty)
    }

    @Test fun lutExtraSetsTheMode() {
        for (mode in LutMode.entries) {
            val result = apply(lut = mode.value)
            assertEquals(mode, result.assist.lut)
            assertTrue(result.rejected.isEmpty())
        }
        // остальное не трогается
        val result = apply(MonitorAssist().with(MonitorTool.zebra, "70")!!.with(MonitorTool.orientation, "mirror")!!, lut = "slog3cine")
        assertEquals(LutMode.slog3cine, result.assist.lut)
        assertEquals(MonitorAssist.Zebra.skin70, result.assist.zebra)
        assertEquals(FrameOrientation.mirror, result.assist.orientation)
    }

    @Test fun badLutValueIsRejectedAndNothingChanges() {
        val start = MonitorAssist().with(MonitorTool.lut, "hlg")!!
        for (bad in listOf("bogus", "S-Log3", "AUTO", "", 3, true)) {
            val result = apply(start, lut = bad)
            assertEquals("$bad", LutMode.hlg, result.assist.lut)
            assertEquals("$bad", listOf("lut"), result.rejected)
        }
    }

    @Test fun vectorscopeExtraTakesBooleansAndOnOff() {
        assertTrue(apply(vectorscope = true).assist.vectorscope)
        assertTrue(apply(vectorscope = "on").assist.vectorscope)
        assertTrue(apply(vectorscope = "true").assist.vectorscope)
        assertTrue(apply(vectorscope = 1).assist.vectorscope)
        val on = MonitorAssist().with(MonitorTool.vectorscope, "on")!!
        assertFalse(apply(on, vectorscope = false).assist.vectorscope)
        assertFalse(apply(on, vectorscope = "off").assist.vectorscope)
        assertFalse(apply(on, vectorscope = "False").assist.vectorscope)
        assertFalse(apply(on, vectorscope = 0).assist.vectorscope)
        // что не понято — отказ, состояние прежнее
        val bad = apply(on, vectorscope = "maybe")
        assertTrue(bad.assist.vectorscope)
        assertEquals(listOf("vectorscope"), bad.rejected)
    }

    @Test fun vectorscopeZoomExtraIsOneOrTwo() {
        assertEquals(2, apply(zoom = 2).assist.vectorscopeZoom)
        assertEquals(2, apply(zoom = "2").assist.vectorscopeZoom)
        assertEquals(2, apply(zoom = 2L).assist.vectorscopeZoom)
        assertEquals(1, apply(MonitorAssist(vectorscopeZoom = 2), zoom = 1).assist.vectorscopeZoom)
        for (bad in listOf(0, 3, -1, "x", "1.5", true)) {
            val result = apply(MonitorAssist(vectorscopeZoom = 2), zoom = bad)
            assertEquals("$bad", 2, result.assist.vectorscopeZoom)
            assertEquals("$bad", listOf("vectorscopeZoom"), result.rejected)
        }
        // масштаб ставится и при выключенном вектороскопе: он настройка, а не включатель
        val off = apply(zoom = 2).assist
        assertFalse(off.vectorscope)
        assertEquals(2, off.vectorscopeZoom)
    }

    @Test fun lutScopesExtra() {
        assertFalse(apply(lutScopes = false).assist.lutScopes)
        assertFalse(apply(lutScopes = "off").assist.lutScopes)
        assertTrue(apply(MonitorAssist(lutScopes = false), lutScopes = true).assist.lutScopes)
        assertEquals(listOf("lutScopes"), apply(lutScopes = "so-so").rejected)
    }

    @Test fun lutFileExtraSelectsTheFileAndTheOwnMode() {
        val result = apply(lutFile = "Тёплый закат")
        assertEquals(LutMode.file, result.assist.lut)
        assertEquals("Тёплый закат", result.assist.lutFile)
        // вместе с режимом: сначала файл, потом режим — «lut off» выключает, но файл помнится
        val remembered = apply(lut = "off", lutFile = "Mono")
        assertEquals(LutMode.off, remembered.assist.lut)
        assertEquals("Mono", remembered.assist.lutFile)
        assertEquals(listOf("lutFile"), apply(lutFile = "").rejected)
        assertEquals(listOf("lutFile"), apply(lutFile = 5).rejected)
        assertNull(apply(lutFile = "  ").assist.lutFile)
    }

    @Test fun severalExtrasAtOnceAreAppliedTogetherAndBadOnesAreNamed() {
        val result = apply(lut = "slog3", vectorscope = "on", zoom = 7, lutScopes = false)
        assertEquals(LutMode.slog3, result.assist.lut)
        assertTrue(result.assist.vectorscope)
        assertEquals(1, result.assist.vectorscopeZoom)
        assertFalse(result.assist.lutScopes)
        assertEquals(listOf("vectorscopeZoom"), result.rejected)
    }

    @Test fun theLogLineNamesWhatTheCommandLeft() {
        assertEquals("монитор: lut=off lutFile=- lutScopes=true vectorscope=off zoom=1 orient=off probe=off at=0.500,0.500",
            AssistExtras.describe(MonitorAssist()))
        val assist = apply(lut = "auto", vectorscope = true, zoom = 2, lutScopes = false).assist
        assertEquals("монитор: lut=auto lutFile=- lutScopes=false vectorscope=on zoom=2 orient=off probe=off at=0.500,0.500",
            AssistExtras.describe(assist))
        assertEquals("монитор: lut=file lutFile=Warm lutScopes=true vectorscope=off zoom=1 orient=off probe=off at=0.500,0.500",
            AssistExtras.describe(apply(lutFile = "Warm").assist))
        // картинка и пипетка: что выбрано и где стоит метка (доли кадра на экране)
        val mirrored = apply(orientation = "mirror", probe = true, probeX = 0.3, probeY = 0.7)
        assertEquals("монитор: lut=off lutFile=- lutScopes=true vectorscope=off zoom=1 orient=mirror probe=on at=0.300,0.700",
            AssistExtras.describe(mirrored.assist, mirrored.probe))
    }

    // картинка и пипетка: orientation, probe, probeX, probeY

    @Test fun orientationExtraSetsThePictureAndKeepsTheRest() {
        for (o in FrameOrientation.entries) {
            val result = apply(orientation = o.value)
            assertEquals(o, result.assist.orientation)
            assertTrue(result.rejected.isEmpty())
        }
        // «180» приходит строкой, как её кладёт `--es orientation 180`
        assertEquals(FrameOrientation.rotate180, apply(orientation = "180").assist.orientation)
        val start = MonitorAssist().with(MonitorTool.zebra, "70")!!.with(MonitorTool.lut, "hlg")!!
        val result = apply(start, orientation = "mirror180")
        assertEquals(FrameOrientation.mirror180, result.assist.orientation)
        assertEquals(MonitorAssist.Zebra.skin70, result.assist.zebra)
        assertEquals(LutMode.hlg, result.assist.lut)
        // и обратно
        assertEquals(FrameOrientation.off, apply(MonitorAssist(orientation = FrameOrientation.mirror), orientation = "off").assist.orientation)
    }

    @Test fun badOrientationIsRejectedAndNothingChanges() {
        val start = MonitorAssist(orientation = FrameOrientation.mirror)
        for (bad in listOf("flip", "MIRROR", "rotate180", "90", "", 180, true)) {
            val result = apply(start, orientation = bad)
            assertEquals("$bad", FrameOrientation.mirror, result.assist.orientation)
            assertEquals("$bad", listOf("orientation"), result.rejected)
        }
    }

    @Test fun probeExtraTakesBooleansAndOnOff() {
        assertTrue(apply(probe = true).assist.probe)
        assertTrue(apply(probe = "on").assist.probe)
        assertTrue(apply(probe = "true").assist.probe)
        assertTrue(apply(probe = 1).assist.probe)
        val on = MonitorAssist(probe = true)
        assertFalse(apply(on, probe = false).assist.probe)
        assertFalse(apply(on, probe = "off").assist.probe)
        assertFalse(apply(on, probe = 0).assist.probe)
        val bad = apply(on, probe = "maybe")
        assertTrue(bad.assist.probe)
        assertEquals(listOf("probe"), bad.rejected)
    }

    @Test fun probePointExtrasAreScreenFractionsAndMoveOneCoordinateAtATime() {
        val both = apply(probeX = 0.6875, probeY = 0.3)
        assertEquals(FramePoint(0.6875, 0.3), both.probe)
        assertTrue(both.rejected.isEmpty())
        // одна координата — вторая остаётся на месте
        val here = FramePoint(0.2, 0.9)
        assertEquals(FramePoint(0.4, 0.9), apply(probeX = 0.4, at = here).probe)
        assertEquals(FramePoint(0.2, 0.1), apply(probeY = 0.1, at = here).probe)
        // `--ef` приходит Float, `--ei` целым, `--es` строкой
        assertEquals(0.5, apply(probeX = 0.5f).probe.x, 1e-7)
        assertEquals(1.0, apply(probeX = 1).probe.x, 0.0)
        assertEquals(0.25, apply(probeX = "0.25").probe.x, 0.0)
        assertEquals(0.75, apply(probeY = " 0.75 ").probe.y, 0.0)
    }

    @Test fun probePointExtrasAreKeptInsideTheFrame() {
        // как действие API на Mac: за кадром — к краю
        val far = apply(probeX = -3, probeY = 7.5)
        assertEquals(FramePoint(0.0, 1.0), far.probe)
        assertTrue(far.rejected.isEmpty())
    }

    @Test fun probePointExtrasThatAreNotNumbersAreRejectedAndTheMarkStays() {
        val here = FramePoint(0.3, 0.4)
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, "x", "", true, "NaN")) {
            val x = apply(probeX = bad, at = here)
            assertEquals("$bad", here, x.probe)
            assertEquals("$bad", listOf("probeX"), x.rejected)
            val y = apply(probeY = bad, at = here)
            assertEquals("$bad", here, y.probe)
            assertEquals("$bad", listOf("probeY"), y.rejected)
        }
        // одна плохая, одна хорошая: хорошая ставится
        val mixed = apply(probeX = "bad", probeY = 0.8, at = here)
        assertEquals(FramePoint(0.3, 0.8), mixed.probe)
        assertEquals(listOf("probeX"), mixed.rejected)
    }

    @Test fun probeExtrasWorkTogetherWithTheOldOnes() {
        val result = apply(lut = "slog3", orientation = "mirror", probe = "on", probeX = 0.3125, probeY = 0.3, vectorscope = true)
        assertEquals(LutMode.slog3, result.assist.lut)
        assertEquals(FrameOrientation.mirror, result.assist.orientation)
        assertTrue(result.assist.probe)
        assertTrue(result.assist.vectorscope)
        assertEquals(FramePoint(0.3125, 0.3), result.probe)
        assertTrue(result.rejected.isEmpty())
    }
}
