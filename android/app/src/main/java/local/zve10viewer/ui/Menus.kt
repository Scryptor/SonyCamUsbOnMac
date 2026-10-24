package local.zve10viewer.ui

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import kotlinx.coroutines.delay
import local.zve10viewer.CameraController
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.FnSlot
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingGroup
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOptions
import local.zve10viewer.settings.ValueFormatter
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
                    Text(ValueFormatter.label(id, controller.property(id)), color = Color.White, fontSize = 19.sp,
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
            Text("⚙", color = Color.White, fontSize = 19.sp)
        }
    }
}

/** Лента значений выбранной настройки; у баланса белого рядом — тонкая настройка. */
@Composable
fun ValueStrip(controller: CameraController, id: SettingID) {
    val property = controller.property(id)
    val options = property?.let { SettingOptions.options(id, it) } ?: emptyList()
    val current = property?.current?.intValue
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
                            .clickable { controller.request(id, option.value) }
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

/** Цветовая температура (только в режиме K) и сдвиг A–B / G–M. */
@Composable
private fun WhiteBalanceFineTune(controller: CameraController) {
    val temperature = controller.property(SettingID.colorTemperature)
    val ab = controller.property(SettingID.wbShiftAB)
    val gm = controller.property(SettingID.wbShiftGM)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        val range = temperature?.range
        Column(Modifier.width(200.dp)) {
            Text("Цвет. темп.: ${ValueFormatter.label(SettingID.colorTemperature, temperature)}", color = Secondary, fontSize = 14.sp)
            if (range != null && range.max > range.min) {
                var kelvin by remember { mutableFloatStateOf((temperature.current.intValue ?: range.min).toFloat()) }
                LaunchedEffect(temperature.current.intValue) { temperature.current.intValue?.let { kelvin = it.toFloat() } }
                Slider(
                    kelvin, { kelvin = it }, valueRange = range.min.toFloat()..range.max.toFloat(),
                    steps = ((range.max - range.min) / range.step - 1).toInt().coerceAtLeast(0),
                    enabled = controller.isWritable(SettingID.colorTemperature),
                    onValueChangeFinished = { controller.request(SettingID.colorTemperature, (kelvin / range.step).roundToLong() * range.step) },
                )
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
                    Text(ValueFormatter.label(id, controller.property(id)), color = if (writable) Color.White else Secondary,
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
fun RightColumn(controller: CameraController, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        ModeSwitch(controller)
        if (controller.mode == CaptureMode.photo) {
            ShutterButton(controller)
        } else {
            GrabButton(controller)
            RecordButton(controller)
        }
    }
}

@Composable
private fun ModeSwitch(controller: CameraController) {
    val enabled = controller.isWritable(SettingID.exposureProgram) && !controller.isRecording
    Column(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(4.dp).alpha(if (enabled) 1f else 0.5f)) {
        listOf(CaptureMode.photo to "Фото", CaptureMode.video to "Видео").forEach { (mode, title) ->
            val active = controller.mode == mode
            Text(title, color = if (active) Color.Black else Color.White, fontSize = 17.sp,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.width(88.dp).background(if (active) Color.White else Color.Transparent, RoundedCornerShape(9.dp))
                    .clickable(enabled = enabled) { controller.switchMode(mode) }.padding(vertical = 10.dp))
        }
    }
}

@Composable
private fun ShutterButton(controller: CameraController) {
    val busy = controller.isCapturing
    Box(
        Modifier.size(84.dp).border(5.dp, Color.White.copy(alpha = 0.9f), CircleShape)
            .clickable(enabled = controller.phase == CameraController.Phase.ready && !busy) { controller.capturePhoto() },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(64.dp).background(if (busy) Color.Gray else Color.White, CircleShape))
        if (busy) CircularProgressIndicator(Modifier.size(24.dp), color = Color.Black, strokeWidth = 3.dp)
    }
}

@Composable
private fun GrabButton(controller: CameraController) {
    Box(
        Modifier.size(58.dp).background(Panel, CircleShape).clickable(enabled = controller.frame != null) { controller.grabFrame() },
        contentAlignment = Alignment.Center,
    ) {
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

@Composable
private fun RecordButton(controller: CameraController) {
    val ready = controller.phase == CameraController.Phase.ready
    Box(
        Modifier.size(84.dp).border(5.dp, Color.White.copy(alpha = 0.9f), CircleShape).alpha(if (ready) 1f else 0.4f)
            .clickable(enabled = ready) { controller.toggleRecording() },
        contentAlignment = Alignment.Center,
    ) {
        if (controller.isRecording) {
            Box(Modifier.size(32.dp).background(Color.Red, RoundedCornerShape(7.dp)))
        } else {
            Box(Modifier.size(60.dp).background(Color.Red, CircleShape))
        }
    }
}

/** Последние снимки и стоп-кадры: касание — открыть поверх видео, долгое касание — открыть в галерее. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShotsStrip(controller: CameraController) {
    Row(Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        controller.shots.take(6).forEachIndexed { index, shot ->
            var menu by remember { mutableStateOf(false) }
            Box {
                val thumbnail = shot.thumbnail
                Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(PanelLight)
                    .combinedClickable(onClick = { controller.openShot(index) }, onLongClick = { menu = true })) {
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
        if (controller.shots.size > 6) Text("+${controller.shots.size - 6}", color = Secondary, fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp))
    }
}

/** Просмотр снимка поверх видео: по таймеру после съёмки или до закрытия, если открыт из ленты. */
@Composable
fun ReviewOverlay(controller: CameraController, review: CameraController.Review) {
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(review) { detectTapGestures { controller.dismissReview() } }) {
        val image = review.image
        if (image != null) {
            Image(image, review.title, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        } else {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
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
            if (review.detail.isNotEmpty()) Text(review.detail, color = Secondary, fontSize = 16.sp)
            review.files.firstOrNull()?.let { file ->
                Text("Открыть", color = Color.White, fontSize = 16.sp, modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp))
                    .clickable { controller.openExternally(review.files.firstOrNull { it.mime == "image/jpeg" } ?: file) }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
            }
        }
        review.endsAt?.let { endsAt ->
            Countdown(endsAt, Modifier.align(Alignment.CenterEnd).padding(end = 24.dp))
        }
    }
}

@Composable
private fun Countdown(endsAt: Long, modifier: Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(endsAt) {
        while (now < endsAt) {
            delay(250)
            now = System.currentTimeMillis()
        }
    }
    val left = maxOf(0L, endsAt - now)
    Box(modifier.size(76.dp).border(4.dp, Color.White.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
        Text("${(left + 999) / 1000}", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
    }
}
