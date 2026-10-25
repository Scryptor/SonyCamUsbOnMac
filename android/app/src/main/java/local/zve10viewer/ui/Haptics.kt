package local.zve10viewer.ui

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View

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

/** Вибрация через `View.performHapticFeedback`: без своего разрешения, с учётом «Виброотклика» в настройках системы. */
class Haptics(private val view: View, private val enabled: () -> Boolean) {
    fun perform(event: HapticEvent) {
        if (enabled()) view.performHapticFeedback(hapticConstant(event, Build.VERSION.SDK_INT))
    }

    companion object {
        /** Есть ли у устройства вибромотор: без него переключателя в ⚙ нет (у Redmi Pad Pro его нет). */
        fun deviceHasVibrator(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator?.hasVibrator() == true
            } else {
                @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)?.hasVibrator() == true
            }
        }.getOrDefault(false)
    }
}
