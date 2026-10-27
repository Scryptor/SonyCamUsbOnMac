package local.zve10viewer

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.test.runTest
import local.zve10viewer.monitor.FocusDrive
import local.zve10viewer.monitor.FocusFrame
import local.zve10viewer.monitor.PowerZoom
import local.zve10viewer.monitor.TouchFocus
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.sony.LiveViewFrame
import local.zve10viewer.sony.SonyControl
import local.zve10viewer.sony.SonyExtInfo
import local.zve10viewer.sony.SonyOp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Фокус касанием, зум и шкала фокуса — те же проверки, что у программы для Mac (CameraAssistTests.swift). */
class CameraAssistTest {
    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name.bin")!!.readBytes()
    private fun near(a: Float, b: Double, tolerance: Double = 0.001) = abs(a - b) < tolerance

    // рамки фокуса в кадре live view

    @Test fun realLiveViewFrameHasAnEmptyFocusFrameBlock() {
        // дамп ZV-E10: блок FocalFrameInfo версии 1.01 есть (смещение 1012, 120 байт), рамок нет — тёмная комната
        val data = fixture("lv_frame_raw")
        assertTrue(FocusFrame.fromLiveView(data).isEmpty())
        assertNotNull(LiveViewFrame.locate(data))
    }

    @Test fun focusFramesSurviveEncodingAndKeepTheirCentre() {
        val frames = listOf(
            FocusFrame(FocusFrame.Kind.focus, Rect(0.4f, 0.3f, 0.5f, 0.5f), true),
            FocusFrame(FocusFrame.Kind.tracking, Rect(0.6f, 0.5f, 0.75f, 0.7f), false),
        )
        val parsed = FocusFrame.fromInfo(FocusFrame.encode(frames))
        assertEquals(2, parsed.size)
        assertTrue(parsed[0].kind == FocusFrame.Kind.focus && parsed[0].focused)
        assertTrue(near(parsed[0].rect.center.x, 0.45) && near(parsed[0].rect.center.y, 0.4))
        assertTrue(near(parsed[0].rect.width, 0.1))
        assertTrue(parsed[1].kind == FocusFrame.Kind.tracking && !parsed[1].focused)
    }

    @Test fun focusFramesAreReadFromTheLiveViewHeader() {
        val info = FocusFrame.encode(listOf(FocusFrame(FocusFrame.Kind.focus, Rect(0.2f, 0.2f, 0.3f, 0.3f), true)))
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        val header = java.io.ByteArrayOutputStream()
        for (v in listOf(16 + info.size, jpeg.size, 16, info.size)) for (shift in 0 until 32 step 8) header.write(v shr shift and 0xFF)
        val data = header.toByteArray() + info + jpeg
        assertArrayEquals(jpeg, LiveViewFrame.jpeg(data))
        val frames = FocusFrame.fromLiveView(data)
        assertEquals(1, frames.size)
        assertTrue(near(frames[0].rect.center.x, 0.25))
        // битый размер — рамок нет, а не падение
        val broken = data.copyOf().also { it[12] = 0xFF.toByte() }
        assertTrue(FocusFrame.fromLiveView(broken).isEmpty())
    }

    // фокус касанием

    @Test fun touchFocusPicksTheCommandByFocusArea() {
        val controls = SonyExtInfo.parse(fixture("sony_extdeviceinfo"))!!.controlCodes.toSet()
        // у ZV-E10 есть обе команды
        assertTrue(SonyControl.AF_AREA_POSITION in controls && SonyControl.REMOTE_TOUCH in controls)
        assertEquals(TouchFocus.Method.areaPosition, TouchFocus.method(0x8004, 0x102, 1, controls))
        assertEquals(TouchFocus.Method.areaPosition, TouchFocus.method(0x8004, 0x2, 1, controls))
        assertEquals(TouchFocus.Method.areaPosition, TouchFocus.method(0x8004, 0x205, 1, controls))
        assertEquals(TouchFocus.Method.remoteTouch, TouchFocus.method(0x8004, 0x1, 1, controls))
        assertNull(TouchFocus.method(0x8004, 0x1, 0, controls))
        assertNull(TouchFocus.method(1, 0x102, 1, controls))
        assertNull(TouchFocus.method(0x8004, 0x102, 1, emptySet()))
    }

