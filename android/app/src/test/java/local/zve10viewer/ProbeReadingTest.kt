package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.ColorProbe
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.FrameSurface
import local.zve10viewer.monitor.PicturePath
import local.zve10viewer.monitor.PictureTransform
import local.zve10viewer.view.ProbeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пипетка читает те же точки, что видны на экране: метка стоит в долях кадра, каким его видно, а помощь считается по картинке
 * после зеркала и переворота. Проверка — через настоящий проход картинки (`PicturePath`) на кадрах с известными цветами: что
 * метка называет, то и лежит на экране под ней, при любом повороте, у краёв и для кадров 16:9, 1:1 и 3:2.
 */
class ProbeReadingTest {
    private val opaque = 0xFF000000.toInt()

    private fun rgb(r: Int, g: Int, b: Int) = opaque or (r shl 16) or (g shl 8) or b

    /** Цветные полосы 75 %, как в `bars.jpg`: белый, жёлтый, циан, зелёный, пурпурный, красный, синий, чёрный. */
    private val bars = listOf(
        rgb(191, 191, 191), rgb(191, 191, 0), rgb(0, 191, 191), rgb(0, 191, 0), rgb(191, 0, 191), rgb(191, 0, 0), rgb(0, 0, 191), rgb(0, 0, 0),
    )

    /** Четыре четверти: сверху слева красная, справа зелёная, снизу слева синяя, справа жёлтая. */
    private val quarters = listOf(rgb(200, 20, 20), rgb(20, 200, 20), rgb(20, 20, 200), rgb(200, 200, 20))

    private class ArraySurface(override val width: Int, override val height: Int, val pixels: IntArray) : FrameSurface {
        override fun read(into: IntArray) = System.arraycopy(pixels, 0, into, 0, width * height)

        override fun write(from: IntArray) = System.arraycopy(from, 0, pixels, 0, width * height)
    }

    private fun barFrame(w: Int, h: Int) = IntArray(w * h) { bars[(it % w) * 8 / w] }

    private fun quarterFrame(w: Int, h: Int) = IntArray(w * h) { quarters[(if ((it / w) * 2 >= h) 2 else 0) + (if ((it % w) * 2 >= w) 1 else 0)] }

    /** Что показано на экране после прохода и по каким точкам считается помощь. */
    private class Shown(val screen: IntArray, val help: IntArray)

    private fun run(frame: IntArray, w: Int, h: Int, o: FrameOrientation, lut: BuiltinLut? = null, lutScopes: Boolean = true): Shown {
        val surface = ArraySurface(w, h, frame.copyOf())
        PicturePath().use { path ->
            val step = path.apply(surface, o, lut?.make(), signalForHelp = lut != null && !lutScopes)
            return Shown(surface.pixels.copyOf(), if (step == PicturePath.Step.Done) path.help.copyOf(w * h) else surface.pixels.copyOf())
        }
    }

    /** Показание метки в экранных долях (x, y) — тем же вызовом, что и поток кадров. */
    private fun read(help: IntArray, w: Int, h: Int, at: FramePoint, white: Int = 234) = ColorProbe.readAt(help, w, h, at, white)

    private fun assertColor(message: String, expected: Int, reading: ColorProbe.Reading) {
        assertEquals("$message R", (expected shr 16) and 0xFF, reading.r)
        assertEquals("$message G", (expected shr 8) and 0xFF, reading.g)
        assertEquals("$message B", expected and 0xFF, reading.b)
    }

    @Test fun theProbeNamesTheBarThatLiesUnderItOnTheScreenForEveryOrientation() {
        val (w, h) = 64 to 36
        for (o in FrameOrientation.entries) {
            val shown = run(barFrame(w, h), w, h, o)
            for (bar in bars.indices) {
                // полоса камеры в долях кадра камеры → то место экрана, где её показывает повёрнутая картинка
                val camera = FramePoint((bar + 0.5) / 8, 0.3)
                val screen = o.fromCamera(camera)
                val reading = read(shown.help, w, h, screen)
                assertColor("$o полоса $bar", bars[bar], reading)
                // и это ровно то, что лежит на экране под меткой
                val x = (screen.x * w).toInt()
                val y = (screen.y * h).toInt()
                assertEquals("$o полоса $bar на экране", bars[bar], shown.screen[y * w + x])
            }
        }
    }

    @Test fun theProbeNamesTheQuarterUnderItInBothDirectionsForEveryOrientation() {
        val (w, h) = 64 to 36
        for (o in FrameOrientation.entries) {
            val shown = run(quarterFrame(w, h), w, h, o)
            for ((index, colour) in quarters.withIndex()) {
                val camera = FramePoint(if (index % 2 == 0) 0.25 else 0.75, if (index < 2) 0.25 else 0.75)
                val screen = o.fromCamera(camera)
                assertColor("$o четверть $index", colour, read(shown.help, w, h, screen))
                assertEquals("$o четверть $index на экране", colour, shown.screen[(screen.y * h).toInt() * w + (screen.x * w).toInt()])
            }
        }
    }

