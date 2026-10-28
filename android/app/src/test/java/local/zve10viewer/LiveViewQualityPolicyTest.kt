package local.zve10viewer

import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.LiveViewQuality.high
import local.zve10viewer.session.LiveViewQuality.low
import local.zve10viewer.session.LiveViewQualityPolicy
import local.zve10viewer.session.PreviewQualityMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HIGH_BYTES = 72 * 1024
private const val LOW_BYTES = 34 * 1024

/**
 * Стенд: камера отдаёт кадры тем качеством, которое ей записали (или не слушается), канал задаёт, сколько идёт запрос
 * кадра. Следующий кадр — не раньше, чем через 40 мс (25 к/с) и не раньше, чем пришёл прошлый.
 */
private class Bench(val policy: LiveViewQualityPolicy = LiveViewQualityPolicy(now = 0), var now: Long = 0) {
    var level = high
    var obeys = true
    /** Картинка увеличена (≥ 1,5×): высокое держим до последнего. */
    var sharp = false
    val switches = mutableListOf<Pair<Long, LiveViewQuality>>()
    /** Выдержка в момент каждого спуска вниз. */
    val holds = mutableListOf<Long>()

    fun run(until: Long, fetchMs: (LiveViewQuality) -> Double) = runWhile(until, fetchMs) { true }

    fun runWhile(limit: Long, fetchMs: (LiveViewQuality) -> Double, more: () -> Boolean) {
        while (now < limit && more()) {
            val fetch = fetchMs(level)
            policy.frame(now, if (level == high) HIGH_BYTES else LOW_BYTES, fetch, level)
            policy.decide(now, outage = false, sharp = sharp)?.let {
                switches += now to it
                if (it == low) holds += policy.holdMs
                if (obeys) level = it
            }
            now += maxOf(40L, fetch.toLong())
        }
    }
}

/** Высокое не помещается (72 КБ идут 400 мс, ~180 КБ/с), низкое идёт легко. */
private val weakForHigh: (LiveViewQuality) -> Double = { if (it == high) 400.0 else 20.0 }
/** Хороший канал: высокое за 30 мс. */
private val good: (LiveViewQuality) -> Double = { if (it == high) 30.0 else 20.0 }

class LiveViewQualityPolicyTest {
    @Test fun levelComesFromTheFrameWidth() {
        assertEquals(high, LiveViewQuality.ofWidth(1024))
        assertEquals(low, LiveViewQuality.ofWidth(640))
        assertNull(LiveViewQuality.ofWidth(0))
        assertEquals(low, LiveViewQuality.of(1))
        assertEquals(high, LiveViewQuality.of(2))
    }

    @Test fun goesDownWhenHighDoesNotFit() {
        val bench = Bench()
        bench.run(until = 4_999, weakForHigh)
        // на этом качестве меньше 5 с — рано
        assertTrue(bench.switches.isEmpty())
        // прогноз: 1 / (10 мс + 72 КБ / (72 КБ / 390 мс)) = 2,5 к/с
        assertEquals(2.5, bench.policy.predictedHighFps(4_800, 5_000)!!, 0.01)
        bench.run(until = 6_000, weakForHigh)
        assertEquals(listOf(5_200L to low), bench.switches)
    }

    @Test fun staysHighOnAGoodLink() {
        val bench = Bench()
        bench.run(until = 300_000, good)
        assertTrue(bench.switches.isEmpty())
        assertEquals(25.0, bench.policy.predictedHighFps(bench.now, 5_000)!!, 1e-9)
    }

    @Test fun aSingleStallDoesNotDowngradeButThreeDo() {
        val policy = LiveViewQualityPolicy(now = 0)
        val stalls = setOf(3_000L, 8_000L, 12_000L)
        var t = 0L
        var down: Long? = null
        while (t <= 13_000 && down == null) {
            policy.frame(t, HIGH_BYTES, if (t in stalls) 2_000.0 else 30.0, high)
            if (policy.decide(t, outage = false) == low) down = t
            t += 40
        }
        // одно и два замирания — не повод; третье за 20 с — снижаем
        assertEquals(12_000L, down)
    }

    @Test fun noDecisionsDuringAnOutage() {
        val policy = LiveViewQualityPolicy(now = 0)
        var t = 0L
        while (t <= 6_000) {
            policy.frame(t, HIGH_BYTES, 400.0, high)
            t += 400
        }
        assertNull(policy.decide(6_000, outage = true))
        assertEquals(low, policy.decide(6_000, outage = false))
    }

    @Test fun goesUpAfterTheHoldWhenTheLinkAllows() {
        val bench = Bench()
        bench.run(until = 6_000, weakForHigh)
        assertEquals(listOf(5_200L to low), bench.switches)
        // сигнал выправился: и низкое, и высокое идут быстро
        bench.run(until = 40_000, good)
        // выдержка 15 с после спуска: не раньше 20,2 с
        assertEquals(listOf(5_200L to low, 20_200L to high), bench.switches)
    }

    @Test fun upNeedsTenSecondsWithoutStalls() {
        val policy = LiveViewQualityPolicy(now = 0)
        policy.frame(0, LOW_BYTES, 20.0, low)
        var t = 40L
        var up: Long? = null
        while (t <= 40_000 && up == null) {
            // замирание в 7 с: пока оно в окне вверх (10 с), не поднимаем
            policy.frame(t, LOW_BYTES, if (t == 7_000L) 2_000.0 else 20.0, low)
            if (policy.decide(t, outage = false) == high) up = t
            t += 40
        }
        assertEquals(17_040L, up)
    }

