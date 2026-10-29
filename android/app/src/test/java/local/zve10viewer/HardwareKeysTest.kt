package local.zve10viewer

import android.view.KeyEvent
import local.zve10viewer.ui.PillInfo
import local.zve10viewer.ui.ScreenState
import local.zve10viewer.view.HardwareKeys
import local.zve10viewer.view.KeyAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Кнопки громкости и «Камера» как главная кнопка съёмки (⚙, по умолчанию выключено): какие клавиши забирает программа, на что
 * она отзывается, где и когда нажатие что-то делает. Коды — те, что в спеке: 24, 25 и 27.
 */
class HardwareKeysTest {
    private val ours = listOf(24, 25, 27)
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    // KeyEvent.ACTION_MULTIPLE (2) помечен устаревшим; самим клавишам он не приходит, но программа его не принимает за нажатие
    private val multiple = 2

    // какие клавиши забираются

    @Test fun theCodesAreVolumeUpVolumeDownAndCamera() {
        assertEquals(24, KeyEvent.KEYCODE_VOLUME_UP)
        assertEquals(25, KeyEvent.KEYCODE_VOLUME_DOWN)
        assertEquals(27, KeyEvent.KEYCODE_CAMERA)
        for (code in ours) assertTrue("клавиша $code", HardwareKeys.consumes(code, enabled = true))
    }

    @Test fun nothingElseIsTakenWhenOn() {
        // «громкость» вообще — только эти две; «без звука», Enter, пробел, «Назад», вспомогательные клавиши пультов — не наши
        for (code in 0..400) {
            if (code in ours) continue
            assertFalse("клавиша $code", HardwareKeys.consumes(code, enabled = true))
        }
        for (code in listOf(KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_FOCUS, KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_DPAD_CENTER)) {
            assertFalse("клавиша $code", HardwareKeys.consumes(code, enabled = true))
        }
    }

    @Test fun nothingIsTakenWhenOffSoTheSystemKeepsTheVolume() {
        for (code in 0..400) assertFalse("клавиша $code", HardwareKeys.consumes(code, enabled = false))
    }

    @Test fun theSwitchIsOffByDefault() {
        // случайная запись хуже, чем лишний поиск в настройках
        assertFalse(HardwareKeys.DEFAULT_ENABLED)
        assertEquals("volumeKeys", HardwareKeys.PREF_KEY)
    }

    // на какое событие клавиши отзывается

    @Test fun onlyTheFirstDownActs() {
        assertTrue(HardwareKeys.acts(down, repeatCount = 0))
    }

    @Test fun aHeldKeyDoesNotRepeat() {
        for (repeat in 1..40) assertFalse("повтор $repeat", HardwareKeys.acts(down, repeat))
    }

    @Test fun releaseAndMultipleNeverAct() {
        for (repeat in 0..3) {
            assertFalse(HardwareKeys.acts(up, repeat))
            assertFalse(HardwareKeys.acts(multiple, repeat))
        }
    }

    // событие целиком: забрать и/или нажать

    @Test fun aPressIsTakenInBothHalvesAndActsOnTheDownOnly() {
        for (code in ours) {
            val first = HardwareKeys.handle(code, down, 0, enabled = true)
            assertTrue(first.consumed)
            assertTrue(first.pressed)
            // отпускание забирается тоже — иначе система сочла бы его громкостью, — но не действует
            val release = HardwareKeys.handle(code, up, 0, enabled = true)
            assertTrue(release.consumed)
            assertFalse(release.pressed)
        }
    }

    @Test fun holdingTheKeyActsOnceAndTakesEveryEvent() {
        for (code in ours) {
            val events = listOf(down to 0, down to 1, down to 2, down to 3, up to 0)
            val handled = events.map { (action, repeat) -> HardwareKeys.handle(code, action, repeat, enabled = true) }
            assertTrue(handled.all { it.consumed })
            assertEquals(1, handled.count { it.pressed })
            assertTrue(handled.first().pressed)
        }
    }

