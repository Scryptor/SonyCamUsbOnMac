import Foundation
import Testing
@testable import ZVCore

// MARK: - Объектив и прогон

/// Лёгкая модель объектива с шагами Near/Far. Два вида.
///
/// Линейный (`Lens(at:gain:…)`): команда n сдвигает положение на n·g, округлённое от нуля на половинке (3,5 → 4, −3,5 → −4,
/// 2,5 → 3), но не за шкалу 0…`limit` (у настоящей — 100). Малая команда (|n| ≤ 3) у края шкалы, ближе d единиц, к краю не
/// двигает — «мёртвая зона». Так же ведёт себя поддельная камера (`scripts/fake_camera.py`, `--focus-gain` и
/// `--focus-deadband`), поэтому проверки на ней сойдутся с этими.
///
/// Таблица PZ 16-50 (`Lens.pz1650(at:)`): так на 16 мм отвечал настоящий объектив ZV-E10 при замерах 30 октября 2026 года
/// (`docs/protocol.md`, «Near/Far»), без разброса: |1| — 0 единиц (1, если до края шкалы не больше единицы), |2| — 1, |3| — 3,
/// |4| — 4, |5| — 5, |6| — 7, |7| — до края шкалы в ту сторону; за шкалу 0…100 не выходит. Так же отвечает поддельная
/// камера с `--focus-lens pz16-50`. Ответ на команду приходит через 0,4 с — это задаёт прогон (`runSlow`, `delay: 0.4`).
private struct Lens {
    private(set) var position: Double
    let gain: Double
    let deadband: Double
    let limit: Double
    /// Отвечает по таблице PZ 16-50, а не по усилению.
    let table: Bool

    init(at position: Double, gain: Double, deadband: Double = 0, limit: Double = 100, table: Bool = false) {
        self.position = position
        self.gain = gain
        self.deadband = deadband
        self.limit = limit
        self.table = table
    }

    /// PZ 16-50 на 16 мм: шкала 0…100, ответы по таблице.
    static func pz1650(at position: Double) -> Lens {
        Lens(at: position, gain: 1, table: true)
    }

    mutating func move(_ steps: Int64) {
        if table {
            moveByTable(steps)
            return
        }
        let toEdge = steps > 0 ? limit - position : position
        if steps != 0, abs(steps) <= 3, toEdge < deadband { return }
        let moved = (Double(steps) * gain).rounded(.toNearestOrAwayFromZero)
        position = min(max(position + moved, 0), limit)
    }

    private mutating func moveByTable(_ steps: Int64) {
        var moved = 0.0
        switch abs(steps) {
        case 0: moved = 0
        case 1: moved = (position <= 1 || position >= limit - 1) ? 1 : 0
        case 2: moved = 1
        case 3: moved = 3
        case 4: moved = 4
        case 5: moved = 5
        case 6: moved = 7
        default: moved = limit
        }
        position = min(max(position + (steps > 0 ? moved : -moved), 0), limit)
    }
}

/// Выполненная команда: когда ушла, какая и откуда докуда сдвинула объектив.
private struct Move {
    let time: Double
    let steps: Int64
    let before: Double
    let after: Double
}

/// Итог прогона шагового перевода.
private struct Run {
    /// Чем кончилось; nil — за время прогона не кончилось (или прогон остановили раньше).
    var outcome: FocusPull.Outcome?
    var finishedAt: Double?
    var moves: [Move] = []
    var pull: FocusPull
    var lens: Lens
}

/// Прогон шагового перевода так, как его ведёт контроллер: тики по 0,1 с, на каждом — `next` с положением объектива. Команду
/// исполняет объектив, и сразу после ответа камеры, в то же самое время, `next` зовут снова с новым положением; если
/// в ответ опять команда, она тоже уходит. Тики считаются по номеру (k · 0,1), а не накоплением, чтобы 2,0 с было 2,0 с.
/// Кончается, когда пришёл `.finish`, по горизонту или когда `stop` скажет «хватит» (после очередной команды). `gain` — стартовый
/// коэффициент автомата, по умолчанию тот же 1,0, что и у `FocusPull`; 3,5 задают там, где проверка построена на старом числе.
private func runSteps(from start: Double, to target: Double, duration: Double, lens: Lens, gain: Double = 1.0,
                      horizon: Double = 10, stop: (Run) -> Bool = { _ in false }) -> Run {
    var run = Run(pull: FocusPull(mode: .steps, from: start, to: target, duration: duration, gain: gain), lens: lens)
    for index in 0...Int((horizon / FocusPull.tick).rounded()) {
        let time = Double(index) * FocusPull.tick
        var action = run.pull.next(elapsed: time, position: run.lens.position)
        var chained = 0
        while case let .nearFar(steps) = action {
            let before = run.lens.position
            run.lens.move(steps)
            run.moves.append(Move(time: time, steps: steps, before: before, after: run.lens.position))
            action = run.pull.next(elapsed: time, position: run.lens.position)
            chained += 1
            if stop(run) { return run }
            if chained >= 50 {
                Issue.record("команды без конца в один и тот же момент \(time) с")
                return run
            }
        }
        if case let .finish(outcome) = action {
            run.outcome = outcome
            run.finishedAt = time
            return run
        }
    }
    return run
}

/// Прогон с медленной камерой. Контроллер тикает каждые 0,1 с и зовёт `next` с тем положением, которое видит; шаг объектив
/// исполняет только через `delay` секунд после команды — до тех пор ответ камеры и свежий 0xD24C не пришли и положение
/// прежнее. `again` — сразу после команды, в тот же момент, `next` зовут ещё раз (как «после ответа камеры»): положение
/// при этом тоже ещё старое.
private func runSlow(from start: Double, to target: Double, duration: Double, lens: Lens, delay: Double, again: Bool,
                     gain: Double = 1.0, horizon: Double = 12) -> Run {
    var run = Run(pull: FocusPull(mode: .steps, from: start, to: target, duration: duration, gain: gain), lens: lens)
    var due: [(time: Double, steps: Int64)] = []
    for index in 0...Int((horizon / FocusPull.tick).rounded()) {
        let time = Double(index) * FocusPull.tick
        while let next = due.first, next.time <= time + 1e-9 {          // шаги, которые объектив уже исполнил
            due.removeFirst()
            let before = run.lens.position
            run.lens.move(next.steps)
            run.moves.append(Move(time: next.time, steps: next.steps, before: before, after: run.lens.position))
        }
        var action = run.pull.next(elapsed: time, position: run.lens.position)
        var repeats = 0
        while case let .nearFar(steps) = action, again, repeats < 50 {
            due.append((time + delay, steps))
            action = run.pull.next(elapsed: time, position: run.lens.position)
            repeats += 1
        }
        if case let .nearFar(steps) = action { due.append((time + delay, steps)) }
        if case let .finish(outcome) = action {
            run.outcome = outcome
            run.finishedAt = time
            return run
        }
    }
    return run
}

/// Прогон перевода на PZ 16-50: объектив отвечает по таблице через 0,4 с (`Lens.pz1650`, `runSlow`); `again` — как в `runSlow`.
private func runPZ(from start: Double, to target: Double, duration: Double, again: Bool) -> Run {
    runSlow(from: start, to: target, duration: duration, lens: Lens.pz1650(at: start), delay: 0.4, again: again)
}

/// На сколько единиц положение выходило за ту из меток, что дальше от пути, — по положениям после команд; 0, если не выходило.
private func overshoot(_ run: Run, from start: Double, to target: Double) -> Double {
    let low = min(start, target)
    let high = max(start, target)
    var worst = 0.0
    for move in run.moves {
        worst = max(worst, low - move.after, move.after - high)
    }
    return worst
}

/// Что запись `.write` кладёт в регистр; nil — это не запись.
private func written(_ action: FocusPull.Action) -> Int? {
    if case let .write(value) = action { return Int(value) }
    return nil
}

/// Что свежий шаговый перевод скажет, когда до цели `error` единиц (цель выше положения — ошибка положительная).
/// Метка одна (50 → 50), поэтому кривая стоит на месте: `ended` — время уже вышло (перевод за 0 с), иначе — ещё идёт.
private func decide(error: Double, ended: Bool = true, gain: Double = 1.0) -> FocusPull.Action {
    var pull = FocusPull(mode: .steps, from: 50, to: 50, duration: ended ? 0 : 2, gain: gain)
    return pull.next(elapsed: 0, position: 50 - error)
}

// MARK: - Модель объектива

@Test func lensModelRoundsFromZeroAndHasADeadZone() {
    var lens = Lens(at: 50, gain: 3.5)
    lens.move(1)
    #expect(lens.position == 54)            // 3,5 → 4
    lens.move(-1)
    #expect(lens.position == 50)            // −3,5 → −4
    lens.move(7)
    #expect(lens.position == 75)            // 24,5 → 25
    lens.move(0)
    #expect(lens.position == 75)

    var slow = Lens(at: 50, gain: 2.5)
    slow.move(1)
    #expect(slow.position == 53)            // 2,5 → 3
    var tiny = Lens(at: 50, gain: 0.2)
    tiny.move(2)
    #expect(tiny.position == 50)            // 0,4 → 0
    tiny.move(3)
    #expect(tiny.position == 51)            // 0,6 → 1

    // шкала 0…100: за край не выходит
    var top = Lens(at: 90, gain: 5)
    top.move(7)
    #expect(top.position == 100)
    var bottom = Lens(at: 10, gain: 5)
    bottom.move(-7)
    #expect(bottom.position == 0)

    // мёртвая зона d = 5: ближе 5 единиц к краю малая команда к краю не двигает, большая — двигает, от края — двигает
    var dead = Lens(at: 97, gain: 3.5, deadband: 5)
    dead.move(1)
    dead.move(3)
    #expect(dead.position == 97)
    dead.move(-1)
    #expect(dead.position == 93)
    var big = Lens(at: 97, gain: 3.5, deadband: 5)
    big.move(4)
    #expect(big.position == 100)
    var border = Lens(at: 95, gain: 3.5, deadband: 5)
    border.move(1)
    #expect(border.position == 99)          // ровно 5 до края — это не «ближе 5»
    var low = Lens(at: 3, gain: 3.5, deadband: 5)
    low.move(-3)
    #expect(low.position == 3)              // у нижнего края — то же
    low.move(1)
    #expect(low.position == 7)
}

