package local.zve10viewer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Switch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
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
import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.view.OrientedFrame
import local.zve10viewer.monitor.LutChoice
import local.zve10viewer.monitor.LutTexts
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorTool

/*
 * Монитор: зебра, false color, пикинг, LUT, вектороскоп, пипетка, маркеры и зеркало — как у программы для Mac. Меню — внизу, на
 * месте строки значений, как меню Fn: сверху значения выбранного инструмента и подсказка, ниже — четырнадцать плиток в две
 * строки по семь (тринадцать инструментов и «Всё — выключить»). Касание плитки выбирает её, касание выбранной — включает или
 * выключает.
 */

/** Плиток в ряду меню «Монитор». */
private const val TILE_COLUMNS = 7

/** Плитки меню по рядам: тринадцать инструментов в порядке `MonitorTool` и «Всё — выключить» (null) последней. */
internal fun assistTileRows(): List<List<MonitorTool?>> = (MonitorTool.entries + listOf(null)).chunked(TILE_COLUMNS)

/** Значение на плитке — 19 sp, в узкой плитке сжимается до 12; заголовок — 14 sp, сжимается до 10 (см. `FittedText`). */
private const val TILE_VALUE_SP = 19f
private const val TILE_VALUE_MIN_SP = 12f
private const val TILE_TITLE_SP = 14f
private const val TILE_TITLE_MIN_SP = 10f

