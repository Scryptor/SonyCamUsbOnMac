package local.zve10viewer.monitor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import local.zve10viewer.sony.SonyControl
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Рамка фокуса из кадра live view — где камера сейчас фокусирует: рамки AF, лица, слежение. В ответе на GetObject
 * 0xFFFFC002 после смещения и размера JPEG идут смещение и размер блока FocalFrameInfo (Sony Camera Control PTP 3
 * Reference): версия, рамки AF, с версии 1.01 — лица и слежение. Координаты — числитель и знаменатель (у ZV-E10 — сетка
 * 640×480 ×1024), X и Y — центр рамки. Как у программы для Mac.
 */
data class FocusFrame(val kind: Kind, val rect: Rect, val focused: Boolean) {
    enum class Kind { focus, face, tracking }

    companion object {
        private fun u16(b: ByteArray, at: Int) = if (at + 2 <= b.size) (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) else 0
        private fun u32(b: ByteArray, at: Int): Double =
            if (at + 4 <= b.size) (u16(b, at).toLong() or (u16(b, at + 2).toLong() shl 16)).toDouble() else 0.0

        /** Рамки из ответа камеры; пусто — блока нет или в нём ни одной рамки. */
        fun fromLiveView(liveView: ByteArray): List<FocusFrame> {
            if (liveView.size < 16) return emptyList()
            val offset = u32(liveView, 8).toLong()
            val size = u32(liveView, 12).toLong()
            if (size <= 0 || offset < 16 || offset + size > liveView.size) return emptyList()
            return fromInfo(liveView.copyOfRange(offset.toInt(), (offset + size).toInt()))
        }

        /** Блок FocalFrameInfo сам по себе. */
        fun fromInfo(info: ByteArray): List<FocusFrame> {
            if (info.size < 56) return emptyList()
            val version = u16(info, 0)
            // версия и резерв (8), резерв (40), счётчик резервного массива и резерв (8), сам массив по 24 байта
            var pos = 48
            pos += 8 + u16(info, pos) * 24
            val frames = ArrayList<FocusFrame>()
            val kinds = if (version >= 101) listOf(Kind.focus, Kind.face, Kind.tracking) else listOf(Kind.focus)
            for (kind in kinds) {
                if (pos + 16 > info.size) break
                val xDen = u32(info, pos)
                val yDen = u32(info, pos + 4)
                val count = u16(info, pos + 8)
                var entry = pos + 16
                repeat(count) {
                    if (entry + 24 > info.size) return@repeat
                    val state = u16(info, entry + 2)
                    val x = u32(info, entry + 8)
                    val y = u32(info, entry + 12)
                    val height = u32(info, entry + 16)
                    val width = u32(info, entry + 20)
                    if (xDen > 0 && yDen > 0 && (width > 0 || height > 0)) {
                        val w = width / xDen
                        val h = height / yDen
                        val cx = x / xDen
                        val cy = y / yDen
                        frames += FocusFrame(kind, Rect((cx - w / 2).toFloat(), (cy - h / 2).toFloat(), (cx + w / 2).toFloat(),
                            (cy + h / 2).toFloat()), state == 2)
                    }
                    entry += 24
                }
                pos += 16 + count * 24
            }
            return frames
        }

        /** Блок FocalFrameInfo версии 1.01 — для тестов: рамки в сетке 640×480 ×1024. */
        fun encode(frames: List<FocusFrame>): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            fun u16(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }
            fun u32(v: Long) { for (shift in 0 until 32 step 8) out.write((v shr shift).toInt() and 0xFF) }
            fun zeros(n: Int) = repeat(n) { out.write(0) }
            u16(101)
            zeros(6 + 40)
            u16(0)
            zeros(6)
            val xDen = 640L * 1024
            val yDen = 480L * 1024
            for (kind in Kind.entries) {
                val mine = frames.filter { it.kind == kind }
                u32(xDen)
                u32(yDen)
                u16(mine.size)
                zeros(6)
                for (frame in mine) {
                    u16(1)
                    u16(if (frame.focused) 2 else 1)
                    zeros(4)
                    u32((frame.rect.center.x * xDen).roundToLong())
                    u32((frame.rect.center.y * yDen).roundToLong())
                    u32((frame.rect.height * yDen).roundToLong())
                    u32((frame.rect.width * xDen).roundToLong())
                }
            }
            zeros(16)
            return out.toByteArray()
        }
    }
}

/**
 * Фокус касанием по кадру: в зонах «Точка» и «Зона» — точка AF (0xD2DC), она двигает рамку, как джойстик камеры; в
 * остальных — касание экрана камеры (Remote Touch 0xD2E4): фокус или слежение, как настроено в камере. Координаты —
 * сетка 640×480 на весь кадр превью, какой бы ни была его форма.
 */
object TouchFocus {
    enum class Method { areaPosition, remoteTouch }

