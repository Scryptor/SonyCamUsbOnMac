package local.zve10viewer.monitor

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * Проход картинки кадра на процессоре: зеркало и переворот перестановкой точек и LUT трилинейно в целых числах. Вызывается
 * на каждый кадр живого вида, поэтому быстрый и без выделений: ни массивов, ни объектов на кадр (новая таблица — только когда
 * приходит другой LUT, и то лишь если он больше прежнего).
 *
 * Строки делятся на [bands] полос, полосы считаются параллельно: одну — поток вызова, остальные — свои потоки объекта
 * (`bands − 1` штук, daemon, запускаются при первом кадре). Объект один на кадровый цикл; когда он не нужен, его закрывают
 * ([close]) — потоки уходят.
 *
 * Не повторно входимый: `apply` вызывает один поток за раз (кадровый цикл); второй одновременный вызов — ошибка
 * [IllegalStateException], а не молчаливая порча кадра. Последовательно из разных потоков можно. [close] можно из любого
 * потока и в любой миг, в том числе посреди кадра: он дожидается кадра, а следующий `apply` отказывает.
 *
 * LUT. Из куба ([ColorLut]) один раз строится целочисленная таблица (узлы по 12 бит на канал в одном `Long`, три канала
 * рядом), и её же берут все следующие кадры, пока приходит тот же экземпляр куба. Другой экземпляр — таблица строится
 * заново на этом вызове (на JVM — десятые доли миллисекунды для 33³ и около полумиллисекунды для 65³), так что держите
 * один экземпляр на LUT. Вход точки `v = канал/255` идёт через домен куба так же, как в [ColorLut.apply].
 *
 * Расхождение с [ColorLut.apply] (числа с плавающей точкой, округление до уровня 0…255), если узлы куба в 0…1:
 * - не больше одного уровня из 255 на канал у встроенных, плавных и тождественных кубов;
 * - до двух уровней у кубов с очень крутыми ступенями между соседними узлами (от чёрного до белого; заметнее при узком
 *   домене): таблица ошибается на 1/512 ячейки по каждой из трёх осей, умноженную на ступень;
 * - узлы вне 0…1 обрезаются ещё до интерполяции — так их получает и Core Image на Mac, — а `ColorLut.apply` обрезает после
 *   неё; у таких кубов расхождение больше, и это намеренно.
 */
class PictureTransform(private val bands: Int = 4) : AutoCloseable {
    init {
        require(bands in 1..MAX_BANDS) { "PictureTransform: полос от 1 до $MAX_BANDS" }
    }

    /**
     * Кадр в [display]: повёрнутый по [orientation] и, если [lut] не null, с LUT; в [analysis] (если не null) — повёрнутый
     * без LUT, для помощи «без LUT». Без поворота и LUT [display] — копия кадра.
     *
     * @param src точки кадра по строкам, ARGB_8888 как у `Bitmap.getPixels` (`0xAARRGGBB`); не меняется. Альфа точки
     *   переходит в результат как есть (у кадров камеры она 255)
     * @param width ширина кадра
     * @param height высота кадра
     * @param display сюда ложится картинка, которую видно; первые `width·height` чисел
     * @param analysis сюда ложится повёрнутая картинка без LUT; null — не нужна
     * @throws IllegalArgumentException размер отрицательный или не влезает в Int, массив меньше кадра, один массив в двух
     *   ролях, в кубе больше [MAX_LUT_SIZE] точек на ребре (из `.cube` приходят до 65). Массивы могут быть больше кадра
     *   (буфер под самый крупный размер): лишнее не читается и не пишется. Кадр нулевого размера — ничего не делает
     * @throws IllegalStateException объект закрыт, или `apply` уже идёт в другом потоке, или в этот миг идёт `close()`
     */
    fun apply(
        src: IntArray,
        width: Int,
        height: Int,
        orientation: FrameOrientation,
        lut: ColorLut?,
        display: IntArray,
        analysis: IntArray?,
    ) {
        // Ворота первыми, и «закрыт» — первое за ними: close() входит через те же ворота, так что между проверкой и кадром оно
        // не вклинится. closing ставится до ожидания ворот, поэтому после начала закрытия кадр отказывает сразу, а не
        // соревнуется с close() за свободный миг между кадрами (у цикла с тяжёлыми кадрами он — сотые доли процента времени)
        check(running.compareAndSet(false, true)) { "PictureTransform.apply уже идёт в другом потоке или объект закрывается" }
        try {
            check(!closing) { "PictureTransform закрыт" }
            require(width >= 0 && height >= 0) { "размер кадра $width×$height" }
            val pixels = width.toLong() * height
            require(pixels <= Int.MAX_VALUE) { "кадр $width×$height не влезает в массив" }
            val count = pixels.toInt()
            require(src.size >= count && display.size >= count && (analysis == null || analysis.size >= count)) {
                "массивы меньше кадра $width×$height: src ${src.size}, display ${display.size}, analysis ${analysis?.size}"
            }
            require(src !== display && analysis !== src && analysis !== display) { "src, display и analysis — три разных массива" }
            require(lut == null || lut.size <= MAX_LUT_SIZE) { "куб ${lut?.size}³: больше $MAX_LUT_SIZE точек на ребре не берём" }
            if (count == 0) return
            if (lut != null) prepare(lut)
            jobSrc = src
            jobDisplay = display
            jobAnalysis = analysis
            jobWidth = width
            jobHeight = height
            jobFlipX = orientation.flipsX
            jobFlipY = orientation.flipsY
            jobLut = lut != null
            runBands()
        } finally {
            // кадр не держим дольше вызова: буферы кадрового цикла могут смениться
            jobSrc = NO_PIXELS
            jobDisplay = NO_PIXELS
            jobAnalysis = null
            running.set(false)
        }
    }

