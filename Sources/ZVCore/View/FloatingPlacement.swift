import CoreGraphics
import Foundation

/// Где стоит перетаскиваемая панель (гистограмма): доли свободного места по ширине и высоте. Свободное место —
/// от отступа 16 до края экрана минус панель и отступ, поэтому место переживает поворот и смену размера окна, а
/// прилипшая к краю панель у края и остаётся.
public struct FloatingPlacement: Equatable, Sendable, Codable {
    public static let margin = 16.0
    /// Отпустили ближе к отступу — прилипает к нему.
    public static let snapDistance = 24.0

    /// 0 — у левого (верхнего) отступа, 1 — у правого (нижнего).
    public var x: Double
    public var y: Double

    public init(x: Double, y: Double) {
        self.x = x
        self.y = y
    }

    /// Левый верхний угол панели размером `panel` на экране `view`.
    public func origin(panel: CGSize, view: CGSize) -> CGPoint {
        CGPoint(x: Self.position(x, panel: panel.width, view: view.width),
                y: Self.position(y, panel: panel.height, view: view.height))
    }

    /// Панель отпустили с левым верхним углом в `origin`: в пределах отступов, у отступа — прилипает.
    public static func dropped(at origin: CGPoint, panel: CGSize, view: CGSize) -> FloatingPlacement {
        FloatingPlacement(x: fraction(origin.x, panel: panel.width, view: view.width),
                          y: fraction(origin.y, panel: panel.height, view: view.height))
    }

    /// Пока тащат: не дальше краёв экрана.
    public static func dragOrigin(_ origin: CGPoint, panel: CGSize, view: CGSize) -> CGPoint {
        CGPoint(x: min(max(origin.x, 0), max(view.width - panel.width, 0)),
                y: min(max(origin.y, 0), max(view.height - panel.height, 0)))
    }

    private static func position(_ fraction: Double, panel: CGFloat, view: CGFloat) -> CGFloat {
        let free = view - panel - 2 * margin
        guard free > 0 else { return margin }
        return margin + free * min(max(fraction, 0), 1)
    }

    private static func fraction(_ origin: CGFloat, panel: CGFloat, view: CGFloat) -> Double {
        let free = view - panel - 2 * margin
        guard free > 0 else { return 0 }
        let inside = min(max(origin, margin), margin + free)
        if inside - margin < snapDistance { return 0 }
        if margin + free - inside < snapDistance { return 1 }
        return (inside - margin) / free
    }
}
