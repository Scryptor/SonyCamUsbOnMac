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
    private val d = KnownCamera("dddddddddddddddd", "D", "ZV-E10", host = "192.168.1.123", port = 15743, paired = true, inStrip = true)

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

    private fun CameraBook.group(): Set<String> = cameras.filter { it.linked }.map { it.id }.toSet()

    @Test fun groupIsStoredOnlyWhenSet() {
        val json = CameraBook.toJson(listOf(b, c))
        val items = JSONArray(json)
        assertTrue(items.getJSONObject(0).getBoolean("group"))
        // не в группе — поля нет; «linked» прежних версий больше не пишется
        assertFalse(items.getJSONObject(1).has("group"))
        assertFalse(json.contains("linked"))
        assertEquals(listOf(b, c), CameraBook.fromJson(json))
    }

    @Test fun linkingBringsTheMainIntoTheOneGroup() {
        val book = CameraBook(listOf(a, b.copy(linked = false), c, d))
        // связали с главной — и главная в группе
        val ab = book.withLinked(b.id, true, main = a.id)
        assertEquals(setOf(a.id, b.id), ab.group())
        val abc = ab.withLinked(c.id, true, main = a.id)
        assertEquals(setOf(a.id, b.id, c.id), abc.group())
        // отвязали — вышла только она
        assertEquals(setOf(a.id, b.id), abc.withLinked(c.id, false, main = a.id).group())
        // главная D не из группы: группа одна — D и C входят в неё
        assertEquals(setOf(a.id, b.id, c.id, d.id), ab.withLinked(c.id, true, main = d.id).group())
        // уже в группе — та же книга; незнакомая камера — тоже
        assertEquals(ab, ab.withLinked(b.id, true, main = a.id))
        assertEquals(ab, ab.withLinked("нет такой", true, main = d.id))
    }

    @Test fun aGroupOfOneIsNoGroup() {
        val book = CameraBook(listOf(a, b.copy(linked = false), c, d))
        val ab = book.withLinked(b.id, true, main = a.id)
        // отвязали последнюю — главная тоже не в группе
        assertEquals(emptySet<String>(), ab.withLinked(b.id, false, main = a.id).group())
        // главной нет — связывать не с кем
        assertEquals(emptySet<String>(), book.withLinked(b.id, true, main = null).group())
        // убрали из ленты или забыли — тоже выход из группы; вернули в ленту — не в группе
        val removed = ab.withStrip(b.id, false)
        assertFalse(removed.find(b.id)!!.inStrip)
        assertEquals(emptySet<String>(), removed.group())
        assertFalse(removed.withStrip(b.id, true).find(b.id)!!.linked)
        assertEquals(emptySet<String>(), ab.forgotten(b.id).group())
        // подключение камеры (новый id по серийному номеру, lastUsed) группу не меняет
        val (after, _) = ab.connected("SERIAL", "ZV-E10", CameraLink.WiFi(WiFiAddress("192.168.1.123", 15741)), listOf(b.id), now)
        assertTrue(after.cameras.first { it.name == "B" }.linked)
    }

    @Test fun oldLinkedFlagBecomesAGroupWithTheLastMain() {
        fun stored(vararg extra: Pair<String, String>): String = JSONArray(CameraBook.toJson(listOf(a, b.copy(linked = false), c, d))).apply {
            for (i in 0 until length()) {
                val item = getJSONObject(i)
                extra.filter { it.first == item.getString("id") }.forEach { item.put(it.second, true) }
            }
        }.toString()
        // прежние версии: «linked» — только у камер ленты, повторяли за любой главной; последней главной была A
        assertEquals(setOf(a.id, b.id, c.id), CameraBook.load(stored(b.id to "linked", c.id to "linked"), a.id, null, null).group())
        // одна связанная, и главная — она же: группы из одной не бывает
        assertEquals(emptySet<String>(), CameraBook.load(stored(b.id to "linked"), b.id, null, null).group())
        // уже новые записи — «linked» без значения
        assertEquals(setOf(c.id, d.id),
            CameraBook.load(stored(b.id to "linked", c.id to "group", d.id to "group"), a.id, null, null).group())
    }

    @Test fun followersAreTheGroupOnlyWhenTheMainIsInIt() {
        val book = CameraBook(listOf(a, b.copy(linked = false), c, d)).withLinked(b.id, true, main = a.id).withLinked(c.id, true, main = a.id)
        assertEquals(listOf(b.id, c.id), book.followers(a.id, listOf(b.id, c.id, d.id)))
        // переключились на камеру группы — она ведёт
        assertEquals(listOf(a.id, c.id), book.followers(b.id, listOf(a.id, c.id, d.id)))
        // на камеру не из группы — группа ждёт
        assertEquals(emptyList<String>(), book.followers(d.id, listOf(a.id, b.id, c.id)))
        assertEquals(emptyList<String>(), book.followers(null, listOf(a.id, b.id, c.id)))
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
