package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.PictureHelp
import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.LumaStats
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Помощь в кадре по картинке: белый уровень, какая картинка нужна помощи, уровни по массиву точек, размах яркости и строка
 * отчёта для logcat.
 */
class PictureHelpTest {
    private val opaque = 0xFF000000.toInt()

    private fun grey(v: Int) = opaque or (v shl 16) or (v shl 8) or v

    /** Уровни: столько-то точек на каждом из перечисленных уровней. */
    private fun levelsOf(vararg points: Pair<Int, Int>) = IntArray(256).also { for ((level, count) in points) it[level] += count }

    // белый уровень

    @Test fun lutPictureHelpAlwaysHasTheFullScale() {
        // после LUT чёрный и белый — снова 0 и 255, даже если в видео сигнал камеры упирался в 234
        val levels = levelsOf(20 to 500, 234 to 500)
        assertEquals(234, LumaStats.whiteLevel(levels, video = true))
        assertEquals(255, PictureHelp.whiteLevel(levels, video = true, lut = true, lutScopes = true))
        assertEquals(255, PictureHelp.whiteLevel(levels, video = false, lut = true, lutScopes = true))
    }

    @Test fun helpWithoutLutKeepsTheSignalRule() {
        val levels = levelsOf(20 to 500, 234 to 500)
        // LUT есть, но помощь — по сигналу камеры: белое как у сигнала
        assertEquals(234, PictureHelp.whiteLevel(levels, video = true, lut = true, lutScopes = false))
        assertEquals(255, PictureHelp.whiteLevel(levels, video = false, lut = true, lutScopes = false))
    }

    @Test fun noLutKeepsTheSignalRule() {
        val dim = levelsOf(20 to 500, 234 to 500)
        assertEquals(234, PictureHelp.whiteLevel(dim, video = true, lut = false, lutScopes = true))
        assertEquals(255, PictureHelp.whiteLevel(dim, video = false, lut = false, lutScopes = true))
        // больше 0,1 % точек ярче 240 — шкала полная и в видео
        val bright = levelsOf(20 to 5000, 250 to 6)
        assertEquals(255, PictureHelp.whiteLevel(bright, video = true, lut = false, lutScopes = true))
        // ровно 0,1 % — ещё нет
        val edge = levelsOf(20 to 9990, 250 to 10)
        assertEquals(234, PictureHelp.whiteLevel(edge, video = true, lut = false, lutScopes = true))
    }

    @Test fun whiteRuleIsTheSameOneTheHistogramUses() {
        val random = Random(3)
        repeat(200) {
            val levels = IntArray(256) { random.nextInt(50) }
            for (video in listOf(true, false)) {
                assertEquals(LumaStats.whiteLevel(levels, video), PictureHelp.whiteLevel(levels, video, lut = false, lutScopes = true))
                assertEquals(LumaStats.whiteLevel(levels, video), PictureHelp.whiteLevel(levels, video, lut = true, lutScopes = false))
            }
        }
    }

    // какая картинка нужна помощи

    @Test fun signalCopyIsOnlyNeededForHelpWithoutLut() {
        val cube = BuiltinLut.hlg.make()
        // помощь выключена — копия не нужна ни при каких настройках
        assertFalse(PictureHelp.needsSignal(cube, lutScopes = false, helpOn = false))
        // LUT есть, помощь «без LUT» — нужна
        assertTrue(PictureHelp.needsSignal(cube, lutScopes = false, helpOn = true))
        // помощь «с LUT» — берёт картинку с LUT
        assertFalse(PictureHelp.needsSignal(cube, lutScopes = true, helpOn = true))
        // LUT нет — сигнал и картинка на экране совпадают
        assertFalse(PictureHelp.needsSignal(null, lutScopes = false, helpOn = true))
        assertFalse(PictureHelp.needsSignal(null, lutScopes = true, helpOn = true))
    }

    // уровни по массиву точек

    @Test fun sampledLevelsOfAFlatFrameAreOneColumn() {
        val w = 1024
        val h = 576
        val levels = IntArray(256)
        val result = LumaHistogram.sampledLevels(IntArray(w * h) { grey(100) }, w, h, levels)
        assertSame(levels, result)
        assertEquals(256 * 144, levels[100])
        assertEquals(256 * 144, levels.sum())
    }

    @Test fun sampledLevelsFollowTheLumaOfTheHistogram() {
        // тот же вес каналов Rec. 709, что у LumaHistogram.levels
        val w = 64
        val h = 36
        val random = Random(4)
        val frame = IntArray(w * h) { random.nextInt() or opaque }
        val sampled = IntArray(256)
        LumaHistogram.sampledLevels(frame, w, h, sampled, columns = w, rows = h)
        assertArrayEquals(LumaHistogram.levels(frame), sampled)
    }

