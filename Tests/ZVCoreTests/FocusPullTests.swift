import Foundation
import Testing
@testable import ZVCore

// MARK: - Объектив и прогон

/// Лёгкая модель объектива с шагами Near/Far: команда n сдвигает положение на n·g, округлённое от нуля на половинке
/// (3,5 → 4, −3,5 → −4, 2,5 → 3), но не за шкалу 0…`limit` (у настоящей — 100). Малая команда (|n| ≤ 3) у края шкалы,
/// ближе d единиц, к краю не двигает — «мёртвая зона». Так же ведёт себя поддельная камера (`scripts/fake_camera.py`,
/// `--focus-gain` и `--focus-deadband`), поэтому проверки на ней сойдутся с этими.
private struct Lens {
    private(set) var position: Double
    let gain: Double
    let deadband: Double
    let limit: Double

    init(at position: Double, gain: Double, deadband: Double = 0, limit: Double = 100) {
        self.position = position
        self.gain = gain
        self.deadband = deadband
        self.limit = limit
    }

    mutating func move(_ steps: Int64) {
        let toEdge = steps > 0 ? limit - position : position
        if steps != 0, abs(steps) <= 3, toEdge < deadband { return }
        let moved = (Double(steps) * gain).rounded(.toNearestOrAwayFromZero)
        position = min(max(position + moved, 0), limit)
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
/// Кончается, когда пришёл `.finish`, по горизонту или когда `stop` скажет «хватит» (после очередной команды).
private func runSteps(from start: Double, to target: Double, duration: Double, lens: Lens, gain: Double = 3.5,
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

/// Что запись `.write` кладёт в регистр; nil — это не запись.
private func written(_ action: FocusPull.Action) -> Int? {
    if case let .write(value) = action { return Int(value) }
    return nil
}

/// Что свежий шаговый перевод скажет, когда до цели `error` единиц (цель выше положения — ошибка положительная).
/// Метка одна (50 → 50), поэтому кривая стоит на месте: `ended` — время уже вышло (перевод за 0 с), иначе — ещё идёт.
private func decide(error: Double, ended: Bool = true, gain: Double = 3.5) -> FocusPull.Action {
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
    // 10 → 90 за 2 с, тики 0,1 с; объектив с усилением g, а автомат начинает с k = 3,5 и уточняет
    let run = runSteps(from: 10, to: 90, duration: 2, lens: Lens(at: 10, gain: gain))
    #expect(run.outcome == .reached)
    #expect((run.finishedAt ?? .infinity) < 5)
    #expect(abs(run.lens.position - 90) <= max(1, gain / 2) + 0.5)
    #expect(run.moves.allSatisfy { abs($0.steps) <= 7 })
    #expect(!run.moves.isEmpty)
}

@Test func stepsNoDither() throws {
    // g = 4: объектив стоит на 10, 14 … 50, 54, а цель 52 — между ними; ближе чем на 2 не подойти, k ≤ 4 — допуск меньше 2
    let run = runSteps(from: 10, to: 52, duration: 2, lens: Lens(at: 10, gain: 4))
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
    // за тик до конца два шага ±1 подряд перескочили цель 52 туда и обратно: на конце новой команды не будет
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 2)
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
    // не нужна, но и «готово» рано — оно придёт на конце времени
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 2)
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
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 0)
    let first = pull.next(elapsed: 0.0, position: 54)                // до цели −2: шаг −1
    #expect(first == .nearFar(-1))
    let second = pull.next(elapsed: 0.1, position: 40)               // ушёл на 14 вниз через цель; до цели 12: шаг +3
    #expect(second == .nearFar(3))
    let third = pull.next(elapsed: 0.2, position: 50)                // сдвинуло на 10; до цели 2: шаг +1
    #expect(third == .nearFar(1))
    let apart = pull.next(elapsed: 0.3, position: 54)                // через цель вверх; шаги −1 и +1 не соседние
    #expect(apart == .nearFar(-1))                                   // идёт дальше: до цели −2, шаг −1
    let end = pull.next(elapsed: 0.4, position: 50)                  // теперь соседние +1 и −1, оба через цель
    #expect(end == .finish(.reached))
}

