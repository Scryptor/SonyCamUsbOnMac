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

/** Сохранённый набор настроек камеры. */
data class Preset(val name: String, val values: Map<SettingID, Long>)

data class PresetResult(val applied: List<SettingID>, val skipped: List<SettingID>)

object PresetCatalog {
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

    fun toJson(presets: List<Preset>): String = JSONArray().apply {
        presets.forEach { preset ->
            put(JSONObject().put("name", preset.name).put("values", JSONObject().apply {
                preset.values.forEach { (id, value) -> put(id.name, value) }
            }))
        }
    }.toString()

    fun fromJson(json: String): List<Preset> = runCatching {
        val array = JSONArray(json)
        List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val values = item.getJSONObject("values")
            Preset(item.getString("name"), values.keys().asSequence().mapNotNull { key ->
                runCatching { SettingID.valueOf(key) }.getOrNull()?.let { it to values.getLong(key) }
            }.toMap())
        }
    }.getOrDefault(emptyList())
}

/** Применяет пресет по одной настройке, в порядке каталога. Совпадающие значения не пишутся. */
suspend fun CameraSession.apply(preset: Preset): PresetResult {
    val applied = ArrayList<SettingID>()
    val skipped = ArrayList<SettingID>()
    var connected = true
    for (id in PresetCatalog.order) {
        val value = preset.values[id] ?: continue
        // температура и сдвиги хранятся в камере отдельно для каждого режима ББ, экспокоррекция
        // работает только с ISO AUTO: если базовая настройка не применилась, зависимые не трогаем
        val base = PresetCatalog.dependency[id]
        val baseFailed = base != null && preset.values[base] != null && base !in applied
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
