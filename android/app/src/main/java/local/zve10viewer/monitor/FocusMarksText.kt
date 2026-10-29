package local.zve10viewer.monitor

/**
 * Тексты меток фокуса на экране планшета и в logcat — те же слова, что у программы для Mac; вместо «правого щелчка» — долгое
 * нажатие. Подписи итога перевода («Фокус у метки A», «Дальше камера не двигает», «Не успел за 3 с») даёт [FocusPull.caption].
 */
object FocusMarksText {
    /** Пункты меню метки (долгое нажатие). */
    const val SAVE_HERE = "Запомнить здесь"
    const val ERASE = "Стереть"

    /** Что написано посередине под линейкой, пока подписи итога нет. */
    const val CAPTION_IDLE = "шкала фокуса"

    const val HELP_TITLE = "Метки фокуса"
    const val HELP_MAIN = "A+ запоминает, где сейчас фокус. A переводит фокус туда за выбранное время, касание во время перевода — " +
        "стоп. Долгое нажатие на метке — запомнить заново или стереть"
    const val HELP_TIME = "Кнопка со временем переключает 0, 1, 2, 4 и 8 секунд по кругу; «0 с» — как можно быстрее. " +
        "Эта подсказка — по долгому нажатию на ней."

    /** Надпись на кнопке: пустая метка — «A+» (касание запомнит фокус), заданная — «A» (касание переведёт фокус к ней). */
    fun buttonLabel(mark: Char, isSet: Boolean): String = if (isSet) mark.toString() else "$mark+"

    /** Время перевода на кнопке: «2 с». */
    fun time(seconds: Double): String = "${seconds.toInt()} с"

    /** Что скажет чтец экрана про кнопку метки. [distance] — расстояние до метки, если камера его даёт (подпись «1,2 м»). */
    fun buttonDescription(mark: Char, isSet: Boolean, moving: Boolean, seconds: Double, distance: String? = null): String = when {
        moving -> "Остановить перевод к метке $mark"
        !isSet -> "Метка $mark: запомнить фокус"
        else -> "Метка $mark" + (distance?.let { ", $it" } ?: "") + ": перевести фокус " +
            if (seconds <= 0.0) "как можно быстрее" else "за ${time(seconds)}"
    }

    /** Что скажет чтец экрана про кнопку времени. */
    fun timeDescription(seconds: Double): String =
        "Время перевода: ${time(seconds)}. Касание — следующее, долгое нажатие — как работают метки"

    /**
     * Метки в строке logcat (тег ZVLens): `marks A=21 B=— pull=2 moving=—`. Положения — в единицах режима перевода (0…100 или
     * 0…65535), «—» — метка пуста; `pull` — время перевода, секунд; `moving` — к какой метке фокус едет сейчас.
     */
    fun report(marks: FocusMarks, moving: Char?): String =
        "marks A=${position(marks.a)} B=${position(marks.b)} pull=${marks.duration.toInt()} moving=${moving ?: "—"}"

    private fun position(value: Double?): String = value?.let { FocusPull.roundHalfAway(it).toLong().toString() } ?: "—"
}

/**
 * Карточка «Метки фокуса» открывается сама один раз за запуск: когда шкала с метками появилась впервые (она то появляется, то
 * прячется — карточка не повторяется). Не открывается, если отмечено «Больше не показывать»; тогда предложение тоже потрачено —
 * сняли галочку посреди запуска, сама карточка не придёт, останется долгое нажатие на кнопке времени.
 */
class FocusMarksHelpOffer {
    private var offered = false

    /** Шкала с метками на экране; [doNotShow] — «Больше не показывать» отмечено. true — открыть карточку сейчас. */
    fun shouldOpen(doNotShow: Boolean): Boolean {
        if (offered) return false
        offered = true
        return !doNotShow
    }
}
