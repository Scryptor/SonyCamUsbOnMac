import Foundation

/// События новых камер Sony, которые сеанс передаёт слушателям (`CameraSession.events`). Изменение свойств (0xC203) сеанс
/// разбирает сам — перечитывает свойства, и слушатель узнаёт о нём из `snapshots`.
public enum CameraEvent: Equatable, Sendable {
    /// 0xC20F: список подписей изменился. `type` — тип списка из параметра события; 0 — параметра не было, значит, все списки:
    /// заново 0x9215 с этим типом.
    case displayListChanged(type: UInt32)
    /// 0xC21B: данные объектива изменились — объектив сменили: заново 0x9223.
    case lensChanged
    /// 0xC218: запись в 0xE042 закончилась. `reached` — параметр события равен 1: фокус дошёл до цели.
    case focusPositionResult(reached: Bool)
}

/// Версия кода производителя — ответ 0x9216 (SonyOp.getVendorCodeVersion).
public enum VendorCodeVersion {
    /// Версию Sony пишет ×100: 310 (0x0136) — «3.10», 100 — «1.00».
    public static func label(_ version: Int) -> String {
        String(format: "%d.%02d", version / 100, version % 100)
    }
}
