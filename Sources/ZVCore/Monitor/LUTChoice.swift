import Foundation

/// Какой встроенный LUT включён: выбор в плитке «LUT» и «Авто» по профилю изображения камеры.
public enum LUTChoice {
    /// Какой встроенный LUT на экране, или nil — его нет. Выбор рукой остаётся тем, что выбран, какой бы ни был профиль;
    /// «Свой» (`.file`) и «Выкл» — не встроенный LUT; «Авто» берёт LUT по профилю изображения камеры.
    ///
    /// - pictureProfile: номер профиля камеры, `0xD23F` (PP1…PP10); у камер без `0xD0E1` — единственное, по чему
    ///   можно судить о гамме
    /// - ppGamma: гамма профиля, `0xD0E1`, если камера её отдаёт
    /// - colorMode: цветовой режим профиля, `0xD0E9`, если камера его отдаёт
    public static func resolve(_ mode: MonitorAssist.LUTMode, pictureProfile: Int64?, ppGamma: Int64?, colorMode: Int64?) -> BuiltinLUT? {
        resolve(mode, pictureProfile: pictureProfile, ppGamma: ppGamma, colorMode: colorMode, cameraConverts: cameraConverts)
    }

    /// «Авто: S-Log3», «Авто: нет» — подпись на плитке при выборе «Авто».
    public static func autoLabel(_ resolved: BuiltinLUT?) -> String {
        "Авто: " + (resolved?.label ?? "нет")
    }

    /// Превью камеры уже приведено к Rec.709 самой камерой (гамма-помощь, Gamma Disp. Assist)? Тогда «Авто» ничего не
    /// включает: LUT поверх дал бы двойное преобразование. Пока это не проверено на камере — false, и превью
    /// считается логарифмическим. Проверка — в конце работ (`docs/protocol.md`, «Превью в S-Log и HLG»).
    public static let cameraConverts = false

    // MARK: - Внутренности

    /// То же, что `resolve`, но с явным `cameraConverts` — чтобы проверять и ветку «камера сама приводит к 709».
    static func resolve(_ mode: MonitorAssist.LUTMode, pictureProfile: Int64?, ppGamma: Int64?, colorMode: Int64?, cameraConverts: Bool) -> BuiltinLUT? {
        switch mode {
        case .off, .file: return nil
        case .slog3cine: return .slog3cine
        case .slog3: return .slog3
        case .slog2: return .slog2
        case .hlg: return .hlg
        case .auto:
            if cameraConverts { return nil }
            return automatic(pictureProfile: pictureProfile, ppGamma: ppGamma, colorMode: colorMode)
        }
    }

    /// «Авто» по профилю. Значения — из таблиц справочника Sony: `0xD0E1` (0x0302 S-Log2, 0x0303 S-Log3,
    /// 0x0401…0x0404 HLG) и `0xD0E9` (0x0008 S-Gamut3.Cine, 0x0009 S-Gamut3).
    private static func automatic(pictureProfile: Int64?, ppGamma: Int64?, colorMode: Int64?) -> BuiltinLUT? {
        // Камера сама называет гамму — тогда номер профиля не нужен: в нём гамму могли перенастроить
        if let gamma = ppGamma {
            switch gamma {
            case 0x0302: return .slog2
            // S-Log3 с S-Gamut3 — свой LUT, с остальными охватами (и без охвата) — S-Gamut3.Cine
            case 0x0303: return colorMode == 0x0009 ? .slog3 : .slog3cine
            case 0x0401...0x0404: return .hlg
            default: return nil
            }
        }
        // Нет 0xD0E1 (ZV-E10): заводские профили Sony. Если профиль в камере перенастроили, «Авто» ошибётся —
        // тогда LUT выбирают руками (так сказано в справке плитки)
        switch pictureProfile {
        case 7: return .slog2
        case 8: return .slog3cine
        case 9: return .slog3
        case 10: return .hlg
        default: return nil
        }
    }
}
