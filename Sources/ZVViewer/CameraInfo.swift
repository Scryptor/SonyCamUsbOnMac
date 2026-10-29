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
    /// Чьи это подписи, версия и таблица: id камеры из списка знакомых. Другая камера в следующем сеансе (по Wi-Fi Direct у всех
    /// один адрес, тихое переподключение может попасть не на ту) — прежнее ей не принадлежит.
    @ObservationIgnored private var camera: String?
    /// Камеры, которые не ответили на необязательную операцию: им новые операции не посылаем до перезапуска программы. Не
    /// сбрасывается вместе с остальным — следующий сеанс той же камеры должен про это помнить.
    @ObservationIgnored private var silent = SilentCameras()
    /// Исходы 0xC218, о которых уже записано в журнал: во время перевода событие приходит на каждую запись цели, раз в 100 мс, и
    /// журнал в 200 строк оборачивался бы за 20 с, вытесняя строки о подключении.
    @ObservationIgnored private var focusResultsNoted: Set<Bool> = []
    /// Растёт, когда сеанс уходит: запоздалый ответ прежнего сеанса ничего не перепишет.
    @ObservationIgnored private var generation = 0
    /// Растёт с каждым запросом данных объектива: из двух запросов подряд остаётся ответ на последний.
    @ObservationIgnored private var lensRequest = 0
    /// Сеанс уже опрошен (`begin`) и не оборван: события и свойства принимаются.
    @ObservationIgnored private var active = false
    /// Камеру можно спрашивать о новых операциях: она на них отвечала или ещё не пробовала.
    private var asking: Bool { active && !silent.skips(camera) }
    /// 0xE086 в прошлых свойствах был 1: данные объектива просят, только когда он стал 1.
    @ObservationIgnored private var lensEnabled = false

    /// Камера на связи названа (её DeviceInfo): если это не та, чьи подписи, версия и таблица здесь лежат, они стираются сразу —
    /// опрос идёт только через несколько секунд после готовности сеанса, а до него чужие названия подписывали бы значения этой
    /// камеры. Тихое переподключение по Wi-Fi Direct (у всех камер один адрес) может попасть не на ту.
    func identify(_ camera: String?) {
        if camera == nil || camera != self.camera {
            strings = DisplayStrings()
            vendorCodeVersion = nil
            lens = nil
        }
        self.camera = camera
    }

    /// Сеанс готов: подписи и версия кода — один раз, данные объектива — если 0xE086 = 1. `camera` — id камеры, `properties` —
    /// её последние свойства. Камера, которая раньше не ответила на необязательную операцию, не спрашивается совсем.
    func begin(_ session: CameraSession, camera: String?, properties: [UInt16: SonyProperty]) {
        generation += 1
        let token = generation
        identify(camera)
        focusResultsNoted = []
        lensEnabled = properties[SonyProp.lensInfoEnabled]?.current.intValue == 1
        active = true
        guard asking else { return }
        Task { [weak self] in
            do {
                // все списки; один сломанный список лишил бы остальных — тогда один Creative Look, а там и названия из таблиц
                let load = try await session.loadDisplayStrings()
                guard let self, self.generation == token else { return }
                for failure in load.failures {
                    self.note("подписи камеры (0x9215, тип 0x\(String(failure.type, radix: 16, uppercase: true))): "
                              + Self.describe(failure.error))
                }
                // что вышло, то и есть у камеры — и когда ничего: прежние подписи (камеры, которой уже нет) не остаются
                self.strings = load.strings
                if !load.strings.lists.isEmpty { self.note("подписи камеры (0x9215): " + Self.summary(load.strings)) }
            } catch {
                self?.failed(error, "подписи камеры (0x9215)", token: token)
            }
            // камера промолчала на подписи — вторую необязательную операцию ей не шлём
            guard let self, self.generation == token, self.asking else { return }
            do {
                let version = try await session.vendorCodeVersion()
                guard self.generation == token else { return }
                self.vendorCodeVersion = version
                if let version { self.note("версия кода производителя (0x9216): \(VendorCodeVersion.label(version))") }
            } catch {
                self.failed(error, "версия кода производителя (0x9216)", token: token)
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
        camera = nil
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
            if asking { fetchStrings(type: type, session) }
        case .lensChanged:
            // таблица прежнего объектива теперь неверна: пока нет новой (или совсем — камера не отвечает), расстояний нет
            lens = nil
            if asking { fetchLens(session) }
        case let .focusPositionResult(reached):
            // по одной строке на исход за сеанс: чтобы было видно, что камера шлёт 0xC218 и с каким параметром, но не по строке на
            // каждую запись цели во время перевода
            if focusResultsNoted.insert(reached).inserted {
                note("абсолютный фокус (0xC218): " + (reached ? "дошёл до цели" : "до цели не дошёл")
                     + " — дальше такие события в журнал не пишу")
            }
        }
    }

    /// Пришли свежие свойства: 0xE086 стал 1 — данные объектива готовы, спрашиваем; стал не 1 — расстояний больше нет.
    func propertiesChanged(_ properties: [UInt16: SonyProperty], session: CameraSession) {
        guard active else { return }
        let enabled = properties[SonyProp.lensInfoEnabled]?.current.intValue == 1
        guard enabled != lensEnabled else { return }
        lensEnabled = enabled
        if enabled {
            if asking { fetchLens(session) }
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
                self?.failed(error, "подписи камеры (0x9215, тип 0x\(String(type, radix: 16, uppercase: true)))", token: token)
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
                self.failed(error, "данные объектива (0x9223)", token: token)
            }
        }
    }

    /// Операция не вышла. Тайм-аут — камера не отвечает: она записывается (`SilentCameras`), в этом сеансе и в следующих ей новых
    /// операций больше не шлём, в журнал одна строка. Остальные ошибки — строка про саму операцию.
    private func failed(_ error: any Error, _ what: String, token: Int) {
        guard generation == token else { return }
        if case let TransportError.timeout(operation)? = error as? TransportError {
            if silent.note(error, camera: camera) {
                note(String(format: "камера не ответила на операцию 0x%04X за время ожидания — новые операции Sony (0x9215, 0x9216, "
                             + "0x9223) ей до перезапуска программы не посылаю: подписи Creative Look — из таблиц Sony, расстояний нет",
                             operation))
            }
        } else {
            note("\(what): \(Self.describe(error))")
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
