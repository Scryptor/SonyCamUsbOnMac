package local.zve10viewer

import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FnSlot
import local.zve10viewer.settings.RecordingMode
import local.zve10viewer.settings.RecordingModes
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
        assertEquals("—", RecordingModes.label(null, 0x21))
    }

    @Test fun videoLayoutHasTheRecordingMode() {
        // в видеорежиме — режим записи вместо DRO в меню Fn и в конце строки значений
        assertEquals(listOf(SettingID.focusArea, SettingID.metering, SettingID.pictureProfile, SettingID.creativeStyle,
            SettingID.movieRecordingSetting).map { FnSlot.Setting(it) } + FnSlot.AllSettings, SettingCatalog.fnBottomRow(CaptureMode.video))
        assertEquals(SettingID.movieRecordingSetting, SettingCatalog.infoLine(CaptureMode.video).last())
        assertEquals(SettingID.fileFormat, SettingCatalog.infoLine(CaptureMode.photo).last())
    }
}
