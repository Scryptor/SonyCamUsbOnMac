import Foundation

/// Подписи значений настроек в том виде, как их показывает программа.
public enum ValueFormatter {
    private static let minus = "\u{2212}"

    public static func label(_ id: SettingID, _ property: SonyProperty?) -> String {
        guard let value = property?.current.intValue else { return "—" }
        return label(id, value)
    }

    public static func label(_ id: SettingID, _ value: Int64) -> String {
        switch id {
        case .shutter: return shutter(value)
        case .aperture: return aperture(value)
        case .iso:
            let iso = value & 0xFF_FFFF
            return iso == 0xFF_FFFF ? "AUTO" : "\(iso)"
        case .exposureComp:
            return value == 0 ? "±0.0" : sign(value) + String(format: "%.1f", Double(abs(value)) / 1000)
        case .colorTemperature: return "\(value) K"
        case .wbShiftAB, .wbShiftGM:
            // проверено по цвету кадра: больше 192 — A (теплее) и G (зеленее), меньше — B и M
            let shift = Double(value - 192) / 4
            if shift == 0 { return "0" }
            let letter = id == .wbShiftAB ? (shift > 0 ? "A" : "B") : (shift > 0 ? "G" : "M")
            return letter + trimmed(abs(shift))
        case .battery: return value < 0 ? "—" : "\(value)%"
        case .cardRemaining:
            let hours = value / 3600
            let minutes = (value % 3600) / 60
            return hours > 0 ? "\(hours) ч \(minutes) мин" : "\(minutes) мин"
        case .focusArea: return focusArea(value) ?? fallback(value)
        case .dro: return dro(value) ?? fallback(value)
        case .pictureProfile:
            if value == 0 { return "Выкл" }
            return (1...10).contains(value) ? "PP\(value)" : fallback(value)
        default:
            return tables[id]?[value] ?? fallback(value)
        }
    }

    private static func fallback(_ value: Int64) -> String { "#\(value)" }

    private static func sign(_ value: Int64) -> String { value < 0 ? minus : "+" }

    /// Число без лишних нулей: 1, 0.5, 0.25.
    private static func trimmed(_ x: Double) -> String {
        var s = String(format: "%.2f", x)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }

    private static func shutter(_ v: Int64) -> String {
        if v == 0 { return "BULB" }
        let num = (v >> 16) & 0xFFFF
        let den = v & 0xFFFF
        guard den > 0 else { return fallback(v) }
        if num == 1 && den > 1 { return "1/\(den)" }
        var s = String(format: "%.1f", Double(num) / Double(den))
        if s.hasSuffix(".0") { s.removeLast(2) }
        return s + "\""
    }

    private static func aperture(_ v: Int64) -> String {
        if v == 0 || v == 0xFFFF { return "f/--" }
        if v % 100 == 0 { return "f/\(v / 100)" }
        return "f/" + String(format: "%.1f", Double(v) / 100)
    }

    private static let spotNames: [Int64: String] = [
        1: "Широкая", 2: "Зона", 3: "Центр",
        0x101: "Точка S", 0x102: "Точка M", 0x103: "Точка L", 0x104: "Расш. точка",
    ]

    private static func focusArea(_ v: Int64) -> String? {
        if let name = spotNames[v] { return name }
        // 0x201…0x207 — те же зоны в режиме слежения
        guard (0x201...0x207).contains(v) else { return nil }
        let base: Int64 = v <= 0x203 ? v - 0x200 : v - 0x204 + 0x101
        guard let name = spotNames[base] else { return nil }
        return "Слежение: " + name.prefix(1).lowercased() + name.dropFirst()
    }

    private static func dro(_ v: Int64) -> String? {
        switch v {
        case 1: return "Выкл"
        case 2: return "DRO"
        case 0x10: return "DRO+"
        case 0x11...0x15: return "DRO \(v - 0x10)"
        case 0x1F: return "DRO авто"
        case 0x20: return "HDR авто"
        case 0x21...0x26: return "HDR \(v - 0x20) EV"
        default: return nil
        }
    }

    private static let tables: [SettingID: [Int64: String]] = [
        .whiteBalance: [
            2: "AWB", 4: "Дневной", 0x8011: "Тень", 0x8010: "Облачно", 6: "Лампа",
            0x8001: "Флуор. тёплый", 0x8002: "Флуор. холодный", 0x8003: "Флуор. дневной белый", 0x8004: "Флуор. дневной",
            7: "Вспышка", 0x8030: "Под водой", 0x8012: "Цвет. темп.",
            0x8020: "Свой 1", 0x8021: "Свой 2", 0x8022: "Свой 3",
        ],
        .focusMode: [1: "MF", 2: "AF-S", 0x8004: "AF-C", 0x8005: "AF-A", 0x8006: "DMF"],
        .metering: [
            0x8001: "Мульти", 0x8002: "Центр", 0x8003: "Весь экран",
            0x8004: "Точечный", 0x8005: "Точечный крупный", 0x8006: "По светлым",
        ],
        // сверить с камерой (Задача 9)
        .creativeStyle: [1: "Стандарт", 2: "Яркий", 3: "Портрет", 4: "Пейзаж", 5: "Закат", 6: "Ч/Б", 0x0D: "Сепия"],
        .exposureProgram: [
            1: "M", 0x10002: "P", 0x20003: "A", 0x30004: "S",
            0x78050: "Видео P", 0x78051: "Видео A", 0x78052: "Видео S", 0x78053: "Видео M", 0x78054: "Видео: сцена",
            0x98059: "S&Q P", 0x9805A: "S&Q A", 0x9805B: "S&Q S", 0x9805C: "S&Q M",
            0x48000: "Интеллект. авто", 0x48001: "Супер авто", 0x68041: "Панорама", 7: "Портрет",
            0x58011: "Спорт", 0x58012: "Закат", 0x58013: "Ночная сцена", 0x58014: "Пейзаж", 0x58015: "Макро",
            0x58016: "Сумерки с рук", 0x58017: "Ночной портрет", 0x58018: "Без смаза",
        ],
        // сверено с меню камеры: 8 и 0x21 подтвердил пользователь, 9 и 0x24 — единственные другие варианты в списке камеры
        .movieFileFormat: [8: "XAVC S 4K", 9: "XAVC S HD"],
        .movieRecordingSetting: [0x21: "25p 100M", 0x24: "25p 60M"],
        .imageSize: [1: "L", 2: "M", 3: "S"],
        .aspectRatio: [1: "3:2", 2: "16:9", 3: "4:3", 4: "1:1"],
        .fileFormat: [1: "RAW", 2: "RAW+JPEG", 3: "JPEG"],
        .jpegQuality: [1: "Экстра", 2: "Высокое", 3: "Стандарт", 4: "Низкое"],
        // сверить с камерой (Задача 9)
        .overheat: [0: "Норма", 1: "Нагрев", 2: "Перегрев"],
    ]
}
