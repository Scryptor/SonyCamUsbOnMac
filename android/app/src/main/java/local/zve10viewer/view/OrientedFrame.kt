package local.zve10viewer.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.FrameRect
import kotlin.math.max

/**
 * Зеркало и переворот картинки на экране в точках и долях Compose (`Offset`, `Rect`, `Float`): куда на экране ложится то, что камера
 * называет в долях своего кадра, — рамки фокуса, метка касания, эталон наложения. Сам перевод — в чистом [FrameOrientation]
 * (`FramePoint` и `FrameRect` в `Double`); здесь он одним местом подключён к экрану, поэтому в вызовах нет ни `toDouble()`, ни
 * `toFloat()` и они не расходятся.
 *
 * `rect` везде — где кадр лежит на экране, с увеличением (`ViewZoom.imageRect`): рамки едут вместе с картинкой, а на зеркальной
 * и перевёрнутой картинке встают на то место, где камера их видит.
 */
object OrientedFrame {
    /**
     * Доли кадра, каким он виден на экране, под точкой экрана [at]; null — мимо кадра. Это экранные доли: перевод в доли камеры
     * (`TouchFocus.cameraPoint`) делается отдельно и по настройке картинки.
     */
    fun fraction(at: Offset, rect: Rect): Offset? {
        if (rect.width <= 0f || rect.height <= 0f || !rect.contains(at)) return null
        return Offset((at.x - rect.left) / rect.width, (at.y - rect.top) / rect.height)
    }

    /** Рамка в долях кадра камеры (рамка фокуса) → прямоугольник на экране при кадре на экране в [rect]. */
    fun toScreen(box: Rect, rect: Rect, orientation: FrameOrientation): Rect {
        val r = orientation.fromCamera(FrameRect(box.left.toDouble(), box.top.toDouble(), box.width.toDouble(), box.height.toDouble()))
        return Rect(
            (rect.left + r.x * rect.width).toFloat(), (rect.top + r.y * rect.height).toFloat(),
            (rect.left + r.maxX * rect.width).toFloat(), (rect.top + r.maxY * rect.height).toFloat(),
        )
    }

    /** Точка в долях кадра камеры (метка касания) → точка на экране при кадре на экране в [rect]. */
    fun toScreen(point: Offset, rect: Rect, orientation: FrameOrientation): Offset {
        val p = orientation.fromCamera(FramePoint(point.x.toDouble(), point.y.toDouble()))
        return Offset((rect.left + p.x * rect.width).toFloat(), (rect.top + p.y * rect.height).toFloat())
    }

    /**
     * Куда перевёрнуто рисуется эталон: `scale(±1, ±1)` вокруг середины кадра на экране. Эталон снят камерой как есть, и на
     * зеркальную картинку он ложится только с тем же поворотом.
     */
    fun flip(orientation: FrameOrientation): Offset =
        Offset(if (orientation.flipsX) -1f else 1f, if (orientation.flipsY) -1f else 1f)

    /**
     * Где лежит эталон размером [image] на кадре [rect]: заполняет кадр по большей стороне, середина та же, лишнее обрезается по
     * краю кадра. Нет эталона или кадра — пусто.
     */
    fun referenceBox(image: Size, rect: Rect): Rect {
        if (image.width <= 0f || image.height <= 0f || rect.width <= 0f || rect.height <= 0f) return Rect.Zero
        val k = max(rect.width / image.width, rect.height / image.height)
        val w = image.width * k
        val h = image.height * k
        return Rect(rect.center.x - w / 2, rect.center.y - h / 2, rect.center.x + w / 2, rect.center.y + h / 2)
    }
}
