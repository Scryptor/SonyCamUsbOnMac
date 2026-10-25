package local.zve10viewer.session

/** Что сейчас со снимком: фокусировка (после полунажатия), спуск отработан, качается файл N из files. */
sealed class CapturePhase {
    data object Focusing : CapturePhase()
    data object Exposing : CapturePhase()
    /** received — сколько байт файла уже пришло, expected — его размер (null — неизвестен). */
    data class Downloading(val file: Int, val files: Int, val received: Long, val expected: Long?) : CapturePhase()
}

/** Подпись и дуга на кнопке спуска, пока камера снимает. Тексты — как у программы для Mac. */
object CaptureFeedback {
    /** Долю показываем только у файлов от мегабайта: мелкий приходит быстрее, чем дуга успела бы сдвинуться. */
    const val MIN_PROGRESS_BYTES = 1_048_576L

    fun caption(phase: CapturePhase): String = when (phase) {
        CapturePhase.Focusing -> "Фокусировка…"
        CapturePhase.Exposing -> "Снимаю…"
        is CapturePhase.Downloading -> {
            val percent = percent(phase)
            // RAW+JPEG — два файла подряд: доля — текущего
            val count = if (phase.files > 1) "${phase.file}/${phase.files}" else null
            when {
                percent == null -> if (count != null) "Загрузка $count…" else "Загрузка…"
                count != null -> "Загрузка $count · $percent %"
                else -> "Загрузка $percent %"
            }
        }
    }

    /** Доля скачанного 0…1; null — дуга крутится (не качается, файл мелкий или размер неизвестен). */
    fun fraction(phase: CapturePhase): Double? {
        val download = phase as? CapturePhase.Downloading ?: return null
        val expected = download.expected ?: return null
        if (expected < MIN_PROGRESS_BYTES) return null
        return (download.received.toDouble() / expected).coerceIn(0.0, 1.0)
    }

    /** Проценты с округлением вниз — «100 %» только когда пришло всё; в целых, без ошибок округления дробей. */
    private fun percent(phase: CapturePhase.Downloading): Long? {
        val expected = phase.expected ?: return null
        if (expected < MIN_PROGRESS_BYTES) return null
        return phase.received.coerceIn(0, expected) * 100 / expected
    }
}
