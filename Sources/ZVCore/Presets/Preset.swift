import Foundation

/// Сохранённый набор настроек камеры.
public struct Preset: Codable, Equatable, Sendable {
    public var name: String
    public var values: [SettingID: Int64]
    /// Настройки, которые пресет не меняет: значение он помнит, но не применяет — включили обратно, и оно на месте.
    public var skip: Set<SettingID>
    /// Кадр пресета (`PresetPreview`): JPEG, снятый вместе со значениями; у пресетов до кадров его нет.
    public var preview: Data?
    /// Значения правили после кадра — он показывает, как было до правок.
    public var previewOutdated: Bool

    public init(name: String, values: [SettingID: Int64], skip: Set<SettingID> = [], preview: Data? = nil,
                previewOutdated: Bool = false) {
        self.name = name
        self.values = values
        self.skip = skip
        self.preview = preview
        self.previewOutdated = previewOutdated
    }

    private enum CodingKeys: String, CodingKey {
        case name, values, skip, preview, previewOutdated
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        name = try c.decode(String.self, forKey: .name)
        // незнакомую настройку из будущей версии пропускаем: иначе не прочитался бы весь список пресетов
        values = Dictionary(uniqueKeysWithValues: try c.decode([String: Int64].self, forKey: .values)
            .compactMap { key, value in SettingID(rawValue: key).map { ($0, value) } })
        // у пресетов до окна пресетов поля нет; незнакомую настройку из будущей версии пропускаем
        skip = Set((try c.decodeIfPresent([String].self, forKey: .skip) ?? []).compactMap(SettingID.init(rawValue:)))
        preview = try c.decodeIfPresent(Data.self, forKey: .preview)
        previewOutdated = try c.decodeIfPresent(Bool.self, forKey: .previewOutdated) ?? false
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(name, forKey: .name)
        try c.encode(values, forKey: .values)
        if !skip.isEmpty { try c.encode(skip.map(\.rawValue).sorted(), forKey: .skip) }
        try c.encodeIfPresent(preview, forKey: .preview)
        if previewOutdated { try c.encode(true, forKey: .previewOutdated) }
    }
}

public struct PresetResult: Equatable, Sendable {
    public var applied: [SettingID] = []
    public var skipped: [SettingID] = []

    public init(applied: [SettingID] = [], skipped: [SettingID] = []) {
        self.applied = applied
        self.skipped = skipped
    }
}

public enum PresetCatalog {
    /// Что входит в пресет и в каком порядке применяется: температура — после баланса белого
    /// (доступна только в режиме K), экспокоррекция — после ISO (доступна только с ISO AUTO).
    public static let order: [SettingID] = [
        .whiteBalance, .colorTemperature, .wbShiftAB, .wbShiftGM,
        .iso, .exposureComp, .shutter, .aperture,
        .metering, .dro, .focusMode, .focusArea, .creativeStyle, .pictureProfile,
    ]

    /// Настройка → от какой она зависит.
    public static let dependency: [SettingID: SettingID] = [
        .colorTemperature: .whiteBalance, .wbShiftAB: .whiteBalance, .wbShiftGM: .whiteBalance,
        .exposureComp: .iso,
    ]

    /// Снимок текущих значений; берутся только настройки, которые камера сейчас даёт менять.
    public static func capture(name: String, from properties: [UInt16: SonyProperty]) -> Preset {
        var values: [SettingID: Int64] = [:]
        for id in order {
            if let property = properties[id.code], property.isWritable, let value = property.current.intValue {
                values[id] = value
            }
        }
        return Preset(name: name, values: values)
    }

    /// Имя нового пресета, если его не назвали: «Пресет N» по месту, которое он займёт; занят такой — следующий номер.
    public static func newName(_ presets: [Preset]) -> String {
        let taken = Set(presets.map(\.name))
        var number = presets.count + 1
        while taken.contains("Пресет \(number)") { number += 1 }
        return "Пресет \(number)"
    }

    /// Что пресет применит: без выключенных настроек и без цветовой температуры, если баланс белого он ставит
    /// не «Цвет. темп.», — в другом режиме камера температуру менять не даёт.
    public static func effective(_ preset: Preset) -> [SettingID: Int64] {
        var values = preset.values.filter { !preset.skip.contains($0.key) }
        if let whiteBalance = values[.whiteBalance], whiteBalance != WhiteBalanceTemperature.manualMode {
            values[.colorTemperature] = nil
        }
        return values
    }

