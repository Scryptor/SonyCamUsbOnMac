import Foundation

/// Справочник Sony (Camera Control PTP 3 Reference): названия всех его свойств и списки значений — для камер, у которых
/// своей подписи нет. JSON лежит в SonyReferenceData (его создаёт scripts/sony_reference.py) и разбирается один раз, при
/// первом обращении. Ключи в нём — коды как в справочнике («0xD0FA», «0x0B»); значение читается так же, как из камеры:
/// UINT64 на всю ширину (0xFFFFFFFFFFFFFFFF) — Int64(bitPattern:), то есть −1.
public enum SonyReferenceTables {
    /// Название свойства по его коду — все свойства справочника.
    public static var propertyNames: [UInt16: String] { parsed.names }

    /// Списки значений: свойство → значение → подпись Sony. Только у свойств, значения которых справочник перечисляет.
    public static var values: [UInt16: [Int64: String]] { parsed.values }

    /// Подпись значения свойства; nil, если справочник такого значения не знает.
    public static func valueName(_ code: UInt16, _ value: Int64) -> String? { parsed.values[code]?[value] }

    private struct Tables: Sendable {
        var names: [UInt16: String] = [:]
        var values: [UInt16: [Int64: String]] = [:]
    }

    private struct Document: Decodable {
        struct Property: Decodable {
            let name: String
            let values: [String: String]?
        }
        let properties: [String: Property]
    }

    private static let parsed: Tables = {
        guard let document = try? JSONDecoder().decode(Document.self, from: Data(SonyReferenceData.json.utf8)) else {
            return Tables()
        }
        var tables = Tables()
        for (key, property) in document.properties {
            guard let code = hex(key).flatMap({ UInt16(exactly: $0) }) else { continue }
            tables.names[code] = property.name
            var list: [Int64: String] = [:]
            for (valueKey, name) in property.values ?? [:] {
                if let value = hex(valueKey) { list[Int64(bitPattern: value)] = name }
            }
            if !list.isEmpty { tables.values[code] = list }
        }
        return tables
    }()

    /// «0x0B» → 11; nil для всего, что не шестнадцатеричное число до 64 бит.
    private static func hex(_ key: String) -> UInt64? {
        guard key.hasPrefix("0x") else { return nil }
        return UInt64(key.dropFirst(2), radix: 16)
    }
}
