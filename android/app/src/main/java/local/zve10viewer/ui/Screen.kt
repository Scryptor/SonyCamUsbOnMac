package local.zve10viewer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.zIndex
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.offset
import android.os.Build
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.launch
import local.zve10viewer.view.FloatingPlacement
import local.zve10viewer.view.ViewZoom
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import local.zve10viewer.CameraController
import local.zve10viewer.CameraController.FrameNotice
import local.zve10viewer.Prefs
import local.zve10viewer.debug.DebugTab
import local.zve10viewer.session.LiveViewQuality
import local.zve10viewer.session.PreviewQualityMode
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
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorTool
import local.zve10viewer.monitor.Waveform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.animation.core.LinearEasing

val Panel = Color(0xD91C1C1E)
val PanelLight = Color(0x1FFFFFFF)
val Secondary = Color(0xFF9A9AA0)
val Accent = Color(0xFF0A84FF)
/** Предупреждение: пересвет и провалы на гистограмме. Жёлтый, а не красный: красный на тёмном видно плохо. */
val Warning = Color(0xFFFFD60A)
/** Пресет изменён на камере: точка на кнопке пресетов, «изменён» в списке. */
val Amber = Color(0xFFFF9F0A)

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

    /** Меню «Монитор» внизу — на месте строки значений, как меню Fn, — и выбранная в нём плитка. */
    var assistOpen by mutableStateOf(false)
    var assistSelected by mutableStateOf(MonitorTool.zebra)

    /** Панель отладки: долгое нажатие на плашку состояния или ⚙ → «Отладка»; закрывается только ×. */
    var debugOpen by mutableStateOf(false)
    var debugTab by mutableStateOf(DebugTab.link)

    /** Где гистограмма и волновая форма на экране: касания по ним не возвращают скрытые элементы. */
    var histogramRect: Rect? = null
    var waveformRect: Rect? = null
    var overlayRect: Rect? = null
    /** Высота ленты снимков (как её разложили) — гистограмма по умолчанию стоит над ней. */
    var stripHeight by mutableStateOf<Float?>(null)
    /** Где меню Fn, пока оно открыто: гистограмма поднимается над ним, а не прячется под ним. */
    var fnMenuRect by mutableStateOf<Rect?>(null)
    /**
     * Где правая колонка кнопок (зум, режим, запись): гистограмма и волновая форма на неё не ложатся — встают левее.
     * Колонку спрятали вместе с элементами — место остаётся прежним: панели не прыгают туда и обратно.
     */
    var rightColumnRect by mutableStateOf<Rect?>(null)

    /** Галерея: какой снимок открыт (по id, а не по месту в ленте — новый снимок его не сбивает); null — закрыта. */
    var galleryShot by mutableStateOf<Long?>(null)

    /** Список пресетов под кнопкой пресетов. */
    var presetMenuOpen by mutableStateOf(false)
    /** Окошко «Новый пресет» — имя и что войдёт. */
    var newPresetOpen by mutableStateOf(false)
    /** Окошко «Сохранить настройки» («☆+», когда текущий пресет изменён): в него или новым пресетом. */
    var savePresetOpen by mutableStateOf(false)
    /** «Посмотреть» держат: окно пресетов прозрачно — не мешает смотреть на картинку. */
    var presetPeeking by mutableStateOf(false)
    /** Окно пресетов и какой пресет в нём выбран (номер с 0). Переживает обрыв связи: на экране ожидания оно то же. */
    var presetsOpen by mutableStateOf(false)
    var presetsSelected by mutableIntStateOf(0)
    /** Где верхняя панель и кнопка пресетов (по горизонтали, в точках экрана) — список встаёт под кнопкой. */
    var topBarLeft by mutableFloatStateOf(0f)
    var presetButtonLeft by mutableFloatStateOf(0f)
    /** Левый край строки состояния (0 — ещё не разложена): кнопка пресетов до неё не дотягивается. */
    var statusPillLeft by mutableFloatStateOf(0f)

    /**
     * Цифровое увеличение видео — свойство экрана: переживает фото / видео / S&Q, запись, смену качества превью и
     * поворот; сбрасывается, когда связь кончилась и показан экран ожидания.
     */
    var zoom by mutableStateOf(ViewZoom.identity)
    /** Пружина или инерция после жеста, сброс ✕ — отменяется следующим касанием. */
    var zoomMotion: kotlinx.coroutines.Job? = null
    /** Где плашка увеличения: касания по ней не возвращают скрытые элементы, гистограмма поднимается над ней. */
    var zoomBadgeRect by mutableStateOf<Rect?>(null)
    /** Отладка: бикубический шейдер (выключить — сравнить с обычной растяжкой). */
    var bicubic by mutableStateOf(true)

    val controlsHidden: Boolean get() = manualHidden || autoHidden
    /** Открыто меню, панель, список пресетов или окошко: элементы не прячутся сами, касание видео закрывает. */
    /** Подсказка «Фокус касанием»: при включении кнопки (пока не отмечено «Больше не показывать») и по долгому нажатию на неё. */
    var touchFocusHelpOpen by mutableStateOf(false)

    val overlayOpen: Boolean
        get() = fnOpen || assistOpen || allSettingsOpen || settingsOpen || pillInfo != null || presetMenuOpen || newPresetOpen ||
            savePresetOpen || presetsOpen || touchFocusHelpOpen

    fun togglePillInfo(info: PillInfo) {
        val open = pillInfo != info
        closeOverlays()
        if (open) pillInfo = info
    }

    fun togglePresetMenu() {
        val open = !presetMenuOpen
        closeOverlays()
        presetMenuOpen = open
    }

    /** Окно пресетов на пресете с этим номером (с 0). */
    fun openPresets(index: Int) {
        closeOverlays()
        presetsSelected = index
        presetsOpen = true
    }

    /** Кнопка «Монитор»: открыть меню инструментов или закрыть его. */
    fun toggleAssistMenu() {
        if (assistOpen) closeOverlays() else openAssist(null)
    }

    fun openAssist(tool: MonitorTool?) {
        if (tool != null) assistSelected = tool
        closeOverlays()
        assistOpen = true
    }

    fun noteActivity() {
        lastActivity = System.currentTimeMillis()
        if (autoHidden) autoHidden = false
    }

    fun openFn(id: SettingID?) {
        if (id != null) selected = id
        assistOpen = false
        pillInfo = null
        presetMenuOpen = false
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
        assistOpen = false
        settingsOpen = false
        pillInfo = null
        presetMenuOpen = false
        allSettingsOpen = true
    }

    fun closeOverlays() {
        touchFocusHelpOpen = false
        fnOpen = false
        assistOpen = false
        allSettingsOpen = false
        settingsOpen = false
        editing = null
        pillInfo = null
        presetMenuOpen = false
        newPresetOpen = false
        savePresetOpen = false
        presetsOpen = false
    }
}

