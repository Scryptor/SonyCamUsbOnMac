package local.zve10viewer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import local.zve10viewer.monitor.FocusMover
import local.zve10viewer.monitor.FocusPull.Mode
import local.zve10viewer.monitor.FocusPullView
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.SessionException
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sign

/**
 * Ход фокуса — ручные шаги и перевод к метке — на модели объектива в виртуальном времени. Модель ведёт себя как поддельная камера
 * (`scripts/fake_camera.py --focus-gain 3.5 --focus-step-ms 80 --focus-deadband 2`, у новой — `--model newer`): шаги Near/Far
 * сдвигают 0xD24C на round(n·g) через 80 мс после команды, а положение камера сообщает, когда её перечитывают; запись 0xE042 двигает
 * 0xE043 к цели со скоростью 40 000 единиц в секунду, и положение приходит событиями раз в 50 мс. Обмены с камерой идут по одному
 * (как под замком транспорта) и, начавшись, заканчиваются, даже если вызвавшего отменили.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusMoverTest {
    /** Что ушло камере: когда (мс виртуального времени), что и с каким числом. */
    private data class Wire(val at: Long, val kind: String, val value: Long)

    private class Lens(
        private val test: TestScope,
        val mode: Mode,
        start: Double,
        private val gain: Double = 3.5,
        private val deadband: Double = 0.0,
        private val speed: Double = 40_000.0,
        var commandMs: Long = 80,
        var refreshMs: Long = 25,
    ) : FocusMover.Port {
        val now: Long get() = test.testScheduler.currentTime

        /** Где объектив на самом деле; программе о нём говорит только `shown`. */
        var position = start
            private set
        var shown = start
            private set
        private var shownAt = 0L
        private var target: Double? = null

        override var ready = true
        val wire = mutableListOf<Wire>()
        val reads = mutableListOf<Long>()
        var refreshesStarted = 0
        var refreshesDone = 0
        var refreshesCancelled = 0

        /** Камера не шлёт событий: положение обновляется только перечитыванием. */
        var silent = false

        /** Код ответа на команды; не 0x2001 — камера отказала и ничего не сделала. */
        var replyCode = 0x2001

        /** Скриптованный отказ: по виду обмена («nearFar», «write», «refresh») — что бросить; null — работает. */
        var failure: (String) -> Throwable? = { null }

        /** Перечитывание держится столько, потом отвечает (по умолчанию — `refreshMs`). */
        var refreshHangsMs: Long? = null

        /** Камера перестала отдавать положение фокуса: свойства его не содержат. */
        var hidesPosition = false

        /** Чтение свойств падает неожиданной ошибкой. */
        var breaksReading = false

        private val bus = Mutex()

        init {
            if (mode == Mode.absolute) {
                test.backgroundScope.launch {
                    while (true) {
                        delay(50)
                        drive()
                    }
                }
            }
        }

        private fun drive() {
            val goal = target
            if (goal != null) {
                val step = speed * 0.05
                position = if (abs(goal - position) <= step) goal else position + sign(goal - position) * step
            }
            if (!silent) report()
        }

        /** Камера сообщила, где объектив. */
        fun report() {
            shown = position
            shownAt = now
        }

        /** События и перечитывание перестали доходить: снимок свойств стареет. */
        fun goStale() {
            silent = true
            failure = { kind -> if (kind == "refresh") TransportException("нет ответа на 0x9209") else null }
        }

        override val properties: Map<Int, SonyProperty>
            get() {
                reads += now
                if (breaksReading) throw IllegalStateException("свойства разобраны неверно")
                if (hidesPosition) return emptyMap()
                val code = if (mode == Mode.absolute) SonyProp.FOCUS_POSITION_CURRENT else SonyProp.FOCAL_POSITION
                val type = if (mode == Mode.absolute) PtpType.UINT16 else PtpType.UINT8
                return mapOf(code to SonyProperty(code, type, 0, 1, PtpValue.Num(floor(shown + 0.5).toLong()), null, emptyList(), emptyList()))
            }

        override fun snapshotAgeMs(): Long = now - shownAt

        override suspend fun refresh() {
            refreshesStarted++
            try {
                failure("refresh")?.let { throw it }
                delay(refreshHangsMs ?: refreshMs)
                report()
                refreshesDone++
            } catch (e: CancellationException) {
                refreshesCancelled++
                throw e
            }
        }

        override suspend fun nearFar(steps: Long): Int = exchange("nearFar", steps) {
            val moved = if (mode == Mode.absolute) {
                // шаг Near/Far снимает цель 0xE042, до которой фокус ещё ехал, и сдвигает 0xE043 на 655 (1 % шкалы)
                target = null
                steps * 655.0
            } else {
                val toEdge = if (steps > 0) 100.0 - position else position
                if (steps != 0L && abs(steps) <= 3 && toEdge < deadband) 0.0 else sign(steps.toDouble()) * floor(abs(steps * gain) + 0.5)
            }
            position = (position + moved).coerceIn(0.0, if (mode == Mode.absolute) 65535.0 else 100.0)
        }

        override suspend fun write(value: Int): Int = exchange("write", value.toLong()) { target = value.toDouble() }

        private suspend fun exchange(kind: String, value: Long, effect: () -> Unit): Int = withContext(NonCancellable) {
            bus.withLock {
                wire += Wire(now, kind, value)
                failure(kind)?.let { throw it }
                delay(commandMs)
                if (replyCode == 0x2001) effect()
                replyCode
            }
        }

        fun wireOf(kind: String) = wire.filter { it.kind == kind }
    }

    /** Ход фокуса с моделью объектива, журналом и лентой видов для экрана. */
    private class Rig(val test: TestScope, val lens: Lens) {
        val journal = mutableListOf<String>()
        val views = mutableListOf<Pair<Long, FocusPullView>>()
        val mover = FocusMover(test.backgroundScope, lens, journal = { journal += it },
            clock = { test.testScheduler.currentTime * 1_000_000L }, onChange = { views += test.testScheduler.currentTime to it })

        val view: FocusPullView get() = mover.view
        val now: Long get() = test.testScheduler.currentTime

        /** Когда подпись итога появилась. */
        val captionAt: Long? get() = views.lastOrNull { it.second.caption != null }?.first

        /** Все подписи итога, что были на экране, по порядку. */
        val captions: List<String> get() = views.mapNotNull { it.second.caption }.distinct()

        fun elapse(ms: Long) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }
    }

    private fun TestScope.stepsRig(start: Double, deadband: Double = 2.0, commandMs: Long = 80, refreshMs: Long = 25, gain: Double = 3.5) =
        Rig(this, Lens(this, Mode.steps, start, gain = gain, deadband = deadband, commandMs = commandMs, refreshMs = refreshMs))

    private fun TestScope.absoluteRig(start: Double, commandMs: Long = 30, speed: Double = 40_000.0) =
        Rig(this, Lens(this, Mode.absolute, start, speed = speed, commandMs = commandMs))

    // MARK: - Перевод от начала до конца

    @Test fun aFourSecondPullOnTheMacLensTakesAboutFourSecondsAndEndsAtTheMark() = runTest {
        val rig = stepsRig(21.0)
        rig.mover.go('A', Mode.steps, 21.0, 100.0, 4.0)
        rig.elapse(6_000)
        assertEquals("Фокус у метки A", rig.view.caption)
        val ended = rig.captionAt!!
        assertTrue("кончился за $ended мс", ended in 3_500..5_000)
        assertTrue("встал на ${rig.lens.position}", abs(rig.lens.position - 100.0) <= 1.0)
        // команды не чаще тика: между ними не меньше 90 мс, и каждая не больше 7
        val commands = rig.lens.wireOf("nearFar")
        assertTrue(commands.size > 5)
        assertTrue(commands.zipWithNext().all { (a, b) -> b.at - a.at >= 90 })
        assertTrue(commands.all { abs(it.value) in 1..7 })
    }

    @Test fun theCaptionStaysThreeSecondsAndThenGoes() = runTest {
        val rig = stepsRig(50.0)
        rig.mover.go('B', Mode.steps, 50.0, 60.0, 1.0)
        rig.elapse(3_000)
        val appeared = rig.captionAt!!
        assertEquals("Фокус у метки B", rig.view.caption)
        assertEquals(1, rig.view.ends['B'])
        rig.elapse(appeared + 2_900 - rig.now)
        assertNotNull(rig.view.caption)
        rig.elapse(200)
        assertNull(rig.view.caption)
        // счёт концов остался: по нему кнопка мигает один раз на конец
        assertEquals(1, rig.view.ends['B'])
    }

    @Test fun theMarkAndTheShareOfThePathAreShownWhileItMovesAndGoWhenItEnds() = runTest {
        val rig = stepsRig(20.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        assertEquals('A', rig.view.mark)
        assertEquals(0.0, rig.view.progress!!, 0.0)
        assertNull(rig.view.caption)
        val shares = ArrayList<Double>()
        repeat(7) {
            rig.elapse(500)
            rig.view.progress?.let { shares += it }
        }
        assertTrue("доля пути растёт: $shares", shares.zipWithNext().all { (a, b) -> b >= a } && shares.last() > 0.5)
        rig.elapse(3_000)
        assertNull(rig.view.mark)
        assertNull(rig.view.progress)
    }

    @Test fun anAbsolutePullOfTwoSecondsEndsInTwoToThreeSecondsWithinHalfAPercent() = runTest {
        val rig = absoluteRig(13790.0)
        rig.mover.go('A', Mode.absolute, 13790.0, 58330.0, 2.0)
        rig.elapse(4_000)
        assertEquals("Фокус у метки A", rig.view.caption)
        val ended = rig.captionAt!!
        assertTrue("кончился за $ended мс", ended in 1_800..3_000)
        assertTrue(abs(rig.lens.position - 58330.0) <= 0.005 * 65535)
        // последняя запись — сама цель
        assertEquals(58330L, rig.lens.wireOf("write").last().value)
    }

    @Test fun timeZeroIsAsFastAsTheLensCanGo() = runTest {
        val rig = absoluteRig(13790.0)
        rig.mover.go('A', Mode.absolute, 13790.0, 58330.0, 0.0)
        rig.elapse(3_000)
        assertEquals("Фокус у метки A", rig.view.caption)
        // 44 540 единиц при 40 000 в секунду — чуть больше секунды, а не две
        assertTrue("кончился за ${rig.captionAt} мс", rig.captionAt!! in 1_100..1_600)
        assertEquals(58330L, rig.lens.wireOf("write").first().value)
    }

    // MARK: - Тик

    @Test fun ticksComeEveryHundredMillisecondsStartToStart() = runTest {
        val rig = absoluteRig(10_000.0, commandMs = 0, speed = 1_000_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 50_000.0, 2.0)
        rig.elapse(2_500)
        // точка кривой на каждом тике, от 0 до 2000 мс; затем цель записана, и на следующем тике перевод кончился
        assertEquals((0L..2000L step 100L).toList(), rig.lens.wireOf("write").map { it.at })
        assertEquals(2_100L, rig.captionAt)
    }

    @Test fun aTickThatTookLongerIsFollowedByTheNextOneAtOnceNotAWholeTickLater() = runTest {
        // команда с ответом камеры (80 мс) и свежим положением (25 мс) занимает 105 мс
        val rig = stepsRig(20.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(3_000)
        val gaps = rig.lens.reads.zipWithNext { a, b -> b - a }
        assertTrue("тик в тик по 100 мс или сразу после долгого — по 105: $gaps", gaps.all { it == 100L || it == 105L })
        // за командами идут тики по 105; на сетке 100 мс следующий тик после команды был бы через 200
        assertTrue("за командами идут тики по 105: $gaps", gaps.count { it == 105L } >= 5)
    }

    @Test fun theFreshPositionIsWaitedForAtMostThreeHundredMillisecondsAndTheExchangeIsNotCancelled() = runTest {
        val rig = stepsRig(20.0)
        rig.lens.refreshHangsMs = 1_000
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(3_000)
        val firstCommand = rig.lens.wireOf("nearFar").first().at
        // после команды: ответ 80 мс, затем не больше 300 мс ожидания положения — и следующий тик сразу
        assertEquals(firstCommand + 80 + 300, rig.lens.reads.first { it > firstCommand })
        // перечитывание, за которым не дождались, никто не отменял: оно идёт до конца
        assertTrue(rig.lens.refreshesStarted >= 1)
        assertEquals(0, rig.lens.refreshesCancelled)
        rig.elapse(8_000)
        assertEquals(rig.lens.refreshesStarted, rig.lens.refreshesDone)
        assertEquals(0, rig.lens.refreshesCancelled)
    }

    @Test fun stoppingWhileWaitingForThePositionDoesNotCancelTheExchange() = runTest {
        val rig = stepsRig(20.0)
        rig.lens.refreshHangsMs = 1_000
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        while (rig.lens.wireOf("nearFar").isEmpty()) rig.elapse(10)
        rig.elapse(150)
        assertEquals(1, rig.lens.refreshesStarted)
        rig.mover.stop()
        rig.elapse(5_000)
        assertEquals(1, rig.lens.refreshesDone)
        assertEquals(0, rig.lens.refreshesCancelled)
        assertNull(rig.view.mark)
    }

    @Test fun absoluteFocusDoesNotAskForTheStateWhileTheCameraKeepsSendingIt() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 50_000.0, 2.0)
        rig.elapse(3_000)
        assertEquals(0, rig.lens.refreshesStarted)
    }

    @Test fun absoluteFocusAsksForTheStateByTheAgeOfTheSnapshotNotOnEveryTick() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.lens.silent = true
        rig.mover.go('A', Mode.absolute, 10_000.0, 50_000.0, 2.0)
        rig.elapse(1_000)
        // событий нет: на первых тиках снимок моложе 250 мс, потом он перечитывается — раз в 300 мс, а не на каждом тике
        val started = rig.lens.refreshesStarted
        assertTrue("перечитываний $started", started in 2..4)
        assertEquals(started, rig.lens.refreshesDone)
    }

    // MARK: - «Встать»: стоп пользователя и ручной шаг в абсолютном фокусе

    @Test fun stoppingAnAbsolutePullWritesWhereTheLensStandsAndTheLensStops() = runTest {
        // время 0: цель записана сразу, и без «встать» объектив доехал бы до неё
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 0.0)
        rig.elapse(510)
        val stoppedAt = rig.now
        val standing = rig.lens.shown
        assertTrue("объектив в пути: $standing", standing > 20_000.0 && standing < 40_000.0)
        rig.mover.stop()
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        rig.elapse(2_000)
        // одна запись — положение, где объектив стоит по последним свойствам
        val after = rig.lens.wireOf("write").filter { it.at >= stoppedAt }
        assertEquals(1, after.size)
        assertEquals(standing.toLong(), after.single().value)
        val parked = rig.lens.position
        rig.elapse(2_000)
        assertEquals("объектив больше не едет", parked, rig.lens.position, 0.0)
        // он встал не дальше, чем уехал бы за один шаг модели от места, где был при стопе
        assertTrue("встал на $parked, был на $standing", abs(parked - standing) <= 2_000.0)
    }

    @Test fun withoutTheStopWriteTheLensWouldDriveOnToTheOldTarget() = runTest {
        // проверка самой модели: если камеру не остановить, она доедет до последней записанной цели, — и тест выше что-то значит
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 0.0)
        rig.elapse(510)
        rig.mover.cancel()
        rig.elapse(3_000)
        assertEquals(60_000.0, rig.lens.position, 0.0)
    }

    @Test fun stoppingAStepsPullSendsNothingMore() = runTest {
        val rig = stepsRig(20.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(1_510)
        rig.mover.stop()
        val count = rig.lens.wire.size
        rig.elapse(5_000)
        assertEquals(count, rig.lens.wire.size)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        assertTrue(rig.lens.wireOf("write").isEmpty())
    }

    @Test fun stoppingWhenNothingMovesDoesNothing() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.stop()
        rig.elapse(1_000)
        assertTrue(rig.lens.wire.isEmpty())
        assertTrue(rig.journal.isEmpty())
    }

    @Test fun aManualStepAfterAnAbsolutePullComesAfterTheStopWrite() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        val stepAt = rig.now
        rig.mover.step(3)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        rig.elapse(1_000)
        // сначала запись «встать» (после последней записи кривой, что была в пути), затем шаг
        assertEquals(listOf("write", "nearFar"), rig.lens.wire.filter { it.at >= stepAt }.map { it.kind })
        val stop = rig.lens.wireOf("write").last()
        val step = rig.lens.wireOf("nearFar").last()
        assertEquals(3L, step.value)
        assertTrue("шаг в ${step.at} раньше конца «встать» (${stop.at} + 30)", stop.at + 30 <= step.at)
        // шаг — от места, где объектив встал, а не от старой цели: к цели он не вернулся
        rig.elapse(3_000)
        assertTrue("объектив ${rig.lens.position}", rig.lens.position < 30_000.0)
    }

    @Test fun aManualStepThatArrivesWhileTheStepLoopIsStillDrainingAlsoWaitsForTheStopWrite() = runTest {
        // цикл ручных шагов ещё занят командой, а перевод уже начался и отменён: шаг, пришедший сейчас, не должен уйти раньше
        // «встать», иначе «встать» откатит шаг
        val rig = absoluteRig(10_000.0, commandMs = 80)
        rig.mover.step(21)
        rig.elapse(10)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(10)
        rig.mover.step(3)
        rig.elapse(2_000)
        val steps = rig.lens.wireOf("nearFar")
        val lastStep = steps.last()
        assertEquals(3L, lastStep.value)
        val lastWrite = rig.lens.wireOf("write").last()
        assertTrue("«встать» (${lastWrite.at}) раньше шага (${lastStep.at})", lastWrite.at < lastStep.at)
        // очередь ручных шагов перевод сбросил: ушла только команда, что была в пути, и этот шаг
        assertEquals(listOf(7L, 3L), steps.map { it.value })
    }

    @Test fun theStopWriteTakesTheSnapshotAsItIsWhileItIsFresh() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        rig.mover.stop()
        rig.elapse(500)
        // события идут: полное перечитывание перед записью только откатило бы объектив — он за это время едет дальше
        assertEquals(0, rig.lens.refreshesStarted)
    }

    @Test fun theStopWriteReadsTheStateAgainWhenTheSnapshotIsStale() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(500)
        rig.lens.goStale()
        rig.elapse(510)
        // камера снова отвечает, а снимок старый (последнее удачное чтение — давно)
        rig.lens.failure = { null }
        val before = rig.lens.refreshesStarted
        rig.mover.stop()
        rig.elapse(1_000)
        assertEquals(before + 1, rig.lens.refreshesStarted)
        // записано положение после перечитывания, а не старое
        assertEquals(rig.lens.shown.toLong(), rig.lens.wireOf("write").last().value)
    }

    @Test fun aStopWriteTheCameraRefusesOrDoesNotGetIsInTheJournal() = runTest {
        val refusing = absoluteRig(10_000.0)
        refusing.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        refusing.elapse(1_010)
        refusing.lens.replyCode = 0x2019
        refusing.mover.stop()
        refusing.elapse(1_000)
        assertTrue(refusing.journal.toString(), refusing.journal.any { it.contains("0x2019") && it.contains("встать") })

        val failing = absoluteRig(10_000.0)
        failing.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        failing.elapse(1_010)
        failing.lens.failure = { kind -> if (kind == "write") TransportException("тайм-аут обмена") else null }
        failing.mover.stop()
        failing.elapse(1_000)
        assertTrue(failing.journal.toString(), failing.journal.any { it.contains("встать") && it.contains("тайм-аут обмена") })
    }

    @Test fun aStopWriteStillWaitingWhenTheSessionLeavesIsNotSentToTheNextOne() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(500)
        rig.lens.goStale()
        rig.elapse(510)
        // «встать» ждёт перечитывания (оно отвечает через секунду, ждать — не больше 300 мс); сеанс уходит, пока ждёт
        rig.lens.failure = { null }
        rig.lens.refreshHangsMs = 1_000
        rig.mover.stop()
        rig.elapse(100)
        rig.mover.release()
        val writes = rig.lens.wireOf("write").size
        rig.elapse(3_000)
        assertEquals(writes, rig.lens.wireOf("write").size)
    }

    // MARK: - Метка, у которой фокус уже стоит

    @Test fun aMarkTheFocusAlreadyStandsAtEndsAtOnceWithTheCaptionAndWithoutCommands() = runTest {
        val onSteps = stepsRig(51.0)
        onSteps.mover.go('A', Mode.steps, 51.0, 52.5, 8.0)
        assertEquals("Фокус у метки A", onSteps.view.caption)
        assertNull(onSteps.view.mark)
        assertEquals(1, onSteps.view.ends['A'])
        onSteps.elapse(1_000)
        assertTrue(onSteps.lens.wire.isEmpty())

        val inAbsolute = absoluteRig(30_000.0)
        inAbsolute.mover.go('B', Mode.absolute, 30_000.0, 30_300.0, 8.0)
        assertEquals("Фокус у метки B", inAbsolute.view.caption)
        inAbsolute.elapse(1_000)
        assertTrue(inAbsolute.lens.wire.isEmpty())
    }

    @Test fun justOutsideTheToleranceItGoes() = runTest {
        val onSteps = stepsRig(51.0)
        onSteps.mover.go('A', Mode.steps, 51.0, 52.76, 2.0)
        assertEquals('A', onSteps.view.mark)
        assertNull(onSteps.view.caption)

        val inAbsolute = absoluteRig(30_000.0)
        inAbsolute.mover.go('A', Mode.absolute, 30_000.0, 30_000.0 + 327.7, 2.0)
        assertEquals('A', inAbsolute.view.mark)
    }

    @Test fun anAbsolutePullPassingTheMarkIsToldToStandThereWhenYouGoToIt() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('B', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        // фокус едет к B и сейчас у A: перевод к A кончается сразу, а камере велено встать здесь — не доезжать до B
        val here = rig.lens.shown
        rig.mover.go('A', Mode.absolute, here, here + 100.0, 2.0)
        assertEquals("Фокус у метки A", rig.view.caption)
        assertNull(rig.view.mark)
        rig.elapse(3_000)
        assertEquals(here.toLong(), rig.lens.wireOf("write").last().value)
        assertTrue("объектив ${rig.lens.position}", rig.lens.position <= here + 2_000.0)
    }

    @Test fun aPullDropsTheManualStepsStillQueued() = runTest {
        // шаг Near/Far сдвигает на единицу: 60 единиц — это девять команд, а метка рядом
        val rig = stepsRig(20.0, deadband = 0.0, gain = 1.0)
        rig.mover.step(60)
        rig.elapse(170)
        // три семёрки ушли (третья ещё в пути), остальные 39 единиц ждут
        assertEquals(listOf(0L, 80L, 160L), rig.lens.wireOf("nearFar").map { it.at })
        assertEquals(39, rig.mover.queuedUnits)
        rig.lens.report()
        rig.mover.go('A', Mode.steps, rig.lens.shown, 50.0, 2.0)
        assertEquals(0, rig.mover.queuedUnits)
        rig.elapse(8_000)
        assertEquals("ушли только три семёрки, что были до перевода", 3, rig.lens.wireOf("nearFar").count { it.value == 7L })
        // очередь ручных шагов довела бы фокус до 80; перевод идёт к метке 50
        assertTrue("объектив ${rig.lens.position}", rig.lens.position in 40.0..60.0)
        assertFalse(rig.mover.isPulling)
        assertTrue(rig.captions.toString(), rig.captions.isNotEmpty())
    }

    @Test fun goingToAnotherMarkReplacesTheRunningPullWithOneLoop() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.go('A', Mode.steps, 50.0, 90.0, 4.0)
        rig.elapse(1_000)
        val replacedAt = rig.now
        rig.lens.report()
        rig.mover.go('B', Mode.steps, rig.lens.shown, 10.0, 2.0)
        assertEquals('B', rig.view.mark)
        rig.elapse(8_000)
        assertEquals(listOf("Фокус у метки B"), rig.captions)
        assertTrue("объектив ${rig.lens.position}", abs(rig.lens.position - 10.0) <= 2.0)
        // одна петля: команды не чаще тика, и после замены все идут к B (в сторону убывания)
        val after = rig.lens.wireOf("nearFar").filter { it.at >= replacedAt }
        assertTrue(after.zipWithNext().all { (a, b) -> b.at - a.at >= 90 })
        assertTrue("после замены: $after", after.drop(1).all { it.value < 0 })
        assertEquals(1, rig.view.ends['B'])
        assertNull(rig.view.ends['A'])
    }

    @Test fun aNewPullWaitsForTheStopWriteWhichWaitsForTheLastCommandOfThePullBeforeIt() = runTest {
        // последняя запись отменённого перевода ещё в пути (обмен 500 мс), а новый перевод уже начался: «встать» ждёт этой записи,
        // а запись нового перевода — «встать»; иначе цель нового перевода ушла бы раньше «встать», и «встать» легло бы поверх неё
        val rig = absoluteRig(10_000.0, commandMs = 500)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 0.0)
        rig.elapse(100)
        rig.mover.stop()
        rig.mover.go('B', Mode.absolute, 10_000.0, 5_000.0, 0.0)
        rig.elapse(5_000)
        // цель первого перевода, «встать» (объектив только тронулся с 10000), цель второго
        val writes = rig.lens.wireOf("write").map { it.value }
        assertEquals(writes.toString(), 3, writes.size)
        assertEquals(60_000L, writes[0])
        assertTrue(writes.toString(), writes[1] in 10_000L..14_000L)
        assertEquals(5_000L, writes[2])
    }

    @Test fun anUnexpectedErrorInThePullEndsItQuietlyInsteadOfBringingTheProgramDown() = runTest {
        val rig = stepsRig(20.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(500)
        rig.lens.breaksReading = true
        rig.elapse(500)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        assertFalse(rig.mover.isPulling)
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("прерван ошибкой") && it.contains("свойства разобраны неверно") })
        // и после ошибки ход фокуса работает: ручной шаг уходит, новый перевод начинается
        rig.lens.breaksReading = false
        rig.mover.step(3)
        rig.elapse(500)
        assertEquals(3L, rig.lens.wireOf("nearFar").last().value)
        rig.mover.go('B', Mode.steps, 20.0, 60.0, 2.0)
        assertEquals('B', rig.view.mark)
    }

    @Test fun aStepAfterTheQueueRanDryStartsTheLoopAgain() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.step(7)
        rig.elapse(500)
        rig.mover.step(3)
        rig.elapse(500)
        assertEquals(listOf(7L, 3L), rig.lens.wireOf("nearFar").map { it.value })
    }

    @Test fun aStopWithoutAPositionToStandAtSaysSoInTheJournalAndWritesNothing() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        rig.lens.hidesPosition = true
        val writes = rig.lens.wireOf("write").size
        rig.mover.stop()
        rig.elapse(1_000)
        assertEquals(writes, rig.lens.wireOf("write").size)
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("встать") && it.contains("0xE043") })
    }

    @Test fun refusalsCountedBeforeAStopAreTotalledInTheJournal() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.lens.replyCode = 0x2019
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 8.0)
        while (rig.lens.wireOf("nearFar").size < 2) rig.elapse(10)
        rig.elapse(120)
        rig.mover.stop()
        assertEquals(1, rig.journal.count { it.contains("0x2019") })
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("всего") })
    }

    @Test fun goingToTheSameMarkAgainRestartsWithOneLoopOnly() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(1_000)
        val restartedAt = rig.now
        rig.lens.report()
        rig.mover.go('A', Mode.steps, rig.lens.shown, 100.0, 4.0)
        assertEquals('A', rig.view.mark)
        assertEquals(0.0, rig.view.progress!!, 0.0)
        rig.elapse(3_000)
        // две петли отдавали бы по команде на каждом тике; одна — не чаще раза в тик
        val after = rig.lens.wireOf("nearFar").filter { it.at > restartedAt }
        assertTrue(after.size > 3)
        assertTrue(after.zipWithNext().all { (a, b) -> b.at - a.at >= 90 })
    }

    @Test fun aNewPullWaitsForTheStopWriteBeforeItsFirstCommand() = runTest {
        // стоп и сразу новый перевод: запись «встать» не должна лечь поверх первой записи нового перевода
        val rig = absoluteRig(10_000.0, commandMs = 80)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 0.0)
        rig.elapse(510)
        val standing = rig.lens.shown
        rig.mover.stop()
        rig.mover.go('B', Mode.absolute, standing, 5_000.0, 0.0)
        rig.elapse(3_000)
        val writes = rig.lens.wireOf("write").map { it.value }
        // 60000 (первый перевод), встать, 5000 (второй): «встать» — раньше цели второго перевода
        assertEquals(listOf(60_000L, standing.toLong(), 5_000L), writes)
        assertEquals("объектив доехал до B, а не остановился на месте стопа", 5_000.0, rig.lens.position, 0.0)
    }

    @Test fun startingAPullClearsTheCaptionOfThePreviousOneAndItsTimerDoesNotClearTheNewOne() = runTest {
        val rig = stepsRig(50.0)
        rig.mover.go('A', Mode.steps, 50.0, 50.5, 2.0)
        assertNotNull(rig.view.caption)
        rig.mover.go('B', Mode.steps, 50.0, 80.0, 2.0)
        assertNull(rig.view.caption)
        rig.mover.cancel()
        rig.elapse(2_000)
        rig.mover.go('B', Mode.steps, 50.0, 50.0, 2.0)
        assertEquals("Фокус у метки B", rig.view.caption)
        // подпись первого перевода должна была погаснуть в 3000 мс: подпись второго она не трогает
        rig.elapse(2_000)
        assertEquals("Фокус у метки B", rig.view.caption)
        rig.elapse(1_100)
        assertNull(rig.view.caption)
    }

    // MARK: - Что отменяет перевод

    @Test fun leavingManualFocusEndsThePullAtTheNextTickWithoutCaptionOrStop() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        rig.lens.ready = false
        val before = rig.lens.wire.size
        rig.elapse(1_000)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        // «встать» не пишется: фокусом занимается камера; перевод не пишет тоже
        assertEquals(before, rig.lens.wire.size)
        rig.elapse(3_000)
        assertEquals(before, rig.lens.wire.size)
    }

    @Test fun cancelEndsThePullAtOnceAndNeverWritesAStopEvenInAbsoluteFocus() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.mover.go('A', Mode.absolute, 10_000.0, 60_000.0, 4.0)
        rig.elapse(1_010)
        rig.mover.cancel()
        assertNull(rig.view.mark)
        assertNull(rig.view.progress)
        assertNull(rig.view.caption)
        val count = rig.lens.wire.size
        rig.elapse(3_000)
        assertEquals(count, rig.lens.wire.size)
    }

    @Test fun aLostLinkOnACommandEndsThePullWithoutCaptionAndWithoutFurtherCommands() = runTest {
        val rig = stepsRig(20.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(1_000)
        rig.lens.failure = { kind -> if (kind == "nearFar") TransportException("связь оборвалась", disconnected = true) else null }
        rig.elapse(1_000)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        val count = rig.lens.wire.size
        rig.elapse(5_000)
        assertEquals(count, rig.lens.wire.size)
        assertTrue(rig.journal.toString(), rig.journal.size < 6)
    }

    @Test fun releaseEndsThePullAndEmptiesTheQueue() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.go('A', Mode.steps, 50.0, 90.0, 4.0)
        rig.elapse(1_010)
        rig.mover.release()
        assertNull(rig.view.mark)
        assertNull(rig.view.progress)
        val count = rig.lens.wire.size
        rig.elapse(5_000)
        assertEquals(count, rig.lens.wire.size)

        val queued = stepsRig(50.0, deadband = 0.0)
        queued.mover.step(60)
        queued.elapse(100)
        queued.mover.release()
        assertEquals(0, queued.mover.queuedUnits)
        val sent = queued.lens.wire.size
        queued.elapse(2_000)
        assertTrue("после ухода сеанса ушло ${queued.lens.wire.size - sent} команд", queued.lens.wire.size - sent <= 0)
    }

    @Test fun releaseClearsTheCaptionButKeepsTheCountOfEnds() = runTest {
        val rig = stepsRig(50.0)
        rig.mover.go('A', Mode.steps, 50.0, 50.2, 2.0)
        assertNotNull(rig.view.caption)
        rig.mover.release()
        assertNull(rig.view.caption)
        // счёт концов остаётся: кнопка уже мигала
        assertEquals(1, rig.view.ends['A'])
        rig.elapse(5_000)
        assertNull(rig.view.caption)
    }

    @Test fun afterReleaseTheNextStepsWorkAndTheOldLoopLeavesNothingBehind() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.step(30)
        rig.elapse(100)
        rig.mover.release()
        rig.elapse(500)
        val old = rig.lens.wire.size
        rig.mover.step(3)
        rig.elapse(500)
        assertEquals(listOf(3L), rig.lens.wire.drop(old).map { it.value })
    }

    @Test fun aManualStepEndsAStepsPullWithoutCaptionAndOnlyTheStepGoesOut() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 4.0)
        rig.elapse(1_510)
        val sent = rig.lens.wire.size
        rig.mover.step(-7)
        assertNull(rig.view.mark)
        assertNull(rig.view.caption)
        rig.elapse(3_000)
        // после шага ушёл только он: перевод больше не командует
        assertEquals(listOf(-7L), rig.lens.wire.drop(sent).map { it.value })
        assertTrue(rig.lens.wireOf("write").isEmpty())
    }

    // MARK: - Отказы камеры идут в журнал, перевод не обрывается

    @Test fun aRefusedCommandIsInTheJournalOnceAndTheTallyAtTheEnd() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.lens.replyCode = 0x2019
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 1.0)
        rig.elapse(8_000)
        // три команды подряд без сдвига — «дальше камера не двигает»
        assertEquals(listOf("Дальше камера не двигает"), rig.captions)
        val refusals = rig.journal.filter { it.contains("0x2019") }
        assertEquals(rig.journal.toString(), 1, refusals.size)
        assertTrue(refusals.single(), refusals.single().contains("Near/Far"))
        // итог: сколько всего отказов за перевод
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("всего 3") })
    }

    @Test fun aCommandThatDidNotGetThroughIsInTheJournalOnceAndThePullGoesOn() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.lens.failure = { kind -> if (kind == "nearFar") TransportException("тайм-аут обмена") else null }
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 1.0)
        rig.elapse(8_000)
        // перевод идёт до срока: команды уходят, ни одна не двигает — «дальше не двигает» или тайм-аут, но не обрыв
        assertTrue(rig.captions.toString(), rig.captions.isNotEmpty())
        assertEquals(rig.journal.toString(), 1, rig.journal.count { it.contains("тайм-аут обмена") })
        assertTrue(rig.lens.wireOf("nearFar").size >= 3)
    }

    @Test fun anotherKindOfFailureLeavesThePullGoingToo() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.lens.failure = { kind -> if (kind == "nearFar") SessionException.NotReady() else null }
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 1.0)
        rig.elapse(8_000)
        assertTrue(rig.captions.toString(), rig.captions.isNotEmpty())
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("камера ещё не готова") })
    }

    @Test fun aFailedRefreshIsInTheJournalOnce() = runTest {
        val rig = stepsRig(20.0, deadband = 0.0)
        rig.lens.failure = { kind -> if (kind == "refresh") TransportException("нет ответа на 0x9209") else null }
        rig.mover.go('A', Mode.steps, 20.0, 100.0, 1.0)
        rig.elapse(8_000)
        assertEquals(rig.journal.toString(), 1, rig.journal.count { it.contains("нет ответа на 0x9209") })
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("перечитать") })
    }

    @Test fun aRefusedTargetWriteInAbsoluteFocusIsInTheJournalOnceAndTheTimeoutEndsThePull() = runTest {
        val rig = absoluteRig(10_000.0)
        rig.lens.replyCode = 0x2019
        rig.mover.go('A', Mode.absolute, 10_000.0, 50_000.0, 1.0)
        rig.elapse(6_000)
        assertEquals(listOf("Не успел за 3 с"), rig.captions)
        assertEquals(rig.journal.toString(), 1, rig.journal.count { it.contains("0x2019") && it.contains("0xE042") })
    }

    @Test fun theJournalTellsWhereThePullStartedAndHowItEnded() = runTest {
        val rig = stepsRig(51.0)
        rig.mover.go('A', Mode.steps, 51.0, 51.5, 2.0)
        assertTrue(rig.journal.toString(), rig.journal.any { it.contains("фокус уже там") && it.contains("A") })

        val moving = stepsRig(20.0)
        moving.mover.go('B', Mode.steps, 20.0, 100.0, 4.0)
        assertTrue(moving.journal.toString(), moving.journal.any { it.contains("перевод к B") && it.contains("20 → 100") && it.contains("4 с") })
        moving.elapse(6_000)
        assertTrue(moving.journal.toString(), moving.journal.any { it.contains("перевод к B кончился за") && it.contains("Фокус у метки B") })
    }

    // MARK: - Ручные шаги

    @Test fun manualStepsGoOneCommandAtATimeAndSplitInTheUsualWay() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.step(17)
        rig.elapse(1_000)
        assertEquals(listOf(7L, 7L, 3L), rig.lens.wireOf("nearFar").map { it.value })
        // по одной: следующая — после ответа на прошлую
        assertEquals(listOf(0L, 80L, 160L), rig.lens.wireOf("nearFar").map { it.at })
    }

    @Test fun stepsThatKeepArrivingWhileTheLoopRunsAreAddedUp() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        rig.mover.step(7)
        rig.elapse(10)
        rig.mover.step(3)
        rig.mover.step(-1)
        rig.elapse(1_000)
        assertEquals(listOf(7L, 1L, 1L), rig.lens.wireOf("nearFar").map { it.value })
    }

    @Test fun stepsAreIgnoredWhenTheFocusCannotBeDrivenFromTheScreen() = runTest {
        val rig = stepsRig(50.0)
        rig.lens.ready = false
        rig.mover.step(7)
        rig.mover.step(0)
        rig.elapse(1_000)
        assertTrue(rig.lens.wire.isEmpty())
        assertEquals(0, rig.mover.queuedUnits)
        assertFalse(rig.mover.isPulling)
    }

    @Test fun aFailedManualStepDoesNotStopTheNextOnes() = runTest {
        val rig = stepsRig(50.0, deadband = 0.0)
        var first = true
        rig.lens.failure = { kind ->
            if (kind == "nearFar" && first) {
                first = false
                TransportException("тайм-аут")
            } else null
        }
        rig.mover.step(10)
        rig.elapse(1_000)
        assertEquals(listOf(7L, 3L), rig.lens.wireOf("nearFar").map { it.value })
    }

    @Test fun whetherAPullIsRunningIsToldPlainly() = runTest {
        val rig = stepsRig(50.0)
        assertFalse(rig.mover.isPulling)
        rig.mover.go('A', Mode.steps, 50.0, 90.0, 4.0)
        assertTrue(rig.mover.isPulling)
        rig.mover.stop()
        assertFalse(rig.mover.isPulling)
    }
}
