package local.zve10viewer.ui

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import local.zve10viewer.view.ProbeDrag
import local.zve10viewer.view.ProbeGesture
import local.zve10viewer.view.ViewZoom
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Цифровое увеличение live view — на стороне планшета, камера о нём не знает. Щипок двумя пальцами — вокруг точки
 * между ними, перетаскивание при увеличении — сдвиг с инерцией; касание по-прежнему прячет и возвращает элементы
 * (двойного касания нет — оно задержало бы одиночное). Пределы 1×–8×, во время щипка — с сопротивлением, после —
 * пружиной назад. Кадр рисуется бикубически (AGSL, Android 13+), справа внизу — плашка с мини-картой и ✕.
 */

/**
 * Шейдер на 1× — нет: на Redmi Pad Pro (120 Гц, бюджет 8,3 мс) он добавляет ~6 мс работы GPU на кадр — 10 мс на 1× и
 * 12 мс на 4× против 4–5 мс обычной растяжки (dumpsys gfxinfo, 50-й процентиль). На 1× видео и так вписано, а при
 * увеличении чёткость важнее — там он всегда.
 */
const val BICUBIC_AT_IDENTITY = false

/**
 * Рисует кадр в прямоугольник экрана бикубически: RuntimeShader с Catmull-Rom поверх BitmapShader кадра
 * (FILTER_MODE_LINEAR, CLAMP). BitmapShader — один на каждый Bitmap из пула кадров (их три по кругу): новые пиксели
 * в том же Bitmap он видит сам. Шейдер компилируется один раз.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
class BicubicRenderer {
    private val shader = RuntimeShader(BICUBIC_AGSL)
    private val paint = android.graphics.Paint()
    private val inputs = object : LinkedHashMap<Bitmap, BitmapShader>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Bitmap, BitmapShader>?) = size > 4
    }

    fun draw(scope: DrawScope, image: ImageBitmap, rect: Rect, alpha: Float) {
        val bitmap = image.asAndroidBitmap()
        if (rect.width <= 0f || rect.height <= 0f) return
        val input = inputs.getOrPut(bitmap) {
            BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { filterMode = BitmapShader.FILTER_MODE_LINEAR }
        }
        shader.setInputShader("image", input)
        shader.setFloatUniform("origin", rect.left, rect.top)
        shader.setFloatUniform("scale", bitmap.width / rect.width, bitmap.height / rect.height)
        paint.shader = shader
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        // только видимая часть: за краем экрана выборки не нужны
        val visible = rect.intersect(Rect(Offset.Zero, scope.size))
        if (visible.isEmpty) return
        scope.drawContext.canvas.nativeCanvas.drawRect(visible.left, visible.top, visible.right, visible.bottom, paint)
    }
}

/** Кадр в прямоугольник экрана: бикубически, если можно, иначе — обычной билинейной растяжкой. */
fun DrawScope.drawFrame(frame: ImageBitmap, rect: Rect, alpha: Float, renderer: BicubicRenderer?) {
    if (renderer != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        renderer.draw(this, frame, rect, alpha)
        return
    }
    if (rect.width <= 0f || rect.height <= 0f) return
    withTransform({
        translate(rect.left, rect.top)
        scale(rect.width / frame.width, rect.height / frame.height, Offset.Zero)
    }) {
        drawImage(frame, alpha = alpha)
    }
}

/**
 * Жесты увеличения над видео. zoom/setZoom — увеличение экрана; frame — размер кадра (null — кадров нет); onTap —
 * касание без сдвига одним пальцем (где коснулись — в точках экрана); onLimit — упёрлись в 8× или вернулись к 1×; onEnd — жест кончился (или прервался):
 * пружиной в пределы или с инерцией дальше. probe — метка пипетки (null — пипетка выключена): жест одним пальцем, начатый на
 * ней, тащит её; два пальца, как всегда, увеличивают.
 */
suspend fun PointerInputScope.zoomGestures(
    zoom: () -> ViewZoom,
    setZoom: (ViewZoom) -> Unit,
    frame: () -> Size?,
    onStart: () -> Unit,
    onTap: (Offset) -> Unit,
    onLimit: () -> Unit,
    onEnd: (Velocity) -> Unit,
    probe: () -> ProbeGesture? = { null },
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onStart()
        // жест начат на метке пипетки: пока палец один, он тащит метку (после сдвига дальше слопа) — см. ProbeDrag
        val marker = probe()?.takeIf { it.grabs(down.position) }
        val markerDrag = ProbeDrag(marker != null, slop)
        var transforming = false
        var multi = false
        var moved = Offset.Zero
        var finished = false
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                if (pressed >= 2) multi = true
                val pan = event.calculatePan()
                moved += pan
                when (markerDrag.step(moved, pressed)) {
                    ProbeDrag.Step.dragging -> {
                        marker!!.drag(moved)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        continue
                    }
                    ProbeDrag.Step.holding -> continue
                    ProbeDrag.Step.notMarker -> Unit
                }
                val view = Size(size.width.toFloat(), size.height.toFloat())
                val f = frame() ?: continue
                // двумя пальцами — всегда увеличение; одним — сдвиг, только когда уже увеличено
                if (!transforming) transforming = pressed >= 2 || (zoom().isZoomed && moved.getDistance() > slop)
                if (!transforming) continue
                // шаг за шагом от текущего увеличения, без своей «базы»: прерванный жест не оставит следующему скачок
                val before = zoom()
                var z = before
                val k = event.calculateZoom()
                if (k != 1f) z = z.zoomed(k.toDouble(), event.calculateCentroid(useCurrent = true), view, f, elastic = true)
                if (pan != Offset.Zero && (z.isZoomed || pressed >= 2)) z = z.panned(pan, view, f)
                if ((before.factor < ViewZoom.MAX_FACTOR && z.factor >= ViewZoom.MAX_FACTOR) || (before.factor > 1.0 && z.factor <= 1.0)) {
                    onLimit()
                }
                setZoom(z)
                event.changes.forEach { if (it.positionChanged()) it.consume() }
                event.calculateCentroid(useCurrent = true).takeIf { it.isSpecified }?.let {
                    tracker.addPosition(event.changes.first().uptimeMillis, it)
                }
            }
            finished = true
        } finally {
            // прерванный жест тоже доводим: увеличение не застревает между 0,87× и 1×
            if (transforming) onEnd(if (finished && !multi) tracker.calculateVelocity() else Velocity.Zero)
        }
        if (!transforming && !multi && moved.getDistance() <= slop) onTap(down.position)
    }
}

