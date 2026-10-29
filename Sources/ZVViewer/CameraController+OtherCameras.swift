import Foundation
import ZVCore

// Другие камеры Sony (спека §7): названия от самой камеры, Creative Look вместо Creative Style и расстояния на шкале фокуса.
// Сами данные — в `info` (CameraInfo), здесь то, что из них видят экраны.

extension CameraController {
    /// Какая настройка на самом деле стоит на месте `id` у камеры на связи: у новой камеры Sony на месте «Творческого стиля» —
    /// Creative Look. У ZV-E10 — та же настройка, что и была. Без камеры (свойств нет) тоже та же.
    func resolved(_ id: SettingID) -> SettingID {
        SettingCatalog.resolved(id, available: Set(properties.keys))
    }

    /// Названия значений настройки от самой камеры; nil — своих названий у камеры для неё нет, подписывают таблицы.
    func names(for id: SettingID) -> [Int64: String]? {
        info.strings.names(for: id)
    }

    // MARK: - Расстояния на шкале фокуса

    /// Данные объектива, по которым на шкале есть расстояния: одна таблица. Таблиц больше одной (у зумов — на каждое фокусное
    /// расстояние) — расстояний нет, а сами таблицы видны в отладке.
    var focusLens: LensInformation? {
        guard let lens = info.lens, lens.tableCount == 1, !lens.pairs.isEmpty else { return nil }
        return lens
    }

    /// Где фокус на шкале, доля 0…1: по 0xE043 (0…65535), если камера его отдаёт, иначе по 0xD24C (0…100).
    var focusFraction: Double? { FocusPosition.fraction(properties) }

    /// Расстояние до предмета подписью («1,2 м», «∞»); nil — расстояний нет или положения фокуса нет.
    var focusDistance: String? {
        guard let lens = focusLens, let fraction = focusFraction else { return nil }
        return lens.label(atFraction: fraction)
    }

    /// Расстояние, на которое поставлена метка A или B; nil — метка пуста или расстояний нет.
    func focusMarkDistance(_ mark: Character) -> String? {
        guard let lens = focusLens, let fraction = focusMarkFraction(mark) else { return nil }
        return lens.label(atFraction: fraction)
    }
}
