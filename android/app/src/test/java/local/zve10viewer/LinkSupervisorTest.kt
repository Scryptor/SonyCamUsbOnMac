package local.zve10viewer

import local.zve10viewer.wifi.LinkAction
import local.zve10viewer.wifi.LinkLoss
import local.zve10viewer.wifi.LinkSupervisor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSupervisorTest {
    /** Слежение, которое уже видело ответ на пробу (иначе неотвеченная проба — не признак провала). */
    private fun answering(): LinkSupervisor = LinkSupervisor(now = 0).apply {
        assertEquals(listOf(LinkAction.SendProbe), tick(0))
        probeAnswered(20)
    }

    /** Шаги по 250 мс от from до to включительно; все решения подряд. */
    private fun LinkSupervisor.ticks(from: Long, to: Long): List<Pair<Long, LinkAction>> =
        (from..to step 250).flatMap { t -> tick(t).map { t to it } }

    @Test fun outageStartsAfterTwoAndAHalfSecondsOfSilenceWhileWaiting() {
        val link = answering()
        link.operationStarted(100)
        link.received(150, command = true)
        assertFalse(link.ticks(250, 2500).any { it.second is LinkAction.Close })
        assertFalse(link.isOutage)
        // тишина считается от последнего байта: 150 + 2500
        link.tick(2650)
        assertTrue(link.isOutage)
        assertEquals(150L, link.outageSince)
        // любой новый байт кончает провал
        link.received(3000, command = false)
        assertFalse(link.isOutage)
        assertEquals(3000L, link.outageEndedAt)
    }

    @Test fun silenceWhenNothingIsExpectedIsNotAnOutage() {
        // проба не отвечена, но камера ещё ни разу не отвечала на пробы — по ней провал не определяем
        val fresh = LinkSupervisor(now = 0)
        fresh.ticks(0, 20_000)
        assertFalse(fresh.isOutage)
        // проба отвечена, операций нет — ждать нечего
        val idle = answering()
        idle.tick(1900)
        assertFalse(idle.isOutage)
    }

    @Test fun unansweredProbeIsAnOutageOnceProbesWereAnswered() {
        val link = answering()
        assertEquals(listOf(LinkAction.SendProbe), link.tick(2000))
        link.tick(4250)
        assertFalse(link.isOutage)
        link.tick(4500)
        assertTrue(link.isOutage)
        assertEquals(2000L, link.outageSince)
    }

    @Test fun heartbeatProbesDoNotPileUp() {
        val link = answering()
        assertEquals(20L, link.rttMs)
        // следующая — через 2 с после прошлой
        assertTrue(link.tick(1750).isEmpty())
        assertEquals(listOf(LinkAction.SendProbe), link.tick(2000))
        // пока проба открыта — новых нет; байты по командному держат связь
        for (t in 2250L..6750L step 250) {
            link.received(t, command = true)
            assertTrue(link.tick(t).isEmpty())
        }
        // без ответа 5 с — считаем потерянной, можно следующую
        link.received(7000, command = true)
        assertEquals(listOf(LinkAction.SendProbe), link.tick(7000))
        link.probeAnswered(7030)
        assertEquals(30L, link.rttMs)
        assertEquals(2, link.rttSamples)
    }

    @Test fun duringAnOutageOnlyPingsAndNoPileUp() {
        val link = answering()
        link.operationStarted(100)
        val actions = link.ticks(250, 6000)
        assertTrue(actions.none { it.second == LinkAction.SendProbe && it.first > 2600 })
        // первый ping сразу, дальше не чаще раза в секунду и только когда прошлый ответил
        val pings = actions.filter { it.second == LinkAction.Ping }.map { it.first }
        assertEquals(listOf(2750L), pings)
        link.pinged(answered = false, now = 3500)
        val next = link.ticks(3750, 6000).filter { it.second == LinkAction.Ping }.map { it.first }
        assertEquals(listOf(3750L), next)
    }

    @Test fun linkBackButSilentTcpIsResetAfterThreeSeconds() {
        val link = answering()
        link.operationStarted(100)
        link.ticks(250, 2750)
        assertTrue(link.isOutage)
        link.pinged(answered = true, now = 3000)
        assertTrue(link.ticks(3250, 5750).none { it.second is LinkAction.Close })
        assertEquals(listOf(LinkAction.Close(LinkLoss.resetAfterOutage, rst = true)), link.tick(6000))
        assertEquals(LinkLoss.resetAfterOutage, link.closedBy)
        // закрыли — дальше слежение молчит
        assertTrue(link.tick(6250).isEmpty())
    }

    @Test fun bytesAfterLinkBackEndTheOutageWithoutReset() {
        val link = answering()
        link.operationStarted(100)
        link.ticks(250, 2750)
        link.pinged(answered = true, now = 3000)
        // TCP ожил: пришли ответ на операцию и ответ на пробу
        link.received(4000, command = true)
        link.operationAnswered()
        link.probeAnswered(4010)
        assertFalse(link.isOutage)
        var t = 4000L
        while (t <= 8000) {
            assertTrue(link.tick(t).none { it is LinkAction.Close })
            link.probeAnswered(t + 10)
            t += 250
        }
        assertFalse(link.isOutage)
        assertNull(link.closedBy)
    }

    @Test fun pingFailingAgainCancelsTheReset() {
        val link = answering()
        link.operationStarted(100)
        link.ticks(250, 2750)
        link.pinged(answered = true, now = 3000)
        link.pinged(answered = false, now = 4000)
        assertTrue(link.ticks(4000, 6500).none { it.second is LinkAction.Close })
        // ответил снова — отсчёт 3 с заново
        link.pinged(answered = true, now = 6500)
        assertTrue(link.ticks(6750, 9250).none { it.second is LinkAction.Close })
        assertEquals(listOf(LinkAction.Close(LinkLoss.resetAfterOutage, rst = true)),
            link.tick(9500).filterIsInstance<LinkAction.Close>())
    }

    @Test fun outageLongerThanThreeMinutesGivesUpWithAnOrdinaryClose() {
        val link = answering()
        link.operationStarted(100)
        val closes = link.ticks(250, 181_000).filter { it.second is LinkAction.Close }
        // тишина с 100 мс: сдаёмся в 180 100 мс, обычным закрытием — FIN дойдёт, если связь вернётся
        assertEquals(listOf(180_250L to LinkAction.Close(LinkLoss.noLink, rst = false)), closes)
    }

    @Test fun cameraSilentWhileProbesAnswerIsReset() {
        val link = answering()
        link.operationStarted(1000)
        var t = 1000L
        val closes = ArrayList<Pair<Long, LinkAction>>()
        while (t <= 17_000) {
            // тайм-аут у вызывающего: live view спрашивает снова — отсчёт от самой старой неотвеченной операции
            if (t == 9000L) link.operationStarted(9000)
            closes += link.tick(t).filterIsInstance<LinkAction.Close>().map { t to it }
            // пробы отвечают: связь есть
            link.probeAnswered(t + 10)
            t += 250
        }
        assertFalse(link.isOutage)
        assertEquals(listOf(16_000L to LinkAction.Close(LinkLoss.cameraSilent, rst = true)), closes)
    }

    @Test fun answeredOperationStopsTheCameraSilentClock() {
        val link = answering()
        link.operationStarted(1000)
        link.received(1040, command = true)
        link.operationAnswered()
        link.operationStarted(12_000)
        var t = 12_000L
        while (t < 26_750) {
            assertTrue(link.tick(t).none { it is LinkAction.Close })
            link.probeAnswered(t + 10)
            t += 250
        }
    }

    @Test fun outageTimeDoesNotCountForTimeoutsOrCameraSilence() {
        val link = answering()
        link.operationStarted(100)
        link.ticks(250, 2750)
        assertTrue(link.isOutage)
        // пока связи нет, тайм-аут операции не идёт
        assertFalse(link.expired(now = 20_000, progressAt = 100, timeoutMs = 8000))
        link.probeAnswered(20_000)
        assertFalse(link.isOutage)
        // после провала — отсчёт заново от его конца
        assertFalse(link.expired(now = 27_000, progressAt = 100, timeoutMs = 8000))
        assertTrue(link.expired(now = 28_000, progressAt = 100, timeoutMs = 8000))
        var t = 20_250L
        while (t < 35_000) {
            assertTrue(link.tick(t).none { it is LinkAction.Close })
            link.probeAnswered(t + 10)
            t += 250
        }
        assertEquals(LinkAction.Close(LinkLoss.cameraSilent, rst = true), link.tick(35_000).single())
    }

    @Test fun lostWifiNetworkIsAnOutageAtOnce() {
        val link = answering()
        link.networkLost(500)
        assertTrue(link.isOutage)
        assertEquals(listOf(LinkAction.Ping), link.tick(750))
        // сеть вернулась и байты пошли — провала нет
        link.received(1500, command = false)
        assertFalse(link.isOutage)
    }

    @Test fun everyLossButNoLinkReconnectsQuietly() {
        assertTrue(LinkLoss.broken.reconnects)
        assertTrue(LinkLoss.resetAfterOutage.reconnects)
        assertTrue(LinkLoss.cameraSilent.reconnects)
        assertFalse(LinkLoss.noLink.reconnects)
        // закрыла сама программа — не переподключаемся
        assertFalse(LinkLoss.closed.reconnects)
    }
}
