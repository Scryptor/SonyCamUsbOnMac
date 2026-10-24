import Foundation
import Testing
@testable import ZVCore

private func props() throws -> [UInt16: SonyProperty] {
    try SonyPropertyParser.parse(Fixture.data("props_settled"))
}

@Test func shutterLabels() {
    #expect(ValueFormatter.label(.shutter, 0x0001_0032) == "1/50")
    #expect(ValueFormatter.label(.shutter, 0x0001_0FA0) == "1/4000")
    #expect(ValueFormatter.label(.shutter, 0x000A_000A) == "1\"")
    #expect(ValueFormatter.label(.shutter, 0x0005_000A) == "0.5\"")
    #expect(ValueFormatter.label(.shutter, 0x0019_000A) == "2.5\"")
    #expect(ValueFormatter.label(.shutter, 0x012C_000A) == "30\"")
    #expect(ValueFormatter.label(.shutter, 0) == "BULB")
}

@Test func apertureLabels() {
    #expect(ValueFormatter.label(.aperture, 280) == "f/2.8")
    #expect(ValueFormatter.label(.aperture, 400) == "f/4")
    #expect(ValueFormatter.label(.aperture, 630) == "f/6.3")
    #expect(ValueFormatter.label(.aperture, 2200) == "f/22")
    #expect(ValueFormatter.label(.aperture, 0xFFFF) == "f/--")
}

@Test func isoAndExposureCompLabels() {
    #expect(ValueFormatter.label(.iso, 0xFF_FFFF) == "AUTO")
    #expect(ValueFormatter.label(.iso, 200) == "200")
    #expect(ValueFormatter.label(.exposureComp, 0) == "±0.0")
    #expect(ValueFormatter.label(.exposureComp, 300) == "+0.3")
    #expect(ValueFormatter.label(.exposureComp, -700) == "\u{2212}0.7")
    #expect(ValueFormatter.label(.exposureComp, 2000) == "+2.0")
}

@Test func enumLabels() {
    #expect(ValueFormatter.label(.whiteBalance, 2) == "AWB")
    #expect(ValueFormatter.label(.whiteBalance, 0x8020) == "Свой 1")
    #expect(ValueFormatter.label(.focusMode, 0x8004) == "AF-C")
    #expect(ValueFormatter.label(.focusArea, 0x103) == "Точка L")
    #expect(ValueFormatter.label(.focusArea, 0x206) == "Слежение: точка L")
    #expect(ValueFormatter.label(.metering, 0x8001) == "Мульти")
    #expect(ValueFormatter.label(.dro, 0x1F) == "DRO авто")
    #expect(ValueFormatter.label(.dro, 0x13) == "DRO 3")
    #expect(ValueFormatter.label(.pictureProfile, 10) == "PP10")
    #expect(ValueFormatter.label(.exposureProgram, 0x0007_8053) == "Видео M")
}

@Test func miscLabels() {
    #expect(ValueFormatter.label(.colorTemperature, 5200) == "5200 K")
    #expect(ValueFormatter.label(.wbShiftAB, 192) == "0")
    #expect(ValueFormatter.label(.wbShiftAB, 194) == "+0.5")
    #expect(ValueFormatter.label(.wbShiftGM, 193) == "+0.25")
    #expect(ValueFormatter.label(.wbShiftGM, 188) == "\u{2212}1")
    #expect(ValueFormatter.label(.battery, -1) == "—")
    #expect(ValueFormatter.label(.battery, 24) == "24%")
    #expect(ValueFormatter.label(.cardRemaining, 9176) == "2 ч 32 мин")
    #expect(ValueFormatter.label(.cardRemaining, 59) == "0 мин")
}

@Test func unknownValueFallsBackToNumber() {
    #expect(ValueFormatter.label(.whiteBalance, 0x000E) == "#14")
    #expect(ValueFormatter.label(.movieFileFormat, 8) == "#8")
    #expect(ValueFormatter.label(.iso, nil) == "—")
}

@Test func optionsComeFromGivenPropertyOnly() throws {
    let p = try props()
    let aperture = SettingOptions.options(.aperture, try #require(p[SonyProp.fNumber]))
    #expect(aperture.count == 19 && aperture.first?.label == "f/2.8" && aperture.last?.label == "f/22")
    let wb = SettingOptions.options(.whiteBalance, try #require(p[SonyProp.whiteBalance]))
    #expect(wb.count == 14 && !wb.contains { $0.value == 7 })
    #expect(SettingOptions.options(.exposureComp, try #require(p[SonyProp.exposureComp])).isEmpty)
    #expect(SettingOptions.options(.colorTemperature, try #require(p[SonyProp.colorTemp])).isEmpty)
    #expect(SettingOptions.options(.wbShiftAB, try #require(p[SonyProp.abShift])).count == 29)
    #expect(SettingOptions.options(.wbShiftGM, try #require(p[SonyProp.gmShift])).count == 57)
}

@Test func neighbourClampsAtEnds() throws {
    let p = try props()
    let f = try #require(p[SonyProp.fNumber])
    #expect(SettingOptions.neighbour(.aperture, f, offset: 1) == 320)
    #expect(SettingOptions.neighbour(.aperture, f, offset: -1) == nil)
    let iso = try #require(p[SonyProp.iso])
    #expect(SettingOptions.neighbour(.iso, iso, offset: 1) == 250)
    #expect(SettingOptions.neighbour(.iso, iso, offset: -2) == 125)
}

@Test func catalogLayout() {
    #expect(SettingCatalog.fnTopRow == [.setting(.shutter), .setting(.aperture), .setting(.iso),
                                        .setting(.whiteBalance), .setting(.exposureComp), .setting(.focusMode)])
    #expect(SettingCatalog.fnBottomRow == [.setting(.focusArea), .setting(.metering), .setting(.pictureProfile),
                                           .setting(.creativeStyle), .setting(.dro), .allSettings])
    #expect(SettingCatalog.infoLine == [.shutter, .aperture, .iso, .whiteBalance, .exposureComp, .focusMode])
    #expect(SettingGroup.allCases.map(\.title) == ["Экспозиция", "Фокус", "Цвет и картинка", "Видео", "Фото", "Камера"])
    #expect(SettingID.allCases.allSatisfy { id in SettingGroup.allCases.filter { $0.settings.contains(id) }.count == 1 })
}
