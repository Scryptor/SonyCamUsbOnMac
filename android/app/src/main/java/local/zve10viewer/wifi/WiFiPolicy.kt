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

    /** Сеть Wi-Fi появилась — пробуем сразу, не дожидаясь паузы после обрыва. */
    fun networkAvailable() {
        nextAttempt = 0
    }

    companion object {
        /**
         * Сколько восстанавливаем оборвавшийся сеанс тихо — с последним кадром на экране, по тому же адресу, без
         * поиска. Дальше — обычное ожидание: экран «Подключите камеру» и поиск камеры в сети.
         */
        const val RECONNECT_WINDOW_MS = 60_000L

        private val RECONNECT_DELAYS = longArrayOf(0, 500, 1000, 2000, 3000)

        /** Пауза перед попыткой номер attempt (с нуля): первая — сразу, дальше 0,5, 1, 2, 3, 5, 5… с. */
        fun reconnectDelayMs(attempt: Int): Long = RECONNECT_DELAYS.getOrElse(attempt) { 5000 }

        /**
         * Бросить тихое восстановление и перейти к обычному ожиданию: прошла минута, или камера отказала в
         * соединении (PC Remote выключили) — тогда ждать по тому же адресу нечего.
         */
        fun reconnectGivesUp(startedAt: Long, now: Long, refused: Boolean): Boolean =
            refused || now - startedAt >= RECONNECT_WINDOW_MS

        /**
         * Отказ камеры в приветствии (Init Fail): забывать ли сопряжение. 1 — камера программу не знает;
         * 2 — занята (например, ещё держит прошлое подключение), 3 — без причины: это проходит, сопряжение не трогаем.
         */
        fun forgetsPairing(initFailReason: Long): Boolean = initFailReason == 1L
    }
}
