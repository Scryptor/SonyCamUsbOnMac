package local.zve10viewer.monitor

import local.zve10viewer.monitor.MonitorAssist.LutMode

/** Какой встроенный LUT включён: выбор в плитке «LUT» и «Авто» по профилю изображения камеры. Правила — как у программы для Mac. */
object LutChoice {
    /**
     * Какой встроенный LUT на экране, или null — его нет. Выбор рукой остаётся тем, что выбран, какой бы ни был профиль;
     * «Свой» (`file`) и «Выкл» — не встроенный LUT; «Авто» берёт LUT по профилю изображения камеры.
     *
     * @param pictureProfile номер профиля камеры, `0xD23F` (PP1…PP10); у камер без `0xD0E1` — единственное, по чему можно
     *   судить о гамме
     * @param ppGamma гамма профиля, `0xD0E1`, если камера её отдаёт
     * @param colorMode цветовой режим профиля, `0xD0E9`, если камера его отдаёт
     */
    fun resolve(mode: LutMode, pictureProfile: Long?, ppGamma: Long?, colorMode: Long?): BuiltinLut? =
        resolve(mode, pictureProfile, ppGamma, colorMode, cameraConverts)

    /** «Авто: S-Log3», «Авто: нет» — подпись на плитке при выборе «Авто». */
    fun autoLabel(resolved: BuiltinLut?): String = "Авто: " + (resolved?.label ?: "нет")

    /**
     * Значение на плитке «LUT». У «Авто» — «Авто: S-Log3»: какой LUT выбрана по профилю камеры сейчас ([resolved] — то, что
     * показывает `CameraController.resolvedLut`). У «Свой» — имя файла, пока он лежит в папке ([ownNames]), иначе «Свой».
     * Остальное — как у `MonitorAssist.label`.
     */
    fun tileValue(assist: MonitorAssist, resolved: BuiltinLut?, ownNames: List<String>): String = when (assist.lut) {
        LutMode.auto -> autoLabel(resolved)
        LutMode.file -> assist.lutFile?.takeIf { it in ownNames } ?: "Свой"
        else -> assist.label(MonitorTool.lut)
    }

    /**
     * Превью камеры уже приведено к Rec.709 самой камерой (гамма-помощь, Gamma Disp. Assist)? Тогда «Авто» ничего не
     * включает: LUT поверх дал бы двойное преобразование. Пока это не проверено на камере — false, и превью считается
     * логарифмическим. Проверка — в конце работ (`docs/protocol.md`, «Превью в S-Log и HLG»).
     */
    val cameraConverts: Boolean = false

    // внутренности

    /** То же, что `resolve`, но с явным `cameraConverts` — чтобы проверять и ветку «камера сама приводит к 709». */
    internal fun resolve(mode: LutMode, pictureProfile: Long?, ppGamma: Long?, colorMode: Long?, cameraConverts: Boolean): BuiltinLut? =
        when (mode) {
            LutMode.off, LutMode.file -> null
            LutMode.slog3cine -> BuiltinLut.slog3cine
            LutMode.slog3 -> BuiltinLut.slog3
            LutMode.slog2 -> BuiltinLut.slog2
            LutMode.hlg -> BuiltinLut.hlg
            LutMode.auto -> if (cameraConverts) null else automatic(pictureProfile, ppGamma, colorMode)
        }

    /**
     * «Авто» по профилю. Значения — из таблиц справочника Sony: `0xD0E1` (0x0302 S-Log2, 0x0303 S-Log3, 0x0401…0x0404 HLG)
     * и `0xD0E9` (0x0008 S-Gamut3.Cine, 0x0009 S-Gamut3, 0x000C S-Gamut — у S-Gamut те же основные цвета, что у S-Gamut3,
     * поэтому и LUT тот же).
     */
    private fun automatic(pictureProfile: Long?, ppGamma: Long?, colorMode: Long?): BuiltinLut? {
        // Камера сама называет гамму — тогда номер профиля не нужен: в нём гамму могли перенастроить
        if (ppGamma != null) {
            return when (ppGamma) {
                0x0302L -> BuiltinLut.slog2
                // S-Log3 с S-Gamut3 или S-Gamut — свой LUT, с остальными охватами (и без охвата) — S-Gamut3.Cine
                0x0303L -> if (colorMode == 0x0009L || colorMode == 0x000CL) BuiltinLut.slog3 else BuiltinLut.slog3cine
                in 0x0401L..0x0404L -> BuiltinLut.hlg
                else -> null
            }
        }
        // Нет 0xD0E1 (ZV-E10): заводские профили Sony. Если профиль в камере перенастроили, «Авто» ошибётся — тогда LUT
        // выбирают руками (так сказано в справке плитки)
        return when (pictureProfile) {
            7L -> BuiltinLut.slog2
            8L -> BuiltinLut.slog3cine
            9L -> BuiltinLut.slog3
            10L -> BuiltinLut.hlg
            else -> null
        }
    }
}

/** Тексты полосы под вариантами LUT в меню «Монитор» — те же, что у программы для Mac. */
object LutTexts {
    const val SCOPES = "Помощь — по картинке с LUT"
    const val ADD = "Добавить LUT…"
    const val DELETE = "Удалить"

    /** Под переключателем: что значит «по картинке с LUT» и что — выключенное (на Mac это подсказка при наведении). */
    const val SCOPES_HELP = "Гистограмма, волновая форма, вектороскоп, зебра, false color, пикинг и пипетка считаются по картинке " +
        "с LUT, и белое в них — 255. Выключено — по сигналу камеры, только повёрнутому."

    /** Под кнопками «Добавить LUT…» и «Удалить» у варианта «Свой». */
    const val FILES_HELP = "Файл 3D LUT в формате .cube, до 12 штук. «Удалить» убирает выбранный из папки программы; " +
        "исходный файл остаётся где был."

    /** Список своих LUT пуст. */
    const val EMPTY = "Своих LUT пока нет: нажмите «Добавить LUT…»"

    /** Строка состояния после добавления файла (и после замены). */
    fun added(name: String): String = "LUT «$name» добавлен"
}
