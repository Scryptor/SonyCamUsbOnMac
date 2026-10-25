package local.zve10viewer.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.net.Uri
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.zve10viewer.CameraController
import local.zve10viewer.CameraController.Shot
import local.zve10viewer.MediaSaver
import local.zve10viewer.view.ViewZoom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/** Что это за снимок — для подписи в галерее: «Стоп-кадр 1024×576», «Снимок 6000×4000», «RAW + JPEG», «Снимок RAW». */
internal fun shotKind(isFrameGrab: Boolean, names: List<String>, pixelSize: IntSize?): String {
    val size = pixelSize?.let { "${it.width}×${it.height}" }
    val raw = names.any { it.endsWith(".ARW", ignoreCase = true) }
    val jpeg = names.any { it.endsWith(".JPG", ignoreCase = true) || it.endsWith(".JPEG", ignoreCase = true) }
    return when {
        isFrameGrab -> listOfNotNull("Стоп-кадр", size).joinToString(" ")
        raw && jpeg -> "RAW + JPEG"
        raw -> "Снимок RAW"
        else -> listOfNotNull("Снимок", size).joinToString(" ")
    }
}

/**
 * Снимки галереи, декодированные из файлов: открытый — под экран (до 2560 точек по длинной стороне), соседние —
 * вдвое меньше, чтобы листать без ожидания. В кэше — не больше 1/8 памяти программы; картинки — в памяти видеокарты.
 */
private class GalleryImages(private val saver: MediaSaver) {
    private class Page(val bitmap: Bitmap, val full: Boolean)

    private val cache = object : LruCache<Long, Page>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Long, value: Page) = value.bitmap.allocationByteCount
    }

    /** Сколько открытых снимков сейчас декодируется: соседние ждут, пока открытый не готов. */
    private val currentLoads = MutableStateFlow(0)

    fun cached(id: Long): Bitmap? = cache.get(id)?.bitmap

    /** Участки снимка в полном разрешении (вызывать не в главном потоке). */
    fun regionDecoder(uri: Uri): BitmapRegionDecoder? = saver.regionDecoder(uri)

    /**
     * Снимок нужного размера: из кэша, если там такой или лучше, иначе — декодируем из файла. Открытый — первым:
     * соседние ждут его. Отменили (страница ушла или стала соседней) — результат не попадает ни на экран, ни в кэш.
     */
    suspend fun load(shot: Shot, fitSide: Int, full: Boolean): Bitmap? {
        cache.get(shot.id)?.let { if (it.full || !full) return it.bitmap }
        val file = shot.jpeg ?: return null
        val side = if (full) fitSide else maxOf(1, fitSide / 2)
        if (!full) currentLoads.first { it == 0 }
        if (full) currentLoads.update { it + 1 }
        val bitmap = try {
            withContext(Dispatchers.IO) { saver.decode(file.uri, side) }
        } finally {
            if (full) currentLoads.update { it - 1 }
        } ?: return null
        cache.put(shot.id, Page(bitmap, full))
        return bitmap
    }

    fun clear() = cache.evictAll()
}

/** Длинная сторона снимка, вписанного в экран, — до 2560 точек и не больше самого снимка. */
private fun fitSide(shot: Shot, view: IntSize): Int {
    val size = shot.pixelSize ?: return 2560
    if (view.width <= 0 || view.height <= 0) return minOf(2560, maxOf(size.width, size.height))
    val scale = min(1f, min(view.width.toFloat() / size.width, view.height.toFloat() / size.height))
    return minOf(2560, (maxOf(size.width, size.height) * scale).roundToInt())
}

/** Участок снимка в полном разрешении поверх обычной картинки: где он (доли снимка) и сам участок. */
private class Tile(val region: Rect, val image: ImageBitmap)