@Test func lensModelAnswersLikeThePZ1650() {
    // середина шкалы: ±1 не двигает, ±2 — на 1, ±3 — на 3, ±4 — на 4, ±5 — на 5, ±6 — на 7; в обе стороны
    let table: [(steps: Int64, moved: Double)] = [(1, 0), (2, 1), (3, 3), (4, 4), (5, 5), (6, 7)]
    for entry in table {
        var up = Lens.pz1650(at: 50)
        up.move(entry.steps)
        #expect(up.position == 50 + entry.moved, "+\(entry.steps)")
        var down = Lens.pz1650(at: 50)
        down.move(-entry.steps)
        #expect(down.position == 50 - entry.moved, "-\(entry.steps)")
    }
    // ±7 — вся шкала в ту сторону
    var whole = Lens.pz1650(at: 62)
    whole.move(-7)
    #expect(whole.position == 0)
    whole.move(7)
    #expect(whole.position == 100)
    // не дальше единицы от края ±1 сдвигает на 1; дальше от края — нет; за край не выходит
    var edge = Lens.pz1650(at: 100)
    edge.move(-1)
    #expect(edge.position == 99)
    edge.move(1)
    #expect(edge.position == 100)
    edge.move(1)
    #expect(edge.position == 100)
    var bottom = Lens.pz1650(at: 1)
    bottom.move(-1)
    #expect(bottom.position == 0)
    var near = Lens.pz1650(at: 98)
    near.move(1)
    #expect(near.position == 98)
    // команда 0 не двигает; большие команды у края упираются в шкалу
    var still = Lens.pz1650(at: 40)
    still.move(0)
    #expect(still.position == 40)
    var top = Lens.pz1650(at: 98)
    top.move(5)
    #expect(top.position == 100)
    var floor = Lens.pz1650(at: 3)
    floor.move(-4)
    #expect(floor.position == 0)
}

// MARK: - Кривая и время

@Test func focusPullKeepsTheShippedConstants() {
    #expect(FocusPull.durations == [0, 1, 2, 4, 8])
    #expect(FocusPull.tick == 0.1)
}

@Test func easeIsTheCubicSmoothstep() {
    #expect(FocusPull.ease(0) == 0)
    #expect(FocusPull.ease(0.5) == 0.5)
    #expect(FocusPull.ease(1) == 1)
    #expect(abs(FocusPull.ease(0.25) - 0.15625) < 1e-12)     // 3 · 0,0625 − 2 · 0,015625
    #expect(abs(FocusPull.ease(0.75) - 0.84375) < 1e-12)
    // t обрезается в 0…1; не-число — начало кривой
    #expect(FocusPull.ease(-3) == 0)
    #expect(FocusPull.ease(7) == 1)
    #expect(FocusPull.ease(-.infinity) == 0)
    #expect(FocusPull.ease(.infinity) == 1)
    #expect(FocusPull.ease(.nan) == 0)
    // симметрична и не убывает
    var last = 0.0
    for i in 0...100 {
        let t = Double(i) / 100
        #expect(abs(FocusPull.ease(t) + FocusPull.ease(1 - t) - 1) < 1e-12)
        #expect(FocusPull.ease(t) >= last)
        last = FocusPull.ease(t)
    }
}

@Test func desiredFollowsTheCurveFromStartToTarget() {
    let up = FocusPull(mode: .steps, from: 10, to: 90, duration: 2)
    #expect(up.desired(at: 0) == 10)
    #expect(abs(up.desired(at: 0.5) - (10 + 80 * 0.15625)) < 1e-9)
    #expect(abs(up.desired(at: 1) - 50) < 1e-9)
    #expect(up.desired(at: 2) == 90)
    #expect(up.desired(at: 5) == 90)                         // после конца — цель
    #expect(up.desired(at: -1) == 10)                        // до начала — старт
    let down = FocusPull(mode: .steps, from: 90, to: 10, duration: 2)
    #expect(abs(down.desired(at: 0.5) - (90 - 80 * 0.15625)) < 1e-9)
    #expect(down.desired(at: 2) == 10)
    // время 0 — сразу цель; отрицательное время — то же, что 0
    #expect(FocusPull(mode: .steps, from: 10, to: 90, duration: 0).desired(at: 0) == 90)
    #expect(FocusPull(mode: .steps, from: 10, to: 90, duration: -3).desired(at: 0) == 90)
    #expect(FocusPull(mode: .absolute, from: 0, to: 65535, duration: .nan).desired(at: 0) == 65535)
}

// MARK: - Абсолютный фокус

@Test func absoluteFollowsCurve() throws {
    // 0 → 65535 за 2 с: на 0,5 с — четверть времени, 3t² − 2t³ = 0,15625 пути (10239,8); на 1,0 с — половина пути
    var pull = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    let quarter = pull.next(elapsed: 0.5, position: 0)
    let half = pull.next(elapsed: 1.0, position: 10240)
    let early = try #require(written(quarter))
    let middle = try #require(written(half))
    #expect(abs(early - 10240) <= 1)
    #expect(abs(middle - 32768) <= 1)
}

@Test func absoluteStartsAtTheStartAndNeverGoesBack() {
    for (from, to) in [(0.0, 65535.0), (65535.0, 0.0), (20000.0, 45000.0), (45000.0, 20000.0)] {
        var pull = FocusPull(mode: .absolute, from: from, to: to, duration: 2)
        var writes: [Int] = []
        for index in 0..<20 {                                // 0,0 … 1,9 с: время ещё идёт
            let action = pull.next(elapsed: Double(index) * FocusPull.tick, position: from)
            guard case let .write(value) = action else { continue }
            writes.append(Int(value))
        }
        #expect(writes.first == Int(from))
        #expect(writes == (to > from ? writes.sorted() : writes.sorted(by: >)))
        #expect(writes.count == 20)
        // до конца времени цель не пишется
        #expect(!writes.contains(Int(to)))
    }
}

@Test func absoluteZeroDurationWritesTarget() {
    var pull = FocusPull(mode: .absolute, from: 1000, to: 40000, duration: 0)
    let first = pull.next(elapsed: 0, position: 1000)
    #expect(first == .write(40000))
    // цель записана один раз; камера ещё в пути — ждём, второй раз не пишем
    let second = pull.next(elapsed: 0.1, position: 12000)
    #expect(second == .wait)
    let third = pull.next(elapsed: 0.2, position: 39900)
    #expect(third == .finish(.reached))
}

@Test func absoluteWritesTheTargetOnceAtTheEnd() {
    var pull = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    _ = pull.next(elapsed: 1.9, position: 60000)
    let end = pull.next(elapsed: 2.0, position: 60000)
    #expect(end == .write(65535))
    // дальше камера едет к цели: ждём, цель не повторяем
    for time in [2.0, 2.1, 2.2, 3.0, 3.9] {
        let action = pull.next(elapsed: time, position: 61000)
        #expect(action == .wait)
    }
}

@Test func absoluteReachedWithinHalfPercent() {
    // 0,5 % от 65535 — 327,675 единицы. Цель к концу времени уже записана; камера от цели ближе — готово, дальше — ждём.
    // В задании пример — положение 65200 при цели 65535: это 335 единиц, 0,511 %, чуть за 0,5 %, поэтому «готово» тут
    // начинается с 65208 (327 единиц), а 65207 (328) и 65200 (335) ещё ждут.
    func answer(at position: Double, target: Double = 65535) -> FocusPull.Action {
        var pull = FocusPull(mode: .absolute, from: 0, to: target, duration: 2)
        _ = pull.next(elapsed: 2.0, position: 0)             // конец времени: пишется цель
        return pull.next(elapsed: 2.1, position: position)
    }
    #expect(answer(at: 65535) == .finish(.reached))
    #expect(answer(at: 65250) == .finish(.reached))          // 285 единиц, 0,435 %
    #expect(answer(at: 65208) == .finish(.reached))          // 327 единиц — внутри
    #expect(answer(at: 65207) == .wait)                      // 328 единиц — снаружи
    #expect(answer(at: 65200) == .wait)                      // 335 единиц, 0,511 %
    #expect(answer(at: 60000) == .wait)
    // по ту сторону цели — так же; ошибка — от цели, а не от шкалы
    #expect(answer(at: 30327, target: 30000) == .finish(.reached))
    #expect(answer(at: 30328, target: 30000) == .wait)
    #expect(answer(at: 29673, target: 30000) == .finish(.reached))
    #expect(answer(at: 29672, target: 30000) == .wait)
}

@Test func absoluteReachedNeedsTheTargetWritten() {
    // камера уже у цели, но цель ещё не записана (первое обращение уже после конца): сначала пишем, потом «готово»
    var pull = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    let first = pull.next(elapsed: 2.0, position: 65500)
    #expect(first == .write(65535))
    let second = pull.next(elapsed: 2.0, position: 65500)
    #expect(second == .finish(.reached))
}

@Test func absoluteTimesOut() {
    // камера стоит на месте: цель записана на 2,0 с, ждать можно ещё 2 с — до 4,0 с
    var pull = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    var finished: (Double, FocusPull.Outcome)?
    for index in 0...50 where finished == nil {
        let time = Double(index) * FocusPull.tick
        let action = pull.next(elapsed: time, position: 30000)
        if case let .finish(outcome) = action { finished = (time, outcome) }
    }
    #expect(finished?.1 == .timedOut)
    #expect((3.95...4.1).contains(finished?.0 ?? 0))
    // до срока — ещё ждём, на 4,1 с — тайм-аут, и он не отменяется, даже если камера потом доехала
    var other = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    _ = other.next(elapsed: 2.0, position: 30000)
    let before = other.next(elapsed: 3.9, position: 30000)
    #expect(before == .wait)
    let after = other.next(elapsed: 4.1, position: 30000)
    #expect(after == .finish(.timedOut))
    let late = other.next(elapsed: 4.2, position: 65535)
    #expect(late == .finish(.timedOut))
    // а если камера доехала как раз к сроку — это «готово», а не тайм-аут
    var arrived = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    _ = arrived.next(elapsed: 2.0, position: 30000)
    let justInTime = arrived.next(elapsed: 4.1, position: 65500)
    #expect(justInTime == .finish(.reached))
}

