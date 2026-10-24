package local.zve10viewer

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.PtpContainer
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionException
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOptions
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.LiveViewFrame
import local.zve10viewer.sony.PropertyRange
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyPropertyParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private fun fixture(name: String): ByteArray =
    CoreTest::class.java.getResource("/fixtures/$name.bin")!!.readBytes()

class CoreTest {
    @Test fun parsesRealDeviceInfo() {
        val info = DeviceInfo.parse(fixture("deviceinfo"))
        assertEquals("Sony Corporation", info.manufacturer)
        assertEquals("ZV-E10", info.model)
        assertEquals("2.00", info.firmware)
        assertEquals(0x11L, info.vendorExtensionId)
        assertEquals(32, info.operations.size)
        assertTrue(info.isPcRemote)
    }

    @Test fun parsesAllRealProperties() {
        val props = SonyPropertyParser.parse(fixture("props_settled"))
        assertEquals(93, props.size)
        val wb = props.getValue(SonyProp.WHITE_BALANCE)
        assertEquals(PtpValue.Num(2), wb.current)
        assertTrue(wb.isWritable)
        assertEquals(14, wb.settableValues.size)
        assertEquals(15, wb.allValues.size)
        assertFalse(wb.settableValues.contains(7L))
        assertTrue(wb.allValues.contains(7L))
        val f = props.getValue(SonyProp.F_NUMBER)
        assertEquals(280L, f.current.intValue)
        assertEquals(19, f.settableValues.size)
        assertEquals(2200L, f.settableValues.last())
        val ev = props.getValue(SonyProp.EXPOSURE_COMP)
        assertFalse(ev.isWritable)
        assertEquals(13, ev.allValues.size)
        assertEquals(200L, props.getValue(SonyProp.ISO).current.intValue)
        assertEquals(0x0001_0032L, props.getValue(SonyProp.SHUTTER).current.intValue)
        assertEquals(PropertyRange(2500, 9900, 100), props.getValue(SonyProp.COLOR_TEMP).range)
        assertEquals(PropertyRange(164, 220, 2), props.getValue(SonyProp.AB_SHIFT).range)
        assertEquals(24L, props.getValue(SonyProp.BATTERY_LEVEL).current.intValue)
    }

    @Test fun extractsJpegFromRealLiveViewFrame() {
        val jpeg = LiveViewFrame.jpeg(fixture("lv_frame_raw"))!!
        assertEquals(20736, jpeg.size)
        assertEquals(0xFF.toByte(), jpeg[0])
        assertEquals(0xD8.toByte(), jpeg[1])
        assertNull(LiveViewFrame.jpeg(byteArrayOf(8, 0, 0, 0, 1, 2, 3, 4, 5, 6)))
    }

    @Test fun labelsMatchTheMacApp() {
        assertEquals("1/50", ValueFormatter.label(SettingID.shutter, 0x0001_0032))
        assertEquals("1\"", ValueFormatter.label(SettingID.shutter, 0x000A_000A))
        assertEquals("2.5\"", ValueFormatter.label(SettingID.shutter, 0x0019_000A))
        assertEquals("BULB", ValueFormatter.label(SettingID.shutter, 0))
        assertEquals("f/2.8", ValueFormatter.label(SettingID.aperture, 280))
        assertEquals("f/4", ValueFormatter.label(SettingID.aperture, 400))
        assertEquals("f/--", ValueFormatter.label(SettingID.aperture, 0xFFFF))
        assertEquals("AUTO", ValueFormatter.label(SettingID.iso, 0xFF_FFFF))
        assertEquals("±0.0", ValueFormatter.label(SettingID.exposureComp, 0))
        assertEquals("+0.3", ValueFormatter.label(SettingID.exposureComp, 300))
        assertEquals("−0.7", ValueFormatter.label(SettingID.exposureComp, -700))
        assertEquals("AWB", ValueFormatter.label(SettingID.whiteBalance, 2))
        assertEquals("Слежение: точка L", ValueFormatter.label(SettingID.focusArea, 0x206))
        assertEquals("DRO 3", ValueFormatter.label(SettingID.dro, 0x13))
        assertEquals("Видео M", ValueFormatter.label(SettingID.exposureProgram, 0x0007_8053))
        assertEquals("A0.5", ValueFormatter.label(SettingID.wbShiftAB, 194))
        assertEquals("G0.25", ValueFormatter.label(SettingID.wbShiftGM, 193))
        assertEquals("M1", ValueFormatter.label(SettingID.wbShiftGM, 188))
        assertEquals("2 ч 32 мин", ValueFormatter.label(SettingID.cardRemaining, 9176))
        assertEquals("#14", ValueFormatter.label(SettingID.whiteBalance, 0x000E))
        assertEquals("—", ValueFormatter.label(SettingID.iso, null))
    }

