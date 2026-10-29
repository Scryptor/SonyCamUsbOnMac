package local.zve10viewer.monitor

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.sony.SonyProperty
import java.util.Locale
import kotlin.math.abs

/** Что видно снаружи про перевод фокуса к метке: для кнопок меток и подписи у шкалы. */
data class FocusPullView(
    /** К какой метке («A» или «B») едет фокус; null — не едет. */
    val mark: Char? = null,
    /** Доля пути к ней, 0…1; null — фокус не едет. */
    val progress: Double? = null,
    /** Чем кончился последний перевод — подпись у шкалы на 3 с («Фокус у метки A»); null — подписи нет. */
    val caption: String? = null,
    /** Сколько переводов кончилось у каждой метки: когда число растёт, кнопка метки мигает. */
    val ends: Map<Char, Int> = emptyMap(),
)

/**
 * Ход фокуса с экрана: ручные шаги Near/Far и перевод к метке — одной очередью, с одним «поколением» на сеанс. Команды фокуса
 * идут по одной; перевод и ручной шаг вытесняют друг друга. Без Android и без камеры: камеру изображает [Port], время — [clock]
 * и корутины [scope] (в тестах — виртуальное).
 *
 * **Перевод** ([go]) — по тику 100 мс от начала прошлого: тик, который занял больше (команда с ответом и свежим положением),
 * сменяется следующим сразу, а не через такт. На каждом тике [FocusPull.next] получает положение и время и говорит, что делать:
 *
 * - шаги Near/Far — команда, затем перечитывание свойств не дольше 300 мс. Перечитывание **не отменяется**, когда время вышло: оно
 *   идёт своим путём (и его ответ всё равно обновит свойства), а перевод просто перестаёт его ждать;
 * - абсолютный фокус — запись 0xE042; положение приходит событиями камеры, и перечитывать его самим нужно, только если снимок
 *   свойств старше 250 мс.
 *
 * Перевод ведёт **одна корутина**, она единственная трогает [FocusPull] (он не потокобезопасен). Перевод кончают: ручной шаг
 * ([step]), стоп ([stop]), выход из ручного фокуса, обрыв связи на команде, смена объектива ([cancel]) и уход сеанса ([release]).
 *
 * **Встать.** Абсолютный фокус после отмены сам не остановится: камера едет к последней записанной цели. Поэтому стоп пользователя,
 * ручной шаг и «уже у метки» после перевода пишут в 0xE042 положение, где объектив стоит сейчас. Эта запись уходит раньше любой
 * следующей команды — ручной шаг (любой, и тот, что пришёл, пока цикл ещё доигрывал прежнюю очередь) и новый перевод ждут её перед
 * каждой своей командой, иначе «встать» откатило бы шаг. Пока события идут (снимок моложе 250 мс), положение берётся из снимка как
 * есть: полное чтение перед записью только отнимало бы время, а объектив едет.
 *
 * **Отказы** камеры в этих командах (код ответа не OK, не дошла, свойства не перечитались) идут в журнал — первый раз с причиной, а
 * сколько их было всего за перевод — одной строкой в конце: иначе на настоящей камере нечем объяснить, почему объектив не встал.
 *
 * Все вызовы — с одного потока (главного); [Port] можно вызывать из корутин.
 */
