package local.zve10viewer

import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpIp
import local.zve10viewer.ptp.PtpIpInitResult
import local.zve10viewer.ptp.PtpIpPacket
import local.zve10viewer.ptp.PtpIpPacketReader
import local.zve10viewer.ptp.PtpIpTransaction
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.Ssdp
import local.zve10viewer.sony.LiveViewFrame
import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
private fun le32(v: Long) = ByteArray(4) { (v ushr (8 * it)).toByte() }
private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
private fun bytes(vararg parts: ByteArray) = parts.fold(ByteArray(0)) { acc, b -> acc + b }
private fun packet(type: Int, payload: ByteArray) = bytes(le32(8L + payload.size), le32(type.toLong()), payload)

class PtpIpTest {
    @Test fun initCommandRequestCarriesGuidNameAndVersion() {
        val guid = ByteArray(16) { (it + 1).toByte() }
        val expected = bytes(le32(34), le32(1), guid, byteArrayOf(0x5A, 0, 0x56, 0, 0, 0), le32(0x0001_0000))
        assertArrayEquals(expected, PtpIp.initCommandRequest(guid, "ZV"))
    }

    @Test fun operationRequestLayout() {
        val expected = bytes(le32(30), le32(6), le32(1), le16(0x9201), le32(5), le32(1), le32(0), le32(0))
        assertArrayEquals(expected, PtpIp.operationRequest(0x9201, 5, listOf(1, 0, 0), dataOut = false))
        assertArrayEquals(le32(2), PtpIp.operationRequest(0x9205, 6, listOf(0xD21E), dataOut = true).copyOfRange(8, 12))
    }

    @Test fun dataOutPacketsWrapTheValue() {
        assertArrayEquals(bytes(le32(20), le32(9), le32(6), le64(4)), PtpIp.startData(6, 4))
        assertArrayEquals(bytes(le32(16), le32(12), le32(6), byteArrayOf(0x20, 3, 0, 0)), PtpIp.endData(6, byteArrayOf(0x20, 3, 0, 0)))
    }

    @Test fun readerAssemblesPacketsFromChunks() {
        val reader = PtpIpPacketReader()
        val stream = packet(7, bytes(le16(0x2001), le32(1))) + packet(8, bytes(le16(0xC203), le32(0)))
        reader.feed(stream.copyOfRange(0, 5))
        assertNull(reader.next())
        reader.feed(stream.copyOfRange(5, 15))
        assertEquals(PtpIpPacket(7, bytes(le16(0x2001), le32(1))), reader.next())
        assertNull(reader.next())
        reader.feed(stream.copyOfRange(15, stream.size))
        assertEquals(8, reader.next()?.type)
        assertNull(reader.next())
    }

    // камера присылает файл пакетами по 2 МБ, а TCP отдаёт их кусками по ~1,5 КБ: склейка должна быть линейной
    @Test(timeout = 3000) fun readerAssemblesBigPacketFromSmallChunksQuickly() {
        val payload = ByteArray(32 * 1024 * 1024) { (it % 251).toByte() }
        val stream = packet(10, payload)
        val reader = PtpIpPacketReader()
        var offset = 0
        var result: PtpIpPacket? = null
        while (offset < stream.size) {
            val count = minOf(1460, stream.size - offset)
            reader.feed(stream.copyOfRange(offset, offset + count))
            offset += count
            reader.next()?.let { result = it }
        }
        assertEquals(10, result?.type)
        assertArrayEquals(payload, result?.payload)
    }

    @Test fun readerRejectsBrokenLength() {
        val reader = PtpIpPacketReader()
        reader.feed(bytes(le32(4), le32(7)))
        try {
            reader.next()
            fail("ожидали ошибку длины")
        } catch (e: PtpParseException) {
            assertTrue(e.message!!.contains("длина"))
        }
    }

    @Test fun initReplyIsAcceptedOrRejected() {
        val name = bytes(*"ZV-E10".map { le16(it.code) }.toTypedArray(), byteArrayOf(0, 0))
        val ack = PtpIpPacket(2, bytes(le32(77), ByteArray(16) { 0xAB.toByte() }, name, le32(0x0001_0000)))
        assertEquals(PtpIpInitResult.Accepted(77, "ZV-E10"), PtpIp.parseInitReply(ack))
        // не сопряжено: камера отвечает Init Fail с причиной 1
        assertEquals(PtpIpInitResult.Rejected(1), PtpIp.parseInitReply(PtpIpPacket(5, le32(1))))
    }

    @Test fun transactionCollectsDataAndSkipsOtherTransactions() {
        val t = PtpIpTransaction(5)
        assertNull(t.consume(PtpIpPacket(9, bytes(le32(5), le64(3)))))
        // остаток оборванной транзакции 4 — мимо
        assertNull(t.consume(PtpIpPacket(12, bytes(le32(4), byteArrayOf(9, 9)))))
        assertNull(t.consume(PtpIpPacket(7, bytes(le16(0x2019), le32(4)))))
        assertNull(t.consume(PtpIpPacket(10, bytes(le32(5), byteArrayOf(1, 2)))))
        assertNull(t.consume(PtpIpPacket(12, bytes(le32(5), byteArrayOf(3)))))
        val reply = t.consume(PtpIpPacket(7, bytes(le16(0x2001), le32(5), le32(7))))!!
        assertEquals(0x2001, reply.code)
        assertEquals(listOf(7L), reply.params)
        assertArrayEquals(byteArrayOf(1, 2, 3), reply.data)
    }

