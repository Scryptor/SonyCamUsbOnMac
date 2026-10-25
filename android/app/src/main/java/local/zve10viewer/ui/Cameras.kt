package local.zve10viewer.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.zve10viewer.CameraController
import local.zve10viewer.settings.CameraEntry
import local.zve10viewer.settings.CameraState
import local.zve10viewer.settings.SettingID

/** «Забыть» — красным, как у системы. */
private val Danger = Color(0xFFFF453A)

/**
 * «Камеры»: текущая, видимые и знакомые — имя, как подключена, состояние и кнопка («Подключить», «Сопрячь»). Долгое
 * нажатие на знакомую — «Переименовать…» и «Забыть» (у текущей — только имя). pairButton — внизу «Сопрячь новую
 * камеру…». Пока список на экране, программа ищет камеры в сети: сразу и раз в 10 с.
 */
@Composable
fun CameraList(controller: CameraController, modifier: Modifier = Modifier, pairButton: Boolean = true, titleSize: TextUnit = 18.sp,
               emptyNote: String? = null) {
    LaunchedEffect(Unit) {
        while (true) {
            controller.searchCameras()
            delay(10_000)
        }
    }
    var renaming by remember { mutableStateOf<CameraEntry?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Камеры", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.SemiBold)
        // камер нет — что делать: под заголовком, перед кнопкой сопряжения
        if (controller.cameraEntries.isEmpty()) emptyNote?.let {
            Text(it, color = Secondary, fontSize = 16.sp, lineHeight = 22.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        controller.cameraEntries.forEach { entry -> CameraRow(controller, entry) { renaming = entry } }
        if (pairButton) {
            val pairing = controller.isPairing
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (pairing) "Отмена" else "Сопрячь новую камеру…", color = Accent, fontSize = 16.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .clickable { if (pairing) controller.cancelPairing() else controller.startPairing() }
                        .padding(horizontal = 4.dp, vertical = 8.dp))
                if (pairing) CircularProgressIndicator(Modifier.size(18.dp), color = Secondary, strokeWidth = 2.dp)
            }
            controller.wifiStatus?.takeIf { pairing }?.let { Text(it, color = Amber, fontSize = 14.sp, lineHeight = 19.sp) }
        }
    }
    renaming?.let { entry ->
        RenameCameraDialog(entry.name, onSave = { name ->
            controller.renameCamera(entry.id, name)
            renaming = null
        }) { renaming = null }
    }
}

/** Строка камеры. Точка — текущая: зелёная на связи, оранжевая — подключаю; синяя — в ленте на связи. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CameraRow(controller: CameraController, entry: CameraEntry, onRename: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val current = entry.state == CameraState.current
    Box {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                // у незнакомой камеры (ещё не подключались) ни имени, ни памяти — меню нет
                .combinedClickable(onClick = {}, onLongClick = { if (entry.known != null) menu = true })
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(8.dp).background(
                when {
                    entry.state == CameraState.side && entry.ready -> Accent
                    !current -> Color.Transparent
                    entry.ready -> Color(0xFF34C759)
                    else -> Amber
                }, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(entry.name, color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${entry.detail} · ${entry.stateLabel}", color = Secondary, fontSize = 14.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            entry.action?.let { action ->
                Text(action, color = Color.White, fontSize = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(if (entry.state == CameraState.needsPairing) PanelLight else Accent)
                        .clickable { controller.selectCamera(entry.id) }.padding(horizontal = 12.dp, vertical = 7.dp))
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (entry.canAddToStrip) {
                DropdownMenuItem(text = { Text("В ленту") }, onClick = {
                    menu = false
                    controller.addToStrip(entry.id)
                })
            }
            if (entry.canRemoveFromStrip) {
                DropdownMenuItem(text = { Text("Убрать из ленты") }, onClick = {
                    menu = false
                    controller.removeFromStrip(entry.id)
                })
            }
            DropdownMenuItem(text = { Text("Переименовать…") }, onClick = {
                menu = false
                onRename()
            })
            // главную (она на связи) и камеру ленты (сначала — убрать) забыть нельзя
            if (entry.canForget) {
                DropdownMenuItem(text = { Text("Забыть", color = Danger) }, onClick = {
                    menu = false
                    controller.forgetCamera(entry.id)
                })
            }
        }
    }
}

/** Имя камеры: прежнее подставлено и выделено — можно сразу печатать своё. Пустое не сохраняется. */
@Composable
private fun RenameCameraDialog(name: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var field by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Имя камеры") },
        text = {
            OutlinedTextField(field, { field = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (field.text.isNotBlank()) onSave(field.text) }))
            LaunchedEffect(Unit) {
                // поле ещё не в окне — без фокуса, но не падаем
                if (runCatching { focus.requestFocus() }.isSuccess) keyboard?.show()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(field.text) }, enabled = field.text.isNotBlank()) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Промежуток между плитками ленты. */
