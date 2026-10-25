import Testing
@testable import ZVCore

@Test func recordingModesListBothFormatsOfTheSameSystem() {
    // камера в 4K (PAL): свои параметры она перечисляет сама, HD — по таблице тех же 25/50/100p
    let modes = RecordingModes.options(formats: [8, 9], format: 8, allowed: [0x21, 0x24])
    #expect(modes.map(\.label) == ["4K 25p 100M", "4K 25p 60M", "HD 100p 100M", "HD 100p 60M",
                                   "HD 50p 50M", "HD 50p 25M", "HD 25p 50M", "HD 25p 16M"])
    // из HD — тот же список
    #expect(RecordingModes.options(formats: [8, 9], format: 9, allowed: [0x04, 0x19, 0x05, 0x1B, 0x1D, 0x1F]) == modes)
}

@Test func recordingModesFollowNTSC() {
    let modes = RecordingModes.options(formats: [8, 9], format: 9, allowed: [0x01, 0x02, 0x03, 0x18, 0x1A, 0x1C, 0x1E])
    #expect(modes.prefix(4).map(\.label) == ["4K 30p 100M", "4K 30p 60M", "4K 24p 100M", "4K 24p 60M"])
    #expect(modes.contains { $0.label == "HD 120p 100M" })
    #expect(!modes.contains { $0.label.contains("25p") })
}

@Test func singleFormatCameraListsOnlyItsOwnSettings() {
    #expect(RecordingModes.options(formats: [9], format: 9, allowed: [0x04, 0x1D]).map(\.label) == ["HD 100p 100M", "HD 50p 50M"])
    #expect(RecordingModes.options(formats: [8, 9], format: nil, allowed: []).isEmpty)
}

@Test func recordingModeCodeRoundTripsAndLabels() {
    let mode = RecordingMode(format: 9, setting: 0x1D)
    #expect(RecordingMode(code: mode.code) == mode)
    #expect(mode.label == "HD 100p 100M")
    #expect(RecordingModes.label(format: 8, setting: 0x21) == "4K 25p 100M")
    // камера без свойства формата (старые модели) — подпись параметров как раньше
    #expect(RecordingModes.label(format: nil, setting: 0x21) == "25p 100M")
    #expect(RecordingModes.label(format: 8, setting: nil) == "—")
}

@Test func recordingModePlanAccountsForAFormatWriteInFlight() {
    let hd100 = RecordingMode(format: 9, setting: 0x1D)
    let fourK60 = RecordingMode(format: 8, setting: 0x24)
    // формат уже тот — только параметры
    #expect(RecordingModes.plan(to: hd100, confirmedFormat: 9, requestedFormat: nil) == .settingOnly)
    // другой формат — сначала формат
    #expect(RecordingModes.plan(to: hd100, confirmedFormat: 8, requestedFormat: nil) == .formatThenSetting)
    // передумали, пока камера переключается в HD: камера ещё в 4K, но формат HD уже в пути — снова формат 4K
    #expect(RecordingModes.plan(to: fourK60, confirmedFormat: 8, requestedFormat: 9) == .formatThenSetting)
    // нужный формат уже в пути — дождаться его, потом параметры
    #expect(RecordingModes.plan(to: hd100, confirmedFormat: 8, requestedFormat: 9) == .waitForFormat)
}

@Test func recordingModeStepsFromTheLastRequest() {
    let modes = RecordingModes.options(formats: [8, 9], format: 8, allowed: [0x21, 0x24])
    // быстрые шаги колесом считаются от последнего запроса, а не от того, что камера уже подтвердила
    let first = RecordingModes.neighbour(of: modes[0], in: modes, offset: 1)
    #expect(first == modes[1])
    #expect(RecordingModes.neighbour(of: first, in: modes, offset: 1) == modes[2])
    #expect(RecordingModes.neighbour(of: modes.last, in: modes, offset: 1) == nil)
    #expect(RecordingModes.neighbour(of: nil, in: modes, offset: 1) == nil)
}

