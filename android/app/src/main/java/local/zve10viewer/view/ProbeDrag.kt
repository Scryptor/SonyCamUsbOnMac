package local.zve10viewer.view

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import local.zve10viewer.monitor.FramePoint

/**
 * Метка пипетки в жесте над видео: берёт ли она касание в этой точке и куда её сдвинуть. Отдельного жеста у метки нет — она лежит
 * над кадром, и свой жест над ним перехватил бы и щипок над меткой (а после касания палец как раз на ней). Метку берёт тот же
 * жест, что двигает увеличенную картинку (`zoomGestures`).
 */
class ProbeGesture(
    /** Начато касание в точке экрана: true — оно на метке, и палец тащит её, а не картинку (и не ставит ничего касанием). */
    val grabs: (Offset) -> Boolean,
    /** Метку тащат: палец ушёл от точки касания на [moved] точек экрана. */
    val drag: (moved: Offset) -> Unit,
)

/** Как метка пипетки берёт жест и куда идёт за пальцем: геометрия [ProbeLayout] и увеличение экрана, собранные в один жест. */
object ProbeDragging {
    /**
     * @param point где метка сейчас (доли кадра, каким он виден) и [place] — поставить её; зажимает в кадре и отказывает не-числу
     *   контроллер
     * @param zoom увеличение экрана и [frame] — размер кадра (null — кадров нет), [view] — размер экрана в точках: по ним видно, где
     *   кадр на экране. Читаются в момент касания и сдвига: увеличение меняется, а жест живёт дольше одной перерисовки
     */
    fun gesture(
        point: () -> FramePoint,
        place: (FramePoint) -> Unit,
        zoom: () -> ViewZoom,
        frame: () -> Size?,
        view: Size,
        sizes: ProbeLayout.Sizes,
    ): ProbeGesture {
        // откуда метку тащат: где она стояла в миг касания; на каждый жест свой, поэтому жест строится заново на каждое касание
        var base = point()
        return ProbeGesture(
            grabs = { at ->
                val f = frame()
                base = point()
                f != null && ProbeLayout.grabs(at, ProbeLayout.markerCenter(base, zoom().imageRect(view, f)), sizes, Rect(Offset.Zero, view))
            },
            drag = { moved ->
                val f = frame()
                if (f != null) {
                    val z = zoom()
                    // не дальше видимой части кадра: метка не уходит за экран и не теряется под увеличением
                    place(ProbeLayout.dragged(base, moved, z.imageRect(view, f), z.visibleRegion(view, f)))
                }
            },
        )
    }
}

/**
 * Что делает палец в жесте, начатом на метке пипетки. Пока палец один и не ушёл дальше слопа ([holding]), он только касается:
 * дрожь пальца метку не двигает, а касание рядом с меткой потом поставит её на новое место. Ушёл дальше — тащит ([dragging]) и
 * тащит уже до конца жеста, даже если вернулся; метка идёт за пальцем на весь путь от касания, как будто слопа не было, —
 * остаётся под пальцем. Второй палец — это увеличение ([notMarker]): метка отпущена и назад не берётся, даже если палец убрали.
 * Жест не на метке — всегда [notMarker]: его ведёт обычный разбор жестов.
 */
class ProbeDrag(private val grabbed: Boolean, private val slop: Float) {
    enum class Step { notMarker, holding, dragging }

    private var dragging = false
    private var multi = false

    /** [moved] — путь пальца от касания, [pressed] — сколько пальцев сейчас на экране. */
    fun step(moved: Offset, pressed: Int): Step {
        if (!grabbed) return Step.notMarker
        if (pressed >= 2) multi = true
        if (multi) return Step.notMarker
        if (!dragging && moved.getDistance() > slop) dragging = true
        return if (dragging) Step.dragging else Step.holding
    }
}
