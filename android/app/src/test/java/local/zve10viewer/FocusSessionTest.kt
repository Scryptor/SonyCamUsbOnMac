package local.zve10viewer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
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
 * То, что метки фокуса берут у сеанса: событие смены объектива (0xC21B), запись числа без ожидания подтверждения (0xE042) и
 * возраст последнего снимка свойств.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FocusSessionTest {
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)

    /** Камера, которая шлёт события, когда ей скажут. */
    private class EventfulCamera(val camera: FakeCamera = FakeCamera()) : PtpTransport by camera {
        val flow = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 8)
        override val events: Flow<PtpEvent> = flow
    }

    // MARK: - Смена объектива

    @Test fun theLensChangeEventReachesEverySubscriberAndOrdinaryPropertyEventsDoNot() = runTest {
        val camera = EventfulCamera()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        val first = ArrayList<Unit>()
        val second = ArrayList<Unit>()
        backgroundScope.launch { session.lensChanges.collect { first += it } }
        backgroundScope.launch { session.lensChanges.collect { second += it } }
        runCurrent()
        // 0xC203 — обычное изменение свойств, к смене объектива не относится
        camera.flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
        runCurrent()
        assertTrue(first.isEmpty() && second.isEmpty())
        // 0xC21B — смена объектива: каждому подписчику по одному разу
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(1, first.size)
        assertEquals(1, second.size)
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(2, first.size)
        assertEquals(2, second.size)
        session.close()
    }

    @Test fun aLensChangeBeforeSubscribingIsNotReplayed() = runTest {
        val camera = EventfulCamera()
        val session = CameraSession(camera, fast)
        session.start(backgroundScope)
        runCurrent()
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        val late = ArrayList<Unit>()
        backgroundScope.launch { session.lensChanges.collect { late += it } }
        runCurrent()
        assertTrue("старое событие не повторяется", late.isEmpty())
        // старого события не будет, новое придёт
        camera.flow.tryEmit(PtpEvent(SonyEvent.LENS_INFO_CHANGED))
        runCurrent()
        assertEquals(1, late.size)
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
        val session = CameraSession(camera, fast)
        session.start(this)
        assertTrue("после рукопожатия снимок свежий: ${session.snapshotAgeMs()} мс", session.snapshotAgeMs() < 1_000)
        Thread.sleep(120)
        assertTrue("снимок старится: ${session.snapshotAgeMs()} мс", session.snapshotAgeMs() >= 100)
        session.refresh()
        assertTrue("после перечитывания снова свежий: ${session.snapshotAgeMs()} мс", session.snapshotAgeMs() < 100)
        session.close()
    }

    @Test fun aSessionThatNeverReadAnythingHasAnInfinitelyOldSnapshot() {
        val session = CameraSession(FakeCamera(), fast)
        assertEquals(Long.MAX_VALUE, session.snapshotAgeMs())
    }
}
