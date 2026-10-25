package local.zve10viewer.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.delay
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
fun CameraList(controller: CameraController, modifier: Modifier = Modifier, pairButton: Boolean = true, titleSize: TextUnit = 18.sp) {
    LaunchedEffect(Unit) {
        while (true) {
            controller.searchCameras()
            delay(10_000)
        }
    }
    var renaming by remember { mutableStateOf<CameraEntry?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Камеры", color = Color.White, fontSize = titleSize, fontWeight = FontWeight.SemiBold)
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

/**
 * Лента камер — колонка слева под верхней панелью: главная (выделена) и камеры ленты. Плитка — превью, имя, «● REC» во
 * время записи, заряд, если 20 % и меньше, «нет связи» поверх последнего кадра. Касание камеры ленты — она главная;
 * долгое нажатие — «Сделать главной», «Убрать из ленты».
 */
@Composable
fun CameraStrip(controller: CameraController, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val battery = controller.property(SettingID.battery)?.current?.intValue?.toInt()?.takeIf { it in 0..100 }
        StripTile(controller.frame, controller.cameraName, main = true, recording = controller.recordingStartedAt != null,
            battery = battery, connected = controller.phase == CameraController.Phase.ready)
        controller.sideCameras.forEach { side ->
            StripTile(side.frame, controller.cameraBook.find(side.id)?.name ?: side.name, main = false, recording = side.recording,
                battery = side.battery, connected = side.connected,
                onMain = { controller.makeMain(side.id) }, onRemove = { controller.removeFromStrip(side.id) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripTile(frame: ImageBitmap?, name: String, main: Boolean, recording: Boolean, battery: Int?, connected: Boolean,
                      onMain: () -> Unit = {}, onRemove: () -> Unit = {}) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(width = 200.dp, height = 112.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black)
                .border(if (main) 3.dp else 1.dp, if (main) Accent else PanelLight, RoundedCornerShape(10.dp))
                // у главной — ни касания, ни меню: она уже на большом экране
                .combinedClickable(enabled = !main, onClick = onMain, onLongClick = { menu = true })
                .semantics { contentDescription = if (main) "$name — главная" else "$name — в ленте" },
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