    @Test fun responseWithoutDataPhase() {
        val reply = PtpIpTransaction(1).consume(PtpIpPacket(7, bytes(le16(0x2001), le32(1))))!!
        assertEquals(PtpReply(0x2001).code, reply.code)
        assertEquals(0, reply.data.size)
    }

    @Test fun eventPacketBecomesPtpEvent() {
        assertEquals(PtpEvent(0xC203, listOf(0xD21EL)), PtpIp.parseEvent(PtpIpPacket(8, bytes(le16(0xC203), le32(0), le32(0xD21E)))))
        assertNull(PtpIp.parseEvent(PtpIpPacket(13, ByteArray(0))))
    }

    @Test fun ssdpFindsTheSonyCamera() {
        assertTrue(String(Ssdp.searchRequest()).contains("ST: urn:schemas-sony-com:service:DigitalImaging:1"))
        // ответ ZV-E10 из домашней сети (MAC в USN заменён нулями)
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT: \r\nLOCATION: http://192.168.1.194:64321/dd.xml\r\n" +
            "SERVER: UPnP/1.0 SonyImagingDevice/1.0\r\nST: urn:schemas-sony-com:service:DigitalImaging:1\r\n" +
            "USN: uuid:00000000-0005-0010-8000-000000000000::urn:schemas-sony-com:service:DigitalImaging:1\r\n\r\n"
        assertEquals(Ssdp.Response("192.168.1.194", "uuid:00000000-0005-0010-8000-000000000000"), Ssdp.parse(reply))
        assertNull(Ssdp.parse("HTTP/1.1 200 OK\r\nLOCATION: http://192.168.1.1:1900/igd.xml\r\nST: upnp:rootdevice\r\n\r\n"))
    }

    @Test fun probeRequestIsAnEmptyPacket() {
        assertArrayEquals(bytes(le32(8), le32(13)), PtpIp.probeRequest)
        assertArrayEquals(bytes(le32(8), le32(14)), PtpIp.probeResponse)
    }

    @Test fun initFailKeepsItsReason() {
        // 2 — камера занята: сопряжение при этом не забываем
        assertEquals(PtpIpInitResult.Rejected(2), PtpIp.parseInitReply(PtpIpPacket(5, le32(2))))
    }

    @Test fun readerReadsStraightFromTheSocketStream() {
        val payload = ByteArray(100_000) { (it % 7).toByte() }
        val first = packet(10, bytes(le32(5), payload))
        val second = packet(7, bytes(le16(0x2001), le32(5)))
        val reader = PtpIpPacketReader()
        // TCP отдаёт поток кусками по 1460 байт
        val input = object : InputStream() {
            val source = ByteArrayInputStream(first + second)
            override fun read(): Int = source.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = source.read(b, off, minOf(len, 1460))
        }
        val transaction = PtpIpTransaction(5)
        var reply: PtpReply? = null
        while (reply == null) {
            val view = reader.nextView()
            if (view == null) {
                assertTrue(reader.readFrom(input) > 0)
                continue
            }
            reply = transaction.consume(view)
        }
        assertEquals(0x2001, reply.code)
        assertArrayEquals(payload, reply.data)
        assertEquals(-1, reader.readFrom(input))
    }

    @Test fun readerRejectsAbsurdLength() {
        // длина больше любого файла камеры — поток сбился: дальше его не разобрать
        val reader = PtpIpPacketReader()
        reader.feed(bytes(le32(0x7FFF_FFF0), le32(10)))
        try {
            reader.next()
            fail("ожидали ошибку длины")
        } catch (e: PtpParseException) {
            assertTrue(e.message!!.contains("длина"))
        }
    }

    @Test fun transactionGrowsPastTheAnnouncedSizeAndTrimsShortData() {
        val more = PtpIpTransaction(3)
        more.consume(PtpIpPacket(9, bytes(le32(3), le64(2))))
        more.consume(PtpIpPacket(12, bytes(le32(3), byteArrayOf(1, 2, 3, 4))))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), more.consume(PtpIpPacket(7, bytes(le16(0x2001), le32(3))))!!.data)
        val less = PtpIpTransaction(4)
        less.consume(PtpIpPacket(9, bytes(le32(4), le64(10))))
        less.consume(PtpIpPacket(12, bytes(le32(4), byteArrayOf(7, 8))))
        assertArrayEquals(byteArrayOf(7, 8), less.consume(PtpIpPacket(7, bytes(le16(0x2001), le32(4))))!!.data)
    }

    @Test fun liveViewJpegIsLocatedInPlaceBySizeField() {
        // u32 смещение, u32 размер, служебное, JPEG, снова служебное
        val data = bytes(le32(12), le32(4), ByteArray(4), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), ByteArray(6) { 9 })
        assertEquals(LiveViewFrame.Span(12, 4), LiveViewFrame.locate(data))
        assertArrayEquals(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2), LiveViewFrame.jpeg(data))
        // размер не сходится — до конца ответа
        val broken = bytes(le32(12), le32(1000), ByteArray(4), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2))
        assertEquals(LiveViewFrame.Span(12, 4), LiveViewFrame.locate(broken))
    }

    @Test fun cameraAccessPointIsRecognisedByItsAddress() {
        // в режиме Wi-Fi Direct камера сама раздаёт сеть и всегда берёт себе 192.168.122.1
        assertTrue(PtpIp.isCameraAccessPoint("192.168.122.1"))
        assertFalse(PtpIp.isCameraAccessPoint("192.168.1.194"))
        assertFalse(PtpIp.isCameraAccessPoint("192.168.122.10"))
    }
}
