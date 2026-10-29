import Foundation

/// Камеры, которые не ответили на необязательную операцию новых камер Sony (0x9215, 0x9216, 0x9223) за время ожидания транспорта.
///
/// Тайм-аут значит, что камера называет операцию в GetDeviceInfo, а отвечать на неё не умеет или не хочет. Слежение за связью
/// закрывает такой сеанс как «камера не отвечает» (15 с без ответа на операцию), тихое переподключение открывает следующий — и
/// программа, спросив то же, снова ждёт без ответа: так по кругу. Поэтому такую камеру о новых операциях больше не спрашиваем
/// до перезапуска программы: подписи Creative Look тогда берутся из таблиц Sony, расстояний на шкале нет. Камера — её id из списка
/// знакомых камер: тот же id — следующий сеанс той же камеры.
public struct SilentCameras: Equatable, Sendable {
    private var cameras: Set<String> = []

    public init() {}

    /// Ошибка необязательной операции камеры `camera`. Тайм-аут записывает камеру; всё остальное — отказ камеры, разбор, обрыв
    /// связи — нет: они ничего не говорят о том, что в следующий раз будет то же. true — камера записана сейчас впервые (в журнал
    /// строка одна). Без id камеры (nil) запомнить некого.
    @discardableResult
    public mutating func note(_ error: any Error, camera: String?) -> Bool {
        guard let camera, case TransportError.timeout? = error as? TransportError else { return false }
        return cameras.insert(camera).inserted
    }

    /// Этой камере необязательные операции не посылаем.
    public func skips(_ camera: String?) -> Bool {
        camera.map(cameras.contains) ?? false
    }
}