@Test func absoluteDoesNotRepeatTheSameValue() {
    // то же число камере второй раз не пишем: ответ на запись, пришедший в том же тике, лишних записей не даёт
    var pull = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    let first = pull.next(elapsed: 0.5, position: 0)
    #expect(first == .write(10240))
    let again = pull.next(elapsed: 0.5, position: 10240)
    #expect(again == .wait)
    let later = pull.next(elapsed: 0.6, position: 10240)
    #expect(written(later) != nil)
}

@Test func absoluteTimeoutCountsFromTheEndOfTheTime() {
    // ждать можно 2 с после конца времени: для перевода за 8 с срок — 10 с, для перевода за 0 с — 2 с
    var long = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 8)
    _ = long.next(elapsed: 8.0, position: 0)
    let stillWaiting = long.next(elapsed: 9.9, position: 0)
    #expect(stillWaiting == .wait)
    let gaveUp = long.next(elapsed: 10.1, position: 0)
    #expect(gaveUp == .finish(.timedOut))
    var instant = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 0)
    _ = instant.next(elapsed: 0, position: 0)
    let waiting = instant.next(elapsed: 1.9, position: 0)
    #expect(waiting == .wait)
    let timedOut = instant.next(elapsed: 2.1, position: 0)
    #expect(timedOut == .finish(.timedOut))
}

@Test func absoluteRunsToTheTarget() {
    // камера мгновенно встаёт туда, куда ей написали: двадцать записей по кривой, на конце — цель, и «готово»
    var pull = FocusPull(mode: .absolute, from: 5000, to: 60000, duration: 2)
    var position = 5000.0
    var writes: [Int] = []
    var outcome: FocusPull.Outcome?
    var time = 0.0
    for index in 0...40 where outcome == nil {
        time = Double(index) * FocusPull.tick
        var action = pull.next(elapsed: time, position: position)
        if let value = written(action) {
            writes.append(value)
            position = Double(value)
            action = pull.next(elapsed: time, position: position)    // после ответа камеры
        }
        if case let .finish(result) = action { outcome = result }
    }
    #expect(outcome == .reached)
    #expect(writes.count == 21)
    #expect(writes.last == 60000)
    #expect(time == 2.0)
}

@Test func absoluteKeepsWritesInsideTheRegister() {
    // метка за шкалой не ломает запись: регистр 0…65535
    var high = FocusPull(mode: .absolute, from: 0, to: 70000, duration: 0)
    let top = high.next(elapsed: 0, position: 0)
    #expect(top == .write(65535))
    var low = FocusPull(mode: .absolute, from: -100, to: 70000, duration: 2)
    let bottom = low.next(elapsed: 0, position: 0)
    #expect(bottom == .write(0))
    var down = FocusPull(mode: .absolute, from: 100, to: -500, duration: 0)
    let floor = down.next(elapsed: 0, position: 100)
    #expect(floor == .write(0))
}

// MARK: - Шаги Near/Far: сходимость

@Test(arguments: [2.0, 3.5, 5.0])
func stepsConverge(gain: Double) {
    // 10 → 90 за 2 с, тики 0,1 с; объектив с усилением g, а автомат начинает с k = 1,0, по первой команде узнаёт g и уточняет
    let run = runSteps(from: 10, to: 90, duration: 2, lens: Lens(at: 10, gain: gain))
    #expect(run.outcome == .reached)
    #expect((run.finishedAt ?? .infinity) < 5)
    #expect(abs(run.lens.position - 90) <= max(1, gain / 2) + 0.5)
    #expect(run.moves.allSatisfy { abs($0.steps) <= 5 })
    #expect(!run.moves.isEmpty)
}

@Test func stepsNoDither() throws {
    // g = 4: объектив стоит на 10, 14 … 50, 54, а цель 52 — между ними; ближе чем на 2 не подойти. Стартовый k = 3,5 задан
    // явно: тогда все команды — ±1, ни одна не уточняет k, допуск остаётся 1,75 < 2 и дребезг у цели случается (при k₀ = 1,0 первая же
    // команда |n| ≥ 2 узнала бы g = 4, допуск стал бы 2, и до дребезга не дошло)
    let run = runSteps(from: 10, to: 52, duration: 2, lens: Lens(at: 10, gain: 4), gain: 3.5)
    #expect(run.outcome == .reached)
    #expect(run.outcome != .timedOut)
    // цель перескочена — положение оказалось по другую сторону от неё
    let jump = try #require(run.moves.firstIndex { ($0.before - 52) * ($0.after - 52) < 0 })
    let after = run.moves[(jump + 1)...]
    #expect(after.count <= 2)
    #expect(after.allSatisfy { abs($0.steps) == 1 })
    #expect(abs(run.lens.position - 52) <= 2)
}

@Test func stepsDitherFinishesAtTheEndWithoutAnotherCommand() {
    // за тик до конца два шага ±1 подряд перескочили цель 52 туда и обратно: на конце новой команды не будет. Стартовый k = 3,5 задан
    // явно: числа ниже (допуск 1,75, шаг ±1) построены на нём
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 2, gain: 3.5)
    let first = pull.next(elapsed: 1.8, position: 48.9)              // до кривой 1,92 > 1,75: шаг вперёд
    #expect(first == .nearFar(1))
    let jumped = pull.next(elapsed: 1.8, position: 54)               // перескочил цель
    #expect(jumped == .wait)
    let second = pull.next(elapsed: 1.9, position: 54)               // кривая 51,7: назад
    #expect(second == .nearFar(-1))
    let back = pull.next(elapsed: 1.9, position: 50)                 // и обратно через цель
    #expect(back == .wait)                                           // время ещё идёт, ошибка 1,7 — в допуске
    let end = pull.next(elapsed: 2.0, position: 50)                  // конец: до цели 2 > 1,75, но ближе не подойти
    #expect(end == .finish(.reached))
}

@Test func stepsDitherDoesNotEndTheTimeEarly() {
    // два шага ±1 перескочили цель 52 туда и обратно ещё до конца времени, а до кривой по-прежнему далеко: новая команда
    // не нужна, но и «готово» рано — оно придёт на конце времени (k = 3,5 задан явно)
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 2, gain: 3.5)
    let down = pull.next(elapsed: 1.7, position: 54)                 // кривая 49,45: до неё −4,55 → шаг −1
    #expect(down == .nearFar(-1))
    let up = pull.next(elapsed: 1.8, position: 48)                   // перескочил цель вниз; кривая 50,82: до неё 2,82 → шаг +1
    #expect(up == .nearFar(1))
    let over = pull.next(elapsed: 1.8, position: 54)                 // и вверх через цель: время ещё идёт
    #expect(over == .wait)
    let end = pull.next(elapsed: 2.0, position: 54)                  // конец времени: до цели −2, ближе не подойти
    #expect(end == .finish(.reached))
}

@Test func stepsDitherPairMustBeNextToEachOther() {
    // шаг −1 через цель, потом шаг другого размера, потом +1 через цель: два шага ±1 тут не «подряд» — это ещё не дребезг
    // (k = 3,5 задан явно)
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 0, gain: 3.5)
    let first = pull.next(elapsed: 0.0, position: 54)                // до цели −2: шаг −1
    #expect(first == .nearFar(-1))
    let second = pull.next(elapsed: 0.1, position: 40)               // ушёл на 14 вниз через цель; до цели 12: шаг +3
    #expect(second == .nearFar(3))
    let third = pull.next(elapsed: 0.2, position: 50)                // сдвинуло на 10 (первое измерение: k = 3,33); до цели 2: шаг +1
    #expect(third == .nearFar(1))
    let apart = pull.next(elapsed: 0.3, position: 54)                // через цель вверх; шаги −1 и +1 не соседние
    #expect(apart == .nearFar(-1))                                   // идёт дальше: до цели −2, шаг −1
    let end = pull.next(elapsed: 0.4, position: 50)                  // теперь соседние +1 и −1, оба через цель
    #expect(end == .finish(.reached))
}

@Test func stepsDitherNeedsOppositeUnitStepsThatCrossTheTarget() {
    // два шага ±1 подряд в разные стороны, и второй перескочил цель 52 с одной стороны на другую: ближе не подойти (k = 3,5 задан
    // явно)
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 0, gain: 3.5)
    let forward = pull.next(elapsed: 0.0, position: 50)              // до цели 2 > 1,75: шаг +1
    #expect(forward == .nearFar(1))
    let back = pull.next(elapsed: 0.1, position: 54)                 // перескочил: до цели −2, шаг −1
    #expect(back == .nearFar(-1))
    let end = pull.next(elapsed: 0.2, position: 50)                  // и обратно: два шага ±1 подряд, оба через цель
    #expect(end == .finish(.reached))

    // а если второй шаг цель не пересёк — это не дребезг: объектив просто ещё далеко
    var apart = FocusPull(mode: .steps, from: 10, to: 90, duration: 0, gain: 3.5)
    let first = apart.next(elapsed: 0.0, position: 85)               // до цели 5: шаг +1
    #expect(first == .nearFar(1))
    let second = apart.next(elapsed: 0.1, position: 95)              // перескочил на 5 выше: шаг −1
    #expect(second == .nearFar(-1))
    let third = apart.next(elapsed: 0.2, position: 96)               // шаг −1 «увёл» ещё дальше, цель не пересечена
    #expect(third == .nearFar(-2))                                   // до цели −6: команда, а не «готово»
}

// MARK: - Шаги Near/Far: окно ожидания и наименьшая команда

