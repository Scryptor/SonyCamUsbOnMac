package local.zve10viewer.monitor

import local.zve10viewer.monitor.MonitorAssist.LutMode
import java.util.Locale

/**
 * Отладочные extras DEBUG_ASSIST для LUT, вектороскопа, картинки и пипетки (только debug-сборка):
 * `--es lut off|auto|slog3cine|slog3|slog2|hlg|file`, `--es lutFile Имя` (выбрать свой файл — режим «Свой»),
 * `--ez lutScopes false` (помощь по сигналу камеры), `--ez vectorscope true` и `--ei vectorscopeZoom 2`,
 * `--es orientation off|mirror|180|mirror180` (зеркало и переворот картинки), `--ez probe true` (пипетка) и
 * `--ef probeX 0.69 --ef probeY 0.3` (где она стоит — экранные доли кадра, каким он виден).
 *
 * Значения приходят как их положил `adb`: логические — `--ez`, но годятся и строки «on» / «off» / «true» / «false» (как у
 * `--es value on`), масштаб — `--ei` или строка, место пипетки — `--ef`, `--ei` или строка. Что не понято, не применяется, а его
 * имя возвращается в [Applied.rejected] — в logcat видно, что команда не дошла, а состояние остаётся прежним. Место пипетки за
 * кадром — к краю кадра, как у действия API на Mac. Разбор без Android — проверяется тестами на JVM.
 *
 * `orientation` здесь — картинка на экране, а не поворот окна: тот же ключ у `am start` (`--es orientation portrait`) читает
 * `MainActivity` для своего окна, и это другой интент.
 */
object AssistExtras {
    /** Что пришло; null — этого extra в команде нет. */
    data class Request(
        val lut: Any? = null,
        val lutFile: Any? = null,
        val lutScopes: Any? = null,
        val vectorscope: Any? = null,
        val vectorscopeZoom: Any? = null,
        val orientation: Any? = null,
        val probe: Any? = null,
        val probeX: Any? = null,
        val probeY: Any? = null,
    ) {
        val isEmpty: Boolean
            get() = lut == null && lutFile == null && lutScopes == null && vectorscope == null && vectorscopeZoom == null &&
                orientation == null && probe == null && probeX == null && probeY == null
    }

    /** Настройки после команды, где стоит пипетка (экранные доли кадра) и имена extras, которые не поняты. */
    class Applied(val assist: MonitorAssist, val rejected: List<String>, val probe: FramePoint = ColorProbe.CENTER)

    /**
     * Сначала файл (он включает режим «Свой»), потом режим: `lut off` вместе с `lutFile` выключает LUT, но файл помнится. [probe] —
     * где пипетка стоит сейчас: `probeX` и `probeY` двигают по одной координате, вторая остаётся на месте.
     */
    fun apply(assist: MonitorAssist, request: Request, probe: FramePoint = ColorProbe.CENTER): Applied {
        var next = assist
        var point = probe
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
        request.orientation?.let { value ->
            val orientation = (value as? String)?.let { text -> FrameOrientation.entries.firstOrNull { it.value == text } }
            if (orientation == null) rejected += "orientation" else next = next.with(MonitorTool.orientation, orientation.value) ?: next
        }
        request.probe?.let { value ->
            val on = flag(value)
            if (on == null) rejected += "probe" else next = next.with(MonitorTool.probe, if (on) "on" else "off") ?: next
        }
        request.probeX?.let { value ->
            val x = fraction(value)
            if (x == null) rejected += "probeX" else point = FramePoint(x, point.y)
        }
        request.probeY?.let { value ->
            val y = fraction(value)
            if (y == null) rejected += "probeY" else point = FramePoint(point.x, y)
        }
        return Applied(if (next == assist) assist else next, rejected, point)
    }

    /**
     * Строка для logcat (тег ZVAssist) о том, что стало после команды; ключи — как в настройках. [probe] — где пипетка: экранные
     * доли кадра, каким он виден, `at=0.688,0.300`.
     */
    fun describe(assist: MonitorAssist, probe: FramePoint = ColorProbe.CENTER): String = String.format(
        Locale.ROOT, "монитор: lut=%s lutFile=%s lutScopes=%b vectorscope=%s zoom=%d orient=%s probe=%s at=%.3f,%.3f",
        assist.lut.value, assist.lutFile ?: "-", assist.lutScopes, if (assist.vectorscope) "on" else "off", assist.vectorscopeZoom,
        assist.orientation.value, if (assist.probe) "on" else "off", probe.x, probe.y,
    )

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

    /** Доля кадра: число (`--ef`, `--ei`, `--ed`) или строка с числом; за кадром — к краю 0…1. Не число и бесконечность — null. */
    internal fun fraction(value: Any?): Double? {
        val number = when (value) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        } ?: return null
        return if (number.isFinite()) number.coerceIn(0.0, 1.0) else null
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
