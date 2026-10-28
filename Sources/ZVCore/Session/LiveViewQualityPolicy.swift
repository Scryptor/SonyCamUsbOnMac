import Foundation

/// Качество превью камеры (свойство 0xD26A LiveViewImageQuality).
public enum LiveViewQuality: Int64, Sendable {
    /// 640×360, ~34 КБ на кадр.
    case low = 1
    /// 1024×576, ~72 КБ на кадр.
    case high = 2

    /// Уровень по ширине кадра: камера отдаёт 1024 на высоком и 640 на низком.
    public init?(frameWidth: Int) {
        switch frameWidth {
        case 1024...: self = .high
        case 1..<1024: self = .low
        default: return nil
        }
    }

    /// Ширина кадра превью этого качества.
    public var frameWidth: Int { self == .high ? 1024 : 640 }

    /// Размер кадра этого качества при такой форме кадра (ширина к высоте): «1024×576», в фото 3:2 — «1024×683».
    public func sizeLabel(aspect: Double) -> String {
        let shape = aspect > 0 ? aspect : 16.0 / 9
        return "\(frameWidth)×\(Int((Double(frameWidth) / shape).rounded()))"
    }
}

/// Какое качество превью держать по Wi-Fi — выбор в подсказке к счётчику кадров и в настройках. По USB камера всегда
/// отдаёт полное: свойство качества там картинку не меняет.
public enum PreviewQualityMode: String, CaseIterable, Sendable {
    /// Решает программа: снижает до низкого, когда канал не тянет высокое, и возвращает, когда тянет снова.
    case auto
    /// Всегда высокое (1024 точки по ширине), даже если на слабом Wi-Fi картинка дёргается.
    case high
    /// Всегда низкое (640 точек): вдвое меньше трафика, плавнее на слабом Wi-Fi, но мельче.
    case low

    /// Что держать; nil — решает политика по каналу.
    public var fixed: LiveViewQuality? {
        switch self {
        case .auto: return nil
        case .high: return .high
        case .low: return .low
        }
    }

    /// Из настроек: новое значение, иначе прежний переключатель «снижать при слабом Wi-Fi» (выключен — всегда высокое).
    public static func restored(_ raw: String?, legacyAdaptive: Bool?) -> PreviewQualityMode {
        if let raw, let mode = PreviewQualityMode(rawValue: raw) { return mode }
        return legacyAdaptive == false ? .high : .auto
    }
}

/// Когда снижать и когда возвращать качество превью по Wi-Fi. Высокое при 25 к/с — ~14 Мбит/с, почти потолок
/// 2,4 ГГц камеры: на слабом канале оно не помещается, и картинка дёргается. Решаем по пропускной способности,
/// которую видно по самим кадрам, с гистерезисом. Без сети и таймеров — для тестов.
public struct LiveViewQualityPolicy: Sendable {
    public struct Tuning: Sendable {
        /// Ниже этой частоты на высоком (прогноз) — снижаем.
        public var downFPS: Double = 12
        /// Не ниже этой частоты на высоком (прогноз) — возвращаем.
        public var upFPS: Double = 18
        /// То же, пока картинка увеличена: чёткость важнее плавности, высокое держим до последнего.
        public var sharpDownFPS: Double = 6
        public var sharpUpFPS: Double = 10
        public var downWindow: TimeInterval = 5
        public var upWindow: TimeInterval = 10
        /// Столько кадров нужно в окне, чтобы судить о канале.
        public var minDownSamples = 8
        public var minUpSamples = 30
        /// Кадр шёл дольше — это замирание связи, а не узкий канал: в оценку полосы не идёт.
        public var stallSeconds: TimeInterval = 1.5
        /// Столько замираний за `stallWindow` — канал плохой, даже если между ними кадры идут быстро.
        public var stallsToDown = 3
        public var stallWindow: TimeInterval = 20
        /// Сколько выждать на низком перед попыткой вернуть высокое.
        public var initialHold: TimeInterval = 15
        public var maxHold: TimeInterval = 300
        /// Подъём не удался, если пришлось спуститься раньше этого.
        public var failedUpWithin: TimeInterval = 15
        /// Столько устойчиво на высоком — выдержка снова короткая.
        public var stableAfter: TimeInterval = 120
        /// Камера отдаёт превью не чаще ~25–30 к/с.
        public var cameraFPS: Double = 25
        /// Камера не перешла на запрошенное качество за это время — запись не удалась, решаем заново.
        public var switchTimeout: TimeInterval = 5

        public init() {}
    }

    struct Sample {
        let at: Date
        let bytes: Int
        let seconds: TimeInterval
        let level: LiveViewQuality
    }

    public let tuning: Tuning
    /// Каким качеством сейчас идут кадры.
    public private(set) var level: LiveViewQuality = .high
    /// Выдержка на низком перед следующей попыткой вернуть высокое, с.
    public private(set) var hold: TimeInterval
    private var holdUntil = Date.distantPast
    private var levelSince: Date
    private var lastUpAt: Date?
    private var requested: (level: LiveViewQuality, at: Date)?
    private var samples: [Sample] = []
    private var rtts: [(at: Date, seconds: TimeInterval)] = []
    /// Средний размер кадра высокого качества — по увиденным кадрам, пока их нет — типичный.
    public private(set) var highFrameBytes: Double = 72 * 1024

