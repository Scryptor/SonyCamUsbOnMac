import Foundation
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
    let preset = Preset(name: "Улица", mode: .video, values: [.iso: 100, .aperture: 560])
    let data = try JSONEncoder().encode(preset)
    #expect(try JSONDecoder().decode(Preset.self, from: data) == preset)
    #expect(String(decoding: data, as: UTF8.self).contains("\"aperture\""))
    #expect(String(decoding: data, as: UTF8.self).contains(#""mode":"video""#))
}

@Test func presetFromBeforeModesGoesIntoBothLists() throws {
    let old = #"[{"name":"А","values":{"iso":400}},{"name":"Б","values":{"iso":800},"skip":["iso"]}]"#
    let presets = PresetLibrary.decode(Data(old.utf8))
    #expect(presets.map(\.name) == ["А", "Б", "А", "Б"])
    #expect(presets.map(\.mode) == [.photo, .photo, .video, .video])
    #expect(presets[3].skip == [.iso])
    #expect(presets[3].values[.iso] == 800)
}

@Test func presetOfUnknownModeIsSkippedAndTheRestRead() {
    let future = #"[{"name":"Таймлапс","mode":"timelapse","values":{"iso":100}},{"name":"Видео","mode":"video","values":{"iso":800}}]"#
    let presets = PresetLibrary.decode(Data(future.utf8))
    #expect(presets == [Preset(name: "Видео", mode: .video, values: [.iso: 800])])
    #expect(PresetLibrary.decode(Data("мусор".utf8)).isEmpty)
}

@Test func libraryRoundTripsThroughJSON() throws {
    let presets = [Preset(name: "Улица", values: [.iso: 100]), Preset(name: "Вечер", mode: .video, values: [.iso: 800])]
    #expect(PresetLibrary.decode(try JSONEncoder().encode(presets)) == presets)
}

@Test func movingAPresetKeepsTheOtherModeInPlace() {
    let all = [Preset(name: "Ф1", values: [:]), Preset(name: "В1", mode: .video, values: [:]),
               Preset(name: "Ф2", values: [:]), Preset(name: "В2", mode: .video, values: [:]),
               Preset(name: "Ф3", values: [:])]
    #expect(PresetLibrary.indices(of: .video, in: all) == [1, 3])
    let moved = PresetLibrary.moved(all, mode: .photo, from: 0, to: 2)
    #expect(moved?.map(\.name) == ["Ф2", "В1", "Ф3", "В2", "Ф1"])
    #expect(PresetLibrary.moved(all, mode: .video, from: 1, to: 0)?.map(\.name) == ["Ф1", "В2", "Ф2", "В1", "Ф3"])
    #expect(PresetLibrary.moved(all, mode: .video, from: 0, to: 2) == nil)
}

@Test func listTitleAndHintNameTheMode() {
    #expect(PresetLibrary.title(.photo) == "Пресеты для фото")
    #expect(PresetLibrary.title(.video) == "Пресеты для видео и S&Q")
    #expect(PresetLibrary.emptyHint(.video).hasPrefix("Для видео и S&Q пресетов пока нет."))
}

private func cameraProperty(_ id: SettingID, _ value: Int64, writable: Bool = true) -> (UInt16, SonyProperty) {
    (id.code, SonyProperty(code: id.code, dataType: PTPType.uint32, getSet: 1, enabled: writable ? 1 : 2,
                           current: .int(value), range: nil, settableValues: [], allValues: []))
}

@Test func changesListWhatTheCameraLetsChangeInSummaryOrder() {
    let evening = Preset(name: "Вечер", mode: .video, values: [
        .whiteBalance: WhiteBalanceTemperature.manualMode, .colorTemperature: 3200, .iso: 800, .shutter: 0x10032,
        .aperture: 280, .focusMode: 2, .exposureComp: 0, .metering: 0x8001,
    ], skip: [.metering])
    let camera = Dictionary(uniqueKeysWithValues: [
        cameraProperty(.whiteBalance, WhiteBalanceTemperature.manualMode), cameraProperty(.colorTemperature, 5600),
        cameraProperty(.iso, 1600), cameraProperty(.shutter, 0x10064), cameraProperty(.aperture, 400, writable: false),
        cameraProperty(.focusMode, 0x8004), cameraProperty(.exposureComp, 700), cameraProperty(.metering, 0x8002),
    ])
    let changes = PresetCatalog.changes(evening, camera)
    #expect(changes.map(\.label) == ["1/50 → 1/100", "ISO 800 → 1600", "3200 K → 5600 K", "Экспокорр. ±0.0 → +0.7",
                                     "Фокус AF-S → AF-C"])
    #expect(changes.first == PresetChange(id: .shutter, preset: 0x10032, camera: 0x10064, label: "1/50 → 1/100"))
    // диафрагму камера сейчас менять не даёт, замер пресет не меняет — это не изменения
    #expect(!changes.contains { $0.id == .aperture || $0.id == .metering })
    // всё как в пресете — изменений нет
    #expect(PresetCatalog.changes(PresetCatalog.recapture(evening, from: camera), camera).isEmpty)
}

