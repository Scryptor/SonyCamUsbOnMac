package local.zve10viewer.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import local.zve10viewer.CameraController
import local.zve10viewer.CameraController.FeedbackTarget
import local.zve10viewer.session.CaptureFeedback
import local.zve10viewer.session.RecordingToggle
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FnSlot
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingGroup
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.ShootingMode
import local.zve10viewer.settings.SettingOptions
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.settings.WhiteBalanceTemperature
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Меню Fn: лента значений над сеткой 2×6, как в самой камере. */
@Composable
fun FnMenu(controller: CameraController, screen: ScreenState) {
    Column(
        Modifier.widthIn(max = 1000.dp).pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ValueStrip(controller, screen.selected)
        val slots = SettingCatalog.fnTopRow + SettingCatalog.fnBottomRow(controller.mode)
        Column(Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            slots.chunked(6).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { slot -> Tile(controller, screen, slot, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(controller: CameraController, screen: ScreenState, slot: FnSlot, modifier: Modifier) {
    when (slot) {
        is FnSlot.Setting -> {
            val id = slot.id
            val enabled = controller.isWritable(id)
            val selected = id == screen.selected
            Column(
                modifier.heightIn(min = 72.dp)
                    .background(if (selected) Accent else PanelLight, RoundedCornerShape(12.dp))
                    .clickable(enabled = enabled) { screen.selected = id }
                    .alpha(if (enabled) 1f else 0.4f)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(id.shortTitle, color = if (selected) Color.White else Secondary, fontSize = 14.sp, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(controller.label(id), color = Color.White, fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                    if (id in controller.pending) {
                        CircularProgressIndicator(Modifier.padding(start = 6.dp).size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                }
            }
        }
        FnSlot.AllSettings -> Column(
            modifier.heightIn(min = 72.dp).background(PanelLight, RoundedCornerShape(12.dp))
                .clickable { screen.openAllSettings() }.padding(vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("Все", color = Secondary, fontSize = 14.sp)
            // высотой со строку значения у соседних плиток
            TopBarIcon(TopBarGlyph.settings, Modifier.size(width = 41.dp, height = 26.dp))
        }
    }
}

/** Лента значений выбранной настройки; у баланса белого рядом — тонкая настройка. */
@Composable
fun ValueStrip(controller: CameraController, id: SettingID) {
    val options = controller.stripOptions(id)
    val current = controller.stripCurrent(id)
    Row(
        Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(16.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (options.isEmpty() || !controller.isWritable(id)) {
            Text("Камера сейчас не даёт менять «${id.title}»", color = Secondary, fontSize = 16.sp,
                modifier = Modifier.weight(1f).padding(16.dp))
        } else {
            val state = rememberLazyListState()
            val currentIndex = options.indexOfFirst { it.value == current }
            LaunchedEffect(id, currentIndex) {
                if (currentIndex >= 0) state.animateScrollToItem(maxOf(0, currentIndex - 3))
            }
            LazyRow(Modifier.weight(1f), state = state, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(options) { _, option ->
                    val isCurrent = option.value == current
                    Text(option.label, color = if (isCurrent) Color.Black else Color.White, fontSize = 18.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                        modifier = Modifier.background(if (isCurrent) Color.White else Color.Transparent, RoundedCornerShape(9.dp))
                            .clickable { controller.choose(id, option.value) }
                            .padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
        if (id == SettingID.whiteBalance) {
            Box(Modifier.padding(horizontal = 12.dp).width(1.dp).height(80.dp).background(PanelLight))
            WhiteBalanceFineTune(controller)
        }
    }
}

/**
 * Цветовая температура и сдвиг A–B / G–M. Ползунок температуры работает в любом режиме:
 * сдвинули — камера переходит на ручную температуру.
 */
@Composable
private fun WhiteBalanceFineTune(controller: CameraController) {
    val temperature = controller.property(SettingID.colorTemperature)
    val ab = controller.property(SettingID.wbShiftAB)
    val gm = controller.property(SettingID.wbShiftGM)
    val reading = WhiteBalanceTemperature.reading(controller.property(SettingID.whiteBalance)?.current?.intValue, temperature?.current?.intValue)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val range = temperature?.range?.takeIf { it.max > it.min } ?: WhiteBalanceTemperature.fallbackRange
        val position = WhiteBalanceTemperature.sliderValue(reading, temperature?.current?.intValue).toFloat()
        Column(Modifier.width(220.dp)) {
            Text("Цвет. темп.: ${WhiteBalanceTemperature.caption(reading)}", color = Secondary, fontSize = 14.sp)
            var kelvin by remember { mutableFloatStateOf(position) }
            LaunchedEffect(position) { kelvin = position }
            Slider(
                kelvin, { kelvin = it }, valueRange = range.min.toFloat()..range.max.toFloat(),
                steps = ((range.max - range.min) / range.step - 1).toInt().coerceAtLeast(0),
                enabled = controller.isWritable(SettingID.whiteBalance) || controller.isWritable(SettingID.colorTemperature),
                onValueChangeFinished = {
                    // касание ползунка без сдвига режим не меняет
                    val k = (kelvin / range.step).roundToLong() * range.step
                    if (k != position.toLong()) controller.setColorTemperature(k)
                },
            )
            if (reading !is WhiteBalanceTemperature.Reading.Manual) {
                Text(WhiteBalanceTemperature.explanation(reading), color = Secondary, fontSize = 12.sp, lineHeight = 15.sp)
            }
        }
        if (ab != null && gm != null) ShiftPad(controller)
    }
}

/** Квадрат сдвига: по горизонтали B слева, A справа; по вертикали G сверху, M снизу. */
@Composable
private fun ShiftPad(controller: CameraController) {
    val ab = controller.property(SettingID.wbShiftAB)!!
    val gm = controller.property(SettingID.wbShiftGM)!!
    val enabled = controller.isWritable(SettingID.wbShiftAB) && controller.isWritable(SettingID.wbShiftGM)
    val side = 110.dp
    var drag by remember { mutableStateOf<Offset?>(null) }
    var start by remember { mutableStateOf(Offset.Zero) }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("A–B ${ValueFormatter.label(SettingID.wbShiftAB, ab)}  G–M ${ValueFormatter.label(SettingID.wbShiftGM, gm)}",
            color = Secondary, fontSize = 13.sp)
        Canvas(
            Modifier.padding(vertical = 4.dp).size(side).clip(RoundedCornerShape(8.dp)).alpha(if (enabled) 1f else 0.4f)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start = it; drag = it },
                        onDrag = { change, _ -> drag = Offset(change.position.x.coerceIn(0f, size.width.toFloat()),
                            change.position.y.coerceIn(0f, size.height.toFloat())) },
                        onDragEnd = {
                            val end = drag
                            if (end != null) {
                                val dx = end.x - start.x
                                val dy = end.y - start.y
                                // пишем только ось, вдоль которой тянули: иначе почти любое движение задевает и вторую
                                val x = abs(dx); val y = abs(dy)
                                val writeAb = maxOf(x, y) >= 4 && x >= y / 2
                                val writeGm = maxOf(x, y) >= 4 && y >= x / 2
                                if (writeAb) request(controller, SettingID.wbShiftAB, end.x / size.width)
                                if (writeGm) request(controller, SettingID.wbShiftGM, 1 - end.y / size.height)
                            }
                            drag = null
                        },
                        onDragCancel = { drag = null },
                    )
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color(0x990A84FF), Color(0x99FF9F0A))))
            drawRect(Brush.verticalGradient(listOf(Color(0x7334C759), Color(0x73FF2D55))))
            val point = drag ?: Offset(fraction(ab) * size.width, (1 - fraction(gm)) * size.height)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = point)
        }
        Text("Сброс", color = Secondary, fontSize = 14.sp, modifier = Modifier.clickable(enabled = enabled) {
            if (ab.current.intValue != 192L) controller.request(SettingID.wbShiftAB, 192)
            if (gm.current.intValue != 192L) controller.request(SettingID.wbShiftGM, 192)
        }.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

/** Положение значения на оси: 0 — минимум диапазона, 1 — максимум. */
private fun fraction(property: local.zve10viewer.sony.SonyProperty): Float {
    val range = property.range ?: return 0.5f
    val value = property.current.intValue ?: return 0.5f
    if (range.max <= range.min) return 0.5f
    return ((value - range.min).toFloat() / (range.max - range.min)).coerceIn(0f, 1f)
}

private fun request(controller: CameraController, id: SettingID, at: Float) {
    val property = controller.property(id) ?: return
    val range = property.range ?: return
    val raw = range.min + (range.max - range.min) * at.coerceIn(0f, 1f)
    val value = range.min + ((raw - range.min) / range.step).roundToLong() * range.step
    if (value != property.current.intValue) controller.request(id, value)
}

/** Все настройки по группам поверх видео. Касание изменяемой строки открывает ленту значений. */
@Composable
fun AllSettingsPanel(controller: CameraController, screen: ScreenState, modifier: Modifier) {
    Row(modifier.background(Color(0xF22C2C2E), RoundedCornerShape(18.dp)).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.width(230.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Все настройки", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 10.dp))
            SettingGroup.entries.forEach { g ->
                Text(g.title, color = Color.White, fontSize = 17.sp, modifier = Modifier.fillMaxWidth()
                    .background(if (g == screen.group) Accent else Color.Transparent, RoundedCornerShape(10.dp))
                    .clickable { screen.openAllSettings(g) }.padding(horizontal = 12.dp, vertical = 12.dp))
            }
        }
        Box(Modifier.width(1.dp).fillMaxSize().background(PanelLight))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
            screen.group.settings.forEach { id ->
                val writable = controller.isWritable(id)
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = writable) {
                        screen.editing = if (screen.editing == id) null else id
                    }.padding(horizontal = 8.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(id.title, color = if (writable) Color.White else Secondary, fontSize = 17.sp, modifier = Modifier.weight(1f))
                    if (id in controller.pending) {
                        CircularProgressIndicator(Modifier.padding(end = 8.dp).size(14.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    Text(controller.label(id), color = if (writable) Color.White else Secondary,
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                }
                if (screen.editing == id) ValueStrip(controller, id)
                HorizontalDivider(color = PanelLight)
            }
        }
    }
}

/** Справа по центру: «Фото / Видео» и под ним спуск (фото) или стоп-кадр и запись (видео). */
@Composable
fun RightColumn(controller: CameraController, feedback: FeedbackState, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModeSwitch(controller)
        if (controller.mode == CaptureMode.photo) {
            ShutterButton(controller, feedback)
        } else {
            GrabButton(controller, feedback)
            RecordButton(controller, feedback)
        }
    }
}

/** «Фото / Видео / S&Q» — значки в капсуле, как кнопки верхней панели: у выбранного белый круг, под капсулой — его название. */
@Composable
private fun ModeSwitch(controller: CameraController) {
    val enabled = controller.isWritable(SettingID.exposureProgram) && !controller.isRecording
    Column(Modifier.alpha(if (enabled) 1f else 0.5f), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.background(Panel, RoundedCornerShape(percent = 50)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ShootingMode.entries.forEach { mode ->
                val active = controller.shootingMode == mode
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(if (active) Color.White else Color.Transparent)
                        .semantics { contentDescription = mode.title }
                        .clickable(enabled = enabled) { controller.switchMode(mode) },
                    contentAlignment = Alignment.Center,
                ) {
                    ModeGlyph(mode, if (active) Color.Black else Color.White)
                }
            }
        }
        Text(controller.shootingMode.title, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
    }
}

/**
 * Значок режима съёмки в квадрате 52×52, как у верхней панели (масштаб 1,12): фото — корпус камеры с объективом, видео —
 * камера с треугольником-объективом, S&Q — буквами: своего значка у камеры для него нет.
 */
@Composable
private fun ModeGlyph(mode: ShootingMode, color: Color) {
    if (mode == ShootingMode.slowQuick) {
        Text("S&Q", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        return
    }
    Canvas(Modifier.size(48.dp)) {
        val k = size.minDimension / 52f * 1.12f
        val dx = (size.width - 52f * k) / 2
        val dy = (size.height - 52f * k) / 2
        fun p(x: Float, y: Float) = Offset(dx + x * k, dy + y * k)
        val line = Stroke(maxOf(2f * k, 1.5.dp.toPx()), cap = StrokeCap.Round, join = StrokeJoin.Round)
        if (mode == ShootingMode.photo) {
            drawRoundRect(color, p(14f, 20f), Size(24f * k, 15f * k), CornerRadius(3f * k), style = line)
            // видоискатель — «горб» над корпусом
            drawRoundRect(color, p(19.5f, 16.5f), Size(6.5f * k, 4f * k), CornerRadius(1.2f * k))
            drawCircle(color, 4.8f * k, p(26f, 27.5f), style = line)
        } else {
            drawRoundRect(color, p(13f, 19.5f), Size(18f * k, 13f * k), CornerRadius(3f * k), style = line)
            val lens = Path().apply {
                p(31.5f, 24.5f).let { moveTo(it.x, it.y) }
                p(38.5f, 20.5f).let { lineTo(it.x, it.y) }
                p(38.5f, 31.5f).let { lineTo(it.x, it.y) }
                p(31.5f, 27.5f).let { lineTo(it.x, it.y) }
                close()
            }
            drawPath(lens, color, style = line)
        }
    }
}

/** Спуск: пока камера снимает — дуга вместо кольца (доля загрузки или крутится) и подпись слева; кнопка неактивна. */
@Composable
private fun ShutterButton(controller: CameraController, feedback: FeedbackState) {
    val busy = controller.isCapturing
    val phase = controller.capturePhase
    // пользователь попросил кнопки съёмки поменьше: было 84 dp
    CaptureButton(72.dp, FeedbackTarget.shutter, feedback, enabled = controller.phase == CameraController.Phase.ready && !busy,
        waveColor = Color.White, caption = phase?.let(CaptureFeedback::caption), onClick = { controller.capturePhoto() }) {
        if (busy) {
            PendingArc(phase?.let(CaptureFeedback::fraction), Color.White, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        }
        Box(Modifier.size(54.dp).background(Color.White.copy(alpha = if (busy) 0.5f else 1f), CircleShape))
    }
}

/** Стоп-кадр — мгновенный, без ожидания: волна, вспышка и полёт кадра в ленту. */
@Composable
private fun GrabButton(controller: CameraController, feedback: FeedbackState) {
    CaptureButton(58.dp, FeedbackTarget.grab, feedback, enabled = controller.frame != null, waveColor = Color.White, caption = null,
        onClick = { controller.grabFrame() }) {
        Box(Modifier.fillMaxSize().background(Panel, CircleShape))
        // рамка видоискателя с объективом — «снимок кадра»
        Canvas(Modifier.size(26.dp)) {
            val c = size.width * 0.28f
            val stroke = Stroke(width = 2.5.dp.toPx())
            val w = size.width
            listOf(
                Offset(0f, c) to Offset(0f, 0f), Offset(0f, 0f) to Offset(c, 0f),
                Offset(w - c, 0f) to Offset(w, 0f), Offset(w, 0f) to Offset(w, c),
                Offset(0f, w - c) to Offset(0f, w), Offset(0f, w) to Offset(c, w),
                Offset(w - c, w) to Offset(w, w), Offset(w, w) to Offset(w, w - c),
            ).forEach { (a, b) -> drawLine(Color.White, a, b, strokeWidth = stroke.width) }
            drawCircle(Color.White, radius = w * 0.18f, style = stroke)
        }
    }
}

/**
 * Запись: пока камера не ответила — крутящаяся дуга (красная — запуск, белая — остановка) и подпись слева. Круг
 * становится квадратом (и обратно) за 250 мс, только когда камера подтвердила запись свойством.
 */
@Composable
private fun RecordButton(controller: CameraController, feedback: FeedbackState) {
    val ready = controller.phase == CameraController.Phase.ready
    val request = controller.recordingRequest
    val morph by animateFloatAsState(if (controller.isRecording) 1f else 0f, tween(250), label = "record")
    CaptureButton(72.dp, FeedbackTarget.record, feedback, enabled = ready && request == null, waveColor = Color.Red,
        caption = when (request) {
            RecordingToggle.Request.starting -> "Запуск записи…"
            RecordingToggle.Request.stopping -> "Остановка…"
            null -> null
        },
        onClick = { controller.toggleRecording() }, modifier = Modifier.alpha(if (ready) 1f else 0.4f)) {
        if (request != null) {
            PendingArc(null, if (request == RecordingToggle.Request.starting) Color.Red else Color.White, Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape))
        }
        Box(Modifier.size(lerp(52.dp, 28.dp, morph)).background(Color.Red, RoundedCornerShape(lerp(26.dp, 6.dp, morph))))
    }
}

/**
 * Последние снимки и стоп-кадры: касание — галерея на этом снимке, «+N» — на седьмом; долгое касание — открыть файл в
 * другой программе. Новая миниатюра появляется с «пружинкой» и белой рамкой; к миниатюре стоп-кадра сначала долетает
 * сам кадр.
 */
@Composable
fun ShotsStrip(controller: CameraController, feedback: FeedbackState, onOpen: (CameraController.Shot) -> Unit) {
    // лента на экране — стоп-кадр летит в неё; спрятали — в угол
    DisposableEffect(Unit) {
        feedback.stripVisible = true
        onDispose {
            feedback.stripVisible = false
            feedback.stripTarget = null
        }
    }
    Row(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        controller.shots.take(6).forEachIndexed { index, shot ->
            key(shot.id) {
                ShotThumbnail(controller, shot, feedback, first = index == 0, onClick = { onOpen(shot) })
            }
        }
        if (controller.shots.size > 6) {
            Text("+${controller.shots.size - 6}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.size(width = 52.dp, height = 64.dp).clip(RoundedCornerShape(8.dp)).background(PanelLight)
                    .clickable { controller.shots.getOrNull(6)?.let(onOpen) }.wrapContentHeight())
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShotThumbnail(controller: CameraController, shot: CameraController.Shot, feedback: FeedbackState, first: Boolean,
                          onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val scale = remember { Animatable(1f) }
    val ring = remember { Animatable(0f) }
    val fresh = feedback.fresh
    LaunchedEffect(fresh) {
        if (fresh?.first != shot.id) return@LaunchedEffect
        // новая: 0,6 → 1 пружиной, белая рамка гаснет за 600 мс
        launch {
            scale.snapTo(0.6f)
            scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
        }
        ring.snapTo(1f)
        ring.animateTo(0f, tween(600))
    }
    val hidden = feedback.hiddenShot == shot.id
    Box {
        val thumbnail = shot.thumbnail
        Box(Modifier.size(64.dp)
            // сюда летит стоп-кадр
            .then(if (first) Modifier.onGloballyPositioned { feedback.stripTarget = it.boundsInRoot() } else Modifier)
            .graphicsLayer {
                alpha = if (hidden) 0f else 1f
                scaleX = scale.value
                scaleY = scale.value
            }
            .clip(RoundedCornerShape(8.dp)).background(PanelLight)
            .drawWithContent {
                drawContent()
                val a = ring.value
                if (a > 0f) {
                    val width = 2.dp.toPx()
                    drawRoundRect(Color.White.copy(alpha = a), Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
                        CornerRadius(7.dp.toPx()), style = Stroke(width))
                }
            }
            .combinedClickable(onClick = onClick, onLongClick = { menu = true })) {
            if (thumbnail != null) Image(thumbnail, shot.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        DropdownMenu(menu, { menu = false }) {
            shot.files.forEach { file ->
                DropdownMenuItem(text = { Text("Открыть ${file.name}") }, onClick = {
                    controller.openExternally(file)
                    menu = false
                })
            }
        }
    }
}

/** Просмотр снимка поверх видео: по таймеру после съёмки или до закрытия, если открыт из ленты. */
@Composable
fun ReviewOverlay(controller: CameraController, review: CameraController.Review) {
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) { detectTapGestures { controller.dismissReview() } }) {
        // скачанный снимок сменяет кадр с камеры плавно
        Crossfade(review.image, animationSpec = tween(250), label = "review") { image ->
            if (image != null) {
                Image(image, review.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize()) { CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White) }
            }
        }
        Text("${review.source} · нажмите, чтобы вернуться", color = Color.White, fontSize = 16.sp,
            modifier = Modifier.align(Alignment.TopCenter).padding(18.dp).background(Panel, RoundedCornerShape(10.dp))
                .padding(horizontal = 14.dp, vertical = 8.dp))
        Row(
            Modifier.align(Alignment.BottomCenter).padding(18.dp).background(Panel, RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(review.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            // снимок ещё качается — та же подпись, что у кнопки спуска
            val detail = if (review.live) controller.capturePhase?.let(CaptureFeedback::caption) ?: review.detail else review.detail
            if (detail.isNotEmpty()) Text(detail, color = Secondary, fontSize = 16.sp, style = TextStyle(fontFeatureSettings = "tnum"))
            review.files.firstOrNull()?.let { file ->
                Text("Открыть", color = Color.White, fontSize = 16.sp, modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp))
                    .clickable { controller.openExternally(review.files.firstOrNull { it.mime == "image/jpeg" } ?: file) }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        review.endsAt?.let { endsAt ->
            Countdown(review.shownAt, endsAt, Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
        }
    }
}

/** Сколько ещё показывать снимок: кольцо плавно убывает от полного до пустого, в середине — целые секунды. */
@Composable
private fun Countdown(startsAt: Long, endsAt: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (now < endsAt) withFrameMillis { now = System.currentTimeMillis() }
    }
    val total = (endsAt - startsAt).coerceAtLeast(1L)
    val seconds by remember(endsAt) { derivedStateOf { (maxOf(0L, endsAt - now) + 999) / 1000 } }
    Box(
        modifier.size(76.dp).background(Panel, CircleShape).drawBehind {
            val width = 4.dp.toPx()
            val left = (endsAt - now).coerceIn(0L, total).toFloat() / total
            drawCircle(Color.White.copy(alpha = 0.25f), size.minDimension / 2 - width / 2, style = Stroke(width))
            drawArc(Color.White, -90f, 360f * left, false, Offset(width / 2, width / 2), Size(size.width - width, size.height - width),
                style = Stroke(width, cap = StrokeCap.Round))
        },
        contentAlignment = Alignment.Center,
    ) {
        Text("$seconds", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}
