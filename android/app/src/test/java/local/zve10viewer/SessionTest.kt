package local.zve10viewer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import local.zve10viewer.ptp.ByteReader
import local.zve10viewer.ptp.PtpEvent
import local.zve10viewer.ptp.PtpReply
import local.zve10viewer.ptp.PtpResponseCode
import local.zve10viewer.ptp.PtpTransport
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.CapturePhase
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.RefreshCoalescer
import local.zve10viewer.session.SessionException
import local.zve10viewer.session.SessionTiming
import local.zve10viewer.session.SetResult
import local.zve10viewer.settings.FileNaming
import local.zve10viewer.settings.LumaHistogram
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.WriteCoalescer
import local.zve10viewer.settings.apply
import local.zve10viewer.sony.PropertyRange
import local.zve10viewer.sony.SonyOp
import local.zve10viewer.sony.SonyProp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream

private val FAST = SessionTiming(settleMs = 1, confirmTimeoutMs = 200, pollMs = 5, captureTimeoutMs = 500)

class SessionTest {
    private suspend fun ready(camera: FakeCamera, scope: kotlinx.coroutines.CoroutineScope): CameraSession {
        val session = CameraSession(camera, FAST)
        session.start(scope)
        session.waitUntilReady()
        return session
    }

    @Test fun setIsConfirmedByReadingBack() = runTest {
        val camera = FakeCamera()
        val session = ready(camera, this)
        assertEquals(SetResult.Confirmed, session.set(SonyProp.WHITE_BALANCE, 4))
        assertTrue(camera.sent.any { it.first == SonyOp.SET_EXT_PROP_VALUE && it.second == listOf(SonyProp.WHITE_BALANCE.toLong()) })
        session.close()
    }

    @Test fun ignoredWriteIsReportedWithTheActualValue() = runTest {
        val camera = FakeCamera().apply { applyWrites = false }
        val session = ready(camera, this)
        assertEquals(SetResult.NotApplied(2), session.set(SonyProp.WHITE_BALANCE, 4))
        session.close()
    }

    @Test fun valueOutsideTheCameraListIsNotSent() = runTest {
        val camera = FakeCamera()
        val session = ready(camera, this)
        try {
            session.set(SonyProp.WHITE_BALANCE, 99)
            fail("ожидали ValueNotAllowed")
        } catch (e: SessionException.ValueNotAllowed) {
            assertTrue(camera.sent.none { it.first == SonyOp.SET_EXT_PROP_VALUE && it.second == listOf(SonyProp.WHITE_BALANCE.toLong()) })
        }
        session.close()
    }

    @Test fun presetSkipsDependentSettingsWhenTheBaseFails() = runTest {
        val camera = FakeCamera().apply { ignored += SonyProp.WHITE_BALANCE }
        val session = ready(camera, this)
        val result = session.apply(Preset("Вечер", mapOf(SettingID.whiteBalance to 4L, SettingID.wbShiftAB to 196L, SettingID.iso to 400L)))
        assertEquals(listOf(SettingID.iso), result.applied)
        assertEquals(listOf(SettingID.whiteBalance, SettingID.wbShiftAB), result.skipped)
        assertTrue(camera.sent.none { it.first == SonyOp.SET_EXT_PROP_VALUE && it.second == listOf(SonyProp.AB_SHIFT.toLong()) })
        session.close()
    }

    @Test fun presetsSurviveJson() {
        val presets = listOf(Preset("Вечер", mapOf(SettingID.iso to 400L, SettingID.shutter to 0x10032L)))
        assertEquals(presets, PresetCatalog.fromJson(PresetCatalog.toJson(presets)))
        assertEquals(emptyList<Preset>(), PresetCatalog.fromJson("не json"))
    }

