package local.zve10viewer.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import local.zve10viewer.TallyStyle
import local.zve10viewer.CameraController.FeedbackEvent
import local.zve10viewer.CameraController.FeedbackTarget
import kotlin.math.roundToInt

/*
 * Отклик кнопок съёмки. Палец закрывает кнопку и место под ней, поэтому отклик — вокруг пальца и на всём кадре:
 * кольцо при нажатии, волна, когда действие принято, шторка, когда камера сняла, вспышка и полёт стоп-кадра в ленту,
 * рамка записи, дуга и подпись, пока камера не ответила, «мотание» при отказе. Срабатывает от событий контроллера —
 * от действия, а не от пальца. Числа — из спеки, те же, что у программы для Mac.
 */

/** Что сейчас анимирует отклик и куда летит стоп-кадр. Живёт, пока показан экран с видео. */
@Stable
class FeedbackState(private val scope: CoroutineScope, private val haptics: Haptics) {
    private val waves = FeedbackTarget.entries.associateWith { Animatable(1f) }
    private val shakes = FeedbackTarget.entries.associateWith { Animatable(0f) }
    /** Шторка: чёрный поверх всего, 0 → 0,75 → 0. */
    val blink = Animatable(0f)
    /** Вспышка стоп-кадра: белый, 0,25 → 0. */
    val flash = Animatable(0f)
    var flight by mutableStateOf<GrabFlight?>(null)
        private set
    /** Миниатюра нового стоп-кадра, пока к ней летит кадр, — спрятана. */
    var hiddenShot by mutableStateOf<Long?>(null)
        private set
    /** Новая миниатюра: «пружинка» и белая рамка; второе число — чтобы та же миниатюра могла ожить снова. */
    var fresh by mutableStateOf<Pair<Long, Int>?>(null)
        private set
    /** Где первая миниатюра ленты (координаты экрана) и видна ли лента — сюда летит стоп-кадр. */
    var stripTarget by mutableStateOf<Rect?>(null)
    var stripVisible by mutableStateOf(false)
    /** Где кадр на экране (вписанный целиком) — отсюда он и улетает. */
    var frameRect: Rect? = null
    var screenSize by mutableStateOf(Size.Zero)
    private var freshCount = 0

    /** Волна у кнопки: 0 — только началась, 1 — погасла. */
    fun wave(target: FeedbackTarget): Float = waves.getValue(target).value

    /** Сдвиг «мотания головой», dp. */
    fun shake(target: FeedbackTarget): Float = shakes.getValue(target).value

    fun haptic(event: HapticEvent) = haptics.perform(event)

    fun handle(event: FeedbackEvent) {
        when (event) {
            is FeedbackEvent.Accepted -> {
                haptics.perform(HapticEvent.accepted)
                val wave = waves.getValue(event.target)
                scope.launch {
                    wave.snapTo(0f)
                    wave.animateTo(1f, tween(450, easing = LinearOutSlowInEasing))
                }
            }
            FeedbackEvent.ShutterFired -> {
                haptics.perform(HapticEvent.shutterFired)
                scope.launch {
                    blink.snapTo(0f)
                    blink.animateTo(0.75f, tween(40, easing = LinearEasing))
                    blink.animateTo(0f, tween(220, easing = LinearOutSlowInEasing))
                }
            }
            is FeedbackEvent.FrameGrabbed -> {
                haptics.perform(HapticEvent.frameGrabbed)
                scope.launch {
                    flash.snapTo(0.25f)
                    flash.animateTo(0f, tween(250, easing = LinearOutSlowInEasing))
                }
                fly(event.image, event.shotId)
            }
            is FeedbackEvent.ShotAdded -> markFresh(event.shotId)
            FeedbackEvent.RecordingStarted -> haptics.perform(HapticEvent.recordingStarted)
            FeedbackEvent.RecordingStopped -> haptics.perform(HapticEvent.recordingStopped)
            is FeedbackEvent.Failed -> {
                haptics.perform(HapticEvent.failed)
                val shake = shakes.getValue(event.target)
                scope.launch {
                    shake.snapTo(0f)
                    // ±6 dp, три раза, 300 мс
                    shake.animateTo(0f, keyframes {
                        durationMillis = 300
                        6f at 25
                        -6f at 75
                        6f at 125
                        -6f at 175
                        6f at 225
                        -6f at 275
                    })
                }
            }
        }
    }

