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
        assertNull(toggle.observe(recording = false, now = 200))
        assertEquals(Outcome.Started, toggle.observe(recording = true, now = 800))
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
        assertNull(confirmed.completed(recording = false, now = 2_500))
        assertEquals(Request.starting, confirmed.request)
        assertEquals(Outcome.Started, confirmed.observe(recording = true, now = 3_000))

        val silent = RecordingToggle()
        silent.press(recording = false, now = 0)
        assertNull(silent.completed(recording = false, now = 2_500))
        assertEquals(Outcome.Failed(Request.starting), silent.tick(now = 6_100))
    }

    @Test fun completedConfirmsTheRequest() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertEquals(Outcome.Started, toggle.completed(recording = true, now = 900))
        assertNull(toggle.request)
    }

    @Test fun completedAfterObserveIsSilent() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertEquals(Outcome.Started, toggle.observe(recording = true, now = 800))
        assertNull(toggle.completed(recording = true, now = 900))
        assertNull(toggle.completed(recording = null, now = 900))
    }

    @Test fun stopConfirmed() {
        val toggle = RecordingToggle()
        assertTrue(toggle.press(recording = true, now = 0))
        assertEquals(Request.stopping, toggle.request)
        assertNull(toggle.observe(recording = true, now = 200))
        assertEquals(Outcome.Stopped, toggle.observe(recording = false, now = 900))
        assertNull(toggle.request)
    }

    @Test fun errorFails() {
        val toggle = RecordingToggle()
        toggle.press(recording = true, now = 0)
        assertEquals(Outcome.Failed(Request.stopping), toggle.completed(recording = null, now = 2_500))
        assertNull(toggle.request)
    }

    @Test fun abortedRightAfterStart() {
        // камера начала по нажатию и сама бросила в первые секунды — отказ, а не молча погасшая рамка
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertNull(toggle.observe(recording = false, now = 100))
        assertEquals(Outcome.Started, toggle.observe(recording = true, now = 800))
        assertNull(toggle.observe(recording = true, now = 1_000))
        assertEquals(Outcome.Aborted, toggle.observe(recording = false, now = 1_200))
        assertNull(toggle.observe(recording = false, now = 1_500))
    }

    @Test fun stoppedByCameraLater() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        toggle.observe(recording = true, now = 800)
        assertEquals(Outcome.StoppedByCamera, toggle.observe(recording = false, now = 10_000))
    }

    @Test fun stoppedFromProgramIsOnlyStopped() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        toggle.observe(recording = true, now = 800)
        toggle.press(recording = true, now = 1_500)
        assertEquals(Outcome.Stopped, toggle.observe(recording = false, now = 2_000))
        assertNull(toggle.observe(recording = false, now = 2_200))
    }

    @Test fun stopConfirmedByCallIsNotStoppedByCamera() {
        // вызов подтвердил остановку раньше свойств — конец записи, пришедший потом, уже наш
        val toggle = RecordingToggle()
        toggle.observe(recording = true, now = 0)
        toggle.press(recording = true, now = 1_000)
        assertEquals(Outcome.Stopped, toggle.completed(recording = false, now = 1_800))
        assertNull(toggle.observe(recording = false, now = 1_900))
    }

    @Test fun cameraButtonRecordingEndsAsStoppedByCamera() {
        // запись с кнопки самой камеры: начало — без исхода (рамка по свойству), конец — сообщение
        val toggle = RecordingToggle()
        assertNull(toggle.observe(recording = false, now = 0))
        assertNull(toggle.observe(recording = true, now = 1_000))
        assertNull(toggle.observe(recording = true, now = 5_000))
        assertEquals(Outcome.StoppedByCamera, toggle.observe(recording = false, now = 6_000))
    }

    @Test fun startFromCallThenDroppedIsAborted() {
        // свойство «пишет» до экрана не дошло, а вызов его видел: обрыв всё равно замечаем
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        assertNull(toggle.observe(recording = false, now = 100))
        assertEquals(Outcome.Started, toggle.completed(recording = true, now = 900))
        assertEquals(Outcome.Aborted, toggle.observe(recording = false, now = 1_100))
    }

    @Test fun lateCallAfterAbortIsSilent() {
        val toggle = RecordingToggle()
        toggle.press(recording = false, now = 0)
        toggle.observe(recording = true, now = 800)
        toggle.observe(recording = false, now = 1_200)
        assertNull(toggle.completed(recording = true, now = 2_500))
        assertNull(toggle.observe(recording = false, now = 2_600))
    }

    @Test fun messages() {
        assertNull(RecordingToggle.message(Outcome.Started, refusal = null))
        assertNull(RecordingToggle.message(Outcome.Stopped, refusal = null))
        assertEquals("Камера не начала запись", RecordingToggle.message(Outcome.Failed(Request.starting), refusal = null))
        assertEquals("Камера не остановила запись", RecordingToggle.message(Outcome.Failed(Request.stopping), refusal = null))
        assertEquals("Камера занята — запись не началась", RecordingToggle.message(Outcome.Failed(Request.starting), refusal = 0x2019))
        assertEquals("Камера занята — запись не остановилась", RecordingToggle.message(Outcome.Failed(Request.stopping), refusal = 0x2019))
        assertEquals("Камера не приняла нажатие — запись не началась",
            RecordingToggle.message(Outcome.Failed(Request.starting), refusal = 0x2002))
        assertEquals("Камера начала запись и сразу остановила", RecordingToggle.message(Outcome.Aborted, refusal = null))
        assertEquals("Запись остановлена на камере", RecordingToggle.message(Outcome.StoppedByCamera, refusal = null))
    }
}
