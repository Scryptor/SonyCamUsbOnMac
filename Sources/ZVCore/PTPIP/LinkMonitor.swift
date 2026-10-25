import Foundation

/// Почему транспорт PTP/IP закрылся сам.
public enum LinkLoss: Equatable, Sendable {
    /// Соединение оборвалось: RST, EOF или ошибка отправки.
    case broken
    /// Связь вернулась (камера отвечает на ping), а TCP так и молчит — сбросили соединения сами.
    case resetAfterOutage
    /// Связь есть (пробы отвечают), а на PTP камера молчит.
    case cameraSilent
    /// Связи нет слишком долго.
    case noLink

    /// После такого обрыва есть смысл сразу переподключаться: камера, скорее всего, рядом и ждёт.
    public var reconnectsAtOnce: Bool { self != .noLink }
}

/// Что сейчас со связью — для экрана.
public struct LinkStatus: Equatable, Sendable {
    /// С какого момента ничего не приходит, если это уже провал связи; nil — связь есть.
    public var outageSince: Date?
    /// Время ответа камеры на пробу, с.
    public var rtt: TimeInterval?

    public init(outageSince: Date? = nil, rtt: TimeInterval? = nil) {
        self.outageSince = outageSince
        self.rtt = rtt
    }
}

/// Слежение за связью PTP/IP: по времени прихода байтов на оба соединения, пробам и ping камеры.
/// Пауза — не обрыв: пока связи нет, сеанс держим (TCP сам доставит данные, когда она вернётся), а рвём,
/// только когда связь вернулась, а TCP молчит, или когда связи нет слишком долго. Без сети и таймеров — для тестов.
public struct LinkMonitor: Sendable {
    public struct Timing: Sendable {
        /// Как часто слать пробу по событийному каналу.
        public var heartbeat: TimeInterval = 2
        /// Проба без ответа так долго — камера, видимо, на пробы не отвечает: можно слать следующую.
        public var probeGiveUp: TimeInterval = 5
        /// Ждём ответа, а ничего не приходит дольше этого, — провал связи.
        public var outageAfter: TimeInterval = 2.5
        /// Как часто пинговать камеру во время провала.
        public var pingEvery: TimeInterval = 1
        /// Камера отвечает на ping, а байтов нет так долго — TCP застрял, соединения сбрасываем.
        public var stuckAfterLinkBack: TimeInterval = 3
        /// Провал дольше этого — сдаёмся.
        public var giveUpAfter: TimeInterval = 180
        /// Ответа на операцию нет так долго, хотя пробы отвечают, — камера не отвечает.
        public var cameraSilentAfter: TimeInterval = 15

        public init() {}
    }

    public enum Action: Equatable, Sendable {
        case sendProbe
        case ping
        case close(LinkLoss)
    }

    public let timing: Timing
    public private(set) var commandRx: Date
    public private(set) var eventRx: Date
    public private(set) var outageSince: Date?
    /// Когда кончился последний провал: тишина до него не идёт ни в «камера не отвечает», ни в тайм-ауты.
    public private(set) var outageEndedAt = Date.distantPast
    /// Когда ping впервые ответил во время провала.
    public private(set) var linkBackAt: Date?
    public private(set) var rtt: TimeInterval?
    private var probeSentAt: Date?
    /// Камера хоть раз ответила на пробу: без этого неотвеченная проба — не признак провала.
    private var probesAnswered = false
    private var lastProbeAt = Date.distantPast
    private var lastPingAt = Date.distantPast
    /// С какого момента ждём ответа: самая старая операция, на которую не пришёл ответ (тайм-аут у вызывающего —
    /// он спросит снова — отсчёт не обнуляет); nil — камера ничего не должна.
    private var waitingSince: Date?

    public init(now: Date, timing: Timing = Timing()) {
        self.timing = timing
        commandRx = now
        eventRx = now
    }

    public var isOutage: Bool { outageSince != nil }
    public var status: LinkStatus { LinkStatus(outageSince: outageSince, rtt: rtt) }

    /// Пришли байты по командному соединению.
    public mutating func commandReceived(at now: Date) {
        commandRx = max(commandRx, now)
        endOutage(at: now)
    }

    /// Пришли байты по событийному соединению.
    public mutating func eventReceived(at now: Date) {
        eventRx = max(eventRx, now)
        endOutage(at: now)
    }

    public mutating func probeAnswered(at now: Date) {
        // ответ на пробу, пережившую провал, — не время отклика
        if let sent = probeSentAt, now.timeIntervalSince(sent) < timing.outageAfter { rtt = now.timeIntervalSince(sent) }
        probeSentAt = nil
        probesAnswered = true
        eventReceived(at: now)
    }

    public mutating func operationStarted(at now: Date) {
        if waitingSince == nil { waitingSince = now }
    }

    /// Пришёл ответ на операцию — камера ничего не должна.
    public mutating func operationAnswered() { waitingSince = nil }

    public mutating func pinged(_ answered: Bool, at now: Date) {
        guard isOutage else { return }
        if answered {
            if linkBackAt == nil { linkBackAt = now }
        } else {
            // связь пропала снова — ждём следующего ответа
            linkBackAt = nil
        }
    }

    /// Раз в ~250 мс: что сделать сейчас.
    public mutating func tick(now: Date) -> [Action] {
        if outageSince == nil, let silentFrom = silentFrom, now.timeIntervalSince(silentFrom) >= timing.outageAfter {
            outageSince = silentFrom
            linkBackAt = nil
            lastPingAt = .distantPast
        }
        if let since = outageSince {
            if now.timeIntervalSince(since) >= timing.giveUpAfter { return [.close(.noLink)] }
            if let back = linkBackAt, now.timeIntervalSince(back) >= timing.stuckAfterLinkBack {
                return [.close(.resetAfterOutage)]
            }
            // пингуем и после первого ответа: связь могла пропасть снова — тогда RST ушёл бы в пустоту
            guard now.timeIntervalSince(lastPingAt) >= timing.pingEvery else { return [] }
            lastPingAt = now
            return [.ping]
        }
        if let waiting = waitingSince, now.timeIntervalSince(max(waiting, commandRx, outageEndedAt)) >= timing.cameraSilentAfter,
           now.timeIntervalSince(eventRx) < timing.outageAfter {
            return [.close(.cameraSilent)]
        }
        let probeOpen = probeSentAt.map { now.timeIntervalSince($0) < timing.probeGiveUp } ?? false
        guard !probeOpen, now.timeIntervalSince(lastProbeAt) >= timing.heartbeat else { return [] }
        lastProbeAt = now
        probeSentAt = now
        return [.sendProbe]
    }

    /// С какого момента ждём ответа и ничего не получаем; nil — ничего и не ждём.
    private var silentFrom: Date? {
        var expected: [Date] = []
        if let waitingSince { expected.append(waitingSince) }
        if probesAnswered, let probeSentAt { expected.append(probeSentAt) }
        guard let earliest = expected.min() else { return nil }
        return max(max(commandRx, eventRx), earliest)
    }

    private mutating func endOutage(at now: Date) {
        guard outageSince != nil else { return }
        outageSince = nil
        outageEndedAt = now
        linkBackAt = nil
    }
}
