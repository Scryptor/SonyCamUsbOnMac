import Foundation

/// Удаление снимка из галереи: какой снимок показать вместо удалённого и куда его вернуть по «Вернуть». Лента — новые
/// сначала.
public enum ShotDeletion {
    /// Сколько видна плашка «Вернуть», с.
    public static let undoSeconds: TimeInterval = 6

    /// Удалили снимок с места index из count: место (в ленте уже без него) снимка, который показать, — того, что встал
    /// на место удалённого (он старше); удалили самый старый — соседнего новее; nil — снимков не осталось.
    public static func neighbor(removing index: Int, count: Int) -> Int? {
        guard count > 1 else { return nil }
        return min(max(index, 0), count - 2)
    }

    /// Куда вернуть снимок, снятый в takenAt: перед первым снятым раньше него. Пока висела «Вернуть», могли прийти
    /// новые снимки — по месту в ленте его уже не вернуть, по времени — да.
    public static func restoreIndex(takenAt: Date, in times: [Date]) -> Int {
        times.firstIndex { $0 < takenAt } ?? times.count
    }
}