@Composable
fun App(controller: CameraController) {
    val screen = remember { ScreenState() }
    // отладка (debug-сборка): увеличение и галерея по adb-broadcast
    LaunchedEffect(Unit) {
        controller.debugCommands.collect { command ->
            when (command) {
                is CameraController.DebugCommand.Zoom -> {
                    command.bicubic?.let { screen.bicubic = it }
                    screen.zoomMotion?.cancel()
                    screen.zoom = if (command.factor <= 1.0) ViewZoom.identity
                    else ViewZoom(command.factor.coerceAtMost(ViewZoom.MAX_FACTOR), command.x, command.y)
                }
                is CameraController.DebugCommand.Gallery -> when {
                    command.undo -> undoDeleteShot(controller, screen)
                    command.delete -> (controller.shots.getOrNull(command.index)?.id ?: screen.galleryShot.takeIf { command.index < 0 })
                        ?.let { deleteShot(controller, screen, it) }
                    else -> screen.galleryShot = if (command.index < 0) null else controller.shots.getOrNull(command.index)?.id
                }
                is CameraController.DebugCommand.Assist -> {
                    val tool = command.tool?.let { name -> MonitorTool.entries.firstOrNull { it.name == name } }
                    // волновая форма и эталон — вместе с остальным или отдельно
                    command.waveform?.let { name ->
                        val mode = Waveform.Mode.entries.firstOrNull { it.name == name }
                        if ((mode == null) == controller.waveformOn) controller.toggleWaveform()
                        if (mode != null) controller.chooseWaveformMode(mode)
                    }
                    if (command.reference) controller.takeReferenceFromFrame()
                    if (command.overlayToggle) controller.toggleOverlayVisible()
                    command.shot?.let { index -> controller.shots.getOrNull(index)?.let { controller.takeReference(it) } }
                    when {
                        command.off -> controller.assistOff()
                        command.menu == true -> screen.openAssist(tool)
                        command.menu == false -> if (screen.assistOpen) screen.closeOverlays()
                        tool != null && command.value != null -> controller.setAssist(tool, command.value)
                        tool != null -> controller.toggleAssist(tool)
                    }
                }
            }
        }
    }
    // связь кончилась, показан экран ожидания — увеличение и галерея сбрасываются
    val waiting = controller.phase == CameraController.Phase.searching || controller.phase == CameraController.Phase.permission ||
        controller.phase == CameraController.Phase.notPcRemote
    LaunchedEffect(waiting) {
        if (waiting) {
            screen.zoomMotion?.cancel()
            screen.zoom = ViewZoom.identity
            screen.galleryShot = null
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, onPrimary = Color.White, secondary = Accent)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (controller.phase) {
                CameraController.Phase.searching, CameraController.Phase.permission -> WaitingScreen(controller, screen, notPcRemote = false)
                CameraController.Phase.notPcRemote -> WaitingScreen(controller, screen, notPcRemote = true)
                else -> LiveScreen(controller, screen)
            }
            // и на экране ожидания: там видно, куда программа стучится и что ей отвечают
            if (screen.debugOpen) {
                // слева под кнопками сверху, как на Mac: справа под панелью остались бы спуск и запись
                DebugPanel(controller, screen, Modifier.align(Alignment.TopStart).padding(start = 18.dp, top = 84.dp)
                    .fillMaxWidth(0.45f).fillMaxHeight(0.8f))
            }
        }
    }
}

@Composable
private fun WaitingScreen(controller: CameraController, screen: ScreenState, notPcRemote: Boolean) {
    // настройки (Wi-Fi, «Забыть») нужны и без камеры: иначе выключенный Wi-Fi не включить без кабеля
    var settingsOpen by remember { mutableStateOf(false) }
    // настройка Wi-Fi — не состояние Compose: читаем её здесь и после панели настроек, где её могли сменить
    var wifiOn by remember { mutableStateOf(controller.prefs.wifiEnabled) }
    Box(Modifier.fillMaxSize()) {
    // не помещается — прокручивается; помещается — по центру
    BoxWithConstraints(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 48.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center) {
        WaitingContent(controller, notPcRemote, wifiOn) {
            controller.prefs.wifiEnabled = true
            wifiOn = true
        }
    }
    }
    Box(
        Modifier.align(Alignment.TopStart).padding(18.dp).size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
            .semantics { contentDescription = "Настройки" }.clickable { settingsOpen = true },
        contentAlignment = Alignment.Center,
    ) {
        TopBarIcon(TopBarGlyph.settings)
    }
    if (settingsOpen) {
        SettingsOverlay(controller, onClose = {
            settingsOpen = false
            wifiOn = controller.prefs.wifiEnabled
        }, onDebug = {
            settingsOpen = false
            screen.debugOpen = true
        }, onPresets = {
            settingsOpen = false
            screen.openPresets(controller.currentPresetIndex ?: 0)
        })
    }
    // пресеты правятся и без камеры: имена, порядок, что менять; значения — когда она на связи
    if (screen.presetsOpen) PresetsOverlay(controller, screen)
    }
}

/** Настройки поверх экрана: касание мимо панели закрывает, касания по самой панели (и её полям) — нет. */
@Composable
private fun SettingsOverlay(controller: CameraController, onClose: () -> Unit, onDebug: () -> Unit, onPresets: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x99000000)).pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(560.dp).fillMaxHeight(0.9f).pointerInput(Unit) { detectTapGestures { } }) {
            SettingsPanel(controller, Modifier.fillMaxSize(), onDebug, onPresets)
        }
    }
}

