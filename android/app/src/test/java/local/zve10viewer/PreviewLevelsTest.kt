package local.zve10viewer

import local.zve10viewer.monitor.BuiltinLut
import local.zve10viewer.monitor.ColorLut
import local.zve10viewer.monitor.PreviewLevels
import local.zve10viewer.monitor.SonyCurves
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10

/**
 * Уровни превью камеры и вход LUT: значение канала превью `v = Y′/255` → код камеры/1023 = `a + b·v`. Те же проверки и те же
 * числа, что у программы для Mac (PreviewLevelsTests.swift); числа — из проверки на ZV-E10 (`scripts/check_log_preview.py`,
 * `docs/protocol.md`, «Превью в S-Log и HLG»).
 */
class PreviewLevelsTest {
    private fun near(a: Double, b: Double, eps: Double): Boolean = abs(a - b) <= eps

    /** Значение превью (0…255) для кода камеры. */
    private fun previewLevel(code: Double): Double = PreviewLevels.preview(code / 1023) * 255

    /** S-Log3: отражение → код 0…1023 (обратное к `SonyCurves.slog3ToLinear`). */
    private fun slog3Code(l: Double): Double =
        if (l >= 0.01125) 420 + 261.5 * log10((l + 0.01) / 0.19) else 95 + l * (171.2102946929 - 95) / 0.01125

    @Test fun previewStretchesCodesSixtyFourToTenNineteen() {
        assertEquals(64.0 / 1023, PreviewLevels.OFFSET, 1e-12)
        assertEquals(955.0 / 1023, PreviewLevels.GAIN, 1e-12)
        assertEquals(64.0, PreviewLevels.code(0.0) * 1023, 1e-9)
        assertEquals(1019.0, PreviewLevels.code(1.0) * 1023, 1e-9)
        var v = 0.0
        while (v <= 1.0) {
            assertEquals("v = $v", v, PreviewLevels.preview(PreviewLevels.code(v)), 1e-12)
            v += 0.125
        }
    }

    @Test fun knownCameraLevelsLandWhereThePreviewShowsThem() {
        // белое видео (код 940, 100 %) — 234: так превью видео и выглядит; чёрное (код 64) — 0, белое фото (код 1019) — 255
        assertTrue(near(previewLevel(940.0), 234.0, 0.5))
        assertTrue(near(previewLevel(64.0), 0.0, 1e-9))
        assertTrue(near(previewLevel(1019.0), 255.0, 1e-9))
        // S-Log3: чёрное (код 95) — 8, а не 24; 18 % серого (код 420) — 95, а не 105; S-Log2: чёрное (код 90) — 7
        assertTrue(near(previewLevel(95.0), 8.3, 0.1))
        assertTrue(near(previewLevel(420.0), 95.1, 0.1))
        assertTrue(near(previewLevel(90.28), 7.0, 0.1))
    }

    private class Measured(val mode: String, val slog2: Double, val slog3: Double)

    /**
     * S-Log2 и S-Log3 одной сцены при той же экспозиции, ZV-E10: медианы блоков 8×8 по корзинам S-Log2 (значение превью S-Log2
     * → значение превью S-Log3 у тех же блоков). Видео 1024×576 и фото 1024×1024, комната с торшером, ISO 640, 1/50, f/3.5.
     */
    private val measuredSLog2ToSLog3 = listOf(
        Measured("видео", 11.0, 16.5), Measured("видео", 15.0, 25.8), Measured("видео", 22.3, 38.5), Measured("видео", 33.9, 54.7),
        Measured("видео", 55.1, 77.4), Measured("видео", 84.6, 101.8), Measured("видео", 121.3, 129.5), Measured("видео", 163.9, 160.6),
        Measured("фото", 11.2, 16.7), Measured("фото", 15.0, 25.0), Measured("фото", 22.2, 38.5), Measured("фото", 34.6, 55.8),
        Measured("фото", 53.9, 76.3), Measured("фото", 84.5, 102.5), Measured("фото", 126.4, 134.4), Measured("фото", 163.7, 160.9),
    )

