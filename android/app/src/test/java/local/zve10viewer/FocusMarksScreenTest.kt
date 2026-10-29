package local.zve10viewer

import local.zve10viewer.ui.ScreenState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Карточка «Метки фокуса» в состоянии экрана: когда она открывается сама и что при этом закрывается. */
class FocusMarksScreenTest {
    @Test fun theCardThatOpensByItselfLeavesWhatTheUserHasOpen() {
        // карточка открылась сама, а у пользователя открыто окно пресетов и «Новый пресет»: их она не закрывает
        val screen = ScreenState()
        screen.presetsOpen = true
        screen.newPresetOpen = true
        screen.presetMenuOpen = true
        screen.offerFocusMarksHelp(doNotShow = false)
        assertTrue(screen.focusMarksHelpOpen)
        assertTrue(screen.presetsOpen)
        assertTrue(screen.newPresetOpen)
        assertTrue(screen.presetMenuOpen)
    }

    @Test fun aLongPressOnTheChipClosesWhatIsOpenLikeEveryOtherHelp() {
        val screen = ScreenState()
        screen.presetMenuOpen = true
        screen.touchFocusHelpOpen = true
        screen.openFocusMarksHelp()
        assertTrue(screen.focusMarksHelpOpen)
        assertFalse(screen.presetMenuOpen)
        assertFalse(screen.touchFocusHelpOpen)
    }

    @Test fun theCardOpensByItselfOnceAndNeverWhenTheBoxIsTicked() {
        val first = ScreenState()
        first.offerFocusMarksHelp(doNotShow = false)
        assertTrue(first.focusMarksHelpOpen)
        first.focusMarksHelpOpen = false
        first.offerFocusMarksHelp(doNotShow = false)
        assertFalse("второй раз за запуск сама не открывается", first.focusMarksHelpOpen)

        val ticked = ScreenState()
        ticked.offerFocusMarksHelp(doNotShow = true)
        assertFalse(ticked.focusMarksHelpOpen)
        // долгое нажатие открывает её всегда
        ticked.openFocusMarksHelp()
        assertTrue(ticked.focusMarksHelpOpen)
    }

    @Test fun anOpenCardKeepsTheControlsFromHidingAndTheTapOnThePictureClosesIt() {
        val screen = ScreenState()
        assertFalse(screen.overlayOpen)
        screen.openFocusMarksHelp()
        assertTrue(screen.overlayOpen)
        screen.closeOverlays()
        assertFalse(screen.focusMarksHelpOpen)
        assertFalse(screen.overlayOpen)
    }
}
