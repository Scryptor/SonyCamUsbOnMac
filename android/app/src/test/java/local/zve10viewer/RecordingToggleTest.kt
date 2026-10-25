package local.zve10viewer

import local.zve10viewer.session.RecordingToggle
import local.zve10viewer.session.RecordingToggle.Outcome
import local.zve10viewer.session.RecordingToggle.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingToggleTest {
    @Test fun startConfirmedByCamera() {
        val toggle = RecordingToggle()
        assertTrue(toggle.press(recording = false, now = 0))
        assertEquals(Request.starting, toggle.request)
        assertNull(toggle.observe(recording = false))
        assertEquals(Outcome.Started, toggle.observe(recording = true))
        assertNull(toggle.request)
    }

    @Test fun secondPressIgnoredWhilePending() {
        val toggle = RecordingToggle()
        assertTrue(toggle.press(recording = false, now = 0))
        assertFalse(toggle.press(recording = false, now = 100))
        assertFalse(toggle.press(recording = true, now = 200))
        assertEquals(Request.starting, toggle.request)
    }

    @Test fun timeoutFails() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 1_000)
        assertNull(toggle.tick(now = 6_900))
        assertEquals(Outcome.Failed(Request.starting), toggle.tick(now = 7_100))
        assertNull(toggle.request)
        assertNull(toggle.tick(now = 20_000))
    }

    @Test fun completedWithoutChangeKeepsWaiting() {
        // сеанс ждёт подтверждения 2,5 с, а камера бывает медленнее: «не изменилось» — ещё не отказ
        val confirmed = RecordingToggle()
        confirmed.press(recording = false, now = 0)
        assertNull(confirmed.completed(recording = false))
        assertEquals(Request.starting, confirmed.request)
        assertEquals(Outcome.Started, confirmed.observe(recording = true))

        val silent = RecordingToggle()
        silent.press(recording = false, now = 0)
        assertNull(silent.completed(recording = false))
        assertEquals(Outcome.Failed(Request.starting), silent.tick(now = 6_100))
    }

    @Test fun completedConfirmsTheRequest() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertEquals(Outcome.Started, toggle.completed(recording = true))
        assertNull(toggle.request)
    }

    @Test fun completedAfterObserveIsSilent() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertEquals(Outcome.Started, toggle.observe(recording = true))
        assertNull(toggle.completed(recording = true))
        assertNull(toggle.completed(recording = null))
    }

    @Test fun stopConfirmed() {
        val toggle = RecordingToggle()
        assertTrue(toggle.press(recording = true, now = 0))
        assertEquals(Request.stopping, toggle.request)
        assertNull(toggle.observe(recording = true))
        assertEquals(Outcome.Stopped, toggle.observe(recording = false))
        assertNull(toggle.request)
    }

    @Test fun errorFails() {
        val toggle = RecordingToggle()
        toggle.press(recording = true, now = 0)
        assertEquals(Outcome.Failed(Request.stopping), toggle.completed(recording = null))
        assertNull(toggle.request)
    }

    @Test fun cameraButtonWithoutRequestIsNotAnOutcome() {
        // запись с кнопки самой камеры: рамка — по свойству, а отклика на нажатие здесь нет
        val toggle = RecordingToggle()
        assertNull(toggle.observe(recording = true))
        assertNull(toggle.observe(recording = false))
    }
}