    @Test fun sLog2AndSLog3OfTheSameSceneAgreeOnlyWithThePreviewStretch() {
        assertEquals(16, measuredSLog2ToSLog3.size)
        // через растяжение превью S-Log2 и S-Log3 дают одну и ту же сцену: S-Log3, предсказанный по S-Log2, — в пределах 1,5 уровня
        for (pair in measuredSLog2ToSLog3) {
            val light = SonyCurves.slog2ToLinear(PreviewLevels.code(pair.slog2 / 255))
            val predicted = previewLevel(slog3Code(light))
            assertTrue("${pair.mode} ${pair.slog2} → $predicted, измерено ${pair.slog3}", near(predicted, pair.slog3, 1.5))
        }
        // прежнее предположение «v = код/1023» в тенях промахивается на 5 уровней и больше: оно и давало чёрную картинку с LUT
        val shadows = measuredSLog2ToSLog3.filter { it.slog2 < 30 }
        assertEquals(6, shadows.size)
        for (pair in shadows) {
            val light = SonyCurves.slog2ToLinear(pair.slog2 / 255)
            val predicted = slog3Code(light) / 1023 * 255
            assertTrue("${pair.mode} ${pair.slog2}: прежнее предположение дало бы $predicted", abs(predicted - pair.slog3) >= 5)
        }
    }

    @Test fun forPreviewKeepsTheNumbersAndMovesOnlyTheDomain() {
        val cube = ColorLut.identity(5)
        val adapted = PreviewLevels.forPreview(cube)
        assertEquals(cube.size, adapted.size)
        assertSame("числа куба — тот же массив, а не копия", cube.values, adapted.values)
        for (channel in 0 until 3) {
            assertEquals(-64.0 / 955, adapted.domainMin[channel].toDouble(), 1e-6)
            assertEquals(959.0 / 955, adapted.domainMax[channel].toDouble(), 1e-6)
        }
        // исходный куб не тронут
        for (channel in 0 until 3) {
            assertEquals(0f, cube.domainMin[channel], 0f)
            assertEquals(1f, cube.domainMax[channel], 0f)
        }
    }

    @Test fun identityCubeForThePreviewShowsTheCameraCode() {
        // тождественный куб видит код камеры/1023: превью 0 — 64/1023, 1 — 1019/1023
        val adapted = PreviewLevels.forPreview(ColorLut.identity(2))
        for (v in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
            val out = adapted.apply(v.toFloat(), v.toFloat(), v.toFloat())
            val want = PreviewLevels.code(v)
            assertTrue("$v → $out", near(out.first.toDouble(), want, 1e-5) && near(out.second.toDouble(), want, 1e-5) &&
                near(out.third.toDouble(), want, 1e-5))
        }
    }

    @Test fun ownCubeDomainIsKeptInCameraCodes() {
        // свой .cube с доменом 0,1…0,9 по коду камеры: вход превью v попадает в него как a + b·v
        val cube = ColorLut(2, ColorLut.identity(2).values, floatArrayOf(0.1f, 0.1f, 0.1f), floatArrayOf(0.9f, 0.9f, 0.9f))
        val adapted = PreviewLevels.forPreview(cube)
        for (v in listOf(0.0, 0.2, 0.5, 0.8, 1.0)) {
            val code = PreviewLevels.code(v).toFloat()
            val want = cube.apply(code, code, code)
            val got = adapted.apply(v.toFloat(), v.toFloat(), v.toFloat())
            assertTrue("$v: $got, ждали $want",
                abs(got.first - want.first) <= 1e-5 && abs(got.second - want.second) <= 1e-5 && abs(got.third - want.third) <= 1e-5)
        }
    }

    @Test fun builtinLutsTurnPreviewLevelsIntoTheControlPoints() {
        // контрольные точки спецификации — теперь со стороны превью: значение превью → куб для превью → экран
        fun out(lut: BuiltinLut, code: Double): Double {
            val v = PreviewLevels.preview(code / 1023).toFloat()
            return PreviewLevels.forPreview(lut.make()).apply(v, v, v).second.toDouble()
        }
        for (lut in listOf(BuiltinLut.slog3cine, BuiltinLut.slog3)) {
            assertTrue("$lut: серый", near(out(lut, 420.0), 0.409, 0.01))
            assertTrue("$lut: чёрное", near(out(lut, 95.0), 0.0, 0.01))
            assertTrue("$lut: 90 %", near(out(lut, 598.0), 0.90, 0.015))
        }
        assertTrue(near(out(BuiltinLut.slog2, 347.0), 0.409, 0.015))
        assertTrue(near(out(BuiltinLut.hlg, 721.0), 0.924, 0.015))
    }
}
