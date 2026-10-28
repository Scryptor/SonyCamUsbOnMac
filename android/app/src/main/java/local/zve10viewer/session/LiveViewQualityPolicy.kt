package local.zve10viewer.session

/** Качество превью камеры (свойство 0xD26A LiveViewImageQuality). */
enum class LiveViewQuality(val cameraValue: Long, val title: String) {
    /** 640×360, ~34 КБ на кадр. */
    low(1, "низкое"),
    /** 1024×576, ~72 КБ на кадр. */
    high(2, "высокое");

    companion object {
        /** По ширине кадра: на высоком камера отдаёт 1024, на низком — 640. */
        fun ofWidth(width: Int): LiveViewQuality? = when {
            width >= 1024 -> high
            width > 0 -> low
            else -> null
        }

        fun of(cameraValue: Long?): LiveViewQuality? = entries.firstOrNull { it.cameraValue == cameraValue }
    }

    /** Ширина кадра превью этого качества. */
    val frameWidth: Int get() = if (this == high) 1024 else 640

    /** Размер кадра этого качества при такой форме кадра (ширина к высоте): «1024×576», в фото 3:2 — «1024×683». */
    fun sizeLabel(aspect: Double): String {
        val shape = if (aspect > 0) aspect else 16.0 / 9
        return "$frameWidth×${Math.round(frameWidth / shape)}"
    }
}

/**
 * Какое качество превью держать по Wi-Fi — выбор в подсказке к счётчику кадров и в настройках, как у программы для Mac.
 * По USB камера всегда отдаёт полное: свойство качества там картинку не меняет.
 */
enum class PreviewQualityMode {
    /** Решает программа: снижает до низкого, когда канал не тянет высокое, и возвращает, когда тянет снова. */
    auto,
    /** Всегда высокое (1024 точки по ширине), даже если на слабом Wi-Fi картинка дёргается. */
    high,
    /** Всегда низкое (640 точек): вдвое меньше трафика, плавнее на слабом Wi-Fi, но мельче. */
    low;

    /** Что держать; null — решает политика по каналу. */
    val fixed: LiveViewQuality? get() = when (this) {
        auto -> null
        high -> LiveViewQuality.high
        low -> LiveViewQuality.low
    }

    companion object {
        /** Из настроек: новое значение, иначе прежний переключатель «снижать при слабом Wi-Fi» (выключен — всегда высокое). */
        fun restored(raw: String?, legacyAdaptive: Boolean?): PreviewQualityMode =
            entries.firstOrNull { it.name == raw } ?: if (legacyAdaptive == false) high else auto
    }
}

/** Пороги политики качества; время — в миллисекундах. Те же, что у программы для Mac. */
data class QualityTiming(
    /** Ниже этой частоты на высоком (прогноз) — снижаем. */
    val downFps: Double = 12.0,
    /** Не ниже этой частоты на высоком (прогноз) — возвращаем. */
    val upFps: Double = 18.0,
    /** Пока картинка увеличена (≥ 1,5×), полное превью держим до последнего: детали важнее плавности. */
    val sharpDownFps: Double = 6.0,
    val sharpUpFps: Double = 10.0,
    val downWindowMs: Long = 5000,
    val upWindowMs: Long = 10_000,
    /** Столько быстрых кадров нужно в окне вниз, чтобы судить о канале. */
    val minDownSamples: Int = 8,
    /** Столько кадров нужно в окне вверх. */
    val minUpSamples: Int = 30,
    /** Кадр шёл дольше — это замирание связи, а не узкий канал: в оценку полосы не идёт. */
    val stallMs: Double = 1500.0,
    /** Столько замираний за stallWindowMs — канал плохой, даже если между ними кадры идут быстро. Одно — не повод. */
    val stallsToDown: Int = 3,
    val stallWindowMs: Long = 20_000,
    /** Выдержка на низком перед попыткой вернуть высокое. */
    val initialHoldMs: Long = 15_000,
    val maxHoldMs: Long = 300_000,
    /** Подъём не удался, если пришлось спуститься раньше этого. */
    val failedUpWithinMs: Long = 15_000,
    /** Столько устойчиво на высоком после подъёма — выдержка снова короткая. */
    val stableAfterMs: Long = 120_000,
    /** Камера отдаёт превью не чаще ~25 к/с. */
    val cameraFps: Double = 25.0,
    /** Время отклика — минимум за это окно. */
    val rttWindowMs: Long = 10_000,
    /** Пока замеров отклика нет. */
    val defaultRttMs: Double = 10.0,
    /** Камера не перешла на запрошенное качество за это время — запись не удалась, решаем заново. */
    val switchTimeoutMs: Long = 5000,
    /** Размер кадра высокого качества, пока своих замеров нет. */
    val highFrameBytes: Double = 72.0 * 1024,
)

