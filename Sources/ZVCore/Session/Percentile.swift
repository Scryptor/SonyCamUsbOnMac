import Foundation

public enum Percentile {
    /// Ближайший ранг: не меньше доли q (0…1) чисел лежит не выше ответа; nil — чисел нет.
    public static func of(_ values: [Double], _ q: Double) -> Double? {
        guard !values.isEmpty else { return nil }
        let sorted = values.sorted()
        let rank = Int((Double(sorted.count) * min(max(q, 0), 1)).rounded(.up))
        return sorted[min(sorted.count - 1, max(0, rank - 1))]
    }
}
