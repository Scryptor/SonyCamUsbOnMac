import Foundation

/// Фото или видео — определяется режимом съёмки камеры (0x500E).
public enum CaptureMode: String, Codable, Sendable {
    case photo, video

    public init(exposureProgram value: Int64) {
        // 0x0007_80xx — видео, 0x0009_80xx — S&Q; всё остальное — фоторежимы
        let family = value >> 16
        self = family == 7 || family == 9 ? .video : .photo
    }

    /// Режим съёмки с той же буквой (P/A/S/M) в другом режиме; nil — уже там или камера не даёт.
    /// S&Q здесь — тоже видео; таблица режимов одна — в `ShootingMode`.
    public static func counterpart(of value: Int64, to mode: CaptureMode, settable: [Int64]) -> Int64? {
        guard CaptureMode(exposureProgram: value) != mode else { return nil }
        return ShootingMode.counterpart(of: value, to: mode == .photo ? .photo : .video, settable: settable)
    }
}
