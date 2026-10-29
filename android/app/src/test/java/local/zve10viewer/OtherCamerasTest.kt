package local.zve10viewer

import local.zve10viewer.debug.PropertyNames
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FnSlot
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingGroup
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.sony.SonyEvent
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyPropertyParser
import local.zve10viewer.sony.SonyReferenceTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Другие камеры Sony: подписи списков от самой камеры (0x9215), данные объектива (0x9223), Creative Look вместо Creative
 * Style и подписи значений из справочника Sony. Те же проверки и числа, что в OtherCamerasTests для Mac. Файлы
 * display_strings.bin и lens_information.bin — одни и те же байты у обеих программ (их пишет
 * Tests/ZVCoreTests/Fixtures/make_other_cameras.py из scripts/fake_newer.py: то же отдаёт поддельная камера в профиле newer).
 */
class OtherCamerasTest {
    // MARK: - Сборка наборов для тестов

    private fun fixture(name: String): ByteArray =
        OtherCamerasTest::class.java.getResource("/fixtures/$name.bin")!!.readBytes()

    private fun le16(v: Int): ByteArray = byteArrayOf(v.toByte(), (v ushr 8).toByte())

    private fun le32(v: Long): ByteArray =
        byteArrayOf(v.toByte(), (v ushr 8).toByte(), (v ushr 16).toByte(), (v ushr 24).toByte())

    /** Набор Sony: u32 смещение до данных, u32 размер данных, сами данные. padding — лишние байты между заголовком и данными. */
    private fun dataset(body: ByteArray, offset: Long = 8, size: Long? = null, padding: Int = 0): ByteArray =
        le32(offset) + le32(size ?: body.size.toLong()) + ByteArray(padding) { 0xEE.toByte() } + body

    /** Список набора 0x9215: тип списка, тип данных PTP, пары «значение — строка». */
    private class StringList(val type: Long, val dataType: Int, val strings: List<Pair<Long, String>>)

    /** Данные набора 0x9215: у строки — размер с нулём и UTF-8 с нулём. */
    private fun stringLists(lists: List<StringList>): ByteArray {
        var body = le16(lists.size) + byteArrayOf(0, 0)
        for (list in lists) {
            body += le32(list.type) + le16(list.dataType) + le16(list.strings.size)
            for ((value, text) in list.strings) {
                val raw = text.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
                body += PtpValue.encode(value, list.dataType) + le16(raw.size) + raw
            }
        }
        return body
    }

    /** Данные набора 0x9223: версия ×100, таблицы из пар (положение, расстояние ×100). */
    private fun lensTables(tables: List<List<Pair<Long, Long>>>, version: Int = 100): ByteArray {
        var body = le16(version) + le16(tables.size)
        for (pairs in tables) {
            body += le16(pairs.size) + byteArrayOf(0, 0)
            for ((position, distance) in pairs) body += le32(position) + le32(distance)
        }
        return body
    }

    private fun lensTable(vararg pairs: Pair<Long, Long>): ByteArray = lensTables(listOf(pairs.toList()))

    /** Разбор обязан бросить именно PtpParseException: любое другое исключение (индекс, память) — ошибка теста. */
    private fun assertParseFails(message: String, block: () -> Unit) {
        try {
            block()
        } catch (e: PtpParseException) {
            return
        }
        fail(message)
    }

    // MARK: - Подписи от камеры (0x9215)

    @Test fun displayStringsOfTheNewerCameraAreParsed() {
        val strings = DisplayStrings.parse(fixture("display_strings"))
        assertEquals(0x13L, DisplayStrings.CREATIVE_LOOK_TYPE)
        assertEquals(setOf(0x13L, 0x15L), strings.lists.keys)
        assertEquals("FL", strings.lists[0x13L]!![0x0006L])
        assertEquals(2, strings.lists[0x15L]!!.size)
        // Creative Look: двенадцать готовых и шесть своих
        val looks = strings.lists[DisplayStrings.CREATIVE_LOOK_TYPE]!!
        assertEquals(18, looks.size)
        assertEquals(listOf("ST", "PT", "NT", "VV", "VV2", "FL", "IN", "SH", "BW", "SE", "FL2", "FL3"), (1L..12L).map { looks[it] })
        assertEquals((1..6).map { "Custom Look $it" }, (1L..6L).map { looks[0x100 + it] })
        // UTF-8 не из ASCII: размер строки в байтах, а не в символах
        assertEquals(mapOf(1L to "Человек", 2L to "Животное"), strings.lists[0x15L])
    }

    @Test fun displayStringsWithoutListsAreEmpty() {
        // ListTypeNum = 0: так камера отвечает на тип списка, которого у неё нет
        val strings = DisplayStrings.parse(dataset(stringLists(emptyList())))
        assertTrue(strings.lists.isEmpty())
        assertEquals(DisplayStrings(), strings)
    }

    @Test fun truncatedDisplayStringsThrow() {
        val data = fixture("display_strings")
        // любой обрезок набора — ошибка: заголовок обещает больше данных, чем пришло
        for (length in 0 until data.size) {
            assertParseFails("обрезок в $length байт") { DisplayStrings.parse(data.copyOf(length)) }
        }
    }

