package local.zve10viewer

import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.LumaStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistogramTest {
    private fun levels(vararg counts: Pair<Int, Int>) = IntArray(256).also { for ((level, count) in counts) it[level] = count }

    @Test fun histogramLevelsGroupIntoBins() {
        val gray = IntArray(30) { 0xFF808080.toInt() }
        val levels = LumaHistogram.levels(gray)
        assertEquals(256, levels.size)
        assertEquals(30, levels[128])
        // столбцы графика — те же уровни, сложенные по четыре
        assertEquals(30, LumaHistogram.binsOf(levels, 64)[32])
        assertEquals(LumaHistogram.bins(gray).toList(), LumaHistogram.binsOf(levels, 64).toList())
    }

    @Test fun histogramStatsSplitTheRangeIntoThirds() {
        // границы третей: 85 — ещё тени, 86 — уже полутона, 170 — полутона, 171 — света
        val stats = LumaStats.of(levels(40 to 20, 85 to 10, 86 to 30, 170 to 20, 171 to 15, 200 to 5))!!
        assertEquals(30.0, stats.shadows, 1e-9)
        assertEquals(50.0, stats.midtones, 1e-9)
        assertEquals(20.0, stats.highlights, 1e-9)
    }

    @Test fun histogramStatsCountNearlyBlackAndNearlyWhite() {
        // почти чёрные — уровни 0–4; пересвет — на самом белом уровне (в фото 255, с запасом в один уровень)
        val stats = LumaStats.of(levels(0 to 2, 4 to 1, 5 to 7, 128 to 80, 253 to 6, 254 to 3, 255 to 1))!!
        assertEquals(3.0, stats.clippedShadows, 1e-9)
        assertEquals(4.0, stats.clippedHighlights, 1e-9)
    }

    @Test fun videoLiveViewIsWhiteAt234() {
        // в видео live view белое — 234: пересвеченное окно ложится столбиком ровно на 234
        val video = levels(20 to 30, 120 to 40, 233 to 3, 234 to 7, 200 to 20)
        assertEquals(234, LumaStats.whiteLevel(video, video = true))
        assertEquals(255, LumaStats.whiteLevel(video, video = false))
        // ярче 240 больше 0,1 % точек — шкала у этого профиля полная
        assertEquals(255, LumaStats.whiteLevel(levels(120 to 998, 250 to 2), video = true))
        assertEquals(10.0, LumaStats.of(video, 234)!!.clippedHighlights, 1e-9)
        // трети и средняя — от шкалы до белого: 0–78, 79–156, 157–234
        val thirds = LumaStats.of(levels(78 to 1, 79 to 1, 156 to 1, 157 to 1), 234)!!
        assertEquals(listOf(25.0, 50.0, 25.0), listOf(thirds.shadows, thirds.midtones, thirds.highlights))
        assertEquals(50.0, LumaStats.of(levels(0 to 1, 234 to 1), 234)!!.mean, 1e-9)
        // столбцы тоже до белого: пересвет — у правого края
        val bins = LumaHistogram.binsOf(video, 64, 234)
        assertEquals(10, bins[63])
        assertEquals(100, bins.sum())
    }

    @Test fun histogramStatsMeanBrightness() {
        assertEquals(50.0, LumaStats.of(levels(0 to 1, 255 to 1))!!.mean, 1e-9)
        assertEquals(20.0, LumaStats.of(levels(51 to 4))!!.mean, 1e-9)
        assertNull(LumaStats.of(IntArray(256)))
    }

    @Test fun histogramPercentLabels() {
        // доли зон — целые проценты, края — с десятой долей, пока их меньше 10 %
        assertEquals("38%", LumaStats.share(38.4))
        assertEquals("0%", LumaStats.share(0.2))
        assertEquals("0,4%", LumaStats.clipping(0.44))
        assertEquals("0%", LumaStats.clipping(0.0))
        assertEquals("13%", LumaStats.clipping(12.6))
        assertTrue(LumaStats.isClipped(1.0))
        assertFalse(LumaStats.isClipped(0.9))
    }
}