    /**
     * Кадр уменьшается и улетает на место первой миниатюры (450 мс); новая миниатюра пока спрятана и появляется, когда
     * кадр долетел, — и в любом случае через 650 мс. Ленты не видно — кадр улетает в левый нижний угол и тает.
     */
    private fun fly(image: ImageBitmap?, shotId: Long) {
        val from = frameRect
        if (image == null || from == null || from.isEmpty) {
            markFresh(shotId)
            return
        }
        val current = GrabFlight(image, from, Animatable(0f))
        flight = current
        hiddenShot = shotId
        scope.launch {
            current.progress.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
            if (flight === current) flight = null
            if (hiddenShot == shotId) hiddenShot = null
            if (stripVisible) markFresh(shotId)
        }
        scope.launch {
            delay(650)
            if (hiddenShot == shotId) hiddenShot = null
        }
    }

    private fun markFresh(shotId: Long) {
        fresh = shotId to ++freshCount
    }
}

/** Летящий стоп-кадр: откуда и как далеко пролетел (0…1, уже с разгоном и торможением). */
class GrabFlight(val image: ImageBitmap, val from: Rect, val progress: Animatable<Float, AnimationVector1D>)

/**
 * Круглая кнопка съёмки с откликом: нажатая — вдавливается (0,92, пружина), вокруг проступает кольцо шире пальца
 * (радиус + 10 dp, 2 dp, белое 60 %); принятое действие расходится волной до радиуса + 34 dp за 450 мс; отказ —
 * «мотание» ±6 dp. caption — подпись ожидания камеры слева от кнопки.
 */
@Composable
fun CaptureButton(
    diameter: Dp,
    target: FeedbackTarget,
    feedback: FeedbackState,
    enabled: Boolean,
    waveColor: Color,
    caption: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "press")
    val ring by animateFloatAsState(if (pressed) 0.6f else 0f, tween(if (pressed) 90 else 200), label = "ring")
    Box(modifier.size(diameter)) {
        // подпись — не дрожит и не вдавливается вместе с кнопкой
        PendingCaption(caption, Modifier.align(Alignment.CenterStart))
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { translationX = feedback.shake(target).dp.toPx() }
                .drawBehind {
                    val radius = size.minDimension / 2
                    val stroke = Stroke(2.dp.toPx())
                    if (ring > 0f) drawCircle(Color.White.copy(alpha = ring), radius + 10.dp.toPx(), style = stroke)
                    val wave = feedback.wave(target)
                    if (wave < 1f) {
                        drawCircle(waveColor.copy(alpha = 0.6f * (1f - wave)), radius + (10f + 24f * wave).dp.toPx(), style = stroke)
                    }
                }
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clickable(interaction, indication = null, enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
            content = content,
        )
    }
}

/** Кольцо кнопки, пока камера не ответила: доля (загрузка снимка) или крутящаяся дуга; под ней — тусклая дорожка. */
@Composable
fun PendingArc(fraction: Double?, color: Color, modifier: Modifier = Modifier) {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle = spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "angle")
    val sweep by animateFloatAsState(((fraction ?: 0.0) * 360).toFloat(), tween(120), label = "sweep")
    Canvas(modifier) {
        val width = 5.dp.toPx()
        val inset = width / 2
        val arcSize = Size(size.width - width, size.height - width)
        drawCircle(color.copy(alpha = 0.25f), size.minDimension / 2 - inset, style = Stroke(width))
        val stroke = Stroke(width, cap = StrokeCap.Round)
        if (fraction != null) {
            drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = stroke)
        } else {
            drawArc(color, angle.value - 90f, 100f, false, Offset(inset, inset), arcSize, style = stroke)
        }
    }
}

/**
 * Плашка с подписью ожидания — слева от кнопки, по её середине: кнопку закрывает палец, а слева её видно. Места в
 * раскладке не занимает: кнопки не сдвигаются. Гаснет, когда ждать больше нечего.
 */