    @Test fun sampledLevelsPickThePointInTheMiddleOfEachCell() {
        // 1024×576 на сетку 256×144: из каждой клетки 4×4 берётся точка (2, 2), как у ближайшего соседа
        val w = 1024
        val h = 576
        val frame = IntArray(w * h) { grey(0) }
        for (j in 0 until 144) for (i in 0 until 256) frame[(4 * j + 2) * w + 4 * i + 2] = grey(200)
        val levels = IntArray(256)
        LumaHistogram.sampledLevels(frame, w, h, levels)
        assertEquals(256 * 144, levels[200])
        assertEquals(0, levels[0])
    }

    @Test fun sampledLevelsOfAHalfAndHalfFrameAreHalfAndHalf() {
        val w = 640
        val h = 360
        val frame = IntArray(w * h) { if (it % w < w / 2) grey(0) else grey(255) }
        val levels = IntArray(256)
        LumaHistogram.sampledLevels(frame, w, h, levels)
        assertEquals(128 * 144, levels[0])
        assertEquals(128 * 144, levels[255])
    }

    @Test fun sampledLevelsAreClearedBetweenCalls() {
        val levels = IntArray(256)
        val frame = IntArray(16 * 9) { grey(10) }
        repeat(3) { LumaHistogram.sampledLevels(frame, 16, 9, levels) }
        assertEquals(256 * 144, levels.sum())
    }

    @Test fun sampledLevelsStayInsideTinyAndOddFrames() {
        val levels = IntArray(256)
        for ((w, h) in listOf(1 to 1, 3 to 2, 2 to 5, 255 to 143, 257 to 145, 1024 to 1024, 1024 to 680)) {
            LumaHistogram.sampledLevels(IntArray(w * h) { grey(it % 256) }, w, h, levels)
            assertEquals("$w×$h", 256 * 144, levels.sum())
        }
    }

    @Test fun sampledLevelsReadOnlyTheFrameAndNotItsTail() {
        // буфер кадрового цикла больше кадра: хвост предыдущего большого кадра не должен попасть в уровни
        val w = 64
        val h = 36
        val buffer = IntArray(1024 * 576) { grey(255) }
        for (i in 0 until w * h) buffer[i] = grey(7)
        val levels = IntArray(256)
        LumaHistogram.sampledLevels(buffer, w, h, levels)
        assertEquals(256 * 144, levels[7])
    }

    // размах яркости p1–p99

    @Test fun percentilesPickTheFirstLevelThatReachesTheShare() {
        // 1000 точек: 10 на уровне 5, 980 на уровне 100, 10 на уровне 200
        val levels = levelsOf(5 to 10, 100 to 980, 200 to 10)
        assertEquals(5, PictureHelp.percentile(levels, 1))
        assertEquals(100, PictureHelp.percentile(levels, 50))
        assertEquals(100, PictureHelp.percentile(levels, 99))
        assertEquals(200, PictureHelp.percentile(levels, 100))
        assertEquals(95, PictureHelp.spread(levels))
    }

    @Test fun percentilesNeedMoreThanTheShareToLeaveALevel() {
        // ровно 1 % на уровне 5: p1 — это он; чуть меньше — уже следующий уровень
        assertEquals(5, PictureHelp.percentile(levelsOf(5 to 10, 100 to 990), 1))
        assertEquals(100, PictureHelp.percentile(levelsOf(5 to 9, 100 to 991), 1))
    }

    @Test fun anEmptyHistogramHasNoSpread() {
        assertEquals(0, PictureHelp.percentile(IntArray(256), 1))
        assertEquals(0, PictureHelp.spread(IntArray(256)))
        assertEquals(0.0, PictureHelp.mean(IntArray(256)), 0.0)
    }

    @Test fun meanIsTheAverageLevel() {
        assertEquals(100.0, PictureHelp.mean(levelsOf(100 to 77)), 1e-9)
        assertEquals(150.0, PictureHelp.mean(levelsOf(100 to 10, 200 to 10)), 1e-9)
    }

    // строка для logcat

    @Test fun reportLineNamesWhatTheChecksLookFor() {
        val levels = levelsOf(5 to 10, 100 to 980, 105 to 10)
        val line = PictureHelp.report(
            lut = "slog3cine", mode = "auto", orientation = FrameOrientation.mirror, lutScopes = true, white = 255,
            levels = levels, width = 1024, height = 576, passAverageMs = 8.44, passMaxMs = 11.26,
        )
        assertEquals(
            "картинка: lut=slog3cine mode=auto orient=mirror lutScopes=true white=255 p1=5 p99=100 spread=95 mean=99.1 " +
                "pass=8.4 passMax=11.3 size=1024x576",
            line,
        )
    }

    @Test fun reportLineForNoLutNoMirrorAndNoPass() {
        val line = PictureHelp.report("off", "off", FrameOrientation.off, true, 234, levelsOf(10 to 4, 20 to 4), 640, 360)
        assertEquals(
            "картинка: lut=off mode=off orient=off lutScopes=true white=234 p1=10 p99=20 spread=10 mean=15.0 pass=- passMax=- size=640x360",
            line,
        )
    }
}
