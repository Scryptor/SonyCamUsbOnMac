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
    const val SHUTTER_HALF_PRESS = 0xD2C1
    const val SHUTTER_FULL_PRESS = 0xD2C2
    const val MOVIE_REC_BUTTON = 0xD2C8
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
    /** Кадр live view: первые 4 байта — смещение до JPEG, дальше сам JPEG. */
    fun jpeg(data: ByteArray): ByteArray? {
        if (data.size <= 8) return null
        val offset = (data[0].toInt() and 0xFF) or ((data[1].toInt() and 0xFF) shl 8) or
            ((data[2].toInt() and 0xFF) shl 16) or ((data[3].toInt() and 0xFF) shl 24)
        if (offset in 0 until data.size - 1 && data[offset] == 0xFF.toByte() && data[offset + 1] == 0xD8.toByte()) {
            return data.copyOfRange(offset, data.size)
        }
        // запасной путь: ищем начало JPEG (FF D8 FF)
        for (i in 0 until data.size - 2) {
            if (data[i] == 0xFF.toByte() && data[i + 1] == 0xD8.toByte() && data[i + 2] == 0xFF.toByte()) {
                return data.copyOfRange(i, data.size)
            }
        }
        return null
    }
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
