package local.zve10viewer.monitor

import local.zve10viewer.monitor.MonitorAssist.LutMode

/**
 * Отладочные extras DEBUG_ASSIST для LUT и вектороскопа (только debug-сборка):
 * `--es lut off|auto|slog3cine|slog3|slog2|hlg|file`, `--es lutFile Имя` (выбрать свой файл — режим «Свой»),
 * `--ez lutScopes false` (помощь по сигналу камеры), `--ez vectorscope true` и `--ei vectorscopeZoom 2`.
 *
 * Значения приходят как их положил `adb`: логические — `--ez`, но годятся и строки «on» / «off» / «true» / «false» (как у
 * `--es value on`), масштаб — `--ei` или строка. Что не понято, не применяется, а его имя возвращается в [Applied.rejected] —
 * в logcat видно, что команда не дошла, а состояние остаётся прежним. Разбор без Android — проверяется тестами на JVM.
 */
object AssistExtras {
    /** Что пришло; null — этого extra в команде нет. */
    data class Request(
        val lut: Any? = null,
        val lutFile: Any? = null,
        val lutScopes: Any? = null,
        val vectorscope: Any? = null,
        val vectorscopeZoom: Any? = null,
    ) {
        val isEmpty: Boolean get() = lut == null && lutFile == null && lutScopes == null && vectorscope == null && vectorscopeZoom == null
    }

    /** Настройки после команды и имена extras, которые не поняты. */
    class Applied(val assist: MonitorAssist, val rejected: List<String>)

    /** Сначала файл (он включает режим «Свой»), потом режим: `lut off` вместе с `lutFile` выключает LUT, но файл помнится. */
    fun apply(assist: MonitorAssist, request: Request): Applied {
        var next = assist
        val rejected = ArrayList<String>()
        request.lutFile?.let { value ->
            val name = (value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            if (name == null) rejected += "lutFile" else next = next.lutFileSelected(name)
        }
        request.lut?.let { value ->
            val mode = (value as? String)?.let { text -> LutMode.entries.firstOrNull { it.value == text } }
            if (mode == null) rejected += "lut" else next = next.with(MonitorTool.lut, mode.value) ?: next
        }
        request.lutScopes?.let { value ->
            val on = flag(value)
            if (on == null) rejected += "lutScopes" else next = next.copy(lutScopes = on)
        }
        request.vectorscope?.let { value ->
            val on = flag(value)
            if (on == null) rejected += "vectorscope" else next = next.with(MonitorTool.vectorscope, if (on) "on" else "off") ?: next
        }
        request.vectorscopeZoom?.let { value ->
            val zoom = zoom(value)
            if (zoom == null) rejected += "vectorscopeZoom" else next = next.copy(vectorscopeZoom = zoom)
        }
        return Applied(if (next == assist) assist else next, rejected)
    }

    /** Строка для logcat (тег ZVAssist) о том, что стало после команды; ключи — как в настройках. */
    fun describe(assist: MonitorAssist): String = "монитор: lut=${assist.lut.value} lutFile=${assist.lutFile ?: "-"} " +
        "lutScopes=${assist.lutScopes} vectorscope=${if (assist.vectorscope) "on" else "off"} zoom=${assist.vectorscopeZoom}"

    /** Логическое значение: Boolean, «on» / «off», «true» / «false», «1» / «0» без учёта регистра или число 1 / 0. */
    internal fun flag(value: Any?): Boolean? = when (value) {
        is Boolean -> value
        is Number -> when (value.toDouble()) {
            1.0 -> true
            0.0 -> false
            else -> null
        }
        is String -> when (value.trim().lowercase()) {
            "on", "true", "1", "yes" -> true
            "off", "false", "0", "no" -> false
            else -> null
        }
        else -> null
    }

    /** Масштаб вектороскопа: 1 или 2 — числом (`--ei`, `--el`, `--ef` с целым значением) или строкой. */
    internal fun zoom(value: Any?): Int? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()?.takeIf { value.trim().all { c -> c.isDigit() } }
            else -> null
        }
        return when (number) {
            1.0 -> 1
            2.0 -> 2
            else -> null
        }
    }
}
