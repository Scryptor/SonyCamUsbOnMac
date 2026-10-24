import Foundation

public enum LiveViewFrame {
    /// Кадр live view: первые 4 байта — смещение до JPEG, дальше сам JPEG.
    public static func jpeg(from data: Data) -> Data? {
        let bytes = [UInt8](data)
        guard bytes.count > 8 else { return nil }
        let offset = Int(UInt32(bytes[0]) | UInt32(bytes[1]) << 8 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 24)
        if offset + 1 < bytes.count, bytes[offset] == 0xFF, bytes[offset + 1] == 0xD8 {
            return Data(bytes[offset...])
        }
        // запасной путь: ищем начало JPEG (FF D8 FF)
        for i in 0..<(bytes.count - 2) where bytes[i] == 0xFF && bytes[i + 1] == 0xD8 && bytes[i + 2] == 0xFF {
            return Data(bytes[i...])
        }
        return nil
    }
}
