package local.zve10viewer.wifi

/** Когда и к кому подключаться по Wi-Fi. Без сети и таймеров, чтобы проверять тестами; время — в миллисекундах. */
class WiFiPolicy {
    /** Неудачные попытки подряд к камере, которую сейчас видно в сети. */
    var failures = 0
        private set
    /** Оборвавшиеся подряд сеансы — от них растёт пауза перед новой попыткой. */
    var sessionLosses = 0
        private set
    var nextAttempt = 0L
        private set

    /** Стучаться ли сейчас: Wi-Fi включён или идёт сопряжение; после обрыва — выждав паузу (сопряжение не ждёт). */
    fun mayAttempt(enabled: Boolean, pairing: Boolean, now: Long): Boolean = pairing || (enabled && now >= nextAttempt)

    /**
     * Приветствие — только камере, с которой программа уже работала, или при сопряжении:
     * камера в режиме Pairing показывает любой входящий запрос, и чужой стук мешал бы сопрягать другое устройство.
     */
    fun maySendInit(paired: Boolean, pairing: Boolean): Boolean = paired || pairing

    /** Неудачная попытка. Считаем только попытки к камере, найденной в сети сейчас: адрес из памяти мог устареть. */
    fun attemptFailed(discovered: Boolean) {
        if (discovered) failures++
    }

    /** Камера видна, но трижды подряд не ответила — похоже, держит прошлое подключение. */
    val showsStaleHint: Boolean get() = failures >= 3

    /** В сети камеры не видно — счёт неудач с начала. */
    fun nothingFound() {
        failures = 0
    }

    /** Сеанс начат. */
    fun connected() {
        failures = 0
    }

    /** Сеанс дошёл до готовности — прошлые обрывы забыты. */
    fun sessionReady() {
        sessionLosses = 0
        nextAttempt = 0
    }

    /** Сеанс оборвался. Пауза перед новой попыткой — 2, 4, 8, 10, 10… с, как у USB. Возвращает её в секундах. */
    fun sessionLost(now: Long): Int {
        sessionLosses++
        val seconds = minOf(1 shl minOf(sessionLosses, 4), 10)
        nextAttempt = now + seconds * 1000L
        return seconds
    }
}
