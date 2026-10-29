import Foundation

/// Перевод фокуса от метки к метке за заданное время — конечный автомат без ввода-вывода: ни камеры, ни часов, ни потоков.
/// Mac и Android ведут его одинаково:
///
/// - контроллер зовёт `next(elapsed:position:)` на каждом тике (`tick`, 100 мс) — и, если хочет, ещё раз сразу после того, как
///   камера ответила на команду, — с самым свежим известным положением. Тики пропускать не нужно, даже пока команда без
///   ответа и положение не обновилось: автомат сам ждёт его (см. ниже). `elapsed` — секунды от начала перевода, `position` —
///   в единицах режима;
/// - исполняет ответ: `.write` — записать 0xE042, `.nearFar` — послать шаг Near/Far, `.wait` — ничего до следующего вызова,
///   `.finish` — перевод кончился (чем — в `Outcome`, подпись — `caption`), больше `next` не зовут;
/// - при любом ручном движении фокуса, выходе из ручного фокуса, обрыве связи или касании едущей метки зовёт `cancel()`.
///
/// **Абсолютный фокус** (0…65535). Пока время идёт — `.write(точка кривой)`, повтор прошлого числа не нужен (`.wait`). На
/// конце времени (и при времени 0 — сразу) цель пишется один раз и дальше `.wait`, пока положение не станет в пределах 0,5 %
/// шкалы от цели (327,675 единицы) — тогда `.finish(.reached)`. Не успело за 2 с после конца — `.finish(.timedOut)`.
///
/// **Шаги Near/Far** (0…100, отрицательный шаг уменьшает положение — фокус ближе):
///
/// - команда считается отработанной, когда положение стало другим, чем было перед ней, или когда с неё прошло 0,3 с (тогда
///   положение берётся как есть, и если оно прежнее — камера не двинулась). Пока нет ни того ни другого, `next` отвечает
///   `.wait`: ответ камеры и свежее положение могли просто не дойти. Срок (тайм-аут) и `cancel()` при этом работают, а новая
///   команда уходит только после того, как прошлая отработана;
/// - у отработанной команды n сдвиг Δ = положение − положение до неё. При |n| ≥ 3 и Δ ≠ 0 коэффициент (единиц положения на
///   шаг) уточняется: k ← 0,7·k + 0,3·(Δ/n), в пределах 0,2…20. Три команды подряд в одну сторону с Δ = 0 — `.finish(.stuck)`:
///   дальше камера не двигает (у края шкалы так бывает);
/// - ошибка e — кривая в этот момент минус положение; допуск τ = max(1, k/2). В допуске — `.wait` до конца времени и
///   `.finish(.reached)` на конце. Вне допуска — команда `round(e / k)` в пределах −7…7, но не 0 (шаг ±1 сдвигает фокус
///   примерно на k, ближе камера не подойдёт);
/// - после конца времени два шага ±1 подряд в разные стороны, второй из которых перескочил цель, — тоже `.reached`:
///   это ближайшее, куда камера может встать;
/// - команды уходят не чаще одной за тик (с запасом 10 % на дрожание таймера): вызов сразу после ответа камеры только
///   учитывает результат, а следующую команду автомат отдаёт на ближайшем тике;
/// - не кончилось за 3 с после конца времени — `.finish(.timedOut)`.
///
/// После любого конца `next` отвечает тем же самым. Метки и положения — числа в единицах режима: автомат шкалу не проверяет,
/// кроме записи в регистр (она обрезается до 0…65535). Любые конечные числа, хоть ±1e308, он переваривает без остановки
/// программы: кривая считается взвешенной суммой, а не разностью меток, а не-число в регистре и в шаге даёт 0.
public struct FocusPull: Sendable {
    /// Как камера двигает фокус.
    public enum Mode: Equatable, Sendable {
        /// Записывается положение 0xE042 (0…65535), читается 0xE043 — у новых камер.
        case absolute
        /// Шаги Near/Far 0xD2D1 (−7…7) и положение 0xD24C (0…100) — у ZV-E10.
        case steps
    }

