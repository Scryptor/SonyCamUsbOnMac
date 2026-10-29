package local.zve10viewer.sony

import local.zve10viewer.ptp.ByteReader
import local.zve10viewer.ptp.PtpType
import local.zve10viewer.ptp.PtpValue

/** Коды операций PTP, которые нужны для работы с Sony. */
object SonyOp {
    const val GET_DEVICE_INFO = 0x1001
    const val OPEN_SESSION = 0x1002
    const val CLOSE_SESSION = 0x1003
    const val GET_OBJECT_INFO = 0x1008
    const val GET_OBJECT = 0x1009
    const val CONNECT = 0x9201
    const val GET_EXT_DEVICE_INFO = 0x9202
    const val SET_EXT_PROP_VALUE = 0x9205
    const val CONTROL_DEVICE = 0x9207
    const val GET_ALL_EXT_PROP_INFO = 0x9209

    // у ZV-E10 в списке операций их нет: зовём, только если камера назвала их в GetDeviceInfo (DeviceInfo.supports)
    /** Подписи значений от самой камеры (Creative Look и др.); параметр — тип списка, 0 — все списки. */
    const val GET_DISPLAY_STRING_LIST = 0x9215
    /** Версия кода производителя (в параметре ответа, ×100: 0x0136 — 3.10). */
    const val GET_VENDOR_CODE_VERSION = 0x9216
    /** Данные объектива: пары «положение фокуса — расстояние»; параметр 1 — футы, 2 — метры. */
    const val GET_LENS_INFORMATION = 0x9223
}

/** Коды свойств камеры (стандартные PTP 0x50xx и расширения Sony 0xD2xx). */
object SonyProp {
    const val WHITE_BALANCE = 0x5005
    const val F_NUMBER = 0x5007
    const val FOCUS_MODE = 0x500A
    const val METERING = 0x500B
    const val EXPOSURE_PROGRAM = 0x500E
    const val EXPOSURE_COMP = 0x5010
    const val DRO = 0xD201
    const val IMAGE_SIZE = 0xD203
    const val SHUTTER = 0xD20D
    const val COLOR_TEMP = 0xD20F
    const val GM_SHIFT = 0xD210
    const val ASPECT_RATIO = 0xD211
    const val FOCUS_FOUND = 0xD213
    const val OBJECT_IN_MEMORY = 0xD215
    const val BATTERY_LEVEL = 0xD218
    const val AB_SHIFT = 0xD21C
    const val MOVIE_RECORDING_STATE = 0xD21D
    const val ISO = 0xD21E
    /** Куда сохранять снимок: 0x01 — только компьютер, 0x10 — только карта, 0x11 — оба. */
    const val STILL_IMAGE_STORE_DESTINATION = 0xD222
    /** Дата и время камеры (строка). */
    const val DATE_TIME_SET = 0xD223
    const val FOCUS_AREA = 0xD22C
    const val PICTURE_PROFILE = 0xD23F
    const val CREATIVE_STYLE = 0xD240
    const val MOVIE_FILE_FORMAT = 0xD241
    const val MOVIE_RECORDING_SETTING = 0xD242
    const val CARD_REMAINING_SHOTS = 0xD249
    const val CARD_REMAINING_TIME = 0xD24A
    const val OVERHEAT = 0xD251
    const val JPEG_QUALITY = 0xD252
    const val FILE_FORMAT = 0xD253
    const val PRIORITY_MODE = 0xD25A
    /** Качество превью: 1 — низкое (640×360, ~34 КБ на кадр), 2 — высокое (1024×576, ~72 КБ). По Wi-Fi работает. */
    const val LIVE_VIEW_IMAGE_QUALITY = 0xD26A
    const val SHUTTER_HALF_PRESS = 0xD2C1
    const val SHUTTER_FULL_PRESS = 0xD2C2
    const val MOVIE_REC_BUTTON = 0xD2C8

    // фокус и зум с экрана (Sony Camera Control PTP 3 Reference)
    /** Near/Far — доступна ли перефокусировка ближе/дальше (0x01 — да; у ZV-E10 — в MF). */
    const val NEAR_FAR_ENABLED = 0xD235
    /** Положение фокуса 0…100 (UINT8), только чтение. */
    const val FOCAL_POSITION = 0xD24C
    /** Можно ли управлять зумом (0x01 — да): объектив с приводом или Clear Image Zoom. */
    const val ZOOM_ENABLED = 0xD25B
    /** Масштаб зума ×1000 (UINT32): 1000 — ×1,0. */
    const val ZOOM_SCALE = 0xD25C
    /** Шкала зума (UINT32): биты 31–24 — сколько отрезков, 23–16 — в каком, 15–0 — где в нём (0…100). */
    const val ZOOM_BAR = 0xD25D
    /** Что делает касание экрана камеры: 0x03 — фокус, 0x04 — слежение. */
    const val TOUCH_FUNCTION = 0xD283
    /** Можно ли «коснуться» экрана камеры командой (0x01 — да) и снять это касание. */
    const val REMOTE_TOUCH_ENABLED = 0xD284
    const val REMOTE_TOUCH_CANCEL_ENABLED = 0xD285

