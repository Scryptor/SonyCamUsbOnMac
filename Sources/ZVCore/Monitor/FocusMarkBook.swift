import Foundation

/// Метки фокуса A и B всех камер на время работы программы. Правила, которые не зависят от экрана:
///
/// - у каждой камеры свои метки, по её id; обрыв связи с той же камерой их не стирает;
/// - метки — числа в единицах режима перевода (0…65535 у абсолютного фокуса, 0…100 у шагов Near/Far), и в другом режиме они
///   ничего не значат: камера, у которой 0xE042 то открыт, то закрыт, не должна поехать к «65000» по шкале 0…100. Поэтому
///   метки, записанные в одном режиме, в другом не видны; первая же новая метка начинает набор заново;
/// - смена объектива (событие 0xC21B) стирает обе метки; время перевода — настройка, а не снимок объектива, и остаётся.
public struct FocusMarkBook: Equatable, Sendable {
    /// Метки, которые есть.
    public static let letters: [Character] = ["A", "B"]

    private var marks: [String: FocusMarks] = [:]
    /// В единицах какого режима записаны метки камеры; нет записи — меток не ставили.
    private var modes: [String: FocusPull.Mode] = [:]

    public init() {}

    /// Метки камеры `camera` в единицах режима `mode`; нет камеры в книге — пустые, с временем по умолчанию.
    public func marks(for camera: String, mode: FocusPull.Mode) -> FocusMarks {
        var stored = marks[camera] ?? FocusMarks()
        if let recorded = modes[camera], recorded != mode {
            stored.a = nil
            stored.b = nil
        }
        return stored
    }

    /// Запомнить `position` в метку `mark` («A» или «B», другая буква и не-число игнорируются). Метки другого режима при этом
    /// уходят: рядом с числами новых единиц им не место.
    public mutating func save(_ mark: Character, at position: Double, for camera: String, mode: FocusPull.Mode) {
        guard Self.letters.contains(mark), position.isFinite else { return }
        var current = marks(for: camera, mode: mode)
        current[mark] = position
        marks[camera] = current
        modes[camera] = mode
    }

    /// Стереть метку; пустую стирать нечего.
    public mutating func clear(_ mark: Character, for camera: String, mode: FocusPull.Mode) {
        var current = marks(for: camera, mode: mode)
        guard current[mark] != nil else { return }
        current[mark] = nil
        marks[camera] = current
    }

    /// Время перевода камеры, секунд.
    public func duration(for camera: String) -> Double {
        marks[camera]?.duration ?? FocusMarks().duration
    }

    /// Следующее время по кругу 0 → 1 → 2 → 4 → 8 → 0; метки не трогает.
    public mutating func cycleDuration(for camera: String) {
        var stored = marks[camera] ?? FocusMarks()
        stored.cycleDuration()
        marks[camera] = stored
    }

    /// Поставить время перевода: только из списка (0, 1, 2, 4, 8). false — другое число, ничего не изменилось.
    @discardableResult
    public mutating func setDuration(_ seconds: Double, for camera: String) -> Bool {
        guard FocusPull.durations.contains(seconds) else { return false }
        var stored = marks[camera] ?? FocusMarks()
        stored.duration = seconds
        marks[camera] = stored
        return true
    }

    /// Камера сообщила о смене объектива: обе метки стёрты, время перевода осталось.
    public mutating func lensChanged(for camera: String) {
        guard var stored = marks[camera] else { return }
        stored.a = nil
        stored.b = nil
        marks[camera] = stored
        modes[camera] = nil
    }

    // MARK: Касание кнопки метки

    /// Что делает касание кнопки метки.
    public enum Tap: Equatable, Sendable {
        /// Метка пуста: запомнить, где фокус сейчас.
        case save
        /// Метка задана: перевести фокус к ней.
        case go
        /// Фокус едет к этой метке: остановить.
        case stop
    }

    /// Касание кнопки `mark`: едущая метка — стоп, пустая — запомнить, заданная — ехать (в том числе когда фокус едет к другой метке).
    public static func tap(on mark: Character, marks: FocusMarks, moving: Character?) -> Tap {
        if moving == mark { return .stop }
        return marks[mark] == nil ? .save : .go
    }
}

extension FocusMarks {
    /// Положение метки «A» или «B»; другая буква — nil, и присвоить ей нельзя.
    public subscript(mark: Character) -> Double? {
        get { mark == "A" ? a : mark == "B" ? b : nil }
        set {
            if mark == "A" {
                a = newValue
            } else if mark == "B" {
                b = newValue
            }
        }
    }
}

/// Положение фокуса и способ его переводить — по свойствам камеры.
public enum FocusPosition {
    /// Как у этой камеры переводить фокус между метками: абсолютно, если она перечисляет 0xE042, он открыт на запись и 0xE043
    /// отдаёт положение; иначе шагами Near/Far, если отдаёт 0xD24C; иначе (положения нет — метку запомнить нечем) nil.
    public static func mode(_ properties: [UInt16: SonyProperty]) -> FocusPull.Mode? {
        if let setting = properties[SonyProp.focusPositionSetting], setting.isWritable,
           current(properties, mode: .absolute) != nil {
            return .absolute
        }
        return current(properties, mode: .steps) != nil ? .steps : nil
    }

    /// Где фокус сейчас в единицах режима: 0xE043 (0…65535) или 0xD24C (0…100). Свойства нет или оно закрыто — nil.
    public static func current(_ properties: [UInt16: SonyProperty], mode: FocusPull.Mode) -> Double? {
        let code = mode == .absolute ? SonyProp.focusPositionCurrent : SonyProp.focalPosition
        guard let property = properties[code], property.enabled != 0, let value = property.current.intValue else { return nil }
        return Double(value)
    }

    /// Вся шкала режима: 65535 у абсолютного фокуса, 100 у шагов.
    public static func scale(_ mode: FocusPull.Mode) -> Double {
        mode == .absolute ? 65535 : 100
    }

    /// Какая доля шкалы 0…1 приходится на положение (метка на линейке); не-число — 0.
    public static func fraction(of position: Double, mode: FocusPull.Mode) -> Double {
        let share = position / scale(mode)
        return share.isNaN ? 0 : min(max(share, 0), 1)
    }

    /// Насколько близко фокус уже «у метки» и ехать не нужно: 0,5 % шкалы у абсолютного, `max(1, k/2)` при k₀ = 3,5 у шагов —
    /// столько же, сколько допускает сам `FocusPull`.
    public static func tolerance(_ mode: FocusPull.Mode) -> Double {
        mode == .absolute ? 0.005 * 65535 : 1.75
    }
}
