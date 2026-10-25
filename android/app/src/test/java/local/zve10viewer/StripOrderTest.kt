package local.zve10viewer

import local.zve10viewer.settings.CameraBook
import local.zve10viewer.settings.CameraLink
import local.zve10viewer.settings.CameraStrip
import local.zve10viewer.settings.KnownCamera
import local.zve10viewer.settings.WiFiAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Порядок плиток ленты — порядок знакомых камер: выбор главной его не меняет, перетаскивание меняет и запоминается. */
class StripOrderTest {
    private val now = 1_761_400_000_000L
    private val a = KnownCamera("aaaaaaaaaaaaaaaa", "A", "ZV-E10", host = "192.168.1.123", paired = true, inStrip = true)
    private val b = KnownCamera("bbbbbbbbbbbbbbbb", "B", "ZV-E10", host = "192.168.1.123", port = 15741, paired = true,
        inStrip = true)
    private val c = KnownCamera("cccccccccccccccc", "C", "ZV-E10", host = "192.168.1.123", port = 15742, paired = true,
        inStrip = true)
    /** Знакомые, но не в ленте: стоят в книге между камерами ленты. */
    private val x = KnownCamera("xxxxxxxxxxxxxxxx", "X", "ZV-E10", host = "192.168.1.50", paired = true)
    private val y = KnownCamera("yyyyyyyyyyyyyyyy", "Y", "ZV-E10")

    private fun CameraBook.names(): List<String> = cameras.map { it.name }

    @Test fun tilesFollowTheBookAndUnknownIdsGoLast() {
        val book = CameraBook(listOf(a, x, b, c))
        // главная (B) — на своём месте, а не первой
        assertEquals(listOf(a.id, b.id, c.id), book.stripOrder(listOf(b.id, c.id, a.id)))
        // id без записи — в конце, в том порядке, в каком пришли
        assertEquals(listOf(a.id, c.id, "новая", "ещё"), book.stripOrder(listOf("новая", c.id, "ещё", a.id)))
        assertEquals(emptyList<String>(), book.stripOrder(emptyList()))
    }

    @Test fun choosingTheMainMovesNoTile() {
        // главная — A, в ленте B и C; касание по C: A уходит в ленту, C — главная, ни одна запись не переставлена
        val book = CameraBook(listOf(a.copy(inStrip = false), b, c), preferred = a.id)
        val switched = book.withStrip(a.id, true).switchedTo(c.id, previous = a.id)
            .connected(null, "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.123", 15742)), listOf(c.id), now).first
        assertEquals(listOf("A", "B", "C"), switched.names())
        assertEquals(c.id, switched.preferred)
        assertEquals(listOf(a.id, b.id, c.id), switched.stripOrder(listOf(c.id, b.id, a.id)))
        // убрали из ленты и вернули — снова на своём месте
        assertEquals(listOf("A", "B", "C"), switched.withStrip(b.id, false).withStrip(b.id, true).names())
        // камера, которая подключилась впервые, — в конце
        val (more, d) = switched.connected("D", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.60")), emptyList(), now)
        assertEquals(listOf(a.id, b.id, c.id, d), more.withStrip(d, true).stripOrder(listOf(d, c.id, b.id, a.id)))
    }

    @Test fun movedDownLandsAfterTheTarget() {
        val book = CameraBook(listOf(a, b, c), preferred = b.id)
        val moved = book.moved(a.id, b.id)
        assertEquals(listOf("B", "A", "C"), moved.names())
        assertEquals(listOf("B", "C", "A"), book.moved(a.id, c.id).names())
        // предпочтённая — та же
        assertEquals(b.id, moved.preferred)
    }

    @Test fun movedUpLandsBeforeTheTarget() {
        val book = CameraBook(listOf(a, b, c))
        assertEquals(listOf("A", "C", "B"), book.moved(c.id, b.id).names())
        assertEquals(listOf("C", "A", "B"), book.moved(c.id, a.id).names())
    }

    @Test fun movedAcrossCamerasThatAreNotInTheStrip() {
        val book = CameraBook(listOf(a, x, b, y, c))
        val strip = listOf(a.id, b.id, c.id)
        // A на B (вниз): в ленте A за B
        val down = book.moved(a.id, b.id)
        assertEquals(listOf("X", "B", "A", "Y", "C"), down.names())
        assertEquals(listOf(b.id, a.id, c.id), down.stripOrder(strip))
        // C на B (вверх): в ленте C перед B
        val up = book.moved(c.id, b.id)
        assertEquals(listOf("A", "X", "C", "B", "Y"), up.names())
        assertEquals(listOf(a.id, c.id, b.id), up.stripOrder(strip))
        // C на A — через обе камеры не из ленты: в ленте C первой
        assertEquals(listOf(c.id, a.id, b.id), book.moved(c.id, a.id).stripOrder(strip))
    }

    @Test fun movedWithAnUnknownOrTheSameIdIsTheSameBook() {
        val book = CameraBook(listOf(a, b, c), preferred = a.id)
        assertSame(book, book.moved("нет такой", b.id))
        assertSame(book, book.moved(a.id, "нет такой"))
        assertSame(book, book.moved(b.id, b.id))
    }

    @Test fun orderSurvivesSavingAndLoading() {
        val book = CameraBook(listOf(a, x, b, c), preferred = c.id).moved(c.id, a.id).moved(x.id, b.id)
        assertEquals(listOf("C", "A", "B", "X"), book.names())
        // как в настройках: массив cameras, предпочтённая — отдельно
        val loaded = CameraBook.load(CameraBook.toJson(book.cameras), book.preferred, null, null)
        assertEquals(book, loaded)
        assertEquals(listOf(c.id, a.id, b.id), loaded.stripOrder(listOf(a.id, b.id, c.id)))
    }

    @Test fun theTopConnectedStripCameraTakesOver() {
        // лента сверху вниз: C, A, B; сеансы открывались в другом порядке
        val book = CameraBook(listOf(a, b, c)).moved(c.id, a.id)
        assertEquals(c.id, CameraStrip.successor(book, listOf(b.id, c.id)))
        assertEquals(a.id, CameraStrip.successor(book, listOf(b.id, a.id)))
        assertNull(CameraStrip.successor(book, emptyList()))
    }
}
