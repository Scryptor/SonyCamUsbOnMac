package local.zve10viewer.ui

import android.annotation.SuppressLint
import android.view.HapticFeedbackConstants

/** События, на которые планшет отвечает вибрацией (если у него есть мотор и она включена). */
enum class HapticEvent {
    /** Действие принято: отпустили спуск, стоп-кадр или запись. */
    accepted,
    /** Камера сработала: шторка. */
    shutterFired,
    recordingStarted,
    recordingStopped,
    /** Стоп-кадр сохранён. */
    frameGrabbed,
    /** Отказ: камера не начала запись, снимок или стоп-кадр не удался. */
    failed,
    /** Увеличение вернулось к 1× или упёрлось в предел. */
    zoomLimit,
    /** Гистограмму подхватили пальцем. */
    dragStart,
}

/**
 * Какой отклик `View.performHapticFeedback` на событие: с Android 11 (API 30) — CONFIRM, REJECT и GESTURE_START, раньше —
 * ближайшие из старых. Таблица — в спеке; система сама учитывает «Виброотклик» в настройках и молчит без мотора.
 */
@SuppressLint("InlinedApi")
fun hapticConstant(event: HapticEvent, sdk: Int): Int {
    val modern = sdk >= 30
    return when (event) {
        HapticEvent.accepted -> HapticFeedbackConstants.VIRTUAL_KEY
        HapticEvent.shutterFired -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
        HapticEvent.recordingStarted -> HapticFeedbackConstants.LONG_PRESS
        HapticEvent.recordingStopped -> if (modern) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
        HapticEvent.frameGrabbed -> HapticFeedbackConstants.CONTEXT_CLICK
        HapticEvent.failed -> if (modern) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
        HapticEvent.zoomLimit -> HapticFeedbackConstants.CLOCK_TICK
        HapticEvent.dragStart -> if (modern) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.LONG_PRESS
    }
}
