package local.zve10viewer

import local.zve10viewer.monitor.FrameOrientation
import local.zve10viewer.monitor.FramePoint
import local.zve10viewer.monitor.FrameRect
import local.zve10viewer.monitor.MonitorAssist
import local.zve10viewer.monitor.MonitorTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/** Зеркало и переворот картинки — те же проверки, что у программы для Mac (FrameOrientationTests.swift). */
class FrameOrientationTest {
    private fun close(a: Double, b: Double, eps: Double = 1e-12) = abs(a - b) < eps

    private fun close(a: FramePoint, b: FramePoint, eps: Double = 1e-12) = close(a.x, b.x, eps) && close(a.y, b.y, eps)

    private fun close(a: FrameRect, b: FrameRect, eps: Double = 1e-12) =
        close(a.x, b.x, eps) && close(a.y, b.y, eps) && close(a.width, b.width, eps) && close(a.height, b.height, eps)

    /**
     * Точки кадра: углы, центр и значения, которых нет в двоичной дроби точно (0,3; 0,1…): на них видна ошибка округления
     * в «1 − x».
     */
    private val points = listOf(
        FramePoint(0.3, 0.5), FramePoint(0.2, 0.1), FramePoint(0.0, 0.0), FramePoint(1.0, 1.0), FramePoint(1.0, 0.0),
        FramePoint(0.5, 0.5), FramePoint(0.123, 0.987), FramePoint(0.01, 0.99),
    )

    /** Прямоугольники не квадратные и не по центру: широкий, высокий, у самого края, на весь кадр. */
    private val rects = listOf(
        FrameRect(0.1, 0.2, 0.3, 0.1), FrameRect(0.05, 0.1, 0.1, 0.6), FrameRect(0.9, 0.0, 0.1, 1.0),
        FrameRect(0.0, 0.4, 1.0, 0.2), FrameRect(0.0, 0.0, 1.0, 1.0),
    )

    // значения и подписи

    @Test fun orientationKeepsTheCasesAndRawValues() {
        // порядок — как в таблице спецификации: 0 «Как есть», 1 «Зеркально», 2 «Вверх ногами», 3 «Зеркально + 180°»
        assertEquals(
            listOf(FrameOrientation.off, FrameOrientation.mirror, FrameOrientation.rotate180, FrameOrientation.mirror180),
            FrameOrientation.entries,
        )
        assertEquals(listOf("off", "mirror", "180", "mirror180"), FrameOrientation.entries.map { it.value })
        assertEquals(FrameOrientation.rotate180, FrameOrientation.entries.firstOrNull { it.value == "180" })
        assertNull(FrameOrientation.entries.firstOrNull { it.value == "rotate180" })
        assertNull(FrameOrientation.entries.firstOrNull { it.value == "90" })
    }

    @Test fun orientationFlipsMatchTheTable() {
        // x переворачивают зеркало и 180°; y — 180° и зеркало + 180°
        assertEquals(listOf(false, true, true, false), FrameOrientation.entries.map { it.flipsX })
        assertEquals(listOf(false, false, true, true), FrameOrientation.entries.map { it.flipsY })
    }

    @Test fun orientationTitlesAndLabelsAreTheShippedTexts() {
        // варианты в полосе над плитками и значение на самой плитке
        assertEquals(listOf("Как есть", "Зеркально", "Вверх ногами", "Зеркально + 180°"), FrameOrientation.entries.map { it.title })
        assertEquals(listOf("—", "Зеркало", "180°", "Зеркало 180°"), FrameOrientation.entries.map { it.label })
    }

    @Test fun orientationIsStoredByItsValue() {
        // в настройках — строкой, «180» тоже строка: тот же JSON, что у программы для Mac
        for (o in FrameOrientation.entries) {
            val assist = MonitorAssist().with(MonitorTool.orientation, o.value)!!
            assertEquals(o.value, JSONObject(assist.toJson()).get("orientation"))
            assertEquals(o, MonitorAssist.fromJson("""{"orientation":"${o.value}"}""").orientation)
        }
    }

    // точки

    @Test fun orientationOffLeavesPointsAndRectsAlone() {
        for (p in points) {
            assertEquals(p, FrameOrientation.off.toCamera(p))
            assertEquals(p, FrameOrientation.off.fromCamera(p))
        }
        for (r in rects) assertEquals(r, FrameOrientation.off.fromCamera(r))
    }

    @Test fun mirrorFlipsOnlyTheXAxis() {
        assertTrue(close(FrameOrientation.mirror.toCamera(FramePoint(0.3, 0.5)), FramePoint(0.7, 0.5)))
        assertTrue(close(FrameOrientation.mirror.fromCamera(FramePoint(0.3, 0.5)), FramePoint(0.7, 0.5)))
    }