@Test func whiteBalanceChangeShowsTheTemperatureWhereItIsUsed() {
    let warm = Preset(name: "Тепло", values: [.whiteBalance: WhiteBalanceTemperature.manualMode, .colorTemperature: 3200])
    let auto = Dictionary(uniqueKeysWithValues: [cameraProperty(.whiteBalance, 2),
                                                 cameraProperty(.colorTemperature, 5600, writable: false)])
    #expect(PresetCatalog.changes(warm, auto).map(\.label) == ["3200 K → AWB"])
    let manual = Dictionary(uniqueKeysWithValues: [cameraProperty(.whiteBalance, WhiteBalanceTemperature.manualMode),
                                                   cameraProperty(.colorTemperature, 5600)])
    #expect(PresetCatalog.changes(Preset(name: "Авто", values: [.whiteBalance: 2]), manual).map(\.label) == ["AWB → 5600 K"])
    // свойства на камере нет — сравнивать не с чем
    #expect(PresetCatalog.changes(warm, [:]).isEmpty)
}

@Test func captureTakesTheModeItIsGiven() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    #expect(PresetCatalog.capture(name: "В", mode: .video, from: props).mode == .video)
    // «Взять с камеры» режим не меняет
    #expect(PresetCatalog.recapture(Preset(name: "В", mode: .video, values: [:]), from: props).mode == .video)
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

@Test func lookRestoresTheCameraValuesOfWhatThePresetChanges() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let preset = Preset(name: "P", values: [.iso: 6400, .aperture: 560, .dro: 1], skip: [.dro])
    #expect(PresetCatalog.restoreValues(preset, from: props) == [.iso: 200, .aperture: 280])
}

@Test func restorePutsDependentSettingsBackBeforeTheirBase() async throws {
    // просмотр поставил «Цвет. темп.» 3200 K и ISO AUTO с +0.7; возвращаем AWB, 7400 K, ISO 200 и ±0
    let cam = FakeCamera(props: [
        .init(code: SonyProp.whiteBalance, type: PTPType.uint16, current: 0x8012, values: [2, 4, 0x8012]),
        .init(code: SonyProp.colorTemp, type: PTPType.uint16, current: 3200, values: [3200, 7400]),
        .init(code: SonyProp.iso, type: PTPType.uint32, current: 0xFF_FFFF, values: [0xFF_FFFF, 200]),
        .init(code: SonyProp.exposureComp, type: PTPType.int16, current: 700, values: [0, 700]),
    ])
    let s = try await readySession(cam)
    let result = await s.restore([.whiteBalance: 2, .colorTemperature: 7400, .iso: 200, .exposureComp: 0])
    #expect(result.skipped.isEmpty)
    let writes = cam.sent.filter { $0.op == 0x9205 && $0.params != [0xD25A] }.map { $0.params[0] }
    // температура — пока камера ещё в «Цвет. темп.», экспокоррекция — пока ещё ISO AUTO
    #expect(writes == [UInt32(SonyProp.colorTemp), UInt32(SonyProp.exposureComp), UInt32(SonyProp.whiteBalance),
                       UInt32(SonyProp.iso)])
}

@Test func applyStopsWhenTheLookIsOver() async throws {
    let cam = FakeCamera(props: [
        .init(code: SonyProp.iso, type: PTPType.uint32, current: 200, values: [100, 200, 400]),
        .init(code: SonyProp.fNumber, type: PTPType.uint16, current: 280, values: [280, 320]),
    ])
    let s = try await readySession(cam)
    let preset = Preset(name: "P", values: [.iso: 400, .aperture: 320])
    // кнопку отпустили до того, как пресет дописался: остальное он уже не пишет
    let result = await s.apply(preset, while: { false })
    #expect(result.applied.isEmpty)
    #expect(result.skipped == [.iso, .aperture])
}
