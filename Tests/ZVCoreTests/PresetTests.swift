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
    let preset = Preset(name: "Улица", values: [.iso: 100, .aperture: 560])
    let data = try JSONEncoder().encode(preset)
    #expect(try JSONDecoder().decode(Preset.self, from: data) == preset)
    #expect(String(decoding: data, as: UTF8.self).contains("\"aperture\""))
}
