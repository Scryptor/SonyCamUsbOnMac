package local.zve10viewer.view

/**
 * Удаление снимка из галереи: какой снимок показать вместо удалённого и куда его вернуть по «Вернуть». Лента — новые
 * сначала. Те же правила, что у программы для Mac.
 */
object ShotDeletion {
    /** Сколько видна плашка «Вернуть», мс. */
    const val UNDO_MILLIS = 6_000L

    /**
     * Удалили снимок с места removing из count: место (в ленте уже без него) снимка, который показать, — того, что встал
     * на место удалённого (он старше); удалили самый старый — соседнего новее; null — снимков не осталось.
     */
    fun neighbor(removing: Int, count: Int): Int? = if (count > 1) removing.coerceIn(0, count - 2) else null

    /**
     * Куда вернуть снимок, снятый в takenAt (мс): перед первым снятым раньше него. Пока висела «Вернуть», могли прийти
     * новые снимки — по месту в ленте его уже не вернуть, по времени — да.
     */
    fun restoreIndex(takenAt: Long, times: List<Long>): Int = times.indexOfFirst { it < takenAt }.let { if (it < 0) times.size else it }
}
