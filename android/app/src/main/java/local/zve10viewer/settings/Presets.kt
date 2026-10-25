package local.zve10viewer.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import local.zve10viewer.ptp.TransportException
import local.zve10viewer.session.CameraSession
import local.zve10viewer.session.SetResult
import local.zve10viewer.sony.SonyProperty
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Сохранённый набор настроек камеры. skip — настройки, которые пресет не меняет: значение он помнит, но не применяет
 * (включили обратно — оно на месте).
 */
data class Preset(val name: String, val values: Map<SettingID, Long>, val skip: Set<SettingID> = emptySet())

data class PresetResult(val applied: List<SettingID>, val skipped: List<SettingID>)

object PresetCatalog {
    /** Температура, которую берёт пресет, если камера свою ручную не сообщила. */
    const val DEFAULT_KELVIN = 5600L

    /**
     * Что входит в пресет и в каком порядке применяется: температура — после баланса белого
     * (доступна только в режиме K), экспокоррекция — после ISO (доступна только с ISO AUTO).
     */
    val order = listOf(
        SettingID.whiteBalance, SettingID.colorTemperature, SettingID.wbShiftAB, SettingID.wbShiftGM,
        SettingID.iso, SettingID.exposureComp, SettingID.shutter, SettingID.aperture,
        SettingID.metering, SettingID.dro, SettingID.focusMode, SettingID.focusArea,
        SettingID.creativeStyle, SettingID.pictureProfile,
    )

    /** Настройка → от какой она зависит. */
    val dependency = mapOf(
        SettingID.colorTemperature to SettingID.whiteBalance,
        SettingID.wbShiftAB to SettingID.whiteBalance,
        SettingID.wbShiftGM to SettingID.whiteBalance,
        SettingID.exposureComp to SettingID.iso,
    )

    /** Снимок текущих значений; берутся только настройки, которые камера сейчас даёт менять. */
    fun capture(name: String, properties: Map<Int, SonyProperty>): Preset {
        val values = LinkedHashMap<SettingID, Long>()
        for (id in order) {
            val property = properties[id.code] ?: continue
            val value = property.current.intValue ?: continue
            if (property.isWritable) values[id] = value
        }
        return Preset(name, values)
    }

    /** Группы окна пресетов — как во «Всех настройках»; вместе — ровно настройки каталога. */
    val groups: List<Pair<String, List<SettingID>>> = listOf(
        "Экспозиция" to listOf(SettingID.shutter, SettingID.aperture, SettingID.iso, SettingID.exposureComp,
            SettingID.metering, SettingID.dro),
        "Цвет и картинка" to listOf(SettingID.whiteBalance, SettingID.colorTemperature, SettingID.wbShiftAB,
            SettingID.wbShiftGM, SettingID.creativeStyle, SettingID.pictureProfile),
        "Фокус" to listOf(SettingID.focusMode, SettingID.focusArea),
    )

    /** Меняет ли пресет настройку: значение есть и не выключено. */
    fun includes(preset: Preset, id: SettingID): Boolean = id in preset.values && id !in preset.skip

    /**
     * Что применится, в порядке каталога: значения без выключенных и без цветовой температуры, если баланс белого
     * в пресете есть, применяется и он не «Цвет. темп.» — температура действует только в этом режиме.
     */
    fun effective(preset: Preset): Map<SettingID, Long> {
        val whiteBalance = preset.values[SettingID.whiteBalance]?.takeIf { includes(preset, SettingID.whiteBalance) }
        val result = LinkedHashMap<SettingID, Long>()
        for (id in order) {
            val value = preset.values[id] ?: continue
            if (id in preset.skip) continue
            if (id == SettingID.colorTemperature && whiteBalance != null && whiteBalance != WhiteBalanceTemperature.MANUAL_MODE) continue
            result[id] = value
        }
        return result
    }

    /** Стоит ли пресет на камере: всё, что он применяет, равно текущему (свойства нет — не стоит). Пустой — не стоит. */
    fun matches(preset: Preset, properties: Map<Int, SonyProperty>): Boolean {
        val values = effective(preset)
        return values.isNotEmpty() && values.all { (id, value) -> properties[id.code]?.current?.intValue == value }
    }