    /// Что делать контроллеру.
    public enum Action: Equatable, Sendable {
        /// Записать это в 0xE042 (абсолютный фокус).
        case write(UInt16)
        /// Послать шаг Near/Far; отрицательный — ближе, положение уменьшает.
        case nearFar(Int64)
        /// Ничего до следующего вызова.
        case wait
        /// Перевод кончился: чем — в `Outcome`.
        case finish(Outcome)
    }

    /// Чем кончился перевод.
    public enum Outcome: Equatable, Sendable {
        /// Фокус у метки (или ближе, чем камера может встать).
        case reached
        /// Три команды подряд в одну сторону ничего не сдвинули.
        case stuck
        /// Не успел: на конце времени фокус ещё далеко, а срок вышел.
        case timedOut
        /// Остановлен снаружи (`cancel`): подписи нет.
        case cancelled
    }

    /// Время перевода по кругу, секунд.
    public static let durations: [Double] = [0, 1, 2, 4, 8]
    /// Шаг расписания, секунд.
    public static let tick = 0.1

    public let mode: Mode
    /// Откуда и куда, в единицах режима.
    public let start: Double
    public let target: Double
    /// Сколько времени на путь, секунд; 0 — как можно быстрее.
    public let duration: Double
    /// Единиц положения на шаг Near/Far: начальное значение уточняется по ходу (режим шагов).
    public private(set) var gain: Double

    // MARK: Числа из спецификации

    /// «Готово» у абсолютного фокуса — в пределах 0,5 % шкалы.
    private static let absoluteTolerance = 0.005 * 65535
    /// Сколько ждать после конца времени, секунд: абсолютный фокус — 2, шаги — 3.
    private static let absoluteGrace = 2.0
    private static let stepsGrace = 3.0
    /// Самая большая команда Near/Far.
    private static let maxSteps = 7.0
    /// Сколько пустых команд подряд в одну сторону — «дальше камера не двигает».
    private static let stuckRun = 3
    /// Не чаще одной команды за тик, с запасом на дрожание таймера.
    private static let commandSpacing = tick * 0.9
    /// Сколько ждать новое положение после шага, секунд: пока оно прежнее и этого времени не прошло, «камера не двинулась» не
    /// засчитывается. Столько же (300 мс) контроллер ждёт свежий 0xD24C.
    private static let settleWindow = 0.3
    /// Коэффициент: пределы и начальное значение, если дали не число.
    private static let gainRange = 0.2...20.0
    private static let defaultGain = 3.5

    // MARK: Состояние

    /// Команда Near/Far, ответа на которую ещё не учли.
    private struct Command: Sendable {
        let steps: Int64
        /// Положение перед командой и время, когда она ушла.
        let before: Double
        let issuedAt: Double
    }

    private var outcome: Outcome?
    private var lastPosition: Double
    private var lastWritten: UInt16?
    private var pending: Command?
    private var lastCommandAt = -Double.infinity
    /// Сколько пустых команд подряд и в какую сторону (−1, 1; 0 — не было).
    private var zeroRun = 0
    private var zeroDirection = 0
    /// Сторона прошлого шага ±1 (0 — прошлая команда была другой) и признак «два шага ±1 в разные стороны перескочили цель».
    private var unitDirection = 0
    private var dithered = false

    /// Перевод из `start` в `target` за `duration` секунд. Не-числа читаются как 0, отрицательное время — как 0, начальный
    /// коэффициент `gain` — в пределах 0,2…20.
    public init(mode: Mode, from start: Double, to target: Double, duration: Double, gain: Double = 3.5) {
        self.mode = mode
        self.start = start.isFinite ? start : 0
        self.target = target.isFinite ? target : 0
        self.duration = duration.isFinite ? max(duration, 0) : 0
        self.gain = Self.sane(gain)
        lastPosition = self.start
    }