/**
 * Галерея снимков сеанса: весь экран, чёрный. Сверху — ×, «3 из 12», «Открыть» и «Поделиться»; снизу — подпись (имя
 * файла, что это, МБ, время) и лента всех снимков: текущий — в белой рамке, лента сама прокручивается к нему, касание —
 * переход. Листание — смахиванием, как в ленте: новые слева, влево — к более старым. Увеличение — щипком и двойным
 * касанием (вписать ↔ 2,5× в точке), при увеличении — сдвиг пальцем, а листание выключено. Закрыть — ×, «Назад» или
 * смахивание вниз неувеличенного снимка (снимок едет за пальцем, фон светлеет). Касание снимка прячет и возвращает
 * верх и ленту. Держит снимок по id: новый снимок, пока галерея открыта, открытый не сбивает.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryOverlay(controller: CameraController, screen: ScreenState) {
    val shots = controller.shots
    val openId = screen.galleryShot ?: return
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    // страницы — от старых к новым, а пейджер развёрнут (reverseLayout): новые слева, влево — к более старым, как в
    // ленте. Новый снимок встаёт последней страницей, и номера открытых страниц не сдвигаются — открытый снимок ни на
    // кадр не сменяется другим
    val pager = rememberPagerState(initialPage = (shots.size - 1 - shots.indexOfFirst { it.id == openId }).coerceIn(0, maxOf(0, shots.size - 1))) {
        controller.shots.size
    }
    val images = remember { GalleryImages(controller.saver) }
    var view by remember { mutableStateOf(IntSize.Zero) }
    var chrome by remember { mutableStateOf(true) }
    var zoom by remember { mutableStateOf(ViewZoom.identity) }
    var zoomJob by remember { mutableStateOf<Job?>(null) }
    /** Насколько снимок утащили вниз, точек: закрытие смахиванием. */
    val dismiss = remember { Animatable(0f) }
    val strip = rememberLazyListState()
    val currentShots by rememberUpdatedState(shots)

    fun close() {
        screen.galleryShot = null
    }

    /** Снимок на странице page (страницы — от старых к новым) и место страницы снимка с местом index в ленте. */
    fun shotAt(page: Int): Shot? = currentShots.getOrNull(currentShots.size - 1 - page)
    fun pageOf(index: Int): Int = currentShots.size - 1 - index

    DisposableEffect(Unit) { onDispose { images.clear() } }
    BackHandler { close() }

    // на всякий случай: открытый снимок не на своей странице — возвращаемся к нему
    LaunchedEffect(shots) {
        val index = shots.indexOfFirst { it.id == screen.galleryShot }
        if (index >= 0 && pageOf(index) != pager.currentPage) pager.scrollToPage(pageOf(index))
    }
    // перелистнули — запоминаем открытый снимок; увеличение — только у него
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.collect { page ->
            val id = shotAt(page)?.id ?: return@collect
            if (id != screen.galleryShot) {
                screen.galleryShot = id
                zoomJob?.cancel()
                zoom = ViewZoom.identity
            }
        }
    }
    /** Место текущего снимка в ленте: 0 — самый новый. */
    val currentIndex = shots.size - 1 - pager.currentPage
    // лента галереи — к текущему снимку, по центру
    LaunchedEffect(currentIndex, view) {
        val itemWidth = with(density) { 72.dp.toPx() }
        val viewport = strip.layoutInfo.viewportSize.width
        strip.animateScrollToItem(currentIndex.coerceAtLeast(0), -((viewport - itemWidth) / 2).roundToInt().coerceAtLeast(0))
    }

    val zoomed = zoom.isZoomed
    val progress = if (view.height > 0) (dismiss.value / (view.height * 0.6f)).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxSize().onSizeChanged { view = it }.background(Color.Black.copy(alpha = 1f - progress))) {
        HorizontalPager(
            pager,
            Modifier.fillMaxSize().graphicsLayer {
                translationY = dismiss.value
                val scale = 1f - 0.12f * progress
                scaleX = scale
                scaleY = scale
            },
            beyondBoundsPageCount = 1,
            userScrollEnabled = !zoomed && dismiss.value == 0f,
            reverseLayout = true,
            key = { page -> shotAt(page)?.id ?: -page - 1L },
        ) { page ->
            val shot = shotAt(page) ?: return@HorizontalPager
            val current = page == pager.currentPage
            GalleryPage(
                shot, images, view, current,
                zoom = if (current) zoom else ViewZoom.identity,
                onZoom = { zoomJob?.cancel(); zoom = it },
                onSettle = { target ->
                    zoomJob?.cancel()
                    val from = zoom
                    zoomJob = scope.launch {
                        animate(0f, 1f, animationSpec = spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)) { t, _ ->
                            zoom = ViewZoom.lerp(from, target, t.toDouble())
                        }
                    }
                },
                onTap = { chrome = !chrome },
                onDismissDrag = { dy -> scope.launch { dismiss.snapTo(maxOf(0f, dismiss.value + dy)) } },
                onDismissEnd = { velocity ->
                    scope.launch {
                        if (dismiss.value > with(density) { 120.dp.toPx() } || velocity > with(density) { 1200.dp.toPx() }) {
                            dismiss.animateTo(view.height.toFloat(), tween(180))
                            close()
                        } else {
                            dismiss.animateTo(0f, spring(dampingRatio = 0.8f))
                        }
                    }
                },
            )
        }
        val shot = shots.getOrNull(currentIndex)
        AnimatedVisibility(chrome && dismiss.value == 0f && shot != null, enter = fadeIn(), exit = fadeOut()) {
            if (shot == null) return@AnimatedVisibility
            Box(Modifier.fillMaxSize()) {
                GalleryTopBar(controller, shot, currentIndex, shots.size, onClose = { close() }, Modifier.align(Alignment.TopCenter))
                Column(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                        .padding(top = 28.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(galleryCaption(shot), color = Color.White, fontSize = 16.sp, style = TextStyle(fontFeatureSettings = "tnum"))
                    LazyRow(state = strip, contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        itemsIndexed(shots, key = { _, item -> item.id }) { index, item ->
                            val selected = index == currentIndex
                            Box(Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)).background(PanelLight)
                                .border(if (selected) 2.dp else 0.dp, if (selected) Color.White else Color.Transparent, RoundedCornerShape(8.dp))
                                .clickable {
                                    zoomJob?.cancel()
                                    zoom = ViewZoom.identity
                                    scope.launch { pager.animateScrollToPage(pageOf(index)) }
                                }) {
                                item.thumbnail?.let { Image(it, item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Подпись под снимком: имя файла, что это, сколько весит (меньше мегабайта — в КБ) и когда снят. */
private fun galleryCaption(shot: Shot): String = listOf(
    shot.title,
    shotKind(shot.isFrameGrab, shot.files.map { it.name }, shot.pixelSize),
    if (shot.bytes < 1_048_576) "${(shot.bytes + 1023) / 1024} КБ" else "%.1f МБ".format(shot.bytes / 1_048_576.0),
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(shot.takenAt)),
).joinToString(" · ")

@Composable
private fun GalleryTopBar(controller: CameraController, shot: Shot, index: Int, count: Int, onClose: () -> Unit, modifier: Modifier) {
    Box(
        modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)))
            .padding(horizontal = 18.dp, vertical = 16.dp),
    ) {
        Text("×", color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterStart).size(48.dp).clip(RoundedCornerShape(12.dp)).background(Panel)
                .clickable(onClick = onClose).padding(top = 1.dp))
        Text("${index + 1} из $count", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"), modifier = Modifier.align(Alignment.Center))
        Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GalleryButton("Открыть") { controller.openExternally(shot.jpeg ?: shot.files.first()) }
            GalleryButton("Поделиться") { controller.share(shot.files) }
        }
    }
}

@Composable
private fun GalleryButton(title: String, onClick: () -> Unit) {
    Text(title, color = Color.White, fontSize = 16.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Panel)
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp))
}

