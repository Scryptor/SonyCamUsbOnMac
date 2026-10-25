package local.zve10viewer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.zve10viewer.CameraController
import local.zve10viewer.settings.Preset
import local.zve10viewer.settings.PresetCatalog
import local.zve10viewer.settings.PresetChange
import local.zve10viewer.settings.PresetLibrary
import local.zve10viewer.settings.SettingID
import local.zve10viewer.settings.SettingOption
import local.zve10viewer.settings.ValueFormatter
import local.zve10viewer.settings.WhiteBalanceTemperature

/** Удалить — красным, как у системы. */
private val Danger = Color(0xFFFF453A)

/** Изменения на камере одной строкой: «ISO 800 → 1600 · 1/50 → 1/100». */
private fun List<PresetChange>.labels(): String = joinToString(" · ") { it.label }

/**
 * Кнопка пресетов в верхней панели: «2 · Вечер ▾» — текущий пресет (см. CameraController.currentPresetIndex), иначе
 * «Пресеты ▾». Изменили на камере то, что он ставит, — оранжевая точка перед ▾. Касание — список под кнопкой, долгое —
 * сразу окно пресетов.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PresetButton(controller: CameraController, screen: ScreenState, modifier: Modifier = Modifier) {
    val current = controller.currentPresetIndex
    val changes = controller.currentPresetChanges
    // длинное имя не дотягивает панель до строки состояния справа (она шире во время записи): справа от кнопки ещё ☆+
    // и ⚙ — 106 dp, и зазор 12
    val room = with(LocalDensity.current) {
        if (screen.statusPillLeft > 0f) (screen.statusPillLeft - screen.presetButtonLeft).toDp() - 118.dp else 320.dp
    }
    Row(
        modifier.height(44.dp).widthIn(max = room.coerceIn(120.dp, 320.dp))
            .background(if (screen.presetMenuOpen) Accent else PanelLight, RoundedCornerShape(9.dp))
            .combinedClickable(onClick = { screen.togglePresetMenu() }, onLongClick = { screen.openPresets(current ?: 0) })
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val name = current?.let { controller.presets.getOrNull(it)?.name }
        // длинное имя сокращается, а ● и ▾ остаются
        Text(if (current != null && name != null) "${current + 1} · $name" else "Пресеты", color = Color.White, fontSize = 16.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        if (name != null && changes.isNotEmpty()) {
            Text(" ●", color = Amber, fontSize = 12.sp,
                modifier = Modifier.semantics { contentDescription = "«$name» изменён: ${changes.labels()}" })
        }
        Text(" ▾", color = Color.White, fontSize = 16.sp)
    }
}

/**
 * Список пресетов под кнопкой — пресеты режима камеры (у фото и у видео свои). Галочка — у каждого, что стоит на камере;
 * у изменённого текущего — «изменён». Касание строки применяет пресет и закрывает список; пока пресет применяется или
 * камера не готова, строки неактивны. Внизу — «Сохранить в «Вечер»» (только когда текущий изменён), «Новый пресет…»
 * и «Настроить…» (окно пресетов).
 */
