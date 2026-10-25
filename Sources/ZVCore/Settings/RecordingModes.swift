import Foundation

/// Режим записи видео одним выбором: формат (`0xD241`) и параметры записи (`0xD242`) вместе — «4K 25p 100M».
public struct RecordingMode: Hashable, Sendable {
    public let format: Int64
    public let setting: Int64

    public init(format: Int64, setting: Int64) {
        self.format = format
        self.setting = setting
    }

    /// Одно число для ленты значений: формат в старшем байте, параметры — в младшем.
    public var code: Int64 { format << 8 | setting }

    public init(code: Int64) {
        self.init(format: code >> 8, setting: code & 0xFF)
    }

    public var label: String {
        let format: String
        switch self.format {
        case RecordingModes.fourK: format = "4K"
        case RecordingModes.hd: format = "HD"
        default: format = ValueFormatter.label(.movieFileFormat, self.format)
        }
        return "\(format) \(ValueFormatter.label(.movieRecordingSetting, setting))"
    }
}

/// Какие режимы записи предложить. Камера перечисляет параметры записи только для текущего формата,
/// поэтому для второго формата они берутся из таблицы кодов — той же системы (PAL или NTSC), что у текущих.
public enum RecordingModes {
    public static let fourK: Int64 = 8
    public static let hd: Int64 = 9

    /// Частота и битрейт по коду `0xD242`: XAVC S HD — `0x01`–`0x1F`, XAVC S 4K — `0x20`–`0x25`.
    static let specs: [Int64: (fps: Int, mbps: Int)] = [
        0x01: (60, 50), 0x02: (30, 50), 0x03: (24, 50), 0x04: (50, 50), 0x05: (25, 50),
        0x18: (60, 25), 0x19: (50, 25), 0x1A: (30, 16), 0x1B: (25, 16),
        0x1C: (120, 100), 0x1D: (100, 100), 0x1E: (120, 60), 0x1F: (100, 60),
        0x20: (30, 100), 0x21: (25, 100), 0x22: (24, 100),
        0x23: (30, 60), 0x24: (25, 60), 0x25: (24, 60),
    ]

    static func format(of setting: Int64) -> Int64 { setting >= 0x20 ? fourK : hd }
    static func isPAL(_ setting: Int64) -> Bool { [25, 50, 100].contains(specs[setting]?.fps ?? 0) }

    /// formats — какие форматы камера разрешает, format — текущий, allowed — параметры, разрешённые для текущего.
    public static func options(formats: [Int64], format: Int64?, allowed: [Int64]) -> [RecordingMode] {
        guard let format else { return [] }
        let pal = allowed.contains(where: isPAL)
        var modes = allowed.map { RecordingMode(format: format, setting: $0) }
        for other in formats where other != format && (other == fourK || other == hd) {
            modes += specs.keys.filter { Self.format(of: $0) == other && isPAL($0) == pal }
                .map { RecordingMode(format: other, setting: $0) }
        }
        return modes.sorted(by: order)
    }

    /// Частота кадров ролика по коду `0xD242`.
    public static func frameRate(of setting: Int64) -> Int? { specs[setting]?.fps }

    /// Система по коду `0xD242`: 25/50/100p — PAL.
    public static func isPALSetting(_ setting: Int64) -> Bool { isPAL(setting) }

    /// Подпись режима; у камеры без свойства формата — подпись одних параметров, как раньше.
    public static func label(format: Int64?, setting: Int64?) -> String {
        guard let setting else { return "—" }
        guard let format else { return ValueFormatter.label(.movieRecordingSetting, setting) }
        return RecordingMode(format: format, setting: setting).label
    }

    /// Как перейти в режим. Параметры камера принимает только для текущего формата, поэтому смотрим и на формат,
    /// запись которого ещё в пути: передумали, пока камера переключается, — пишем формат снова.
    public enum Plan: Equatable, Sendable {
        case settingOnly
        case formatThenSetting
        case waitForFormat
    }

    public static func plan(to mode: RecordingMode, confirmedFormat: Int64?, requestedFormat: Int64?) -> Plan {
        if (requestedFormat ?? confirmedFormat) != mode.format { return .formatThenSetting }
        return confirmedFormat == mode.format ? .settingOnly : .waitForFormat
    }

    /// Соседний режим; base — последний запрошенный, если он есть, иначе текущий.
    public static func neighbour(of base: RecordingMode?, in modes: [RecordingMode], offset: Int) -> RecordingMode? {
        guard let base, let index = modes.firstIndex(of: base) else { return nil }
        let target = min(max(index + offset, 0), modes.count - 1)
        return target == index ? nil : modes[target]
    }

    /// 4K раньше HD; внутри формата — по убыванию частоты, затем битрейта.
    static func order(_ a: RecordingMode, _ b: RecordingMode) -> Bool {
        if a.format != b.format { return a.format == fourK }
        let sa = specs[a.setting] ?? (0, 0)
        let sb = specs[b.setting] ?? (0, 0)
        if sa.fps != sb.fps { return sa.fps > sb.fps }
        if sa.mbps != sb.mbps { return sa.mbps > sb.mbps }
        return a.setting < b.setting
    }
}