@Composable
private fun LiveScreen(controller: CameraController, screen: ScreenState) {
    var hint by remember { mutableStateOf(false) }
    // отклик кнопок: волна, шторка, полёт стоп-кадра, «мотание», вибрация — по событиям контроллера
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val feedback = remember { FeedbackState(scope, Haptics(view) { controller.prefs.hapticsEnabled }) }
    LaunchedEffect(Unit) { controller.feedback.collect { feedback.handle(it) } }
    // подсказка о S&Q пропадает вместе с режимом (например, MODE на камере) — закрываем и её флаг
    LaunchedEffect(controller.shootingMode) {
        if (controller.shootingMode != ShootingMode.slowQuick && screen.pillInfo == PillInfo.slowMotion) screen.pillInfo = null
    }
    // связь пропала — подсказки к ней больше нет; список пресетов, «Новый пресет» и «Сохранить настройки» без камеры не нужны
    DisposableEffect(Unit) {
        onDispose {
            screen.pillInfo = null
            screen.presetMenuOpen = false
            screen.newPresetOpen = false
            screen.savePresetOpen = false
        }
    }
    // автоскрытие: через заданное в настройках время без касаний, если не открыто меню, панель или просмотр снимка
    LaunchedEffect(Unit) {
        while (true) {
            delay(500)
            // и пока кнопка записи ждёт камеру или показывает отказ — не прятать её из-под взгляда
            val busy = screen.overlayOpen || controller.review != null || screen.galleryShot != null ||
                controller.recordingRequest != null || controller.recordingNotice != null ||
                // держат «T» или «W» — зум не должен оборваться вместе с кнопками
                controller.zoomDirection != 0
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
            .onSizeChanged { feedback.screenSize = it.toSize() }
            // любое касание — «активность», элементы не прячутся; само касание не перехватываем. Касания по гистограмме
            // (её перетаскивают) скрытые элементы не возвращают
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    var onPanel = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.changes.count { it.pressed } == 1) {
                            val at = event.changes.first().position
                            onPanel = screen.histogramRect?.contains(at) == true || screen.waveformRect?.contains(at) == true ||
                                screen.overlayRect?.contains(at) == true || screen.zoomBadgeRect?.contains(at) == true
                            if (!onPanel) screen.revealTap = screen.controlsHidden
                        }
                        if (!onPanel) screen.noteActivity()
                    }
                }
            },
    ) {
        Video(controller, screen, feedback, Modifier.fillMaxSize()) { point ->
            when {
                screen.overlayOpen -> screen.closeOverlays()
                screen.revealTap || screen.manualHidden -> {
                    screen.revealTap = false
                    screen.manualHidden = false
                }
                // фокус касанием: касание кадра ставит туда фокус, элементы не прячет
                controller.touchFocusOn && point != null -> controller.touchFocus(point.x.toDouble(), point.y.toDouble())
                else -> {
                    screen.manualHidden = true
                    hint = true
                }
            }
        }
        // гистограмма — над видео, под кнопками и меню; не прячется вместе с элементами
        if (controller.histogramOn) FloatingHistogram(controller, screen, feedback)
        if (controller.waveformOn) FloatingWaveform(controller, screen, feedback)
        // панель наложения — тоже не среди элементов: касание миниатюры нужно и когда они спрятаны
        if (controller.assist.overlay != MonitorAssist.Overlay.off) FloatingOverlay(controller, screen, feedback)
        // плашка увеличения — видна и при скрытых элементах: увеличенная картинка — не весь кадр
        val zoomFrame = controller.frame
        if (screen.zoom.isZoomed && zoomFrame != null) {
            ZoomBadge(zoomFrame, screen.zoom, feedback.screenSize,
                onCenter = { at ->
                    screen.zoomMotion?.cancel()
                    screen.zoom = screen.zoom.centered(at, feedback.screenSize, Size(zoomFrame.width.toFloat(), zoomFrame.height.toFloat()))
                },
                onReset = {
                    screen.zoomMotion?.cancel()
                    feedback.haptic(HapticEvent.zoomLimit)
                    screen.zoomMotion = resetZoom(scope, { screen.zoom }) { screen.zoom = it }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp)
                    .onGloballyPositioned { screen.zoomBadgeRect = it.boundsInRoot() })
            DisposableEffect(Unit) { onDispose { screen.zoomBadgeRect = null } }
        }
        AnimatedVisibility(showControls, enter = fadeIn(), exit = fadeOut()) {
            Controls(controller, screen, feedback)
        }
        // строка состояния — своим слоем: всегда вместе с элементами, а закреплённая — и когда они скрыты, на том же месте
        AnimatedVisibility(showControls || controller.statusBarPinned, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().padding(18.dp)) {
                // поверх кнопок справа: подсказка может их накрыть
                Column(Modifier.align(Alignment.TopEnd), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(controller, screen)
                    screen.pillInfo?.let { PillInfoCard(controller, it) }
                }
            }
        }
        // закреплённая строка уже показывает «● REC» с таймером — отдельная плашка не нужна
        if (!showControls && !controller.statusBarPinned) {
            controller.recordingStartedAt?.let { started ->
                RecBadge(started, Modifier.align(Alignment.TopEnd).padding(20.dp)
                    .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (screen.allSettingsOpen) {
            AllSettingsPanel(controller, screen, Modifier.align(Alignment.Center).fillMaxWidth(0.62f).fillMaxHeight(0.78f))
        }
        if (screen.settingsOpen) {
            SettingsOverlay(controller, onClose = { screen.settingsOpen = false }, onDebug = {
                screen.settingsOpen = false
                screen.debugOpen = true
            }, onPresets = { screen.openPresets(controller.currentPresetIndex ?: 0) })
        }
        if (screen.presetsOpen) PresetsOverlay(controller, screen)
        // быстрый просмотр пресета: окно прозрачно, над картинкой — что сейчас на камере
        controller.previewingPreset?.let { index ->
            Text(if (controller.previewReturning) "Возвращаю как было…"
                else "Просмотр «${controller.presets.getOrNull(index)?.name.orEmpty()}» — отпустите кнопку, вернётся как было",
                color = Color.White, fontSize = 17.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp).background(Panel, RoundedCornerShape(20.dp))
                    .padding(horizontal = 18.dp, vertical = 10.dp))
        }
        // касания в окошке — в своём окне, экран их не видит: закрыли — считаем касанием, элементы не исчезают сразу
        if (screen.savePresetOpen) SavePresetDialog(controller, onNew = {
            screen.savePresetOpen = false
            screen.newPresetOpen = true
        }) {
            screen.savePresetOpen = false
            screen.noteActivity()
        }
        if (screen.newPresetOpen) NewPresetDialog(controller) {
            screen.newPresetOpen = false
            screen.noteActivity()
        }
        controller.review?.let { ReviewOverlay(controller, it) }
        if (screen.galleryShot != null) GalleryOverlay(controller, screen)
        // «Вернуть» удалённый снимок: над лентой галереи или над строкой значений
        DeletedShotBanner(controller, screen,
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (screen.galleryShot != null) 150.dp else 112.dp))
        if (hint) {
            Text("Элементы скрыты · нажмите, чтобы показать", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                    .background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
        }
        // шторка и вспышка — поверх всего, и поверх просмотра снимка; выше — только летящий кадр
        FlashLayer(feedback)
        GrabFlightLayer(feedback)
    }
}