@Composable
fun PresetMenu(controller: CameraController, screen: ScreenState, modifier: Modifier = Modifier) {
    val ready = controller.phase == CameraController.Phase.ready
    val current = controller.currentPresetIndex
    val changes = controller.currentPresetChanges
    Column(
        modifier.width(440.dp).background(Color(0xF71C1C1E), RoundedCornerShape(14.dp))
            // касания по списку не уходят к видео под ним: оно закрыло бы список
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(8.dp),
    ) {
        Text(PresetLibrary.title(controller.presetMode), color = Secondary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp))
        if (controller.presets.isEmpty()) {
            Text(PresetLibrary.emptyHint(controller.presetMode), color = Secondary, fontSize = 15.sp, lineHeight = 20.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        } else {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                controller.presets.forEachIndexed { index, preset ->
                    PresetRow(index + 1, preset, active = PresetCatalog.matches(preset, controller.properties), selected = false,
                        enabled = ready && !controller.isApplyingPreset, modified = index == current && changes.isNotEmpty()) {
                        screen.presetMenuOpen = false
                        controller.applyPreset(index)
                    }
                }
            }
        }
        HorizontalDivider(color = PanelLight, modifier = Modifier.padding(vertical = 6.dp))
        val name = current?.let { controller.presets.getOrNull(it)?.name }
        if (name != null && changes.isNotEmpty()) {
            // куда сохранится — видно по самой строке; под ней — что именно поменяли на камере
            val enabled = ready && !controller.isApplyingPreset
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled) {
                screen.presetMenuOpen = false
                controller.savePresetChanges()
            }.alpha(if (enabled) 1f else 0.4f).padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text("Сохранить в «$name»", color = Color.White, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(changes.labels(), color = Secondary, fontSize = 14.sp, lineHeight = 18.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        MenuAction("Новый пресет…", enabled = ready) {
            screen.presetMenuOpen = false
            screen.newPresetOpen = true
        }
        MenuAction("Настроить…") { screen.openPresets(current ?: 0) }
    }
}

@Composable
private fun MenuAction(title: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(title, color = if (enabled) Color.White else Secondary, fontSize = 16.sp, modifier = Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(10.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp))
}

/**
 * Строка пресета — в списке под кнопкой и в окне пресетов: номер, имя, кратко значения, галочка у того, что стоит на
 * камере; у изменённого текущего (modified) — «изменён».
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(number: Int, preset: Preset, active: Boolean, selected: Boolean, enabled: Boolean,
                      modifier: Modifier = Modifier, modified: Boolean = false, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(if (selected) Accent else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("$number", color = if (selected) Color.White else Secondary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center, style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.width(24.dp))
        Column(Modifier.weight(1f)) {
            Text(preset.name, color = Color.White, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(PresetCatalog.summary(preset), color = if (selected) Color.White.copy(alpha = 0.8f) else Secondary, fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Text("✓", color = if (selected) Color.White else Accent, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        else if (modified) Text("изменён", color = if (selected) Color.White else Amber, fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold)
    }
}

/**
 * «Сохранить настройки» — «☆+», когда текущий пресет изменён: в него («Сохранить в «Вечер»») или новым пресетом
 * (onNew — дальше окошко «Новый пресет»). Текущего не стало (связь пропала) — окошко закрывается само.
 */
@Composable
fun SavePresetDialog(controller: CameraController, onNew: () -> Unit, onDismiss: () -> Unit) {
    val name = controller.currentPresetIndex?.let { controller.presets.getOrNull(it)?.name }
    val ready = controller.phase == CameraController.Phase.ready
    LaunchedEffect(name, ready) {
        if (name == null || !ready) onDismiss()
    }
    name ?: return
    val changes = controller.currentPresetChanges
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Сохранить настройки") },
        text = {
            Text(if (changes.isEmpty()) "Камера настроена, как в «$name»." else "Камера отличается от «$name»: ${changes.labels()}.",
                fontSize = 16.sp, lineHeight = 22.sp)
        },
        confirmButton = {
            Button(onClick = {
                controller.savePresetChanges()
                onDismiss()
            }, enabled = ready && !controller.isApplyingPreset) {
                Text("Сохранить в «$name»", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onNew, enabled = ready) { Text("Новый пресет…") }
                TextButton(onClick = onDismiss) { Text("Отмена") }
            }
        },
    )
}

/**
 * «Новый пресет»: имя сразу — подставлено «Пресет N» и выделено, можно сразу печатать; под ним — для какого режима он
 * и что в него войдёт.
 */
