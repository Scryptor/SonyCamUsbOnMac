import Foundation

// Расстояния на шкале фокуса (спека §7.4). Данные объектива (LensInformation) говорят, какое расстояние при каком положении
// фокуса; здесь они ложатся на линейку шкалы: расстояние для места на ней, подписи под делениями, которые не налезают друг на
// друга, и положение метки по свойствам камеры.

extension LensInformation {
    /// Вся шкала таблицы: 65535, если положения в ней в шкале 0xE043, и 100, если в шкале 0xD24C. Какая из двух, таблица не
    /// говорит — это догадка `absoluteScale` по наибольшему положению.
    public var positionScale: Double { absoluteScale ? 65535 : 100 }

    /// Расстояние в метрах для места на линейке — доли шкалы фокуса 0…1. Долю надо перевести в положение, как его записала
    /// таблица: умножить на 65535 или на 100; за краями линейки — ближний или дальний край таблицы. nil — расстояния нет:
    /// таблиц больше одной, таблицы нет или доля не число.
    public func meters(atFraction fraction: Double) -> Double? {
        guard !fraction.isNaN else { return nil }
        return meters(atPosition: min(max(fraction, 0), 1) * positionScale)
    }

    /// То же подписью для шкалы фокуса («1,2 м», «∞»).
    public func label(atFraction fraction: Double) -> String? {
        meters(atFraction: fraction).map(Self.label)
    }

    /// Доля шкалы 0…1 с таким расстоянием: линейная интерполяция в обратную сторону по первому отрезку таблицы, где оно лежит.
    /// nil — таблица не одна или такого расстояния в ней нет.
    func fraction(atMeters target: Double) -> Double? {
        guard tableCount == 1, let first = pairs.first else { return nil }
        if pairs.count == 1 { return first.meters == target ? Self.share(first.position, of: positionScale) : nil }
        for (a, b) in zip(pairs, pairs.dropFirst()) where a.position < b.position && a.meters != b.meters {
            guard (min(a.meters, b.meters)...max(a.meters, b.meters)).contains(target) else { continue }
            let position = a.position + (target - a.meters) / (b.meters - a.meters) * (b.position - a.position)
            return Self.share(position, of: positionScale)
        }
        return nil
    }

    fileprivate static func share(_ position: Double, of scale: Double) -> Double {
        min(max(position / scale, 0), 1)
    }
}

extension FocusPosition {
    /// Где фокус на шкале, доля 0…1: по 0xE043 (0…65535), если камера его отдаёт, иначе по 0xD24C (0…100). Положения нет — nil.
    public static func fraction(_ properties: [UInt16: SonyProperty]) -> Double? {
        for mode in [FocusPull.Mode.absolute, .steps] {
            if let position = current(properties, mode: mode) { return fraction(of: position, mode: mode) }
        }
        return nil
    }
}

/// Подписи расстояний под линейкой шкалы фокуса.
public enum FocusDistanceScale {
    /// Подпись под линейкой: где (доля 0…1) и что написано.
    public struct Tick: Equatable, Sendable {
        public let fraction: Double
        public let label: String
    }

    /// Круглые расстояния, метры — по важности: если места мало, подписывается то, что раньше в списке.
    private static let roundDistances: [Double] = [1, 0.5, 2, 5, 10, 3, 1.5, 0.3, 20, 50]

    /// Какое место по горизонтали занимает подпись шириной `labelWidth` под делением в доле `fraction` линейки шириной `width`:
    /// она стоит по центру под своим делением, но у краёв сдвигается внутрь линейки и не выходит за неё.
    public static func span(fraction: Double, labelWidth: Double, width: Double) -> ClosedRange<Double> {
        let half = min(labelWidth, width) / 2
        let centre = min(max(fraction * width, half), width - half)
        return (centre - half)...(centre + half)
    }

    /// Подписи под линейкой шириной `width`: оба края таблицы (ближнее и дальнее расстояние — «0,25 м», «∞») и круглые расстояния
    /// между ними («0,5 м», «1 м», «2 м»…) там, где они лежат на шкале. Берётся столько, сколько поместится: краёв хватает
    /// всегда, дальше по важности, и каждая подпись отстоит от остальных не меньше чем на `spacing` и не лезёт на занятые
    /// места `reserved` (например, на подпись у метки положения). `labelWidth` говорит, сколько места занимает текст. Расстояний
    /// нет — таблиц не одна или таблицы нет — подписей тоже нет. По порядку слева направо.
    public static func ticks(for lens: LensInformation, width: Double, spacing: Double = 12, reserved: [ClosedRange<Double>] = [],
                             labelWidth: (String) -> Double) -> [Tick] {
        guard width > 0, lens.tableCount == 1, let near = lens.pairs.first, let far = lens.pairs.last else { return [] }
        let scale = lens.positionScale
        var wanted = [Tick(fraction: LensInformation.share(near.position, of: scale), label: LensInformation.label(near.meters))]
        if lens.pairs.count > 1 {
            wanted.append(Tick(fraction: LensInformation.share(far.position, of: scale), label: LensInformation.label(far.meters)))
        }
        let ends = Set(wanted.map(\.label))
        for meters in roundDistances {
            guard let fraction = lens.fraction(atMeters: meters) else { continue }
            let label = LensInformation.label(meters)
            if !ends.contains(label) { wanted.append(Tick(fraction: fraction, label: label)) }
        }
        var taken = reserved
        var placed: [Tick] = []
        for tick in wanted {
            let place = span(fraction: tick.fraction, labelWidth: labelWidth(tick.label), width: width)
            guard taken.allSatisfy({ place.upperBound + spacing <= $0.lowerBound || $0.upperBound + spacing <= place.lowerBound }) else {
                continue
            }
            taken.append(place)
            placed.append(tick)
        }
        return placed.sorted { $0.fraction < $1.fraction }
    }
}
