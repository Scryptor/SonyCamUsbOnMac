package local.zve10viewer.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Цифровое увеличение картинки — свойство экрана, камера о нём не знает. factor — во сколько раз кадр крупнее
 * вписанного в экран (1 — кадр виден целиком), centerX и centerY — точка кадра (доли 0…1) в центре экрана. Поэтому
 * увеличение переживает поворот, смену размера окна и качества превью: та же часть кадра при другом размере в точках.
 *
 * Кадр на экране: размер S = fit × factor (fit — вписанный размер), начало O = view/2 − center·S. По оси, где S больше
 * экрана, центр не выходит из [h, 1 − h], h = (view/2)/S, — пустых полос нет; где меньше — кадр по центру. Пределы
 * применяются при каждом расчёте, а сам центр хранится как есть: повернули экран туда и обратно — то же место кадра.
 * Те же формулы и значения, что у программы для Mac.
 */
data class ViewZoom(val factor: Double, val centerX: Double, val centerY: Double) {
    val isZoomed: Boolean get() = factor > 1.0001

    /** Подпись множителя: целое — «2×», иначе одна цифра после запятой — «2,4×». */
    val label: String
        get() {
            val tenths = (factor * 10).roundToInt()
            return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10},${tenths % 10}×"
        }

    /** Где кадр на экране; центр — уже в пределах. */
    fun imageRect(view: Size, frame: Size): Rect {
        val p = Placement.of(view, frame, factor, centerX, centerY) ?: return Rect(Offset.Zero, view)
        return Rect(p.left.toFloat(), p.top.toFloat(), (p.left + p.width).toFloat(), (p.top + p.height).toFloat())
    }

    /**
     * Увеличение в k раз вокруг точки экрана `around` (между пальцами): точка кадра под ней остаётся под ней. elastic —
     * во время щипка: за пределами 1…8 — с сопротивлением (k^0,3), но не дальше 0,87 и 8,6; без него — сразу 1…8.
     */
    fun zoomed(k: Double, around: Offset, view: Size, frame: Size, elastic: Boolean): ViewZoom {
        if (k.isNaN() || k <= 0) return this
        val before = Placement.of(view, frame, factor, centerX, centerY) ?: return this
        val u = (around.x - before.left) / before.width
        val v = (around.y - before.top) / before.height
        val f = if (elastic) elasticFactor(factor, k) else (factor * k).coerceIn(1.0, MAX_FACTOR)
        val after = Placement.of(view, frame, f, 0.5, 0.5) ?: return this
        // начало кадра — так, чтобы (u, v) осталась под пальцами; центр экрана — в долях кадра
        val cx = (view.width / 2.0 - (around.x - u * after.width)) / after.width
        val cy = (view.height / 2.0 - (around.y - v * after.height)) / after.height
        return ViewZoom(f, after.clampX(cx), after.clampY(cy))
    }

    /** Сдвиг картинки за пальцем на delta точек экрана — до края кадра, не дальше. */
    fun panned(delta: Offset, view: Size, frame: Size): ViewZoom {
        val p = Placement.of(view, frame, factor, centerX, centerY) ?: return this
        return copy(centerX = p.clampX(p.centerX - delta.x / p.width), centerY = p.clampY(p.centerY - delta.y / p.height))
    }

    /** После жеста: множитель 1…8, меньше 1,05 — без увеличения; центр — в пределах. */
    fun settled(view: Size, frame: Size): ViewZoom {
        if (factor < SNAP_BELOW) return identity
        val f = min(factor, MAX_FACTOR)
        val p = Placement.of(view, frame, f, centerX, centerY) ?: return copy(factor = f)
        return ViewZoom(f, p.centerX, p.centerY)
    }

    /** В центр экрана — точка кадра `at` (доли), насколько позволяют края: касание по мини-карте. */
    fun centered(at: Offset, view: Size, frame: Size): ViewZoom {
        val p = Placement.of(view, frame, factor, at.x.toDouble(), at.y.toDouble()) ?: return this
        return copy(centerX = p.centerX, centerY = p.centerY)
    }

    /** Какая часть кадра видна на экране — доли 0…1 (для мини-карты). */
    fun visibleRegion(view: Size, frame: Size): Rect {
        val p = Placement.of(view, frame, factor, centerX, centerY) ?: return Rect(0f, 0f, 1f, 1f)
        fun share(x: Double, origin: Double, side: Double) = ((x - origin) / side).coerceIn(0.0, 1.0).toFloat()
        return Rect(share(0.0, p.left, p.width), share(0.0, p.top, p.height),
            share(view.width.toDouble(), p.left, p.width), share(view.height.toDouble(), p.top, p.height))
    }

    /** Кадр на экране при этом множителе: размер, начало и центр в пределах — всё в Double, без потерь Float. */
    private class Placement(val view: Size, val width: Double, val height: Double, centerX: Double, centerY: Double) {
        val centerX = clampX(centerX)
        val centerY = clampY(centerY)
        val left: Double get() = view.width / 2.0 - centerX * width
        val top: Double get() = view.height / 2.0 - centerY * height

        fun clampX(center: Double) = clamp(center, view.width.toDouble(), width)
        fun clampY(center: Double) = clamp(center, view.height.toDouble(), height)

        private fun clamp(center: Double, viewSide: Double, side: Double): Double {
            // кадр меньше экрана по этой оси — по центру; больше — без пустых полос
            if (side <= viewSide) return 0.5
            val h = viewSide / 2 / side
            return if (center.isNaN()) 0.5 else center.coerceIn(h, 1 - h)
        }

        companion object {
            fun of(view: Size, frame: Size, factor: Double, centerX: Double, centerY: Double): Placement? {
                if (view.width <= 0 || view.height <= 0 || frame.width <= 0 || frame.height <= 0) return null
                val fit = min(view.width.toDouble() / frame.width, view.height.toDouble() / frame.height)
                return Placement(view, frame.width * fit * factor, frame.height * fit * factor, centerX, centerY)
            }
        }
    }

    companion object {
        const val MAX_FACTOR = 8.0
        /** Отпустили ниже — к 1×. */
        const val SNAP_BELOW = 1.05
        /** Жёсткие края во время щипка и сопротивление за пределами 1…8. */
        const val ELASTIC_MIN = 0.87
        const val ELASTIC_MAX = 8.6
        private const val RESISTANCE = 0.3
        /** Ступени ⌘+ / ⌘− у программы для Mac. */
        private val LADDER = listOf(1.0, 1.5, 2.0, 3.0, 4.0, 6.0, 8.0)

        val identity = ViewZoom(1.0, 0.5, 0.5)

        fun step(from: Double, up: Boolean): Double =
            if (up) LADDER.firstOrNull { it > from + 1e-6 } ?: MAX_FACTOR else LADDER.lastOrNull { it < from - 1e-6 } ?: 1.0

        /** Для анимации: множитель — по логарифму (увеличение на глаз равномерное), центр — по прямой. */
        fun lerp(from: ViewZoom, to: ViewZoom, t: Double): ViewZoom = ViewZoom(
            exp(ln(from.factor) + (ln(to.factor) - ln(from.factor)) * t),
            from.centerX + (to.centerX - from.centerX) * t,
            from.centerY + (to.centerY - from.centerY) * t,
        )

        /**
         * Множитель после щипка в k раз с сопротивлением: в логарифмах — внутри 1…8 как есть, за пределом — 0,3 от
         * шага (это и есть k^0,3); обратно к пределам — без сопротивления.
         */
        private fun elasticFactor(factor: Double, k: Double): Double {
            val low = 0.0
            val high = ln(MAX_FACTOR)
            var x = ln(factor)
            var rest = ln(k)
            if (rest > 0 && x < high) {
                val step = min(rest, high - x)
                x += step
                rest -= step
            } else if (rest < 0 && x > low) {
                val step = max(rest, low - x)
                x += step
                rest -= step
            }
            x += rest * RESISTANCE
            return exp(x).coerceIn(ELASTIC_MIN, ELASTIC_MAX)
        }
    }
}
