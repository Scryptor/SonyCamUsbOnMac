import CoreGraphics
import Foundation

/// Рамки фокуса из кадра live view — где камера сейчас фокусирует: рамки AF, лица, слежение. В ответе на GetObject
/// 0xFFFFC002 после смещения и размера JPEG идут смещение и размер блока FocalFrameInfo (Sony Camera Control PTP 3
/// Reference): версия, рамки AF, с версии 1.01 — лица и слежение. Координаты — числитель и знаменатель (у ZV-E10 —
/// сетка 640×480 ×1024), X и Y — центр рамки.
public struct FocusFrame: Equatable, Sendable {
    public enum Kind: String, Sendable {
        case focus, face, tracking
    }

    public let kind: Kind
    /// Рамка в долях кадра.
    public let rect: CGRect
    /// Фокус найден (у рамок AF — состояние 0x02, у лиц и слежения — тоже 0x02).
    public let focused: Bool

    public init(kind: Kind, rect: CGRect, focused: Bool) {
        self.kind = kind
        self.rect = rect
        self.focused = focused
    }

    /// Рамки из ответа камеры; пусто — блока нет или в нём ни одной рамки.
    public static func parse(liveView data: Data) -> [FocusFrame] {
        guard data.count >= 16 else { return [] }
        let base = data.startIndex
        func u32(_ at: Int) -> Int {
            Int(UInt32(data[base + at]) | UInt32(data[base + at + 1]) << 8 | UInt32(data[base + at + 2]) << 16
                | UInt32(data[base + at + 3]) << 24)
        }
        let offset = u32(8), size = u32(12)
        guard size > 0, offset >= 16, offset + size <= data.count else { return [] }
        return parse(info: data.subdata(in: (base + offset)..<(base + offset + size)))
    }

    /// Блок FocalFrameInfo сам по себе.
    public static func parse(info: Data) -> [FocusFrame] {
        let bytes = [UInt8](info)
        func u16(_ at: Int) -> Int { at + 2 <= bytes.count ? Int(bytes[at]) | Int(bytes[at + 1]) << 8 : 0 }
        func u32(_ at: Int) -> Double {
            guard at + 4 <= bytes.count else { return 0 }
            return Double(UInt32(bytes[at]) | UInt32(bytes[at + 1]) << 8 | UInt32(bytes[at + 2]) << 16 | UInt32(bytes[at + 3]) << 24)
        }
        guard bytes.count >= 56 else { return [] }
        let version = u16(0)
        // версия и резерв (8), резерв (40), счётчик резервного массива и резерв (8), сам массив по 24 байта
        var pos = 48
        pos += 8 + u16(pos) * 24
        var frames: [FocusFrame] = []
        let kinds: [Kind] = version >= 101 ? [.focus, .face, .tracking] : [.focus]
        for kind in kinds {
            guard pos + 16 <= bytes.count else { break }
            let xDen = u32(pos), yDen = u32(pos + 4), count = u16(pos + 8)
            var entry = pos + 16
            for _ in 0..<count {
                guard entry + 24 <= bytes.count else { break }
                let state = u16(entry + 2)
                let x = u32(entry + 8), y = u32(entry + 12), height = u32(entry + 16), width = u32(entry + 20)
                if xDen > 0, yDen > 0, width > 0 || height > 0 {
                    let w = width / xDen, h = height / yDen
                    frames.append(FocusFrame(kind: kind, rect: CGRect(x: x / xDen - w / 2, y: y / yDen - h / 2, width: w, height: h),
                                             focused: state == 2))
                }
                entry += 24
            }
            pos += 16 + count * 24
        }
        return frames
    }

    /// Блок FocalFrameInfo версии 1.01 — для поддельной камеры и тестов: рамки в сетке 640×480 ×1024.
    public static func encode(_ frames: [FocusFrame]) -> Data {
        var out = Data()
        func u16(_ v: Int) { out.append(contentsOf: [UInt8(v & 0xFF), UInt8(v >> 8 & 0xFF)]) }
        func u32(_ v: Int) { for shift in stride(from: 0, to: 32, by: 8) { out.append(UInt8(v >> shift & 0xFF)) } }
        u16(101)
        out.append(Data(count: 6 + 40))
        u16(0)
        out.append(Data(count: 6))
        let xDen = 640 * 1024, yDen = 480 * 1024
        for kind in [Kind.focus, .face, .tracking] {
            let mine = frames.filter { $0.kind == kind }
            u32(xDen)
            u32(yDen)
            u16(mine.count)
            out.append(Data(count: 6))
            for frame in mine {
                u16(1)
                u16(frame.focused ? 2 : 1)
                out.append(Data(count: 4))
                u32(Int((frame.rect.midX * CGFloat(xDen)).rounded()))
                u32(Int((frame.rect.midY * CGFloat(yDen)).rounded()))
                u32(Int((frame.rect.height * CGFloat(yDen)).rounded()))
                u32(Int((frame.rect.width * CGFloat(xDen)).rounded()))
            }
        }
        out.append(Data(count: 16))
        return out
    }
}
