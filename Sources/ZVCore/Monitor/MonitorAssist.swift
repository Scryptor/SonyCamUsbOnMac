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

    /// Наложение кадра: эталонный кадр поверх живого с этой непрозрачностью (5–100 %) — повторить сцену. В настройках и
    /// API — строкой: «off» или проценты, «40»; прежние «25», «50», «75» читаются как раньше.
    public struct Overlay: RawRepresentable, Codable, Equatable, Sendable {
        /// Непрозрачность в процентах; nil — выключено.
        public let percent: Int?

        public static let off = Overlay(percent: nil)
        public static let range = 5...100

        public init(percent: Int?) {
            self.percent = percent.map { min(max($0, Self.range.lowerBound), Self.range.upperBound) }
        }

        public init?(rawValue: String) {
            if rawValue == "off" {
                percent = nil
            } else if let n = Int(rawValue), Self.range.contains(n) {
                percent = n
            } else {
                return nil
            }
        }

        public var rawValue: String { percent.map(String.init) ?? "off" }

        public var opacity: Double? { percent.map { Double($0) / 100 } }

        /// С ползунка (0…1) — с шагом 5 %, не меньше 5.
        public static func steps(_ opacity: Double) -> Overlay { Overlay(percent: Int((opacity * 20).rounded()) * 5) }
    }

    /// LUT для превью: S-Log и HLG на экране выглядят привычно. «Авто» выбирает LUT по профилю изображения камеры
    /// (какой именно — решает `LUTChoice`), следующие четыре — встроенные, «Свой» — файл .cube из `lutFile`.
    public enum LUTMode: String, Codable, CaseIterable, Sendable {
        case off, auto, slog3cine, slog3, slog2, hlg, file
    }

    public var zebra = Zebra.off
    public var falseColor = false
    public var peaking = Peaking.off
    public var peakingColor = PeakingColor.red
    public var lut = LUTMode.off
    /// Какой свой LUT выбран — имя файла без расширения; nil — ни один. Остаётся и при другом выборе, и после
    /// «Всё — выключить».
    public var lutFile: String?
    /// Помощь (гистограмма, волновая форма, вектороскоп, зебра, false color, пикинг, пипетка) считать по картинке
    /// с LUT, а не по сигналу камеры. Не плитка и не включатель: «Всё — выключить» её не трогает.
    public var lutScopes = true
    public var vectorscope = false
    /// Масштаб вектороскопа: 1 или 2. Не плитка: «Всё — выключить» его не трогает.
    public var vectorscopeZoom = 1
    public var probe = false
    public var grid = Grid.off
    public var center = false
    public var frameMarker = FrameMarker.off
    public var safeZone = SafeZone.off
    public var overlay = Overlay.off
    /// Зеркало и переворот картинки на экране — плитка «Картинка».
    public var orientation = FrameOrientation.off
    /// Последнее включённое значение каждого инструмента — к нему возвращает повторное касание выбранной плитки.
    public var lastOn: [String: String] = [:]

    public init() {}

    public static let none = MonitorAssist()

    /// Что рисуется растром поверх кадра: зебра, false color, пикинг.
    public var needsRaster: Bool { zebra != .off || falseColor || peaking != .off }

    /// Нужны ли точки кадра: растр помощи, вектороскоп или пипетка.
    public var needsPixels: Bool { needsRaster || vectorscope || probe }

    /// Что рисуется линиями поверх кадра: сетка, центр, рамка, безопасная зона.
    public var hasMarkers: Bool { grid != .off || center || frameMarker != .off || safeZone != .off }

    /// Выбраны ли LUT или зеркало с переворотом — по настройкам. Это не «картинка меняется»: «Авто» может ничего не
    /// выбрать (профиль без LUT), «Свой» — остаться без файла. Кто рисует, решает по итоговому кубу (`LUTChoice.resolve`
    /// или свой файл) и по `orientation`: нет ни куба, ни поворота — преобразования нет и оно ничего не стоит.
    public var transformsPicture: Bool { lut != .off || orientation != .off }

    /// Включено хоть что-то.
    public var isActive: Bool { needsPixels || transformsPicture || hasMarkers || overlay != .off }

    public init(from decoder: Decoder) throws {
        // новые инструменты в старых настройках — выключены; чужое значение или неверный тип — как «не записано»
        let c = try decoder.container(keyedBy: CodingKeys.self)
        zebra = (try? c.decode(Zebra.self, forKey: .zebra)) ?? .off
        falseColor = (try? c.decode(Bool.self, forKey: .falseColor)) ?? false
        peaking = (try? c.decode(Peaking.self, forKey: .peaking)) ?? .off
        peakingColor = (try? c.decode(PeakingColor.self, forKey: .peakingColor)) ?? .red
        lut = (try? c.decode(LUTMode.self, forKey: .lut)) ?? .off
        lutFile = try? c.decode(String.self, forKey: .lutFile)
        lutScopes = (try? c.decode(Bool.self, forKey: .lutScopes)) ?? true
        vectorscope = (try? c.decode(Bool.self, forKey: .vectorscope)) ?? false
        // масштаб вектороскопа — только 1 и 2
        vectorscopeZoom = (try? c.decode(Int.self, forKey: .vectorscopeZoom)) == 2 ? 2 : 1
        probe = (try? c.decode(Bool.self, forKey: .probe)) ?? false
        grid = (try? c.decode(Grid.self, forKey: .grid)) ?? .off
        center = (try? c.decode(Bool.self, forKey: .center)) ?? false
        frameMarker = (try? c.decode(FrameMarker.self, forKey: .frameMarker)) ?? .off
        safeZone = (try? c.decode(SafeZone.self, forKey: .safeZone)) ?? .off
        overlay = (try? c.decode(Overlay.self, forKey: .overlay)) ?? .off
        orientation = (try? c.decode(FrameOrientation.self, forKey: .orientation)) ?? .off
        lastOn = (try? c.decode([String: String].self, forKey: .lastOn)) ?? [:]
    }

    /// Значение инструмента строкой — как в API: «95», «on», «9:16», «slog3», «180».
    public func value(_ tool: MonitorTool) -> String {
        switch tool {
        case .zebra: return zebra.rawValue
        case .falseColor: return falseColor ? "on" : "off"
        case .peaking: return peaking.rawValue
        case .peakingColor: return peakingColor.rawValue
        case .lut: return lut.rawValue
        case .vectorscope: return vectorscope ? "on" : "off"
        case .probe: return probe ? "on" : "off"
        case .grid: return grid.rawValue
        case .center: return center ? "on" : "off"
        case .frameMarker: return frameMarker.rawValue
        case .safeZone: return safeZone.rawValue
        case .overlay: return overlay.rawValue
        case .orientation: return orientation.rawValue
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
        case .lut: guard let v = LUTMode(rawValue: value) else { return false }; lut = v
        case .vectorscope: guard let v = Self.flag(value) else { return false }; vectorscope = v
        case .probe: guard let v = Self.flag(value) else { return false }; probe = v
        case .grid: guard let v = Grid(rawValue: value) else { return false }; grid = v
        case .center: guard let v = Self.flag(value) else { return false }; center = v
        case .frameMarker: guard let v = FrameMarker(rawValue: value) else { return false }; frameMarker = v
        case .safeZone: guard let v = SafeZone(rawValue: value) else { return false }; safeZone = v
        case .overlay: guard let v = Overlay(rawValue: value) else { return false }; overlay = v
        case .orientation: guard let v = FrameOrientation(rawValue: value) else { return false }; orientation = v
        }
        if value != "off" { lastOn[tool.rawValue] = value }
        return true
    }

    /// Выбрать свой LUT: режим «Свой» и этот файл. Прежний выбранный файл забывается.
    public mutating func selectLUTFile(_ name: String) {
        set(.lut, "file")
        lutFile = name
    }

    /// Свой LUT удалили из папки. Выбранный (или просто помнившийся) файл забывается; если LUT был включён как «Свой»,
    /// он выключается, и повторное «включить» даёт «Авто», а не «Свой» без файла. Встроенный LUT, включённый поверх
    /// помнившегося файла, остаётся. Другой файл — ничего не меняется.
    public mutating func lutFileDeleted(_ name: String) {
        guard lutFile == name else { return }
        lutFile = nil
        if lut == .file { lut = .off }
        if lastOn[MonitorTool.lut.rawValue] == "file" { lastOn[MonitorTool.lut.rawValue] = nil }
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
            set(tool, lastOn[tool.rawValue].flatMap { Self.accepts(tool, $0) && $0 != "off" ? $0 : nil } ?? options[1])
        }
    }

    /// Такое значение у инструмента бывает: из списка, а у наложения — любые проценты 5–100.
    public static func accepts(_ tool: MonitorTool, _ value: String) -> Bool {
        tool == .overlay ? Overlay(rawValue: value) != nil : tool.options.contains { $0.value == value }
    }

    /// Соседнее значение — стрелки и колесо в меню «Монитор»; nil — дальше некуда. У наложения между ступенями (40 % с
    /// ползунка) — ближайшая ступень в эту сторону.
    public func stepped(_ tool: MonitorTool, by offset: Int) -> String? {
        let options = tool.options.map(\.value)
        if let i = options.firstIndex(of: value(tool)) { return options[min(max(i + offset, 0), options.count - 1)] }
        guard tool == .overlay, let percent = overlay.percent else { return nil }
        let steps = options.compactMap { Overlay(rawValue: $0)?.percent }
        if offset > 0 { return steps.first { $0 > percent }.map(String.init) }
        return steps.last { $0 < percent }.map(String.init) ?? "off"
    }

    /// Всё выключить, зеркало и LUT тоже: «всё выключено» — картинка такая, какой её даёт камера. Запомненные значения,
    /// выбранный файл LUT, помощь «по картинке с LUT» и масштаб вектороскопа остаются.
    public mutating func turnOffAll() {
        for tool in MonitorTool.allCases where tool.options.contains(where: { $0.value == "off" }) {
            set(tool, "off")
        }
    }

    /// Горит ли плитка инструмента: включён. Цвет пикинга «выкл» не бывает — он горит вместе с пикингом.
    public func isOn(_ tool: MonitorTool) -> Bool {
        tool == .peakingColor ? peaking != .off : value(tool) != "off"
    }

    /// Подпись значения на плитке: «95 %», «Выкл», «Красный»; у наложения с ползунка — «40 %»; у своего LUT — имя файла
    /// («Свой», пока файл не выбран); у картинки — короткое «Зеркало», «180°». «Авто: S-Log3» на плитке LUT складывает
    /// `LUTChoice` по профилю камеры, здесь у «Авто» — «Авто».
    public func label(_ tool: MonitorTool) -> String {
        switch tool {
        case .lut where lut == .file: return lutFile.flatMap { $0.isEmpty ? nil : $0 } ?? "Свой"
        case .orientation: return orientation.label
        default:
            let v = value(tool)
            return tool.options.first { $0.value == v }?.label ?? (tool == .overlay ? "\(v) %" : v)
        }
    }

    private static func flag(_ value: String) -> Bool? {
        switch value {
        case "on", "true", "1": return true
        case "off", "false", "0": return false
        default: return nil
        }
    }
}