@Composable
fun NewPresetDialog(controller: CameraController, onDismiss: () -> Unit) {
    val initial = remember { controller.newPresetName }
    var name by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val ready = controller.phase == CameraController.Phase.ready
    fun save() {
        if (controller.savePreset(name.text) != null) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Новый пресет") },
        text = {
            val focus = remember { FocusRequester() }
            val keyboard = LocalSoftwareKeyboardController.current
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { save() }))
                Text(PresetLibrary.forMode(controller.presetMode) + ": " +
                    PresetCatalog.summary(PresetCatalog.capture(name.text, controller.properties)),
                    color = Secondary, fontSize = 14.sp, lineHeight = 19.sp)
            }
            LaunchedEffect(Unit) {
                // поле ещё не в окне — без фокуса, но не падаем
                if (runCatching { focus.requestFocus() }.isSuccess) keyboard?.show()
            }
        },
        confirmButton = { TextButton(onClick = { save() }, enabled = ready) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/**
 * Окно пресетов поверх экрана, как «Настройки» — пресеты режима камеры (у фото и у видео свои). Слева — список: номер,
 * имя, кратко значения, галочка у каждого, что стоит на камере; «Выше» / «Ниже» двигают выбранный; «+ Новый пресет» —
 * новый пресет с камеры. Справа — выбранный: имя (правится на месте), «Применить», «Взять с камеры», «Дублировать»,
 * «Удалить» и его настройки группами. Всё сохраняется сразу, кнопки «Сохранить» нет. Закрывают ×, «Назад» и касание
 * мимо панели.
 */
@Composable
fun PresetsOverlay(controller: CameraController, screen: ScreenState) {
    val presets = controller.presets
    val selected = screen.presetsSelected.coerceIn(0, maxOf(0, presets.lastIndex))
    val online = controller.phase == CameraController.Phase.ready
    // новый пресет с камеры: имя сразу выделено — можно печатать
    var focusName by remember { mutableStateOf(false) }
    // сменился режим камеры — в окне другой список: выбран его текущий пресет, иначе первый
    val mode = controller.presetMode
    var listMode by remember { mutableStateOf(mode) }
    LaunchedEffect(mode) {
        if (mode == listMode) return@LaunchedEffect
        listMode = mode
        screen.presetsSelected = controller.currentPresetIndex ?: 0
    }
    BackHandler { screen.presetsOpen = false }
    Box(
        // пока держат «Посмотреть» — окна не видно, но палец оно не отпускает
        Modifier.fillMaxSize().alpha(if (screen.presetPeeking) 0f else 1f).background(Color(0x99000000))
            .pointerInput(Unit) { detectTapGestures { screen.presetsOpen = false } },
        contentAlignment = Alignment.Center,
    ) {
        // непрозрачная: под окном на экране ожидания — крупный белый текст, он просвечивал бы сквозь значения
        Column(
            Modifier.fillMaxWidth(0.92f).fillMaxHeight(0.92f).background(Color(0xFF2C2C2E), RoundedCornerShape(18.dp))
                .pointerInput(Unit) { detectTapGestures { } },
        ) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(PresetLibrary.title(mode), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                // что сделала программа: «Применяю…», «применено 12 из 12», «Сохранено» — окно закрывает строку под панелью
                Text(controller.statusMessage ?: "", color = Secondary, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End, modifier = Modifier.weight(1f))
                Text("×", color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).semantics { contentDescription = "Закрыть" }
                        .clickable { screen.presetsOpen = false }.padding(top = 1.dp))
            }
            if (!online) {
                Text("Камера не на связи — значения видно, выбирать их можно, когда камера подключена", color = Color.White,
                    fontSize = 15.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp).fillMaxWidth()
                        .background(Color(0x33FF9F0A), RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
            }
            HorizontalDivider(color = PanelLight)
            Row(Modifier.weight(1f)) {
                PresetList(controller, screen, selected, onCreated = { index ->
                    screen.presetsSelected = index
                    focusName = true
                }, Modifier.width(340.dp).fillMaxHeight())
                Box(Modifier.width(1.dp).fillMaxHeight().background(PanelLight))
                if (presets.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(PresetLibrary.emptyHint(mode), color = Secondary, fontSize = 17.sp, lineHeight = 23.sp,
                            textAlign = TextAlign.Center)
                    }
                } else {
                    PresetEditor(controller, screen, selected, focusName, onNameFocused = { focusName = false },
                        Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

/**
 * Левая часть окна: пресеты по порядку, галочка — у каждого, что стоит на камере, «изменён» — у изменённого текущего;
 * «Выше» / «Ниже» переставляют выбранный, внизу — «+ Новый пресет» из текущих настроек камеры (сразу выбран, имя выделено).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PresetList(controller: CameraController, screen: ScreenState, selected: Int, onCreated: (Int) -> Unit, modifier: Modifier) {
    val presets = controller.presets
    val current = controller.currentPresetIndex
    val modified = controller.currentPresetModified
    // выбранный (и переставленный) — всегда на виду
    val selectedRow = remember { BringIntoViewRequester() }
    LaunchedEffect(selected, presets.size) {
        withFrameNanos { }
        selectedRow.bringIntoView()
    }
    Column(modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            presets.forEachIndexed { index, preset ->
                PresetRow(index + 1, preset, active = PresetCatalog.matches(preset, controller.properties), selected = index == selected,
                    enabled = true,
                    modifier = if (index == selected) Modifier.bringIntoViewRequester(selectedRow) else Modifier,
                    modified = modified && index == current) {
                    screen.presetsSelected = index
                }
            }
        }
        if (presets.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WindowButton("Выше", Modifier.weight(1f), enabled = selected > 0) {
                    controller.movePreset(selected, selected - 1)
                    screen.presetsSelected = selected - 1
                }
                WindowButton("Ниже", Modifier.weight(1f), enabled = selected < presets.lastIndex) {
                    controller.movePreset(selected, selected + 1)
                    screen.presetsSelected = selected + 1
                }
            }
        }
        WindowButton("+ Новый пресет", Modifier.fillMaxWidth(), enabled = controller.phase == CameraController.Phase.ready,
            accent = true) {
            controller.savePreset()?.let(onCreated)
        }
    }
}

/**
 * Правая часть окна: выбранный пресет. Имя правится на месте, сразу; кнопки «Применить» и «Взять с камеры» — только при
 * камере на связи. Выбран изменённый текущий — строка, что поменяли на камере. Ниже — настройки группами: у каждой
 * переключатель «менять» и значение.
 */
@Composable
private fun PresetEditor(controller: CameraController, screen: ScreenState, index: Int, focusName: Boolean, onNameFocused: () -> Unit,
                         modifier: Modifier) {
    // пресет удалили — пока окно перестраивается, номер мог устареть
    val preset = controller.presets.getOrNull(index) ?: return
    val online = controller.phase == CameraController.Phase.ready
    val changes = if (controller.currentPresetIndex == index) controller.currentPresetChanges else emptyList()
    var deleting by remember { mutableStateOf(false) }
    Column(modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PresetNameField(controller, index, preset.name, focusName, onNameFocused)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val free = online && !controller.isApplyingPreset
            WindowButton("Применить", enabled = free, accent = true) { controller.applyPreset(index) }
            PeekButton(controller, screen, index, enabled = free)
            WindowButton("Взять с камеры", enabled = free) { controller.recapturePreset(index) }
            // копия встаёт следом и сразу выбрана
            WindowButton("Дублировать") { controller.duplicatePreset(index)?.let { screen.presetsSelected = it } }
            Box(Modifier.weight(1f))
            WindowButton("Удалить", destructive = true) { deleting = true }
        }
        if (changes.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("●", color = Amber, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
                Text("На камере изменено: ${changes.labels()}. «Взять с камеры» запишет это в пресет.", color = Secondary,
                    fontSize = 15.sp, lineHeight = 20.sp)
            }
        }
        HorizontalDivider(color = PanelLight)
        PresetSettings(controller, index, preset, online, Modifier.weight(1f))
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Удалить «${preset.name}»?") },
            confirmButton = {
                TextButton({
                    deleting = false
                    controller.deletePreset(index)
                    screen.presetsSelected = minOf(index, controller.presets.lastIndex).coerceAtLeast(0)
                }) { Text("Удалить", color = Danger) }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Отмена") } },
        )
    }
}

/**
 * Имя пресета, правится на месте и сохраняется с каждой буквой. Пустое имя пресету не даём, но стереть всё и набрать
 * заново можно: ушли из пустого поля — в нём снова имя пресета.
 */
@Composable
private fun PresetNameField(controller: CameraController, index: Int, name: String, focus: Boolean, onFocused: () -> Unit) {
    val count = controller.presets.size
    // под тем же номером может оказаться другой пресет: сменился режим камеры, список стал короче
    var field by remember(index, count, controller.presetMode) { mutableStateOf(TextFieldValue(name)) }
    var focused by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // имя поменялось не здесь (на этом месте теперь другой пресет) — показываем его
    LaunchedEffect(name, focused) {
        if (!focused && field.text.trim() != name) field = TextFieldValue(name)
    }
    LaunchedEffect(index, focus) {
        if (!focus) return@LaunchedEffect
        field = TextFieldValue(name, TextRange(0, name.length))
        if (runCatching { requester.requestFocus() }.isSuccess) keyboard?.show()
        onFocused()
    }
    OutlinedTextField(
        field,
        { value ->
            if (value.text != field.text) controller.renamePreset(index, value.text)
            field = value
        },
        label = { Text("Название") }, singleLine = true, textStyle = TextStyle(fontSize = 18.sp),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = Modifier.fillMaxWidth().focusRequester(requester).onFocusChanged {
            focused = it.isFocused
            if (!it.isFocused && field.text.isBlank()) field = TextFieldValue(name)
        },
    )
}

/** Настройки пресета группами, как во «Всех настройках». На широкой панели — две колонки: экспозиция слева, цвет и фокус справа. */
@Composable
private fun PresetSettings(controller: CameraController, index: Int, preset: Preset, online: Boolean, modifier: Modifier) {
    val groups = PresetCatalog.groups
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 700.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f)) { SettingsGroup(controller, index, preset, online, groups[0]) }
                    Column(Modifier.weight(1f)) { groups.drop(1).forEach { SettingsGroup(controller, index, preset, online, it) } }
                }
            } else {
                groups.forEach { SettingsGroup(controller, index, preset, online, it) }
            }
        }
    }
}

