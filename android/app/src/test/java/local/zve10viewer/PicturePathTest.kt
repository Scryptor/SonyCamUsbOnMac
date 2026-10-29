package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.CameraProfile
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.CubeFile
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FrameSurface
import local.zve10viewer.monitor.LutCache
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.PictureHelp
import local.zve10viewer.monitor.PicturePath
import local.zve10viewer.monitor.PicturePath.Step
import local.zve10viewer.monitor.PictureTransform
import local.zve10viewer.settings.LumaHistogram
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Locale
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Проход картинки в потоке кадров: когда он идёт, по чему считается помощь, как переиспользуются буферы и что бывает после
 * закрытия. Сам проход (зеркало, LUT) проверяет `PictureTransformTest`; здесь — решения вокруг него, на массивах вместо
 * Bitmap.
 */
class PicturePathTest {
    /** Потерянное пробуждение потока — провал теста, а не сборка, которая не кончается. */
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val opaque = 0xFF000000.toInt()

    /** Кадр в массиве: читается и пишется как Bitmap, обращения считаются. */
    private class ArraySurface(override val width: Int, override val height: Int, val pixels: IntArray) : FrameSurface {
        var reads = 0
        var writes = 0

        override fun read(into: IntArray) {
            reads += 1
            System.arraycopy(pixels, 0, into, 0, width * height)
        }

        override fun write(from: IntArray) {
            writes += 1
            System.arraycopy(from, 0, pixels, 0, width * height)
        }
    }

    private fun surfaceOf(width: Int, height: Int, pixels: IntArray = numbered(width, height)) = ArraySurface(width, height, pixels)

    /** Кадр, где каждая точка называет свой номер. */
    private fun numbered(width: Int, height: Int) = IntArray(width * height) { opaque or it }

    private fun randomFrame(width: Int, height: Int, seed: Long): IntArray {
        val random = Random(seed)
        return IntArray(width * height) { random.nextInt() or opaque }
    }

    /** Кадр S-Log: плавный градиент с шумом в кодах 95…600 из 1023 — это 24…150 из 255, плоская картинка без контраста. */
    private fun logFrame(width: Int, height: Int, seed: Long): IntArray {
        val random = Random(seed)
        return IntArray(width * height) {
            val x = it % width
            val y = it / width
            val r = 24 + (x * 126 / width) + random.nextInt(6)
            val g = 24 + (y * 126 / height) + random.nextInt(6)
            val b = 24 + ((x + y) * 63 / (width + height)) + random.nextInt(6)
            opaque or (r shl 16) or (g shl 8) or b
        }
    }

    /** Что должно выйти: тот же проход напрямую, одной полосой. */
    private fun expected(src: IntArray, width: Int, height: Int, o: FrameOrientation, lut: ColorLut?): IntArray {
        val out = IntArray(width * height)
        PictureTransform(1).use { it.apply(src, width, height, o, lut, out, null) }
        return out
    }

    private fun head(array: IntArray, count: Int) = array.copyOf(count)

    private fun slog3cine(): ColorLut = BuiltinLut.slog3cine.make()

    private fun level(v: Float) = floor(v * 255f + 0.5f).toInt()

    private fun channelDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    // когда проход идёт

    @Test fun noOrientationAndNoCubeMeansNothingToDo() {
        assertFalse(PicturePath.isNeeded(FrameOrientation.off, null))
    }

    @Test fun anOrientationAloneNeedsThePass() {
        for (o in FrameOrientation.entries.filter { it != FrameOrientation.off }) {
            assertTrue("$o", PicturePath.isNeeded(o, null))
        }
    }

    @Test fun aCubeAloneNeedsThePass() {
        assertTrue(PicturePath.isNeeded(FrameOrientation.off, slog3cine()))
    }

