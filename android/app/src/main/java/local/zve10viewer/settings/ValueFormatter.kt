package local.zve10viewer.settings

import local.zve10viewer.sony.SonyProperty
import local.zve10viewer.sony.SonyReferenceTables
import java.util.Locale
import kotlin.math.abs

/** Подписи значений настроек в том виде, как их показывает программа. */
object ValueFormatter {
    private const val MINUS = "−"

    /** Подпись текущего значения свойства; [names] — названия значений от самой камеры (`DisplayStrings.names`), они важнее наших таблиц. */
    fun label(id: SettingID, property: SonyProperty?, names: Map<Long, String>? = null): String {
        val value = property?.current?.intValue ?: return "—"
        return label(id, value, names)
    }

    /** Баланс белого в строке значений: в режиме «Цвет. темп.» — сама температура, как на экране камеры. */
    fun whiteBalanceLabel(whiteBalance: Long?, colorTemperature: Long?): String {
        whiteBalance ?: return "—"
        if (whiteBalance == WhiteBalanceTemperature.MANUAL_MODE && colorTemperature != null) return label(SettingID.colorTemperature, colorTemperature)
        return label(SettingID.whiteBalance, whiteBalance)
    }

    /**
     * Подпись значения. Порядок: название от самой камеры ([names], список 0x9215) → наши русские подписи и особые ветки →
     * английское название из справочника Sony → «#N».
     */
    fun label(id: SettingID, value: Long, names: Map<Long, String>? = null): String {
        names?.get(value)?.let { return it }
        return russianOrReference(id, value)
    }

    private fun russianOrReference(id: SettingID, value: Long): String = when (id) {
        SettingID.shutter -> shutter(value)
        SettingID.aperture -> aperture(value)
        SettingID.iso -> {
            val iso = value and 0xFF_FFFF
            if (iso == 0xFF_FFFFL) "AUTO" else "$iso"
        }
        SettingID.exposureComp ->
            if (value == 0L) "±0.0" else sign(value) + format("%.1f", abs(value) / 1000.0)
        SettingID.colorTemperature -> "$value K"
        SettingID.wbShiftAB, SettingID.wbShiftGM -> {
            // проверено по цвету кадра: больше 192 — A (теплее) и G (зеленее), меньше — B и M
            val shift = (value - 192) / 4.0
            if (shift == 0.0) "0" else {
                val letter = if (id == SettingID.wbShiftAB) (if (shift > 0) "A" else "B") else (if (shift > 0) "G" else "M")
                letter + trimmed(abs(shift))
            }
        }
        SettingID.battery -> if (value < 0) "—" else "$value%"
        SettingID.cardRemaining -> {
            val hours = value / 3600
            val minutes = (value % 3600) / 60
            if (hours > 0) "$hours ч $minutes мин" else "$minutes мин"
        }
        SettingID.focusArea -> focusArea(value) ?: fallback(id, value)
        SettingID.dro -> dro(value) ?: fallback(id, value)
        SettingID.pictureProfile -> when (value) {
            0L -> "Выкл"
            in 1L..10L -> "PP$value"
            else -> fallback(id, value)
        }
        else -> tables[id]?.get(value) ?: fallback(id, value)
    }

    /** Значения, которых нет в наших таблицах: английское название Sony из справочника, а если и его нет — «#N». */
    private fun fallback(id: SettingID, value: Long): String = SonyReferenceTables.valueName(id.code, value) ?: "#$value"

    private fun sign(value: Long) = if (value < 0) MINUS else "+"

    private fun format(pattern: String, value: Double) = String.format(Locale.US, pattern, value)

    /** Число без лишних нулей: 1, 0.5, 0.25. */
    private fun trimmed(x: Double): String = format("%.2f", x).trimEnd('0').trimEnd('.')

    private fun shutter(v: Long): String {
        if (v == 0L) return "BULB"
        val num = (v shr 16) and 0xFFFF
        val den = v and 0xFFFF
        if (den <= 0) return fallback(SettingID.shutter, v)
        if (num == 1L && den > 1) return "1/$den"
        var s = format("%.1f", num.toDouble() / den)
        if (s.endsWith(".0")) s = s.dropLast(2)
        return "$s\""
    }

    private fun aperture(v: Long): String = when {
        v == 0L || v == 0xFFFFL -> "f/--"
        v % 100 == 0L -> "f/${v / 100}"
        else -> "f/" + format("%.1f", v / 100.0)
    }

    private val spotNames = mapOf(
        1L to "Широкая", 2L to "Зона", 3L to "Центр",
        0x101L to "Точка S", 0x102L to "Точка M", 0x103L to "Точка L", 0x104L to "Расш. точка",
    )

