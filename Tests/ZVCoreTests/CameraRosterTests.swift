import Foundation
import Testing
@testable import ZVCore

private let now = Date(timeIntervalSince1970: 1_761_400_000)

private func wifi(_ host: String, _ port: UInt16 = PTPIP.port, usn: String? = nil, ago: TimeInterval = 0) -> CameraSighting {
    CameraSighting(link: .wifi(CameraEndpoint(host: host, port: port)), fingerprint: usn.map(CameraFingerprint.usn),
                   seenAt: now.addingTimeInterval(-ago))
}

private func usb(_ key: String, serial: String? = nil) -> CameraSighting {
    CameraSighting(link: .usb(key), model: "ZV-E10", fingerprint: serial.flatMap(CameraFingerprint.serial), seenAt: now)
}

@Test func endpointReadsHostAndOptionalPort() {
    #expect(CameraEndpoint("192.168.1.40") == CameraEndpoint(host: "192.168.1.40", port: 15740))
    #expect(CameraEndpoint("127.0.0.1:15741") == CameraEndpoint(host: "127.0.0.1", port: 15741))
    #expect(CameraEndpoint("") == nil)
    #expect(CameraEndpoint("host:0") == nil)
    #expect(CameraEndpoint("host:port") == nil)
    #expect(CameraEndpoint("127.0.0.1:15741")?.description == "127.0.0.1:15741")
    #expect(CameraEndpoint("192.168.1.40")?.description == "192.168.1.40")
}

@Test func fingerprintIsAShortHashAndNeverTheSerial() {
    let fingerprint = CameraFingerprint.serial("0000000000000000000000000000FAKEA")
    #expect(fingerprint?.count == 16)
    #expect(fingerprint == CameraFingerprint.hash("sn:0000000000000000000000000000FAKEA"))
    #expect(fingerprint != CameraFingerprint.serial("0000000000000000000000000000FAKEB"))
    #expect(CameraFingerprint.serial("  ") == nil)
    // первые 8 байт SHA-256 от «sn:1» — так же считает планшет
    #expect(CameraFingerprint.serial("1") == "25cb6b2d717e95fb")
}

@Test func ssdpReplyOfAVirtualCameraCarriesItsPort() {
    let reply = "HTTP/1.1 200 OK\r\nLOCATION: http://127.0.0.1:15741/fake_camera.xml\r\n"
        + "ST: urn:schemas-sony-com:service:DigitalImaging:1\r\nUSN: uuid:abc::urn:schemas-sony-com:service:DigitalImaging:1\r\n"
        + "X-PTPIP-Port: 15741\r\n\r\n"
    #expect(SSDP.parse(reply) == SSDP.Response(host: "127.0.0.1", usn: "uuid:abc", port: 15741))
    // без заголовка — порт настоящих камер
    let real = reply.replacingOccurrences(of: "X-PTPIP-Port: 15741\r\n", with: "")
    #expect(SSDP.parse(real)?.port == 15740)
}

@Test func rosterRoundTripsThroughJSONAndSkipsWhatItDoesNotKnow() throws {
    let camera = KnownCamera(id: "3f2a9c0d1e4b5a67", aliases: ["9b8c"], name: "A", model: "ZV-E10", host: "127.0.0.1",
                             port: 15741, paired: true, lastUsed: now)
    let real = KnownCamera(id: "1111", name: "ZV-E10", model: "ZV-E10", host: "192.168.1.40", port: 15740, paired: true)
    let data = try #require(CameraRoster(known: [camera, real]).encoded())
    let text = String(decoding: data, as: UTF8.self)
    #expect(text.contains(#""port":15741"#))
    #expect(!text.contains(#""port":15740"#))   // у настоящих камер порт не пишется
    var loaded = CameraRoster.load(cameras: data, preferred: "1111", legacyName: nil, legacyHost: nil)
    #expect(loaded.known.first == camera)
    #expect(loaded.known.last?.port == nil)
    #expect(loaded.preferred == "1111")
    // запись без id и незнакомые поля не губят остальные; предпочтение на неизвестную — сброшено
    let future = #"[{"name":"без id"},{"id":"x","name":"B","model":"ZV-E10","paired":false,"color":"red"}]"#
    loaded = CameraRoster.load(cameras: Data(future.utf8), preferred: "нет такой", legacyName: nil, legacyHost: nil)
    #expect(loaded.known.map(\.id) == ["x"])
    #expect(loaded.preferred == nil)
}

