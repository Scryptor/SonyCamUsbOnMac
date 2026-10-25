package local.zve10viewer

import androidx.compose.ui.unit.IntSize
import local.zve10viewer.ui.shotKind
import org.junit.Assert.assertEquals
import org.junit.Test

class GalleryTest {
    @Test fun captionSaysWhatTheShotIs() {
        assertEquals("Стоп-кадр 1024×576", shotKind(true, listOf("Кадр 2026-10-25 15-25-06.jpg"), IntSize(1024, 576)))
        assertEquals("Снимок 6000×4000", shotKind(false, listOf("DSC01001.JPG"), IntSize(6000, 4000)))
        assertEquals("RAW + JPEG", shotKind(false, listOf("DSC01002.ARW", "DSC01002.JPG"), IntSize(6000, 4000)))
        // RAW без JPEG: размер неизвестен
        assertEquals("Снимок RAW", shotKind(false, listOf("DSC01003.ARW"), null))
    }
}
