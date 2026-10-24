import Foundation

public enum PTPResponseCode {
    public static let ok: UInt16 = 0x2001
    public static let invalidObjectHandle: UInt16 = 0x2009
    public static let accessDenied: UInt16 = 0x200F
}

public enum PTPCommand {
    /// USB-контейнер команды: длина, тип 1, код, transaction id (его назначает система), параметры.
    public static func encode(_ operation: UInt16, _ params: [UInt32]) -> Data {
        var data = Data()
        data.appendLE(UInt32(12 + 4 * params.count))
        data.appendLE(UInt16(1))
        data.appendLE(operation)
        data.appendLE(UInt32(0))
        for p in params { data.appendLE(p) }
        return data
    }
}

public struct PTPReply: Equatable, Sendable {
    public let code: UInt16
    public let params: [UInt32]
    public let data: Data

    public init(code: UInt16, params: [UInt32] = [], data: Data = Data()) {
        self.code = code
        self.params = params
        self.data = data
    }

    public var isOK: Bool { code == PTPResponseCode.ok }

    /// Собирает ответ из контейнера ответа и данных, которые вернула система.
    public static func fromContainers(response: Data, data: Data, operation: UInt16) -> PTPReply {
        var r = ByteReader(response)
        var code: UInt16 = 0
        var params: [UInt32] = []
        if (try? r.skip(6)) != nil, let c = try? r.u16() {
            code = c
            if (try? r.skip(4)) != nil {
                while let p = try? r.u32() { params.append(p) }
            }
        }
        return PTPReply(code: code, params: params, data: stripDataHeader(data, operation: operation))
    }

    /// Заголовок контейнера данных снимается, только если он точно есть.
    private static func stripDataHeader(_ data: Data, operation: UInt16) -> Data {
        guard data.count >= 12 else { return data }
        var r = ByteReader(data)
        guard let length = try? r.u32(), let type = try? r.u16(), let code = try? r.u16(),
              Int(length) == data.count, type == 2, code == operation else { return data }
        return Data(data.dropFirst(12))
    }
}
