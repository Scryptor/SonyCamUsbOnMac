package local.zve10viewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.FocusDistanceScale
import local.zve10viewer.monitor.FocusDrive
import local.zve10viewer.monitor.FocusFlags
import local.zve10viewer.monitor.FocusFrame
import local.zve10viewer.monitor.FocusMarksText
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.get
import local.zve10viewer.monitor.TouchFocus
import local.zve10viewer.monitor.labelAtFraction
import local.zve10viewer.sony.LensInformation
import local.zve10viewer.view.OrientedFrame

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
 * экрана камеры: фокус или слежение, как настроено в камере). Не горит — касания фокус не трогают; рамки фокуса камеры
 * видны всегда, и начатое слежение продолжается.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TouchFocusButton(controller: CameraController, screen: ScreenState) {
    val available = controller.touchFocusMethod != null
    // пипетка отбирает касание у фокуса: кнопка остаётся включённой, но приглушена, и описание говорит почему
    val probeOn = controller.assist.probe
    // подпись под кнопкой — как «Зум» и режим съёмки: значок сам по себе не говорит, что это
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(58.dp).background(if (controller.touchFocusOn) Accent else Panel, CircleShape)
                .alpha(TouchFocus.buttonAlpha(available, probeOn))
                .semantics {
                    contentDescription = TouchFocus.buttonDescription(available, probeOn, controller.touchRefusal)
                }
                // включили — как это работает, пока не отметили «Больше не показывать»; долгое нажатие — подсказка всегда
                .combinedClickable(
                    onClick = {
                        controller.toggleTouchFocus()
                        val explain = controller.touchFocusOn && !controller.prefs.touchFocusHelpHidden
                        if (explain) screen.closeOverlays()
                        screen.touchFocusHelpOpen = explain
                    },
                    onLongClick = {
                        screen.closeOverlays()
                        screen.touchFocusHelpOpen = true
                    },
                ),
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
        Text("Фокус", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.clearAndSetSemantics { })
    }
}

/**
 * Подсказка «Фокус касанием»: что сделает касание сейчас и как это устроено — как у программы для Mac. Открывается при
 * включении кнопки (пока не отмечено «Больше не показывать») и по долгому нажатию на неё; закрывается «Понятно» или
 * касанием мимо.
 */
