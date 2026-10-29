package local.zve10viewer.sony

import local.zve10viewer.ptp.ByteReader
import local.zve10viewer.ptp.PtpParseException
import local.zve10viewer.ptp.PtpValue
import local.zve10viewer.settings.SettingID

/**
 * Подписи значений от самой камеры — ответ Sony 0x9215 (GetDisplayStringList). Камера составляет такие списки сама:
 * Creative Look, BaseLook, кнопки, FTP и т. п. Результат — «тип списка → значение → строка». Программа просит их только у
 * камер, которые называют 0x9215 в GetDeviceInfo ([DeviceInfo.supports]): у ZV-E10 такой операции нет.
 */
data class DisplayStrings(
    /** Тип списка → значение → строка. Тип — UINT32, значение — целое со знаком своего типа данных (UINT64 — как Long). */
    val lists: Map<Long, Map<Long, String>> = emptyMap(),
) {
    /**
     * Подписи, пришедшие позже (по событию «список изменился»): список того же типа заменяется целиком, остальные остаются.
     * Набор — значение: возвращается новый, а оба исходных остаются прежними (на Mac `merge` меняет структуру на месте;
     * здесь набор лежит в состоянии контроллера, и изменяемый объект там не заметили бы).
     */
    fun merge(other: DisplayStrings): DisplayStrings = DisplayStrings(lists + other.lists)

    /**
     * Камера сообщила, что список типа [type] изменился, и прислала его заново ([fresh]). Тип 0 — «все списки»: набор заменяется
     * целиком. Иначе заменяется список этого типа; если камера его больше не отдаёт (в ответе его нет), он стирается — старые
     * названия не должны жить дальше нового списка. Списки других типов остаются. Набор — значение, как и у [merge]: возвращается
     * новый.
     */
    fun replace(type: Long, fresh: DisplayStrings): DisplayStrings =
        if (type == 0L) fresh else DisplayStrings((lists - type) + fresh.lists)

    /**
     * Названия значений настройки от самой камеры ([local.zve10viewer.settings.ValueFormatter.label] с `names`). Из списков
     * камеры программа показывает только Creative Look (тип 0x13); у остальных настроек списков камеры нет, их подписывают наши
     * таблицы и справочник Sony. null — названий от камеры нет.
     */
    fun names(id: SettingID): Map<Long, String>? = if (id == SettingID.creativeLook) lists[CREATIVE_LOOK_TYPE] else null

    companion object {
        /** Тип списка с названиями Creative Look. */
        const val CREATIVE_LOOK_TYPE = 0x13L

        /**
         * Разбирает ответ 0x9215 (little-endian). Набор: u32 смещение до данных и u32 размер данных; данные — со смещения:
         * u16 число списков и 2 байта резерва; у списка u32 тип, u16 тип данных PTP, u16 число строк; у строки значение
         * (размер по типу данных), u16 размер строки с нулём и сама строка, UTF-8 с нулём. Значение — целое со знаком типа
         * данных; список с другим типом (строка, массив) в ключи не годится. Список без строк остаётся пустым списком: он
         * значит «подписей этого типа больше нет», и [merge] должен уметь им стирать. Тот же тип списка (и то же значение)
         * дважды в наборе — побеждает позднее.
         * Обрезанный или противоречивый набор — [PtpParseException]: лучше без подписей, чем с наполовину прочитанными.
         * Чужие данные: ни размер, ни число строк не выделяют память заранее, цикл всегда упирается в конец данных.
         */
        fun parse(data: ByteArray): DisplayStrings {
            val body = SonyDataset.body(data)
            val r = ByteReader(body)
            val listCount = r.u16()
            r.skip(2)                                               // резерв
            val lists = LinkedHashMap<Long, Map<Long, String>>()
            repeat(listCount) {
                val type = r.u32()
                val dataType = r.u16()
                val stringCount = r.u16()
                val strings = LinkedHashMap<Long, String>()
                repeat(stringCount) {
                    val value = PtpValue.read(r, dataType).intValue ?: throw PtpParseException("unsupported type $dataType")
                    val size = r.u16()
                    val start = r.offset
                    r.skip(size)                                    // строка длиннее оставшегося — обрезанный набор
                    var end = start + size
                    while (end > start && body[end - 1] == 0.toByte()) end--    // размер считается вместе с нулём
                    strings[value] = String(body, start, end - start, Charsets.UTF_8)
                }
                lists[type] = strings
            }
            return DisplayStrings(lists)
        }
    }
}

/** Набор данных Sony (ответы 0x9215 и 0x9223): u32 смещение до данных, u32 размер данных, дальше сами данные. */
internal object SonyDataset {
    /**
     * Данные набора — ровно `размер` байт со `смещения`. Заголовок, который обещает больше, чем пришло, — обрезанный
     * набор; смещение внутрь самого заголовка — не набор: обе ошибки [PtpParseException]. Всё, что за `смещение + размер`,
     * набору не принадлежит, а между заголовком и `смещением` пропускается.
     */
    fun body(data: ByteArray): ByteArray {
        val header = ByteReader(data)
        val offset = header.u32()
        val size = header.u32()
        if (offset < 8) throw PtpParseException("bad dataset offset $offset")
        if (offset + size > data.size) throw PtpParseException("truncated at ${data.size}")
        return data.copyOfRange(offset.toInt(), (offset + size).toInt())
    }
}