private val StripSpacing = 8.dp

/**
 * Лента камер — колонка слева под верхней панелью: главная (выделена) и камеры ленты, в порядке знакомых камер. Плитка —
 * превью, имя, «● REC» во время записи, заряд, если 20 % и меньше, «нет связи» поверх последнего кадра. Касание камеры
 * ленты — она главная, на своём месте: плитки не переставляются. Долгое нажатие поднимает плитку (у камеры ленты — ещё и
 * меню «Сделать главной», «Убрать из ленты»); повели пальцем — плитка едет за ним, соседние раздвигаются, отпустили — она
 * на новом месте. Цепочка справа внизу — связать камеру с главной: горит — связана; у главной цепочка (без кнопки) —
 * когда хоть одна связана.
 */
@Composable
fun CameraStrip(controller: CameraController, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val drag = remember { StripDrag(scope, { stripTiles(controller) }, controller::moveInStrip) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(StripSpacing)) {
        val battery = controller.property(SettingID.battery)?.current?.intValue?.toInt()?.takeIf { it in 0..100 }
        val book = controller.cameraBook
        val main = controller.currentCameraId
        val sides = controller.sideCameras.associateBy { it.id }
        stripTiles(controller).forEach { id ->
            // у каждой камеры своя плитка: переставили — она едет на новое место вместе с жестом и меню
            key(id) {
                val side = id?.takeIf { it != main }?.let(sides::get)
                if (side == null) {
                    StripTile(id, drag, controller.frame, controller.cameraName, main = true,
                        recording = controller.recordingStartedAt != null, battery = battery,
                        connected = controller.phase == CameraController.Phase.ready,
                        linked = controller.sideCameras.any { book.find(it.id)?.linked == true })
                } else {
                    val linked = book.find(side.id)?.linked == true
                    StripTile(id, drag, side.frame, book.find(side.id)?.name ?: side.name, main = false, recording = side.recording,
                        battery = side.battery, connected = side.connected, linked = linked,
                        onMain = { controller.makeMain(side.id) }, onRemove = { controller.removeFromStrip(side.id) },
                        onLink = { controller.setLinked(side.id, !linked) })
                }
            }
        }
    }
}

/**
 * Плитки ленты сверху вниз: главная и камеры ленты — в порядке знакомых камер. null — главная, чей id ещё не известен
 * (подключается, DeviceInfo не пришёл): она первой.
 */
private fun stripTiles(controller: CameraController): List<String?> {
    val sides = controller.sideCameras.map { it.id }
    val main = controller.currentCameraId ?: return listOf(null) + controller.cameraBook.stripOrder(sides)
    return controller.cameraBook.stripOrder((listOf(main) + sides).distinct())
}

/**
 * Перетаскивание в ленте. Поднятая плитка едет за пальцем; её середина прошла середину соседней — они сразу меняются
 * местами в книге камер (move), а сдвиг плитки уменьшается на одно место: она остаётся под пальцем, соседняя плавно
 * съезжает на освободившееся место. Отпустили — плитка плавно встаёт на своё. tiles — плитки сверху вниз сейчас. Без
 * экрана — чтобы проверять тестами.
 */
