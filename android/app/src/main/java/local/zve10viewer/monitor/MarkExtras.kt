package local.zve10viewer.monitor

/**
 * Отладочные extras DEBUG_LENS для меток фокуса (только debug-сборка): `--es mark A|B` и `--es markOp save|go|clear|tap|stop`
 * (без `markOp` — `tap`, как касание кнопки метки: пуста — запомнить, задана — ехать, едет к ней — стоп; `stop` метки не требует),
 * `--ei pullTime 0|1|2|4|8` (время перевода, секунд). Время ставится раньше метки: `--ei pullTime 4 --es mark A --es markOp go`
 * едет за 4 с. Значения приходят как их положил `adb` (число или строка), буквы и названия — без учёта регистра. Что не понято, не
 * применяется, а имя extra возвращается в [Parsed.rejected] — в logcat видно, что команда не дошла. Разбор без Android —
 * проверяется тестами на JVM.
 */
object MarkExtras {
    /** Что пришло; null — этого extra в команде нет. */
    data class Request(val mark: Any? = null, val markOp: Any? = null, val pullTime: Any? = null) {
        val isEmpty: Boolean get() = mark == null && markOp == null && pullTime == null
    }

    /** Что сделать с меткой. */
    enum class Op { save, go, clear, tap, stop }

    /**
     * Что разобрано: время перевода (null — не менять), метка и действие над ней (null — ничего не делать) и имена непонятых extras.
     */
    class Parsed(val pullTime: Double?, val mark: Char?, val op: Op?, val rejected: List<String>)

    fun parse(request: Request): Parsed {
        val rejected = ArrayList<String>()
        val time = if (request.pullTime == null) null else time(request.pullTime).also { if (it == null) rejected += "pullTime" }
        val mark = if (request.mark == null) null else letter(request.mark).also { if (it == null) rejected += "mark" }
        val requested = when {
            request.markOp != null -> operation(request.markOp).also { if (it == null) rejected += "markOp" }
            // метка без действия — касание её кнопки; непонятая метка действия не даёт
            mark != null -> Op.tap
            else -> null
        }
        val op = when {
            requested == null -> null
            requested == Op.stop -> Op.stop
            mark != null -> requested
            else -> {
                // метки нет вовсе — это называем; непонятая метка уже названа
                if (request.mark == null) rejected += "mark"
                null
            }
        }
        return Parsed(time, mark, op, rejected)
    }

    private fun letter(value: Any?): Char? =
        (value as? String)?.trim()?.uppercase()?.singleOrNull()?.takeIf { it in FocusMarkBook.letters }

    private fun operation(value: Any?): Op? {
        val name = (value as? String)?.trim()?.lowercase()
        return Op.entries.firstOrNull { it.name == name }
    }

    /** Время из списка 0, 1, 2, 4, 8: число (`--ei`, `--ef`) или строка с числом. */
    private fun time(value: Any?): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return FocusPull.durations.firstOrNull { it == number }
    }
}