@Test func oldSingleWiFiCameraBecomesARecordThatGetsItsRealFingerprint() {
    var roster = CameraRoster.load(cameras: nil, preferred: nil, legacyName: "ZV-E10", legacyHost: "192.168.1.194")
    #expect(roster.known == [KnownCamera(id: "legacy", name: "ZV-E10", model: "ZV-E10", host: "192.168.1.194",
                                         port: 15740, paired: true)])
    #expect(roster.firstEndpoint == CameraEndpoint(host: "192.168.1.194"))
    roster.rename("legacy", to: "Основная")
    let id = roster.connected(serial: "SERIAL1", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "192.168.1.194")),
                              usn: "uuid:cam1", now: now)
    #expect(id == CameraFingerprint.serial("SERIAL1"))
    #expect(roster.known.count == 1)
    #expect(roster.known[0].name == "Основная")
    #expect(roster.known[0].aliases == [CameraFingerprint.usn("uuid:cam1")])
    #expect(roster.preferred == id)
    // без прежней камеры и без списка — пусто
    #expect(CameraRoster.load(cameras: nil, preferred: nil, legacyName: nil, legacyHost: nil).known.isEmpty)
}

@Test func oldCameraIsFoundEvenIfItsAddressChanged() {
    var roster = CameraRoster.load(cameras: nil, preferred: nil, legacyName: "ZV-E10", legacyHost: "192.168.1.194")
    // пока прежняя камера себя не узнала, стучаться можно к любой — как раньше
    #expect(roster.isPaired(CameraEndpoint(host: "192.168.1.77")))
    let id = roster.connected(serial: "S", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "192.168.1.77")), now: now)
    #expect(roster.known.map(\.id) == [id])
    #expect(roster.known[0].host == "192.168.1.77")
    // дальше — только знакомые
    #expect(!roster.isPaired(CameraEndpoint(host: "192.168.1.78")))
}

@Test func connectingNamesNewCamerasAndRemembersTheAddress() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1")), usn: "uuid:a", now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1", port: 15741)),
                             usn: "uuid:b", now: now.addingTimeInterval(10))
    let c = roster.connected(serial: "C", model: "ZV-E10", link: .usb("dev1"), usbSerial: "C-USB", now: now.addingTimeInterval(20))
    #expect(roster.known.map(\.name) == ["ZV-E10", "ZV-E10 2", "ZV-E10 3"])
    #expect(roster.camera(b)?.endpoint == CameraEndpoint(host: "127.0.0.1", port: 15741))
    #expect(roster.camera(b)?.paired == true)
    #expect(roster.camera(c)?.paired == false)                 // по кабелю сопряжение не нужно
    #expect(roster.camera(c)?.aliases == [CameraFingerprint.serial("C-USB")!])
    #expect(roster.preferred == c)
    // снова A — та же запись, она же предпочтённая
    #expect(roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.2")), now: now) == a)
    #expect(roster.known.count == 3)
    #expect(roster.camera(a)?.host == "127.0.0.2")
    #expect(roster.firstEndpoint == CameraEndpoint(host: "127.0.0.2"))
}

@Test func sightingsMatchByFingerprintAliasOrAddress() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.5")), usn: "uuid:a", now: now)
    #expect(roster.index(of: wifi("10.0.0.99", usn: "uuid:a")) == 0)            // адрес сменился, uuid тот же
    #expect(roster.index(of: wifi("10.0.0.5")) == 0)                             // uuid не пришёл — по адресу
    #expect(roster.index(of: wifi("10.0.0.5", 15741)) == nil)                    // другой порт — другая камера
    #expect(roster.index(of: usb("d", serial: "A")) == 0)                        // по кабелю — серийный номер
    #expect(roster.index(of: wifi("10.0.0.7", usn: "uuid:z")) == nil)
    #expect(roster.isPaired(CameraEndpoint(host: "10.0.0.99"), usn: "uuid:a"))
    #expect(!roster.isPaired(CameraEndpoint(host: "10.0.0.7")))
    roster.unpair(CameraEndpoint(host: "10.0.0.5"))
    #expect(roster.camera(a)?.paired == false)
}