    @Test fun theRedBarOfTheTestCardReadsAsOnTheMacBothMirroredAndNot() {
        // проверка на планшете: красная полоса — доля 0,6875 без зеркала и 0,3125 с зеркалом; процент 15–18, R ≥ 180, G и B ≤ 15
        val (w, h) = 64 to 36
        val plain = read(run(barFrame(w, h), w, h, FrameOrientation.off).help, w, h, FramePoint(0.6875, 0.3))
        val mirrored = read(run(barFrame(w, h), w, h, FrameOrientation.mirror).help, w, h, FramePoint(0.3125, 0.3))
        for ((name, reading) in listOf("без зеркала" to plain, "зеркало" to mirrored)) {
            assertTrue("$name: ${reading.percent} %", reading.percent in 15..18)
            assertTrue("$name: R ${reading.r}", reading.r >= 180)
            assertTrue("$name: G ${reading.g} B ${reading.b}", reading.g <= 15 && reading.b <= 15)
        }
        assertEquals(plain, mirrored)
        // белая полоса: слева без зеркала, справа с зеркалом — 79–84 %
        val whitePlain = read(run(barFrame(w, h), w, h, FrameOrientation.off).help, w, h, FramePoint(0.0625, 0.3))
        val whiteMirrored = read(run(barFrame(w, h), w, h, FrameOrientation.mirror).help, w, h, FramePoint(0.9375, 0.3))
        assertTrue("белая ${whitePlain.percent}", whitePlain.percent in 79..84)
        assertEquals(whitePlain, whiteMirrored)
    }

    @Test fun theProbeAtTheEdgesReadsTheCornerBlocksOfAnyFrameShape() {
        // метка на краю: доли 0 и 1 — крайние точки кадра (квадрат 5×5 в углу обрезается краем), кадры 16:9, 1:1 и 3:2
        for ((w, h) in listOf(64 to 36, 40 to 40, 48 to 32, 1024 to 576)) {
            for (o in FrameOrientation.entries) {
                val frame = quarterFrame(w, h)
                val shown = run(frame, w, h, o)
                // в углу камеры (0, 0) — красная четверть; на экране она там, куда её переводит fromCamera
                val corners = listOf(FramePoint(0.0, 0.0) to quarters[0], FramePoint(1.0, 0.0) to quarters[1],
                    FramePoint(0.0, 1.0) to quarters[2], FramePoint(1.0, 1.0) to quarters[3])
                for ((camera, colour) in corners) {
                    val screen = o.fromCamera(camera)
                    assertColor("$w×$h $o $camera", colour, read(shown.help, w, h, screen))
                }
            }
        }
    }

    @Test fun aDraggedProbeIsKeptInsideEveryFrameShapeAndStillReads() {
        // метку утащили за край и за кадр: точка зажата в 0…1, и показание — угловое
        for ((w, h) in listOf(64 to 36, 40 to 40, 48 to 32)) {
            val frame = quarterFrame(w, h)
            val shown = run(frame, w, h, FrameOrientation.off)
            val far = ProbeLayout.clamped(FramePoint(3.7, -2.0))
            assertNotNull(far)
            assertEquals(FramePoint(1.0, 0.0), far)
            assertColor("$w×$h", quarters[1], read(shown.help, w, h, far!!))
            val near = ProbeLayout.clamped(FramePoint(-0.5, 9.0))!!
            assertColor("$w×$h", quarters[2], read(shown.help, w, h, near))
        }
    }

    @Test fun withTheLutAndTheHelpByThePictureTheProbeReadsTheLutPicture() {
        // помощь «с LUT»: пипетка читает картинку, которая видна, и белый у неё 255
        val (w, h) = 64 to 36
        val shown = run(barFrame(w, h), w, h, FrameOrientation.mirror, lut = BuiltinLut.slog3cine, lutScopes = true)
        for (bar in bars.indices) {
            val screen = FrameOrientation.mirror.fromCamera(FramePoint((bar + 0.5) / 8, 0.3))
            val reading = read(shown.help, w, h, screen, white = 255)
            val x = (screen.x * w).toInt()
            val y = (screen.y * h).toInt()
            val onScreen = shown.screen[y * w + x]
            // то же, что на экране под меткой
            assertEquals("полоса $bar R", (onScreen shr 16) and 0xFF, reading.r)
            assertEquals("полоса $bar G", (onScreen shr 8) and 0xFF, reading.g)
            assertEquals("полоса $bar B", onScreen and 0xFF, reading.b)
        }
    }

    @Test fun withTheLutAndTheHelpBySignalTheProbeReadsTheOrientedSignalNotTheScreen() {
        // помощь «без LUT»: метка стоит там же, но читает повёрнутый сигнал камеры, без LUT
        val (w, h) = 64 to 36
        val shown = run(barFrame(w, h), w, h, FrameOrientation.mirror, lut = BuiltinLut.slog3cine, lutScopes = false)
        var differs = false
        for (bar in bars.indices) {
            val screen = FrameOrientation.mirror.fromCamera(FramePoint((bar + 0.5) / 8, 0.3))
            assertColor("сигнал, полоса $bar", bars[bar], read(shown.help, w, h, screen))
            val x = (screen.x * w).toInt()
            val y = (screen.y * h).toInt()
            if (shown.screen[y * w + x] != bars[bar]) differs = true
        }
        // а на экране — уже картинка с LUT, не сигнал
        assertTrue("LUT что-то изменил на экране", differs)
    }

    @Test fun theTransformMovesTheSamePixelsThatFromCameraNames() {
        // связь двух частей: PictureTransform переносит точку камеры (x, y) туда же, куда её переводит FrameOrientation.fromCamera
        val (w, h) = 8 to 6
        val numbered = IntArray(w * h) { opaque or it }
        for (o in FrameOrientation.entries) {
            val out = IntArray(w * h)
            PictureTransform(1).use { it.apply(numbered, w, h, o, null, out, null) }
            for (y in 0 until h) {
                for (x in 0 until w) {
                    // центр точки (x, y) камеры в долях → место на экране → номер точки там
                    val screen = o.fromCamera(FramePoint((x + 0.5) / w, (y + 0.5) / h))
                    val sx = (screen.x * w).toInt()
                    val sy = (screen.y * h).toInt()
                    assertEquals("$o $x,$y", opaque or (y * w + x), out[sy * w + sx])
                }
            }
        }
    }
}
