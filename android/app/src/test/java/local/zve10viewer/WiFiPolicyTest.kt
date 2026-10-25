package local.zve10viewer

import local.zve10viewer.wifi.WiFiPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WiFiPolicyTest {
    @Test fun attemptsOnlyWhenEnabledOrPairing() {
        val policy = WiFiPolicy()
        assertFalse(policy.mayAttempt(enabled = false, pairing = false, now = 0))
        assertTrue(policy.mayAttempt(enabled = true, pairing = false, now = 0))
        assertTrue(policy.mayAttempt(enabled = false, pairing = true, now = 0))
    }

    @Test fun sendsInitOnlyToAPairedCameraOrWhilePairing() {
        // без сопряжения не стучимся: камера в режиме Pairing показала бы запрос не от того устройства
        val policy = WiFiPolicy()
        assertFalse(policy.maySendInit(paired = false, pairing = false))
        assertTrue(policy.maySendInit(paired = true, pairing = false))
        assertTrue(policy.maySendInit(paired = false, pairing = true))
    }

    @Test fun backsOffAfterLostSessions() {
        val policy = WiFiPolicy()
        // сеанс оборвался — пауза 2, 4, 8, 10, 10 с, как у USB
        assertEquals(listOf(2, 4, 8, 10, 10), List(5) { policy.sessionLost(now = 1_000) })
        assertFalse(policy.mayAttempt(enabled = true, pairing = false, now = 1_000 + 9_000))
        assertTrue(policy.mayAttempt(enabled = true, pairing = false, now = 1_000 + 10_000))
        // сопряжение ждать не заставляем
        assertTrue(policy.mayAttempt(enabled = true, pairing = true, now = 1_000))
        // сеанс дошёл до готовности — счёт с начала
        policy.sessionReady()
        assertEquals(2, policy.sessionLost(now = 1_000))
    }

    @Test fun countsOnlyFailuresToADiscoveredCamera() {
        val policy = WiFiPolicy()
        // адрес из памяти мог устареть (камера выключена, новый адрес) — это не «держит прошлое подключение»
        repeat(5) { policy.attemptFailed(discovered = false) }
        assertFalse(policy.showsStaleHint)
        repeat(3) { policy.attemptFailed(discovered = true) }
        assertTrue(policy.showsStaleHint)
        // камеру в сети больше не видно — подсказка уходит
        policy.nothingFound()
        assertFalse(policy.showsStaleHint)
        repeat(3) { policy.attemptFailed(discovered = true) }
        policy.connected()
        assertFalse(policy.showsStaleHint)
    }

    @Test fun quietReconnectRetriesAtOnceThenBacksOffToFiveSeconds() {
        // по тому же адресу: сразу, затем 0,5, 1, 2, 3, 5, 5… с
        assertEquals(listOf(0L, 500L, 1000L, 2000L, 3000L, 5000L, 5000L, 5000L), List(8) { WiFiPolicy.reconnectDelayMs(it) })
    }

    @Test fun quietReconnectGivesUpAfterAMinuteOrWhenTheCameraRefuses() {
        assertFalse(WiFiPolicy.reconnectGivesUp(startedAt = 1_000, now = 60_999, refused = false))
        assertTrue(WiFiPolicy.reconnectGivesUp(startedAt = 1_000, now = 61_000, refused = false))
        // PC Remote выключили — ждать по тому же адресу нечего
        assertTrue(WiFiPolicy.reconnectGivesUp(startedAt = 1_000, now = 1_500, refused = true))
    }

    @Test fun onlyNotPairedInitFailForgetsThePairing() {
        assertTrue(WiFiPolicy.forgetsPairing(1))
        // 2 — камера занята (держит прошлое подключение), 3 — без причины: это проходит
        assertFalse(WiFiPolicy.forgetsPairing(2))
        assertFalse(WiFiPolicy.forgetsPairing(3))
        assertFalse(WiFiPolicy.forgetsPairing(0))
    }

    @Test fun wifiNetworkAppearingSkipsTheBackoff() {
        val policy = WiFiPolicy()
        policy.sessionLost(now = 1_000)
        assertFalse(policy.mayAttempt(enabled = true, pairing = false, now = 1_500))
        policy.networkAvailable()
        assertTrue(policy.mayAttempt(enabled = true, pairing = false, now = 1_500))
    }
}
