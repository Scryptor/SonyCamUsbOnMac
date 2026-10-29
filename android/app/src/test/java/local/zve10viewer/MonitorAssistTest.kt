package local.zve10viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.AssistRaster
import local.zve10viewer.monitor.AssistRaster.FalseColorBand
import local.zve10viewer.monitor.FrameMarkers
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorAssist.LutMode
import local.zve10viewer.monitor.MonitorAssist.Zebra
import local.zve10viewer.monitor.MonitorTool
import local.zve10viewer.monitor.Waveform
import org.json.JSONObject
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
        // цвет пикинга горит вместе с пикингом
        assertTrue(assist.isOn(MonitorTool.peakingColor))
        assertFalse(MonitorAssist().isOn(MonitorTool.peakingColor))
        assertFalse(assist.isOn(MonitorTool.zebra))
    }

    @Test fun overlayIsAToolWithOpacity() {
        val assist = MonitorAssist().with(MonitorTool.overlay, "50")!!
        assertTrue(assist.isActive)
        assertFalse(assist.needsRaster || assist.hasMarkers)
        assertEquals(0.5f, assist.overlay.opacity)
        assertEquals("50 %", assist.label(MonitorTool.overlay))
        assertEquals(MonitorAssist.Overlay.off, assist.toggled(MonitorTool.overlay).overlay)
        assertEquals(50, assist.toggled(MonitorTool.overlay).toggled(MonitorTool.overlay).overlay.percent)
        assertEquals(assist, MonitorAssist.fromJson(assist.toJson()))
    }

    @Test fun overlayOpacityIsAnyPercentFrom5To100() {
        val assist = MonitorAssist().with(MonitorTool.overlay, "40")!!
        assertEquals(0.4f, assist.overlay.opacity!!, 1e-6f)
        assertEquals("40 %", assist.label(MonitorTool.overlay))
        assertNull(assist.with(MonitorTool.overlay, "0"))
        assertNull(assist.with(MonitorTool.overlay, "105"))
        assertNull(assist.with(MonitorTool.overlay, "half"))
        // ползунок — шагом 5 %, не меньше 5
        assertEquals(45, MonitorAssist.Overlay.steps(0.43f).percent)
        assertEquals(5, MonitorAssist.Overlay.steps(0f).percent)
        assertEquals(100, MonitorAssist.Overlay.steps(1f).percent)
        // повторное касание плитки возвращает 40 %, а не первую ступень
        assertEquals(40, assist.toggled(MonitorTool.overlay).toggled(MonitorTool.overlay).overlay.percent)
        // стрелки — к соседней ступени
        assertEquals("50", assist.stepped(MonitorTool.overlay, 1))
        assertEquals("25", assist.stepped(MonitorTool.overlay, -1))
        assertEquals("off", assist.with(MonitorTool.overlay, "10")!!.stepped(MonitorTool.overlay, -1))
        assertEquals("100", assist.with(MonitorTool.overlay, "100")!!.stepped(MonitorTool.overlay, 1))
        // старые настройки читаются как раньше, битые — выключено; как у программы для Mac
        assertEquals(25, MonitorAssist.fromJson("""{"overlay":"25","zebra":"95"}""").overlay.percent)
        assertEquals(MonitorAssist.Overlay.off, MonitorAssist.fromJson("""{"overlay":"7%"}""").overlay)
        assertEquals(assist, MonitorAssist.fromJson(assist.toJson()))
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

    // LUT, вектороскоп, пипетка, картинка

    @Test fun monitorToolsKeepTheTileOrder() {
        // 13 плиток; четырнадцатая — «Всё — выключить» — рисуется отдельно. Ряд 1: до «Пипетки», ряд 2: от «Сетки»
        assertEquals(
            listOf(
                "zebra", "falseColor", "peaking", "peakingColor", "lut", "vectorscope", "probe",
                "grid", "center", "frameMarker", "safeZone", "overlay", "orientation",
            ),
            MonitorTool.entries.map { it.name },
        )
    }

    @Test fun newToolsHaveTheirTitlesOptionsAndHelp() {
        assertEquals("LUT", MonitorTool.lut.title)
        assertEquals("Вектороскоп", MonitorTool.vectorscope.title)
        assertEquals("Пипетка", MonitorTool.probe.title)
        assertEquals("Картинка", MonitorTool.orientation.title)

        assertEquals(listOf("off", "auto", "slog3cine", "slog3", "slog2", "hlg", "file"), MonitorTool.lut.options.map { it.first })
        assertEquals(listOf("Выкл", "Авто", "S-Log3 Cine", "S-Log3", "S-Log2", "HLG", "Свой"), MonitorTool.lut.options.map { it.second })
        for (tool in listOf(MonitorTool.vectorscope, MonitorTool.probe)) {
            assertEquals(listOf("off", "on"), tool.options.map { it.first })
            assertEquals(listOf("Выкл", "Вкл"), tool.options.map { it.second })
        }
        assertEquals(listOf("off", "mirror", "180", "mirror180"), MonitorTool.orientation.options.map { it.first })
        assertEquals(listOf("Как есть", "Зеркально", "Вверх ногами", "Зеркально + 180°"), MonitorTool.orientation.options.map { it.second })

        // у каждой плитки есть справка, и у новых она говорит главное
        for (tool in MonitorTool.entries) assertTrue("$tool", tool.help.isNotEmpty())
        // «Авто» — по профилю с заводскими гаммами Sony; перенастроенный профиль — вручную
        for (pp in listOf("PP7", "S-Log2", "PP8", "PP9", "S-Log3", "PP10", "HLG")) assertTrue(pp, MonitorTool.lut.help.contains(pp))
        assertTrue(MonitorTool.probe.help.contains("Касание кадра ставит пипетку; пока она включена, фокус касанием не ставится."))
        assertTrue(MonitorTool.vectorscope.help.contains("×2"))
        assertTrue(MonitorTool.orientation.help.contains("Зеркально") && MonitorTool.orientation.help.contains("Вверх ногами"))
    }

    @Test fun lutIsSetByItsApiValues() {
        var assist = MonitorAssist().with(MonitorTool.lut, "auto")!!
        assertEquals(LutMode.auto, assist.lut)
        assertEquals("auto", assist.value(MonitorTool.lut))
        // все семь значений — в обе стороны
        for (mode in LutMode.entries) {
            assist = assist.with(MonitorTool.lut, mode.value)!!
            assertEquals(mode, assist.lut)
            assertEquals(mode.value, assist.value(MonitorTool.lut))
        }
        assist = assist.with(MonitorTool.lut, "slog2")!!
        // чужие значения не принимаются и ничего не меняют
        for (bad in listOf("bogus", "S-Log2", "on", "", "AUTO")) assertNull(bad, assist.with(MonitorTool.lut, bad))
        assertEquals(LutMode.slog2, assist.lut)
        assertEquals(listOf("off", "auto", "slog3cine", "slog3", "slog2", "hlg", "file"), LutMode.entries.map { it.value })
    }

    @Test fun orientationIsSetByItsApiValues() {
        var assist = MonitorAssist().with(MonitorTool.orientation, "180")!!
        assertEquals(FrameOrientation.rotate180, assist.orientation)
        assertEquals("180", assist.value(MonitorTool.orientation))
        for (o in FrameOrientation.entries) {
            assist = assist.with(MonitorTool.orientation, o.value)!!
            assertEquals(o, assist.orientation)
            assertEquals(o.value, assist.value(MonitorTool.orientation))
        }
        assist = assist.with(MonitorTool.orientation, "mirror")!!
        // в API — «180», а не имя варианта
        for (bad in listOf("rotate180", "90", "flip", "on")) assertNull(bad, assist.with(MonitorTool.orientation, bad))
        assertEquals(FrameOrientation.mirror, assist.orientation)
    }

    @Test fun vectorscopeAndProbeAreOnOffInTheApi() {
        var assist = MonitorAssist()
        assertTrue(assist.value(MonitorTool.vectorscope) == "off" && assist.value(MonitorTool.probe) == "off")
        assist = assist.with(MonitorTool.vectorscope, "on")!!.with(MonitorTool.probe, "on")!!
        assertTrue(assist.vectorscope && assist.probe)
        assertTrue(assist.value(MonitorTool.vectorscope) == "on" && assist.value(MonitorTool.probe) == "on")
        assist = assist.with(MonitorTool.vectorscope, "off")!!.with(MonitorTool.probe, "off")!!
        assertFalse(assist.vectorscope || assist.probe)
        // как у false color: «true» и «1» тоже читаются
        assist = assist.with(MonitorTool.vectorscope, "true")!!.with(MonitorTool.probe, "1")!!
        assertTrue(assist.vectorscope && assist.probe)
        assertNull(assist.with(MonitorTool.vectorscope, "maybe"))
        assertNull(assist.with(MonitorTool.probe, "2"))
        assertNull(assist.with(MonitorTool.probe, ""))
        assertTrue(assist.vectorscope && assist.probe)
    }

    @Test fun newToolsLightUpTheirTilesWhenOn() {
        val tools = listOf(MonitorTool.lut, MonitorTool.vectorscope, MonitorTool.probe, MonitorTool.orientation)
        for (tool in tools) assertFalse("$tool", MonitorAssist().isOn(tool))
        val assist = MonitorAssist().with(MonitorTool.lut, "hlg")!!.with(MonitorTool.vectorscope, "on")!!
            .with(MonitorTool.probe, "on")!!.with(MonitorTool.orientation, "mirror180")!!
        for (tool in tools) assertTrue("$tool", assist.isOn(tool))
        // «Авто» тоже горит, даже когда профиль не опознан: гасит её сама плитка
        assertTrue(assist.with(MonitorTool.lut, "auto")!!.isOn(MonitorTool.lut))
    }

    @Test fun lutToggleRemembersTheLastValue() {
        // в первый раз — «Авто», первое значение после «Выкл»
        var assist = MonitorAssist().toggled(MonitorTool.lut)
        assertEquals(LutMode.auto, assist.lut)
        // после «Авто»: выкл, и снова «Авто»
        assist = assist.with(MonitorTool.lut, "auto")!!.toggled(MonitorTool.lut)
        assertEquals(LutMode.off, assist.lut)
        assist = assist.toggled(MonitorTool.lut)
        assertEquals(LutMode.auto, assist.lut)
        // запомнился последний выбор, а не первый
        assist = assist.with(MonitorTool.lut, "slog2")!!.toggled(MonitorTool.lut)
        assertEquals(LutMode.off, assist.lut)
        assist = assist.toggled(MonitorTool.lut)
        assertEquals(LutMode.slog2, assist.lut)
        assist = assist.with(MonitorTool.lut, "file")!!.toggled(MonitorTool.lut).toggled(MonitorTool.lut)
        assertEquals(LutMode.file, assist.lut)
    }

    @Test fun otherNewToolsToggleLikeTheOldOnes() {
        var assist = MonitorAssist().toggled(MonitorTool.vectorscope).toggled(MonitorTool.probe).toggled(MonitorTool.orientation)
        assertTrue(assist.vectorscope && assist.probe)
        assertEquals(FrameOrientation.mirror, assist.orientation)
        assist = assist.with(MonitorTool.orientation, "mirror180")!!.toggled(MonitorTool.orientation)
        assertEquals(FrameOrientation.off, assist.orientation)
        assist = assist.toggled(MonitorTool.orientation)
        assertEquals(FrameOrientation.mirror180, assist.orientation)
        assist = assist.toggled(MonitorTool.vectorscope).toggled(MonitorTool.probe)
        assertFalse(assist.vectorscope || assist.probe)
        assist = assist.toggled(MonitorTool.vectorscope)
        assertTrue(assist.vectorscope)
    }

    @Test fun newToolsStepThroughTheirOptions() {
        var assist = MonitorAssist()
        assertEquals("auto", assist.stepped(MonitorTool.lut, 1))
        assist = assist.with(MonitorTool.lut, "hlg")!!
        assertEquals("file", assist.stepped(MonitorTool.lut, 1))
        assertEquals("file", assist.stepped(MonitorTool.lut, 5))
        assertEquals("slog2", assist.stepped(MonitorTool.lut, -1))
        assist = assist.with(MonitorTool.orientation, "mirror")!!
        assertEquals("off", assist.stepped(MonitorTool.orientation, -1))
        assertEquals("180", assist.stepped(MonitorTool.orientation, 1))
        assertEquals("on", assist.stepped(MonitorTool.vectorscope, 1))
        assertTrue(MonitorAssist.accepts(MonitorTool.lut, "slog3cine"))
        assertFalse(MonitorAssist.accepts(MonitorTool.lut, "slog4"))
        assertTrue(MonitorAssist.accepts(MonitorTool.orientation, "180") && !MonitorAssist.accepts(MonitorTool.orientation, "rotate180"))
        assertTrue(MonitorAssist.accepts(MonitorTool.probe, "on") && !MonitorAssist.accepts(MonitorTool.probe, "true"))
    }

    @Test fun lutTileLabelFollowsTheChoice() {
        var assist = MonitorAssist()
        assertEquals("Выкл", assist.label(MonitorTool.lut))
        // подпись «Авто: S-Log3» решает LutChoice по профилю камеры; здесь — «Авто»
        assist = assist.with(MonitorTool.lut, "auto")!!
        assertEquals("Авто", assist.label(MonitorTool.lut))
        assertEquals("S-Log3 Cine", assist.with(MonitorTool.lut, "slog3cine")!!.label(MonitorTool.lut))
        assertEquals("HLG", assist.with(MonitorTool.lut, "hlg")!!.label(MonitorTool.lut))
        // свой файл: на плитке его имя; файл ещё не выбран — «Свой»
        assist = assist.with(MonitorTool.lut, "file")!!
        assertEquals("Свой", assist.label(MonitorTool.lut))
        assist = assist.copy(lutFile = "Тёплый закат")
        assertEquals("Тёплый закат", assist.label(MonitorTool.lut))
        assertEquals("Свой", assist.copy(lutFile = "").label(MonitorTool.lut))
        // файл помнится, но при другом выборе не показывается
        assertEquals("S-Log2", assist.with(MonitorTool.lut, "slog2")!!.label(MonitorTool.lut))
        assertEquals("Выкл", assist.with(MonitorTool.lut, "off")!!.label(MonitorTool.lut))
    }

    @Test fun pictureTileShowsTheShortLabel() {
        val assist = MonitorAssist()
        // на плитке — короткое значение, в полосе вариантов — длинное
        assertEquals("—", assist.label(MonitorTool.orientation))
        assertEquals("Зеркало", assist.with(MonitorTool.orientation, "mirror")!!.label(MonitorTool.orientation))
        assertEquals("180°", assist.with(MonitorTool.orientation, "180")!!.label(MonitorTool.orientation))
        assertEquals("Зеркало 180°", assist.with(MonitorTool.orientation, "mirror180")!!.label(MonitorTool.orientation))
        val on = assist.with(MonitorTool.vectorscope, "on")!!.with(MonitorTool.probe, "on")!!
        assertTrue(on.label(MonitorTool.vectorscope) == "Вкл" && on.label(MonitorTool.probe) == "Вкл")
    }

    @Test fun pixelsAndPictureTransformFollowTheTools() {
        assertTrue(!MonitorAssist().needsPixels && !MonitorAssist().transformsPicture && !MonitorAssist().isActive)
        // (инструмент, значение, нужны ли точки кадра, меняет ли картинку, нужен ли растр зебры/пикинга)
        class Case(val tool: MonitorTool, val value: String, val pixels: Boolean, val transforms: Boolean, val raster: Boolean)
        val cases = listOf(
            Case(MonitorTool.zebra, "100", true, false, true), Case(MonitorTool.falseColor, "on", true, false, true),
            Case(MonitorTool.peaking, "low", true, false, true),
            Case(MonitorTool.vectorscope, "on", true, false, false), Case(MonitorTool.probe, "on", true, false, false),
            Case(MonitorTool.lut, "auto", false, true, false), Case(MonitorTool.lut, "slog3", false, true, false),
            Case(MonitorTool.lut, "file", false, true, false),
            Case(MonitorTool.orientation, "mirror", false, true, false), Case(MonitorTool.orientation, "180", false, true, false),
            Case(MonitorTool.orientation, "mirror180", false, true, false),
            Case(MonitorTool.grid, "thirds", false, false, false), Case(MonitorTool.center, "on", false, false, false),
            Case(MonitorTool.frameMarker, "9:16", false, false, false), Case(MonitorTool.safeZone, "90", false, false, false),
            Case(MonitorTool.overlay, "50", false, false, false),
        )
        for (c in cases) {
            val assist = MonitorAssist().with(c.tool, c.value)!!
            val what = "${c.tool} ${c.value}"
            assertEquals(what, c.pixels, assist.needsPixels)
            assertEquals(what, c.transforms, assist.transformsPicture)
            assertEquals(what, c.raster, assist.needsRaster)
            // «Всё — выключить» доступна, пока включено хоть что-то
            assertTrue(what, assist.isActive)
        }
        // цвет пикинга без пикинга ничего не включает
        val color = MonitorAssist().with(MonitorTool.peakingColor, "blue")!!
        assertTrue(!color.needsPixels && !color.transformsPicture && !color.isActive)
        // настройки, которые сами ничего не включают
        val settings = MonitorAssist().copy(lutFile = "Тёплый", lutScopes = false, vectorscopeZoom = 2)
        assertTrue(!settings.needsPixels && !settings.transformsPicture && !settings.isActive)
    }

    @Test fun turnOffAllSwitchesTheNewToolsOffButKeepsTheirSettings() {
        var assist = MonitorAssist().with(MonitorTool.lut, "slog3")!!.copy(lutFile = "Тёплый", lutScopes = false)
            .with(MonitorTool.vectorscope, "on")!!.copy(vectorscopeZoom = 2)
            .with(MonitorTool.probe, "on")!!.with(MonitorTool.orientation, "mirror")!!.with(MonitorTool.zebra, "95")!!
        assertTrue(assist.isActive && assist.needsPixels && assist.transformsPicture)
        assist = assist.allOff()
        assertEquals(LutMode.off, assist.lut)
        assertFalse(assist.vectorscope || assist.probe)
        assertEquals(FrameOrientation.off, assist.orientation)
        assertEquals(Zebra.off, assist.zebra)
        assertTrue(!assist.isActive && !assist.needsPixels && !assist.transformsPicture)
        // помощь «по картинке с LUT», масштаб вектороскопа и выбранный файл — не включатели, остаются как были
        assertFalse(assist.lutScopes)
        assertEquals(2, assist.vectorscopeZoom)
        assertEquals("Тёплый", assist.lutFile)
        // запомненные значения тоже: повторное касание плиток возвращает прежнее
        assist = assist.toggled(MonitorTool.lut).toggled(MonitorTool.orientation)
        assertTrue(assist.lut == LutMode.slog3 && assist.orientation == FrameOrientation.mirror)
    }

    @Test fun settingsWithoutTheNewKeysReadAsDefaults() {
        // JSON, записанный версией до LUT, вектороскопа и картинки: только старые ключи
        val old = MonitorAssist.fromJson("""{"zebra":"95","falseColor":true,"overlay":"40","lastOn":{"zebra":"95"}}""")
        assertTrue(old.zebra == Zebra.above95 && old.falseColor && old.overlay.percent == 40)
        assertEquals(LutMode.off, old.lut)
        assertNull(old.lutFile)
        assertTrue(old.lutScopes)
        assertFalse(old.vectorscope)
        assertEquals(1, old.vectorscopeZoom)
        assertFalse(old.probe)
        assertEquals(FrameOrientation.off, old.orientation)
        assertFalse(old.transformsPicture)
        assertEquals(MonitorAssist(), MonitorAssist.fromJson("{}"))
        // в новой настройке без включённого — «по картинке с LUT», масштаб ×1, файла нет
        val fresh = MonitorAssist()
        assertTrue(fresh.lut == LutMode.off && fresh.lutFile == null && fresh.lutScopes && !fresh.vectorscope)
        assertTrue(fresh.vectorscopeZoom == 1 && !fresh.probe && fresh.orientation == FrameOrientation.off)
    }

    @Test fun settingsWithTheNewKeysSurviveJson() {
        val assist = MonitorAssist().with(MonitorTool.lut, "file")!!.copy(lutFile = "Тёплый закат", lutScopes = false)
            .with(MonitorTool.vectorscope, "on")!!.copy(vectorscopeZoom = 2)
            .with(MonitorTool.probe, "on")!!.with(MonitorTool.orientation, "mirror180")!!.with(MonitorTool.zebra, "70")!!
        val text = assist.toJson()
        val decoded = MonitorAssist.fromJson(text)
        assertEquals(assist, decoded)
        assertTrue(decoded.lut == LutMode.file && decoded.lutFile == "Тёплый закат" && !decoded.lutScopes)
        assertTrue(decoded.vectorscope && decoded.vectorscopeZoom == 2 && decoded.probe)
        assertEquals(FrameOrientation.mirror180, decoded.orientation)
        // запомненные значения новых плиток тоже доезжают
        assertTrue(decoded.lastOn["lut"] == "file" && decoded.lastOn["orientation"] == "mirror180")

        // как это лежит на диске и читается программой для Mac: выбор из нескольких — строкой, вкл/выкл — bool, масштаб — число
        val json = JSONObject(text)
        assertEquals("file", json.get("lut"))
        assertEquals("Тёплый закат", json.get("lutFile"))
        assertEquals(false, json.get("lutScopes"))
        assertEquals(true, json.get("vectorscope"))
        assertEquals(2, json.get("vectorscopeZoom"))
        assertEquals(true, json.get("probe"))
        assertEquals("mirror180", json.get("orientation"))
        assertEquals(false, json.get("falseColor"))
        // «180» лежит строкой, как и все значения плиток
        assertEquals("180", JSONObject(MonitorAssist().with(MonitorTool.orientation, "180")!!.toJson()).get("orientation"))
        // файла нет — ключа нет
        assertFalse(JSONObject(MonitorAssist().toJson()).has("lutFile"))
    }

    /** JSON как дерево значений: у JSONObject нет equals, а тип значения (bool, строка, число) важен. */
    private fun tree(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { key ->
        when (val value = json.get(key)) {
            is JSONObject -> tree(value)
            else -> value
        }
    }

    @Test fun settingsWrittenByTheMacAppAreReadAndOursLookTheSame() {
        // то, что программа для Mac пишет на диск (JSONEncoder) для этих же настроек, — слово в слово
        val mac = listOf(
            """{"center":true,"falseColor":true,"frameMarker":"9:16","grid":"thirds",""",
            """"lastOn":{"center":"on","falseColor":"on","frameMarker":"9:16","grid":"thirds","lut":"file",""",
            """"orientation":"180","overlay":"40","peaking":"mid","peakingColor":"blue","probe":"on","safeZone":"90",""",
            """"vectorscope":"on","zebra":"70"},""",
            """"lut":"file","lutFile":"Тёплый закат","lutScopes":false,"orientation":"180","overlay":"40","peaking":"mid",""",
            """"peakingColor":"blue","probe":true,"safeZone":"90","vectorscope":true,"vectorscopeZoom":2,"zebra":"70"}""",
        ).joinToString("")
        val built = MonitorAssist().with(MonitorTool.zebra, "70")!!.with(MonitorTool.falseColor, "on")!!
            .with(MonitorTool.peaking, "mid")!!.with(MonitorTool.peakingColor, "blue")!!.with(MonitorTool.lut, "file")!!
            .with(MonitorTool.vectorscope, "on")!!.with(MonitorTool.probe, "on")!!.with(MonitorTool.grid, "thirds")!!
            .with(MonitorTool.center, "on")!!.with(MonitorTool.frameMarker, "9:16")!!.with(MonitorTool.safeZone, "90")!!
            .with(MonitorTool.overlay, "40")!!.with(MonitorTool.orientation, "180")!!
            .copy(lutFile = "Тёплый закат", lutScopes = false, vectorscopeZoom = 2)
        assertEquals(built, MonitorAssist.fromJson(mac))
        assertEquals(tree(JSONObject(mac)), tree(JSONObject(built.toJson())))

        // настройка «по умолчанию»: те же шестнадцать ключей, файла нет
        val macDefault = listOf(
            """{"center":false,"falseColor":false,"frameMarker":"off","grid":"off","lastOn":{},"lut":"off","lutScopes":true,""",
            """"orientation":"off","overlay":"off","peaking":"off","peakingColor":"red","probe":false,"safeZone":"off",""",
            """"vectorscope":false,"vectorscopeZoom":1,"zebra":"off"}""",
        ).joinToString("")
        assertEquals(MonitorAssist(), MonitorAssist.fromJson(macDefault))
        assertEquals(tree(JSONObject(macDefault)), tree(JSONObject(MonitorAssist().toJson())))
    }

    @Test fun brokenNewKeysFallBackToDefaults() {
        // неизвестное значение, неверный тип и неверный масштаб — как «не записано»; остальное читается
        val broken = """{"zebra":"100","lut":"bogus","lutFile":5,"lutScopes":"no","vectorscope":"on","vectorscopeZoom":3,""" +
            """"probe":"yes","orientation":"sideways"}"""
        val assist = MonitorAssist.fromJson(broken)
        assertEquals(Zebra.above100, assist.zebra)
        assertEquals(LutMode.off, assist.lut)
        assertNull(assist.lutFile)
        assertTrue(assist.lutScopes)
        assertFalse(assist.vectorscope)
        assertEquals(1, assist.vectorscopeZoom)
        assertFalse(assist.probe)
        assertEquals(FrameOrientation.off, assist.orientation)
        // масштаб — только 1 и 2: число 2 в любой записи, всё остальное — 1
        val zooms = listOf("1" to 1, "2" to 2, "0" to 1, "-2" to 1, "4" to 1, "2.0" to 2, "2e0" to 2, "1.5" to 1, "2.5" to 1,
            "\"2\"" to 1, "true" to 1, "null" to 1)
        for ((text, zoom) in zooms) assertEquals(text, zoom, MonitorAssist.fromJson("""{"vectorscopeZoom":$text}""").vectorscopeZoom)
        // «null» вместо файла — файла нет
        val none = MonitorAssist.fromJson("""{"lut":"file","lutFile":null}""")
        assertTrue(none.lut == LutMode.file && none.lutFile == null)
    }

    @Test fun newKeysAreReadOnlyByTheirOwnJsonType() {
        // org.json охотно приводит типы: optString делает из числа строку, optBoolean читает «true» и «false». Как у программы
        // для Mac, новые ключи читаются только своим типом, иначе — как «не записано»
        fun read(key: String, raw: String) = MonitorAssist.fromJson("""{"$key":$raw}""")
        assertNull(read("lutFile", "5").lutFile)
        assertNull(read("lutFile", "true").lutFile)
        assertNull(read("lutFile", "null").lutFile)
        assertEquals("", read("lutFile", "\"\"").lutFile)
        assertEquals("Look 1", read("lutFile", "\"Look 1\"").lutFile)
        assertTrue(read("lutScopes", "\"false\"").lutScopes)
        assertTrue(read("lutScopes", "0").lutScopes)
        assertFalse(read("lutScopes", "false").lutScopes)
        assertFalse(read("vectorscope", "1").vectorscope)
        assertFalse(read("vectorscope", "\"true\"").vectorscope)
        assertTrue(read("vectorscope", "true").vectorscope)
        assertFalse(read("probe", "1").probe)
        assertFalse(read("probe", "\"on\"").probe)
        assertTrue(read("probe", "true").probe)
        // «180» — строка; число 180 не принимается
        assertEquals(FrameOrientation.off, read("orientation", "180").orientation)
        assertEquals(FrameOrientation.rotate180, read("orientation", "\"180\"").orientation)
        assertEquals(LutMode.off, read("lut", "true").lut)
        assertEquals(LutMode.off, read("lut", "1").lut)
        assertEquals(LutMode.hlg, read("lut", "\"hlg\"").lut)
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

    // волновая форма

    /** Кадр ARGB: слева половина одного цвета, справа — другого. */
    private fun halves(width: Int, height: Int, left: Int, right: Int) =
        IntArray(width * height) { if (it % width < width / 2) left else right }

    @Test fun waveformPutsEachColumnAtItsBrightness() {
        val w = Waveform.counts(halves(512, 10, 0xFF000000.toInt(), 0xFFC8C8C8.toInt()), 512, 10, Waveform.Mode.luma)
        assertEquals(Waveform.LUMA_COLUMNS, w.columns)
        assertEquals(20, w.samplesPerColumn)
        assertEquals(20, w.count(0, 0))
        assertEquals(20, w.count(255, 100))
        assertEquals(0, w.count(255, 0))
        assertEquals(5120, w.counts.sum())
        assertEquals(Pair(0, 201), w.levelRange)
    }

    @Test fun waveformParadeSplitsChannels() {
        val w = Waveform.counts(halves(256, 4, 0xFFFF0000.toInt(), 0xFF0000FF.toInt()), 256, 4, Waveform.Mode.parade)
        val p = Waveform.PARADE_COLUMNS
        assertEquals(p * 3, w.columns)
        assertEquals(8, w.count(0, 127))
        assertEquals(8, w.count(p - 1, 0))
        assertEquals(8, w.count(2 * p, 0))
        assertEquals(8, w.count(3 * p - 1, 127))
        assertTrue((p until 2 * p).all { w.count(it, 0) == 8 })
    }

    @Test fun waveformImageIsBrightWhereColumnsAreDense() {
        val w = Waveform.counts(halves(512, 10, 0xFF000000.toInt(), 0xFFC8C8C8.toInt()), 512, 10, Waveform.Mode.luma)
        val pixels = Waveform.image(w)
        assertEquals(w.columns * Waveform.ROWS, pixels.size)
        // строки картинки — сверху вниз: чёрное — в последней строке
        assertEquals(255, pixels[(Waveform.ROWS - 1) * w.columns] ushr 24)
        assertEquals(0, pixels[0] ushr 24)
        assertEquals(0.0, Waveform.intensity(0, 100), 1e-9)
        assertTrue(Waveform.intensity(1, 10_000) >= 0.18)
    }

    // свой файл LUT: выбрать и удалить (MonitorAssistTests.swift — choosingAFileSelectsTheOwnLUTMode и дальше)

    @Test fun choosingAFileSelectsTheOwnLutMode() {
        var assist = MonitorAssist().with(MonitorTool.lut, "slog3")!!
        assist = assist.lutFileSelected("Тёплый закат")
        assertEquals(LutMode.file, assist.lut)
        assertEquals("Тёплый закат", assist.lutFile)
        assertEquals("Тёплый закат", assist.label(MonitorTool.lut))
        // выключили и включили снова — вернулись к файлу, а не к «Авто»
        assist = assist.with(MonitorTool.lut, "off")!!.toggled(MonitorTool.lut)
        assertEquals(LutMode.file, assist.lut)
        assertEquals("Тёплый закат", assist.lutFile)
        // выбрали другой — прежний забыт, режим тот же
        assist = assist.lutFileSelected("Холод")
        assertEquals(LutMode.file, assist.lut)
        assertEquals("Холод", assist.lutFile)
    }

    @Test fun choosingAFileKeepsTheOtherSettings() {
        val before = MonitorAssist().with(MonitorTool.zebra, "95")!!.with(MonitorTool.vectorscope, "on")!!.copy(vectorscopeZoom = 2, lutScopes = false)
        val after = before.lutFileSelected("a")
        assertEquals(Zebra.above95, after.zebra)
        assertTrue(after.vectorscope)
        assertEquals(2, after.vectorscopeZoom)
        assertFalse(after.lutScopes)
        assertEquals("file", after.lastOn["lut"])
    }

    @Test fun deletingTheChosenFileClearsTheChoice() {
        var assist = MonitorAssist().lutFileSelected("a")
        // удалили чужой — выбор на месте
        assist = assist.lutFileDeleted("b")
        assertEquals(LutMode.file, assist.lut)
        assertEquals("a", assist.lutFile)
        // удалили выбранный — файла нет, LUT выключен, и «включить» теперь «Авто», а не «Свой» без файла
        assist = assist.lutFileDeleted("a")
        assertNull(assist.lutFile)
        assertEquals(LutMode.off, assist.lut)
        assist = assist.toggled(MonitorTool.lut)
        assertEquals(LutMode.auto, assist.lut)
    }

    @Test fun deletingTheRememberedFileKeepsAnotherLutOn() {
        // файл помнится, но на экране встроенный LUT: удаление забывает файл и не трогает то, что включено
        var assist = MonitorAssist().lutFileSelected("c").with(MonitorTool.lut, "slog3")!!
        assist = assist.lutFileDeleted("c")
        assertNull(assist.lutFile)
        assertEquals(LutMode.slog3, assist.lut)
        // выключили и включили — вернулся встроенный LUT, а не «Свой» без файла
        assist = assist.with(MonitorTool.lut, "off")!!.toggled(MonitorTool.lut)
        assertEquals(LutMode.slog3, assist.lut)
    }

    @Test fun deletingTheFileDoesNotTouchOtherSettings() {
        val assist = MonitorAssist().lutFileSelected("a").with(MonitorTool.orientation, "mirror")!!.copy(lutScopes = false, vectorscopeZoom = 2)
        val after = assist.lutFileDeleted("a")
        assertEquals(FrameOrientation.mirror, after.orientation)
        assertFalse(after.lutScopes)
        assertEquals(2, after.vectorscopeZoom)
        // нет такого — тот же объект
        assertEquals(assist, assist.lutFileDeleted("zzz"))
        assertEquals(MonitorAssist(), MonitorAssist().lutFileDeleted("a"))
    }

    @Test fun chosenFileSurvivesJson() {
        val assist = MonitorAssist().lutFileSelected("Тёплый закат").copy(lutScopes = false)
        assertEquals(assist, MonitorAssist.fromJson(assist.toJson()))
    }
}