/**
 * Когда снижать и когда возвращать качество превью по Wi-Fi. Высокое при 25 к/с — ~1,8 МБ/с (~14 Мбит/с), почти
 * потолок 2,4 ГГц камеры: на слабом канале оно не помещается, и картинка дёргается. Решаем по пропускной способности,
 * которую видно по самим быстрым кадрам: G = Σбайт / Σ(время запроса − RTT), прогноз на высоком
 * fpsH = min(25, 1 / (RTT + размерH / G)). Вниз — на высоком за 5 с fpsH < 12 или 3 замирания за 20 с; вверх — на
 * низком после выдержки за 10 с fpsH ≥ 18 и ни одного замирания. Выдержка 15 с, после неудачного подъёма — вдвое
 * дольше (до 5 мин), после 2 мин устойчивого высокого — снова 15 с. Во время провала связи решений нет.
 * Без сети и таймеров — для тестов; время подставляется. Вызывать с одного потока.
 */
class LiveViewQualityPolicy(private val timing: QualityTiming = QualityTiming(), now: Long) {
    private class Sample(val at: Long, val bytes: Int, val fetchMs: Double)

    /** Каким качеством сейчас идут кадры (по их ширине); null — кадров ещё не было. */
    var level: LiveViewQuality? = null
        private set
    /** Выдержка на низком перед следующей попыткой вернуть высокое, мс. */
    var holdMs = timing.initialHoldMs
        private set
    /** Средний размер кадра высокого качества — по увиденным кадрам, пока их нет — типичный. */
    var highFrameBytes = timing.highFrameBytes
        private set

    private var holdUntil = Long.MIN_VALUE
    /** С какого момента кадры идут этим качеством. */
    private var levelSince = now
    private var lastUpAt: Long? = null
    private var requested: LiveViewQuality? = null
    private var requestedAt = 0L
    private val samples = ArrayDeque<Sample>()
    private val rtts = ArrayDeque<Pair<Long, Double>>()

    /** Кадр получен: размер ответа, сколько шёл запрос, вернувший его (от отправки до ответа), каким качеством. */
    fun frame(now: Long, bytes: Int, fetchMs: Double, observed: LiveViewQuality) {
        if (observed != level) {
            // камера перешла (по нашей записи или её переключили не мы) — окна считаем от этого момента
            level = observed
            levelSince = now
            samples.clear()
        }
        if (requested == observed) requested = null
        if (observed == LiveViewQuality.high) highFrameBytes = highFrameBytes * 0.9 + bytes * 0.1
        samples.addLast(Sample(now, bytes, fetchMs))
        val keep = maxOf(timing.upWindowMs, timing.stallWindowMs)
        while (samples.isNotEmpty() && now - samples.first().at > keep) samples.removeFirst()
    }

    /** Время короткого ответа камеры (проба, AccessDenied), мс. Берётся минимум за 10 с. */
    fun rtt(now: Long, ms: Double) {
        rtts.addLast(now to ms.coerceIn(1.0, 500.0))
        while (rtts.isNotEmpty() && now - rtts.first().first > timing.rttWindowMs) rtts.removeFirst()
    }

    /** Программа сама попросила камеру сменить качество (не по решению политики, например в начале сеанса). */
    fun requested(now: Long, target: LiveViewQuality) {
        requested = target
        requestedAt = now
    }

