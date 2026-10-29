import CoreGraphics

/// Пипетка на экране: где стоит метка, где рядом с ней табличка и куда уходит точка, когда метку тащат. Чистая геометрия
/// без SwiftUI — вид только рисует по ней, а края и увеличение проверяются тестами.
///
/// Точка пипетки живёт в долях всего кадра, каким он виден на экране (зеркальный или перевёрнутый — как есть; увеличение
/// на доли не влияет: это доли кадра, а не видимой части). Ей, как и увеличению, не нужен перевод в координаты камеры.
public enum ProbeLayout {
    /// Диаметр метки, pt.
    public static let markerDiameter: CGFloat = 20
    /// Просвет между меткой и табличкой, pt.
    public static let gap: CGFloat = 6
    /// Ближе этого к краю области табличка не встаёт, pt.
    public static let margin: CGFloat = 4
    /// Метку берёт жест, начатый не дальше этого от её середины, pt: чуть шире самого кольца, чтобы попасть мышью.
    public static let grabRadius: CGFloat = 16

    /// Где метка на экране: точка кадра (доли) при кадре, лежащем на экране в `rect` (с увеличением — он больше экрана,
    /// и метка может оказаться за его краем).
    public static func markerCenter(_ fraction: CGPoint, in rect: CGRect) -> CGPoint {
        CGPoint(x: rect.minX + fraction.x * rect.width, y: rect.minY + fraction.y * rect.height)
    }

    /// Левый верхний угол таблички (`plate` — её размер) при метке с центром `marker`; `area` — где табличка может стоять
    /// (видимая часть кадра). Обычно она справа от метки, по её высоте посередине. У правого края, где справа места нет, —
    /// слева от метки; у нижнего табличка поднимается ровно настолько, чтобы уместиться (у верхнего — опускается). Сторона
    /// остаётся той же, поэтому метка всегда рядом с табличкой, а не под ней. Табличка не заходит за область ближе, чем на
    /// `margin`, и не ложится на метку, пока по ширине помещается хоть с одной стороны; в области уже таблички она стоит у
    /// левого края (у верхнего — если не хватает высоты).
    public static func plateOrigin(marker: CGPoint, plate: CGSize, in area: CGRect) -> CGPoint {
        // метки нет под рукой (не-число) — считаем её серединой области: табличка всё равно остаётся в области
        let center = CGPoint(x: marker.x.isFinite ? marker.x : area.midX, y: marker.y.isFinite ? marker.y : area.midY)
        let reach = markerDiameter / 2 + gap
        let right = center.x + reach
        let left = center.x - reach - plate.width
        let fitsRight = right + plate.width <= area.maxX - margin
        let fitsLeft = left >= area.minX + margin
        let x: CGFloat
        if fitsRight {
            x = right
        } else if fitsLeft {
            x = left
        } else {
            // негде с обеих сторон: туда, где больше места
            x = area.maxX - center.x >= center.x - area.minX ? right : left
        }
        return CGPoint(x: clamp(x, from: area.minX + margin, to: area.maxX - margin - plate.width),
                       y: clamp(center.y - plate.height / 2, from: area.minY + margin, to: area.maxY - margin - plate.height))
    }

    /// Метку тащат: точка кадра (доли), в которую она встала, когда её сдвинули на `translation` pt от точки `start`, с кадром
    /// на экране в `rect`. Не дальше видимой части кадра (`visible`, доли): метка не уходит за экран и не теряется под
    /// увеличением. Нет кадра или сдвиг не число — точка остаётся где была.
    public static func dragged(from start: CGPoint, by translation: CGSize, rect: CGRect, visible: CGRect) -> CGPoint {
        guard rect.width > 0, rect.height > 0, translation.width.isFinite, translation.height.isFinite else { return start }
        return CGPoint(x: clamp(start.x + translation.width / rect.width, from: visible.minX, to: visible.maxX),
                       y: clamp(start.y + translation.height / rect.height, from: visible.minY, to: visible.maxY))
    }

    /// Жест начат на метке (тянет её, а не картинку): точка начала не дальше `grabRadius` от середины метки.
    public static func grabs(_ start: CGPoint, marker: CGPoint) -> Bool {
        let dx = start.x - marker.x, dy = start.y - marker.y
        return dx.isFinite && dy.isFinite && dx * dx + dy * dy <= grabRadius * grabRadius
    }

    /// Точка кадра (доли) в пределах кадра; nil — не число: такую точку не ставят.
    public static func clamped(_ point: CGPoint) -> CGPoint? {
        guard point.x.isFinite, point.y.isFinite else { return nil }
        return CGPoint(x: min(max(point.x, 0), 1), y: min(max(point.y, 0), 1))
    }

    /// Значение в пределах; где верхний предел ниже нижнего (табличка больше области) — нижний.
    private static func clamp(_ value: CGFloat, from low: CGFloat, to high: CGFloat) -> CGFloat {
        min(max(value, low), max(low, high))
    }
}