    @Test fun captureDownloadsEveryObjectOfTheShot() = runTest {
        val camera = FakeCamera().apply {
            capturedFiles = listOf("DSC00001.ARW" to byteArrayOf(1, 2, 3, 4), "DSC00001.JPG" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9))
        }
        val session = ready(camera, this)
        val phases = mutableListOf<CapturePhase>()
        val files = session.capturePhoto(download = true, files = 2) { phases += it }
        assertEquals(listOf("DSC00001.ARW", "DSC00001.JPG"), files.map { it.filename })
        val presses = camera.sent.filter { it.first == SonyOp.CONTROL_DEVICE }
        assertEquals(listOf(0xD2C1L, 0xD2C2L, 0xD2C2L, 0xD2C1L), presses.map { it.second[0] })
        assertEquals(listOf(2, 2, 1, 1), presses.map { it.third!![0].toInt() })
        // RAW+JPEG: доля — у каждого файла своя, «1/2» и «2/2»
        assertEquals(listOf(CapturePhase.Downloading(1, 2, 0, 4), CapturePhase.Downloading(2, 2, 0, 3)),
            phases.filterIsInstance<CapturePhase.Downloading>())
        assertTrue(pollsAfterLastDownload(camera).isEmpty())
        session.close()
    }

    @Test fun capturePhaseOrder() = runTest {
        val camera = FakeCamera().apply { capturedFiles = listOf("DSC00002.JPG" to ByteArray(5)) }
        val session = ready(camera, this)
        val phases = mutableListOf<CapturePhase>()
        session.capturePhoto(download = true, files = 1) { phases += it }
        assertEquals(listOf(CapturePhase.Focusing, CapturePhase.Exposing, CapturePhase.Downloading(1, 1, 0, 5)), phases)
        session.close()
    }

    @Test fun captureReturnsRightAfterTheExpectedFiles() = runTest {
        // JPEG без RAW: после файла камеру больше не опрашиваем — спуск свободен сразу, а не через 2,5 с
        val camera = FakeCamera().apply { capturedFiles = listOf("DSC00003.JPG" to ByteArray(5)) }
        val session = ready(camera, this)
        assertEquals(1, session.capturePhoto(download = true, files = 1).size)
        assertTrue(pollsAfterLastDownload(camera).isEmpty())
        session.close()
    }

    @Test fun captureTakesTheLeftoverObjectToo() = runTest {
        // прошлый снимок не забрали (тайм-аут, обрыв): объект уже в памяти камеры до спуска
        val camera = FakeCamera().apply { leaveInMemory("DSC00010.JPG" to ByteArray(4)) }
        val session = ready(camera, this)
        camera.capturedFiles = listOf("DSC00011.JPG" to ByteArray(6))
        val phases = mutableListOf<CapturePhase>()
        val files = session.capturePhoto(download = true, files = 1) { phases += it }
        // оба файла — настоящие снимки; иначе каждый следующий снимок отставал бы на один
        assertEquals(listOf("DSC00010.JPG", "DSC00011.JPG"), files.map { it.filename })
        assertEquals(listOf(CapturePhase.Downloading(1, 2, 0, 4), CapturePhase.Downloading(2, 2, 0, 6)),
            phases.filterIsInstance<CapturePhase.Downloading>())
        assertEquals(0, camera.objectsInMemory)
        session.close()
    }

    @Test fun captureWithoutDownloadStopsAfterTheShutter() = runTest {
        val camera = FakeCamera().apply { capturedFiles = listOf("DSC00004.JPG" to ByteArray(5)) }
        val session = ready(camera, this)
        val phases = mutableListOf<CapturePhase>()
        assertTrue(session.capturePhoto(download = false, files = 1) { phases += it }.isEmpty())
        assertEquals(listOf(CapturePhase.Focusing, CapturePhase.Exposing), phases)
        session.close()
    }

    /** Что ушло в камеру после последнего GetObject снимка. */
    private fun pollsAfterLastDownload(camera: FakeCamera): List<Int> {
        val last = camera.sent.indexOfLast { it.first == SonyOp.GET_OBJECT && it.second == listOf(0xFFFF_C001L) }
        assertTrue(last >= 0)
        return camera.sent.drop(last + 1).map { it.first }
    }

    @Test fun failedShutterStillReleasesBothButtons() = runTest {
        val camera = FakeCamera().apply { busyOnNextPress = SonyProp.SHUTTER_FULL_PRESS }
        val session = ready(camera, this)
        try {
            session.capturePhoto(download = true, files = 1)
            fail("ожидали ошибку спуска")
        } catch (e: SessionException.OperationFailed) {
            assertEquals(PtpResponseCode.DEVICE_BUSY, e.code)
        }
        val presses = camera.sent.filter { it.first == SonyOp.CONTROL_DEVICE }
        assertEquals(listOf(0xD2C1L, 0xD2C2L, 0xD2C2L, 0xD2C1L), presses.map { it.second[0] })
        assertEquals(listOf(2, 2, 1, 1), presses.map { it.third!![0].toInt() })
        session.close()
    }

    @Test fun recordingToggleReportsTheNewState() = runTest {
        val camera = FakeCamera()
        val session = ready(camera, this)
        assertTrue(session.toggleRecording())
        assertEquals(false, session.toggleRecording())
        session.close()
    }

    @Test fun coalescerWritesFirstAndLastOnly() = runTest {
        val written = mutableListOf<Long>()
        val gate = CompletableDeferred<Unit>()
        val coalescer = WriteCoalescer(this) { _, value ->
            written += value
            if (value == 1L) gate.await()
        }
        coalescer.submit(0x5005, 1)
        testScheduler.runCurrent()
        coalescer.submit(0x5005, 2)
        coalescer.submit(0x5005, 3)
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf(1L, 3L), written)
    }

    @Test fun propertyEventBurstRefreshesOneMoreTimeAtMost() = runTest {
        var refreshes = 0
        val gate = CompletableDeferred<Unit>()
        // своя область: перечитывающая корутина ждёт событий бесконечно, тест её потом отменяет
        val scope = CoroutineScope(coroutineContext + Job())
        val refresher = RefreshCoalescer(scope) {
            refreshes++
            if (refreshes == 1) gate.await()
        }
        refresher.request()
        testScheduler.runCurrent()
        assertEquals(1, refreshes)
        // пока первое перечитывание идёт, пачка событий — одно в очереди
        repeat(10) { refresher.request() }
        gate.complete(Unit)
        testScheduler.advanceUntilIdle()
        assertEquals(2, refreshes)
        refresher.request()
        testScheduler.advanceUntilIdle()
        assertEquals(3, refreshes)
        scope.cancel()
    }

    @Test fun liveViewFrameComesWithItsTimingAndShortReplies() = runTest {
        val frame = javaClass.getResource("/fixtures/lv_frame_raw.bin")!!.readBytes()
        var denied = 2
        val camera = object : PtpTransport {
            override val events: Flow<PtpEvent> = emptyFlow()
            override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply {
                // кадр не готов дважды (7 и 6 мс), потом кадр за 30 мс
                val reply = if (denied-- > 0) PtpReply(PtpResponseCode.ACCESS_DENIED) else PtpReply(PtpResponseCode.OK, data = frame)
                reply.elapsedNanos = if (reply.isOK) 30_000_000 else (6 + maxOf(denied, 0)) * 1_000_000L
                return reply
            }
            override fun close() {}
        }
        val sample = CameraSession(camera, FAST).nextLiveViewFrame()
        assertEquals(2, sample.waits)
        assertEquals(22260, sample.bytes)
        // JPEG — прямо в ответе камеры, без копии
        assertEquals(1524, sample.jpegOffset)
        assertEquals(20736, sample.jpegLength)
        assertTrue(sample.data === frame)
        assertEquals(30.0, sample.fetchMs, 1e-9)
        assertEquals(6.0, sample.shortReplyMs!!, 1e-9)
        assertEquals(20736, sample.jpeg().size)
    }

    @Test fun liveViewQualityIsWrittenWithoutReadBack() = runTest {
        val camera = FakeCamera()
        val session = ready(camera, this)
        val before = camera.sent.size
        session.setLiveViewQuality(LiveViewQuality.low)
        val after = camera.sent.drop(before)
        // смену подтвердит ширина кадров — перечитывать свойства незачем
        assertEquals(listOf(SonyOp.SET_EXT_PROP_VALUE to listOf(SonyProp.LIVE_VIEW_IMAGE_QUALITY.toLong())), after.map { it.first to it.second })
        assertArrayEquals(byteArrayOf(1), after[0].third)
        session.close()
    }

    @Test fun fileNamesNeverOverwrite() {
        val taken = setOf("DSC1.JPG", "DSC1 (2).JPG")
        assertEquals("DSC1 (3).JPG", FileNaming.unique("DSC1.JPG") { it in taken })
        assertEquals("DSC2.JPG", FileNaming.unique("DSC2.JPG") { it in taken })
    }

    @Test fun histogramPutsBlackAndWhiteAtTheEnds() {
        val bins = LumaHistogram.bins(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(1, bins.first())
        assertEquals(2, bins.last())
    }
}