@Test func stepsDitherNeedsOppositeUnitStepsThatCrossTheTarget() {
    // два шага ±1 подряд в разные стороны, и второй перескочил цель 52 с одной стороны на другую: ближе не подойти
    var pull = FocusPull(mode: .steps, from: 10, to: 52, duration: 0)
    let forward = pull.next(elapsed: 0.0, position: 50)              // до цели 2 > 1,75: шаг +1
    #expect(forward == .nearFar(1))
    let back = pull.next(elapsed: 0.1, position: 54)                 // перескочил: до цели −2, шаг −1
    #expect(back == .nearFar(-1))
    let end = pull.next(elapsed: 0.2, position: 50)                  // и обратно: два шага ±1 подряд, оба через цель
    #expect(end == .finish(.reached))

    // а если второй шаг цель не пересёк — это не дребезг: объектив просто ещё далеко
    var apart = FocusPull(mode: .steps, from: 10, to: 90, duration: 0)
    let first = apart.next(elapsed: 0.0, position: 85)               // до цели 5: шаг +1
    #expect(first == .nearFar(1))
    let second = apart.next(elapsed: 0.1, position: 95)              // перескочил на 5 выше: шаг −1
    #expect(second == .nearFar(-1))
    let third = apart.next(elapsed: 0.2, position: 96)               // шаг −1 «увёл» ещё дальше, цель не пересечена
    #expect(third == .nearFar(-2))                                   // до цели −6: команда, а не «готово»
}

@Test func stepsMarksSweep() {
    // все пары меток 5, 15 … 95 (в обе стороны и совпадающие), g = 3,5, время 2 с: ни одного тайм-аута
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
    // 90 → 10: отрицательный шаг уменьшает положение, то есть фокус ближе — все команды отрицательные
    let down = runSteps(from: 90, to: 10, duration: 2, lens: Lens(at: 90, gain: 3.5))
    #expect(down.outcome == .reached)
    #expect(!down.moves.isEmpty)
    #expect(down.moves.allSatisfy { $0.steps < 0 })
    #expect(down.moves.allSatisfy { $0.after < $0.before })
    // и наоборот
    let up = runSteps(from: 10, to: 90, duration: 2, lens: Lens(at: 10, gain: 3.5))
    #expect(!up.moves.isEmpty)
    #expect(up.moves.allSatisfy { $0.steps > 0 })
    // та же команда без объектива: на середине пути до кривой 40 единиц вниз — самый большой шаг, отрицательный
    var pull = FocusPull(mode: .steps, from: 90, to: 10, duration: 2)
    let action = pull.next(elapsed: 1.0, position: 90)
    #expect(action == .nearFar(-7))
}