internal class StripDrag(
    private val scope: CoroutineScope,
    private val tiles: () -> List<String?>,
    private val move: (String, String) -> Unit,
) {
    /** Какую плитку держат пальцем; null — никакую. */
    var lifted by mutableStateOf<String?>(null)
        private set
    /** Плитка поверх соседних: поднятая, а отпущенная — пока едет на место. */
    var onTop by mutableStateOf<String?>(null)
        private set
    /** Насколько плитка видна ниже своего места в ленте (выше — меньше нуля), пикс. */
    private val shifts = mutableStateMapOf<String, Float>()
    private val slides = HashMap<String, Job>()
    /** Шаг ленты: высота плитки и промежуток, пикс. */
    private var pitch = 0f
    /** Где палец относительно места поднятой плитки — без ограничения краями ленты. */
    private var finger = 0f

    fun shift(id: String?): Float = id?.let { shifts[it] } ?: 0f

    fun lift(id: String, pitch: Float) {
        slides.remove(id)?.cancel()
        this.pitch = pitch
        finger = shift(id)
        lifted = id
        onTop = id
    }

    fun drag(id: String, dy: Float) {
        if (lifted != id || pitch <= 0f) return
        finger += dy
        while (true) {
            val order = tiles()
            val index = order.indexOf(id)
            val step = if (finger > pitch) 1 else if (finger < -pitch) -1 else 0
            if (index < 0 || step == 0) break
            // главную без id (null) не двигаем; переставить не вышло — плитка просто над соседней
            val other = order.getOrNull(index + step) ?: break
            move(id, other)
            if (tiles().indexOf(id) != index + step) break
            finger -= step * pitch
            slide(other, step * pitch)
        }
        val order = tiles()
        val index = order.indexOf(id)
        if (index < 0) return
        // за край ленты плитка не уходит; палец вернётся — поедет снова, когда дойдёт до неё
        shifts[id] = finger.coerceIn(-index * pitch, (order.size - 1 - index) * pitch)
    }

    fun drop(id: String) {
        if (lifted == id) lifted = null
        slide(id, 0f) { if (onTop == id) onTop = null }
    }

    /** Место плитки сдвинулось на by, а видна она пока там, где была: оттуда — плавно на место. */
    private fun slide(id: String, by: Float, done: () -> Unit = {}) {
        slides.remove(id)?.cancel()
        val from = shift(id) + by
        if (from == 0f) {
            shifts.remove(id)
            done()
            return
        }
        shifts[id] = from
        slides[id] = scope.launch {
            animate(from, 0f, animationSpec = tween(200)) { value, _ -> shifts[id] = value }
            shifts.remove(id)
            done()
        }
    }
}

/**
 * Жесты плитки ленты. Касание — onTap. Долгое нажатие — onLift; повели пальцем дальше touch slop — onDragStart, потом
 * onDrag: на сколько палец сдвинулся по вертикали (первый раз — от места долгого нажатия); отпустили или жест прервали —
 * onDrop. Касание, которое забрала вложенная кнопка (цепочка), — не наше. press — нажатие для отклика на плитке.
 */
private suspend fun PointerInputScope.stripTileGestures(
    press: MutableInteractionSource,
    onTap: () -> Unit,
    onLift: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDrop: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown()
        val pressed = PressInteraction.Press(down.position)
        press.tryEmit(pressed)
        var up: PointerInputChange? = null
        // раньше срока палец отпустили (up) или жест прервали — увели за край плитки, забрал другой: не долгое нажатие
        val held = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { up = waitForUpOrCancellation() } == null
        val tap = up
        if (!held) {
            press.tryEmit(if (tap != null) PressInteraction.Release(pressed) else PressInteraction.Cancel(pressed))
            if (tap != null) {
                tap.consume()
                onTap()
            }
            return@awaitEachGesture
        }
        press.tryEmit(PressInteraction.Release(pressed))
        onLift()
        var moved = Offset.Zero
        var dragging = false
        try {
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                // плитка поднята — палец её: ни кнопкам под ним, ни видео
                event.changes.forEach { it.consume() }
                if (!change.pressed) break
                val step = change.position - change.previousPosition
                if (dragging) {
                    onDrag(step.y)
                } else {
                    moved += step
                    if (moved.getDistance() > slop) {
                        dragging = true
                        onDragStart()
                        onDrag(moved.y)
                    }
                }
            }
        } finally {
            onDrop()
        }
    }
}

/**
 * id — камера в книге: по нему плитку перетаскивают; null — главная ещё подключается, её не тащат. linked — у камеры
 * ленты: связана с главной; у главной — ведёт группу.
 */
