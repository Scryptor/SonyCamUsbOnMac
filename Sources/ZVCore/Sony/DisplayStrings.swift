import Foundation

/// Подписи значений от самой камеры — ответ Sony 0x9215 (GetDisplayStringList). Камера составляет такие списки сама:
/// Creative Look, BaseLook, кнопки, FTP и т. п. Результат — «тип списка → значение → строка». Программа просит их только у
/// камер, которые называют 0x9215 в GetDeviceInfo (`DeviceInfo.supports`): у ZV-E10 такой операции нет.
public struct DisplayStrings: Equatable, Sendable {
    /// Тип списка с названиями Creative Look.
    public static let creativeLookType: UInt32 = 0x13

    public var lists: [UInt32: [Int64: String]]

    public init(lists: [UInt32: [Int64: String]] = [:]) {
        self.lists = lists
    }

    /// Разбирает ответ 0x9215 (little-endian). Набор: u32 смещение до данных и u32 размер данных; данные — со смещения:
    /// u16 число списков и 2 байта резерва; у списка u32 тип, u16 тип данных PTP, u16 число строк; у строки значение (размер
    /// по типу данных), u16 размер строки с нулём и сама строка, UTF-8 с нулём. Значение — целое со знаком типа данных;
    /// список с другим типом (строка, массив) в ключи не годится. Список без строк остаётся пустым списком: он значит
    /// «подписей этого типа больше нет», и `merge` должен уметь им стирать.
    /// Обрезанный или противоречивый набор — ошибка: лучше без подписей, чем с наполовину прочитанными.
    public static func parse(_ data: Data) throws -> DisplayStrings {
        var r = ByteReader(try SonyDataset.body(data))
        let listCount = Int(try r.u16())
        try r.skip(2)                                           // резерв
        var lists: [UInt32: [Int64: String]] = [:]
        for _ in 0..<listCount {
            let type = try r.u32()
            let dataType = try r.u16()
            let stringCount = Int(try r.u16())
            var strings: [Int64: String] = [:]
            for _ in 0..<stringCount {
                guard let value = try PTPValue.read(&r, type: dataType).intValue else {
                    throw PTPParseError.unsupportedType(dataType)
                }
                let size = Int(try r.u16())
                var raw: [UInt8] = []
                raw.reserveCapacity(min(size, r.remaining))
                for _ in 0..<size { raw.append(try r.u8()) }
                while raw.last == 0 { raw.removeLast() }       // размер считается вместе с нулём
                strings[value] = String(decoding: raw, as: UTF8.self)
            }
            lists[type] = strings
        }
        return DisplayStrings(lists: lists)
    }

    /// Подписи, пришедшие позже (по событию «список изменился»): список того же типа заменяется целиком, остальные остаются.
    public mutating func merge(_ other: DisplayStrings) {
        lists.merge(other.lists) { _, new in new }
    }

    /// Камера сообщила, что список типа `type` изменился, и прислала его заново. Тип 0 — «все списки»: набор заменяется целиком.
    /// Иначе заменяется список этого типа; если камера его больше не отдаёт (в ответе его нет), он стирается — старые названия не
    /// должны жить дальше нового списка. Списки других типов остаются.
    public mutating func replace(type: UInt32, with fresh: DisplayStrings) {
        if type == 0 {
            self = fresh
        } else {
            lists[type] = nil
            merge(fresh)
        }
    }

    /// Названия значений настройки от самой камеры (`ValueFormatter.label(…, names:)`). Из списков камеры программа показывает
    /// только Creative Look (тип 0x13); у остальных настроек списков камеры нет, их подписывают наши таблицы и справочник Sony.
    /// nil — названий от камеры нет.
    public func names(for id: SettingID) -> [Int64: String]? {
        id == .creativeLook ? lists[Self.creativeLookType] : nil
    }
}

/// Набор данных Sony (ответы 0x9215 и 0x9223): u32 смещение до данных, u32 размер данных, дальше сами данные.
enum SonyDataset {
    /// Данные набора — ровно `размер` байт со `смещения`. Заголовок, который обещает больше, чем пришло, — обрезанный
    /// набор; смещение внутрь самого заголовка — не набор: обе ошибки `PTPParseError`.
    static func body(_ data: Data) throws -> Data {
        var header = ByteReader(data)
        let offset = Int(try header.u32())
        let size = Int(try header.u32())
        guard offset >= 8 else { throw PTPParseError.badCount(offset, offset: 0) }
        guard offset + size <= data.count else { throw PTPParseError.truncated(offset: data.count) }
        let start = data.startIndex + offset
        return data.subdata(in: start..<start + size)
    }
}