/**
 * Видео с цифровым увеличением: щипок двумя пальцами, сдвиг при увеличении (с инерцией), касание — onTap (прячет и
 * возвращает элементы; с фокусом касанием — точка кадра, доли, или null мимо кадра). Кадр — бикубически (AGSL, Android 13+), рамка записи — по краю видимой картинки.
 */
@Composable
private fun Video(controller: CameraController, screen: ScreenState, feedback: FeedbackState, modifier: Modifier, onTap: (Offset?) -> Unit) {
    var viewSize by remember { mutableStateOf(Size.Zero) }
    val scope = rememberCoroutineScope()
    val renderer = remember { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) BicubicRenderer() else null }
    val frame = controller.frame
    val frameSize = frame?.let { Size(it.width.toFloat(), it.height.toFloat()) }
    // жесты живут дольше одной перерисовки: размер кадра и обработчик касания — всегда последние
    val latestFrame by rememberUpdatedState(frameSize)
    val latestTap by rememberUpdatedState(onTap)
    // увеличено ≥ 1,5× — полное превью держим до последнего; экран ушёл — подсказка снята
    val sharp = screen.zoom.factor >= 1.5
    LaunchedEffect(sharp) { controller.sharpPreview = sharp }
    DisposableEffect(Unit) { onDispose { controller.sharpPreview = false } }
    Box(
        modifier.onSizeChanged { viewSize = it.toSize() }.pointerInput(Unit) {
            zoomGestures(
                zoom = { screen.zoom },
                setZoom = { screen.zoom = it },
                frame = { latestFrame },
                onStart = { screen.zoomMotion?.cancel() },
                onTap = { at ->
                    // точка кадра под пальцем — с увеличением; мимо кадра — null
                    val f = latestFrame
                    val rect = f?.let { screen.zoom.imageRect(Size(size.width.toFloat(), size.height.toFloat()), it) }
                    latestTap(rect?.takeIf { it.width > 0f && it.height > 0f && it.contains(at) }
                        ?.let { Offset((at.x - it.left) / it.width, (at.y - it.top) / it.height) })
                },
                onLimit = { feedback.haptic(HapticEvent.zoomLimit) },
                onEnd = { velocity ->
                    val f = latestFrame ?: return@zoomGestures
                    screen.zoomMotion = settleZoom(scope, { screen.zoom }, { screen.zoom = it }, Size(size.width.toFloat(), size.height.toFloat()),
                        f, velocity) { feedback.haptic(HapticEvent.zoomLimit) }
                },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && frameSize != null) {
            val notice = controller.frameNotice
            // связь прервалась — последний кадр остаётся, но приглушён: видно, что он не живой
            val dimmed = notice == FrameNotice.interrupted || notice == FrameNotice.reconnecting
            val bicubic = renderer != null && screen.bicubic && (BICUBIC_AT_IDENTITY || screen.zoom.isZoomed)
            val assist = controller.assist
            val overlay = if (assist.needsRaster) controller.assistOverlay else null
            val reference = assist.overlay.opacity?.takeIf { !controller.overlayHidden }?.let { opacity -> controller.reference?.let { it to opacity } }
            // отметка касания сжимается и гаснет за секунду
            val mark = controller.touchMark
            val markProgress = remember { Animatable(1f) }
            LaunchedEffect(mark) {
                if (mark == null) markProgress.snapTo(1f) else {
                    markProgress.snapTo(0f)
                    markProgress.animateTo(1f, tween(1000, easing = LinearEasing))
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                val rect = screen.zoom.imageRect(size, frameSize)
                drawFrame(frame, rect, if (dimmed) 0.55f else 1f, if (bicubic) renderer else null)
                // эталонный кадр — полупрозрачно поверх живого: заполняет кадр, лишнее обрезано по его краю
                if (reference != null) drawReference(reference.first.image, rect, reference.second * if (dimmed) 0.55f else 1f)
                // зебра, false color и пикинг — растр над своей частью кадра; едет вместе с картинкой
                if (overlay != null) {
                    val area = assistArea(overlay, rect)
                    drawImage(overlay.image, dstOffset = IntOffset(area.left.roundToInt(), area.top.roundToInt()),
                        dstSize = IntSize(area.width.roundToInt(), area.height.roundToInt()), alpha = if (dimmed) 0.55f else 1f,
                        filterQuality = FilterQuality.Low)
                }
                if (assist.hasMarkers) drawAssistMarkers(assist, frameSize, rect)
                // рамки фокуса камеры — всегда, как на её экране: выключили кнопку «Фокус» — слежение продолжается, рамка
                // видна; отметка касания — после касания в режиме фокуса касанием
                drawFocusFrames(controller.focusFrames, rect, mark?.first, markProgress.value)
            }
            // растр помощи считается по видимой части кадра
            SideEffect {
                val visible = screen.zoom.visibleRegion(viewSize, frameSize)
                controller.assistView = CameraController.AssistView(visible.left.toDouble(), visible.top.toDouble(),
                    visible.width.toDouble(), visible.height.toDouble(), screen.zoom.factor)
            }
            if (assist.falseColor) {
                FalseColorLegend(Modifier.align(Alignment.TopCenter).padding(top = if (screen.controlsHidden && !screen.overlayOpen) 18.dp else 84.dp))
            }
            // стоп-кадр — всегда целиком: улетает из вписанного кадра; рамка записи — по краю видимой картинки
            SideEffect { feedback.frameRect = ViewZoom.identity.imageRect(viewSize, frameSize) }
            TallyBorder(controller.isRecording, controller.tallyStyle, { screen.zoom.imageRect(viewSize, frameSize) })
            when (notice) {
                // кадр не тускнеет: картинка, скорее всего, сейчас пойдёт дальше
                FrameNotice.weakSignal -> Text("Слабый сигнал Wi-Fi", color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 84.dp)
                        .background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 8.dp))
                FrameNotice.interrupted, FrameNotice.reconnecting -> LinkNotice(controller, notice)
                FrameNotice.none -> Unit
            }
        } else {
            CircularProgressIndicator(color = Color.White)
        }
    }
}