    /// Доля пройденного пути по последнему положению, которое видел автомат: 0…1 (1, если метка там же, где старт).
    public var progress: Double {
        let span = target - start
        guard span != 0 else { return 1 }
        let share = (lastPosition - start) / span
        return share.isNaN ? 0 : min(max(share, 0), 1)
    }

    /// Кривая перевода 3t² − 2t³: медленно трогается и медленно подходит. t обрезается в 0…1, не-число — 0.
    public static func ease(_ t: Double) -> Double {
        let x = t.isNaN ? 0 : min(max(t, 0), 1)
        return x * x * (3 - 2 * x)
    }

    /// Где должен быть фокус через `elapsed` секунд: от `start` к `target` по кривой, а на конце времени и дальше — `target`.
    /// При времени 0 — `target` сразу.
    public func desired(at elapsed: Double) -> Double {
        guard duration > 0 else { return target }
        let time = elapsed.isNaN ? 0 : elapsed
        if time >= duration { return target }
        // взвешенная сумма, а не start + (target − start)·s: разность двух далёких меток может не поместиться в Double
        let share = Self.ease(time / duration)
        return start * (1 - share) + target * share
    }

    /// Что делать сейчас. Зовётся по тику и после ответа камеры; правила — в описании типа. Положение — не-число: ничего не
    /// считаем, ждём (срок при этом идёт).
    public mutating func next(elapsed: Double, position: Double) -> Action {
        if let outcome { return .finish(outcome) }
        let time = elapsed.isNaN ? 0 : max(elapsed, 0)
        guard position.isFinite else { return time >= deadline ? end(.timedOut) : .wait }
        lastPosition = position
        switch mode {
        case .absolute: return nextAbsolute(time: time, position: position)
        case .steps: return nextSteps(time: time, position: position)
        }
    }

    /// Остановить перевод снаружи. Всегда отвечает `.finish(.cancelled)`; если перевод уже кончился, его исход не меняется —
    /// `next` по-прежнему отдаёт первый.
    @discardableResult
    public mutating func cancel() -> Action {
        if outcome == nil { outcome = .cancelled }
        return .finish(.cancelled)
    }

    /// Подпись к концу перевода у метки `mark` («A» или «B»); у остановленного снаружи подписи нет.
    public static func caption(_ outcome: Outcome, mark: String) -> String? {
        switch outcome {
        case .reached: return "Фокус у метки \(mark)"
        case .stuck: return "Дальше камера не двигает"
        case .timedOut: return "Не успел за 3 с"
        case .cancelled: return nil
        }
    }

    // MARK: Абсолютный фокус

    private mutating func nextAbsolute(time: Double, position: Double) -> Action {
        let near = abs(position - target) <= Self.absoluteTolerance
        if time < duration {
            // время идёт: точка кривой; то же число камере второй раз не нужно
            let value = Self.register(desired(at: time))
            guard value != lastWritten else { return .wait }
            lastWritten = value
            return .write(value)
        }
        if time >= deadline { return end(near ? .reached : .timedOut) }
        // конец времени: цель пишется один раз, потом ждём, пока камера до неё доедет
        let goal = Self.register(target)
        if lastWritten != goal {
            lastWritten = goal
            return .write(goal)
        }
        return near ? end(.reached) : .wait
    }

    // MARK: Шаги Near/Far

