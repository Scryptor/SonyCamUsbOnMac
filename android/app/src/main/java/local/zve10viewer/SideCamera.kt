package local.zve10viewer

import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.settings.WiFiAddress
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.wifi.LinkLoss
import local.zve10viewer.wifi.PtpIpTransport

/**
 * Открытый сеанс с камерой по Wi-Fi. Его передают между главным экраном и лентой, не закрывая: события камеры слушает
 * scope самого сеанса — он живёт, пока сеанс открыт, кто бы его ни показывал. recordingSince — когда камера начала
 * писать (таймер записи переходит вместе с сеансом); null — не пишет.
 */
class OpenCamera(
    val id: String,
    val address: WiFiAddress,
    val transport: PtpIpTransport,
    val session: CameraSession,
    val scope: CoroutineScope,
    var recordingSince: Long? = null,
) {
    /** Закрыть насовсем; abort — сбросом RST: камера освобождает сеанс сразу. */
    fun close(abort: Boolean = false) {
        session.close()
        scope.cancel()
        if (abort) transport.abort(LinkLoss.closed) else transport.close()
    }
}

/**
 * Камера в ленте: свой открытый сеанс, превью низкого качества около 5 к/с (уменьшенный кадр), запись и заряд. Связь
 * пропала — сеанс закрыт, плитка «нет связи» с последним кадром; заново подключает контроллер. Строк состояния нет.
 */
class SideCamera(val id: String, name: String, private val scope: CoroutineScope, private val liveViewWanted: StateFlow<Boolean>) {
    var name by mutableStateOf(name)
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    var recording by mutableStateOf(false)
        private set
    /** Заряд, %; null — камера не сообщила. */
    var battery by mutableStateOf<Int?>(null)
        private set
    /** Сеанс открыт. */
    var connected by mutableStateOf(false)
        private set
    /** Сколько кадров в секунду доходит до плитки. */
    var fps by mutableStateOf(0)
        private set
    var address: WiFiAddress? = null
        private set
    /** Связь пропала сама (не закрыли и не отдали главному экрану). */
    var onLost: (SideCamera) -> Unit = {}

    private var open: OpenCamera? = null
    private val jobs = mutableListOf<Job>()
    private val frameTimes = ArrayDeque<Long>()

    /** Камера в ленте: сеанс — её; превью — низкое, закрытие вернёт камере высокое. */
    fun attach(camera: OpenCamera) {
        stopJobs()
        open = camera
        address = camera.address
        connected = true
        camera.transport.farewell = CameraController.RESTORE_HIGH_QUALITY
        jobs += scope.launch {
            runCatching { camera.session.setLiveViewQuality(LiveViewQuality.low) }
                .onFailure { if (it is CancellationException) throw it else Log.d(TAG, "$name: низкое качество не записалось: ${it.message}") }
        }
        jobs += scope.launch { camera.session.properties.collect { apply(camera, it) } }
        jobs += scope.launch {
            camera.session.ended.await()
            if (open === camera) lost(camera)
        }
        jobs += scope.launch(Dispatchers.IO) { liveView(camera) }
    }

    /** Отдать сеанс главному экрану: свои корутины — стоп, сеанс открыт. */
    fun detach(): OpenCamera? {
        stopJobs()
        val camera = open
        open = null
        connected = false
        fps = 0
        return camera
    }

    /** Плитка камеры, пропавшей с большого экрана: её последний кадр, «нет связи», пока лента не подключит её снова. */
    fun showLast(image: ImageBitmap?) {
        if (open == null && image != null) frame = image
    }

    /** Убрать из ленты или выход: сеанс закрывается. */
    fun close() {
        detach()?.close()
    }

    private fun stopJobs() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }

    private fun lost(camera: OpenCamera) {
        Log.i(TAG, "$name: связь пропала")
        stopJobs()
        open = null
        connected = false
        fps = 0
        camera.close(abort = true)
        onLost(this)
    }

    private fun apply(camera: OpenCamera, properties: Map<Int, local.zve10viewer.sony.SonyProperty>) {
        val writing = properties[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue == 1L
        if (writing && camera.recordingSince == null) camera.recordingSince = System.currentTimeMillis()
        if (!writing) camera.recordingSince = null
        recording = writing
        battery = properties[SonyProp.BATTERY_LEVEL]?.current?.intValue?.toInt()?.takeIf { it in 0..100 }
    }

    /** Кадр раз в 200 мс; в фоне — пауза. Уменьшенный вдвое: плитке хватает. */
    private suspend fun liveView(camera: OpenCamera) {
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        var errors = 0
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            if (!liveViewWanted.value) liveViewWanted.first { it }
            val started = System.currentTimeMillis()
            try {
                val sample = camera.session.nextLiveViewFrame()
                errors = 0
                val bitmap = BitmapFactory.decodeByteArray(sample.data, sample.jpegOffset, sample.jpegLength, options)
                if (bitmap != null) {
                    val image = bitmap.asImageBitmap()
                    withContext(Dispatchers.Main) {
                        if (open !== camera) return@withContext
                        frame = image
                        val now = System.currentTimeMillis()
                        frameTimes.addLast(now)
                        while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2000) frameTimes.removeFirst()
                        fps = frameTimes.size / 2
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: TransportException) {
                if (e.disconnected) return
                errors++
                delay(minOf(200L * errors, 2000L))
            } catch (e: Exception) {
                errors++
                delay(minOf(200L * errors, 2000L))
            }
            delay(maxOf(0L, FRAME_MS - (System.currentTimeMillis() - started)))
        }
    }

    private companion object {
        const val TAG = "ZVStrip"
        /** Около 5 кадров в секунду. */
        const val FRAME_MS = 200L
    }
}