    @Test fun nothingToDoTouchesNeitherTheFrameNorTheBuffers() {
        val surface = surfaceOf(64, 36)
        val before = surface.pixels.copyOf()
        PicturePath().use { path ->
            assertEquals(Step.Untouched, path.apply(surface, FrameOrientation.off, null, signalForHelp = true))
            assertEquals(0, surface.reads)
            assertEquals(0, surface.writes)
            assertArrayEquals(before, surface.pixels)
            assertEquals("без прохода буферов нет", 0, path.allocations)
            assertEquals("помощи после «не тронут» брать нечего", 0, path.help.size)
        }
    }

    // что получает экран и что видит помощь

    @Test fun mirrorAloneMirrorsTheFrameAndHelpSeesIt() {
        val w = 5
        val h = 3
        val src = numbered(w, h)
        val surface = surfaceOf(w, h, src.copyOf())
        PicturePath().use { path ->
            assertEquals(Step.Done, path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false))
            val shown = expected(src, w, h, FrameOrientation.mirror, null)
            assertArrayEquals(shown, surface.pixels)
            assertArrayEquals("помощь видит то, что на экране", shown, head(path.help, w * h))
            // зеркало: левая точка верхней строки теперь с правого края
            assertEquals(src[w - 1], surface.pixels[0])
        }
    }

    @Test fun lutPictureGoesToTheFrameAndHelpSeesItByDefault() {
        val w = 64
        val h = 36
        val src = randomFrame(w, h, 1)
        val lut = slog3cine()
        val surface = surfaceOf(w, h, src.copyOf())
        PicturePath().use { path ->
            assertEquals(Step.Done, path.apply(surface, FrameOrientation.off, lut, signalForHelp = false))
            val shown = expected(src, w, h, FrameOrientation.off, lut)
            assertArrayEquals(shown, surface.pixels)
            assertArrayEquals("помощь «с LUT» считается по картинке с LUT", shown, head(path.help, w * h))
            assertEquals(1, surface.reads)
            assertEquals(1, surface.writes)
        }
    }

    @Test fun helpWithoutLutSeesTheOrientedSignalWhileTheScreenGetsTheLutPicture() {
        val w = 64
        val h = 36
        val lut = slog3cine()
        PicturePath().use { path ->
            for (o in FrameOrientation.entries) {
                val src = randomFrame(w, h, 2L + o.ordinal)
                val surface = surfaceOf(w, h, src.copyOf())
                assertEquals(Step.Done, path.apply(surface, o, lut, signalForHelp = true))
                assertArrayEquals("$o: на экран — поворот и LUT", expected(src, w, h, o, lut), surface.pixels)
                assertArrayEquals("$o: помощь «без LUT» — сигнал камеры, только повёрнутый", expected(src, w, h, o, null), head(path.help, w * h))
            }
        }
    }

    @Test fun helpIsClearedWhenALaterFrameIsLeftAlone() {
        // «не тронут» не оставляет помощи точки прошлого кадра: взять их по ошибке было бы тихой порчей
        PicturePath().use { path ->
            path.apply(surfaceOf(16, 9), FrameOrientation.mirror, null, signalForHelp = false)
            assertTrue(path.help.isNotEmpty())
            assertEquals(Step.Untouched, path.apply(surfaceOf(16, 9), FrameOrientation.off, null, signalForHelp = false))
            assertEquals(0, path.help.size)
        }
    }

    @Test fun signalCopyIsOnlyKeptWhenACubeIsOn() {
        // «без LUT» при выключенном LUT — тот же сигнал, что на экране: второй копии нет
        val w = 8
        val h = 4
        val src = numbered(w, h)
        val surface = surfaceOf(w, h, src.copyOf())
        PicturePath().use { path ->
            assertEquals(Step.Done, path.apply(surface, FrameOrientation.rotate180, null, signalForHelp = true))
            assertArrayEquals(surface.pixels, head(path.help, w * h))
            assertEquals("только src и display, без analysis", 2, path.allocations)
        }
    }

    @Test fun pictureNeverReadsTheScreenTwice() {
        // помощь берёт точки у прохода, а не просит кадр ещё раз
        val surface = surfaceOf(32, 18)
        PicturePath().use { path ->
            repeat(5) { path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = true) }
            assertEquals(5, surface.reads)
            assertEquals(5, surface.writes)
        }
    }

    // буферы

    @Test fun buffersAreMadeOncePerSizeAndReusedBetweenFrames() {
        val lut = slog3cine()
        PicturePath().use { path ->
            val surface = surfaceOf(1024, 576, randomFrame(1024, 576, 3))
            path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = false)
            val first = path.help
            assertEquals("src и display", 2, path.allocations)
            repeat(10) {
                assertEquals(Step.Done, path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = false))
                assertSame("тот же массив на тот же размер", first, path.help)
            }
            assertEquals("новых массивов на кадр нет", 2, path.allocations)
            // копия сигнала нужна только «без LUT»: она одна и остаётся
            path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = true)
            val signal = path.help
            assertEquals(3, path.allocations)
            repeat(10) {
                path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = true)
                assertSame(signal, path.help)
            }
            assertEquals(3, path.allocations)
            // и обратно на «с LUT»: прежний массив картинки
            path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = false)
            assertSame(first, path.help)
            assertEquals(3, path.allocations)
        }
    }

    @Test fun smallerFramesReuseTheBiggerBuffersAndGetTheRightPicture() {
        // качество превью по Wi-Fi: 1024×576 ↔ 640×360; лишний хвост большого кадра в маленьком не виден
        val lut = slog3cine()
        PicturePath().use { path ->
            var big: IntArray? = null
            for ((w, h) in listOf(1024 to 576, 640 to 360, 1024 to 576, 640 to 360, 64 to 36)) {
                val src = randomFrame(w, h, (w + h).toLong())
                val surface = surfaceOf(w, h, src.copyOf())
                assertEquals(Step.Done, path.apply(surface, FrameOrientation.mirror180, lut, signalForHelp = true))
                assertArrayEquals("$w×$h на экране", expected(src, w, h, FrameOrientation.mirror180, lut), surface.pixels)
                assertArrayEquals("$w×$h для помощи", expected(src, w, h, FrameOrientation.mirror180, null), head(path.help, w * h))
                if (big == null) big = path.help else assertSame("$w×$h", big, path.help)
            }
            assertEquals("src, display, analysis — по одному на всё время", 3, path.allocations)
        }
    }

    @Test fun biggerFrameGrowsTheBuffersOnceAndKeepsThemAfterwards() {
        val lut = slog3cine()
        PicturePath().use { path ->
            path.apply(surfaceOf(640, 360, randomFrame(640, 360, 4)), FrameOrientation.mirror, lut, signalForHelp = false)
            val small = path.help
            assertEquals(2, path.allocations)
            val surface = surfaceOf(1024, 576, randomFrame(1024, 576, 5))
            val src = surface.pixels.copyOf()
            path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = false)
            val big = path.help
            assertTrue(big.size >= 1024 * 576)
            assertTrue(big !== small)
            assertEquals("выросли оба", 4, path.allocations)
            assertArrayEquals(expected(src, 1024, 576, FrameOrientation.mirror, lut), surface.pixels)
            path.apply(surfaceOf(640, 360, randomFrame(640, 360, 6)), FrameOrientation.mirror, lut, signalForHelp = false)
            assertSame(big, path.help)
            assertEquals(4, path.allocations)
        }
    }

    @Test fun framesThatAreNotSixteenByNineComeOutRight() {
        // фото 1:1 и 3:2
        val lut = slog3cine()
        PicturePath().use { path ->
            for ((w, h) in listOf(1024 to 1024, 1024 to 680, 3 to 5, 1 to 1, 17 to 2)) {
                for (o in FrameOrientation.entries) {
                    val src = randomFrame(w, h, (w * 31 + h).toLong())
                    val surface = surfaceOf(w, h, src.copyOf())
                    if (!PicturePath.isNeeded(o, lut)) continue
                    assertEquals("$w×$h $o", Step.Done, path.apply(surface, o, lut, signalForHelp = true))
                    assertArrayEquals("$w×$h $o", expected(src, w, h, o, lut), surface.pixels)
                    assertArrayEquals("$w×$h $o", expected(src, w, h, o, null), head(path.help, w * h))
                }
            }
        }
    }

    // смена LUT между кадрами

    @Test fun anotherCubeBetweenFramesAppliesFromTheNextFrame() {
        val w = 64
        val h = 36
        val src = randomFrame(w, h, 7)
        val a = BuiltinLut.slog3cine.make()
        val b = BuiltinLut.hlg.make()
        PicturePath().use { path ->
            for (lut in listOf(a, b, a, null, b, a)) {
                val surface = surfaceOf(w, h, src.copyOf())
                assertEquals(Step.Done, path.apply(surface, FrameOrientation.mirror, lut, signalForHelp = false))
                assertArrayEquals(expected(src, w, h, FrameOrientation.mirror, lut), surface.pixels)
            }
        }
    }

    @Test fun cubeWithAReversedDomainGivesWhatColorLutApplyGives() {
        // DOMAIN_MAX ≤ DOMAIN_MIN: цвет стоит в начале домена, как в ColorLut.apply, а не вылетает и не чернеет по-своему
        val rows = StringBuilder("LUT_3D_SIZE 2\nDOMAIN_MIN 0.8 0.8 0.8\nDOMAIN_MAX 0.2 0.2 0.2\n")
        val corners = listOf(
            "0.1 0.2 0.3", "0.9 0.2 0.3", "0.1 0.8 0.3", "0.9 0.8 0.3", "0.1 0.2 0.7", "0.9 0.2 0.7", "0.1 0.8 0.7", "0.9 0.8 0.7",
        )
        for (row in corners) rows.append(row).append('\n')
        val lut = CubeFile.parse(rows.toString())
        val w = 32
        val h = 18
        val src = randomFrame(w, h, 8)
        val surface = surfaceOf(w, h, src.copyOf())
        PicturePath().use { path ->
            assertEquals(Step.Done, path.apply(surface, FrameOrientation.off, lut, signalForHelp = false))
        }
        for (i in src.indices) {
            val p = src[i]
            val out = lut.apply(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f)
            val want = opaque or (level(out.first) shl 16) or (level(out.second) shl 8) or level(out.third)
            assertTrue("точка $i: ${surface.pixels[i].toUInt().toString(16)} вместо ${want.toUInt().toString(16)}",
                channelDistance(want, surface.pixels[i]) <= 1)
        }
    }

    // закрытие

    @Test fun closedPathStopsTheFrameWithoutThrowing() {
        val surface = surfaceOf(16, 9)
        val path = PicturePath()
        path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false)
        path.close()
        val shown = surface.pixels.copyOf()
        assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false))
        assertEquals("после закрытия кадр не пишется", 1, surface.writes)
        assertArrayEquals(shown, surface.pixels)
        // и дальше то же самое, сколько ни звать: цикл кадров по этому выходит, а не крутится
        repeat(3) { assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false)) }
        assertEquals(1, surface.writes)
        assertEquals(0, path.help.size)
    }

    @Test fun nothingToDoAfterCloseIsStillStopped() {
        // закрытый проход не должен выглядеть «ничего не делать»: цикл кадров остановится, а не покажет кадр без LUT
        val path = PicturePath()
        path.close()
        assertEquals(Step.Stopped, path.apply(surfaceOf(8, 4), FrameOrientation.off, null, signalForHelp = false))
    }

    @Test fun closeIsRepeatable() {
        val path = PicturePath()
        path.apply(surfaceOf(8, 4), FrameOrientation.mirror, null, signalForHelp = false)
        path.close()
        path.close()
        assertEquals(Step.Stopped, path.apply(surfaceOf(8, 4), FrameOrientation.mirror, null, signalForHelp = false))
    }

    @Test fun passClosedUnderneathStopsTheFrameAndKeepsTheReason() {
        // IllegalStateException от PictureTransform — это стоп, а не падение
        val transform = PictureTransform()
        transform.close()
        val path = PicturePath(transform)
        val surface = surfaceOf(16, 9)
        assertNull(path.failure)
        assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false))
        assertTrue(path.failure is IllegalStateException)
        assertEquals("кадр не записан", 0, surface.writes)
        assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false))
        assertEquals(0, surface.writes)
    }

    @Test fun closeInTheMiddleOfAFrameStopsThatFrameWithoutACrash() {
        // главный поток закрывает проход, пока цикл кадров читает точки: кадр не пишется, цикл получает «стоп»
        val entered = CountDownLatch(1)
        val go = CountDownLatch(1)
        var writes = 0
        val surface = object : FrameSurface {
            override val width = 32
            override val height = 18
            override fun read(into: IntArray) {
                entered.countDown()
                go.await()
                java.util.Arrays.fill(into, 0, 32 * 18, opaque or 0x123456)
            }

            override fun write(from: IntArray) {
                writes += 1
            }
        }
        val path = PicturePath()
        val step = AtomicReference<Step>()
        val error = AtomicReference<Throwable>()
        val worker = thread(name = "frame loop") {
            try {
                step.set(path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = false))
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        assertTrue(entered.await(10, TimeUnit.SECONDS))
        path.close()
        go.countDown()
        worker.join(10_000)
        assertFalse(worker.isAlive)
        assertNull("падения быть не должно: ${error.get()}", error.get())
        assertEquals(Step.Stopped, step.get())
        assertEquals(0, writes)
    }

    @Test fun closeWhileTheFrameIsBeingWrittenLetsThatFrameFinish() {
        // кадр, который уже посчитан, доводится до конца; останавливается следующий
        val inWrite = CountDownLatch(1)
        val go = CountDownLatch(1)
        var writes = 0
        val surface = object : FrameSurface {
            override val width = 32
            override val height = 18
            override fun read(into: IntArray) = java.util.Arrays.fill(into, 0, 32 * 18, opaque or 0x654321)
            override fun write(from: IntArray) {
                inWrite.countDown()
                go.await()
                writes += 1
            }
        }
        val path = PicturePath()
        val steps = ArrayList<Step>()
        val worker = thread(name = "frame loop") {
            steps += path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false)
            steps += path.apply(surface, FrameOrientation.mirror, null, signalForHelp = false)
        }
        assertTrue(inWrite.await(10, TimeUnit.SECONDS))
        path.close()
        go.countDown()
        worker.join(10_000)
        assertEquals(listOf(Step.Done, Step.Stopped), steps)
        assertEquals(1, writes)
    }

    @Test fun twoPathsDoNotGetInEachOthersWay() {
        // у каждого сеанса свой проход: старый, ещё доделывающий кадр, не ломает новый
        val old = PicturePath()
        val fresh = PicturePath()
        old.apply(surfaceOf(16, 9), FrameOrientation.mirror, null, signalForHelp = false)
        old.close()
        assertEquals(Step.Done, fresh.apply(surfaceOf(16, 9), FrameOrientation.mirror, null, signalForHelp = false))
        fresh.close()
    }

    // Bitmap, который не читается или не пишется

    /** Кадр, у которого чтение или запись отказывают, как у Bitmap, освобождённого (`recycle`) или неизменяемого. */
    private class FailingSurface(private val readError: RuntimeException?, private val writeError: RuntimeException?) : FrameSurface {
        override val width = 16
        override val height = 9
        var reads = 0
        var writes = 0

        override fun read(into: IntArray) {
            reads += 1
            readError?.let { throw it }
            java.util.Arrays.fill(into, 0, 16 * 9, 0xFF102030.toInt())
        }

        override fun write(from: IntArray) {
            writes += 1
            writeError?.let { throw it }
        }
    }

    private val bitmapErrors = listOf<RuntimeException>(
        IllegalStateException("Can't call getPixels() on a recycled bitmap"),
        IllegalArgumentException("x + width must be <= bitmap.width()"),
    )

    @Test fun bitmapThatCannotBeReadStopsTheFrameInsteadOfThrowing() {
        for (error in bitmapErrors) {
            val surface = FailingSurface(readError = error, writeError = null)
            PicturePath().use { path ->
                assertEquals("$error", Step.Stopped, path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = true))
                assertSame("причина остаётся у прохода", error, path.failure)
                assertEquals("картинки для помощи нет", 0, path.help.size)
                assertEquals("на экран ничего не записано", 0, surface.writes)
                // и дальше то же, кадр больше не трогают: цикл кадров по этому выходит
                assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = true))
                assertEquals(1, surface.reads)
            }
        }
    }

    @Test fun bitmapThatCannotBeWrittenStopsTheFrameAndOffersNothingToTheHelp() {
        for (error in bitmapErrors) {
            val surface = FailingSurface(readError = null, writeError = error)
            PicturePath().use { path ->
                assertEquals("$error", Step.Stopped, path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = false))
                assertSame(error, path.failure)
                // картинка, которая до экрана не дошла, помощи не достаётся: она считала бы то, чего не видно
                assertEquals(0, path.help.size)
                assertEquals(Step.Stopped, path.apply(surface, FrameOrientation.mirror, slog3cine(), signalForHelp = false))
                assertEquals(1, surface.reads)
                assertEquals(1, surface.writes)
            }
        }
    }

    @Test fun stopReasonSaysWhatHappened() {
        PicturePath().use { path ->
            assertEquals("работает", path.stopReason)
            path.apply(FailingSurface(IllegalStateException("Bitmap освобождён"), null), FrameOrientation.mirror, null, signalForHelp = false)
            assertEquals("IllegalStateException: Bitmap освобождён", path.stopReason)
        }
        val closed = PicturePath()
        closed.close()
        assertEquals("закрыт", closed.stopReason)
        // закрыт по close(): причина «закрыт», пока кадр не споткнулся об это сам
        val midFrame = PicturePath()
        midFrame.apply(surfaceOf(8, 4), FrameOrientation.mirror, null, signalForHelp = false)
        midFrame.close()
        assertEquals("закрыт", midFrame.stopReason)
        assertEquals(Step.Stopped, midFrame.apply(surfaceOf(8, 4), FrameOrientation.mirror, null, signalForHelp = false))
        assertEquals("закрыт", midFrame.stopReason)
    }

    // без выделений

    private fun workerThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("PictureTransform-") }

    @Test fun aWholeFrameOfTheLoopAllocatesNothing() {
        // всё, что цикл кадров делает с картинкой на каждом кадре: выбор куба, проход, уровни по точкам прохода, белый
        val counter = AllocationCounter.create()
        assumeTrue("нет счётчика выделений", counter != null)
        val cache = LutCache(java.io.File("нет такой папки"), { it.run() })
        val assist = MonitorAssist(lut = MonitorAssist.LutMode.auto)
        val camera = CameraProfile(pictureProfile = 8)
        val w = 640
        val h = 360
        val surface = surfaceOf(w, h, randomFrame(w, h, 11))
        val levels = IntArray(256)
        val before = workerThreads().map { it.id }.toSet()
        PicturePath().use { path ->
            var sink = 0
            fun frames(count: Int) {
                for (i in 0 until count) {
                    // все повороты, помощь «с LUT» и «без LUT» — чтобы прогреть и измерить всё
                    val scopes = i and 4 == 0
                    val lut = cache.cube(assist, camera)
                    val step = path.apply(surface, FrameOrientation.entries[i and 3], lut, PictureHelp.needsSignal(lut, scopes, helpOn = true))
                    LumaHistogram.sampledLevels(path.help, w, h, levels)
                    sink += PictureHelp.whiteLevel(levels, video = true, lut = lut != null, lutScopes = scopes) + step.ordinal
                }
            }
            frames(600) // прогрев: JIT, куб и таблица LUT, потоки полос; первые сотни кадров JIT ещё выделяет сам
            val mine = workerThreads().map { it.id }.filter { it !in before }
            assertEquals(3, mine.size)
            val ids = (mine + Thread.currentThread().id).toLongArray()
            val spent = counter!!.spent(ids) { frames(200) }
            println("PicturePathTest: за 200 кадров выделено $spent байт ($sink)")
            assertEquals("за 200 кадров выделено $spent байт", 0L, spent)
        }
    }

    // что видно в числах

    @Test fun lutOpensUpAFlatLogPicture() {
        // то, что проверка на планшете смотрит в logcat: размах яркости p1–p99 с LUT больше, чем без него
        val w = 256
        val h = 144
        val src = logFrame(w, h, 9)
        val flat = surfaceOf(w, h, src.copyOf())
        val withLut = surfaceOf(w, h, src.copyOf())
        val levels = IntArray(256)
        val shown = IntArray(256)
        PicturePath().use { path ->
            assertEquals(Step.Done, path.apply(withLut, FrameOrientation.off, slog3cine(), signalForHelp = false))
            LumaHistogram.sampledLevels(flat.pixels, w, h, levels)
            LumaHistogram.sampledLevels(path.help, w, h, shown)
        }
        val before = PictureHelp.spread(levels)
        val after = PictureHelp.spread(shown)
        assertTrue("без LUT $before, с LUT $after", after > before + 20)
    }

    // замер

    /**
     * Сколько миллисекунд на кадр 1024×576 у всего прохода (чтение точек, зеркало и LUT, запись) на JVM. Цель на планшете — не
     * больше 12 мс, и это число лишь сравнивают между правками:
     * ZV_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*PicturePathTest' -i
     */
    @Test fun benchmark1024x576() {
        assumeTrue("ZV_BENCH не задан", System.getenv("ZV_BENCH") != null)
        val w = 1024
        val h = 576
        val lut = slog3cine()
        val original = logFrame(w, h, 12)
        val surface = surfaceOf(w, h, original.copyOf())
        class Case(val name: String, val orientation: FrameOrientation, val lut: ColorLut?, val signal: Boolean)
        val cases = listOf(
            Case("LUT + зеркало, помощь с LUT", FrameOrientation.mirror, lut, false),
            Case("LUT + зеркало, помощь без LUT", FrameOrientation.mirror, lut, true),
            Case("только LUT", FrameOrientation.off, lut, false),
            Case("только зеркало", FrameOrientation.mirror, null, false),
        )
        for (case in cases) {
            PicturePath().use { path ->
                repeat(80) {
                    System.arraycopy(original, 0, surface.pixels, 0, original.size)
                    path.apply(surface, case.orientation, case.lut, case.signal)
                }
                val times = DoubleArray(300)
                for (i in times.indices) {
                    System.arraycopy(original, 0, surface.pixels, 0, original.size)
                    val start = System.nanoTime()
                    path.apply(surface, case.orientation, case.lut, case.signal)
                    times[i] = (System.nanoTime() - start) / 1e6
                }
                times.sort()
                println(
                    String.format(
                        Locale.ROOT, "кадр 1024×576, весь проход, %s: медиана %.2f мс, среднее %.2f мс, лучший %.2f мс, 95 %% — %.2f мс",
                        case.name, times[times.size / 2], times.average(), times[0], times[times.size * 95 / 100],
                    ),
                )
            }
        }
    }
}
