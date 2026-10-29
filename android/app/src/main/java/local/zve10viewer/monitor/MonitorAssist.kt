package local.zve10viewer.monitor

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Помощь в кадре, как у мониторов: зебра, false color, пикинг, маркеры, вектороскоп и пипетка, а ещё LUT и зеркало с
 * переворотом картинки. Всё считается и рисуется по картинке превью на экране планшета — камера об этом не знает, и на
 * запись это не влияет. Значения и подписи — как у программы для Mac.
 */
data class MonitorAssist(
    val zebra: Zebra = Zebra.off,
    val falseColor: Boolean = false,
    val peaking: Peaking = Peaking.off,
    val peakingColor: PeakingColor = PeakingColor.red,
    val lut: LutMode = LutMode.off,
    /**
     * Какой свой LUT выбран — имя файла без расширения; null — ни один. Остаётся и при другом выборе, и после «Всё —
     * выключить».
     */
    val lutFile: String? = null,
    /**
     * Помощь (гистограмма, волновая форма, вектороскоп, зебра, false color, пикинг, пипетка) считать по картинке с LUT, а
     * не по сигналу камеры. Не плитка и не включатель: «Всё — выключить» её не трогает.
     */
    val lutScopes: Boolean = true,
    val vectorscope: Boolean = false,
    /** Масштаб вектороскопа: 1 или 2. Не плитка: «Всё — выключить» его не трогает. */
    val vectorscopeZoom: Int = 1,
    val probe: Boolean = false,
    val grid: Grid = Grid.off,
    val center: Boolean = false,
    val frameMarker: FrameMarker = FrameMarker.off,
    val safeZone: SafeZone = SafeZone.off,
    val overlay: Overlay = Overlay.off,
    /** Зеркало и переворот картинки на экране — плитка «Картинка». */
    val orientation: FrameOrientation = FrameOrientation.off,
    /** Последнее включённое значение каждого инструмента — к нему возвращает повторное касание выбранной плитки. */
    val lastOn: Map<String, String> = emptyMap(),
) {
    /** Зебра: полосы там, где яркость выше порога (100 % и 95 % — пересвет) или в полосе кожи (70 % ± 5). */
    enum class Zebra(val value: String) { off("off"), above100("100"), above95("95"), skin70("70") }

    /** Пикинг — подсветка резких краёв; уровень — как у камеры: чем выше, тем больше краёв подсвечено. */
    enum class Peaking(val value: String) { off("off"), low("low"), mid("mid"), high("high") }

    enum class PeakingColor(val value: String) { red("red"), yellow("yellow"), white("white"), blue("blue") }

    enum class Grid(val value: String) { off("off"), thirds("thirds"), squares("squares") }

    /** Рамка кадра другого формата: что войдёт в вертикальное видео, пост, кино. aspect — ширина к высоте. */
    enum class FrameMarker(val value: String, val aspect: Double?) {
        off("off", null), vertical("9:16", 9.0 / 16), portrait("4:5", 4.0 / 5), square("1:1", 1.0), classic("4:3", 4.0 / 3),
        scope("2.39:1", 2.39),
    }

    /** Безопасная зона: доля кадра (или рамки), куда помещают надписи и главное. */
    enum class SafeZone(val value: String, val fraction: Double?) { off("off", null), p90("90", 0.9), p80("80", 0.8) }

    /**
     * Наложение кадра: эталонный кадр поверх живого с этой непрозрачностью (5–100 %) — повторить сцену. В настройках —
     * строкой: «off» или проценты, «40»; прежние «25», «50», «75» читаются как раньше.
     */
    data class Overlay(val percent: Int?) {
        val value: String get() = percent?.toString() ?: "off"
        val opacity: Float? get() = percent?.let { it / 100f }

        companion object {
            val off = Overlay(null)
            val range = 5..100

            fun of(value: String): Overlay? = if (value == "off") off else value.toIntOrNull()?.takeIf { it in range }?.let { Overlay(it) }

            /** С ползунка (0…1) — с шагом 5 %, не меньше 5. */
            fun steps(opacity: Float): Overlay = Overlay(((opacity * 20).roundToInt() * 5).coerceIn(range))
        }
    }

    /**
     * LUT для превью: S-Log и HLG на экране выглядят привычно. «Авто» выбирает LUT по профилю изображения камеры (какой
     * именно — решает LutChoice), следующие четыре — встроенные, «Свой» — файл .cube из lutFile.
     */
    enum class LutMode(val value: String) {
        off("off"), auto("auto"), slog3cine("slog3cine"), slog3("slog3"), slog2("slog2"), hlg("hlg"), file("file")
    }

    /** Что считается по точкам кадра: зебра, false color, пикинг. */
    val needsRaster: Boolean get() = zebra != Zebra.off || falseColor || peaking != Peaking.off

    /** Нужны ли точки кадра: растр помощи, вектороскоп или пипетка. */
    val needsPixels: Boolean get() = needsRaster || vectorscope || probe

    /** Что рисуется линиями поверх кадра: сетка, центр, рамка, безопасная зона. */
    val hasMarkers: Boolean get() = grid != Grid.off || center || frameMarker != FrameMarker.off || safeZone != SafeZone.off

    /**
     * Выбраны ли LUT или зеркало с переворотом — по настройкам. Это не «картинка меняется»: «Авто» может ничего не выбрать
     * (профиль без лога), «Свой» — остаться без файла. Кто рисует, решает по итоговому кубу (`LutCache.cube`) и по
     * `orientation`: нет ни куба, ни поворота — преобразования нет и оно ничего не стоит.
     */
    val transformsPicture: Boolean get() = lut != LutMode.off || orientation != FrameOrientation.off

    /** Включено хоть что-то. */
    val isActive: Boolean get() = needsPixels || transformsPicture || hasMarkers || overlay != Overlay.off

    /** Значение инструмента строкой — как в API программы для Mac: «95», «on», «9:16», «slog3», «180». */
    fun value(tool: MonitorTool): String = when (tool) {
        MonitorTool.zebra -> zebra.value
        MonitorTool.falseColor -> if (falseColor) "on" else "off"
        MonitorTool.peaking -> peaking.value
        MonitorTool.peakingColor -> peakingColor.value
        MonitorTool.lut -> lut.value
        MonitorTool.vectorscope -> if (vectorscope) "on" else "off"
        MonitorTool.probe -> if (probe) "on" else "off"
        MonitorTool.grid -> grid.value
        MonitorTool.center -> if (center) "on" else "off"
        MonitorTool.frameMarker -> frameMarker.value
        MonitorTool.safeZone -> safeZone.value
        MonitorTool.overlay -> overlay.value
        MonitorTool.orientation -> orientation.value
    }

    /** С новым значением инструмента; null — такого значения у него нет. */
    fun with(tool: MonitorTool, value: String): MonitorAssist? {
        val next = when (tool) {
            MonitorTool.zebra -> Zebra.entries.firstOrNull { it.value == value }?.let { copy(zebra = it) }
            MonitorTool.falseColor -> flag(value)?.let { copy(falseColor = it) }
            MonitorTool.peaking -> Peaking.entries.firstOrNull { it.value == value }?.let { copy(peaking = it) }
            MonitorTool.peakingColor -> PeakingColor.entries.firstOrNull { it.value == value }?.let { copy(peakingColor = it) }
            MonitorTool.lut -> LutMode.entries.firstOrNull { it.value == value }?.let { copy(lut = it) }
            MonitorTool.vectorscope -> flag(value)?.let { copy(vectorscope = it) }
            MonitorTool.probe -> flag(value)?.let { copy(probe = it) }
            MonitorTool.grid -> Grid.entries.firstOrNull { it.value == value }?.let { copy(grid = it) }
            MonitorTool.center -> flag(value)?.let { copy(center = it) }
            MonitorTool.frameMarker -> FrameMarker.entries.firstOrNull { it.value == value }?.let { copy(frameMarker = it) }
            MonitorTool.safeZone -> SafeZone.entries.firstOrNull { it.value == value }?.let { copy(safeZone = it) }
            MonitorTool.overlay -> Overlay.of(value)?.let { copy(overlay = it) }
            MonitorTool.orientation -> FrameOrientation.entries.firstOrNull { it.value == value }?.let { copy(orientation = it) }
        } ?: return null
        return if (value != "off") next.copy(lastOn = lastOn + (tool.name to value)) else next
    }

    /**
     * Выключить, если включён, иначе вернуть последнее включённое значение (или первое после «Выкл»). У цвета пикинга
     * «выкл» нет — он идёт по кругу.
     */
    fun toggled(tool: MonitorTool): MonitorAssist {
        val options = tool.options.map { it.first }
        if ("off" !in options) {
            val i = options.indexOf(value(tool)).coerceAtLeast(0)
            return with(tool, options[(i + 1) % options.size]) ?: this
        }
        if (value(tool) != "off") return with(tool, "off") ?: this
        val remembered = lastOn[tool.name]?.takeIf { accepts(tool, it) && it != "off" }
        return with(tool, remembered ?: options[1]) ?: this
    }

    /**
     * Соседнее значение — стрелки в меню «Монитор»; null — дальше некуда. У наложения между ступенями (40 % с ползунка) —
     * ближайшая ступень в эту сторону.
     */
    fun stepped(tool: MonitorTool, offset: Int): String? {
        val options = tool.options.map { it.first }
        val i = options.indexOf(value(tool))
        if (i >= 0) return options[(i + offset).coerceIn(0, options.size - 1)]
        val percent = overlay.percent?.takeIf { tool == MonitorTool.overlay } ?: return null
        val steps = options.mapNotNull { Overlay.of(it)?.percent }
        return if (offset > 0) steps.firstOrNull { it > percent }?.toString() else steps.lastOrNull { it < percent }?.toString() ?: "off"
    }

    /**
     * Всё выключено, зеркало и LUT тоже: «всё выключено» — картинка такая, какой её даёт камера. Запомненные значения,
     * выбранный файл LUT, помощь «по картинке с LUT» и масштаб вектороскопа остаются.
     */
    fun allOff(): MonitorAssist = MonitorTool.entries.fold(this) { assist, tool ->
        if (tool.options.any { it.first == "off" }) assist.with(tool, "off") ?: assist else assist
    }

    /** Горит ли плитка инструмента: включён. Цвет пикинга «выкл» не бывает — он горит вместе с пикингом. */
    fun isOn(tool: MonitorTool): Boolean = if (tool == MonitorTool.peakingColor) peaking != Peaking.off else value(tool) != "off"

    /**
     * Подпись значения на плитке: «95 %», «Выкл», «Красный»; у наложения с ползунка — «40 %»; у своего LUT — имя файла
     * («Свой», пока файл не выбран); у картинки — короткое «Зеркало», «180°». «Авто: S-Log3» на плитке LUT складывает
     * LutChoice по профилю камеры, здесь у «Авто» — «Авто».
     */
    fun label(tool: MonitorTool): String {
        if (tool == MonitorTool.lut && lut == LutMode.file) return lutFile?.takeIf { it.isNotEmpty() } ?: "Свой"
        if (tool == MonitorTool.orientation) return orientation.label
        val v = value(tool)
        return tool.options.firstOrNull { it.first == v }?.second ?: if (tool == MonitorTool.overlay) "$v %" else v
    }

    /**
     * Как хранится в настройках — тот же JSON, что у программы для Mac: выбор из нескольких — строкой («95», «180»),
     * вкл/выкл — bool, масштаб вектороскопа — число; файла LUT нет — ключа нет.
     */
    fun toJson(): String = JSONObject().apply {
        put("zebra", zebra.value)
        put("falseColor", falseColor)
        put("peaking", peaking.value)
        put("peakingColor", peakingColor.value)
        put("lut", lut.value)
        lutFile?.let { put("lutFile", it) }
        put("lutScopes", lutScopes)
        put("vectorscope", vectorscope)
        put("vectorscopeZoom", vectorscopeZoom)
        put("probe", probe)
        put("grid", grid.value)
        put("center", center)
        put("frameMarker", frameMarker.value)
        put("safeZone", safeZone.value)
        put("overlay", overlay.value)
        put("orientation", orientation.value)
        put("lastOn", JSONObject(lastOn))
    }.toString()

    companion object {
        val none = MonitorAssist()

        /** Такое значение у инструмента бывает: из списка, а у наложения — любые проценты 5–100. */
        fun accepts(tool: MonitorTool, value: String): Boolean =
            if (tool == MonitorTool.overlay) Overlay.of(value) != null else tool.options.any { it.first == value }

        private fun flag(value: String): Boolean? = when (value) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> null
        }

        /**
         * Из настроек; новые инструменты в старых настройках и битые значения — выключены. Ключи, появившиеся позже
         * остальных (LUT, вектороскоп, пипетка, картинка), читаются только своим типом JSON, как у программы для Mac:
         * строка, bool или число; другой тип — как «не записано». org.json сам приводит типы (optString делает из числа
         * строку, optBoolean читает «true»), поэтому тип проверяется здесь.
         */
        fun fromJson(text: String?): MonitorAssist {
            val json = runCatching { JSONObject(text ?: return none) }.getOrNull() ?: return none
            var assist = none
            for (tool in MonitorTool.entries) {
                val raw = json.opt(tool.name) ?: continue
                val value = when (tool) {
                    // новые: выбор — только строкой, вкл/выкл — только bool
                    MonitorTool.lut, MonitorTool.orientation -> raw as? String
                    MonitorTool.vectorscope, MonitorTool.probe -> (raw as? Boolean)?.let { if (it) "on" else "off" }
                    // прежние читаются как раньше: bool или строка
                    else -> if (raw is Boolean) (if (raw) "on" else "off") else raw.toString()
                } ?: continue
                // значения из прошлого не должны попадать в память «последнего включённого»: её читаем отдельно
                assist = assist.with(tool, value)?.copy(lastOn = assist.lastOn) ?: assist
            }
            val last = json.optJSONObject("lastOn")
            val remembered = last?.keys()?.asSequence()?.associateWith { last.optString(it) }.orEmpty()
            return assist.copy(
                // не плитки: свой файл LUT, помощь «по картинке с LUT» и масштаб вектороскопа — только 1 и 2 (число 2 в
                // любой записи, всё остальное — 1)
                lutFile = json.opt("lutFile") as? String,
                lutScopes = (json.opt("lutScopes") as? Boolean) ?: true,
                vectorscopeZoom = if ((json.opt("vectorscopeZoom") as? Number)?.toDouble() == 2.0) 2 else 1,
                lastOn = remembered,
            )
        }
    }
}

