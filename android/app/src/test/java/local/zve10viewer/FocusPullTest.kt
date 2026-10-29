package local.zve10viewer

import local.zve10viewer.monitor.FocusMarks
import local.zve10viewer.monitor.FocusPull
import local.zve10viewer.monitor.FocusPull.Action
import local.zve10viewer.monitor.FocusPull.Mode
import local.zve10viewer.monitor.FocusPull.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

/**
 * Ближайшее целое, на половинке — от нуля (3,5 → 4, −3,5 → −4, 2,5 → 3), как `rounded()` в Swift. Своя, а не та, что в
 * программе: тесты не должны проверять программу её же округлением. kotlin.math.round округлил бы 2,5 к чётному — до 2.
 */
private fun halfAway(x: Double): Double = sign(x) * floor(abs(x) + 0.5)

/**
 * Перевод фокуса — те же проверки и те же числа, что у программы для Mac (FocusPullTests.swift). Объектив — лёгкая модель
 * с шагами Near/Far, как у поддельной камеры (`scripts/fake_camera.py`).
 */
class FocusPullTest {
    // MARK: - Объектив и прогон

    /**
     * Лёгкая модель объектива с шагами Near/Far: команда n сдвигает положение на n·g, округлённое от нуля на половинке
     * (3,5 → 4, −3,5 → −4, 2,5 → 3), но не за шкалу 0…`limit` (у настоящей — 100). Малая команда (|n| ≤ 3) у края шкалы,
     * ближе d единиц, к краю не двигает — «мёртвая зона». Так же ведёт себя поддельная камера (`scripts/fake_camera.py`,
     * `--focus-gain` и `--focus-deadband`), поэтому проверки на ней сойдутся с этими.
     */
    private class Lens(position: Double, val gain: Double, val deadband: Double = 0.0, val limit: Double = 100.0) {
        var position = position
            private set

        fun move(steps: Long) {
            val toEdge = if (steps > 0) limit - position else position
            if (steps != 0L && abs(steps) <= 3 && toEdge < deadband) return
            val moved = halfAway(steps.toDouble() * gain)
            position = min(max(position + moved, 0.0), limit)
        }
    }

    /** Выполненная команда: когда ушла, какая и откуда докуда сдвинула объектив. */
    private class Move(val time: Double, val steps: Long, val before: Double, val after: Double)

    /** Итог прогона шагового перевода. */
    private class Run(val pull: FocusPull, val lens: Lens) {
        /** Чем кончилось; null — за время прогона не кончилось (или прогон остановили раньше). */
        var outcome: Outcome? = null
        var finishedAt: Double? = null
        val moves = mutableListOf<Move>()
    }

    /**
     * Прогон шагового перевода так, как его ведёт контроллер: тики по 0,1 с, на каждом — `next` с положением объектива.
     * Команду исполняет объектив, и сразу после ответа камеры, в то же самое время, `next` зовут снова с новым положением;
     * если в ответ опять команда, она тоже уходит. Тики считаются по номеру (k · 0,1), а не накоплением, чтобы 2,0 с было
     * 2,0 с. Кончается, когда пришёл `Finish`, по горизонту или когда `stop` скажет «хватит» (после очередной команды).
     */
    private fun runSteps(
        from: Double, to: Double, duration: Double, lens: Lens, gain: Double = 3.5, horizon: Double = 10.0,
        stop: (Run) -> Boolean = { false },
    ): Run {
        val run = Run(FocusPull(Mode.steps, from, to, duration, gain), lens)
        for (index in 0..halfAway(horizon / FocusPull.TICK).toInt()) {
            val time = index * FocusPull.TICK
            var action = run.pull.next(time, run.lens.position)
            var chained = 0
            while (action is Action.NearFar) {
                val steps = action.steps
                val before = run.lens.position
                run.lens.move(steps)
                run.moves.add(Move(time, steps, before, run.lens.position))
                action = run.pull.next(time, run.lens.position)
                chained++
                if (stop(run)) return run
                if (chained >= 50) {
                    fail("команды без конца в один и тот же момент $time с")
                    return run
                }
            }
            if (action is Action.Finish) {
                run.outcome = action.outcome
                run.finishedAt = time
                return run
            }
        }
        return run
    }

    /**
     * Прогон с медленной камерой. Контроллер тикает каждые 0,1 с и зовёт `next` с тем положением, которое видит; шаг
     * объектив исполняет только через `delay` секунд после команды — до тех пор ответ камеры и свежий 0xD24C не пришли и
     * положение прежнее. `again` — сразу после команды, в тот же момент, `next` зовут ещё раз (как «после ответа камеры»):
     * положение при этом тоже ещё старое.
     */
    private fun runSlow(
        from: Double, to: Double, duration: Double, lens: Lens, delay: Double, again: Boolean, gain: Double = 3.5,
        horizon: Double = 12.0,
    ): Run {
        val run = Run(FocusPull(Mode.steps, from, to, duration, gain), lens)
        val due = ArrayDeque<Pair<Double, Long>>()
        for (index in 0..halfAway(horizon / FocusPull.TICK).toInt()) {
            val time = index * FocusPull.TICK
            while (due.isNotEmpty() && due.first().first <= time + 1e-9) {          // шаги, которые объектив уже исполнил
                val next = due.removeFirst()
                val before = run.lens.position
                run.lens.move(next.second)
                run.moves.add(Move(next.first, next.second, before, run.lens.position))
            }
            var action = run.pull.next(time, run.lens.position)
            var repeats = 0
            while (again && repeats < 50) {
                val command = action as? Action.NearFar ?: break
                due.add(Pair(time + delay, command.steps))
                action = run.pull.next(time, run.lens.position)
                repeats++
            }
            if (action is Action.NearFar) due.add(Pair(time + delay, action.steps))
            if (action is Action.Finish) {
                run.outcome = action.outcome
                run.finishedAt = time
                return run
            }
        }
        return run
    }

    /** Что запись `Write` кладёт в регистр; null — это не запись. */
    private fun written(action: Action): Int? = (action as? Action.Write)?.value

    /** Число записи; если это не запись — тест падает (как `try #require` в Swift). */
    private fun requireWritten(action: Action): Int {
        val value = written(action)
        assertNotNull("ждали запись, а пришло $action", value)
        return value!!
    }

    private fun nearFar(steps: Long): Action = Action.NearFar(steps)

    private fun finish(outcome: Outcome): Action = Action.Finish(outcome)

    /**
     * Что свежий шаговый перевод скажет, когда до цели `error` единиц (цель выше положения — ошибка положительная). Метка
     * одна (50 → 50), поэтому кривая стоит на месте: `ended` — время уже вышло (перевод за 0 с), иначе — ещё идёт.
     */
    private fun decide(error: Double, ended: Boolean = true, gain: Double = 3.5): Action {
        val pull = FocusPull(Mode.steps, 50.0, 50.0, if (ended) 0.0 else 2.0, gain)
        return pull.next(0.0, 50 - error)
    }

    // MARK: - Модель объектива

    @Test fun lensModelRoundsFromZeroAndHasADeadZone() {
        val lens = Lens(50.0, 3.5)
        lens.move(1)
        assertEquals(54.0, lens.position, 0.0)            // 3,5 → 4
        lens.move(-1)
        assertEquals(50.0, lens.position, 0.0)            // −3,5 → −4
        lens.move(7)
        assertEquals(75.0, lens.position, 0.0)            // 24,5 → 25
        lens.move(0)
        assertEquals(75.0, lens.position, 0.0)

        val slow = Lens(50.0, 2.5)
        slow.move(1)
        assertEquals(53.0, slow.position, 0.0)           // 2,5 → 3
        val tiny = Lens(50.0, 0.2)
        tiny.move(2)
        assertEquals(50.0, tiny.position, 0.0)           // 0,4 → 0
        tiny.move(3)
        assertEquals(51.0, tiny.position, 0.0)           // 0,6 → 1

        // шкала 0…100: за край не выходит
        val top = Lens(90.0, 5.0)
        top.move(7)
        assertEquals(100.0, top.position, 0.0)
        val bottom = Lens(10.0, 5.0)
        bottom.move(-7)
        assertEquals(0.0, bottom.position, 0.0)

        // мёртвая зона d = 5: ближе 5 единиц к краю малая команда к краю не двигает, большая — двигает, от края — двигает
        val dead = Lens(97.0, 3.5, deadband = 5.0)
        dead.move(1)
        dead.move(3)
        assertEquals(97.0, dead.position, 0.0)
        dead.move(-1)
        assertEquals(93.0, dead.position, 0.0)
        val big = Lens(97.0, 3.5, deadband = 5.0)
        big.move(4)
        assertEquals(100.0, big.position, 0.0)
        val border = Lens(95.0, 3.5, deadband = 5.0)
        border.move(1)
        assertEquals(99.0, border.position, 0.0)         // ровно 5 до края — это не «ближе 5»
        val low = Lens(3.0, 3.5, deadband = 5.0)
        low.move(-3)
        assertEquals(3.0, low.position, 0.0)             // у нижнего края — то же
        low.move(1)
        assertEquals(7.0, low.position, 0.0)
    }

    // MARK: - Кривая и время