/** Плашка с именем своего LUT: ширина и поля, dp. */
private val ChipWidth = 112.dp
private val ChipPadding = 8.dp

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
                    val pick = rememberReferencePicker()
                    Text("Из файла…", color = Color.White, fontSize = 16.sp,
                        modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp)).clickable { pick() }
                            .padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            // LUT: помощь «по картинке с LUT» и — у «Свой» — список своих файлов, «Добавить LUT…» и «Удалить»
            if (tool == MonitorTool.lut) LutStrip(controller, assist)
            Text(tool.help, color = Secondary, fontSize = 14.sp, lineHeight = 18.sp, textAlign = TextAlign.Center)
        }
        Column(Modifier.background(Panel, RoundedCornerShape(16.dp)).padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val measurer = rememberTextMeasurer()
            assistTileRows().forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        if (item == null) {
                            AssistTile("Всё", "Выключить", on = false, selected = false, enabled = assist.isActive, measurer = measurer,
                                modifier = Modifier.weight(1f)) { controller.assistOff() }
                        } else {
                            AssistTile(item.title, tileValue(controller, assist, item), on = assist.isOn(item),
                                selected = item == tool, measurer = measurer, modifier = Modifier.weight(1f)) {
                                if (screen.assistSelected == item) controller.toggleAssist(item) else screen.assistSelected = item
                            }
                        }
                    }
                    // неполный ряд — плитки той же ширины, что в полном
                    repeat(TILE_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Значение на плитке. У «Авто» в LUT — «Авто: S-Log3»: какой LUT выбран по профилю камеры сейчас; у «Свой» — имя файла, пока
 * он лежит в папке. Читает состояние, за которым следит экран (`resolvedLut` идёт по свойствам камеры), поэтому плитка
 * перерисовывается сама, когда профиль в камере сменили.
 */
private fun tileValue(controller: CameraController, assist: MonitorAssist, tool: MonitorTool): String =
    if (tool == MonitorTool.lut) LutChoice.tileValue(assist, controller.resolvedLut, controller.lutNames) else assist.label(tool)

@Composable
private fun AssistTile(title: String, value: String, on: Boolean, selected: Boolean, modifier: Modifier, measurer: TextMeasurer,
                       enabled: Boolean = true, onClick: () -> Unit) {
    Column(
        modifier.heightIn(min = 72.dp)
            .background(if (selected) Accent else if (on) Color(0x29FFFFFF) else PanelLight, RoundedCornerShape(12.dp))
            .semantics { contentDescription = "$title: $value" }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        // семь плиток в ряду — по ≈126 dp (в портрете ≈84): «Цвет пикинга», «Авто: S-Log3 Cine» или имя файла не умещаются, и их
        // размер подбирается по ширине плитки и замеру настоящего текста (`FittedText`), а не обрезается
        FittedText(title, if (selected) Color.White else Secondary, TILE_TITLE_SP, TILE_TITLE_MIN_SP, measurer)
        FittedText(value, if (on || selected) Color.White else Color.White.copy(alpha = 0.45f), TILE_VALUE_SP, TILE_VALUE_MIN_SP, measurer,
            FontWeight.SemiBold)
    }
}

/**
 * Под вариантами LUT: «Помощь — по картинке с LUT» и — у «Свой» — список своих файлов, «Добавить LUT…» и «Удалить». Файл выбирается
 * касанием имени; выбранный — белым. Добавить — через системный выбор файла.
 */
@Composable
private fun LutStrip(controller: CameraController, assist: MonitorAssist) {
    val names = controller.lutNames
    // выбранный файл, если он лежит в папке: пропавший из папки не считается выбранным
    val chosen = assist.lutFile?.takeIf { it in names }
    val own = assist.lut == MonitorAssist.LutMode.file
    val pick = rememberLutPicker()
    val measurer = rememberTextMeasurer()
    // папку могли тронуть руками: список читается заново, когда полоса открывается
    LaunchedEffect(Unit) { controller.refreshLuts() }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (own) {
            if (names.isEmpty()) {
                Text(LutTexts.EMPTY, color = Secondary, fontSize = 16.sp, textAlign = TextAlign.Center)
            } else {
                // не больше шести имён в ряду: ряд стоит посередине, как строки над ним и под ним
                names.chunked(6).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (name in row) LutChip(name, name == chosen, measurer) { controller.selectLutFile(name) }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                Modifier.clickable { controller.setLutScopes(!assist.lutScopes) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(LutTexts.SCOPES, color = Color.White, fontSize = 16.sp)
                Switch(assist.lutScopes, { controller.setLutScopes(it) })
            }
            if (own) {
                Text(LutTexts.ADD, color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.background(PanelLight, RoundedCornerShape(9.dp)).clickable { pick() }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
                Text(LutTexts.DELETE, color = Color.White, fontSize = 16.sp,
                    modifier = Modifier.alpha(if (chosen != null) 1f else 0.4f).background(PanelLight, RoundedCornerShape(9.dp))
                        .clickable(enabled = chosen != null) { chosen?.let(controller::deleteLut) }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        // подсказки: на планшете нет «всплывающей подсказки при наведении», как на Mac
        Text(LutTexts.SCOPES_HELP, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
        if (own) Text(LutTexts.FILES_HELP, color = Secondary, fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
    }
}

/**
 * «Добавить LUT…»: системный выбор файла — любого (у `.cube` в системе часто нет своего типа, и фильтр по типу скрыл бы его);
 * выбрали — файл проверяется разбором, копируется в папку программы и выбирается. Выбор запускает `MainActivity` и она же
 * принимает результат (`FilePickers`): меню на это время может уйти с экрана — в фоне через минуту сеанс закрывается и экран
 * живого видео сменяется экраном ожидания, — а запрос меню ушёл бы вместе с ним.
 */
@Composable
private fun rememberLutPicker(): () -> Unit = LocalFilePickers.current.lut

/** Плашка с именем своего LUT; в узкой плашке длинное имя теряет середину, а не конец: `look_v1` и `look_v2` не сливаются. */
@Composable
private fun LutChip(name: String, current: Boolean, measurer: TextMeasurer, onClick: () -> Unit) {
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = 16.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal))
    val room = with(LocalDensity.current) { (ChipWidth - ChipPadding * 2).toPx() }
    val shown = remember(name, style, room, measurer) {
        middleEllipsis(name) { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width <= room }
    }
    Text(shown, color = if (current) Color.Black else Color.White, fontSize = 16.sp, maxLines = 1, softWrap = false,
        textAlign = TextAlign.Center, fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier.width(ChipWidth).background(if (current) Color.White else PanelLight, RoundedCornerShape(9.dp))
            .semantics { contentDescription = "LUT $name" + if (current) ", выбран" else "" }
            .clickable(onClick = onClick).padding(horizontal = ChipPadding, vertical = 8.dp))
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

/**
 * Эталонный кадр поверх живого: заполняет кадр на экране (rect) по большей стороне, лишнее обрезано по краю кадра. Эталон снят
 * камерой как есть, а живая картинка может быть зеркальной или перевёрнутой ([orientation]): он рисуется с тем же поворотом,
 * `scale(±1, ±1)` вокруг середины кадра на экране — она же середина эталона, поэтому он остаётся на месте и только переворачивается.
 */
fun DrawScope.drawReference(image: ImageBitmap, rect: Rect, alpha: Float, orientation: FrameOrientation) {
    val box = OrientedFrame.referenceBox(Size(image.width.toFloat(), image.height.toFloat()), rect)
    if (box.isEmpty) return
    val flip = OrientedFrame.flip(orientation)
    withTransform({ scale(flip.x, flip.y, rect.center) }) {
        clipRect(rect.left, rect.top, rect.right, rect.bottom) {
            drawImage(image, dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
                dstSize = IntSize(box.width.roundToInt(), box.height.roundToInt()), alpha = alpha, filterQuality = FilterQuality.Low)
        }
    }
}

/** Кнопка «Монитор» верхней панели: открывает и закрывает меню инструментов; горит, пока оно открыто. */
@Composable
fun MonitorButton(screen: ScreenState) {
    Box(
        Modifier.size(44.dp).background(if (screen.assistOpen) Accent else PanelLight, RoundedCornerShape(9.dp))
            .semantics { contentDescription = "Монитор: зебра, false color, пикинг, LUT, вектороскоп, пипетка, сетка, рамки и зеркало" }
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
