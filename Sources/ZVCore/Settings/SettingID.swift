import Foundation

/// Настройки камеры, которые показывает программа.
public enum SettingID: String, CaseIterable, Hashable, Sendable, Codable, CodingKeyRepresentable {
    case exposureProgram, shutter, aperture, iso, exposureComp, metering, dro
    case focusMode, focusArea
    case whiteBalance, colorTemperature, wbShiftAB, wbShiftGM, creativeStyle, pictureProfile
    case movieFileFormat, movieRecordingSetting
    case imageSize, aspectRatio, fileFormat, jpegQuality
    case battery, cardRemaining, overheat

    public var code: UInt16 {
        switch self {
        case .exposureProgram: return SonyProp.exposureProgram
        case .shutter: return SonyProp.shutter
        case .aperture: return SonyProp.fNumber
        case .iso: return SonyProp.iso
        case .exposureComp: return SonyProp.exposureComp
        case .metering: return SonyProp.metering
        case .dro: return SonyProp.dro
        case .focusMode: return SonyProp.focusMode
        case .focusArea: return SonyProp.focusArea
        case .whiteBalance: return SonyProp.whiteBalance
        case .colorTemperature: return SonyProp.colorTemp
        case .wbShiftAB: return SonyProp.abShift
        case .wbShiftGM: return SonyProp.gmShift
        case .creativeStyle: return SonyProp.creativeStyle
        case .pictureProfile: return SonyProp.pictureProfile
        case .movieFileFormat: return SonyProp.movieFileFormat
        case .movieRecordingSetting: return SonyProp.movieRecordingSetting
        case .imageSize: return SonyProp.imageSize
        case .aspectRatio: return SonyProp.aspectRatio
        case .fileFormat: return SonyProp.fileFormat
        case .jpegQuality: return SonyProp.jpegQuality
        case .battery: return SonyProp.batteryLevel
        case .cardRemaining: return SonyProp.cardRemainingTime
        case .overheat: return SonyProp.overheat
        }
    }

    public var title: String {
        switch self {
        case .exposureProgram: return "Режим съёмки"
        case .shutter: return "Выдержка"
        case .aperture: return "Диафрагма"
        case .iso: return "ISO"
        case .exposureComp: return "Экспокоррекция"
        case .metering: return "Замер"
        case .dro: return "DRO / HDR"
        case .focusMode: return "Режим фокуса"
        case .focusArea: return "Зона фокуса"
        case .whiteBalance: return "Баланс белого"
        case .colorTemperature: return "Цветовая температура"
        case .wbShiftAB: return "Сдвиг A–B"
        case .wbShiftGM: return "Сдвиг G–M"
        case .creativeStyle: return "Творческий стиль"
        case .pictureProfile: return "Профиль изображения"
        case .movieFileFormat: return "Формат видео"
        case .movieRecordingSetting: return "Параметры записи"
        case .imageSize: return "Размер снимка"
        case .aspectRatio: return "Соотношение сторон"
        case .fileFormat: return "Формат снимка"
        case .jpegQuality: return "Качество JPEG"
        case .battery: return "Батарея"
        case .cardRemaining: return "Осталось на карте"
        case .overheat: return "Температура камеры"
        }
    }

    public var shortTitle: String {
        switch self {
        case .exposureProgram: return "Режим"
        case .exposureComp: return "Экспокорр."
        case .dro: return "DRO"
        case .focusMode: return "Фокус"
        case .colorTemperature: return "Цвет. темп."
        case .wbShiftAB: return "A–B"
        case .wbShiftGM: return "G–M"
        case .creativeStyle: return "Стиль"
        case .pictureProfile: return "Профиль"
        case .movieRecordingSetting: return "Запись"
        case .imageSize: return "Размер"
        case .aspectRatio: return "Пропорции"
        case .fileFormat: return "Формат"
        case .jpegQuality: return "JPEG"
        case .cardRemaining: return "Карта"
        case .overheat: return "Температура"
        default: return title
        }
    }
}

public enum SettingGroup: String, CaseIterable, Sendable, Codable {
    case exposure, focus, color, video, photo, camera

    public var title: String {
        switch self {
        case .exposure: return "Экспозиция"
        case .focus: return "Фокус"
        case .color: return "Цвет и картинка"
        case .video: return "Видео"
        case .photo: return "Фото"
        case .camera: return "Камера"
        }
    }

    public var settings: [SettingID] {
        switch self {
        case .exposure: return [.exposureProgram, .shutter, .aperture, .iso, .exposureComp, .metering, .dro]
        case .focus: return [.focusMode, .focusArea]
        case .color: return [.whiteBalance, .colorTemperature, .wbShiftAB, .wbShiftGM, .creativeStyle, .pictureProfile]
        case .video: return [.movieFileFormat, .movieRecordingSetting]
        case .photo: return [.imageSize, .aspectRatio, .fileFormat, .jpegQuality]
        case .camera: return [.battery, .cardRemaining, .overheat]
        }
    }
}
