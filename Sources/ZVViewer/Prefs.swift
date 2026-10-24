import Foundation

/// Настройки программы (окно ⌘, и API автоматизации). Хранятся в UserDefaults.
enum Prefs {
    enum Destination: String, CaseIterable, Identifiable {
        case macAndCard, macOnly, cardOnly

        var id: String { rawValue }

        var title: String {
            switch self {
            case .macAndCard: return "На Mac и на карту камеры"
            case .macOnly: return "Только на Mac"
            case .cardOnly: return "Только на карту"
            }
        }

        /// Значение для камеры (PC Remote → Still Img. Save Dest., 0xD222).
        var cameraValue: Int64 {
            switch self {
            case .macAndCard: return 0x11
            case .macOnly: return 0x01
            case .cardOnly: return 0x10
            }
        }

        var savesToMac: Bool { self != .cardOnly }
    }

    enum ReviewSource: String, CaseIterable, Identifiable {
        case downloaded, camera

        var id: String { rawValue }

        var title: String {
            switch self {
            case .downloaded: return "Загруженный снимок"
            case .camera: return "Кадр с камеры"
            }
        }
    }

    enum Key {
        static let destination = "photoDestination"
        static let folder = "photoFolder"
        static let reviewEnabled = "reviewEnabled"
        static let reviewSeconds = "reviewSeconds"
        static let reviewSource = "reviewSource"
        static let autoHide = "autoHideControls"
        static let wifiEnabled = "wifiEnabled"
        static let wifiHost = "wifiHost"
        static let wifiCameraName = "wifiCameraName"
    }

    private static var defaults: UserDefaults { .standard }

    static var defaultFolder: String {
        FileManager.default.urls(for: .downloadsDirectory, in: .userDomainMask)[0].path
    }

    static var destination: Destination {
        get { Destination(rawValue: defaults.string(forKey: Key.destination) ?? "") ?? .macAndCard }
        set { defaults.set(newValue.rawValue, forKey: Key.destination) }
    }

    static var folderURL: URL {
        get { URL(fileURLWithPath: defaults.string(forKey: Key.folder) ?? defaultFolder, isDirectory: true) }
        set { defaults.set(newValue.path, forKey: Key.folder) }
    }

    static var reviewEnabled: Bool {
        get { defaults.object(forKey: Key.reviewEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.reviewEnabled) }
    }

    /// 1…10 секунд.
    static var reviewSeconds: Int {
        get { min(max(defaults.object(forKey: Key.reviewSeconds) as? Int ?? 3, 1), 10) }
        set { defaults.set(min(max(newValue, 1), 10), forKey: Key.reviewSeconds) }
    }

    static var reviewSource: ReviewSource {
        get { ReviewSource(rawValue: defaults.string(forKey: Key.reviewSource) ?? "") ?? .downloaded }
        set { defaults.set(newValue.rawValue, forKey: Key.reviewSource) }
    }

    static var autoHide: Bool {
        get { defaults.object(forKey: Key.autoHide) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.autoHide) }
    }

    /// Подключаться к камере по Wi-Fi, когда её нет на кабеле.
    static var wifiEnabled: Bool {
        get { defaults.object(forKey: Key.wifiEnabled) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.wifiEnabled) }
    }

    /// Последний адрес сопряжённой камеры в сети: с него начинаем поиск.
    static var wifiHost: String? {
        get { defaults.string(forKey: Key.wifiHost) }
        set { defaults.set(newValue, forKey: Key.wifiHost) }
    }

    static var wifiCameraName: String? {
        get { defaults.string(forKey: Key.wifiCameraName) }
        set { defaults.set(newValue, forKey: Key.wifiCameraName) }
    }
}