    private mutating func nextSteps(time: Double, position: Double) -> Action {
        if let command = pending {
            // положение прежнее и с команды не прошло 0,3 с: ответ камеры и свежее положение могли просто не дойти — «камера
            // не двинулась» ещё не засчитываем, ждём (срок при этом идёт)
            if position == command.before, time - command.issuedAt < Self.settleWindow {
                return time >= deadline ? end(.timedOut) : .wait
            }
            pending = nil
            settle(command, at: position)
        }
        let error = desired(at: time) - position
        let ended = time >= duration
        if abs(error) <= max(1, gain / 2) { return ended ? end(.reached) : .wait }
        // вне допуска. Ближе камера не подходит: два шага ±1 в разные стороны перескочили цель
        if ended, dithered { return end(.reached) }
        // дальше не двигает — только если и теперь надо в ту же сторону
        let direction = error > 0 ? 1 : -1
        if zeroRun >= Self.stuckRun, direction == zeroDirection { return end(.stuck) }
        if time >= deadline { return end(.timedOut) }
        // следующая команда — на ближайшем тике, а не сразу после ответа на прошлую
        if time - lastCommandAt < Self.commandSpacing { return .wait }
        var steps = Self.steps(from: error / gain)
        if steps == 0 { steps = Int64(direction) }
        pending = Command(steps: steps, before: position, issuedAt: time)
        lastCommandAt = time
        return .nearFar(steps)
    }

    /// Что вышло из команды: уточняет коэффициент, считает пустые команды и дребезг.
    private mutating func settle(_ command: Command, at position: Double) {
        let moved = position - command.before
        let sign = command.steps > 0 ? 1 : -1
        if abs(command.steps) >= 3, moved != 0 {
            gain = Self.sane(0.7 * gain + 0.3 * (moved / Double(command.steps)))
        }
        if moved == 0 {
            zeroRun = zeroDirection == sign ? zeroRun + 1 : 1
            zeroDirection = sign
        } else {
            zeroRun = 0
            zeroDirection = 0
        }
        if abs(command.steps) == 1 {
            let crossed = (command.before - target) * (position - target) < 0
            dithered = unitDirection == -sign && crossed
            unitDirection = sign
        } else {
            dithered = false
            unitDirection = 0
        }
    }

    // MARK: Общее

    /// Срок: конец времени и ещё 2 с (абсолютный фокус) или 3 с (шаги).
    private var deadline: Double {
        duration + (mode == .absolute ? Self.absoluteGrace : Self.stepsGrace)
    }

    private mutating func end(_ result: Outcome) -> Action {
        outcome = result
        return .finish(result)
    }

    /// Число для регистра 0xE042: целое в пределах 0…65535; не-число — 0, бесконечность — край. Не `private` ради проверки.
    static func register(_ value: Double) -> UInt16 {
        guard !value.isNaN else { return 0 }
        return UInt16(min(max(value.rounded(), 0), 65535))
    }

    /// Команда Near/Far из «шагов» (ошибка / k): целое в пределах −7…7; не-число — 0. Не `private` ради проверки.
    static func steps(from raw: Double) -> Int64 {
        guard !raw.isNaN else { return 0 }
        return Int64(min(max(raw.rounded(), -maxSteps), maxSteps))
    }

    /// Коэффициент в разумных пределах; не-число — начальное значение.
    private static func sane(_ gain: Double) -> Double {
        gain.isNaN ? defaultGain : min(max(gain, gainRange.lowerBound), gainRange.upperBound)
    }
}

/// Метки фокуса камеры: A и B — положения в единицах её режима (0…65535 у абсолютного фокуса, 0…100 у шагов), и время
/// перевода к ним.
public struct FocusMarks: Equatable, Sendable {
    /// Положение метки; nil — метка пуста.
    public var a: Double?
    public var b: Double?
    /// Время перевода, секунд (по умолчанию 2).
    public var duration: Double

    public init(a: Double? = nil, b: Double? = nil, duration: Double = 2) {
        self.a = a
        self.b = b
        self.duration = duration
    }

    /// Следующее время по кругу: 0 → 1 → 2 → 4 → 8 → 0. Значение не из списка — ближайшее большее из списка, а за краем — 0.
    public mutating func cycleDuration() {
        let all = FocusPull.durations
        duration = all.first { $0 > duration } ?? all[0]
    }
}
