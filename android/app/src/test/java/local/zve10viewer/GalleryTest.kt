package local.zve10viewer

import androidx.compose.ui.unit.IntSize
import local.zve10viewer.ui.shotKind
import local.zve10viewer.view.ShotDeletion
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

class ShotDeletionTest {
    @Test fun deletedShotGivesItsPlaceToTheOlderOne() {
        // пять снимков, удалили третий: на его место встал бывший четвёртый — его и показать
        assertEquals(2, ShotDeletion.neighbor(removing = 2, count = 5))
        // удалили самый старый — показать соседний новее
        assertEquals(3, ShotDeletion.neighbor(removing = 4, count = 5))
        assertEquals(0, ShotDeletion.neighbor(removing = 0, count = 2))
        assertEquals(0, ShotDeletion.neighbor(removing = 1, count = 2))
        // удалили единственный — показывать нечего
        assertEquals(null, ShotDeletion.neighbor(removing = 0, count = 1))
    }

    @Test fun restoredShotReturnsToItsPlaceByTime() {
        // новые сначала: снятый между вторым и третьим встаёт перед третьим
        val times = listOf(40L, 30L, 10L)
        assertEquals(2, ShotDeletion.restoreIndex(takenAt = 20, times = times))
        // пока висела «Вернуть», пришли новые снимки — удалённый встаёт после них
        assertEquals(1, ShotDeletion.restoreIndex(takenAt = 35, times = times))
        // новее всех — первым, старше всех и в пустой ленте — последним
        assertEquals(0, ShotDeletion.restoreIndex(takenAt = 50, times = times))
        assertEquals(3, ShotDeletion.restoreIndex(takenAt = 0, times = times))
        assertEquals(0, ShotDeletion.restoreIndex(takenAt = 0, times = emptyList()))
    }
}
