package local.zve10viewer

import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.Ssdp
import local.zve10viewer.settings.CameraBook
import local.zve10viewer.settings.CameraLink
import local.zve10viewer.settings.CameraSighting
import local.zve10viewer.settings.CameraState
import local.zve10viewer.settings.CurrentCamera
import local.zve10viewer.settings.KnownCamera
import local.zve10viewer.settings.WiFiAddress
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CamerasTest {
    private val now = 1_761_400_000_000L

    private fun wifi(host: String, port: Int = PtpIp.PORT, print: String? = null, at: Long = now) =
        CameraSighting(CameraLink.WiFi(WiFiAddress(host, port)), null, print, at)

    @Test fun fingerprintIsTheSha256PrefixLikeOnTheMac() {
        assertEquals("25cb6b2d717e95fb", CameraBook.fingerprint("sn:1"))
        assertEquals("25cb6b2d717e95fb", CameraBook.serialFingerprint("1"))
        assertEquals("2dc10c215ca3487c", CameraBook.serialFingerprint("12345678"))
        // USN — как его отдаёт разбор ответа на поиск, с «uuid:»
        assertEquals("558944f05a4c9f95", CameraBook.usnFingerprint("uuid:00000000-0005-0010-8000-000000000001"))
    }

    @Test fun addressReadsHostAndPort() {
        assertEquals(WiFiAddress("192.168.1.40"), WiFiAddress.parse("192.168.1.40"))
        assertEquals(WiFiAddress("192.168.1.123", 15741), WiFiAddress.parse(" 192.168.1.123:15741 "))
        assertEquals("192.168.1.40", WiFiAddress("192.168.1.40", PtpIp.PORT).toString())
        assertEquals("127.0.0.1:15742", WiFiAddress("127.0.0.1", 15742).toString())
        assertNull(WiFiAddress.parse(""))
        assertNull(WiFiAddress.parse("192.168.1.40:0"))
        assertNull(WiFiAddress.parse("192.168.1.40:порт"))
        assertNull(WiFiAddress.parse(":15740"))
    }

    @Test fun ssdpReadsThePortOfAVirtualCamera() {
        val reply = "HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.123:15741/dd.xml\r\n" +
            "ST: urn:schemas-sony-com:service:DigitalImaging:1\r\n" +
            "USN: uuid:00000000-0005-0010-8000-0000000000b2::urn:schemas-sony-com:service:DigitalImaging:1\r\nX-PTPIP-Port: 15741\r\n\r\n"
        assertEquals(Ssdp.Response("192.168.1.123", "uuid:00000000-0005-0010-8000-0000000000b2", 15741), Ssdp.parse(reply))
        // настоящая камера порт не шлёт — 15740; мусор вместо порта — тоже 15740
        assertEquals(PtpIp.PORT, Ssdp.parse(reply.replace("X-PTPIP-Port: 15741\r\n", ""))?.port)
        assertEquals(PtpIp.PORT, Ssdp.parse(reply.replace("Port: 15741", "Port: порт"))?.port)
    }

    @Test fun knownCamerasRoundTripThroughJson() {
        val cameras = listOf(
            KnownCamera("3f2a9c0d1e4b5a67", "Левая", "ZV-E10", listOf("9b8c000000000000"), "192.168.1.40", null, true, 1_761_400_000),
            KnownCamera("aaaaaaaaaaaaaaaa", "ZV-E10 2", "ZV-E10", host = "192.168.1.123", port = 15741),
            KnownCamera("bbbbbbbbbbbbbbbb", "Кабельная", "ZV-E10"),
        )
        val json = CameraBook.toJson(cameras)
        assertEquals(cameras, CameraBook.fromJson(json))
        // порт 15740 не пишется, другой — пишется
        assertFalse(JSONArray(json).getJSONObject(0).has("port"))
        assertEquals(15741, JSONArray(json).getJSONObject(1).getInt("port"))
    }

    @Test fun unknownFieldsAndRecordsWithoutIdAreSkipped() {
        val json = """[{"id":"aaaa","name":"А","model":"ZV-E10","paired":true,"lens":"16-50","future":{"x":1}},
            {"name":"без id"},{"id":"","name":"пустой id"},
            {"id":"bbbb","model":"ZV-E10","port":15740,"host":null}, 5]"""
        val cameras = CameraBook.fromJson(json)
        assertEquals(listOf("aaaa", "bbbb"), cameras.map { it.id })
        assertTrue(cameras[0].paired)
        // нет имени — модель; порт 15740 и host: null — как не записанные
        assertEquals("ZV-E10", cameras[1].name)
        assertNull(cameras[1].port)
        assertNull(cameras[1].host)
        assertTrue(CameraBook.fromJson("мусор").isEmpty())
    }

    @Test fun oldMemoryBecomesOneLegacyCamera() {
        val book = CameraBook.load(null, null, "ZV-E10", "192.168.1.194")
        assertEquals(listOf(KnownCamera(CameraBook.LEGACY_ID, "ZV-E10", "ZV-E10", host = "192.168.1.194", paired = true)), book.cameras)
        // ключ cameras уже есть — прежняя память не читается
        assertTrue(CameraBook.load("[]", null, "ZV-E10", "192.168.1.194").cameras.isEmpty())
        assertTrue(CameraBook.load(null, null, null, null).cameras.isEmpty())
        assertEquals("x", CameraBook.load("[]", "x", null, null).preferred)
    }

    @Test fun legacyTakesTheRealFingerprintOnTheFirstWiFiConnection() {
        val renamed = CameraBook.load(null, null, "ZV-E10", "192.168.1.194").renamed(CameraBook.LEGACY_ID, "Моя")
        val (book, id) = renamed.connected("12345678", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.194")), emptyList(), now)
        assertEquals("2dc10c215ca3487c", id)
        val camera = book.cameras.single()
        assertEquals("Моя", camera.name)
        assertTrue(camera.paired)
        // у «legacy» отпечатка не было — и псевдонима нет
        assertTrue(camera.aliases.isEmpty())
        assertEquals(now / 1000, camera.lastUsed)
        assertEquals(id, book.preferred)
        // адрес сменился (DHCP): запись всё равно достаётся первой камере по Wi-Fi — прежние версии помнили одну
        val moved = CameraBook.load(null, null, "ZV-E10", "192.168.1.194")
            .connected("12345678", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.77")), emptyList(), now).first
        assertEquals(listOf("2dc10c215ca3487c"), moved.cameras.map { it.id })
        assertEquals("192.168.1.77", moved.cameras.single().host)
        // по кабелю «legacy» не трогается: на кабеле может быть другая камера
        val usb = CameraBook.load(null, null, "ZV-E10", "192.168.1.194")
            .connected("87654321", "ZV-E10", CameraLink.Usb("/dev/bus/usb/001/002"), emptyList(), now).first
        assertEquals(listOf(CameraBook.LEGACY_ID, "3d6284e869321b08"), usb.cameras.map { it.id })
        assertEquals("ZV-E10 2", usb.find("3d6284e869321b08")!!.name)
    }

    @Test fun newCamerasGetNumberedNamesAndBecomePreferred() {
        val (afterA, a) = CameraBook().connected("1", "ZV-E10", CameraLink.WiFi(WiFiAddress("127.0.0.1")), listOf("usn-a"), now)
        val (afterB, b) = afterA.connected("2", "ZV-E10", CameraLink.WiFi(WiFiAddress("127.0.0.1", 15741)), emptyList(), now + 1000)
        val (book, c) = afterB.connected("3", "ZV-E10", CameraLink.Usb("/dev/bus/usb/001/003"), emptyList(), now + 2000)
        assertEquals(listOf("ZV-E10", "ZV-E10 2", "ZV-E10 3"), book.cameras.map { it.name })
        assertEquals("25cb6b2d717e95fb", a)
        assertEquals(c, book.preferred)
        assertEquals(listOf("usn-a"), book.find(a)!!.aliases)
        assertEquals(15741, book.find(b)!!.port)
        assertNull(book.find(a)!!.port)
        // по кабелю сопряжения не бывает
        assertFalse(book.find(c)!!.paired)
        assertTrue(book.find(b)!!.paired)
        // та же камера снова — запись та же, имя то же
        val again = book.renamed(a, "Левая").connected("1", "ZV-E10", CameraLink.WiFi(WiFiAddress("127.0.0.1")), emptyList(), now + 5000).first
        assertEquals(3, again.cameras.size)
        assertEquals("Левая", again.find(a)!!.name)
        assertEquals(a, again.preferred)
        // серийного номера нет — по USN или USB, иначе по адресу
        assertEquals("usn-x", CameraBook().connected(null, "ZV-E10", CameraLink.WiFi(WiFiAddress("10.0.0.5")), listOf("usn-x"), now).second)
        assertEquals(CameraBook.fingerprint("addr:10.0.0.5:15741"),
            CameraBook().connected(" ", "ZV-E10", CameraLink.WiFi(WiFiAddress("10.0.0.5", 15741)), emptyList(), now).second)
        assertEquals(CameraBook.fingerprint("usb:/dev/bus/usb/001/009"),
            CameraBook().connected(null, "ZV-E10", CameraLink.Usb("/dev/bus/usb/001/009"), emptyList(), now).second)
    }

    @Test fun anAddressBelongsToTheCameraThatHasItNow() {
        val book = CameraBook(listOf(KnownCamera("aaaa", "A", "ZV-E10", host = "192.168.1.40", paired = true)))
        val next = book.connected("2", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.40")), emptyList(), now).first
        assertNull(next.find("aaaa")!!.host)
        assertEquals("192.168.1.40", next.find(CameraBook.serialFingerprint("2"))!!.host)
    }

    @Test fun sightingsMatchByFingerprintAliasOrAddress() {
        val book = CameraBook(listOf(
            KnownCamera("aaaa", "A", "ZV-E10", aliases = listOf("usn-a"), host = "192.168.1.40", paired = true),
            KnownCamera("bbbb", "B", "ZV-E10", host = "192.168.1.123", port = 15741),
        ))
        assertEquals("aaaa", book.match(wifi("10.0.0.9", print = "usn-a"))?.id)
        assertEquals("aaaa", book.match(wifi("10.0.0.9", print = "aaaa"))?.id)
        assertEquals("bbbb", book.match(wifi("192.168.1.123", 15741))?.id)
        // тот же хост, другой порт — другая камера
        assertNull(book.match(wifi("192.168.1.123")))
        assertNull(book.match(wifi("10.0.0.9", print = "чужой")))
        assertEquals("aaaa", book.match(CameraSighting(CameraLink.Usb("/dev/x"), "ZV-E10", "aaaa", now))?.id)
    }

    @Test fun pairingIsPerCameraWithTheLegacyFallback() {
        val book = CameraBook(listOf(
            KnownCamera("aaaa", "A", "ZV-E10", aliases = listOf("usn-a"), host = "192.168.1.40", paired = true),
            KnownCamera("bbbb", "B", "ZV-E10", host = "192.168.1.41", paired = false),
        ))
        assertTrue(book.isPaired(WiFiAddress("10.0.0.9"), "usn-a"))
        assertFalse(book.isPaired(WiFiAddress("192.168.1.41")))
        assertTrue(book.isPaired(WiFiAddress("192.168.1.40")))
        assertFalse(book.isPaired(WiFiAddress("10.0.0.9")))
        // прежние версии стучались к любой найденной: пока есть сопряжённая «legacy» — тоже
        val legacy = CameraBook.load(null, null, "ZV-E10", "192.168.1.194")
        assertTrue(legacy.isPaired(WiFiAddress("192.168.1.77")))
        assertFalse(legacy.unpairedAll().isPaired(WiFiAddress("192.168.1.77")))
        // камера ответила «не знаю программу» — больше не сопряжена; чужая по прежней памяти — снимается «legacy»
        assertFalse(book.pairingLost(WiFiAddress("192.168.1.40")).isPaired(WiFiAddress("192.168.1.40")))
        assertFalse(legacy.pairingLost(WiFiAddress("192.168.1.77")).isPaired(WiFiAddress("192.168.1.77")))
    }

    @Test fun firstAddressIsThePreferredPairedCamera() {
        val book = CameraBook(listOf(
            KnownCamera("aaaa", "A", "ZV-E10", host = "192.168.1.40", paired = true, lastUsed = 100),
            KnownCamera("bbbb", "B", "ZV-E10", host = "192.168.1.41", paired = true, lastUsed = 200),
            KnownCamera("cccc", "C", "ZV-E10", host = "192.168.1.42", paired = false, lastUsed = 300),
        ), preferred = "aaaa")
        assertEquals(WiFiAddress("192.168.1.40"), book.firstAddress())
        // предпочтённая не сопряжена или её нет — сопряжённая, к которой подключались позже всех
        assertEquals(WiFiAddress("192.168.1.41"), book.copy(preferred = "cccc").firstAddress())
        assertEquals(WiFiAddress("192.168.1.41"), book.copy(preferred = null).firstAddress())
        assertNull(CameraBook(listOf(KnownCamera("dddd", "D", "ZV-E10", paired = true))).firstAddress())
    }

    @Test fun listShowsCurrentThenVisibleThenTheRest() {
        val book = CameraBook(listOf(
            KnownCamera("a", "A", "ZV-E10", host = "192.168.1.40", paired = true, lastUsed = 100),
            KnownCamera("b", "B", "ZV-E10", host = "192.168.1.41", paired = true, lastUsed = 300),
            KnownCamera("c", "C", "ZV-E10", host = "192.168.1.42", paired = false, lastUsed = 200),
            KnownCamera("d", "D", "ZV-E10", lastUsed = 400),
            KnownCamera("e", "E", "ZV-E10", host = "192.168.1.44", paired = true, lastUsed = 50),
        ))
        val sightings = listOf(
            wifi("192.168.1.40"), wifi("192.168.1.41"), wifi("192.168.1.42"),
            wifi("192.168.1.123", 15742, print = "чужой"),
            CameraSighting(CameraLink.Usb("/dev/bus/usb/001/005"), "ZV-E10", null, now),
        )
        val current = CurrentCamera(CameraLink.WiFi(WiFiAddress("192.168.1.40")), "a", ready = true, model = "ZV-E10")
        val entries = book.entries(sightings, current, now)
        assertEquals(listOf("a", "usb:/dev/bus/usb/001/005", "b", "c", "wifi:192.168.1.123:15742", "d", "e"), entries.map { it.id })
        assertEquals(listOf(CameraState.current, CameraState.available, CameraState.available, CameraState.needsPairing,
            CameraState.needsPairing, CameraState.offline, CameraState.offline), entries.map { it.state })
        assertEquals(listOf("на связи", "свободна", "свободна", "нужно сопряжение", "нужно сопряжение", "не подключена", "не видна в сети"),
            entries.map { it.stateLabel })
        assertEquals(listOf(null, "Подключить", "Подключить", "Сопрячь", "Сопрячь", null, "Подключить"), entries.map { it.action })
        assertEquals(listOf("Wi-Fi · 192.168.1.40", "по кабелю", "Wi-Fi · 192.168.1.41", "Wi-Fi · 192.168.1.42",
            "Wi-Fi · 192.168.1.123:15742", "по кабелю", "Wi-Fi · 192.168.1.44"), entries.map { it.detail })
        assertEquals(listOf("A", "ZV-E10", "B", "C", "Камера в сети", "D", "E"), entries.map { it.name })
    }

    @Test fun currentWhileConnectingAndOneRowPerCamera() {
        val connecting = CameraBook().entries(emptyList(), CurrentCamera(CameraLink.WiFi(WiFiAddress("127.0.0.1", 15741)), null, false, null), now)
        assertEquals("wifi:127.0.0.1:15741", connecting.single().id)
        assertEquals("подключаю…", connecting.single().stateLabel)
        assertEquals("Камера в сети", connecting.single().name)
        // видна по Wi-Fi и текущая — одна строка, знакомая по адресу ещё до DeviceInfo
        val book = CameraBook(listOf(KnownCamera("a", "A", "ZV-E10", host = "192.168.1.40", paired = true)))
        val both = book.entries(listOf(wifi("192.168.1.40")), CurrentCamera(CameraLink.WiFi(WiFiAddress("192.168.1.40")), null, true, "ZV-E10"), now)
        assertEquals(listOf("a"), both.map { it.id })
        assertEquals("A", both.single().name)
    }

    @Test fun pairingEntryHasNoButton() {
        val entry = CameraBook().entries(listOf(wifi("192.168.1.50")), null, now).single().copy(pairing = true)
        assertEquals("сопрягаю…", entry.stateLabel)
        assertNull(entry.action)
        assertEquals(WiFiAddress("192.168.1.50"), entry.address)
    }

    @Test fun wifiCameraIsVisibleForThirtySeconds() {
        val book = CameraBook(listOf(KnownCamera("a", "A", "ZV-E10", host = "192.168.1.40", paired = true)))
        val seen = wifi("192.168.1.40")
        assertEquals(CameraState.available, book.entries(listOf(seen), null, now + 29_000).single().state)
        assertEquals(CameraState.offline, book.entries(listOf(seen), null, now + 30_000).single().state)
        // по кабелю — пока на шине, сколько бы ни прошло
        val usb = CameraSighting(CameraLink.Usb("/dev/x"), "ZV-E10", null, now)
        assertEquals(CameraState.available, CameraBook().entries(listOf(usb), null, now + 600_000).single().state)
    }

    @Test fun renameForgetAndPreferred() {
        val book = CameraBook(listOf(KnownCamera("a", "A", "ZV-E10"), KnownCamera("b", "B", "ZV-E10")), preferred = "a")
        assertEquals("Левая", book.renamed("a", "  Левая ").find("a")!!.name)
        assertEquals("A", book.renamed("a", "   ").find("a")!!.name)
        assertSame(book, book.renamed("нет", "X"))
        val forgot = book.forgotten("a")
        assertEquals(listOf("b"), forgot.cameras.map { it.id })
        assertNull(forgot.preferred)
        assertEquals("a", book.forgotten("b").preferred)
        assertTrue(CameraBook(listOf(KnownCamera("a", "A", "ZV-E10", paired = true))).unpairedAll().cameras.none { it.paired })
    }

    @Test fun defaultNamesAreNumbered() {
        val book = CameraBook(listOf(KnownCamera("a", "ZV-E10", "ZV-E10"), KnownCamera("b", "ZV-E10 2", "ZV-E10")))
        assertEquals("ZV-E10 3", book.defaultName("ZV-E10"))
        assertEquals("ILCE-7M4", book.defaultName("ILCE-7M4"))
        assertEquals("ZV-E10", CameraBook().defaultName("ZV-E10"))
        assertEquals("ZV-E10 2", book.defaultName("ZV-E10 2", except = "b"))
    }
}
