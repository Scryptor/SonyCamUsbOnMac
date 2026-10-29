package local.zve10viewer.monitor

import local.zve10viewer.sony.LensInformation

// Расстояния на шкале фокуса (спека §7.4). Данные объектива (LensInformation) говорят, какое расстояние при каком положении
// фокуса; здесь они ложатся на линейку шкалы: расстояние для места на ней, подписи под делениями, которые не налезают друг на
// друга. Положение метки по свойствам камеры — FocusPosition.fraction. Те же правила и числа, что в FocusDistanceScale.swift.

/**
 * Вся шкала таблицы: 65535, если положения в ней в шкале 0xE043, и 100, если в шкале 0xD24C. Какая из двух, таблица не говорит —
 * это догадка [LensInformation.absoluteScale] по наибольшему положению.
 */
val LensInformation.positionScale: Double get() = if (absoluteScale) 65535.0 else 100.0

/**
 * Расстояние в метрах для места на линейке — доли шкалы фокуса 0…1. Долю надо перевести в положение, как его записала таблица:
 * умножить на 65535 или на 100; за краями линейки — ближний или дальний край таблицы. null — расстояния нет: таблиц больше
 * одной, таблицы нет или доля не число.
 */
fun LensInformation.metersAtFraction(fraction: Double): Double? {
    if (fraction.isNaN()) return null
    return meters(fraction.coerceIn(0.0, 1.0) * positionScale)
}

/** То же подписью для шкалы фокуса («1,2 м», «∞»). */
fun LensInformation.labelAtFraction(fraction: Double): String? = metersAtFraction(fraction)?.let(LensInformation.Companion::label)

/**
 * Доля шкалы 0…1 с таким расстоянием: линейная интерполяция в обратную сторону по первому отрезку таблицы, где оно лежит. null —
 * таблица не одна или такого расстояния в ней нет.
 */
internal fun LensInformation.fractionAtMeters(target: Double): Double? {
    val first = pairs.firstOrNull()
    if (tableCount != 1 || first == null) return null
    if (pairs.size == 1) return if (first.meters == target) share(first.position, positionScale) else null
    for ((a, b) in pairs.zipWithNext()) {
        if (!(a.position < b.position && a.meters != b.meters)) continue
        if (target !in minOf(a.meters, b.meters)..maxOf(a.meters, b.meters)) continue
        val position = a.position + (target - a.meters) / (b.meters - a.meters) * (b.position - a.position)
        return share(position, positionScale)
    }
    return null
}

/** Положение таблицы как доля её шкалы, в пределах линейки. */
private fun share(position: Double, scale: Double): Double = (position / scale).coerceIn(0.0, 1.0)

/** Подписи расстояний под линейкой шкалы фокуса. */
object FocusDistanceScale {
    /** Подпись под линейкой: где (доля 0…1) и что написано. */
    data class Tick(val fraction: Double, val label: String)

    /** Круглые расстояния, метры — по важности: если места мало, подписывается то, что раньше в списке. */
    private val roundDistances = listOf(1.0, 0.5, 2.0, 5.0, 10.0, 3.0, 1.5, 0.3, 20.0, 50.0)

    /**
     * Какое место по горизонтали занимает подпись шириной [labelWidth] под делением в доле [fraction] линейки шириной [width]: она
     * стоит по центру под своим делением, но у краёв сдвигается внутрь линейки и не выходит за неё.
     */
    fun span(fraction: Double, labelWidth: Double, width: Double): ClosedFloatingPointRange<Double> {
        val half = minOf(labelWidth, width) / 2
        val centre = (fraction * width).coerceIn(half, width - half)
        return (centre - half)..(centre + half)
    }

    /**
     * Подписи под линейкой шириной [width]: оба края таблицы (ближнее и дальнее расстояние — «0,25 м», «∞») и круглые расстояния
     * между ними («0,5 м», «1 м», «2 м»…) там, где они лежат на шкале. Берётся столько, сколько поместится: краёв хватает всегда,
     * дальше по важности, и каждая подпись отстоит от остальных не меньше чем на [spacing] и не лезет на занятые места
     * [reserved] (например, на подпись у метки положения). [labelWidth] говорит, сколько места занимает текст. Расстояний нет —
     * таблиц не одна или таблицы нет — подписей тоже нет. По порядку слева направо. Все числа — в одних единицах (точки экрана).
     */
    fun ticks(
        lens: LensInformation, width: Double, spacing: Double = 12.0, reserved: List<ClosedFloatingPointRange<Double>> = emptyList(),
        labelWidth: (String) -> Double,
    ): List<Tick> {
        val near = lens.pairs.firstOrNull()
        val far = lens.pairs.lastOrNull()
        if (!(width > 0) || lens.tableCount != 1 || near == null || far == null) return emptyList()
        val scale = lens.positionScale
        val wanted = ArrayList<Tick>()
        wanted += Tick(share(near.position, scale), LensInformation.label(near.meters))
        if (lens.pairs.size > 1) wanted += Tick(share(far.position, scale), LensInformation.label(far.meters))
        val ends = wanted.map { it.label }.toSet()
        for (meters in roundDistances) {
            val fraction = lens.fractionAtMeters(meters) ?: continue
            val label = LensInformation.label(meters)
            if (label !in ends) wanted += Tick(fraction, label)
        }
        val taken = ArrayList(reserved)
        val placed = ArrayList<Tick>()
        for (tick in wanted) {
            val place = span(tick.fraction, labelWidth(tick.label), width)
            if (!taken.all { place.endInclusive + spacing <= it.start || it.endInclusive + spacing <= place.start }) continue
            taken += place
            placed += tick
        }
        return placed.sortedBy { it.fraction }
    }
}