    @Test fun aKeyThatIsNotOursIsLeftToTheSystemEvenOnItsFirstDown() {
        for (code in listOf(KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BACK)) {
            val handled = HardwareKeys.handle(code, down, 0, enabled = true)
            assertFalse(handled.consumed)
            assertFalse(handled.pressed)
        }
    }

    @Test fun withTheSwitchOffNothingIsTakenAndNothingActs() {
        for (code in ours) {
            for ((action, repeat) in listOf(down to 0, down to 1, up to 0)) {
                val handled = HardwareKeys.handle(code, action, repeat, enabled = false)
                assertFalse("клавиша $code, действие $action, повтор $repeat", handled.consumed)
                assertFalse(handled.pressed)
            }
        }
    }

    @Test fun anActingEventIsAlwaysATakenOne() {
        for (code in 0..400) {
            for (enabled in listOf(false, true)) {
                for ((action, repeat) in listOf(down to 0, down to 1, up to 0)) {
                    val handled = HardwareKeys.handle(code, action, repeat, enabled)
                    if (handled.pressed) assertTrue(handled.consumed)
                    assertEquals(HardwareKeys.consumes(code, enabled), handled.consumed)
                }
            }
        }
    }

    // что делает нажатие

    private fun press(
        onFrameScreen: Boolean = true, photoMode: Boolean = true, ready: Boolean = true, photoBusy: Boolean = false,
        recordPending: Boolean = false,
    ) = HardwareKeys.press(onFrameScreen, photoMode, ready, photoBusy, recordPending)

    @Test fun inPhotoModeTheKeyReleasesTheShutter() {
        assertEquals(KeyAction.photo, press(photoMode = true))
    }

    @Test fun inVideoAndSlowQuickTheKeyStartsOrStopsTheRecording() {
        // видео и S&Q для кнопки на экране одно и то же: «режим съёмки — видео»
        assertEquals(KeyAction.record, press(photoMode = false))
    }

    @Test fun offTheFrameScreenNothingHappensWhateverElseIsTrue() {
        for (photo in listOf(false, true)) {
            for (ready in listOf(false, true)) {
                for (busy in listOf(false, true)) {
                    for (pending in listOf(false, true)) {
                        assertEquals(KeyAction.elsewhere, press(false, photo, ready, busy, pending))
                    }
                }
            }
        }
    }

    @Test fun withoutACameraThatIsReadyNothingHappens() {
        assertEquals(KeyAction.notReady, press(ready = false, photoMode = true))
        assertEquals(KeyAction.notReady, press(ready = false, photoMode = false))
    }

    @Test fun whileTheCameraHasNotAnsweredThePreviousShotNothingHappens() {
        // снимок идёт (фокус, спуск, загрузка) или пресет ещё применяется — то же, что серая кнопка спуска
        assertEquals(KeyAction.busy, press(photoMode = true, photoBusy = true))
    }

    @Test fun whileTheCameraHasNotAnsweredThePreviousRecordPressNothingHappens() {
        // запрос «Запуск записи…» / «Остановка…» висит — то же, что серая кнопка записи; а после отказа по времени (6 с) вызов мог
        // не вернуться — и тогда тоже: камера не ответила, второе нажатие не её дело и не повод для сообщений
        assertEquals(KeyAction.busy, press(photoMode = false, recordPending = true))
    }

    @Test fun eachModeLooksOnlyAtItsOwnButton() {
        // в фото кнопка записи не видна, в видео — кнопки спуска: чужое ожидание им не мешает
        assertEquals(KeyAction.photo, press(photoMode = true, recordPending = true))
        assertEquals(KeyAction.record, press(photoMode = false, photoBusy = true))
    }

