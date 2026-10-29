package local.zve10viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import local.zve10viewer.monitor.ProbePlateClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подписи пипетки обновляются не чаще пяти раз в секунду: цифры, которые меняются тридцать раз в секунду, не прочитать. Время
 * здесь виртуальное, и табличка видит значение только тогда, когда её опрашивают: поэтому предел держится при любой частоте
 * кадров и любой перерисовке.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProbePlateClockTest {
    /** Что табличка показала и когда (мс виртуального времени). */
    private class Shown(val at: Long, val value: Int?)

    @Test fun constantsAreFivePerSecondAndAQuickFirstLook() {
        assertEquals(200L, ProbePlateClock.INTERVAL_MS)
        assertEquals(50L, ProbePlateClock.FIRST_LOOK_MS)
        // пять раз в секунду
        assertEquals(5, (1000L / ProbePlateClock.INTERVAL_MS).toInt())
    }

    @Test fun aReadingThatChangesEveryThirtyMillisecondsShowsAtMostFiveTimesInAnySecond() = runTest {
        var current: Int? = null
        val shown = ArrayList<Shown>()
        // кадры идут каждые 30 мс (около 33 в секунду), и каждый приносит новое показание
        backgroundScope.launch {
            var n = 0
            while (true) {
                current = n++
                delay(30)
            }
        }
        backgroundScope.launch { ProbePlateClock.follow({ current }) { shown += Shown(currentTime, it) } }
        advanceTimeBy(6_000)
        assertTrue("табличка вообще обновлялась: ${shown.size}", shown.size >= 25)
        // в любом окне в одну секунду — не больше пяти обновлений, а между соседними — не меньше 200 мс
        for (start in shown.map { it.at }) {
            val inWindow = shown.count { it.at >= start && it.at < start + 1000 }
            assertTrue("окно с $start мс: $inWindow", inWindow <= 5)
        }
        for ((a, b) in shown.zipWithNext()) assertTrue("между ${a.at} и ${b.at}", b.at - a.at >= ProbePlateClock.INTERVAL_MS)
    }

    @Test fun theFirstReadingShowsAlmostAtOnceNotAfterAFullInterval() = runTest {
        var current: Int? = null
        val shown = ArrayList<Shown>()
        backgroundScope.launch { ProbePlateClock.follow({ current }) { shown += Shown(currentTime, it) } }
        advanceTimeBy(120)
        assertTrue(shown.isEmpty())
        // первое показание пришло на 120-й миллисекунде: ждать приходится не дольше короткого первого опроса
        current = 42
        advanceTimeBy(ProbePlateClock.FIRST_LOOK_MS + 1)
        assertEquals(1, shown.size)
        assertEquals(42, shown.single().value)
        assertTrue("в ${shown.single().at}", shown.single().at <= 120 + ProbePlateClock.FIRST_LOOK_MS)
    }

    @Test fun anUnchangedReadingDoesNotTouchTheLabel() = runTest {
        val shown = ArrayList<Shown>()
        backgroundScope.launch { ProbePlateClock.follow({ 7 }) { shown += Shown(currentTime, it) } }
        advanceTimeBy(5_000)
        assertEquals(1, shown.size)
        assertEquals(7, shown.single().value)
    }

    @Test fun theLabelFollowsTheLatestReadingNotEveryOne() = runTest {
        var current: Int? = null
        val shown = ArrayList<Shown>()
        backgroundScope.launch { ProbePlateClock.follow({ current }) { shown += Shown(currentTime, it) } }
        current = 1
        advanceTimeBy(60)
        // между двумя опросами показание сменилось трижды — видно последнее
        current = 2
        advanceTimeBy(30)
        current = 3
        advanceTimeBy(30)
        current = 4
        advanceTimeBy(ProbePlateClock.INTERVAL_MS)
        assertEquals(listOf<Int?>(1, 4), shown.map { it.value })
    }

    @Test fun whenTheReadingGoesAwayTheLabelGoesAwayAndComesBackQuickly() = runTest {
        var current: Int? = 5
        val shown = ArrayList<Shown>()
        backgroundScope.launch { ProbePlateClock.follow({ current }) { shown += Shown(currentTime, it) } }
        advanceTimeBy(100)
        current = null
        advanceTimeBy(ProbePlateClock.INTERVAL_MS + 10)
        assertEquals(listOf<Int?>(5, null), shown.map { it.value })
        assertNull(shown.last().value)
        // нет показания — снова смотрим часто, как до первого
        current = 9
        advanceTimeBy(ProbePlateClock.FIRST_LOOK_MS + 5)
        assertEquals(listOf<Int?>(5, null, 9), shown.map { it.value })
    }

    @Test fun cancellingStopsTheLoop() = runTest {
        var current: Int? = 1
        val shown = ArrayList<Shown>()
        val job = backgroundScope.launch { ProbePlateClock.follow({ current }) { shown += Shown(currentTime, it) } }
        advanceTimeBy(100)
        job.cancel()
        current = 2
        advanceTimeBy(2_000)
        assertEquals(listOf<Int?>(1), shown.map { it.value })
    }
}
