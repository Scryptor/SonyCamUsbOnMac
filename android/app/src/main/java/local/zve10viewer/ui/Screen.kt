package local.zve10viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.zve10viewer.CameraController
import local.zve10viewer.Prefs
import local.zve10viewer.settings.CaptureMode
import local.zve10viewer.settings.SettingCatalog
import local.zve10viewer.settings.SettingGroup
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.sony.SonyProp
import kotlin.math.sqrt

val Panel = Color(0xD91C1C1E)
val PanelLight = Color(0x1FFFFFFF)
val Secondary = Color(0xFF9A9AA0)
val Accent = Color(0xFF0A84FF)

/** Что открыто на экране. Меняется касаниями. */
class ScreenState {
    var fnOpen by mutableStateOf(false)
    var allSettingsOpen by mutableStateOf(false)
    var settingsOpen by mutableStateOf(false)
    var selected by mutableStateOf(SettingID.shutter)
    var group by mutableStateOf(SettingGroup.exposure)
    var editing by mutableStateOf<SettingID?>(null)
    /** Спрятано касанием — возвращается касанием. */
    var manualHidden by mutableStateOf(false)
    /** Спрятано само через 3 с без касаний — возвращается любым касанием. */
    var autoHidden by mutableStateOf(false)
    var lastActivity by mutableLongStateOf(System.currentTimeMillis())
    /** Касание началось, когда элементы были спрятаны: оно только показывает их. */
    var revealTap = false

    val controlsHidden: Boolean get() = manualHidden || autoHidden
    val overlayOpen: Boolean get() = fnOpen || allSettingsOpen || settingsOpen

    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
        if (autoHidden) autoHidden = false
    }

    fun openFn(id: SettingID?) {
        if (id != null) selected = id
        allSettingsOpen = false
        settingsOpen = false
        fnOpen = true
    }

    fun openAllSettings(group: SettingGroup? = null) {
        if (group != null) {
            this.group = group
            editing = null
        }
        fnOpen = false
        settingsOpen = false
        allSettingsOpen = true
    }

    fun closeOverlays() {
        fnOpen = false
        allSettingsOpen = false
        settingsOpen = false
        editing = null
    }
}

@Composable
fun App(controller: CameraController) {
    val screen = remember { ScreenState() }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.White, secondary = Accent)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (controller.phase) {
                CameraController.Phase.searching, CameraController.Phase.permission -> WaitingScreen(controller, notPcRemote = false)
                CameraController.Phase.notPcRemote -> WaitingScreen(controller, notPcRemote = true)
                else -> LiveScreen(controller, screen)
            }
        }
    }
}

@Composable
private fun WaitingScreen(controller: CameraController, notPcRemote: Boolean) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
        Text(
            if (notPcRemote) "Камера подключена, но не в режиме PC Remote" else "Подключите камеру",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(20.dp))
        listOf(
            "1. На камере: MENU → Network → Smartphone Connect → Smartphone Connection: Off",
            "2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: USB",
            if (notPcRemote) "Переподключите кабель" else "3. Подключите камеру к планшету кабелем USB-C",
        ).forEach { Text(it, color = Secondary, fontSize = 20.sp, modifier = Modifier.padding(vertical = 5.dp)) }
        Spacer(Modifier.height(16.dp))
        val status = when {
            controller.phase == CameraController.Phase.permission -> "Разрешите доступ к камере в окне Android…"
            controller.message != null -> controller.message
            notPcRemote -> null
            else -> "Ищу камеру…"
        }
        if (status != null) Text(status, color = Secondary, fontSize = 18.sp)
    }
}

