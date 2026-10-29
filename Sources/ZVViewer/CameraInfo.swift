import Foundation
import Observation
import ZVCore

/// Что камера сообщает о себе сверх свойств (спека §7.3–7.4): подписи списков (0x9215), версия кода производителя (0x9216) и
/// данные объектива (0x9223). Спрашиваются только у камер, которые называют эти операции в GetDeviceInfo; у ZV-E10 класс остаётся
/// пустым и камеру ни о чём не спрашивает. Ничего отсюда не задерживает подключение: запросы идут после того, как сеанс готов,
/// каждый в своей задаче, а ошибки уходят в журнал панели отладки — подписи берутся тогда из таблиц Sony, расстояний нет.
@MainActor
@Observable
final class CameraInfo {
    /// Подписи значений от камеры (Creative Look и др.).
    private(set) var strings = DisplayStrings()
    /// Версия кода производителя ×100 (310 — «3.10»); nil — нет.
    private(set) var vendorCodeVersion: Int?
    /// Данные объектива; nil — их нет: операции нет, 0xE086 не 1, не вышло прочитать или объектив только что сменили.
    private(set) var lens: LensInformation?

    /// Строка в журнал (панель отладки и системный журнал); контроллер подставляет свой `note`.
    @ObservationIgnored var note: (String) -> Void = { _ in }
    /// Растёт, когда сеанс уходит: запоздалый ответ прежнего сеанса ничего не перепишет.
    @ObservationIgnored private var generation = 0
    /// Растёт с каждым запросом данных объектива: из двух запросов подряд остаётся ответ на последний.
    @ObservationIgnored private var lensRequest = 0
    /// Сеанс уже опрошен (`begin`) и не оборван: события и свойства принимаются.
    @ObservationIgnored private var active = false
    /// 0xE086 в прошлых свойствах был 1: данные объектива просят, только когда он стал 1.
    @ObservationIgnored private var lensEnabled = false

    /// Сеанс готов: подписи и версия кода — один раз, данные объектива — если 0xE086 = 1. `properties` — последние свойства камеры.
    func begin(_ session: CameraSession, properties: [UInt16: SonyProperty]) {
        generation += 1
        let token = generation
        active = true
        lensEnabled = properties[SonyProp.lensInfoEnabled]?.current.intValue == 1
        Task { [weak self] in
            do {
                // все списки; один сломанный список лишил бы остальных — тогда один Creative Look, а там и названия из таблиц
                let load = try await session.loadDisplayStrings()
                guard let self, self.generation == token else { return }
                for failure in load.failures {
                    self.note("подписи камеры (0x9215, тип 0x\(String(failure.type, radix: 16, uppercase: true))): "
                              + Self.describe(failure.error))
                }
                if !load.strings.lists.isEmpty {
                    self.strings = load.strings
                    self.note("подписи камеры (0x9215): " + Self.summary(load.strings))
                }
            } catch {
                self?.note("подписи камеры (0x9215): \(Self.describe(error))")
            }
            do {
                let version = try await session.vendorCodeVersion()
                guard let self, self.generation == token else { return }
                self.vendorCodeVersion = version
                if let version { self.note("версия кода производителя (0x9216): \(VendorCodeVersion.label(version))") }
            } catch {
                self?.note("версия кода производителя (0x9216): \(Self.describe(error))")
            }
        }
        fetchLens(session)
    }

    /// Сеанс оборвался, но камеру ждём обратно (тихое восстановление): то, что о ней известно, остаётся на экране, пока не придёт
    /// новое, а запросы прежнего сеанса отменяются.
    func suspend() {
        generation += 1
        lensRequest += 1
        active = false
    }

    /// Камеры больше нет: ничего о ней не помним.
    func reset() {
        suspend()
        strings = DisplayStrings()
        vendorCodeVersion = nil
        lens = nil
        lensEnabled = false
    }

    /// Камера прислала событие.
    func handle(_ event: CameraEvent, session: CameraSession) {
        guard active else { return }
        switch event {
        case let .displayListChanged(type):
            fetchStrings(type: type, session)
        case .lensChanged:
            // таблица прежнего объектива теперь неверна: пока нет новой, расстояний нет
            lens = nil
            fetchLens(session)
        case let .focusPositionResult(reached):
            note("абсолютный фокус (0xC218): " + (reached ? "дошёл до цели" : "до цели не дошёл"))
        }
    }

    /// Пришли свежие свойства: 0xE086 стал 1 — данные объектива готовы, спрашиваем; стал не 1 — расстояний больше нет.
    func propertiesChanged(_ properties: [UInt16: SonyProperty], session: CameraSession) {
        guard active else { return }
        let enabled = properties[SonyProp.lensInfoEnabled]?.current.intValue == 1
        guard enabled != lensEnabled else { return }
        lensEnabled = enabled
        if enabled {
            fetchLens(session)
        } else {
            lensRequest += 1
            lens = nil
            note("данные объектива: 0xE086 больше не 1, расстояний нет")
        }
    }

    // MARK: - Запросы

    private func fetchStrings(type: UInt32, _ session: CameraSession) {
        let token = generation
        Task { [weak self] in
            do {
                let fresh = try await session.displayStrings(type: type)
                guard let self, self.generation == token else { return }
                self.strings.replace(type: type, with: fresh)
                self.note("подписи камеры (0xC20F, тип 0x\(String(type, radix: 16, uppercase: true))): "
                          + (fresh.lists.isEmpty ? "списка больше нет" : Self.summary(fresh)))
            } catch {
                self?.note("подписи камеры (0x9215, тип 0x\(String(type, radix: 16, uppercase: true))): \(Self.describe(error))")
            }
        }
    }

    private func fetchLens(_ session: CameraSession) {
        lensRequest += 1
        let request = lensRequest
        let token = generation
        Task { [weak self] in
            do {
                let table = try await session.lensInformation()
                guard let self, self.generation == token, self.lensRequest == request else { return }
                self.lens = table
                if let table { self.note("данные объектива (0x9223): " + Self.summary(table)) }
            } catch {
                guard let self, self.generation == token, self.lensRequest == request else { return }
                // лучше без расстояния, чем с неверным
                self.lens = nil
                self.note("данные объектива (0x9223): \(Self.describe(error))")
            }
        }
    }

    // MARK: - Подписи

    /// «0x13 — 18 строк, 0x15 — 2 строки».
    nonisolated static func summary(_ strings: DisplayStrings) -> String {
        strings.lists.keys.sorted().map { type in
            "0x\(String(type, radix: 16, uppercase: true)) — \(strings.lists[type]?.count ?? 0) шт."
        }.joined(separator: ", ")
    }

    /// «таблиц 1, пар 12, шкала 0…65535».
    nonisolated static func summary(_ lens: LensInformation) -> String {
        "таблиц \(lens.tableCount), пар \(lens.tables.map { String($0.count) }.joined(separator: " + ")), "
            + "шкала 0…\(lens.absoluteScale ? "65535" : "100")"
    }

    nonisolated static func describe(_ error: Error) -> String {
        switch error {
        case let SessionError.operationFailed(_, code): return String(format: "камера ответила 0x%04X", code)
        case is PTPParseError: return "ответ не разобран (\(error))"
        case TransportError.timeout: return "нет ответа"
        case TransportError.disconnected: return "соединение закрыто"
        case let TransportError.failed(text): return text
        default: return "\(error)"
        }
    }
}
