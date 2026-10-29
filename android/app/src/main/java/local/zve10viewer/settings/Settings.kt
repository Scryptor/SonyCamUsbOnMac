package local.zve10viewer.settings

import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty

/** Настройки камеры, которые показывает программа. Имена совпадают с API программы для Mac. */
enum class SettingID(val code: Int, val title: String, private val short: String? = null) {
    exposureProgram(SonyProp.EXPOSURE_PROGRAM, "Режим съёмки", "Режим"),
    shutter(SonyProp.SHUTTER, "Выдержка"),
    aperture(SonyProp.F_NUMBER, "Диафрагма"),
    iso(SonyProp.ISO, "ISO"),
    exposureComp(SonyProp.EXPOSURE_COMP, "Экспокоррекция", "Экспокорр."),
    metering(SonyProp.METERING, "Замер"),
    dro(SonyProp.DRO, "DRO / HDR", "DRO"),
    focusMode(SonyProp.FOCUS_MODE, "Режим фокуса", "Фокус"),
    focusArea(SonyProp.FOCUS_AREA, "Зона фокуса"),
    whiteBalance(SonyProp.WHITE_BALANCE, "Баланс белого"),
    colorTemperature(SonyProp.COLOR_TEMP, "Цветовая температура", "Цвет. темп."),
    wbShiftAB(SonyProp.AB_SHIFT, "Сдвиг A–B", "A–B"),
    wbShiftGM(SonyProp.GM_SHIFT, "Сдвиг G–M", "G–M"),
    creativeStyle(SonyProp.CREATIVE_STYLE, "Творческий стиль", "Стиль"),
    creativeLook(SonyProp.CREATIVE_LOOK, "Creative Look", "Look"),
    pictureProfile(SonyProp.PICTURE_PROFILE, "Профиль изображения", "Профиль"),
    movieFileFormat(SonyProp.MOVIE_FILE_FORMAT, "Формат видео"),
    movieRecordingSetting(SonyProp.MOVIE_RECORDING_SETTING, "Параметры записи", "Запись"),
    imageSize(SonyProp.IMAGE_SIZE, "Размер снимка", "Размер"),
    aspectRatio(SonyProp.ASPECT_RATIO, "Соотношение сторон", "Пропорции"),
    fileFormat(SonyProp.FILE_FORMAT, "Формат снимка", "Формат"),
    jpegQuality(SonyProp.JPEG_QUALITY, "Качество JPEG", "JPEG"),
    battery(SonyProp.BATTERY_LEVEL, "Батарея"),
    cardRemaining(SonyProp.CARD_REMAINING_TIME, "Осталось на карте", "Карта"),
    overheat(SonyProp.OVERHEAT, "Температура камеры", "Температура");

    val shortTitle: String get() = short ?: title
}

enum class SettingGroup(val title: String, val settings: List<SettingID>) {
    exposure("Экспозиция", listOf(SettingID.exposureProgram, SettingID.shutter, SettingID.aperture, SettingID.iso,
        SettingID.exposureComp, SettingID.metering, SettingID.dro)),
    focus("Фокус", listOf(SettingID.focusMode, SettingID.focusArea)),
    color("Цвет и картинка", listOf(SettingID.whiteBalance, SettingID.colorTemperature, SettingID.wbShiftAB,
        SettingID.wbShiftGM, SettingID.creativeStyle, SettingID.pictureProfile)),
    video("Видео", listOf(SettingID.movieFileFormat, SettingID.movieRecordingSetting)),
    photo("Фото", listOf(SettingID.imageSize, SettingID.aspectRatio, SettingID.fileFormat, SettingID.jpegQuality)),
    camera("Камера", listOf(SettingID.battery, SettingID.cardRemaining, SettingID.overheat)),
}

/** Фото или видео — определяется режимом съёмки камеры (0x500E). */
enum class CaptureMode {
    photo, video;

    companion object {
        fun of(exposureProgram: Long): CaptureMode {
            // 0x0007_80xx — видео, 0x0009_80xx — S&Q; всё остальное — фоторежимы
            val family = exposureProgram shr 16
            return if (family == 7L || family == 9L) video else photo
        }

        /** Режим съёмки с той же буквой (P/A/S/M) в другом режиме; null — уже там или камера не даёт. */
        fun counterpart(value: Long, to: CaptureMode, settable: List<Long>): Long? {
            // S&Q здесь — тоже видео; таблица режимов одна — в ShootingMode
            if (of(value) == to) return null
            return ShootingMode.counterpart(value, if (to == photo) ShootingMode.photo else ShootingMode.video, settable)
        }
    }
}