@Composable
private fun LiveScreen(controller: CameraController, screen: ScreenState) {
    var hint by remember { mutableStateOf(false) }
    // автоскрытие: 3 с без касаний, если не открыто меню, панель или просмотр снимка
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val busy = screen.overlayOpen || controller.review != null
            if (busy || !controller.prefs.autoHide) {
                screen.autoHidden = false
            } else if (!screen.autoHidden && System.currentTimeMillis() - screen.lastActivity > 3000) {
                screen.autoHidden = true
            }
        }
    }
    LaunchedEffect(hint) {
        if (hint) {
            delay(2000)
            hint = false
        }
    }
    val showControls = !screen.controlsHidden || screen.overlayOpen
    Box(
        Modifier.fillMaxSize()
            // любое касание — «активность», элементы не прячутся; само касание не перехватываем
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) screen.revealTap = screen.controlsHidden
                        screen.noteActivity()
                    }
                }
            },
    ) {
        Video(controller, Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures {
                when {
                    screen.overlayOpen -> screen.closeOverlays()
                    screen.revealTap || screen.manualHidden -> {
                        screen.revealTap = false
                        screen.manualHidden = false
                    }
                    else -> {
                        screen.manualHidden = true
                        hint = true
                    }
                }
            }
        })
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            Controls(controller, screen)
        }
        if (!showControls) {
            controller.recordingStartedAt?.let { started ->
                RecBadge(started, Modifier.align(Alignment.TopEnd).padding(20.dp)
                    .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (screen.allSettingsOpen) {
            AllSettingsPanel(controller, screen, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.78f))
        }
        if (screen.settingsOpen) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { screen.settingsOpen = false } },
                contentAlignment = Alignment.Center,
            ) {
                SettingsPanel(controller, Modifier.width(560.dp).fillMaxHeight(0.9f))
            }
        }
        controller.review?.let { ReviewOverlay(controller, it) }
        if (hint) {
            Text("Элементы скрыты · нажмите, чтобы показать", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                    .background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

@Composable
private fun Video(controller: CameraController, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        val frame = controller.frame
        if (frame != null) {
            Image(frame, null, contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().alpha(if (controller.isFrameStale) 0.45f else 1f))
            if (controller.isFrameStale) {
                Text("Нет картинки с камеры…", color = Color.White, fontSize = 18.sp,
                    modifier = Modifier.background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 16.dp, vertical = 10.dp))
            }
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

@Composable
private fun Controls(controller: CameraController, screen: ScreenState) {
    Box(Modifier.fillMaxSize().padding(18.dp)) {
        Column(Modifier.align(Alignment.TopStart)) {
            TopBar(controller, screen)
            controller.statusMessage?.let {
                Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(top = 8.dp)
                    .background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        StatusPill(controller, Modifier.align(Alignment.TopEnd))
        RightColumn(controller, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        if (!screen.fnOpen) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val bins = controller.histogram
                if (controller.histogramOn && bins != null) HistogramView(bins)
                if (controller.shots.isNotEmpty()) ShotsStrip(controller)
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(end = if (screen.fnOpen) 110.dp else 0.dp)) {
            if (screen.fnOpen) {
                FnMenu(controller, screen)
            } else if (!screen.allSettingsOpen) {
                InfoLine(controller) { screen.openFn(it) }
            }
        }
    }
}

@Composable
private fun StatusPill(controller: CameraController, modifier: Modifier) {
    Row(
        modifier.background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.recordingStartedAt?.let { RecBadge(it) }
        val ready = controller.phase == CameraController.Phase.ready
        Box(Modifier.size(10.dp).background(if (ready) Color(0xFF34C759) else Color(0xFFFF9F0A), CircleShape))
        PillText("${controller.deviceInfo?.model ?: "Камера"} · USB")
        PillText(ValueFormatter.label(SettingID.exposureProgram, controller.property(SettingID.exposureProgram)))
        if (controller.mode == CaptureMode.video) {
            PillText("Карта: ${ValueFormatter.label(SettingID.cardRemaining, controller.property(SettingID.cardRemaining))}")
        } else {
            val card = controller.properties[SonyProp.CARD_REMAINING_SHOTS]
            val shots = card?.current?.intValue
            if (controller.prefs.destination != Prefs.Destination.macOnly && card?.enabled != 0 && shots != null) {
                // при сохранении «только на планшет» карта не используется — счётчик не показываем
                PillText("Карта: $shots кадров")
            }
        }
        PillText("Камера: ${ValueFormatter.label(SettingID.battery, controller.property(SettingID.battery))}")
        controller.tabletBattery?.let { PillText("Планшет: $it%" + if (controller.tabletCharging) " ⚡" else "") }
    }
}

@Composable
private fun PillText(text: String) = Text(text, color = Color.White, fontSize = 16.sp)

/** Тонкая строка с основными значениями, как на дисплее камеры. Касание открывает меню Fn. */
@Composable
private fun InfoLine(controller: CameraController, onOpen: (SettingID?) -> Unit) {
    Row(
        Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp)
            .alpha(if (controller.phase == CameraController.Phase.ready) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingCatalog.infoLine(controller.mode).forEach { id ->
            Column(
                Modifier.clickable { onOpen(id) }.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(id.shortTitle.uppercase(), color = Secondary, fontSize = 12.sp)
                Text(ValueFormatter.label(id, controller.property(id)), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Text("Fn", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 8.dp).background(PanelLight, RoundedCornerShape(8.dp))
                .clickable { onOpen(null) }.padding(horizontal = 14.dp, vertical = 10.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TopBar(controller: CameraController, screen: ScreenState) {
    var menuFor by remember { mutableIntStateOf(-1) }
    var renaming by remember { mutableIntStateOf(-1) }
    var newName by remember { mutableStateOf("") }
    Row(
        Modifier.background(Panel, RoundedCornerShape(12.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(if (controller.histogramOn) Accent else PanelLight, RoundedCornerShape(9.dp))
                .clickable { controller.toggleHistogram() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(22.dp)) {
                val w = size.width / 5
                listOf(0.5f, 0.85f, 0.65f).forEachIndexed { i, h ->
                    drawRect(Color.White, Offset(w * (i * 2 - 0.5f + 1), size.height * (1 - h)), Size(w, size.height * h))
                }
            }
        }
        controller.presets.forEachIndexed { index, preset ->
            Box {
                Text(
                    if (index < 9) "${index + 1} · ${preset.name}" else preset.name,
                    color = Color.White, fontSize = 16.sp, maxLines = 1,
                    modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp))
                        .combinedClickable(
                            enabled = controller.phase == CameraController.Phase.ready && !controller.isApplyingPreset,
                            onClick = { controller.applyPreset(index) },
                            onLongClick = { menuFor = index },
                        )
                        .padding(horizontal = 14.dp, vertical = 11.dp),
                )
                DropdownMenu(expanded = menuFor == index, onDismissRequest = { menuFor = -1 }) {
                    DropdownMenuItem(text = { Text("Переименовать…") }, onClick = {
                        newName = preset.name
                        renaming = index
                        menuFor = -1
                    })
                    DropdownMenuItem(text = { Text("Удалить") }, onClick = {
                        controller.deletePreset(index)
                        menuFor = -1
                    })
                }
            }
        }
        Text("+", color = Color.White, fontSize = 22.sp,
            modifier = Modifier.size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
                .clickable(enabled = controller.phase == CameraController.Phase.ready) { controller.savePreset() }
                .padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Text("⚙", color = Color.White, fontSize = 22.sp,
            modifier = Modifier.size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
                .clickable { screen.closeOverlays(); screen.settingsOpen = true }
                .padding(top = 6.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (renaming >= 0) {
        AlertDialog(
            onDismissRequest = { renaming = -1 },
            title = { Text("Название пресета") },
            text = { OutlinedTextField(newName, { newName = it }, singleLine = true) },
            confirmButton = { TextButton({ controller.renamePreset(renaming, newName); renaming = -1 }) { Text("Сохранить") } },
            dismissButton = { TextButton({ renaming = -1 }) { Text("Отмена") } },
        )
    }
}

/** Гистограмма яркости: слева тени, справа света. */
@Composable
private fun HistogramView(bins: IntArray) {
    Canvas(Modifier.background(Color(0x73000000), RoundedCornerShape(10.dp)).padding(8.dp).size(240.dp, 90.dp)) {
        val peak = bins.maxOrNull()?.takeIf { it > 0 } ?: return@Canvas
        val w = size.width / bins.size
        val path = Path().apply {
            moveTo(0f, size.height)
            bins.forEachIndexed { i, count ->
                // корень сглаживает пики, чтобы был виден и «хвост»
                val h = size.height * sqrt(count.toFloat() / peak)
                lineTo(i * w, size.height - h)
                lineTo((i + 1) * w, size.height - h)
            }
            lineTo(size.width, size.height)
            close()
        }
        drawPath(path, Color.White.copy(alpha = 0.75f))
    }
}

/** Настройки программы (кнопка ⚙). */
@Composable
private fun SettingsPanel(controller: CameraController, modifier: Modifier) {
    val prefs = controller.prefs
    var destination by remember { mutableStateOf(prefs.destination) }
    var reviewEnabled by remember { mutableStateOf(prefs.reviewEnabled) }
    var reviewSeconds by remember { mutableIntStateOf(prefs.reviewSeconds) }
    var reviewSource by remember { mutableStateOf(prefs.reviewSource) }
    var autoHide by remember { mutableStateOf(prefs.autoHide) }
    Column(
        modifier.background(Color(0xF22C2C2E), RoundedCornerShape(18.dp)).padding(24.dp)
            .verticalScroll(rememberScrollState()).pointerInput(Unit) { detectTapGestures { } },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Настройки", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text("Куда сохранять снимки", color = Secondary, fontSize = 15.sp)
        Prefs.Destination.entries.forEach { d ->
            Choice(d.title, destination == d) {
                destination = d
                prefs.destination = d
                controller.applyDestinationPreference()
            }
        }
        Text("Папка на планшете: ${controller.saver.folderTitle}", color = Secondary, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Toggle("Показывать снимок после съёмки", reviewEnabled) { reviewEnabled = it; prefs.reviewEnabled = it }
        Text("Сколько показывать: $reviewSeconds с", color = if (reviewEnabled) Color.White else Secondary, fontSize = 16.sp)
        Slider(reviewSeconds.toFloat(), { reviewSeconds = it.toInt(); prefs.reviewSeconds = reviewSeconds },
            valueRange = 1f..10f, steps = 8, enabled = reviewEnabled)
        Text("Во время просмотра показывать", color = Secondary, fontSize = 15.sp)
        Prefs.ReviewSource.entries.forEach { s ->
            Choice(s.title, reviewSource == s, enabled = reviewEnabled) { reviewSource = s; prefs.reviewSource = s }
        }
        Text("Если снимок сохраняется только на карту, показывается кадр с камеры.", color = Secondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Toggle("Скрывать элементы через 3 с без касаний", autoHide) { autoHide = it; prefs.autoHide = it }
        Text("Касание видео прячет и возвращает элементы.", color = Secondary, fontSize = 13.sp)
    }
}

@Composable
private fun Choice(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick, enabled = enabled)
        Text(title, color = if (enabled) Color.White else Secondary, fontSize = 16.sp)
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun RecBadge(startedAt: Long, modifier: Modifier = Modifier) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val seconds = maxOf(0L, (now - startedAt) / 1000)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(Color.Red, CircleShape))
        Text("REC %02d:%02d".format(seconds / 60, seconds % 60), color = Color.White, fontSize = 16.sp)
    }
}

