package local.zve10viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import local.zve10viewer.monitor.Waveform
import kotlin.math.roundToInt
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.AssistRaster
import local.zve10viewer.monitor.FrameMarkers
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorTool

/*
 * Монитор: зебра, false color, пикинг и маркеры — как у программы для Mac. Меню — внизу, на месте строки значений, как
 * меню Fn: сверху значения выбранного инструмента и подсказка, ниже — плитки. Касание плитки выбирает её, касание
 * выбранной — включает или выключает.
 */

@Composable
fun AssistMenu(controller: CameraController, screen: ScreenState) {
    val tool = screen.assistSelected
    val assist = controller.assist
    Column(
        Modifier.widthIn(max = 940.dp).pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((value, label) in tool.options) {
                    val current = assist.value(tool) == value
                    Text(label, color = if (current) Color.Black else Color.White, fontSize = 18.sp,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.background(if (current) Color.White else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { controller.setAssist(tool, value) }
                            .padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            if (tool == MonitorTool.overlay) {
                // эталон: откуда он и как взять новый; снимок — кнопкой «Наложить на кадр» в галерее
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(controller.reference?.let { "Эталон: ${it.source}" } ?: "Эталона пока нет", color = Secondary, fontSize = 16.sp)
                    Text("Новый кадр", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp))
                            .clickable(enabled = controller.frame != null) {
                                controller.takeReferenceFromFrame()
                                controller.showOverlay()
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                    val pick = rememberReferencePicker(controller)
                    Text("Из файла…", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp)).clickable { pick() }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            Text(tool.help, color = Secondary, fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val tiles: List<MonitorTool?> = MonitorTool.entries + listOf(null)
            tiles.chunked(5).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        if (item == null) {
                            AssistTile("Всё", "Выключить", on = false, selected = false, enabled = assist.isActive,
                                modifier = Modifier.weight(1f)) { controller.assistOff() }
                        } else {
                            AssistTile(item.title, assist.label(item), on = assist.isOn(item),
                                selected = item == tool, modifier = Modifier.weight(1f)) {
                                if (screen.assistSelected == item) controller.toggleAssist(item) else screen.assistSelected = item
                            }
                        }
                    }
                    // неполный ряд — плитки той же ширины, что в полном
                    repeat(5 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun AssistTile(title: String, value: String, on: Boolean, selected: Boolean, modifier: Modifier, enabled: Boolean = true,
                       onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 72.dp)
            .background(if (selected) Accent else if (on) Color(0x29FFFFFF) else PanelLight, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "$title: $value" }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(title, color = if (selected) Color.White else Secondary, fontSize = 14.sp, maxLines = 1)
        Text(value, color = if (on || selected) Color.White else Color.White.copy(alpha = 0.45f), fontSize = 19.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** Легенда false color — что значит каждый цвет. Видна, пока false color включён. */
@Composable
fun FalseColorLegend(modifier: Modifier = Modifier) {
    Row(
        modifier.background(Panel, RoundedCornerShape(20.dp)).padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (band in AssistRaster.FalseColorBand.entries) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.size(14.dp).background(Color(0xFF000000 or band.rgb.toLong()), RoundedCornerShape(3.dp)))
                Text(band.title, color = Color.White, fontSize = 14.sp)
            }
        }
    }
}

/**
 * Маркеры поверх кадра: рамка другого формата (снаружи притемнено), безопасная зона пунктиром, сетка и центр. rect — где
 * кадр на экране (с увеличением); линии — одной толщины при любом увеличении, с тенью: видно и на светлом небе.
 */
fun DrawScope.drawAssistMarkers(assist: MonitorAssist, frame: Size, rect: Rect) {
    val layout = FrameMarkers.layout(assist, frame)
    fun point(p: Offset) = Offset(rect.left + p.x * rect.width, rect.top + p.y * rect.height)
    fun area(r: Rect) = Rect(point(r.topLeft), point(r.bottomRight))
    val shadow = Color.Black.copy(alpha = 0.35f)
    fun line(from: Offset, to: Offset, opacity: Float, width: Float, dash: PathEffect? = null) {
        drawLine(shadow, from, to, width + 1.5.dp.toPx(), pathEffect = dash)
        drawLine(Color.White.copy(alpha = opacity), from, to, width, pathEffect = dash)
    }
    fun box(r: Rect, opacity: Float, width: Float, dash: PathEffect? = null) {
        line(r.topLeft, r.topRight, opacity, width, dash)
        line(r.topRight, r.bottomRight, opacity, width, dash)
        line(r.bottomRight, r.bottomLeft, opacity, width, dash)
        line(r.bottomLeft, r.topLeft, opacity, width, dash)
    }
    val thin = 1.dp.toPx()
    layout.marker?.let { marker ->
        val inner = area(marker)
        val visible = Rect(Offset.Zero, size).intersect(rect)
        val outside = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(visible)
            addRect(inner)
        }
        drawPath(outside, Color.Black.copy(alpha = 0.45f))
        box(inner, 0.9f, 1.5.dp.toPx())
    }
    layout.safe?.let { box(area(it), 0.75f, thin, PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))) }
    for (l in layout.grid) line(point(l.from), point(l.to), 0.55f, thin)
    layout.center?.let { c ->
        val at = point(c)
        val arm = 14.dp.toPx()
        line(Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), 0.9f, 1.5.dp.toPx())
        line(Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), 0.9f, 1.5.dp.toPx())
    }
}

