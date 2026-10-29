package local.zve10viewer.monitor

/**
 * Кадр, из которого проход картинки берёт точки и в который кладёт результат. На Android это Bitmap из пула кадров
 * (`getPixels` и `setPixels`), в тестах — массив. Точки — ARGB_8888, `0xAARRGGBB`, по строкам, ровно `width · height`.
 */
interface FrameSurface {
    val width: Int
    val height: Int

    /** Все точки кадра — в начало [into] (массив может быть больше кадра). */
    fun read(into: IntArray)

    /** Заменить точки кадра первыми `width · height` числами [from]: это и есть картинка на экране. */
    fun write(from: IntArray)
}

/**
 * Проход картинки в потоке кадров: зеркало, переворот и LUT один раз, сразу после декодирования. Кадр читается в
 * многоразовый массив, [PictureTransform] считает картинку на экран (и, если помощь считается «без LUT», повёрнутый сигнал
 * камеры), результат ложится обратно в тот же кадр, и помощь берёт нужные точки у прохода, не читая кадр второй раз.
 *
 * Меняется только картинка на экране. JPEG камеры (снимок, стоп-кадр, эталон наложения) и сама камера остаются как есть.
 *
 * Буферы — три массива на сеанс: точки кадра, картинка на экран и (только при помощи «без LUT») сигнал. Растут вместе с
 * кадром и не уменьшаются: кадр поменьше (качество превью по Wi-Fi) берёт начало большого массива. Новых массивов на кадр нет.
 *
 * Владелец — цикл кадров своего сеанса: у каждого сеанса свой проход, так что старый, ещё доделывающий кадр, не мешает новому
 * (второй одновременный `apply` у [PictureTransform] — ошибка). Закрывает его тот же цикл, когда кончается. [close] можно из
 * любого потока и в любой миг: кадр, который уже считается, доводится до конца, а следующий получает [Step.Stopped].
 *
 * `apply` зовёт один поток за раз.
 */
class PicturePath(private val transform: PictureTransform = PictureTransform()) : AutoCloseable {
    /** Что вышло с кадром. */
    enum class Step {
        /** Ни поворота, ни куба: кадр не тронут, его точки брать у самого кадра. [help] пуст. */
        Untouched,

        /** Кадр посчитан и записан обратно; точки для помощи — [help]. */
        Done,

        /** Проход закрыт (или остановлен ошибкой): кадр не тронут, цикл кадров должен кончиться, а не крутиться. */
        Stopped,
    }

    /**
     * Точки, по которым считается помощь на последнем кадре: картинка, которую видно (с поворотом и LUT), или, если помощь
     * «без LUT», повёрнутый сигнал камеры. Первые `width · height` чисел — остальное мусор прежних кадров. Годится до
     * следующего `apply`. После [Step.Untouched] и [Step.Stopped] пуст.
     */
    var help: IntArray = NO_PIXELS
        private set

    /**
     * Ошибка, на которой остановился кадр: `IllegalStateException` от [PictureTransform] — полоса не сосчиталась или `close()`
     * пришёл посреди кадра. После `close()` до кадра, как и пока всё в порядке, null.
     */
    var failure: Throwable? = null
        private set

    /** Сколько раз за жизнь прохода выделялся массив: растёт, только когда кадр больше всех прежних. Для проверки буферов. */
    var allocations = 0
        private set

    @Volatile private var stopped = false

    private var src = NO_PIXELS
    private var display = NO_PIXELS
    private var analysis = NO_PIXELS

    /**
     * @param signalForHelp помощь считается по повёрнутому сигналу камеры без LUT (`lutScopes` выключен), и она сейчас нужна;
     *   имеет смысл только при кубе — без него сигнал и картинка на экране одно и то же
     */
    fun apply(surface: FrameSurface, orientation: FrameOrientation, lut: ColorLut?, signalForHelp: Boolean): Step {
        // закрытый проход не «ничего не делает», а останавливает цикл: иначе кадры без LUT шли бы так, будто всё в порядке
        if (stopped) return stop()
        if (!isNeeded(orientation, lut)) {
            help = NO_PIXELS
            return Step.Untouched
        }
        val width = surface.width
        val height = surface.height
        val count = width * height
        val signal = signalForHelp && lut != null
        reserve(count, signal)
        surface.read(src)
        try {
            transform.apply(src, width, height, orientation, lut, display, if (signal) analysis else null)
        } catch (e: IllegalStateException) {
            // закрыт: close() пришёл, пока кадр читался, — или полоса не сосчиталась. И то и другое — конец цикла кадров
            failure = e
            return stop()
        }
        surface.write(display)
        help = if (signal) analysis else display
        return Step.Done
    }

    /** Останавливает проход и его потоки; можно из любого потока, сколько угодно раз. */
    override fun close() {
        stopped = true
        transform.close()
    }

    private fun stop(): Step {
        stopped = true
        help = NO_PIXELS
        return Step.Stopped
    }

    private fun reserve(count: Int, signal: Boolean) {
        if (src.size < count) {
            src = IntArray(count)
            allocations += 1
        }
        if (display.size < count) {
            display = IntArray(count)
            allocations += 1
        }
        if (signal && analysis.size < count) {
            analysis = IntArray(count)
            allocations += 1
        }
    }

    companion object {
        /**
         * Нужен ли проход: есть куб или поворот. По итоговому кубу (`LutCache.cube`), а не по настройке: «Авто» у камеры без
         * профиля лога куба не даёт, и тогда кадр не трогают совсем — это и держит частоту кадров.
         */
        fun isNeeded(orientation: FrameOrientation, lut: ColorLut?): Boolean = lut != null || orientation != FrameOrientation.off

        private val NO_PIXELS = IntArray(0)
    }
}
