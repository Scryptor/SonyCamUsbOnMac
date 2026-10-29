package local.zve10viewer.monitor

import kotlin.math.min

/**
 * Трёхмерная таблица цвета (LUT): куб `size³` точек, в каждой — цвет на выходе. Между точками — трилинейно. Встроенные LUT
 * и файлы `.cube` приходят сюда одинаковыми; на Mac таблица уходит в `CIColorCube`, на Android — в целочисленную таблицу
 * `PictureTransform`.
 *
 * Массивы принадлежат кубу: после создания их не меняют (`PictureTransform` держит таблицу, собранную из них, и узнаёт куб
 * по экземпляру).
 *
 * @property size точек на ребре куба, не меньше 2
 * @property values `size³ × 3` чисел: красный, зелёный, синий каждой точки; красный меняется быстрее всех, потом зелёный,
 *   потом синий (как в `.cube`). Выход — 0…1; что за ними, `apply` обрезает, а таблица `PictureTransform` — ещё до
 *   интерполяции, как Core Image на Mac
 * @property domainMin вход, который куб растягивает на свои 0…`size − 1`: по числу на красный, зелёный и синий. У `.cube`
 *   это `DOMAIN_MIN`/`DOMAIN_MAX` или `LUT_3D_INPUT_RANGE`; по умолчанию 0…1
 * @property domainMax верхняя граница входа, см. `domainMin`
 */
class ColorLut(
    val size: Int,
    val values: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
) {
    init {
        val cells = size.toLong() * size * size
        require(size >= 2 && values.size.toLong() == cells * 3) { "ColorLut: нужны size ≥ 2 и size³·3 чисел" }
        require(domainMin.size == 3 && domainMax.size == 3) { "ColorLut: домен — по одному числу на канал" }
    }

    /**
     * Цвет на выходе для цвета на входе. Вход — в своём домене (за ним обрезается), между точками — трилинейно; выход
     * обрезается до 0…1. Ни бесконечность, ни NaN на входе не ломают: это край домена или его начало; домен без ширины или
     * перевёрнутый (max ≤ min) стоит в начале.
     */
    fun apply(r: Float, g: Float, b: Float): Triple<Float, Float, Float> {
        val rx = position(r, 0)
        val gx = position(g, 1)
        val bx = position(b, 2)
        val r0 = min(rx.toInt(), size - 2)
        val g0 = min(gx.toInt(), size - 2)
        val b0 = min(bx.toInt(), size - 2)
        val rf = rx - r0
        val gf = gx - g0
        val bf = bx - b0
        return Triple(channel(0, r0, g0, b0, rf, gf, bf), channel(1, r0, g0, b0, rf, gf, bf), channel(2, r0, g0, b0, rf, gf, bf))
    }

    /** Равны, если равны размер, домен и все числа — поэлементно, как `==` у массивов в Swift (0 и −0 равны). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ColorLut || size != other.size) return false
        return sameNumbers(domainMin, other.domainMin) && sameNumbers(domainMax, other.domainMax) && sameNumbers(values, other.values)
    }

    override fun hashCode(): Int = (size * 31 + hashOf(domainMin)) * 31 + hashOf(domainMax)

    override fun toString() = "ColorLut(size=$size, domain=${domainMin.toList()}…${domainMax.toList()})"

    // внутренности

    /** Место входа на оси канала: 0…`size − 1` (домен применён, вне его обрезано, NaN — начало). */
    private fun position(value: Float, channel: Int): Float {
        val span = domainMax[channel] - domainMin[channel]
        // `>`, а не `max/min`: NaN попадает в «не больше нуля» и становится началом домена
        val t = if (span > 0f) (value - domainMin[channel]) / span else 0f
        val unit = if (t > 0f) (if (t < 1f) t else 1f) else 0f
        return unit * (size - 1).toFloat()
    }

    private fun channel(k: Int, r0: Int, g0: Int, b0: Int, rf: Float, gf: Float, bf: Float): Float {
        val n = size
        val base = ((b0 * n + g0) * n + r0) * 3 + k
        val dg = n * 3
        val db = n * n * 3
        // сначала вдоль красного, потом вдоль зелёного, потом вдоль синего
        val c00 = lerp(values[base], values[base + 3], rf)
        val c10 = lerp(values[base + dg], values[base + dg + 3], rf)
        val c01 = lerp(values[base + db], values[base + db + 3], rf)
        val c11 = lerp(values[base + db + dg], values[base + db + dg + 3], rf)
        val c0 = lerp(c00, c10, gf)
        val c1 = lerp(c01, c11, gf)
        return clamp(lerp(c0, c1, bf))
    }

    companion object {
        /** Тождественный куб: выход равен входу. */
        fun identity(size: Int): ColorLut {
            require(size >= 2 && size.toLong() * size * size * 3 <= Int.MAX_VALUE) { "ColorLut: размер от 2 и так, чтобы size³·3 влезло в массив" }
            val values = FloatArray(size * size * size * 3)
            val last = (size - 1).toFloat()
            var i = 0
            for (b in 0 until size) {
                for (g in 0 until size) {
                    for (r in 0 until size) {
                        values[i++] = r / last
                        values[i++] = g / last
                        values[i++] = b / last
                    }
                }
            }
            return ColorLut(size, values)
        }

        /** `a + (b − a)·t`, а не `a·(1 − t) + b·t`: если углы равны, получается ровно он, без шума округления. */
        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

        private fun clamp(v: Float): Float = if (v > 0f) (if (v < 1f) v else 1f) else 0f

        private fun sameNumbers(a: FloatArray, b: FloatArray): Boolean {
            if (a.size != b.size) return false
            for (i in a.indices) if (a[i] != b[i]) return false
            return true
        }

        /** Хеш домена; `+ 0f` превращает −0 в 0, чтобы равные кубы (0 == −0) давали один хеш. */
        private fun hashOf(a: FloatArray): Int {
            var h = 1
            for (x in a) h = h * 31 + (x + 0f).hashCode()
            return h
        }
    }
}
