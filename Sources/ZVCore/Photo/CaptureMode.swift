import Foundation

/// Фото или видео — определяется режимом съёмки камеры (0x500E).
public enum CaptureMode: String, Codable, Sendable {
    case photo, video

    public init(exposureProgram value: Int64) {
        // 0x0007_80xx — видео, 0x0009_80xx — S&Q; всё остальное — фоторежимы
        let family = value >> 16
        self = family == 7 || family == 9 ? .video : .photo
    }

    private enum Letter { case p, a, s, m }

    private static func letter(of value: Int64) -> Letter {
        switch value {
        case 0x1, 0x78053, 0x9805C: return .m
        case 0x20003, 0x78051, 0x9805A: return .a
        case 0x30004, 0x78052, 0x9805B: return .s
        default: return .p  // P, авто, сюжеты
        }
    }

    private static func code(_ letter: Letter, _ mode: CaptureMode) -> Int64 {
        switch (letter, mode) {
        case (.p, .photo): return 0x10002
        case (.a, .photo): return 0x20003
        case (.s, .photo): return 0x30004
        case (.m, .photo): return 0x1
        case (.p, .video): return 0x78050
        case (.a, .video): return 0x78051
        case (.s, .video): return 0x78052
        case (.m, .video): return 0x78053
        }
    }

    /// Режим съёмки с той же буквой (P/A/S/M) в другом режиме; nil — уже там или камера не даёт.
    public static func counterpart(of value: Int64, to mode: CaptureMode, settable: [Int64]) -> Int64? {
        guard CaptureMode(exposureProgram: value) != mode else { return nil }
        let target = code(letter(of: value), mode)
        return settable.contains(target) ? target : nil
    }
}
