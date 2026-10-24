import Foundation

/// Сохранённый набор настроек камеры.
public struct Preset: Codable, Equatable, Sendable {
    public var name: String
    public var values: [SettingID: Int64]

    public init(name: String, values: [SettingID: Int64]) {
        self.name = name
        self.values = values
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
}

extension CameraSession {
    /// Применяет пресет по одной настройке, в порядке каталога. Совпадающие значения не пишутся.
    public func apply(_ preset: Preset) async -> PresetResult {
        var result = PresetResult()
        var connected = true
        for id in PresetCatalog.order {
            guard let value = preset.values[id] else { continue }
            // температура и сдвиги хранятся в камере отдельно для каждого режима ББ, экспокоррекция
            // работает только с ISO AUTO: если базовая настройка не применилась, зависимые не трогаем
            let base = PresetCatalog.dependency[id]
            let baseFailed = base.map { preset.values[$0] != nil && !result.applied.contains($0) } ?? false
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