@Composable
private fun ColumnScope.SettingsGroup(controller: CameraController, index: Int, preset: Preset, online: Boolean,
                                      group: Pair<String, List<SettingID>>) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.Bottom) {
        Text(group.first, color = Secondary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        // подпись над переключателями
        Text("менять", color = Secondary, fontSize = 13.sp)
    }
    group.second.forEach { id ->
        PresetSettingRow(controller, index, preset, id, online)
        HorizontalDivider(color = PanelLight)
    }
}

/**
 * Настройка в пресете: переключатель «менять» и значение. Значение выбирается из того, что камера даёт выбрать сейчас,
 * камера при этом не меняется; выключенная настройка значение помнит, но выбрать его можно, только включив. Камера на
 * связи, а выбрать не из чего (другой режим, нет списка) — подпись «камера сейчас не даёт выбрать»; без связи —
 * значения просто видно, почему — говорит плашка сверху.
 */
@Composable
private fun PresetSettingRow(controller: CameraController, index: Int, preset: Preset, id: SettingID, online: Boolean) {
    val value = preset.values[id]
    val included = PresetCatalog.includes(preset, id)
    val options = if (online) PresetCatalog.options(id, controller.property(id), preset) else emptyList()
    // температура действует только в «Цвет. темп.»: при другом балансе белого в пресете строка неактивна
    val whiteBalance = preset.values[SettingID.whiteBalance]?.takeIf { PresetCatalog.includes(preset, SettingID.whiteBalance) }
    val onlyManual = id == SettingID.colorTemperature && whiteBalance != null && whiteBalance != WhiteBalanceTemperature.MANUAL_MODE
    val canChoose = value != null && options.isNotEmpty() && included && !onlyManual
    // включить можно, если значение есть или его есть откуда взять
    val canSwitch = !onlyManual && (value != null || PresetCatalog.initialValue(id, controller.properties) != null)
    val note = when {
        // неразрывный пробел: «Цвет. темп.» не разрывается между строками узкой колонки
        onlyManual -> "только при «Цвет. темп.»"
        online && value != null && options.isEmpty() -> "камера сейчас не даёт выбрать"
        else -> null
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f)) {
            Text(id.title, color = if (included && !onlyManual) Color.White else Secondary, fontSize = 16.sp, maxLines = 2)
            note?.let { Text(it, color = Secondary, fontSize = 12.sp, lineHeight = 15.sp) }
        }
        ValueChooser(id, value, options, canChoose) { controller.setPresetValue(index, id, it) }
        Switch(included, { controller.setPresetIncluded(index, id, it) }, enabled = canSwitch,
            modifier = Modifier.semantics { contentDescription = "менять «${id.title}»" })
    }
}