@Test func stepsSettleWindowIsSevenTenths() {
    // команда ушла; положение прежнее — до 0,7 с камеру ждут (PZ 16-50 отвечает через 0,33–0,45 с), потом это «не двинулась»
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 0.0, position: 0)                 // до цели 100, самая большая команда — 5
    #expect(first == .nearFar(5))
    let at04 = pull.next(elapsed: 0.4, position: 0)                  // 0,4 с — ответ мог ещё не дойти
    #expect(at04 == .wait)
    let at065 = pull.next(elapsed: 0.65, position: 0)
    #expect(at065 == .wait)
    let at075 = pull.next(elapsed: 0.75, position: 0)                // 0,75 с: пустой шаг, уходит следующий
    #expect(at075 == .nearFar(5))
    // а как только положение изменилось — оно учтено сразу, не дожидаясь окна; новая команда — на ближайшем тике
    let moved = pull.next(elapsed: 0.9, position: 5)
    #expect(moved == .nearFar(5))
}

@Test func stepsSettleWindowIgnoresFloatingPointNoise() {
    // тики прогонов считаются как k · 0,1: 23 · 0,1 и 30 · 0,1 — это ровно 0,7 с, хотя в Double их разность 0,6999999999999997.
    // Окно всё равно вышло. Кривая (0 → 100 за 8 с) на 2,3 с на 20 при объективе на 10: идти надо вперёд
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 8)
    let first = pull.next(elapsed: 23 * 0.1, position: 10)
    #expect(first == .nearFar(5))
    let before = pull.next(elapsed: 29 * 0.1, position: 10)          // 0,6 с — ещё ждём
    #expect(before == .wait)
    let exact = pull.next(elapsed: 30 * 0.1, position: 10)           // 0,7 с — пустой шаг, уходит следующий
    #expect(exact == .nearFar(5))
}

@Test func stepsEmptyUnitStepRaisesTheSmallestCommand() {
    // e = 1,4 при допуске 1: команда round(1,4) = 1. Объектив ±1 не отрабатывает — после этого меньше 2 команд нет
    var pull = FocusPull(mode: .steps, from: 50, to: 51.4, duration: 0)
    let one = pull.next(elapsed: 0.0, position: 50)
    #expect(one == .nearFar(1))
    let waiting = pull.next(elapsed: 0.65, position: 50)
    #expect(waiting == .wait)
    let two = pull.next(elapsed: 0.75, position: 50)                 // ±1 ничего не сдвинул: наименьшая команда теперь 2
    #expect(two == .nearFar(2))
    // 2 сдвинула на 2,5 (первое измерение: k = 1,25), цель теперь на 1,1 ниже, round(−0,88) = −1 — но и в обратную сторону
    // меньше 2 не бывает
    let back = pull.next(elapsed: 1.15, position: 52.5)
    #expect(back == .nearFar(-2))
}

@Test func stepsLensThatMovesOnOneKeepsOne() {
    // объектив отработал ±1 (сдвинул на 2): наименьшая команда остаётся 1 — следующий шаг тоже ±1 (k = 3,5 задан явно)
    var pull = FocusPull(mode: .steps, from: 50, to: 54, duration: 0, gain: 3.5)
    let first = pull.next(elapsed: 0.0, position: 50)                // до цели 4: round(1,14) = 1
    #expect(first == .nearFar(1))
    let second = pull.next(elapsed: 0.4, position: 52)               // сдвинуло на 2; до цели 2 > 1,75: round(0,57) = 1
    #expect(second == .nearFar(1))
    #expect(pull.gain == 3.5)                                        // ±1 коэффициент не уточняет
}

@Test func stepsDitherPairsTheSmallestCommandAfterItWasRaised() {
    // ±1 ничего не сдвинул → наименьшая команда 2. Дальше «два шага наименьшего размера подряд в разные стороны, второй перескочил
    // цель» — это два шага ±2, а не ±1 (k = 3,5 задан явно, время 0: конец времени уже наступил)
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 0, gain: 3.5)
    let one = pull.next(elapsed: 0.0, position: 50)                  // до цели 2 > 1,75: round(0,57) = 1
    #expect(one == .nearFar(1))
    let two = pull.next(elapsed: 0.8, position: 50)                  // 0,8 с — пустой шаг; наименьшая команда 2
    #expect(two == .nearFar(2))
    let back = pull.next(elapsed: 0.9, position: 55)                 // +2 сдвинула на 5 через цель (k = 2,5); до цели −3: −1 → −2
    #expect(back == .nearFar(-2))
    let end = pull.next(elapsed: 1.0, position: 49)                  // −2 вернула через цель: два шага ±2 подряд — «у метки»
    #expect(end == .finish(.reached))
}

@Test func stepsMarksSweep() {
    // все пары меток 5, 15 … 95 (в обе стороны и совпадающие), объектив g = 3,5, автомат начинает с k₀ = 1,0, время 2 с: ни одного
    // тайм-аута
    let marks = Array(stride(from: 5.0, through: 95.0, by: 10.0))
    var timedOut: [String] = []
    var unfinished: [String] = []
    var stuck: [String] = []
    for from in marks {
        for to in marks {
            let run = runSteps(from: from, to: to, duration: 2, lens: Lens(at: from, gain: 3.5))
            let name = "\(Int(from))→\(Int(to))"
            switch run.outcome {
            case .timedOut: timedOut.append(name)
            case .stuck: stuck.append(name)
            case nil: unfinished.append(name)
            default: break
            }
        }
    }
    #expect(timedOut.isEmpty, "тайм-аут: \(timedOut)")
    #expect(unfinished.isEmpty, "не кончились: \(unfinished)")
    #expect(stuck.isEmpty, "упёрлись: \(stuck)")
}

@Test func stepsSignCloser() {
    // 90 → 10: отрицательный шаг уменьшает положение, то есть фокус ближе — все команды отрицательные. Стартовый k = 3,5 задан явно:
    // с k₀ = 1,0 первая же команда перелетает кривую, и одна-две команды в обратную сторону в начале перевода — в порядке вещей
    let down = runSteps(from: 90, to: 10, duration: 2, lens: Lens(at: 90, gain: 3.5), gain: 3.5)
    #expect(down.outcome == .reached)
    #expect(!down.moves.isEmpty)
    #expect(down.moves.allSatisfy { $0.steps < 0 })
    #expect(down.moves.allSatisfy { $0.after < $0.before })
    // и наоборот
    let up = runSteps(from: 10, to: 90, duration: 2, lens: Lens(at: 10, gain: 3.5), gain: 3.5)
    #expect(!up.moves.isEmpty)
    #expect(up.moves.allSatisfy { $0.steps > 0 })
    // та же команда без объектива: на середине пути до кривой 40 единиц вниз — самый большой шаг, отрицательный
    var pull = FocusPull(mode: .steps, from: 90, to: 10, duration: 2)
    let action = pull.next(elapsed: 1.0, position: 90)
    #expect(action == .nearFar(-5))
}

@Test func stepsBounds() {
    // самая большая команда — 5, в любую сторону, как бы далеко ни была цель (у PZ 16-50 ±7 проходит всю шкалу)
    #expect(decide(error: 80) == .nearFar(5))
    #expect(decide(error: -80) == .nearFar(-5))
    #expect(decide(error: 1e9) == .nearFar(5))
    #expect(decide(error: -1e9) == .nearFar(-5))
    #expect(decide(error: 6) == .nearFar(5))
    #expect(decide(error: 4.5) == .nearFar(5))
    #expect(decide(error: 4.4) == .nearFar(4))
    // при k₀ = 1,0 допуск max(1, 0,5) = 1: ошибка 0,9 — команды нет (ждём до конца, на конце — «готово»), ровно 1 — ещё «в допуске»,
    // 1,1 — шаг 1
    #expect(decide(error: 0.9, ended: false) == .wait)
    #expect(decide(error: -0.9, ended: false) == .wait)
    #expect(decide(error: 0.9) == .finish(.reached))
    #expect(decide(error: -0.9) == .finish(.reached))
    #expect(decide(error: 1) == .finish(.reached))
    #expect(decide(error: -1) == .finish(.reached))
    #expect(decide(error: 1.1, ended: false) == .nearFar(1))
    #expect(decide(error: -1.1, ended: false) == .nearFar(-1))
    #expect(decide(error: 1.1) == .nearFar(1))
    // команда — e / k, округлённое от нуля на половинке: 1,5 → 2, 2,5 → 3
    #expect(decide(error: 1.4) == .nearFar(1))
    #expect(decide(error: 1.5) == .nearFar(2))
    #expect(decide(error: -1.5) == .nearFar(-2))
    #expect(decide(error: 2.4) == .nearFar(2))
    #expect(decide(error: 2.5) == .nearFar(3))

    // то же при k = 3,5 (его задают явно): допуск 1,75, команда e / 3,5
    #expect(decide(error: 24.5, gain: 3.5) == .nearFar(5))
    #expect(decide(error: 17.5, gain: 3.5) == .nearFar(5))
    #expect(decide(error: 15.7, gain: 3.5) == .nearFar(4))
    #expect(decide(error: 1.5, ended: false, gain: 3.5) == .wait)
    #expect(decide(error: -1.5, ended: false, gain: 3.5) == .wait)
    #expect(decide(error: 1.5, gain: 3.5) == .finish(.reached))
    #expect(decide(error: 2.5, ended: false, gain: 3.5) == .nearFar(1))
    #expect(decide(error: -2.5, ended: false, gain: 3.5) == .nearFar(-1))
    // ровно допуск — ещё «в допуске»; чуть больше — уже шаг
    #expect(decide(error: 1.75, gain: 3.5) == .finish(.reached))
    #expect(decide(error: 1.76, gain: 3.5) == .nearFar(1))
    #expect(decide(error: -1.76, gain: 3.5) == .nearFar(-1))
    #expect(decide(error: 5.24, gain: 3.5) == .nearFar(1))
    #expect(decide(error: 5.25, gain: 3.5) == .nearFar(2))
    #expect(decide(error: -5.25, gain: 3.5) == .nearFar(-2))
    #expect(decide(error: 8.74, gain: 3.5) == .nearFar(2))
    #expect(decide(error: 8.75, gain: 3.5) == .nearFar(3))
}

