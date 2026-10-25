package local.zve10viewer.debug

import kotlin.math.roundToLong

/** Значение, ниже которого лежит доля q (0…1) чисел; null — чисел нет. Так же считает программа для Mac. */
fun percentile(values: List<Double>, q: Double): Double? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    if (q >= 1.0) return sorted.last()
    return sorted[(sorted.size * q).toInt().coerceIn(0, sorted.size - 1)]
}

/** Время ответа камеры на пробы за последнюю минуту: последнее, минимум и среднее. Время — в миллисекундах. */
class RttWindow(private val windowMs: Long = 60_000) {
    data class Stats(val lastMs: Long, val minMs: Long, val averageMs: Long)

    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun add(at: Long, ms: Long) {
        samples.addLast(at to ms)
        prune(at)
    }

    /** null — за минуту замеров не было. */
    fun stats(now: Long): Stats? {
        prune(now)
        if (samples.isEmpty()) return null
        return Stats(samples.last().second, samples.minOf { it.second }, samples.map { it.second }.average().roundToLong())
    }

    fun clear() = samples.clear()

    private fun prune(now: Long) {
        while (samples.isNotEmpty() && now - samples.first().first > windowMs) samples.removeFirst()
    }
}

/**
 * Кадры за последние секунды — для панели отладки: паузы между кадрами (за 10 с), время запроса кадра и «кадр не
 * готов» (за 5 с), поток превью (за 5 с). Время — в миллисекундах.
 */
class FrameWindow(private val keepMs: Long = 10_000) {
    private class Frame(val at: Long, val gapMs: Long?, val bytes: Int, val fetchMs: Double, val waits: Int)

    data class Gaps(val p50: Long, val p95: Long, val max: Long)
    data class Fetch(val p50: Long, val p95: Long, val waitsPerFrame: Double)

    private val frames = ArrayDeque<Frame>()
    private var lastAt: Long? = null
    private var since: Long? = null

    /** Кадр пришёл: размер ответа, сколько шёл запрос, сколько раз перед ним камера ответила «кадр не готов». */
    fun add(at: Long, bytes: Int, fetchMs: Double, waits: Int) {
        frames.addLast(Frame(at, lastAt?.let { at - it }, bytes, fetchMs, waits))
        lastAt = at
        if (since == null) since = at
        while (frames.isNotEmpty() && at - frames.first().at > keepMs) frames.removeFirst()
    }

    /** Паузы между кадрами за windowMs; null — пауз ещё нет (был один кадр или ни одного). */
    fun gaps(now: Long, windowMs: Long = keepMs): Gaps? {
        val gaps = recent(now, windowMs).mapNotNull { it.gapMs?.toDouble() }
        if (gaps.isEmpty()) return null
        return Gaps(percentile(gaps, 0.5)!!.toLong(), percentile(gaps, 0.95)!!.toLong(), gaps.max().toLong())
    }

    fun fetch(now: Long, windowMs: Long = 5000): Fetch? {
        val recent = recent(now, windowMs)
        if (recent.isEmpty()) return null
        val times = recent.map { it.fetchMs }
        return Fetch(percentile(times, 0.5)!!.roundToLong(), percentile(times, 0.95)!!.roundToLong(), recent.map { it.waits }.average())
    }

    /** Поток превью за windowMs, байт/с; пока кадры идут меньше окна — за то время, что идут. null — кадров нет. */
    fun throughput(now: Long, windowMs: Long = 5000): Double? {
        val recent = recent(now, windowMs)
        if (recent.isEmpty()) return null
        val span = minOf(windowMs, now - (since ?: now)).coerceAtLeast(500)
        return recent.sumOf { it.bytes.toDouble() } * 1000 / span
    }

    fun clear() {
        frames.clear()
        lastAt = null
        since = null
    }

    private fun recent(now: Long, windowMs: Long) = frames.filter { now - it.at <= windowMs }
}