/** Связи с камерой нет: сколько уже длится и, через 10 с, что можно сделать. */
@Composable
private fun LinkNotice(controller: CameraController, notice: FrameNotice) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val seconds = maxOf(0L, (now - controller.noticeSince) / 1000)
    val wifi = controller.transportKind == CameraController.TransportKind.wifi
    val title = when {
        notice == FrameNotice.reconnecting -> "Переподключаюсь к камере…"
        wifi -> "Связь с камерой прервалась · %d:%02d".format(seconds / 60, seconds % 60)
        else -> "Нет картинки с камеры · %d:%02d".format(seconds / 60, seconds % 60)
    }
    Column(
        Modifier.widthIn(max = 480.dp).background(Panel, RoundedCornerShape(14.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            Text(title, color = Color.White, fontSize = 18.sp, style = TextStyle(fontFeatureSettings = "tnum"))
        }
        if (wifi && seconds >= 10) {
            Text("Поднесите камеру ближе к роутеру или планшету и выключите Bluetooth, если он не нужен",
                color = Secondary, fontSize = 15.sp, lineHeight = 20.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun Controls(controller: CameraController, screen: ScreenState, feedback: FeedbackState) {
    Box(Modifier.fillMaxSize().padding(18.dp)) {
        Column(Modifier.align(Alignment.TopStart)) {
            TopBar(controller, screen)
            if (screen.presetMenuOpen) {
                // под самой кнопкой пресетов, а не под началом панели
                val start = with(LocalDensity.current) { (screen.presetButtonLeft - screen.topBarLeft).coerceAtLeast(0f).toDp() }
                PresetMenu(controller, screen, Modifier.padding(start = start, top = 8.dp))
            }
            // лента камер — под верхней панелью, когда на связи не одна камера; прячется вместе с элементами. Сообщение —
            // справа от ленты (без ленты — под панелью): ленту не сдвигает, а в ряду панели ему не хватает места
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (controller.stripVisible) CameraStrip(controller)
                controller.statusMessage?.let {
                    Text(it, color = Color.White, fontSize = 15.sp, modifier = Modifier
                        .background(Panel, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp))
                }
            }
        }
        RightColumn(controller, screen, feedback, Modifier.align(Alignment.CenterEnd).padding(end = 4.dp)
            .onGloballyPositioned { screen.rightColumnRect = it.boundsInRoot() })
        // подсказка к фокусу касанием — левее кнопок справа
        if (screen.touchFocusHelpOpen) TouchFocusHelpCard(controller, screen, Modifier.align(Alignment.CenterEnd).padding(end = 96.dp))
        if (!screen.fnOpen && !screen.assistOpen && controller.shots.isNotEmpty()) {
            Box(Modifier.align(Alignment.BottomStart).padding(bottom = 76.dp)
                .onSizeChanged { screen.stripHeight = it.height.toFloat() }) {
                ShotsStrip(controller, feedback) { screen.galleryShot = it.id }
            }
        }
        Box(Modifier.align(Alignment.BottomCenter).padding(end = if (screen.fnOpen || screen.assistOpen) 110.dp else 0.dp)) {
            if (screen.fnOpen) {
                // где меню — для гистограммы: она поднимается над ним
                DisposableEffect(Unit) { onDispose { screen.fnMenuRect = null } }
                Box(Modifier.onGloballyPositioned { screen.fnMenuRect = it.boundsInRoot() }) { FnMenu(controller, screen) }
            } else if (screen.assistOpen) {
                // меню «Монитор» — на том же месте, гистограмма поднимается и над ним
                DisposableEffect(Unit) { onDispose { screen.fnMenuRect = null } }
                Box(Modifier.onGloballyPositioned { screen.fnMenuRect = it.boundsInRoot() }) { AssistMenu(controller, screen) }
            } else if (!screen.allSettingsOpen) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // слежение от касания — снять, и когда кнопка «Фокус» уже выключена: камера следит дальше; ручной фокус —
                    // шкала фокуса над строкой значений
                    if (controller.touchCancelAvailable) CancelTouchChip(controller)
                    if (controller.focusDriveAvailable) FocusScale(controller)
                    InfoLine(controller) { screen.openFn(it) }
                }
            }
        }
    }
}

@Composable
private fun StatusPill(controller: CameraController, screen: ScreenState) {
    Row(
        Modifier.onGloballyPositioned { screen.statusPillLeft = it.positionInRoot().x }
            .background(Panel, RoundedCornerShape(14.dp))
            // долгое нажатие — панель отладки; короткие по надписям — подсказки, как раньше
            .onLongPress {
                screen.pillInfo = null
                screen.debugOpen = true
            }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        controller.recordingStartedAt?.let { RecBadge(it) }
        val ready = controller.phase == CameraController.Phase.ready
        Box(Modifier.size(10.dp).background(if (ready) Color(0xFF34C759) else Color(0xFFFF9F0A), CircleShape))
        // касание — подсказка: адреса и сигнал / что это за кадры
        PillText("${controller.cameraName} · ${transportLabel(controller)}",
            Modifier.clickable { screen.togglePillInfo(PillInfo.connection) })
        // и частота, и разрешение превью — всегда, и в авто: снижение из-за слабого Wi-Fi видно сразу
        val size = controller.frame?.let { " · ${it.height}p" } ?: ""
        PillText("${controller.shownFps} к/с$size", Modifier.clickable { screen.togglePillInfo(PillInfo.frames) })
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
                CameraList(controller)
                Box(Modifier.fillMaxWidth().height(1.dp).background(PanelLight))
                InfoTitle("Подключение по ${transportLabel(controller)}")
                controller.deviceInfo?.let { InfoRow("Камера", "${it.model}, прошивка ${it.firmware}") }
                if (wifi) {
                    details.cameraHost?.let { InfoRow("IP камеры", it) }
                    details.localHost?.let { InfoRow("IP планшета", it) }
                    details.signalDbm?.let { InfoRow("Сигнал Wi-Fi", "${it.toString().replace('-', '−')} дБм, ${signalWord(it)}") }
                    when (controller.liveViewQuality) {
                        LiveViewQuality.low -> InfoNote("Превью 640×360 — сигнала не хватает на полное качество", Color.White)
                        LiveViewQuality.high -> InfoNote("Превью 1024×576")
                        null -> Unit
                    }
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
                // сверху — качество превью: авто или вручную, и что сейчас; ниже — сводка о кадрах
                InfoTitle("Качество превью")
                QualityChoice(controller, enabled = wifi)
                controller.frame?.let { InfoRow("Сейчас", "${it.width}×${it.height}") }
                InfoNote(qualityNote(controller, wifi))
                Box(Modifier.fillMaxWidth().height(1.dp).background(PanelLight))
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

/** Выбор качества превью: «Авто», «1024×576», «640×360» — размеры по форме кадра (в фото 3:2 — «1024×683»). */
@Composable
private fun QualityChoice(controller: CameraController, enabled: Boolean) {
    val frame = controller.frame
    val aspect = if (frame != null && frame.height > 0) frame.width.toDouble() / frame.height else 16.0 / 9
    Row(Modifier.alpha(if (enabled) 1f else 0.45f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (mode in PreviewQualityMode.entries) {
            val label = mode.fixed?.sizeLabel(aspect) ?: "Авто"
            val current = controller.previewQualityMode == mode
            Text(label, color = if (current) Color.Black else Color.White, fontSize = 16.sp,
                fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                style = TextStyle(fontFeatureSettings = "tnum"),
                modifier = Modifier.background(if (current) Color.White else PanelLight, RoundedCornerShape(9.dp))
                    .semantics { contentDescription = "Качество превью: $label" }
                    .clickable(enabled = enabled) { controller.applyPreviewQuality(mode) }
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}

/** Что делает выбранное качество — как у программы для Mac. */
private fun qualityNote(controller: CameraController, wifi: Boolean): String = when {
    !wifi -> "По кабелю камера всегда отдаёт полное превью — выбор качества действует по Wi-Fi."
    controller.previewQualityMode == PreviewQualityMode.high -> "Всегда полное качество. На слабом Wi-Fi картинка может дёргаться."
    controller.previewQualityMode == PreviewQualityMode.low ->
        "Всегда облегчённое превью: вдвое меньше трафика и плавнее на слабом Wi-Fi, но мельче."
    controller.liveViewQuality == LiveViewQuality.low ->
        "Авто: сигнала не хватает на полное качество — превью облегчено. Полное вернётся само, когда канал потянет."
    else -> "Авто: полное качество, а когда Wi-Fi его не тянет — облегчённое, чтобы картинка шла плавно."
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

/** Слева сверху: гистограмма, закрепление строки состояния, пресеты (кнопка со списком), ☆+ — новый пресет, ⚙. */
@Composable
private fun TopBar(controller: CameraController, screen: ScreenState) {
    Row(
        Modifier.onGloballyPositioned { screen.topBarLeft = it.positionInRoot().x }
            .background(Panel, RoundedCornerShape(12.dp)).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).background(if (controller.histogramOn) Accent else PanelLight, RoundedCornerShape(9.dp))
                .clickable { controller.toggleHistogram() },
            contentAlignment = Alignment.Center,
        ) {
            TopBarIcon(TopBarGlyph.histogram)
        }
        Box(
            Modifier.size(44.dp).background(if (controller.waveformOn) Accent else PanelLight, RoundedCornerShape(9.dp))
                .semantics { contentDescription = "Волновая форма" }
                .clickable { controller.toggleWaveform() },
            contentAlignment = Alignment.Center,
        ) {
            TopBarIcon(TopBarGlyph.waveform)
        }
        // зебра, false color, пикинг и маркеры — меню внизу, на месте строки значений
        MonitorButton(screen)
        // строка состояния справа вверху видна вместе с элементами; кнопка закрепляет её — тогда остаётся и без них
        Box(
            Modifier.size(44.dp).background(if (controller.statusBarPinned) Accent else PanelLight, RoundedCornerShape(9.dp))
                .clickable { controller.toggleStatusBarPin() },
            contentAlignment = Alignment.Center,
        ) {
            TopBarIcon(TopBarGlyph.statusLine)
        }
        // одна кнопка вместо ряда: много пресетов не наезжают на строку состояния
        PresetButton(controller, screen, Modifier.onGloballyPositioned { screen.presetButtonLeft = it.positionInRoot().x })
        // текущий пресет изменён — «☆+» спрашивает, куда сохранить: в него или новым; иначе сразу «Новый пресет»
        val changed = controller.currentPresetIndex?.takeIf { controller.currentPresetModified }
            ?.let { controller.presets.getOrNull(it)?.name }
        Box(
            Modifier.size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
                .semantics {
                    contentDescription = changed?.let { "Сохранить настройки: в «$it» или новым пресетом" }
                        ?: "Новый пресет из текущих настроек"
                }
                .clickable(enabled = controller.phase == CameraController.Phase.ready) {
                    screen.closeOverlays()
                    if (changed != null) screen.savePresetOpen = true else screen.newPresetOpen = true
                },
            contentAlignment = Alignment.Center,
        ) {
            // сохранить текущие настройки — сразу с именем: звезда с плюсом
            TopBarIcon(TopBarGlyph.savePreset)
        }
        Box(
            Modifier.size(44.dp).background(PanelLight, RoundedCornerShape(9.dp))
                .semantics { contentDescription = "Настройки" }
                .clickable { screen.closeOverlays(); screen.settingsOpen = true },
            contentAlignment = Alignment.Center,
        ) {
            TopBarIcon(TopBarGlyph.settings)
        }
    }
}

/**
 * Панель отдельным слоем над видео — гистограмма, волновая форма: видна и при скрытых элементах, и при открытом меню Fn
 * (там она нужнее всего — меняют экспозицию; если меню или плашка увеличения её закрыли бы, она поднимается над ними).
 * Тащат за любое место — она чуть крупнее (1,03) и с тенью; отпустили ближе 24 dp к краю — прилипает к отступу 16 dp;
 * за экран не уходит. Место — доли свободного места: переживает поворот и перезапуск. Размер панели — как её разложили,
 * а не на глаз: с неверным размером у краёв она уходила бы за экран. defaultOrigin — где стоять, пока её не перетаскивали
 * (в пикселях, по размеру панели и экрана); onRect — где она сейчас (касания по ней не возвращают скрытые элементы).
 */
@Composable
private fun FloatingPanel(
    screen: ScreenState, feedback: FeedbackState, placement: FloatingPlacement?, onPlace: (FloatingPlacement) -> Unit,
    defaultOrigin: (panel: Size, view: Size) -> Offset, onRect: (Rect?) -> Unit, content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    var panel by remember { mutableStateOf(IntSize.Zero) }
    /** Где панель, пока её тащат; null — не тащат. */
    var drag by remember { mutableStateOf<Offset?>(null) }
    val position = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var placed by remember { mutableStateOf(false) }

    // место панели FloatingPlacement считает в dp — отступ 16 и прилипание 24, как на Mac; Compose — в пикселях
    fun dp(size: Size) = Size(size.width / density.density, size.height / density.density)
    fun dp(offset: Offset) = Offset(offset.x / density.density, offset.y / density.density)
    fun px(offset: Offset) = Offset(offset.x * density.density, offset.y * density.density)

    /** Куда положить панель сейчас: по сохранённому месту или на место по умолчанию, но не под меню Fn и не на кнопки справа. */
    fun goal(): Offset? {
        val view = feedback.screenSize
        if (panel == IntSize.Zero || view == Size.Zero) return null
        val size = panel.toSize()
        return with(density) {
            // легла бы на запись и спуск — встаёт левее колонки, на той же высоте
            fun besideColumn(at: Offset): Offset {
                val column = screen.rightColumnRect ?: return at
                return if (Rect(at, size).overlaps(column)) at.copy(x = maxOf(16.dp.toPx(), column.left - 12.dp.toPx() - size.width)) else at
            }
            val origin = placement?.let { px(it.origin(dp(size), dp(view))) } ?: defaultOrigin(size, view)
            // меню Fn и плашка увеличения её не закрывают: если легла бы под них — поднимается над ними
            var placed = besideColumn(origin)
            for (obstacle in listOfNotNull(screen.fnMenuRect, screen.zoomBadgeRect)) {
                if (Rect(placed, size).overlaps(obstacle)) placed = placed.copy(y = maxOf(16.dp.toPx(), obstacle.top - 10.dp.toPx() - size.height))
            }
            // поднялась — могла выйти на колонку
            besideColumn(placed)
        }
    }

    // место поменялось (поворот, лента, меню Fn, сохранили новое) — едем туда; в первый раз — сразу
    val target = goal()
    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        if (drag != null) return@LaunchedEffect
        if (placed) {
            position.animateTo(to, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
        } else {
            position.snapTo(to)
            placed = true
        }
    }
    DisposableEffect(Unit) { onDispose { onRect(null) } }

    fun drop() {
        val from = drag ?: return
        onPlace(FloatingPlacement.dropped(dp(from), dp(panel.toSize()), dp(feedback.screenSize)))
        scope.launch {
            position.snapTo(from)
            drag = null
            goal()?.let { position.animateTo(it, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) }
        }
    }

    val lifted by animateFloatAsState(if (drag != null) 1f else 0f, tween(150), label = "lift")
    Box(
        Modifier
            .offset { (drag ?: position.value).round() }
            .onGloballyPositioned {
                panel = it.size
                onRect(it.boundsInRoot())
            }
            .graphicsLayer {
                // пока размер не известен — не показываем: место считается от размера
                alpha = if (placed) 1f else 0f
                scaleX = 1f + 0.03f * lifted
                scaleY = 1f + 0.03f * lifted
                shadowElevation = 16.dp.toPx() * lifted
                shape = RoundedCornerShape(10.dp)
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        drag = position.value
                        feedback.haptic(HapticEvent.dragStart)
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag = px(FloatingPlacement.dragOrigin(dp((drag ?: position.value) + amount), dp(panel.toSize()),
                            dp(feedback.screenSize)))
                    },
                    onDragEnd = { drop() },
                    onDragCancel = { drop() },
                )
            },
    ) {
        content()
    }
}

/** Гистограмма; по умолчанию — как раньше, слева внизу над лентой снимков. */
@Composable
private fun FloatingHistogram(controller: CameraController, screen: ScreenState, feedback: FeedbackState) {
    val bins = controller.histogram ?: return
    val density = LocalDensity.current
    FloatingPanel(screen, feedback, controller.histogramPlacement, controller::placeHistogram,
        defaultOrigin = { size, view ->
            with(density) {
                // над лентой снимков: отступ экрана 18, над строкой значений — 76, до ленты — 10
                val strip = if (controller.shots.isNotEmpty()) (screen.stripHeight ?: 76.dp.toPx()) + 10.dp.toPx() else 0f
                Offset(18.dp.toPx(), view.height - 18.dp.toPx() - 76.dp.toPx() - strip - size.height)
            }
        },
        onRect = { screen.histogramRect = it },
    ) {
        HistogramView(bins, controller.histogramStats)
    }
}

/** Панель наложения; по умолчанию — справа вверху, под строкой состояния, левее кнопок справа. */
@Composable
private fun FloatingOverlay(controller: CameraController, screen: ScreenState, feedback: FeedbackState) {
    val reference = controller.reference ?: return
    val density = LocalDensity.current
    FloatingPanel(screen, feedback, controller.overlayPlacement, controller::placeOverlay,
        defaultOrigin = { size, view -> with(density) { Offset(view.width - 18.dp.toPx() - size.width, 84.dp.toPx()) } },
        onRect = { screen.overlayRect = it },
    ) {
        OverlayPanel(controller, reference)
    }
}

/** Волновая форма; по умолчанию — справа внизу, над строкой значений, левее кнопок справа. */
@Composable
private fun FloatingWaveform(controller: CameraController, screen: ScreenState, feedback: FeedbackState) {
    val trace = controller.waveform ?: return
    val density = LocalDensity.current
    FloatingPanel(screen, feedback, controller.waveformPlacement, controller::placeWaveform,
        defaultOrigin = { size, view ->
            with(density) { Offset(view.width - 18.dp.toPx() - size.width, view.height - 18.dp.toPx() - 76.dp.toPx() - size.height) }
        },
        onRect = { screen.waveformRect = it },
    ) {
        WaveformView(trace, controller.waveformMode) { controller.chooseWaveformMode(it) }
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
private fun SettingsPanel(controller: CameraController, modifier: Modifier, onDebug: () -> Unit, onPresets: () -> Unit) {
    val prefs = controller.prefs
    var destination by remember { mutableStateOf(prefs.destination) }
    var reviewEnabled by remember { mutableStateOf(prefs.reviewEnabled) }
    var reviewSeconds by remember { mutableIntStateOf(prefs.reviewSeconds) }
    var reviewSource by remember { mutableStateOf(prefs.reviewSource) }
    var autoHide by remember { mutableStateOf(prefs.autoHide) }
    var autoHideSeconds by remember { mutableIntStateOf(prefs.autoHideSeconds) }
    var wifiEnabled by remember { mutableStateOf(prefs.wifiEnabled) }
    // переключатель вибрации — только если у планшета есть мотор
    val context = LocalContext.current
    val hasVibrator = remember { Haptics.deviceHasVibrator(context) }
    var haptics by remember { mutableStateOf(prefs.hapticsEnabled) }
    val tally = controller.tallyStyle
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
        if (hasVibrator) {
            Spacer(Modifier.height(8.dp))
            Toggle("Вибрация при съёмке", haptics) { haptics = it; prefs.hapticsEnabled = it }
            Text("Спуск, запись и стоп-кадр отвечают и вибрацией — если она включена в настройках системы.",
                color = Secondary, fontSize = 13.sp)
        }
        Spacer(Modifier.height(8.dp))
        Toggle("Красная рамка во время записи", tally.enabled) { controller.applyTallyStyle(tally.copy(enabled = it)) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1f)) {
                val color = if (tally.enabled) Color.White else Secondary
                Text("Толщина: ${tally.width.roundToInt()} dp", color = color, fontSize = 16.sp)
                Slider(tally.width, { controller.applyTallyStyle(tally.copy(width = it.roundToInt().toFloat())) },
                    valueRange = 1f..12f, steps = 10, enabled = tally.enabled)
                Text("Яркость: ${(tally.opacity * 100).roundToInt()} %", color = color, fontSize = 16.sp)
                Slider(tally.opacity, { controller.applyTallyStyle(tally.copy(opacity = (it * 20).roundToInt() / 20f)) },
                    valueRange = 0.3f..1f, steps = 13, enabled = tally.enabled)
                Toggle("Мигать", tally.blink, enabled = tally.enabled) { controller.applyTallyStyle(tally.copy(blink = it)) }
            }
            // образец вживую: тёмный кадр с рамкой как есть
            Box(Modifier.size(160.dp, 90.dp).background(Color(0xFF101012), RoundedCornerShape(4.dp))) {
                TallyBorder(recording = true, style = tally, picture = { null })
            }
        }
        Text("Рамка — по краю картинки, видна и при скрытых элементах; «● REC» в строке состояния остаётся и без неё.",
            color = Secondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Text("Wi-Fi", color = Secondary, fontSize = 15.sp)
        Toggle("Подключаться по Wi-Fi, когда камеры нет на кабеле", wifiEnabled) { wifiEnabled = it; prefs.wifiEnabled = it }
        Text("Качество превью по Wi-Fi", color = Color.White, fontSize = 16.sp)
        QualityChoice(controller, enabled = true)
        Text("Авто — 1024×576, а когда сигнала не хватает — 640×360: картинка идёт плавнее. 1024×576 и 640×360 — всегда это " +
            "качество. То же — по касанию счётчика кадров в строке состояния.", color = Secondary, fontSize = 13.sp)
        val paired = controller.cameraBook.cameras.filter { it.paired }
        if (paired.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text((if (paired.size == 1) "Сопряжена: " else "Сопряжены: ") + paired.joinToString(", ") { it.name },
                    color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text("Забыть сопряжение", color = Accent, fontSize = 16.sp,
                    modifier = Modifier.clickable { controller.forgetPairing() }.padding(8.dp))
            }
            Text("Камеры перестанут узнавать планшет — их придётся сопрячь заново; имена в списке камер останутся.",
                color = Secondary, fontSize = 13.sp)
        } else {
            Text("С камерами по Wi-Fi ещё не сопрягались", color = Secondary, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onPresets).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Пресеты…", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("›", color = Accent, fontSize = 22.sp)
        }
        Text("Имена, порядок и что меняет каждый пресет. То же — долгое нажатие на кнопку пресетов вверху.",
            color = Secondary, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable(onClick = onDebug).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Отладка", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
            Text("›", color = Accent, fontSize = 22.sp)
        }
        Text("Связь, превью, камера, свойства и журнал событий — поверх экрана. То же — долгое нажатие на плашку состояния.",
            color = Secondary, fontSize = 13.sp)
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
private fun Toggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = if (enabled) Color.White else Secondary, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled)
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