@Composable
private fun StripTile(id: String?, drag: StripDrag, frame: ImageBitmap?, name: String, main: Boolean, recording: Boolean,
                      battery: Int?, connected: Boolean, linked: Boolean, onMain: () -> Unit = {}, onRemove: () -> Unit = {},
                      onLink: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    // жест у плитки один на всё время в ленте: главная она сейчас или нет — узнаёт по ходу
    val isMain by rememberUpdatedState(main)
    val makeMain by rememberUpdatedState(onMain)
    val lift by animateFloatAsState(if (id != null && drag.lifted == id) 1f else 0f, tween(150), label = "lift")
    val description = when {
        !main -> "$name — в ленте"
        linked -> "$name — главная, ведёт группу"
        else -> "$name — главная"
    }
    Box(
        Modifier.zIndex(if (id != null && drag.onTop == id) 1f else 0f).then(
            // жест — снаружи слоя плитки: её сдвиг и увеличение не искажают, сколько прошёл палец
            if (id == null) Modifier else Modifier.pointerInput(Unit) {
                stripTileGestures(press,
                    // у главной касание ничего не делает: она уже на большом экране
                    onTap = { if (!isMain) makeMain() },
                    onLift = {
                        drag.lift(id, size.height + StripSpacing.toPx())
                        // у главной меню нет — её только перетаскивают
                        if (!isMain) menu = true
                    },
                    onDragStart = { menu = false },
                    onDrag = { drag.drag(id, it) },
                    onDrop = { drag.drop(id) },
                )
            }),
    ) {
        Box(
            Modifier
                .graphicsLayer {
                    translationY = drag.shift(id)
                    scaleX = 1f + 0.05f * lift
                    scaleY = 1f + 0.05f * lift
                    shadowElevation = 16.dp.toPx() * lift
                    shape = RoundedCornerShape(10.dp)
                }
                .size(width = 200.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(if (main) 3.dp else 1.dp, if (main) Accent else PanelLight, RoundedCornerShape(10.dp))
                // отклик на касание — у камеры ленты: у главной касание ничего не делает
                .then(if (main) Modifier else Modifier.indication(press, LocalIndication.current))
                // для TalkBack плитка — одна, с именем внутри, как была с combinedClickable
                .semantics(mergeDescendants = true) {
                    contentDescription = description
                    if (!main) {
                        onClick { onMain(); true }
                        onLongClick { menu = true; true }
                    }
                },
        ) {
            frame?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().alpha(if (connected) 1f else 0.35f))
            }
            if (!connected) {
                Text("нет связи", color = Color.White, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center)
                    .background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
            }
            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Panel).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontWeight = if (main) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                if (recording) Text("● REC", color = Color(0xFFFF453A), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                battery?.takeIf { it <= 20 }?.let { Text("$it%", color = Amber, fontSize = 12.sp) }
            }
            // цепочка: у камеры ленты — кнопка (своё касание: камеру главной не делает), у главной — значок, когда она
            // ведёт группу
            if (!main || linked) {
                val button = if (main) Modifier else Modifier.clip(CircleShape).clickable(onClick = onLink)
                    .semantics { contentDescription = if (linked) "Отвязать от главной" else "Связать с главной" }
                Box(Modifier.align(Alignment.BottomEnd).padding(bottom = 22.dp).then(button).padding(5.dp).size(30.dp)
                    .background(if (linked && !main) Accent else Panel, CircleShape), contentAlignment = Alignment.Center) {
                    ChainGlyph(when {
                        main -> Accent
                        linked -> Color.White
                        else -> Color.White.copy(alpha = 0.6f)
                    }, Modifier.size(18.dp))
                }
            }
        }
        DropdownMenu(menu, onDismissRequest = { menu = false }) {
            if (connected) {
                DropdownMenuItem(text = { Text("Сделать главной") }, onClick = {
                    menu = false
                    onMain()
                })
            }
            DropdownMenuItem(text = { Text("Убрать из ленты") }, onClick = {
                menu = false
                onRemove()
            })
        }
    }
}

/** Два звена цепи наискось. */
@Composable
private fun ChainGlyph(color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.minDimension
        val link = Size(w * 0.56f, w * 0.3f)
        val corner = CornerRadius(link.height / 2)
        val stroke = Stroke(maxOf(w * 0.1f, 1.5.dp.toPx()))
        rotate(-45f) {
            val y = center.y - link.height / 2
            drawRoundRect(color, Offset(center.x - link.width * 0.82f, y), link, corner, style = stroke)
            drawRoundRect(color, Offset(center.x - link.width * 0.18f, y), link, corner, style = stroke)
        }
    }
}