/**
 * После жеста: за пределами 1…8 или ниже 1,05 — пружиной в пределы (к 1× — с «тиком»), иначе — сдвиг с инерцией
 * по скорости пальца. Возвращает задачу анимации — её отменяет следующее касание.
 */
fun settleZoom(scope: CoroutineScope, current: () -> ViewZoom, set: (ViewZoom) -> Unit, view: Size, frame: Size,
               velocity: Velocity, onIdentity: () -> Unit): Job = scope.launch {
    val from = current()
    val target = from.settled(view, frame)
    if (target.factor != from.factor) {
        if (target == ViewZoom.identity && from.factor > 1.0) onIdentity()
        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
            set(ViewZoom.lerp(from, target, t.toDouble()))
        }
        return@launch
    }
    if (!from.isZoomed || (abs(velocity.x) < 150f && abs(velocity.y) < 150f)) return@launch
    var last = Offset.Zero
    AnimationState(Offset.VectorConverter, Offset.Zero, Offset(velocity.x, velocity.y)).animateDecay(exponentialDecay(frictionMultiplier = 2f)) {
        val delta = value - last
        last = value
        set(current().panned(delta, view, frame))
    }
}

/** Плавно к 1× за 250 мс — кнопка ✕. */
fun resetZoom(scope: CoroutineScope, current: () -> ViewZoom, set: (ViewZoom) -> Unit): Job = scope.launch {
    val from = current()
    animate(0f, 1f, animationSpec = tween(250)) { t, _ -> set(ViewZoom.lerp(from, ViewZoom.identity, t.toDouble())) }
    set(ViewZoom.identity)
}

/**
 * Плашка увеличения справа внизу — не шире 104 dp, чтобы не лезть под меню Fn: сверху мини-карта (весь кадр, видимая
 * часть — в белой рамке, остальное притемнено; касание и перетаскивание по ней сдвигают видимую часть), снизу «2,4×» и
 * круглая ✕ «Отменить увеличение». Видна и при скрытых элементах: увеличенная картинка — не весь кадр.
 */
@Composable
fun ZoomBadge(frame: ImageBitmap, zoom: ViewZoom, view: Size, onCenter: (Offset) -> Unit, onReset: () -> Unit, modifier: Modifier) {
    val frameSize = Size(frame.width.toFloat(), frame.height.toFloat())
    Column(modifier.width(104.dp).background(Panel, RoundedCornerShape(12.dp)).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(frameSize.width / frameSize.height).clip(RoundedCornerShape(6.dp))
                .pointerInputForMinimap(onCenter),
        ) {
            drawImage(frame, IntOffset.Zero, IntSize(frame.width, frame.height), IntOffset.Zero,
                IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
            val region = zoom.visibleRegion(view, frameSize)
            val r = Rect(region.left * size.width, region.top * size.height, region.right * size.width, region.bottom * size.height)
            val shade = Color.Black.copy(alpha = 0.55f)
            // всё, что за пределами видимой части, — притемнено
            drawRect(shade, Offset.Zero, Size(size.width, r.top))
            drawRect(shade, Offset(0f, r.bottom), Size(size.width, size.height - r.bottom))
            drawRect(shade, Offset(0f, r.top), Size(r.left, r.height))
            drawRect(shade, Offset(r.right, r.top), Size(size.width - r.right, r.height))
            val w = 1.5.dp.toPx()
            drawRect(Color.White, Offset(r.left + w / 2, r.top + w / 2), Size(r.width - w, r.height - w), style = Stroke(w))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(zoom.label, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.weight(1f).padding(start = 4.dp))
            Box(
                Modifier.size(30.dp).clip(CircleShape).background(PanelLight)
                    .semantics { contentDescription = "Отменить увеличение" }
                    .clickable(onClick = onReset),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(11.dp)) {
                    val w = 2.dp.toPx()
                    drawLine(Color.White, Offset.Zero, Offset(size.width, size.height), w, StrokeCap.Round)
                    drawLine(Color.White, Offset(size.width, 0f), Offset(0f, size.height), w, StrokeCap.Round)
                }
            }
        }
    }
}

/** Касание и перетаскивание по мини-карте: точка под пальцем — в центр экрана (доли кадра). */
@Composable
private fun Modifier.pointerInputForMinimap(onCenter: (Offset) -> Unit): Modifier {
    val latest by rememberUpdatedState(onCenter)
    return pointerInput(Unit) {
        val share = { p: Offset -> Offset((p.x / size.width).coerceIn(0f, 1f), (p.y / size.height).coerceIn(0f, 1f)) }
        coroutineScope {
            launch { detectTapGestures { latest(share(it)) } }
            launch {
                detectDragGestures { change, _ ->
                    change.consume()
                    latest(share(change.position))
                }
            }
        }
    }
}