    /** «Взять с камеры»: всё, что камера сейчас даёт менять, — текущими значениями; остальное, имя и skip — как были. */
    fun recapture(preset: Preset, properties: Map<Int, SonyProperty>): Preset =
        preset.copy(values = preset.values + capture(preset.name, properties).values)

    /**
     * Кратко, что меняет пресет (по тому, что применится): выдержка, диафрагма, ISO, баланс белого (в «Цвет. темп.» —
     * кельвины; баланс не меняет, а температуру меняет — одни кельвины), экспокоррекция, если не ±0.0, режим фокуса.
     * Ничего из этого — названия того, что меняет.
     */
    fun summary(preset: Preset): String {
        val values = effective(preset)
        if (values.isEmpty()) return "ничего не меняет"
        val parts = ArrayList<String>()
        values[SettingID.shutter]?.let { parts += ValueFormatter.label(SettingID.shutter, it) }
        values[SettingID.aperture]?.let { parts += ValueFormatter.label(SettingID.aperture, it) }
        values[SettingID.iso]?.let { parts += "ISO " + ValueFormatter.label(SettingID.iso, it) }
        val whiteBalance = values[SettingID.whiteBalance]
        val kelvin = values[SettingID.colorTemperature]
        if (whiteBalance != null) parts += ValueFormatter.whiteBalanceLabel(whiteBalance, kelvin)
        else if (kelvin != null) parts += ValueFormatter.label(SettingID.colorTemperature, kelvin)
        values[SettingID.exposureComp]?.takeIf { it != 0L }?.let { parts += ValueFormatter.label(SettingID.exposureComp, it) }
        values[SettingID.focusMode]?.let { parts += ValueFormatter.label(SettingID.focusMode, it) }
        if (parts.isEmpty()) values.keys.forEach { parts += it.shortTitle }
        return parts.joinToString(" · ")
    }

    /**
     * Из чего выбирать значение в окне пресетов: что камера даёт выбрать сейчас, с подписями меню Fn. Температура,
     * которую камера сейчас выбрать не даёт (не тот режим баланса белого), — из запасного диапазона. Свойства нет
     * (камера не на связи) — выбирать не из чего. Значения пресета нет среди вариантов — оно первым, со своей подписью.
     */
    fun options(id: SettingID, property: SonyProperty?, preset: Preset): List<SettingOption> {
        property ?: return emptyList()
        var list = SettingOptions.options(id, property)
        if (list.isEmpty() && id == SettingID.colorTemperature) {
            val range = WhiteBalanceTemperature.fallbackRange
            list = (range.min..range.max step range.step).map { SettingOption(it, ValueFormatter.label(id, it)) }
        }
        val value = preset.values[id]
        if (list.isEmpty() || value == null || list.any { it.value == value }) return list
        return listOf(SettingOption(value, ValueFormatter.label(id, value))) + list
    }

    /**
     * Включить или выключить настройку в пресете. Выключенная помнит значение; включили, а значения ещё не было, —
     * берётся текущее с камеры. Взять неоткуда — null: включить нельзя.
     */
    fun including(preset: Preset, id: SettingID, included: Boolean, properties: Map<Int, SonyProperty>): Preset? {
        if (id !in order) return null
        if (!included) return if (id in preset.values) preset.copy(skip = preset.skip + id) else preset
        val value = preset.values[id] ?: initialValue(id, properties) ?: return null
        return preset.copy(values = preset.values + (id to value), skip = preset.skip - id)
    }

    /** Значение настройки, которой в пресете ещё не было: текущее с камеры; у температуры — сохранённая камерой ручная, иначе 5600 K. */
    fun initialValue(id: SettingID, properties: Map<Int, SonyProperty>): Long? {
        val current = properties[id.code]?.current?.intValue
        return if (id == SettingID.colorTemperature) current ?: DEFAULT_KELVIN else current
    }

    /** Список, где пресет с места from стоит на месте to; null — такого места нет. */
    fun moved(presets: List<Preset>, from: Int, to: Int): List<Preset>? {
        if (from !in presets.indices || to !in presets.indices) return null
        return presets.toMutableList().also { it.add(to, it.removeAt(from)) }
    }

