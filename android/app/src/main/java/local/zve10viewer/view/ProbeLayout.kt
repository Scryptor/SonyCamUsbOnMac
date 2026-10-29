package local.zve10viewer.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.FramePoint
import kotlin.math.max
import kotlin.math.min

/**
 * Пипетка на экране: где стоит метка, где рядом с ней табличка и куда уходит точка, когда метку тащат. Чистая геометрия без
 * Compose-слоя — вид только рисует по ней, а края, увеличение и колонка кнопок проверяются тестами. Правила те же, что у программы
 * для Mac (ProbeLayout.swift), и одно сверх них: табличка не ложится на колонку кнопок справа (у Mac она могла лечь под неё).
 *
 * Точка пипетки живёт в долях всего кадра, каким он виден на экране (зеркальный или перевёрнутый — как есть; увеличение на доли
 * не влияет: это доли кадра, а не видимой части). Ей, как и увеличению, не нужен перевод в координаты камеры: помощь считается
 * по той же повёрнутой картинке, что на экране.
 *
 * Единицы — одни у всего вызова: dp (у констант [Sizes.dp] и в тестах) или пиксели ([Sizes.scaled] с плотностью экрана).
 */
object ProbeLayout {
    /**
     * Размеры вокруг метки. [markerDiameter] — кольцо метки; [gap] — просвет между меткой и табличкой; [margin] — ближе этого к
     * краю области табличка не встаёт; [grabRadius] — метку берёт жест, начатый не дальше этого от её середины (чуть шире
     * самого кольца, чтобы попасть пальцем); [columnGap] — просвет между табличкой и препятствием (колонкой кнопок).
     */
    data class Sizes(val markerDiameter: Float, val gap: Float, val margin: Float, val grabRadius: Float, val columnGap: Float) {
        /** Те же размеры в других единицах: dp → пиксели при плотности [factor]. */
        fun scaled(factor: Float) = Sizes(markerDiameter * factor, gap * factor, margin * factor, grabRadius * factor, columnGap * factor)

        companion object {
            /** Метка 20 dp, просвет 6, поле 4, захват 16, просвет до колонки 12 (как у плавающих панелей). */
            val dp = Sizes(20f, 6f, 4f, 16f, 12f)
        }
    }

    /**
     * Где метка на экране: точка кадра (доли) при кадре, лежащем на экране в [rect] (с увеличением он больше экрана, и метка
     * может оказаться за его краем).
     */
    fun markerCenter(fraction: FramePoint, rect: Rect): Offset =
        Offset((rect.left + fraction.x * rect.width).toFloat(), (rect.top + fraction.y * rect.height).toFloat())