    @Test fun rotate180FlipsBothAxes() {
        assertTrue(close(FrameOrientation.rotate180.toCamera(FramePoint(0.2, 0.1)), FramePoint(0.8, 0.9)))
        assertTrue(close(FrameOrientation.rotate180.fromCamera(FramePoint(0.2, 0.1)), FramePoint(0.8, 0.9)))
    }

    @Test fun mirror180FlipsOnlyTheYAxis() {
        assertTrue(close(FrameOrientation.mirror180.toCamera(FramePoint(0.2, 0.1)), FramePoint(0.2, 0.9)))
        assertTrue(close(FrameOrientation.mirror180.fromCamera(FramePoint(0.2, 0.1)), FramePoint(0.2, 0.9)))
    }

    @Test fun orientationMappingIsAnInvolution() {
        for (o in FrameOrientation.entries) {
            for (p in points) {
                // туда и обратно — та же точка (до округления дробей), в любую сторону
                assertTrue("$o $p", close(o.toCamera(o.fromCamera(p)), p))
                assertTrue("$o $p", close(o.fromCamera(o.toCamera(p)), p))
                // и перевод в одну сторону совпадает с переводом в другую
                assertEquals("$o $p", o.toCamera(p), o.fromCamera(p))
            }
        }
    }

    @Test fun orientationKeepsTheFrameCenterAndMapsCornersToCorners() {
        for (o in FrameOrientation.entries) {
            assertEquals("$o", FramePoint(0.5, 0.5), o.toCamera(FramePoint(0.5, 0.5)))
        }
        // левый верхний угол экрана при 180° — правый нижний угол камеры
        assertEquals(FramePoint(1.0, 1.0), FrameOrientation.rotate180.toCamera(FramePoint(0.0, 0.0)))
        assertEquals(FramePoint(1.0, 0.0), FrameOrientation.mirror.toCamera(FramePoint(0.0, 0.0)))
        assertEquals(FramePoint(0.0, 1.0), FrameOrientation.mirror180.toCamera(FramePoint(0.0, 0.0)))
    }

    // прямоугольники

    @Test fun fromCameraMovesARectWithoutChangingItsSize() {
        val rect = FrameRect(0.1, 0.2, 0.3, 0.1)
        assertTrue(close(FrameOrientation.off.fromCamera(rect), rect))
        assertTrue(close(FrameOrientation.mirror.fromCamera(rect), FrameRect(0.6, 0.2, 0.3, 0.1)))
        assertTrue(close(FrameOrientation.rotate180.fromCamera(rect), FrameRect(0.6, 0.7, 0.3, 0.1)))
        assertTrue(close(FrameOrientation.mirror180.fromCamera(rect), FrameRect(0.1, 0.7, 0.3, 0.1)))
    }

    @Test fun fromCameraRectIsTheRectOfItsMappedCorners() {
        // широкие и высокие прямоугольники, у краёв и на весь кадр: ширина и высота не меняются местами, рамка не уходит
        // за кадр и остаётся на месте своих углов
        for (o in FrameOrientation.entries) {
            for (r in rects) {
                val a = o.fromCamera(FramePoint(r.x, r.y))
                val b = o.fromCamera(FramePoint(r.maxX, r.maxY))
                val expected = FrameRect(min(a.x, b.x), min(a.y, b.y), abs(a.x - b.x), abs(a.y - b.y))
                val mapped = o.fromCamera(r)
                assertTrue("$o $r", close(mapped, expected))
                assertTrue("$o $r", close(mapped.width, r.width) && close(mapped.height, r.height))
                assertTrue("$o $r", mapped.x >= -1e-12 && mapped.maxX <= 1 + 1e-12)
                assertTrue("$o $r", mapped.y >= -1e-12 && mapped.maxY <= 1 + 1e-12)
                // из камеры на экран и обратно — тот же прямоугольник
                assertTrue("$o $r", close(o.fromCamera(mapped), r))
            }
        }
    }

    @Test fun fromCameraRectKeepsItsCenterOnTheMappedPoint() {
        // центр рамки фокуса попадает туда же, куда его точка
        for (o in FrameOrientation.entries) {
            for (r in rects) {
                val mapped = o.fromCamera(r)
                val center = o.fromCamera(FramePoint(r.x + r.width / 2, r.y + r.height / 2))
                assertTrue("$o $r", close(FramePoint(mapped.x + mapped.width / 2, mapped.y + mapped.height / 2), center))
            }
        }
    }
}
