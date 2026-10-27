import Foundation

public enum LiveViewFrame {
    /// Кадр live view: u32 смещение до JPEG, u32 его размер, дальше служебное (рамки фокуса), сам JPEG и снова служебное.
    public static func jpeg(from data: Data) -> Data? {
        guard data.count > 8 else { return nil }
        let base = data.startIndex
        func u32(_ at: Int) -> Int {
            Int(UInt32(data[base + at]) | UInt32(data[base + at + 1]) << 8 | UInt32(data[base + at + 2]) << 16
                | UInt32(data[base + at + 3]) << 24)
        }
        let offset = u32(0), size = u32(4)
        if offset + 1 < data.count, data[base + offset] == 0xFF, data[base + offset + 1] == 0xD8 {
            let end = size > 0 && offset + size <= data.count ? offset + size : data.count
            return data.subdata(in: (base + offset)..<(base + end))
        }
        // запасной путь: ищем начало JPEG (FF D8 FF)
        var i = base
        while i + 2 < data.endIndex {
            if data[i] == 0xFF, data[i + 1] == 0xD8, data[i + 2] == 0xFF { return data.subdata(in: i..<data.endIndex) }
            i += 1
        }
        return nil
    }
}

/// Кадр live view и как он шёл — по этим отсчётам видно, сколько тянет канал.
public struct LiveViewSample: Sendable {
    public let jpeg: Data
    /// Сколько байт пришло в ответе камеры.
    public let bytes: Int
    /// Сколько шёл запрос, вернувший кадр: от отправки до ответа, с.
    public let seconds: TimeInterval
    /// Самый короткий ответ «кадр не готов» перед этим кадром — оценка времени отклика камеры, с.
    public let shortReplySeconds: TimeInterval?
    /// Сколько раз перед этим кадром камера ответила «кадр не готов».
    public let waits: Int
    /// Рамки фокуса этого кадра: где камера фокусирует.
    public let focusFrames: [FocusFrame]

    public init(jpeg: Data, bytes: Int, seconds: TimeInterval, shortReplySeconds: TimeInterval?, waits: Int = 0,
                focusFrames: [FocusFrame] = []) {
        self.jpeg = jpeg
        self.bytes = bytes
        self.seconds = seconds
        self.shortReplySeconds = shortReplySeconds
        self.waits = waits
        self.focusFrames = focusFrames
    }
}

extension Duration {
    var seconds: TimeInterval { Double(components.seconds) + Double(components.attoseconds) / 1e18 }
}