@Test func listShowsCurrentThenVisibleThenTheRest() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1")), usn: "uuid:a", now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1", port: 15741)),
                             usn: "uuid:b", now: now.addingTimeInterval(5))
    _ = roster.connected(serial: "OLD", model: "ZV-E10", link: .usb("old"), now: now.addingTimeInterval(-100))
    roster.rename(b, to: "B")
    let sightings = [wifi("127.0.0.1", 15741, usn: "uuid:b"), usb("dev7"), wifi("127.0.0.1", 15742, usn: "uuid:new"),
                     wifi("127.0.0.1", usn: "uuid:a")]
    let entries = roster.entries(sightings: sightings, current: (.wifi(CameraEndpoint(host: "127.0.0.1")), a, true), now: now)
    #expect(entries.map(\.name) == ["ZV-E10", "ZV-E10", "B", "Камера в сети", "ZV-E10 3"])
    #expect(entries.map(\.state) == [.current, .available, .available, .needsPairing, .offline])
    #expect(entries.map(\.stateLabel) == ["на связи", "свободна", "свободна", "нужно сопряжение", "не подключена"])
    #expect(entries.map(\.detail) == ["Wi-Fi · 127.0.0.1", "по кабелю", "Wi-Fi · 127.0.0.1:15741", "Wi-Fi · 127.0.0.1:15742",
                                      "по кабелю"])
    #expect(entries[1].id == "usb:dev7")
    #expect(entries[3].id == "wifi:127.0.0.1:15742")
    #expect(entries.map { $0.action.map(String.init(describing:)) } == [nil, "connect", "connect", "pair", nil])
    // B пропала из поиска больше 30 с назад — не видна, но знакома: попробовать по прежнему адресу
    let later = roster.entries(sightings: [wifi("127.0.0.1", 15741, usn: "uuid:b", ago: 31)],
                               current: (.wifi(CameraEndpoint(host: "127.0.0.1")), a, false), now: now)
    #expect(later.map(\.stateLabel) == ["подключаю…", "не видна в сети", "не подключена"])
    #expect(later[1].endpoint == CameraEndpoint(host: "127.0.0.1", port: 15741))
    #expect(later[1].action.map(String.init(describing:)) == "connect")
    // с ней идёт сопряжение — так и написано, кнопки нет
    var pairing = entries[3]
    pairing.pairing = true
    #expect(pairing.stateLabel == "сопрягаю…")
    #expect(pairing.action == nil)
}

@Test func renameForgetAndUnpairAll() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.5")), now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.6")), now: now)
    roster.rename(a, to: "  Левая  ")
    roster.rename(a, to: "   ")                              // пустое имя не сохраняется
    #expect(roster.camera(a)?.name == "Левая")
    #expect(roster.defaultName(model: "ZV-E10") == "ZV-E10")  // «ZV-E10» теперь свободно
    roster.forget(b)
    #expect(roster.preferred == nil)
    #expect(roster.known.map(\.id) == [a])
    roster.unpairAll()
    #expect(roster.camera(a)?.paired == false)
    #expect(roster.camera(a)?.name == "Левая")
    #expect(roster.firstEndpoint == nil)
}

@Test func trackerSwitchesToTheChosenCamera() {
    var tracker = ConnectionTracker<Int>()
    #expect(tracker.appeared(1) == .connect(1))
    #expect(tracker.appeared(2) == .none)                     // вторая ждёт
    #expect(tracker.choose(2) == .disconnect(thenConnect: 2))
    #expect(tracker.current == 2)
    #expect(tracker.choose(2) == .connect(2))                 // уже она — просто подключить, если сеанса нет
    #expect(tracker.choose(9) == .none)                       // такой на шине нет
    #expect(tracker.disappeared(2) == .disconnect(thenConnect: 1))
}

@Test func stripFlagIsStoredOnlyWhenSet() throws {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.5")), now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.6")), now: now)
    roster.setInStrip(b, true)
    let text = String(decoding: try #require(roster.encoded()), as: UTF8.self)
    #expect(text.components(separatedBy: #""strip":true"#).count == 2)   // только у B
    let loaded = CameraRoster.load(cameras: Data(text.utf8), preferred: nil, legacyName: nil, legacyHost: nil)
    #expect(loaded.camera(b)?.inStrip == true)
    #expect(loaded.camera(a)?.inStrip == false)
}

@Test func stripCamerasFollowTheMainInTheList() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1")), now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1", port: 15741)), now: now)
    let c = roster.connected(serial: "C", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "127.0.0.1", port: 15742)), now: now)
    roster.setInStrip(b, true)
    roster.setInStrip(c, true)
    let entries = roster.entries(sightings: [wifi("127.0.0.1", 15741)],
                                 current: (.wifi(CameraEndpoint(host: "127.0.0.1")), a, true),
                                 strip: [(c, true), (b, false)], now: now)
    #expect(entries.map(\.id) == [a, c, b])
    #expect(entries.map(\.state) == [.current, .side, .side])
    #expect(entries.map(\.stateLabel) == ["на связи", "в ленте", "в ленте · нет связи"])
    // без связи — подключить главной по последнему адресу
    #expect(entries.map { $0.action.map(String.init(describing:)) } == [nil, "makeMain", "connect"])
    #expect(entries[1].inStrip && entries[1].stripConnected)
}

