package local.zve10viewer.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Текст, который должен уместиться в своё место, а не обрезаться. Размер шрифта подбирается по настоящему замеру, а не пропорцией:
 * в теме Material у `Text` разрядка 0,5 sp на букву, и она от размера шрифта не зависит — «Зеркало 180°», сжатое пропорционально,
 * всё равно кончалось многоточием. Длинное имя файла теряет середину, как на Mac: у `look_v1` и `look_v2` концы разные.
 */

/** Точность подбора размера, sp: четверть пункта на глаз не видна. */
private const val SIZE_PRECISION = 0.25f

/**
 * Самый большой размер шрифта от [floor] до [base], при котором текст умещается в [available]. [measure] — ширина текста при
 * таком размере (настоящий замер, в тех же единицах, что [available]); считается, что с размером она не убывает. Умещается и
 * при [base] — он и остаётся; не умещается даже при [floor] — [floor], а дальше многоточие. Размер ищется делением пополам: в
 * конце [measure] для найденного размера уже измерено, что он умещается (или это [floor], и тогда уже ничего не поделать).
 * Ширина, которую не прочитали (не число), считается умещающейся.
 */
internal fun fitFontSize(available: Float, base: Float, floor: Float, measure: (Float) -> Float): Float {
    if (!(measure(base) > available)) return base
    if (floor >= base) return base
    if (measure(floor) > available) return floor
    var fits = floor
    var over = base
    while (over - fits > SIZE_PRECISION) {
        val middle = (fits + over) / 2
        if (measure(middle) > available) over = middle else fits = middle
    }
    return fits
}

private const val ELLIPSIS = "…"

/**
 * Имя, которое умещается ([fits] — «умещается ли такая строка»): целиком, а длинное — с многоточием посередине, поровну с начала
 * и с конца (нечётная буква — в начале): `look_v1` и `look_v2` в узком месте остаются `lo…v1` и `lo…v2`, а не одинаковым
 * `look_…`. Не умещается и одно многоточие — оно и остаётся. Пару символов UTF-16 не разрезает.
 */
internal fun middleEllipsis(text: String, fits: (String) -> Boolean): String {
    if (fits(text)) return text
    // оставить kept букв: с начала — больше, с конца — меньше; обрезанная пополам пара символов выбрасывается
    fun shortened(kept: Int): String {
        var head = text.substring(0, (kept + 1) / 2)
        var tail = text.substring(text.length - kept / 2)
        if (head.isNotEmpty() && head.last().isHighSurrogate()) head = head.dropLast(1)
        if (tail.isNotEmpty() && tail.first().isLowSurrogate()) tail = tail.drop(1)
        return head + ELLIPSIS + tail
    }
    // самое большое число оставленных букв, при котором умещается: чем больше букв, тем шире
    var low = 0
    var high = text.length - 1
    while (low < high) {
        val middle = (low + high + 1) / 2
        if (fits(shortened(middle))) low = middle else high = middle - 1
    }
    return shortened(low)
}

/** Поля по бокам места под текст плитки, dp. */
private val FitPadding = 6.dp

/**
 * Одна строка, которая сжимается от [baseSp] до [floorSp] по ширине места и только потом кончается многоточием. Замер — тем же
 * стилем, каким рисует `Text` (его стиль берётся из темы и подмешивает размер и жирность), так что цифры совпадают с тем, что
 * видно.
 */
@Composable
internal fun FittedText(text: String, color: Color, baseSp: Float, floorSp: Float, measurer: TextMeasurer, fontWeight: FontWeight? = null) {
    val style = LocalTextStyle.current.merge(TextStyle(fontWeight = fontWeight))
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = FitPadding), contentAlignment = Alignment.Center) {
        val available = constraints.maxWidth.toFloat()
        val size = remember(text, available, style, baseSp, floorSp, measurer) {
            fitFontSize(available, baseSp, floorSp) { sp ->
                measurer.measure(text, style.copy(fontSize = sp.sp), softWrap = false, maxLines = 1).size.width.toFloat()
            }
        }
        Text(text, color = color, fontSize = size.sp, fontWeight = fontWeight, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}
