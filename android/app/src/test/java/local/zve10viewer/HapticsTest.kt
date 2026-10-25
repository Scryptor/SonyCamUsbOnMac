package local.zve10viewer

import android.view.HapticFeedbackConstants
import local.zve10viewer.ui.HapticEvent
import local.zve10viewer.ui.hapticConstant
import org.junit.Assert.assertEquals
import org.junit.Test

class HapticsTest {
    @Test fun modernDevicesGetTheDedicatedEffects() {
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstant(HapticEvent.shutterFired, 34))
        assertEquals(HapticFeedbackConstants.REJECT, hapticConstant(HapticEvent.failed, 34))
        assertEquals(HapticFeedbackConstants.GESTURE_START, hapticConstant(HapticEvent.dragStart, 34))
        assertEquals(HapticFeedbackConstants.CONFIRM, hapticConstant(HapticEvent.recordingStopped, 34))
    }

    @Test fun androidTenFallsBack() {
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstant(HapticEvent.shutterFired, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstant(HapticEvent.failed, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstant(HapticEvent.dragStart, 29))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, hapticConstant(HapticEvent.recordingStopped, 29))
    }

    @Test fun sameEverywhere() {
        for (sdk in listOf(29, 30, 34)) {
            assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, hapticConstant(HapticEvent.accepted, sdk))
            assertEquals(HapticFeedbackConstants.LONG_PRESS, hapticConstant(HapticEvent.recordingStarted, sdk))
            assertEquals(HapticFeedbackConstants.CONTEXT_CLICK, hapticConstant(HapticEvent.frameGrabbed, sdk))
            assertEquals(HapticFeedbackConstants.CLOCK_TICK, hapticConstant(HapticEvent.zoomLimit, sdk))
        }
    }
}