    // новые камеры Sony (справочник Camera Control PTP 3 Reference); у ZV-E10 этих свойств нет
    /** Гамма профиля изображения (UINT16): 0x0303 — S-Log3. По ней и по цвету профиля LUT «Авто» выбирает кривую. */
    const val PP_GAMMA = 0xD0E1
    /** Цвет профиля изображения (UINT16): 0x0008 — S-Gamut3.Cine. */
    const val PP_COLOR_MODE = 0xD0E9
    /** Creative Look (UINT16) — вместо Creative Style 0xD240. Числа значений совпадают со стилем, смысл другой. */
    const val CREATIVE_LOOK = 0xD0FA
    /** Абсолютный фокус: цель 0…65535 (UINT16), запись. */
    const val FOCUS_POSITION_SETTING = 0xE042
    /** Абсолютный фокус: положение 0…65535 (UINT16), только чтение. */
    const val FOCUS_POSITION_CURRENT = 0xE043
    /** Данные объектива готовы (UINT8, 1 — да): тогда можно звать 0x9223. */
    const val LENS_INFO_ENABLED = 0xE086
}

/** События PTP от новых камер Sony. О смене свойства сообщает Sony.PROPERTY_CHANGED_EVENT (0xC203). */
object SonyEvent {
    /** Список подписей изменился; в параметре — тип списка: заново 0x9215 с этим типом. */
    const val DISPLAY_LIST_CHANGED = 0xC20F
    /** Запись в 0xE042 закончилась; параметр 1 — фокус дошёл до цели. */
    const val FOCUS_POSITION_RESULT = 0xC218
    /** Данные объектива изменились: заново 0x9223. */
    const val LENS_INFO_CHANGED = 0xC21B
}

/** Команды управления 0x9207 (Control Device) — кроме кнопок спуска и записи; тип значения — свой у каждой. */
object SonyControl {
    /** Ближе (−) / дальше (+) на 1…7 шагов, INT16; только при Near/Far Enable 0xD235 = 1. */
    const val NEAR_FAR = 0xD2D1
    /** Точка AF: (x shl 16) or y в сетке 640×480, UINT32; двигает рамку в зонах «Точка» и «Зона». */
    const val AF_AREA_POSITION = 0xD2DC
    /** Зум, INT8: 1 — к теле, −1 — к широкому, 0 — стоп; едет, пока не придёт стоп. */
    const val ZOOM_OPERATION = 0xD2DD
    /** Касание экрана камеры в точке (x shl 16) or y сетки 640×480, UINT32: фокус или слежение — как настроено в камере. */
    const val REMOTE_TOUCH = 0xD2E4
    /** Снять касание (слежение), кнопка UINT16: нажать и отпустить. */
    const val REMOTE_TOUCH_CANCEL = 0xD2E5
}

/** Ответ Sony GetExtDeviceInfo (0x9202): версия протокола, коды свойств и коды команд управления. */
class SonyExtInfo(val version: Int, val propertyCodes: List<Int>, val controlCodes: List<Int>) {
    companion object {
        fun parse(data: ByteArray): SonyExtInfo? = runCatching {
            fun u16(at: Int) = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            fun list(at: Int): Pair<List<Int>, Int> {
                val count = u32(at)
                return List(count) { u16(at + 4 + it * 2) } to at + 4 + count * 2
            }
            val (properties, next) = list(2)
            val controls = if (next < data.size) list(next).first else emptyList()
            SonyExtInfo(u16(0), properties, controls)
        }.getOrNull()
    }
}

object Sony {
    const val VENDOR_ID = 0x054C
    const val PROTOCOL_VERSION = 0x12CL
    const val LIVE_VIEW_HANDLE = 0xFFFF_C002L
    /** Снимок в памяти камеры (когда 0xD215 > 0x8000). */
    const val CAPTURED_OBJECT_HANDLE = 0xFFFF_C001L
    const val PROPERTY_CHANGED_EVENT = 0xC203
}

/** Стандартный набор PTP DeviceInfo (ответ на GetDeviceInfo). */
data class DeviceInfo(
    val vendorExtensionId: Long,
    val operations: List<Int>,
    val events: List<Int>,
    val manufacturer: String,
    val model: String,
    val firmware: String,
    val serial: String,
) {
    /** Команды Sony есть только в режиме PC Remote. */
    val isPcRemote: Boolean get() = operations.contains(SonyOp.CONNECT)

    /**
     * Назвала ли камера операцию в GetDeviceInfo. Новые операции Sony (0x9215, 0x9216, 0x9223) зовём только если да:
     * у ZV-E10 их нет.
     */
    fun supports(op: Int): Boolean = operations.contains(op)

    companion object {
        fun parse(data: ByteArray): DeviceInfo {
            val r = ByteReader(data)
            r.u16()                     // версия стандарта
            val vendor = r.u32()
            r.u16()                     // версия расширения
            r.ptpString()               // описание расширения
            r.u16()                     // functional mode
            val operations = r.u16Array()
            val events = r.u16Array()
            r.u16Array()                // стандартные свойства
            r.u16Array()                // форматы съёмки
            r.u16Array()                // форматы изображений
            return DeviceInfo(vendor, operations, events, r.ptpString(), r.ptpString(), r.ptpString(), r.ptpString())
        }
    }
}

