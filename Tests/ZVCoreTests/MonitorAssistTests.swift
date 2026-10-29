import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

// MARK: - Настройки

@Test func monitorAssistStartsOffAndSurvivesCoding() throws {
    var assist = MonitorAssist()
    #expect(!assist.isActive)
    assist.set(.zebra, "95")
    assist.set(.frameMarker, "9:16")
    let decoded = try JSONDecoder().decode(MonitorAssist.self, from: JSONEncoder().encode(assist))
    #expect(decoded == assist)
    #expect(decoded.needsRaster)
    #expect(decoded.hasMarkers)
    // старые настройки без новых инструментов — инструменты выключены
    let partial = try JSONDecoder().decode(MonitorAssist.self, from: Data(#"{"zebra":"100"}"#.utf8))
    #expect(partial.zebra == .above100)
    #expect(partial.peaking == .off)
    #expect(partial.peakingColor == .red)
}

@Test func monitorAssistSetsByAPIValues() {
    var assist = MonitorAssist()
    // #expect не принимает изменяющие вызовы — результаты сначала в переменные
    let accepted = [assist.set(.peaking, "high"), assist.set(.falseColor, "on"), assist.set(.safeZone, "80")]
    let rejected = [assist.set(.zebra, "50"), assist.set(.center, "maybe")]
    #expect(accepted == [true, true, true])
    #expect(rejected == [false, false])
    #expect(assist.value(.peaking) == "high")
    #expect(assist.label(.peaking) == "Высокий")
    #expect(assist.label(.zebra) == "Выкл")
    #expect(assist.label(.safeZone) == "80 %")
    #expect(assist.falseColor)
    // цвет пикинга горит вместе с пикингом
    #expect(assist.isOn(.peakingColor))
    #expect(!MonitorAssist().isOn(.peakingColor))
    #expect(!assist.isOn(.zebra))
}

@Test func monitorAssistToggleRemembersLastValue() {
    var assist = MonitorAssist()
    // в первый раз — первое значение после «Выкл»
    assist.toggle(.zebra)
    #expect(assist.zebra == .above100)
    assist.set(.zebra, "70")
    assist.toggle(.zebra)
    #expect(assist.zebra == .off)
    assist.toggle(.zebra)
    #expect(assist.zebra == .skin70)
    // у цвета пикинга «выкл» нет — по кругу
    assist.toggle(.peakingColor)
    #expect(assist.peakingColor == .yellow)
    assist.set(.peakingColor, "blue")
    assist.toggle(.peakingColor)
    #expect(assist.peakingColor == .red)
}

@Test func monitorAssistOverlayIsAToolWithOpacity() {
    var assist = MonitorAssist()
    assist.set(.overlay, "50")
    #expect(assist.isActive)
    #expect(!assist.needsRaster && !assist.hasMarkers)
    #expect(assist.overlay.opacity == 0.5)
    #expect(assist.label(.overlay) == "50 %")
    assist.toggle(.overlay)
    #expect(assist.overlay == .off)
    assist.toggle(.overlay)
    #expect(assist.overlay.percent == 50)
}

@Test func overlayOpacityIsAnyPercentFrom5To100() throws {
    var assist = MonitorAssist()
    let took = assist.set(.overlay, "40")
    #expect(took)
    #expect(assist.overlay.opacity == 0.4)
    #expect(assist.label(.overlay) == "40 %")
    let zero = assist.set(.overlay, "0"), over = assist.set(.overlay, "105"), word = assist.set(.overlay, "half")
    #expect(!zero && !over && !word)
    #expect(assist.overlay.percent == 40)
    // ползунок — шагом 5 %, не меньше 5
    #expect(MonitorAssist.Overlay.steps(0.43).percent == 45)
    #expect(MonitorAssist.Overlay.steps(0).percent == 5)
    #expect(MonitorAssist.Overlay.steps(1).percent == 100)
    // повторное касание плитки возвращает 40 %, а не первую ступень
    assist.toggle(.overlay)
    #expect(assist.overlay == .off)
    assist.toggle(.overlay)
    #expect(assist.overlay.percent == 40)
    // стрелки в меню — к соседней ступени
    #expect(assist.stepped(.overlay, by: 1) == "50")
    #expect(assist.stepped(.overlay, by: -1) == "25")
    assist.set(.overlay, "10")
    #expect(assist.stepped(.overlay, by: -1) == "off")
    assist.set(.overlay, "100")
    #expect(assist.stepped(.overlay, by: 1) == "100")
    // старые настройки читаются как раньше, битые — выключено
    let old = try JSONDecoder().decode(MonitorAssist.self, from: Data(#"{"overlay":"25","zebra":"95"}"#.utf8))
    #expect(old.overlay.percent == 25 && old.zebra == .above95)
    let broken = try JSONDecoder().decode(MonitorAssist.self, from: Data(#"{"overlay":"7%"}"#.utf8))
    #expect(broken.overlay == .off)
    let saved = try JSONDecoder().decode(MonitorAssist.self, from: JSONEncoder().encode(assist))
    #expect(saved.overlay.percent == 100)
}

@Test func monitorAssistTurnsEverythingOffButKeepsMemory() {
    var assist = MonitorAssist()
    assist.set(.overlay, "75")
    assist.set(.peaking, "low")
    assist.set(.peakingColor, "white")
    assist.set(.grid, "thirds")
    assist.set(.center, "on")
    assist.turnOffAll()
    #expect(!assist.isActive)
    #expect(assist.peakingColor == .white)
    assist.toggle(.peaking)
    #expect(assist.peaking == .low)
}

// MARK: - LUT, вектороскоп, пипетка, картинка

@Test func monitorToolsKeepTheTileOrder() {
    // 13 плиток; четырнадцатая — «Всё — выключить» — рисуется отдельно. Ряд 1: до «Пипетки», ряд 2: от «Сетки»
    #expect(MonitorTool.allCases.map(\.rawValue) == [
        "zebra", "falseColor", "peaking", "peakingColor", "lut", "vectorscope", "probe",
        "grid", "center", "frameMarker", "safeZone", "overlay", "orientation",
    ])
}

@Test func newToolsHaveTheirTitlesOptionsAndHelp() {
    #expect(MonitorTool.lut.title == "LUT")
    #expect(MonitorTool.vectorscope.title == "Вектороскоп")
    #expect(MonitorTool.probe.title == "Пипетка")
    #expect(MonitorTool.orientation.title == "Картинка")

    #expect(MonitorTool.lut.options.map(\.value) == ["off", "auto", "slog3cine", "slog3", "slog2", "hlg", "file"])
    #expect(MonitorTool.lut.options.map(\.label) == ["Выкл", "Авто", "S-Log3 Cine", "S-Log3", "S-Log2", "HLG", "Свой"])
    for tool in [MonitorTool.vectorscope, .probe] {
        #expect(tool.options.map(\.value) == ["off", "on"])
        #expect(tool.options.map(\.label) == ["Выкл", "Вкл"])
    }
    #expect(MonitorTool.orientation.options.map(\.value) == ["off", "mirror", "180", "mirror180"])
    #expect(MonitorTool.orientation.options.map(\.label) == ["Как есть", "Зеркально", "Вверх ногами", "Зеркально + 180°"])

    // у каждой плитки есть справка, и у новых она говорит главное
    for tool in MonitorTool.allCases { #expect(!tool.help.isEmpty, "\(tool)") }
    // «Авто» — по профилю с заводскими гаммами Sony; перенастроенный профиль — вручную
    for pp in ["PP7", "S-Log2", "PP8", "PP9", "S-Log3", "PP10", "HLG"] { #expect(MonitorTool.lut.help.contains(pp), "\(pp)") }
    #expect(MonitorTool.probe.help.contains("Касание кадра ставит пипетку; пока она включена, фокус касанием не ставится."))
    #expect(MonitorTool.vectorscope.help.contains("×2"))
    #expect(MonitorTool.orientation.help.contains("Зеркально") && MonitorTool.orientation.help.contains("Вверх ногами"))
}

@Test func lutIsSetByItsAPIValues() {
    var assist = MonitorAssist()
    let took = assist.set(.lut, "auto")
    #expect(took)
    #expect(assist.lut == .auto)
    #expect(assist.value(.lut) == "auto")
    // все семь значений — в обе стороны
    for mode in MonitorAssist.LUTMode.allCases {
        let ok = assist.set(.lut, mode.rawValue)
        #expect(ok)
        #expect(assist.lut == mode)
        #expect(assist.value(.lut) == mode.rawValue)
    }
    assist.set(.lut, "slog2")
    // чужие значения не принимаются и ничего не меняют
    let bad = ["bogus", "S-Log2", "on", "", "AUTO"].map { assist.set(.lut, $0) }
    #expect(bad == [false, false, false, false, false])
    #expect(assist.lut == .slog2)
    #expect(MonitorAssist.LUTMode.allCases.map(\.rawValue) == ["off", "auto", "slog3cine", "slog3", "slog2", "hlg", "file"])
}

@Test func orientationIsSetByItsAPIValues() {
    var assist = MonitorAssist()
    let took = assist.set(.orientation, "180")
    #expect(took)
    #expect(assist.orientation == .rotate180)
    #expect(assist.value(.orientation) == "180")
    for o in FrameOrientation.allCases {
        let ok = assist.set(.orientation, o.rawValue)
        #expect(ok)
        #expect(assist.orientation == o)
        #expect(assist.value(.orientation) == o.rawValue)
    }
    assist.set(.orientation, "mirror")
    // в API — «180», а не имя варианта
    let bad = ["rotate180", "90", "flip", "on"].map { assist.set(.orientation, $0) }
    #expect(bad == [false, false, false, false])
    #expect(assist.orientation == .mirror)
}

@Test func vectorscopeAndProbeAreOnOffInTheAPI() {
    var assist = MonitorAssist()
    #expect(assist.value(.vectorscope) == "off" && assist.value(.probe) == "off")
    let on = [assist.set(.vectorscope, "on"), assist.set(.probe, "on")]
    #expect(on == [true, true])
    #expect(assist.vectorscope && assist.probe)
    #expect(assist.value(.vectorscope) == "on" && assist.value(.probe) == "on")
    let off = [assist.set(.vectorscope, "off"), assist.set(.probe, "off")]
    #expect(off == [true, true])
    #expect(!assist.vectorscope && !assist.probe)
    // как у false color: «true» и «1» тоже читаются
    let words = [assist.set(.vectorscope, "true"), assist.set(.probe, "1")]
    #expect(words == [true, true])
    #expect(assist.vectorscope && assist.probe)
    let bad = [assist.set(.vectorscope, "maybe"), assist.set(.probe, "2"), assist.set(.probe, "")]
    #expect(bad == [false, false, false])
    #expect(assist.vectorscope && assist.probe)
}

@Test func newToolsLightUpTheirTilesWhenOn() {
    var assist = MonitorAssist()
    for tool in [MonitorTool.lut, .vectorscope, .probe, .orientation] { #expect(!assist.isOn(tool), "\(tool)") }
    assist.set(.lut, "hlg")
    assist.set(.vectorscope, "on")
    assist.set(.probe, "on")
    assist.set(.orientation, "mirror180")
    for tool in [MonitorTool.lut, .vectorscope, .probe, .orientation] { #expect(assist.isOn(tool), "\(tool)") }
    // «Авто» тоже горит, даже когда профиль не опознан: гасит её сама плитка
    assist.set(.lut, "auto")
    #expect(assist.isOn(.lut))
}

@Test func lutToggleRemembersTheLastValue() {
    var assist = MonitorAssist()
    // в первый раз — «Авто», первое значение после «Выкл»
    assist.toggle(.lut)
    #expect(assist.lut == .auto)
    // после «Авто»: выкл, и снова «Авто»
    assist.set(.lut, "auto")
    assist.toggle(.lut)
    #expect(assist.lut == .off)
    assist.toggle(.lut)
    #expect(assist.lut == .auto)
    // запомнился последний выбор, а не первый
    assist.set(.lut, "slog2")
    assist.toggle(.lut)
    #expect(assist.lut == .off)
    assist.toggle(.lut)
    #expect(assist.lut == .slog2)
    assist.set(.lut, "file")
    assist.toggle(.lut)
    assist.toggle(.lut)
    #expect(assist.lut == .file)
}

@Test func otherNewToolsToggleLikeTheOldOnes() {
    var assist = MonitorAssist()
    assist.toggle(.vectorscope)
    assist.toggle(.probe)
    assist.toggle(.orientation)
    #expect(assist.vectorscope && assist.probe)
    #expect(assist.orientation == .mirror)
    assist.set(.orientation, "mirror180")
    assist.toggle(.orientation)
    #expect(assist.orientation == .off)
    assist.toggle(.orientation)
    #expect(assist.orientation == .mirror180)
    assist.toggle(.vectorscope)
    assist.toggle(.probe)
    #expect(!assist.vectorscope && !assist.probe)
    assist.toggle(.vectorscope)
    #expect(assist.vectorscope)
}

@Test func newToolsStepThroughTheirOptions() {
    var assist = MonitorAssist()
    #expect(assist.stepped(.lut, by: 1) == "auto")
    assist.set(.lut, "hlg")
    #expect(assist.stepped(.lut, by: 1) == "file")
    #expect(assist.stepped(.lut, by: 5) == "file")
    #expect(assist.stepped(.lut, by: -1) == "slog2")
    assist.set(.orientation, "mirror")
    #expect(assist.stepped(.orientation, by: -1) == "off")
    #expect(assist.stepped(.orientation, by: 1) == "180")
    #expect(assist.stepped(.vectorscope, by: 1) == "on")
    #expect(MonitorAssist.accepts(.lut, "slog3cine"))
    #expect(!MonitorAssist.accepts(.lut, "slog4"))
    #expect(MonitorAssist.accepts(.orientation, "180") && !MonitorAssist.accepts(.orientation, "rotate180"))
    #expect(MonitorAssist.accepts(.probe, "on") && !MonitorAssist.accepts(.probe, "true"))
}

@Test func lutTileLabelFollowsTheChoice() {
    var assist = MonitorAssist()
    #expect(assist.label(.lut) == "Выкл")
    assist.set(.lut, "auto")
    // подпись «Авто: S-Log3» решает LUTChoice по профилю камеры; здесь — «Авто»
    #expect(assist.label(.lut) == "Авто")
    assist.set(.lut, "slog3cine")
    #expect(assist.label(.lut) == "S-Log3 Cine")
    assist.set(.lut, "hlg")
    #expect(assist.label(.lut) == "HLG")
    // свой файл: на плитке его имя; файл ещё не выбран — «Свой»
    assist.set(.lut, "file")
    #expect(assist.label(.lut) == "Свой")
    assist.lutFile = "Тёплый закат"
    #expect(assist.label(.lut) == "Тёплый закат")
    assist.lutFile = ""
    #expect(assist.label(.lut) == "Свой")
    // файл помнится, но при другом выборе не показывается
    assist.lutFile = "Тёплый закат"
    assist.set(.lut, "slog2")
    #expect(assist.label(.lut) == "S-Log2")
    assist.set(.lut, "off")
    #expect(assist.label(.lut) == "Выкл")
}

@Test func pictureTileShowsTheShortLabel() {
    var assist = MonitorAssist()
    // на плитке — короткое значение, в полосе вариантов — длинное
    #expect(assist.label(.orientation) == "—")
    assist.set(.orientation, "mirror")
    #expect(assist.label(.orientation) == "Зеркало")
    assist.set(.orientation, "180")
    #expect(assist.label(.orientation) == "180°")
    assist.set(.orientation, "mirror180")
    #expect(assist.label(.orientation) == "Зеркало 180°")
    assist.set(.vectorscope, "on")
    assist.set(.probe, "on")
    #expect(assist.label(.vectorscope) == "Вкл" && assist.label(.probe) == "Вкл")
}

@Test func pixelsAndPictureTransformFollowTheTools() {
    #expect(!MonitorAssist().needsPixels && !MonitorAssist().transformsPicture && !MonitorAssist().isActive)
    // (инструмент, значение, нужны ли точки кадра, меняет ли картинку, нужен ли растр зебры/пикинга)
    let cases: [(MonitorTool, String, pixels: Bool, transforms: Bool, raster: Bool)] = [
        (.zebra, "100", true, false, true), (.falseColor, "on", true, false, true), (.peaking, "low", true, false, true),
        (.vectorscope, "on", true, false, false), (.probe, "on", true, false, false),
        (.lut, "auto", false, true, false), (.lut, "slog3", false, true, false), (.lut, "file", false, true, false),
        (.orientation, "mirror", false, true, false), (.orientation, "180", false, true, false),
        (.orientation, "mirror180", false, true, false),
        (.grid, "thirds", false, false, false), (.center, "on", false, false, false),
        (.frameMarker, "9:16", false, false, false), (.safeZone, "90", false, false, false),
        (.overlay, "50", false, false, false),
    ]
    for (tool, value, pixels, transforms, raster) in cases {
        var assist = MonitorAssist()
        assist.set(tool, value)
        #expect(assist.needsPixels == pixels, "\(tool) \(value)")
        #expect(assist.transformsPicture == transforms, "\(tool) \(value)")
        #expect(assist.needsRaster == raster, "\(tool) \(value)")
        // «Всё — выключить» доступна, пока включено хоть что-то
        #expect(assist.isActive, "\(tool) \(value)")
    }
    // цвет пикинга без пикинга ничего не включает
    var color = MonitorAssist()
    color.set(.peakingColor, "blue")
    #expect(!color.needsPixels && !color.transformsPicture && !color.isActive)
    // настройки, которые сами ничего не включают
    var settings = MonitorAssist()
    settings.lutFile = "Тёплый"
    settings.lutScopes = false
    settings.vectorscopeZoom = 2
    #expect(!settings.needsPixels && !settings.transformsPicture && !settings.isActive)
}

@Test func turnOffAllSwitchesTheNewToolsOffButKeepsTheirSettings() {
    var assist = MonitorAssist()
    assist.set(.lut, "slog3")
    assist.lutFile = "Тёплый"
    assist.lutScopes = false
    assist.set(.vectorscope, "on")
    assist.vectorscopeZoom = 2
    assist.set(.probe, "on")
    assist.set(.orientation, "mirror")
    assist.set(.zebra, "95")
    #expect(assist.isActive && assist.needsPixels && assist.transformsPicture)
    assist.turnOffAll()
    #expect(assist.lut == .off)
    #expect(!assist.vectorscope && !assist.probe)
    #expect(assist.orientation == .off)
    #expect(assist.zebra == .off)
    #expect(!assist.isActive && !assist.needsPixels && !assist.transformsPicture)
    // помощь «по картинке с LUT», масштаб вектороскопа и выбранный файл — не включатели, остаются как были
    #expect(!assist.lutScopes)
    #expect(assist.vectorscopeZoom == 2)
    #expect(assist.lutFile == "Тёплый")
    // запомненные значения тоже: повторное касание плиток возвращает прежнее
    assist.toggle(.lut)
    assist.toggle(.orientation)
    #expect(assist.lut == .slog3 && assist.orientation == .mirror)
}

@Test func settingsWithoutTheNewKeysReadAsDefaults() throws {
    // JSON, записанный версией до LUT, вектороскопа и картинки: только старые ключи
    let old = try JSONDecoder().decode(
        MonitorAssist.self, from: Data(#"{"zebra":"95","falseColor":true,"overlay":"40","lastOn":{"zebra":"95"}}"#.utf8))
    #expect(old.zebra == .above95 && old.falseColor && old.overlay.percent == 40)
    #expect(old.lut == .off)
    #expect(old.lutFile == nil)
    #expect(old.lutScopes)
    #expect(!old.vectorscope)
    #expect(old.vectorscopeZoom == 1)
    #expect(!old.probe)
    #expect(old.orientation == .off)
    #expect(old.transformsPicture == false)
    #expect(try JSONDecoder().decode(MonitorAssist.self, from: Data("{}".utf8)) == MonitorAssist())
    // в новой настройке без включённого — «по картинке с LUT», масштаб ×1, файла нет
    let fresh = MonitorAssist()
    #expect(fresh.lut == .off && fresh.lutFile == nil && fresh.lutScopes && !fresh.vectorscope)
    #expect(fresh.vectorscopeZoom == 1 && !fresh.probe && fresh.orientation == .off)
}

@Test func settingsWithTheNewKeysSurviveCoding() throws {
    var assist = MonitorAssist()
    assist.set(.lut, "file")
    assist.lutFile = "Тёплый закат"
    assist.lutScopes = false
    assist.set(.vectorscope, "on")
    assist.vectorscopeZoom = 2
    assist.set(.probe, "on")
    assist.set(.orientation, "mirror180")
    assist.set(.zebra, "70")
    let data = try JSONEncoder().encode(assist)
    let decoded = try JSONDecoder().decode(MonitorAssist.self, from: data)
    #expect(decoded == assist)
    #expect(decoded.lut == .file && decoded.lutFile == "Тёплый закат" && !decoded.lutScopes)
    #expect(decoded.vectorscope && decoded.vectorscopeZoom == 2 && decoded.probe)
    #expect(decoded.orientation == .mirror180)
    // запомненные значения новых плиток тоже доезжают
    #expect(decoded.lastOn["lut"] == "file" && decoded.lastOn["orientation"] == "mirror180")

    // как это лежит на диске и читается Android: выбор из нескольких — строкой, вкл/выкл — bool, масштаб — число
    let json = try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    #expect(json["lut"] as? String == "file")
    #expect(json["lutFile"] as? String == "Тёплый закат")
    #expect(json["lutScopes"] as? Bool == false)
    #expect(json["vectorscope"] as? Bool == true)
    #expect(json["vectorscopeZoom"] as? Int == 2)
    #expect(json["probe"] as? Bool == true)
    #expect(json["orientation"] as? String == "mirror180")
    #expect(json["falseColor"] as? Bool == false)
    // «180» лежит строкой, как и все значения плиток
    var turned = MonitorAssist()
    turned.set(.orientation, "180")
    let turnedJSON = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(turned)) as? [String: Any])
    #expect(turnedJSON["orientation"] as? String == "180")
    // файла нет — ключа нет
    let plain = try #require(JSONSerialization.jsonObject(with: JSONEncoder().encode(MonitorAssist())) as? [String: Any])
    #expect(plain["lutFile"] == nil)
}

@Test func settingsWrittenByAnotherPlatformAreRead() throws {
    // JSON так, как его пишет Android: ключи и значения те же
    let wire = #"{"zebra":"off","lut":"slog3cine","lutFile":"Look 1","lutScopes":false,"vectorscope":true,"#
        + #""vectorscopeZoom":2,"probe":true,"orientation":"180","lastOn":{"lut":"slog3cine"}}"#
    let assist = try JSONDecoder().decode(MonitorAssist.self, from: Data(wire.utf8))
    #expect(assist.lut == .slog3cine)
    #expect(assist.lutFile == "Look 1")
    #expect(!assist.lutScopes)
    #expect(assist.vectorscope)
    #expect(assist.vectorscopeZoom == 2)
    #expect(assist.probe)
    #expect(assist.orientation == .rotate180)
    #expect(assist.needsPixels && assist.transformsPicture)
}

@Test func brokenNewKeysFallBackToDefaults() throws {
    // неизвестное значение, неверный тип и неверный масштаб — как «не записано»; остальное читается
    let broken = #"{"zebra":"100","lut":"bogus","lutFile":5,"lutScopes":"no","vectorscope":"on","vectorscopeZoom":3,"#
        + #""probe":"yes","orientation":"sideways"}"#
    let assist = try JSONDecoder().decode(MonitorAssist.self, from: Data(broken.utf8))
    #expect(assist.zebra == .above100)
    #expect(assist.lut == .off)
    #expect(assist.lutFile == nil)
    #expect(assist.lutScopes)
    #expect(!assist.vectorscope)
    #expect(assist.vectorscopeZoom == 1)
    #expect(!assist.probe)
    #expect(assist.orientation == .off)
    // масштаб — только 1 и 2
    for (text, zoom) in [("1", 1), ("2", 2), ("0", 1), ("-2", 1), ("4", 1)] {
        let decoded = try JSONDecoder().decode(MonitorAssist.self, from: Data(#"{"vectorscopeZoom":\#(text)}"#.utf8))
        #expect(decoded.vectorscopeZoom == zoom, "\(text)")
    }
    // «null» вместо файла — файла нет
    let none = try JSONDecoder().decode(MonitorAssist.self, from: Data(#"{"lut":"file","lutFile":null}"#.utf8))
    #expect(none.lut == .file && none.lutFile == nil)
}

// MARK: - Зебра и false color

@Test func zebraThresholdsFollowTheWhiteLevel() {
    // видео: белое — 234
    #expect(AssistRaster.isZebra(233, .above100, white: 234))
    #expect(!AssistRaster.isZebra(232, .above100, white: 234))
    #expect(AssistRaster.isZebra(223, .above95, white: 234))
    #expect(!AssistRaster.isZebra(222, .above95, white: 234))
    // кожа — 70 ± 5 %
    #expect(!AssistRaster.isZebra(152, .skin70, white: 234))
    #expect(AssistRaster.isZebra(153, .skin70, white: 234))
    #expect(AssistRaster.isZebra(175, .skin70, white: 234))
    #expect(!AssistRaster.isZebra(176, .skin70, white: 234))
    // фото: белое — 255
    #expect(AssistRaster.isZebra(254, .above100, white: 255))
    #expect(!AssistRaster.isZebra(240, .above100, white: 255))
    #expect(!AssistRaster.isZebra(255, .off, white: 255))
}

@Test func falseColorBandsCoverTheScale() {
    #expect(AssistRaster.falseColorBand(0, white: 234) == .crushed)
    #expect(AssistRaster.falseColorBand(10, white: 234) == .shadows)
    // 18 % серого — около 42 % шкалы, кожа — на ступень светлее
    #expect(AssistRaster.falseColorBand(100, white: 234) == .middleGray)
    #expect(AssistRaster.falseColorBand(130, white: 234) == .skin)
    #expect(AssistRaster.falseColorBand(70, white: 234) == nil)
    #expect(AssistRaster.falseColorBand(225, white: 234) == .nearWhite)
    // между «почти белым» и пересветом щели нет: 253 из 255 — ещё «почти белое»
    #expect(AssistRaster.falseColorBand(253, white: 255) == .nearWhite)
    #expect(AssistRaster.falseColorBand(232, white: 234) == .nearWhite)
    // пересвет — как у гистограммы: белый и на единицу ниже
    #expect(AssistRaster.falseColorBand(233, white: 234) == .clipped)
    #expect(AssistRaster.falseColorBand(255, white: 234) == .clipped)
}

/// Кадр: слева — уровень left, справа — right, граница после столбца edge.
private func step(width: Int, height: Int, edge: Int, left: UInt8, right: UInt8) -> [UInt8] {
    var luma = [UInt8](repeating: left, count: width * height)
    for y in 0..<height { for x in (edge + 1)..<width { luma[y * width + x] = right } }
    return luma
}

@Test func renderPaintsZebraStripesOnlyWhereBright() throws {
    let luma = step(width: 16, height: 8, edge: 7, left: 100, right: 234)
    var assist = MonitorAssist()
    assist.set(.zebra, "100")
    let out = try #require(AssistRaster.render(luma: luma, width: 16, height: 8, scale: 1, white: 234, assist: assist))
    #expect(out.zebraShare == 50)
    #expect(out.peakingShare == 0)
    func alpha(_ x: Int, _ y: Int) -> UInt8 { out.pixels[(y * 16 + x) * 4 + 3] }
    // слева прозрачно; справа — полосы наискось: чёрные на 60 % и прозрачные
    #expect((0..<8).allSatisfy { x in (0..<8).allSatisfy { alpha(x, $0) == 0 } })
    #expect(alpha(8, 0) == 153)
    #expect(alpha(12, 0) == 0)
    #expect(alpha(11, 1) == 0)
    #expect(alpha(10, 1) == 153)
}

@Test func renderFalseColorIsOpaqueEverywhere() throws {
    let luma = step(width: 8, height: 4, edge: 3, left: 0, right: 234)
    var assist = MonitorAssist()
    assist.set(.falseColor, "on")
    let out = try #require(AssistRaster.render(luma: luma, width: 8, height: 4, scale: 1, white: 234, assist: assist))
    #expect(stride(from: 3, to: out.pixels.count, by: 4).allSatisfy { out.pixels[$0] == 255 })
    // провал — фиолетовый, пересвет — красный
    #expect(out.pixels[0] == 150 && out.pixels[2] == 200)
    #expect(out.pixels[7 * 4] == 255 && out.pixels[7 * 4 + 1] == 30)
    #expect(AssistRaster.render(luma: luma, width: 8, height: 4, scale: 1, white: 234, assist: MonitorAssist()) == nil)
}

// MARK: - Пикинг

@Test func peakingMarksASharpEdgeAsAThinLine() {
    let luma = step(width: 20, height: 10, edge: 9, left: 40, right: 200)
    let mask = AssistRaster.peakingMask(luma, width: 20, height: 10, threshold: 30, scale: 1)
    for y in 1..<8 {
        let row = Array(mask[(y * 20)..<(y * 20 + 20)])
        #expect(row.enumerated().filter { $0.element == 1 }.map(\.offset) == [9])
    }
}

@Test func peakingIgnoresSoftGradientsAndFlatAreas() {
    // мягкий переход: по 10 уровней на точку — вне фокуса
    var luma = [UInt8](repeating: 0, count: 30 * 6)
    for y in 0..<6 { for x in 0..<30 { luma[y * 30 + x] = UInt8(min(250, x * 10)) } }
    #expect(AssistRaster.peakingMask(luma, width: 30, height: 6, threshold: 20, scale: 1).allSatisfy { $0 == 0 })
    let flat = [UInt8](repeating: 128, count: 100)
    #expect(AssistRaster.peakingMask(flat, width: 10, height: 10, threshold: 20, scale: 1).allSatisfy { $0 == 0 })
}

@Test func peakingStaysThinWhenTheViewIsZoomed() {
    // тот же резкий край, увеличенный вчетверо: линия в одну точку, а не полоса в 4
    let width = 40, height = 20
    let luma = step(width: width, height: height, edge: 19, left: 40, right: 200)
    let plan = AssistRaster.Plan(region: .init(x: 10, y: 5, width: 20, height: 10), width: 80, height: 40)
    let big = AssistRaster.resample(luma, width: width, height: height, plan: plan)
    let mask = AssistRaster.peakingMask(big, width: 80, height: 40, threshold: 60, scale: plan.scale)
    for y in 2..<38 {
        let hits = (0..<80).filter { mask[y * 80 + $0] == 1 }
        #expect(hits.count == 1)
        // граница кадра между точками 19 и 20 — в растре между 39 и 40
        #expect(hits.allSatisfy { (38...40).contains($0) })
    }
    // без поправки порога на увеличение край в растре не прошёл бы
    #expect(AssistRaster.peakingMask(big, width: 80, height: 40, threshold: 60, scale: 1).allSatisfy { $0 == 0 })
    #expect(AssistRaster.peakingMask(luma, width: width, height: height, threshold: 60, scale: 1).contains(1))
}

// MARK: - Часть кадра

@Test func resampleCopiesWhenNotZoomed() {
    let luma = (0..<(6 * 4)).map { UInt8($0) }
    let full = AssistRaster.plan(frameWidth: 6, frameHeight: 4, visible: (0, 0, 1, 1), factor: 1)
    #expect(full.region == .init(x: 0, y: 0, width: 6, height: 4))
    #expect(full.width == 6 && full.height == 4)
    #expect(AssistRaster.resample(luma, width: 6, height: 4, plan: full) == luma)
    let crop = AssistRaster.Plan(region: .init(x: 2, y: 1, width: 3, height: 2), width: 3, height: 2)
    #expect(AssistRaster.resample(luma, width: 6, height: 4, plan: crop) == [8, 9, 10, 14, 15, 16])
}

@Test func resampleKeepsFlatAreasFlat() {
    let luma = [UInt8](repeating: 77, count: 32 * 18)
    let plan = AssistRaster.Plan(region: .init(x: 3.3, y: 2.7, width: 10.5, height: 6), width: 63, height: 36)
    #expect(AssistRaster.resample(luma, width: 32, height: 18, plan: plan).allSatisfy { $0 == 77 })
}

@Test func planScalesTheVisiblePartButNotBeyondTheFrame() {
    // 4× по центру: видна четверть по каждой стороне, с запасом 10 %
    let plan = AssistRaster.plan(frameWidth: 1024, frameHeight: 576, visible: (0.375, 0.375, 0.25, 0.25), factor: 4)
    #expect(abs(plan.region.x - 0.35 * 1024) < 0.001)
    #expect(abs(plan.region.width - 0.3 * 1024) < 0.001)
    #expect(Double(plan.width * plan.height) <= 1024 * 576 * AssistRaster.maxPixelsRatio + 2048)
    #expect(plan.scale > 3.5 && plan.scale <= 4)
    // 8×: видимая часть с запасом в 8 раз крупнее не помещается в предел — чуть меньше
    let eight = AssistRaster.plan(frameWidth: 1024, frameHeight: 576, visible: (0.4375, 0.4375, 0.125, 0.125), factor: 8)
    #expect(eight.scale > 7 && eight.scale <= 8)
    #expect(Double(eight.width * eight.height) <= 1024 * 576 * AssistRaster.maxPixelsRatio + 2048)
}

@Test func lumaMatchesTheHistogramFormula() {
    let rgba: [UInt8] = [255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 200, 200, 200, 255]
    #expect(AssistRaster.luma(rgba: rgba, width: 4, height: 1, bytesPerRow: 16) == [54, 182, 18, 200])
}

// MARK: - Маркеры

@Test func frameMarkersFitInsideTheFrame() throws {
    let frame = CGSize(width: 1024, height: 576)
    let vertical = try #require(FrameMarkers.markerRect(aspect: 9.0 / 16, frame: frame))
    #expect(abs(vertical.width - (9.0 / 16) / (16.0 / 9)) < 0.0001)
    #expect(vertical.height == 1)
    #expect(abs(vertical.midX - 0.5) < 0.0001)
    let scope = try #require(FrameMarkers.markerRect(aspect: 2.39, frame: frame))
    #expect(scope.width == 1)
    #expect(abs(scope.height - (16.0 / 9) / 2.39) < 0.0001)
    // кадр уже этого формата — рамки нет
    #expect(FrameMarkers.markerRect(aspect: 16.0 / 9, frame: frame) == nil)
    // квадратный кадр фоторежима: 9:16 — по высоте
    let square = try #require(FrameMarkers.markerRect(aspect: 9.0 / 16, frame: CGSize(width: 1024, height: 1024)))
    #expect(abs(square.width - 0.5625) < 0.0001)
}

@Test func safeZoneAndGridFollowTheMarker() throws {
    var assist = MonitorAssist()
    assist.set(.frameMarker, "1:1")
    assist.set(.safeZone, "90")
    assist.set(.grid, "thirds")
    assist.set(.center, "on")
    let layout = FrameMarkers.layout(assist, frame: CGSize(width: 1600, height: 900))
    let marker = try #require(layout.marker)
    let safe = try #require(layout.safe)
    #expect(abs(safe.width - marker.width * 0.9) < 0.0001)
    #expect(abs(safe.midX - 0.5) < 0.0001)
    #expect(layout.grid.count == 4)
    // трети — внутри рамки
    #expect(layout.grid.allSatisfy { $0.from.x >= marker.minX - 0.0001 && $0.to.x <= marker.maxX + 0.0001 })
    #expect(layout.center == CGPoint(x: 0.5, y: 0.5))
    // без рамки безопасная зона — от всего кадра
    assist.set(.frameMarker, "off")
    let plain = FrameMarkers.layout(assist, frame: CGSize(width: 1600, height: 900))
    let safe90 = try #require(plain.safe)
    #expect(abs(safe90.minX - 0.05) < 0.0001 && abs(safe90.minY - 0.05) < 0.0001 && abs(safe90.width - 0.9) < 0.0001)
}

@Test func squareGridHasSquareCells() {
    let frame = CGSize(width: 1600, height: 900)
    let lines = FrameMarkers.grid(.squares, in: CGRect(x: 0, y: 0, width: 1, height: 1), frame: frame)
    let verticals = lines.filter { $0.from.x == $0.to.x }.map(\.from.x).sorted()
    let horizontals = lines.filter { $0.from.y == $0.to.y }.map(\.from.y).sorted()
    #expect(horizontals.count == 3)
    // шаг по x в точках равен шагу по y в точках
    let stepX = (verticals[1] - verticals[0]) * frame.width
    let stepY = (horizontals[1] - horizontals[0]) * frame.height
    #expect(abs(stepX - stepY) < 0.01)
    #expect(verticals.contains(0.5))
}

// MARK: - Волновая форма

/// Кадр RGBA: слева половина одного цвета, справа — другого.
private func halves(width: Int, height: Int, left: (UInt8, UInt8, UInt8), right: (UInt8, UInt8, UInt8)) -> [UInt8] {
    var rgba = [UInt8](repeating: 255, count: width * height * 4)
    for y in 0..<height {
        for x in 0..<width {
            let (r, g, b) = x < width / 2 ? left : right
            let i = (y * width + x) * 4
            rgba[i] = r; rgba[i + 1] = g; rgba[i + 2] = b
        }
    }
    return rgba
}

@Test func waveformPutsEachColumnAtItsBrightness() {
    let rgba = halves(width: 512, height: 10, left: (0, 0, 0), right: (200, 200, 200))
    let w = Waveform.counts(rgba: rgba, width: 512, height: 10, bytesPerRow: 512 * 4, mode: .luma)
    #expect(w.columns == Waveform.lumaColumns)
    #expect(w.samplesPerColumn == 20)
    // левая половина — внизу (чёрное), правая — на уровне 200 (строка 100)
    #expect(w.count(column: 0, row: 0) == 20)
    #expect(w.count(column: 255, row: 100) == 20)
    #expect(w.count(column: 255, row: 0) == 0)
    #expect(w.counts.reduce(0, +) == 5120)
    #expect(w.levelRange?.low == 0)
    #expect(w.levelRange?.high == 201)
}

@Test func waveformParadeSplitsChannels() {
    let rgba = halves(width: 256, height: 4, left: (255, 0, 0), right: (0, 0, 255))
    let w = Waveform.counts(rgba: rgba, width: 256, height: 4, bytesPerRow: 256 * 4, mode: .parade)
    #expect(w.columns == Waveform.paradeColumns * 3)
    let p = Waveform.paradeColumns
    // красный: слева сверху, справа внизу; синий — наоборот; зелёный — весь внизу
    #expect(w.count(column: 0, row: 127) == 8)
    #expect(w.count(column: p - 1, row: 0) == 8)
    #expect(w.count(column: 2 * p, row: 0) == 8)
    #expect(w.count(column: 3 * p - 1, row: 127) == 8)
    #expect((p..<(2 * p)).allSatisfy { w.count(column: $0, row: 0) == 8 })
}

@Test func waveformImageIsBrightWhereColumnsAreDense() {
    let rgba = halves(width: 512, height: 10, left: (0, 0, 0), right: (200, 200, 200))
    let w = Waveform.counts(rgba: rgba, width: 512, height: 10, bytesPerRow: 512 * 4, mode: .luma)
    let pixels = Waveform.image(w)
    #expect(pixels.count == w.columns * Waveform.rows * 4)
    // строки картинки — сверху вниз: чёрное — в последней строке
    let bottom = ((Waveform.rows - 1) * w.columns + 0) * 4
    #expect(pixels[bottom + 3] == 255)
    #expect(pixels[3] == 0)
    #expect(Waveform.intensity(0, samplesPerColumn: 100) == 0)
    #expect(Waveform.intensity(1, samplesPerColumn: 10_000) >= 0.18)
}