    @Test fun everyCombinationIsDecidedByTheSameRules() {
        for (frame in listOf(false, true)) for (photo in listOf(false, true)) for (ready in listOf(false, true)) {
            for (busy in listOf(false, true)) for (pending in listOf(false, true)) {
                val expected = when {
                    !frame -> KeyAction.elsewhere
                    !ready -> KeyAction.notReady
                    photo -> if (busy) KeyAction.busy else KeyAction.photo
                    else -> if (pending) KeyAction.busy else KeyAction.record
                }
                assertEquals("frame=$frame photo=$photo ready=$ready busy=$busy pending=$pending", expected,
                    press(frame, photo, ready, busy, pending))
            }
        }
    }

    @Test fun everyActionHasAJournalLine() {
        val lines = KeyAction.entries.map { it.journalText }
        assertTrue(lines.all { it.isNotBlank() })
        assertEquals(lines.size, lines.toSet().size)
        // и «что сделали», и «почему нет» — одной строкой
        assertTrue(KeyAction.entries.filter { it != KeyAction.photo && it != KeyAction.record }.all { "ничего" in it.journalText })
    }

    // экран кадра: ни ⚙, ни галереи, ни пресетов, ни отладки, ни просмотра снимка, ни меню

    @Test fun anEmptyScreenIsTheFrameScreen() {
        val screen = ScreenState()
        assertTrue(screen.isFrameScreen(reviewOpen = false))
    }

    @Test fun hiddenControlsDoNotMakeItAnotherScreen() {
        // с пультом и снимают при спрятанных кнопках — для того он и нужен
        val screen = ScreenState()
        screen.manualHidden = true
        assertTrue(screen.isFrameScreen(reviewOpen = false))
        screen.manualHidden = false
        screen.autoHidden = true
        assertTrue(screen.isFrameScreen(reviewOpen = false))
    }

    @Test fun zoomingTheFrameDoesNotMakeItAnotherScreenEither() {
        val screen = ScreenState()
        screen.zoom = local.zve10viewer.view.ViewZoom(2.0, 0.5, 0.5)
        assertTrue(screen.zoom.isZoomed)
        assertTrue(screen.isFrameScreen(reviewOpen = false))
    }

    @Test fun eachPanelAndMenuTakesTheFrameScreenAway() {
        val opens: Map<String, (ScreenState) -> Unit> = mapOf(
            "⚙" to { it.settingsOpen = true },
            "все настройки" to { it.allSettingsOpen = true },
            "меню Fn" to { it.fnOpen = true },
            "меню «Монитор»" to { it.assistOpen = true },
            "подсказка строки состояния" to { it.pillInfo = PillInfo.connection },
            "список пресетов" to { it.presetMenuOpen = true },
            "«Новый пресет»" to { it.newPresetOpen = true },
            "«Сохранить настройки»" to { it.savePresetOpen = true },
            "окно пресетов" to { it.presetsOpen = true },
            "подсказка «Фокус касанием»" to { it.touchFocusHelpOpen = true },
            "карточка «Метки фокуса»" to { it.focusMarksHelpOpen = true },
            "панель отладки" to { it.debugOpen = true },
            "галерея" to { it.galleryShot = 7L },
        )
        for ((name, open) in opens) {
            val screen = ScreenState()
            open(screen)
            assertFalse(name, screen.isFrameScreen(reviewOpen = false))
        }
    }

    @Test fun aShotOnReviewTakesTheFrameScreenAway() {
        // просмотр снимка живёт в контроллере, не в состоянии экрана — его передают отдельно
        assertFalse(ScreenState().isFrameScreen(reviewOpen = true))
    }

    @Test fun closingWhatWasOpenBringsTheFrameScreenBack() {
        val screen = ScreenState()
        screen.fnOpen = true
        screen.settingsOpen = true
        assertFalse(screen.isFrameScreen(reviewOpen = false))
        screen.closeOverlays()
        assertTrue(screen.isFrameScreen(reviewOpen = false))
        // отладку и галерею закрывают сами, не касанием по кадру
        screen.debugOpen = true
        screen.closeOverlays()
        assertFalse(screen.isFrameScreen(reviewOpen = false))
        screen.debugOpen = false
        assertTrue(screen.isFrameScreen(reviewOpen = false))
    }
}
