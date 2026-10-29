package local.zve10viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.Vectorscope
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/*
 * Вектороскоп: цвета кадра на плоскости Cb (вправо) и Cr (вверх), Rec. 709. Серый — в середине, насыщенный цвет — к краю в
 * своём направлении; под сеткой — картинка точек 128×128 из потока кадров, растянутая на всю плоскость.
 *
 * Плоскость — квадрат |Cb|, |Cr| ≤ 0,5/масштаб, а не круг: насыщенные G/Mg (0,596) и R/Cy (0,513) лежат за кругом сетки, и
 * картинка по кругу не обрезается. Сетка: круг по краю плоскости, тонкий крест, шесть меток цветных полос 75 % (R, Mg, B, Cy, G,
 * Yl) и линия тона кожи под углом 123°. При ×2 метки полос лежат за краем 0,25 и не рисуются. Касание панели переключает масштаб
 * ×1 / ×2 (в углу «×2» горит, пока масштаб ×2); перетаскивание двигает панель. Карточки-подсказки нет, как у волновой формы.
 */

/** Размеры и места панели вектороскопа — числами, чтобы проверять без экрана. Единицы: dp у констант, дальше — любые одни. */
internal object VectorscopeGeometry {
    /** Сторона панели, dp. */
    const val SIDE_DP = 176f

    /** Отступ плоскости от края панели, dp: обводка круга у края не должна обрезаться. */
    const val INSET_DP = 4f

    /** На сколько буква метки отстоит от самой метки наружу: по горизонтали и по вертикали, dp. */
    const val LABEL_OUT_X_DP = 10f
    const val LABEL_OUT_Y_DP = 8f

    /** Плоскость: квадрат на панели со стороной [side] с отступом [inset]. Круг сетки вписан в него, картинка растянута на него. */
    fun plane(side: Float, inset: Float): Rect = Rect(inset, inset, side - inset, side - inset)

    /** Место на панели для точки плоскости, заданной долями её ширины и высоты (y вниз). */
    fun point(fraction: Vectorscope.PanelPoint, plane: Rect): Offset =
        Offset(plane.left + fraction.x.toFloat() * plane.width, plane.top + fraction.y.toFloat() * plane.height)

    /** Середина буквы метки: от метки наружу, по прямой от середины плоскости. Метка в самой середине — букву не смещают. */
    fun labelCentre(mark: Offset, centre: Offset, outX: Float, outY: Float): Offset {
        val dx = mark.x - centre.x
        val dy = mark.y - centre.y
        val length = max(sqrt(dx * dx + dy * dy), 1f)
        return Offset(mark.x + dx / length * outX, mark.y + dy / length * outY)
    }
}

private val GraticuleColor = Color.White
private val SkinColor = Color(red = 1f, green = 0.74f, blue = 0.5f)

/**
 * Панель вектороскопа: 176 dp, картинка точек под сеткой. [onZoom] — касание панели (масштаб ×1 ↔ ×2). Тащить панель умеет
 * `FloatingPanel` снаружи; здесь только касание.
 */
@Composable
fun VectorscopePanel(trace: CameraController.VectorscopeTrace, onZoom: () -> Unit) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(fontSize = 8.5.sp, fontWeight = FontWeight.SemiBold, color = GraticuleColor.copy(alpha = 0.85f)) }
    // буквы меток меряются один раз, а не на каждый кадр
    val labels = remember(measurer) { Vectorscope.targets.associate { it.name to measurer.measure(it.name, labelStyle) } }
    val zoomed = trace.zoom == 2
    Box(
        Modifier.size(VectorscopeGeometry.SIDE_DP.dp)
            .background(Color(0x8C000000), RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = "Вектороскоп"
                stateDescription = if (zoomed) "Масштаб ×2" else "Масштаб ×1"
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null,
                onClickLabel = if (zoomed) "Вернуть масштаб ×1" else "Масштаб ×2", role = Role.Button, onClick = onZoom,
            ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val plane = VectorscopeGeometry.plane(size.width, VectorscopeGeometry.INSET_DP.dp.toPx())
            // точки — под сеткой и не по кругу: всё, что лежит в квадрате плоскости, видно
            drawImage(
                trace.image, dstOffset = IntOffset(plane.left.roundToInt(), plane.top.roundToInt()),
                dstSize = IntSize(plane.width.roundToInt(), plane.height.roundToInt()), filterQuality = FilterQuality.Low,
            )
            val thin = Stroke(1.dp.toPx())
            // круг — край плоскости при любом масштабе (при ×1 это 0,5, при ×2 — 0,25)
            drawOval(GraticuleColor.copy(alpha = 0.45f), topLeft = plane.topLeft, size = plane.size, style = thin)
            // крест
            drawLine(GraticuleColor.copy(alpha = 0.22f), Offset(plane.left, plane.center.y), Offset(plane.right, plane.center.y), thin.width)
            drawLine(GraticuleColor.copy(alpha = 0.22f), Offset(plane.center.x, plane.top), Offset(plane.center.x, plane.bottom), thin.width)
            // тон кожи: из середины до круга под 123°
            drawLine(SkinColor.copy(alpha = 0.7f), plane.center, VectorscopeGeometry.point(Vectorscope.skinLineEnd, plane), thin.width)
            // метки полос 75 %: квадратик на месте цвета и буква снаружи от него; при ×2 их нет — они за краем плоскости
            val half = 3.5.dp.toPx()
            for (mark in Vectorscope.visibleTargets(trace.zoom)) {
                val at = VectorscopeGeometry.point(mark.at, plane)
                drawRect(GraticuleColor.copy(alpha = 0.85f), topLeft = Offset(at.x - half, at.y - half), size = Size(2 * half, 2 * half), style = thin)
                val label = labels.getValue(mark.target.name)
                val centre = VectorscopeGeometry.labelCentre(
                    at, plane.center, VectorscopeGeometry.LABEL_OUT_X_DP.dp.toPx(), VectorscopeGeometry.LABEL_OUT_Y_DP.dp.toPx(),
                )
                drawText(label, topLeft = Offset(centre.x - label.size.width / 2f, centre.y - label.size.height / 2f))
            }
        }
        // «×2» в углу: горит, пока масштаб ×2; в ×1 приглушена, но на месте — это и есть подсказка, куда касаться
        Text(
            "×2", fontSize = 10.sp, fontWeight = FontWeight.Bold,
            color = if (zoomed) Color.Black else Color.White.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(if (zoomed) Color.White else Color.White.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                .padding(horizontal = 5.dp, vertical = 1.5.dp),
        )
    }
}
