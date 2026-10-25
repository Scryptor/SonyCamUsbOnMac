import CryptoKit
import Foundation

/// Где камера в сети: адрес и порт PTP/IP. У настоящих камер порт всегда 15740, другой бывает только у виртуальных.
public struct CameraEndpoint: Hashable, Sendable, CustomStringConvertible {
    public var host: String
    public var port: UInt16

    public init(host: String, port: UInt16 = PTPIP.port) {
        self.host = host
        self.port = port
    }

    /// «192.168.1.40» или «127.0.0.1:15741».
    public init?(_ text: String) {
        let parts = text.split(separator: ":", omittingEmptySubsequences: false)
        guard let host = parts.first, !host.isEmpty, parts.count <= 2 else { return nil }
        guard parts.count == 2 else {
            self.init(host: String(host))
            return
        }
        guard let port = UInt16(parts[1]), port > 0 else { return nil }
        self.init(host: String(host), port: port)
    }

    public var description: String { port == PTPIP.port ? host : "\(host):\(port)" }
}

/// Отпечаток камеры: SHA-256 от «sn:<серийный номер>» или «usn:<идентификатор в сети>», первые 16 знаков hex.
/// Сам номер нигде не хранится.
public enum CameraFingerprint {
    public static func serial(_ serial: String) -> String? {
        let trimmed = serial.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : hash("sn:" + trimmed)
    }

    public static func usn(_ usn: String) -> String { hash("usn:" + usn) }

    static func hash(_ text: String) -> String {
        SHA256.hash(data: Data(text.utf8)).prefix(8).map { String(format: "%02x", $0) }.joined()
    }
}

/// Камера, которую программа уже видела: как её назвали, где она была в сети, сопряжена ли.
public struct KnownCamera: Codable, Equatable, Sendable, Identifiable {
    public var id: String
    /// Другие отпечатки той же камеры: в сети она называет себя иначе, чем серийным номером.
    public var aliases: [String]
    public var name: String
    public var model: String
    public var host: String?
    /// nil — 15740.
    public var port: UInt16?
    public var paired: Bool
    public var lastUsed: Date?
    /// В ленте камер: программа держит её на связи рядом с главной.
    public var inStrip: Bool

    public init(id: String, aliases: [String] = [], name: String, model: String, host: String? = nil, port: UInt16? = nil,
                paired: Bool = false, lastUsed: Date? = nil, inStrip: Bool = false) {
        self.id = id
        self.aliases = aliases
        self.name = name
        self.model = model
        self.host = host
        self.port = port
        self.paired = paired
        self.lastUsed = lastUsed
        self.inStrip = inStrip
    }

    public var endpoint: CameraEndpoint? { host.map { CameraEndpoint(host: $0, port: port ?? PTPIP.port) } }

    func matches(_ fingerprints: [String]) -> Bool {
        fingerprints.contains(id) || fingerprints.contains(where: aliases.contains)
    }

    private enum CodingKeys: String, CodingKey {
        case id, aliases, name, model, host, port, paired, lastUsed, strip
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = try c.decode(String.self, forKey: .id)
        aliases = try c.decodeIfPresent([String].self, forKey: .aliases) ?? []
        model = try c.decodeIfPresent(String.self, forKey: .model) ?? "Камера"
        name = try c.decodeIfPresent(String.self, forKey: .name) ?? model
        host = try c.decodeIfPresent(String.self, forKey: .host)
        port = try c.decodeIfPresent(UInt16.self, forKey: .port)
        paired = try c.decodeIfPresent(Bool.self, forKey: .paired) ?? false
        lastUsed = try c.decodeIfPresent(Int.self, forKey: .lastUsed).map { Date(timeIntervalSince1970: TimeInterval($0)) }
        inStrip = try c.decodeIfPresent(Bool.self, forKey: .strip) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(id, forKey: .id)
        if !aliases.isEmpty { try c.encode(aliases, forKey: .aliases) }
        try c.encode(name, forKey: .name)
        try c.encode(model, forKey: .model)
        try c.encodeIfPresent(host, forKey: .host)
        if let port, port != PTPIP.port { try c.encode(port, forKey: .port) }
        try c.encode(paired, forKey: .paired)
        if let lastUsed { try c.encode(Int(lastUsed.timeIntervalSince1970), forKey: .lastUsed) }
        if inStrip { try c.encode(true, forKey: .strip) }
    }
}

