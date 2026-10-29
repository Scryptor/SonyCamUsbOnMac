import CoreGraphics

/// Зеркало и переворот картинки на экране: камера об этом не знает. Меняется то, что видно: сам кадр, помощь, рамки
/// фокуса и касание; снимки, файлы и `GET /frame` остаются как у камеры.
///
/// Координаты — доли кадра 0…1, поэтому формат кадра (16:9, 3:2, 1:1) значения не имеет. Все четыре отображения —
/// инволюции (дважды — и всё как было), так что перевод из экрана в камеру и из камеры в экран — одно и то же.
public enum FrameOrientation: String, Codable, CaseIterable, Sendable {
    /// Как есть; «зеркало»; «вверх ногами» (поворот на 180°); «зеркало + 180°». В API и настройках — «off», «mirror»,
    /// «180», «mirror180».
    case off, mirror, rotate180 = "180", mirror180

    /// Слева направо переворачивают зеркало и 180°.
    public var flipsX: Bool { self == .mirror || self == .rotate180 }

    /// Сверху вниз переворачивают 180° и зеркало + 180° (зеркало поверх 180° возвращает x на место).
    public var flipsY: Bool { self == .rotate180 || self == .mirror180 }

    /// Точка на экране (доли кадра, каким он виден) → та же точка в кадре камеры: куда ставить фокус и пипетку.
    public func toCamera(_ p: CGPoint) -> CGPoint {
        CGPoint(x: flipsX ? 1 - p.x : p.x, y: flipsY ? 1 - p.y : p.y)
    }

    /// Точка в кадре камеры → на экране (метка касания, центр рамки фокуса). Отображение — инволюция, поэтому то же,
    /// что `toCamera`.
    public func fromCamera(_ p: CGPoint) -> CGPoint { toCamera(p) }

    /// Прямоугольник в кадре камеры (рамка фокуса) → на экране: уезжает на место, размер тот же — поворота на 90° нет.
    public func fromCamera(_ r: CGRect) -> CGRect {
        CGRect(x: flipsX ? 1 - r.maxX : r.minX, y: flipsY ? 1 - r.maxY : r.minY, width: r.width, height: r.height)
    }

    /// Вариант в полосе над плитками «Картинка».
    public var title: String {
        switch self {
        case .off: return "Как есть"
        case .mirror: return "Зеркально"
        case .rotate180: return "Вверх ногами"
        case .mirror180: return "Зеркально + 180°"
        }
    }

    /// Значение на самой плитке «Картинка».
    public var label: String {
        switch self {
        case .off: return "—"
        case .mirror: return "Зеркало"
        case .rotate180: return "180°"
        case .mirror180: return "Зеркало 180°"
        }
    }
}
