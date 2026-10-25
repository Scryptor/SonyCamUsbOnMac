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

private let fourKAllowed: [Int64] = [0x21, 0x24]
private let hdAllowed: [Int64] = [0x04, 0x19, 0x05, 0x1B, 0x1D, 0x1F]

@Test func recordingStepLooksAtWhatTheCameraHasNow() {
    let hd25 = RecordingMode(format: 9, setting: 0x1B)
    // камера в 4K — сначала формат
    #expect(RecordingModes.nextStep(to: hd25, format: 8, setting: 0x21, allowed: fourKAllowed) == .writeFormat(9))
    // формат уже HD, а параметры камера показывает прежние (код 4K) — пишем нужные
    #expect(RecordingModes.nextStep(to: hd25, format: 9, setting: 0x21, allowed: hdAllowed) == .writeSetting(0x1B))
    // формат HD, а список параметров ещё от 4K — ждём
    #expect(RecordingModes.nextStep(to: hd25, format: 9, setting: 0x21, allowed: fourKAllowed) == .wait)
    #expect(RecordingModes.nextStep(to: hd25, format: 9, setting: 0x1B, allowed: hdAllowed) == .done)
    // камера без свойства формата — только параметры
    #expect(RecordingModes.nextStep(to: hd25, format: nil, setting: 0x1D, allowed: hdAllowed) == .writeSetting(0x1B))
}

@Test func recordingModeDriverRetriesASkippedWriteThenGivesUp() {
    // камера отвечает «готово», но пропускает запись, пришедшую, пока она применяет прошлую, — повторяем, но не вечно
    var driver = RecordingModeDriver()
    driver.choose(RecordingMode(format: 9, setting: 0x1D))
    for _ in 0..<RecordingModeDriver.maxAttempts {
        #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == .writeFormat(9))
    }
    #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == nil)
    #expect(driver.target == nil)
}

@Test func recordingModeDriverFollowsTheLatestChoice() {
    var driver = RecordingModeDriver()
    driver.choose(RecordingMode(format: 8, setting: 0x24))
    #expect(driver.next(format: 9, setting: 0x1D, allowed: hdAllowed) == .writeFormat(8))
    // передумали, пока камера переключалась в 4K (параметры она показывает прежние): формат обратно, потом параметры
    driver.choose(RecordingMode(format: 9, setting: 0x1B))
    #expect(driver.next(format: 8, setting: 0x1D, allowed: fourKAllowed) == .writeFormat(9))
    #expect(driver.next(format: 9, setting: 0x1D, allowed: hdAllowed) == .writeSetting(0x1B))
    #expect(driver.next(format: 9, setting: 0x1B, allowed: hdAllowed) == nil)
    #expect(driver.target == nil)
}

@Test func recordingModeDriverStartsCountingAgainAfterANewChoice() {
    var driver = RecordingModeDriver()
    driver.choose(RecordingMode(format: 9, setting: 0x1D))
    #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == .writeFormat(9))
    #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == .writeFormat(9))
    driver.choose(RecordingMode(format: 9, setting: 0x1B))
    for _ in 0..<RecordingModeDriver.maxAttempts {
        #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == .writeFormat(9))
    }
    #expect(driver.next(format: 8, setting: 0x21, allowed: fourKAllowed) == nil)
}

@Test func recordingModeDriverWaitsForTheCameraButNotForever() {
    var driver = RecordingModeDriver()
    driver.choose(RecordingMode(format: 9, setting: 0x1B))
    for _ in 0..<RecordingModeDriver.maxWaits {
        #expect(driver.next(format: 9, setting: 0x21, allowed: fourKAllowed) == .wait)
    }
    #expect(driver.next(format: 9, setting: 0x21, allowed: fourKAllowed) == nil)
}

@Test func formatChoiceKeepsTheFrameRateWhenItCan() {
    let modes = RecordingModes.options(formats: [8, 9], format: 8, allowed: fourKAllowed)
    // 4K 25p 100M → HD: те же 25p с самым высоким битрейтом
    #expect(RecordingModes.closest(to: RecordingMode(format: 8, setting: 0x21), format: 9, in: modes)?.label == "HD 25p 50M")
    // HD 100p → 4K: 100p в 4K нет — первый режим 4K
    #expect(RecordingModes.closest(to: RecordingMode(format: 9, setting: 0x1D), format: 8, in: modes)?.label == "4K 25p 100M")
    // формат тот же — режим не меняется
    #expect(RecordingModes.closest(to: RecordingMode(format: 8, setting: 0x24), format: 8, in: modes)?.label == "4K 25p 60M")
    #expect(RecordingModes.closest(to: nil, format: 9, in: modes)?.label == "HD 100p 100M")
}

@Test func recordingModeLabelHidesParametersOfTheOtherFormat() {
    // после смены формата камера ещё показывает параметры прежнего: «HD 25p 100M» не бывает — только формат
    #expect(RecordingModes.label(format: 9, setting: 0x21, allowed: hdAllowed) == "HD")
    #expect(RecordingModes.label(format: 8, setting: 0x21, allowed: fourKAllowed) == "4K 25p 100M")
    // список параметров не пришёл — как раньше
    #expect(RecordingModes.label(format: 8, setting: 0x21, allowed: []) == "4K 25p 100M")
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

