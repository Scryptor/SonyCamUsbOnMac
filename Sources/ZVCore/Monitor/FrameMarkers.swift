import CoreGraphics
import Foundation

/// Маркеры поверх кадра: рамка другого формата, безопасная зона, сетка и центр. Всё — в долях кадра (0…1), поэтому
/// маркеры едут вместе с цифровым увеличением и подходят к кадру любой формы (в фоторежиме он бывает и квадратным).
public enum FrameMarkers {
    /// Отрезок в долях кадра.
    public struct Line: Equatable, Sendable {
        public let from: CGPoint
        public let to: CGPoint

        public init(from: CGPoint, to: CGPoint) {
            self.from = from
            self.to = to
        }
    }

    /// Что рисовать.
    public struct Layout: Equatable, Sendable {
        /// Рамка другого формата; nil — нет.
        public var marker: CGRect?
        /// Безопасная зона — внутри рамки, а без неё — внутри кадра; nil — нет.
        public var safe: CGRect?
        /// Линии сетки — внутри рамки, а без неё — по всему кадру.
        public var grid: [Line]
        /// Центр кадра; nil — крестика нет.
        public var center: CGPoint?
    }

    public static func layout(_ assist: MonitorAssist, frame: CGSize) -> Layout {
        let marker = assist.frameMarker.aspect.flatMap { markerRect(aspect: $0, frame: frame) }
        let area = marker ?? CGRect(x: 0, y: 0, width: 1, height: 1)
        let safe = assist.safeZone.fraction.map { inset(area, keeping: $0) }
        return Layout(marker: marker, safe: safe, grid: grid(assist.grid, in: area, frame: frame),
                      center: assist.center ? CGPoint(x: 0.5, y: 0.5) : nil)
    }

    /// Самая большая рамка с такими пропорциями (ширина к высоте) по центру кадра; nil — кадр и так такой.
    public static func markerRect(aspect: Double, frame: CGSize) -> CGRect? {
        guard aspect > 0, frame.width > 0, frame.height > 0 else { return nil }
        let frameAspect = Double(frame.width / frame.height)
        if abs(frameAspect - aspect) < 0.005 { return nil }
        if aspect < frameAspect {
            // уже кадра — по высоте целиком
            let w = aspect / frameAspect
            return CGRect(x: (1 - w) / 2, y: 0, width: w, height: 1)
        }
        let h = frameAspect / aspect
        return CGRect(x: 0, y: (1 - h) / 2, width: 1, height: h)
    }

    /// Та же рамка, уменьшенная до доли `keeping` по каждой стороне, по центру.
    public static func inset(_ rect: CGRect, keeping: Double) -> CGRect {
        let dx = rect.width * (1 - keeping) / 2, dy = rect.height * (1 - keeping) / 2
        return rect.insetBy(dx: dx, dy: dy)
    }

    /// Трети — по две линии поперёк и вдоль. Квадраты — четыре ряда по высоте и столбцы той же ширины от центра.
    public static func grid(_ grid: MonitorAssist.Grid, in area: CGRect, frame: CGSize) -> [Line] {
        switch grid {
        case .off:
            return []
        case .thirds:
            return [1.0 / 3, 2.0 / 3].flatMap { f -> [Line] in
                let x = area.minX + area.width * f, y = area.minY + area.height * f
                return [Line(from: CGPoint(x: x, y: area.minY), to: CGPoint(x: x, y: area.maxY)),
                        Line(from: CGPoint(x: area.minX, y: y), to: CGPoint(x: area.maxX, y: y))]
            }
        case .squares:
            guard frame.width > 0, frame.height > 0 else { return [] }
            // сторона квадрата — четверть высоты; в долях ширины кадра она другая
            let side = area.height / 4
            let sideX = side * frame.height / frame.width
            var lines: [Line] = []
            for i in 1..<4 {
                let y = area.minY + side * CGFloat(i)
                lines.append(Line(from: CGPoint(x: area.minX, y: y), to: CGPoint(x: area.maxX, y: y)))
            }
            let mid = area.midX
            lines.append(Line(from: CGPoint(x: mid, y: area.minY), to: CGPoint(x: mid, y: area.maxY)))
            var k: CGFloat = 1
            while mid + sideX * k < area.maxX - 0.001 {
                for x in [mid - sideX * k, mid + sideX * k] {
                    lines.append(Line(from: CGPoint(x: x, y: area.minY), to: CGPoint(x: x, y: area.maxY)))
                }
                k += 1
            }
            return lines
        }
    }
}
