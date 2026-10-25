package local.zve10viewer.debug

import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty
import java.util.Locale

/** Вкладки панели отладки — те же, что у программы для Mac. */
enum class DebugTab(val title: String) {
    link("Связь"),
    preview("Превью"),
    camera("Камера"),
    properties("Свойства"),
    log("Журнал"),
}

/** Строка панели отладки: подпись и значение. */
data class DebugRow(val label: String, val value: String)

/** Строки всех вкладок разом — и для экрана, и для «Скопировать». */
class DebugReport(private val tabs: Map<DebugTab, List<DebugRow>>) {
    fun rows(tab: DebugTab): List<DebugRow> = tabs[tab].orEmpty()

    /** Все вкладки текстом — чтобы переслать: заголовок, затем по разделу на вкладку. */
    fun text(header: String): String = buildString {
        append(header)
        for (tab in DebugTab.entries) {
            append("\n\n== ").append(tab.title).append(" ==")
            for (row in rows(tab)) append('\n').append(row.label).append(": ").append(row.value)
        }
    }
}

/** Подписи чисел для панели отладки: десятичная запятая, «−» вместо дефиса. Как у программы для Mac. */
object DebugFormat {
    /** Сколько длится: «0:07», «12:34», «1:02:03». */
    fun clock(ms: Long): String {
        val total = maxOf(0L, ms / 1000)
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** «7,4 с». */
    fun seconds(ms: Long): String = decimal(ms / 1000.0) + " с"

    /** «14,2 Мбит/с». */
    fun mbit(bytesPerSecond: Double): String = decimal(bytesPerSecond * 8 / 1_000_000) + " Мбит/с"

    fun decimal(x: Double, digits: Int = 1): String = String.format(Locale.US, "%.${digits}f", x).replace('.', ',')

    fun minus(value: Int): String = value.toString().replace('-', '−')

    /** Канал Wi-Fi по частоте: 2,4 ГГц — 1…14, 5 ГГц — 36…177, 6 ГГц — 1…233; null — частота не из этих полос. */
    fun wifiChannel(frequencyMhz: Int): Int? = when (frequencyMhz) {
        2484 -> 14
        in 2412..2472 -> (frequencyMhz - 2407) / 5
        in 5160..5885 -> (frequencyMhz - 5000) / 5
        in 5955..7115 -> (frequencyMhz - 5950) / 5
        else -> null
    }

    /**
     * Строка свойства для вкладки «Свойства»: «0xD21E · ISO» — значение (число и подпись программы, если она его знает),
     * можно ли менять, допустимые значения. Текст свойств, кроме даты и времени, не показываем: мало ли что в нём.
     */
    fun propertyRow(property: SonyProperty): DebugRow {
        val id = SettingID.entries.firstOrNull { it.code == property.code }
        val parts = ArrayList<String>()
        parts += rawValue(property)
        if (id != null) parts += ValueFormatter.label(id, property)
        parts += when {
            property.enabled == 0 -> "недоступно"
            property.isWritable -> "можно менять"
            property.getSet == 1 -> "менять нельзя сейчас"
            else -> "только чтение"
        }
        val range = property.range
        when {
            range != null -> parts += "от ${range.min} до ${range.max}, шаг ${range.step}"
            property.settableValues.isNotEmpty() && property.allValues.size != property.settableValues.size ->
                parts += "${property.settableValues.size} из ${property.allValues.size} значений"
            property.allValues.isNotEmpty() -> parts += values(property.allValues.size)
        }
        return DebugRow("0x%04X · %s".format(property.code, PropertyNames.title(property.code)), parts.joinToString(" · "))
    }

    /** Само значение: число (большое — и в шестнадцатеричном виде), текст, список. */
    fun rawValue(property: SonyProperty): String = when (val value = property.current) {
        is PtpValue.Num -> number(value.value)
        is PtpValue.Text -> if (property.code == SonyProp.DATE_TIME_SET) "«${value.value}»" else "текст, ${value.value.length} симв."
        is PtpValue.Items -> "[" + value.values.joinToString(", ") + "]"
        PtpValue.Unsupported -> if (property.dataType == PtpType.STRING) "текст" else "тип не разобран"
    }

    private fun number(v: Long): String = if (v > 9 || v < 0) "$v (0x%X)".format(v) else "$v"

    /** «1 значение», «3 значения», «12 значений». */
    fun values(count: Int): String {
        val word = when {
            count % 100 in 11..14 -> "значений"
            count % 10 == 1 -> "значение"
            count % 10 in 2..4 -> "значения"
            else -> "значений"
        }
        return "$count $word"
    }
}