data class PropertyRange(val min: Long, val max: Long, val step: Long)

/** Описание одного свойства камеры из GetAllExtDevicePropInfo. */
data class SonyProperty(
    val code: Int,
    val dataType: Int,
    val getSet: Int,
    /** 0 — недоступно, 1 — доступно, 2 — только показ. */
    val enabled: Int,
    val current: PtpValue,
    val range: PropertyRange?,
    /** Значения, которые камера разрешает установить сейчас. */
    val settableValues: List<Long>,
    /** Полный список значений (второй список камеры, если он есть). */
    val allValues: List<Long>,
) {
    val isWritable: Boolean get() = getSet == 1 && enabled == 1
}

object SonyPropertyParser {
    /** Разбирает ответ 0x9209: u32 количество, u32 резерв, затем описатели свойств. */
    fun parse(data: ByteArray): Map<Int, SonyProperty> {
        val r = ByteReader(data)
        r.u32()
        r.u32()
        val result = HashMap<Int, SonyProperty>()
        while (r.remaining > 0) {
            val property = descriptor(r)
            result[property.code] = property
        }
        return result
    }

    private fun descriptor(r: ByteReader): SonyProperty {
        val code = r.u16()
        val type = r.u16()
        val getSet = r.u8()
        val enabled = r.u8()
        val defaultValue = PtpValue.read(r, type)
        // строковое свойство в самом конце буфера может прийти без текущего значения
        val current = if (type == PtpType.STRING && r.remaining == 0) defaultValue else PtpValue.read(r, type)

        var range: PropertyRange? = null
        var first: List<Long> = emptyList()
        var second: List<Long>? = null
        if (r.remaining > 0) {
            when (r.u8()) {
                1 -> {
                    val lo = PtpValue.read(r, type).intValue ?: 0
                    val hi = PtpValue.read(r, type).intValue ?: 0
                    val step = PtpValue.read(r, type).intValue ?: 0
                    range = PropertyRange(lo, hi, step)
                }
                2 -> {
                    first = values(r, type)
                    // у части свойств следом идёт второй список; иначе дальше уже код следующего свойства (0x5xxx/0xDxxx)
                    val next = r.peekU16()
                    if (next != null && next < 0x200) second = values(r, type)
                }
            }
        }
        return SonyProperty(code, type, getSet, enabled, current, range, first, second ?: first)
    }

    private fun values(r: ByteReader, type: Int): List<Long> {
        val count = r.u16()
        val list = ArrayList<Long>(count)
        repeat(count) { PtpValue.read(r, type).intValue?.let(list::add) }
        return list
    }
}

object LiveViewFrame {
    /** Где в ответе камеры лежит JPEG. */
    data class Span(val offset: Int, val length: Int)

    /**
     * Кадр live view: u32 смещение до JPEG, u32 его размер, дальше служебное (рамки фокуса), сам JPEG и снова
     * служебное. Возвращает место JPEG в ответе — декодировать можно прямо оттуда, без копии.
     */
    fun locate(data: ByteArray): Span? {
        if (data.size <= 8) return null
        val offset = le32(data, 0)
        val size = le32(data, 4)
        if (offset in 0 until data.size - 1 && data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte()) {
            val length = if (size > 0 && size <= data.size - offset) size else data.size - offset
            return Span(offset, length)
        }
        // запасной путь: ищем начало JPEG (FF D8 FF)
        for (i in 0 until data.size - 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                return Span(i, data.size - i)
            }
        }
        return null
    }

    /** Сам JPEG отдельным массивом (копия). */
    fun jpeg(data: ByteArray): ByteArray? = locate(data)?.let { data.copyOfRange(it.offset, it.offset + it.length) }

    /** Отрицательное (больше 2 ГБ) — не размер и не смещение: такой кадр не разобрать. */
    private fun le32(data: ByteArray, at: Int): Int = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8) or
        ((data[at + 2].toInt() and 0xFF) shl 16) or ((data[at + 3].toInt() and 0xFF) shl 24)
}

/** Стандартный набор PTP ObjectInfo (ответ на GetObjectInfo) — нужны имя файла, размер и формат. */
data class ObjectInfo(val format: Int, val compressedSize: Long, val filename: String) {
    companion object {
        fun parse(data: ByteArray): ObjectInfo {
            val r = ByteReader(data)
            r.u32()             // StorageID
            val format = r.u16()
            r.u16()             // ProtectionStatus
            val size = r.u32()
            r.u16()             // ThumbFormat
            r.skip(4 * 7)       // ThumbCompressedSize … ParentObject
            r.u16()             // AssociationType
            r.skip(4 * 2)       // AssociationDesc, SequenceNumber
            return ObjectInfo(format, size, r.ptpString())
        }
    }
}