@Test func stepsBounds() {
    // самая большая команда — 7, в любую сторону, как бы далеко ни была цель
    #expect(decide(error: 80) == .nearFar(7))
    #expect(decide(error: -80) == .nearFar(-7))
    #expect(decide(error: 1e9) == .nearFar(7))
    #expect(decide(error: -1e9) == .nearFar(-7))
    #expect(decide(error: 24.5) == .nearFar(7))
    #expect(decide(error: 22.8) == .nearFar(7))
    #expect(decide(error: 21) == .nearFar(6))
    // при k = 3,5 допуск 1,75: ошибка 1,5 — команды нет (ждём до конца, на конце — «готово»), ошибка 2,5 — шаг 1
    #expect(decide(error: 1.5, ended: false) == .wait)
    #expect(decide(error: -1.5, ended: false) == .wait)
    #expect(decide(error: 1.5) == .finish(.reached))
    #expect(decide(error: -1.5) == .finish(.reached))
    #expect(decide(error: 2.5, ended: false) == .nearFar(1))
    #expect(decide(error: -2.5, ended: false) == .nearFar(-1))
    #expect(decide(error: 2.5) == .nearFar(1))
    // ровно допуск — ещё «в допуске»; чуть больше — уже шаг
    #expect(decide(error: 1.75) == .finish(.reached))
    #expect(decide(error: 1.76) == .nearFar(1))
    #expect(decide(error: -1.76) == .nearFar(-1))
    // команда — e / k, округлённое от нуля на половинке: 1,5 → 2, 2,5 → 3
    #expect(decide(error: 5.24) == .nearFar(1))
    #expect(decide(error: 5.25) == .nearFar(2))
    #expect(decide(error: -5.25) == .nearFar(-2))
    #expect(decide(error: 8.74) == .nearFar(2))
    #expect(decide(error: 8.75) == .nearFar(3))
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

@Test func stepsGainAdapts() {
    // g = 5, k₀ = 3,5; каждая команда |n| ≥ 3 со сдвигом уточняет k ← 0,7k + 0,3·(Δ/n): 3,95 → 4,265 → 4,4855 → 4,64 → 4,748.
    // Пять таких команд в одном переводе на шкале 0…100 при g = 5 не уместить: каждая двигает на 15…35 единиц из ста
    // (0 → 100 — это 7, 7, 7; лучший быстрый перевод 5 → 95 за 0,5 с — 3, 4, 5, 5; см. следующую проверку). Закон от единиц
    // не зависит, поэтому здесь шкала длинная — 0…1000, и пять команд по 7 (по 35 единиц) идут подряд.
    let run = runSteps(from: 0, to: 1000, duration: 0, lens: Lens(at: 0, gain: 5, limit: 1000),
                       stop: { $0.moves.filter { abs($0.steps) >= 3 }.count >= 5 })
    #expect(run.moves.filter { abs($0.steps) >= 3 }.count == 5)
    #expect(run.pull.gain > 4.5 && run.pull.gain < 5.5)
    #expect(abs(run.pull.gain - 4.747895) < 1e-6)
}

@Test func stepsGainAdaptsOnTheRealScale() {
    // на шкале 0…100 при g = 5 быстрый перевод 5 → 95 за 0,5 с даёт четыре команды |n| ≥ 3 (3, 4, 5, 5): k уже около g
    let run = runSteps(from: 5, to: 95, duration: 0.5, lens: Lens(at: 5, gain: 5))
    #expect(run.outcome == .reached)
    #expect(run.moves.filter { abs($0.steps) >= 3 }.count == 4)
    #expect(run.pull.gain > 4.5 && run.pull.gain < 5.5)
}

@Test func stepsGainIgnoresSmallStepsAndStalls() {
    // команда |n| < 3 коэффициент не трогает, даже если сдвинула
    var small = FocusPull(mode: .steps, from: 50, to: 60, duration: 0)
    let one = small.next(elapsed: 0, position: 56)
    #expect(one == .nearFar(1))
    let moved = small.next(elapsed: 0.1, position: 60)
    #expect(moved == .finish(.reached))
    #expect(small.gain == 3.5)
    // и |n| = 2 тоже: уточняют только шаги от 3
    var two = FocusPull(mode: .steps, from: 50, to: 60, duration: 0)
    let pair = two.next(elapsed: 0, position: 52.5)                  // до цели 7,5: round(2,14) = 2
    #expect(pair == .nearFar(2))
    _ = two.next(elapsed: 0.1, position: 57.5)                       // сдвинуло на 5
    #expect(two.gain == 3.5)
    // команда |n| ≥ 3, которая ничего не сдвинула, — тоже
    var stalled = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let big = stalled.next(elapsed: 0, position: 0)
    #expect(big == .nearFar(7))
    _ = stalled.next(elapsed: 0.1, position: 0)
    #expect(stalled.gain == 3.5)
    // а сдвинувшая — уточняет: 0,7 · 3,5 + 0,3 · 25 / 7
    var moving = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    _ = moving.next(elapsed: 0, position: 0)
    _ = moving.next(elapsed: 0.1, position: 25)
    #expect(abs(moving.gain - (0.7 * 3.5 + 0.3 * 25.0 / 7.0)) < 1e-12)
}

@Test func stepsGainStaysPositiveAndSane() {
    // стартовое значение — в пределах 0,2…20, не-число — 3,5
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: 100).gain == 20)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: 0).gain == 0.2)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: -4).gain == 0.2)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1, gain: .nan).gain == 3.5)
    #expect(FocusPull(mode: .steps, from: 0, to: 1, duration: 1).gain == 3.5)
    // сдвиг, в разы больше, чем «n · k», тянет k вверх, но не выше 20
    var jump = FocusPull(mode: .steps, from: 0, to: 10_000, duration: 0)
    _ = jump.next(elapsed: 0, position: 0)
    _ = jump.next(elapsed: 0.1, position: 700)                        // 700 / 7 = 100
    #expect(jump.gain == 20)
    // сдвиг в обратную сторону (по формуле — отрицательное отношение) тянет k вниз, но не ниже 0,2 и не в минус
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
    #expect(gains.allSatisfy { $0 >= 0.2 })
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
    // две пустые команды, потом сдвиг, потом снова две пустые — ещё не «упёрлись»; третья пустая подряд — упёрлись
    var pull = FocusPull(mode: .steps, from: 80, to: 100, duration: 0)
    let c1 = pull.next(elapsed: 0.0, position: 96)                   // команда 1
    let c2 = pull.next(elapsed: 0.1, position: 96)                   // команда 1 пустая (одна подряд); команда 2
    let c3 = pull.next(elapsed: 0.2, position: 97)                   // команда 2 сдвинула — счёт с нуля; команда 3
    let c4 = pull.next(elapsed: 0.3, position: 97)                   // команда 3 пустая (одна подряд); команда 4
    let c5 = pull.next(elapsed: 0.4, position: 97)                   // команда 4 пустая (две подряд); команда 5
    #expect([c1, c2, c3, c4, c5].allSatisfy { $0 == .nearFar(1) })
    let stuck = pull.next(elapsed: 0.5, position: 97)                // команда 5 пустая — три подряд
    #expect(stuck == .finish(.stuck))
}