@Test func stepsToleranceFollowsTheGain() {
    // допуск — max(1, k / 2): при большом k он растёт, при малом — не меньше 1
    #expect(decide(error: 3.9, ended: false, gain: 8) == .wait)
    #expect(decide(error: 4.1, ended: false, gain: 8) == .nearFar(1))
    #expect(decide(error: 4, gain: 8) == .finish(.reached))
    #expect(decide(error: 1, gain: 0.5) == .finish(.reached))
    #expect(decide(error: 1.1, gain: 0.5) == .nearFar(2))            // 1,1 / 0,5 = 2,2
    #expect(decide(error: 0.9, ended: false, gain: 0.5) == .wait)
}

// MARK: - Шаги Near/Far: коэффициент k

@Test func stepsGainStartsAtOne() {
    // стартовый коэффициент — 1,0 (у PZ 16-50 команда ±2…±5 сдвигает примерно на единицу на значение шага)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1).gain == 1)
    // при k = 1,0 команда — просто ошибка в единицах, не больше 5
    #expect(decide(error: 3) == .nearFar(3))
    #expect(decide(error: -4) == .nearFar(-4))
    #expect(decide(error: 12) == .nearFar(5))
}

@Test func stepsFirstMeasurementReplacesTheGain() {
    // первая же отработанная команда |n| ≥ 2 со сдвигом заменяет k на Δ/n, а не подмешивается: 20 за шаг 5 — сразу 4
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 0.0, position: 0)                 // до цели 100: самая большая команда
    #expect(first == .nearFar(5))
    let reply = pull.next(elapsed: 0.4, position: 20)                // сдвинуло на 20 за 5: k = 4; до цели 80 / 4 = 20 → 5
    #expect(reply == .nearFar(5))
    #expect(pull.gain == 4)
    // следующие измерения уточняют: k ← 0,7k + 0,3·(Δ/n). Сдвиг 10 за 5 — 2; 0,7 · 4 + 0,3 · 2 = 3,4
    let second = pull.next(elapsed: 0.8, position: 30)
    #expect(second == .nearFar(5))
    #expect(abs(pull.gain - 3.4) < 1e-12)
    // и снова: сдвиг 15 за 5 — 3; 0,7 · 3,4 + 0,3 · 3 = 3,28
    let third = pull.next(elapsed: 1.2, position: 45)
    #expect(third == .nearFar(5))
    #expect(abs(pull.gain - 3.28) < 1e-12)

    // уже |n| = 2 годится: сдвинуло на 1 за шаг 2 — k = 0,5
    var two = FocusPull(mode: .steps, from: 50, to: 52, duration: 0)
    let pair = two.next(elapsed: 0.0, position: 50)                  // до цели 2 > 1: round(2) = 2
    #expect(pair == .nearFar(2))
    _ = two.next(elapsed: 0.4, position: 51)
    #expect(two.gain == 0.5)
}

@Test func stepsGainIgnoresUnitStepsStallsAndOppositeMoves() {
    // шаг ±1 коэффициент не трогает, даже если сдвинул: 3 за шаг 1 — k остаётся 1,0
    var unit = FocusPull(mode: .steps, from: 50, to: 51.4, duration: 0)
    let one = unit.next(elapsed: 0.0, position: 50)                  // до цели 1,4: round(1,4) = 1
    #expect(one == .nearFar(1))
    _ = unit.next(elapsed: 0.4, position: 53)
    #expect(unit.gain == 1)

    // команда |n| ≥ 2, которая ничего не сдвинула, — тоже: k не трогаем, и первым измерением остаётся следующее
    var stalled = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let big = stalled.next(elapsed: 0, position: 0)
    #expect(big == .nearFar(5))
    let waiting = stalled.next(elapsed: 0.1, position: 0)            // положение прежнее, 0,1 с — ещё ждём
    #expect(waiting == .wait)
    let settled = stalled.next(elapsed: 0.8, position: 0)            // 0,7 с прошло: пустой шаг, k не трогаем
    #expect(settled == .nearFar(5))
    #expect(stalled.gain == 1)

    // сдвиг в обратную сторону (Δ/n ≤ 0: фокус ушёл не туда) не учитывается и первым измерением не считается
    var back = FocusPull(mode: .steps, from: 50, to: 100, duration: 0)
    let first = back.next(elapsed: 0.0, position: 50)
    #expect(first == .nearFar(5))
    let wrong = back.next(elapsed: 0.4, position: 40)                // −10 за +5: Δ/n = −2
    #expect(wrong == .nearFar(5))
    #expect(back.gain == 1)
    let right = back.next(elapsed: 0.8, position: 60)                // +20 за +5: это первое измерение — k = 4, а не 0,7 + 1,2
    #expect(right == .nearFar(5))
    #expect(back.gain == 4)
}

@Test func stepsGainAdaptsOnTheRealScale() {
    // на шкале 0…100 при g = 5 быстрый перевод 5 → 95 за 0,5 с: команды 5, 1, 5, 5, 2; k сразу узнал g по первой, остальные его
    // подтверждают, а шаг ±1 не трогает
    let run = runSteps(from: 5, to: 95, duration: 0.5, lens: Lens(at: 5, gain: 5))
    #expect(run.outcome == .reached)
    #expect(run.moves.map { $0.steps } == [5, 1, 5, 5, 2])
    #expect(abs(run.pull.gain - 5) < 1e-9)
}

@Test func stepsGainLongScaleKeepsTheLearnedValue() {
    // длинная шкала 0…1000, g = 5: пять команд по 5 подряд; k = 5 уже после первой и таким остаётся
    let run = runSteps(from: 0, to: 1000, duration: 0, lens: Lens(at: 0, gain: 5, limit: 1000),
                       stop: { $0.moves.count >= 5 })
    #expect(run.moves.count == 5)
    #expect(run.moves.allSatisfy { $0.steps == 5 })
    #expect(abs(run.pull.gain - 5) < 1e-9)
}

@Test func stepsGainStaysPositiveAndSane() {
    // стартовое значение — в пределах 0,2…20, не-число — 1,0
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: 100).gain == 20)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: 0).gain == 0.2)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: -4).gain == 0.2)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: .nan).gain == 1)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1).gain == 1)
    // сдвиг, в разы больше, чем «n · k», первым измерением тянет k вверх, но не выше 20
    var jump = FocusPull(mode: .steps, from: 0, to: 10_000, duration: 0)
    _ = jump.next(elapsed: 0, position: 0)
    _ = jump.next(elapsed: 0.1, position: 700)                        // 700 / 5 = 140
    #expect(jump.gain == 20)
    // и вниз — не ниже 0,2: сдвиг на полединицы за шаг 5 — 0,1
    var tiny = FocusPull(mode: .steps, from: 0, to: 10_000, duration: 0)
    _ = tiny.next(elapsed: 0, position: 0)
    _ = tiny.next(elapsed: 0.1, position: 0.5)
    #expect(tiny.gain == 0.2)
    // сдвиг в обратную сторону (по формуле — отрицательное отношение) не учитывается: k остаётся прежним
    var back = FocusPull(mode: .steps, from: 0, to: 10_000, duration: 0)
    var position = 5000.0
    var gains: [Double] = []
    for index in 0..<30 {
        let action = back.next(elapsed: Double(index) * FocusPull.tick, position: position)
        guard case .nearFar = action else { break }
        position -= 35
        gains.append(back.gain)
    }
    #expect(gains.count >= 3)
    #expect(gains.allSatisfy { $0 == 1 })
}

// MARK: - Шаги Near/Far: PZ 16-50

// Объектив отвечает по таблице (`Lens.pz1650`) через 0,4 с: положение меняется к тику через 0,4 с после команды. `again` — как в
// `runSlow`: после каждой команды автомат зовут ещё раз в тот же момент, когда положение ещё прежнее.

@Test(arguments: [false, true])
func pzFourSecondPullEndsOnTime(again: Bool) {
    // 88 → 100, 100 → 88 и 40 → 52 за 4 с (12 единиц; у настоящего объектива перевод 88 → 100 шёл 6,0 с): «готово» на конце времени и
    // не позже чем через 1,5 с после него, в единице от метки, команды не больше 5
    for (from, to) in [(88.0, 100.0), (100.0, 88.0), (40.0, 52.0)] {
        let run = runPZ(from: from, to: to, duration: 4, again: again)
        let name = "\(Int(from))→\(Int(to))"
        #expect(run.outcome == .reached, "\(name)")
        #expect((3.5...5.5).contains(run.finishedAt ?? .infinity), "\(name): \(run.finishedAt ?? -1) с")
        #expect(abs(run.lens.position - to) <= 1, "\(name): \(run.lens.position)")
        #expect(!run.moves.isEmpty)
        #expect(run.moves.allSatisfy { abs($0.steps) <= 5 }, "\(name)")
    }
}

@Test(arguments: [false, true])
func pzTwoSecondPullEndsWithinThreePointTwoSeconds(again: Bool) {
    // 100 → 87, 50 → 62 и 62 → 50 за 2 с (13 и 12 единиц; у настоящего объектива перевод 100 → 87 шёл 3,9 с)
    for (from, to) in [(100.0, 87.0), (50.0, 62.0), (62.0, 50.0)] {
        let run = runPZ(from: from, to: to, duration: 2, again: again)
        let name = "\(Int(from))→\(Int(to))"
        #expect(run.outcome == .reached, "\(name)")
        #expect((run.finishedAt ?? .infinity) <= 3.2, "\(name): \(run.finishedAt ?? -1) с")
        #expect(abs(run.lens.position - to) <= 1, "\(name): \(run.lens.position)")
        #expect(run.moves.allSatisfy { abs($0.steps) <= 5 }, "\(name)")
    }
}

