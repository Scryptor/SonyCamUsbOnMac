package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.LookWatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Что лежит на картинке, замеченное по кадрам: смена куба (по ссылке) или поворота даёт строку для logcat сразу — по ней
 * видно, за сколько LUT сменился после нажатия или смены профиля камеры.
 */
class LookWatchTest {
    private val cine = BuiltinLut.slog3cine.make()
    private val hlg = BuiltinLut.hlg.make()

    @Test fun nothingOnThePictureIsNotAChange() {
        val watch = LookWatch()
        repeat(3) { assertFalse(watch.changed(null, FrameOrientation.off)) }
    }

    @Test fun aCubeAppearingIsAChangeAndTheLineSaysFromWhatToWhat() {
        val watch = LookWatch()
        assertTrue(watch.changed(cine, FrameOrientation.off))
        assertEquals("lut: off → slog3cine orient=off", watch.accept(cine, "slog3cine", FrameOrientation.off))
        // принят — дальше то же самое не меняется, сколько ни смотри
        repeat(5) { assertFalse(watch.changed(cine, FrameOrientation.off)) }
    }

    @Test fun mirrorComingWithTheCubeIsOneLine() {
        val watch = LookWatch()
        assertTrue(watch.changed(cine, FrameOrientation.mirror))
        assertEquals("lut: off → slog3cine orient=mirror", watch.accept(cine, "slog3cine", FrameOrientation.mirror))
        assertFalse(watch.changed(cine, FrameOrientation.mirror))
    }

    @Test fun anotherCubeIsAChange() {
        val watch = LookWatch()
        watch.accept(cine, "slog3cine", FrameOrientation.off)
        assertTrue(watch.changed(hlg, FrameOrientation.off))
        assertEquals("lut: slog3cine → hlg orient=off", watch.accept(hlg, "hlg", FrameOrientation.off))
    }

    @Test fun theCubeGoingAwayIsAChange() {
        // «Авто»: профиль сменился на не лог — куба нет
        val watch = LookWatch()
        watch.accept(cine, "slog3cine", FrameOrientation.mirror)
        assertTrue(watch.changed(null, FrameOrientation.mirror))
        assertEquals("lut: slog3cine → off orient=mirror", watch.accept(null, "off", FrameOrientation.mirror))
        assertFalse(watch.changed(null, FrameOrientation.mirror))
    }

    @Test fun orientationAloneIsAChange() {
        val watch = LookWatch()
        watch.accept(cine, "slog3cine", FrameOrientation.off)
        assertTrue(watch.changed(cine, FrameOrientation.rotate180))
        assertEquals("lut: slog3cine → slog3cine orient=180", watch.accept(cine, "slog3cine", FrameOrientation.rotate180))
        assertTrue(watch.changed(cine, FrameOrientation.mirror180))
        assertEquals("lut: slog3cine → slog3cine orient=mirror180", watch.accept(cine, "slog3cine", FrameOrientation.mirror180))
    }

    @Test fun theSameNumbersInAnotherInstanceAreAChange() {
        // файл заменили, и в нём те же числа: это другой экземпляр, таблица прохода строится заново — и это видно в logcat
        val watch = LookWatch()
        val copy = ColorLut(cine.size, cine.values.copyOf())
        assertTrue("равны по числам", cine == copy)
        watch.accept(cine, "file:look", FrameOrientation.off)
        assertTrue(watch.changed(copy, FrameOrientation.off))
        assertEquals("lut: file:look → file:look orient=off", watch.accept(copy, "file:look", FrameOrientation.off))
    }

    @Test fun aNewLoopStartsFromNothingOnThePicture() {
        // у каждого цикла кадров свой следящий: после переподключения или смены главной камеры строка о LUT приходит снова
        val first = LookWatch()
        first.accept(cine, "slog3cine", FrameOrientation.mirror)
        val second = LookWatch()
        assertTrue(second.changed(cine, FrameOrientation.mirror))
        assertEquals("lut: off → slog3cine orient=mirror", second.accept(cine, "slog3cine", FrameOrientation.mirror))
    }

    /** Кадры, на которых ничего не менялось: сколько раз следящий всё же сказал «изменилось». */
    private fun idleFrames(watch: LookWatch, count: Int): Int {
        var changes = 0
        for (i in 0 until count) if (watch.changed(cine, FrameOrientation.mirror)) changes += 1
        return changes
    }

    @Test fun framesWhereNothingChangedAllocateNothing() {
        val counter = AllocationCounter.create()
        assumeTrue("нет счётчика выделений", counter != null)
        val watch = LookWatch()
        watch.accept(cine, "slog3cine", FrameOrientation.mirror)
        val ids = longArrayOf(Thread.currentThread().id)
        val changes = IntArray(1)
        // прогрев: метод кадра собирается JIT как обычный горячий, а не на ходу посреди замера
        repeat(200) { changes[0] += idleFrames(watch, 1_000) }
        val spent = counter!!.spent(ids) { changes[0] += idleFrames(watch, 10_000) }
        assertEquals(0, changes[0])
        assertEquals("за 10 000 кадров без перемен выделено $spent байт", 0L, spent)
    }
}
