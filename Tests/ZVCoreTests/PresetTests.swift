import Foundation
import ImageIO
import Testing
@testable import ZVCore

@Test func captureTakesOnlyWritablePresetSettings() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let preset = PresetCatalog.capture(name: "Вечер", from: props)
    #expect(preset.name == "Вечер")
    #expect(preset.values[.aperture] == 280)
    #expect(preset.values[.iso] == 200)
    #expect(preset.values[.whiteBalance] == 2)
    #expect(preset.values[.exposureComp] == nil)      // в дампе только для показа
    #expect(preset.values[.colorTemperature] == nil)  // в дампе выключена
    #expect(preset.values[.exposureProgram] == nil)   // режим съёмки в пресеты не входит
}

@Test func applyWritesInCatalogOrderAndReportsSkipped() async throws {
    let cam = FakeCamera(props: [
        .init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200, 400]),
        .init(code: SonyProp.fNumber, type: PTPType.uint16, current: 280, values: [280, 320]),
        .init(code: SonyProp.shutter, type: PTPType.uint32, enabled: 2, current: 0x10028, values: []),
        .init(code: SonyProp.whiteBalance, type: PTPType.uint16, current: 2, values: [2, 4]),
    ])
    let s = try await readySession(cam)
    let preset = Preset(name: "P", values: [.aperture: 320, .iso: 400, .shutter: 0x10019, .whiteBalance: 2])
    let result = await s.apply(preset)
    #expect(result.applied == [.whiteBalance, .iso, .aperture])
    #expect(result.skipped == [.shutter])
    // баланс белого уже совпадает — его не пишем; остальное в порядке каталога
    let writes = cam.sent.filter { $0.op == 0x9205 && $0.params != [0xD25A] }.map { $0.params[0] }
    #expect(writes == [UInt32(SonyProp.iso), UInt32(SonyProp.fNumber)])
}

@Test func presetRoundTripsThroughJSON() throws {
    let preset = Preset(name: "Улица", values: [.iso: 100, .aperture: 560])
    let data = try JSONEncoder().encode(preset)
    #expect(try JSONDecoder().decode(Preset.self, from: data) == preset)
    #expect(String(decoding: data, as: UTF8.self).contains("\"aperture\""))
}

@Test func presetFromBeforeTheWindowHasNothingSkipped() throws {
    let old = #"{"name":"Старый","values":{"iso":400}}"#
    let preset = try JSONDecoder().decode(Preset.self, from: Data(old.utf8))
    #expect(preset.skip.isEmpty)
    #expect(preset.values[.iso] == 400)
}

@Test func skipRoundTripsThroughJSON() throws {
    let preset = Preset(name: "Цвет", values: [.iso: 400, .whiteBalance: 4], skip: [.iso])
    let data = try JSONEncoder().encode(preset)
    #expect(try JSONDecoder().decode(Preset.self, from: data) == preset)
    #expect(String(decoding: data, as: UTF8.self).contains(#""skip":["iso"]"#))
    // незнакомая настройка из будущей версии не губит пресет
    let future = #"{"name":"Новый","values":{"iso":400},"skip":["iso","somethingNew"]}"#
    #expect(try JSONDecoder().decode(Preset.self, from: Data(future.utf8)).skip == [.iso])
}

@Test func effectiveLeavesOutSkippedAndTemperatureWithoutManualWhiteBalance() {
    let daylight = Preset(name: "Д", values: [.whiteBalance: 4, .colorTemperature: 5600, .iso: 400, .shutter: 0x10032],
                          skip: [.shutter])
    #expect(PresetCatalog.effective(daylight) == [.whiteBalance: 4, .iso: 400])
    let manual = Preset(name: "К", values: [.whiteBalance: WhiteBalanceTemperature.manualMode, .colorTemperature: 3200])
    #expect(PresetCatalog.effective(manual)[.colorTemperature] == 3200)
    // баланс белого пресет не меняет — температура остаётся: применится, если камера сейчас в «Цвет. темп.»
    let keepWB = Preset(name: "Т", values: [.whiteBalance: 4, .colorTemperature: 3200], skip: [.whiteBalance])
    #expect(PresetCatalog.effective(keepWB) == [.colorTemperature: 3200])
}

@Test func applyLeavesSkippedSettingsAlone() async throws {
    let cam = FakeCamera(props: [
        .init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200, 400]),
        .init(code: SonyProp.fNumber, type: PTPType.uint16, current: 280, values: [280, 320]),
    ])
    let s = try await readySession(cam)
    let result = await s.apply(Preset(name: "P", values: [.iso: 400, .aperture: 320], skip: [.aperture]))
    #expect(result.applied == [.iso])
    #expect(result.skipped.isEmpty)
    let writes = cam.sent.filter { $0.op == 0x9205 && $0.params != [0xD25A] }.map { $0.params[0] }
    #expect(writes == [UInt32(SonyProp.iso)])
}

@Test func matchesWhenEverythingThePresetSetsIsOnTheCamera() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let preset = PresetCatalog.capture(name: "Сейчас", from: props)
    #expect(PresetCatalog.matches(preset, props))
    var other = preset
    other.values[.iso] = 6400
    #expect(!PresetCatalog.matches(other, props))
    // выключенная настройка не мешает
    other.skip = [.iso]
    #expect(PresetCatalog.matches(other, props))
    // пресет, который ничего не меняет, не «стоит» никогда
    #expect(!PresetCatalog.matches(Preset(name: "Пусто", values: [:]), props))
    #expect(!PresetCatalog.matches(Preset(name: "Всё выключено", values: [.iso: 200], skip: [.iso]), props))
}

