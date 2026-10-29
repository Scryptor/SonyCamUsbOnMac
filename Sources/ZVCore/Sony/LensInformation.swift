import Foundation

/// Данные объектива — ответ Sony 0x9223 (GetLensInformation) с параметром 2 (метры): по нормированному положению фокуса —
/// расстояние до предмета. Программа просит их только у камер, которые называют 0x9223 в GetDeviceInfo, и только при
/// 0xE086 = 1 (`SonyProp.lensInfoEnabled`).
public struct LensInformation: Equatable, Sendable {
    /// Положение фокуса в шкале таблицы и расстояние до предмета в метрах.
    public typealias Pair = (position: Double, meters: Double)

    /// Пары первой таблицы по возрастанию положения; равные положения остаются в том порядке, в каком пришли.
    public let pairs: [Pair]
    /// Все таблицы набора, каждая по возрастанию положения. Таблиц больше одной — расстояний не показываем, но панель
    /// отладки показывает их все.
    public let tables: [[Pair]]
    /// Таблица в шкале 0xE043 (0…65535), а не 0xD24C (0…100): наибольшее положение больше 100. Справочник единиц не
    /// называет — это догадка, а сырая таблица видна в отладке.
    public let absoluteScale: Bool
    /// Сколько таблиц в наборе. У зумов их может быть по одной на фокусное расстояние; какая верна, справочник не говорит,
    /// поэтому при нескольких расстояний нет: лучше без расстояния, чем с неверным.
    public let tableCount: Int

    /// Каждая таблица упорядочивается по положению (равные положения — как пришли).
    public init(tables: [[Pair]]) {
        let sorted = tables.map { pairs in
            pairs.enumerated().sorted { a, b in
                a.element.position != b.element.position ? a.element.position < b.element.position : a.offset < b.offset
            }.map(\.element)
        }
        self.tables = sorted
        pairs = sorted.first ?? []
        tableCount = sorted.count
        absoluteScale = sorted.contains { $0.contains { $0.position > 100 } }
    }

    /// Разбирает ответ 0x9223 (little-endian). Набор: u32 смещение до данных и u32 размер данных; данные — со смещения:
    /// u16 версия ×100 (1.00 — 100; на разбор не влияет) и u16 число таблиц; у таблицы u16 число пар и 2 байта резерва, затем
    /// пары — u32 нормированное положение и u32 расстояние ×100. Обрезанный набор — ошибка.
    public static func parse(_ data: Data) throws -> LensInformation {
        var r = ByteReader(try SonyDataset.body(data))
        _ = try r.u16()
        let tableCount = Int(try r.u16())
        var tables: [[Pair]] = []
        for _ in 0..<tableCount {
            let pairCount = Int(try r.u16())
            try r.skip(2)                                       // резерв
            var pairs: [Pair] = []
            pairs.reserveCapacity(min(pairCount, r.remaining / 8))
            for _ in 0..<pairCount {
                let position = Double(try r.u32())
                let distance = Double(try r.u32()) / 100
                pairs.append((position: position, meters: distance))
            }
            tables.append(pairs)
        }
        return LensInformation(tables: tables)
    }

    /// Расстояние в метрах для положения `p` (в шкале таблицы): линейная интерполяция между соседними парами; в точке таблицы —
    /// ровно её расстояние; за краями — расстояние ближайшего края. nil — пар нет, таблиц больше одной или `p` не число.
    public func meters(atPosition p: Double) -> Double? {
        guard tableCount == 1, let first = pairs.first, let last = pairs.last, !p.isNaN else { return nil }
        if p <= first.position { return first.meters }
        if p >= last.position { return last.meters }
        // p строго внутри таблицы: первая пара не левее p и пара перед ней (её положение меньше p)
        guard let upper = pairs.firstIndex(where: { $0.position >= p }) else { return last.meters }
        let b = pairs[upper]
        if b.position == p { return b.meters }
        let a = pairs[upper - 1]
        return a.meters + (b.meters - a.meters) * ((p - a.position) / (b.position - a.position))
    }

    /// Расстояние для шкалы фокуса: до 1 м — две цифры после запятой («0,35 м»), до 10 м — одна («1,2 м»), дальше — целые
    /// («12 м»); от 100 м — «∞». Запятая — всегда, чей бы ни был язык системы.
    public static func label(_ meters: Double) -> String {
        if meters.isNaN { return "—" }
        if meters >= 100 { return "∞" }
        let format = meters < 1 ? "%.2f" : meters < 10 ? "%.1f" : "%.0f"
        return String(format: format, meters).replacingOccurrences(of: ".", with: ",") + " м"
    }

    public static func == (lhs: LensInformation, rhs: LensInformation) -> Bool {
        lhs.tables.count == rhs.tables.count && zip(lhs.tables, rhs.tables).allSatisfy { tables in
            tables.0.count == tables.1.count && zip(tables.0, tables.1).allSatisfy { pair in
                pair.0.position == pair.1.position && pair.0.meters == pair.1.meters
            }
        }
    }
}
