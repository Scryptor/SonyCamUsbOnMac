package local.zve10viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Камера с событиями: как настоящая, сообщает «свойства изменились» (0xC203). */
private class EventfulCamera(val camera: FakeCamera = FakeCamera()) : PtpTransport by camera {
    val flow = MutableSharedFlow<PtpEvent>(extraBufferCapacity = 8)
    override val events = flow

    fun change(code: Int, value: Long) {
        camera.props.first { it.code == code }.current = value
        flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
    }
}

/**
 * Сеанс переходит между главным экраном и лентой открытым: слушатели свойств подписываются и отписываются, а события
 * камеры слушает scope самого сеанса — отписка главного экрана его не трогает.
 */
class HandoverTest {
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)

    @Test fun sessionKeepsListeningAfterTheMainScreenLetsGo() = runBlocking {
        val camera = EventfulCamera()
        val owned = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = CameraSession(camera, fast)
        session.start(owned)
        session.waitUntilReady()
        // главный экран слушает свойства — и отпускает сеанс в ленту
        val mainScreen = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seenByMain = mutableListOf<Long?>()
        mainScreen.launch { session.properties.collect { seenByMain += it[SonyProp.ISO]?.current?.intValue } }
        withTimeout(2000) { while (seenByMain.isEmpty()) kotlinx.coroutines.delay(5) }
        mainScreen.cancel()
        // камера поменяла ISO — сеанс перечитал сам, лента видит новое значение сразу, без своего запроса
        camera.change(SonyProp.ISO, 400)
        val iso = withTimeout(2000) { session.properties.first { it[SonyProp.ISO]?.current?.intValue == 400L } }
        assertEquals(400L, iso[SonyProp.ISO]?.current?.intValue)
        assertTrue(session.isReady)
        assertFalse(session.ended.isCompleted)
        // второй подписчик получает последнее значение сразу
        assertEquals(400L, withTimeout(500) { session.properties.first() }[SonyProp.ISO]?.current?.intValue)
        // закрыли сеанс насовсем — его scope и события кончились
        session.close()
        owned.cancel()
        assertTrue(session.ended.isCompleted)
    }
}
