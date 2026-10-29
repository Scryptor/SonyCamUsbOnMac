package local.zve10viewer

import local.zve10viewer.monitor.FocusMarks
import local.zve10viewer.monitor.FocusMarksHelpOffer
import local.zve10viewer.monitor.FocusMarksText
import local.zve10viewer.monitor.FocusPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Тексты меток фокуса и «карточка один раз за запуск». */
class FocusMarksTextTest {
    @Test fun anEmptyMarkSaysPlusAndASetOneSaysItsLetter() {
        assertEquals("A+", FocusMarksText.buttonLabel('A', isSet = false))
        assertEquals("B+", FocusMarksText.buttonLabel('B', isSet = false))
        assertEquals("A", FocusMarksText.buttonLabel('A', isSet = true))
        assertEquals("B", FocusMarksText.buttonLabel('B', isSet = true))
    }

    @Test fun timeIsWholeSecondsWithTheUnit() {
        assertEquals(listOf("0 с", "1 с", "2 с", "4 с", "8 с"), FocusPull.durations.map(FocusMarksText::time))
    }

    @Test fun theMenuAndTheCaptionsAreTheOnesFromTheSpec() {
        assertEquals("Запомнить здесь", FocusMarksText.SAVE_HERE)
        assertEquals("Стереть", FocusMarksText.ERASE)
        assertEquals("шкала фокуса", FocusMarksText.CAPTION_IDLE)
        // подписи итога — из автомата перевода, одни и те же на обеих платформах
        assertEquals("Фокус у метки A", FocusPull.caption(FocusPull.Outcome.reached, "A"))
        assertEquals("Дальше камера не двигает", FocusPull.caption(FocusPull.Outcome.stuck, "A"))
        assertEquals("Не успел за 3 с", FocusPull.caption(FocusPull.Outcome.timedOut, "B"))
    }

    @Test fun theButtonTellsWhatATapDoes() {
        assertEquals("Метка A: запомнить фокус", FocusMarksText.buttonDescription('A', isSet = false, moving = false, seconds = 2.0))
        assertEquals("Метка B: перевести фокус за 4 с", FocusMarksText.buttonDescription('B', isSet = true, moving = false, seconds = 4.0))
        assertEquals("Метка B: перевести фокус как можно быстрее",
            FocusMarksText.buttonDescription('B', isSet = true, moving = false, seconds = 0.0))
        assertEquals("Остановить перевод к метке A", FocusMarksText.buttonDescription('A', isSet = true, moving = true, seconds = 2.0))
        // расстояние, если оно есть, — после названия метки
        assertEquals("Метка A, 1,2 м: перевести фокус за 2 с",
            FocusMarksText.buttonDescription('A', isSet = true, moving = false, seconds = 2.0, distance = "1,2 м"))
    }

    @Test fun theTimeButtonSaysTheTimeAndHowToGetHelp() {
        assertEquals("Время перевода: 2 с. Касание — следующее, долгое нажатие — как работают метки", FocusMarksText.timeDescription(2.0))
    }

    @Test fun theCardSaysLongPressNotRightClick() {
        val text = FocusMarksText.HELP_MAIN
        assertTrue(text, text.startsWith("A+ запоминает, где сейчас фокус. A переводит фокус туда за выбранное время, касание во время перевода — стоп."))
        assertTrue(text, text.endsWith("Долгое нажатие на метке — запомнить заново или стереть"))
        assertFalse(text, text.contains("щелчок"))
        assertEquals("Метки фокуса", FocusMarksText.HELP_TITLE)
        assertTrue(FocusMarksText.HELP_TIME, FocusMarksText.HELP_TIME.contains("0, 1, 2, 4 и 8"))
        assertTrue(FocusMarksText.HELP_TIME, FocusMarksText.HELP_TIME.contains("долгому нажатию"))
        assertFalse(FocusMarksText.HELP_TIME, FocusMarksText.HELP_TIME.contains("щелчок"))
    }

    @Test fun theLogLineShowsMarksTimeAndWhatMoves() {
        assertEquals("marks A=21 B=— pull=2 moving=—", FocusMarksText.report(FocusMarks(a = 21.0), moving = null))
        assertEquals("marks A=21 B=100 pull=4 moving=B", FocusMarksText.report(FocusMarks(21.0, 100.0, 4.0), moving = 'B'))
        // абсолютный фокус: большие числа целыми; половинка — от нуля, как везде
        assertEquals("marks A=58330 B=13790 pull=0 moving=A", FocusMarksText.report(FocusMarks(58330.2, 13789.5, 0.0), moving = 'A'))
        assertEquals("marks A=0 B=— pull=2 moving=—", FocusMarksText.report(FocusMarks(a = 0.0), moving = null))
    }

    // карточка один раз за запуск

    @Test fun theCardOpensByItselfOnTheFirstScaleWithMarksOnly() {
        val offer = FocusMarksHelpOffer()
        assertTrue(offer.shouldOpen(doNotShow = false))
        // шкала то появляется, то прячется — карточка не открывается снова
        assertFalse(offer.shouldOpen(doNotShow = false))
        assertFalse(offer.shouldOpen(doNotShow = false))
    }

    @Test fun aTickedBoxKeepsTheCardClosedAndTheOfferIsSpent() {
        val offer = FocusMarksHelpOffer()
        assertFalse(offer.shouldOpen(doNotShow = true))
        // галочку сняли посреди запуска: сама карточка уже не придёт — только по долгому нажатию на кнопке времени
        assertFalse(offer.shouldOpen(doNotShow = false))
    }

    @Test fun everyRunStartsWithAFreshOffer() {
        assertTrue(FocusMarksHelpOffer().shouldOpen(doNotShow = false))
        assertTrue(FocusMarksHelpOffer().shouldOpen(doNotShow = false))
    }
}