@Test(arguments: [false, true])
func pzInstantPullGoesStraightToTheMark(again: Bool) {
    // 84 → 63 «как можно быстрее» (у настоящего объектива фокус бросило через всю шкалу 84 → 18 → 0 → 100 → … и кончилось «Не успел
    // за 3 с»): теперь четыре команды −5 подряд, в единице от метки, ни на единицу дальше 63 больше чем на 5 и ближе 84 + 5
    let run = runPZ(from: 84, to: 63, duration: 0, again: again)
    #expect(run.outcome == .reached)
    #expect((run.finishedAt ?? .infinity) <= 2.5, "\(run.finishedAt ?? -1) с")
    #expect(abs(run.lens.position - 63) <= 1, "\(run.lens.position)")
    #expect(overshoot(run, from: 84, to: 63) <= 5)
    #expect(run.moves.map { $0.steps } == [-5, -5, -5, -5])
    #expect(abs(run.pull.gain - 1) < 1e-9)                           // первая команда узнала k = 5 / 5
}

@Test(arguments: [false, true])
func pzMarksSweep(again: Bool) {
    // все пары меток 5, 15 … 95 и все времена перевода, где объектив успевает за кривой (его скорость — не больше пяти единиц за
    // 0,4 с, то есть 12,5 в секунду; пары дальше 8 · время + 20 единиц друг от друга не берём): везде «у метки» в единице от неё, не позже
    // чем через 2 с после конца времени, команды не больше 5 и фокус не дальше чем на 5 за дальнюю из меток
    let marks = Array(stride(from: 5.0, through: 95.0, by: 10.0))
    var failed: [String] = []
    var runs = 0
    for duration in FocusPull.durations {
        for from in marks {
            for to in marks where to != from && abs(to - from) <= 8 * duration + 20 {
                let run = runPZ(from: from, to: to, duration: duration, again: again)
                runs += 1
                let name = "\(Int(from))→\(Int(to)) за \(Int(duration)) с"
                if run.outcome != .reached {
                    failed.append("\(name): \(String(describing: run.outcome))")
                } else if (run.finishedAt ?? .infinity) - duration > 2 {
                    failed.append("\(name): кончился на \(run.finishedAt ?? -1) с")
                } else if abs(run.lens.position - to) > 1 {
                    failed.append("\(name): встал на \(run.lens.position)")
                } else if overshoot(run, from: from, to: to) > 5 {
                    failed.append("\(name): перелёт \(overshoot(run, from: from, to: to))")
                } else if !run.moves.allSatisfy({ abs($0.steps) <= 5 }) {
                    failed.append("\(name): команда больше 5")
                }
            }
        }
    }
    #expect(runs == 274, "прогонов \(runs)")
    #expect(failed.isEmpty, "\(failed.prefix(5))")
}

@Test func pzOneStepDoesNothingSoThePullStopsSendingIt() {
    // середина шкалы: ±1 ничего не сдвигает. Первый такой шаг уходит (ошибка у кривой чуть больше допуска) и остаётся пустым;
    // после него автомат шлёт не меньше 2 — и доходит до метки
    let run = runPZ(from: 40, to: 52, duration: 4, again: false)
    #expect(run.outcome == .reached)
    let ones = run.moves.filter { abs($0.steps) == 1 }
    #expect(ones.count == 1)
    #expect(ones.allSatisfy { $0.after == $0.before })
    #expect(!run.moves.isEmpty)
    #expect(abs(run.moves[0].steps) == 1)                            // и это самая первая команда
    #expect(run.moves.dropFirst().allSatisfy { abs($0.steps) >= 2 })
    #expect(abs(run.lens.position - 52) <= 1)
}

@Test(arguments: [false, true])
func oldFakeLensStillConverges(again: Bool) {
    // поддельная камера прежней физики (`--focus-gain 3.5 --focus-step-ms 80 --focus-deadband 2`): ответ через 80 мс, всё время
    // перевода, все пары меток — «у метки» не позже чем через секунду после конца времени, не дальше 2 единиц, команды не больше 5
    let marks = Array(stride(from: 5.0, through: 95.0, by: 10.0))
    var failed: [String] = []
    var runs = 0
    for duration in FocusPull.durations {
        for from in marks {
            for to in marks where to != from {
                let run = runSlow(from: from, to: to, duration: duration, lens: Lens(at: from, gain: 3.5, deadband: 2),
                                  delay: 0.08, again: again)
                runs += 1
                let name = "\(Int(from))→\(Int(to)) за \(Int(duration)) с"
                if run.outcome != .reached || (run.finishedAt ?? .infinity) - duration > 1 || abs(run.lens.position - to) > 2
                    || !run.moves.allSatisfy({ abs($0.steps) <= 5 }) {
                    failed.append("\(name): \(String(describing: run.outcome)) на \(run.finishedAt ?? -1) с, \(run.lens.position)")
                }
            }
        }
    }
    #expect(runs == 450, "прогонов \(runs)")
    #expect(failed.isEmpty, "\(failed.prefix(5))")
}

// MARK: - Шаги Near/Far: упёрлись, не успели

@Test func stepsStuck() {
    // d = 5: у верхнего края малые команды вверх не двигают, цель 100 из 80 — и объектив встаёт на 96
    let run = runSteps(from: 80, to: 100, duration: 2, lens: Lens(at: 80, gain: 3.5, deadband: 5))
    #expect(run.outcome == .stuck)
    // последние три команды — в одну сторону, и ни одна ничего не сдвинула
    let last = run.moves.suffix(3)
    #expect(last.count == 3)
    #expect(last.allSatisfy { $0.steps > 0 && $0.after == $0.before })
    #expect(run.lens.position < 98)
}

@Test func stepsStuckNeedsThreeInARowInOneDirection() {
    // две пустые команды, потом сдвиг, потом снова две пустые — ещё не «упёрлись»; третья пустая подряд — упёрлись.
    // Команда «пустая», когда положение не изменилось и с неё прошло 0,7 с, поэтому вызовы идут через 0,8 с. Время перевода 8 с и
    // вызовы с 2-й секунды: кривая всё время выше объектива, и на всём пути надо вперёд
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 8)
    let c1 = pull.next(elapsed: 2.0, position: 10)                   // команда 1
    let c2 = pull.next(elapsed: 2.8, position: 10)                   // команда 1 пустая (одна подряд); команда 2
    let c3 = pull.next(elapsed: 3.6, position: 10)                   // команда 2 пустая (две подряд) — ещё не упёрлись; команда 3
    let c4 = pull.next(elapsed: 4.4, position: 12)                   // команда 3 сдвинула — счёт с нуля; команда 4
    let c5 = pull.next(elapsed: 5.2, position: 12)                   // команда 4 пустая (одна подряд); команда 5
    let c6 = pull.next(elapsed: 6.0, position: 12)                   // команда 5 пустая (две подряд); команда 6
    #expect([c1, c2, c3, c4, c5, c6].allSatisfy { $0 == .nearFar(5) })
    let stuck = pull.next(elapsed: 6.8, position: 12)                // команда 6 пустая — три подряд
    #expect(stuck == .finish(.stuck))
}

@Test func stepsEmptyUnitStepCountsTowardsStuck() {
    // пустой шаг ±1 — такая же пустая команда, как любая: после него наименьшая команда 2, но счёт «три подряд» его помнит
    var pull = FocusPull(mode: .steps, from: 80, to: 100, duration: 0, gain: 3.5)
    let c1 = pull.next(elapsed: 0.0, position: 96)                   // до цели 4: round(1,14) = 1
    #expect(c1 == .nearFar(1))
    let c2 = pull.next(elapsed: 0.8, position: 96)                   // команда 1 пустая (одна подряд); теперь не меньше 2
    #expect(c2 == .nearFar(2))
    let c3 = pull.next(elapsed: 1.6, position: 96)                   // команда 2 пустая (две подряд)
    #expect(c3 == .nearFar(2))
    let stuck = pull.next(elapsed: 2.4, position: 96)                // команда 3 пустая — три подряд, среди них ±1
    #expect(stuck == .finish(.stuck))
}

@Test func stepsStuckCountRestartsWhenTheDirectionChanges() {
    // объектив не двигается совсем, а кривая идёт сверху вниз через его положение 50: пустые команды вверх и вниз вместе
    // не считаются — нужны три в одну сторону
    var pull = FocusPull(mode: .steps, from: 100, to: 0, duration: 2)
    let up1 = pull.next(elapsed: 0.0, position: 50)
    #expect(up1 == .nearFar(5))
    let up2 = pull.next(elapsed: 0.8, position: 50)                  // пустых вверх подряд: 1
    #expect(up2 == .nearFar(5))
    let down1 = pull.next(elapsed: 1.6, position: 50)                // вверх уже 2; кривая теперь ниже — команда вниз
    #expect(down1 == .nearFar(-5))
    let down2 = pull.next(elapsed: 2.4, position: 50)                // первая пустая вниз — счёт заново: 1
    #expect(down2 == .nearFar(-5))
    let down3 = pull.next(elapsed: 3.2, position: 50)                // вниз подряд: 2
    #expect(down3 == .nearFar(-5))
    let stuck = pull.next(elapsed: 4.0, position: 50)                // вниз подряд: 3 — упёрлись (всего пустых было пять)
    #expect(stuck == .finish(.stuck))
}

@Test func stepsStuckOnlyWhenTheCurveStillWantsThatWay() {
    // объектив стоит на 42 и не двигается; три пустые команды вверх набрались, но кривая (100 → 0 за 4 с) как раз проходит
    // через его положение — двигать нечего, ждём; а дальше она уходит вниз, и «упёрлись» про другое направление
    var pull = FocusPull(mode: .steps, from: 100, to: 0, duration: 4)
    _ = pull.next(elapsed: 0.0, position: 42)                        // команда вверх
    _ = pull.next(elapsed: 0.75, position: 42)                       // пустых вверх подряд: 1; команда вверх
    _ = pull.next(elapsed: 1.5, position: 42)                        // пустых подряд: 2; команда вверх
    let pass = pull.next(elapsed: 2.2, position: 42)                 // пустых подряд: 3, но кривая на 42,5: в допуске
    #expect(pass == .wait)
    let down = pull.next(elapsed: 2.3, position: 42)                 // кривая 38,8: команда вниз, а не «упёрлись»
    #expect(down == .nearFar(-3))
}

