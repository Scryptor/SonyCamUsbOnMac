package local.zve10viewer.session

/**
 * Нажатие кнопки записи и ответ камеры. Запрос висит, пока камера не подтвердит смену — свойством MovieRecordingState
 * (observe) или ответом самого вызова (completed), — но не дольше 6 с: тогда отказ. Второе нажатие, пока запрос идёт,
 * не считается. Вызов вернулся без смены — ещё не отказ: сеанс ждёт подтверждения только 2,5 с, а камера бывает
 * медленнее, — ждём свойства до 6 с. Вызов не прошёл (ошибка) — отказ сразу. Запись с кнопки самой камеры исходом не
 * считается: на неё отвечает рамка записи по свойству, а не отклик на нажатие. Время — в мс, без таймеров — для тестов.
 */
class RecordingToggle {
    enum class Request { starting, stopping }

    sealed class Outcome {
        data object Started : Outcome()
        data object Stopped : Outcome()
        data class Failed(val request: Request) : Outcome()
    }

    /** Чего ждём от камеры; null — ничего. */
    var request: Request? = null
        private set
    private var pressedAt = 0L

    /** Нажали кнопку записи; recording — идёт ли запись сейчас. false — запрос уже идёт, нажатие не считается. */
    fun press(recording: Boolean, now: Long): Boolean {
        if (request != null) return false
        request = if (recording) Request.stopping else Request.starting
        pressedAt = now
        return true
    }

    /** Камера сообщила свойство MovieRecordingState. */
    fun observe(recording: Boolean): Outcome? = resolve(recording)

    /** Вызов вернулся: идёт ли запись после него; null — вызов не прошёл. */
    fun completed(recording: Boolean?): Outcome? {
        val pending = request ?: return null
        if (recording == null) {
            request = null
            return Outcome.Failed(pending)
        }
        return resolve(recording)
    }

    /** Раз в полсекунды: камера так и не ответила — отказ. */
    fun tick(now: Long): Outcome? {
        val pending = request ?: return null
        if (now - pressedAt < TIMEOUT_MS) return null
        request = null
        return Outcome.Failed(pending)
    }

    private fun resolve(recording: Boolean): Outcome? = when (request) {
        Request.starting -> if (recording) Outcome.Started.also { request = null } else null
        Request.stopping -> if (!recording) Outcome.Stopped.also { request = null } else null
        null -> null
    }

    companion object {
        const val TIMEOUT_MS = 6000L
    }
}
