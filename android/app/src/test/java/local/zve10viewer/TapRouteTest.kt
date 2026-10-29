package local.zve10viewer

import local.zve10viewer.monitor.TouchFocus
import local.zve10viewer.view.TapAction
import local.zve10viewer.view.TapRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Куда идёт касание кадра, когда включена пипетка, и что про это говорит программа. Порядок тот же, что у Mac: меню — закрыть;
 * пипетка — ставит пипетку, а не фокус, что бы ни горело на кнопке «Фокус»; иначе при фокусе касанием — фокус. Элементы,
 * спрятанные касанием, сначала возвращаются, как и раньше.
 */
class TapRouteTest {
    private fun route(
        overlayOpen: Boolean = false, revealing: Boolean = false, probeOn: Boolean = false, touchFocusOn: Boolean = false,
        onPicture: Boolean = true,
    ) = TapRoute.decide(overlayOpen, revealing, probeOn, touchFocusOn, onPicture)

    @Test fun anOpenMenuIsClosedBeforeAnythingElse() {
        for (probe in listOf(false, true)) {
            for (focus in listOf(false, true)) {
                for (revealing in listOf(false, true)) {
                    for (onPicture in listOf(false, true)) {
                        assertEquals(TapAction.closeOverlays, route(true, revealing, probe, focus, onPicture))
                    }
                }
            }
        }
    }

    @Test fun hiddenControlsComeBackFirstEvenWithTheProbeOn() {
        assertEquals(TapAction.reveal, route(revealing = true, probeOn = true, touchFocusOn = true))
        assertEquals(TapAction.reveal, route(revealing = true))
    }

    @Test fun theProbeTakesTheTouchWhateverTheFocusButtonSays() {
        // кнопка «Фокус» горит — касание всё равно ставит пипетку, а не фокус
        assertEquals(TapAction.placeProbe, route(probeOn = true, touchFocusOn = true))
        assertEquals(TapAction.placeProbe, route(probeOn = true, touchFocusOn = false))
    }

    @Test fun withoutTheProbeTheTouchSetsFocusWhenTheButtonIsLit() {
        assertEquals(TapAction.focus, route(touchFocusOn = true))
    }

    @Test fun withNeitherTheTouchHidesTheControlsAsBefore() {
        assertEquals(TapAction.hide, route())
    }

    @Test fun aTouchOffThePictureNeverPlacesAnythingAndHidesTheControls() {
        // чёрная полоса рядом с кадром: ни пипетки, ни фокуса — как при фокусе касанием и раньше
        assertEquals(TapAction.hide, route(probeOn = true, onPicture = false))
        assertEquals(TapAction.hide, route(touchFocusOn = true, onPicture = false))
        assertEquals(TapAction.hide, route(probeOn = true, touchFocusOn = true, onPicture = false))
    }

    @Test fun focusIsSetOnlyWhenNothingElseOwnsTheTouch() {
        for (overlay in listOf(false, true)) {
            for (revealing in listOf(false, true)) {
                for (probe in listOf(false, true)) {
                    for (focus in listOf(false, true)) {
                        for (onPicture in listOf(false, true)) {
                            val action = route(overlay, revealing, probe, focus, onPicture)
                            val key = "overlay=$overlay revealing=$revealing probe=$probe focus=$focus onPicture=$onPicture"
                            if (action == TapAction.focus) assertTrue(key, focus && !probe && !overlay && !revealing && onPicture)
                            if (action == TapAction.placeProbe) assertTrue(key, probe && !overlay && !revealing && onPicture)
                        }
                    }
                }
            }
        }
    }

    // подсказка «Фокус касанием»

    private val nows = listOf(
        TouchFocus.helpNow(TouchFocus.Method.areaPosition, "Точка L", 3L, 0L, 1000L),
        TouchFocus.helpNow(TouchFocus.Method.remoteTouch, "Широкая", 3L, 0L, 1000L),
        TouchFocus.helpNow(null, "Точка L", null, 1L, null),
        TouchFocus.helpNow(null, "Точка L", null, 0L, 2000L),
        TouchFocus.helpNow(null, "Точка L", null, 0L, 1000L),
    )

    @Test fun whileTheProbeOwnsTheTouchTheCardDoesNotSayTheTouchMovesTheAfPoint() {
        for (now in nows) {
            val shown = TouchFocus.nowLine(true, now)
            assertEquals(TouchFocus.PROBE_NOW, shown)
            // ни «касание двигает точку AF», ни «касание экрана камеры»: про фокус говорит только строка про пипетку
            assertFalse(shown, "точку AF" in shown)
            assertFalse(shown, "касание экрана камеры" in shown)
            assertFalse(shown, "ручной фокус" in shown)
        }
        // а белая строка «Сейчас: …» остаётся, пока пипетка выключена
        for (now in nows) assertEquals(now, TouchFocus.nowLine(false, now))
        assertTrue(nows[0], "касание двигает точку AF" in nows[0])
    }

    @Test fun theProbeLineSaysWhatToDoAboutIt() {
        val line = TouchFocus.PROBE_NOW
        assertTrue(line, "Пипетка" in line && "ставит её, а не фокус" in line && "Выключили пипетку" in line)
    }

    @Test fun theFocusButtonSaysWhyItDoesNothingWhileTheProbeIsOn() {
        assertEquals("Фокус касанием", TouchFocus.buttonDescription(available = true, probeOn = false, refusal = "Камера сейчас не даёт ставить фокус касанием"))
        val on = TouchFocus.buttonDescription(available = true, probeOn = true, refusal = "Камера сейчас не даёт ставить фокус касанием")
        assertTrue(on, "Пипетка" in on && "пипетку, а не фокус" in on)
        // камера фокус не даёт — про это и говорим: пипетка тут ни при чём
        assertEquals("В ручном фокусе — шкала фокуса внизу",
            TouchFocus.buttonDescription(available = false, probeOn = true, refusal = "В ручном фокусе — шкала фокуса внизу"))
        assertEquals("Камера сейчас не даёт ставить фокус касанием",
            TouchFocus.buttonDescription(available = false, probeOn = false, refusal = "Камера сейчас не даёт ставить фокус касанием"))
    }

    @Test fun theButtonIsDimmedWhileTheProbeOwnsTheTouchOrTheCameraRefuses() {
        assertEquals(1f, TouchFocus.buttonAlpha(available = true, probeOn = false), 0f)
        assertEquals(0.5f, TouchFocus.buttonAlpha(available = true, probeOn = true), 0f)
        assertEquals(0.5f, TouchFocus.buttonAlpha(available = false, probeOn = false), 0f)
        assertEquals(0.5f, TouchFocus.buttonAlpha(available = false, probeOn = true), 0f)
    }

    // строка ZVLens про касание

    @Test fun theLensLineNamesTheScreenPointAndTheCameraPoint() {
        val line = TouchFocus.report(0.3, 0.5, 0.7, 0.5, "mirror", 447, 239)
        assertEquals("касание: экран 0.300,0.500 → камера 0.700,0.500 (картинка mirror), AF 447,239", line)
        assertEquals("касание: экран 0.200,0.100 → камера 0.800,0.900 (картинка 180), AF 511,431",
            TouchFocus.report(0.2, 0.1, 0.8, 0.9, "180", 511, 431))
    }
}