class FocusMover(
    private val scope: CoroutineScope,
    private val port: Port,
    private val journal: (String) -> Unit,
    /** Монотонные часы, наносекунды. */
    private val clock: () -> Long = System::nanoTime,
    /** Вид перевода изменился. */
    private val onChange: (FocusPullView) -> Unit = {},
) {
    /** Камера и свойства, как они нужны ходу фокуса. */
    interface Port {
        /** Фокус можно двигать с экрана сейчас: сеанс готов, Near/Far открыт (ручной фокус). */
        val ready: Boolean

        /** Последний снимок свойств сеанса (не копия контроллера, она на шаг позади). */
        val properties: Map<Int, SonyProperty>

        /** Сколько миллисекунд назад свойства перечитаны. */
        fun snapshotAgeMs(): Long

        /** Перечитать свойства камеры. */
        suspend fun refresh()

        /** Шаг Near/Far (−7…7); отдаёт код ответа камеры. Бросает [TransportException], если обмена не вышло. */
        suspend fun nearFar(steps: Long): Int

        /** Записать цель в 0xE042 (0…65535); отдаёт код ответа камеры. */
        suspend fun write(value: Int): Int
    }

    /** Вид перевода для экрана. */
    var view = FocusPullView()
        private set

    /** Фокус едет к метке. */
    val isPulling: Boolean get() = run != null

    /** Единиц Near/Far, которые ещё не ушли камере: шкалу тянут быстрее, чем она отвечает. */
    var queuedUnits = 0
        private set

    private var driving = false

    /** Растёт, когда сеанс уходит: циклы прошлого сеанса останавливаются и новому не мешают. */
    private var generation = 0
    private var run: Run? = null

    /** Закончится, когда уйдёт всё, что должно быть раньше следующей команды: «встать» и последняя команда отменённого перевода. */
    private var settle: Job? = null
    private val stops = ArrayList<Job>()
    private var captionJob: Job? = null

    /** Один перевод: откуда, куда, за сколько и что было с камерой. */
    private class Run(
        val mark: Char,
        val mode: FocusPull.Mode,
        val start: Double,
        val target: Double,
        val duration: Double,
        val generation: Int,
        /** Что должно закончиться до первого тика: «встать» и последняя команда прошлого перевода. */
        val after: Job?,
    ) {
        var job: Job? = null
        val failures = Failures()
    }

    /** Отказы за один перевод: в журнал — первый каждого вида, остальные считаются. */
    private class Failures {
        private val counts = LinkedHashMap<String, Int>()

        fun first(kind: String): Boolean {
            val count = (counts[kind] ?: 0) + 1
            counts[kind] = count
            return count == 1
        }

        fun summary(): List<String> = counts.filterValues { it > 1 }.map { (kind, count) -> "метки фокуса: $kind — всего $count" }
    }

    // MARK: Ручные шаги

    /**
     * Перефокусировка на столько единиц Near/Far (отрицательное — ближе): по одной команде за раз, остальное копится. Идущий
     * перевод кончается; у абсолютного фокуса перед шагом камере велено встать там, где она стоит.
     */
    fun step(units: Int) {
        if (units == 0 || !port.ready) return
        cancelRun(stopCamera = true)
        queuedUnits += units
        if (driving) return
        driving = true
        val mine = generation
        scope.launch {
            try {
                while (queuedUnits != 0 && generation == mine) {
                    // «встать» и последняя команда отменённого перевода — раньше шага; перед каждой командой, а не только перед
                    // первой: цикл мог ещё доигрывать очередь, когда перевод начался и был отменён
                    settle?.join()
                    if (generation != mine || queuedUnits == 0) break
                    val next = FocusDrive.commands(queuedUnits).firstOrNull() ?: break
                    queuedUnits -= next.toInt()
                    try {
                        port.nearFar(next)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // ответ ручного шага не важен: следующий уйдёт, как только камера освободится
                    }
                }
            } finally {
                // сеанс ушёл, пока шла команда, — флаг уже сброшен, и новый цикл мог начаться: его не трогаем
                if (generation == mine) driving = false
            }
        }
    }

    // MARK: Перевод к метке

    /**
     * Перевести фокус из [start] в [target] (в единицах режима [mode]) за [duration] секунд; [mark] — метка, к которой он едет.
     * Идущий перевод уступает новому, ручные шаги, что ещё не ушли, сбрасываются (как ручной шаг сбрасывает перевод: иначе они
     * довели бы фокус до края, пока перевод пытается вернуться). Фокус уже у цели (в пределах [FocusPosition.tolerance]) — перевод
     * кончается сразу с подписью: ждать для этого всё выбранное время (до 8 с) незачем; если при этом он ехал к другой метке и
     * проезжал эту, абсолютному фокусу велено встать здесь. Когда нельзя (нет сеанса, не ручной фокус, нет положения), решает
     * вызывающий — здесь проверок нет.
     */
    fun go(mark: Char, mode: FocusPull.Mode, start: Double, target: Double, duration: Double) {
        queuedUnits = 0
        val there = abs(target - start) <= FocusPosition.tolerance(mode)
        cancelRun(stopCamera = there)
        captionJob?.cancel()
        captionJob = null
        publish(view.copy(caption = null))
        if (there) {
            journal("метки фокуса: $mark — фокус уже там")
            showCaption(FocusPull.caption(FocusPull.Outcome.reached, mark.toString()), mark)
            return
        }
        journal("метки фокуса: перевод к $mark, ${whole(start)} → ${whole(target)} за ${duration.toInt()} с, " +
            if (mode == FocusPull.Mode.absolute) "абсолютный фокус" else "шаги Near/Far")
        val started = Run(mark, mode, start, target, duration, generation, settle?.takeIf { it.isActive })
        run = started
        publish(view.copy(mark = mark, progress = 0.0))
        started.job = scope.launch {
            try {
                runPull(started)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // неожиданное: перевод кончается, а не роняет программу посреди съёмки
                journal("метки фокуса: перевод прерван ошибкой: ${e.message ?: e.javaClass.simpleName}")
                if (alive(started)) cancelRun(stopCamera = false)
            }
        }
    }

    /**
     * Остановить идущий перевод (стоп пользователя): шаги Near/Far просто прекращаются, а абсолютному фокусу камера дойдёт до
     * последней записанной цели сама, поэтому ей велено встать там, где фокус стоит.
     */
    fun stop() = cancelRun(stopCamera = true)

    /** Прекратить идущий перевод без «встать»: вышли из ручного фокуса, объектив сменился — фокусом занимается камера. */
    fun cancel() = cancelRun(stopCamera = false)

    /**
     * Сеанс уходит (обрыв, другая камера, лента): перевод кончился, очередь шагов пуста, подписи нет; «встать», что ещё не ушло,
     * не уйдёт — ни этой камере, ни новой. Счёт концов остаётся.
     */
    fun release() {
        generation++
        cancelRun(stopCamera = false)
        queuedUnits = 0
        driving = false
        stops.toList().forEach { it.cancel() }
        stops.clear()
        settle = null
        captionJob?.cancel()
        captionJob = null
        publish(view.copy(mark = null, progress = null, caption = null))
    }

    // MARK: Цикл перевода

    private fun alive(run: Run): Boolean = this.run === run && generation == run.generation

    private suspend fun runPull(run: Run) {
        // «встать» и последняя команда прошлого перевода — раньше первого тика этого
        run.after?.join()
        if (!alive(run)) return
        val pull = FocusPull(run.mode, run.start, run.target, run.duration)
        val began = clock()
        var position = run.start
        while (true) {
            val tickStart = clock()
            currentCoroutineContext().ensureActive()
            if (!alive(run)) return
            // вышли из ручного фокуса, пока ждали тик: дальше фокусом занимается камера
            if (!port.ready) {
                cancelRun(stopCamera = false)
                return
            }
            // у абсолютного фокуса положение приходит событиями камеры; если свойств давно нет — спросить самим
            if (run.mode == FocusPull.Mode.absolute && port.snapshotAgeMs() > FRESH_SNAPSHOT_MS) refreshWithin(REFRESH_WAIT_MS, run.failures)
            FocusPosition.current(port.properties, run.mode)?.let { position = it }
            currentCoroutineContext().ensureActive()
            if (!alive(run)) return
            val elapsed = (clock() - began) / 1e9
            val action = pull.next(elapsed, position)
            publish(view.copy(mark = run.mark, progress = pull.progress))
            try {
                when (action) {
                    is FocusPull.Action.Finish -> {
                        end(run, action.outcome, elapsed)
                        return
                    }
                    FocusPull.Action.Wait -> Unit
                    is FocusPull.Action.Write -> {
                        val code = port.write(action.value)
                        currentCoroutineContext().ensureActive()
                        if (code != PtpResponseCode.OK) refused(run, "запись цели 0xE042 = ${action.value}", "отказов записи 0xE042", code)
                    }
                    is FocusPull.Action.NearFar -> {
                        val code = port.nearFar(action.steps)
                        currentCoroutineContext().ensureActive()
                        if (code != PtpResponseCode.OK) refused(run, "Near/Far ${signed(action.steps)}", "отказов Near/Far", code)
                        // свежее положение — не дольше 300 мс; не пришло — автомат сам подождёт и посчитает по сроку
                        refreshWithin(REFRESH_WAIT_MS, run.failures)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                if (e.disconnected) {
                    // связь оборвалась на этой команде; если перевод к этому времени уже заменили другим, чужой не трогаем
                    if (alive(run)) cancelRun(stopCamera = false)
                    return
                }
                failed(run, action, e)
            } catch (e: Exception) {
                failed(run, action, e)
            }
            val remaining = tickStart + TICK_NS - clock()
            if (remaining > 0) delay((remaining + 999_999) / 1_000_000)
        }
    }

    /**
     * Перечитать свойства камеры и подождать не дольше [limitMs]. Запрос не отменяется, если время вышло, и не привязан к переводу:
     * он идёт своим путём, а перевод просто не ждёт ответа.
     */
    private suspend fun refreshWithin(limitMs: Long, failures: Failures?) {
        val done = CompletableDeferred<Unit>()
        scope.launch {
            try {
                port.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (failures == null || failures.first("неудачных перечитываний свойств")) {
                    journal("метки фокуса: не удалось перечитать свойства: ${e.message ?: e.javaClass.simpleName}")
                }
            } finally {
                done.complete(Unit)
            }
        }
        withTimeoutOrNull(limitMs) { done.await() }
    }

    // MARK: Конец и отмена

    /** Перевод кончился сам: подпись на 3 с, кнопка мигает. */
    private fun end(run: Run, outcome: FocusPull.Outcome, seconds: Double) {
        this.run = null
        publish(view.copy(mark = null, progress = null))
        run.failures.summary().forEach(journal)
        val caption = FocusPull.caption(outcome, run.mark.toString()) ?: return
        journal("метки фокуса: перевод к ${run.mark} кончился за " + String.format(Locale.ROOT, "%.1f", seconds) + " с — $caption")
        showCaption(caption, run.mark)
    }

    private fun showCaption(caption: String?, mark: Char) {
        if (caption == null) return
        publish(view.copy(caption = caption, ends = view.ends + (mark to (view.ends[mark] ?: 0) + 1)))
        captionJob?.cancel()
        captionJob = scope.launch {
            delay(CAPTION_MS)
            publish(view.copy(caption = null))
        }
    }

    /**
     * Прекратить идущий перевод — без подписи. [stopCamera]: у абсолютного фокуса камера едет к последней записанной цели и сама
     * не остановится — ей пишется положение, в котором фокус стоит сейчас. Что бы ни ушло, следующая команда (ручной шаг, новый
     * перевод) ждёт этого и последней команды отменённого перевода.
     */
    private fun cancelRun(stopCamera: Boolean) {
        val cancelled = run ?: return
        run = null
        cancelled.job?.cancel()
        publish(view.copy(mark = null, progress = null))
        journal("метки фокуса: перевод остановлен")
        cancelled.failures.summary().forEach(journal)
        val last = if (stopCamera && cancelled.mode == FocusPull.Mode.absolute) launchStop(cancelled) else cancelled.job
        if (last == null) return
        val earlier = settle?.takeIf { it.isActive }
        settle = if (earlier == null) last else scope.launch {
            earlier.join()
            last.join()
        }
    }

    /** Запись «встать»: положение, где объектив стоит, — в цель. Уходит после последней команды отменённого перевода. */
    private fun launchStop(cancelled: Run): Job {
        val job = scope.launch {
            // последняя команда перевода уходит раньше «встать»: иначе «встать» легло бы под неё
            cancelled.job?.join()
            try {
                if (port.snapshotAgeMs() > FRESH_SNAPSHOT_MS) refreshWithin(REFRESH_WAIT_MS, null)
                // сеанс ушёл, пока ждали, — чужой камере «встать» не уйдёт
                if (generation != cancelled.generation) return@launch
                val here = FocusPosition.current(port.properties, FocusPull.Mode.absolute)
                if (here == null) {
                    journal("метки фокуса: встать на месте нечем — положения 0xE043 нет")
                    return@launch
                }
                val value = FocusPull.register(here)
                val code = port.write(value)
                if (code != PtpResponseCode.OK) journal("метки фокуса: камера не приняла «встать» (0xE042 = $value): ответ ${hex(code)}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                journal("метки фокуса: не удалось встать на месте: ${e.message ?: e.javaClass.simpleName}")
            }
        }
        stops += job
        job.invokeOnCompletion { stops -= job }
        return job
    }

    // MARK: Журнал

    private fun refused(run: Run, what: String, kind: String, code: Int) {
        if (run.failures.first(kind)) journal("метки фокуса: камера не приняла $what: ответ ${hex(code)}")
    }

    private fun failed(run: Run, action: FocusPull.Action, error: Exception) {
        val what = when (action) {
            is FocusPull.Action.Write -> "запись цели 0xE042 = ${action.value}"
            is FocusPull.Action.NearFar -> "Near/Far ${signed(action.steps)}"
            else -> "команда"
        }
        if (run.failures.first("команд, что не дошли")) {
            journal("метки фокуса: команда не дошла ($what): ${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun publish(next: FocusPullView) {
        if (next == view) return
        view = next
        onChange(next)
    }

    private fun whole(value: Double): String = FocusPull.roundHalfAway(value).toLong().toString()

    private fun signed(steps: Long): String = if (steps > 0) "+$steps" else steps.toString()

    private fun hex(code: Int): String = "0x%04X".format(code)

    companion object {
        /** Тик перевода, нс: 100 мс от начала прошлого тика. */
        private const val TICK_NS = 100_000_000L

        /** Сколько ждать свежее положение после команды, мс. */
        const val REFRESH_WAIT_MS = 300L

        /** Снимок свойств моложе этого, мс, у абсолютного фокуса считается свежим: положение приходит событиями камеры. */
        const val FRESH_SNAPSHOT_MS = 250L

        /** Сколько держится подпись итога, мс. */
        const val CAPTION_MS = 3000L
    }
}