/** Поддельная камера: хранит свойства, применяет записи, «снимает» заданные файлы. */
class FakeCamera : PtpTransport {
    class Prop(val code: Int, val type: Int, var current: Long, val values: List<Long> = emptyList(),
               val range: PropertyRange? = null, val getSet: Int = 1, val enabled: Int = 1)

    val props = mutableListOf(
        Prop(SonyProp.WHITE_BALANCE, PtpType.UINT16, 2, listOf(2, 4, 0x8012)),
        Prop(SonyProp.AB_SHIFT, PtpType.UINT8, 192, range = PropertyRange(164, 220, 2)),
        Prop(SonyProp.ISO, PtpType.UINT32, 200, listOf(100, 200, 400, 0xFFFFFF)),
        Prop(SonyProp.FOCUS_MODE, PtpType.UINT16, 1, listOf(1, 2)),
        Prop(SonyProp.MOVIE_RECORDING_STATE, PtpType.UINT8, 0, getSet = 0),
        Prop(SonyProp.OBJECT_IN_MEMORY, PtpType.UINT16, 0, getSet = 0),
    )
    val sent = mutableListOf<Triple<Int, List<Long>, ByteArray?>>()
    var applyWrites = true
    val ignored = mutableSetOf<Int>()
    var busyOnNextPress: Int? = null
    var capturedFiles: List<Pair<String, ByteArray>> = emptyList()
    private var memory = ArrayDeque<Pair<String, ByteArray>>()
    override val events: Flow<PtpEvent> = emptyFlow()