@Test func stepsTimeout() {
    // g = 0,2: команда сдвигает не больше чем на единицу (малые вовсе не двигают), за 1 с до 100 не дойти — тайм-аут через 3 с
    // после конца времени, то есть на 4,0 с
    let run = runSteps(from: 0, to: 100, duration: 1, lens: Lens(at: 0, gain: 0.2))
    #expect(run.outcome == .timedOut)
    let at = run.finishedAt ?? .infinity
    #expect(at <= 4.1)
    #expect(at >= 3.9)
    // объектив двигался (это не «упёрлись»), но не доехал
    #expect(run.lens.position > 10 && run.lens.position < 90)
}

@Test func stepsTimeoutCountsFromTheEndOfTheTime() {
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 8)
    let before = pull.next(elapsed: 10.9, position: 0)
    if case .finish = before { Issue.record("рано: срок 8 + 3 = 11 с") }
    // шагу 0,2 с и положение прежнее — по правилу устаревшего положения это ещё «ждём», но срок вышел: тайм-аут важнее
    let after = pull.next(elapsed: 11.1, position: 0)
    #expect(after == .finish(.timedOut))
    var instant = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let late = instant.next(elapsed: 3.1, position: 0)
    #expect(late == .finish(.timedOut))
}

// MARK: - Шаги Near/Far: положение не успело прийти

@Test func stepsStaleTicksAreNotEmptySteps() {
    // Шаг ушёл, а ответ камеры и свежее положение идут дольше тика: на тиках через 0,1 и 0,2 с положение ещё прежнее. Это не
    // «камера не двинулась» — автомат ждёт, пока положение не изменится или не пройдёт 0,7 с. Восемь шагов подряд, ни один не пустой.
    var pull = FocusPull(mode: .steps, from: 0, to: 1000, duration: 0)
    var position = 0.0
    var time = 0.0
    var action = pull.next(elapsed: time, position: position)
    for _ in 0..<8 {
        #expect(action == .nearFar(5))
        let first = pull.next(elapsed: time + 0.1, position: position)
        let second = pull.next(elapsed: time + 0.2, position: position)
        #expect(first == .wait)
        #expect(second == .wait)
        position += 25                                               // 5 · 5 = 25: положение пришло
        time += 0.25
        action = pull.next(elapsed: time, position: position)        // шаг учтён, следующий уходит сразу
    }
    #expect(action == .nearFar(5))
    // учтён как есть, со сдвигом 25: первое измерение заменило k на 25 / 5, остальные его подтвердили
    #expect(abs(pull.gain - 5) < 1e-9)
}

@Test func stepsStaleWaitEndsAfterSevenTenthsOfASecond() {
    // k = 3,5 задан явно: шаг ±1, после пустого ±1 — не меньше 2
    var pull = FocusPull(mode: .steps, from: 80, to: 100, duration: 0, gain: 3.5)
    let first = pull.next(elapsed: 0.0, position: 96)                // шаг +1
    #expect(first == .nearFar(1))
    let early = pull.next(elapsed: 0.45, position: 96)               // 0,45 с, положение прежнее — ждём (PZ 16-50 отвечает через 0,33–0,45 с)
    #expect(early == .wait)
    let almost = pull.next(elapsed: 0.65, position: 96)              // и 0,65 с — ещё ждём
    #expect(almost == .wait)
    let late = pull.next(elapsed: 0.75, position: 96)                // 0,75 с: камера не двинулась — пустой шаг, уходит следующий (≥ 2)
    #expect(late == .nearFar(2))

    // а изменившееся положение учитывается сразу, как только пришло (хоть через 10 мс): k заменяется по сдвигу 3 за шаг 5
    var quick = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let step = quick.next(elapsed: 1.0, position: 0)
    #expect(step == .nearFar(5))
    let reply = quick.next(elapsed: 1.01, position: 3)
    #expect(reply == .wait)                                          // следующая команда — на тике
    #expect(abs(quick.gain - 0.6) < 1e-12)
}

@Test(arguments: [2.0, 3.5, 5.0])
func stepsSlowCameraConvergesWithoutFalseStuck(gain: Double) {
    // Ответ камеры и свежее положение идут 120…280 мс, контроллер тикает каждые 100 мс — и тик за тиком видит прежнее положение.
    // Раньше каждый такой шаг засчитывался «ничего не сдвинул», три подряд давали «упёрлись» (на 450 переводах — все 450).
    // Теперь все доходят до метки, и не позже чем через 1,5 с после конца времени (команда ≤ 5 вместо ≤ 7: самые длинные пробеги
    // 5 → 95 у слабого объектива g = 2 идут около 3,2 с, раньше укладывались в 3).
    let marks = Array(stride(from: 5.0, through: 95.0, by: 10.0))
    var failed: [String] = []
    for delay in [0.12, 0.15, 0.18, 0.22, 0.28] {
        for again in [false, true] {
            for from in marks {
                for to in marks where to != from {
                    let run = runSlow(from: from, to: to, duration: 2, lens: Lens(at: from, gain: gain), delay: delay,
                                      again: again)
                    if run.outcome != .reached || (run.finishedAt ?? .infinity) > 3.5 {
                        failed.append("\(Int(from))→\(Int(to)) \(delay) с \(again ? "и" : "без") повтора: \(String(describing: run.outcome)) \(run.finishedAt ?? -1)")
                    }
                }
            }
        }
    }
    let sample = failed.prefix(3).joined(separator: "; ")
    #expect(failed.count == 0, "не дошли до метки: \(sample)")
}

@Test func stepsSilentCameraStillEnds() {
    // Камера молчит: положение не меняется никогда. Зависнуть нельзя: три пустых шага подряд — «упёрлись» (на 1…3 с), а если
    // до этого не дошло, то тайм-аут по сроку
    let cases: [(Double, Double, Double)] = [(10, 90, 2), (10, 90, 8), (90, 10, 0), (50, 52, 2), (0, 100, 0.5)]
    for (from, to, duration) in cases {
        for again in [false, true] {
            let run = runSlow(from: from, to: to, duration: duration, lens: Lens(at: from, gain: 0), delay: 0.12, again: again)
            #expect(run.outcome == .stuck || run.outcome == .timedOut, "\(from)→\(to) за \(duration) с: \(String(describing: run.outcome))")
            #expect((run.finishedAt ?? .infinity) <= duration + 3 + FocusPull.tick)
            #expect(!run.moves.isEmpty)
        }
        let direct = runSteps(from: from, to: to, duration: duration, lens: Lens(at: from, gain: 0))
        #expect(direct.outcome == .stuck || direct.outcome == .timedOut)
        #expect((direct.finishedAt ?? .infinity) <= duration + 3 + FocusPull.tick)
    }
}

@Test func focusPullSurvivesAbsurdMarks() {
    // Метки и положения, от которых разность не помещается в Double (±1e308), не роняют программу и дают разумные команды
    let big = 1e308
    var rising = FocusPull(mode: .absolute, from: -big, to: big, duration: 2)
    let low = rising.next(elapsed: 0, position: 0)
    #expect(low == .write(0))                                        // −1e308 — низ регистра
    let high = rising.next(elapsed: 1.5, position: 0)
    #expect(high == .write(65535))                                   // 0,69e308 — верх
    let end = rising.next(elapsed: 2.0, position: 0)
    #expect(end == .wait)                                            // цель в регистре — те же 65535, что уже записаны
    var falling = FocusPull(mode: .absolute, from: big, to: -big, duration: 0)
    let floor = falling.next(elapsed: 0, position: big)
    #expect(floor == .write(0))
    var instant = FocusPull(mode: .absolute, from: -big, to: big, duration: 0)
    let ceiling = instant.next(elapsed: 0, position: -big)
    #expect(ceiling == .write(65535))

    var steps = FocusPull(mode: .steps, from: -big, to: big, duration: 0)
    let up = steps.next(elapsed: 0, position: 0)
    #expect(up == .nearFar(5))
    var down = FocusPull(mode: .steps, from: big, to: -big, duration: 2)
    let far = down.next(elapsed: 2.0, position: big)                 // до цели −2e308: больше Double — всё равно самый большой шаг
    #expect(far == .nearFar(-5))
    #expect(down.progress.isFinite)
    #expect((0.0...1.0).contains(down.progress))
    #expect(down.desired(at: 1).isFinite)
    #expect(steps.desired(at: 0).isFinite)

    // регистр и шаг сами по себе: не-число — 0, бесконечность — край
    #expect(FocusPull.register(.nan) == 0)
    #expect(FocusPull.register(.infinity) == 65535)
    #expect(FocusPull.register(-.infinity) == 0)
    #expect(FocusPull.register(1e308) == 65535)
    #expect(FocusPull.register(-1e308) == 0)
    #expect(FocusPull.register(65535.4) == 65535)
    #expect(FocusPull.register(0.5) == 1)
    #expect(FocusPull.steps(from: .nan) == 0)
    #expect(FocusPull.steps(from: .infinity) == 5)
    #expect(FocusPull.steps(from: -.infinity) == -5)
    #expect(FocusPull.steps(from: 1e308) == 5)
    #expect(FocusPull.steps(from: 6.4) == 5)
    #expect(FocusPull.steps(from: -2.5) == -3)
    #expect(FocusPull.steps(from: 0.4) == 0)
}

@Test func focusPullNeverTrapsOnFiniteNumbers() {
    // Перебор крайних конечных чисел во всех местах: метки, время перевода, момент и положение — ни один вызов не падает,
    // кривая не-число не даёт, доля пути остаётся в 0…1
    let sizes: [Double] = [0, 1e-320, 5e-324, 1, 100, 65535, 1e300, 1e308, .greatestFiniteMagnitude]
    let values = sizes.flatMap { [$0, -$0] }
    let times: [Double] = [0, 0.05, 1, 2, 3, 10, 1e300, .infinity, -1, .nan]
    let positions: [Double] = [0, 50, 1e308, -1e308, .greatestFiniteMagnitude]
    var notNumbers = 0
    var badProgress = 0
    var calls = 0
    for mode in [FocusPull.Mode.absolute, .steps] {
        for from in values {
            for to in values {
                for duration in [0.0, 2.0, 1e-320, 1e300] {
                    var pull = FocusPull(mode: mode, from: from, to: to, duration: duration)
                    for time in times {
                        if pull.desired(at: time).isNaN { notNumbers += 1 }
                        for position in positions {
                            _ = pull.next(elapsed: time, position: position)
                            if !(0.0...1.0).contains(pull.progress) { badProgress += 1 }
                            calls += 1
                        }
                    }
                }
            }
        }
    }
    #expect(calls == 2 * 18 * 18 * 4 * 10 * 5)
    #expect(notNumbers == 0)
    #expect(badProgress == 0)
}

