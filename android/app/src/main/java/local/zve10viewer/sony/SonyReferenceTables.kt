package local.zve10viewer.sony

import org.json.JSONObject

/**
 * Справочник Sony (Camera Control PTP 3 Reference): названия всех его свойств и списки значений — для камер, у которых
 * своей подписи нет. JSON — Java-ресурс /sony_reference.json (его создаёт scripts/sony_reference.py), разбирается один раз,
 * при первом обращении. Ключи в нём — коды как в справочнике («0xD0FA», «0x0B»); значение читается так же, как из камеры:
 * UINT64 на всю ширину (0xFFFFFFFFFFFFFFFF) — Long −1.
 */
object SonyReferenceTables {
    /** Название свойства по его коду — все свойства справочника. */
    val propertyNames: Map<Int, String> get() = tables.names

    /** Списки значений: свойство → значение → подпись Sony. Только у свойств, значения которых справочник перечисляет. */
    val values: Map<Int, Map<Long, String>> get() = tables.values

    /** Подпись значения свойства; null, если справочник такого значения не знает. */
    fun valueName(code: Int, value: Long): String? = tables.values[code]?.get(value)

    /**
     * Разобрать справочник сейчас; отдаёт, сколько свойств он называет. Первое обращение разбирает 137 КБ JSON (на JVM ~26 мс, на
     * планшете больше), и если оно придёт с экрана — кадр дрогнет. Программа зовёт это в фоне при запуске; пока идёт разбор,
     * другое обращение ждёт его конца, а не начинает второй (`lazy` синхронизирован).
     */
    fun warmUp(): Int = tables.names.size

    private class Tables(val names: Map<Int, String>, val values: Map<Int, Map<Long, String>>)

    private val tables: Tables by lazy { parse(resource()) }

    private fun resource(): String =
        SonyReferenceTables::class.java.getResourceAsStream("/sony_reference.json")
            ?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""

    private fun parse(json: String): Tables {
        if (json.isEmpty()) return Tables(emptyMap(), emptyMap())
        val properties = JSONObject(json).getJSONObject("properties")
        val names = HashMap<Int, String>()
        val values = HashMap<Int, Map<Long, String>>()
        for (key in properties.keys()) {
            val code = hex(key)?.takeIf { it in 0L..0xFFFFL }?.toInt() ?: continue
            val property = properties.getJSONObject(key)
            names[code] = property.getString("name")
            val list = property.optJSONObject("values") ?: continue
            val byValue = HashMap<Long, String>()
            for (valueKey in list.keys()) {
                val value = hex(valueKey) ?: continue
                byValue[value] = list.getString(valueKey)
            }
            if (byValue.isNotEmpty()) values[code] = byValue
        }
        return Tables(names, values)
    }

    /** «0x0B» → 11; null для всего, что не шестнадцатеричное число до 64 бит. Старший бит — в знак, как у Int64 в камере. */
    private fun hex(key: String): Long? =
        if (key.startsWith("0x")) key.substring(2).toULongOrNull(16)?.toLong() else null
}