/**
 * Плитки меню «Монитор» — по порядку на экране; после последней идёт «Всё — выключить», она не инструмент. options —
 * значение и подпись.
 */
enum class MonitorTool(val title: String, val options: List<Pair<String, String>>, val help: String) {
    zebra("Зебра", listOf("off" to "Выкл", "100" to "100 %", "95" to "95 %", "70" to "70 % кожа"),
        "Полосы там, где кадр ярче порога: 100 % и 95 % — пересвет и почти пересвет, 70 % — светлая кожа (70 ± 5 %)."),
    falseColor("False color", listOf("off" to "Выкл", "on" to "Вкл"),
        "Кадр в условных цветах по яркости: фиолетовый — провал, синий — глубокие тени, зелёный — средне-серый, " +
            "розовый — кожа, жёлтый — почти белое, красный — пересвет; остальное — серым."),
    peaking("Пикинг", listOf("off" to "Выкл", "low" to "Низкий", "mid" to "Средний", "high" to "Высокий"),
        "Подсветка резких краёв — где фокус. С увеличением кадра работает точнее."),
    peakingColor("Цвет пикинга", listOf("red" to "Красный", "yellow" to "Жёлтый", "white" to "Белый", "blue" to "Синий"),
        "Цвет подсветки резких краёв."),
    lut("LUT", listOf("off" to "Выкл", "auto" to "Авто", "slog3cine" to "S-Log3 Cine", "slog3" to "S-Log3", "slog2" to "S-Log2",
        "hlg" to "HLG", "file" to "Свой"),
        "Превью S-Log и HLG с обычным контрастом и цветом — только на экране. «Авто» берёт LUT по профилю " +
            "изображения камеры с заводскими гаммами Sony (PP7 — S-Log2, PP8 и PP9 — S-Log3, PP10 — HLG); если " +
            "профиль перенастроен, выберите LUT вручную. «Свой» — файл .cube."),
    vectorscope("Вектороскоп", listOf("off" to "Выкл", "on" to "Вкл"),
        "Цвета кадра на круге: направление — оттенок, расстояние от центра — насыщенность, наклонная " +
            "линия — тон кожи. Касание панели — масштаб ×2, чтобы разглядеть бледные цвета лога."),
    probe("Пипетка", listOf("off" to "Выкл", "on" to "Вкл"),
        "Касание кадра ставит пипетку; пока она включена, фокус касанием не ставится. Метка показывает " +
            "яркость в процентах, R, G, B и зону false color — среднее по квадрату 5×5 точек; её можно тащить."),
    grid("Сетка", listOf("off" to "Выкл", "thirds" to "Трети", "squares" to "Квадраты"),
        "Сетка по третям или квадратами — для композиции."),
    center("Центр", listOf("off" to "Выкл", "on" to "Вкл"), "Крестик в центре кадра."),
    frameMarker("Рамка", listOf("off" to "Выкл", "9:16" to "9:16", "4:5" to "4:5", "1:1" to "1:1", "4:3" to "4:3", "2.39:1" to "2.39:1"),
        "Рамка другого формата: что войдёт в вертикальное видео (9:16), пост (4:5, 1:1) или кино (2.39:1)."),
    safeZone("Безоп. зона", listOf("off" to "Выкл", "90" to "90 %", "80" to "80 %"),
        "Безопасная зона: 90 % или 80 % кадра (или рамки) — внутри неё надписи не обрежутся."),
    overlay("Наложение", listOf("off" to "Выкл", "25" to "25 %", "50" to "50 %", "75" to "75 %", "100" to "100 %"),
        "Эталонный кадр поверх живого — повторить сцену. Эталон — текущий кадр, картинка из файла или снимок " +
            "из галереи; прозрачность и «спрятать» — на панели наложения."),
    orientation("Картинка", FrameOrientation.entries.map { it.value to it.title },
        "Только на экране: «Зеркально» — как в зеркале, чтобы снимать себя; «Вверх ногами» — камера на риге " +
            "или подвесе перевёрнута. Снимки, файлы и сама камера остаются как есть."),
}