    /// Стоит ли пресет на камере: всё, что он применяет, уже такое же. Пресет, который ничего не меняет, не стоит.
    public static func matches(_ preset: Preset, _ properties: [UInt16: SonyProperty]) -> Bool {
        let values = effective(preset)
        return !values.isEmpty && values.allSatisfy { properties[$0.key.code]?.current.intValue == $0.value }
    }

    /// «Взять с камеры»: всё, что камера сейчас даёт менять, — её текущими значениями; остальное, имя и
    /// выключенные настройки — как были.
    public static func recapture(_ preset: Preset, from properties: [UInt16: SonyProperty]) -> Preset {
        var fresh = preset
        fresh.values.merge(capture(name: preset.name, from: properties).values) { _, camera in camera }
        return fresh
    }

    /// Кратко, что пресет ставит, — для списков: «1/50 · f/2.8 · ISO 800 · 3200 K · AF-C». Ничего из главного —
    /// какие настройки он меняет: «Замер · Стиль».
    public static func summary(_ preset: Preset) -> String {
        let values = effective(preset)
        var parts: [String] = []
        if let shutter = values[.shutter] { parts.append(ValueFormatter.label(.shutter, shutter)) }
        if let aperture = values[.aperture] { parts.append(ValueFormatter.label(.aperture, aperture)) }
        if let iso = values[.iso] { parts.append("ISO " + ValueFormatter.label(.iso, iso)) }
        if let whiteBalance = values[.whiteBalance] {
            parts.append(ValueFormatter.whiteBalanceLabel(whiteBalance: whiteBalance, colorTemperature: values[.colorTemperature]))
        } else if let kelvin = values[.colorTemperature] {
            parts.append(ValueFormatter.label(.colorTemperature, kelvin))
        }
        if let comp = values[.exposureComp], comp != 0 { parts.append(ValueFormatter.label(.exposureComp, comp)) }
        if let focus = values[.focusMode] { parts.append(ValueFormatter.label(.focusMode, focus)) }
        if parts.isEmpty { parts = order.filter { values[$0] != nil }.map(\.shortTitle) }
        return parts.isEmpty ? "ничего не меняет" : parts.joined(separator: " · ")
    }

    /// Из чего выбирать значение в окне пресетов: что камера даёт выбрать сейчас, с подписями меню Fn; температура
    /// без списка камеры — из запасного диапазона. Значение пресета, которого среди вариантов нет, — в начале.
    public static func options(_ id: SettingID, _ property: SonyProperty?, _ preset: Preset) -> [SettingOption] {
        var list = property.map { SettingOptions.options(id, $0) } ?? []
        if list.isEmpty, id == .colorTemperature, property != nil {
            let range = WhiteBalanceTemperature.fallbackRange
            list = stride(from: range.min, through: range.max, by: Int64.Stride(range.step))
                .map { SettingOption(value: $0, label: ValueFormatter.label(id, $0)) }
        }
        if !list.isEmpty, let value = preset.values[id], !list.contains(where: { $0.value == value }) {
            list.insert(SettingOption(value: value, label: ValueFormatter.label(id, value)), at: 0)
        }
        return list
    }
}

extension CameraSession {
    /// Применяет пресет по одной настройке, в порядке каталога. Совпадающие значения не пишутся.
    public func apply(_ preset: Preset) async -> PresetResult {
        var result = PresetResult()
        var connected = true
        let values = PresetCatalog.effective(preset)
        for id in PresetCatalog.order {
            guard let value = values[id] else { continue }
            // температура и сдвиги хранятся в камере отдельно для каждого режима ББ, экспокоррекция
            // работает только с ISO AUTO: если базовая настройка не применилась, зависимые не трогаем
            let base = PresetCatalog.dependency[id]
            let baseFailed = base.map { values[$0] != nil && !result.applied.contains($0) } ?? false
            guard connected, !baseFailed else {
                result.skipped.append(id)
                continue
            }
            if properties[id.code]?.current.intValue == value {
                result.applied.append(id)
                continue
            }
            do {
                if try await set(id.code, to: value) == .confirmed {
                    result.applied.append(id)
                } else {
                    result.skipped.append(id)
                }
            } catch TransportError.disconnected {
                connected = false
                result.skipped.append(id)
            } catch {
                result.skipped.append(id)
            }
        }
        return result
    }
}