/// Камера, которую видно сейчас: на шине USB или ответила на поиск в сети.
public struct CameraSighting: Equatable, Sendable {
    public enum Link: Hashable, Sendable {
        /// Какое устройство на шине.
        case usb(String)
        case wifi(CameraEndpoint)
    }

    public var link: Link
    public var model: String?
    /// `sn:` от серийного номера USB или `usn:` от идентификатора в сети.
    public var fingerprint: String?
    public var seenAt: Date

    public init(link: Link, model: String? = nil, fingerprint: String? = nil, seenAt: Date) {
        self.link = link
        self.model = model
        self.fingerprint = fingerprint
        self.seenAt = seenAt
    }
}

/// Строка списка камер.
public struct CameraEntry: Equatable, Sendable, Identifiable {
    public enum State: String, Sendable {
        case current, side, available, needsPairing, offline
    }

    public enum Action: Sendable {
        case connect, pair, makeMain
    }

    /// id знакомой камеры, иначе «usb:…» или «wifi:адрес».
    public var id: String
    public var knownID: String?
    public var name: String
    public var model: String
    /// Как до неё достать сейчас; nil — не видна.
    public var link: CameraSighting.Link?
    /// Где была по Wi-Fi в последний раз.
    public var lastEndpoint: CameraEndpoint?
    public var state: State
    public var paired: Bool
    /// Текущая: сеанс ещё не готов.
    public var connecting = false
    /// С ней сейчас идёт сопряжение («Сопрячь» в списке).
    public var pairing = false
    /// В ленте: камера отмечена, а её сеанс жив (иначе — «нет связи»).
    public var inStrip = false
    public var stripConnected = false

    /// «по кабелю», «Wi-Fi · 192.168.1.40».
    public var detail: String {
        switch link {
        case .usb: return "по кабелю"
        case let .wifi(endpoint): return "Wi-Fi · \(endpoint)"
        case nil: return lastEndpoint.map { "Wi-Fi · \($0)" } ?? "по кабелю"
        }
    }

    public var stateLabel: String {
        if pairing { return "сопрягаю…" }
        switch state {
        case .current: return connecting ? "подключаю…" : "на связи"
        case .side: return stripConnected ? "в ленте" : "в ленте · нет связи"
        case .available: return "свободна"
        case .needsPairing: return "нужно сопряжение"
        case .offline: return lastEndpoint == nil ? "не подключена" : "не видна в сети"
        }
    }

    public var action: Action? {
        if pairing { return nil }
        switch state {
        case .current: return nil
        case .side: return stripConnected ? .makeMain : .connect
        case .available: return .connect
        case .needsPairing: return .pair
        case .offline: return lastEndpoint == nil ? nil : .connect
        }
    }

    /// Куда стучаться по Wi-Fi: где видна сейчас, иначе где была.
    public var endpoint: CameraEndpoint? {
        if case let .wifi(endpoint) = link { return endpoint }
        return link == nil ? lastEndpoint : nil
    }
}

/// Знакомые камеры и какую выбрали последней. Без ImageCaptureCore и сети, чтобы проверять тестами.
public struct CameraRoster: Equatable, Sendable {
    public var known: [KnownCamera]
    /// К какой подключались последней — к ней программа стучится по Wi-Fi первой.
    public var preferred: String?

    /// Сколько камера по Wi-Fi считается видимой после последнего ответа на поиск.
    public static let wifiVisibility: TimeInterval = 30
    static let legacyID = "legacy"

    public init(known: [KnownCamera] = [], preferred: String? = nil) {
        self.known = known
        self.preferred = preferred
    }