    /**
     * Останавливает потоки объекта. Можно из любого потока и сколько угодно раз; с первого мига новые `apply` отказывают
     * (`IllegalStateException`), а кадр, который считается, доводится до конца, и `close` ждёт его (миллисекунды). Ждать
     * нужно: помощник, ушедший до уже объявленной полосы, оставил бы `apply` ждать его вечно. Поэтому потоки останавливает
     * тот же вход `running`, что у кадра, — либо целиком до кадра, либо целиком после.
     */
    override fun close() {
        closing = true
        while (!running.compareAndSet(false, true)) LockSupport.parkNanos(GATE_POLL_NANOS)
        try {
            closed = true
            for (worker in workers) LockSupport.unpark(worker)
        } finally {
            running.set(false)
        }
    }

    // задание вызова: пишет поток вызова до запуска полос, читают полосы после него (передаёт `generation`)

    private var jobSrc: IntArray = NO_PIXELS
    private var jobDisplay: IntArray = NO_PIXELS
    private var jobAnalysis: IntArray? = null
    private var jobWidth = 0
    private var jobHeight = 0
    private var jobFlipX = false
    private var jobFlipY = false
    private var jobLut = false

    // таблица LUT: узлы, куда попадает каждое из 256 значений канала, и от какого куба это всё

    private var table = LongArray(0)
    private var tableN = 0
    private var tableLut: ColorLut? = null

    /** На каждое значение канала 0…255 (красный, зелёный, синий подряд): `(сдвиг узла вдоль оси) shl 9 or доля 0…256`. */
    private val places = IntArray(3 * 256)

    // потоки

    private val running = AtomicBoolean(false)
    private val pending = AtomicInteger(0)

    @Volatile private var generation = 0

    /** Закрытие началось: новые кадры отказывают; идущий кадр дойдёт до конца. Ставит close() до того, как встанет в ворота. */
    @Volatile private var closing = false

    /** Помощникам пора уходить. Ставится только за воротами `running`, когда кадра нет и нового не будет, — полосу не пропустить. */
    @Volatile private var closed = false

    @Volatile private var waiter: Thread? = null

    @Volatile private var failure: Throwable? = null
    @Volatile private var workers: Array<Thread> = NO_WORKERS
    private val serial = SERIAL.incrementAndGet()

    private inner class Worker(private val band: Int) : Thread("PictureTransform-$serial-$band") {
        init {
            isDaemon = true
        }

        override fun run() {
            var seen = 0
            while (true) {
                var current = generation
                while (current == seen) {
                    // closed ставят только за воротами running, когда кадра нет и нового не будет: объявленной полосы не пропустить
                    if (closed) return
                    LockSupport.park(this@PictureTransform)
                    Thread.interrupted() // поднятый флаг сделал бы park пустым: ждать нечем, а цикл крутится
                    current = generation
                }
                seen = current
                try {
                    runBand(band)
                } catch (t: Throwable) {
                    failure = t
                }
                // кто ждёт — читаем до уменьшения: после него вызывающий поток может уже начать следующий кадр
                val caller = waiter
                if (pending.decrementAndGet() == 0) LockSupport.unpark(caller)
            }
        }
    }