    @Test fun touchFocusMapsTheFrameTo640x480() {
        assertEquals(0L, TouchFocus.encode(0.0, 0.0))
        assertEquals((639L shl 16) or 479L, TouchFocus.encode(1.0, 1.0))
        assertEquals((320L shl 16) or 240L, TouchFocus.encode(0.5, 0.5))
        assertEquals(639L shl 16, TouchFocus.encode(1.4, -0.2))
        val back = TouchFocus.decode(TouchFocus.encode(0.25, 0.75))
        assertTrue(abs(back.x - 0.25) < 0.002 && abs(back.y - 0.75) < 0.002)
    }

    // зум и шкала фокуса

    @Test fun zoomBarFromTheRealCamera() {
        val bar = PowerZoom.Bar.of(0x0100_0000)
        assertTrue(bar.boxes == 1 && bar.box == 0 && bar.position == 0)
        assertEquals(0.0, bar.fraction!!, 1e-9)
        assertEquals(0.75, PowerZoom.Bar.of(0x0201_0032).fraction!!, 1e-9)
        assertNull(PowerZoom.Bar.of(0).fraction)
        assertEquals("×1,0", PowerZoom.label(1000))
        assertEquals("×1,5", PowerZoom.label(1500))
        assertNull(PowerZoom.label(null))
        assertTrue(PowerZoom.isAvailable(1, setOf(SonyControl.ZOOM_OPERATION)))
        assertFalse(PowerZoom.isAvailable(0, setOf(SonyControl.ZOOM_OPERATION)))
    }

    @Test fun focusDriveSplitsUnitsIntoCommands() {
        assertEquals(listOf(7L, 7L, 3L), FocusDrive.commands(17))
        assertEquals(listOf(-1L, -1L), FocusDrive.commands(-2))
        assertTrue(FocusDrive.commands(0).isEmpty())
        assertEquals(-7L, FocusDrive.value(FocusDrive.Step.large, nearer = true))
        assertEquals(1L, FocusDrive.value(FocusDrive.Step.small, nearer = false))
        assertEquals(-28, FocusDrive.steps(-0.1))
        assertEquals(0, FocusDrive.steps(0.001))
        assertFalse(FocusDrive.isAvailable(0, setOf(SonyControl.NEAR_FAR)))
        assertTrue(FocusDrive.isAvailable(1, setOf(SonyControl.NEAR_FAR)))
    }

    // команды сеанса

    @Test fun sessionKnowsControlCodesAndSendsTypedControls() = runTest {
        val camera = FakeCamera()
        val session = CameraSession(camera, SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500))
        session.start(this)
        assertTrue(SonyControl.ZOOM_OPERATION in session.controlCodes)
        // зум к широкому — INT8 −1, точка AF — UINT32, Near/Far — INT16
        session.control(SonyControl.ZOOM_OPERATION, -1, PtpType.INT8)
        session.control(SonyControl.AF_AREA_POSITION, TouchFocus.encode(0.5, 0.5), PtpType.UINT32)
        session.control(SonyControl.NEAR_FAR, -3, PtpType.INT16)
        session.pressButton(SonyControl.REMOTE_TOUCH_CANCEL)
        val controls = camera.sent.filter { it.first == SonyOp.CONTROL_DEVICE }
        assertEquals(listOf(0xD2DDL, 0xD2DCL, 0xD2D1L, 0xD2E5L, 0xD2E5L), controls.map { it.second[0] })
        assertArrayEquals(byteArrayOf(0xFF.toByte()), controls[0].third)
        assertArrayEquals(byteArrayOf(0xF0.toByte(), 0x00, 0x40, 0x01), controls[1].third)
        assertArrayEquals(byteArrayOf(0xFD.toByte(), 0xFF.toByte()), controls[2].third)
        assertArrayEquals(byteArrayOf(0x02, 0x00), controls[3].third)
        assertArrayEquals(byteArrayOf(0x01, 0x00), controls[4].third)
        session.close()
    }
}