@Composable
fun TouchFocusHelpCard(controller: CameraController, screen: ScreenState, modifier: Modifier) {
    var hidden by remember { mutableStateOf(controller.prefs.touchFocusHelpHidden) }
    Column(
        modifier.width(440.dp).background(Color(0xF71C1C1E), RoundedCornerShape(14.dp))
            // касания по карточке не уходят к кадру и кнопкам под ней
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Фокус касанием", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        // пока касание отдано пипетке, строки «Сейчас: … касание двигает точку AF» нет: она была бы неправдой, и жёлтая под ней
        // противоречила бы ей
        val probeOn = controller.assist.probe
        Text(TouchFocus.nowLine(probeOn, controller.touchFocusNow), color = if (probeOn) Warning else Color.White, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, lineHeight = 20.sp)
        val note = TextStyle(color = Secondary, fontSize = 14.sp, lineHeight = 19.sp)
        Text("Пока кнопка горит синим, касание кадра ставит туда фокус. Как — решает зона фокуса камеры:", style = note)
        Text("• «Точка» и «Зона» — рамка AF переезжает в точку касания.", style = note)
        Text("• «Широкая» и другие — программа касается экрана камеры: камера фокусируется или следит за предметом, как выбрано " +
            "в её меню (Touch Function). Слежение снимает «Снять слежение» над строкой значений.", style = note)
        Text("• В ручном фокусе касание фокус не ставит — для него шкала фокуса внизу.", style = note)
        Text("Рамки фокуса самой камеры видны всегда, как на её экране: зелёная — фокус найден, пунктир — слежение.", style = note)
        Text("Выключили кнопку — касание кадра прячет и возвращает элементы и фокус не трогает, а начатое слежение продолжается, " +
            "и его рамка видна. Снять его — «Снять слежение». Эта подсказка — по долгому нажатию на кнопку.", style = note)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable {
                hidden = !hidden
                controller.prefs.touchFocusHelpHidden = hidden
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(hidden, onCheckedChange = {
                    hidden = it
                    controller.prefs.touchFocusHelpHidden = it
                })
                Text("Больше не показывать", color = Color.White, fontSize = 14.sp)
            }
            Text("Понятно", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(Accent, RoundedCornerShape(9.dp)).clickable { screen.touchFocusHelpOpen = false }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

/**
 * Рамки фокуса камеры (зелёные — фокус найден, слежение — пунктиром) и отметка касания: сжимается к точке и гаснет за
 * секунду. rect — где кадр на экране, с увеличением; progress — сколько прошло от касания, 0…1. И рамки, и отметка лежат в долях
 * кадра камеры, а кадр на экране может быть зеркальным или перевёрнутым ([orientation], настройка «Картинка»): они переезжают на то
 * место картинки, где камера их видит (`OrientedFrame.toScreen`).
 */
fun DrawScope.drawFocusFrames(frames: List<FocusFrame>, rect: Rect, mark: Offset?, progress: Float, orientation: FrameOrientation) {
    for (frame in frames) {
        val box = OrientedFrame.toScreen(frame.rect, rect, orientation)
        drawRect(Color.Black.copy(alpha = 0.35f), box.topLeft, box.size, style = Stroke(3.dp.toPx()))
        drawRect(if (frame.focused) Color(0xFF34C759) else Color.White, box.topLeft, box.size,
            style = Stroke(1.5.dp.toPx(), pathEffect = if (frame.kind == FocusFrame.Kind.tracking)
                PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())) else null))
    }
    if (mark != null && progress < 1f) {
        val side = (70 - 20 * minOf(progress / 0.25f, 1f)).dp.toPx()
        val c = OrientedFrame.toScreen(mark, rect, orientation)
        drawRect(Color(0xFFFFD60A).copy(alpha = (1 - maxOf(0f, progress - 0.5f) * 2).coerceIn(0f, 1f)),
            Offset(c.x - side / 2, c.y - side / 2), Size(side, side), style = Stroke(2.dp.toPx()))
    }
}

/** Цвета меток: на линейке, в кнопках и в карточке одни и те же, как у программы для Mac. */
private fun markColor(mark: Char): Color = if (mark == 'A') Color(0xFF59CCFF) else Color(0xFFFF9E33)

/**
 * Шкала фокуса — ручной фокус с экрана (в MF): слева ближе, справа дальше. Кнопки — шаги 7, 3 и 1; перетаскивание по
 * шкале — как поворот кольца фокуса. Метка — где фокус, если камера его отдаёт: по 0xE043, а если его нет, — по 0xD24C. Если
 * камера дала данные объектива (одну таблицу), под линейкой подписаны расстояния — на делениях и у метки положения. Под линейкой —
 * метки фокуса: `[A] [B] [⏱ 2 с]`; на самой линейке над ней — значки меток, посередине под ней — чем кончился перевод (3 с). Первый
 * раз за запуск, когда шкала с метками появилась, открывается карточка «Метки фокуса» (над шкалой, её рисует экран).
 */
@Composable
fun FocusScale(controller: CameraController, screen: ScreenState) {
    var width by remember { mutableFloatStateOf(1f) }
    var dragged by remember { mutableFloatStateOf(0f) }
    val marks = controller.focusMarksAvailable
    // данные объектива есть — под линейкой полоса подписей расстояний; нет (ZV-E10) — шкала как была
    val lens = controller.focusLens
    val measurer = rememberTextMeasurer(cacheSize = 32)
    LaunchedEffect(marks) { if (marks) screen.offerFocusMarksHelp(controller.prefs.focusMarksHelpShown) }
    // шкала ушла (ручной фокус кончился, элементы спрятаны) — карточка над ней не остаётся открытой
    DisposableEffect(Unit) { onDispose { screen.focusMarksHelpOpen = false } }
    Row(
        Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (step in listOf(FocusDrive.Step.large, FocusDrive.Step.medium, FocusDrive.Step.small)) FocusKey(controller, step, nearer = true)
        Column(Modifier.widthIn(min = 320.dp).width(360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Canvas(
                Modifier.fillMaxWidth().height(34.dp + (if (marks) FLAGS_HEIGHT else 0.dp) + (if (lens != null) DISTANCES_HEIGHT else 0.dp))
                    .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
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
                // над линейкой, пока метки есть, — полоса под значки меток; под ней, пока есть расстояния, — под подписи
                val flags = if (marks) FLAGS_HEIGHT.toPx() else 0f
                val labels = if (lens != null) DISTANCES_HEIGHT.toPx() else 0f
                val barHeight = size.height - flags - labels
                // деления — как на кольце фокуса; сдвигаются, пока шкалу тянут
                val spacing = 14.dp.toPx()
                var x = ((dragged % spacing) + spacing) % spacing
                val mid = flags + barHeight / 2
                while (x < size.width) {
                    drawLine(Color.White.copy(alpha = 0.4f), Offset(x, mid - 6.dp.toPx()), Offset(x, mid + 6.dp.toPx()), 1.dp.toPx())
                    x += spacing
                }
                // где фокус: по 0xE043, если камера его отдаёт (точнее, чем сотые доли 0xD24C), иначе по 0xD24C
                val fraction = controller.focusFraction
                if (fraction != null) {
                    val px = size.width * fraction.toFloat()
                    drawRoundRect(Color(0xFFFFD60A), Offset(px - 2.dp.toPx(), flags + 3.dp.toPx()), Size(4.dp.toPx(), barHeight - 6.dp.toPx()),
                        CornerRadius(2.dp.toPx()))
                }
                if (lens != null) drawDistances(lens, fraction, measurer, flags + barHeight)
                if (marks) drawFlags(controller, measurer, flags)
            }
            Row {
                Text("ближе", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
                // посередине: чем кончился перевод к метке (3 с), иначе — что это за шкала
                val caption = controller.focusPull.caption
                Text(caption ?: FocusMarksText.CAPTION_IDLE, color = if (caption == null) Color.White.copy(alpha = 0.5f) else Color.White,
                    fontSize = 13.sp, fontWeight = if (caption == null) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1,
                    softWrap = false, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Text("дальше", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp)
            }
            if (marks) MarksRow(controller, screen)
        }
        for (step in listOf(FocusDrive.Step.small, FocusDrive.Step.medium, FocusDrive.Step.large)) FocusKey(controller, step, nearer = false)
    }
}

/** Высота полосы над линейкой под значки меток, когда метки есть. */
private val FLAGS_HEIGHT = 18.dp

/** Высота полосы под линейкой под подписи расстояний (деление и строка 10 sp), когда данные объектива есть. */
private val DISTANCES_HEIGHT = 16.dp

/** Подписи расстояний: на делениях — белым вполсилы, у метки положения — жёлтым, плотнее. */
private val DISTANCE_TICK_STYLE = TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
private val DISTANCE_NOW_STYLE = TextStyle(color = Color(0xFFFFD60A), fontSize = 10.sp, fontWeight = FontWeight.SemiBold)

/**
 * Подписи расстояний под линейкой, от высоты [top]: на делениях — круглые расстояния и оба края таблицы, сколько помещается
 * ([FocusDistanceScale.ticks]), а у метки положения — текущее расстояние жёлтым (деления рядом с ним не подписываются, чтобы подписи
 * не налезали друг на друга). [fraction] — где метка положения, доля 0…1; null — положения нет, подписаны одни деления.
 */
private fun DrawScope.drawDistances(lens: LensInformation, fraction: Double?, measurer: TextMeasurer, top: Float) {
    val ruler = size.width.toDouble()
    val text = top + 3.dp.toPx()
    val reserved = ArrayList<ClosedFloatingPointRange<Double>>(1)
    val current = fraction?.let { f -> lens.labelAtFraction(f)?.let { f to it } }
    if (current != null) {
        val layout = measurer.measure(current.second, DISTANCE_NOW_STYLE)
        val span = FocusDistanceScale.span(current.first, layout.size.width.toDouble(), ruler)
        reserved += span
        drawText(layout, topLeft = Offset(span.start.toFloat(), text))
    }
    val ticks = FocusDistanceScale.ticks(lens, ruler, spacing = 12.dp.toPx().toDouble(), reserved = reserved) { label ->
        measurer.measure(label, DISTANCE_TICK_STYLE).size.width.toDouble()
    }
    for (tick in ticks) {
        val layout = measurer.measure(tick.label, DISTANCE_TICK_STYLE)
        val x = size.width * tick.fraction.toFloat()
        drawLine(Color.White.copy(alpha = 0.4f), Offset(x, top), Offset(x, top + 3.dp.toPx()), 1.dp.toPx())
        drawText(layout, topLeft = Offset(FocusDistanceScale.span(tick.fraction, layout.size.width.toDouble(), ruler).start.toFloat(), text))
    }
}

/** Плашка значка метки на линейке: ширина и высота (над остриём). */
private val FLAG_BADGE_WIDTH = 16.dp
private val FLAG_BADGE_HEIGHT = 14.dp

/**
 * Значки меток на линейке: буква на цветной плашке и остриё к месту на линейке. Рядом друг с другом плашки расходятся, чтобы не
 * закрывать буквы ([FocusFlags]); остриё остаётся там, где метка.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFlags(
    controller: CameraController, measurer: androidx.compose.ui.text.TextMeasurer, strip: Float,
) {
    val marks = listOf('A', 'B').mapNotNull { mark -> controller.focusMarkFraction(mark)?.let { mark to it } }
    val badgeWidth = FLAG_BADGE_WIDTH.toPx()
    val badgeHeight = FLAG_BADGE_HEIGHT.toPx()
    for (flag in FocusFlags.layout(marks, size.width, badgeWidth)) {
        val color = markColor(flag.mark)
        val half = 3.5.dp.toPx()
        val pointer = Path().apply {
            moveTo(flag.tip - half, badgeHeight - 1.dp.toPx())
            lineTo(flag.tip + half, badgeHeight - 1.dp.toPx())
            lineTo(flag.tip, strip)
            close()
        }
        drawPath(pointer, color)
        drawRoundRect(color, Offset(flag.center - badgeWidth / 2, 0f), Size(badgeWidth, badgeHeight), CornerRadius(3.dp.toPx()))
        val letter = measurer.measure(flag.mark.toString(), TextStyle(color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold))
        drawText(letter, topLeft = Offset(flag.center - letter.size.width / 2f, badgeHeight / 2 - letter.size.height / 2f))
    }
}

/** Строка под линейкой: метки A и B, справа — время перевода. */
@Composable
private fun MarksRow(controller: CameraController, screen: ScreenState) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MarkButton(controller, screen, 'A')
        MarkButton(controller, screen, 'B')
        Spacer(Modifier.weight(1f))
        TimeChip(controller, screen)
    }
}

/**
 * Кнопка метки. Пустая — «A+»: касание запоминает, где фокус. Заданная — «A»: касание переводит фокус к ней; если камера дала
 * данные объектива — «A 1,2 м», расстояние, на которое метка поставлена. Пока фокус едет, кнопка заливается по доле пути и
 * показывает значок «стоп» — касание её же останавливает; на конце мигает один раз. Долгое нажатие — меню «Запомнить здесь» /
 * «Стереть».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MarkButton(controller: CameraController, screen: ScreenState, mark: Char) {
    val marks = controller.focusMarks
    val isSet = marks[mark] != null
    val distance = if (isSet) controller.focusMarkDistance(mark) else null
    val moving = controller.focusPullMark == mark
    val color = markColor(mark)
    val shape = RoundedCornerShape(10.dp)
    var menu by remember { mutableStateOf(false) }
    // конец перевода: вспышка один раз на каждый конец (а не при каждом появлении кнопки на экране)
    val ends = controller.focusPull.ends[mark] ?: 0
    var seenEnds by remember { mutableIntStateOf(ends) }
    val flash = remember { Animatable(0f) }
    LaunchedEffect(ends) {
        if (ends != seenEnds) {
            seenEnds = ends
            flash.snapTo(0.7f)
            flash.animateTo(0f, tween(400, easing = LinearEasing))
        }
    }
    // меню открыто — элементы не прячутся вместе с ним
    LaunchedEffect(menu) {
        while (menu) {
            screen.noteActivity()
            delay(1000)
        }
    }
    Box {
        Row(
            // с данными объектива обе кнопки шире, пустая тоже: строка не прыгает, когда метку ставят и стирают; без них (ZV-E10) — как была
            Modifier.width(if (controller.focusLens == null) 64.dp else 84.dp).height(40.dp).clip(shape)
                .background(if (isSet) color.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.1f))
                // доля пути — заливкой слева направо; читается при рисовании, перекомпоновки на каждый тик не нужно
                .drawBehind {
                    if (moving) drawRect(color.copy(alpha = 0.6f), size = Size(size.width * (controller.focusPull.progress ?: 0.0).toFloat(), size.height))
                }
                .then(if (isSet) Modifier.border(1.dp, color.copy(alpha = 0.85f), shape) else Modifier)
                .drawWithContent {
                    drawContent()
                    if (flash.value > 0f) drawRect(Color.White.copy(alpha = flash.value))
                }
                .semantics {
                    contentDescription = FocusMarksText.buttonDescription(mark, isSet, moving, marks.duration, distance)
                }
                .combinedClickable(onClick = { controller.focusMarkTapped(mark) }, onLongClick = { menu = true }),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Text(FocusMarksText.buttonLabel(mark, isSet), color = Color.White.copy(alpha = if (isSet) 1f else 0.85f), fontSize = 16.sp,
                fontWeight = FontWeight.Bold, maxLines = 1)
            if (distance != null) {
                Text(" $distance", color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, maxLines = 1)
            }
            // едет — значок «стоп»: касание остановит
            if (moving) Box(Modifier.padding(start = 6.dp).size(9.dp).background(Color.White, RoundedCornerShape(2.dp)))
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text(FocusMarksText.SAVE_HERE) }, onClick = {
                menu = false
                controller.focusMarkSave(mark)
            })
            DropdownMenuItem(text = { Text(FocusMarksText.ERASE) }, enabled = isSet, onClick = {
                menu = false
                controller.focusMarkClear(mark)
            })
        }
    }
}

/** Время перевода по кругу: 0 → 1 → 2 → 4 → 8 с. Долгое нажатие — карточка «Метки фокуса». */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TimeChip(controller: CameraController, screen: ScreenState) {
    val seconds = controller.focusMarks.duration
    Row(
        Modifier.width(72.dp).height(40.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.1f))
            .semantics { contentDescription = FocusMarksText.timeDescription(seconds) }
            .combinedClickable(onClick = { controller.focusPullCycleTime() }, onLongClick = { screen.openFocusMarksHelp() }),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) {
        // секундомер: циферблат, стрелка и кнопка сверху
        Canvas(Modifier.size(14.dp)) {
            val unit = size.minDimension / 14f
            val stroke = 1.6f * unit
            drawCircle(Color.White.copy(alpha = 0.9f), 5f * unit, Offset(7f * unit, 8f * unit), style = Stroke(stroke))
            drawLine(Color.White.copy(alpha = 0.9f), Offset(7f * unit, 8f * unit), Offset(7f * unit, 5f * unit), stroke, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.9f), Offset(5.5f * unit, 1.5f * unit), Offset(8.5f * unit, 1.5f * unit), stroke, StrokeCap.Round)
        }
        Text(" " + FocusMarksText.time(seconds), color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

/**
 * Карточка «Метки фокуса»: что делают «A+» и «A», время перевода и долгое нажатие. Открывается сама, когда шкала с метками
 * появилась впервые за запуск (пока не отмечено «Больше не показывать»), и по долгому нажатию на кнопке времени; закрывается
 * «Понятно» или касанием мимо.
 */
@Composable
fun FocusMarksHelpCard(controller: CameraController, screen: ScreenState) {
    var hidden by remember { mutableStateOf(controller.prefs.focusMarksHelpShown) }
    Column(
        Modifier.width(440.dp).background(Color(0xF71C1C1E), RoundedCornerShape(14.dp))
            // касания по карточке не уходят к кадру и кнопкам под ней
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                for (mark in listOf('A', 'B')) {
                    Text(mark.toString(), color = Color.Black, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.width(22.dp).background(markColor(mark), RoundedCornerShape(5.dp)).padding(vertical = 2.dp))
                }
            }
            Text(FocusMarksText.HELP_TITLE, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(FocusMarksText.HELP_MAIN, color = Color.White, fontSize = 15.sp, lineHeight = 20.sp)
        Text(FocusMarksText.HELP_TIME, style = TextStyle(color = Secondary, fontSize = 14.sp, lineHeight = 19.sp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable {
                hidden = !hidden
                controller.prefs.focusMarksHelpShown = hidden
            }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(hidden, onCheckedChange = {
                    hidden = it
                    controller.prefs.focusMarksHelpShown = it
                })
                Text("Больше не показывать", color = Color.White, fontSize = 14.sp)
            }
            Text("Понятно", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.background(Accent, RoundedCornerShape(9.dp)).clickable { screen.focusMarksHelpOpen = false }
                    .padding(horizontal = 16.dp, vertical = 9.dp))
        }
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
