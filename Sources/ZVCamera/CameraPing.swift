import Darwin
import Foundation

/// ICMP ping камеры без root (`SOCK_DGRAM` + `IPPROTO_ICMP`). Нужен, чтобы во время провала связи понять, что сеть
/// вернулась: TCP после долгого провала молчит ещё долго (повторы отправки с растущими паузами).
enum CameraPing {
    /// Ответила ли камера на один запрос за timeout.
    static func ping(_ host: String, timeout: TimeInterval) async -> Bool {
        await withCheckedContinuation { continuation in
            DispatchQueue.global(qos: .utility).async {
                continuation.resume(returning: pingBlocking(host, timeout: timeout))
            }
        }
    }

    private static let identifier = UInt16(truncatingIfNeeded: getpid())
    private static let sequence = Sequence()

    private final class Sequence: @unchecked Sendable {
        private let lock = NSLock()
        private var value: UInt16 = 0
        func next() -> UInt16 {
            lock.lock()
            defer { lock.unlock() }
            value &+= 1
            return value
        }
    }

    private static func pingBlocking(_ host: String, timeout: TimeInterval) -> Bool {
        var target = sockaddr_in()
        target.sin_family = sa_family_t(AF_INET)
        guard inet_pton(AF_INET, host, &target.sin_addr) == 1 else { return false }
        let fd = socket(AF_INET, SOCK_DGRAM, IPPROTO_ICMP)
        guard fd >= 0 else { return false }
        defer { close(fd) }
        var wait = timeval(tv_sec: Int(timeout), tv_usec: Int32((timeout - floor(timeout)) * 1_000_000))
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &wait, socklen_t(MemoryLayout<timeval>.size))

        let seq = sequence.next()
        var packet: [UInt8] = [8, 0, 0, 0, UInt8(identifier >> 8), UInt8(identifier & 0xFF), UInt8(seq >> 8), UInt8(seq & 0xFF)]
        packet += Array("zv-ping".utf8)
        let sum = checksum(packet)
        packet[2] = UInt8(sum >> 8)
        packet[3] = UInt8(sum & 0xFF)
        let sent = packet.withUnsafeBytes { bytes in
            withUnsafePointer(to: &target) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(fd, bytes.baseAddress, bytes.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        guard sent == packet.count else { return false }

        let deadline = Date().addingTimeInterval(timeout)
        var buffer = [UInt8](repeating: 0, count: 256)
        while Date() < deadline {
            var from = sockaddr_in()
            var length = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = withUnsafeMutablePointer(to: &from) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { recvfrom(fd, &buffer, buffer.count, 0, $0, &length) }
            }
            guard n > 0 else { return false }
            guard from.sin_addr.s_addr == target.sin_addr.s_addr else { continue }
            // ответ приходит вместе с заголовком IP
            let offset = buffer[0] >> 4 == 4 ? Int(buffer[0] & 0x0F) * 4 : 0
            if n >= offset + 8, buffer[offset] == 0 { return true }
        }
        return false
    }

    private static func checksum(_ bytes: [UInt8]) -> UInt16 {
        var sum: UInt32 = 0
        var i = 0
        while i + 1 < bytes.count {
            sum += UInt32(bytes[i]) << 8 | UInt32(bytes[i + 1])
            i += 2
        }
        if i < bytes.count { sum += UInt32(bytes[i]) << 8 }
        while sum >> 16 != 0 { sum = (sum & 0xFFFF) + (sum >> 16) }
        return ~UInt16(sum)
    }
}
