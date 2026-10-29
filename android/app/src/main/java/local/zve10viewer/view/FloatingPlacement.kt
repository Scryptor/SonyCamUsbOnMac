package local.zve10viewer.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.max

/**
 * Где лежит плавающая панель (гистограмма): x и y — доли свободного места по ширине и высоте, от отступа 16 у левого
 * (верхнего) края до отступа у правого (нижнего). Доли, а не точки: место переживает поворот, смену размера окна и
 * перезапуск. Отпустили ближе 24 к отступу — панель прилипает к нему. Те же значения, что у программы для Mac.
 */
data class FloatingPlacement(val x: Double, val y: Double) {
    /** Левый верхний угол панели на экране. Экран меньше панели с отступами — у левого верхнего отступа. */
    fun origin(panel: Size, view: Size): Offset = Offset(position(x, panel.width, view.width), position(y, panel.height, view.height))

    companion object {
        const val MARGIN = 16.0
        const val SNAP_DISTANCE = 24.0

        /** Куда положить панель, которую отпустили с левым верхним углом в origin. */
        fun dropped(at: Offset, panel: Size, view: Size): FloatingPlacement =
            FloatingPlacement(share(at.x, panel.width, view.width), share(at.y, panel.height, view.height))

        /** Пока тащат: за палец, но не за край экрана (к отступу прилипнет, когда отпустят). */
        fun dragOrigin(origin: Offset, panel: Size, view: Size): Offset = Offset(
            origin.x.coerceIn(0f, max(0f, view.width - panel.width)),
            origin.y.coerceIn(0f, max(0f, view.height - panel.height)),
        )

        /**
         * Панель с левым верхним углом в [origin] не должна лежать на [obstacle] (колонка кнопок справа, лента камер слева):
         * если лежит, встаёт рядом на той же высоте, на [gap] от края препятствия, — с той стороны, что дальше от края экрана:
         * левее препятствия у правого края, правее — у левого. Остаётся между отступами экрана; места нет — у отступа. Единицы
         * любые, но одни: размер экрана, панели, препятствия, [gap] и [margin] — в точках (dp) или все в пикселях.
         */
        fun clear(origin: Offset, panel: Size, obstacle: Rect, view: Size, gap: Float = 12f, margin: Float = MARGIN.toFloat()): Offset {
            if (!Rect(origin, panel).overlaps(obstacle)) return origin
            val beside = if (obstacle.center.x > view.width / 2) obstacle.left - gap - panel.width else obstacle.right + gap
            val rightmost = max(margin, view.width - panel.width - margin)
            return Offset(beside.coerceIn(margin, rightmost), origin.y)
        }

        /** Из настроек: испорченное — как не сохранено, за пределами — в пределы. */
        fun restored(x: Float, y: Float): FloatingPlacement? {
            if (x.isNaN() || y.isNaN()) return null
            return FloatingPlacement(x.toDouble().coerceIn(0.0, 1.0), y.toDouble().coerceIn(0.0, 1.0))
        }

        private fun position(share: Double, panel: Float, view: Float): Float {
            val free = view - panel - 2 * MARGIN
            if (free <= 0) return MARGIN.toFloat()
            return (MARGIN + share.coerceIn(0.0, 1.0) * free).toFloat()
        }

        private fun share(origin: Float, panel: Float, view: Float): Double {
            val free = view - panel - 2 * MARGIN
            if (free <= 0) return 0.0
            val offset = origin - MARGIN
            return when {
                offset < SNAP_DISTANCE -> 0.0
                free - offset < SNAP_DISTANCE -> 1.0
                else -> offset / free
            }
        }
    }
}
