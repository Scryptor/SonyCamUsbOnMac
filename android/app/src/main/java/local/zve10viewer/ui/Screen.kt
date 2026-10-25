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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
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
import local.zve10viewer.settings.ShootingMode
import local.zve10viewer.settings.SlowQuick
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.settings.LumaStats
import local.zve10viewer.sony.SonyProp
import kotlin.math.sqrt

val Panel = Color(0xD91C1C1E)
val PanelLight = Color(0x1FFFFFFF)
val Secondary = Color(0xFF9A9AA0)
val Accent = Color(0xFF0A84FF)
/** Предупреждение: пересвет и провалы на гистограмме. Жёлтый, а не красный: красный на тёмном видно плохо. */
val Warning = Color(0xFFFFD60A)

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

    /** Подсказка под строкой состояния: о подключении или о кадрах. */
    var pillInfo by mutableStateOf<PillInfo?>(null)

    val controlsHidden: Boolean get() = manualHidden || autoHidden
    val overlayOpen: Boolean get() = fnOpen || allSettingsOpen || settingsOpen || pillInfo != null

    fun togglePillInfo(info: PillInfo) {
        val open = pillInfo != info
        closeOverlays()
        if (open) pillInfo = info
    }

    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
        if (autoHidden) autoHidden = false
    }

    fun openFn(id: SettingID?) {
        if (id != null) selected = id
        pillInfo = null
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
        pillInfo = null
        allSettingsOpen = true
    }

    fun closeOverlays() {
        fnOpen = false
        allSettingsOpen = false
        settingsOpen = false
        editing = null
        pillInfo = null
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
    // настройки (Wi-Fi, «Забыть») нужны и без камеры: иначе выключенный Wi-Fi не включить без кабеля
    var settingsOpen by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().padding(48.dp), verticalArrangement = Arrangement.Center) {
        Text(
            if (notPcRemote) "Камера подключена, но не в режиме PC Remote" else "Подключите камеру",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Column(Modifier.weight(1f)) {
                Text("По кабелю USB", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "1. MENU → Network → Smartphone Connect → Smartphone Connection: Off",
                    "2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: USB",
                    if (notPcRemote) "Переподключите кабель" else "3. Подключите камеру к планшету кабелем USB-C",
                ).forEach { Text(it, color = Secondary, fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                if (controller.phase == CameraController.Phase.permission) {
                    Text("Разрешите доступ к камере в окне Android…", color = Secondary, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
            // камера на кабеле не в PC Remote (например, на зарядке в режиме Wi-Fi) — по сети всё равно можно
            Column(Modifier.weight(1f)) {
                Text("По Wi-Fi", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                listOf(
                    "1. Камера и планшет — в одной сети Wi-Fi (у камеры: MENU → Network → Wi-Fi Settings → Access Point Set.)",
                    "2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: Wi-Fi Access Point",
                    "3. Один раз — сопряжение: кнопка ниже, на камере PC Remote Function → Pairing",
                    "Без роутера: на камере PC Remote Cnct Method: Wi-Fi Direct, а планшет подключите к сети камеры (DIRECT-…, пароль — на экране камеры)",
                ).forEach { Text(it, color = Secondary, fontSize = 18.sp, modifier = Modifier.padding(vertical = 4.dp)) }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    val pairing = controller.isPairing
                    Text(if (pairing) "Отмена" else "Сопрячь с камерой", color = Color.White, fontSize = 17.sp,
                        modifier = Modifier.background(if (pairing) PanelLight else Accent, RoundedCornerShape(10.dp))
                            .clickable { if (pairing) controller.cancelPairing() else controller.startPairing() }
                            .padding(horizontal = 18.dp, vertical = 12.dp))
                    if (pairing) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                    val name = controller.prefs.wifiCameraName
                    if (!pairing && controller.prefs.wifiEnabled && name != null) {
                        CircularProgressIndicator(Modifier.size(18.dp), color = Secondary, strokeWidth = 2.dp)
                        Text("Ищу $name в сети…", color = Secondary, fontSize = 16.sp)
                    }
                }
                controller.wifiStatus?.let { Text(it, color = Color(0xFFFF9F0A), fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp)) }
                if (!controller.prefs.wifiEnabled) {
                    Text("Подключение по Wi-Fi выключено в настройках", color = Secondary, fontSize = 16.sp, modifier = Modifier.padding(top = 10.dp))
                }
            }
        }
        controller.message?.let {
            Spacer(Modifier.height(18.dp))
            Text(it, color = Secondary, fontSize = 17.sp)
        }
    }
    Text("⚙", color = Color.White, fontSize = 22.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.align(Alignment.TopStart).padding(18.dp).size(44.dp)
            .background(PanelLight, RoundedCornerShape(9.dp)).clickable { settingsOpen = true }.padding(top = 6.dp))
    if (settingsOpen) SettingsOverlay(controller) { settingsOpen = false }
    }
}

/** Настройки поверх экрана: касание мимо панели закрывает, касания по самой панели (и её полям) — нет. */
@Composable
private fun SettingsOverlay(controller: CameraController, onClose: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(560.dp).fillMaxHeight(0.9f).pointerInput(Unit) { detectTapGestures { } }) {
            SettingsPanel(controller, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LiveScreen(controller: CameraController, screen: ScreenState) {
    var hint by remember { mutableStateOf(false) }
    // подсказка о S&Q пропадает вместе с режимом (например, MODE на камере) — закрываем и её флаг
    LaunchedEffect(controller.shootingMode) {
        if (controller.shootingMode != ShootingMode.slowQuick && screen.pillInfo == PillInfo.slowMotion) screen.pillInfo = null
    }
    // связь пропала — подсказки к ней больше нет
    DisposableEffect(Unit) { onDispose { screen.pillInfo = null } }
    // автоскрытие: через заданное в настройках время без касаний, если не открыто меню, панель или просмотр снимка
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            val busy = screen.overlayOpen || controller.review != null
            if (busy || !controller.prefs.autoHide) {
                screen.autoHidden = false
            } else if (!screen.autoHidden && System.currentTimeMillis() - screen.lastActivity > controller.prefs.autoHideSeconds * 1000L) {
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
        if (screen.settingsOpen) SettingsOverlay(controller) { screen.settingsOpen = false }
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
        // поверх кнопок справа: подсказка может их накрыть
        Column(Modifier.align(Alignment.TopEnd).zIndex(1f), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusPill(controller, screen)
            screen.pillInfo?.let { PillInfoCard(controller, it) }
        }
        RightColumn(controller, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp))
        if (!screen.fnOpen) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val bins = controller.histogram
                if (controller.histogramOn && bins != null) HistogramView(bins, controller.histogramStats)
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
private fun StatusPill(controller: CameraController, screen: ScreenState) {
    Row(
        Modifier.background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.recordingStartedAt?.let { RecBadge(it) }
        val ready = controller.phase == CameraController.Phase.ready
        Box(Modifier.size(10.dp).background(if (ready) Color(0xFF34C759) else Color(0xFFFF9F0A), CircleShape))
        // касание — подсказка: адреса и сигнал / что это за кадры
        PillText("${controller.deviceInfo?.model ?: "Камера"} · ${transportLabel(controller)}",
            Modifier.clickable { screen.togglePillInfo(PillInfo.connection) })
        PillText("${controller.shownFps} к/с", Modifier.clickable { screen.togglePillInfo(PillInfo.frames) })
        PillText(ValueFormatter.label(SettingID.exposureProgram, controller.property(SettingID.exposureProgram)))
        controller.slowMotion?.let { slow ->
            PillText(slowMotionSummary(slow), Modifier.clickable { screen.togglePillInfo(PillInfo.slowMotion) })
        }
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
private fun PillText(text: String, modifier: Modifier = Modifier) =
    Text(text, color = Color.White, fontSize = 16.sp, style = TextStyle(fontFeatureSettings = "tnum"), modifier = modifier)

enum class PillInfo { connection, frames, slowMotion }

private fun transportLabel(controller: CameraController) = when {
    controller.transportKind != CameraController.TransportKind.wifi -> "USB"
    controller.wifiDirect -> "Wi-Fi Direct"
    else -> "Wi-Fi"
}

@Composable
private fun PillInfoCard(controller: CameraController, info: PillInfo) {
    val wifi = controller.transportKind == CameraController.TransportKind.wifi
    Column(
        // плотнее обычной панели: лежит поверх кнопок справа
        Modifier.width(430.dp).background(Color(0xF71C1C1E), RoundedCornerShape(14.dp))
            // касания по карточке не уходят к кнопкам под ней (переключатель режима, запись)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when (info) {
            PillInfo.connection -> {
                // сигнал меняется — пока подсказка открыта, обновляем раз в секунду
                var details by remember { mutableStateOf(controller.connectionDetails()) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        details = controller.connectionDetails()
                    }
                }
                InfoTitle("Подключение по ${transportLabel(controller)}")
                controller.deviceInfo?.let { InfoRow("Камера", "${it.model}, прошивка ${it.firmware}") }
                if (wifi) {
                    details.cameraHost?.let { InfoRow("IP камеры", it) }
                    details.localHost?.let { InfoRow("IP планшета", it) }
                    details.signalDbm?.let { InfoRow("Сигнал Wi-Fi", "${it.toString().replace('-', '−')} дБм, ${signalWord(it)}") }
                    InfoNote(if (controller.wifiDirect) {
                        "Камера сама раздаёт сеть, сигнал — между планшетом и камерой. Если картинка дёргается, поднесите планшет ближе к камере."
                    } else {
                        "Сигнал — между планшетом и роутером. Если картинка дёргается, подойдите ближе к роутеру или поставьте ближе к нему камеру."
                    })
                } else {
                    InfoNote("Без кабеля планшет сам найдёт камеру по Wi-Fi, если она сопряжена (⚙ → Wi-Fi).")
                }
            }
            PillInfo.slowMotion -> {
                val slow = controller.slowMotion
                InfoTitle("Замедленная и ускоренная съёмка (S&Q)")
                InfoRow("Камера снимает", slow?.capture?.let { "$it кадров в секунду" } ?: "не видно")
                InfoRow("Ролик", controller.label(SettingID.movieRecordingSetting))
                if (slow?.capture != null && slow.record != null) InfoRow("Скорость", SlowQuick.speedLabel(slow.capture, slow.record))
                InfoNote("Частоту съёмки камера по кабелю менять не даёт: её выбирают на камере — MENU → S&Q Settings → Frame Rate. " +
                    "Частоту и битрейт ролика меняет «Запись» в строке внизу.")
                InfoNote("Частоту съёмки программа узнаёт по самой длинной выдержке, которую разрешает камера: выдержка не может быть " +
                    "длиннее кадра. В режимах S&Q P и A выдержку выбирает камера, и частоту не видно.")
            }
            PillInfo.frames -> {
                InfoTitle("Кадры трансляции")
                InfoNote("Столько кадров в секунду (сейчас ${controller.shownFps}) доходит до планшета ${if (wifi) "по Wi-Fi" else "по USB"}. " +
                    "Это картинка для просмотра, а не съёмка.", Color.White)
                InfoNote("На запись это не влияет: камера пишет видео на карту со своей частотой (например, 25p) " +
                    "и снимает фото в полном качестве, сколько бы кадров ни доходило сюда.")
                InfoNote("Счётчик — чтобы проверить связь. Камера отдаёт до 25–30 кадров в секунду; если число заметно меньше " +
                    "или скачет, связь не успевает и картинка подёргивается.")
                InfoRow("Самая долгая пауза за 10 с", "${controller.frameGapMs} мс")
                InfoNote("Обычно между кадрами около 40 мс; паузы дольше 200 мс заметны как рывок.")
            }
        }
    }
}

@Composable
private fun InfoTitle(text: String) = Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, color = Secondary, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Text(value, color = Color.White, fontSize = 15.sp, style = TextStyle(fontFeatureSettings = "tnum"))
    }
}

@Composable
private fun InfoNote(text: String, color: Color = Secondary) = Text(text, color = color, fontSize = 14.sp, lineHeight = 19.sp)

/** Коротко для строки состояния: «100 к/с · ×4 замедл.». */
private fun slowMotionSummary(slow: CameraController.SlowMotion): String {
    val capture = slow.capture ?: return "? к/с"
    val record = slow.record ?: return "$capture к/с"
    if (capture == record) return "$capture к/с · 1:1"
    return "$capture к/с · " + SlowQuick.speedLabel(capture, record).replace("медленнее", "замедл.").replace("быстрее", "ускор.")
}

private fun signalWord(dbm: Int) = when {
    dbm >= -60 -> "хороший"
    dbm >= -70 -> "средний"
    else -> "слабый"
}

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
                Text(controller.label(id), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
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
        Box(
            Modifier.size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
                .clickable(enabled = controller.phase == CameraController.Phase.ready) { controller.savePreset() },
            contentAlignment = Alignment.Center,
        ) {
            // «+» рисуем сами, как значок гистограммы: символ шрифта стоял выше центра и был мельче соседей
            Canvas(Modifier.size(20.dp)) {
                val stroke = 2.5.dp.toPx()
                drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
                drawLine(Color.White, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke, StrokeCap.Round)
            }
        }
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

/**
 * Гистограмма яркости: слева тени, справа света. Пунктир делит шкалу на трети — тени, полутона и света; под графиком —
 * их доли, провалы в чёрное, средняя яркость (она же — метка под графиком) и пересвет.
 */
@Composable
private fun HistogramView(bins: IntArray, stats: LumaStats?) {
    Column(
        Modifier.background(Color(0x73000000), RoundedCornerShape(10.dp)).padding(10.dp).width(300.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            // снизу — полоска под метку средней яркости
            val height = size.height - 9.dp.toPx()
            val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
            for (third in listOf(1f / 3, 2f / 3)) {
                drawLine(Color.White.copy(alpha = 0.3f), Offset(size.width * third, 0f), Offset(size.width * third, height),
                    strokeWidth = 1.dp.toPx(), pathEffect = dash)
            }
            val peak = bins.maxOrNull() ?: 0
            if (peak > 0) {
                val w = size.width / bins.size
                val path = Path().apply {
                    moveTo(0f, height)
                    bins.forEachIndexed { i, count ->
                        // корень сглаживает пики, чтобы был виден и «хвост»
                        val h = height * sqrt(count.toFloat() / peak)
                        lineTo(i * w, height - h)
                        lineTo((i + 1) * w, height - h)
                    }
                    lineTo(size.width, height)
                    close()
                }
                drawPath(path, Color.White.copy(alpha = 0.75f))
            }
            if (stats == null) return@Canvas
            // провалы и пересвет — жёлтая кромка с той стороны, где детали потеряны
            val edge = 3.dp.toPx()
            if (LumaStats.isClipped(stats.clippedShadows)) drawRect(Warning, Offset.Zero, Size(edge, height))
            if (LumaStats.isClipped(stats.clippedHighlights)) drawRect(Warning, Offset(size.width - edge, 0f), Size(edge, height))
            val x = size.width * (stats.mean / 100).toFloat()
            val marker = Path().apply {
                moveTo(x, height + 2.dp.toPx())
                lineTo(x - 5.dp.toPx(), size.height)
                lineTo(x + 5.dp.toPx(), size.height)
                close()
            }
            drawPath(marker, Color.White)
        }
        if (stats != null) {
            HistogramRow(listOf(Triple("Тени", LumaStats.share(stats.shadows), false),
                Triple("Полутона", LumaStats.share(stats.midtones), false),
                Triple("Света", LumaStats.share(stats.highlights), false)))
            HistogramRow(listOf(
                Triple("Провалы", LumaStats.clipping(stats.clippedShadows), LumaStats.isClipped(stats.clippedShadows)),
                Triple("Средняя", LumaStats.share(stats.mean), false),
                Triple("Пересвет", LumaStats.clipping(stats.clippedHighlights), LumaStats.isClipped(stats.clippedHighlights))))
        }
    }
}

/** Строка подписей под гистограммой: подпись, значение, выделить ли его. Каждая — под своей третью. */
@Composable
private fun HistogramRow(figures: List<Triple<String, String, Boolean>>) {
    Row(Modifier.fillMaxWidth()) {
        for ((label, value, alert) in figures) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center) {
                Text(label, Modifier.alignByBaseline(), color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, maxLines = 1)
                Text(" $value", Modifier.alignByBaseline(), color = if (alert) Warning else Color.White.copy(alpha = 0.9f),
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"))
            }
        }
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
    var autoHideSeconds by remember { mutableIntStateOf(prefs.autoHideSeconds) }
    var wifiEnabled by remember { mutableStateOf(prefs.wifiEnabled) }
    var wifiCamera by remember { mutableStateOf(prefs.wifiCameraName?.let { name -> prefs.wifiHost?.let { "$name · $it" } ?: name }) }
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
        Toggle("Скрывать элементы без касаний", autoHide) { autoHide = it; prefs.autoHide = it }
        Text("Через: $autoHideSeconds с", color = if (autoHide) Color.White else Secondary, fontSize = 16.sp)
        Slider(autoHideSeconds.toFloat(), { autoHideSeconds = it.roundToInt(); prefs.autoHideSeconds = autoHideSeconds },
            valueRange = 1f..30f, steps = 28, enabled = autoHide)
        Text("Касание видео прячет и возвращает элементы.", color = Secondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("Wi-Fi", color = Secondary, fontSize = 15.sp)
        Toggle("Подключаться по Wi-Fi, когда камеры нет на кабеле", wifiEnabled) { wifiEnabled = it; prefs.wifiEnabled = it }
        val cameraName = wifiCamera
        if (cameraName != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Камера: $cameraName", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("Забыть", color = Accent, fontSize = 16.sp, modifier = Modifier.clickable {
                    controller.forgetWiFiCamera()
                    wifiCamera = null
                }.padding(8.dp))
            }
        } else {
            Text("Камера ещё не подключалась по Wi-Fi", color = Secondary, fontSize = 14.sp)
        }
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