    /// Из настроек. Ключа `cameras` нет, а прежняя память о камере по Wi-Fi есть — она одна, с id «legacy»: настоящий
    /// отпечаток появится при первом подключении к ней.
    public static func load(cameras: Data?, preferred: String?, legacyName: String?, legacyHost: String?) -> CameraRoster {
        guard let cameras else {
            guard let legacyName else { return CameraRoster() }
            let endpoint = legacyHost.flatMap(CameraEndpoint.init)
            return CameraRoster(known: [KnownCamera(id: legacyID, name: legacyName, model: legacyName, host: endpoint?.host,
                                                    port: endpoint?.port, paired: true)])
        }
        // незнакомые поля и записи без id пропускаем: остальные читаются
        let records = (try? JSONDecoder().decode([Failable].self, from: cameras))?.compactMap(\.camera) ?? []
        return CameraRoster(known: records, preferred: records.contains { $0.id == preferred } ? preferred : nil)
    }

    private struct Failable: Decodable {
        let camera: KnownCamera?
        init(from decoder: Decoder) throws { camera = try? KnownCamera(from: decoder) }
    }

    public func encoded() -> Data? { try? JSONEncoder().encode(known) }

    /// Чья это запись: отпечаток совпал с id или другим отпечатком; иначе по Wi-Fi — тот же адрес и порт.
    public func index(of sighting: CameraSighting) -> Int? {
        if let fingerprint = sighting.fingerprint, let index = known.firstIndex(where: { $0.matches([fingerprint]) }) {
            return index
        }
        guard case let .wifi(endpoint) = sighting.link else { return nil }
        return known.firstIndex { $0.endpoint == endpoint }
    }

    /// Список: текущая; видимые — на кабеле, потом по Wi-Fi, каждые по тому, когда подключались (незнакомые в
    /// конце); невидимые знакомые — по тому же. По Wi-Fi камера видна `wifiVisibility` после последнего ответа.
    public func entries(sightings: [CameraSighting], current: (link: CameraSighting.Link, knownID: String?, ready: Bool)?,
                        strip: [(id: String, connected: Bool)] = [], now: Date) -> [CameraEntry] {
        var used = Set<String>()
        var result: [CameraEntry] = []
        if let current {
            let sighting = sightings.first { $0.link == current.link }
            let index = current.knownID.flatMap { id in known.firstIndex { $0.id == id } } ?? sighting.flatMap(index(of:))
            let record = index.map { known[$0] }
            if let record { used.insert(record.id) }
            result.append(CameraEntry(id: record?.id ?? Self.entryID(current.link), knownID: record?.id,
                                      name: record?.name ?? Self.unknownName(current.link, sighting?.model),
                                      model: record?.model ?? sighting?.model ?? "Камера", link: current.link,
                                      lastEndpoint: record?.endpoint, state: .current, paired: record?.paired ?? false,
                                      connecting: !current.ready, inStrip: record?.inStrip ?? false))
        }
        // камеры ленты — следом за главной, в порядке ленты
        for side in strip where !used.contains(side.id) {
            guard let record = known.first(where: { $0.id == side.id }) else { continue }
            used.insert(record.id)
            result.append(CameraEntry(id: record.id, knownID: record.id, name: record.name, model: record.model,
                                      link: record.endpoint.map { .wifi($0) }, lastEndpoint: record.endpoint, state: .side,
                                      paired: record.paired, inStrip: true, stripConnected: side.connected))
        }
        let visible = sightings.filter { sighting in
            guard sighting.link != current?.link else { return false }
            if case .wifi = sighting.link { return now.timeIntervalSince(sighting.seenAt) <= Self.wifiVisibility }
            return true
        }
        var seen: [(entry: CameraEntry, usb: Bool, lastUsed: Date?)] = []
        for sighting in visible {
            let record = index(of: sighting).map { known[$0] }
            if let record {
                guard !used.contains(record.id) else { continue }
                used.insert(record.id)
            }
            let usb: Bool
            if case .usb = sighting.link { usb = true } else { usb = false }
            let paired = record?.paired ?? false
            seen.append((CameraEntry(id: record?.id ?? Self.entryID(sighting.link), knownID: record?.id,
                                     name: record?.name ?? Self.unknownName(sighting.link, sighting.model),
                                     model: record?.model ?? sighting.model ?? "Камера", link: sighting.link,
                                     lastEndpoint: record?.endpoint, state: usb || paired ? .available : .needsPairing,
                                     paired: paired, inStrip: record?.inStrip ?? false), usb, record?.lastUsed))
        }
        // стабильно: кабель раньше Wi-Fi, знакомые — от последней, незнакомые — как нашлись
        let order = seen.enumerated().sorted { a, b in
            if a.element.usb != b.element.usb { return a.element.usb }
            return Self.later(a.element.lastUsed, b.element.lastUsed, a.offset, b.offset)
        }
        result += order.map(\.element.entry)
        let offline = known.enumerated().filter { !used.contains($0.element.id) }.sorted {
            Self.later($0.element.lastUsed, $1.element.lastUsed, $0.offset, $1.offset)
        }
        result += offline.map { _, record in
            CameraEntry(id: record.id, knownID: record.id, name: record.name, model: record.model, link: nil,
                        lastEndpoint: record.endpoint, state: .offline, paired: record.paired, inStrip: record.inStrip)
        }
        return result
    }

