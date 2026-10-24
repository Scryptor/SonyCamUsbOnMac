import Foundation

/// Что показать о цветовой температуре при текущем режиме баланса белого.
/// Камера сообщает только ручную температуру (0xD20F) — и в любом режиме отдаёт её, сохранённую.
/// Температуру пресетов и автобаланса она не сообщает, проверено по всем 95 свойствам.
public enum WhiteBalanceTemperature {
    public enum Reading: Equatable, Sendable {
        /// Режим «Цвет. темп.»: температура камеры.
        case manual(Int64)
        /// Пресет: примерная температура.
        case approximate(Int64)
        /// Автобаланс, «Под водой», «Свой»: подбирает камера, значение неизвестно.
        case automatic
    }

    /// Режим баланса белого «Цвет. темп.».
    public static let manualMode: Int64 = 0x8012
    /// Диапазон ручной температуры, если камера его не сообщила.
    public static let fallbackRange = PropertyRange(min: 2500, max: 9900, step: 100)

    /// Примерная температура пресетов: дневной, тень, облачно, лампа, вспышка — по Sony Alpha Universe,
    /// флуоресцентные — по классам ламп (тёплый, холодный, дневной белый, дневной).
    static let typical: [Int64: Int64] = [
        4: 5600, 0x8011: 7000, 0x8010: 6000, 6: 3200, 7: 5500,
        0x8001: 3000, 0x8002: 4000, 0x8003: 5000, 0x8004: 6500,
    ]

    public static func reading(whiteBalance: Int64?, colorTemperature: Int64?) -> Reading {
        guard let whiteBalance else { return .automatic }
        if whiteBalance == manualMode, let colorTemperature { return .manual(colorTemperature) }
        if let kelvin = typical[whiteBalance] { return .approximate(kelvin) }
        return .automatic
    }

    public static func caption(_ reading: Reading) -> String {
        switch reading {
        case let .manual(kelvin): return "\(kelvin) K"
        case let .approximate(kelvin): return "≈ \(kelvin) K"
        case .automatic: return "авто"
        }
    }

    /// Где стоит ползунок: на ручной или примерной температуре; при автобалансе — на последней ручной.
    public static func sliderValue(_ reading: Reading, stored: Int64?) -> Int64 {
        switch reading {
        case let .manual(kelvin), let .approximate(kelvin): return kelvin
        case .automatic: return stored ?? 5600
        }
    }

    /// Пояснение к значению: откуда оно и что будет, если сдвинуть ползунок.
    public static func explanation(_ reading: Reading) -> String {
        switch reading {
        case .manual: return "Температура задана вручную."
        case .approximate: return "Примерно: температуру пресетов камера не сообщает. Сдвиньте — камера перейдёт на ручную температуру."
        case .automatic: return "Температуру подбирает камера и не сообщает её. Сдвиньте — камера перейдёт на ручную температуру."
        }
    }
}