    @Test fun focusPullKeepsTheShippedConstants() {
        assertEquals(listOf(0.0, 1.0, 2.0, 4.0, 8.0), FocusPull.durations)
        assertEquals(0.1, FocusPull.TICK, 0.0)
    }

    @Test fun easeIsTheCubicSmoothstep() {
        assertEquals(0.0, FocusPull.ease(0.0), 0.0)
        assertEquals(0.5, FocusPull.ease(0.5), 0.0)
        assertEquals(1.0, FocusPull.ease(1.0), 0.0)
        assertEquals(0.15625, FocusPull.ease(0.25), 1e-12)      // 3 · 0,0625 − 2 · 0,015625
        assertEquals(0.84375, FocusPull.ease(0.75), 1e-12)
        // t обрезается в 0…1; не-число — начало кривой
        assertEquals(0.0, FocusPull.ease(-3.0), 0.0)
        assertEquals(1.0, FocusPull.ease(7.0), 0.0)
        assertEquals(0.0, FocusPull.ease(Double.NEGATIVE_INFINITY), 0.0)
        assertEquals(1.0, FocusPull.ease(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(0.0, FocusPull.ease(Double.NaN), 0.0)
        // симметрична и не убывает
        var last = 0.0
        for (i in 0..100) {
            val t = i / 100.0
            assertTrue(abs(FocusPull.ease(t) + FocusPull.ease(1 - t) - 1) < 1e-12)
            assertTrue(FocusPull.ease(t) >= last)
            last = FocusPull.ease(t)
        }
    }

    @Test fun desiredFollowsTheCurveFromStartToTarget() {
        val up = FocusPull(Mode.steps, 10.0, 90.0, 2.0)
        assertEquals(10.0, up.desired(0.0), 0.0)
        assertEquals(10 + 80 * 0.15625, up.desired(0.5), 1e-9)
        assertEquals(50.0, up.desired(1.0), 1e-9)
        assertEquals(90.0, up.desired(2.0), 0.0)
        assertEquals(90.0, up.desired(5.0), 0.0)                // после конца — цель
        assertEquals(10.0, up.desired(-1.0), 0.0)               // до начала — старт
        val down = FocusPull(Mode.steps, 90.0, 10.0, 2.0)
        assertEquals(90 - 80 * 0.15625, down.desired(0.5), 1e-9)
        assertEquals(10.0, down.desired(2.0), 0.0)
        // время 0 — сразу цель; отрицательное время — то же, что 0
        assertEquals(90.0, FocusPull(Mode.steps, 10.0, 90.0, 0.0).desired(0.0), 0.0)
        assertEquals(90.0, FocusPull(Mode.steps, 10.0, 90.0, -3.0).desired(0.0), 0.0)
        assertEquals(65535.0, FocusPull(Mode.absolute, 0.0, 65535.0, Double.NaN).desired(0.0), 0.0)
    }

    // MARK: - Абсолютный фокус

    @Test fun absoluteFollowsCurve() {
        // 0 → 65535 за 2 с: на 0,5 с — четверть времени, 3t² − 2t³ = 0,15625 пути (10239,8); на 1,0 с — половина пути
        val pull = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        val quarter = pull.next(0.5, 0.0)
        val half = pull.next(1.0, 10240.0)
        val early = requireWritten(quarter)
        val middle = requireWritten(half)
        assertTrue(abs(early - 10240) <= 1)
        assertTrue(abs(middle - 32768) <= 1)
    }

    @Test fun absoluteStartsAtTheStartAndNeverGoesBack() {
        for ((from, to) in listOf(Pair(0.0, 65535.0), Pair(65535.0, 0.0), Pair(20000.0, 45000.0), Pair(45000.0, 20000.0))) {
            val pull = FocusPull(Mode.absolute, from, to, 2.0)
            val writes = mutableListOf<Int>()
            for (index in 0 until 20) {                                // 0,0 … 1,9 с: время ещё идёт
                val action = pull.next(index * FocusPull.TICK, from)
                if (action is Action.Write) writes.add(action.value)
            }
            assertEquals(from.toInt(), writes.firstOrNull())
            assertEquals(if (to > from) writes.sorted() else writes.sortedDescending(), writes)
            assertEquals(20, writes.size)
            // до конца времени цель не пишется
            assertFalse(writes.contains(to.toInt()))
        }
    }

    @Test fun absoluteZeroDurationWritesTarget() {
        val pull = FocusPull(Mode.absolute, 1000.0, 40000.0, 0.0)
        val first = pull.next(0.0, 1000.0)
        assertEquals(Action.Write(40000), first)
        // цель записана один раз; камера ещё в пути — ждём, второй раз не пишем
        val second = pull.next(0.1, 12000.0)
        assertEquals(Action.Wait, second)
        val third = pull.next(0.2, 39900.0)
        assertEquals(finish(Outcome.reached), third)
    }

    @Test fun absoluteWritesTheTargetOnceAtTheEnd() {
        val pull = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        pull.next(1.9, 60000.0)
        val end = pull.next(2.0, 60000.0)
        assertEquals(Action.Write(65535), end)
        // дальше камера едет к цели: ждём, цель не повторяем
        for (time in listOf(2.0, 2.1, 2.2, 3.0, 3.9)) {
            val action = pull.next(time, 61000.0)
            assertEquals(Action.Wait, action)
        }
    }

    @Test fun absoluteReachedWithinHalfPercent() {
        // 0,5 % от 65535 — 327,675 единицы. Цель к концу времени уже записана; камера от цели ближе — готово, дальше — ждём.
        // В задании пример — положение 65200 при цели 65535: это 335 единиц, 0,511 %, чуть за 0,5 %, поэтому «готово» тут
        // начинается с 65208 (327 единиц), а 65207 (328) и 65200 (335) ещё ждут.
        fun answer(position: Double, target: Double = 65535.0): Action {
            val pull = FocusPull(Mode.absolute, 0.0, target, 2.0)
            pull.next(2.0, 0.0)                                      // конец времени: пишется цель
            return pull.next(2.1, position)
        }
        assertEquals(finish(Outcome.reached), answer(65535.0))
        assertEquals(finish(Outcome.reached), answer(65250.0))      // 285 единиц, 0,435 %
        assertEquals(finish(Outcome.reached), answer(65208.0))      // 327 единиц — внутри
        assertEquals(Action.Wait, answer(65207.0))                  // 328 единиц — снаружи
        assertEquals(Action.Wait, answer(65200.0))                  // 335 единиц, 0,511 %
        assertEquals(Action.Wait, answer(60000.0))
        // по ту сторону цели — так же; ошибка — от цели, а не от шкалы
        assertEquals(finish(Outcome.reached), answer(30327.0, target = 30000.0))
        assertEquals(Action.Wait, answer(30328.0, target = 30000.0))
        assertEquals(finish(Outcome.reached), answer(29673.0, target = 30000.0))
        assertEquals(Action.Wait, answer(29672.0, target = 30000.0))
    }

    @Test fun absoluteReachedNeedsTheTargetWritten() {
        // камера уже у цели, но цель ещё не записана (первое обращение уже после конца): сначала пишем, потом «готово»
        val pull = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        val first = pull.next(2.0, 65500.0)
        assertEquals(Action.Write(65535), first)
        val second = pull.next(2.0, 65500.0)
        assertEquals(finish(Outcome.reached), second)
    }

    @Test fun absoluteTimesOut() {
        // камера стоит на месте: цель записана на 2,0 с, ждать можно ещё 2 с — до 4,0 с
        val pull = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        var finishedAt: Double? = null
        var finishedWith: Outcome? = null
        for (index in 0..50) {
            if (finishedAt != null) break
            val time = index * FocusPull.TICK
            val action = pull.next(time, 30000.0)
            if (action is Action.Finish) {
                finishedAt = time
                finishedWith = action.outcome
            }
        }
        assertEquals(Outcome.timedOut, finishedWith)
        assertTrue((finishedAt ?: 0.0) in 3.95..4.1)
        // до срока — ещё ждём, на 4,1 с — тайм-аут, и он не отменяется, даже если камера потом доехала
        val other = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        other.next(2.0, 30000.0)
        val before = other.next(3.9, 30000.0)
        assertEquals(Action.Wait, before)
        val after = other.next(4.1, 30000.0)
        assertEquals(finish(Outcome.timedOut), after)
        val late = other.next(4.2, 65535.0)
        assertEquals(finish(Outcome.timedOut), late)
        // а если камера доехала как раз к сроку — это «готово», а не тайм-аут
        val arrived = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        arrived.next(2.0, 30000.0)
        val justInTime = arrived.next(4.1, 65500.0)
        assertEquals(finish(Outcome.reached), justInTime)
    }

    @Test fun absoluteDoesNotRepeatTheSameValue() {
        // то же число камере второй раз не пишем: ответ на запись, пришедший в том же тике, лишних записей не даёт
        val pull = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        val first = pull.next(0.5, 0.0)
        assertEquals(Action.Write(10240), first)
        val again = pull.next(0.5, 10240.0)
        assertEquals(Action.Wait, again)
        val later = pull.next(0.6, 10240.0)
        assertNotNull(written(later))
    }

    @Test fun absoluteTimeoutCountsFromTheEndOfTheTime() {
        // ждать можно 2 с после конца времени: для перевода за 8 с срок — 10 с, для перевода за 0 с — 2 с
        val long = FocusPull(Mode.absolute, 0.0, 65535.0, 8.0)
        long.next(8.0, 0.0)
        val stillWaiting = long.next(9.9, 0.0)
        assertEquals(Action.Wait, stillWaiting)
        val gaveUp = long.next(10.1, 0.0)
        assertEquals(finish(Outcome.timedOut), gaveUp)
        val instant = FocusPull(Mode.absolute, 0.0, 65535.0, 0.0)
        instant.next(0.0, 0.0)
        val waiting = instant.next(1.9, 0.0)
        assertEquals(Action.Wait, waiting)
        val timedOut = instant.next(2.1, 0.0)
        assertEquals(finish(Outcome.timedOut), timedOut)
    }

    @Test fun absoluteRunsToTheTarget() {
        // камера мгновенно встаёт туда, куда ей написали: двадцать записей по кривой, на конце — цель, и «готово»
        val pull = FocusPull(Mode.absolute, 5000.0, 60000.0, 2.0)
        var position = 5000.0
        val writes = mutableListOf<Int>()
        var outcome: Outcome? = null
        var time = 0.0
        for (index in 0..40) {
            if (outcome != null) break
            time = index * FocusPull.TICK
            var action = pull.next(time, position)
            val value = written(action)
            if (value != null) {
                writes.add(value)
                position = value.toDouble()
                action = pull.next(time, position)                   // после ответа камеры
            }
            if (action is Action.Finish) outcome = action.outcome
        }
        assertEquals(Outcome.reached, outcome)
        assertEquals(21, writes.size)
        assertEquals(60000, writes.lastOrNull())
        assertEquals(2.0, time, 0.0)
    }

    @Test fun absoluteKeepsWritesInsideTheRegister() {
        // метка за шкалой не ломает запись: регистр 0…65535
        val high = FocusPull(Mode.absolute, 0.0, 70000.0, 0.0)
        val top = high.next(0.0, 0.0)
        assertEquals(Action.Write(65535), top)
        val low = FocusPull(Mode.absolute, -100.0, 70000.0, 2.0)
        val bottom = low.next(0.0, 0.0)
        assertEquals(Action.Write(0), bottom)
        val down = FocusPull(Mode.absolute, 100.0, -500.0, 0.0)
        val lowest = down.next(0.0, 100.0)
        assertEquals(Action.Write(0), lowest)
    }

    // MARK: - Шаги Near/Far: сходимость

    @Test fun stepsConverge() {
        // 10 → 90 за 2 с, тики 0,1 с; объектив с усилением g, а автомат начинает с k = 3,5 и уточняет
        for (gain in listOf(2.0, 3.5, 5.0)) {
            val run = runSteps(10.0, 90.0, 2.0, Lens(10.0, gain))
            assertEquals("g = $gain", Outcome.reached, run.outcome)
            assertTrue("g = $gain: ${run.finishedAt}", (run.finishedAt ?: Double.POSITIVE_INFINITY) < 5)
            assertTrue("g = $gain: ${run.lens.position}", abs(run.lens.position - 90) <= max(1.0, gain / 2) + 0.5)
            assertTrue("g = $gain", run.moves.all { abs(it.steps) <= 7 })
            assertTrue("g = $gain", run.moves.isNotEmpty())
        }
    }

    @Test fun stepsNoDither() {
        // g = 4: объектив стоит на 10, 14 … 50, 54, а цель 52 — между ними; ближе чем на 2 не подойти, k ≤ 4 — допуск меньше 2
        val run = runSteps(10.0, 52.0, 2.0, Lens(10.0, 4.0))
        assertEquals(Outcome.reached, run.outcome)
        assertNotEquals(Outcome.timedOut, run.outcome)
        // цель перескочена — положение оказалось по другую сторону от неё
        val jump = run.moves.indexOfFirst { (it.before - 52) * (it.after - 52) < 0 }
        assertTrue("цель так и не перескочили", jump >= 0)
        val after = run.moves.drop(jump + 1)
        assertTrue(after.size <= 2)
        assertTrue(after.all { abs(it.steps) == 1L })
        assertTrue(abs(run.lens.position - 52) <= 2)
    }

    @Test fun stepsDitherFinishesAtTheEndWithoutAnotherCommand() {
        // за тик до конца два шага ±1 подряд перескочили цель 52 туда и обратно: на конце новой команды не будет
        val pull = FocusPull(Mode.steps, 10.0, 52.0, 2.0)
        val first = pull.next(1.8, 48.9)                             // до кривой 1,92 > 1,75: шаг вперёд
        assertEquals(nearFar(1), first)
        val jumped = pull.next(1.8, 54.0)                            // перескочил цель
        assertEquals(Action.Wait, jumped)
        val second = pull.next(1.9, 54.0)                            // кривая 51,7: назад
        assertEquals(nearFar(-1), second)
        val back = pull.next(1.9, 50.0)                              // и обратно через цель
        assertEquals(Action.Wait, back)                              // время ещё идёт, ошибка 1,7 — в допуске
        val end = pull.next(2.0, 50.0)                               // конец: до цели 2 > 1,75, но ближе не подойти
        assertEquals(finish(Outcome.reached), end)
    }

    @Test fun stepsDitherDoesNotEndTheTimeEarly() {
        // два шага ±1 перескочили цель 52 туда и обратно ещё до конца времени, а до кривой по-прежнему далеко: новая команда
        // не нужна, но и «готово» рано — оно придёт на конце времени
        val pull = FocusPull(Mode.steps, 10.0, 52.0, 2.0)
        val down = pull.next(1.7, 54.0)                              // кривая 49,45: до неё −4,55 → шаг −1
        assertEquals(nearFar(-1), down)
        val up = pull.next(1.8, 48.0)                                // перескочил цель вниз; кривая 50,82: до неё 2,82 → шаг +1
        assertEquals(nearFar(1), up)
        val over = pull.next(1.8, 54.0)                              // и вверх через цель: время ещё идёт
        assertEquals(Action.Wait, over)
        val end = pull.next(2.0, 54.0)                               // конец времени: до цели −2, ближе не подойти
        assertEquals(finish(Outcome.reached), end)
    }

    @Test fun stepsDitherPairMustBeNextToEachOther() {
        // шаг −1 через цель, потом шаг другого размера, потом +1 через цель: два шага ±1 тут не «подряд» — это ещё не дребезг
        val pull = FocusPull(Mode.steps, 10.0, 52.0, 0.0)
        val first = pull.next(0.0, 54.0)                             // до цели −2: шаг −1
        assertEquals(nearFar(-1), first)
        val second = pull.next(0.1, 40.0)                            // ушёл на 14 вниз через цель; до цели 12: шаг +3
        assertEquals(nearFar(3), second)
        val third = pull.next(0.2, 50.0)                             // сдвинуло на 10; до цели 2: шаг +1
        assertEquals(nearFar(1), third)
        val apart = pull.next(0.3, 54.0)                             // через цель вверх; шаги −1 и +1 не соседние
        assertEquals(nearFar(-1), apart)                             // идёт дальше: до цели −2, шаг −1
        val end = pull.next(0.4, 50.0)                               // теперь соседние +1 и −1, оба через цель
        assertEquals(finish(Outcome.reached), end)
    }

    @Test fun stepsDitherNeedsOppositeUnitStepsThatCrossTheTarget() {
        // два шага ±1 подряд в разные стороны, и второй перескочил цель 52 с одной стороны на другую: ближе не подойти
        val pull = FocusPull(Mode.steps, 10.0, 52.0, 0.0)
        val forward = pull.next(0.0, 50.0)                           // до цели 2 > 1,75: шаг +1
        assertEquals(nearFar(1), forward)
        val back = pull.next(0.1, 54.0)                              // перескочил: до цели −2, шаг −1
        assertEquals(nearFar(-1), back)
        val end = pull.next(0.2, 50.0)                               // и обратно: два шага ±1 подряд, оба через цель
        assertEquals(finish(Outcome.reached), end)

        // а если второй шаг цель не пересёк — это не дребезг: объектив просто ещё далеко
        val apart = FocusPull(Mode.steps, 10.0, 90.0, 0.0)
        val first = apart.next(0.0, 85.0)                            // до цели 5: шаг +1
        assertEquals(nearFar(1), first)
        val second = apart.next(0.1, 95.0)                           // перескочил на 5 выше: шаг −1
        assertEquals(nearFar(-1), second)
        val third = apart.next(0.2, 96.0)                            // шаг −1 «увёл» ещё дальше, цель не пересечена
        assertEquals(nearFar(-2), third)                             // до цели −6: команда, а не «готово»
    }

    @Test fun stepsMarksSweep() {
        // все пары меток 5, 15 … 95 (в обе стороны и совпадающие), g = 3,5, время 2 с: ни одного тайм-аута
        val marks = (0..9).map { 5.0 + 10.0 * it }
        val timedOut = mutableListOf<String>()
        val unfinished = mutableListOf<String>()
        val stuck = mutableListOf<String>()
        for (from in marks) {
            for (to in marks) {
                val run = runSteps(from, to, 2.0, Lens(from, 3.5))
                val name = "${from.toInt()}→${to.toInt()}"
                when (run.outcome) {
                    Outcome.timedOut -> timedOut.add(name)
                    Outcome.stuck -> stuck.add(name)
                    null -> unfinished.add(name)
                    else -> {}
                }
            }
        }
        assertTrue("тайм-аут: $timedOut", timedOut.isEmpty())
        assertTrue("не кончились: $unfinished", unfinished.isEmpty())
        assertTrue("упёрлись: $stuck", stuck.isEmpty())
    }

    @Test fun stepsSignCloser() {
        // 90 → 10: отрицательный шаг уменьшает положение, то есть фокус ближе — все команды отрицательные
        val down = runSteps(90.0, 10.0, 2.0, Lens(90.0, 3.5))
        assertEquals(Outcome.reached, down.outcome)
        assertTrue(down.moves.isNotEmpty())
        assertTrue(down.moves.all { it.steps < 0 })
        assertTrue(down.moves.all { it.after < it.before })
        // и наоборот
        val up = runSteps(10.0, 90.0, 2.0, Lens(10.0, 3.5))
        assertTrue(up.moves.isNotEmpty())
        assertTrue(up.moves.all { it.steps > 0 })
        // та же команда без объектива: на середине пути до кривой 40 единиц вниз — самый большой шаг, отрицательный
        val pull = FocusPull(Mode.steps, 90.0, 10.0, 2.0)
        val action = pull.next(1.0, 90.0)
        assertEquals(nearFar(-7), action)
    }

    @Test fun stepsBounds() {
        // самая большая команда — 7, в любую сторону, как бы далеко ни была цель
        assertEquals(nearFar(7), decide(80.0))
        assertEquals(nearFar(-7), decide(-80.0))
        assertEquals(nearFar(7), decide(1e9))
        assertEquals(nearFar(-7), decide(-1e9))
        assertEquals(nearFar(7), decide(24.5))
        assertEquals(nearFar(7), decide(22.8))
        assertEquals(nearFar(6), decide(21.0))
        // при k = 3,5 допуск 1,75: ошибка 1,5 — команды нет (ждём до конца, на конце — «готово»), ошибка 2,5 — шаг 1
        assertEquals(Action.Wait, decide(1.5, ended = false))
        assertEquals(Action.Wait, decide(-1.5, ended = false))
        assertEquals(finish(Outcome.reached), decide(1.5))
        assertEquals(finish(Outcome.reached), decide(-1.5))
        assertEquals(nearFar(1), decide(2.5, ended = false))
        assertEquals(nearFar(-1), decide(-2.5, ended = false))
        assertEquals(nearFar(1), decide(2.5))
        // ровно допуск — ещё «в допуске»; чуть больше — уже шаг
        assertEquals(finish(Outcome.reached), decide(1.75))
        assertEquals(nearFar(1), decide(1.76))
        assertEquals(nearFar(-1), decide(-1.76))
        // команда — e / k, округлённое от нуля на половинке: 1,5 → 2, 2,5 → 3
        assertEquals(nearFar(1), decide(5.24))
        assertEquals(nearFar(2), decide(5.25))
        assertEquals(nearFar(-2), decide(-5.25))
        assertEquals(nearFar(2), decide(8.74))
        assertEquals(nearFar(3), decide(8.75))
    }

    @Test fun stepsToleranceFollowsTheGain() {
        // допуск — max(1, k / 2): при большом k он растёт, при малом — не меньше 1
        assertEquals(Action.Wait, decide(3.9, ended = false, gain = 8.0))
        assertEquals(nearFar(1), decide(4.1, ended = false, gain = 8.0))
        assertEquals(finish(Outcome.reached), decide(4.0, gain = 8.0))
        assertEquals(finish(Outcome.reached), decide(1.0, gain = 0.5))
        assertEquals(nearFar(2), decide(1.1, gain = 0.5))            // 1,1 / 0,5 = 2,2
        assertEquals(Action.Wait, decide(0.9, ended = false, gain = 0.5))
    }

    // MARK: - Шаги Near/Far: коэффициент k

    @Test fun stepsGainAdapts() {
        // g = 5, k₀ = 3,5; каждая команда |n| ≥ 3 со сдвигом уточняет k ← 0,7k + 0,3·(Δ/n): 3,95 → 4,265 → 4,4855 → 4,64 → 4,748.
        // Пять таких команд в одном переводе на шкале 0…100 при g = 5 не уместить: каждая двигает на 15…35 единиц из ста
        // (0 → 100 — это 7, 7, 7; лучший быстрый перевод 5 → 95 за 0,5 с — 3, 4, 5, 5; см. следующую проверку). Закон от единиц
        // не зависит, поэтому здесь шкала длинная — 0…1000, и пять команд по 7 (по 35 единиц) идут подряд.
        val run = runSteps(0.0, 1000.0, 0.0, Lens(0.0, 5.0, limit = 1000.0),
            stop = { r -> r.moves.count { abs(it.steps) >= 3 } >= 5 })
        assertEquals(5, run.moves.count { abs(it.steps) >= 3 })
        assertTrue(run.pull.gain > 4.5 && run.pull.gain < 5.5)
        assertEquals(4.747895, run.pull.gain, 1e-6)
    }

    @Test fun stepsGainAdaptsOnTheRealScale() {
        // на шкале 0…100 при g = 5 быстрый перевод 5 → 95 за 0,5 с даёт четыре команды |n| ≥ 3 (3, 4, 5, 5): k уже около g
        val run = runSteps(5.0, 95.0, 0.5, Lens(5.0, 5.0))
        assertEquals(Outcome.reached, run.outcome)
        assertEquals(4, run.moves.count { abs(it.steps) >= 3 })
        assertTrue(run.pull.gain > 4.5 && run.pull.gain < 5.5)
    }

    @Test fun stepsGainIgnoresSmallStepsAndStalls() {
        // команда |n| < 3 коэффициент не трогает, даже если сдвинула
        val small = FocusPull(Mode.steps, 50.0, 60.0, 0.0)
        val one = small.next(0.0, 56.0)
        assertEquals(nearFar(1), one)
        val moved = small.next(0.1, 60.0)
        assertEquals(finish(Outcome.reached), moved)
        assertEquals(3.5, small.gain, 0.0)
        // и |n| = 2 тоже: уточняют только шаги от 3
        val two = FocusPull(Mode.steps, 50.0, 60.0, 0.0)
        val pair = two.next(0.0, 52.5)                               // до цели 7,5: round(2,14) = 2
        assertEquals(nearFar(2), pair)
        two.next(0.1, 57.5)                                          // сдвинуло на 5
        assertEquals(3.5, two.gain, 0.0)
        // команда |n| ≥ 3, которая ничего не сдвинула, — тоже
        val stalled = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        val big = stalled.next(0.0, 0.0)
        assertEquals(nearFar(7), big)
        val waiting = stalled.next(0.1, 0.0)                         // положение прежнее, 0,1 с — ещё ждём
        assertEquals(Action.Wait, waiting)
        val settled = stalled.next(0.4, 0.0)                         // 0,3 с прошло: пустой шаг, k не трогаем
        assertEquals(nearFar(7), settled)
        assertEquals(3.5, stalled.gain, 0.0)
        // а сдвинувшая — уточняет: 0,7 · 3,5 + 0,3 · 25 / 7
        val moving = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        moving.next(0.0, 0.0)
        moving.next(0.1, 25.0)
        assertEquals(0.7 * 3.5 + 0.3 * 25.0 / 7.0, moving.gain, 1e-12)
    }

    @Test fun stepsGainStaysPositiveAndSane() {
        // стартовое значение — в пределах 0,2…20, не-число — 3,5
        assertEquals(20.0, FocusPull(Mode.steps, 0.0, 1.0, 1.0, gain = 100.0).gain, 0.0)
        assertEquals(0.2, FocusPull(Mode.steps, 0.0, 1.0, 1.0, gain = 0.0).gain, 0.0)
        assertEquals(0.2, FocusPull(Mode.steps, 0.0, 1.0, 1.0, gain = -4.0).gain, 0.0)
        assertEquals(3.5, FocusPull(Mode.steps, 0.0, 1.0, 1.0, gain = Double.NaN).gain, 0.0)
        assertEquals(3.5, FocusPull(Mode.steps, 0.0, 1.0, 1.0).gain, 0.0)
        // сдвиг, в разы больше, чем «n · k», тянет k вверх, но не выше 20
        val jump = FocusPull(Mode.steps, 0.0, 10_000.0, 0.0)
        jump.next(0.0, 0.0)
        jump.next(0.1, 700.0)                                        // 700 / 7 = 100
        assertEquals(20.0, jump.gain, 0.0)
        // сдвиг в обратную сторону (по формуле — отрицательное отношение) тянет k вниз, но не ниже 0,2 и не в минус
        val back = FocusPull(Mode.steps, 0.0, 10_000.0, 0.0)
        var position = 5000.0
        val gains = mutableListOf<Double>()
        for (index in 0 until 30) {
            val action = back.next(index * FocusPull.TICK, position)
            if (action !is Action.NearFar) break
            position -= 35
            gains.add(back.gain)
        }
        assertTrue(gains.size >= 3)
        assertTrue(gains.all { it >= 0.2 })
    }

    // MARK: - Шаги Near/Far: упёрлись, не успели

    @Test fun stepsStuck() {
        // d = 5: у верхнего края малые команды вверх не двигают, цель 100 из 80 — и объектив встаёт на 96
        val run = runSteps(80.0, 100.0, 2.0, Lens(80.0, 3.5, deadband = 5.0))
        assertEquals(Outcome.stuck, run.outcome)
        // последние три команды — в одну сторону, и ни одна ничего не сдвинула
        val last = run.moves.takeLast(3)
        assertEquals(3, last.size)
        assertTrue(last.all { it.steps > 0 && it.after == it.before })
        assertTrue(run.lens.position < 98)
    }

    @Test fun stepsStuckNeedsThreeInARowInOneDirection() {
        // две пустые команды, потом сдвиг, потом снова две пустые — ещё не «упёрлись»; третья пустая подряд — упёрлись.
        // Команда «пустая», когда положение не изменилось и с неё прошло 0,3 с, поэтому вызовы идут через 0,4 с
        val pull = FocusPull(Mode.steps, 80.0, 100.0, 0.0)
        val c1 = pull.next(0.0, 96.0)                                // команда 1
        val c2 = pull.next(0.4, 96.0)                                // команда 1 пустая (одна подряд); команда 2
        val c3 = pull.next(0.8, 97.0)                                // команда 2 сдвинула — счёт с нуля; команда 3
        val c4 = pull.next(1.2, 97.0)                                // команда 3 пустая (одна подряд); команда 4
        val c5 = pull.next(1.6, 97.0)                                // команда 4 пустая (две подряд); команда 5
        assertTrue(listOf(c1, c2, c3, c4, c5).all { it == nearFar(1) })
        val stuck = pull.next(2.0, 97.0)                             // команда 5 пустая — три подряд
        assertEquals(finish(Outcome.stuck), stuck)
    }

    @Test fun stepsStuckCountRestartsWhenTheDirectionChanges() {
        // объектив не двигается совсем, а кривая идёт сверху вниз через его положение 50: пустые команды вверх и вниз вместе
        // не считаются — нужны три в одну сторону
        val pull = FocusPull(Mode.steps, 100.0, 0.0, 2.0)
        val up1 = pull.next(0.0, 50.0)
        assertEquals(nearFar(7), up1)
        val up2 = pull.next(0.4, 50.0)                               // пустых вверх подряд: 1
        assertEquals(nearFar(7), up2)
        val down1 = pull.next(1.6, 50.0)                             // вверх уже 2; кривая теперь ниже — команда вниз
        assertEquals(nearFar(-7), down1)
        val down2 = pull.next(2.0, 50.0)                             // первая пустая вниз — счёт заново: 1
        assertEquals(nearFar(-7), down2)
        val down3 = pull.next(2.4, 50.0)                             // вниз подряд: 2
        assertEquals(nearFar(-7), down3)
        val stuck = pull.next(2.8, 50.0)                             // вниз подряд: 3 — упёрлись (всего пустых было пять)
        assertEquals(finish(Outcome.stuck), stuck)
    }

    @Test fun stepsStuckOnlyWhenTheCurveStillWantsThatWay() {
        // объектив стоит на 50 и не двигается; три пустые команды вверх набрались, но кривая (100 → 0) как раз проходит
        // через его положение — двигать нечего, ждём; а дальше она уходит вниз, и «упёрлись» про другое направление
        val pull = FocusPull(Mode.steps, 100.0, 0.0, 2.0)
        pull.next(0.0, 50.0)                                         // команда вверх
        pull.next(0.33, 50.0)                                        // пустых вверх подряд: 1; команда вверх
        pull.next(0.66, 50.0)                                        // пустых подряд: 2; команда вверх
        val pass = pull.next(1.0, 50.0)                              // пустых подряд: 3, но кривая на 50: в допуске
        assertEquals(Action.Wait, pass)
        val down = pull.next(1.1, 50.0)                              // кривая ниже: команда вниз, а не «упёрлись»
        assertEquals(nearFar(-2), down)
    }

    @Test fun stepsTimeout() {
        // g = 0,2: команда сдвигает не больше чем на единицу (малые вовсе не двигают), за 1 с до 100 не дойти — тайм-аут через
        // 3 с после конца времени, то есть на 4,0 с
        val run = runSteps(0.0, 100.0, 1.0, Lens(0.0, 0.2))
        assertEquals(Outcome.timedOut, run.outcome)
        val at = run.finishedAt ?: Double.POSITIVE_INFINITY
        assertTrue(at <= 4.1)
        assertTrue(at >= 3.9)
        // объектив двигался (это не «упёрлись»), но не доехал
        assertTrue(run.lens.position > 10 && run.lens.position < 90)
    }

    @Test fun stepsTimeoutCountsFromTheEndOfTheTime() {
        val pull = FocusPull(Mode.steps, 0.0, 100.0, 8.0)
        val before = pull.next(10.9, 0.0)
        if (before is Action.Finish) fail("рано: срок 8 + 3 = 11 с")
        // шагу 0,2 с и положение прежнее — по правилу устаревшего положения это ещё «ждём», но срок вышел: тайм-аут важнее
        val after = pull.next(11.1, 0.0)
        assertEquals(finish(Outcome.timedOut), after)
        val instant = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        val late = instant.next(3.1, 0.0)
        assertEquals(finish(Outcome.timedOut), late)
    }

    // MARK: - Шаги Near/Far: положение не успело прийти

    @Test fun stepsStaleTicksAreNotEmptySteps() {
        // Шаг ушёл, а ответ камеры и свежее положение идут дольше тика: на тиках через 0,1 и 0,2 с положение ещё прежнее. Это не
        // «камера не двинулась» — автомат ждёт, пока положение не изменится или не пройдёт 0,3 с. Восемь шагов подряд, ни один не пустой.
        val pull = FocusPull(Mode.steps, 0.0, 1000.0, 0.0)
        var position = 0.0
        var time = 0.0
        var action = pull.next(time, position)
        for (round in 0 until 8) {
            assertEquals("шаг $round", nearFar(7), action)
            val first = pull.next(time + 0.1, position)
            val second = pull.next(time + 0.2, position)
            assertEquals(Action.Wait, first)
            assertEquals(Action.Wait, second)
            position += 25                                           // 7 · 3,5 = 24,5 → 25: положение пришло
            time += 0.25
            action = pull.next(time, position)                       // шаг учтён, следующий уходит сразу
        }
        assertEquals(nearFar(7), action)
        // учтён как есть, со сдвигом 25: k подошёл к 25 / 7
        assertTrue(abs(pull.gain - 25.0 / 7.0) < 0.01)
    }

    @Test fun stepsStaleWaitEndsAfterThreeTenthsOfASecond() {
        val pull = FocusPull(Mode.steps, 80.0, 100.0, 0.0)
        val first = pull.next(0.0, 96.0)                             // шаг +1
        assertEquals(nearFar(1), first)
        val early = pull.next(0.25, 96.0)                            // 0,25 с, положение прежнее — ждём
        assertEquals(Action.Wait, early)
        val late = pull.next(0.35, 96.0)                             // 0,35 с: камера не двинулась — пустой шаг, уходит следующий
        assertEquals(nearFar(1), late)

        // граница — 0,3 с от команды: в 0,29 с ещё ждём, в 0,31 с шаг уже пустой
        val edge = FocusPull(Mode.steps, 80.0, 100.0, 0.0)
        edge.next(0.0, 96.0)
        assertEquals(Action.Wait, edge.next(0.29, 96.0))
        assertEquals(nearFar(1), edge.next(0.31, 96.0))

        // а изменившееся положение учитывается сразу, как только пришло (хоть через 10 мс): k уточняется по сдвигу 3 за шаг 7
        val quick = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        val step = quick.next(1.0, 0.0)
        assertEquals(nearFar(7), step)
        val reply = quick.next(1.01, 3.0)
        assertEquals(Action.Wait, reply)                             // следующая команда — на тике
        assertEquals(0.7 * 3.5 + 0.3 * 3.0 / 7.0, quick.gain, 1e-12)
    }

    @Test fun stepsSlowCameraConvergesWithoutFalseStuck() {
        // Ответ камеры и свежее положение идут 120…280 мс, контроллер тикает каждые 100 мс — и тик за тиком видит прежнее
        // положение. Раньше каждый такой шаг засчитывался «ничего не сдвинул», три подряд давали «упёрлись» (на 450
        // переводах — все 450). Теперь все доходят до метки, и не позже чем за секунду после конца времени.
        val marks = (0..9).map { 5.0 + 10.0 * it }
        for (gain in listOf(2.0, 3.5, 5.0)) {
            val failed = mutableListOf<String>()
            for (delay in listOf(0.12, 0.15, 0.18, 0.22, 0.28)) {
                for (again in listOf(false, true)) {
                    for (from in marks) {
                        for (to in marks) {
                            if (to == from) continue
                            val run = runSlow(from, to, 2.0, Lens(from, gain), delay, again)
                            if (run.outcome != Outcome.reached || (run.finishedAt ?: Double.POSITIVE_INFINITY) > 3) {
                                failed.add("${from.toInt()}→${to.toInt()} $delay с ${if (again) "и" else "без"} повтора: " +
                                    "${run.outcome} ${run.finishedAt ?: -1.0}")
                            }
                        }
                    }
                }
            }
            val sample = failed.take(3).joinToString("; ")
            assertEquals("g = $gain, не дошли до метки: $sample", 0, failed.size)
        }
    }

    @Test fun stepsSilentCameraStillEnds() {
        // Камера молчит: положение не меняется никогда. Зависнуть нельзя: три пустых шага подряд — «упёрлись» (на 1…3 с), а если
        // до этого не дошло, то тайм-аут по сроку
        val cases = listOf(Triple(10.0, 90.0, 2.0), Triple(10.0, 90.0, 8.0), Triple(90.0, 10.0, 0.0), Triple(50.0, 52.0, 2.0),
            Triple(0.0, 100.0, 0.5))
        for ((from, to, duration) in cases) {
            for (again in listOf(false, true)) {
                val run = runSlow(from, to, duration, Lens(from, 0.0), 0.12, again)
                val name = "$from→$to за $duration с: ${run.outcome}"
                assertTrue(name, run.outcome == Outcome.stuck || run.outcome == Outcome.timedOut)
                assertTrue(name, (run.finishedAt ?: Double.POSITIVE_INFINITY) <= duration + 3 + FocusPull.TICK)
                assertTrue(name, run.moves.isNotEmpty())
            }
            val direct = runSteps(from, to, duration, Lens(from, 0.0))
            assertTrue(direct.outcome == Outcome.stuck || direct.outcome == Outcome.timedOut)
            assertTrue((direct.finishedAt ?: Double.POSITIVE_INFINITY) <= duration + 3 + FocusPull.TICK)
        }
    }

    @Test fun focusPullSurvivesAbsurdMarks() {
        // Метки и положения, от которых разность не помещается в Double (±1e308), не роняют программу и дают разумные команды
        val big = 1e308
        val rising = FocusPull(Mode.absolute, -big, big, 2.0)
        val low = rising.next(0.0, 0.0)
        assertEquals(Action.Write(0), low)                           // −1e308 — низ регистра
        val high = rising.next(1.5, 0.0)
        assertEquals(Action.Write(65535), high)                      // 0,69e308 — верх
        val end = rising.next(2.0, 0.0)
        assertEquals(Action.Wait, end)                               // цель в регистре — те же 65535, что уже записаны
        val falling = FocusPull(Mode.absolute, big, -big, 0.0)
        val lowest = falling.next(0.0, big)
        assertEquals(Action.Write(0), lowest)
        val instant = FocusPull(Mode.absolute, -big, big, 0.0)
        val ceiling = instant.next(0.0, -big)
        assertEquals(Action.Write(65535), ceiling)

        val steps = FocusPull(Mode.steps, -big, big, 0.0)
        val up = steps.next(0.0, 0.0)
        assertEquals(nearFar(7), up)
        val down = FocusPull(Mode.steps, big, -big, 2.0)
        val far = down.next(2.0, big)                                // до цели −2e308: больше Double — всё равно самый большой шаг
        assertEquals(nearFar(-7), far)
        assertTrue(down.progress.isFinite())
        assertTrue(down.progress in 0.0..1.0)
        assertTrue(down.desired(1.0).isFinite())
        assertTrue(steps.desired(0.0).isFinite())

        // регистр и шаг сами по себе: не-число — 0, бесконечность — край
        assertEquals(0, FocusPull.register(Double.NaN))
        assertEquals(65535, FocusPull.register(Double.POSITIVE_INFINITY))
        assertEquals(0, FocusPull.register(Double.NEGATIVE_INFINITY))
        assertEquals(65535, FocusPull.register(1e308))
        assertEquals(0, FocusPull.register(-1e308))
        assertEquals(65535, FocusPull.register(65535.4))
        assertEquals(1, FocusPull.register(0.5))
        assertEquals(0L, FocusPull.commandSteps(Double.NaN))
        assertEquals(7L, FocusPull.commandSteps(Double.POSITIVE_INFINITY))
        assertEquals(-7L, FocusPull.commandSteps(Double.NEGATIVE_INFINITY))
        assertEquals(7L, FocusPull.commandSteps(1e308))
        assertEquals(-3L, FocusPull.commandSteps(-2.5))
        assertEquals(0L, FocusPull.commandSteps(0.4))
    }

    @Test fun focusPullNeverTrapsOnFiniteNumbers() {
        // Перебор крайних конечных чисел во всех местах: метки, время перевода, момент и положение — ни один вызов не бросает
        // исключение, кривая не-число не даёт, доля пути остаётся в 0…1 (в Swift здесь программа падала бы на преобразовании)
        val sizes = listOf(0.0, 1e-320, 5e-324, 1.0, 100.0, 65535.0, 1e300, 1e308, Double.MAX_VALUE)
        val values = sizes.flatMap { listOf(it, -it) }
        val times = listOf(0.0, 0.05, 1.0, 2.0, 3.0, 10.0, 1e300, Double.POSITIVE_INFINITY, -1.0, Double.NaN)
        val positions = listOf(0.0, 50.0, 1e308, -1e308, Double.MAX_VALUE)
        var notNumbers = 0
        var badProgress = 0
        var calls = 0
        for (mode in listOf(Mode.absolute, Mode.steps)) {
            for (from in values) {
                for (to in values) {
                    for (duration in listOf(0.0, 2.0, 1e-320, 1e300)) {
                        val pull = FocusPull(mode, from, to, duration)
                        for (time in times) {
                            if (pull.desired(time).isNaN()) notNumbers++
                            for (position in positions) {
                                pull.next(time, position)
                                if (pull.progress !in 0.0..1.0) badProgress++
                                calls++
                            }
                        }
                    }
                }
            }
        }
        assertEquals(2 * 18 * 18 * 4 * 10 * 5, calls)
        assertEquals(0, notNumbers)
        assertEquals(0, badProgress)
    }

    // MARK: - Шаги Near/Far: темп

    @Test fun stepsSendOneCommandPerTick() {
        val pull = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        val first = pull.next(1.0, 0.0)
        assertEquals(nearFar(7), first)
        // сразу после ответа камеры, в то же время: положение учтено (k уточнился), но новая команда — не раньше следующего тика
        val reply = pull.next(1.0, 25.0)
        assertEquals(Action.Wait, reply)
        assertTrue(abs(pull.gain - (0.7 * 3.5 + 0.3 * 25.0 / 7.0)) < 1e-12)
        val early = pull.next(1.05, 25.0)
        assertEquals(Action.Wait, early)
        // тик с небольшим запасом на дрожание таймера: 95 мс после прошлой команды уже можно
        val tick = pull.next(1.095, 25.0)
        assertEquals(nearFar(7), tick)
    }

    @Test fun stepsTickDoesNotHoldBackTheEnd() {
        // «готово» и «упёрлись» от темпа не зависят: ответ на команду в тот же момент может закончить перевод
        val pull = FocusPull(Mode.steps, 0.0, 100.0, 0.0)
        val first = pull.next(0.5, 0.0)
        assertEquals(nearFar(7), first)
        val reply = pull.next(0.5, 100.0)
        assertEquals(finish(Outcome.reached), reply)
    }

    // MARK: - Шаги Near/Far: пограничное

    @Test fun stepsSameMarkWaitsOutTheTime() {
        // метка совпала с положением: двигать нечего, но время перевода — те же 2 с, «готово» на конце
        val pull = FocusPull(Mode.steps, 40.0, 40.0, 2.0)
        val early = pull.next(0.5, 40.0)
        assertEquals(Action.Wait, early)
        val almost = pull.next(1.9, 40.0)
        assertEquals(Action.Wait, almost)
        val end = pull.next(2.0, 40.0)
        assertEquals(finish(Outcome.reached), end)
    }

    @Test fun focusPullIgnoresNonFiniteInput() {
        // положения нет — ничего не делаем, но срок идёт; времени нет — это начало
        val steps = FocusPull(Mode.steps, 10.0, 90.0, 2.0)
        val nan = steps.next(0.5, Double.NaN)
        assertEquals(Action.Wait, nan)
        val late = steps.next(6.0, Double.POSITIVE_INFINITY)
        assertEquals(finish(Outcome.timedOut), late)
        val absolute = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        val start = absolute.next(Double.NaN, 0.0)
        assertEquals(Action.Write(0), start)
        // метки не-числа не ломают ничего: считаются нулём
        val odd = FocusPull(Mode.steps, Double.NaN, Double.POSITIVE_INFINITY, 2.0)
        assertEquals(0.0, odd.desired(1.0), 0.0)
        assertEquals(1.0, odd.progress, 0.0)
        // и бесконечность вместо метки — тоже 0; конечные метки и время остаются как были
        assertEquals(0.0, FocusPull(Mode.steps, Double.POSITIVE_INFINITY, 50.0, 2.0).start, 0.0)
        assertEquals(0.0, FocusPull(Mode.steps, Double.NEGATIVE_INFINITY, 50.0, 2.0).start, 0.0)
        assertEquals(0.0, FocusPull(Mode.steps, 10.0, Double.NEGATIVE_INFINITY, 2.0).target, 0.0)
        assertEquals(0.0, FocusPull(Mode.steps, 10.0, Double.NaN, 2.0).target, 0.0)
        val plain = FocusPull(Mode.steps, 10.0, 90.0, 2.0)
        assertEquals(10.0, plain.start, 0.0)
        assertEquals(90.0, plain.target, 0.0)
        assertEquals(2.0, plain.duration, 0.0)
    }

    @Test fun negativeElapsedReadsAsTheStart() {
        // время до начала — это начало: перевод за 0 с, куда надо — «готово» сразу, как на нуле
        for (time in listOf(-1.0, Double.NEGATIVE_INFINITY, -1e-9)) {
            val steps = FocusPull(Mode.steps, 50.0, 50.0, 0.0)
            assertEquals("шаги, $time", finish(Outcome.reached), steps.next(time, 50.0))
            val absolute = FocusPull(Mode.absolute, 0.0, 40000.0, 0.0)
            assertEquals("запись, $time", Action.Write(40000), absolute.next(time, 0.0))
            assertEquals("готово, $time", finish(Outcome.reached), absolute.next(time, 40000.0))
        }
    }

    @Test fun brokenDurationReadsAsZero() {
        // отрицательное время и не-число — как 0: и свойство, и поведение (срок тоже от нуля, а не от −3 с)
        for (duration in listOf(-3.0, Double.NEGATIVE_INFINITY, Double.NaN, Double.POSITIVE_INFINITY)) {
            for (mode in listOf(Mode.absolute, Mode.steps)) {
                val odd = FocusPull(mode, 0.0, 100.0, duration)
                val zero = FocusPull(mode, 0.0, 100.0, 0.0)
                assertEquals("$mode, $duration", 0.0, odd.duration, 0.0)
                for (time in listOf(0.0, 0.1, 0.5, 2.0, 3.1, 5.0)) {
                    assertEquals("$mode, $duration, $time", zero.next(time, 0.0), odd.next(time, 0.0))
                }
            }
        }
    }

    // MARK: - Конец перевода

    @Test fun focusPullFinishIsFinal() {
        // после любого конца `next` отвечает тем же самым, что бы ни пришло потом
        val reached = FocusPull(Mode.steps, 50.0, 50.0, 0.0)
        val one = reached.next(0.0, 50.0)
        assertEquals(finish(Outcome.reached), one)
        val again = reached.next(1.0, 0.0)
        assertEquals(finish(Outcome.reached), again)

        val timedOut = FocusPull(Mode.steps, 0.0, 100.0, 1.0)
        val two = timedOut.next(4.1, 0.0)
        assertEquals(finish(Outcome.timedOut), two)
        val still = timedOut.next(4.2, 100.0)
        assertEquals(finish(Outcome.timedOut), still)

        val stuck = FocusPull(Mode.steps, 80.0, 100.0, 0.0)
        for (index in 0 until 3) stuck.next(index * 0.4, 96.0)       // пустые шаги — через 0,4 с
        val three = stuck.next(1.2, 96.0)
        assertEquals(finish(Outcome.stuck), three)
        val kept = stuck.next(1.3, 100.0)
        assertEquals(finish(Outcome.stuck), kept)

        val absolute = FocusPull(Mode.absolute, 0.0, 65535.0, 0.0)
        absolute.next(0.0, 0.0)
        val four = absolute.next(0.1, 65535.0)
        assertEquals(finish(Outcome.reached), four)
        val last = absolute.next(0.2, 0.0)
        assertEquals(finish(Outcome.reached), last)
    }

    @Test fun focusPullCancelAnyTime() {
        for (mode in listOf(Mode.absolute, Mode.steps)) {
            // до первого тика
            val fresh = FocusPull(mode, 10.0, 90.0, 2.0)
            val cancelled = fresh.cancel()
            assertEquals(finish(Outcome.cancelled), cancelled)
            val afterwards = fresh.next(0.1, 10.0)
            assertEquals(finish(Outcome.cancelled), afterwards)

            // посреди пути, в том числе когда команда ушла, а ответа камеры ещё нет
            val moving = FocusPull(mode, 10.0, 90.0, 2.0)
            moving.next(0.5, 10.0)
            moving.next(1.0, 12.0)
            val midway = moving.cancel()
            assertEquals(finish(Outcome.cancelled), midway)
            val silent = moving.next(1.1, 30.0)
            assertEquals(finish(Outcome.cancelled), silent)

            // уже после конца: отмена отвечает «отменено», а первый исход не меняется
            val done = FocusPull(mode, 50.0, 50.0, 0.0)
            done.next(0.0, 50.0)
            done.next(0.1, 50.0)
            val late = done.cancel()
            assertEquals(finish(Outcome.cancelled), late)
            val kept = done.next(0.2, 50.0)
            assertEquals(finish(Outcome.reached), kept)
        }
    }

    @Test fun focusPullProgress() {
        // путь вверх: 0 на старте, доля пройденного по последнему положению, 1 на цели, не больше 1 и не меньше 0
        val up = FocusPull(Mode.steps, 10.0, 90.0, 2.0)
        assertEquals(0.0, up.progress, 0.0)
        up.next(0.5, 30.0)
        assertEquals(0.25, up.progress, 1e-12)
        up.next(1.0, 50.0)
        assertEquals(0.5, up.progress, 1e-12)
        up.next(1.5, 130.0)
        assertEquals(1.0, up.progress, 0.0)
        up.next(1.6, -20.0)
        assertEquals(0.0, up.progress, 0.0)
        up.next(2.0, 90.0)
        assertEquals(1.0, up.progress, 0.0)                          // это «готово»; положение на конце тоже учтено

        // путь вниз
        val down = FocusPull(Mode.steps, 90.0, 10.0, 2.0)
        assertEquals(0.0, down.progress, 0.0)
        down.next(0.5, 70.0)
        assertEquals(0.25, down.progress, 1e-12)
        down.next(1.0, 50.0)
        assertEquals(0.5, down.progress, 1e-12)

        // абсолютный: доля от 0 до 65535
        val absolute = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        assertEquals(0.0, absolute.progress, 0.0)
        absolute.next(0.5, 16383.75)
        assertEquals(0.25, absolute.progress, 1e-12)

        // метка там же, где старт: пути нет — всё пройдено
        val flat = FocusPull(Mode.steps, 40.0, 40.0, 2.0)
        assertEquals(1.0, flat.progress, 0.0)
    }

    @Test fun focusPullCaption() {
        assertEquals("Фокус у метки A", FocusPull.caption(Outcome.reached, "A"))
        assertEquals("Фокус у метки B", FocusPull.caption(Outcome.reached, "B"))
        assertEquals("Дальше камера не двигает", FocusPull.caption(Outcome.stuck, "A"))
        assertEquals("Дальше камера не двигает", FocusPull.caption(Outcome.stuck, "B"))
        assertEquals("Не успел за 3 с", FocusPull.caption(Outcome.timedOut, "A"))
        assertNull(FocusPull.caption(Outcome.cancelled, "A"))
        assertNull(FocusPull.caption(Outcome.cancelled, "B"))
    }

    // MARK: - Метки

    @Test fun focusMarksStartEmptyWithTwoSeconds() {
        val marks = FocusMarks()
        assertNull(marks.a)
        assertNull(marks.b)
        assertEquals(2.0, marks.duration, 0.0)
        val set = FocusMarks(12.0, 80.5, 4.0)
        assertEquals(12.0, set.a!!, 0.0)
        assertEquals(80.5, set.b!!, 0.0)
        assertEquals(4.0, set.duration, 0.0)
        assertEquals(set, FocusMarks(12.0, 80.5, 4.0))
        assertNotEquals(set, FocusMarks(12.0, 80.5, 8.0))
        assertNotEquals(set, FocusMarks(12.0, null, 4.0))
    }

    @Test fun focusMarksCycleDuration() {
        // 0 → 1 → 2 → 4 → 8 → 0, по кругу; метки при этом не трогает
        var marks = FocusMarks(10.0, 20.0, 0.0)
        val seen = mutableListOf<Double>()
        for (round in 0 until 7) {
            marks = marks.cycleDuration()
            seen.add(marks.duration)
        }
        assertEquals(listOf(1.0, 2.0, 4.0, 8.0, 0.0, 1.0, 2.0), seen)
        assertEquals(10.0, marks.a!!, 0.0)
        assertEquals(20.0, marks.b!!, 0.0)
        // метки — значение: у старых время прежнее, новые — отдельные метки с теми же A и B (в Swift то же даёт структура)
        val before = FocusMarks(5.0, null, 1.0)
        val after = before.cycleDuration()
        assertEquals(1.0, before.duration, 0.0)
        assertEquals(2.0, after.duration, 0.0)
        assertEquals(5.0, after.a!!, 0.0)
        assertNull(after.b)
        // с начальных двух секунд
        assertEquals(4.0, FocusMarks().cycleDuration().duration, 0.0)
        // значение вне списка (например, из старого файла) — следующее из списка, а за краем — снова 0
        assertEquals(4.0, FocusMarks(duration = 3.0).cycleDuration().duration, 0.0)
        assertEquals(0.0, FocusMarks(duration = 9.0).cycleDuration().duration, 0.0)
        assertEquals(0.0, FocusMarks(duration = -1.0).cycleDuration().duration, 0.0)
        assertEquals(0.0, FocusMarks(duration = Double.NaN).cycleDuration().duration, 0.0)
    }

    // MARK: - Округление и числа программы для Mac

    @Test fun roundingIsHalfAwayFromZero() {
        // На половинке — от нуля, как `rounded()` в Swift: не к чётному (kotlin.math.round: 2,5 → 2) и не к плюс
        // бесконечности (Math.round: −2,5 → −2)
        val cases = listOf(
            0.5 to 1.0, 1.5 to 2.0, 2.5 to 3.0, 3.5 to 4.0, 6.5 to 7.0, -0.5 to -1.0, -1.5 to -2.0, -2.5 to -3.0,
            -3.5 to -4.0, -6.5 to -7.0, 0.4 to 0.0, -0.4 to 0.0, 0.6 to 1.0, -0.6 to -1.0, 2.4 to 2.0, -2.4 to -2.0,
            1.0 to 1.0, -7.0 to -7.0, 0.0 to 0.0,
            // чуть меньше половинки — вниз (floor(x + 0,5) тут дал бы 1), у границы точности double — как есть
            0.49999999999999994 to 0.0, -0.49999999999999994 to 0.0, 2.4999999999999996 to 2.0,
            4503599627370495.5 to 4503599627370496.0, -4503599627370495.5 to -4503599627370496.0,
            4503599627370497.0 to 4503599627370497.0, 1e308 to 1e308, -1e308 to -1e308,
        )
        for ((x, expected) in cases) assertEquals("round($x)", expected, FocusPull.roundHalfAway(x), 0.0)
        assertTrue(FocusPull.roundHalfAway(Double.NaN).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, FocusPull.roundHalfAway(Double.POSITIVE_INFINITY), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, FocusPull.roundHalfAway(Double.NEGATIVE_INFINITY), 0.0)
        // те же половинки в записи и в команде
        assertEquals(1, FocusPull.register(0.5))
        assertEquals(2, FocusPull.register(1.5))
        assertEquals(3, FocusPull.register(2.5))
        assertEquals(65535, FocusPull.register(65534.5))
        assertEquals(0, FocusPull.register(-0.5))
        assertEquals(1L, FocusPull.commandSteps(0.5))
        assertEquals(-1L, FocusPull.commandSteps(-0.5))
        assertEquals(3L, FocusPull.commandSteps(2.5))
        assertEquals(-3L, FocusPull.commandSteps(-2.5))
        assertEquals(7L, FocusPull.commandSteps(6.5))
        assertEquals(-7L, FocusPull.commandSteps(-6.5))
        // и в самой команде: ошибка 8,75 при k = 3,5 — это 2,5 шага, то есть 3, а не 2
        assertEquals(nearFar(3), decide(8.75))
        assertEquals(nearFar(-3), decide(-8.75))
    }

    @Test fun numbersFromTheMacProgram() {
        // Числа, которые на этих же прогонах дала программа для Mac (FocusPull.swift): здесь они записаны как есть
        fun steps(run: Run) = run.moves.map { it.steps }
        fun times(run: Run) = run.moves.map { it.time }
        fun assertTimes(expected: List<Double>, run: Run) {
            assertEquals(expected.size, run.moves.size)
            for (i in expected.indices) assertEquals("команда $i", expected[i], run.moves[i].time, 1e-9)
        }

        // абсолютный 0 → 65535 за 2 с, тики по 0,1 с, камера стоит на месте: 20 записей по кривой и цель на конце
        val absolute = FocusPull(Mode.absolute, 0.0, 65535.0, 2.0)
        val writes = (0..22).mapNotNull { written(absolute.next(it * FocusPull.TICK, 0.0)) }
        assertEquals(listOf(0, 475, 1835, 3981, 6816, 10240, 14156, 18464, 23068, 27869, 32768, 37666, 42467, 47071, 51379,
            55295, 58719, 61554, 63700, 65060, 65535), writes)

        // шаги, g = 3,5: 10 → 90 за 2 с — шестнадцать малых команд, k не трогают
        val even = runSteps(10.0, 90.0, 2.0, Lens(10.0, 3.5))
        assertEquals(listOf(1L, 1, 1, 2, 1, 1, 2, 2, 1, 2, 2, 1, 1, 1, 1, 1), steps(even))
        assertTimes(listOf(0.2, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0, 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.7, 1.8), even)
        assertEquals(Outcome.reached, even.outcome)
        assertEquals(2.0, even.finishedAt!!, 1e-9)
        assertEquals(89.0, even.lens.position, 0.0)
        assertEquals(3.5, even.pull.gain, 0.0)

        // g = 5 при начальном k = 3,5
        val fiveGain = runSteps(10.0, 90.0, 2.0, Lens(10.0, 5.0))
        assertEquals(listOf(1L, 1, 1, 1, 1, 1, 1, 1, 2, 1, 1, 1, 1, 1, 1), steps(fiveGain))
        assertEquals(Outcome.reached, fiveGain.outcome)
        assertEquals(2.0, fiveGain.finishedAt!!, 1e-9)
        assertEquals(90.0, fiveGain.lens.position, 0.0)

        // g = 4, цель между положениями: одиннадцать шагов вперёд, перескок, один назад — «у метки» на 2,1 с
        val dither = runSteps(10.0, 52.0, 2.0, Lens(10.0, 4.0))
        assertEquals(List(11) { 1L } + (-1L), steps(dither))
        assertEquals(Outcome.reached, dither.outcome)
        assertEquals(2.1, dither.finishedAt!!, 1e-9)
        assertEquals(50.0, dither.lens.position, 0.0)

        // d = 5, 80 → 100: семь шагов вверх, последние три пустые, «упёрлись» на 2,6 с, объектив на 96
        val stuck = runSteps(80.0, 100.0, 2.0, Lens(80.0, 3.5, deadband = 5.0))
        assertEquals(List(7) { 1L }, steps(stuck))
        assertTimes(listOf(0.4, 0.8, 1.0, 1.3, 1.6, 1.9, 2.2), stuck)
        assertEquals(Outcome.stuck, stuck.outcome)
        assertEquals(2.6, stuck.finishedAt!!, 1e-9)
        assertEquals(96.0, stuck.lens.position, 0.0)

        // медленная камера: ответ через 0,18 с, `next` ещё раз сразу после команды; k уточняется по шагам 3
        val slow = runSlow(10.0, 90.0, 2.0, Lens(10.0, 3.5), delay = 0.18, again = true)
        assertEquals(listOf(1L, 1, 3, 3, 3, 3, 3, 2, 2, 1), steps(slow))
        assertTimes(listOf(0.38, 0.58, 0.78, 0.98, 1.18, 1.38, 1.58, 1.78, 1.98, 2.18), slow)
        assertEquals(Outcome.reached, slow.outcome)
        assertEquals(2.2, slow.finishedAt!!, 1e-9)
        assertEquals(91.0, slow.lens.position, 0.0)
        assertEquals(3.638654999999999, slow.pull.gain, 1e-12)

        // g = 0,2: за 1 с до 100 не дойти — тайм-аут на 4,0 с, объектив на 36
        val timeout = runSteps(0.0, 100.0, 1.0, Lens(0.0, 0.2))
        assertEquals(37, timeout.moves.size)
        assertEquals(Outcome.timedOut, timeout.outcome)
        assertEquals(4.0, timeout.finishedAt!!, 1e-9)
        assertEquals(36.0, timeout.lens.position, 0.0)
        assertEquals(0.2, timeout.pull.gain, 0.0)

        // быстрый перевод 5 → 95 за 0,5 с при g = 5: команды 3, 4, 5, 5 уточняют k до 4,63985
        val fast = runSteps(5.0, 95.0, 0.5, Lens(5.0, 5.0))
        assertEquals(listOf(3L, 4, 5, 5, 1), steps(fast))
        assertEquals(Outcome.reached, fast.outcome)
        assertEquals(0.5, fast.finishedAt!!, 1e-9)
        assertEquals(95.0, fast.lens.position, 0.0)
        assertEquals(4.63985, fast.pull.gain, 1e-12)

        // 90 → 10 при g = 3,5: все команды вниз, объектив встаёт на 11
        val down = runSteps(90.0, 10.0, 2.0, Lens(90.0, 3.5))
        assertEquals(listOf(-1L, -1, -1, -2, -1, -1, -2, -2, -1, -2, -2, -1, -1, -1, -1, -1), steps(down))
        assertEquals(Outcome.reached, down.outcome)
        assertEquals(2.0, down.finishedAt!!, 1e-9)
        assertEquals(11.0, down.lens.position, 0.0)
        assertEquals(16, times(down).size)
    }
}