@Test func recaptureTakesWhatTheCameraLetsChangeAndKeepsTheRest() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let old = Preset(name: "Вечер", values: [.iso: 6400, .colorTemperature: 3200, .aperture: 560], skip: [.aperture])
    let fresh = PresetCatalog.recapture(old, from: props)
    #expect(fresh.name == "Вечер")
    #expect(fresh.skip == [.aperture])
    #expect(fresh.values[.iso] == 200)
    #expect(fresh.values[.aperture] == 280)          // и выключенная — с камеры: пригодится, если её включат
    #expect(fresh.values[.colorTemperature] == 3200)  // её камера сейчас менять не даёт — осталась прежней
    #expect(fresh.values[.whiteBalance] == 2)         // в пресете её не было — теперь есть
}

@Test func summaryListsTheMainValues() {
    let evening = Preset(name: "Вечер", values: [
        .shutter: 0x10032, .aperture: 280, .iso: 800, .whiteBalance: WhiteBalanceTemperature.manualMode,
        .colorTemperature: 3200, .exposureComp: 0, .focusMode: 0x8004, .dro: 31,
    ])
    #expect(PresetCatalog.summary(evening) == "1/50 · f/2.8 · ISO 800 · 3200 K · AF-C")
    let auto = Preset(name: "Авто", values: [.iso: 0xFF_FFFF, .exposureComp: 700, .whiteBalance: 2])
    #expect(PresetCatalog.summary(auto) == "ISO AUTO · AWB · +0.7")
    // только температура, баланс белого не меняется
    #expect(PresetCatalog.summary(Preset(name: "К", values: [.whiteBalance: 4, .colorTemperature: 3200],
                                         skip: [.whiteBalance])) == "3200 K")
    // ничего из главного — какие настройки меняет
    #expect(PresetCatalog.summary(Preset(name: "Стиль", values: [.creativeStyle: 1, .metering: 1])) == "Замер · Стиль")
    #expect(PresetCatalog.summary(Preset(name: "Пусто", values: [.iso: 100], skip: [.iso])) == "ничего не меняет")
}

@Test func optionsAddThePresetValueAndFallBackForTemperature() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let preset = Preset(name: "P", values: [.iso: 123_456, .colorTemperature: 3200])
    let iso = PresetCatalog.options(.iso, props[SettingID.iso.code], preset)
    let camera = SettingOptions.options(.iso, try #require(props[SettingID.iso.code]))
    #expect(!camera.isEmpty)
    // значение пресета, которого нет у камеры, — в начале, остальное — как у камеры
    #expect(iso.first == SettingOption(value: 123_456, label: "123456"))
    #expect(Array(iso.dropFirst()) == camera)
    // температура в дампе выключена — запасной диапазон
    let kelvin = PresetCatalog.options(.colorTemperature, props[SettingID.colorTemperature.code], preset)
    #expect(kelvin.first?.label == "2500 K")
    #expect(kelvin.last?.label == "9900 K")
    #expect(kelvin.count == 75)
    // свойства нет — выбирать не из чего
    #expect(PresetCatalog.options(.shutter, nil, preset).isEmpty)
}

@Test func newNameTakesTheNextFreeNumber() {
    #expect(PresetCatalog.newName([]) == "Пресет 1")
    #expect(PresetCatalog.newName([Preset(name: "Вечер", values: [:])]) == "Пресет 2")
    // после удаления «Пресет 2» уже есть — имя не повторяется
    #expect(PresetCatalog.newName([Preset(name: "Пресет 2", values: [:])]) == "Пресет 3")
    #expect(PresetCatalog.newName([Preset(name: "Пресет 3", values: [:]), Preset(name: "Пресет 2", values: [:])]) == "Пресет 4")
}

@Test func unknownSettingInValuesDoesNotLoseThePreset() throws {
    let future = #"[{"name":"Новый","values":{"iso":400,"somethingNew":7}},{"name":"Второй","values":{"aperture":280}}]"#
    let presets = try JSONDecoder().decode([Preset].self, from: Data(future.utf8))
    #expect(presets.map(\.name) == ["Новый", "Второй"])
    #expect(presets[0].values == [.iso: 400])
}

@Test func previewIsASmallJPEGOfTheFrame() throws {
    let jpeg = try #require(LiveViewFrame.jpeg(from: Fixture.data("lv_frame_raw")))
    let preview = try #require(PresetPreview.thumbnail(jpeg: jpeg))
    let source = try #require(CGImageSourceCreateWithData(preview as CFData, nil))
    let props = try #require(CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any])
    #expect(props[kCGImagePropertyPixelWidth] as? Int == PresetPreview.width)
    #expect(props[kCGImagePropertyPixelHeight] as? Int == PresetPreview.width * 9 / 16)
    #expect(preview.count < 40_000)
    #expect(PresetPreview.thumbnail(jpeg: Data([1, 2, 3])) == nil)
}

@Test func previewRoundTripsThroughJSON() throws {
    let preset = Preset(name: "Кадр", values: [.iso: 400], preview: Data([0xFF, 0xD8, 0xFF]), previewOutdated: true)
    let data = try JSONEncoder().encode(preset)
    #expect(try JSONDecoder().decode(Preset.self, from: data) == preset)
    #expect(String(decoding: data, as: UTF8.self).contains(#""previewOutdated":true"#))
    // без кадра полей нет — запись та же, что у прежних версий
    let plain = String(decoding: try JSONEncoder().encode(Preset(name: "П", values: [.iso: 400])), as: UTF8.self)
    #expect(!plain.contains("preview"))
    let old = try JSONDecoder().decode(Preset.self, from: Data(#"{"name":"Старый","values":{"iso":400}}"#.utf8))
    #expect(old.preview == nil && !old.previewOutdated)
}
