package local.zve10viewer.debug

import local.zve10viewer.session.VendorCodeVersion
import local.zve10viewer.sony.DeviceInfo
import local.zve10viewer.sony.DisplayStrings
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.sony.SonyOp
import java.util.Locale

/**
 * Что новая камера Sony сообщает сверх свойств — версия кода (0x9216), списки подписей (0x9215) и таблица объектива (0x9223),
 * как пришли, — для панели отладки: по этим строкам разбирают отчёты с других камер. И одна строка для logcat (тег ZVLens). У
 * ZV-E10 этих операций нет, и строк тоже. Без Android — для тестов на JVM; те же слова, что у программы для Mac (DebugReport).
 */
object OtherCamerasReport {
    /** Неразрывный пробел: пара «код название» и «положение → метры» при переносе строки не рвётся. */
    private const val NBSP = " "

    /**
     * Строки вкладки «Камера» для камеры [device]: каждая — только если камера называет свою операцию в GetDeviceInfo. [version] —
     * версия кода ×100 (null — не пришла), [strings] — списки подписей, [lens] — таблица объектива (null — её нет), [lensEnabled] —
     * значение 0xE086 (null — свойства нет): оно говорит, почему таблицы нет.
     */
    fun rows(device: DeviceInfo, strings: DisplayStrings, version: Int?, lens: LensInformation?, lensEnabled: Long?): List<DebugRow> {
        val rows = ArrayList<DebugRow>()
        if (device.supports(SonyOp.GET_VENDOR_CODE_VERSION)) {
            rows += DebugRow("Версия кода (0x9216)", version?.let { "${VendorCodeVersion.label(it)} ($it)" } ?: "нет")
        }
        if (device.supports(SonyOp.GET_DISPLAY_STRING_LIST)) {
            if (strings.lists.isEmpty()) rows += DebugRow("Подписи (0x9215)", "списков нет")
            for (type in strings.lists.keys.sorted()) {
                val names = strings.lists[type].orEmpty()
                val title = if (type == DisplayStrings.CREATIVE_LOOK_TYPE) "Creative Look, " else ""
                val pairs = names.keys.sorted().joinToString(" · ") { value -> String.format(Locale.ROOT, "0x%04X", value) + NBSP + names[value] }
                rows += DebugRow("Подписи 0x" + type.toString(16).uppercase(Locale.ROOT), "$title${names.size} шт.: $pairs")
            }
        }
        if (device.supports(SonyOp.GET_LENS_INFORMATION)) {
            if (lens != null) {
                for ((index, table) in lens.tables.withIndex()) {
                    val pairs = table.joinToString(" · ") { number(it.position) + NBSP + "→" + NBSP + number(it.meters) + NBSP + "м" }
                    rows += DebugRow(
                        if (lens.tableCount == 1) "Объектив (0x9223)" else "Объектив: таблица ${index + 1} из ${lens.tableCount}",
                        "${table.size} пар, шкала 0…${if (lens.absoluteScale) "65535" else "100"} (догадка по наибольшему положению)\n$pairs")
                }
                if (lens.tableCount > 1) {
                    rows += DebugRow("Расстояния на шкале", "нет: таблиц ${lens.tableCount}, какая верна, справочник не говорит")
                }
            } else {
                rows += DebugRow("Объектив (0x9223)", "данных нет, 0xE086 = " + (lensEnabled?.toString() ?: "нет свойства"))
            }
        }
        return rows
    }

    /**
     * Что камера дала и где метка, одной строкой для logcat (ZVLens): `камера: подписи {0x13=18 0x15=2}, версия 310, таблиц [12],
     * расстояние 1 м, абсолютный фокус да`. «—» — нет. [distance] — расстояние у метки положения на шкале (подпись), [absolute] —
     * перевод фокуса идёт абсолютной записью (0xE042). Те же поля, что в `/state` Mac: `camera.displayStringLists`, `lensPairs`,
     * `vendorCodeVersion`, `lens.distance`, `lens.absolute`.
     */
    fun line(strings: DisplayStrings, version: Int?, lens: LensInformation?, distance: String?, absolute: Boolean): String {
        val lists = if (strings.lists.isEmpty()) "—" else strings.lists.keys.sorted()
            .joinToString(" ", "{", "}") { "0x" + it.toString(16).uppercase(Locale.ROOT) + "=" + strings.lists[it]?.size }
        val tables = lens?.tables?.joinToString(" ", "[", "]") { it.size.toString() } ?: "—"
        return "камера: подписи $lists, версия ${version ?: "—"}, таблиц $tables, расстояние ${distance ?: "—"}, " +
            "абсолютный фокус ${if (absolute) "да" else "нет"}"
    }

    /** Число как пришло, без лишних нулей: 5958, 0,25, 1000000. */
    private fun number(value: Double): String {
        var text = String.format(Locale.ROOT, "%.2f", value)
        if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
        return text.replace('.', ',')
    }
}