    @Test fun displayStringsWhoseContentsOutrunTheirSizeThrow() {
        // списков по счёту больше, чем есть
        val body = stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "ST"))))
        body[0] = 2
        assertParseFails("списков больше, чем есть") { DisplayStrings.parse(dataset(body)) }
        // размер в заголовке меньше данных: набор обрывается посреди строки
        val whole = stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "ST", 2L to "PT"))))
        assertParseFails("размер меньше данных") { DisplayStrings.parse(dataset(whole, size = (whole.size - 3).toLong())) }
        // размер в заголовке больше, чем пришло, хотя разобранное целиком помещается, — набор всё равно обрезан
        assertParseFails("размер на байт больше") { DisplayStrings.parse(dataset(whole, size = whole.size + 1L)) }
        assertParseFails("размер UInt32.max") { DisplayStrings.parse(dataset(whole, size = 0xFFFF_FFFFL)) }
        // строка длиннее оставшегося: размер "ST\0" вместо 3 байт говорит 9
        val cut = stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "ST"))))
        cut[cut.size - 5] = 9
        assertParseFails("строка длиннее оставшегося") { DisplayStrings.parse(dataset(cut)) }
    }

    @Test fun displayStringsDatasetHeaderIsRespected() {
        val body = stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "ST"))))
        val want = mapOf(0x13L to mapOf(1L to "ST"))
        // данные идут со смещения, а не сразу за заголовком
        assertEquals(want, DisplayStrings.parse(dataset(body, offset = 12, padding = 4)).lists)
        // всё, что за объявленным размером, набору не принадлежит
        assertEquals(want, DisplayStrings.parse(dataset(body) + byteArrayOf(1, 2, 3)).lists)
        // смещение внутрь самого заголовка — не набор; при смещении 0 «данные» были бы началом заголовка и читались бы как
        // пустой набор (число списков 0), поэтому проверяется и оно
        assertParseFails("смещение 4") { DisplayStrings.parse(dataset(body, offset = 4)) }
        assertParseFails("смещение 0") { DisplayStrings.parse(dataset(body, offset = 0)) }
        assertParseFails("смещение 7") { DisplayStrings.parse(dataset(body, offset = 7)) }
    }

    @Test fun displayStringValuesFollowTheirDataType() {
        val strings = DisplayStrings.parse(dataset(stringLists(listOf(
            StringList(1, PtpType.INT8, listOf(-1L to "a")),
            StringList(2, PtpType.UINT8, listOf(255L to "b")),
            StringList(3, PtpType.INT16, listOf(-2L to "c")),
            StringList(4, PtpType.UINT32, listOf(0xFFFF_FFFFL to "d")),
            StringList(5, PtpType.UINT64, listOf(-1L to "e")),    // на всю ширину: Long −1, как в SonyReferenceTables
        ))))
        assertEquals(mapOf(1L to mapOf(-1L to "a"), 2L to mapOf(255L to "b"), 3L to mapOf(-2L to "c"),
            4L to mapOf(0xFFFF_FFFFL to "d"), 5L to mapOf(-1L to "e")), strings.lists)
    }

    @Test fun displayStringListWithoutStringsStaysAsEmptyList() {
        // список без строк — не отсутствие списка: слияние должно уметь стереть им прежние подписи
        val strings = DisplayStrings.parse(dataset(stringLists(listOf(StringList(0x13, PtpType.UINT16, emptyList())))))
        assertEquals(mapOf(0x13L to emptyMap<Long, String>()), strings.lists)
    }

    @Test fun displayStringsDropOnlyTheTerminator() {
        // размер строки — вместе с нулём; пустая строка — один нуль или вообще ничего; нуля может и не быть
        var body = le16(1) + byteArrayOf(0, 0)
        body += le32(0x13) + le16(PtpType.UINT16) + le16(4)
        body += le16(1) + le16(1) + byteArrayOf(0)                      // "" — только нуль
        body += le16(2) + le16(0)                                       // "" — размер 0, байтов нет
        body += le16(3) + le16(2) + byteArrayOf(0x41, 0)                // "A"
        body += le16(4) + le16(2) + byteArrayOf(0x42, 0x43)             // "BC" — нуля нет
        assertEquals(mapOf(0x13L to mapOf(1L to "", 2L to "", 3L to "A", 4L to "BC")), DisplayStrings.parse(dataset(body)).lists)
    }

    @Test fun displayStringListOfAStringTypeCannotBeKeyed() {
        // значение-строка (0xFFFF) не годится в ключ Map<Long, String>
        var text = le16(1) + byteArrayOf(0, 0)
        text += le32(0x13) + le16(PtpType.STRING) + le16(1)
        text += byteArrayOf(0) + le16(1) + byteArrayOf(0)               // значение "" и строка списка
        try {
            DisplayStrings.parse(dataset(text))
            fail("список со строковым типом значений разобран")
        } catch (e: PtpParseException) {
            assertEquals("unsupported type ${PtpType.STRING}", e.message)
        }
        // тип данных, которого в PTP нет
        var unknown = le16(1) + byteArrayOf(0, 0)
        unknown += le32(0x13) + le16(0) + le16(1)
        unknown += byteArrayOf(0, 0, 0)
        try {
            DisplayStrings.parse(dataset(unknown))
            fail("список с неизвестным типом значений разобран")
        } catch (e: PtpParseException) {
            assertEquals("unsupported type 0", e.message)
        }
    }

    @Test fun mergeReplacesListsByType() {
        var strings = DisplayStrings(mapOf(0x13L to mapOf(1L to "ST", 2L to "PT"), 0x15L to mapOf(1L to "Человек")))
        strings = strings.merge(DisplayStrings(mapOf(0x13L to mapOf(3L to "NT"), 0x17L to mapOf(5L to "x"))))
        // список того же типа заменяется целиком, а не дополняется; остальные на месте
        assertEquals(mapOf(0x13L to mapOf(3L to "NT"), 0x15L to mapOf(1L to "Человек"), 0x17L to mapOf(5L to "x")), strings.lists)
        // пустой список от камеры тоже заменяет: подписей больше нет
        strings = strings.merge(DisplayStrings(mapOf(0x15L to emptyMap<Long, String>())))
        assertEquals(mapOf(0x13L to mapOf(3L to "NT"), 0x15L to emptyMap<Long, String>(), 0x17L to mapOf(5L to "x")), strings.lists)
        strings = strings.merge(DisplayStrings())
        assertEquals(3, strings.lists.size)
    }

    // MARK: - Данные объектива (0x9223)

    @Test fun lensInformationOfTheNewerCameraIsParsed() {
        val lens = LensInformation.parse(fixture("lens_information"))
        assertEquals(12, lens.pairs.size)
        assertTrue(lens.absoluteScale)                          // наибольшее положение 65535 > 100
        assertEquals(1, lens.tableCount)
        // двенадцать точек шкалы 0…65535 через 5958, последняя — край
        val positions = listOf(0.0, 5958.0, 11916.0, 17874.0, 23832.0, 29790.0, 35748.0, 41706.0, 47664.0, 53622.0, 59580.0, 65535.0)
        assertEquals(positions, lens.pairs.map { it.position })
        // расстояние в файле — ×100; последнее, 1 000 000 м, — «бесконечность»
        val meters = listOf(0.25, 0.30, 0.40, 0.50, 0.70, 1.0, 1.5, 2.0, 3.0, 5.0, 10.0, 1_000_000.0)
        assertEquals(meters, lens.pairs.map { it.meters })
    }

    @Test fun lensDistanceAtATablePointIsExact() {
        val lens = LensInformation.parse(fixture("lens_information"))
        assertEquals(0.30, lens.meters(5958.0))
        assertEquals(0.25, lens.meters(0.0))
        assertEquals(1_000_000.0, lens.meters(65535.0))
        for (pair in lens.pairs) assertEquals(pair.meters, lens.meters(pair.position))
        // и там, где a + (b − a) округлением не равно b: 545,21 м → 16 085,26 м — в точке таблицы ровно её значение
        val odd = LensInformation.parse(dataset(lensTable(0L to 54521L, 50L to 1_608_526L, 100L to 2_000_000L)))
        assertEquals(16085.26, odd.meters(50.0))
        assertEquals(545.21, odd.meters(0.0))
    }

    @Test fun lensDistanceBetweenPointsIsInterpolatedLinearly() {
        val lens = LensInformation.parse(fixture("lens_information"))
        // между 0 (0,25 м) и 5958 (0,30 м)
        assertEquals(0.275, lens.meters(2979.0)!!, 1e-9)
        assertEquals(0.2625, lens.meters(1489.5)!!, 1e-9)
        // между 11916 (0,40 м) и 17874 (0,50 м) — не по первому отрезку
        assertEquals(0.4 + 0.1 / 3, lens.meters(11916 + 5958.0 / 3)!!, 1e-9)
        // середина последнего отрезка, 10 м … 1 000 000 м, — полмиллиона метров, на шкале это «∞»
        val last = lens.meters((59580 + 65535) / 2.0)!!
        assertEquals(500_005.0, last, 1e-6)
        assertEquals("∞", LensInformation.label(last))
        // растёт вместе с положением
        var previous = 0.0
        for (step in 0..655) {
            val meters = lens.meters(step * 100.0)!!
            assertTrue("шаг $step: $meters < $previous", meters >= previous)
            previous = meters
        }
    }

    @Test fun lensDistanceOutsideTheTableIsTheNearestEnd() {
        val lens = LensInformation.parse(fixture("lens_information"))
        assertEquals(0.25, lens.meters(-10.0))
        assertEquals(1_000_000.0, lens.meters(70000.0))
        assertNull(lens.meters(Double.NaN))
    }

    @Test fun lensInformationWithSeveralTablesHasNoDistances() {
        // у зумов таблица может быть на каждое фокусное расстояние; какую брать — справочник не говорит
        val two = LensInformation.parse(dataset(lensTables(listOf(
            listOf(0L to 25L, 65535L to 100_000_000L),
            listOf(0L to 30L, 65535L to 50_000L),
        ))))
        assertEquals(2, two.tableCount)
        assertNull(two.meters(0.0))
        assertNull(two.meters(30000.0))
        // но таблицы целы — для панели отладки
        assertEquals(2, two.tables.size)
        assertEquals(listOf(0.30, 500.0), two.tables[1].map { it.meters })
        assertEquals(listOf(0.25, 1_000_000.0), two.pairs.map { it.meters })        // первая таблица
        assertTrue(two.absoluteScale)
    }

    @Test fun lensInformationWithoutPairsHasNoDistances() {
        val none = LensInformation.parse(dataset(lensTables(emptyList())))
        assertTrue(none.tableCount == 0 && none.pairs.isEmpty() && !none.absoluteScale)
        assertNull(none.meters(10.0))
        val empty = LensInformation.parse(dataset(lensTables(listOf(emptyList()))))
        assertTrue(empty.tableCount == 1 && empty.pairs.isEmpty())
        assertNull(empty.meters(10.0))
    }

    @Test fun lensScaleIsAbsoluteOnlyAboveOneHundred() {
        // 0…100, как у 0xD24C, — не абсолютная; 0…65535, как у 0xE043, — абсолютная
        assertFalse(LensInformation.parse(dataset(lensTable(0L to 25L, 100L to 500L))).absoluteScale)
        assertTrue(LensInformation.parse(dataset(lensTable(0L to 25L, 101L to 500L))).absoluteScale)
        // таблицы могут быть в разном порядке пар
        assertFalse(LensInformation.parse(dataset(lensTable(100L to 500L, 0L to 25L))).absoluteScale)
    }

    @Test fun lensPairsAreSortedByPosition() {
        val lens = LensInformation.parse(dataset(lensTable(65535L to 100_000_000L, 0L to 25L, 5958L to 30L)))
        assertEquals(listOf(0.0, 5958.0, 65535.0), lens.pairs.map { it.position })
        assertEquals(listOf(0.25, 0.30, 1_000_000.0), lens.pairs.map { it.meters })
        assertEquals(0.275, lens.meters(2979.0)!!, 1e-9)
    }

    @Test fun lensTablesWithOddPairsDoNotTrapTheInterpolation() {
        // одна пара — везде её расстояние
        val single = LensInformation.parse(dataset(lensTable(50L to 100L)))
        assertTrue(single.meters(0.0) == 1.0 && single.meters(50.0) == 1.0 && single.meters(99.0) == 1.0)
        // две пары с одним положением: не деление на ноль
        val twin = LensInformation.parse(dataset(lensTable(0L to 25L, 0L to 40L, 100L to 500L)))
        assertNotNull(twin.meters(0.0))
        val inside = twin.meters(50.0)!!
        assertTrue(inside.isFinite() && inside > 0.25 && inside < 5)
    }

    @Test fun equalPositionsKeepTheirOrderInTheFile() {
        // сортировка устойчива: пары с одним положением остаются в том порядке, в каком пришли
        val lens = LensInformation.parse(dataset(lensTable(100L to 500L, 0L to 25L, 0L to 40L, 0L to 30L)))
        assertEquals(listOf(0.25, 0.40, 0.30, 5.0), lens.pairs.map { it.meters })
        assertEquals(listOf(0.0, 0.0, 0.0, 100.0), lens.pairs.map { it.position })
    }

    @Test fun scaleIsJudgedOverAllTablesButPairsAreTheFirstOne() {
        val two = LensInformation.parse(dataset(lensTables(listOf(
            listOf(0L to 25L, 100L to 500L),
            listOf(0L to 25L, 65535L to 500L),
        ))))
        assertEquals(2, two.tableCount)
        assertTrue(two.absoluteScale)                            // во второй таблице наибольшее положение 65535
        assertEquals(listOf(0.0, 100.0), two.pairs.map { it.position })
        assertEquals(listOf(0.0, 65535.0), two.tables[1].map { it.position })
    }

    @Test fun truncatedLensInformationThrows() {
        val data = fixture("lens_information")
        for (length in 0 until data.size) {
            assertParseFails("обрезок в $length байт") { LensInformation.parse(data.copyOf(length)) }
        }
        // пар по счёту больше, чем есть
        val body = lensTable(0L to 25L)
        body[4] = 3
        assertParseFails("пар больше, чем есть") { LensInformation.parse(dataset(body)) }
        // таблиц по счёту больше, чем есть
        val tables = lensTable(0L to 25L)
        tables[2] = 2
        assertParseFails("таблиц больше, чем есть") { LensInformation.parse(dataset(tables)) }
        // размер в заголовке больше, чем пришло, хотя таблицы целы, — набор обрезан; меньше — обрывается посреди пары
        val whole = lensTable(0L to 25L, 65535L to 100_000_000L)
        assertParseFails("размер на байт больше") { LensInformation.parse(dataset(whole, size = whole.size + 1L)) }
        assertParseFails("размер UInt32.max") { LensInformation.parse(dataset(whole, size = 0xFFFF_FFFFL)) }
        assertParseFails("размер на байт меньше") { LensInformation.parse(dataset(whole, size = whole.size - 1L)) }
        // смещение внутрь заголовка; при смещении 0 набор читался бы как пустой (версия 0, таблиц 0)
        assertParseFails("смещение 6") { LensInformation.parse(dataset(lensTables(emptyList()), offset = 6)) }
        assertParseFails("смещение 0") { LensInformation.parse(dataset(lensTables(emptyList()), offset = 0)) }
    }

    @Test fun lensInformationHeaderAndVersionDoNotChangeTheParse() {
        val body = lensTables(listOf(listOf(0L to 25L, 65535L to 100_000_000L)), version = 310)
        val plain = LensInformation.parse(dataset(body))
        assertEquals(listOf(0.25, 1_000_000.0), plain.pairs.map { it.meters })
        assertEquals(plain, LensInformation.parse(dataset(body, offset = 16, padding = 8)))
        assertEquals(plain, LensInformation.parse(dataset(body) + byteArrayOf(9, 9)))
        // а разные данные — разные значения
        assertNotEquals(plain, LensInformation.parse(dataset(lensTable(0L to 25L, 65535L to 99_999_999L))))
        assertNotEquals(plain, LensInformation.parse(dataset(lensTable(0L to 25L, 65534L to 100_000_000L))))
        assertNotEquals(plain, LensInformation.parse(dataset(lensTable(0L to 25L))))
        assertNotEquals(plain, LensInformation.parse(dataset(lensTables(listOf(listOf(0L to 25L, 65535L to 100_000_000L), emptyList())))))
        // равные данные — равные хеши
        assertEquals(plain.hashCode(), LensInformation.parse(dataset(body)).hashCode())
    }

    @Test fun lensDistanceLabels() {
        assertEquals("0,35 м", LensInformation.label(0.35))
        assertEquals("0,5 м", LensInformation.label(0.5))
        assertEquals("1 м", LensInformation.label(1.0))
        assertEquals("1,2 м", LensInformation.label(1.2))
        assertEquals("12 м", LensInformation.label(12.0))
        assertEquals("∞", LensInformation.label(1_000_000.0))
        // сначала округление до показанной точности, потом выбор формата: «1,00 м», «10,0 м» и «100 м» не бывает
        assertEquals("1 м", LensInformation.label(0.996))
        assertEquals("10 м", LensInformation.label(9.99))
        assertEquals("99 м", LensInformation.label(99.4))
        assertEquals("∞", LensInformation.label(99.5))
        assertEquals("∞", LensInformation.label(100.0))
        // до 1 м — до двух цифр после запятой, до 10 м — до одной, дальше — целые; хвостовых нулей и запятой в конце нет
        assertEquals("0,25 м", LensInformation.label(0.25))
        assertEquals("0,3 м", LensInformation.label(0.3))
        assertEquals("0,2 м", LensInformation.label(0.2))
        assertEquals("0,05 м", LensInformation.label(0.05))
        assertEquals("0 м", LensInformation.label(0.004))
        assertEquals("0,01 м", LensInformation.label(0.006))
        assertEquals("2 м", LensInformation.label(2.0))
        assertEquals("2,5 м", LensInformation.label(2.5))
        assertEquals("9,9 м", LensInformation.label(9.9))
        assertEquals("9,9 м", LensInformation.label(9.94))
        assertEquals("10 м", LensInformation.label(9.96))
        assertEquals("10 м", LensInformation.label(10.0))
        assertEquals("10 м", LensInformation.label(10.4))
        assertEquals("11 м", LensInformation.label(10.6))
        assertEquals("50 м", LensInformation.label(50.0))
        assertEquals("99 м", LensInformation.label(99.0))
        // запятая — всегда, чей бы ни был язык системы
        assertFalse(LensInformation.label(2.5).contains("."))
    }

    @Test fun lensDistanceLabelOfAnythingThatIsNotADistance() {
        // ноль и всё, что меньше нуля или не число, — «0 м»; бесконечность — «∞»; ни «inf», ни «nan», ни «−» на экран не попадает
        assertEquals("0 м", LensInformation.label(0.0))
        assertEquals("0 м", LensInformation.label(-0.0))
        assertEquals("0 м", LensInformation.label(-1.0))
        assertEquals("0 м", LensInformation.label(-0.001))
        assertEquals("0 м", LensInformation.label(Double.NaN))
        assertEquals("0 м", LensInformation.label(Double.NEGATIVE_INFINITY))
        assertEquals("0 м", LensInformation.label(-Double.MAX_VALUE))
        assertEquals("∞", LensInformation.label(Double.POSITIVE_INFINITY))
        assertEquals("∞", LensInformation.label(Double.MAX_VALUE))
    }

    @Test fun lensDistanceLabelIsTheValueRoundedToWhatItShows() {
        // каждая тысячная метра от 0 до 120 м (с границами 0,995, 9,95 и 99,5): подпись — число, округлённое до показанной
        // точности (0,01 до 1 м, 0,1 до 10 м, 1 дальше), без хвостовых нулей, с запятой, не убывающее; «∞» — только от 99,5 м
        val problems = ArrayList<String>()
        val number = Regex("""\d+(,\d+)?""")
        var previous = 0.0
        var infinity = false
        for (thousandths in 0..120_000) {
            val meters = thousandths / 1000.0
            val label = LensInformation.label(meters)
            if (label == "∞") {
                if (meters < 99.5) problems += "$meters: ∞"
                infinity = true
                continue
            }
            if (infinity) problems += "$meters: число после ∞"
            if (!label.endsWith(" м")) {
                problems += "$meters: $label без « м»"
                continue
            }
            val text = label.dropLast(2)
            if (!number.matches(text)) {
                problems += "$meters: $label не число с запятой"
                continue
            }
            val shown = text.replace(',', '.').toDouble()
            val decimals = text.split(",").drop(1).firstOrNull()?.length ?: 0
            val precision = if (shown < 1) 0.005 else if (shown < 10) 0.05 else 0.5
            if (Math.abs(shown - meters) > precision + 1e-9) problems += "$meters: $label дальше половины шага"
            if (decimals > (if (shown < 1) 2 else if (shown < 10) 1 else 0)) problems += "$meters: $label лишний знак"
            if (decimals > 0 && text.endsWith("0")) problems += "$meters: $label хвостовой нуль"
            if (shown < previous) problems += "$meters: $label меньше предыдущего"
            previous = shown
        }
        assertTrue("${problems.size} отклонений, первые: ${problems.take(5)}", problems.isEmpty())
    }

    /** Округление — от нуля на половинке, как `rounded()` в Swift; kotlin.math.round и roundToInt идут к чётному. */
    @Test fun lensDistanceLabelRoundsHalfAwayFromZero() {
        // половинки, точно представимые в двоичной записи: до сотых, десятых и целых
        assertEquals("0,13 м", LensInformation.label(0.125))     // 12,5 сотых
        assertEquals("0,63 м", LensInformation.label(0.625))     // 62,5 сотых
        assertEquals("1,3 м", LensInformation.label(1.25))       // 12,5 десятых; к чётному было бы «1,2 м»
        assertEquals("2,3 м", LensInformation.label(2.25))       // 22,5 десятых; к чётному было бы «2,2 м»
        assertEquals("11 м", LensInformation.label(10.5))        // к чётному было бы «10 м»
        assertEquals("12 м", LensInformation.label(11.5))
        assertEquals("13 м", LensInformation.label(12.5))        // к чётному было бы «12 м»
        assertEquals("99 м", LensInformation.label(98.5))        // к чётному было бы «98 м»
        assertEquals("∞", LensInformation.label(99.5))
        // самое большое число меньше половинки округляется вниз; floor(x + 0,5) дал бы здесь 1
        assertEquals("0 м", LensInformation.label(0.004999999999999999))
        assertEquals("0,01 м", LensInformation.label(0.005))
    }

    // MARK: - Creative Look вместо Creative Style

    @Test fun creativeLookIsItsOwnSetting() {
        assertEquals(0xD0FA, SonyProp.CREATIVE_LOOK)
        assertEquals(0xD0FA, SettingID.creativeLook.code)
        assertEquals("Creative Look", SettingID.creativeLook.title)
        assertEquals("Look", SettingID.creativeLook.shortTitle)
        assertEquals(SettingID.creativeLook, SettingID.valueOf("creativeLook"))
        // у Creative Style свой код: значения совпадают числами, но значат разное
        assertEquals(0xD240, SettingID.creativeStyle.code)
    }

    @Test fun creativeLookTakesTheStyleSlotOnlyWhereTheCameraHasNoStyle() {
        assertEquals(SettingID.creativeLook, SettingCatalog.resolved(SettingID.creativeStyle, setOf(0xD0FA)))
        assertEquals(SettingID.creativeStyle, SettingCatalog.resolved(SettingID.creativeStyle, setOf(0xD240)))
        assertEquals(SettingID.creativeStyle, SettingCatalog.resolved(SettingID.creativeStyle, setOf(0xD240, 0xD0FA)))
        assertEquals(SettingID.creativeStyle, SettingCatalog.resolved(SettingID.creativeStyle, emptySet()))
        assertEquals(SettingID.creativeStyle, SettingCatalog.resolved(SettingID.creativeStyle, setOf(0xD0E1, 0xE042)))
        // подменяется только Creative Style
        for (id in SettingID.entries) {
            if (id == SettingID.creativeStyle) continue
            assertEquals("$id", id, SettingCatalog.resolved(id, setOf(0xD0FA)))
            assertEquals("$id", id, SettingCatalog.resolved(id, emptySet()))
        }
    }

    @Test fun creativeLookIsInNoGroupAndNoFnSlot() {
        // «Все настройки» показывают строку группы и без свойства у камеры: Creative Look встаёт на место Creative Style
        // через SettingCatalog.resolved, а у ZV-E10 строки с прочерком не появляется
        assertTrue(SettingGroup.entries.all { SettingID.creativeLook !in it.settings })
        val slots = SettingCatalog.fnTopRow + CaptureMode.entries.flatMap { SettingCatalog.fnBottomRow(it) }
        assertFalse(FnSlot.Setting(SettingID.creativeLook) in slots)
        for (mode in CaptureMode.entries) {
            assertFalse(SettingID.creativeLook in SettingCatalog.infoLine(mode))
        }
        // остальные настройки — по одному разу в группах, как было до Creative Look
        for (id in SettingID.entries) {
            if (id == SettingID.creativeLook) continue
            assertEquals("$id", 1, SettingGroup.entries.count { id in it.settings })
        }
    }

    @Test fun presetsCaptureCreativeLookOnlyFromACameraThatHasIt() {
        // в пресете Creative Look стоит сразу за Creative Style
        val order = PresetCatalog.order
        assertEquals(order.indexOf(SettingID.creativeStyle) + 1, order.indexOf(SettingID.creativeLook))
        val look = SonyProperty(SonyProp.CREATIVE_LOOK, PtpType.UINT16, 1, 1, PtpValue.Num(6), null, listOf(1L, 6L), listOf(1L, 6L))
        assertEquals(mapOf(SettingID.creativeLook to 6L), PresetCatalog.capture("Look", mapOf(SonyProp.CREATIVE_LOOK to look)).values)
        // у ZV-E10 свойства нет: пресет такой же, как был, — со Creative Style и без Creative Look
        val zv = SonyPropertyParser.parse(fixture("props_settled"))
        val captured = PresetCatalog.capture("ZV", zv)
        assertNull(captured.values[SettingID.creativeLook])
        assertTrue(captured.values.keys.all { it != SettingID.creativeLook })
        // всё остальное снимается, как раньше: каждая настройка каталога, которую камера даёт менять
        val before = PresetCatalog.order.filter { it != SettingID.creativeLook }
            .filter { id -> zv[id.code]?.let { it.isWritable && it.current.intValue != null } == true }
        assertEquals(before, captured.values.keys.toList())
        assertTrue(SettingID.creativeStyle in captured.values)
        // пресет со Creative Style на камере без него ничего не пишет в Creative Look
        assertTrue(PresetCatalog.changes(Preset("S", mapOf(SettingID.creativeStyle to 2L)), mapOf(SonyProp.CREATIVE_LOOK to look)).isEmpty())
        // окно пресетов строит строки по группам; Creative Look в них нет — на место стиля его ставит resolved
        assertTrue(PresetCatalog.groups.all { SettingID.creativeLook !in it.second })
    }

    // MARK: - Подписи значений

    @Test fun valueLabelPrefersTheCameraNames() {
        assertEquals("FL", ValueFormatter.label(SettingID.creativeLook, 6, mapOf(6L to "FL")))
        // в списке камеры этого значения нет — таблицы Sony
        assertEquals("FL(Film)", ValueFormatter.label(SettingID.creativeLook, 6, mapOf(1L to "ST")))
        assertEquals("FL(Film)", ValueFormatter.label(SettingID.creativeLook, 6, emptyMap()))
        assertEquals("FL(Film)", ValueFormatter.label(SettingID.creativeLook, 6, null))
        // название от камеры важнее и русской таблицы
        assertEquals("Camera AF-S", ValueFormatter.label(SettingID.focusMode, 2, mapOf(2L to "Camera AF-S")))
        // подпись по списку камеры берётся и из полного списка 0x9215
        assertEquals("Custom Look 2", ValueFormatter.label(SettingID.creativeLook, 0x0102, mapOf(0x0102L to "Custom Look 2")))
    }

    @Test fun valueLabelFallsBackToSonyReferenceTables() {
        assertEquals("FL(Film)", ValueFormatter.label(SettingID.creativeLook, 6))
        assertEquals("XAVC HS 4K", ValueFormatter.label(SettingID.movieFileFormat, 0x0B))
        // зона фокуса: этой точки нет ни в русских подписях, ни в ветке «слежение» — английское имя Sony, а не «#…»
        val area = ValueFormatter.label(SettingID.focusArea, 0x0106)
        assertEquals(SonyReferenceTables.valueName(0xD22C, 0x0106), area)
        assertFalse(area.startsWith("#"))
        // остальные ветки, которые раньше кончались на «#N»
        assertEquals(SonyReferenceTables.valueName(0xD23F, 11), ValueFormatter.label(SettingID.pictureProfile, 11))
        assertFalse(ValueFormatter.label(SettingID.pictureProfile, 11).startsWith("#"))
        assertEquals(SonyReferenceTables.valueName(0x500A, 0x8007), ValueFormatter.label(SettingID.focusMode, 0x8007))
        assertFalse(ValueFormatter.label(SettingID.focusMode, 0x8007).startsWith("#"))
        assertEquals(SonyReferenceTables.valueName(0x500E, 0x8008), ValueFormatter.label(SettingID.exposureProgram, 0x8008))
        assertFalse(ValueFormatter.label(SettingID.exposureProgram, 0x8008).startsWith("#"))
        assertEquals(SonyReferenceTables.valueName(0xD240, 7), ValueFormatter.label(SettingID.creativeStyle, 7))
        assertFalse(ValueFormatter.label(SettingID.creativeStyle, 7).startsWith("#"))
    }

    @Test fun valueLabelEndsInTheNumberOnlyWhenNothingKnowsTheValue() {
        assertEquals("#12345", ValueFormatter.label(SettingID.creativeLook, 12345))
        assertEquals("#12345", ValueFormatter.label(SettingID.focusArea, 12345))
        assertEquals("#48", ValueFormatter.label(SettingID.dro, 0x30))
        assertEquals("#12345", ValueFormatter.label(SettingID.pictureProfile, 12345))
        assertEquals("#99", ValueFormatter.label(SettingID.movieFileFormat, 99))
        // выдержка со знаменателем 0 — ветка выдержки тоже кончается на «#N»
        assertEquals("#65536", ValueFormatter.label(SettingID.shutter, 0x0001_0000))
        // и с названиями камеры, которых для этого значения нет
        assertEquals("#12345", ValueFormatter.label(SettingID.creativeLook, 12345, mapOf(6L to "FL")))
    }

    @Test fun russianLabelsStayAheadOfTheReference() {
        assertEquals("Точка L", ValueFormatter.label(SettingID.focusArea, 0x103))
        assertEquals("Слежение: точка L", ValueFormatter.label(SettingID.focusArea, 0x206))
        assertEquals("Широкая", ValueFormatter.label(SettingID.focusArea, 1))
        assertEquals("XAVC S 4K", ValueFormatter.label(SettingID.movieFileFormat, 8))
        assertEquals("Свой 1", ValueFormatter.label(SettingID.whiteBalance, 0x8020))
        assertEquals("Яркий", ValueFormatter.label(SettingID.creativeStyle, 2))
        assertEquals("Выкл", ValueFormatter.label(SettingID.dro, 1))
        assertEquals("DRO авто", ValueFormatter.label(SettingID.dro, 0x1F))
        assertEquals("Выкл", ValueFormatter.label(SettingID.pictureProfile, 0))
        assertEquals("PP10", ValueFormatter.label(SettingID.pictureProfile, 10))
        assertEquals("Мульти", ValueFormatter.label(SettingID.metering, 0x8001))
        assertEquals("Видео M", ValueFormatter.label(SettingID.exposureProgram, 0x0007_8053))
        assertEquals("JPEG", ValueFormatter.label(SettingID.fileFormat, 3))
        assertEquals("1:1", ValueFormatter.label(SettingID.aspectRatio, 4))
        // и с names, которых для этого значения нет
        assertEquals("Точка L", ValueFormatter.label(SettingID.focusArea, 0x103, mapOf(1L to "x")))
    }

    @Test fun noValueOfTheZveTenDumpIsShownAsANumber() {
        // допустимые, все и текущие значения настроек из дампа ZV-E10: у каждого есть подпись, «#N» нет ни у одного
        val properties = SonyPropertyParser.parse(fixture("props_settled"))
        var checked = 0
        for (id in SettingID.entries) {
            val property = properties[id.code] ?: continue
            val values = LinkedHashSet<Long>(property.settableValues + property.allValues)
            property.current.intValue?.let { values += it }
            for (value in values) {
                assertFalse("$id $value", ValueFormatter.label(id, value).startsWith("#"))
                checked++
            }
        }
        assertTrue("проверено $checked значений", checked > 150)
    }

    // MARK: - Имена свойств

    @Test fun propertyNamesFallBackToTheReference() {
        // что libgphoto2 знает, имя остаётся прежним
        assertEquals("CreativeStyle", PropertyNames.name(0xD240))
        assertEquals("DRangeOptimize", PropertyNames.name(0xD201))
        // коды только новых камер — из справочника Sony
        assertEquals("Creative Look", PropertyNames.name(0xD0FA))
        assertEquals("Focus Position Setting", PropertyNames.name(0xE042))
        assertEquals(SonyReferenceTables.propertyNames[0xE043], PropertyNames.name(0xE043))
        assertEquals(SonyReferenceTables.propertyNames[0xE086], PropertyNames.name(0xE086))
        assertNotNull(PropertyNames.name(0xE086))
        // нет нигде
        assertNull(PropertyNames.name(0x1234))
        // заголовок строки на панели: своё название программы, потом имя libgphoto2, потом справочник, потом прочерк
        assertEquals("Creative Look", PropertyNames.title(0xD0FA))
        assertEquals("Focus Position Setting", PropertyNames.title(0xE042))
        assertEquals("Творческий стиль", PropertyNames.title(0xD240))
        assertEquals("—", PropertyNames.title(0x1234))
    }

    // MARK: - Операции и коды

    @Test fun zveTenHasNoneOfTheNewOperations() {
        val info = DeviceInfo.parse(fixture("deviceinfo"))
        assertFalse(info.supports(0x9215))
        assertTrue(info.supports(0x9209))
        assertFalse(info.supports(SonyOp.GET_DISPLAY_STRING_LIST))
        assertFalse(info.supports(SonyOp.GET_VENDOR_CODE_VERSION))
        assertFalse(info.supports(SonyOp.GET_LENS_INFORMATION))
        assertTrue(info.supports(SonyOp.GET_ALL_EXT_PROP_INFO) && info.supports(SonyOp.CONNECT))
        // список операций остался списком, как был
        assertEquals(32, info.operations.size)
        assertTrue(info.isPcRemote)
    }

    @Test fun newerCameraListsTheNewOperations() {
        val info = DeviceInfo.parse(deviceInfo(listOf(0x9201, 0x9209, 0x9215, 0x9216, 0x9223)))
        assertTrue(info.supports(0x9215) && info.supports(0x9216) && info.supports(0x9223))
        assertFalse(info.supports(0x9202))
    }

    /** Минимальный набор DeviceInfo с заданным списком операций (как FakeCamera.deviceInfo на Mac). */
    private fun deviceInfo(operations: List<Int>): ByteArray {
        fun ptpString(s: String): ByteArray {
            if (s.isEmpty()) return byteArrayOf(0)
            var d = byteArrayOf((s.length + 1).toByte())
            for (c in s + "\u0000") d += le16(c.code)
            return d
        }
        var d = le16(100) + le32(0x11) + le16(100) + ptpString("") + le16(0)
        for (list in listOf(operations, emptyList(), emptyList(), emptyList(), emptyList())) {
            d += le32(list.size.toLong())
            for (code in list) d += le16(code)
        }
        for (s in listOf("Sony Corporation", "ZV-E10", "2.00", "0")) d += ptpString(s)
        return d
    }

    @Test fun newCodesAreTheSonyOnes() {
        assertEquals(0x9215, SonyOp.GET_DISPLAY_STRING_LIST)
        assertEquals(0x9216, SonyOp.GET_VENDOR_CODE_VERSION)
        assertEquals(0x9223, SonyOp.GET_LENS_INFORMATION)
        assertEquals(0xD0FA, SonyProp.CREATIVE_LOOK)
        assertEquals(0xD0E1, SonyProp.PP_GAMMA)
        assertEquals(0xD0E9, SonyProp.PP_COLOR_MODE)
        assertEquals(0xE042, SonyProp.FOCUS_POSITION_SETTING)
        assertEquals(0xE043, SonyProp.FOCUS_POSITION_CURRENT)
        assertEquals(0xE086, SonyProp.LENS_INFO_ENABLED)
        assertEquals(0xC20F, SonyEvent.DISPLAY_LIST_CHANGED)
        assertEquals(0xC218, SonyEvent.FOCUS_POSITION_RESULT)
        assertEquals(0xC21B, SonyEvent.LENS_INFO_CHANGED)
    }

    // MARK: - Фикстуры

    @Test fun fixtureCopiesMatchTheMacOnes() {
        // Tests/ZVCoreTests/Fixtures и android/app/src/test/resources/fixtures — одни и те же байты (их пишет make_other_cameras.py);
        // сверка видна, только если рядом с проектом Android лежит и проект Mac
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "Tests/ZVCoreTests/Fixtures").isDirectory) dir = dir.parentFile
        assumeTrue("рядом нет проекта для Mac", dir != null)
        for (name in listOf("display_strings", "lens_information")) {
            val mac = File(dir, "Tests/ZVCoreTests/Fixtures/$name.bin").readBytes()
            assertTrue("$name.bin", mac.contentEquals(fixture(name)))
        }
    }

    // MARK: - Только для Kotlin

    @Test fun mergeDoesNotChangeItsOperands() {
        val first = DisplayStrings(mapOf(0x13L to mapOf(1L to "ST")))
        val second = DisplayStrings(mapOf(0x13L to mapOf(2L to "PT"), 0x15L to mapOf(1L to "x")))
        val merged = first.merge(second)
        assertEquals(mapOf(0x13L to mapOf(1L to "ST")), first.lists)
        assertEquals(mapOf(0x13L to mapOf(2L to "PT"), 0x15L to mapOf(1L to "x")), second.lists)
        assertEquals(second, merged)
    }

    @Test fun invalidUtf8IsRepairedNotAnError() {
        // строка из одного байта 0xFF: в UTF-8 такого байта нет — подменяется знаком замены, набор не отвергается
        var body = le16(1) + byteArrayOf(0, 0)
        body += le32(0x13) + le16(PtpType.UINT16) + le16(1)
        body += le16(1) + le16(3) + byteArrayOf(0xFF.toByte(), 0x41, 0)
        assertEquals(mapOf(0x13L to mapOf(1L to "\uFFFDA")), DisplayStrings.parse(dataset(body)).lists)
    }

    @Test fun everyTrailingTerminatorIsDroppedButNotTheOnesInside() {
        // как на Mac: снимаются все нули в конце, нуль посреди строки остаётся; одни нули дают пустую строку
        var body = le16(1) + byteArrayOf(0, 0)
        body += le32(0x13) + le16(PtpType.UINT16) + le16(3)
        body += le16(1) + le16(4) + byteArrayOf(0x41, 0x42, 0, 0)       // "AB" и два нуля
        body += le16(2) + le16(4) + byteArrayOf(0x41, 0, 0x42, 0)       // "A\0B": нуль внутри остаётся
        body += le16(3) + le16(3) + byteArrayOf(0, 0, 0)                // одни нули
        assertEquals(mapOf(0x13L to mapOf(1L to "AB", 2L to "A\u0000B", 3L to "")), DisplayStrings.parse(dataset(body)).lists)
    }

    @Test fun twoListsOfOneTypeKeepTheLaterOne() {
        val body = stringLists(listOf(
            StringList(0x13, PtpType.UINT16, listOf(1L to "a", 2L to "b")),
            StringList(0x13, PtpType.UINT16, listOf(3L to "c")),
        ))
        assertEquals(mapOf(0x13L to mapOf(3L to "c")), DisplayStrings.parse(dataset(body)).lists)
        // и одно значение дважды в списке — последняя строка
        val twice = stringLists(listOf(StringList(0x13, PtpType.UINT16, listOf(1L to "a", 1L to "b"))))
        assertEquals(mapOf(0x13L to mapOf(1L to "b")), DisplayStrings.parse(dataset(twice)).lists)
    }

    @Test fun declaredCountsFarBeyondTheDataThrowWithoutAllocating() {
        // 65535 списков и 65535 таблиц на 4 байта данных: разбор останавливается на нехватке байт, а не на памяти
        assertParseFails("списков 65535") { DisplayStrings.parse(dataset(le16(0xFFFF) + byteArrayOf(0, 0))) }
        assertParseFails("таблиц 65535") { LensInformation.parse(dataset(le16(100) + le16(0xFFFF))) }
        // 65535 пар в таблице на 8 байт данных
        assertParseFails("пар 65535") { LensInformation.parse(dataset(le16(100) + le16(1) + le16(0xFFFF) + byteArrayOf(0, 0) + le32(1) + le32(1))) }
        // 65535 строк в списке и строка в 65535 байт
        assertParseFails("строк 65535") {
            DisplayStrings.parse(dataset(le16(1) + byteArrayOf(0, 0) + le32(0x13) + le16(PtpType.UINT16) + le16(0xFFFF)))
        }
        assertParseFails("строка 65535 байт") {
            DisplayStrings.parse(dataset(le16(1) + byteArrayOf(0, 0) + le32(0x13) + le16(PtpType.UINT16) + le16(1) + le16(1) + le16(0xFFFF)))
        }
    }

    @Test(timeout = 120_000) fun hostileDataIsParsedOrRejectedButNeverBreaksAnythingElse() {
        // искажённые наборы: случайные байты вместо данных, обрезки, большие счётчики. Годятся только два исхода — результат
        // или PtpParseException; ни индекса вне массива, ни нехватки памяти, ни зависания (на тест отведено две минуты)
        val random = Random(0x5EED)
        val samples = listOf(fixture("display_strings"), fixture("lens_information"))
        var parsed = 0
        var rejected = 0
        repeat(30_000) {
            val data = samples[random.nextInt(samples.size)].copyOf()
            when (random.nextInt(4)) {
                0 -> repeat(1 + random.nextInt(4)) { data[random.nextInt(data.size)] = random.nextInt(256).toByte() }
                1 -> repeat(1 + random.nextInt(3)) { data[random.nextInt(minOf(data.size, 24))] = 0xFF.toByte() }   // заголовок и счётчики
                2 -> random.nextBytes(data)
                else -> Unit
            }
            val cut = if (random.nextInt(3) == 0) data.copyOf(random.nextInt(data.size + 1)) else data
            try {
                val strings = DisplayStrings.parse(cut)
                strings.lists.values.forEach { list -> list.values.forEach { it.length } }
                parsed++
            } catch (e: PtpParseException) {
                rejected++
            }
            try {
                val lens = LensInformation.parse(cut)
                for (probe in doubleArrayOf(-1.0, 0.0, 50.0, 100.0, 30000.0, 65535.0, 1e300, Double.NaN)) {
                    val meters = lens.meters(probe)
                    if (meters != null) LensInformation.label(meters)
                }
                parsed++
            } catch (e: PtpParseException) {
                rejected++
            }
        }
        assertTrue("разобрано $parsed", parsed > 0)
        assertTrue("отвергнуто $rejected", rejected > 0)
    }
}
