import Foundation

/// Колесо мыши/трекпад → шаги по ленте значений. Считаем только вертикальную прокрутку над лентой,
/// без инерции — иначе прокрутка «мимо» меняла бы настройки камеры.
public struct ScrollStepper {
    private let threshold: Double
    private var accumulated: Double = 0

    public init(threshold: Double = 20) {
        self.threshold = threshold
    }

    /// Возвращает число шагов: отрицательная dy (прокрутка вниз) — вперёд по списку.
    public mutating func feed(dx: Double, dy: Double, precise: Bool, momentum: Bool, inside: Bool) -> Int {
        guard inside, !momentum, abs(dy) > abs(dx) else {
            accumulated = 0
            return 0
        }
        accumulated += precise ? dy : dy * threshold
        var steps = 0
        while accumulated <= -threshold {
            accumulated += threshold
            steps += 1
        }
        while accumulated >= threshold {
            accumulated -= threshold
            steps -= 1
        }
        return steps
    }
}

/// Квадрат сдвига баланса белого: какие оси записывать после перетаскивания.
public enum ShiftPad {
    public struct Axes: Equatable, Sendable {
        public let ab: Bool
        public let gm: Bool

        public init(ab: Bool, gm: Bool) {
            self.ab = ab
            self.gm = gm
        }
    }

    /// Пишем доминирующую ось; обе — только при явно диагональном движении. Клик без движения — ничего.
    public static func axes(dx: Double, dy: Double) -> Axes {
        let x = abs(dx), y = abs(dy)
        if max(x, y) < 2 { return Axes(ab: false, gm: false) }
        if x >= 2 * y { return Axes(ab: true, gm: false) }
        if y >= 2 * x { return Axes(ab: false, gm: true) }
        return Axes(ab: true, gm: true)
    }
}
