package local.zve10viewer.settings

/** Режим записи видео одним выбором: формат (`0xD241`) и параметры записи (`0xD242`) вместе — «4K 25p 100M». */
data class RecordingMode(val format: Long, val setting: Long) {
    /** Одно число для ленты значений: формат в старшем байте, параметры — в младшем. */
    val code: Long get() = (format shl 8) or setting

    val label: String
        get() {
            val name = when (format) {
                RecordingModes.FOUR_K -> "4K"
                RecordingModes.HD -> "HD"
                else -> ValueFormatter.label(SettingID.movieFileFormat, format)
            }
            return "$name ${ValueFormatter.label(SettingID.movieRecordingSetting, setting)}"
        }

    companion object {
        fun of(code: Long) = RecordingMode(code shr 8, code and 0xFF)
    }
}

/**
 * Какие режимы записи предложить. Камера перечисляет параметры записи только для текущего формата,
 * поэтому для второго формата они берутся из таблицы кодов — той же системы (PAL или NTSC), что у текущих.
 */
object RecordingModes {
    const val FOUR_K = 8L
    const val HD = 9L

    /** Частота и битрейт по коду `0xD242`: XAVC S HD — `0x01`–`0x1F`, XAVC S 4K — `0x20`–`0x25`. */
    private val specs: Map<Long, Pair<Int, Int>> = mapOf(
        0x01L to (60 to 50), 0x02L to (30 to 50), 0x03L to (24 to 50), 0x04L to (50 to 50), 0x05L to (25 to 50),
        0x18L to (60 to 25), 0x19L to (50 to 25), 0x1AL to (30 to 16), 0x1BL to (25 to 16),
        0x1CL to (120 to 100), 0x1DL to (100 to 100), 0x1EL to (120 to 60), 0x1FL to (100 to 60),
        0x20L to (30 to 100), 0x21L to (25 to 100), 0x22L to (24 to 100),
        0x23L to (30 to 60), 0x24L to (25 to 60), 0x25L to (24 to 60),
    )

    private fun formatOf(setting: Long) = if (setting >= 0x20) FOUR_K else HD
    private fun isPal(setting: Long) = specs[setting]?.first in setOf(25, 50, 100)

    /** formats — какие форматы камера разрешает, format — текущий, allowed — параметры, разрешённые для текущего. */
    fun options(formats: List<Long>, format: Long?, allowed: List<Long>): List<RecordingMode> {
        format ?: return emptyList()
        val pal = allowed.any(::isPal)
        val modes = allowed.map { RecordingMode(format, it) }.toMutableList()
        for (other in formats) {
            if (other == format || (other != FOUR_K && other != HD)) continue
            modes += specs.keys.filter { formatOf(it) == other && isPal(it) == pal }.map { RecordingMode(other, it) }
        }
        return modes.sortedWith(order)
    }

    /** Частота кадров ролика по коду `0xD242`. */
    fun frameRate(setting: Long): Int? = specs[setting]?.first

    /** Система по коду `0xD242`: 25/50/100p — PAL. */
    fun isPalSetting(setting: Long): Boolean = isPal(setting)

    /**
     * Подпись режима; у камеры без свойства формата — подпись одних параметров, как раньше.
     * allowed — параметры, которые камера разрешает сейчас: после смены формата она ещё показывает параметры
     * прежнего (в HD — код 4K), и таких параметров не бывает — тогда подписываем один формат.
     */
    fun label(format: Long?, setting: Long?, allowed: List<Long> = emptyList()): String = when {
        setting == null -> "—"
        format == null -> ValueFormatter.label(SettingID.movieRecordingSetting, setting)
        allowed.isNotEmpty() && setting !in allowed -> RecordingMode(format, setting).label.substringBefore(' ')
        else -> RecordingMode(format, setting).label
    }