    /** Идёт смена качества: камера ещё не перешла, а время ожидания не вышло. */
    fun isSwitching(now: Long): Boolean = requested != null && now - requestedAt < timing.switchTimeoutMs

    /**
     * Какое качество нужно сейчас; null — оставить как есть. outage — связь пропала: о канале ничего не известно.
     * sharp — картинка увеличена (≥ 1,5×): снижаем при прогнозе меньше 6 к/с и возвращаем с 10 (вместо 12 и 18).
     */
    fun decide(now: Long, outage: Boolean, sharp: Boolean = false): LiveViewQuality? {
        if (requested != null) {
            if (now - requestedAt < timing.switchTimeoutMs) return null
            requested = null
        }
        if (outage) return null
        val current = level ?: return null
        val target = when (current) {
            LiveViewQuality.high -> if (shouldGoDown(now, if (sharp) timing.sharpDownFps else timing.downFps)) LiveViewQuality.low else null
            LiveViewQuality.low -> if (shouldGoUp(now, if (sharp) timing.sharpUpFps else timing.upFps)) LiveViewQuality.high else null
        }
        if (target == null) {
            val up = lastUpAt
            if (current == LiveViewQuality.high && up != null && now - up >= timing.stableAfterMs) {
                holdMs = timing.initialHoldMs
                lastUpAt = null
            }
            return null
        }
        if (target == LiveViewQuality.low) {
            val up = lastUpAt
            if (up != null && now - up < timing.failedUpWithinMs) holdMs = minOf(holdMs * 2, timing.maxHoldMs)
            lastUpAt = null
            holdUntil = now + holdMs
        } else {
            lastUpAt = now
        }
        requested(now, target)
        return target
    }

    /** Прогноз частоты превью на высоком качестве по быстрым кадрам за окно; null — таких кадров нет. */
    fun predictedHighFps(now: Long, windowMs: Long): Double? {
        val g = throughput(now, windowMs) ?: return null
        return minOf(timing.cameraFps, 1000.0 / (rttMs(now) + highFrameBytes / g))
    }

    /** Пропускная способность канала по быстрым кадрам за окно, байт/мс (≈ КБ/с); null — таких кадров нет. */
    fun throughput(now: Long, windowMs: Long): Double? {
        val fast = samples.filter { now - it.at <= windowMs && it.fetchMs <= timing.stallMs }
        if (fast.isEmpty()) return null
        val rtt = rttMs(now)
        return fast.sumOf { it.bytes.toDouble() } / fast.sumOf { maxOf(it.fetchMs - rtt, 2.0) }
    }

    fun rttMs(now: Long): Double =
        rtts.filter { now - it.first <= timing.rttWindowMs }.minOfOrNull { it.second } ?: timing.defaultRttMs

    /**
     * Через сколько мс самое раннее можно вернуть высокое: кончится выдержка и наберётся окно на низком. null — кадры
     * и так идут высоким (или их ещё нет).
     */
    fun upAttemptInMs(now: Long): Long? {
        if (level != LiveViewQuality.low) return null
        return maxOf(holdUntil, levelSince + timing.upWindowMs, now) - now
    }

    private fun shouldGoDown(now: Long, downFps: Double): Boolean {
        if (now - levelSince < timing.downWindowMs) return false
        val stalls = samples.count { now - it.at <= timing.stallWindowMs && it.fetchMs > timing.stallMs }
        if (stalls >= timing.stallsToDown) return true
        val fast = samples.count { now - it.at <= timing.downWindowMs && it.fetchMs <= timing.stallMs }
        if (fast < timing.minDownSamples) return false
        val fps = predictedHighFps(now, timing.downWindowMs) ?: return false
        return fps < downFps
    }

    private fun shouldGoUp(now: Long, upFps: Double): Boolean {
        if (now < holdUntil || now - levelSince < timing.upWindowMs) return false
        val recent = samples.filter { now - it.at <= timing.upWindowMs }
        if (recent.size < timing.minUpSamples || recent.any { it.fetchMs > timing.stallMs }) return false
        val fps = predictedHighFps(now, timing.upWindowMs) ?: return false
        return fps >= upFps
    }
}
