import Foundation

/// Поиск камеры в сети: камеры Sony в режиме PC Remote по Wi-Fi отвечают на SSDP M-SEARCH службой DigitalImaging.
public enum SSDP {
    public static let multicastHost = "239.255.255.250"
    public static let port: UInt16 = 1900
    public static let searchTarget = "urn:schemas-sony-com:service:DigitalImaging:1"

    public struct Response: Equatable, Sendable {
        /// Адрес камеры в сети.
        public let host: String
        /// Постоянный идентификатор камеры (в нём её MAC-адрес).
        public let usn: String
        /// Порт PTP/IP: у настоящих камер всегда 15740, другой сообщают только виртуальные (`X-PTPIP-Port`).
        public let port: UInt16

        public init(host: String, usn: String, port: UInt16 = PTPIP.port) {
            self.host = host
            self.usn = usn
            self.port = port
        }

        public var endpoint: CameraEndpoint { CameraEndpoint(host: host, port: port) }
    }

    public static func searchRequest() -> Data {
        Data(("M-SEARCH * HTTP/1.1\r\nHOST: \(multicastHost):\(port)\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
            + "ST: \(searchTarget)\r\n\r\n").utf8)
    }

    /// Ответ камеры Sony со службой DigitalImaging; всё остальное в сети — nil.
    public static func parse(_ text: String) -> Response? {
        var headers: [String: String] = [:]
        for line in text.components(separatedBy: "\r\n") {
            guard let colon = line.firstIndex(of: ":") else { continue }
            headers[line[..<colon].uppercased()] = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
        }
        guard headers["ST"] == searchTarget || headers["NT"] == searchTarget,
              let location = headers["LOCATION"], let host = URL(string: location)?.host else { return nil }
        let usn = headers["USN"].map { $0.components(separatedBy: "::").first ?? $0 } ?? host
        let port = headers["X-PTPIP-PORT"].flatMap(UInt16.init).flatMap { $0 > 0 ? $0 : nil } ?? PTPIP.port
        return Response(host: host, usn: usn, port: port)
    }
}