    /** Имя нового пресета: «Пресет N» по месту, которое он займёт; занято — следующее свободное. */
    fun newName(presets: List<Preset>): String {
        var n = presets.size + 1
        while (presets.any { it.name == "Пресет $n" }) n++
        return "Пресет $n"
    }

    /** Имя копии: «Вечер — копия». */
    fun copyName(name: String): String = "$name — копия"

    fun toJson(presets: List<Preset>): String = JSONArray().apply {
        presets.forEach { preset ->
            val item = JSONObject().put("name", preset.name).put("values", JSONObject().apply {
                preset.values.forEach { (id, value) -> put(id.name, value) }
            })
            // выключенных нет — поля нет: запись та же, что у прежних версий; есть — по алфавиту, как на Mac
            if (preset.skip.isNotEmpty()) item.put("skip", JSONArray(preset.skip.map { it.name }.sorted()))
            put(item)
        }
    }.toString()

    /** Чего эта версия не знает (пресет из более новой), пропускает: остальное читается. Нет «skip» — выключенных нет. */
    fun fromJson(json: String): List<Preset> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val values = item.getJSONObject("values")
            val skip = item.optJSONArray("skip")?.let { list -> List(list.length()) { known(list.optString(it)) }.filterNotNull().toSet() }
            Preset(item.getString("name"), values.keys().asSequence().mapNotNull { key ->
                known(key)?.let { it to values.getLong(key) }
            }.toMap(), skip ?: emptySet())
        }
    }.getOrDefault(emptyList())

    private fun known(name: String): SettingID? = runCatching { SettingID.valueOf(name) }.getOrNull()
}

/** Применяет пресет по одной настройке, в порядке каталога: только то, что применится (effective). Совпадающие значения не пишутся. */
suspend fun CameraSession.apply(preset: Preset): PresetResult {
    val applied = ArrayList<SettingID>()
    val skipped = ArrayList<SettingID>()
    val values = PresetCatalog.effective(preset)
    var connected = true
    for (id in PresetCatalog.order) {
        val value = values[id] ?: continue
        // температура и сдвиги хранятся в камере отдельно для каждого режима ББ, экспокоррекция
        // работает только с ISO AUTO: если базовая настройка не применилась, зависимые не трогаем
        val base = PresetCatalog.dependency[id]
        val baseFailed = base != null && values[base] != null && base !in applied
        if (!connected || baseFailed) {
            skipped += id
            continue
        }
        if (properties.value[id.code]?.current?.intValue == value) {
            applied += id
            continue
        }
        try {
            if (set(id.code, value) == SetResult.Confirmed) applied += id else skipped += id
        } catch (e: TransportException) {
            if (e.disconnected) connected = false
            skipped += id
        } catch (e: Exception) {
            skipped += id
        }
    }
    return PresetResult(applied, skipped)
}

/**
 * Не даёт засыпать камеру записями: по каждому свойству одна запись за раз,
 * а всё, что пришло за это время, схлопывается в последнее значение. Вызывать с одного потока.
 */
class WriteCoalescer(private val scope: CoroutineScope, private val write: suspend (Int, Long) -> Unit) {
    private val inFlight = HashSet<Int>()
    private val latest = HashMap<Int, Long>()

    fun submit(code: Int, value: Long) {
        if (code in inFlight) {
            latest[code] = value
            return
        }
        inFlight += code
        scope.launch {
            var next: Long? = value
            while (next != null) {
                write(code, next)
                next = latest.remove(code)
            }
            inFlight -= code
        }
    }
}

object FileNaming {
    /** Свободное имя: DSC01234.JPG → DSC01234 (2).JPG → DSC01234 (3).JPG … */
    fun unique(name: String, isTaken: (String) -> Boolean): String {
        if (!isTaken(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!isTaken(candidate)) return candidate
            n++
        }
    }

    /** Имя стоп-кадра из видео: «Кадр 2026-10-24 20-31-05.jpg». */
    fun frameGrab(calendar: Calendar = Calendar.getInstance()): String = "Кадр %04d-%02d-%02d %02d-%02d-%02d.jpg".format(
        calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH),
        calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), calendar.get(Calendar.SECOND),
    )
}