    private fun focusArea(v: Long): String? {
        spotNames[v]?.let { return it }
        // 0x201…0x207 — те же зоны в режиме слежения
        if (v !in 0x201L..0x207L) return null
        val base = if (v <= 0x203) v - 0x200 else v - 0x204 + 0x101
        val name = spotNames[base] ?: return null
        return "Слежение: " + name.substring(0, 1).lowercase() + name.substring(1)
    }

    private fun dro(v: Long): String? = when (v) {
        1L -> "Выкл"
        2L -> "DRO"
        0x10L -> "DRO+"
        in 0x11L..0x15L -> "DRO ${v - 0x10}"
        0x1FL -> "DRO авто"
        0x20L -> "HDR авто"
        in 0x21L..0x26L -> "HDR ${v - 0x20} EV"
        else -> null
    }

    private val tables: Map<SettingID, Map<Long, String>> = mapOf(
        SettingID.whiteBalance to mapOf(
            2L to "AWB", 4L to "Дневной", 0x8011L to "Тень", 0x8010L to "Облачно", 6L to "Лампа",
            0x8001L to "Флуор. тёплый", 0x8002L to "Флуор. холодный", 0x8003L to "Флуор. дневной белый",
            0x8004L to "Флуор. дневной", 7L to "Вспышка", 0x8030L to "Под водой", 0x8012L to "Цвет. темп.",
            0x8020L to "Свой 1", 0x8021L to "Свой 2", 0x8022L to "Свой 3",
        ),
        SettingID.focusMode to mapOf(1L to "MF", 2L to "AF-S", 0x8004L to "AF-C", 0x8005L to "AF-A", 0x8006L to "DMF"),
        SettingID.metering to mapOf(
            0x8001L to "Мульти", 0x8002L to "Центр", 0x8003L to "Весь экран",
            0x8004L to "Точечный", 0x8005L to "Точечный крупный", 0x8006L to "По светлым",
        ),
        SettingID.creativeStyle to mapOf(1L to "Стандарт", 2L to "Яркий", 3L to "Портрет", 4L to "Пейзаж",
            5L to "Закат", 6L to "Ч/Б", 0x0DL to "Сепия"),
        SettingID.exposureProgram to mapOf(
            1L to "M", 0x10002L to "P", 0x20003L to "A", 0x30004L to "S",
            0x78050L to "Видео P", 0x78051L to "Видео A", 0x78052L to "Видео S", 0x78053L to "Видео M",
            0x78054L to "Видео: сцена",
            0x98059L to "S&Q P", 0x9805AL to "S&Q A", 0x9805BL to "S&Q S", 0x9805CL to "S&Q M",
            0x48000L to "Интеллект. авто", 0x48001L to "Супер авто", 0x68041L to "Панорама", 7L to "Портрет",
            0x58011L to "Спорт", 0x58012L to "Закат", 0x58013L to "Ночная сцена", 0x58014L to "Пейзаж",
            0x58015L to "Макро", 0x58016L to "Сумерки с рук", 0x58017L to "Ночной портрет", 0x58018L to "Без смаза",
        ),
        SettingID.movieFileFormat to mapOf(8L to "XAVC S 4K", 9L to "XAVC S HD"),
        // по таблице Sony Camera Remote SDK (Recording Setting Movie); 4K 0x21/0x24 и весь список HD (PAL) сверены с камерой
        SettingID.movieRecordingSetting to mapOf(
            0x01L to "60p 50M", 0x02L to "30p 50M", 0x03L to "24p 50M", 0x04L to "50p 50M", 0x05L to "25p 50M",
            0x18L to "60p 25M", 0x19L to "50p 25M", 0x1AL to "30p 16M", 0x1BL to "25p 16M",
            0x1CL to "120p 100M", 0x1DL to "100p 100M", 0x1EL to "120p 60M", 0x1FL to "100p 60M",
            0x20L to "30p 100M", 0x21L to "25p 100M", 0x22L to "24p 100M",
            0x23L to "30p 60M", 0x24L to "25p 60M", 0x25L to "24p 60M",
        ),
        SettingID.imageSize to mapOf(1L to "L", 2L to "M", 3L to "S"),
        SettingID.aspectRatio to mapOf(1L to "3:2", 2L to "16:9", 3L to "4:3", 4L to "1:1"),
        SettingID.fileFormat to mapOf(1L to "RAW", 2L to "RAW+JPEG", 3L to "JPEG"),
        SettingID.jpegQuality to mapOf(1L to "Экстра", 2L to "Высокое", 3L to "Стандарт", 4L to "Низкое"),
        SettingID.overheat to mapOf(0L to "Норма", 1L to "Нагрев", 2L to "Перегрев"),
    )
}
