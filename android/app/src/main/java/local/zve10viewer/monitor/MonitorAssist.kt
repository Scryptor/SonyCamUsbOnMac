package local.zve10viewer.monitor

import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Помощь в кадре, как у мониторов: зебра, false color, пикинг и маркеры. Всё считается и рисуется по картинке превью
 * на экране планшета — камера об этом не знает, и на запись это не влияет. Значения и подписи — как у программы для Mac.
 */
data class MonitorAssist(
    val zebra: Zebra = Zebra.off,
    val falseColor: Boolean = false,
    val peaking: Peaking = Peaking.off,
    val peakingColor: PeakingColor = PeakingColor.red,
    val grid: Grid = Grid.off,
    val center: Boolean = false,
    val frameMarker: FrameMarker = FrameMarker.off,
    val safeZone: SafeZone = SafeZone.off,
    val overlay: Overlay = Overlay.off,
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

    /** Что считается по точкам кадра: зебра, false color, пикинг. */
    val needsRaster: Boolean get() = zebra != Zebra.off || falseColor || peaking != Peaking.off

    /** Что рисуется линиями поверх кадра: сетка, центр, рамка, безопасная зона. */
    val hasMarkers: Boolean get() = grid != Grid.off || center || frameMarker != FrameMarker.off || safeZone != SafeZone.off

    /** Включено хоть что-то. */
    val isActive: Boolean get() = needsRaster || hasMarkers || overlay != Overlay.off

    /** Значение инструмента строкой — как в API программы для Mac: «95», «on», «9:16». */
    fun value(tool: MonitorTool): String = when (tool) {
        MonitorTool.zebra -> zebra.value
        MonitorTool.falseColor -> if (falseColor) "on" else "off"
        MonitorTool.peaking -> peaking.value
        MonitorTool.peakingColor -> peakingColor.value
        MonitorTool.grid -> grid.value
        MonitorTool.center -> if (center) "on" else "off"
        MonitorTool.frameMarker -> frameMarker.value
        MonitorTool.safeZone -> safeZone.value
        MonitorTool.overlay -> overlay.value
    }

    /** С новым значением инструмента; null — такого значения у него нет. */
    fun with(tool: MonitorTool, value: String): MonitorAssist? {
        val next = when (tool) {
            MonitorTool.zebra -> Zebra.entries.firstOrNull { it.value == value }?.let { copy(zebra = it) }
            MonitorTool.falseColor -> flag(value)?.let { copy(falseColor = it) }
            MonitorTool.peaking -> Peaking.entries.firstOrNull { it.value == value }?.let { copy(peaking = it) }
            MonitorTool.peakingColor -> PeakingColor.entries.firstOrNull { it.value == value }?.let { copy(peakingColor = it) }
            MonitorTool.grid -> Grid.entries.firstOrNull { it.value == value }?.let { copy(grid = it) }
            MonitorTool.center -> flag(value)?.let { copy(center = it) }
            MonitorTool.frameMarker -> FrameMarker.entries.firstOrNull { it.value == value }?.let { copy(frameMarker = it) }
            MonitorTool.safeZone -> SafeZone.entries.firstOrNull { it.value == value }?.let { copy(safeZone = it) }
            MonitorTool.overlay -> Overlay.of(value)?.let { copy(overlay = it) }
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

    /** Всё выключено; запомненные значения остаются. */
    fun allOff(): MonitorAssist = MonitorTool.entries.fold(this) { assist, tool ->
        if (tool.options.any { it.first == "off" }) assist.with(tool, "off") ?: assist else assist
    }

    /** Горит ли плитка инструмента: включён. Цвет пикинга «выкл» не бывает — он горит вместе с пикингом. */
    fun isOn(tool: MonitorTool): Boolean = if (tool == MonitorTool.peakingColor) peaking != Peaking.off else value(tool) != "off"

    /** Подпись значения на плитке: «95 %», «Выкл», «Красный»; у наложения с ползунка — «40 %». */
    fun label(tool: MonitorTool): String {
        val v = value(tool)
        return tool.options.firstOrNull { it.first == v }?.second ?: if (tool == MonitorTool.overlay) "$v %" else v
    }

    /** Как хранится в настройках — тот же JSON, что у программы для Mac. */
    fun toJson(): String = JSONObject().apply {
        put("zebra", zebra.value)
        put("falseColor", falseColor)
        put("peaking", peaking.value)
        put("peakingColor", peakingColor.value)
        put("grid", grid.value)
        put("center", center)
        put("frameMarker", frameMarker.value)
        put("safeZone", safeZone.value)
        put("overlay", overlay.value)
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

        /** Из настроек; новые инструменты в старых настройках и битые значения — выключены. */
        fun fromJson(text: String?): MonitorAssist {
            val json = runCatching { JSONObject(text ?: return none) }.getOrNull() ?: return none
            var assist = none
            for (tool in MonitorTool.entries) {
                val raw = json.opt(tool.name) ?: continue
                val value = if (raw is Boolean) (if (raw) "on" else "off") else raw.toString()
                // значения из прошлого не должны попадать в память «последнего включённого»: её читаем отдельно
                assist = assist.with(tool, value)?.copy(lastOn = assist.lastOn) ?: assist
            }
            val last = json.optJSONObject("lastOn")
            val remembered = last?.keys()?.asSequence()?.associateWith { last.optString(it) }.orEmpty()
            return assist.copy(lastOn = remembered)
        }
    }
}

/** Плитки меню «Монитор» — по порядку на экране. options — значение и подпись. */
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
}
