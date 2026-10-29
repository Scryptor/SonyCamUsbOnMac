package local.zve10viewer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SessionException
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.session.VendorCodeVersion
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.Sony
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Сеанс и другие камеры Sony: подписи списков (0x9215), версия кода производителя (0x9216) и данные объектива (0x9223) — когда
 * их спрашивают, как и что приходит. Те же проверки, что в OtherCamerasTests для Mac (раздел «Сеанс»). Новая камера —
 * [newerCamera]; каждое ожидание ограничено по времени ([eventually]), сломанный код роняет проверку, а не подвешивает тест.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OtherCamerasSessionTest {
    private val fast = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)

    private suspend fun ready(camera: PtpTransport, scope: CoroutineScope): CameraSession {
        val session = CameraSession(camera, fast)
        session.start(scope)
        session.waitUntilReady()
        return session
    }

    private fun FakeCamera.asked(operation: Int): List<List<Long>> = sent.filter { it.first == operation }.map { it.second }

    // MARK: - Что спрашиваем и у кого

    @Test fun sessionAsksForDisplayStringsOfTheTypeItIsGiven() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        val all = session.displayStrings(0)
        assertEquals("FL", all.lists[0x13L]!![0x0006L])
        assertEquals(2, all.lists[0x15L]!!.size)
        val looks = session.displayStrings(DisplayStrings.CREATIVE_LOOK_TYPE)
        assertEquals(mapOf(0x13L to mapOf(1L to "ST", 6L to "FL")), looks.lists)
        // тип списка — параметр запроса, 0 — «все»
        assertEquals(listOf(listOf(0L), listOf(0x13L)), cam.asked(SonyOp.GET_DISPLAY_STRING_LIST))
        // тип, которого у камеры нет, — пустой набор, а не ошибка
        assertEquals(DisplayStrings(), session.displayStrings(0x99))
    }

    @Test fun cameraThatDoesNotListTheNewOperationsIsNeverAskedForThem() = runTest {
        // ZV-E10: в списке операций нет ни 0x9215, ни 0x9216, ни 0x9223. Эта камера на них ответила бы (ловушка) и даже говорит
        // 0xE086 = 1, — но сеанс смотрит в список операций из GetDeviceInfo, а не на свойства
        val cam = FakeCamera()
        cam.props += FakeCamera.Prop(SonyProp.LENS_INFO_ENABLED, PtpType.UINT8, 1, listOf(0, 1), getSet = 0)
        for (operation in NEW_OPERATIONS) cam.answers[operation] = { PtpReply(PtpResponseCode.OK, listOf(0x0136L), ByteArray(64)) }
        val session = ready(cam, backgroundScope)
        assertEquals(DisplayStrings(), session.displayStrings(0))
        assertEquals(DisplayStrings(), session.displayStrings(DisplayStrings.CREATIVE_LOOK_TYPE))
        val load = session.loadDisplayStrings()
        assertEquals(DisplayStrings(), load.strings)
        assertTrue(load.failures.isEmpty())
        assertNull(session.vendorCodeVersion())
        assertNull(session.lensInformation())
        assertTrue("${cam.sent.map { it.first.toString(16) }}", cam.sent.none { it.first in NEW_OPERATIONS })
    }

    @Test fun oneOfTheOperationsDoesNotOpenTheOthers() = runTest {
        // камера назвала только 0x9223: подписи и версию по-прежнему не спрашиваем
        val cam = newerCamera(operations = listOf(0x9201, 0x9209, 0x9223))
        val session = ready(cam, backgroundScope)
        assertEquals(DisplayStrings(), session.displayStrings(0))
        assertNull(session.vendorCodeVersion())
        assertNotNull(session.lensInformation())
        assertEquals(listOf(SonyOp.GET_LENS_INFORMATION), cam.sent.filter { it.first in NEW_OPERATIONS }.map { it.first })
    }

    // MARK: - Версия кода (0x9216)

    @Test fun vendorCodeVersionIsTheReplyParameter() = runTest {
        assertEquals(0x0136, ready(newerCamera(), backgroundScope).vendorCodeVersion())
        assertEquals(310, ready(newerCamera(), backgroundScope).vendorCodeVersion())
        // без параметра в ответе версии нет
        val bare = newerCamera()
        bare.answers[0x9216] = { PtpReply(PtpResponseCode.OK) }
        assertNull(ready(bare, backgroundScope).vendorCodeVersion())
        // отказ камеры — ошибка с кодом операции
        val refusing = newerCamera()
        refusing.answers[0x9216] = { PtpReply(0x2002) }
        val session = ready(refusing, backgroundScope)
        try {
            session.vendorCodeVersion()
            fail("ожидали отказ камеры")
        } catch (e: SessionException.OperationFailed) {
            assertEquals(SonyOp.GET_VENDOR_CODE_VERSION, e.operation)
            assertEquals(0x2002, e.code)
        }
    }

    @Test fun vendorCodeVersionIsShownLikeSonyWritesIt() {
        // версия ×100: 0x0136 — 310 — «3.10»; 100 — «1.00»
        assertEquals("3.10", VendorCodeVersion.label(310))
        assertEquals("3.10", VendorCodeVersion.label(0x0136))
        assertEquals("1.00", VendorCodeVersion.label(100))
        assertEquals("1.05", VendorCodeVersion.label(105))
        assertEquals("0.05", VendorCodeVersion.label(5))
        assertEquals("12.34", VendorCodeVersion.label(1234))
    }

    // MARK: - Данные объектива (0x9223)

    @Test fun lensInformationIsAskedInMetersAndOnlyWhileTheCameraHasItEnabled() = runTest {
        val on = newerCamera(lensEnabled = 1)
        val lens = ready(on, backgroundScope).lensInformation()!!
        assertEquals(12, lens.pairs.size)
        assertTrue(lens.absoluteScale)
        assertEquals(1, lens.tableCount)
        assertEquals(listOf(listOf(2L)), on.asked(SonyOp.GET_LENS_INFORMATION))

        // 0xE086 = 0 — данных объектива нет (например, объектив без электроники): камеру не спрашиваем
        val off = newerCamera(lensEnabled = 0)
        assertNull(ready(off, backgroundScope).lensInformation())
        assertTrue(off.sent.none { it.first == SonyOp.GET_LENS_INFORMATION })

        // свойства 0xE086 у камеры нет — то же самое
        val none = newerCamera(lensEnabled = null)
        assertNull(ready(none, backgroundScope).lensInformation())
        assertTrue(none.sent.none { it.first == SonyOp.GET_LENS_INFORMATION })
    }

    @Test fun lensInformationFollowsTheEnableFlagAsItChanges() = runTest {
        val cam = CameraWithEvents(newerCamera(lensEnabled = 0))
        val session = ready(cam, backgroundScope)
        assertNull(session.lensInformation())
        val flag = cam.camera.props.first { it.code == SonyProp.LENS_INFO_ENABLED }
        // на камере поставили объектив с электроникой: 0xE086 стал 1, камера сообщила об изменении свойств; ждём перечитывания
        // с потолком — сломанный код роняет проверку, а не подвешивает тест
        flag.current = 1
        cam.flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
        assertTrue(eventually { session.properties.value[SonyProp.LENS_INFO_ENABLED]?.current?.intValue == 1L })
        assertEquals(12, session.lensInformation()?.pairs?.size)
        // и снова снят
        flag.current = 0
        cam.flow.tryEmit(PtpEvent(Sony.PROPERTY_CHANGED_EVENT))
        assertTrue(eventually { session.properties.value[SonyProp.LENS_INFO_ENABLED]?.current?.intValue == 0L })
        assertNull(session.lensInformation())
    }

    @Test fun lensInformationThatCannotBeReadIsAnErrorNotATable() = runTest {
        val cam = newerCamera()
        cam.answers[0x9223] = { PtpReply(PtpResponseCode.OK, data = byteArrayOf(8, 0, 0, 0, 40, 0, 0, 0)) }
        val session = ready(cam, backgroundScope)
        try {
            session.lensInformation()
            fail("ожидали ошибку разбора")
        } catch (e: PtpParseException) {
            // так и должно быть
        }
        // отказ камеры — ошибка с кодом
        cam.answers[0x9223] = { PtpReply(0x2019) }
        try {
            session.lensInformation()
            fail("ожидали отказ камеры")
        } catch (e: SessionException.OperationFailed) {
            assertEquals(SonyOp.GET_LENS_INFORMATION, e.operation)
            assertEquals(0x2019, e.code)
        }
    }

    // MARK: - Подписи при подключении: один сломанный список не лишает остальных

    @Test fun startupDisplayStringsAreAllTheListsOfTheCamera() = runTest {
        val cam = newerCamera()
        val load = ready(cam, backgroundScope).loadDisplayStrings()
        assertEquals(setOf(0x13L, 0x15L), load.strings.lists.keys)
        assertTrue(load.failures.isEmpty())
        assertEquals(listOf(listOf(0L)), cam.asked(SonyOp.GET_DISPLAY_STRING_LIST))
    }

    @Test fun startupDisplayStringsFallBackToCreativeLookWhenTheWholeSetIsUnreadable() = runTest {
        // один список с неизвестным типом данных PTP обрывает разбор всего набора (тип 0): первый список целый, второй — нет
        var whole = CameraBytes.le16(2) + byteArrayOf(0, 0)
        whole += CameraBytes.le32(0x13) + CameraBytes.le16(PtpType.UINT16) + CameraBytes.le16(1)       // список 0x13 из одной строки…
        whole += CameraBytes.le16(1) + CameraBytes.le16(3) + byteArrayOf(0x53, 0x54, 0)                // …1 → «ST»
        whole += CameraBytes.le32(0x15) + CameraBytes.le16(0x0000) + CameraBytes.le16(1)               // список 0x15: типа данных 0 в PTP нет
        whole += byteArrayOf(0, 0, 0)
        val unreadable = CameraBytes.dataset(whole)
        val cam = newerCamera()
        cam.answers[0x9215] = { params ->
            PtpReply(PtpResponseCode.OK, listOf(100), if (params == listOf(DisplayStrings.CREATIVE_LOOK_TYPE)) CameraBytes.creativeLookOnly() else unreadable)
        }
        val load = ready(cam, backgroundScope).loadDisplayStrings()
        // от камеры — только Creative Look, единственное, что программа показывает
        assertEquals(mapOf(0x13L to mapOf(1L to "ST", 6L to "FL")), load.strings.lists)
        assertEquals(listOf(0L), load.failures.map { it.type })
        assertTrue(load.failures.first().error is PtpParseException)
        assertEquals(listOf(listOf(0L), listOf(0x13L)), cam.asked(SonyOp.GET_DISPLAY_STRING_LIST))
    }

    @Test fun startupDisplayStringsFallBackWhenTheCameraRefusesTheWholeSet() = runTest {
        val cam = newerCamera()
        val looks = CameraBytes.dataset(CameraBytes.stringLists(listOf(
            CameraBytes.StringList(0x13, PtpType.UINT16, listOf(6L to "FL")))))
        cam.answers[0x9215] = { params -> if (params == listOf(0L)) PtpReply(0x2006) else PtpReply(PtpResponseCode.OK, listOf(100), looks) }
        val load = ready(cam, backgroundScope).loadDisplayStrings()
        assertEquals(mapOf(0x13L to mapOf(6L to "FL")), load.strings.lists)
        assertEquals(1, load.failures.size)
        assertEquals(0L, load.failures[0].type)
        val refusal = load.failures[0].error as SessionException.OperationFailed
        assertEquals(SonyOp.GET_DISPLAY_STRING_LIST, refusal.operation)
        assertEquals(0x2006, refusal.code)
    }

    @Test fun startupDisplayStringsCarryOnWithoutNamesWhenNothingCanBeRead() = runTest {
        val cam = newerCamera()
        cam.answers[0x9215] = { PtpReply(PtpResponseCode.OK, data = byteArrayOf(1, 2, 3)) }
        val load = ready(cam, backgroundScope).loadDisplayStrings()
        // ни ошибки, ни названий: программа берёт названия Creative Look из таблиц Sony
        assertEquals(DisplayStrings(), load.strings)
        assertEquals(listOf(0L, 0x13L), load.failures.map { it.type })
        assertEquals(2, cam.asked(SonyOp.GET_DISPLAY_STRING_LIST).size)
    }

    @Test fun startupDisplayStringsDoNotAskAgainAfterTheLinkIsLost() = runTest {
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        cam.disconnectOnNext = SonyOp.GET_DISPLAY_STRING_LIST
        try {
            session.loadDisplayStrings()
            fail("обрыв связи должен дойти до вызывающего")
        } catch (e: TransportException) {
            assertTrue(e.disconnected)
        }
        // обрыв — не повод пробовать другой тип: второй запрос в оборванную связь не уходит
        assertEquals(1, cam.asked(SonyOp.GET_DISPLAY_STRING_LIST).size)
    }

    // MARK: - Камера, которая не отвечает: тайм-аут — не повод спрашивать ещё

    @Test fun startupDisplayStringsDoNotAskAgainAfterATimeout() = runTest {
        // камера называет 0x9215, но молчит: транспорт ждёт 8 с и бросает тайм-аут. Второй запрос (0x13) ушёл бы в ту же тишину, а
        // слежение за связью («камера не отвечает» через 15 с без ответа на операцию) закрыло бы сеанс на нём — и тихое
        // переподключение пошло бы по кругу
        val cam = newerCamera()
        val session = ready(cam, backgroundScope)
        cam.timeoutOn += SonyOp.GET_DISPLAY_STRING_LIST
        try {
            session.loadDisplayStrings()
            fail("тайм-аут должен дойти до вызывающего")
        } catch (e: TransportException) {
            assertTrue(e.timeout)
        }
        assertEquals(1, cam.asked(SonyOp.GET_DISPLAY_STRING_LIST).size)
    }

    @Test fun aTimeoutOfAnOptionalOperationReachesTheCallerAndLeavesTheSessionAlive() = runTest {
        val cam = CameraWithEvents(newerCamera())
        val session = ready(cam, backgroundScope)
        cam.camera.timeoutOn += NEW_OPERATIONS
        for (call in listOf<suspend () -> Any?>({ session.displayStrings(0) }, { session.vendorCodeVersion() }, { session.lensInformation() })) {
            try {
                call()
                fail("тайм-аут должен дойти до вызывающего")
            } catch (e: TransportException) {
                assertTrue(e.timeout)
                assertFalse(e.disconnected)
            }
        }
        // тайм-аут — не обрыв: сеанс жив, другие операции идут
        assertTrue(session.isReady)
        cam.camera.timeoutOn.clear()
        assertEquals(310, session.vendorCodeVersion())
    }

    @Test fun onlyTheTransportMarksATimeout() {
        // по умолчанию — обычная ошибка обмена: ни обрыв, ни тайм-аут
        val plain = TransportException("запись в камеру не прошла")
        assertFalse(plain.timeout)
        assertFalse(plain.disconnected)
        assertTrue(TransportException("камера не ответила вовремя", timeout = true).timeout)
    }
}
