import CoreGraphics
import Foundation

/// Фокус касанием по кадру: в зонах «Точка» и «Зона» — точка AF (0xD2DC), она двигает рамку, как джойстик камеры; в
/// остальных — касание экрана камеры (Remote Touch 0xD2E4): фокус или слежение, как настроено в камере. Координаты —
/// сетка 640×480 на весь кадр превью, какой бы ни была его форма.
public enum TouchFocus {
    public enum Method: String, Equatable, Sendable {
        /// Точка AF — в зонах «Точка» и «Зона».
        case areaPosition
        /// Касание экрана камеры.
        case remoteTouch
    }

    public static let gridWidth = 640
    public static let gridHeight = 480

    /// Зона фокуса, в которой точку AF можно двигать: зона и точка S/M/L/расширенная, они же со слежением.
    public static func movesPoint(focusArea: Int64?) -> Bool {
        guard let area = focusArea else { return false }
        return area == 0x2 || (0x101...0x104).contains(area) || area == 0x202 || (0x204...0x207).contains(area)
    }

    /// Как ставить фокус касанием сейчас; nil — никак: ручной фокус или камера этого не умеет. controls — команды
    /// 0x9207, которые камера назвала в GetExtDeviceInfo.
    public static func method(focusMode: Int64?, focusArea: Int64?, remoteTouchEnabled: Int64?, controls: Set<UInt16>) -> Method? {
        if focusMode == 1 { return nil }
        if movesPoint(focusArea: focusArea), controls.contains(SonyControl.afAreaPosition) { return .areaPosition }
        if remoteTouchEnabled == 1, controls.contains(SonyControl.remoteTouch) { return .remoteTouch }
        return nil
    }

    /// Точка кадра (доли 0…1) — значение команды: x в старших двух байтах, y в младших.
    public static func encode(x: Double, y: Double) -> Int64 {
        let gx = Int64((min(max(x, 0), 1) * Double(gridWidth - 1)).rounded())
        let gy = Int64((min(max(y, 0), 1) * Double(gridHeight - 1)).rounded())
        return gx << 16 | gy
    }

    /// Обратно: значение команды — точка кадра в долях.
    public static func decode(_ value: Int64) -> CGPoint {
        CGPoint(x: Double(value >> 16 & 0xFFFF) / Double(gridWidth - 1), y: Double(value & 0xFFFF) / Double(gridHeight - 1))
    }

    /// Что сказать, если касание фокус не ставит.
    public static func refusal(focusMode: Int64?) -> String {
        focusMode == 1 ? "В ручном фокусе — шкала фокуса внизу" : "Камера сейчас не даёт ставить фокус касанием"
    }
}

/// Зум объектива с приводом (у ZV-E10 — китовый PZ 16-50) или Clear Image Zoom камеры: пока держат T или W, камера
/// зумирует (0xD2DD = 1 или −1), отпустили — стоп (0). Где зум сейчас — по шкале 0xD25D и масштабу 0xD25C.
public enum PowerZoom {
    /// Доступен: камера разрешает зум и знает команду.
    public static func isAvailable(zoomEnabled: Int64?, controls: Set<UInt16>) -> Bool {
        zoomEnabled == 1 && controls.contains(SonyControl.zoomOperation)
    }

    /// Шкала зума 0xD25D: сколько отрезков, в каком (с 0), где в нём (0…100).
    public struct Bar: Equatable, Sendable {
        public let boxes: Int
        public let box: Int
        public let position: Int

        public init(_ value: Int64) {
            boxes = Int(value >> 24 & 0xFF)
            box = Int(value >> 16 & 0xFF)
            position = Int(value & 0xFFFF)
        }

        /// Где зум на всей шкале: 0 — самый широкий, 1 — самый длинный; nil — шкалы нет.
        public var fraction: Double? {
            guard boxes > 0 else { return nil }
            return min(max((Double(box) + Double(min(position, 100)) / 100) / Double(boxes), 0), 1)
        }
    }

    /// Масштаб 0xD25C (×1000) подписью: «×1,0», «×1,5»; nil — масштаба нет.
    public static func label(scale: Int64?) -> String? {
        guard let scale, scale > 0 else { return nil }
        return String(format: "×%.1f", Double(scale) / 1000).replacingOccurrences(of: ".", with: ",")
    }
}

/// Шкала фокуса: перефокусировка ближе или дальше шагами Near/Far (0xD2D1: −7…7) — ручной фокус с экрана. Где фокус —
/// по 0xD24C (0…100), если камера его отдаёт.
public enum FocusDrive {
    /// Шаг: маленький, средний, большой — 1, 3, 7 единиц Near/Far.
    public enum Step: Int, CaseIterable, Sendable {
        case small = 1, medium = 3, large = 7
    }

    public static func isAvailable(nearFarEnabled: Int64?, controls: Set<UInt16>) -> Bool {
        nearFarEnabled == 1 && controls.contains(SonyControl.nearFar)
    }

    /// Значение команды: ближе — отрицательное.
    public static func value(_ step: Step, nearer: Bool) -> Int64 {
        Int64(nearer ? -step.rawValue : step.rawValue)
    }

    /// Перетаскивание по шкале на долю её ширины — шаги, чтобы вся шкала была как пол-оборота кольца фокуса:
    /// около 40 больших шагов. Отрицательное — ближе. Короче шага — 0.
    public static func steps(forDrag fraction: Double) -> Int {
        Int((fraction * 280).rounded(.towardZero))
    }

    /// Разложить столько единиц Near/Far на команды не больше 7: 17 → 7, 7, 3; −2 → −1, −1.
    public static func commands(units: Int) -> [Int64] {
        var rest = abs(units)
        var out: [Int64] = []
        for step in [7, 3, 1] {
            while rest >= step {
                out.append(Int64(units < 0 ? -step : step))
                rest -= step
            }
        }
        return out
    }
}