    /**
     * Что записать в камеру, чтобы прийти к режиму, глядя на то, что на ней сейчас. Параметры камера принимает только
     * для текущего формата и перечисляет только их; после смены формата она ещё показывает параметры прежнего.
     */
    fun nextStep(to: RecordingMode, format: Long?, setting: Long?, allowed: List<Long>): RecordingStep = when {
        format != null && format != to.format -> RecordingStep.WriteFormat(to.format)
        setting == to.setting && (allowed.isEmpty() || to.setting in allowed) -> RecordingStep.Done
        to.setting in allowed -> RecordingStep.WriteSetting(to.setting)
        else -> RecordingStep.Wait
    }

    /** Режим в другом формате, ближайший к текущему: та же частота с самым высоким битрейтом, иначе первый режим формата. */
    fun closest(current: RecordingMode?, format: Long, modes: List<RecordingMode>): RecordingMode? {
        if (current != null && current.format == format && current in modes) return current
        val candidates = modes.filter { it.format == format }
        val fps = current?.let { specs[it.setting]?.first }
        return candidates.firstOrNull { specs[it.setting]?.first == fps } ?: candidates.firstOrNull()
    }

    /** Соседний режим; base — последний запрошенный, если он есть, иначе текущий. */
    fun neighbour(base: RecordingMode?, modes: List<RecordingMode>, offset: Int): RecordingMode? {
        val index = base?.let { modes.indexOf(it) } ?: return null
        if (index < 0) return null
        val target = (index + offset).coerceIn(0, modes.size - 1)
        return if (target == index) null else modes[target]
    }

    /** 4K раньше HD; внутри формата — по убыванию частоты, затем битрейта. */
    private val order = compareBy<RecordingMode>({ if (it.format == FOUR_K) 0 else 1 },
        { -(specs[it.setting]?.first ?: 0) }, { -(specs[it.setting]?.second ?: 0) }, { it.setting })
}

/** Шаг к выбранному режиму записи. */
sealed interface RecordingStep {
    data object Done : RecordingStep
    data class WriteFormat(val format: Long) : RecordingStep
    data class WriteSetting(val setting: Long) : RecordingStep
    /** формат тот, а нужных параметров камера ещё не открыла — ждём */
    data object Wait : RecordingStep
}

/**
 * Ведёт камеру к последнему выбранному режиму записи. Камера отвечает «готово», но молча пропускает запись формата
 * или параметров, пришедшую, пока она применяет прошлую, — поэтому пишем по одной (следующую — когда камера
 * подтвердила прошлую) и каждый раз смотрим, что на камере: пропущенная запись повторяется, но не бесконечно.
 */
class RecordingModeDriver {
    var target: RecordingMode? = null
        private set
    private var lastStep: RecordingStep? = null
    private var repeats = 0

    /** Новый выбор: счёт повторов — заново. */
    fun choose(mode: RecordingMode) {
        target = mode
        lastStep = null
        repeats = 0
    }

    /** Следующий шаг по тому, что на камере сейчас; null — пришли или сдались (тогда `target` пуст). */
    fun next(format: Long?, setting: Long?, allowed: List<Long>): RecordingStep? {
        val target = target ?: return null
        val step = RecordingModes.nextStep(target, format, setting, allowed)
        repeats = if (step == lastStep) repeats + 1 else 1
        lastStep = step
        val limit = if (step == RecordingStep.Wait) MAX_WAITS else MAX_ATTEMPTS
        if (step == RecordingStep.Done || repeats > limit) {
            this.target = null
            return null
        }
        return step
    }

    /** Камера отключилась или режим больше не нужен. */
    fun cancel() {
        target = null
    }

    companion object {
        /** Сколько раз подряд повторить одну и ту же запись, прежде чем сдаться. */
        const val MAX_ATTEMPTS = 3
        /** Сколько раз подряд подождать, пока камера откроет нужные параметры (раз в 100 мс — 5 с). */
        const val MAX_WAITS = 50
    }
}
