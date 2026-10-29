package local.zve10viewer.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Перевод фокуса от метки к метке за заданное время — конечный автомат без ввода-вывода: ни камеры, ни часов, ни потоков.
 * Те же правила и числа, что у программы для Mac (FocusPull.swift); обе ведут его одинаково:
 *
 * - контроллер зовёт [next] на каждом тике ([TICK], 100 мс) — и, если хочет, ещё раз сразу после того, как камера ответила
 *   на команду, — с самым свежим известным положением. Тики пропускать не нужно, даже пока команда без ответа и положение
 *   не обновилось: автомат сам ждёт его (см. ниже). `elapsed` — секунды от начала перевода, `position` — в единицах режима;
 * - исполняет ответ: [Action.Write] — записать 0xE042, [Action.NearFar] — послать шаг Near/Far, [Action.Wait] — ничего до
 *   следующего вызова, [Action.Finish] — перевод кончился (чем — в [Outcome], подпись — [caption]), больше [next] не зовут;
 * - при любом ручном движении фокуса, выходе из ручного фокуса, обрыве связи или касании едущей метки зовёт [cancel].
 *
 * **Абсолютный фокус** (0…65535). Пока время идёт — [Action.Write] точки кривой, повтор прошлого числа не нужен
 * ([Action.Wait]). На конце времени (и при времени 0 — сразу) цель пишется один раз и дальше [Action.Wait], пока положение
 * не станет в пределах 0,5 % шкалы от цели (327,675 единицы) — тогда [Outcome.reached]. Не успело за 2 с после конца —
 * [Outcome.timedOut].
 *
 * **Шаги Near/Far** (0…100, отрицательный шаг уменьшает положение — фокус ближе):
 *
 * - команда считается отработанной, когда положение стало другим, чем было перед ней, или когда с неё прошло 0,3 с (тогда
 *   положение берётся как есть, и если оно прежнее — камера не двинулась). Пока нет ни того ни другого, [next] отвечает
 *   [Action.Wait]: ответ камеры и свежее положение могли просто не дойти. Срок (тайм-аут) и [cancel] при этом работают, а
 *   новая команда уходит только после того, как прошлая отработана;
 * - у отработанной команды n сдвиг Δ = положение − положение до неё. При |n| ≥ 3 и Δ ≠ 0 коэффициент (единиц положения на
 *   шаг) уточняется: k ← 0,7·k + 0,3·(Δ/n), в пределах 0,2…20. Три команды подряд в одну сторону с Δ = 0 —
 *   [Outcome.stuck]: дальше камера не двигает (у края шкалы так бывает);
 * - ошибка e — кривая в этот момент минус положение; допуск τ = max(1, k/2). В допуске — [Action.Wait] до конца времени и
 *   [Outcome.reached] на конце. Вне допуска — команда `round(e / k)` в пределах −7…7, но не 0 (шаг ±1 сдвигает фокус
 *   примерно на k, ближе камера не подойдёт);
 * - после конца времени два шага ±1 подряд в разные стороны, второй из которых перескочил цель, — тоже [Outcome.reached]:
 *   это ближайшее, куда камера может встать;
 * - команды уходят не чаще одной за тик (с запасом 10 % на дрожание таймера): вызов сразу после ответа камеры только
 *   учитывает результат, а следующую команду автомат отдаёт на ближайшем тике;
 * - не кончилось за 3 с после конца времени — [Outcome.timedOut].
 *
 * После любого конца [next] отвечает тем же самым. Метки и положения — числа в единицах режима: автомат шкалу не проверяет,
 * кроме записи в регистр (она обрезается до 0…65535). Любые конечные числа, хоть ±1e308, он переваривает без исключений:
 * кривая считается взвешенной суммой, а не разностью меток, а не-число в регистре и в шаге даёт 0. Округление везде — от
 * нуля на половинке (2,5 → 3, −2,5 → −3), как `rounded()` в Swift, а не к чётному, как kotlin.math.round.
 *
 * Не потокобезопасен: все вызовы — с одного потока или корутины.
 */
