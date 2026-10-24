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

    fun label(format: Long?, setting: Long?): String =
        if (format == null || setting == null) "—" else RecordingMode(format, setting).label

    /** 4K раньше HD; внутри формата — по убыванию частоты, затем битрейта. */
    private val order = compareBy<RecordingMode>({ if (it.format == FOUR_K) 0 else 1 },
        { -(specs[it.setting]?.first ?: 0) }, { -(specs[it.setting]?.second ?: 0) }, { it.setting })
}
