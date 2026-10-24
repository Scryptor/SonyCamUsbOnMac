import Foundation
import Testing
@testable import ZVCore

@Test func parsesRealDeviceInfo() throws {
    let info = try DeviceInfo(data: Fixture.data("deviceinfo"))
    #expect(info.manufacturer == "Sony Corporation")
    #expect(info.model == "ZV-E10")
    #expect(info.firmware == "2.00")
    #expect(info.vendorExtensionID == 0x11)
    #expect(info.operations.count == 32)
    #expect(info.isPCRemote)
}

@Test func parsesRealExtInfo() throws {
    let ext = try SonyExtInfo(data: Fixture.data("sony_extdeviceinfo"))
    #expect(ext.version == 0x012C)
    #expect(ext.propertyCodes.count == 93)
    #expect(ext.controlCodes.count == 29)
}

@Test func parsesAllRealProperties() throws {
    let props = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    #expect(props.count == 93)
    let wb = try #require(props[SonyProp.whiteBalance])
    #expect(wb.current == .int(2) && wb.isWritable)
    #expect(wb.settableValues.count == 14 && wb.allValues.count == 15)
    #expect(!wb.settableValues.contains(7) && wb.allValues.contains(7))
    let f = try #require(props[SonyProp.fNumber])
    #expect(f.current == .int(280) && f.settableValues.count == 19)
    #expect(f.settableValues.first == 280 && f.settableValues.last == 2200)
    let ev = try #require(props[SonyProp.exposureComp])
    #expect(!ev.isWritable && ev.settableValues.isEmpty && ev.allValues.count == 13)
    #expect(props[SonyProp.iso]?.current == .int(200))
    #expect(props[SonyProp.shutter]?.current == .int(0x0001_0032))
    #expect(props[SonyProp.colorTemp]?.range == PropertyRange(min: 2500, max: 9900, step: 100))
    #expect(props[SonyProp.colorTemp]?.isWritable == false)
    #expect(props[SonyProp.abShift]?.range == PropertyRange(min: 164, max: 220, step: 2))
    #expect(props[SonyProp.abShift]?.current == .int(192))
    #expect(props[SonyProp.batteryLevel]?.current == .int(24))
}

@Test func extractsJPEGFromRealLiveViewFrame() throws {
    let jpeg = try #require(LiveViewFrame.jpeg(from: Fixture.data("lv_frame_raw")))
    #expect(jpeg.count == 20736)
    #expect(jpeg.prefix(2) == Data([0xFF, 0xD8]))
}

@Test func liveViewWithoutJPEGIsNil() {
    #expect(LiveViewFrame.jpeg(from: Data([8, 0, 0, 0, 1, 2, 3, 4, 5, 6])) == nil)
}