class FocusPull(
    val mode: Mode,
    from: Double,
    to: Double,
    duration: Double,
    gain: Double = DEFAULT_GAIN,
) {
    /** Как камера двигает фокус. */
    enum class Mode {
        /** Записывается положение 0xE042 (0…65535), читается 0xE043 — у новых камер. */
        absolute,

        /** Шаги Near/Far 0xD2D1 (−7…7) и положение 0xD24C (0…100) — у камер без абсолютного фокуса, например ZV-E10. */
        steps,
    }

    /** Что делать контроллеру. */
    sealed interface Action {
        /** Записать это в 0xE042 (абсолютный фокус): целое без знака 0…65535, в Int. */
        data class Write(val value: Int) : Action

        /** Послать шаг Near/Far; отрицательный — ближе, положение уменьшает. */
        data class NearFar(val steps: Long) : Action

        /** Ничего до следующего вызова. */
        data object Wait : Action

        /** Перевод кончился: чем — в [Outcome]. */
        data class Finish(val outcome: Outcome) : Action
    }

    /** Чем кончился перевод. */
    enum class Outcome {
        /** Фокус у метки (или ближе, чем камера может встать). */
        reached,

        /** Три команды подряд в одну сторону ничего не сдвинули. */
        stuck,

        /** Не успел: на конце времени фокус ещё далеко, а срок вышел. */
        timedOut,

        /** Остановлен снаружи ([cancel]): подписи нет. */
        cancelled,
    }

    /** Откуда и куда, в единицах режима. Не-число читается как 0. */
    val start: Double = if (from.isFinite()) from else 0.0
    val target: Double = if (to.isFinite()) to else 0.0

    /** Сколько времени на путь, секунд; 0 — как можно быстрее. Отрицательное и не-число читаются как 0. */
    val duration: Double = if (duration.isFinite()) max(duration, 0.0) else 0.0

    /**
     * Единиц положения на шаг Near/Far: начальное значение (в пределах 0,2…20, не-число — 3,5) уточняется по ходу (режим
     * шагов).
     */
    var gain: Double = sane(gain)
        private set

    // состояние

    /** Команда Near/Far, ответа на которую ещё не учли. */
    private class Command(
        val steps: Long,
        /** Положение перед командой и время, когда она ушла. */
        val before: Double,
        val issuedAt: Double,
    )

    private var outcome: Outcome? = null
    private var lastPosition: Double = this.start
    private var lastWritten: Int? = null
    private var pending: Command? = null
    private var lastCommandAt = Double.NEGATIVE_INFINITY

    /** Сколько пустых команд подряд и в какую сторону (−1, 1; 0 — не было). */
    private var zeroRun = 0
    private var zeroDirection = 0

    /** Сторона прошлого шага ±1 (0 — прошлая команда была другой) и признак «два шага ±1 в разные стороны перескочили цель». */
    private var unitDirection = 0
    private var dithered = false

    /** Доля пройденного пути по последнему положению, которое видел автомат: 0…1 (1, если метка там же, где старт). */
    val progress: Double
        get() {
            val span = target - start
            if (span == 0.0) return 1.0
            val share = (lastPosition - start) / span
            return if (share.isNaN()) 0.0 else min(max(share, 0.0), 1.0)
        }

    /**
     * Где должен быть фокус через `elapsed` секунд: от `start` к `target` по кривой, а на конце времени и дальше — `target`.
     * При времени 0 — `target` сразу. Не-число вместо времени — начало.
     */
    fun desired(elapsed: Double): Double {
        if (duration <= 0.0) return target
        val time = if (elapsed.isNaN()) 0.0 else elapsed
        if (time >= duration) return target
        // взвешенная сумма, а не start + (target − start)·s: разность двух далёких меток может не поместиться в Double
        val share = ease(time / duration)
        return start * (1.0 - share) + target * share
    }

    /**
     * Что делать сейчас. Зовётся по тику и после ответа камеры; правила — в описании типа. Положение — не-число: ничего не
     * считаем, ждём (срок при этом идёт).
     */
    fun next(elapsed: Double, position: Double): Action {
        outcome?.let { return Action.Finish(it) }
        val time = if (elapsed.isNaN()) 0.0 else max(elapsed, 0.0)
        if (!position.isFinite()) return if (time >= deadline) end(Outcome.timedOut) else Action.Wait
        lastPosition = position
        return when (mode) {
            Mode.absolute -> nextAbsolute(time, position)
            Mode.steps -> nextSteps(time, position)
        }
    }

    /**
     * Остановить перевод снаружи. Всегда отвечает [Outcome.cancelled]; если перевод уже кончился, его исход не меняется —
     * [next] по-прежнему отдаёт первый.
     */
    fun cancel(): Action {
        if (outcome == null) outcome = Outcome.cancelled
        return Action.Finish(Outcome.cancelled)
    }

    // абсолютный фокус

    private fun nextAbsolute(time: Double, position: Double): Action {
        val near = abs(position - target) <= ABSOLUTE_TOLERANCE
        if (time < duration) {
            // время идёт: точка кривой; то же число камере второй раз не нужно
            val value = register(desired(time))
            if (value == lastWritten) return Action.Wait
            lastWritten = value
            return Action.Write(value)
        }
        if (time >= deadline) return end(if (near) Outcome.reached else Outcome.timedOut)
        // конец времени: цель пишется один раз, потом ждём, пока камера до неё доедет
        val goal = register(target)
        if (lastWritten != goal) {
            lastWritten = goal
            return Action.Write(goal)
        }
        return if (near) end(Outcome.reached) else Action.Wait
    }

    // шаги Near/Far

    private fun nextSteps(time: Double, position: Double): Action {
        val command = pending
        if (command != null) {
            // положение прежнее и с команды не прошло 0,3 с: ответ камеры и свежее положение могли просто не дойти — «камера
            // не двинулась» ещё не засчитываем, ждём (срок при этом идёт)
            if (position == command.before && time - command.issuedAt < SETTLE_WINDOW) {
                return if (time >= deadline) end(Outcome.timedOut) else Action.Wait
            }
            pending = null
            settle(command, position)
        }
        val error = desired(time) - position
        val ended = time >= duration
        if (abs(error) <= max(1.0, gain / 2)) return if (ended) end(Outcome.reached) else Action.Wait
        // вне допуска. Ближе камера не подходит: два шага ±1 в разные стороны перескочили цель
        if (ended && dithered) return end(Outcome.reached)
        // дальше не двигает — только если и теперь надо в ту же сторону
        val direction = if (error > 0) 1 else -1
        if (zeroRun >= STUCK_RUN && direction == zeroDirection) return end(Outcome.stuck)
        if (time >= deadline) return end(Outcome.timedOut)
        // следующая команда — на ближайшем тике, а не сразу после ответа на прошлую
        if (time - lastCommandAt < COMMAND_SPACING) return Action.Wait
        var count = commandSteps(error / gain)
        if (count == 0L) count = direction.toLong()
        pending = Command(count, position, time)
        lastCommandAt = time
        return Action.NearFar(count)
    }

    /** Что вышло из команды: уточняет коэффициент, считает пустые команды и дребезг. */
    private fun settle(command: Command, position: Double) {
        val moved = position - command.before
        val side = if (command.steps > 0) 1 else -1
        if (abs(command.steps) >= 3 && moved != 0.0) {
            gain = sane(0.7 * gain + 0.3 * (moved / command.steps.toDouble()))
        }
        if (moved == 0.0) {
            zeroRun = if (zeroDirection == side) zeroRun + 1 else 1
            zeroDirection = side
        } else {
            zeroRun = 0
            zeroDirection = 0
        }
        if (abs(command.steps) == 1L) {
            val crossed = (command.before - target) * (position - target) < 0
            dithered = unitDirection == -side && crossed
            unitDirection = side
        } else {
            dithered = false
            unitDirection = 0
        }
    }

    // общее

    /** Срок: конец времени и ещё 2 с (абсолютный фокус) или 3 с (шаги). */
    private val deadline: Double
        get() = duration + (if (mode == Mode.absolute) ABSOLUTE_GRACE else STEPS_GRACE)

    private fun end(result: Outcome): Action {
        outcome = result
        return Action.Finish(result)
    }

    companion object {
        /** Время перевода по кругу, секунд. */
        val durations: List<Double> = listOf(0.0, 1.0, 2.0, 4.0, 8.0)

        /** Шаг расписания, секунд. */
        const val TICK = 0.1

        /** Единиц положения на шаг Near/Far в начале перевода (k₀). */
        const val DEFAULT_GAIN = 3.5

        // числа из спецификации

        /** «Готово» у абсолютного фокуса — в пределах 0,5 % шкалы. */
        private const val ABSOLUTE_TOLERANCE = 0.005 * 65535.0

        /** Сколько ждать после конца времени, секунд: абсолютный фокус — 2, шаги — 3. */
        private const val ABSOLUTE_GRACE = 2.0
        private const val STEPS_GRACE = 3.0

        /** Самая большая команда Near/Far. */
        private const val MAX_STEPS = 7.0

        /** Сколько пустых команд подряд в одну сторону — «дальше камера не двигает». */
        private const val STUCK_RUN = 3

        /** Не чаще одной команды за тик, с запасом на дрожание таймера. */
        private const val COMMAND_SPACING = TICK * 0.9

        /**
         * Сколько ждать новое положение после шага, секунд: пока оно прежнее и этого времени не прошло, «камера не
         * двинулась» не засчитывается. Столько же (300 мс) контроллер ждёт свежий 0xD24C.
         */
        private const val SETTLE_WINDOW = 0.3

        /** Коэффициент: пределы. */
        private const val MIN_GAIN = 0.2
        private const val MAX_GAIN = 20.0

        /** Кривая перевода 3t² − 2t³: медленно трогается и медленно подходит. t обрезается в 0…1, не-число — 0. */
        fun ease(t: Double): Double {
            val x = if (t.isNaN()) 0.0 else min(max(t, 0.0), 1.0)
            return x * x * (3 - 2 * x)
        }

        /** Подпись к концу перевода у метки `mark` («A» или «B»); у остановленного снаружи подписи нет. */
        fun caption(outcome: Outcome, mark: String): String? = when (outcome) {
            Outcome.reached -> "Фокус у метки $mark"
            Outcome.stuck -> "Дальше камера не двигает"
            Outcome.timedOut -> "Не успел за 3 с"
            Outcome.cancelled -> null
        }

        /**
         * Число для регистра 0xE042: целое в пределах 0…65535 (Int, без знака); не-число — 0, бесконечность — край. Не
         * `private` ради проверки.
         */
        internal fun register(value: Double): Int {
            if (value.isNaN()) return 0
            return min(max(roundHalfAway(value), 0.0), 65535.0).toInt()
        }

        /** Команда Near/Far из «шагов» (ошибка / k): целое в пределах −7…7; не-число — 0. Не `private` ради проверки. */
        internal fun commandSteps(raw: Double): Long {
            if (raw.isNaN()) return 0L
            return min(max(roundHalfAway(raw), -MAX_STEPS), MAX_STEPS).toLong()
        }

        /**
         * Ближайшее целое, на половинке — от нуля (2,5 → 3, −2,5 → −3), как `rounded()` в Swift. kotlin.math.round на
         * половинке идёт к чётному (2,5 → 2), Math.round — к плюс бесконечности (−2,5 → −2): оба тут не годятся. Не-число и
         * бесконечность возвращает как есть. Не `private` ради проверки.
         */
        internal fun roundHalfAway(x: Double): Double {
            if (x.isNaN() || x.isInfinite()) return x
            val whole = truncate(x)
            // дробная часть double точна (x − whole не округляется), поэтому ровно 0,5 различимо; floor(x + 0,5) тут врёт
            // и у 0,49999999999999994, и у больших нечётных
            return if (abs(x - whole) >= 0.5) whole + sign(x) else whole
        }

        /** Коэффициент в разумных пределах; не-число — начальное значение. */
        private fun sane(gain: Double): Double =
            if (gain.isNaN()) DEFAULT_GAIN else min(max(gain, MIN_GAIN), MAX_GAIN)
    }
}

/**
 * Метки фокуса камеры: A и B — положения в единицах её режима (0…65535 у абсолютного фокуса, 0…100 у шагов), и время
 * перевода к ним. Значение, а не изменяемый объект: [cycleDuration] возвращает новые метки, а у старых время прежнее — так же
 * ведёт себя структура в программе для Mac.
 */
data class FocusMarks(
    /** Положение метки; null — метка пуста. */
    val a: Double? = null,
    val b: Double? = null,
    /** Время перевода, секунд (по умолчанию 2). */
    val duration: Double = 2.0,
) {
    /**
     * Метки со следующим временем по кругу: 0 → 1 → 2 → 4 → 8 → 0. Значение не из списка — ближайшее большее из списка, а
     * за краем (и не-число) — 0. Сами метки не меняются.
     */
    fun cycleDuration(): FocusMarks {
        val all = FocusPull.durations
        return copy(duration = all.firstOrNull { it > duration } ?: all[0])
    }
}