sealed class FnSlot {
    data class Setting(val id: SettingID) : FnSlot()
    data object AllSettings : FnSlot()
}

/** Раскладка меню Fn и строки значений. */
object SettingCatalog {
    val fnTopRow: List<FnSlot> = listOf(SettingID.shutter, SettingID.aperture, SettingID.iso, SettingID.whiteBalance,
        SettingID.exposureComp, SettingID.focusMode).map { FnSlot.Setting(it) }

    /**
     * В фоторежиме видео-настройки (профиль, стиль) уступают место формату и соотношению сторон снимка.
     * В видеорежиме вместо DRO — режим записи (формат, частота, битрейт одним выбором); DRO — во «Всех настройках».
     */
    fun fnBottomRow(mode: CaptureMode): List<FnSlot> = when (mode) {
        CaptureMode.video -> listOf(SettingID.focusArea, SettingID.metering, SettingID.pictureProfile,
            SettingID.creativeStyle, SettingID.movieRecordingSetting).map { FnSlot.Setting(it) } + FnSlot.AllSettings
        CaptureMode.photo -> listOf(SettingID.focusArea, SettingID.metering, SettingID.dro,
            SettingID.fileFormat, SettingID.aspectRatio).map { FnSlot.Setting(it) } + FnSlot.AllSettings
    }

    /**
     * Какая настройка на самом деле стоит на месте [id] у этой камеры ([available] — коды её свойств). Новые камеры Sony
     * вместо Creative Style 0xD240 имеют Creative Look 0xD0FA: на место «Творческого стиля» в Fn и во «Всех настройках»
     * встаёт он. У камеры, у которой есть и стиль, место за стилем. Остальные настройки — как есть.
     * В группах и рядах Fn Creative Look не значится: «Все настройки» показывают строку группы и без свойства камеры.
     */
    fun resolved(id: SettingID, available: Set<Int>): SettingID =
        if (id == SettingID.creativeStyle && SonyProp.CREATIVE_STYLE !in available && SonyProp.CREATIVE_LOOK in available) {
            SettingID.creativeLook
        } else {
            id
        }

    private val infoLine = listOf(SettingID.shutter, SettingID.aperture, SettingID.iso, SettingID.whiteBalance,
        SettingID.exposureComp, SettingID.focusMode)

    /** В конце строки значений — формат снимка в фоторежиме и режим записи в видеорежиме. */
    fun infoLine(mode: CaptureMode): List<SettingID> =
        infoLine + if (mode == CaptureMode.photo) SettingID.fileFormat else SettingID.movieRecordingSetting
}

data class SettingOption(val value: Long, val label: String)

object SettingOptions {
    /** Значения, которые можно выбрать сейчас. Берутся только из переданного свойства. */
    fun options(id: SettingID, property: SonyProperty): List<SettingOption> =
        values(property).map { SettingOption(it, ValueFormatter.label(id, it)) }

    /** Соседнее разрешённое значение; null, если сдвинуться некуда. `from` — точка отсчёта вместо текущего значения. */
    fun neighbour(property: SonyProperty, offset: Int, from: Long? = null): Long? {
        val list = values(property)
        val current = from ?: property.current.intValue ?: return null
        val index = list.indexOf(current)
        if (index < 0) return null
        val target = (index + offset).coerceIn(0, list.size - 1)
        return if (target == index) null else list[target]
    }

    fun values(property: SonyProperty): List<Long> {
        if (!property.isWritable) return emptyList()
        if (property.settableValues.isNotEmpty()) return property.settableValues
        val range = property.range ?: return emptyList()
        if (range.step <= 0 || range.max < range.min) return emptyList()
        return (range.min..range.max step range.step).toList()
    }

    fun allows(property: SonyProperty, value: Long): Boolean {
        if (property.settableValues.isNotEmpty()) return property.settableValues.contains(value)
        val range = property.range ?: return false
        if (range.step <= 0) return false
        return value in range.min..range.max && (value - range.min) % range.step == 0L
    }
}