    private fun prop(code: Int) = props.firstOrNull { it.code == code }

    /** Сколько объектов сейчас в памяти камеры. */
    val objectsInMemory: Int get() = memory.size

    /** Объект, оставшийся в памяти камеры от прошлого снимка. */
    fun leaveInMemory(file: Pair<String, ByteArray>) {
        memory.addLast(file)
        syncMemory()
    }

    override fun send(operation: Int, params: List<Long>, outData: ByteArray?, timeoutMs: Int?): PtpReply {
        sent += Triple(operation, params, outData)
        val ok = PtpResponseCode.OK
        return when (operation) {
            SonyOp.GET_DEVICE_INFO -> PtpReply(ok, data = javaClass.getResource("/fixtures/deviceinfo.bin")!!.readBytes())
            SonyOp.GET_EXT_DEVICE_INFO -> PtpReply(ok, data = javaClass.getResource("/fixtures/sony_extdeviceinfo.bin")!!.readBytes())
            SonyOp.SET_EXT_PROP_VALUE -> {
                val p = prop(params[0].toInt())
                if (p != null && applyWrites && p.code !in ignored && outData != null) {
                    p.current = PtpValue.read(ByteReader(outData), p.type).intValue ?: p.current
                }
                PtpReply(ok)
            }
            SonyOp.CONTROL_DEVICE -> {
                val button = params[0].toInt()
                val press = outData?.get(0)?.toInt() == 2
                if (press && busyOnNextPress == button) {
                    busyOnNextPress = null
                    return PtpReply(PtpResponseCode.DEVICE_BUSY)
                }
                if (!press && button == SonyProp.MOVIE_REC_BUTTON) {
                    prop(SonyProp.MOVIE_RECORDING_STATE)!!.let { it.current = 1 - it.current }
                }
                if (!press && button == SonyProp.SHUTTER_FULL_PRESS && capturedFiles.isNotEmpty()) {
                    // как у камеры: новые файлы встают в очередь за теми, что ещё не забрали
                    memory.addAll(capturedFiles)
                    capturedFiles = emptyList()
                    syncMemory()
                }
                PtpReply(ok)
            }
            SonyOp.GET_ALL_EXT_PROP_INFO -> PtpReply(ok, data = encodedProps())
            SonyOp.GET_OBJECT_INFO -> memory.firstOrNull()?.let { PtpReply(ok, data = objectInfo(it.first, it.second.size)) }
                ?: PtpReply(PtpResponseCode.INVALID_OBJECT_HANDLE)
            SonyOp.GET_OBJECT -> memory.removeFirstOrNull()?.let { file ->
                syncMemory()
                PtpReply(ok, data = file.second)
            } ?: PtpReply(PtpResponseCode.INVALID_OBJECT_HANDLE)
            else -> PtpReply(ok)
        }
    }

    override fun close() {}

    private fun syncMemory() {
        prop(SonyProp.OBJECT_IN_MEMORY)!!.current = if (memory.isEmpty()) 0 else 0x8000L + memory.size
    }

    private fun encodedProps(): ByteArray = ByteArrayOutputStream().apply {
        write(PtpValue.encode(props.size.toLong(), PtpType.UINT32))
        write(PtpValue.encode(0, PtpType.UINT32))
        for (p in props) {
            write(PtpValue.encode(p.code.toLong(), PtpType.UINT16))
            write(PtpValue.encode(p.type.toLong(), PtpType.UINT16))
            write(p.getSet)
            write(p.enabled)
            write(PtpValue.encode(p.current, p.type))
            write(PtpValue.encode(p.current, p.type))
            val range = p.range
            if (range != null) {
                write(1)
                listOf(range.min, range.max, range.step).forEach { write(PtpValue.encode(it, p.type)) }
            } else {
                write(2)
                write(PtpValue.encode(p.values.size.toLong(), PtpType.UINT16))
                p.values.forEach { write(PtpValue.encode(it, p.type)) }
            }
        }
    }.toByteArray()

    private fun objectInfo(name: String, size: Int): ByteArray = ByteArrayOutputStream().apply {
        write(ByteArray(4))                                       // StorageID
        write(PtpValue.encode(0x3801, PtpType.UINT16))            // формат
        write(ByteArray(2))                                       // ProtectionStatus
        write(PtpValue.encode(size.toLong(), PtpType.UINT32))
        write(ByteArray(2 + 4 * 7 + 2 + 4 * 2))
        write(name.length + 1)
        name.forEach { write(PtpValue.encode(it.code.toLong(), PtpType.UINT16)) }
        write(ByteArray(2))
    }.toByteArray()
}
