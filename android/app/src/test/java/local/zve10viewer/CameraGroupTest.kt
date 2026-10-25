package local.zve10viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.GroupFollower
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.settings.CameraBook
import local.zve10viewer.settings.CameraLink
import local.zve10viewer.settings.GroupFailures
import local.zve10viewer.settings.KnownCamera
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.WhiteBalanceTemperature
import local.zve10viewer.settings.WiFiAddress
import local.zve10viewer.sony.PropertyRange
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Группа камер: связанные камеры ленты повторяют за главной настройки, пресеты и запись. */
class CameraGroupTest {
    private val now = 1_761_400_000_000L
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)
    private val a = KnownCamera("aaaaaaaaaaaaaaaa", "A", "ZV-E10", host = "192.168.1.123", paired = true, inStrip = true)
    private val b = KnownCamera("bbbbbbbbbbbbbbbb", "B", "ZV-E10", host = "192.168.1.123", port = 15741, paired = true, inStrip = true,
        linked = true)
    private val c = KnownCamera("cccccccccccccccc", "C", "ZV-E10", host = "192.168.1.123", port = 15742, paired = true, inStrip = true)

    private suspend fun ready(camera: FakeCamera, scope: CoroutineScope): CameraSession =
        CameraSession(camera, fast).also {
            it.start(scope)
            it.waitUntilReady()
            camera.sent.clear()
        }

    /** Повтор за главной в своей области: обмен с камерой идёт в Dispatchers.IO — idle() ждёт, пока дойдут все записи. */
    private fun CoroutineScope.follower(session: CameraSession, onFailed: (SettingID, Long) -> Unit = { _, _ -> }): Pair<GroupFollower, Job> {
        val work = Job(coroutineContext[Job])
        return GroupFollower(session, CoroutineScope(coroutineContext + work), onFailed) to work
    }

    private suspend fun Job.idle() = children.toList().forEach { it.join() }

    private fun FakeCamera.written(): List<Int> = sent.filter { it.first == SonyOp.SET_EXT_PROP_VALUE }.map { it.second.first().toInt() }
    private fun FakeCamera.value(code: Int): Long = props.first { it.code == code }.current

    @Test fun linkedIsStoredOnlyWhenSet() {
        val json = CameraBook.toJson(listOf(b, c))
        val items = JSONArray(json)
        assertTrue(items.getJSONObject(0).getBoolean("linked"))
        // не связана — поля нет: запись та же, что у прежних версий
        assertFalse(items.getJSONObject(1).has("linked"))
        assertEquals(listOf(b, c), CameraBook.fromJson(json))
    }

    @Test fun removingFromTheStripAlsoUnlinks() {
        val book = CameraBook(listOf(a, b.copy(linked = false), c))
        val linked = book.withLinked(b.id, true)
        assertTrue(linked.find(b.id)!!.linked)
        assertEquals(book, book.withLinked("нет такой", true))
        assertFalse(linked.withLinked(b.id, false).find(b.id)!!.linked)
        // убрали из ленты — и не связана; вернули в ленту — связи нет
        val removed = linked.withStrip(b.id, false)
        assertFalse(removed.find(b.id)!!.inStrip)
        assertFalse(removed.find(b.id)!!.linked)
        assertFalse(removed.withStrip(b.id, true).find(b.id)!!.linked)
        // подключение камеры (новый id по серийному номеру, lastUsed) связь не снимает
        val (after, _) = linked.connected("SERIAL", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.123", 15741)), listOf(b.id), now)
        assertTrue(after.cameras.first { it.name == "B" }.linked)
    }

    @Test fun switchingToALinkedCameraKeepsTheGroupTogether() {
        val book = CameraBook(listOf(a, b, c))
        // главной стала связанная B — прежняя главная A (уже в ленте) тоже связана
        assertTrue(book.switchedTo(b.id, a.id).find(a.id)!!.linked)
        // главной стала несвязанная C — связи не меняются
        assertEquals(book, book.switchedTo(c.id, a.id))
        // прежней главной нет или она не в ленте (была по кабелю) — тоже
        assertEquals(book, book.switchedTo(b.id, null))
        val usb = CameraBook(listOf(a.copy(inStrip = false), b, c))
        assertEquals(usb, usb.switchedTo(b.id, a.id))
    }

    @Test fun groupFailuresMakeOneStatusLine() {
        var failures = GroupFailures()
        assertNull(failures.text)
        failures = failures.plus("C", GroupFailures.label(SettingID.iso, 51200)).plus("C", GroupFailures.label(SettingID.iso, 51200))
        assertEquals("Не удалось на «C»: ISO 51200", failures.text)
        failures = failures.plus("B", GroupFailures.RECORDING).plus("C", GroupFailures.label(SettingID.aperture, 180))
        assertEquals("Не удалось на «C»: ISO 51200, f/1.8; на «B»: запись", failures.text)
        assertEquals("Фокус AF-C", GroupFailures.label(SettingID.focusMode, 0x8004))
        assertEquals("1/50", GroupFailures.label(SettingID.shutter, 0x10032))
        assertEquals("AWB", GroupFailures.label(SettingID.whiteBalance, 2))
        assertEquals("Видео M", GroupFailures.label(SettingID.exposureProgram, 0x78053))
        assertEquals("3200 K", GroupFailures.label(SettingID.colorTemperature, 3200))
    }

    @Test fun quickStepsDoNotReportTheValuesInBetween() = runBlocking {
        val camera = FakeCamera()
        val session = ready(camera, this)
        val failed = mutableListOf<Pair<SettingID, Long>>()
        val (follower, work) = follower(session) { id, value -> failed += id to value }
        // колесом: 800 камера выбрать не даёт, но следом пришло 400 — судим только его
        follower.write(SettingID.iso, 800)
        follower.write(SettingID.iso, 400)
        work.idle()
        assertEquals(400L, camera.value(SonyProp.ISO))
        assertEquals(emptyList<Pair<SettingID, Long>>(), failed)
        session.close()
        work.cancel()
    }

    @Test fun lastValueTheCameraDoesNotTakeIsReported() = runBlocking {
        val camera = FakeCamera().apply { ignored += SonyProp.FOCUS_MODE }
        val session = ready(camera, this)
        val failed = mutableListOf<Pair<SettingID, Long>>()
        val (follower, work) = follower(session) { id, value -> failed += id to value }
        // 800 нет среди значений камеры; фокус камера не подтвердила
        follower.write(SettingID.iso, 800)
        follower.write(SettingID.focusMode, 2)
        work.idle()
        assertEquals(setOf(SettingID.iso to 800L, SettingID.focusMode to 2L), failed.toSet())
        assertEquals(200L, camera.value(SonyProp.ISO))
        session.close()
        work.cancel()
    }

    @Test fun sameValueIsNotWrittenAgain() = runBlocking {
        val camera = FakeCamera()
        val session = ready(camera, this)
        val (follower, work) = follower(session) { id, _ -> fail("$id — не писали") }
        follower.write(SettingID.iso, 200)
        work.idle()
        assertEquals(emptyList<Int>(), camera.written())
        session.close()
        work.cancel()
    }

    @Test fun temperatureSwitchesWhiteBalanceToColorTemperatureFirst() = runBlocking {
        // та же цифра, но не в «Цвет. темп.» — ещё не то же самое
        val camera = FakeCamera().apply { props += FakeCamera.Prop(SonyProp.COLOR_TEMP, PtpType.UINT16, 3200, range = PropertyRange(2500, 9900, 100)) }
        val session = ready(camera, this)
        val failed = mutableListOf<Pair<SettingID, Long>>()
        val (follower, work) = follower(session) { id, value -> failed += id to value }
        follower.write(SettingID.colorTemperature, 3200)
        work.idle()
        assertEquals(listOf(SonyProp.WHITE_BALANCE, SonyProp.COLOR_TEMP), camera.written())
        assertEquals(WhiteBalanceTemperature.MANUAL_MODE, camera.value(SonyProp.WHITE_BALANCE))
        assertEquals(3200L, camera.value(SonyProp.COLOR_TEMP))
        // уже в «Цвет. темп.» и та же температура — не пишем
        follower.write(SettingID.colorTemperature, 3200)
        work.idle()
        assertEquals(2, camera.written().size)
        assertTrue(failed.isEmpty())
        session.close()
        work.cancel()
    }

    @Test fun recordingFollowsTheMainEvenWhenStopComesBeforeTheCameraAnswered() = runBlocking {
        val camera = FakeCamera()
        val session = ready(camera, this)
        val (follower, work) = follower(session)
        // «пуск» и сразу «стоп»: второе нажатие ждёт ответа камеры на первое — камера не остаётся писать
        val start = async { follower.setRecording(true) }
        val stop = async { follower.setRecording(false) }
        assertTrue(start.await())
        assertTrue(stop.await())
        assertEquals(0L, camera.value(SonyProp.MOVIE_RECORDING_STATE))
        // уже не пишет — «стоп» не нажимает
        val presses = camera.sent.count { it.first == SonyOp.CONTROL_DEVICE }
        assertTrue(follower.setRecording(false))
        assertEquals(presses, camera.sent.count { it.first == SonyOp.CONTROL_DEVICE })
        session.close()
        work.cancel()
    }

    @Test fun afterStopNothingIsWrittenOrJudged() = runBlocking {
        val camera = FakeCamera()
        val session = ready(camera, this)
        val (follower, work) = follower(session) { id, _ -> fail("$id — сеанс уже не у ленты") }
        follower.write(SettingID.iso, 800)
        // сеанс отдали главному экрану: начатая запись доходит, но её не судим, новых нет
        follower.stop()
        follower.write(SettingID.iso, 400)
        work.idle()
        assertEquals(emptyList<Int>(), camera.written())
        assertFalse(follower.setRecording(true))
        session.close()
        work.cancel()
    }
}