@Test func stepsStuckCountRestartsWhenTheDirectionChanges() {
    // объектив не двигается совсем, а кривая идёт сверху вниз через его положение 50: пустые команды вверх и вниз вместе
    // не считаются — нужны три в одну сторону
    var pull = FocusPull(mode: .steps, from: 100, to: 0, duration: 2)
    let up1 = pull.next(elapsed: 0.0, position: 50)
    #expect(up1 == .nearFar(7))
    let up2 = pull.next(elapsed: 0.1, position: 50)                  // пустых вверх подряд: 1
    #expect(up2 == .nearFar(7))
    let down1 = pull.next(elapsed: 1.5, position: 50)                // вверх уже 2; кривая теперь ниже — команда вниз
    #expect(down1 == .nearFar(-7))
    let down2 = pull.next(elapsed: 1.6, position: 50)                // первая пустая вниз — счёт заново: 1
    #expect(down2 == .nearFar(-7))
    let down3 = pull.next(elapsed: 1.7, position: 50)                // вниз подряд: 2
    #expect(down3 == .nearFar(-7))
    let stuck = pull.next(elapsed: 1.8, position: 50)                // вниз подряд: 3 — упёрлись (всего пустых было пять)
    #expect(stuck == .finish(.stuck))
}

@Test func stepsStuckOnlyWhenTheCurveStillWantsThatWay() {
    // объектив стоит на 50 и не двигается; три пустые команды вверх набрались, но кривая (100 → 0) как раз проходит
    // через его положение — двигать нечего, ждём; а дальше она уходит вниз, и «упёрлись» про другое направление
    var pull = FocusPull(mode: .steps, from: 100, to: 0, duration: 2)
    _ = pull.next(elapsed: 0.0, position: 50)                        // команда вверх
    _ = pull.next(elapsed: 0.1, position: 50)                        // пустых вверх подряд: 1; команда вверх
    _ = pull.next(elapsed: 0.2, position: 50)                        // пустых подряд: 2; команда вверх
    let pass = pull.next(elapsed: 1.0, position: 50)                 // пустых подряд: 3, но кривая на 50: в допуске
    #expect(pass == .wait)
    let down = pull.next(elapsed: 1.1, position: 50)                 // кривая ниже: команда вниз, а не «упёрлись»
    #expect(down == .nearFar(-2))
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
    let after = pull.next(elapsed: 11.1, position: 0)
    #expect(after == .finish(.timedOut))
    var instant = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let late = instant.next(elapsed: 3.1, position: 0)
    #expect(late == .finish(.timedOut))
}

// MARK: - Шаги Near/Far: темп

@Test func stepsSendOneCommandPerTick() {
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 1.0, position: 0)
    #expect(first == .nearFar(7))
    // сразу после ответа камеры, в то же время: положение учтено (k уточнился), но новая команда — не раньше следующего тика
    let reply = pull.next(elapsed: 1.0, position: 25)
    #expect(reply == .wait)
    #expect(abs(pull.gain - (0.7 * 3.5 + 0.3 * 25.0 / 7.0)) < 1e-12)
    let early = pull.next(elapsed: 1.05, position: 25)
    #expect(early == .wait)
    // тик с небольшим запасом на дрожание таймера: 95 мс после прошлой команды уже можно
    let tick = pull.next(elapsed: 1.095, position: 25)
    #expect(tick == .nearFar(7))
}

@Test func stepsTickDoesNotHoldBackTheEnd() {
    // «готово» и «упёрлись» от темпа не зависят: ответ на команду в тот же момент может закончить перевод
    var pull = FocusPull(mode: .steps, from: 0, to: 100, duration: 0)
    let first = pull.next(elapsed: 0.5, position: 0)
    #expect(first == .nearFar(7))
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
    for index in 0..<3 { _ = stuck.next(elapsed: Double(index) * FocusPull.tick, position: 96) }
    let three = stuck.next(elapsed: 0.3, position: 96)
    #expect(three == .finish(.stuck))
    let kept = stuck.next(elapsed: 0.4, position: 100)
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
