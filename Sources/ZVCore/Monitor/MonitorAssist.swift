import Foundation

/// Помощь в кадре, как у мониторов: зебра, false color, пикинг и маркеры. Всё считается и рисуется по картинке превью
/// на экране программы — камера об этом не знает, и на запись это не влияет.
public struct MonitorAssist: Codable, Equatable, Sendable {
    /// Зебра: полосы там, где яркость выше порога (100 % и 95 % — пересвет) или в полосе кожи (70 % ± 5).
    public enum Zebra: String, Codable, CaseIterable, Sendable {
        case off, above100 = "100", above95 = "95", skin70 = "70"
    }

    /// Пикинг — подсветка резких краёв; уровень — как у камеры: чем выше, тем больше краёв подсвечено.
    public enum Peaking: String, Codable, CaseIterable, Sendable {
        case off, low, mid, high
    }

    public enum PeakingColor: String, Codable, CaseIterable, Sendable {
        case red, yellow, white, blue
    }

    public enum Grid: String, Codable, CaseIterable, Sendable {
        case off, thirds, squares
    }

    /// Рамка кадра другого формата: что войдёт в вертикальное видео, пост, кино.
    public enum FrameMarker: String, Codable, CaseIterable, Sendable {
        case off, vertical = "9:16", portrait = "4:5", square = "1:1", classic = "4:3", scope = "2.39:1"

        /// Ширина к высоте; nil — рамки нет.
        public var aspect: Double? {
            switch self {
            case .off: return nil
            case .vertical: return 9.0 / 16
            case .portrait: return 4.0 / 5
            case .square: return 1
            case .classic: return 4.0 / 3
            case .scope: return 2.39
            }
        }
    }

    /// Безопасная зона: доля кадра (или рамки), куда помещают надписи и главное.
    public enum SafeZone: String, Codable, CaseIterable, Sendable {
        case off, p90 = "90", p80 = "80"

        public var fraction: Double? {
            switch self {
            case .off: return nil
            case .p90: return 0.9
            case .p80: return 0.8
            }
        }
    }

    /// Наложение кадра: эталонный кадр поверх живого с этой непрозрачностью — повторить композицию.
    public enum Overlay: String, Codable, CaseIterable, Sendable {
        case off, p25 = "25", p50 = "50", p75 = "75"

        public var opacity: Double? {
            switch self {
            case .off: return nil
            case .p25: return 0.25
            case .p50: return 0.5
            case .p75: return 0.75
            }
        }
    }

    public var zebra = Zebra.off
    public var falseColor = false
    public var peaking = Peaking.off
    public var peakingColor = PeakingColor.red
    public var grid = Grid.off
    public var center = false
    public var frameMarker = FrameMarker.off
    public var safeZone = SafeZone.off
    public var overlay = Overlay.off
    /// Последнее включённое значение каждого инструмента — к нему возвращает повторное касание выбранной плитки.
    public var lastOn: [String: String] = [:]

    public init() {}

    public static let none = MonitorAssist()

    /// Что считается по точкам кадра: зебра, false color, пикинг.
    public var needsRaster: Bool { zebra != .off || falseColor || peaking != .off }

    /// Что рисуется линиями поверх кадра: сетка, центр, рамка, безопасная зона.
    public var hasMarkers: Bool { grid != .off || center || frameMarker != .off || safeZone != .off }

    /// Включено хоть что-то.
    public var isActive: Bool { needsRaster || hasMarkers || overlay != .off }

    public init(from decoder: Decoder) throws {
        // новые инструменты в старых настройках — выключены
        let c = try decoder.container(keyedBy: CodingKeys.self)
        zebra = (try? c.decode(Zebra.self, forKey: .zebra)) ?? .off
        falseColor = (try? c.decode(Bool.self, forKey: .falseColor)) ?? false
        peaking = (try? c.decode(Peaking.self, forKey: .peaking)) ?? .off
        peakingColor = (try? c.decode(PeakingColor.self, forKey: .peakingColor)) ?? .red
        grid = (try? c.decode(Grid.self, forKey: .grid)) ?? .off
        center = (try? c.decode(Bool.self, forKey: .center)) ?? false
        frameMarker = (try? c.decode(FrameMarker.self, forKey: .frameMarker)) ?? .off
        safeZone = (try? c.decode(SafeZone.self, forKey: .safeZone)) ?? .off
        overlay = (try? c.decode(Overlay.self, forKey: .overlay)) ?? .off
        lastOn = (try? c.decode([String: String].self, forKey: .lastOn)) ?? [:]
    }

    /// Значение инструмента строкой — как в API: «95», «on», «9:16».
    public func value(_ tool: MonitorTool) -> String {
        switch tool {
        case .zebra: return zebra.rawValue
        case .falseColor: return falseColor ? "on" : "off"
        case .peaking: return peaking.rawValue
        case .peakingColor: return peakingColor.rawValue
        case .grid: return grid.rawValue
        case .center: return center ? "on" : "off"
        case .frameMarker: return frameMarker.rawValue
        case .safeZone: return safeZone.rawValue
        case .overlay: return overlay.rawValue
        }
    }

