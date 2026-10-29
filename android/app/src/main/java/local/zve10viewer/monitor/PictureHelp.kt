package local.zve10viewer.monitor

import local.zve10viewer.settings.LumaStats
import java.util.Locale

/**
 * Помощь в кадре (гистограмма, зебра, false color, волновая форма) по картинке после прохода: какая картинка ей нужна, какой
 * у неё белый и что о ней пишет отладка. Правила — как у программы для Mac.
 */
object PictureHelp {
    /**
     * Нужна ли помощи вторая картинка — повёрнутый сигнал камеры без LUT. Только если LUT на экране есть, помощь выбрана «без
     * LUT» (`lutScopes` выключен) и помощь вообще считается: иначе она берёт картинку с экрана.
     */
    fun needsSignal(lut: ColorLut?, lutScopes: Boolean, helpOn: Boolean): Boolean = helpOn && lut != null && !lutScopes

    /**
     * Белый уровень шкалы помощи: 255, если помощь считается по картинке с LUT и LUT есть (после LUT чёрный и белый — снова 0
     * и 255), иначе по сигналу камеры (234 в видео, 255 в фото — `LumaStats.whiteLevel`). Одно правило для гистограммы, зебры,
     * false color и волновой формы.
     *
     * @param levels уровни той картинки, по которой считается помощь
     */
    fun whiteLevel(levels: IntArray, video: Boolean, lut: Boolean, lutScopes: Boolean): Int =
        if (lut && lutScopes) 255 else LumaStats.whiteLevel(levels, video)

    /** Наименьший уровень, до которого (включительно) набралось [percent] % точек; 0 — точек нет. */
    fun percentile(levels: IntArray, percent: Int): Int {
        var total = 0L
        for (points in levels) total += points
        if (total <= 0) return 0
        var cumulative = 0L
        for (level in levels.indices) {
            cumulative += levels[level]
            if (cumulative * 100 >= percent * total) return level
        }
        return levels.lastIndex
    }

    /** Размах яркости p1–p99: насколько широко лежит картинка по шкале (у плоского лога он мал, с LUT — шире). */
    fun spread(levels: IntArray): Int = percentile(levels, 99) - percentile(levels, 1)

    /** Средний уровень яркости, 0…255; 0 — точек нет. */
    fun mean(levels: IntArray): Double {
        var total = 0L
        var weighted = 0L
        for (level in levels.indices) {
            total += levels[level]
            weighted += level.toLong() * levels[level]
        }
        return if (total > 0) weighted.toDouble() / total else 0.0
    }

    /**
     * Строка для logcat (тег ZVAssist): что на экране, какова картинка, по которой считается помощь, и сколько стоит проход.
     * Ключи — как в настройках, чтобы по строке было видно состояние и им же его проверять.
     *
     * @param lut какой LUT на экране: `slog3cine`, `file:имя` или `off`
     * @param mode что выбрано в плитке «LUT»: `off`, `auto`, `slog3`, `file`…
     * @param levels уровни картинки помощи
     * @param passAverageMs сколько в среднем и [passMaxMs] сколько самое долгое заняли чтение кадра, зеркало с LUT и запись
     *   обратно за время с прошлой строки, мс; NaN — проход не шёл
     * @param vectorscope включён ли вектороскоп и в каком он масштабе ([vectorscopeZoom]: 1 или 2; называется и у выключенного —
     *   это настройка, а не включатель)
     */
    fun report(
        lut: String,
        mode: String,
        orientation: FrameOrientation,
        lutScopes: Boolean,
        white: Int,
        levels: IntArray,
        width: Int,
        height: Int,
        passAverageMs: Double = Double.NaN,
        passMaxMs: Double = Double.NaN,
        vectorscope: Boolean = false,
        vectorscopeZoom: Int = 1,
    ): String {
        val low = percentile(levels, 1)
        val high = percentile(levels, 99)
        return String.format(
            Locale.ROOT,
            "картинка: lut=%s mode=%s orient=%s lutScopes=%b vectorscope=%s zoom=%d white=%d p1=%d p99=%d spread=%d mean=%.1f " +
                "pass=%s passMax=%s size=%dx%d",
            lut, mode, orientation.value, lutScopes, if (vectorscope) "on" else "off", vectorscopeZoom, white, low, high, high - low,
            mean(levels),
            milliseconds(passAverageMs), milliseconds(passMaxMs), width, height,
        )
    }

    private fun milliseconds(value: Double): String = if (value.isNaN()) "-" else String.format(Locale.ROOT, "%.1f", value)
}