    private fun startWorkers() {
        if (workers.isNotEmpty()) return
        val created = Array<Thread>(bands - 1) { Worker(it + 1) }
        try {
            for (worker in created) worker.start()
        } catch (t: Throwable) {
            closing = true
            closed = true
            for (worker in created) LockSupport.unpark(worker)
            throw t
        }
        workers = created
    }

    private fun runBands() {
        if (bands == 1) {
            runBand(0)
            return
        }
        startWorkers()
        waiter = Thread.currentThread()
        failure = null
        pending.set(bands - 1)
        generation += 1 // пишет один поток, а читают полосы: задание выше этой записи им видно
        for (worker in workers) LockSupport.unpark(worker)
        var own: Throwable? = null
        try {
            runBand(0)
        } catch (t: Throwable) {
            own = t
        }
        // дождаться всех полос, даже если своя упала: они пишут в буферы вызывающего
        var interrupted = false
        while (pending.get() != 0) {
            LockSupport.park(this)
            if (Thread.interrupted()) interrupted = true
        }
        if (interrupted) Thread.currentThread().interrupt()
        val problem = own ?: failure
        if (problem != null) throw IllegalStateException("PictureTransform: полоса не сосчиталась", problem)
    }

    /** Полоса `band`: строки кадра от `height·band/bands` до `height·(band + 1)/bands`. */
    private fun runBand(band: Int) {
        val rows = jobHeight.toLong()
        val first = (rows * band / bands).toInt()
        val last = (rows * (band + 1) / bands).toInt()
        if (first >= last) return
        if (jobLut) lutRows(first, last) else copyRows(first, last)
    }

    /** Без LUT: строки целиком (`arraycopy`) или задом наперёд, если зеркало. */
    private fun copyRows(first: Int, last: Int) {
        val src = jobSrc
        val out = jobDisplay
        val side = jobAnalysis
        val w = jobWidth
        val h = jobHeight
        val flipX = jobFlipX
        val flipY = jobFlipY
        for (y in first until last) {
            val from = (if (flipY) h - 1 - y else y) * w
            val to = y * w
            if (flipX) {
                var s = from + w - 1
                var d = to
                val rowEnd = to + w
                while (d < rowEnd) {
                    out[d] = src[s]
                    d += 1
                    s -= 1
                }
            } else {
                System.arraycopy(src, from, out, to, w)
            }
            if (side != null) System.arraycopy(out, to, side, to, w)
        }
    }

    /**
     * С LUT: на каждую точку — три места в таблице (по каналам), восемь углов ячейки и три шага интерполяции: вдоль
     * красного (4), зелёного (2), синего (1). Три канала считаются разом, как три поля одного `Long` (см. [LANE]).
     */
    private fun lutRows(first: Int, last: Int) {
        val src = jobSrc
        val out = jobDisplay
        val side = jobAnalysis
        val w = jobWidth
        val h = jobHeight
        val flipX = jobFlipX
        val flipY = jobFlipY
        val table = table
        val places = places
        val n = tableN
        val n2 = n * n
        val step = if (flipX) -1 else 1
        for (y in first until last) {
            var s = (if (flipY) h - 1 - y else y) * w + (if (flipX) w - 1 else 0)
            var d = y * w
            val rowEnd = d + w
            while (d < rowEnd) {
                val p = src[s]
                if (side != null) side[d] = p
                val pr = places[(p shr 16) and 0xFF]
                val pg = places[256 + ((p shr 8) and 0xFF)]
                val pb = places[512 + (p and 0xFF)]
                val i = (pr ushr 9) + (pg ushr 9) + (pb ushr 9)
                val fr = (pr and 0x1FF).toLong()
                val fg = (pg and 0x1FF).toLong()
                val fb = (pb and 0x1FF).toLong()
                // вдоль красного: четыре пары узлов ячейки
                val c00 = ((table[i] * (256 - fr) + table[i + 1] * fr + ROUND_8) ushr 8) and LANES
                val c10 = ((table[i + n] * (256 - fr) + table[i + n + 1] * fr + ROUND_8) ushr 8) and LANES
                val c01 = ((table[i + n2] * (256 - fr) + table[i + n2 + 1] * fr + ROUND_8) ushr 8) and LANES
                val c11 = ((table[i + n2 + n] * (256 - fr) + table[i + n2 + n + 1] * fr + ROUND_8) ushr 8) and LANES
                // вдоль зелёного
                val d0 = ((c00 * (256 - fg) + c10 * fg + ROUND_8) ushr 8) and LANES
                val d1 = ((c01 * (256 - fg) + c11 * fg + ROUND_8) ushr 8) and LANES
                // вдоль синего, сразу в уровни 0…255 (узлы в 1/16 уровня, доли в 1/256: всего 1/4096, с округлением)
                val e = d0 * (256 - fb) + d1 * fb + ROUND_12
                val red = ((e ushr 12) and 0xFF).toInt()
                val green = ((e ushr 33) and 0xFF).toInt()
                val blue = ((e ushr 54) and 0xFF).toInt()
                out[d] = (p and ALPHA) or (red shl 16) or (green shl 8) or blue
                d += 1
                s += step
            }
        }
    }