    /// Поставить значение; false — такого значения у инструмента нет.
    @discardableResult
    public mutating func set(_ tool: MonitorTool, _ value: String) -> Bool {
        switch tool {
        case .zebra: guard let v = Zebra(rawValue: value) else { return false }; zebra = v
        case .falseColor: guard let v = Self.flag(value) else { return false }; falseColor = v
        case .peaking: guard let v = Peaking(rawValue: value) else { return false }; peaking = v
        case .peakingColor: guard let v = PeakingColor(rawValue: value) else { return false }; peakingColor = v
        case .grid: guard let v = Grid(rawValue: value) else { return false }; grid = v
        case .center: guard let v = Self.flag(value) else { return false }; center = v
        case .frameMarker: guard let v = FrameMarker(rawValue: value) else { return false }; frameMarker = v
        case .safeZone: guard let v = SafeZone(rawValue: value) else { return false }; safeZone = v
        case .overlay: guard let v = Overlay(rawValue: value) else { return false }; overlay = v
        }
        if value != "off" { lastOn[tool.rawValue] = value }
        return true
    }

    /// Выключить, если включён, иначе вернуть последнее включённое значение (или первое после «Выкл»).
    /// У цвета пикинга «выкл» нет — он идёт по кругу.
    public mutating func toggle(_ tool: MonitorTool) {
        let options = tool.options.map(\.value)
        guard options.contains("off") else {
            let i = options.firstIndex(of: value(tool)) ?? 0
            set(tool, options[(i + 1) % options.count])
            return
        }
        if value(tool) != "off" {
            set(tool, "off")
        } else {
            set(tool, lastOn[tool.rawValue].flatMap { options.contains($0) ? $0 : nil } ?? options[1])
        }
    }

    /// Всё выключить; запомненные значения остаются.
    public mutating func turnOffAll() {
        for tool in MonitorTool.allCases where tool.options.contains(where: { $0.value == "off" }) {
            set(tool, "off")
        }
    }

    /// Горит ли плитка инструмента: включён. Цвет пикинга «выкл» не бывает — он горит вместе с пикингом.
    public func isOn(_ tool: MonitorTool) -> Bool {
        tool == .peakingColor ? peaking != .off : value(tool) != "off"
    }

    /// Подпись значения на плитке: «95 %», «Выкл», «Красный».
    public func label(_ tool: MonitorTool) -> String {
        let v = value(tool)
        return tool.options.first { $0.value == v }?.label ?? v
    }

    private static func flag(_ value: String) -> Bool? {
        switch value {
        case "on", "true", "1": return true
        case "off", "false", "0": return false
        default: return nil
        }
    }
}

/// Плитки меню «Монитор» — по порядку на экране.
public enum MonitorTool: String, CaseIterable, Sendable {
    case zebra, falseColor, peaking, peakingColor, grid, center, frameMarker, safeZone, overlay

    public var title: String {
        switch self {
        case .zebra: return "Зебра"
        case .falseColor: return "False color"
        case .peaking: return "Пикинг"
        case .peakingColor: return "Цвет пикинга"
        case .grid: return "Сетка"
        case .center: return "Центр"
        case .frameMarker: return "Рамка"
        case .safeZone: return "Безоп. зона"
        case .overlay: return "Наложение"
        }
    }

    public struct Option: Equatable, Sendable {
        public let value: String
        public let label: String
    }

    public var options: [Option] {
        func o(_ value: String, _ label: String) -> Option { Option(value: value, label: label) }
        switch self {
        case .zebra: return [o("off", "Выкл"), o("100", "100 %"), o("95", "95 %"), o("70", "70 % кожа")]
        case .falseColor, .center: return [o("off", "Выкл"), o("on", "Вкл")]
        case .peaking: return [o("off", "Выкл"), o("low", "Низкий"), o("mid", "Средний"), o("high", "Высокий")]
        case .peakingColor: return [o("red", "Красный"), o("yellow", "Жёлтый"), o("white", "Белый"), o("blue", "Синий")]
        case .grid: return [o("off", "Выкл"), o("thirds", "Трети"), o("squares", "Квадраты")]
        case .frameMarker:
            return [o("off", "Выкл"), o("9:16", "9:16"), o("4:5", "4:5"), o("1:1", "1:1"), o("4:3", "4:3"), o("2.39:1", "2.39:1")]
        case .safeZone: return [o("off", "Выкл"), o("90", "90 %"), o("80", "80 %")]
        case .overlay: return [o("off", "Выкл"), o("25", "25 %"), o("50", "50 %"), o("75", "75 %")]
        }
    }

    /// Что делает — подсказка к плитке.
    public var help: String {
        switch self {
        case .zebra:
            return "Полосы там, где кадр ярче порога: 100 % и 95 % — пересвет и почти пересвет, 70 % — светлая кожа (70 ± 5 %)."
        case .falseColor:
            return "Кадр в условных цветах по яркости: фиолетовый — провал, синий — глубокие тени, зелёный — средне-серый, "
                + "розовый — кожа, жёлтый — почти белое, красный — пересвет; остальное — серым."
        case .peaking: return "Подсветка резких краёв — где фокус. С увеличением кадра работает точнее."
        case .peakingColor: return "Цвет подсветки резких краёв."
        case .grid: return "Сетка по третям или квадратами — для композиции."
        case .center: return "Крестик в центре кадра."
        case .frameMarker: return "Рамка другого формата: что войдёт в вертикальное видео (9:16), пост (4:5, 1:1) или кино (2.39:1)."
        case .safeZone: return "Безопасная зона: 90 % или 80 % кадра (или рамки) — внутри неё надписи не обрежутся."
        case .overlay:
            return "Эталонный кадр полупрозрачно поверх живого — повторить композицию. Эталон — текущий кадр («Новый кадр») "
                + "или снимок из галереи («Наложить на кадр»)."
        }
    }
}
