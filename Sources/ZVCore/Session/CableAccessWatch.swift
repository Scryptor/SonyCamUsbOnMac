import Foundation

/// Когда подсказать про доступ к камере на кабеле. После установки или обновления программы macOS спрашивает,
/// можно ли ей читать «файлы на съёмном томе», и до ответа не отдаёт камеру — хотя на шине USB она уже есть.
/// Подсказка появляется, если камера на шине, а программе не видна дольше 5 с.
public struct CableAccessWatch: Sendable {
    public static let delay: TimeInterval = 5
    private var since: Date?

    public init() {}

    /// onBus — камера Sony на шине USB; visible — macOS отдала её программе. Возвращает, показывать ли подсказку.
    public mutating func update(onBus: Bool, visible: Bool, now: Date) -> Bool {
        guard onBus, !visible else {
            since = nil
            return false
        }
        let start = since ?? now
        since = start
        return now.timeIntervalSince(start) >= Self.delay
    }
}