    private static func later(_ a: Date?, _ b: Date?, _ ia: Int, _ ib: Int) -> Bool {
        switch (a, b) {
        case let (a?, b?) where a != b: return a > b
        case (_?, nil): return true
        case (nil, _?): return false
        default: return ia < ib
        }
    }

    static func entryID(_ link: CameraSighting.Link) -> String {
        switch link {
        case let .usb(key): return "usb:\(key)"
        case let .wifi(endpoint): return "wifi:\(endpoint)"
        }
    }

    static func unknownName(_ link: CameraSighting.Link, _ model: String?) -> String {
        if case .usb = link { return model ?? "Камера" }
        return "Камера в сети"
    }

    /// Камера подключилась и назвала себя. Запись — по отпечатку или другому отпечатку; прежняя «legacy» — у первой
    /// камеры по Wi-Fi (её адрес мог смениться, а прежние версии помнили одну камеру); иначе новая. Обновляются адрес, сопряжение (по Wi-Fi — да), когда подключались; она же — предпочтённая.
    @discardableResult
    public mutating func connected(serial: String?, model: String, link: CameraSighting.Link, usbSerial: String? = nil,
                                   usn: String? = nil, now: Date) -> String {
        let main = serial.flatMap(CameraFingerprint.serial)
        let others = [usn.map(CameraFingerprint.usn), usbSerial.flatMap(CameraFingerprint.serial)].compactMap { $0 }
        let fingerprints = ([main].compactMap { $0 } + others)
        var index = known.firstIndex { $0.matches(fingerprints) }
        if index == nil, case let .wifi(endpoint) = link {
            index = known.firstIndex { $0.id == Self.legacyID && $0.host == endpoint.host }
                ?? known.firstIndex { $0.id == Self.legacyID }
        }
        let id: String
        if let index {
            var record = known[index]
            if let main, record.id != main {
                if record.id != Self.legacyID, !record.aliases.contains(record.id) { record.aliases.append(record.id) }
                record.id = main
            }
            for fingerprint in others where fingerprint != record.id && !record.aliases.contains(fingerprint) {
                record.aliases.append(fingerprint)
            }
            record.aliases.removeAll { $0 == record.id }
            record.model = model
            record.lastUsed = now
            if case let .wifi(endpoint) = link {
                record.host = endpoint.host
                record.port = endpoint.port == PTPIP.port ? nil : endpoint.port
                record.paired = true
            }
            known[index] = record
            // та же камера могла остаться второй записью (была незнакомой под другим отпечатком) — сливаем
            known.removeAll { $0.id == record.id && $0 != record }
            id = record.id
        } else {
            let fallback: String
            switch link {
            case let .usb(key): fallback = CameraFingerprint.hash("usb:" + key)
            case let .wifi(endpoint): fallback = CameraFingerprint.hash("addr:\(endpoint)")
            }
            id = main ?? others.first ?? fallback
            var record = KnownCamera(id: id, aliases: (main == nil ? Array(others.dropFirst()) : others),
                                     name: defaultName(model: model), model: model, lastUsed: now)
            if case let .wifi(endpoint) = link {
                record.host = endpoint.host
                record.port = endpoint.port == PTPIP.port ? nil : endpoint.port
                record.paired = true
            }
            known.append(record)
        }
        preferred = id
        return id
    }

