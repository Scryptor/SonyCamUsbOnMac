package local.zve10viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Значки верхней панели — «как на камере». */
enum class TopBarGlyph { histogram, statusLine, savePreset, settings }

/**
 * Значок кнопки верхней панели: белый рисунок по утверждённому макету — в квадрате 52×52, на кнопке 44 dp с масштабом
 * 44/52 × 1,12; толщина линий — с тем же масштабом, но не тоньше 1,5 dp. Фон кнопки (Accent / PanelLight) — у самой
 * кнопки.
 */
@Composable
fun TopBarIcon(glyph: TopBarGlyph, modifier: Modifier = Modifier.size(44.dp)) {
    Canvas(modifier) {
        // рисунок макета занимает середину квадрата 52 — он крупнее в 1,12 раза вокруг центра (26, 26), который ложится в
        // центр кнопки (на Mac — 1,25; на планшете пользователь попросил чуть мельче); на 44 dp ничего не обрезается.
        // Масштаб — по большей стороне: в низкой и широкой ячейке (плитка «Все» в меню Fn) значок того же размера, а
        // пустые поля макета выходят за её край
        val k = size.maxDimension / 52f * 1.12f
        val dx = (size.width - 52f * k) / 2
        val dy = (size.height - 52f * k) / 2
        val icon = Icon52(this, k, dx, dy)
        when (glyph) {
            TopBarGlyph.histogram -> icon.histogram()
            TopBarGlyph.statusLine -> icon.statusLine()
            TopBarGlyph.savePreset -> icon.savePreset()
            TopBarGlyph.settings -> icon.settings()
        }
    }
}

/** Рисование в координатах макета 52×52. */
private class Icon52(private val scope: DrawScope, private val k: Float, private val dx: Float, private val dy: Float) {
    private val white = Color.White

    private fun p(x: Float, y: Float) = Offset(dx + x * k, dy + y * k)
    private fun s(v: Float) = v * k
    private fun stroke(width: Float) = with(scope) {
        Stroke(maxOf(width * k, 1.5.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
    }

    /** Восемь столбцов и пунктир на третях — как у нашей гистограммы. */
    fun histogram() = with(scope) {
        val xs = floatArrayOf(16f, 18.6f, 21.2f, 23.8f, 26.4f, 29f, 31.6f, 34.2f)
        val hs = floatArrayOf(5f, 11f, 16f, 14f, 9f, 7f, 5f, 2.5f)
        for (i in xs.indices) {
            drawRoundRect(white, p(xs[i], 35f - hs[i]), Size(s(2f), s(hs[i])), CornerRadius(s(0.5f)))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(s(2f), s(2f)))
        for (x in floatArrayOf(22.3f, 29.7f)) {
            drawLine(white.copy(alpha = 0.55f), p(x, 15f), p(x, 35f), strokeWidth = 1.dp.toPx(), pathEffect = dash)
        }
    }

    /** Строка состояния: точка связи, Wi-Fi и батарея. */
    fun statusLine() = with(scope) {
        drawCircle(white, s(2.2f), p(15.5f, 27f))
        val wide = Path().apply {
            moveTo(p(21f, 25f).x, p(21f, 25f).y)
            quadraticBezierTo(p(25f, 21f).x, p(25f, 21f).y, p(29f, 25f).x, p(29f, 25f).y)
        }
        val narrow = Path().apply {
            moveTo(p(23f, 27.5f).x, p(23f, 27.5f).y)
            quadraticBezierTo(p(25f, 25.6f).x, p(25f, 25.6f).y, p(27f, 27.5f).x, p(27f, 27.5f).y)
        }
        drawPath(wide, white, style = stroke(1.8f))
        drawPath(narrow, white, style = stroke(1.8f))
        drawCircle(white, s(1.1f), p(25f, 30f))
        drawRoundRect(white, p(31f, 23f), Size(s(7f), s(8f)), CornerRadius(s(1.5f)), style = stroke(1.8f))
        drawLine(white, p(39.5f, 25.5f), p(39.5f, 28.5f), stroke(1.8f).width, StrokeCap.Round)
        drawRoundRect(white, p(32.5f, 24.5f), Size(s(4f), s(5f)), CornerRadius(s(0.5f)))
    }

    /** Сохранить пресет: звезда с плюсом справа внизу. */
    fun savePreset() = with(scope) {
        val points = floatArrayOf(25f, 17f, 27.4f, 22f, 32.8f, 22.6f, 28.8f, 26.3f, 29.9f, 31.6f, 25f, 28.9f, 20.1f, 31.6f,
            21.2f, 26.3f, 17.2f, 22.6f, 22.6f, 22f)
        val star = Path().apply {
            for (i in points.indices step 2) {
                val at = p(points[i], points[i + 1])
                if (i == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
            }
            close()
        }
        drawPath(star, white, style = stroke(1.9f))
        val width = stroke(1.9f).width
        drawLine(white, p(33f, 29f), p(33f, 36f), width, StrokeCap.Round)
        drawLine(white, p(29.5f, 32.5f), p(36.5f, 32.5f), width, StrokeCap.Round)
    }

    /** Настройки: сплошная шестерня с отверстием (восемь зубцов). */
    fun settings() = with(scope) {
        val points = floatArrayOf(24.19f, 18.82f, 24.36f, 16.14f, 27.64f, 16.14f, 27.81f, 18.82f, 29.79f, 19.65f, 31.81f, 17.86f,
            34.14f, 20.19f, 32.35f, 22.21f, 33.18f, 24.19f, 35.86f, 24.36f, 35.86f, 27.64f, 33.18f, 27.81f, 32.35f, 29.79f,
            34.14f, 31.81f, 31.81f, 34.14f, 29.79f, 32.35f, 27.81f, 33.18f, 27.64f, 35.86f, 24.36f, 35.86f, 24.19f, 33.18f,
            22.21f, 32.35f, 20.19f, 34.14f, 17.86f, 31.81f, 19.65f, 29.79f, 18.82f, 27.81f, 16.14f, 27.64f, 16.14f, 24.36f,
            18.82f, 24.19f, 19.65f, 22.21f, 17.86f, 20.19f, 20.19f, 17.86f, 22.21f, 19.65f)
        val gear = Path().apply {
            fillType = PathFillType.EvenOdd
            for (i in points.indices step 2) {
                val at = p(points[i], points[i + 1])
                if (i == 0) moveTo(at.x, at.y) else lineTo(at.x, at.y)
            }
            close()
            addOval(Rect(p(26f, 26f), s(3.4f)))
        }
        drawPath(gear, white)
    }
}