/**
 * Волновая форма: по горизонтали — кадр слева направо, по вертикали — яркость, снизу чёрное. Линии — 0, 50 и 100 % (белое
 * превью: в видео 234, в фото 255); что выше 100 % — пересвет. Под графиком — переключатель «Яркость / RGB».
 */
@Composable
fun WaveformView(trace: CameraController.WaveformTrace, mode: Waveform.Mode, onMode: (Waveform.Mode) -> Unit) {
    val measurer = rememberTextMeasurer()
    Column(
        Modifier.background(Color(0x73000000), RoundedCornerShape(10.dp)).padding(10.dp).width(300.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(116.dp)) {
            fun y(level: Double) = size.height * (1 - level.toFloat() / 256)
            drawImage(trace.image, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low)
            val label = TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 9.sp)
            for (fraction in listOf(1.0, 0.5, 0.0)) {
                val at = y(trace.white * fraction + 0.5)
                drawLine(Color.White.copy(alpha = if (fraction == 0.5) 0.3f else 0.45f), Offset(0f, at), Offset(size.width, at),
                    1.dp.toPx(), pathEffect = if (fraction == 0.5) PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())) else null)
                drawText(measurer, "${(fraction * 100).roundToInt()}", Offset(2.dp.toPx(), (at - 13.dp.toPx()).coerceIn(0f, size.height - 12.dp.toPx())),
                    style = label)
            }
            if (mode == Waveform.Mode.parade) {
                for (third in listOf(1f / 3, 2f / 3)) {
                    drawLine(Color.White.copy(alpha = 0.3f), Offset(size.width * third, 0f), Offset(size.width * third, size.height), 1.dp.toPx())
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Волновая форма", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, modifier = Modifier.weight(1f))
            for (item in Waveform.Mode.entries) {
                val current = item == mode
                Text(item.title, color = if (current) Color.Black else Color.White, fontSize = 13.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 6.dp).background(if (current) Color.White else PanelLight, RoundedCornerShape(6.dp))
                        .semantics { contentDescription = "Волновая форма: ${item.title}" }
                        .clickable { onMode(item) }.padding(horizontal = 10.dp, vertical = 5.dp))
            }
        }
    }
}

/** Эталонный кадр поверх живого: заполняет кадр на экране (rect) по большей стороне, лишнее обрезано по краю кадра. */
fun DrawScope.drawReference(image: ImageBitmap, rect: Rect, alpha: Float) {
    if (image.width <= 0 || image.height <= 0 || rect.width <= 0f || rect.height <= 0f) return
    val k = maxOf(rect.width / image.width, rect.height / image.height)
    val w = image.width * k
    val h = image.height * k
    val left = rect.center.x - w / 2
    val top = rect.center.y - h / 2
    clipRect(rect.left, rect.top, rect.right, rect.bottom) {
        drawImage(image, dstOffset = IntOffset(left.roundToInt(), top.roundToInt()), dstSize = IntSize(w.roundToInt(), h.roundToInt()),
            alpha = alpha, filterQuality = FilterQuality.Low)
    }
}

/** Кнопка «Монитор» верхней панели: открывает и закрывает меню инструментов; горит, пока оно открыто. */
@Composable
fun MonitorButton(screen: ScreenState) {
    Box(
        Modifier.size(44.dp).background(if (screen.assistOpen) Accent else PanelLight, RoundedCornerShape(9.dp))
            .semantics { contentDescription = "Монитор: зебра, false color, пикинг, сетка и рамки" }
            .clickable { screen.toggleAssistMenu() },
        contentAlignment = Alignment.Center,
    ) {
        TopBarIcon(TopBarGlyph.monitor)
    }
}

/** Где растр помощи в кадре на экране: часть кадра region (доли) при кадре на экране rect. */
fun assistArea(overlay: CameraController.AssistOverlay, rect: Rect) = Rect(
    rect.left + overlay.left * rect.width, rect.top + overlay.top * rect.height,
    rect.left + (overlay.left + overlay.width) * rect.width, rect.top + (overlay.top + overlay.height) * rect.height,
)
