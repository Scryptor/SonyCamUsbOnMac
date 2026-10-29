package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.CubeFile
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.PictureTransform
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * Проход картинки кадра: зеркало и переворот перестановкой точек, LUT — целыми числами, полосами строк параллельно.
 * Эталон для LUT — настоящий куб `ColorLut.apply` (числа с плавающей точкой), для перестановки — таблица из спецификации.
 */
class PictureTransformTest {
    /** Потерянное пробуждение потока — провал теста, а не сборка, которая не кончается. */
    @get:Rule val timeout: Timeout = Timeout.seconds(60)

    private val opaque = 0xFF000000.toInt()

    /** Проверка без цены на каждой точке: сообщение собирается, только если она не прошла. */
    private inline fun verify(ok: Boolean, message: () -> String) {
        if (!ok) throw AssertionError(message())
    }

    /** Кадр из случайных непрозрачных точек. */
    private fun randomFrame(width: Int, height: Int, seed: Long): IntArray {
        val random = Random(seed)
        return IntArray(width * height) { random.nextInt() or opaque }
    }

    /** Кадр, где каждая точка называет свой номер, — по нему видно, откуда она пришла. */
    private fun numberedFrame(width: Int, height: Int) = IntArray(width * height) { it or opaque }

    /** Эталон перестановки: точка (x, y) вида берётся из кадра по таблице спецификации. */
    private fun oriented(src: IntArray, width: Int, height: Int, o: FrameOrientation): IntArray {
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = if (o == FrameOrientation.mirror || o == FrameOrientation.rotate180) width - 1 - x else x
                val sy = if (o == FrameOrientation.rotate180 || o == FrameOrientation.mirror180) height - 1 - y else y
                out[y * width + x] = src[sy * width + sx]
            }
        }
        return out
    }

    private fun level(v: Float) = floor(v * 255f + 0.5f).toInt()

    /** Что должно выйти для точки по настоящему кубу: числа с плавающей точкой, округление до уровня 0…255. */
    private fun expectedPixel(lut: ColorLut, p: Int): Int {
        val out = lut.apply(((p shr 16) and 0xFF) / 255f, ((p shr 8) and 0xFF) / 255f, (p and 0xFF) / 255f)
        return (p and opaque) or (level(out.first) shl 16) or (level(out.second) shl 8) or level(out.third)
    }

    private fun channelDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return max(dr, max(dg, db))
    }

    /** Расхождение с эталоном: наибольшее по каналам и сколько точек совпало точно. */
    private class Match(val worst: Int, val exact: Int, val total: Int) {
        val share: Double get() = if (total == 0) 1.0 else exact.toDouble() / total
        override fun toString() = "расхождение до $worst, точно ${"%.1f".format(Locale.ROOT, share * 100)} % из $total"
    }

    private fun compareWithCube(lut: ColorLut, source: IntArray, result: IntArray, count: Int = source.size): Match {
        var worst = 0
        var exact = 0
        for (i in 0 until count) {
            val expected = expectedPixel(lut, source[i])
            val d = channelDistance(expected, result[i])
            if (d == 0) exact += 1
            if (d > worst) worst = d
            verify(expected ushr 24 == result[i] ushr 24) { "альфа точки $i: ${result[i] ushr 24}, а нужна ${expected ushr 24}" }
        }
        return Match(worst, exact, count)
    }

    /** Все цвета с шагом 5 по каждому каналу, края 0, 1, 254, 255, и случайные: покрывают куб целиком. */
    private fun colourSweep(seed: Long): IntArray {
        val colours = ArrayList<Int>()
        val levels = ((0..255 step 5).toList() + listOf(1, 2, 253, 254, 255)).distinct()
        for (r in levels) for (g in levels) for (b in levels) colours.add(opaque or (r shl 16) or (g shl 8) or b)
        val random = Random(seed)
        repeat(60_000) { colours.add(random.nextInt() or opaque) }
        return colours.toIntArray()
    }

    private val threeByTwo = intArrayOf(
        0xFF010203.toInt(), 0xFF040506.toInt(), 0xFF070809.toInt(),
        0xFF0A0B0C.toInt(), 0xFF0D0E0F.toInt(), 0xFF101112.toInt(),
    )

    // перестановка

    @Test fun threeByTwoFrameIsPermutedAsTheTableSays() {
        val a = threeByTwo[0]
        val b = threeByTwo[1]
        val c = threeByTwo[2]
        val d = threeByTwo[3]
        val e = threeByTwo[4]
        val f = threeByTwo[5]
        // [a b c / d e f]: зеркало — по строкам, переворот — по столбцам, 180° — то и другое, зеркало + 180° — только по столбцам
        val expected = mapOf(
            FrameOrientation.off to intArrayOf(a, b, c, d, e, f),
            FrameOrientation.mirror to intArrayOf(c, b, a, f, e, d),
            FrameOrientation.rotate180 to intArrayOf(f, e, d, c, b, a),
            FrameOrientation.mirror180 to intArrayOf(d, e, f, a, b, c),
        )
        assertEquals(FrameOrientation.entries.toSet(), expected.keys)
        for (bands in listOf(1, 2, 3, 4, 8)) {
            PictureTransform(bands).use { transform ->
                for ((orientation, want) in expected) {
                    val display = IntArray(6) { 0x11111111 }
                    val analysis = IntArray(6) { 0x22222222 }
                    transform.apply(threeByTwo, 3, 2, orientation, null, display, analysis)
                    assertArrayEquals("$orientation, полос $bands", want, display)
                    assertArrayEquals("$orientation, полос $bands: analysis", want, analysis)
                    // и без analysis
                    val alone = IntArray(6) { 0x33333333 }
                    transform.apply(threeByTwo, 3, 2, orientation, null, alone, null)
                    assertArrayEquals("$orientation, полос $bands: без analysis", want, alone)
                }
            }
        }
    }

    @Test fun sourceFrameIsNeverChanged() {
        val src = randomFrame(37, 23, 1)
        val copy = src.copyOf()
        val lut = BuiltinLut.slog3.make()
        PictureTransform().use { transform ->
            for (o in FrameOrientation.entries) {
                transform.apply(src, 37, 23, o, null, IntArray(37 * 23), IntArray(37 * 23))
                transform.apply(src, 37, 23, o, lut, IntArray(37 * 23), IntArray(37 * 23))
            }
        }
        assertArrayEquals(copy, src)
    }

    @Test fun offWithoutLutIsACopy() {
        val src = randomFrame(64, 36, 2)
        PictureTransform().use { transform ->
            val display = IntArray(64 * 36)
            transform.apply(src, 64, 36, FrameOrientation.off, null, display, null)
            assertNotSame(src, display)
            assertArrayEquals(src, display)
            // с analysis — две копии
            val display2 = IntArray(64 * 36)
            val analysis = IntArray(64 * 36)
            transform.apply(src, 64, 36, FrameOrientation.off, null, display2, analysis)
            assertArrayEquals(src, display2)
            assertArrayEquals(src, analysis)
        }
    }

    @Test fun everyOrientationMatchesTheReferencePermutationOnEveryShape() {
        // кадры не 16:9 (Review Focus 2): 1:1, 3:2, широкий, узкий, одна строка, один столбец, одна точка
        val shapes = listOf(1024 to 1024, 1024 to 680, 640 to 360, 5 to 7, 7 to 5, 1 to 9, 9 to 1, 1 to 1, 2 to 2, 3 to 2, 2 to 3)
        PictureTransform(4).use { transform ->
            for ((w, h) in shapes) {
                val src = randomFrame(w, h, (w * 31 + h).toLong())
                for (o in FrameOrientation.entries) {
                    val display = IntArray(w * h)
                    val analysis = IntArray(w * h)
                    transform.apply(src, w, h, o, null, display, analysis)
                    val want = oriented(src, w, h, o)
                    assertArrayEquals("$w×$h $o", want, display)
                    assertArrayEquals("$w×$h $o analysis", want, analysis)
                }
            }
        }
    }

    @Test fun displayedPixelComesFromTheCameraPointThatToCameraNames() {
        // зеркало на экране и касание/пипетка используют одно отображение: точка (x, y) на экране — это точка toCamera в кадре
        PictureTransform(3).use { transform ->
            for ((w, h) in listOf(3 to 2, 5 to 7, 1024 to 680, 64 to 64)) {
                val src = numberedFrame(w, h)
                for (o in FrameOrientation.entries) {
                    val out = IntArray(w * h)
                    transform.apply(src, w, h, o, null, out, null)
                    for (y in 0 until h) {
                        for (x in 0 until w) {
                            val p = o.toCamera(FramePoint((x + 0.5) / w, (y + 0.5) / h))
                            val sx = floor(p.x * w).toInt()
                            val sy = floor(p.y * h).toInt()
                            verify(sy * w + sx == (out[y * w + x] and 0xFFFFFF)) {
                                "$w×$h $o: на экране ($x; $y) — точка ${out[y * w + x] and 0xFFFFFF}, а toCamera называет ${sy * w + sx}"
                            }
                        }
                    }
                }
            }
        }
    }

    // LUT

    @Test fun identityLutOfTwoCornersChangesChannelsByAtMostOne() {
        val lut = ColorLut.identity(2)
        val sweep = colourSweep(3)
        val w = 300
        val h = (sweep.size + w - 1) / w
        val src = sweep.copyOf(w * h)
        for (i in sweep.size until src.size) src[i] = opaque
        PictureTransform(4).use { transform ->
            for (o in FrameOrientation.entries) {
                val display = IntArray(w * h)
                transform.apply(src, w, h, o, lut, display, null)
                val want = oriented(src, w, h, o)
                var worst = 0
                for (i in want.indices) {
                    worst = max(worst, channelDistance(want[i], display[i]))
                    verify(display[i] ushr 24 == 0xFF) { "$o: альфа точки $i — ${display[i] ushr 24}" }
                }
                assertTrue("$o: расхождение $worst", worst <= 1)
                // чёрное и белое — ровно
                val black = IntArray(1)
                transform.apply(intArrayOf(opaque), 1, 1, o, lut, black, null)
                assertEquals(opaque, black[0])
                val white = IntArray(1)
                transform.apply(intArrayOf(-1), 1, 1, o, lut, white, null)
                assertEquals(-1, white[0])
            }
        }
    }

    @Test fun lutKeepsTheAlphaOfTheSourcePoint() {
        // кадры камеры непрозрачны (альфа 255) и остаются такими; чужая альфа, если вдруг пришла, не пропадает
        val lut = BuiltinLut.hlg.make()
        val src = intArrayOf(0xFF204080.toInt(), 0x80204080.toInt(), 0x00204080)
        PictureTransform(2).use { transform ->
            val display = IntArray(3)
            transform.apply(src, 3, 1, FrameOrientation.off, lut, display, null)
            assertEquals(listOf(0xFF, 0x80, 0x00), display.map { it ushr 24 })
            // тот же цвет у всех трёх
            assertEquals(display[0] and 0xFFFFFF, display[1] and 0xFFFFFF)
            assertEquals(display[0] and 0xFFFFFF, display[2] and 0xFFFFFF)
            val plain = IntArray(3)
            transform.apply(src, 3, 1, FrameOrientation.mirror, null, plain, null)
            assertArrayEquals(intArrayOf(src[2], src[1], src[0]), plain)
        }
    }

    @Test fun analysisIsTheOrientedPictureWithoutTheLut() {
        val lut = BuiltinLut.slog3cine.make()
        val src = randomFrame(48, 27, 4)
        PictureTransform(4).use { transform ->
            for (o in FrameOrientation.entries) {
                val display = IntArray(48 * 27)
                val analysis = IntArray(48 * 27)
                transform.apply(src, 48, 27, o, lut, display, analysis)
                val want = oriented(src, 48, 27, o)
                assertArrayEquals("$o: analysis без LUT", want, analysis)
                // display — то же, повёрнутое, и с LUT
                val match = compareWithCube(lut, want, display)
                assertTrue("$o: $match", match.worst <= 1)
                assertFalse("$o: LUT должен менять кадр", display.contentEquals(want))
            }
        }
    }

    @Test fun lutFollowsTheFloatCubeWithinOneLevel() {
        val sweep = colourSweep(5)
        val cubes = listOf(
            "slog3cine" to BuiltinLut.slog3cine.make(), "slog3" to BuiltinLut.slog3.make(), "slog2" to BuiltinLut.slog2.make(),
            "hlg" to BuiltinLut.hlg.make(), "slog3cine 65" to BuiltinLut.slog3cine.make(65), "slog3 9" to BuiltinLut.slog3.make(9),
            "identity 33" to ColorLut.identity(33), "identity 65" to ColorLut.identity(65), "slog2 2" to BuiltinLut.slog2.make(2),
        )
        PictureTransform(4).use { transform ->
            for ((name, lut) in cubes) {
                val display = IntArray(sweep.size)
                transform.apply(sweep, sweep.size, 1, FrameOrientation.off, lut, display, null)
                val match = compareWithCube(lut, sweep, display)
                println("PictureTransformTest: $name — $match")
                assertTrue("$name: $match", match.worst <= 1)
                // ячейка крупного куба (9³, 2³) — десятки уровней, и 1/256 её пути уже заметная доля уровня: точных совпадений меньше.
                // У кубов 33³ и 65³ точно 96,0…97,8 % (округление выкинуть из таблицы или из шагов — 92,8…95,9 %)
                assertTrue("$name: $match", match.share > if (lut.size >= 33) 0.955 else if (lut.size >= 17) 0.9 else 0.8)
            }
        }
    }

    @Test fun lutHonoursTheDomainOfTheCube() {
        // домен .cube растягивает вход; вне домена вход обрезается, у домена без ширины канал стоит в начале домена
        val rows = ArrayList<String>()
        for (b in 0 until 3) for (g in 0 until 3) for (r in 0 until 3) rows.add("${r / 2.0} ${(g * g) / 4.0} ${1 - b / 2.0}")
        val header = listOf("LUT_3D_SIZE 3", "DOMAIN_MIN 0.2 0.0 0.5", "DOMAIN_MAX 0.8 0.5 0.5")
        val lut = CubeFile.parse((header + rows).joinToString("\n"))
        val sweep = colourSweep(6)
        PictureTransform(4).use { transform ->
            val display = IntArray(sweep.size)
            transform.apply(sweep, sweep.size, 1, FrameOrientation.off, lut, display, null)
            val match = compareWithCube(lut, sweep, display)
            assertTrue("$match", match.worst <= 1)
            assertTrue("$match", match.share > 0.8) // куб 3³: ячейки крупные
        }
        // и перевёрнутый домен
        val flipped = ColorLut(lut.size, lut.values, floatArrayOf(0.8f, 0.5f, 0f), floatArrayOf(0.2f, 0f, 1f))
        PictureTransform(2).use { transform ->
            val display = IntArray(sweep.size)
            transform.apply(sweep, sweep.size, 1, FrameOrientation.off, flipped, display, null)
            val match = compareWithCube(flipped, sweep, display)
            assertTrue("$match", match.worst <= 1)
        }
    }

    @Test fun nodesOutsideZeroOneAreClampedBeforeInterpolationLikeOnTheMac() {
        // выход .cube чуть за 0…1: узлы обрезаются до интерполяции (так их получает и Core Image на Mac)
        val values = ColorLut.identity(2).values.copyOf()
        values[0] = -0.5f // узел (0; 0; 0): красный ниже нуля,
        values[1] = 1.5f // зелёный выше единицы
        val lut = ColorLut(2, values)
        PictureTransform(1).use { transform ->
            val display = IntArray(3)
            transform.apply(intArrayOf(opaque, opaque or 0x800000, -1), 3, 1, FrameOrientation.off, lut, display, null)
            // сам узел: красный обрезан до 0, зелёный до 1
            assertEquals(0xFF00FF00.toInt(), display[0])
            // середина ребра — между обрезанными узлами (0; 1; 0) и (1; 0; 0), то есть около (128; 127; 0), а не (64; 191; 0)
            assertTrue("${display[1].toUInt().toString(16)}", abs(((display[1] shr 16) and 0xFF) - 128) <= 1)
            assertTrue("${display[1].toUInt().toString(16)}", abs(((display[1] shr 8) and 0xFF) - 127) <= 1)
            assertEquals(0, display[1] and 0xFF)
            // белый — узел (1; 1; 1), он не тронут
            assertEquals(-1, display[2])
        }
    }

    @Test fun aNewLutAppliesFromTheNextCall() {
        val src = randomFrame(40, 30, 7)
        val a = BuiltinLut.slog3cine.make()
        val inverted = ColorLut(2, ColorLut.identity(2).values.map { 1 - it }.toFloatArray())
        PictureTransform(4).use { transform ->
            val first = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, a, first, null)
            assertTrue(compareWithCube(a, src, first).worst <= 1)

            // другой LUT — другая картинка уже на следующем вызове, без остатков прежнего
            val second = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, inverted, second, null)
            assertTrue(compareWithCube(inverted, src, second).worst <= 1)
            assertNotEquals(first.toList(), second.toList())

            // тот же размер куба, другие числа: таблица переписывается на месте
            val sameSize = BuiltinLut.hlg.make()
            assertEquals(a.size, sameSize.size)
            val hlg = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, sameSize, hlg, null)
            assertTrue(compareWithCube(sameSize, src, hlg).worst <= 1)
            assertNotEquals(first.toList(), hlg.toList())
            // и тот же размер, тот же домен, а все числа вдвое меньше — тоже другой куб
            val halved = ColorLut(a.size, FloatArray(a.values.size) { a.values[it] * 0.5f })
            val dimmer = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, halved, dimmer, null)
            assertTrue(compareWithCube(halved, src, dimmer).worst <= 1)
            assertNotEquals(first.toList(), dimmer.toList())

            // обратно на первый: ровно та же картинка, что в первый раз
            val third = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, a, third, null)
            assertArrayEquals(first, third)

            // LUT выключили — кадр как есть
            val fourth = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, null, fourth, null)
            assertArrayEquals(src, fourth)

            // и снова включили; а равный по числам, но другой экземпляр даёт ту же картинку
            val fifth = IntArray(40 * 30)
            transform.apply(src, 40, 30, FrameOrientation.off, BuiltinLut.slog3cine.make(), fifth, null)
            assertArrayEquals(first, fifth)
        }
    }

    @Test fun cubeSizeMayChangeBetweenCalls() {
        // таблица растёт и сжимается вместе с кубом: 2³ → 33³ → 65³ → 2³ → 9³; следов прежнего куба нет
        val src = randomFrame(32, 32, 8)
        val cubes = listOf(ColorLut.identity(2), BuiltinLut.hlg.make(33), BuiltinLut.slog2.make(65), BuiltinLut.slog3.make(2), BuiltinLut.slog3cine.make(9))
        val want = oriented(src, 32, 32, FrameOrientation.mirror180)
        PictureTransform(4).use { transform ->
            for (round in 0 until 2) {
                for (lut in cubes) {
                    val display = IntArray(32 * 32)
                    transform.apply(src, 32, 32, FrameOrientation.mirror180, lut, display, null)
                    val match = compareWithCube(lut, want, display)
                    assertTrue("круг $round, куб ${lut.size}: $match", match.worst <= 1)
                }
            }
        }
    }

    // размер кадра

    @Test fun frameSizeMayChangeBetweenCalls() {
        // 1024×576 → 640×360 и дальше: те же буферы большего размера и буферы ровно по кадру, с LUT и без
        val lut = BuiltinLut.slog3cine.make()
        val sizes = listOf(
            1024 to 576, 640 to 360, 1024 to 576, 1024 to 1024, 1024 to 680, 640 to 360, 7 to 3, 1 to 1, 1 to 5, 5 to 1, 640 to 360,
        )
        val bigSrc = randomFrame(1024, 1024, 9)
        val bigDisplay = IntArray(1024 * 1024)
        val bigAnalysis = IntArray(1024 * 1024)
        PictureTransform(4).use { transform ->
            for ((i, size) in sizes.withIndex()) {
                val (w, h) = size
                val o = FrameOrientation.entries[i % 4]
                val frame = bigSrc.copyOf(w * h)
                val want = oriented(frame, w, h, o)
                for (useLut in listOf(false, true)) {
                    val cube = if (useLut) lut else null
                    // 1. буферы по размеру кадра
                    val display = IntArray(w * h)
                    val analysis = IntArray(w * h)
                    transform.apply(frame, w, h, o, cube, display, analysis)
                    // 2. общие буферы побольше и кадр в большом буфере — результат тот же
                    java.util.Arrays.fill(bigDisplay, 0x7A7A7A7A)
                    java.util.Arrays.fill(bigAnalysis, 0x7B7B7B7B)
                    transform.apply(bigSrc, w, h, o, cube, bigDisplay, bigAnalysis)
                    assertArrayEquals("$w×$h $o: analysis", want, analysis)
                    assertArrayEquals("$w×$h $o: analysis в большом буфере", want, bigAnalysis.copyOf(w * h))
                    assertArrayEquals("$w×$h $o: большой буфер, как малый", display, bigDisplay.copyOf(w * h))
                    if (cube == null) {
                        assertArrayEquals("$w×$h $o: display", want, display)
                    } else {
                        val match = compareWithCube(cube, want, display)
                        assertTrue("$w×$h $o: $match", match.worst <= 1)
                    }
                    // за кадром в большом буфере ничего не тронуто
                    for (j in w * h until bigDisplay.size) {
                        verify(bigDisplay[j] == 0x7A7A7A7A && bigAnalysis[j] == 0x7B7B7B7B) { "$w×$h $o: запись за кадром, точка $j" }
                    }
                }
            }
        }
    }

    @Test fun anEmptyFrameIsNothingToDo() {
        PictureTransform(4).use { transform ->
            val display = intArrayOf(5, 6)
            transform.apply(IntArray(0), 0, 0, FrameOrientation.mirror, BuiltinLut.hlg.make(), display, null)
            transform.apply(IntArray(0), 0, 7, FrameOrientation.off, null, display, null)
            transform.apply(IntArray(0), 7, 0, FrameOrientation.off, null, display, IntArray(0))
            assertArrayEquals(intArrayOf(5, 6), display)
        }
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("Ожидалось IllegalArgumentException")
    }

    @Test fun badArgumentsAreRefusedBeforeAnythingIsWritten() {
        val src = IntArray(12)
        PictureTransform(2).use { transform ->
            val display = IntArray(12) { 1 }
            // буферы меньше кадра
            refused { transform.apply(IntArray(11), 4, 3, FrameOrientation.off, null, display, null) }
            refused { transform.apply(src, 4, 3, FrameOrientation.off, null, IntArray(11), null) }
            refused { transform.apply(src, 4, 3, FrameOrientation.off, null, display, IntArray(11)) }
            // один массив в двух ролях
            refused { transform.apply(src, 4, 3, FrameOrientation.off, null, src, null) }
            refused { transform.apply(src, 4, 3, FrameOrientation.mirror, null, display, src) }
            refused { transform.apply(src, 4, 3, FrameOrientation.mirror, null, display, display) }
            // отрицательный размер
            refused { transform.apply(src, -4, -3, FrameOrientation.off, null, display, null) }
            refused { transform.apply(src, 4, -3, FrameOrientation.off, null, display, null) }
            // размер, у которого ширина × высота не влезает в Int
            refused { transform.apply(src, 70_000, 70_000, FrameOrientation.off, null, display, null) }
            assertTrue(display.all { it == 1 })
            // и после отказов объект работает
            transform.apply(src, 4, 3, FrameOrientation.off, null, display, null)
            assertArrayEquals(src, display)
        }
        refused { PictureTransform(0) }
        refused { PictureTransform(-1) }
    }

    // полосы и потоки

    @Test fun everyNumberOfBandsGivesTheSamePicture() {
        // высота не делится на число полос, полос больше, чем строк, и одна строка
        val lut = BuiltinLut.slog3.make()
        val shapes = listOf(37 to 23, 8 to 3, 16 to 1, 1 to 16, 64 to 64, 100 to 17)
        val bandCounts = listOf(1, 2, 3, 4, 5, 7, 8, 16, 33)
        for ((w, h) in shapes) {
            val src = randomFrame(w, h, (w + 100 * h).toLong())
            for (o in FrameOrientation.entries) {
                val results = bandCounts.map { bands ->
                    PictureTransform(bands).use { transform ->
                        val display = IntArray(w * h)
                        val analysis = IntArray(w * h)
                        transform.apply(src, w, h, o, lut, display, analysis)
                        display to analysis
                    }
                }
                for ((i, result) in results.withIndex()) {
                    assertArrayEquals("$w×$h $o, полос ${bandCounts[i]}", results[0].first, result.first)
                    assertArrayEquals("$w×$h $o, полос ${bandCounts[i]}: analysis", results[0].second, result.second)
                }
            }
        }
    }

    @Test fun manyCallsInARowKeepTheThreadsInStep() {
        // тысячи кадров подряд, разные размеры, повороты, LUT и analysis: каждый совпадает с однополосным, зависаний нет
        val cubes = listOf(null, BuiltinLut.hlg.make(), ColorLut.identity(2), BuiltinLut.slog3cine.make(17))
        val random = Random(10)
        PictureTransform(1).use { single ->
            PictureTransform(4).use { multi ->
                repeat(3000) { i ->
                    val w = 1 + random.nextInt(70)
                    val h = 1 + random.nextInt(40)
                    val src = IntArray(w * h) { random.nextInt() or opaque }
                    val o = FrameOrientation.entries[random.nextInt(4)]
                    val lut = cubes[random.nextInt(cubes.size)]
                    val withAnalysis = random.nextBoolean()
                    val expectedDisplay = IntArray(w * h)
                    val expectedAnalysis = IntArray(w * h)
                    single.apply(src, w, h, o, lut, expectedDisplay, if (withAnalysis) expectedAnalysis else null)
                    val display = IntArray(w * h)
                    val analysis = IntArray(w * h)
                    multi.apply(src, w, h, o, lut, display, if (withAnalysis) analysis else null)
                    verify(expectedDisplay.contentEquals(display)) { "кадр $i: $w×$h $o, display расходится" }
                    verify(!withAnalysis || expectedAnalysis.contentEquals(analysis)) { "кадр $i: $w×$h $o, analysis расходится" }
                }
            }
        }
    }

    @Test fun callsMayComeFromDifferentThreadsOneAfterAnother() {
        // кадровый цикл может переезжать с потока на поток (корутины): подряд из разных потоков можно, одновременно нельзя
        val src = randomFrame(200, 100, 13)
        val lut = BuiltinLut.slog3.make()
        val want = IntArray(200 * 100)
        PictureTransform(1).use { it.apply(src, 200, 100, FrameOrientation.mirror, lut, want, null) }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(3)
        try {
            PictureTransform(4).use { transform ->
                val threads = HashSet<Long>()
                for (i in 0 until 90) {
                    val display = IntArray(200 * 100)
                    pool.submit(
                        Runnable {
                            synchronized(threads) { threads.add(Thread.currentThread().id) }
                            transform.apply(src, 200, 100, FrameOrientation.mirror, lut, display, null)
                        },
                    ).get()
                    verify(display.contentEquals(want)) { "кадр $i" }
                }
                println("PictureTransformTest: кадры считали потоки пула: ${threads.size}")
            }
        } finally {
            pool.shutdown()
        }
    }

    @Test fun cubeLargerThanTheLimitIsRefused() {
        PictureTransform(2).use { transform ->
            val display = IntArray(4)
            val src = IntArray(4)
            refused { transform.apply(src, 2, 2, FrameOrientation.off, ColorLut.identity(PictureTransform.MAX_LUT_SIZE + 1), display, null) }
            // а куб на самом пределе берётся, и после отказа объект работает
            val atLimit = ColorLut.identity(PictureTransform.MAX_LUT_SIZE)
            transform.apply(intArrayOf(0xFF102030.toInt(), -1, opaque, 0xFF808080.toInt()), 2, 2, FrameOrientation.off, atLimit, display, null)
            assertEquals(listOf(-1, opaque), listOf(display[1], display[2]))
            assertTrue(compareWithCube(atLimit, intArrayOf(0xFF102030.toInt(), -1, opaque, 0xFF808080.toInt()), display).worst <= 1)
        }
    }

    private fun workerThreads() = Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("PictureTransform-") }

    @Test fun workersAreStartedOnDemandAndStoppedByClose() {
        val before = workerThreads().map { it.id }.toSet()
        val transform = PictureTransform(4)
        assertEquals("до первого кадра потоков нет", before, workerThreads().map { it.id }.toSet())
        transform.apply(threeByTwo, 3, 2, FrameOrientation.off, null, IntArray(6), null)
        // три помощника, четвёртая полоса — в потоке вызова
        val mine = workerThreads().filter { it.id !in before }
        assertEquals(3, mine.size)
        assertTrue(mine.all { it.isDaemon })
        transform.close()
        transform.close() // второй раз ничего не делает
        val deadline = System.nanoTime() + 5_000_000_000L
        while (mine.any { it.isAlive } && System.nanoTime() < deadline) Thread.sleep(10)
        assertFalse("после close потоки не остались", mine.any { it.isAlive })
        // закрытым пользоваться нельзя
        try {
            transform.apply(threeByTwo, 3, 2, FrameOrientation.off, null, IntArray(6), null)
            throw AssertionError("Ожидалось IllegalStateException")
        } catch (e: IllegalStateException) {
            // так и должно быть
        }
    }

    @Test fun oneBandNeedsNoThreads() {
        val before = workerThreads().map { it.id }.toSet()
        PictureTransform(1).use { transform ->
            transform.apply(threeByTwo, 3, 2, FrameOrientation.mirror, BuiltinLut.hlg.make(2), IntArray(6), IntArray(6))
            assertEquals(before, workerThreads().map { it.id }.toSet())
        }
    }

    /**
     * Выделенные потоком байты (HotSpot: com.sun.management.ThreadMXBean). Через отражение: тесты собираются с android.jar,
     * где java.lang.management нет, а на JVM, где они идут, он есть. Нет счётчика — null.
     */
    private class AllocationCounter private constructor(private val bean: Any, private val read: java.lang.reflect.Method) {
        fun total(ids: LongArray): Long {
            val sizes = read.invoke(bean, ids) as LongArray
            var sum = 0L
            for (size in sizes) sum += size
            return sum
        }

        companion object {
            fun create(): AllocationCounter? = try {
                val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
                val sun = Class.forName("com.sun.management.ThreadMXBean")
                if (bean == null || !sun.isInstance(bean) || sun.getMethod("isThreadAllocatedMemorySupported").invoke(bean) != true) {
                    null
                } else {
                    AllocationCounter(bean, sun.getMethod("getThreadAllocatedBytes", LongArray::class.java))
                }
            } catch (e: ReflectiveOperationException) {
                null
            }
        }
    }

    @Test fun callsAllocateNothing() {
        // кадр за кадром без новых массивов и объектов: ни у вызывающего потока, ни у помощников. Меряем выделенные байты потоков
        val counter = AllocationCounter.create()
        assumeTrue("нет счётчика выделений", counter != null)
        val lut = BuiltinLut.slog3cine.make()
        val src = randomFrame(640, 360, 11)
        val display = IntArray(640 * 360)
        val analysis = IntArray(640 * 360)
        val before = workerThreads().map { it.id }.toSet()
        PictureTransform(4).use { transform ->
            fun frames(count: Int) {
                for (i in 0 until count) {
                    // все повороты, с LUT и без, с analysis и без — чтобы прогреть и измерить всё
                    val o = FrameOrientation.entries[i and 3]
                    transform.apply(src, 640, 360, o, if (i and 4 == 0) lut else null, display, if (i and 8 == 0) analysis else null)
                }
            }
            frames(400) // прогрев: JIT и таблица LUT
            val mine = workerThreads().map { it.id }.filter { it !in before }
            assertEquals(3, mine.size)
            val ids = (mine + Thread.currentThread().id).toLongArray()
            repeat(50) { counter!!.total(ids) } // прогрев самого замера: отражение сначала дороже
            val first = counter!!.total(ids)
            frames(200)
            val second = counter.total(ids)
            val third = counter.total(ids)
            // между вторым и третьим замером кадров нет — это цена одного замера, она есть и в разности первого и второго
            val spent = (second - first) - (third - second)
            println("PictureTransformTest: за 200 кадров выделено $spent байт (сам замер — ${third - second})")
            assertEquals("за 200 кадров выделено $spent байт", 0L, spent)
        }
    }

    // замер

    /**
     * Сколько миллисекунд на кадр 1024×576 с LUT S-Log3 Cine, зеркалом и analysis. Только на JVM: цель на планшете — не больше
     * 12 мс, и это число лишь сравнивают между правками:
     * ZV_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*PictureTransformTest' -i
     */
    @Test fun benchmark1024x576() {
        assumeTrue("ZV_BENCH не задан", System.getenv("ZV_BENCH") != null)
        val w = 1024
        val h = 576
        // кадр S-Log: коды 95…600 из 1023 — это 24…150 из 255, плавный градиент с шумом
        val random = Random(12)
        val src = IntArray(w * h) {
            val x = it % w
            val y = it / w
            val r = 24 + (x * 126 / w) + random.nextInt(6)
            val g = 24 + (y * 126 / h) + random.nextInt(6)
            val b = 24 + ((x + y) * 63 / (w + h)) + random.nextInt(6)
            opaque or (r shl 16) or (g shl 8) or b
        }
        val lut = BuiltinLut.slog3cine.make()
        val display = IntArray(w * h)
        val analysis = IntArray(w * h)
        class Case(val name: String, val bands: Int, val orientation: FrameOrientation, val lut: ColorLut?, val withAnalysis: Boolean)
        val cases = listOf(
            Case("slog3cine + зеркало + analysis, 4 полосы", 4, FrameOrientation.mirror, lut, true),
            Case("slog3cine + зеркало, 4 полосы", 4, FrameOrientation.mirror, lut, false),
            Case("slog3cine + зеркало, 1 полоса", 1, FrameOrientation.mirror, lut, false),
            Case("slog3cine без поворота, 4 полосы", 4, FrameOrientation.off, lut, false),
            Case("только зеркало, 4 полосы", 4, FrameOrientation.mirror, null, false),
            Case("только 180° + analysis, 4 полосы", 4, FrameOrientation.rotate180, null, true),
        )
        // первый кадр с новым LUT: к кадру добавляется одноразовая сборка таблицы (и сам куб считается отдельно)
        for (size in listOf(33, 65)) {
            val made = System.nanoTime()
            val cube = BuiltinLut.slog3cine.make(size)
            val makeMs = (System.nanoTime() - made) / 1e6
            PictureTransform(4).use { transform ->
                repeat(30) { transform.apply(src, w, h, FrameOrientation.mirror, lut, display, null) }
                val first = System.nanoTime()
                transform.apply(src, w, h, FrameOrientation.mirror, cube, display, null)
                val firstMs = (System.nanoTime() - first) / 1e6
                val next = System.nanoTime()
                transform.apply(src, w, h, FrameOrientation.mirror, cube, display, null)
                val nextMs = (System.nanoTime() - next) / 1e6
                println(
                    String.format(
                        Locale.ROOT, "куб %d³: make() %.1f мс; первый кадр с ним (таблица + кадр) %.2f мс; следующий кадр %.2f мс",
                        size, makeMs, firstMs, nextMs,
                    ),
                )
            }
        }
        for (case in cases) {
            PictureTransform(case.bands).use { transform ->
                val target = if (case.withAnalysis) analysis else null
                repeat(80) { transform.apply(src, w, h, case.orientation, case.lut, display, target) }
                val times = DoubleArray(300)
                for (i in times.indices) {
                    val start = System.nanoTime()
                    transform.apply(src, w, h, case.orientation, case.lut, display, target)
                    times[i] = (System.nanoTime() - start) / 1e6
                }
                times.sort()
                println(
                    String.format(
                        Locale.ROOT, "кадр 1024×576, %s: медиана %.2f мс, среднее %.2f мс, лучший %.2f мс, 95 %% — %.2f мс",
                        case.name, times[times.size / 2], times.average(), times[0], times[times.size * 95 / 100],
                    ),
                )
            }
        }
    }
}