@Composable
fun PendingCaption(text: String?, modifier: Modifier = Modifier) {
    val last = remember { mutableStateOf("") }
    SideEffect { if (text != null) last.value = text }
    val alpha by animateFloatAsState(if (text != null) 1f else 0f, tween(if (text != null) 120 else 250), label = "caption")
    if (alpha <= 0f && text == null) return
    Text(text ?: last.value, color = Color.White, fontSize = 15.sp, maxLines = 1, style = TextStyle(fontFeatureSettings = "tnum"),
        modifier = modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(Constraints())
                layout(0, 0) { placeable.place(-placeable.width - 16.dp.roundToPx(), -placeable.height / 2) }
            }
            .alpha(alpha)
            .background(Panel, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

/** Шторка (камера сняла) и вспышка (стоп-кадр) — поверх всего, и поверх просмотра снимка. Касаний не ловят. */
@Composable
fun FlashLayer(feedback: FeedbackState) {
    Spacer(Modifier.fillMaxSize().drawBehind {
        val blink = feedback.blink.value
        if (blink > 0f) drawRect(Color.Black, alpha = blink)
        val flash = feedback.flash.value
        if (flash > 0f) drawRect(Color.White, alpha = flash)
    })
}

/** Стоп-кадр улетает в ленту: уменьшается, белая рамка 2 dp, скругление и тень. */
@Composable
fun GrabFlightLayer(feedback: FeedbackState) {
    val flight = feedback.flight ?: return
    val density = LocalDensity.current
    val p = flight.progress.value
    val screen = feedback.screenSize
    val strip = feedback.stripTarget.takeIf { feedback.stripVisible }
    val target = strip ?: with(density) {
        // ленты не видно — в левый нижний угол
        val side = 64.dp.toPx()
        val margin = 18.dp.toPx()
        Rect(margin, screen.height - margin - side, margin + side, screen.height - margin)
    }
    val rect = lerp(flight.from, target, p)
    val corner = 8.dp * p
    val shape = RoundedCornerShape(corner)
    Box(
        Modifier
            .offset { IntOffset(rect.left.roundToInt(), rect.top.roundToInt()) }
            .size(with(density) { rect.width.toDp() }, with(density) { rect.height.toDp() })
            .graphicsLayer {
                alpha = if (strip != null) 1f else 1f - p
                shadowElevation = 12.dp.toPx()
                this.shape = shape
                clip = true
            }
            .border(2.dp, Color.White, shape),
    ) {
        Image(flight.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Рамка записи вокруг видимой картинки (при увеличении — по краю экрана), красная, как тэлли у мониторов: запись
 * началась — вспыхивает (100 % → яркость из настроек, по умолчанию 70 %), остановилась — тает за 300 мс; с «Мигать» —
 * медленно мигает между этой яркостью и её четвертью, раз в секунду. Видна и при скрытых элементах.
 * picture — где картинка на экране; null — по краю всего места (образец в настройках).
 */
@Composable
fun TallyBorder(recording: Boolean, style: TallyStyle, picture: () -> Rect?, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    // вспышка — один раз на запись, а не при каждой смене настроек
    val lit = remember { BooleanArray(1) }
    LaunchedEffect(recording, style.enabled, style.opacity, style.blink) {
        if (!recording || !style.enabled) {
            lit[0] = false
            alpha.animateTo(0f, tween(300))
            return@LaunchedEffect
        }
        if (!lit[0]) {
            lit[0] = true
            alpha.snapTo(1f)
        }
        alpha.animateTo(style.opacity, tween(600))
        while (style.blink) {
            alpha.animateTo(style.opacity * 0.25f, tween(500, easing = FastOutSlowInEasing))
            alpha.animateTo(style.opacity, tween(500, easing = FastOutSlowInEasing))
        }
    }
    Spacer(modifier.fillMaxSize().drawBehind {
        val a = alpha.value
        if (a <= 0f) return@drawBehind
        val area = Rect(Offset.Zero, size)
        val rect = (picture() ?: area).intersect(area).takeIf { !it.isEmpty } ?: return@drawBehind
        val width = style.width.dp.toPx()
        drawRect(Color.Red.copy(alpha = a), Offset(rect.left + width / 2, rect.top + width / 2),
            Size(rect.width - width, rect.height - width), style = Stroke(width))
    })
}
