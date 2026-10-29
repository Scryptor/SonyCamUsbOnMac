package local.zve10viewer.monitor

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Значки меток A и B на линейке шкалы фокуса: остриё — там, где метка, плашка с буквой — над ним. Две метки рядом (ближе одной
 * плашки) расходятся вокруг своей середины, чтобы буквы не закрывали друг друга; остриё остаётся там, где метка. Те же правила,
 * что у линейки в программе для Mac, с двумя поправками: левая плашка — у левой метки, какая бы у неё ни была буква, и пара у
 * края линейки сдвигается целиком (у Mac обе плашки там ложились на одно место). Числа — точки экрана; чистая геометрия, без Compose.
 */
object FocusFlags {
    /** Значок метки: [tip] — где остриё (от левого края линейки), [center] — середина плашки с буквой. */
    data class Flag(val mark: Char, val tip: Float, val center: Float)

    /**
     * Значки для меток [marks] (буква и доля шкалы 0…1) на линейке шириной [width]; плашка шириной [badge]. Порядок — слева направо,
     * при равенстве — как в [marks].
     */
    fun layout(marks: List<Pair<Char, Double>>, width: Float, badge: Float): List<Flag> {
        val sorted = marks.sortedBy { it.second }
        val tips = sorted.map { (width * it.second).toFloat() }
        val margin = badge / 2
        if (sorted.size == 2 && abs(tips[1] - tips[0]) < badge) {
            // плашки на расстоянии «плашка и зазор в точку» друг от друга, пара — вокруг середины острий; у края — целиком внутрь
            val half = (badge + 1f) / 2
            val middle = min(max((tips[0] + tips[1]) / 2, margin + half), width - margin - half)
            return listOf(Flag(sorted[0].first, tips[0], middle - half), Flag(sorted[1].first, tips[1], middle + half))
        }
        return sorted.mapIndexed { index, (mark, _) -> Flag(mark, tips[index], min(max(tips[index], margin), width - margin)) }
    }
}
