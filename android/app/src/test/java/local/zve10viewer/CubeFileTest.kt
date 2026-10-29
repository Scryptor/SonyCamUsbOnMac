package local.zve10viewer

import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.CubeFile
import local.zve10viewer.monitor.CubeFile.Failure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * Разбор .cube и тексты ошибок — те же проверки, что у программы для Mac (CubeFileTests.swift), плюс то, чем Kotlin
 * отличается от Swift: числа только десятичные (Java принимает «1f», «NaN», «0x1p3»), цифры только ASCII, размер —
 * до Int64.
 */
class CubeFileTest {
    // помощники

    /** Строки данных тождественного куба, как их пишут программы (шесть знаков); красный меняется быстрее всех. */
    private fun identityRows(size: Int): List<String> {
        val steps = (0 until size).map { String.format(Locale.ROOT, "%.6f", it.toDouble() / (size - 1)) }
        val rows = ArrayList<String>(size * size * size)
        for (b in 0 until size) {
            for (g in 0 until size) {
                for (r in 0 until size) rows.add("${steps[r]} ${steps[g]} ${steps[b]}")
            }
        }
        return rows
    }

    private fun lines(items: List<String>, eol: String = "\n") = items.joinToString(eol)

    private fun near(a: Triple<Float, Float, Float>, b: Triple<Float, Float, Float>, eps: Float) =
        abs(a.first - b.first) <= eps && abs(a.second - b.second) <= eps && abs(a.third - b.third) <= eps

    /** Файл 2³ с заголовком из `header` и строками данных `rows` (по умолчанию — тождественный куб). */
    private fun smallCube(header: List<String> = listOf("LUT_3D_SIZE 2"), rows: List<String>? = null, eol: String = "\n") =
        lines(header + (rows ?: identityRows(2)), eol)

    /** Ошибка, с которой разбор отказал; любое другое исключение (и успех) — провал теста. */
    private fun failureOf(text: String): Failure {
        try {
            CubeFile.parse(text)
        } catch (f: Failure) {
            return f
        }
        fail("Ожидалась ошибка разбора, а куб принят")
        throw IllegalStateException()
    }

    private fun assertFails(expected: Failure, text: String, message: String = "") =
        assertEquals(message, expected, failureOf(text))

    // правильные файлы

