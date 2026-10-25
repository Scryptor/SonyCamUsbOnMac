import Foundation

/// Пакет PTP/IP: u32 длина (вместе с заголовком), u32 тип, затем содержимое.
public struct PTPIPPacket: Equatable, Sendable {
    public let type: UInt32
    public let payload: Data

    public init(type: UInt32, payload: Data) {
        self.type = type
        self.payload = payload
    }
}

public enum PTPIPError: Error, Equatable {
    case badLength(UInt32)
    case unexpectedPacket(UInt32)
    case rejected(reason: UInt32)
}

public enum PTPIPInitResult: Equatable, Sendable {
    case accepted(connectionNumber: UInt32, cameraName: String)
    /// 1 — программа не сопряжена с камерой.
    case rejected(reason: UInt32)
}

/// PTP поверх TCP (CIPA DC-005): два соединения на порт 15740 — команды с данными и события.
public enum PTPIP {
    public static let port: UInt16 = 15740
    /// Адрес камеры, когда она сама раздаёт сеть (Wi-Fi Direct): у камер Sony он всегда такой.
    public static let cameraAccessPointHost = "192.168.122.1"

    /// Камера раздаёт сеть сама — связь напрямую, без роутера.
    public static func isCameraAccessPoint(_ host: String) -> Bool { host == cameraAccessPointHost }

    public enum PacketType {
        public static let initCommandRequest: UInt32 = 1
        public static let initCommandAck: UInt32 = 2
        public static let initEventRequest: UInt32 = 3
        public static let initEventAck: UInt32 = 4
        public static let initFail: UInt32 = 5
        public static let operationRequest: UInt32 = 6
        public static let operationResponse: UInt32 = 7
        public static let event: UInt32 = 8
        public static let startData: UInt32 = 9
        public static let data: UInt32 = 10
        public static let cancel: UInt32 = 11
        public static let endData: UInt32 = 12
        public static let probeRequest: UInt32 = 13
        public static let probeResponse: UInt32 = 14
    }

    /// Приветствие: GUID программы, её имя (его камера показывает при сопряжении) и версия протокола 1.0.
    public static func initCommandRequest(guid: Data, name: String) -> Data {
        var payload = guid
        for unit in name.utf16 { payload.appendLE(unit) }
        payload.appendLE(UInt16(0))
        payload.appendLE(UInt32(0x0001_0000))
        return packet(PacketType.initCommandRequest, payload)
    }

    public static func initEventRequest(connectionNumber: UInt32) -> Data {
        var payload = Data()
        payload.appendLE(connectionNumber)
        return packet(PacketType.initEventRequest, payload)
    }

    /// dataOut — у операции есть данные от программы к камере (запись значения).
    public static func operationRequest(_ operation: UInt16, transactionID: UInt32, params: [UInt32], dataOut: Bool) -> Data {
        var payload = Data()
        payload.appendLE(UInt32(dataOut ? 2 : 1))
        payload.appendLE(operation)
        payload.appendLE(transactionID)
        for p in params { payload.appendLE(p) }
        return packet(PacketType.operationRequest, payload)
    }

    public static func startData(transactionID: UInt32, length: UInt64) -> Data {
        var payload = Data()
        payload.appendLE(transactionID)
        payload.appendLE(length)
        return packet(PacketType.startData, payload)
    }

    public static func endData(transactionID: UInt32, payload data: Data) -> Data {
        var payload = Data()
        payload.appendLE(transactionID)
        payload.append(data)
        return packet(PacketType.endData, payload)
    }

    public static var probeResponse: Data { packet(PacketType.probeResponse, Data()) }

    /// Проба «ты здесь?» по событийному соединению: камера отвечает ProbeResponse за 5–50 мс.
    public static var probeRequest: Data { packet(PacketType.probeRequest, Data()) }

    public static func parseInitReply(_ packet: PTPIPPacket) throws -> PTPIPInitResult {
        var r = ByteReader(packet.payload)
        switch packet.type {
        case PacketType.initCommandAck:
            let connection = try r.u32()
            try r.skip(16)  // GUID камеры
            var units: [UInt16] = []
            while r.remaining >= 2 {
                let unit = try r.u16()
                if unit == 0 { break }
                units.append(unit)
            }
            return .accepted(connectionNumber: connection, cameraName: String(decoding: units, as: UTF16.self))
        case PacketType.initFail:
            return .rejected(reason: r.remaining >= 4 ? try r.u32() : 0)
        default:
            throw PTPIPError.unexpectedPacket(packet.type)
        }
    }

    public static func parseEvent(_ packet: PTPIPPacket) -> PTPEvent? {
        guard packet.type == PacketType.event else { return nil }
        var r = ByteReader(packet.payload)
        guard let code = try? r.u16(), (try? r.u32()) != nil else { return nil }
        var params: [UInt32] = []
        while let p = try? r.u32() { params.append(p) }
        return PTPEvent(code: code, params: params)
    }

    static func packet(_ type: UInt32, _ payload: Data) -> Data {
        var data = Data()
        data.appendLE(UInt32(8 + payload.count))
        data.appendLE(type)
        data.append(payload)
        return data
    }
}

/// Поток TCP приходит кусками: копим байты и отдаём целые пакеты.
public struct PTPIPPacketReader: Sendable {
    private var buffer = Data()

    public init() {}

    public mutating func feed(_ data: Data) {
        buffer.append(data)
    }

    /// Следующий целый пакет; nil — пакет ещё не дочитан.
    public mutating func next() throws -> PTPIPPacket? {
        guard buffer.count >= 8 else { return nil }
        var r = ByteReader(buffer.prefix(8))
        let length = try r.u32()
        let type = try r.u32()
        // длиннее 256 МБ пакетов не бывает (файлы идут по 2 МБ): такая длина — сбившийся поток
        guard length >= 8, length <= 256 << 20 else { throw PTPIPError.badLength(length) }
        guard buffer.count >= Int(length) else { return nil }
        let payload = Data(buffer.dropFirst(8).prefix(Int(length) - 8))
        buffer = Data(buffer.dropFirst(Int(length)))
        return PTPIPPacket(type: type, payload: payload)
    }
}

/// Ответ на одну операцию: данные (Start Data → Data… → End Data) и ответ камеры.
/// Пакеты чужих транзакций — остатки оборванного обмена — пропускаются.
public struct PTPIPTransaction: Sendable {
    public let transactionID: UInt32
    /// Сколько байт данных обещала камера (из Start Data); nil — фазы данных ещё не было.
    public private(set) var expected: Int?
    private var data = Data()

    public init(transactionID: UInt32) {
        self.transactionID = transactionID
    }

    /// Возвращает ответ, когда он пришёл; до этого — nil.
    public mutating func consume(_ packet: PTPIPPacket) -> PTPReply? {
        var r = ByteReader(packet.payload)
        switch packet.type {
        case PTPIP.PacketType.startData:
            if (try? r.u32()) == transactionID, let length = try? r.u64() { expected = Int(clamping: length) }
            return nil
        case PTPIP.PacketType.data, PTPIP.PacketType.endData:
            guard (try? r.u32()) == transactionID else { return nil }
            data.append(packet.payload.dropFirst(4))
            return nil
        case PTPIP.PacketType.operationResponse:
            guard let code = try? r.u16(), (try? r.u32()) == transactionID else { return nil }
            var params: [UInt32] = []
            while let p = try? r.u32() { params.append(p) }
            return PTPReply(code: code, params: params, data: data)
        default:
            return nil
        }
    }
}
