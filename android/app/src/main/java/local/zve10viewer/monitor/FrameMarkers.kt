package local.zve10viewer.monitor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.abs

/**
 * Маркеры поверх кадра: рамка другого формата, безопасная зона, сетка и центр. Всё — в долях кадра (0…1), поэтому маркеры
 * едут вместе с цифровым увеличением и подходят к кадру любой формы (в фоторежиме он бывает и квадратным).
 */
object FrameMarkers {
    /** Отрезок в долях кадра. */
    data class Line(val from: Offset, val to: Offset)

    /**
     * Что рисовать: рамка другого формата (null — нет), безопасная зона — внутри рамки, а без неё внутри кадра, линии сетки
     * — внутри рамки или по всему кадру, центр кадра (null — крестика нет).
     */
    data class Layout(val marker: Rect?, val safe: Rect?, val grid: List<Line>, val center: Offset?)

    private val whole = Rect(0f, 0f, 1f, 1f)

    fun layout(assist: MonitorAssist, frame: Size): Layout {
        val marker = assist.frameMarker.aspect?.let { markerRect(it, frame) }
        val area = marker ?: whole
        val safe = assist.safeZone.fraction?.let { inset(area, it) }
        return Layout(marker, safe, grid(assist.grid, area, frame), if (assist.center) Offset(0.5f, 0.5f) else null)
    }

    /** Самая большая рамка с такими пропорциями (ширина к высоте) по центру кадра; null — кадр и так такой. */
    fun markerRect(aspect: Double, frame: Size): Rect? {
        if (aspect <= 0 || frame.width <= 0 || frame.height <= 0) return null
        val frameAspect = frame.width.toDouble() / frame.height
        if (abs(frameAspect - aspect) < 0.005) return null
        if (aspect < frameAspect) {
            // уже кадра — по высоте целиком
            val w = (aspect / frameAspect).toFloat()
            return Rect((1 - w) / 2, 0f, (1 + w) / 2, 1f)
        }
        val h = (frameAspect / aspect).toFloat()
        return Rect(0f, (1 - h) / 2, 1f, (1 + h) / 2)
    }

    /** Та же рамка, уменьшенная до доли `keeping` по каждой стороне, по центру. */
    fun inset(rect: Rect, keeping: Double): Rect {
        val dx = (rect.width * (1 - keeping) / 2).toFloat()
        val dy = (rect.height * (1 - keeping) / 2).toFloat()
        return Rect(rect.left + dx, rect.top + dy, rect.right - dx, rect.bottom - dy)
    }

    /** Трети — по две линии поперёк и вдоль. Квадраты — четыре ряда по высоте и столбцы той же ширины от центра. */
    fun grid(grid: MonitorAssist.Grid, area: Rect, frame: Size): List<Line> = when (grid) {
        MonitorAssist.Grid.off -> emptyList()
        MonitorAssist.Grid.thirds -> listOf(1f / 3, 2f / 3).flatMap { f ->
            val x = area.left + area.width * f
            val y = area.top + area.height * f
            listOf(Line(Offset(x, area.top), Offset(x, area.bottom)), Line(Offset(area.left, y), Offset(area.right, y)))
        }
        MonitorAssist.Grid.squares -> if (frame.width <= 0 || frame.height <= 0) emptyList() else buildList {
            // сторона квадрата — четверть высоты; в долях ширины кадра она другая
            val side = area.height / 4
            val sideX = side * frame.height / frame.width
            for (i in 1 until 4) {
                val y = area.top + side * i
                add(Line(Offset(area.left, y), Offset(area.right, y)))
            }
            val mid = area.center.x
            add(Line(Offset(mid, area.top), Offset(mid, area.bottom)))
            var k = 1
            while (mid + sideX * k < area.right - 0.001f) {
                for (x in listOf(mid - sideX * k, mid + sideX * k)) add(Line(Offset(x, area.top), Offset(x, area.bottom)))
                k++
            }
        }
    }
}
