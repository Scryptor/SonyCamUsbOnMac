package local.zve10viewer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.FocusDrive
import local.zve10viewer.monitor.FocusFrame

/*
 * Фокус и зум с экрана — как у программы для Mac: «T» и «W» (держать — зум), фокус касанием (касание кадра ставит
 * туда фокус, на кадре — рамки фокуса камеры), шкала фокуса в ручном фокусе.
 */

/**
 * Зум с экрана: «T» и «W» в капсуле, как рычажок зума камеры, — держите, и камера зумирует (объектив с приводом или
 * Clear Image Zoom), отпустили — стоп. Между ними — где зум на шкале, под капсулой — масштаб, если он больше ×1,0.
 */
@Composable
fun ZoomRocker(controller: CameraController) {
    DisposableEffect(Unit) { onDispose { controller.zoom(0) } }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.background(Panel, RoundedCornerShape(percent = 50)).padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ZoomKey(controller, "T", 1)
            controller.zoomBar?.fraction?.let { fraction ->
                Box(Modifier.width(5.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().height((40 * fraction).coerceAtLeast(3.0).dp).background(Color.White.copy(alpha = 0.85f)))
                }
            }
            ZoomKey(controller, "W", -1)
        }
        Text(controller.zoomScaleLabel ?: "Зум", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

@Composable
private fun ZoomKey(controller: CameraController, title: String, direction: Int) {
    val moving = controller.zoomDirection == direction
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(if (moving) Color.White else Color.Transparent)
            .semantics { contentDescription = if (direction > 0) "Зум к теле" else "Зум к широкому" }
            // держат — зум едет; отпустили (или касание отменилось) — стоп
            .pointerInput(direction) {
                awaitEachGesture {
                    awaitFirstDown()
                    controller.zoom(direction)
                    waitForUpOrCancellation()
                    controller.zoom(0)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(title, color = if (moving) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Фокус касанием: горит — касание кадра ставит туда фокус (в зонах «Точка» и «Зона» — точка AF, в остальных — касание
 * экрана камеры: фокус или слежение, как настроено в камере), а на кадре видны рамки фокуса камеры.
 */
@Composable
fun TouchFocusButton(controller: CameraController) {
    val available = controller.touchFocusMethod != null
    Box(
        Modifier.size(58.dp).background(if (controller.touchFocusOn) Accent else Panel, CircleShape)
            .alpha(if (available) 1f else 0.5f)
            .semantics {
                contentDescription = if (available) "Фокус касанием" else controller.touchRefusal
            }
            .clickable { controller.toggleTouchFocus() },
        contentAlignment = Alignment.Center,
    ) {
        // уголки рамки фокуса и точка в середине
        Canvas(Modifier.size(26.dp)) {
            val s = size.minDimension / 24f
            val stroke = 2f * s
            for ((cx, cy, dx, dy) in listOf(listOf(4f, 4f, 1f, 1f), listOf(20f, 4f, -1f, 1f), listOf(4f, 20f, 1f, -1f), listOf(20f, 20f, -1f, -1f))) {
                drawLine(Color.White, Offset(cx * s, (cy + 5 * dy) * s), Offset(cx * s, cy * s), stroke, StrokeCap.Round)
                drawLine(Color.White, Offset(cx * s, cy * s), Offset((cx + 5 * dx) * s, cy * s), stroke, StrokeCap.Round)
            }
            drawCircle(Color.White, 2.5f * s, Offset(12f * s, 12f * s))
        }
    }
}

/**
 * Рамки фокуса камеры (зелёные — фокус найден, слежение — пунктиром) и отметка касания: сжимается к точке и гаснет за
 * секунду. rect — где кадр на экране, с увеличением; progress — сколько прошло от касания, 0…1.
 */
fun DrawScope.drawFocusFrames(frames: List<FocusFrame>, rect: Rect, mark: Offset?, progress: Float) {
    fun area(r: Rect) = Rect(rect.left + r.left * rect.width, rect.top + r.top * rect.height,
        rect.left + r.right * rect.width, rect.top + r.bottom * rect.height)
    for (frame in frames) {
        val box = area(frame.rect)
        drawRect(Color.Black.copy(alpha = 0.35f), box.topLeft, box.size, style = Stroke(3.dp.toPx()))
        drawRect(if (frame.focused) Color(0xFF34C759) else Color.White, box.topLeft, box.size,
            style = Stroke(1.5.dp.toPx(), pathEffect = if (frame.kind == FocusFrame.Kind.tracking)
                PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null))
    }
    if (mark != null && progress < 1f) {
        val side = (70 - 20 * minOf(progress / 0.25f, 1f)).dp.toPx()
        val c = Offset(rect.left + mark.x * rect.width, rect.top + mark.y * rect.height)
        drawRect(Color(0xFFFFD60A).copy(alpha = (1 - maxOf(0f, progress - 0.5f) * 2).coerceIn(0f, 1f)),
            Offset(c.x - side / 2, c.y - side / 2), Size(side, side), style = Stroke(2.dp.toPx()))
    }
}

/**
 * Шкала фокуса — ручной фокус с экрана (в MF): слева ближе, справа дальше. Кнопки — шаги 7, 3 и 1; перетаскивание по
 * шкале — как поворот кольца фокуса. Метка — где фокус, если камера его отдаёт.
 */
@Composable
fun FocusScale(controller: CameraController) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    Row(
        Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (step in listOf(FocusDrive.Step.large, FocusDrive.Step.medium, FocusDrive.Step.small)) FocusKey(controller, step, nearer = true)
        Column(Modifier.widthIn(min = 320.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(34.dp).onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
                    .semantics { contentDescription = "Шкала фокуса: тяните влево — ближе, вправо — дальше" }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragEnd = { dragged = 0f }, onDragCancel = { dragged = 0f }) { change, amount ->
                            change.consume()
                            // тянут влево — ближе: шаги по мере движения, без накопления дробей
                            val sent = FocusDrive.steps((dragged / width).toDouble())
                            dragged += amount
                            val target = FocusDrive.steps((dragged / width).toDouble())
                            if (target != sent) controller.focusStep(target - sent)
                        }
                    },
            ) {
                // деления — как на кольце фокуса; сдвигаются, пока шкалу тянут
                val spacing = 14.dp.toPx()
                var x = ((dragged % spacing) + spacing) % spacing
                val mid = size.height / 2
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(x, mid - 6.dp.toPx()), Offset(x, mid + 6.dp.toPx()), 1.dp.toPx())
                    x += spacing
                }
                controller.focalPosition?.let { position ->
                    val px = size.width * position / 100f
                    drawRoundRect(Color(0xFFFFD60A), Offset(px - 2.dp.toPx(), 3.dp.toPx()), Size(4.dp.toPx(), size.height - 6.dp.toPx()),
                        androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
                }
            }
            Row {
                Text("ближе", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                Text("шкала фокуса", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("дальше", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
        }
        for (step in listOf(FocusDrive.Step.small, FocusDrive.Step.medium, FocusDrive.Step.large)) FocusKey(controller, step, nearer = false)
    }
}

@Composable
private fun FocusKey(controller: CameraController, step: FocusDrive.Step, nearer: Boolean) {
    val arrows = (if (nearer) "‹" else "›").repeat(if (step == FocusDrive.Step.large) 3 else if (step == FocusDrive.Step.medium) 2 else 1)
    Text(arrows, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(PanelLight, RoundedCornerShape(10.dp))
            .semantics { contentDescription = (if (nearer) "Фокус ближе на " else "Фокус дальше на ") + step.units }
            .clickable { controller.focusStep(FocusDrive.value(step, nearer).toInt()) }
            .padding(horizontal = 12.dp, vertical = 6.dp))
}

/** «Снять слежение» — когда касание начало слежение и камера даёт его снять. */
@Composable
fun CancelTouchChip(controller: CameraController) {
    Text("Снять слежение", color = Color.White, fontSize = 16.sp,
        modifier = Modifier.background(Panel, RoundedCornerShape(20.dp)).clickable { controller.cancelTouch() }
            .padding(horizontal = 16.dp, vertical = 9.dp))
}
