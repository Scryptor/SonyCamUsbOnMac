package local.zve10viewer

import local.zve10viewer.debug.DebugFormat
import local.zve10viewer.debug.DebugReport
import local.zve10viewer.debug.DebugRow
import local.zve10viewer.debug.DebugTab
import local.zve10viewer.debug.EventJournal
import local.zve10viewer.debug.FrameWindow
import local.zve10viewer.debug.PropertyNames
import local.zve10viewer.debug.RttWindow
import local.zve10viewer.debug.clockTime
import local.zve10viewer.debug.percentile
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.LiveViewQualityPolicy
import local.zve10viewer.sony.PropertyRange
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyPropertyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

private fun prop(code: Int, current: PtpValue, type: Int = PtpType.UINT16, getSet: Int = 1, enabled: Int = 1,
                 range: PropertyRange? = null, settable: List<Long> = emptyList(), all: List<Long> = settable) =
    SonyProperty(code, type, getSet, enabled, current, range, settable, all)

class DebugPanelTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test fun journalKeepsTheLast200NewestFirst() {
        val journal = EventJournal(200)
        repeat(250) { journal.add(it.toLong(), "событие $it") }
        val entries = journal.newestFirst()
        assertEquals(200, entries.size)
        assertEquals("событие 249", entries.first().text)
        assertEquals("событие 50", entries.last().text)
        journal.clear()
        assertTrue(journal.newestFirst().isEmpty())
    }

    @Test fun journalTimeHasMilliseconds() {
        assertEquals("00:00:00.000", clockTime(0, utc))
        assertEquals("01:02:03.456", clockTime(3_723_456, utc))
        assertEquals("23:59:59.999", clockTime(86_399_999, utc))
    }

    @Test fun percentileMatchesTheMacApp() {
        val values = listOf(40.0, 10.0, 30.0, 20.0)
        assertEquals(10.0, percentile(values, 0.0)!!, 1e-9)
        assertEquals(30.0, percentile(values, 0.5)!!, 1e-9)
        assertEquals(40.0, percentile(values, 0.95)!!, 1e-9)
        assertEquals(40.0, percentile(values, 1.0)!!, 1e-9)
        assertNull(percentile(emptyList(), 0.5))
    }

    @Test fun probeRttIsLastMinAndAverageOverAMinute() {
        val window = RttWindow()
        assertNull(window.stats(0))
        window.add(0, 10)
        window.add(10_000, 4)
        window.add(30_000, 7)
        assertEquals(RttWindow.Stats(lastMs = 7, minMs = 4, averageMs = 7), window.stats(30_000))
        // первый замер старше минуты — не в счёт
        assertEquals(RttWindow.Stats(lastMs = 7, minMs = 4, averageMs = 6), window.stats(65_000))
        assertNull(window.stats(200_000))
    }

    @Test fun frameWindowGivesGapsFetchWaitsAndThroughput() {
        val window = FrameWindow()
        var t = 1_000L
        // 50 кадров по 40 мс, один — после паузы 300 мс; 72 КБ, запрос 20 мс, «кадр не готов» по разу
        repeat(50) { i ->
            window.add(t, 72 * 1024, 20.0 + i % 5, waits = 1)
            t += if (i == 30) 300 else 40
        }
        val now = t - 40
        val gaps = window.gaps(now)!!
        assertEquals(40L, gaps.p50)
        assertEquals(40L, gaps.p95)
        assertEquals(300L, gaps.max)
        val fetch = window.fetch(now)!!
        assertEquals(22L, fetch.p50)
        assertEquals(24L, fetch.p95)
        assertEquals(1.0, fetch.waitsPerFrame, 1e-9)
        // кадры идут ~2,2 с — поток считаем за это время, а не за все 5 с окна
        val span = now - 1_000
        assertEquals(50 * 72 * 1024 * 1000.0 / span, window.throughput(now)!!, 1.0)
        window.clear()
        assertNull(window.gaps(now))
        assertNull(window.throughput(now))
    }

    @Test fun numbersReadTheRussianWay() {
        assertEquals("0:07", DebugFormat.clock(7_000))
        assertEquals("12:34", DebugFormat.clock(754_000))
        assertEquals("1:02:03", DebugFormat.clock(3_723_000))
        assertEquals("0:00", DebugFormat.clock(-5))
        assertEquals("7,4 с", DebugFormat.seconds(7_400))
        assertEquals("14,2 Мбит/с", DebugFormat.mbit(1_775_000.0))
        assertEquals("−58", DebugFormat.minus(-58))
        assertEquals(listOf("1 значение", "3 значения", "5 значений", "11 значений", "21 значение", "104 значения"),
            listOf(1, 3, 5, 11, 21, 104).map(DebugFormat::values))
    }

    @Test fun wifiChannelComesFromTheFrequency() {
        assertEquals(1, DebugFormat.wifiChannel(2412))
        assertEquals(6, DebugFormat.wifiChannel(2437))
        assertEquals(13, DebugFormat.wifiChannel(2472))
        assertEquals(14, DebugFormat.wifiChannel(2484))
        assertEquals(36, DebugFormat.wifiChannel(5180))
        assertEquals(149, DebugFormat.wifiChannel(5745))
        assertEquals(1, DebugFormat.wifiChannel(5955))
        assertNull(DebugFormat.wifiChannel(900))
    }

    @Test fun propertyNamesPreferTheAppsOwnTitles() {
        assertEquals("ISO", PropertyNames.title(SonyProp.ISO))
        assertEquals("Баланс белого", PropertyNames.title(SonyProp.WHITE_BALANCE))
        // программа это свойство не показывает — имя по libgphoto2
        assertEquals("LiveViewImageQuality", PropertyNames.title(SonyProp.LIVE_VIEW_IMAGE_QUALITY))
        assertEquals("PriorityMode", PropertyNames.name(SonyProp.PRIORITY_MODE))
        assertEquals("—", PropertyNames.title(0xD2FF))
        assertNull(PropertyNames.name(0xD2FF))
    }

    @Test fun propertyRowShowsValueLabelWritabilityAndChoices() {
        val iso = prop(SonyProp.ISO, PtpValue.Num(200), PtpType.UINT32, settable = listOf(100, 200, 400), all = listOf(100, 200, 400, 800))
        assertEquals(DebugRow("0xD21E · ISO", "200 (0xC8) · 200 · можно менять · 3 из 4 значений"), DebugFormat.propertyRow(iso))
        val quality = prop(SonyProp.LIVE_VIEW_IMAGE_QUALITY, PtpValue.Num(2), PtpType.UINT8, settable = listOf(1, 2))
        assertEquals(DebugRow("0xD26A · LiveViewImageQuality", "2 · можно менять · 2 значения"), DebugFormat.propertyRow(quality))
        val temperature = prop(SonyProp.COLOR_TEMP, PtpValue.Num(5500), range = PropertyRange(2500, 9900, 100))
        assertEquals("5500 (0x157C) · 5500 K · можно менять · от 2500 до 9900, шаг 100", DebugFormat.propertyRow(temperature).value)
        assertEquals("только чтение", DebugFormat.propertyRow(prop(0xD215, PtpValue.Num(0), getSet = 0)).value.split(" · ")[1])
        assertEquals("недоступно", DebugFormat.propertyRow(prop(0xD215, PtpValue.Num(0), enabled = 0)).value.split(" · ")[1])
        assertEquals("менять нельзя сейчас", DebugFormat.propertyRow(prop(0xD215, PtpValue.Num(0), enabled = 2)).value.split(" · ")[1])
    }

    @Test fun textPropertiesAreNotShownExceptTheClock() {
        // в текстовом свойстве может оказаться что угодно — показываем только длину
        val unknown = prop(0xD278, PtpValue.Text("секрет"), PtpType.STRING, getSet = 0)
        assertEquals("текст, 6 симв. · только чтение", DebugFormat.propertyRow(unknown).value)
        val clock = prop(SonyProp.DATE_TIME_SET, PtpValue.Text("20261025T134500"), PtpType.STRING)
        assertTrue(DebugFormat.propertyRow(clock).value.startsWith("«20261025T134500»"))
    }

    @Test fun everyRealPropertyGetsARow() {
        val properties = SonyPropertyParser.parse(javaClass.getResource("/fixtures/props_settled.bin")!!.readBytes())
        val rows = properties.keys.sorted().map { DebugFormat.propertyRow(properties.getValue(it)) }
        assertEquals(93, rows.size)
        assertTrue(rows.all { it.label.matches(Regex("0x[0-9A-F]{4} · .+")) && it.value.isNotEmpty() })
        assertTrue(rows.first().label < rows.last().label)
    }

    @Test fun copiedTextHasEveryTabInOrder() {
        val report = DebugReport(mapOf(
            DebugTab.link to listOf(DebugRow("Подключение", "Wi-Fi"), DebugRow("Отклик камеры", "6 мс")),
            DebugTab.log to listOf(DebugRow("13:45:10.123", "подключился к 192.168.1.194")),
        ))
        val text = report.text("ZV-E10 Viewer 0.2.2")
        assertEquals(listOf(
            "ZV-E10 Viewer 0.2.2",
            "",
            "== Связь ==",
            "Подключение: Wi-Fi",
            "Отклик камеры: 6 мс",
            "",
            "== Превью ==",
            "",
            "== Камера ==",
            "",
            "== Свойства ==",
            "",
            "== Журнал ==",
            "13:45:10.123: подключился к 192.168.1.194",
        ), text.lines())
        assertFalse(report.rows(DebugTab.camera).any())
    }

    @Test fun upAttemptWaitsForTheHoldAndTheWindowAtLow() {
        val policy = LiveViewQualityPolicy(now = 0)
        var t = 0L
        while (t <= 5_200) {
            policy.frame(t, 72 * 1024, 400.0, LiveViewQuality.high)
            if (t == 5_200L) assertEquals(LiveViewQuality.low, policy.decide(t, outage = false))
            t += 400
        }
        assertNull(policy.upAttemptInMs(5_200))
        policy.frame(5_600, 34 * 1024, 20.0, LiveViewQuality.low)
        // выдержка до 20,2 с, окно на низком — до 15,6 с: попытка через 14,6 с
        assertEquals(14_600L, policy.upAttemptInMs(5_600))
        assertEquals(0L, policy.upAttemptInMs(25_000))
    }
}
