package local.zve10viewer

import kotlinx.coroutines.test.TestScope
import local.zve10viewer.ui.StripDrag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Перетаскивание в ленте: середина плитки прошла середину соседней — они меняются местами, а плитка остаётся под пальцем.
 * Анимации здесь не идут (TestScope их не запускает): видно, откуда соседняя поедет на новое место.
 */
class StripDragTest {
    /** Шаг ленты на планшете: плитка 112 dp и промежуток 8 dp при плотности 2. */
    private val pitch = 240f

    /** Лента как в книге камер: move ставит плитку на место другой. */
    private class Strip(vararg ids: String?) {
        val order = ids.toMutableList()
        val moves = mutableListOf<Pair<String, String>>()

        fun move(id: String, onto: String) {
            moves += id to onto
            order.add(order.indexOf(onto), order.removeAt(order.indexOf(id)))
        }
    }

    private fun drag(strip: Strip) = StripDrag(TestScope(), { strip.order }, strip::move)

    @Test fun tileSwapsWhenItsMiddlePassesTheNeighboursAndStaysUnderTheFinger() {
        val strip = Strip("A", "B", "C")
        val drag = drag(strip)
        drag.lift("A", pitch)
        assertEquals("A", drag.lifted)
        // середина A ещё не дошла до середины B — плитка просто едет за пальцем
        drag.drag("A", 200f)
        assertEquals(emptyList<Any>(), strip.moves)
        assertEquals(200f, drag.shift("A"))
        // прошла: A за B; место A ниже на шаг — сдвиг на шаг меньше, плитка там же, под пальцем
        drag.drag("A", 60f)
        assertEquals(listOf("A" to "B"), strip.moves)
        assertEquals(listOf("B", "A", "C"), strip.order)
        assertEquals(20f, drag.shift("A"))
        // B видна там, где была, — оттуда поедет на освободившееся место
        assertEquals(pitch, drag.shift("B"))
        // палец вернулся выше прежней середины B — обратно; B, которая ещё не уехала, снова на месте
        drag.drag("A", -270f)
        assertEquals(listOf("A", "B", "C"), strip.order)
        assertEquals(0f, drag.shift("B"))
        // палец на 10 пикселей выше верхнего места — выше ленты плитка не уходит
        assertEquals(0f, drag.shift("A"))
    }

    @Test fun oneLongMoveCanPassSeveralTiles() {
        // adb шлёт редкие MOVE: палец прыгнул сразу на два места
        val strip = Strip("A", "B", "C", "D")
        val drag = drag(strip)
        drag.lift("A", pitch)
        drag.drag("A", 500f)
        assertEquals(listOf("A" to "B", "A" to "C"), strip.moves)
        assertEquals(listOf("B", "C", "A", "D"), strip.order)
        assertEquals(20f, drag.shift("A"))
        assertEquals(pitch, drag.shift("C"))
    }

    @Test fun tileStaysInsideTheStrip() {
        val strip = Strip("A", "B", "C")
        val drag = drag(strip)
        drag.lift("C", pitch)
        // ниже последней — некуда: плитка на месте
        drag.drag("C", 300f)
        assertEquals(0f, drag.shift("C"))
        // палец вернулся — плитка поедет, только когда он дойдёт до неё
        drag.drag("C", -350f)
        assertEquals(-50f, drag.shift("C"))
        assertEquals(emptyList<Any>(), strip.moves)
    }

    @Test fun theMainWithoutIdAndARefusedMoveStopTheSwap() {
        // главная ещё подключается (null) — её место не занимают
        val connecting = Strip(null, "A", "B")
        val drag = drag(connecting)
        drag.lift("A", pitch)
        drag.drag("A", -300f)
        assertEquals(emptyList<Any>(), connecting.moves)
        assertEquals(-pitch, drag.shift("A"))
        // книга не переставила (нет записи) — плитка просто над соседней, без бесконечного цикла
        val order = mutableListOf<String?>("A", "B")
        val refused = StripDrag(TestScope(), { order }) { _, _ -> }
        refused.lift("A", pitch)
        refused.drag("A", 300f)
        assertEquals(pitch, refused.shift("A"))
        assertEquals(listOf("A", "B"), order)
    }

    @Test fun afterTheDropTheFingerNoLongerMovesTheTile() {
        val strip = Strip("A", "B")
        val drag = drag(strip)
        drag.lift("B", pitch)
        drag.drop("B")
        assertNull(drag.lifted)
        drag.drag("B", -300f)
        // не поднятую плитку тоже не двигают
        drag.drag("A", 300f)
        assertEquals(emptyList<Any>(), strip.moves)
        assertEquals(0f, drag.shift("B"))
    }
}
