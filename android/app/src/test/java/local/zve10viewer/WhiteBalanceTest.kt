package local.zve10viewer

import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.settings.WhiteBalanceTemperature
import local.zve10viewer.settings.WhiteBalanceTemperature.Reading
import local.zve10viewer.sony.PropertyRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteBalanceTest {
    @Test fun temperatureReadingFollowsTheMode() {
        // ручная температура — значение камеры
        assertEquals(Reading.Manual(7400), WhiteBalanceTemperature.reading(0x8012, 7400))
        // пресет — примерное значение: камера отдаёт сохранённое ручное, а не температуру пресета
        assertEquals(Reading.Approximate(5600), WhiteBalanceTemperature.reading(4, 7400))
        assertEquals(Reading.Approximate(3200), WhiteBalanceTemperature.reading(6, 7400))
        assertEquals(Reading.Approximate(7000), WhiteBalanceTemperature.reading(0x8011, 7400))
        // автобаланс, под водой, свой — камера температуру не сообщает
        assertEquals(Reading.Automatic, WhiteBalanceTemperature.reading(2, 7400))
        assertEquals(Reading.Automatic, WhiteBalanceTemperature.reading(0x8030, 7400))
        assertEquals(Reading.Automatic, WhiteBalanceTemperature.reading(0x8020, 7400))
        assertEquals(Reading.Automatic, WhiteBalanceTemperature.reading(null, null))
    }

    @Test fun temperatureCaptionAndSliderPosition() {
        assertEquals("7400 K", WhiteBalanceTemperature.caption(Reading.Manual(7400)))
        assertEquals("≈ 5600 K", WhiteBalanceTemperature.caption(Reading.Approximate(5600)))
        assertEquals("авто", WhiteBalanceTemperature.caption(Reading.Automatic))
        // ползунок: ручное и пресет — на своём значении, авто — на последнем ручном
        assertEquals(7400L, WhiteBalanceTemperature.sliderValue(Reading.Manual(7400), 7400))
        assertEquals(5600L, WhiteBalanceTemperature.sliderValue(Reading.Approximate(5600), 7400))
        assertEquals(7400L, WhiteBalanceTemperature.sliderValue(Reading.Automatic, 7400))
        assertEquals(5600L, WhiteBalanceTemperature.sliderValue(Reading.Automatic, null))
    }

    @Test fun whiteBalanceLabelShowsKelvinInManualMode() {
        // в режиме «Цвет. темп.» в строке значений — сама температура, как на экране камеры
        assertEquals("7400 K", ValueFormatter.whiteBalanceLabel(0x8012, 7400))
        assertEquals("AWB", ValueFormatter.whiteBalanceLabel(2, 7400))
        assertEquals("Дневной", ValueFormatter.whiteBalanceLabel(4, 7400))
    }

    @Test fun temperatureMustFitTheCameraRange() {
        // проверяем до переключения в «Цвет. темп.»: иначе режим сменится, а температура не запишется
        val range = PropertyRange(2500, 9900, 100)
        assertTrue(WhiteBalanceTemperature.accepts(7400, range))
        assertTrue(WhiteBalanceTemperature.accepts(2500, range))
        assertTrue(WhiteBalanceTemperature.accepts(9900, range))
        assertFalse(WhiteBalanceTemperature.accepts(12345, range))
        assertFalse(WhiteBalanceTemperature.accepts(7450, range))
        // камера не сообщила диапазон — тот же, что у ZV-E10
        assertTrue(WhiteBalanceTemperature.accepts(5600, null))
        assertFalse(WhiteBalanceTemperature.accepts(10000, null))
    }
}