    @Test fun waitsForTheRequestedLevelBeforeDecidingAgain() {
        // камера запись не выполнила — решаем заново не раньше, чем через 5 с
        val bench = Bench().apply { obeys = false }
        bench.run(until = 20_000, weakForHigh)
        val times = bench.switches.map { it.first }
        assertEquals(listOf(5_200L, 10_400L, 15_600L), times)
        assertTrue(bench.switches.all { it.second == low })
    }

    @Test fun holdDoublesWhenAnUpSwitchFailsQuickly() {
        val bench = Bench()
        bench.run(until = 1_200_000, weakForHigh)
        // первый спуск — 15 с; каждый подъём, после которого пришлось спуститься за 15 с, — вдвое дольше, до 5 мин
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), bench.holds.take(7))
    }

    @Test fun holdResetsAfterTwoMinutesOfStableHigh() {
        val bench = Bench()
        var highFits = false
        val channel: (LiveViewQuality) -> Double = { if (it == high) (if (highFits) 30.0 else 400.0) else 20.0 }
        bench.runWhile(200_000, channel) { bench.holds.size < 2 }
        assertEquals(listOf(15_000L, 30_000L), bench.holds)
        // сигнал выправился: подъём после выдержки и две минуты устойчиво на высоком
        highFits = true
        bench.runWhile(400_000, channel) { bench.level == low }
        val upAt = bench.now
        bench.run(until = upAt + 121_000, channel)
        assertEquals(15_000L, bench.policy.holdMs)
        // снова плохо — выдержка опять с 15 с, а не вдвое
        highFits = false
        bench.runWhile(upAt + 200_000, channel) { bench.holds.size < 3 }
        assertEquals(15_000L, bench.holds.last())
    }

    @Test fun sharpKeepsHighDownToSixFPS() {
        // высокое идёт 125 мс — прогноз 8 к/с: обычно снижаем, при увеличении держим
        val eight: (LiveViewQuality) -> Double = { if (it == high) 125.0 else 20.0 }
        val usual = Bench()
        usual.run(until = 30_000, eight)
        assertEquals(low, usual.level)
        val sharp = Bench().apply { this.sharp = true }
        sharp.run(until = 30_000, eight)
        assertTrue(sharp.switches.isEmpty())
        // 200 мс — 5 к/с: снижаем и при увеличении
        val five: (LiveViewQuality) -> Double = { if (it == high) 200.0 else 20.0 }
        val sharpFive = Bench().apply { this.sharp = true }
        sharpFive.run(until = 30_000, five)
        assertEquals(low, sharpFive.level)
    }

    @Test fun sharpReturnsHighFromTenFPS() {
        // на низком кадр идёт 48 мс: прогноз на высоком ~11 к/с — мало для 18, хватает для 10
        val middling: (LiveViewQuality) -> Double = { if (it == high) 90.0 else 48.0 }
        for (sharp in listOf(false, true)) {
            val bench = Bench().apply { this.sharp = sharp }
            bench.run(until = 6_000, weakForHigh)
            assertEquals(low, bench.level)
            bench.run(until = 60_000, middling)
            assertEquals(if (sharp) high else low, bench.level)
        }
    }

    @Test fun rttIsTheMinimumOverTenSeconds() {
        val policy = LiveViewQualityPolicy(now = 0)
        // пока замеров нет — 10 мс
        assertEquals(10.0, policy.rttMs(0), 1e-9)
        policy.rtt(1_000, 40.0)
        policy.rtt(2_000, 6.0)
        policy.rtt(3_000, 0.1)
        // меньше 1 мс не бывает: столько уходит на один пакет
        assertEquals(1.0, policy.rttMs(3_000), 1e-9)
        policy.rtt(14_000, 30.0)
        // старые — дольше 10 с назад — не в счёт
        assertEquals(30.0, policy.rttMs(14_000), 1e-9)
    }

    @Test fun previewQualityModeRestoresAndLabelsSizes() {
        // новое значение — как есть; без него — прежний переключатель: выключенный — всегда высокое, иначе авто
        assertEquals(PreviewQualityMode.low, PreviewQualityMode.restored("low", legacyAdaptive = true))
        assertEquals(PreviewQualityMode.high, PreviewQualityMode.restored(null, legacyAdaptive = false))
        assertEquals(PreviewQualityMode.auto, PreviewQualityMode.restored(null, legacyAdaptive = true))
        assertEquals(PreviewQualityMode.auto, PreviewQualityMode.restored(null, legacyAdaptive = null))
        assertEquals(PreviewQualityMode.high, PreviewQualityMode.restored("bogus", legacyAdaptive = false))
        assertNull(PreviewQualityMode.auto.fixed)
        assertEquals(low, PreviewQualityMode.low.fixed)
        assertEquals(high, PreviewQualityMode.high.fixed)
        // подписи — по форме кадра: видео 16:9, фото 3:2 и 1:1; как у программы для Mac
        assertEquals("1024×576", high.sizeLabel(1024.0 / 576))
        assertEquals("640×360", low.sizeLabel(1024.0 / 576))
        assertEquals("1024×683", high.sizeLabel(1.5))
        assertEquals("640×640", low.sizeLabel(1.0))
        assertEquals("1024×576", high.sizeLabel(0.0))
    }
}