/** Значение настройки пресета: касание — варианты камеры с подписями меню Fn, сразу на текущем; выбранное отмечено. */
@Composable
private fun ValueChooser(id: SettingID, value: Long?, options: List<SettingOption>, enabled: Boolean, onChoose: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    Box {
        Row(
            Modifier.width(170.dp).height(44.dp).clip(RoundedCornerShape(9.dp)).background(PanelLight)
                .clickable(enabled = enabled) { open = true }.alpha(if (enabled) 1f else 0.45f).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value?.let { ValueFormatter.label(id, it) } ?: "—", color = Color.White, fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (enabled) Text("▾", color = Color.White, fontSize = 15.sp)
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 480.dp), scrollState = scroll) {
            options.forEach { option ->
                val current = option.value == value
                DropdownMenuItem(
                    text = { Text(option.label, fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal) },
                    trailingIcon = if (current) ({ Text("✓", color = Accent, fontSize = 18.sp) }) else null,
                    onClick = {
                        open = false
                        onChoose(option.value)
                    },
                )
            }
        }
        // список открылся — сразу на текущем значении: у выдержки их десятки (строка меню — 48 dp)
        LaunchedEffect(open) {
            if (!open) return@LaunchedEffect
            val at = options.indexOfFirst { it.value == value }
            scroll.scrollTo(with(density) { (maxOf(0, at - 2) * 48).dp.roundToPx() })
        }
    }
}

