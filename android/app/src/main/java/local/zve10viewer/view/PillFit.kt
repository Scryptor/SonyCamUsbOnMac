package local.zve10viewer.view

/**
 * Какие пункты строки состояния видны, когда ей не хватает места: строка не шире [room], пункты прячутся по порядку — сначала
 * тот, у кого число в [order] меньше; `Int.MAX_VALUE` — виден всегда, даже если места нет совсем. Ширины и [gap] между пунктами —
 * в одних единицах. Отдаёт для каждого пункта, виден ли он.
 */
object PillFit {
    fun shown(widths: List<Int>, order: List<Int>, gap: Int, room: Int): List<Boolean> {
        val shown = BooleanArray(widths.size) { true }
        fun total(): Int {
            val visible = widths.indices.filter { shown[it] }
            return visible.sumOf { widths[it] } + gap * (visible.size - 1).coerceAtLeast(0)
        }
        while (total() > room) {
            val next = widths.indices.filter { shown[it] && order[it] != Int.MAX_VALUE }.minByOrNull { order[it] } ?: break
            shown[next] = false
        }
        return shown.toList()
    }
}
