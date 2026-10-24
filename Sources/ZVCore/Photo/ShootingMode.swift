import Foundation

/// Фото, видео или замедленная/ускоренная съёмка (S&Q) — по режиму съёмки камеры (0x500E).
/// Для интерфейса S&Q — та же видеосъёмка (`captureMode == .video`), отличается только переключатель.
public enum ShootingMode: String, Codable, Sendable, CaseIterable {
    case photo, video, slowQuick

    public init(exposureProgram value: Int64) {
        // 0x0007_80xx — видео, 0x0009_80xx — S&Q; всё остальное — фоторежимы
        switch value >> 16 {
        case 7: self = .video
        case 9: self = .slowQuick
        default: self = .photo
        }
    }

    public var captureMode: CaptureMode { self == .photo ? .photo : .video }

    private enum Letter: Int { case p, a, s, m }

    private static func letter(of value: Int64) -> Letter {
        switch value {
        case 0x1, 0x78053, 0x9805C: return .m
        case 0x20003, 0x78051, 0x9805A: return .a
        case 0x30004, 0x78052, 0x9805B: return .s
        default: return .p  // P, авто, сюжеты
        }
    }

    private static let codes: [ShootingMode: [Int64]] = [
        .photo: [0x10002, 0x20003, 0x30004, 0x1],
        .video: [0x78050, 0x78051, 0x78052, 0x78053],
        .slowQuick: [0x98059, 0x9805A, 0x9805B, 0x9805C],
    ]

    /// Режим съёмки с той же буквой (P/A/S/M) в другом режиме; nil — уже там или камера не даёт.
    public static func counterpart(of value: Int64, to mode: ShootingMode, settable: [Int64]) -> Int64? {
        guard ShootingMode(exposureProgram: value) != mode, let target = codes[mode]?[letter(of: value).rawValue] else { return nil }
        return settable.contains(target) ? target : nil
    }
}

/// Замедленная и ускоренная съёмка (S&Q). Частоту съёмки (1–100 к/с) ZV-E10 по кабелю не сообщает и менять не даёт:
/// её видно только по самой длинной выдержке, которую камера разрешает, — выдержка не может быть длиннее кадра.
public enum SlowQuick {
    static let palRates = [1, 2, 4, 8, 15, 25, 50, 100]
    static let ntscRates = [1, 2, 4, 8, 15, 30, 60, 120]

    /// Частота съёмки S&Q по допустимым выдержкам (`0xD20D`): числитель в старших 16 битах, знаменатель в младших.
    public static func captureFrameRate(shutterValues: [Int64], pal: Bool) -> Int? {
        let seconds = shutterValues.compactMap { value -> Double? in
            let numerator = Double(value >> 16), denominator = Double(value & 0xFFFF)
            return numerator > 0 && denominator > 0 ? numerator / denominator : nil
        }
        guard let slowest = seconds.max() else { return nil }
        let rates = pal ? palRates : ntscRates
        // камера даёт выдержку чуть длиннее кадра (1/13 при 15 к/с, 1/6 при 8): ближайшая частота не меньше 1/выдержка
        return rates.first { Double($0) >= 1 / slowest - 0.01 } ?? rates.last
    }

    /// Во сколько раз изменится скорость: частота съёмки против частоты ролика.
    public static func speedLabel(capture: Int, record: Int) -> String {
        guard capture > 0, record > 0, capture != record else { return "обычная скорость" }
        let factor = capture > record ? Double(capture) / Double(record) : Double(record) / Double(capture)
        let number = factor == factor.rounded()
            ? String(Int(factor))
            : String(format: "%.1f", factor).replacingOccurrences(of: ".", with: ",")
        return "×\(number) " + (capture > record ? "медленнее" : "быстрее")
    }
}