    /**
     * Левый верхний угол таблички (его размер — [plate]) при метке с центром [marker]; [area] — где табличка может стоять
     * (видимая часть кадра). Обычно она справа от метки, по её высоте посередине. У правого края, где справа места нет, —
     * слева от метки; у нижнего табличка поднимается ровно настолько, чтобы уместиться (у верхнего — опускается). Сторона
     * остаётся той же, поэтому метка всегда рядом с табличкой, а не под ней. Табличка не заходит за область ближе, чем на
     * [Sizes.margin], и не ложится на метку, пока по ширине помещается хоть с одной стороны; в области уже таблички она стоит у
     * левого края (у верхнего — если не хватает высоты).
     *
     * [avoid] — то, на что табличка не ложится: колонка кнопок справа (в пикселях экрана, как и всё остальное). Сторона, где
     * табличка легла бы на неё (с просветом [Sizes.columnGap]), не годится: берётся другая; если не годятся обе (метка стоит прямо
     * под колонкой) — табличка встаёт рядом с самой колонкой, со стороны, обращённой к середине области. Важность по порядку:
     * табличка целиком в области; табличка не закрывает метку; табличка не ложится на колонку. Если область уже таблички с
     * колонкой, колонкой придётся пожертвовать.
     */
    fun plateOrigin(marker: Offset, plate: Size, area: Rect, sizes: Sizes = Sizes.dp, avoid: Rect? = null): Offset {
        // метки нет под рукой (не-число) — считаем её серединой области: табличка всё равно остаётся в области
        val cx = if (marker.x.isFinite()) marker.x else area.center.x
        val cy = if (marker.y.isFinite()) marker.y else area.center.y
        val reach = sizes.markerDiameter / 2 + sizes.gap
        val right = cx + reach
        val left = cx - reach - plate.width
        val low = area.left + sizes.margin
        val high = area.right - sizes.margin - plate.width
        val y = clamp(cy - plate.height / 2, area.top + sizes.margin, area.bottom - sizes.margin - plate.height)
        val keepOff = avoid?.inflate(sizes.columnGap)
        val ring = sizes.markerDiameter / 2
        val markerBox = Rect(cx - ring, cy - ring, cx + ring, cy + ring)
        fun fits(x: Float) = x >= low && x <= high
        fun box(x: Float) = Rect(x, y, x + plate.width, y + plate.height)
        fun clear(x: Float) = keepOff == null || !box(x).overlaps(keepOff)
        val x = when {
            fits(right) && clear(right) -> right
            fits(left) && clear(left) -> left
            else -> {
                // негде с обеих сторон: туда, где в области больше места
                val roomy = if (area.right - cx >= cx - area.left) right else left
                val base = if (fits(right)) right else if (fits(left)) left else roomy
                if (keepOff == null || clear(base)) {
                    base
                } else {
                    // легла бы на препятствие: рядом с ним, со стороны середины области. Метку закрывать хуже, чем колонку, —
                    // если рядом с колонкой табличка легла бы на метку, она остаётся там, где была
                    val beside = clamp(if (keepOff.center.x > area.center.x) keepOff.left - plate.width else keepOff.right, low, high)
                    if (box(beside).overlaps(markerBox)) base else beside
                }
            }
        }
        return Offset(clamp(x, low, high), y)
    }

    /**
     * Метку тащат: точка кадра (доли), в которую она встала, когда её сдвинули на [by] от точки [start], с кадром на экране в
     * [rect]. Не дальше видимой части кадра ([visible], доли): метка не уходит за экран и не теряется под увеличением. Нет
     * кадра или сдвиг не число — точка остаётся где была.
     */
    fun dragged(start: FramePoint, by: Offset, rect: Rect, visible: Rect): FramePoint {
        if (rect.width <= 0f || rect.height <= 0f || !by.x.isFinite() || !by.y.isFinite()) return start
        return FramePoint(
            limit(start.x + by.x.toDouble() / rect.width, visible.left.toDouble(), visible.right.toDouble()),
            limit(start.y + by.y.toDouble() / rect.height, visible.top.toDouble(), visible.bottom.toDouble()),
        )
    }

    /**
     * Жест начат на метке (тянет её, а не картинку): точка начала не дальше [Sizes.grabRadius] от середины метки. [within] — где
     * метка видна (экран): невидимую, за краем, жест не хватает.
     */
    fun grabs(start: Offset, marker: Offset, sizes: Sizes = Sizes.dp, within: Rect? = null): Boolean {
        val dx = start.x - marker.x
        val dy = start.y - marker.y
        if (!dx.isFinite() || !dy.isFinite()) return false
        if (within != null && !within.contains(marker)) return false
        return dx * dx + dy * dy <= sizes.grabRadius * sizes.grabRadius
    }

    /** Точка кадра (доли) в пределах кадра; null — не число: такую точку не ставят. */
    fun clamped(point: FramePoint): FramePoint? {
        if (!point.x.isFinite() || !point.y.isFinite()) return null
        return FramePoint(min(max(point.x, 0.0), 1.0), min(max(point.y, 0.0), 1.0))
    }

    /** Значение в пределах; где верхний предел ниже нижнего (табличка больше области) — нижний. */
    private fun clamp(value: Float, low: Float, high: Float): Float = min(max(value, low), max(low, high))

    private fun limit(value: Double, low: Double, high: Double): Double = min(max(value, low), max(low, high))
}
