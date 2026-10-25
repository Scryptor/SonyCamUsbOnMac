package local.zve10viewer

import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.settings.CameraBook
import local.zve10viewer.settings.CameraLink
import local.zve10viewer.settings.CameraSighting
import local.zve10viewer.settings.CameraState
import local.zve10viewer.settings.CameraStrip
import local.zve10viewer.settings.CurrentCamera
import local.zve10viewer.settings.KnownCamera
import local.zve10viewer.settings.WiFiAddress
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStripTest {
    private val now = 1_761_400_000_000L
    private val a = KnownCamera("aaaaaaaaaaaaaaaa", "A", "ZV-E10", host = "192.168.1.123", paired = true, lastUsed = 30, inStrip = true)
    private val b = KnownCamera("bbbbbbbbbbbbbbbb", "B", "ZV-E10", host = "192.168.1.123", port = 15741, paired = true, lastUsed = 20,
        inStrip = true)
    private val c = KnownCamera("cccccccccccccccc", "C", "ZV-E10", host = "192.168.1.123", port = 15742, paired = true, lastUsed = 10)

    private fun wifi(address: WiFiAddress, print: String? = null, at: Long = now) =
        CameraSighting(CameraLink.WiFi(address), null, print, at)

    @Test fun stripIsStoredOnlyWhenSet() {
        val json = CameraBook.toJson(listOf(a, c))
        val items = JSONArray(json)
        assertTrue(items.getJSONObject(0).getBoolean("strip"))
        // не в ленте — поля нет: запись та же, что у прежних версий
        assertFalse(items.getJSONObject(1).has("strip"))
        assertEquals(listOf(a, c), CameraBook.fromJson(json))
        // поле из более новой версии рядом не мешает
        val future = """[{"id":"x","name":"X","model":"ZV-E10","paired":true,"strip":true,"tally":"red"}]"""
        assertTrue(CameraBook.fromJson(future).single().inStrip)
    }

    @Test fun withStripTurnsTheFlagOnAndOff() {
        val book = CameraBook(listOf(a.copy(inStrip = false), c))
        val added = book.withStrip(a.id, true)
        assertTrue(added.find(a.id)!!.inStrip)
        assertEquals(book, book.withStrip("нет такой", true))
        assertFalse(added.withStrip(a.id, false).find(a.id)!!.inStrip)
        // подключение камеры (новый адрес, lastUsed) ленту не снимает
        val (after, _) = added.connected("SERIAL", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.50")), listOf(a.id), now)
        assertTrue(after.cameras.first { it.name == "A" }.inStrip)
    }

    @Test fun plannerConnectsStripCamerasThatAreNotOnTheConnection() {
        val book = CameraBook(listOf(a, b, c))
        // главная — A: стучимся к B (C не в ленте)
        assertEquals(listOf(b to WiFiAddress("192.168.1.123", 15741)),
            CameraStrip.due(book, a.id, emptySet(), setOf(WiFiAddress("192.168.1.123")), emptyMap(), emptyList(), now))
        // B уже на связи или подключается — никого
        assertEquals(emptyList<Any>(), CameraStrip.due(book, a.id, setOf(b.id), emptySet(), emptyMap(), emptyList(), now))
        // несопряжённую и без адреса — нет
        val unpaired = CameraBook(listOf(a, b.copy(paired = false), c.copy(inStrip = true, host = null)))
        assertEquals(emptyList<Any>(), CameraStrip.due(unpaired, a.id, emptySet(), emptySet(), emptyMap(), emptyList(), now))
    }

    @Test fun plannerWaitsTenSecondsBetweenTries() {
        val book = CameraBook(listOf(a, b))
        val tried = mapOf(b.id to now - 9_000)
        assertEquals(emptyList<Any>(), CameraStrip.due(book, a.id, emptySet(), emptySet(), tried, emptyList(), now))
        assertEquals(listOf(b.id), CameraStrip.due(book, a.id, emptySet(), emptySet(), tried, emptyList(), now + 1_000).map { it.first.id })
    }

    @Test fun plannerKeepsFourCamerasAtMost() {
        val more = (1..5).map { KnownCamera("d$it".padEnd(16, '0'), "D$it", "ZV-E10", host = "10.0.0.$it", paired = true, inStrip = true) }
        val book = CameraBook(listOf(a) + more)
        // главная и три в ленте
        assertEquals(3, CameraStrip.due(book, a.id, emptySet(), emptySet(), emptyMap(), emptyList(), now).size)
        assertEquals(1, CameraStrip.due(book, a.id, setOf(more[0].id, more[1].id), emptySet(), emptyMap(), emptyList(), now).size)
        assertEquals(0, CameraStrip.due(book, a.id, more.take(3).map { it.id }.toSet(), emptySet(), emptyMap(), emptyList(), now).size)
    }

    @Test fun plannerSkipsTheMainAddressAndPrefersWhereTheCameraIsSeen() {
        val book = CameraBook(listOf(a, b.copy(aliases = listOf("usnb"))))
        // главная ещё подключается к адресу B (id не знаем) — B в ленту не тянем
        assertEquals(emptyList<Any>(), CameraStrip.due(book, null, emptySet(), setOf(WiFiAddress("192.168.1.123", 15741)) +
            WiFiAddress("192.168.1.123"), emptyMap(), emptyList(), now))
        // B видна в сети по новому адресу — туда
        val moved = WiFiAddress("192.168.1.77", 15741)
        assertEquals(listOf(moved), CameraStrip.due(book, a.id, emptySet(), emptySet(), emptyMap(), listOf(wifi(moved, "usnb")), now)
            .map { it.second })
    }

    @Test fun stripCamerasFollowTheMainInTheList() {
        val book = CameraBook(listOf(c, b, a))
        val main = CurrentCamera(CameraLink.WiFi(WiFiAddress("192.168.1.123")), a.id, ready = true, model = "ZV-E10")
        val sightings = listOf(wifi(WiFiAddress("192.168.1.123", 15742)), wifi(WiFiAddress("192.168.1.123", 15741)))
        val entries = book.entries(sightings, main, now, sideReady = setOf(b.id))
        assertEquals(listOf("A", "B", "C"), entries.map { it.name })
        assertEquals(listOf(CameraState.current, CameraState.side, CameraState.available), entries.map { it.state })
        val side = entries[1]
        assertEquals("в ленте", side.stateLabel)
        assertEquals("Сделать главной", side.action)
        assertTrue(side.canRemoveFromStrip)
        assertFalse(side.canForget)
        assertFalse(side.canAddToStrip)
        assertEquals("Wi-Fi · 192.168.1.123:15741", side.detail)
        // свободная сопряжённая по Wi-Fi — «В ленту» и «Забыть»
        assertTrue(entries[2].canAddToStrip)
        assertTrue(entries[2].canForget)
        assertFalse(entries[2].canRemoveFromStrip)
        // у главной — ничего из этого
        assertFalse(entries[0].canAddToStrip || entries[0].canRemoveFromStrip || entries[0].canForget)
    }

    @Test fun stripCameraWithoutConnectionSaysSo() {
        val book = CameraBook(listOf(a, b))
        val main = CurrentCamera(CameraLink.WiFi(WiFiAddress("192.168.1.123")), a.id, ready = true, model = "ZV-E10")
        val side = book.entries(emptyList(), main, now).first { it.id == b.id }
        assertEquals(CameraState.side, side.state)
        assertEquals("в ленте · нет связи", side.stateLabel)
        // без связи — подключить главной, как свободную
        assertEquals("Подключить", side.action)
        assertNull(side.link)
        assertEquals("Wi-Fi · 192.168.1.123:15741", side.detail)
        // камера на кабеле в ленту не попадает
        val usb = CameraBook(listOf(a.copy(inStrip = false, host = null, paired = false)))
            .entries(listOf(CameraSighting(CameraLink.Usb("/dev/bus/usb/001/002"), "ZV-E10", null, now)), null, now)
        assertFalse(usb.first { it.link is CameraLink.Usb }.canAddToStrip)
        // знакомая без адреса по Wi-Fi — тоже нет
        assertFalse(usb.first { it.known != null }.canAddToStrip)
        assertEquals(PtpIp.PORT, a.address!!.port)
    }
}
