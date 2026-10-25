import CoreGraphics
import Foundation

/// Цифровое увеличение live view на экране программы: камера о нём не знает. Хранится как множитель и точка кадра в
/// центре экрана (доли 0…1), поэтому переживает смену размера окна, поворот и смену качества превью. Кадр всегда
/// закрывает экран по той оси, где он больше экрана; по меньшей оси стоит по центру.
public struct ViewZoom: Equatable, Sendable, Codable {
    public static let maxFactor = 8.0
    /// Отпустили ниже — возвращаемся к 1×.
    public static let snapBelow = 1.05
    /// Пределы во время жеста: за 1× и 8× — с сопротивлением.
    static let elasticMin = 0.87
    static let elasticMax = 8.6
    /// Ступени ⌘+ и ⌘−.
    static let steps: [Double] = [1, 1.5, 2, 3, 4, 6, 8]

    public static let identity = ViewZoom(factor: 1, center: CGPoint(x: 0.5, y: 0.5))

    /// 1 — кадр вписан в экран.
    public var factor: Double
    /// Точка кадра в центре экрана, доли.
    public var center: CGPoint

    public init(factor: Double, center: CGPoint) {
        self.factor = factor
        self.center = center
    }

    public var isZoomed: Bool { factor > 1.0001 }

    /// «2×», «2,4×».
    public var label: String {
        let whole = factor.rounded()
        if abs(factor - whole) < 0.05 { return "\(Int(whole))×" }
        return String(format: "%.1f×", factor).replacingOccurrences(of: ".", with: ",")
    }

    /// Где кадр на экране; центр — уже в пределах этого экрана.
    public func imageRect(view: CGSize, frame: CGSize) -> CGRect {
        let size = displayedSize(view: view, frame: frame)
        let c = clamped(view: view, frame: frame).center
        return CGRect(x: view.width / 2 - c.x * size.width, y: view.height / 2 - c.y * size.height,
                      width: size.width, height: size.height)
    }

    /// Увеличить в k раз так, чтобы точка кадра под `point` осталась под ним. elastic — во время жеста: за пределами
    /// 1× и 8× — с сопротивлением, до 0,87× и 8,6×.
    public func zoomed(by k: Double, around point: CGPoint, view: CGSize, frame: CGSize, elastic: Bool) -> ViewZoom {
        let rect = imageRect(view: view, frame: frame)
        guard k > 0, rect.width > 0, rect.height > 0 else { return self }
        let next = elastic ? Self.resisted(factor, by: k) : min(max(factor * k, 1), Self.maxFactor)
        let under = CGPoint(x: (point.x - rect.minX) / rect.width, y: (point.y - rect.minY) / rect.height)
        let size = ViewZoom(factor: next, center: center).displayedSize(view: view, frame: frame)
        let origin = CGPoint(x: point.x - under.x * size.width, y: point.y - under.y * size.height)
        let c = CGPoint(x: (view.width / 2 - origin.x) / size.width, y: (view.height / 2 - origin.y) / size.height)
        return ViewZoom(factor: next, center: c).clamped(view: view, frame: frame)
    }

    /// Сдвинуть картинку на смещение пальца или мыши.
    public func panned(by delta: CGSize, view: CGSize, frame: CGSize) -> ViewZoom {
        let size = displayedSize(view: view, frame: frame)
        guard size.width > 0, size.height > 0 else { return self }
        let base = clamped(view: view, frame: frame).center
        return ViewZoom(factor: factor, center: CGPoint(x: base.x - delta.width / size.width,
                                                        y: base.y - delta.height / size.height))
            .clamped(view: view, frame: frame)
    }

    /// После жеста: в пределах 1…8, ниже 1,05× — к 1×.
    public func settled(view: CGSize, frame: CGSize) -> ViewZoom {
        guard factor >= Self.snapBelow else { return .identity }
        return ViewZoom(factor: min(factor, Self.maxFactor), center: center).clamped(view: view, frame: frame)
    }

    /// Поставить в центр экрана точку кадра (доли) — по касанию мини-карты.
    public func centered(at point: CGPoint, view: CGSize, frame: CGSize) -> ViewZoom {
        ViewZoom(factor: factor, center: point).clamped(view: view, frame: frame)
    }

    /// Видимая часть кадра, доли.
    public func visibleRegion(view: CGSize, frame: CGSize) -> CGRect {
        let rect = imageRect(view: view, frame: frame)
        guard rect.width > 0, rect.height > 0 else { return CGRect(x: 0, y: 0, width: 1, height: 1) }
        let x0 = max(0, -rect.minX / rect.width), x1 = min(1, (view.width - rect.minX) / rect.width)
        let y0 = max(0, -rect.minY / rect.height), y1 = min(1, (view.height - rect.minY) / rect.height)
        return CGRect(x: x0, y: y0, width: x1 - x0, height: y1 - y0)
    }

    /// Центр в пределах экрана: по оси, где кадр больше экрана, края кадра не заходят внутрь.
    public func clamped(view: CGSize, frame: CGSize) -> ViewZoom {
        let size = displayedSize(view: view, frame: frame)
        func clamp(_ c: CGFloat, _ view: CGFloat, _ image: CGFloat) -> CGFloat {
            guard image > view else { return 0.5 }
            let half = view / 2 / image
            return min(max(c, half), 1 - half)
        }
        return ViewZoom(factor: factor, center: CGPoint(x: clamp(center.x, view.width, size.width),
                                                        y: clamp(center.y, view.height, size.height)))
    }

    /// Следующая ступень для ⌘+ / ⌘−.
    public static func step(from factor: Double, up: Bool) -> Double {
        up ? steps.first { $0 > factor + 0.01 } ?? maxFactor : steps.last { $0 < factor - 0.01 } ?? 1
    }

    private func displayedSize(view: CGSize, frame: CGSize) -> CGSize {
        guard frame.width > 0, frame.height > 0 else { return .zero }
        let fit = min(view.width / frame.width, view.height / frame.height) * factor
        return CGSize(width: frame.width * fit, height: frame.height * fit)
    }

    /// Множитель после шага k во время жеста: до предела — как есть, за ним — k^0,3; обратно — без сопротивления.
    private static func resisted(_ factor: Double, by k: Double) -> Double {
        var f = factor, rest = k
        if rest > 1, f < maxFactor {
            let inside = min(rest, maxFactor / f)
            // дошли до предела — ровно предел: 7,999999999999999 пропустил бы сопротивление
            f = inside == maxFactor / f ? maxFactor : f * inside
            rest /= inside
        } else if rest < 1, f > 1 {
            let inside = max(rest, 1 / f)
            f = inside == 1 / f ? 1 : f * inside
            rest /= inside
        }
        f *= (rest > 1 && f >= maxFactor) || (rest < 1 && f <= 1) ? pow(rest, 0.3) : rest
        return min(max(f, elasticMin), elasticMax)
    }
}
