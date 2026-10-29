package local.zve10viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.session.CameraEvent
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.settings.SettingID
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Что камера сообщает о себе сверх свойств — подписи списков, версия кода, данные объектива — в том виде, как их держит
 * приложение ([CameraInfo]): когда спрашивает, что оставляет, что стирает и что пишет в журнал. Правила из круга исправлений Mac:
 * 0xC218 в журнал раз за исход, тайм-аут — второго запроса нет и камеру больше не спрашивают, названия заменяются итогом каждой
 * загрузки и стираются со сменой камеры. Камеры — [newerCamera]; ожидания — [eventually] и защёлки с потолком, не бесконечные.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraInfoTest {
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)
    private val notes = ArrayList<String>()

    private suspend fun ready(camera: PtpTransport, scope: CoroutineScope): CameraSession {
        val session = CameraSession(camera, fast)
        session.start(scope)
        session.waitUntilReady()
        return session
    }

    private fun FakeCamera.count(operation: Int): Int = sent.count { it.first == operation }

    private fun TestScope.newInfo() = CameraInfo(this) { notes += it }

    private fun lensFlag(camera: FakeCamera) = camera.props.first { it.code == SonyProp.LENS_INFO_ENABLED }

    // MARK: - Что спрашиваем при подключении

    @Test fun aCameraThatListsNothingNewIsNotAskedAndShowsNothing() = runTest {
        val cam = FakeCamera()
        for (operation in NEW_OPERATIONS) cam.answers[operation] = { PtpReply(PtpResponseCode.OK, listOf(0x0136L), ByteArray(64)) }
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "zv-e10", session.properties.value).join()
        assertTrue(cam.sent.none { it.first in NEW_OPERATIONS })
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.vendorCodeVersion)
        assertNull(info.lens)
        assertTrue(notes.toString(), notes.isEmpty())
    }

    @Test fun aNewerCameraIsAskedOnceForEachThingAfterReadyAndEverythingIsKept() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        assertEquals(listOf(listOf(0L)), cam.sent.filter { it.first == SonyOp.GET_DISPLAY_STRING_LIST }.map { it.second })
        assertEquals(1, cam.count(SonyOp.GET_VENDOR_CODE_VERSION))
        assertEquals(listOf(listOf(2L)), cam.sent.filter { it.first == SonyOp.GET_LENS_INFORMATION }.map { it.second })
        assertEquals(setOf(0x13L, 0x15L), info.strings.lists.keys)
        assertEquals("FL", info.strings.names(SettingID.creativeLook)!![6])
        assertEquals(310, info.vendorCodeVersion)
        assertEquals(12, info.lens!!.pairs.size)
        // всё, что получено, — по строке в журнале панели отладки
        assertEquals(notes.toString(), 3, notes.size)
        assertTrue(notes.toString(), "подписи камеры (0x9215): 0x13 — 18 шт., 0x15 — 2 шт." in notes)
        assertTrue(notes.toString(), "версия кода производителя (0x9216): 3.10" in notes)
        assertTrue(notes.toString(), "данные объектива (0x9223): таблиц 1, пар 12, шкала 0…65535" in notes)
        // 0xE086 был 1 уже при подключении: «стал 1» — это не он, повторно таблицу не просят
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), session))
        assertEquals(1, cam.count(SonyOp.GET_LENS_INFORMATION))
    }

    @Test fun lensDataIsAskedOnlyWhileTheCameraHasItEnabled() = runTest {
        val cam = newerCamera(lensEnabled = 0)
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        assertEquals(0, cam.count(SonyOp.GET_LENS_INFORMATION))
        assertNull(info.lens)
        // подписи и версия от этого не зависят
        assertEquals(310, info.vendorCodeVersion)
        assertFalse(info.strings.lists.isEmpty())
    }

    @Test fun aCameraThatListsNothingNewIgnoresTheEventsAndTheLensFlagToo() = runTest {
        // ZV-E10 таких событий не шлёт; но пришло бы — спрашивать нечего (операций у неё нет), и в журнал это не попадает
        val cam = FakeCamera()
        for (operation in NEW_OPERATIONS) cam.answers[operation] = { PtpReply(PtpResponseCode.OK, listOf(0x0136L), ByteArray(64)) }
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "zv-e10", session.properties.value).join()
        assertNull(info.handle(CameraEvent.DisplayListChanged(0x13), session))
        assertNull(info.handle(CameraEvent.DisplayListChanged(0), session))
        assertNull(info.handle(CameraEvent.LensChanged, session))
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), session))
        assertTrue(cam.sent.none { it.first in NEW_OPERATIONS })
        assertTrue(notes.toString(), notes.isEmpty())
        assertEquals(DisplayStrings(), info.strings)
    }

    // MARK: - Исправление 1: 0xC218 в журнал раз за исход

    @Test fun theFocusResultGoesToTheJournalOncePerOutcomeAndSession() = runTest {
        val session = ready(newerCamera(), backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        notes.clear()
        // во время перевода событие приходит на каждую запись цели, раз в 100 мс: журнал в 200 строк оборачивался бы за 20 с
        repeat(60) { info.handle(CameraEvent.FocusPositionResult(true), session) }
        assertEquals(notes.toString(), 1, notes.count { "0xC218" in it })
        assertTrue(notes.single(), "дошёл до цели" in notes.single())
        // другой исход — своя строка, и тоже одна
        repeat(60) { info.handle(CameraEvent.FocusPositionResult(false), session) }
        assertEquals(notes.toString(), 2, notes.count { "0xC218" in it })
        assertTrue(notes.last(), "не дошёл" in notes.last())
        // новый сеанс — снова по строке
        info.pause()
        info.begin(session, "ilce", session.properties.value).join()
        notes.clear()
        repeat(5) { info.handle(CameraEvent.FocusPositionResult(true), session) }
        assertEquals(1, notes.count { "0xC218" in it })
    }

    // MARK: - Исправление 2: тайм-аут — второго запроса нет, камеру больше не спрашивают

    @Test fun aCameraThatDoesNotAnswerIsAskedOnceAndNeverAgainUntilTheAppRestarts() = runTest {
        val cam = newerCamera()
        cam.timeoutOn += NEW_OPERATIONS
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "cam-1", session.properties.value).join()
        // 0x9215 — один раз, без запасного 0x13; 0x9216 не спрашивали вовсе; 0x9223 уже стоял в очереди рядом с 0x9215
        assertEquals(1, cam.count(SonyOp.GET_DISPLAY_STRING_LIST))
        assertEquals(0, cam.count(SonyOp.GET_VENDOR_CODE_VERSION))
        assertEquals(1, cam.count(SonyOp.GET_LENS_INFORMATION))
        // одна строка о том, что камера не отвечает, а не по строке на операцию
        val silent = notes.filter { "не ответила на операцию" in it }
        assertEquals(notes.toString(), 1, silent.size)
        assertTrue(silent.single(), "0x9215" in silent.single() && "до перезапуска программы" in silent.single())
        assertEquals(1, notes.size)
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.lens)

        // следующий сеанс той же камеры: тихое переподключение, свежая сессия — новых операций ей не шлём
        info.pause()
        val again = newerCamera()
        val second = ready(again, backgroundScope)
        info.begin(second, "cam-1", second.properties.value).join()
        assertTrue(again.sent.none { it.first in NEW_OPERATIONS })
        // события и смена 0xE086 в этом сеансе её тоже не будят
        assertNull(info.handle(CameraEvent.DisplayListChanged(0x13), second))
        assertNull(info.handle(CameraEvent.LensChanged, second))
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(0)), second))
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), second))
        assertTrue(again.sent.none { it.first in NEW_OPERATIONS })
        // запись переживает и уход камеры (reset), и новое подключение
        info.reset()
        val third = newerCamera()
        val last = ready(third, backgroundScope)
        info.begin(last, "cam-1", last.properties.value).join()
        assertTrue(third.sent.none { it.first in NEW_OPERATIONS })
        assertEquals(1, notes.count { "не ответила на операцию" in it })
        // другая камера не записана — её спрашиваем как обычно
        info.reset()
        val other = newerCamera()
        val otherSession = ready(other, backgroundScope)
        info.begin(otherSession, "cam-2", otherSession.properties.value).join()
        assertEquals(1, other.count(SonyOp.GET_DISPLAY_STRING_LIST))
        assertEquals(310, info.vendorCodeVersion)
    }

    @Test fun aCameraWithoutAnIdIsAskedAgainEveryTime() = runTest {
        val cam = newerCamera()
        cam.timeoutOn += SonyOp.GET_DISPLAY_STRING_LIST
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, null, session.properties.value).join()
        // записать некого: строки «до перезапуска программы» нет — тайм-аут назван, как любой другой отказ, — и в следующий раз
        // камеру спросят снова
        assertTrue(notes.toString(), notes.none { "до перезапуска программы" in it })
        assertTrue(notes.toString(), "подписи камеры (0x9215): нет ответа" in notes)
        val again = newerCamera()
        val next = ready(again, backgroundScope)
        info.pause()
        info.begin(next, null, next.properties.value).join()
        assertEquals(1, again.count(SonyOp.GET_DISPLAY_STRING_LIST))
    }

    @Test fun otherFailuresAreJournaledPerOperationAndDoNotSilenceTheCamera() = runTest {
        val cam = newerCamera()
        cam.answers[0x9216] = { PtpReply(0x2002) }
        cam.answers[0x9223] = { PtpReply(PtpResponseCode.OK, data = byteArrayOf(8, 0, 0, 0, 40, 0, 0, 0)) }
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        // подписи прочитались, версия и таблица — нет: у каждой своя строка, и лучше без расстояния, чем с неверным
        assertFalse(info.strings.lists.isEmpty())
        assertNull(info.vendorCodeVersion)
        assertNull(info.lens)
        assertTrue(notes.toString(), notes.any { it == "версия кода производителя (0x9216): камера ответила 0x2002" })
        assertTrue(notes.toString(), notes.any { it.startsWith("данные объектива (0x9223): ответ не разобран") })
        // это не молчание: камеру спросят и в следующем сеансе
        info.pause()
        val again = newerCamera()
        val next = ready(again, backgroundScope)
        info.begin(next, "ilce", next.properties.value).join()
        assertEquals(1, again.count(SonyOp.GET_DISPLAY_STRING_LIST))
        assertEquals(310, info.vendorCodeVersion)
    }

    // MARK: - Исправление 3: итог каждой загрузки — в названиях; смена камеры стирает

    @Test fun everyFinishedLoadReplacesTheNamesAlsoAnEmptyOne() = runTest {
        val session = ready(newerCamera(), backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        assertFalse(info.strings.lists.isEmpty())
        // тот же id, но теперь камера не отдаёт ничего прочитанного (и версии, и таблицы): прежнее не остаётся
        val bare = newerCamera(lensEnabled = 0)
        bare.answers[0x9215] = { PtpReply(PtpResponseCode.OK, data = byteArrayOf(1, 2, 3)) }
        bare.answers[0x9216] = { PtpReply(PtpResponseCode.OK) }
        val next = ready(bare, backgroundScope)
        info.pause()
        info.begin(next, "ilce", next.properties.value).join()
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.vendorCodeVersion)
        // обе попытки (тип 0 и 0x13) названы в журнале с причиной
        assertTrue(notes.toString(), notes.count { it.startsWith("подписи камеры (0x9215, тип 0x") && "ответ не разобран" in it } >= 2)
    }

    @Test fun namesAreClearedAtOnceWhenTheCameraThatComesBackIsNotTheSameOne() = runTest {
        val session = ready(newerCamera(), backgroundScope)
        val info = newInfo()
        info.begin(session, "cam-1", session.properties.value).join()
        assertFalse(info.strings.lists.isEmpty())
        assertNotNull(info.lens)
        // тихое переподключение по Wi-Fi Direct (у всех камер один адрес) попало на другую: до опроса, через секунды после
        // готовности сеанса, её значения не должны называться чужими названиями
        info.pause()
        info.identify("cam-2")
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.vendorCodeVersion)
        assertNull(info.lens)
        // а та же камера возвращается — что о ней известно, остаётся на экране, пока не придёт новое
        info.begin(session, "cam-2", session.properties.value).join()
        info.pause()
        info.identify("cam-2")
        assertFalse(info.strings.lists.isEmpty())
        assertNotNull(info.lens)
        // камеры без id нет в списке знакомых: ничьё прежнее ей не принадлежит
        info.identify(null)
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.lens)
    }

    @Test fun theSameCameraKeepsWhatItGaveAcrossAQuietReconnectEvenIfALaterLoadFails() = runTest {
        val session = ready(newerCamera(), backgroundScope)
        val info = newInfo()
        info.begin(session, "cam-1", session.properties.value).join()
        val known = info.strings
        assertFalse(known.lists.isEmpty())
        // сеанс оборвался и восстанавливается; новый сеанс той же камеры: подписи не пришли (тайм-аут), прежние на месте
        info.pause()
        info.identify("cam-1")
        val silent = newerCamera()
        silent.timeoutOn += SonyOp.GET_DISPLAY_STRING_LIST
        val next = ready(silent, backgroundScope)
        info.begin(next, "cam-1", next.properties.value).join()
        assertEquals(known, info.strings)
    }

    @Test fun resetForgetsEverythingAndWakesNobody() = runTest {
        val session = ready(newerCamera(), backgroundScope)
        val info = newInfo()
        info.begin(session, "cam-1", session.properties.value).join()
        info.reset()
        assertEquals(DisplayStrings(), info.strings)
        assertNull(info.vendorCodeVersion)
        assertNull(info.lens)
        // после reset события и свойства никого не будят: сеанса нет
        assertNull(info.handle(CameraEvent.LensChanged, session))
        assertNull(info.handle(CameraEvent.DisplayListChanged(0x13), session))
    }

    // MARK: - События камеры

    @Test fun aChangedListIsReadAgainAndAVanishedOneIsErased() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        // «FL» → «FLM» и событие 0xC20F с типом 0x13: заново этот список, второй — на месте
        val renamed = CameraBytes.dataset(CameraBytes.stringLists(listOf(
            CameraBytes.StringList(0x13, PtpType.UINT16, listOf(1L to "ST", 6L to "FLM")))))
        cam.answers[0x9215] = { PtpReply(PtpResponseCode.OK, listOf(100), renamed) }
        info.handle(CameraEvent.DisplayListChanged(0x13), session)!!.join()
        assertEquals(mapOf(1L to "ST", 6L to "FLM"), info.strings.lists[0x13L])
        assertEquals(2, info.strings.lists[0x15L]!!.size)
        assertEquals(listOf(0x13L), cam.sent.filter { it.first == 0x9215 }.last().second)
        // список пропал у камеры: ответ — пустой набор, старые названия стираются
        cam.answers[0x9215] = { PtpReply(PtpResponseCode.OK, listOf(100), CameraBytes.noLists()) }
        info.handle(CameraEvent.DisplayListChanged(0x13), session)!!.join()
        assertNull(info.strings.lists[0x13L])
        assertEquals(setOf(0x15L), info.strings.lists.keys)
        assertTrue(notes.toString(), notes.any { it == "подписи камеры (0xC20F, тип 0x13): списка больше нет" })
        // без параметра (тип 0) — все списки заново, набор заменяется целиком
        cam.answers[0x9215] = { PtpReply(PtpResponseCode.OK, listOf(100), CameraBytes.fixture("display_strings")) }
        info.handle(CameraEvent.DisplayListChanged(0), session)!!.join()
        assertEquals(setOf(0x13L, 0x15L), info.strings.lists.keys)
        assertEquals(listOf(0L), cam.sent.filter { it.first == 0x9215 }.last().second)
    }

    @Test fun aListAnswerThatArrivesAfterTheSessionLeftIsIgnored() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        val known = info.strings
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        cam.answers[0x9215] = {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            PtpReply(PtpResponseCode.OK, listOf(100), CameraBytes.noLists())
        }
        val job = info.handle(CameraEvent.DisplayListChanged(0x13), session)!!
        runCurrent()
        assertTrue("запрос не ушёл", entered.await(10, TimeUnit.SECONDS))
        // сеанс ушёл, пока камера думала: пустой набор, который она потом прислала, названия не стирает
        info.pause()
        gate.countDown()
        job.join()
        assertEquals(known, info.strings)
        assertTrue(notes.toString(), notes.none { it.startsWith("подписи камеры (0xC20F") })
    }

    @Test fun aLensChangeDropsTheOldTableAtOnceAndReadsTheNewOne() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        assertEquals(12, info.lens!!.pairs.size)
        // телеобъектив: пять пар, от 1,2 м до «∞»
        val tele = CameraBytes.dataset(CameraBytes.le16(100) + CameraBytes.le16(1) + CameraBytes.le16(5) + byteArrayOf(0, 0) +
            listOf(0L to 120L, 20000L to 200L, 40000L to 500L, 50000L to 2000L, 65535L to 100_000_000L)
                .fold(ByteArray(0)) { bytes, (position, distance) -> bytes + CameraBytes.le32(position) + CameraBytes.le32(distance) })
        cam.answers[0x9223] = { PtpReply(PtpResponseCode.OK, data = tele) }
        val job = info.handle(CameraEvent.LensChanged, session)!!
        // таблица прежнего объектива теперь неверна: пока нет новой, расстояний нет
        assertNull(info.lens)
        job.join()
        assertEquals(5, info.lens!!.pairs.size)
        assertEquals(2, cam.count(SonyOp.GET_LENS_INFORMATION))
    }

    @Test fun aLensTableThatCannotBeReadLeavesNoTable() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        cam.answers[0x9223] = { PtpReply(PtpResponseCode.OK, data = byteArrayOf(8, 0, 0, 0, 40, 0, 0, 0)) }
        info.handle(CameraEvent.LensChanged, session)!!.join()
        // лучше без расстояния, чем с неверным; камера на связи как была
        assertNull(info.lens)
        assertTrue(notes.toString(), notes.any { it.startsWith("данные объектива (0x9223): ответ не разобран") })
        assertEquals(310, info.vendorCodeVersion)
    }

    @Test fun theLensFlagTurnsTheTableOnAndOff() = runTest {
        val cam = newerCamera(lensEnabled = 0)
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        info.begin(session, "ilce", session.properties.value).join()
        assertNull(info.lens)
        assertEquals(0, cam.count(SonyOp.GET_LENS_INFORMATION))
        // 0xE086 стал 1: заново; свойства прежнего вида ничего не будят
        lensFlag(cam).current = 1
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(0)), session))
        // сеанс перечитал свойства: 0xE086 теперь 1 и у самого сеанса (по нему 0x9223 и спрашивается)
        session.refresh()
        info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), session)!!.join()
        assertEquals(12, info.lens!!.pairs.size)
        assertEquals(1, cam.count(SonyOp.GET_LENS_INFORMATION))
        // то же значение — не повод спрашивать снова
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), session))
        // 0xE086 перестал быть 1: расстояний больше нет, и камеру не спрашивают
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(0)), session))
        assertNull(info.lens)
        assertEquals(1, cam.count(SonyOp.GET_LENS_INFORMATION))
        assertTrue(notes.toString(), notes.any { it == "данные объектива: 0xE086 больше не 1, расстояний нет" })
    }

    // MARK: - Запоздавший ответ прежнего сеанса ничего не пишет

    @Test fun anAnswerThatArrivesAfterTheSessionLeftIsIgnored() = runTest {
        val cam = newerCamera()
        val entered = CountDownLatch(1)
        val gate = CountDownLatch(1)
        val all = CameraBytes.fixture("display_strings")
        cam.answers[0x9215] = {
            entered.countDown()
            // ответ приходит, когда сеанса уже нет
            gate.await(10, TimeUnit.SECONDS)
            PtpReply(PtpResponseCode.OK, listOf(100), all)
        }
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        val job = info.begin(session, "ilce", session.properties.value)
        runCurrent()
        assertTrue("запрос не ушёл", entered.await(10, TimeUnit.SECONDS))
        info.pause()
        gate.countDown()
        job.join()
        assertEquals(DisplayStrings(), info.strings)
        assertTrue(notes.toString(), notes.none { it.startsWith("подписи камеры") })
        // 0x9216 прежний сеанс уже не спрашивает
        assertEquals(0, cam.count(SonyOp.GET_VENDOR_CODE_VERSION))
    }

    @Test fun eventsAndPropertiesWakeNobodyBeforeTheSessionIsReadyAndAfterItIsGone() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        // begin ещё не был: события приходят в первые секунды после рукопожатия, и подписей никто не просит
        assertNull(info.handle(CameraEvent.DisplayListChanged(0x13), session))
        assertNull(info.handle(CameraEvent.LensChanged, session))
        assertNull(info.propertiesChanged(mapOf(SonyProp.LENS_INFO_ENABLED to flagProperty(1)), session))
        assertTrue(cam.sent.none { it.first in NEW_OPERATIONS })
        info.begin(session, "ilce", session.properties.value).join()
        val asked = cam.sent.count { it.first in NEW_OPERATIONS }
        // сеанс оборвался: события и свойства ничего не спрашивают
        info.pause()
        assertNull(info.handle(CameraEvent.DisplayListChanged(0x13), session))
        assertNull(info.handle(CameraEvent.LensChanged, session))
        assertEquals(asked, cam.sent.count { it.first in NEW_OPERATIONS })
    }

    @Test fun beginOnACameraWithNothingNewEndsAtOnce() = runTest {
        val cam = FakeCamera()
        val session = ready(cam, backgroundScope)
        val info = newInfo()
        val job = info.begin(session, "zv-e10", session.properties.value)
        job.join()
        assertTrue(job.isCompleted)
    }

    /** Свойство 0xE086 с этим значением — как его присылает камера в перечитанных свойствах. */
    private fun flagProperty(value: Long) = SonyProperty(SonyProp.LENS_INFO_ENABLED, PtpType.UINT8, 0, 1, PtpValue.Num(value), null,
        listOf(0L, 1L), listOf(0L, 1L))
}