    /** Таблица и места для этого куба; тот же экземпляр, что в прошлый раз, ничего не стоит. */
    private fun prepare(lut: ColorLut) {
        if (lut === tableLut) return
        val n = lut.size
        val nodes = n * n * n
        if (table.size < nodes) table = LongArray(nodes)
        val values = lut.values
        val table = table
        var j = 0
        for (i in 0 until nodes) {
            table[i] = node(values[j]) or (node(values[j + 1]) shl LANE) or (node(values[j + 2]) shl (2 * LANE))
            j += 3
        }
        for (channel in 0 until 3) {
            val low = lut.domainMin[channel]
            val span = lut.domainMax[channel] - low
            val stride = when (channel) {
                0 -> 1
                1 -> n
                else -> n * n
            }
            for (value in 0..255) {
                // та же цепочка, что в ColorLut.apply: домен, обрезка, место на оси узлов, ячейка и доля пути
                val t = if (span > 0f) (value / 255f - low) / span else 0f
                val unit = if (t > 0f) (if (t < 1f) t else 1f) else 0f
                val place = unit * (n - 1).toFloat()
                val cell = min(place.toInt(), n - 2)
                val fraction = ((place - cell) * 256f + 0.5f).toInt()
                places[channel * 256 + value] = ((cell * stride) shl 9) or fraction
            }
        }
        tableN = n
        tableLut = lut
    }

    /** Узел таблицы: выход 0…1 (за ними обрезано) в 1/16 уровня из 255, то есть 0…4080. NaN — 0. */
    private fun node(x: Float): Long {
        val c = if (x > 0f) (if (x < 1f) x else 1f) else 0f
        return (c * NODE_ONE + 0.5f).toLong()
    }

    companion object {
        /** Куб больше этого не берём: места в таблице хранятся как `сдвиг shl 9`, и у куба побольше они не влезли бы в Int. */
        const val MAX_LUT_SIZE = 129

        private const val MAX_BANDS = 64

        /** Как часто close() заглядывает в ворота, пока идёт кадр. */
        private const val GATE_POLL_NANOS = 50_000L

        /** Ширина поля одного канала в `Long`: 21 бит. Узел — до 4080, умноженный на вес до 256, — до 2²⁰: влезает с запасом. */
        private const val LANE = 21
        private const val ALPHA = 0xFF000000.toInt()

        /** Узел, равный 1: 255 уровней по 16 долей. */
        private const val NODE_ONE = 4080f

        /** По 128 и по 2048 в каждое поле: округление до сдвига на 8 и на 12 бит. */
        private const val ROUND_8 = (128L) or (128L shl LANE) or (128L shl (2 * LANE))
        private const val ROUND_12 = (2048L) or (2048L shl LANE) or (2048L shl (2 * LANE))

        /** После сдвига вправо на 8 в поле остаются лишние биты соседнего поля; здесь нижние 12 бит каждого. */
        private const val LANES = (0xFFFL) or (0xFFFL shl LANE) or (0xFFFL shl (2 * LANE))

        private val NO_PIXELS = IntArray(0)
        private val NO_WORKERS = emptyArray<Thread>()
        private val SERIAL = AtomicInteger()
    }
}
