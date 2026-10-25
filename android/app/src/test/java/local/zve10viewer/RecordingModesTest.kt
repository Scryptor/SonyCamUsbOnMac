package local.zve10viewer

import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FnSlot
import local.zve10viewer.settings.RecordingMode
import local.zve10viewer.settings.RecordingModeDriver
import local.zve10viewer.settings.RecordingModes
import local.zve10viewer.settings.RecordingStep
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingModesTest {
    @Test fun recordingModesListBothFormatsOfTheSameSystem() {
        // камера в 4K (PAL): свои параметры она перечисляет сама, HD — по таблице тех же 25/50/100p
        val modes = RecordingModes.options(listOf(8, 9), 8, listOf(0x21, 0x24))
        assertEquals(listOf("4K 25p 100M", "4K 25p 60M", "HD 100p 100M", "HD 100p 60M",
            "HD 50p 50M", "HD 50p 25M", "HD 25p 50M", "HD 25p 16M"), modes.map { it.label })
        // из HD — тот же список
        assertEquals(modes, RecordingModes.options(listOf(8, 9), 9, listOf(0x04, 0x19, 0x05, 0x1B, 0x1D, 0x1F)))
    }

    @Test fun recordingModesFollowNTSC() {
        val modes = RecordingModes.options(listOf(8, 9), 9, listOf(0x01, 0x02, 0x03, 0x18, 0x1A, 0x1C, 0x1E))
        assertEquals(listOf("4K 30p 100M", "4K 30p 60M", "4K 24p 100M", "4K 24p 60M"), modes.take(4).map { it.label })
        assertTrue(modes.any { it.label == "HD 120p 100M" })
        assertFalse(modes.any { it.label.contains("25p") })
    }

    @Test fun singleFormatCameraListsOnlyItsOwnSettings() {
        assertEquals(listOf("HD 100p 100M", "HD 50p 50M"), RecordingModes.options(listOf(9), 9, listOf(0x04, 0x1D)).map { it.label })
        assertTrue(RecordingModes.options(listOf(8, 9), null, emptyList()).isEmpty())
    }

    @Test fun recordingModeCodeRoundTripsAndLabels() {
        val mode = RecordingMode(9, 0x1D)
        assertEquals(mode, RecordingMode.of(mode.code))
        assertEquals("HD 100p 100M", mode.label)
        assertEquals("4K 25p 100M", RecordingModes.label(8, 0x21))
        // камера без свойства формата (старые модели) — подпись параметров как раньше
        assertEquals("25p 100M", RecordingModes.label(null, 0x21))
        assertEquals("—", RecordingModes.label(8, null))
    }

    @Test fun videoLayoutHasTheRecordingMode() {
        // в видеорежиме — режим записи вместо DRO в меню Fn и в конце строки значений
        assertEquals(listOf(SettingID.focusArea, SettingID.metering, SettingID.pictureProfile, SettingID.creativeStyle,
            SettingID.movieRecordingSetting).map { FnSlot.Setting(it) } + FnSlot.AllSettings, SettingCatalog.fnBottomRow(CaptureMode.video))
        assertEquals(SettingID.movieRecordingSetting, SettingCatalog.infoLine(CaptureMode.video).last())
        assertEquals(SettingID.fileFormat, SettingCatalog.infoLine(CaptureMode.photo).last())
    }

    private val fourKAllowed = listOf<Long>(0x21, 0x24)
    private val hdAllowed = listOf<Long>(0x04, 0x19, 0x05, 0x1B, 0x1D, 0x1F)

    @Test fun recordingStepLooksAtWhatTheCameraHasNow() {
        val hd25 = RecordingMode(9, 0x1B)
        // камера в 4K — сначала формат
        assertEquals(RecordingStep.WriteFormat(9), RecordingModes.nextStep(hd25, 8, 0x21, fourKAllowed))
        // формат уже HD, а параметры камера показывает прежние (код 4K) — пишем нужные
        assertEquals(RecordingStep.WriteSetting(0x1B), RecordingModes.nextStep(hd25, 9, 0x21, hdAllowed))
        // формат HD, а список параметров ещё от 4K — ждём
        assertEquals(RecordingStep.Wait, RecordingModes.nextStep(hd25, 9, 0x21, fourKAllowed))
        assertEquals(RecordingStep.Done, RecordingModes.nextStep(hd25, 9, 0x1B, hdAllowed))
        // камера без свойства формата — только параметры
        assertEquals(RecordingStep.WriteSetting(0x1B), RecordingModes.nextStep(hd25, null, 0x1D, hdAllowed))
    }

    @Test fun recordingModeDriverRetriesASkippedWriteThenGivesUp() {
        // камера отвечает «готово», но пропускает запись, пришедшую, пока она применяет прошлую, — повторяем, но не вечно
        val driver = RecordingModeDriver()
        driver.choose(RecordingMode(9, 0x1D))
        repeat(RecordingModeDriver.MAX_ATTEMPTS) {
            assertEquals(RecordingStep.WriteFormat(9), driver.next(8, 0x21, fourKAllowed))
        }
        assertEquals(null, driver.next(8, 0x21, fourKAllowed))
        assertEquals(null, driver.target)
    }

    @Test fun recordingModeDriverFollowsTheLatestChoice() {
        val driver = RecordingModeDriver()
        driver.choose(RecordingMode(8, 0x24))
        assertEquals(RecordingStep.WriteFormat(8), driver.next(9, 0x1D, hdAllowed))
        // передумали, пока камера переключалась в 4K (параметры она показывает прежние): формат обратно, потом параметры
        driver.choose(RecordingMode(9, 0x1B))
        assertEquals(RecordingStep.WriteFormat(9), driver.next(8, 0x1D, fourKAllowed))
        assertEquals(RecordingStep.WriteSetting(0x1B), driver.next(9, 0x1D, hdAllowed))
        assertEquals(null, driver.next(9, 0x1B, hdAllowed))
        assertEquals(null, driver.target)
    }

    @Test fun recordingModeDriverStartsCountingAgainAfterANewChoice() {
        val driver = RecordingModeDriver()
        driver.choose(RecordingMode(9, 0x1D))
        assertEquals(RecordingStep.WriteFormat(9), driver.next(8, 0x21, fourKAllowed))
        assertEquals(RecordingStep.WriteFormat(9), driver.next(8, 0x21, fourKAllowed))
        driver.choose(RecordingMode(9, 0x1B))
        repeat(RecordingModeDriver.MAX_ATTEMPTS) {
            assertEquals(RecordingStep.WriteFormat(9), driver.next(8, 0x21, fourKAllowed))
        }
        assertEquals(null, driver.next(8, 0x21, fourKAllowed))
    }

    @Test fun recordingModeDriverWaitsForTheCameraButNotForever() {
        val driver = RecordingModeDriver()
        driver.choose(RecordingMode(9, 0x1B))
        repeat(RecordingModeDriver.MAX_WAITS) {
            assertEquals(RecordingStep.Wait, driver.next(9, 0x21, fourKAllowed))
        }
        assertEquals(null, driver.next(9, 0x21, fourKAllowed))
    }

    @Test fun formatChoiceKeepsTheFrameRateWhenItCan() {
        val modes = RecordingModes.options(listOf(8, 9), 8, fourKAllowed)
        // 4K 25p 100M → HD: те же 25p с самым высоким битрейтом
        assertEquals("HD 25p 50M", RecordingModes.closest(RecordingMode(8, 0x21), 9, modes)?.label)
        // HD 100p → 4K: 100p в 4K нет — первый режим 4K
        assertEquals("4K 25p 100M", RecordingModes.closest(RecordingMode(9, 0x1D), 8, modes)?.label)
        // формат тот же — режим не меняется
        assertEquals("4K 25p 60M", RecordingModes.closest(RecordingMode(8, 0x24), 8, modes)?.label)
        assertEquals("HD 100p 100M", RecordingModes.closest(null, 9, modes)?.label)
    }

    @Test fun recordingModeLabelHidesParametersOfTheOtherFormat() {
        // после смены формата камера ещё показывает параметры прежнего: «HD 25p 100M» не бывает — только формат
        assertEquals("HD", RecordingModes.label(9, 0x21, hdAllowed))
        assertEquals("4K 25p 100M", RecordingModes.label(8, 0x21, fourKAllowed))
        // список параметров не пришёл — как раньше
        assertEquals("4K 25p 100M", RecordingModes.label(8, 0x21, emptyList()))
    }

    @Test fun recordingModeStepsFromTheLastRequest() {
        val modes = RecordingModes.options(listOf(8, 9), 8, listOf(0x21, 0x24))
        // быстрые шаги колесом считаются от последнего запроса, а не от того, что камера уже подтвердила
        val first = RecordingModes.neighbour(modes[0], modes, 1)
        assertEquals(modes[1], first)
        assertEquals(modes[2], RecordingModes.neighbour(first, modes, 1))
        assertEquals(null, RecordingModes.neighbour(modes.last(), modes, 1))
        assertEquals(null, RecordingModes.neighbour(null, modes, 1))
    }
}
