package local.zve10viewer.session

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetResult
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.WhiteBalanceTemperature
import local.zve10viewer.settings.WriteCoalescer
import local.zve10viewer.settings.apply
import local.zve10viewer.sony.SonyProp

/**
 * Что связанная камера ленты повторяет за главной на своём сеансе: настройки, пресеты и запись. Настройки — по свойству
 * одна запись за раз и только последнее значение (WriteCoalescer), на всю камеру — одна запись за раз (и пресет
 * целиком): камера, занятая прошлой записью, следующую пропускает. Судим только последнее значение: быстрые шаги
 * колесом не дают ложных «не удалось». Корутины — в scope (не сеанса): отдали сеанс главному экрану — начатая запись
 * доходит до подтверждения, а не обрывается посреди обмена. Звать с одного потока. Без Android — чтобы проверять тестами.
 */
class GroupFollower(private val session: CameraSession, scope: CoroutineScope, private val onFailed: (SettingID, Long) -> Unit) {
    private val gate = Mutex()
    /** Какое значение по свойству ждём последним. */
    private val requested = HashMap<Int, Long>()
    /** Нажатия записи — по одному: «стоп», пока камера не ответила на «пуск», смотрит уже на её ответ. */
    private val recordGate = Mutex()
    private var stopped = false

    private val coalescer = WriteCoalescer(scope) { code, value ->
        val confirmed = gate.withLock {
            try {
                put(code, value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.d(TAG, "0x${code.toString(16)} = ${value.toString(16)} не записалось: ${e.message}")
                false
            }
        }
        finished(code, value, confirmed)
    }

    /** То же значение, что на главной. Уже такое на камере и ничего не ждём — не пишем. */
    fun write(id: SettingID, value: Long) {
        if (stopped) return
        val properties = session.properties.value
        // температура — ещё и в режиме «Цвет. темп.»: без него та же цифра на камере ничего не значит
        val same = properties[id.code]?.current?.intValue == value && (id != SettingID.colorTemperature ||
            properties[SonyProp.WHITE_BALANCE]?.current?.intValue == WhiteBalanceTemperature.MANUAL_MODE)
        if (same && id.code !in requested) return
        requested[id.code] = value
        coalescer.submit(id.code, value)
    }

    /** Пресет — и на эту камеру. */
    suspend fun apply(preset: Preset): PresetResult = gate.withLock { session.apply(preset) }

    /** Запись — к тому же состоянию, что на главной: нажимаем, только если иначе. Возвращает, вышло ли. */
    suspend fun setRecording(on: Boolean): Boolean = recordGate.withLock {
        if (stopped) return@withLock false
        if ((session.properties.value[SonyProp.MOVIE_RECORDING_STATE]?.current?.intValue == 1L) == on) return@withLock true
        try {
            session.toggleRecording() == on
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "запись не переключилась: ${e.message}")
            false
        }
    }

    /** Сеанс больше не у ленты: новых записей нет, начатые доходят, но их не судим. */
    fun stop() {
        stopped = true
        requested.clear()
    }

    /** Одна запись. Температура — сначала «Цвет. темп.» в балансе белого, если камера не в нём: как ползунок на главной. */
    private suspend fun put(code: Int, value: Long): Boolean {
        if (code == SonyProp.COLOR_TEMP) {
            if (session.properties.value[SonyProp.WHITE_BALANCE]?.current?.intValue != WhiteBalanceTemperature.MANUAL_MODE &&
                session.set(SonyProp.WHITE_BALANCE, WhiteBalanceTemperature.MANUAL_MODE) != SetResult.Confirmed) return false
            // температуру камера открывает не сразу после смены режима
            var waits = 0
            while (session.properties.value[code]?.isWritable != true && waits++ < 40) delay(100)
        }
        val confirmed = session.set(code, value) == SetResult.Confirmed
        // формат камера подтверждает раньше, чем готова принять следующую запись — как на главной
        if (code == SonyProp.MOVIE_FILE_FORMAT) delay(500)
        return confirmed
    }

    private fun finished(code: Int, value: Long, confirmed: Boolean) {
        if (stopped || requested[code] != value) return
        requested.remove(code)
        if (confirmed) return
        SettingID.entries.firstOrNull { it.code == code }?.let { onFailed(it, value) }
    }

    private companion object {
        const val TAG = "ZVGroup"
    }
}