    @Test fun optionsComeFromTheProperty() {
        val p = SonyPropertyParser.parse(fixture("props_settled"))
        val aperture = SettingOptions.options(SettingID.aperture, p.getValue(SonyProp.F_NUMBER))
        assertEquals(19, aperture.size)
        assertEquals("f/2.8", aperture.first().label)
        assertEquals("f/22", aperture.last().label)
        assertTrue(SettingOptions.options(SettingID.exposureComp, p.getValue(SonyProp.EXPOSURE_COMP)).isEmpty())
        assertEquals(29, SettingOptions.options(SettingID.wbShiftAB, p.getValue(SonyProp.AB_SHIFT)).size)
        assertEquals(57, SettingOptions.options(SettingID.wbShiftGM, p.getValue(SonyProp.GM_SHIFT)).size)
    }

    @Test fun captureModePairsKeepTheLetter() {
        assertEquals(CaptureMode.video, CaptureMode.of(0x78053))
        assertEquals(CaptureMode.photo, CaptureMode.of(0x1))
        assertEquals(0x1L, CaptureMode.counterpart(0x78053, CaptureMode.photo, listOf(0x1L, 0x78053L)))
        assertNull(CaptureMode.counterpart(0x78053, CaptureMode.photo, listOf(0x78053L)))
    }

    @Test fun commandContainerLayout() {
        val bytes = PtpContainer.command(0x9201, 5, listOf(1L, 0L, 0L))
        assertArrayEquals(
            byteArrayOf(24, 0, 0, 0, 1, 0, 0x01, 0x92.toByte(), 5, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            bytes,
        )
    }

    @Test fun startDoesTheSonyHandshakeInOrder() = runTest {
        val camera = FakeTransport()
        val session = CameraSession(camera, SessionTiming(settleMs = 1, pollMs = 1))
        session.start(this)
        val sent = camera.sent.map { it.first to it.second }
        assertEquals(
            listOf(
                SonyOp.OPEN_SESSION to listOf(1L),
                SonyOp.GET_DEVICE_INFO to emptyList(),
                SonyOp.CONNECT to listOf(1L, 0L, 0L),
                SonyOp.CONNECT to listOf(2L, 0L, 0L),
                SonyOp.GET_EXT_DEVICE_INFO to listOf(0x12CL, 1L),
                SonyOp.CONNECT to listOf(3L, 0L, 0L),
                SonyOp.SET_EXT_PROP_VALUE to listOf(SonyProp.PRIORITY_MODE.toLong()),
                SonyOp.GET_ALL_EXT_PROP_INFO to emptyList(),
            ),
            sent,
        )
        assertArrayEquals(byteArrayOf(1), camera.sent[6].third)
        assertEquals("ZV-E10", session.deviceInfo?.model)
        assertEquals(93, session.properties.value.size)
        session.close()
    }

    @Test fun cameraOutsidePcRemoteIsReported() = runTest {
        val camera = FakeTransport(deviceInfo = fixture("deviceinfo").also { stripSonyOps(it) })
        val session = CameraSession(camera, SessionTiming(settleMs = 1, pollMs = 1))
        try {
            session.start(this)
            fail("ожидали NotPcRemote")
        } catch (e: SessionException.NotPcRemote) {
            assertEquals(listOf(SonyOp.OPEN_SESSION, SonyOp.GET_DEVICE_INFO), camera.sent.map { it.first })
        }
    }

    /** Заменяет коды операций Sony (0x92xx) на неизвестные, чтобы камера выглядела «не в PC Remote». */
    private fun stripSonyOps(bytes: ByteArray) {
        val info = DeviceInfo.parse(bytes)
        // ищем массив операций по его первому элементу и портим старший байт у всех 0x92xx
        var i = 0
        while (i < bytes.size - 1) {
            if (bytes[i + 1] == 0x92.toByte() && info.operations.contains(((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i].toInt() and 0xFF))) {
                bytes[i + 1] = 0x77
            }
            i++
        }
    }
}

/** Камера-заглушка: отвечает дампами настоящей камеры и записывает, что ей отправили. */
private class FakeTransport(private val deviceInfo: ByteArray = fixture("deviceinfo")) : PtpTransport {
    val sent = mutableListOf<Triple<Int, List<Long>, ByteArray?>>()
    override val events: Flow<PtpEvent> = emptyFlow()

    override fun send(operation: Int, params: List<Long>, outData: ByteArray?): PtpReply {
        sent += Triple(operation, params, outData)
        val ok = PtpResponseCode.OK
        return when (operation) {
            SonyOp.GET_DEVICE_INFO -> PtpReply(ok, data = deviceInfo)
            SonyOp.GET_EXT_DEVICE_INFO -> PtpReply(ok, data = fixture("sony_extdeviceinfo"))
            SonyOp.GET_ALL_EXT_PROP_INFO -> PtpReply(ok, data = fixture("props_settled"))
            else -> PtpReply(ok)
        }
    }

    override fun close() {}
}