/// Плитки меню «Монитор» — по порядку на экране: первая строка до «Пипетки», вторая — от «Сетки»; четырнадцатая плитка
/// «Всё — выключить» не инструмент.
public enum MonitorTool: String, CaseIterable, Sendable {
    case zebra, falseColor, peaking, peakingColor, lut, vectorscope, probe
    case grid, center, frameMarker, safeZone, overlay, orientation

    public var title: String {
        switch self {
        case .zebra: return "Зебра"
        case .falseColor: return "False color"
        case .peaking: return "Пикинг"
        case .peakingColor: return "Цвет пикинга"
        case .lut: return "LUT"
        case .vectorscope: return "Вектороскоп"
        case .probe: return "Пипетка"
        case .grid: return "Сетка"
        case .center: return "Центр"
        case .frameMarker: return "Рамка"
        case .safeZone: return "Безоп. зона"
        case .overlay: return "Наложение"
        case .orientation: return "Картинка"
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
        case .falseColor, .center, .vectorscope, .probe: return [o("off", "Выкл"), o("on", "Вкл")]
        case .peaking: return [o("off", "Выкл"), o("low", "Низкий"), o("mid", "Средний"), o("high", "Высокий")]
        case .peakingColor: return [o("red", "Красный"), o("yellow", "Жёлтый"), o("white", "Белый"), o("blue", "Синий")]
        case .lut:
            return [o("off", "Выкл"), o("auto", "Авто"), o("slog3cine", "S-Log3 Cine"), o("slog3", "S-Log3"),
                    o("slog2", "S-Log2"), o("hlg", "HLG"), o("file", "Свой")]
        case .grid: return [o("off", "Выкл"), o("thirds", "Трети"), o("squares", "Квадраты")]
        case .frameMarker:
            return [o("off", "Выкл"), o("9:16", "9:16"), o("4:5", "4:5"), o("1:1", "1:1"), o("4:3", "4:3"), o("2.39:1", "2.39:1")]
        case .safeZone: return [o("off", "Выкл"), o("90", "90 %"), o("80", "80 %")]
        case .overlay: return [o("off", "Выкл"), o("25", "25 %"), o("50", "50 %"), o("75", "75 %"), o("100", "100 %")]
        case .orientation: return FrameOrientation.allCases.map { o($0.rawValue, $0.title) }
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
        case .lut:
            return "Превью S-Log и HLG с обычным контрастом и цветом — только на экране. «Авто» берёт LUT по профилю "
                + "изображения камеры с заводскими гаммами Sony (PP7 — S-Log2, PP8 и PP9 — S-Log3, PP10 — HLG); если "
                + "профиль перенастроен, выберите LUT вручную. «Свой» — файл .cube."
        case .vectorscope:
            return "Цвета кадра на круге: направление — оттенок, расстояние от центра — насыщенность, наклонная "
                + "линия — тон кожи. Касание панели — масштаб ×2, чтобы разглядеть бледные цвета лога."
        case .probe:
            return "Касание кадра ставит пипетку; пока она включена, фокус касанием не ставится. Метка показывает "
                + "яркость в процентах, R, G, B и зону false color — среднее по квадрату 5×5 точек; её можно тащить."
        case .grid: return "Сетка по третям или квадратами — для композиции."
        case .center: return "Крестик в центре кадра."
        case .frameMarker: return "Рамка другого формата: что войдёт в вертикальное видео (9:16), пост (4:5, 1:1) или кино (2.39:1)."
        case .safeZone: return "Безопасная зона: 90 % или 80 % кадра (или рамки) — внутри неё надписи не обрежутся."
        case .overlay:
            return "Эталонный кадр поверх живого — повторить сцену. Эталон — текущий кадр, картинка из файла или снимок "
                + "из галереи; прозрачность и «спрятать» — на панели наложения."
        case .orientation:
            return "Только на экране: «Зеркально» — как в зеркале, чтобы снимать себя; «Вверх ногами» — камера на риге "
                + "или подвесе перевёрнута. Снимки, файлы и сама камера остаются как есть."
        }
    }
}
