package local.zve10viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.session.CameraEvent
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyEvent
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * То, что метки фокуса и другие камеры Sony берут у сеанса: события камеры (0xC21B смена объектива, 0xC20F список подписей
 * изменился, 0xC218 фокус дошёл), запись числа без ожидания подтверждения (0xE042) и возраст последнего снимка свойств.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusSessionTest {
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)

    // MARK: - События камеры

    @Test fun theLensChangeEventReachesEverySubscriberAndOrdinaryPropertyEventsDoNot() = runTest {
        val camera = CameraWithEvents()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        val first = ArrayList<CameraEvent>()
        val second = ArrayList<CameraEvent>()
        backgroundScope.launch { session.events.collect { first += it } }
        backgroundScope.launch { session.events.collect { second += it } }
        runCurrent()
        // 0xC203 — обычное изменение свойств, к событиям камеры не относится
        camera.flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
        runCurrent()
        assertTrue(first.isEmpty() && second.isEmpty())
        // 0xC21B — смена объектива: каждому подписчику по одному разу
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(listOf<CameraEvent>(CameraEvent.LensChanged), first)
        assertEquals(listOf<CameraEvent>(CameraEvent.LensChanged), second)
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        session.close()
    }

    @Test fun eventsBeforeSubscribingAreNotReplayed() = runTest {
        val camera = CameraWithEvents()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        runCurrent()
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        camera.flow.tryEmit(PtpEvent(SonyEvent.DISPLAY_LIST_CHANGED, listOf(0x13L)))
        camera.flow.tryEmit(PtpEvent(SonyEvent.FOCUS_POSITION_RESULT, listOf(1L)))
        runCurrent()
        val late = ArrayList<CameraEvent>()
        backgroundScope.launch { session.events.collect { late += it } }
        runCurrent()
        assertTrue("старые события не повторяются", late.isEmpty())
        // старых событий не будет, новое придёт
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(listOf<CameraEvent>(CameraEvent.LensChanged), late)
        session.close()
    }

    @Test fun cameraEventsCarryTheirParametersInOrder() = runTest {
        val camera = CameraWithEvents()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        val events = ArrayList<CameraEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()
        // список подписей изменился: тип списка — в параметре события; без параметра — 0, «все списки»
        camera.flow.tryEmit(PtpEvent(SonyEvent.DISPLAY_LIST_CHANGED, listOf(0x13L)))
        camera.flow.tryEmit(PtpEvent(SonyEvent.DISPLAY_LIST_CHANGED))
        // фокус дошёл до цели (параметр 1) или нет (0, а без параметра — тоже нет)
        camera.flow.tryEmit(PtpEvent(SonyEvent.FOCUS_POSITION_RESULT, listOf(1L)))
        camera.flow.tryEmit(PtpEvent(SonyEvent.FOCUS_POSITION_RESULT, listOf(0L)))
        camera.flow.tryEmit(PtpEvent(SonyEvent.FOCUS_POSITION_RESULT))
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        // чужое событие поток не пачкает
        camera.flow.tryEmit(PtpEvent(0xC2FF, listOf(7L)))
        camera.flow.tryEmit(PtpEvent(SonyEvent.DISPLAY_LIST_CHANGED, listOf(0x15L)))
        runCurrent()
        assertEquals(listOf(
            CameraEvent.DisplayListChanged(0x13), CameraEvent.DisplayListChanged(0), CameraEvent.FocusPositionResult(true),
            CameraEvent.FocusPositionResult(false), CameraEvent.FocusPositionResult(false), CameraEvent.LensChanged,
            CameraEvent.DisplayListChanged(0x15)), events)
        session.close()
    }

    @Test fun propertyEventsStillRefreshTheSnapshotAlongsideCameraEvents() = runTest {
        val camera = CameraWithEvents()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        val events = ArrayList<CameraEvent>()
        backgroundScope.launch { session.events.collect { events += it } }
        runCurrent()
        camera.camera.props.first { it.code == SonyProp.ISO }.current = 400
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        camera.flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
        // перечитывание идёт в потоке обмена: ждём с потолком
        assertTrue(eventually { session.properties.value[SonyProp.ISO]?.current?.intValue == 400L })
        assertEquals(listOf<CameraEvent>(CameraEvent.LensChanged), events)
        session.close()
    }

    // MARK: - Запись без ожидания

    @Test fun setQuietlyWritesTheNumberOnceInItsOwnTypeAndDoesNotReadBack() = runTest {
        val camera = FakeCamera()
        val session = CameraSession(camera, fast)
        session.start(this)
        val before = camera.sent.size
        val code = session.setQuietly(SonyProp.FOCUS_POSITION_SETTING, 0x1234, PtpType.UINT16)
        assertEquals(PtpResponseCode.OK, code)
        // одна запись 0xE042, два байта, младшим вперёд; ни перечитывания, ни ожидания подтверждения
        val sent = camera.sent.drop(before)
        assertEquals(1, sent.size)
        assertEquals(SonyOp.SET_EXT_PROP_VALUE, sent[0].first)
        assertEquals(listOf(SonyProp.FOCUS_POSITION_SETTING.toLong()), sent[0].second)
        assertArrayEquals(byteArrayOf(0x34, 0x12), sent[0].third)
        session.close()
    }

    @Test fun setQuietlyGivesTheCodeTheCameraRepliedWithInsteadOfThrowing() = runTest {
        val camera = FakeCamera()
        var busyNow = false
        val busy = object : PtpTransport by camera {
            override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply =
                if (busyNow && operation == SonyOp.SET_EXT_PROP_VALUE) PtpReply(PtpResponseCode.DEVICE_BUSY)
                else camera.send(operation, params, outData, timeoutMs)
        }
        val session = CameraSession(busy, fast)
        // рукопожатие тоже пишет свойство (приоритет) — отказывать начинаем после него
        session.start(this)
        busyNow = true
        assertEquals(PtpResponseCode.DEVICE_BUSY, session.setQuietly(SonyProp.FOCUS_POSITION_SETTING, 100, PtpType.UINT16))
        session.close()
    }

    // MARK: - Возраст снимка свойств

    @Test fun theSnapshotGetsOlderAndRefreshMakesItYoungAgain() = runTest {
        val camera = FakeCamera()
        // часы сеанса — виртуальные тестовые: возраст снимка точный, а не «примерно» по стенным часам, и загруженный Mac его не собьёт
        val session = CameraSession(camera, fast, clock = { testScheduler.currentTime * 1_000_000L })
        session.start(this)
        assertEquals("после рукопожатия снимок только что прочитан", 0L, session.snapshotAgeMs())
        advanceTimeBy(120)
        assertEquals(120L, session.snapshotAgeMs())
        session.refresh()
        assertEquals("после перечитывания снова свежий", 0L, session.snapshotAgeMs())
        advanceTimeBy(1)
        assertEquals(1L, session.snapshotAgeMs())
        session.close()
    }

    @Test fun aSessionThatNeverReadAnythingHasAnInfinitelyOldSnapshot() {
        val session = CameraSession(FakeCamera(), fast)
        assertEquals(Long.MAX_VALUE, session.snapshotAgeMs())
    }
}