@Test func stripPlanKeepsFourCamerasAndRetriesEveryTenSeconds() {
    var roster = CameraRoster()
    var ids: [String] = []
    for (i, letter) in ["A", "B", "C", "D", "E"].enumerated() {
        let id = roster.connected(serial: letter, model: "ZV-E10",
                                  link: .wifi(CameraEndpoint(host: "127.0.0.1", port: UInt16(15740 + i))),
                                  now: now.addingTimeInterval(TimeInterval(i)))
        roster.setInStrip(id, true)
        ids.append(id)
    }
    let usb = roster.connected(serial: "U", model: "ZV-E10", link: .usb("dev"), now: now.addingTimeInterval(9))
    roster.setInStrip(usb, true)                  // по кабелю в ленту не попадает
    roster.unpair(CameraEndpoint(host: "127.0.0.1", port: 15744))   // E не сопряжена
    // главная A — в ленте ещё три, от последней: D, C, B
    #expect(CameraStripPlan.members(roster, main: ids[0]).map(\.id) == [ids[3], ids[2], ids[1]])
    #expect(CameraStripPlan.members(roster, main: nil).count == 4)
    // C на связи, D пробовали 3 с назад — сейчас только B
    let plan = CameraStripPlan.toConnect(roster, main: ids[0], connected: [ids[2]], lastAttempt: [ids[3]: now.addingTimeInterval(-3)],
                                         now: now)
    #expect(plan.map(\.id) == [ids[1]])
    // через 10 с — и D
    #expect(CameraStripPlan.toConnect(roster, main: ids[0], connected: [ids[2]], lastAttempt: [ids[3]: now.addingTimeInterval(-10)],
                                      now: now).map(\.id) == [ids[3], ids[1]])
    roster.setInStrip(ids[3], false)
    #expect(CameraStripPlan.members(roster, main: ids[0]).map(\.id) == [ids[2], ids[1]])
}

@Test func linkedFlagIsStoredOnlyWhenSetAndLeavesWithTheStrip() throws {
    var roster = CameraRoster()
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.6")), now: now)
    roster.setInStrip(b, true)
    roster.setLinked(b, true)
    let text = String(decoding: try #require(roster.encoded()), as: UTF8.self)
    #expect(text.contains(#""linked":true"#))
    #expect(CameraRoster.load(cameras: Data(text.utf8), preferred: nil, legacyName: nil, legacyHost: nil).camera(b)?.linked == true)
    roster.setInStrip(b, false)                       // убрали из ленты — и не связана
    #expect(roster.camera(b)?.linked == false)
    #expect(!String(decoding: try #require(roster.encoded()), as: UTF8.self).contains("linked"))
}

@Test func switchingToALinkedCameraKeepsTheGroupTogether() {
    var roster = CameraRoster()
    let a = roster.connected(serial: "A", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.5")), now: now)
    let b = roster.connected(serial: "B", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.6")), now: now)
    let c = roster.connected(serial: "C", model: "ZV-E10", link: .wifi(CameraEndpoint(host: "10.0.0.7")), now: now)
    for id in [a, b, c] { roster.setInStrip(id, true) }
    roster.switchedTo(c, from: a)                     // на несвязанную — связи не меняются
    #expect(roster.camera(a)?.linked == false)
    roster.setLinked(b, true)
    roster.switchedTo(b, from: c)                     // на связанную — прежняя главная тоже связана
    #expect(roster.camera(c)?.linked == true)
    roster.setInStrip(a, false)
    roster.switchedTo(b, from: a)                     // прежняя не в ленте — связывать нечего
    #expect(roster.camera(a)?.linked == false)
    roster.switchedTo(b, from: nil)
    roster.switchedTo(b, from: b)
    #expect(roster.camera(b)?.linked == true)
}

@Test func groupFailuresMakeOneStatusLine() {
    var failures = GroupFailures()
    #expect(failures.text == nil)
    failures.add(camera: "C", what: GroupFailures.label(.iso, 51200))
    failures.add(camera: "C", what: GroupFailures.label(.iso, 51200))   // дважды — один раз
    #expect(failures.text == "Не удалось на «C»: ISO 51200")
    failures.add(camera: "B", what: "запись")
    failures.add(camera: "C", what: GroupFailures.label(.aperture, 180))
    #expect(failures.text == "Не удалось на «C»: ISO 51200, f/1.8; на «B»: запись")
    #expect(GroupFailures.label(.focusMode, 0x8004) == "Фокус AF-C")
    #expect(GroupFailures.label(.shutter, 0x10032) == "1/50")
}
