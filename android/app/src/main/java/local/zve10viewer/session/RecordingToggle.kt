package local.zve10viewer.session

import local.zve10viewer.ptp.PtpResponseCode

/**
 * Нажатие кнопки записи и ответ камеры. Запрос висит, пока камера не подтвердит смену — свойством MovieRecordingState
 * (observe) или ответом самого вызова (completed), — но не дольше 6 с: тогда отказ. Второе нажатие, пока запрос идёт,
 * не считается. Вызов вернулся без смены — ещё не отказ: сеанс ждёт подтверждения только 2,5 с, а камера бывает
 * медленнее, — ждём свойства до 6 с. Вызов не прошёл (ошибка) — отказ сразу: сеанс и после отказа камеры уже ждал,
 * не сменится ли запись. Заодно следит за концом записи: камера, начав запись по нажатию, бывает, тут же её бросает —
 * это отказ, а не молча погасшая рамка; запись, кончившаяся без нажатия в программе, — «остановлена на камере».
 * Время — в мс, без таймеров — для тестов.
 */
class RecordingToggle {
    enum class Request { starting, stopping }

    sealed class Outcome {
        data object Started : Outcome()
        data object Stopped : Outcome()
        data class Failed(val request: Request) : Outcome()
        /** Камера начала запись по нажатию и сама бросила её в первые секунды. */
        data object Aborted : Outcome()
        /** Запись кончилась без нажатия в программе: кнопкой на камере или сама камера. */
        data object StoppedByCamera : Outcome()
    }

    /** Чего ждём от камеры; null — ничего. */
    var request: Request? = null
        private set
    private var pressedAt = 0L
    /** Что камера сообщала последним; null — ещё ничего. */
    private var recording: Boolean? = null
    /** Когда подтвердился запуск с кнопки программы; null — запись не наша или её нет. */
    private var startedAt: Long? = null

    /** Нажали кнопку записи; recording — идёт ли запись сейчас. false — запрос уже идёт, нажатие не считается. */
    fun press(recording: Boolean, now: Long): Boolean {
        if (request != null) return false
        request = if (recording) Request.stopping else Request.starting
        pressedAt = now
        return true
    }

    /** Камера сообщила свойство MovieRecordingState: исход — подтверждение запроса или конец записи. */
    fun observe(recording: Boolean, now: Long): Outcome? {
        val ended = !recording && (this.recording == true || startedAt != null)
        this.recording = recording
        confirm(recording, now)?.let { return it }
        if (!ended) return null
        val quick = startedAt?.let { now - it < ABORT_WINDOW_MS } ?: false
        startedAt = null
        return if (quick) Outcome.Aborted else Outcome.StoppedByCamera
    }

    /**
     * Вызов вернулся: идёт ли запись после него; null — вызов не прошёл. Конец записи здесь не ищем: ответ вызова может
     * быть старше свойств, которые уже пришли.
     */
    fun completed(recording: Boolean?, now: Long): Outcome? {
        val pending = request ?: return null
        if (recording == null) {
            request = null
            return Outcome.Failed(pending)
        }
        return confirm(recording, now)
    }

    /** Раз в полсекунды: камера так и не ответила — отказ. */
    fun tick(now: Long): Outcome? {
        val pending = request ?: return null
        if (now - pressedAt < TIMEOUT_MS) return null
        request = null
        return Outcome.Failed(pending)
    }

    /** Подтверждает запрос; тогда и запоминает состояние — чтобы конец нашей же остановки не сочли концом «на камере». */
    private fun confirm(recording: Boolean, now: Long): Outcome? = when {
        request == Request.starting && recording -> {
            this.recording = true
            startedAt = now
            request = null
            Outcome.Started
        }
        request == Request.stopping && !recording -> {
            this.recording = false
            startedAt = null
            request = null
            Outcome.Stopped
        }
        else -> null
    }

    companion object {
        const val TIMEOUT_MS = 6000L
        /** Запись, кончившаяся раньше этого после запуска с кнопки программы, — камера её «сразу бросила». */
        const val ABORT_WINDOW_MS = 3000L

        /**
         * Что сказать в строке состояния; null — исход виден и так (кнопка, рамка записи). refusal — код, которым камера
         * отклонила нажатие.
         */
        fun message(outcome: Outcome, refusal: Int?): String? = when (outcome) {
            Outcome.Started, Outcome.Stopped -> null
            is Outcome.Failed -> {
                val result = if (outcome.request == Request.starting) "запись не началась" else "запись не остановилась"
                when (refusal) {
                    null -> if (outcome.request == Request.starting) "Камера не начала запись" else "Камера не остановила запись"
                    PtpResponseCode.DEVICE_BUSY -> "Камера занята — $result"
                    else -> "Камера не приняла нажатие — $result"
                }
            }
            Outcome.Aborted -> "Камера начала запись и сразу остановила"
            Outcome.StoppedByCamera -> "Запись остановлена на камере"
        }
    }
}