    /// Имя новой камеры: модель; занято — «ZV-E10 2», «ZV-E10 3»…
    public func defaultName(model: String) -> String {
        let taken = Set(known.map(\.name))
        guard taken.contains(model) else { return model }
        var number = 2
        while taken.contains("\(model) \(number)") { number += 1 }
        return "\(model) \(number)"
    }

    public mutating func rename(_ id: String, to name: String) {
        let trimmed = name.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let index = known.firstIndex(where: { $0.id == id }) else { return }
        known[index].name = trimmed
    }

    /// В ленту или из неё.
    public mutating func setInStrip(_ id: String, _ inStrip: Bool) {
        guard let index = known.firstIndex(where: { $0.id == id }) else { return }
        known[index].inStrip = inStrip
    }

    public mutating func forget(_ id: String) {
        known.removeAll { $0.id == id }
        if preferred == id { preferred = nil }
    }

    /// Сопряжена ли камера по этому адресу (или с этим идентификатором в сети). Пока прежняя память о камере не
    /// узнала свою камеру («legacy»), сопряжённой считается любая незнакомая: прежние версии так и стучались.
    public func isPaired(_ endpoint: CameraEndpoint, usn: String? = nil) -> Bool {
        if let usn, let record = known.first(where: { $0.matches([CameraFingerprint.usn(usn)]) }) { return record.paired }
        if let record = known.first(where: { $0.endpoint == endpoint }) { return record.paired }
        return known.contains { $0.id == Self.legacyID && $0.paired }
    }

    /// Камера по этому адресу отказала: программу она больше не знает.
    public mutating func unpair(_ endpoint: CameraEndpoint) {
        for index in known.indices where known[index].endpoint == endpoint { known[index].paired = false }
    }

    /// «Забыть сопряжение»: у программы новый GUID — ни одна камера его не знает, имена остаются.
    public mutating func unpairAll() {
        for index in known.indices { known[index].paired = false }
    }

    /// Куда стучаться по Wi-Fi первой: предпочтённая, если сопряжена и был адрес; иначе сопряжённая, к которой
    /// подключались последней.
    public var firstEndpoint: CameraEndpoint? {
        if let preferred, let record = known.first(where: { $0.id == preferred }), record.paired, let endpoint = record.endpoint {
            return endpoint
        }
        return known.enumerated().filter { $0.element.paired && $0.element.endpoint != nil }
            .sorted { Self.later($0.element.lastUsed, $1.element.lastUsed, $0.offset, $1.offset) }
            .first?.element.endpoint
    }

    public func camera(_ id: String?) -> KnownCamera? { id.flatMap { id in known.first { $0.id == id } } }
}

/// Лента камер: кто в ней и к кому подключаться. Без сети и таймеров, чтобы проверять тестами.
public enum CameraStripPlan {
    /// Всего на связи — главная и камеры ленты.
    public static let limit = 4
    /// Как часто стучаться к камере ленты, которой нет на связи.
    public static let retry: TimeInterval = 10

    /// Камеры ленты: отмеченные, сопряжённые, с адресом по Wi-Fi, кроме главной; от последней по времени. Сколько
    /// помещается: 4 вместе с главной; остальные ждут.
    public static func members(_ roster: CameraRoster, main: String?) -> [KnownCamera] {
        let room = limit - (main == nil ? 0 : 1)
        let candidates = roster.known.enumerated().filter { _, camera in
            camera.inStrip && camera.paired && camera.endpoint != nil && camera.id != main
        }
        let ordered = candidates.sorted { a, b in
            switch (a.element.lastUsed, b.element.lastUsed) {
            case let (x?, y?) where x != y: return x > y
            case (_?, nil): return true
            case (nil, _?): return false
            default: return a.offset < b.offset
            }
        }
        return Array(ordered.prefix(max(0, room)).map(\.element))
    }

    /// К каким камерам ленты подключаться сейчас: не на связи и последняя попытка — не меньше `retry` назад.
    public static func toConnect(_ roster: CameraRoster, main: String?, connected: Set<String>, lastAttempt: [String: Date],
                                 now: Date) -> [KnownCamera] {
        members(roster, main: main).filter { camera in
            !connected.contains(camera.id) && (lastAttempt[camera.id].map { now.timeIntervalSince($0) >= retry } ?? true)
        }
    }
}