// MARK: - Шаги Near/Far: темп

@Test func stepsSendOneCommandPerTick() {
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 1.0, position: 0)
    #expect(first == .nearFar(5))
    // сразу после ответа камеры, в то же время: положение учтено (k заменён на 25 / 5), но новая команда — не раньше следующего тика
    let reply = pull.next(elapsed: 1.0, position: 25)
    #expect(reply == .wait)
    #expect(pull.gain == 5)
    let early = pull.next(elapsed: 1.05, position: 25)
    #expect(early == .wait)
    // тик с небольшим запасом на дрожание таймера: 95 мс после прошлой команды уже можно
    let tick = pull.next(elapsed: 1.095, position: 25)
    #expect(tick == .nearFar(5))
}

@Test func stepsTickDoesNotHoldBackTheEnd() {
    // «готово» и «упёрлись» от темпа не зависят: ответ на команду в тот же момент может закончить перевод
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 0.5, position: 0)
    #expect(first == .nearFar(5))
    let reply = pull.next(elapsed: 0.5, position: 100)
    #expect(reply == .finish(.reached))
}

// MARK: - Шаги Near/Far: пограничное

@Test func stepsSameMarkWaitsOutTheTime() {
    // метка совпала с положением: двигать нечего, но время перевода — те же 2 с, «готово» на конце
    var pull = FocusPull(mode: .steps, from: 40, to: 40, duration: 2)
    let early = pull.next(elapsed: 0.5, position: 40)
    #expect(early == .wait)
    let almost = pull.next(elapsed: 1.9, position: 40)
    #expect(almost == .wait)
    let end = pull.next(elapsed: 2.0, position: 40)
    #expect(end == .finish(.reached))
}

@Test func focusPullIgnoresNonFiniteInput() {
    // положения нет — ничего не делаем, но срок идёт; времени нет — это начало
    var steps = FocusPull(mode: .steps, from: 10, to: 90, duration: 2)
    let nan = steps.next(elapsed: 0.5, position: .nan)
    #expect(nan == .wait)
    let late = steps.next(elapsed: 6, position: .infinity)
    #expect(late == .finish(.timedOut))
    var absolute = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    let start = absolute.next(elapsed: .nan, position: 0)
    #expect(start == .write(0))
    // метки не-числа не ломают ничего: считаются нулём
    let odd = FocusPull(mode: .steps, from: .nan, to: .infinity, duration: 2)
    #expect(odd.desired(at: 1) == 0)
    #expect(odd.progress == 1)
}

// MARK: - Конец перевода

@Test func focusPullFinishIsFinal() {
    // после любого конца `next` отвечает тем же самым, что бы ни пришло потом
    var reached = FocusPull(mode: .steps, from: 50, to: 50, duration: 0)
    let one = reached.next(elapsed: 0, position: 50)
    #expect(one == .finish(.reached))
    let again = reached.next(elapsed: 1, position: 0)
    #expect(again == .finish(.reached))

    var timedOut = FocusPull(mode: .steps, from: 0, to: 100, duration: 1)
    let two = timedOut.next(elapsed: 4.1, position: 0)
    #expect(two == .finish(.timedOut))
    let still = timedOut.next(elapsed: 4.2, position: 100)
    #expect(still == .finish(.timedOut))

    var stuck = FocusPull(mode: .steps, from: 80, to: 100, duration: 0)
    for index in 0..<3 { _ = stuck.next(elapsed: Double(index) * 0.8, position: 96) }   // пустые шаги — через 0,8 с
    let three = stuck.next(elapsed: 2.4, position: 96)
    #expect(three == .finish(.stuck))
    let kept = stuck.next(elapsed: 1.3, position: 100)
    #expect(kept == .finish(.stuck))

    var absolute = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 0)
    _ = absolute.next(elapsed: 0, position: 0)
    let four = absolute.next(elapsed: 0.1, position: 65535)
    #expect(four == .finish(.reached))
    let last = absolute.next(elapsed: 0.2, position: 0)
    #expect(last == .finish(.reached))
}

@Test func focusPullCancelAnyTime() {
    for mode in [FocusPull.Mode.absolute, .steps] {
        // до первого тика
        var fresh = FocusPull(mode: mode, from: 10, to: 90, duration: 2)
        let cancelled = fresh.cancel()
        #expect(cancelled == .finish(.cancelled))
        let afterwards = fresh.next(elapsed: 0.1, position: 10)
        #expect(afterwards == .finish(.cancelled))

        // посреди пути, в том числе когда команда ушла, а ответа камеры ещё нет
        var moving = FocusPull(mode: mode, from: 10, to: 90, duration: 2)
        _ = moving.next(elapsed: 0.5, position: 10)
        _ = moving.next(elapsed: 1.0, position: 12)
        let midway = moving.cancel()
        #expect(midway == .finish(.cancelled))
        let silent = moving.next(elapsed: 1.1, position: 30)
        #expect(silent == .finish(.cancelled))

        // уже после конца: отмена отвечает «отменено», а первый исход не меняется
        var done = FocusPull(mode: mode, from: 50, to: 50, duration: 0)
        _ = done.next(elapsed: 0, position: 50)
        _ = done.next(elapsed: 0.1, position: 50)
        let late = done.cancel()
        #expect(late == .finish(.cancelled))
        let kept = done.next(elapsed: 0.2, position: 50)
        #expect(kept == .finish(.reached))
    }
}

@Test func focusPullProgress() {
    // путь вверх: 0 на старте, доля пройденного по последнему положению, 1 на цели, не больше 1 и не меньше 0
    var up = FocusPull(mode: .steps, from: 10, to: 90, duration: 2)
    #expect(up.progress == 0)
    _ = up.next(elapsed: 0.5, position: 30)
    #expect(abs(up.progress - 0.25) < 1e-12)
    _ = up.next(elapsed: 1.0, position: 50)
    #expect(abs(up.progress - 0.5) < 1e-12)
    _ = up.next(elapsed: 1.5, position: 130)
    #expect(up.progress == 1)
    _ = up.next(elapsed: 1.6, position: -20)
    #expect(up.progress == 0)
    _ = up.next(elapsed: 2.0, position: 90)
    #expect(up.progress == 1)                                        // это «готово»; положение на конце тоже учтено

    // путь вниз
    var down = FocusPull(mode: .steps, from: 90, to: 10, duration: 2)
    #expect(down.progress == 0)
    _ = down.next(elapsed: 0.5, position: 70)
    #expect(abs(down.progress - 0.25) < 1e-12)
    _ = down.next(elapsed: 1.0, position: 50)
    #expect(abs(down.progress - 0.5) < 1e-12)

    // абсолютный: доля от 0 до 65535
    var absolute = FocusPull(mode: .absolute, from: 0, to: 65535, duration: 2)
    #expect(absolute.progress == 0)
    _ = absolute.next(elapsed: 0.5, position: 16383.75)
    #expect(abs(absolute.progress - 0.25) < 1e-12)

    // метка там же, где старт: пути нет — всё пройдено
    let flat = FocusPull(mode: .steps, from: 40, to: 40, duration: 2)
    #expect(flat.progress == 1)
}

@Test func focusPullCaption() {
    #expect(FocusPull.caption(.reached, mark: "A") == "Фокус у метки A")
    #expect(FocusPull.caption(.reached, mark: "B") == "Фокус у метки B")
    #expect(FocusPull.caption(.stuck, mark: "A") == "Дальше камера не двигает")
    #expect(FocusPull.caption(.stuck, mark: "B") == "Дальше камера не двигает")
    #expect(FocusPull.caption(.timedOut, mark: "A") == "Не успел за 3 с")
    #expect(FocusPull.caption(.cancelled, mark: "A") == nil)
    #expect(FocusPull.caption(.cancelled, mark: "B") == nil)
}

// MARK: - Метки

@Test func focusMarksStartEmptyWithTwoSeconds() {
    let marks = FocusMarks()
    #expect(marks.a == nil)
    #expect(marks.b == nil)
    #expect(marks.duration == 2)
    let set = FocusMarks(a: 12, b: 80.5, duration: 4)
    #expect(set.a == 12)
    #expect(set.b == 80.5)
    #expect(set.duration == 4)
    #expect(set == FocusMarks(a: 12, b: 80.5, duration: 4))
    #expect(set != FocusMarks(a: 12, b: 80.5, duration: 8))
    #expect(set != FocusMarks(a: 12, b: nil, duration: 4))
}

@Test func focusMarksCycleDuration() {
    // 0 → 1 → 2 → 4 → 8 → 0, по кругу; метки при этом не трогает
    var marks = FocusMarks(a: 10, b: 20, duration: 0)
    var seen: [Double] = []
    for _ in 0..<7 {
        marks.cycleDuration()
        seen.append(marks.duration)
    }
    #expect(seen == [1, 2, 4, 8, 0, 1, 2])
    #expect(marks.a == 10)
    #expect(marks.b == 20)
    // с начальных двух секунд
    var fresh = FocusMarks()
    fresh.cycleDuration()
    #expect(fresh.duration == 4)
    // значение вне списка (например, из старого файла) — следующее из списка, а за краем — снова 0
    var odd = FocusMarks(duration: 3)
    odd.cycleDuration()
    #expect(odd.duration == 4)
    var big = FocusMarks(duration: 9)
    big.cycleDuration()
    #expect(big.duration == 0)
    var negative = FocusMarks(duration: -1)
    negative.cycleDuration()
    #expect(negative.duration == 0)
    var broken = FocusMarks(duration: .nan)
    broken.cycleDuration()
    #expect(broken.duration == 0)
}
