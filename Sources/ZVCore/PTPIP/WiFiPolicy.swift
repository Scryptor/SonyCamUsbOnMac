import Foundation

/// Когда и к кому подключаться по Wi-Fi. Без сети и таймеров, чтобы проверять тестами.
public struct WiFiPolicy: Sendable {
    /// Неудачные попытки подряд к камере, которую сейчас видно в сети.
    public private(set) var failures = 0
    /// Оборвавшиеся подряд сеансы — от них растёт пауза перед новой попыткой.
    public private(set) var sessionLosses = 0
    public private(set) var nextAttempt = Date.distantPast

    public init() {}

    /// Стучаться ли сейчас: Wi-Fi включён или идёт сопряжение; после обрыва — выждав паузу (сопряжение не ждёт).
    public func mayAttempt(enabled: Bool, pairing: Bool, now: Date) -> Bool {
        pairing || (enabled && now >= nextAttempt)
    }

    /// Приветствие — только камере, с которой программа уже работала, или при сопряжении:
    /// камера в режиме Pairing показывает любой входящий запрос, и чужой стук мешал бы сопрягать другое устройство.
    public func maySendInit(paired: Bool, pairing: Bool) -> Bool { paired || pairing }

    /// Неудачная попытка. Считаем только попытки к камере, найденной в сети сейчас: адрес из памяти мог устареть.
    public mutating func attemptFailed(discovered: Bool) {
        if discovered { failures += 1 }
    }

    /// Камера видна, но трижды подряд не ответила — похоже, держит прошлое подключение.
    public var showsStaleHint: Bool { failures >= 3 }

    /// В сети камеры не видно — счёт неудач с начала.
    public mutating func nothingFound() { failures = 0 }

    /// Сеанс начат.
    public mutating func connected() { failures = 0 }

    /// Сеанс дошёл до готовности — прошлые обрывы забыты.
    public mutating func sessionReady() {
        sessionLosses = 0
        nextAttempt = .distantPast
    }

    /// Сеанс оборвался. Пауза перед новой попыткой — 2, 4, 8, 10, 10… с, как у USB. Возвращает её в секундах.
    @discardableResult
    public mutating func sessionLost(now: Date) -> Int {
        sessionLosses += 1
        let seconds = min(1 << min(sessionLosses, 4), 10)
        nextAttempt = now.addingTimeInterval(TimeInterval(seconds))
        return seconds
    }

    // MARK: - Тихое восстановление оборвавшегося сеанса

    /// Сколько восстанавливаем сеанс тихо — с последним кадром на экране, по тому же адресу, без поиска.
    /// Дальше — обычное ожидание с поиском камеры в сети.
    public static let reconnectWindow: TimeInterval = 60

    /// Пауза перед попыткой восстановить сеанс: первая — сразу, дальше 0,5, 1, 2, 3, 5, 5… с.
    public static func reconnectDelay(attempt: Int) -> TimeInterval {
        let delays: [TimeInterval] = [0, 0.5, 1, 2, 3]
        return attempt < delays.count ? delays[max(attempt, 0)] : 5
    }

    /// Отказ камеры в приветствии (Init Fail): забывать ли сопряжение. 1 — камера программу не знает;
    /// 2 — занята, 3 — без причины: это повторяется позже, сопряжение не трогаем.
    public static func forgetsPairing(initFailReason reason: UInt32) -> Bool { reason == 1 }
}
