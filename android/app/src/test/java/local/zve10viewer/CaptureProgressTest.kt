package local.zve10viewer

import local.zve10viewer.session.CaptureFeedback
import local.zve10viewer.session.CapturePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

private const val MB = 1_048_576L

class CaptureProgressTest {
    @Test fun focusingAndExposingSpin() {
        assertEquals("Фокусировка…", CaptureFeedback.caption(CapturePhase.Focusing))
        assertNull(CaptureFeedback.fraction(CapturePhase.Focusing))
        assertEquals("Снимаю…", CaptureFeedback.caption(CapturePhase.Exposing))
        assertNull(CaptureFeedback.fraction(CapturePhase.Exposing))
    }

    @Test fun downloadShowsTheShareOfABigFile() {
        // 42,9 % от 8 МБ — «42 %»: проценты округляются вниз
        val phase = CapturePhase.Downloading(file = 1, files = 1, received = 3_600_000, expected = 8 * MB)
        assertEquals("Загрузка 42 %", CaptureFeedback.caption(phase))
        assertEquals(3_600_000.0 / (8 * MB), CaptureFeedback.fraction(phase)!!, 1e-12)
        // «100 %» — только когда пришло всё
        assertEquals("Загрузка 99 %", CaptureFeedback.caption(phase.copy(received = 8_388_000)))
        assertEquals("Загрузка 100 %", CaptureFeedback.caption(phase.copy(received = 8 * MB)))
        assertEquals("Загрузка 29 %", CaptureFeedback.caption(phase.copy(received = 29 * MB, expected = 100 * MB)))
    }

    @Test fun rawPlusJpegCountsFiles() {
        val phase = CapturePhase.Downloading(file = 2, files = 2, received = 3_600_000, expected = 8 * MB)
        assertEquals("Загрузка 2/2 · 42 %", CaptureFeedback.caption(phase))
        // доля неизвестна — без процентов, но с номером файла
        assertEquals("Загрузка 2/2…", CaptureFeedback.caption(phase.copy(expected = null)))
    }

    @Test fun smallOrUnknownFileSpins() {
        val small = CapturePhase.Downloading(file = 1, files = 1, received = 100_000, expected = 300 * 1024)
        assertEquals("Загрузка…", CaptureFeedback.caption(small))
        assertNull(CaptureFeedback.fraction(small))
        val unknown = CapturePhase.Downloading(file = 1, files = 1, received = 100_000, expected = null)
        assertEquals("Загрузка…", CaptureFeedback.caption(unknown))
        assertNull(CaptureFeedback.fraction(unknown))
    }

    @Test fun fractionNeverLeavesZeroToOne() {
        val over = CapturePhase.Downloading(file = 1, files = 1, received = 9 * MB, expected = 8 * MB)
        assertEquals(1.0, CaptureFeedback.fraction(over)!!, 1e-9)
        assertEquals("Загрузка 100 %", CaptureFeedback.caption(over))
    }
}