    const val GRID_WIDTH = 640
    const val GRID_HEIGHT = 480

    /** Зона фокуса, в которой точку AF можно двигать: зона и точка S/M/L/расширенная, они же со слежением. */
    fun movesPoint(focusArea: Long?): Boolean =
        focusArea != null && (focusArea == 0x2L || focusArea in 0x101L..0x104L || focusArea == 0x202L || focusArea in 0x204L..0x207L)

    /** Как ставить фокус касанием сейчас; null — никак: ручной фокус или камера этого не умеет. */
    fun method(focusMode: Long?, focusArea: Long?, remoteTouchEnabled: Long?, controls: Set<Int>): Method? = when {
        focusMode == 1L -> null
        movesPoint(focusArea) && SonyControl.AF_AREA_POSITION in controls -> Method.areaPosition
        remoteTouchEnabled == 1L && SonyControl.REMOTE_TOUCH in controls -> Method.remoteTouch
        else -> null
    }

    /** Точка кадра (доли 0…1) — значение команды: x в старших двух байтах, y в младших. */
    fun encode(x: Double, y: Double): Long {
        val gx = (x.coerceIn(0.0, 1.0) * (GRID_WIDTH - 1)).roundToLong()
        val gy = (y.coerceIn(0.0, 1.0) * (GRID_HEIGHT - 1)).roundToLong()
        return (gx shl 16) or gy
    }

    /** Обратно: значение команды — точка кадра в долях. */
    fun decode(value: Long): Offset =
        Offset(((value shr 16) and 0xFFFF).toFloat() / (GRID_WIDTH - 1), (value and 0xFFFF).toFloat() / (GRID_HEIGHT - 1))

    /** Что сказать, если касание фокус не ставит. */
    fun refusal(focusMode: Long?) =
        if (focusMode == 1L) "В ручном фокусе — шкала фокуса внизу" else "Камера сейчас не даёт ставить фокус касанием"
}

/**
 * Зум объектива с приводом (у ZV-E10 — китовый PZ 16-50) или Clear Image Zoom камеры: пока держат T или W, камера
 * зумирует (0xD2DD = 1 или −1), отпустили — стоп (0). Где зум сейчас — по шкале 0xD25D и масштабу 0xD25C.
 */
object PowerZoom {
    fun isAvailable(zoomEnabled: Long?, controls: Set<Int>) = zoomEnabled == 1L && SonyControl.ZOOM_OPERATION in controls

    /** Шкала зума 0xD25D: сколько отрезков, в каком (с 0), где в нём (0…100). */
    data class Bar(val boxes: Int, val box: Int, val position: Int) {
        /** Где зум на всей шкале: 0 — самый широкий, 1 — самый длинный; null — шкалы нет. */
        val fraction: Double?
            get() = if (boxes <= 0) null else ((box + min(position, 100) / 100.0) / boxes).coerceIn(0.0, 1.0)

        companion object {
            fun of(value: Long) = Bar((value shr 24 and 0xFF).toInt(), (value shr 16 and 0xFF).toInt(), (value and 0xFFFF).toInt())
        }
    }

    /** Масштаб 0xD25C (×1000) подписью: «×1,0», «×1,5»; null — масштаба нет. */
    fun label(scale: Long?): String? =
        if (scale == null || scale <= 0) null else "×" + String.format(java.util.Locale.ROOT, "%.1f", scale / 1000.0).replace('.', ',')
}

/**
 * Шкала фокуса: перефокусировка ближе или дальше шагами Near/Far (0xD2D1: −7…7) — ручной фокус с экрана. Где фокус —
 * по 0xD24C (0…100), если камера его отдаёт.
 */
object FocusDrive {
    /** Шаг: маленький, средний, большой — 1, 3, 7 единиц Near/Far. */
    enum class Step(val units: Int) { small(1), medium(3), large(7) }

    fun isAvailable(nearFarEnabled: Long?, controls: Set<Int>) = nearFarEnabled == 1L && SonyControl.NEAR_FAR in controls

    /** Значение команды: ближе — отрицательное. */
    fun value(step: Step, nearer: Boolean): Long = (if (nearer) -step.units else step.units).toLong()

    /** Перетаскивание по шкале на долю её ширины — шаги: вся шкала — около 40 больших шагов. Отрицательное — ближе. */
    fun steps(forDrag: Double): Int = (forDrag * 280).toInt()

    /** Разложить столько единиц Near/Far на команды не больше 7: 17 → 7, 7, 3; −2 → −1, −1. */
    fun commands(units: Int): List<Long> {
        var rest = abs(units)
        val out = ArrayList<Long>()
        for (step in listOf(7, 3, 1)) {
            while (rest >= step) {
                out += (if (units < 0) -step else step).toLong()
                rest -= step
            }
        }
        return out
    }
}
