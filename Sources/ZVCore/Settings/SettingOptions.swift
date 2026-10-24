import Foundation

public struct SettingOption: Equatable, Sendable {
    public let value: Int64
    public let label: String

    public init(value: Int64, label: String) {
        self.value = value
        self.label = label
    }
}

public enum SettingOptions {
    /// Значения, которые можно выбрать сейчас. Берутся только из переданного свойства.
    public static func options(_ id: SettingID, _ property: SonyProperty) -> [SettingOption] {
        values(property).map { SettingOption(value: $0, label: ValueFormatter.label(id, $0)) }
    }

    /// Соседнее разрешённое значение; nil, если сдвинуться некуда.
    /// `from` — точка отсчёта вместо текущего значения камеры (например, ещё не подтверждённый запрос).
    public static func neighbour(_ id: SettingID, _ property: SonyProperty, offset: Int, from: Int64? = nil) -> Int64? {
        let list = values(property)
        guard let current = from ?? property.current.intValue, let index = list.firstIndex(of: current) else { return nil }
        let target = min(max(index + offset, 0), list.count - 1)
        return target == index ? nil : list[target]
    }

    static func values(_ property: SonyProperty) -> [Int64] {
        guard property.isWritable else { return [] }
        if !property.settableValues.isEmpty { return property.settableValues }
        guard let range = property.range, range.step > 0, range.max >= range.min else { return [] }
        return Array(stride(from: range.min, through: range.max, by: Int64.Stride(range.step)))
    }
}