/**
 * «Посмотреть»: пока палец на кнопке, окно пресетов прозрачно, а пресет стоит на камере; отпустили — камера как была.
 * Нажатие не перезапускается, когда кнопка гаснет (пресет пишется — «Применить» и она неактивны): иначе отпускание
 * потерялось бы и камера осталась на пресете. По той же причине конец просмотра — в finally.
 */
@Composable
private fun PeekButton(controller: CameraController, screen: ScreenState, index: Int, enabled: Boolean) {
    val canPeek by rememberUpdatedState(enabled)
    val preset by rememberUpdatedState(index)
    val held = screen.presetPeeking
    Text("Посмотреть", color = if (enabled || held) Color.White else Secondary, fontSize = 16.sp, textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (held) Accent else PanelLight)
            .semantics { contentDescription = "Посмотреть: держите, пока смотрите" }
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    if (!canPeek) return@detectTapGestures
                    screen.presetPeeking = true
                    controller.beginPresetPreview(preset)
                    try {
                        tryAwaitRelease()
                    } finally {
                        screen.presetPeeking = false
                        controller.endPresetPreview()
                    }
                })
            }
            .padding(horizontal = 16.dp, vertical = 11.dp))
}

/** Кнопка окна пресетов: Accent — главное действие, красная надпись — удаление. */
@Composable
private fun WindowButton(title: String, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Boolean = false,
                         destructive: Boolean = false, onClick: () -> Unit) {
    Text(title, color = when {
            !enabled -> Secondary
            destructive -> Danger
            else -> Color.White
        }, fontSize = 16.sp, textAlign = TextAlign.Center, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(if (accent && enabled) Accent else PanelLight)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp, vertical = 11.dp))
}