/**
 * Страница галереи: снимок из файла (открытый — под экран, соседний — вдвое меньше; пока нет — миниатюра), при
 * увеличении сильнее, чем позволяет декодированная картинка, — видимый участок в полном разрешении через
 * BitmapRegionDecoder через 150 мс покоя. RAW без JPEG — кадр с камеры с пометкой.
 */
@Composable
private fun GalleryPage(
    shot: Shot,
    images: GalleryImages,
    view: IntSize,
    current: Boolean,
    zoom: ViewZoom,
    onZoom: (ViewZoom) -> Unit,
    onSettle: (ViewZoom) -> Unit,
    onTap: () -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
) {
    val fit = remember(shot.id, view) { fitSide(shot, view) }
    var bitmap by remember(shot.id) { mutableStateOf(images.cached(shot.id)) }
    LaunchedEffect(shot.id, current, fit) {
        images.load(shot, fit, full = current)?.let { bitmap = it }
    }
    val image: ImageBitmap? = remember(bitmap) { bitmap?.asImageBitmap() } ?: shot.cameraFrame ?: shot.thumbnail
    val loading = bitmap == null && shot.jpeg != null
    // размер в точках снимка: от него — пропорции и масштаб для участка в полном разрешении
    val full = shot.pixelSize?.let { Size(it.width.toFloat(), it.height.toFloat()) }
        ?: image?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: Size(16f, 9f)
    val tile = rememberTile(shot, images, bitmap, view, current, zoom, full)
    // жесты живут дольше одной перерисовки: размеры и обработчики — всегда последние, а не с первого показа
    val latestZoom by rememberUpdatedState(zoom)
    val latestView by rememberUpdatedState(view)
    val latestFull by rememberUpdatedState(full)
    val latestTap by rememberUpdatedState(onTap)
    val latestSettle by rememberUpdatedState(onSettle)

    Box(
        Modifier.fillMaxSize()
            .pointerInput(shot.id) {
                detectTapGestures(
                    onTap = { latestTap() },
                    onDoubleTap = { point ->
                        val z = latestZoom
                        latestSettle(if (z.isZoomed) ViewZoom.identity
                        else ViewZoom.identity.zoomed(2.5, point, latestView.toSize(), latestFull, elastic = false))
                    },
                )
            }
            .pointerInput(shot.id, view) {
                pageGestures(
                    zoom = { latestZoom }, view = view.toSize(), full = full,
                    onZoom = onZoom, onSettle = onSettle, onDismissDrag = onDismissDrag, onDismissEnd = onDismissEnd,
                )
            },
    ) {
        if (image != null) {
            Canvas(Modifier.fillMaxSize()) {
                val rect = zoom.imageRect(size, full)
                drawPicture(image, rect)
                tile?.let { t ->
                    val r = Rect(rect.left + t.region.left * rect.width, rect.top + t.region.top * rect.height,
                        rect.left + t.region.right * rect.width, rect.top + t.region.bottom * rect.height)
                    drawPicture(t.image, r)
                }
            }
        }
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), color = Color.White, strokeWidth = 2.dp)
        if (shot.jpeg == null) {
            Text("RAW — откройте в другой программе", color = Color.White, fontSize = 16.sp,
                modifier = Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

/** Картинка в прямоугольник экрана; при уменьшении — со сглаживанием. */
private fun DrawScope.drawPicture(image: ImageBitmap, rect: Rect) {
    if (rect.width <= 0f || rect.height <= 0f) return
    drawImage(image, IntOffset.Zero, IntSize(image.width, image.height),
        IntOffset(rect.left.roundToInt(), rect.top.roundToInt()), IntSize(rect.width.roundToInt(), rect.height.roundToInt()),
        filterQuality = FilterQuality.Medium)
}

/**
 * Участок снимка в полном разрешении: когда увеличили сильнее, чем позволяет декодированная под экран картинка, и
 * жест кончился 150 мс назад. Видимая часть с запасом 10 %, прореживание — степень двойки, чтобы участок был не меньше
 * точек экрана, но и не больше полутора экранов.
 */
@Composable
private fun rememberTile(shot: Shot, images: GalleryImages, base: Bitmap?, view: IntSize, current: Boolean, zoom: ViewZoom,
                         full: Size): Tile? {
    var tile by remember(shot.id) { mutableStateOf<Tile?>(null) }
    var decoder by remember(shot.id) { mutableStateOf<BitmapRegionDecoder?>(null) }
    DisposableEffect(shot.id) { onDispose { decoder?.recycle() } }
    LaunchedEffect(shot.id, current, zoom, view, base) {
        val file = shot.jpeg
        if (!current || !zoom.isZoomed || base == null || file == null || view.width <= 0 || full.width <= 0f) {
            tile = null
            return@LaunchedEffect
        }
        delay(150)
        val viewSize = view.toSize()
        val rect = zoom.imageRect(viewSize, full)
        // точек экрана на точку снимка; у декодированной картинки — её точек на точку снимка
        val display = rect.width / full.width
        val baseScale = base.width / full.width
        if (display <= baseScale * 1.05f) {
            tile = null
            return@LaunchedEffect
        }
        val visible = zoom.visibleRegion(viewSize, full)
        val padX = visible.width * 0.1f
        val padY = visible.height * 0.1f
        val region = Rect((visible.left - padX).coerceAtLeast(0f), (visible.top - padY).coerceAtLeast(0f),
            (visible.right + padX).coerceAtMost(1f), (visible.bottom + padY).coerceAtMost(1f))
        val px = android.graphics.Rect((region.left * full.width).toInt(), (region.top * full.height).toInt(),
            (region.right * full.width).toInt(), (region.bottom * full.height).toInt())
        if (px.isEmpty) return@LaunchedEffect
        var sample = Integer.highestOneBit(maxOf(1, floor(1f / display).toInt()))
        val limit = view.width.toLong() * view.height * 3 / 2
        while (px.width().toLong() / sample * (px.height() / sample) > limit) sample *= 2
        // участок не чётче той картинки, что уже есть, — незачем
        if (1f / sample <= baseScale * 1.05f) {
            tile = null
            return@LaunchedEffect
        }
        val opened = decoder ?: withContext(Dispatchers.IO) { images.regionDecoder(file.uri) }?.also { decoder = it }
            ?: return@LaunchedEffect
        val decoded = withContext(Dispatchers.IO) {
            runCatching { opened.decodeRegion(px, BitmapFactory.Options().apply { inSampleSize = sample }) }.getOrNull()
        } ?: return@LaunchedEffect
        tile = Tile(Rect(px.left / full.width, px.top / full.height, px.right / full.width, px.bottom / full.height),
            decoded.asImageBitmap())
        android.util.Log.d("ZVGallery", "участок ${px.width()}×${px.height()} из ${full.width.toInt()}×${full.height.toInt()}, " +
            "прореживание $sample → ${decoded.width}×${decoded.height}, на экране ${"%.2f".format(display)} точки на точку снимка")
    }
    return tile
}

/**
 * Жесты страницы: щипок — увеличение вокруг точки между пальцами (с сопротивлением за пределами), сдвиг увеличенного
 * снимка пальцем; неувеличенный снимок, утащенный вниз, — закрытие; смахивание вбок неувеличенного — листание (его
 * ведёт пейджер, здесь не трогаем).
 */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.pageGestures(
    zoom: () -> ViewZoom,
    view: Size,
    full: Size,
    onZoom: (ViewZoom) -> Unit,
    onSettle: (ViewZoom) -> Unit,
    onDismissDrag: (Float) -> Unit,
    onDismissEnd: (Float) -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // что делает этот жест: ещё не ясно, увеличение/сдвиг, закрытие или листание (не наше)
        var mode = 0
        var moved = Offset.Zero
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        try {
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.count { it.pressed }
                if (pressed == 0) break
                val pan = event.calculatePan()
                if (mode == 0) {
                    moved += pan
                    when {
                        pressed >= 2 -> mode = TRANSFORM
                        moved.getDistance() > slop && zoom().isZoomed -> mode = TRANSFORM
                        moved.getDistance() > slop -> mode = if (moved.y > 0 && abs(moved.y) > abs(moved.x) * 1.2f) DISMISS else PAGER
                    }
                }
                when (mode) {
                    TRANSFORM -> {
                        // шаг за шагом от текущего увеличения, без своей «базы»: прерванный жест не оставит следующему скачок
                        var z = zoom()
                        val k = event.calculateZoom()
                        if (k != 1f) z = z.zoomed(k.toDouble(), event.calculateCentroid(useCurrent = true), view, full, elastic = true)
                        if (pan != Offset.Zero && (z.isZoomed || pressed >= 2)) z = z.panned(pan, view, full)
                        onZoom(z)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    DISMISS -> {
                        onDismissDrag(pan.y)
                        event.changes.firstOrNull()?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        } finally {
            // и когда жест прервали: увеличение не застревает между 0,87× и 1×, снимок не висит на полпути вниз
            when (mode) {
                TRANSFORM -> onSettle(zoom().settled(view, full))
                DISMISS -> onDismissEnd(tracker.calculateVelocity().y)
            }
        }
    }
}

private const val TRANSFORM = 1
private const val DISMISS = 2
private const val PAGER = 3