    @Test fun smallCubeWithTitleCommentsAndBlankLinesParses() {
        val text = lines(listOf("# сделано вручную", "TITLE \"x\"", "", "# размер", "LUT_3D_SIZE 2", "") + identityRows(2) + listOf("", "# конец"))
        val lut = CubeFile.parse(text)
        assertEquals(2, lut.size)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.domainMax, 0f)
        assertEquals(ColorLut.identity(2), lut)
    }

    @Test fun domainMinAndMaxStretchTheInput() {
        val lut = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 2 2 2")))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), lut.domainMax, 0f)
        // 1 на домене 0…2 — середина куба
        assertTrue(near(lut.apply(1f, 1f, 1f), Triple(0.5f, 0.5f, 0.5f), 1e-6f))
        assertTrue(near(lut.apply(2f, 2f, 2f), Triple(1f, 1f, 1f), 1e-6f))
        // за доменом вход обрезается
        assertTrue(near(lut.apply(5f, -3f, 1f), Triple(1f, 0f, 0.5f), 1e-6f))
    }

    @Test fun domainMayStartBelowZeroAndDifferPerChannel() {
        val lut = CubeFile.parse(smallCube(listOf("DOMAIN_MIN -1 0 0.5", "DOMAIN_MAX 1 4 1.5", "LUT_3D_SIZE 2")))
        assertArrayEquals(floatArrayOf(-1f, 0f, 0.5f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 4f, 1.5f), lut.domainMax, 0f)
        assertTrue(near(lut.apply(0f, 1f, 0.75f), Triple(0.5f, 0.25f, 0.25f), 1e-6f))
    }

    @Test fun domainKeywordsMayComeInAnyOrder() {
        val lut = CubeFile.parse(smallCube(listOf("DOMAIN_MAX 3 3 3", "LUT_3D_SIZE 2", "DOMAIN_MIN 1 1 1")))
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), lut.domainMax, 0f)
    }

    @Test fun inputRangeSetsTheDomainOfAllThreeChannels() {
        val lut = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2", "LUT_3D_INPUT_RANGE 0.0 2.0 # на все каналы")))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(2f, 2f, 2f), lut.domainMax, 0f)
        assertTrue(near(lut.apply(1f, 1f, 1f), Triple(0.5f, 0.5f, 0.5f), 1e-6f))
        // тот же куб, что с DOMAIN_MIN 0 0 0 и DOMAIN_MAX 2 2 2
        val domain = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 2 2 2")))
        assertEquals(domain, lut)
    }

    @Test fun inputRangeMayStartBelowZeroAndComeBeforeTheSize() {
        val lut = CubeFile.parse(smallCube(listOf("LUT_3D_INPUT_RANGE -1 3", "LUT_3D_SIZE 2")))
        assertArrayEquals(floatArrayOf(-1f, -1f, -1f), lut.domainMin, 0f)
        assertArrayEquals(floatArrayOf(3f, 3f, 3f), lut.domainMax, 0f)
    }

    @Test fun inputRangeAndDomainLinesOverwriteEachOtherFieldByField() {
        // что позже, то и верно: диапазон ставит и нижнюю, и верхнюю границы, DOMAIN_* — свою
        val rangeThenMax = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2", "LUT_3D_INPUT_RANGE 0 2", "DOMAIN_MAX 4 5 6")))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), rangeThenMax.domainMin, 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), rangeThenMax.domainMax, 0f)
        val domainThenRange = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2", "DOMAIN_MIN 1 2 3", "DOMAIN_MAX 9 9 9", "LUT_3D_INPUT_RANGE 0 1")))
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), domainThenRange.domainMin, 0f)
        assertArrayEquals(floatArrayOf(1f, 1f, 1f), domainThenRange.domainMax, 0f)
    }

    @Test fun crlfAndBomDoNotGetInTheWay() {
        val header = listOf("# сделано вручную", "TITLE \"x\"", "LUT_3D_SIZE 2")
        val plain = CubeFile.parse(smallCube(header))
        val crlf = CubeFile.parse(smallCube(header, eol = "\r\n") + "\r\n")
        val bom = CubeFile.parse("\uFEFF" + smallCube(header))
        val both = CubeFile.parse("\uFEFF" + smallCube(header, eol = "\r\n") + "\r\n\r\n")
        val oldMac = CubeFile.parse(smallCube(header, eol = "\r"))
        assertEquals(ColorLut.identity(2), plain)
        assertEquals(plain, crlf)
        assertEquals(plain, bom)
        assertEquals(plain, both)
        assertEquals(plain, oldMac)
    }

    @Test fun commentAfterNumbersIsDropped() {
        val rows = identityRows(2).toMutableList()
        // и через пробел, и впритык к числу, и без пробела вовсе
        for (i in rows.indices) rows[i] = rows[i] + listOf(" # строка $i", "#r", "   #   0.5 0.5 0.5")[i % 3]
        val lut = CubeFile.parse(smallCube(listOf("LUT_3D_SIZE 2 # восемь строк"), rows))
        assertEquals(ColorLut.identity(2), lut)
    }

    @Test fun titleInQuotesIsJustIgnored() {
        // в названии бывают решётка, числа и даже ключевые слова
        for (title in listOf("x", "My # LUT", "LUT_3D_SIZE 99", "S-Log3 → 709 (v1.0, 33 точки)", "", "кавычка \" внутри")) {
            val lut = CubeFile.parse(smallCube(listOf("TITLE \"$title\"", "LUT_3D_SIZE 2")))
            assertEquals("TITLE \"$title\"", ColorLut.identity(2), lut)
        }
    }

    @Test fun valuesStayInFileOrderRedFastest() {
        // строка i — (i/10; i/10 + 0,01; i/10 + 0,02): по ней видно, какая строка к какому углу куба
        val rows = (0 until 8).map { i ->
            val v = i.toDouble() / 10
            "$v ${v + 0.01} ${v + 0.02}"
        }
        val lut = CubeFile.parse(smallCube(rows = rows))
        for (i in 0 until 8) {
            val r = (i % 2).toFloat()
            val g = ((i / 2) % 2).toFloat()
            val b = (i / 4).toFloat()
            val v = i.toFloat() / 10
            assertTrue("угол $i", near(lut.apply(r, g, b), Triple(v, v + 0.01f, v + 0.02f), 1e-6f))
        }
    }

    @Test fun numbersMayBeWrittenInAnyUsualWay() {
        // табуляция, лишние пробелы, знак плюс, порядок степени, точка без цифр с одной стороны
        val rows = listOf(
            "0\t0\t0", "  1.0   0.0 0.0  ", "+0.0 1e0 0", "1. 1.0 .0", "0 0 1E0", "1 0 1", ".0 1. 1", "1 1 +1.0e+0",
        )
        assertEquals(ColorLut.identity(2), CubeFile.parse(smallCube(rows = rows)))
    }

    @Test fun fileWithoutFinalNewlineOrWithBlankTailParses() {
        val plain = smallCube()
        assertEquals(ColorLut.identity(2), CubeFile.parse(plain))
        assertEquals(ColorLut.identity(2), CubeFile.parse(plain + "\n\n\n"))
        assertEquals(ColorLut.identity(2), CubeFile.parse("\n\n" + plain))
    }

    @Test fun otherKeywordsFromOtherProgramsAreSkipped() {
        val header = listOf("LUT_IN_VIDEO_RANGE", "SOME_FUTURE_KEYWORD 1 2 3", "LUT_3D_SIZE 2", "LUT_OUT_VIDEO_RANGE")
        assertEquals(ColorLut.identity(2), CubeFile.parse(smallCube(header)))
    }

    @Test fun sizeLimitsAreAccepted() {
        // 2 — самый маленький куб
        assertEquals(2, CubeFile.parse(smallCube()).size)
        // 65 — самый большой: заголовок принят, а данных нет — об этом и ошибка
        assertFails(Failure.Count(found = 0, expected = 65 * 65 * 65), "LUT_3D_SIZE 65\n")
    }

    @Test fun mediumCubeKeepsEveryValue() {
        val size = 17
        val lut = CubeFile.parse(lines(listOf("LUT_3D_SIZE $size") + identityRows(size)))
        val identity = ColorLut.identity(size)
        assertEquals(size, lut.size)
        assertEquals(identity.values.size, lut.values.size)
        var worst = 0f
        for (i in lut.values.indices) worst = max(worst, abs(lut.values[i] - identity.values[i]))
        // шесть знаков в файле — до 5·10⁻⁷ на число
        assertTrue("расхождение $worst", worst < 1e-6f)
    }

    // ошибки

    @Test fun oneDimensionalTableIsNotThreeD() {
        assertFails(Failure.NotThreeD, "LUT_1D_SIZE 1024\n0 0 0\n1 1 1\n")
        assertFails(Failure.NotThreeD, "TITLE \"x\"\nLUT_1D_SIZE 1024 # шейпер\n")
        // и если в файле есть оба
        assertFails(Failure.NotThreeD, smallCube(listOf("LUT_3D_SIZE 2", "LUT_1D_SIZE 16")))
    }

    @Test fun fileWithoutSizeIsNotThreeD() {
        // не .cube вовсе: пустой файл, одни комментарии, чужой текст, числа без заголовка, как в .3dl
        assertFails(Failure.NotThreeD, "")
        assertFails(Failure.NotThreeD, "# просто комментарий\n\n")
        assertFails(Failure.NotThreeD, "{\"lut\": 1}\n")
        assertFails(Failure.NotThreeD, "hello world\n0 0 0\n")
        assertFails(Failure.NotThreeD, "0 64 128 192 256 320\n0 0 0\n")
        assertFails(Failure.NotThreeD, lines(identityRows(2)))
    }

    @Test fun sizeOutsideTwoToSixtyFiveIsBad() {
        for (size in listOf<Long>(66, 67, 100, 1000, 1, 0, -3)) {
            assertFails(Failure.BadSize(size), smallCube(listOf("LUT_3D_SIZE $size")), "размер $size")
        }
    }

    @Test fun badSizeIsReportedBeforeAnyData() {
        // файла дальше может не быть вовсе — размер проверяется сразу, а не когда посчитаны строки
        assertFails(Failure.BadSize(66), "LUT_3D_SIZE 66")
    }

    @Test fun sizeIsAnInt64LikeOnTheMac() {
        // Int в Swift — 64 бита: большой, но целый размер — «размер вне 2…65», а не «не удалось прочитать числа»
        assertFails(Failure.BadSize(3_000_000_000L), "LUT_3D_SIZE 3000000000\n")
        assertFails(Failure.BadSize(Long.MAX_VALUE), "LUT_3D_SIZE 9223372036854775807\n")
        assertFails(Failure.BadSize(Long.MIN_VALUE), "LUT_3D_SIZE -9223372036854775808\n")
        assertFails(Failure.BadSize(66), "LUT_3D_SIZE +66\n")
        assertFails(Failure.Number(1), "LUT_3D_SIZE 9223372036854775808\n")
        assertFails(Failure.Number(1), "LUT_3D_SIZE -9223372036854775809\n")
        assertEquals("Размер LUT — 3 000 000 000, а нужен от 2 до 65", CubeFile.message(Failure.BadSize(3_000_000_000L)))
        assertEquals("Размер LUT — -9 223 372 036 854 775 808, а нужен от 2 до 65", CubeFile.message(Failure.BadSize(Long.MIN_VALUE)))
        // знак плюс — как у Int("+2") в Swift
        assertEquals(2, CubeFile.parse(smallCube(listOf("LUT_3D_SIZE +2"))).size)
    }

    @Test fun wrongRowCountIsReportedWithBothNumbers() {
        val rows = identityRows(2)
        assertFails(Failure.Count(found = 7, expected = 8), smallCube(rows = rows.dropLast(1)))
        assertFails(Failure.Count(found = 9, expected = 8), smallCube(rows = rows + "0 0 0"))
        assertFails(Failure.Count(found = 0, expected = 8), "LUT_3D_SIZE 2\n")
        // комментарии и пустые строки между данными в счёт не идут
        assertFails(Failure.Count(found = 7, expected = 8), smallCube(rows = rows.dropLast(1) + listOf("", "# дальше пусто", "   ")))
    }

    @Test fun notANumberInDataIsReportedWithItsLine() {
        val text = lines(
            listOf(
                "# сделано вручную", // 1
                "TITLE \"x\"", // 2
                "", // 3
                "LUT_3D_SIZE 2", // 4
                "0 0 0", // 5
                "abc 0 0", // 6
            ) + identityRows(2).drop(2),
        )
        assertFails(Failure.Number(6), text)
        // номер строки — строка файла, а не строка данных; CRLF, BOM и «\r» его не сдвигают
        assertFails(Failure.Number(6), text.replace("\n", "\r\n"))
        assertFails(Failure.Number(6), "\uFEFF" + text)
        assertFails(Failure.Number(6), text.replace("\n", "\r"))
    }

    @Test fun badDataRowIsReportedWithItsLine() {
        val rows = listOf(
            "abc 0 0", "0 abc 0", "0 0 abc", "abc", "0 0", "0 0 0 0", "nan 0 0", "NaN 0 0", "inf 0 0", "-inf 0 0", "0 0 infinity",
            "0,5 0 0", "0..5 0 0", "1e 0 0", "0x 0 0", "- 0 0", "++1 0 0", ". 0 0", "1e999 0 0", "0 0 0 abc", "0 0 0.5.5",
            // числа, которые принимают одни разборщики и не принимают другие, здесь не числа ни у кого
            "1f 0 0", "1d 0 0", "0x10 0 0", "0x1p3 0 0", "1_0 0 0", "0 \u0661 0", "1e+ 0 0", "e5 0 0",
            // и то, что принимает Java, а Swift нет: Infinity с большой буквы, знак после порядка, пробельные знаки не из .cube
            "Infinity 0 0", "-Infinity 0 0", "1e5f 0 0", "1e5D 0 0", "1.0F 0 0", "0 0 \u00A01", "0 0 1\u00A0", "\u0660 0 0",
            "+-1 0 0", "1-1 0 0", "1e1e1 0 0", "1e1.5 0 0", "123\u200B 0 0",
        )
        for (row in rows) {
            val text = lines(listOf("LUT_3D_SIZE 2", "0 0 0", row) + identityRows(2).drop(2))
            assertFails(Failure.Number(3), text, "строка «$row»")
        }
    }

    @Test fun brokenInputRangeLineIsReportedWithItsLine() {
        val rows = listOf(
            "LUT_3D_INPUT_RANGE", "LUT_3D_INPUT_RANGE 0", "LUT_3D_INPUT_RANGE 0 1 2", "LUT_3D_INPUT_RANGE a b",
            "LUT_3D_INPUT_RANGE 0 nan", "LUT_3D_INPUT_RANGE 0 1e999", "LUT_3D_INPUT_RANGE 0x0 1", "LUT_3D_INPUT_RANGE 0,0 1",
        )
        for (line in rows) {
            assertFails(Failure.Number(2), "LUT_3D_SIZE 2\n$line\n" + lines(identityRows(2)), line)
        }
    }

    @Test fun sizeLineAfterDataRowsIsAnErrorAtItsLine() {
        // LUT_3D_SIZE 2, десять строк, LUT_3D_SIZE 3 и ещё семнадцать. Строк всего 27 = 3³, но по первому размеру сохранено
        // было лишь 8, и куб из такого набора собрать нельзя: ошибка стоит на строке со вторым размером (12-й)
        val rows = identityRows(3)
        val text = lines(listOf("LUT_3D_SIZE 2") + rows.subList(0, 10) + "LUT_3D_SIZE 3" + rows.subList(10, 27))
        assertFails(Failure.Number(12), text)
    }

    @Test fun anySizeLineAfterDataRowsIsRejectedAtItsLine() {
        // все сочетания: первый размер a, n строк данных, второй размер b и остаток до b³ строк
        for (a in 2..4) {
            for (b in 2..5) {
                val rows = identityRows(b)
                for (n in 0..rows.size) {
                    val text = lines(listOf("LUT_3D_SIZE $a") + rows.subList(0, n) + "LUT_3D_SIZE $b" + rows.subList(n, rows.size))
                    if (n == 0) {
                        // данных ещё не было — действует последний размер
                        val lut = CubeFile.parse(text)
                        assertEquals("$a → $b", b, lut.size)
                        assertEquals("$a → $b", b * b * b * 3, lut.values.size)
                    } else {
                        assertFails(Failure.Number(n + 2), text, "$a, $n строк, $b")
                    }
                }
            }
        }
    }

    @Test fun repeatedSizeBeforeAnyDataLastOneWins() {
        val lut = CubeFile.parse(lines(listOf("LUT_3D_SIZE 3", "# передумали", "LUT_3D_SIZE 2") + identityRows(2)))
        assertEquals(ColorLut.identity(2), lut)
    }

    /** Случайные числа без сюрпризов — «seed 1», как в CubeFileTests.swift: те же файлы, что проверяет программа для Mac. */
    private class CubeRandom {
        private var seed = 1

        fun int(count: Int): Int {
            seed = seed * 1_664_525 + 1_013_904_223
            return ((seed ushr 8).toDouble() / 16_777_216.0 * count).toInt()
        }

        fun <T> pick(items: List<T>): T = items[int(items.size)]
    }

    /** Строки, из которых собираются случайные файлы: годные, битые и повторяющие заголовок. */
    private val fuzzLines = listOf(
        "LUT_3D_SIZE 2", "LUT_3D_SIZE 3", "LUT_3D_SIZE 4", "LUT_3D_SIZE 66", "LUT_1D_SIZE 8", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 1 1 1",
        "DOMAIN_MAX 2 2 2", "DOMAIN_MIN 1 1 1", "DOMAIN_MAX 0 0 0", "LUT_3D_INPUT_RANGE 0 1", "LUT_3D_INPUT_RANGE 1 0",
        "TITLE \"x\"", "# комментарий", "", "   ", "LUT_IN_VIDEO_RANGE", "0 0 0", "1 1 1", "0.5 0.25 0.75", "0 0", "abc 0 0",
        "0 0 0 0", "-1 2 3", "0 0 0", "1 0 1",
    )

    /**
     * Случайный файл: либо из чего попало, либо почти настоящий — первый размер, около target³ строк и одна вставка посреди
     * (чаще всего — второй размер target: строк как раз хватает на него).
     */
    private fun fuzzedCube(random: CubeRandom): String {
        if (random.int(2) == 0) {
            return lines((0 until (1 + random.int(40))).map { random.pick(fuzzLines) })
        }
        val first = 2 + random.int(3)
        val target = 2 + random.int(3)
        val rows = identityRows(target)
        val count = rows.size - 1 + random.int(3)
        val data = (0 until count).map { rows[it % rows.size] }.toMutableList()
        val twist = random.pick(
            listOf(
                "", "", "LUT_3D_SIZE $target", "LUT_3D_SIZE $target", "LUT_3D_SIZE $first", "DOMAIN_MAX 2 2 2",
                "LUT_3D_INPUT_RANGE 0 2", "# комментарий", "TITLE \"x\"", "0 0", "abc 0 0", "LUT_1D_SIZE 8",
            ),
        )
        if (twist.isNotEmpty()) data.add(random.int(count + 1), twist)
        return lines(listOf("LUT_3D_SIZE $first") + data)
    }

    @Test fun parseNeverFailsWithAnythingButFailureAndAlwaysReturnsAWholeCube() {
        // ничто из файла не должно дойти до проверок в ColorLut: разбор либо отказывает ошибкой Failure, либо отдаёт целый куб
        val random = CubeRandom()
        var parsed = 0
        var rejected = 0
        for (i in 0 until 4000) {
            val text = fuzzedCube(random)
            try {
                val lut = CubeFile.parse(text)
                parsed += 1
                assertEquals(lut.size * lut.size * lut.size * 3, lut.values.size)
                assertTrue(lut.domainMin.size == 3 && lut.domainMax.size == 3)
            } catch (e: Failure) {
                rejected += 1
            }
        }
        println("CubeFileTest: разобрано $parsed, отказов $rejected")
        // и успех, и отказ случаются не раз, иначе проверка ни о чём; у Mac на тех же файлах — 124 и 3 876
        assertTrue(parsed > 50)
        assertTrue(rejected > 1000)
        assertEquals(124, parsed)
        assertEquals(3876, rejected)
    }

    @Test fun badNumberBeatsRowCount() {
        // строк мало, и одна из них битая: говорим о битой — она найдена первой
        assertFails(Failure.Number(3), "LUT_3D_SIZE 2\n0 0 0\nx 0 0\n")
    }

    @Test fun brokenSizeLineIsReportedWithItsLine() {
        val sizeLines = listOf(
            "LUT_3D_SIZE", "LUT_3D_SIZE abc", "LUT_3D_SIZE 2.5", "LUT_3D_SIZE 2 2", "LUT_3D_SIZE 0x2", "LUT_3D_SIZE 99999999999999999999",
            // Kotlin: toInt() принял бы цифры других письменностей; Swift и мы — только ASCII
            "LUT_3D_SIZE \u0662", "LUT_3D_SIZE \uFF12", "LUT_3D_SIZE +", "LUT_3D_SIZE -", "LUT_3D_SIZE 2e0", "LUT_3D_SIZE 1_0",
        )
        for (line in sizeLines) {
            assertFails(Failure.Number(2), "# заголовок\n$line\n" + lines(identityRows(2)), line)
        }
    }

    @Test fun brokenDomainLineIsReportedWithItsLine() {
        val domainLines = listOf(
            "DOMAIN_MIN", "DOMAIN_MIN 0 0", "DOMAIN_MIN 0 0 0 0", "DOMAIN_MIN a b c", "DOMAIN_MIN 0 nan 0", "DOMAIN_MAX 1 1",
            "DOMAIN_MAX 1 1 1f", "DOMAIN_MAX 1e999 1 1", "DOMAIN_MIN 0x0 0 0",
        )
        for (line in domainLines) {
            assertFails(Failure.Number(2), "LUT_3D_SIZE 2\n$line\n" + lines(identityRows(2)), line)
        }
    }

    // числа: быстрый путь против Float.parseFloat

    /** Ожидаемое значение токена: десятичная запись по белому списку знаков, конечная; иначе null. */
    private fun reference(token: String): Float? {
        if (token.isEmpty() || !token.all { it in '0'..'9' || it == '+' || it == '-' || it == '.' || it == 'e' || it == 'E' }) return null
        val v = try {
            java.lang.Float.parseFloat(token)
        } catch (e: NumberFormatException) {
            return null
        }
        return if (v.isNaN() || v.isInfinite()) null else v
    }

    @Test fun numberGrammarIsPlainDecimalLikeParseFloat() {
        // случайные слова из знаков десятичной записи и из чужих (x, f, n, _, запятая…): первое число куба — слово, остальное годное
        val random = CubeRandom()
        val good = listOf(
            "0", "1", "0.5", "1e0", "0.333333", "-0", "+1", ".5", "5.", "1E-3", "0.0000000001", "16777216", "16777217", "123456789.123456789",
            "1e-45", "3.4028235e38", "3.4028236e38", "1e39", "0e999999999999", "1e-999999999999",
        )
        val alphabet = "0123456789012345678901234567890123456789+-.eE.eE-+xfdNnIi,"
        var accepted = 0
        var rejected = 0
        val tokens = ArrayList<String>()
        tokens.addAll(good)
        for (i in 0 until 6000) {
            val length = 1 + random.int(9)
            tokens.add(String(CharArray(length) { alphabet[random.int(alphabet.length)] }))
        }
        // и длинные десятичные записи, которым быстрый путь не по силам
        for (i in 0 until 500) {
            val digits = 12 + random.int(30)
            val text = String(CharArray(digits) { '0' + random.int(10) })
            tokens.add("0." + text)
            tokens.add(text.substring(0, 8) + "." + text.substring(8))
            tokens.add(text + "e-" + random.int(60))
        }
        for (token in tokens) {
            val text = lines(listOf("LUT_3D_SIZE 2", "$token 0 0") + identityRows(2).drop(1))
            val expected = reference(token)
            if (expected == null) {
                rejected += 1
                assertFails(Failure.Number(2), text, "слово «$token»")
            } else {
                accepted += 1
                val got = CubeFile.parse(text).values[0]
                // побитно: -0 и 0 тоже должны совпасть, как у parseFloat и Swift
                assertEquals("слово «$token»", java.lang.Float.floatToRawIntBits(expected), java.lang.Float.floatToRawIntBits(got))
            }
        }
        println("CubeFileTest: слов принято $accepted, отвергнуто $rejected")
        assertTrue(accepted > 1500)
        assertTrue(rejected > 1500)
    }

    // сообщения

    @Test fun messagesAreTheShippedTexts() {
        assertEquals("В файле 35 936 строк данных, а нужно 35 937", CubeFile.message(Failure.Count(found = 35936, expected = 35937)))
        assertEquals("Нужен 3D LUT в формате .cube", CubeFile.message(Failure.NotThreeD))
        assertEquals("Размер LUT — 66, а нужен от 2 до 65", CubeFile.message(Failure.BadSize(66)))
        assertEquals("В строке 12 не удалось прочитать числа", CubeFile.message(Failure.Number(12)))
    }

    @Test fun countMessageGroupsThousandsWithASpace() {
        assertEquals("В файле 7 строк данных, а нужно 8", CubeFile.message(Failure.Count(found = 7, expected = 8)))
        assertEquals("В файле 1 000 строк данных, а нужно 35 937", CubeFile.message(Failure.Count(found = 1000, expected = 35937)))
        assertEquals("В файле 274 624 строки данных, а нужно 274 625", CubeFile.message(Failure.Count(found = 274_624, expected = 274_625)))
        assertEquals("В файле 1 000 000 строк данных, а нужно 274 625", CubeFile.message(Failure.Count(found = 1_000_000, expected = 274_625)))
        assertEquals("Размер LUT — 1 000 000, а нужен от 2 до 65", CubeFile.message(Failure.BadSize(1_000_000)))
        assertEquals("Размер LUT — -3, а нужен от 2 до 65", CubeFile.message(Failure.BadSize(-3)))
        // обычный пробел (U+0020), как в тексте спецификации
        assertFalse(CubeFile.message(Failure.Count(found = 35936, expected = 35937)).contains('\u00A0'))
    }

    @Test fun countMessageAgreesTheWordForRowsWithTheNumber() {
        fun word(found: Int) = CubeFile.message(Failure.Count(found, 8)).removePrefix("В файле ").substringBefore(" данных")
        assertEquals("0 строк", word(0))
        assertEquals("1 строка", word(1))
        assertEquals("2 строки", word(2))
        assertEquals("4 строки", word(4))
        assertEquals("5 строк", word(5))
        assertEquals("11 строк", word(11))
        assertEquals("12 строк", word(12))
        assertEquals("14 строк", word(14))
        assertEquals("21 строка", word(21))
        assertEquals("22 строки", word(22))
        assertEquals("25 строк", word(25))
        assertEquals("101 строка", word(101))
        assertEquals("111 строк", word(111))
        assertEquals("112 строк", word(112))
        assertEquals("35 936 строк", word(35936))
    }

    @Test fun everyFailureHasADistinctMessage() {
        val failures = listOf(Failure.NotThreeD, Failure.BadSize(66), Failure.Count(found = 1, expected = 8), Failure.Number(3))
        val texts = failures.map { CubeFile.message(it) }
        assertEquals(failures.size, texts.toSet().size)
        assertTrue(texts.all { it.isNotEmpty() })
    }

    @Test fun failuresCompareByTheirNumbersAndCarryNoStackTrace() {
        // ошибка — значение: равна себе по числам, а стек не собирается (разбор отказывает часто и по делу)
        assertEquals(Failure.Count(1, 8), Failure.Count(1, 8))
        assertNotEquals(Failure.Count(1, 8), Failure.Count(2, 8))
        val number: Failure = Failure.Number(3)
        val size: Failure = Failure.BadSize(3)
        assertNotEquals(number, size)
        assertEquals(0, Failure.Number(3).stackTrace.size)
        assertEquals(0, Failure.NotThreeD.stackTrace.size)
    }

    // скорость

    /**
     * Куб 65³ — самый большой из принимаемых, 274 625 строк. Разбор — меньше секунды (на JVM; на планшете — тот же порядок):
     * ZV_BENCH=1 ./gradlew :app:testDebugUnitTest --tests '*CubeFileTest' -i
     */
    @Test fun cubeParseBenchmark() {
        assumeTrue("ZV_BENCH не задан", System.getenv("ZV_BENCH") != null)
        val size = 65
        val rows = identityRows(size)
        // обычный файл и самый неудобный: CRLF, BOM, заголовок с комментариями и комментарий после каждой строки
        val plain = lines(listOf("LUT_3D_SIZE $size") + rows + "")
        val header = listOf("# сделано вручную", "TITLE \"тяжёлый # куб\"", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 1 1 1", "LUT_3D_SIZE $size")
        val commented = rows.map { "$it # r g b" }
        val heavy = "\uFEFF" + lines(header + commented + "", "\r\n")
        for ((name, text) in listOf("LF" to plain, "CRLF + BOM + комментарии" to heavy)) {
            for (run in 1..3) {
                val start = System.nanoTime()
                val lut = CubeFile.parse(text)
                val elapsed = (System.nanoTime() - start) / 1e9
                println(String.format(Locale.ROOT, "куб 65³, %s, %.1f МБ, прогон %d: %.0f мс", name, text.length / 1e6, run, elapsed * 1000))
                assertEquals(size, lut.size)
                assertEquals(size * size * size * 3, lut.values.size)
                // первый прогон на холодной JVM медленнее; предел — тот же, что у Mac
                assertTrue("$elapsed с", elapsed < 1.0)
            }
        }
        // и результат тот же, что у тождественного куба
        val lut = CubeFile.parse(plain)
        val identity = ColorLut.identity(size)
        var worst = 0f
        for (i in lut.values.indices) worst = max(worst, abs(lut.values[i] - identity.values[i]))
        assertTrue(worst < 1e-6f)
    }
}
