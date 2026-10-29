package local.zve10viewer.ui

import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import local.zve10viewer.BuildConfig
import local.zve10viewer.CameraController
import local.zve10viewer.monitor.ColorProbe
import local.zve10viewer.monitor.ProbePlateClock
import local.zve10viewer.view.ProbeLayout
import kotlin.math.roundToInt

/*
 * Пипетка на экране: метка 20 dp с перекрестием в точке кадра и рядом табличка из двух строк — «47 % · кожа» и «R 182 · G 140 · B 121».
 * Табличка справа от метки, у правого края и рядом с колонкой кнопок справа — слева, у нижнего — поднимается (`ProbeLayout`).
 *
 * Только рисует: жестов у слоя нет, и касания проходят сквозь него к кадру. Ставит метку касание кадра (`LiveScreen`: пока пипетка
 * включена, оно ставит её, а не фокус), тащит — жест, начатый на метке (`zoomGestures`: он же двигает увеличенную картинку, поэтому
 * щипок и сдвиг работают и над самой меткой, а перетаскивание метки картинку не двигает).
 *
 * Метка и табличка живут в экранных долях кадра (`CameraController.probePoint`): с увеличением они едут вместе с картинкой, а с
 * зеркалом остаются на месте экрана — меряется то, что под меткой видно. Пока метка за краем экрана (увеличение сдвинули), её и
 * таблички нет.
 */

/** Сколько места под табличку, пока её размер не измерен, dp: две строки по 150 × 40. */
private const val PLATE_GUESS_WIDTH_DP = 150f
private const val PLATE_GUESS_HEIGHT_DP = 40f

/**
 * Метка и табличка пипетки поверх кадра. [frame] — размер кадра, [view] — размер экрана (в пикселях): по ним и по увеличению видно,
 * где кадр на экране. Табличка не ложится на колонку кнопок справа (`ScreenState.rightColumnRect`) и не заходит за видимую часть
 * кадра.
 */
@Composable
fun ProbeLayer(controller: CameraController, screen: ScreenState, frame: Size, view: Size) {
    val density = LocalDensity.current
    val sizes = remember(density.density) { ProbeLayout.Sizes.dp.scaled(density.density) }
    val rect = screen.zoom.imageRect(view, frame)
    val screenRect = Rect(Offset.Zero, view)
    val markerAt = ProbeLayout.markerCenter(controller.probePoint, rect)
    val onScreen = screenRect.contains(markerAt)
    // видимая часть кадра: у её краёв табличка уходит внутрь
    val visible = rect.intersect(screenRect)
    val area = if (visible.isEmpty) screenRect else visible
    // цифры берутся не чаще пяти раз в секунду: меняться чаще — не прочитать; поток кадров их не толкает, табличка спрашивает сама
    val shown by produceState<ColorProbe.Reading?>(null, controller) {
        ProbePlateClock.follow({ controller.probeReading }) {
            value = it
            // отладочная сборка: каждая смена таблички — строка в logcat (ZVAssist); по меткам времени видно, что их не больше пяти в секунду
            if (BuildConfig.DEBUG) Log.i("ZVAssist", "табличка пипетки: " + (it?.lines?.let { (first, second) -> "$first | $second" } ?: "нет"))
        }
    }
    // размер таблички: от него зависит, с какой стороны от метки ей хватит места
    var plate by remember { mutableStateOf(Size.Zero) }
    Box(Modifier.fillMaxSize()) {
        // слой живёт, пока пипетка включена, и опрос показаний — в нём; за краем экрана метки и таблички не рисуются
        Canvas(Modifier.fillMaxSize()) { if (onScreen) drawProbeMarker(markerAt) }
        val reading = shown
        if (reading != null && onScreen) {
            val size = if (plate == Size.Zero) with(density) { Size(PLATE_GUESS_WIDTH_DP.dp.toPx(), PLATE_GUESS_HEIGHT_DP.dp.toPx()) } else plate
            val origin = ProbeLayout.plateOrigin(markerAt, size, area, sizes, screen.rightColumnRect)
            ProbePlate(reading, Modifier.offset { IntOffset(origin.x.roundToInt(), origin.y.roundToInt()) }
                .onSizeChanged { plate = it.toSize() })
        }
    }
}

/** Табличка: «47 % · кожа» и «R 182 · G 140 · B 121» на тёмной подложке. Для чтеца экрана и проверки по uiautomator — одной строкой. */
@Composable
private fun ProbePlate(reading: ColorProbe.Reading, modifier: Modifier) {
    val (first, second) = reading.lines
    val digits = TextStyle(fontFeatureSettings = "tnum")
    Column(
        modifier.background(Color(0x9E000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 5.dp)
            .clearAndSetSemantics { contentDescription = "Пипетка: $first, $second" },
        verticalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        Text(first, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, style = digits, maxLines = 1, softWrap = false)
        Text(second, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, style = digits, maxLines = 1, softWrap = false)
    }
}

/**
 * Метка: кольцо ровно 20 dp по белому и четыре чёрточки от кольца к середине; середина свободна — под ней те точки, что
 * меряются. Белая линия на тёмной обводке: метку видно и на белом, и на чёрном.
 */
fun DrawScope.drawProbeMarker(at: Offset) {
    // середина линии кольца — на три четверти dp внутрь от края: белое кольцо снаружи ровно 20 dp
    val radius = (ProbeLayout.Sizes.dp.markerDiameter / 2 - 0.75f).dp.toPx()
    val inner = 3.dp.toPx()
    val ring = Path().apply { addOval(Rect(at, radius)) }
    val ticks = Path().apply {
        for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
            moveTo(at.x + dx * inner, at.y + dy * inner)
            lineTo(at.x + dx * radius, at.y + dy * radius)
        }
    }
    for (path in listOf(ring, ticks)) drawPath(path, Color.Black.copy(alpha = 0.65f), style = Stroke(3.5.dp.toPx(), cap = StrokeCap.Round))
    for (path in listOf(ring, ticks)) drawPath(path, Color.White, style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
}
