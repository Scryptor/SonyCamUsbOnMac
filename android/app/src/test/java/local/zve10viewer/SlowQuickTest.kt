package local.zve10viewer

import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.RecordingModes
import local.zve10viewer.settings.ShootingMode
import local.zve10viewer.settings.SlowQuick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SlowQuickTest {
    @Test fun shootingModeFollowsTheExposureProgram() {
        assertEquals(ShootingMode.video, ShootingMode.of(0x78053))
        assertEquals(ShootingMode.slowQuick, ShootingMode.of(0x9805C))
        assertEquals(ShootingMode.photo, ShootingMode.of(0x1))
        assertEquals(ShootingMode.photo, ShootingMode.of(0x48000))
        // замедленная съёмка — та же видеосъёмка: интерфейс видео, запись кнопкой REC
        assertEquals(CaptureMode.video, ShootingMode.slowQuick.captureMode)
        assertEquals(CaptureMode.photo, ShootingMode.photo.captureMode)
    }

    @Test fun shootingModeCounterpartKeepsTheLetter() {
        val settable = listOf(0x10002L, 0x20003L, 0x30004L, 0x1L, 0x78050L, 0x78051L, 0x78052L, 0x78053L,
            0x98059L, 0x9805AL, 0x9805BL, 0x9805CL, 0x48000L)
        assertEquals(0x9805CL, ShootingMode.counterpart(0x78053, ShootingMode.slowQuick, settable))   // видео M → S&Q M
        assertEquals(0x78051L, ShootingMode.counterpart(0x9805A, ShootingMode.video, settable))       // S&Q A → видео A
        assertEquals(0x9805CL, ShootingMode.counterpart(0x1, ShootingMode.slowQuick, settable))       // M → S&Q M
        assertEquals(0x98059L, ShootingMode.counterpart(0x48000, ShootingMode.slowQuick, settable))   // авто → S&Q P
        assertEquals(0x1L, ShootingMode.counterpart(0x9805C, ShootingMode.photo, settable))           // S&Q M → M
        assertNull(ShootingMode.counterpart(0x9805C, ShootingMode.slowQuick, settable))               // уже там
        assertNull(ShootingMode.counterpart(0x78053, ShootingMode.slowQuick, listOf(0x78053L)))       // камера не даёт
    }

    @Test fun slowQuickFrameRateComesFromTheSlowestShutter() {
        // так на ZV-E10 (PAL): самая длинная выдержка, которую камера даёт в S&Q при частоте съёмки 100…1 к/с
        val observed = listOf(0x10064L to 100, 0x10032L to 50, 0x10019L to 25, 0x1000DL to 15,
            0x10006L to 8, 0x10003L to 4, 0x5000AL to 2, 0xA000AL to 1)
        for ((slowest, fps) in observed) {
            assertEquals(fps, SlowQuick.captureFrameRate(listOf(0x10FA0L, 0x103E8L, slowest), pal = true))
        }
        assertNull(SlowQuick.captureFrameRate(emptyList(), pal = true))
        // NTSC: самая длинная 1/125 — 120 к/с
        assertEquals(120, SlowQuick.captureFrameRate(listOf(0x1007DL, 0x103E8L), pal = false))
    }

    @Test fun slowQuickSpeedLabel() {
        assertEquals("×4 медленнее", SlowQuick.speedLabel(100, 25))
        assertEquals("обычная скорость", SlowQuick.speedLabel(100, 100))
        assertEquals("×25 быстрее", SlowQuick.speedLabel(1, 25))
        assertEquals("×3,3 быстрее", SlowQuick.speedLabel(15, 50))
        assertEquals(100, RecordingModes.frameRate(0x1D))
        assertEquals(25, RecordingModes.frameRate(0x05))
    }
}