    public init(now: Date, tuning: Tuning = Tuning()) {
        self.tuning = tuning
        hold = tuning.initialHold
        levelSince = now
    }

    /// Кадр получен: размер, сколько шёл запрос (от отправки до ответа), каким качеством.
    public mutating func record(bytes: Int, seconds: TimeInterval, level observed: LiveViewQuality, at now: Date) {
        if observed != level {
            // камера перешла (по нашей записи или её переключили не мы) — окна считаем от этого момента
            level = observed
            levelSince = now
            samples.removeAll()
        }
        if let wanted = requested, wanted.level == observed { requested = nil }
        if observed == .high { highFrameBytes = highFrameBytes * 0.9 + Double(bytes) * 0.1 }
        samples.append(Sample(at: now, bytes: bytes, seconds: seconds, level: observed))
        let keep = max(tuning.upWindow, tuning.stallWindow)
        samples.removeAll { now.timeIntervalSince($0.at) > keep }
    }

    /// Время короткого ответа камеры (AccessDenied, проба), с. Берётся минимум за 10 с.
    public mutating func noteRTT(_ seconds: TimeInterval, at now: Date) {
        rtts.append((now, min(max(seconds, 0.001), 0.5)))
        rtts.removeAll { now.timeIntervalSince($0.at) > 10 }
    }

    private func rtt(now: Date) -> TimeInterval {
        rtts.filter { now.timeIntervalSince($0.at) <= 10 }.map(\.seconds).min() ?? 0.01
    }

    /// На низком — сколько ещё ждать до попытки вернуть высокое (выдержка и окно на низком), с; на высоком — nil.
    public func secondsUntilUpAttempt(now: Date) -> TimeInterval? {
        guard level == .low else { return nil }
        return max(0, max(holdUntil.timeIntervalSince(now), tuning.upWindow - now.timeIntervalSince(levelSince)))
    }

    /// Прогноз частоты превью на высоком качестве по быстрым кадрам за окно; nil — данных мало.
    public func predictedHighFPS(now: Date, window: TimeInterval) -> Double? {
        let recent = samples.filter { now.timeIntervalSince($0.at) <= window && $0.seconds <= tuning.stallSeconds }
        guard !recent.isEmpty else { return nil }
        let rtt = rtt(now: now)
        let bytes = Double(recent.reduce(0) { $0 + $1.bytes })
        let busy = recent.reduce(0.0) { $0 + max($1.seconds - rtt, 0.002) }
        return min(tuning.cameraFPS, 1 / (rtt + highFrameBytes / (bytes / busy)))
    }

    /// Какое качество нужно сейчас; nil — оставить как есть. outage — связь пропала: о канале ничего не известно.
    /// sharp — картинка увеличена на экране: пороги частоты ниже (6 и 10 к/с вместо 12 и 18).
    public mutating func decide(now: Date, outage: Bool, sharp: Bool = false) -> LiveViewQuality? {
        if let wanted = requested {
            guard now.timeIntervalSince(wanted.at) > tuning.switchTimeout else { return nil }
            requested = nil
        }
        guard !outage else { return nil }
        let target: LiveViewQuality?
        switch level {
        case .high: target = shouldGoDown(now: now, below: sharp ? tuning.sharpDownFPS : tuning.downFPS) ? .low : nil
        case .low: target = shouldGoUp(now: now, from: sharp ? tuning.sharpUpFPS : tuning.upFPS) ? .high : nil
        }
        guard let target else {
            if level == .high, let up = lastUpAt, now.timeIntervalSince(up) >= tuning.stableAfter {
                hold = tuning.initialHold
                lastUpAt = nil
            }
            return nil
        }
        if target == .low {
            if let up = lastUpAt, now.timeIntervalSince(up) < tuning.failedUpWithin { hold = min(hold * 2, tuning.maxHold) }
            lastUpAt = nil
            holdUntil = now.addingTimeInterval(hold)
        } else {
            lastUpAt = now
        }
        requested = (target, now)
        return target
    }

    private func shouldGoDown(now: Date, below downFPS: Double) -> Bool {
        guard now.timeIntervalSince(levelSince) >= tuning.downWindow else { return false }
        let stalls = samples.filter { now.timeIntervalSince($0.at) <= tuning.stallWindow && $0.seconds > tuning.stallSeconds }
        if stalls.count >= tuning.stallsToDown { return true }
        let fast = samples.filter { now.timeIntervalSince($0.at) <= tuning.downWindow && $0.seconds <= tuning.stallSeconds }
        guard fast.count >= tuning.minDownSamples, let fps = predictedHighFPS(now: now, window: tuning.downWindow) else { return false }
        return fps < downFPS
    }

    private func shouldGoUp(now: Date, from upFPS: Double) -> Bool {
        guard now >= holdUntil, now.timeIntervalSince(levelSince) >= tuning.upWindow else { return false }
        let recent = samples.filter { now.timeIntervalSince($0.at) <= tuning.upWindow }
        guard recent.count >= tuning.minUpSamples, !recent.contains(where: { $0.seconds > tuning.stallSeconds }),
              let fps = predictedHighFPS(now: now, window: tuning.upWindow) else { return false }
        return fps >= upFPS
    }
}
