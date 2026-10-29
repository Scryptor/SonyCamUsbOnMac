package local.zve10viewer.monitor

/**
 * Разбор файлов `.cube` — своих LUT. Принимаются только 3D-таблицы. Правила, числа и тексты ошибок — как у программы для
 * Mac.
 *
 * Что понимает:
 * - `LUT_3D_SIZE n`, `n` от 2 до 65;
 * - `DOMAIN_MIN` и `DOMAIN_MAX` по три числа (по умолчанию 0 и 1); `LUT_3D_INPUT_RANGE min max` — то же одной парой на все
 *   три канала. Что позже, то и верно, по полям;
 * - `TITLE "…"` — название пропускается целиком, с любыми кавычками, решётками и числами внутри;
 * - комментарии `#` — с начала строки и после чисел; пустые строки;
 * - CRLF, одинокий CR и BOM в начале;
 * - данные — `n³` строк `r g b`, красный меняется быстрее всех;
 * - чужие ключевые слова вроде `LUT_IN_VIDEO_RANGE` — пропускаются.
 *
 * Размер должен стоять до первой строки данных: до них последний `LUT_3D_SIZE` заменяет прежний, а после них — ошибка на
 * строке с размером.
 *
 * Числа — только десятичная запись (цифры, знак, точка, порядок): `Float.parseFloat` в Java принимает ещё «1f», «NaN»,
 * «Infinity» и шестнадцатеричные, а Swift — «0x10» и «inf»; чтобы файл читался одинаково везде, знаки проверяются по белому
 * списку, а значение должно быть конечным.
 */
object CubeFile {
    /**
     * Почему файл не принят. Ошибка — значение: равна другой по числам; стек не собирается, разбор отказывает часто и по
     * делу. Текст для окна — [message].
     */
    sealed class Failure : Exception(null, null, false, false) {
        /** Не 3D-таблица `.cube`: `LUT_1D_SIZE`, нет `LUT_3D_SIZE`, а то и вовсе не `.cube`. */
        data object NotThreeD : Failure()

        /** `LUT_3D_SIZE` вне 2…65. Размер — Long: `Int` в Swift 64-битный, и большой целый размер тоже «вне 2…65». */
        data class BadSize(val size: Long) : Failure()

        /** Строк данных не `size³`. */
        data class Count(val found: Int, val expected: Int) : Failure()

        /** В строке (по счёту от 1, считая комментарии и пустые) нет нужных чисел. */
        data class Number(val line: Int) : Failure()
    }

    /** Допустимый размер куба. */
    internal val sizes = 2..65

    /** Разбирает текст файла; отказ — [Failure]. */
    @Throws(Failure::class)
    fun parse(text: String): ColorLut {
        val end = text.length
        var pos = if (end > 0 && text[0] == '\uFEFF') 1 else 0

        var size = 0 // 0 — `LUT_3D_SIZE` ещё не встретился
        var expected = 0 // size³
        var domainMin = floatArrayOf(0f, 0f, 0f)
        var domainMax = floatArrayOf(1f, 1f, 1f)
        var values = NO_VALUES
        var rows = 0
        var line = 0

        while (pos < end) {
            line += 1
            // одним проходом: где кончается строка и с какого места в ней комментарий
            var lineEnd = pos
            var contentEnd = -1
            while (lineEnd < end) {
                val c = text[lineEnd]
                if (c == '\n' || c == '\r') break
                if (c == '#' && contentEnd < 0) contentEnd = lineEnd
                lineEnd += 1
            }
            if (contentEnd < 0) contentEnd = lineEnd
            val lineStart = pos
            // «\r\n» — один перевод строки, одинокий «\r» тоже
            pos = if (lineEnd < end && text[lineEnd] == '\r' && lineEnd + 1 < end && text[lineEnd + 1] == '\n') lineEnd + 2 else lineEnd + 1

            var t = lineStart
            while (t < contentEnd && isBlank(text[t])) t += 1
            if (t == contentEnd) continue // пустая строка или один комментарий

            if (isNumberStart(text[t])) {
                // строка данных
                if (size == 0) throw Failure.NotThreeD
                var count = 0
                var red = 0f
                var green = 0f
                var blue = 0f
                while (true) {
                    while (t < contentEnd && isBlank(text[t])) t += 1
                    if (t == contentEnd) break
                    val start = t
                    while (t < contentEnd && !isBlank(text[t])) t += 1
                    val v = if (count < 3) number(text, start, t) else Float.NaN
                    if (v.isNaN()) throw Failure.Number(line)
                    when (count) {
                        0 -> red = v
                        1 -> green = v
                        else -> blue = v
                    }
                    count += 1
                }
                if (count != 3) throw Failure.Number(line)
                if (rows == 0) values = FloatArray(expected * 3)
                // лишние строки считаем, но не копим: файл всё равно не примут
                if (rows < expected) {
                    values[rows * 3] = red
                    values[rows * 3 + 1] = green
                    values[rows * 3 + 2] = blue
                }
                rows += 1
                continue
            }

            // ключевое слово и его аргументы
            val wordStart = t
            while (t < contentEnd && !isBlank(text[t])) t += 1
            val word = text.substring(wordStart, t)
            val arguments = tokens(text, t, contentEnd)
            when (word) {
                "TITLE" -> {}
                "LUT_1D_SIZE" -> throw Failure.NotThreeD
                "LUT_3D_SIZE" -> {
                    val n = if (arguments.size == 1) integer(arguments[0]) else null
                    if (n == null) throw Failure.Number(line)
                    if (n < sizes.first || n > sizes.last) throw Failure.BadSize(n)
                    // до первой строки данных последний размер заменяет прежний, после — размер менять поздно
                    if (rows != 0) throw Failure.Number(line)
                    size = n.toInt()
                    expected = size * size * size
                }
                "LUT_3D_INPUT_RANGE" -> {
                    // то же, что DOMAIN_MIN min min min и DOMAIN_MAX max max max
                    if (arguments.size != 2) throw Failure.Number(line)
                    val low = number(arguments[0], 0, arguments[0].length)
                    val high = number(arguments[1], 0, arguments[1].length)
                    if (low.isNaN() || high.isNaN()) throw Failure.Number(line)
                    domainMin = floatArrayOf(low, low, low)
                    domainMax = floatArrayOf(high, high, high)
                }
                "DOMAIN_MIN", "DOMAIN_MAX" -> {
                    if (arguments.size != 3) throw Failure.Number(line)
                    val triple = FloatArray(3)
                    for (i in 0 until 3) {
                        val v = number(arguments[i], 0, arguments[i].length)
                        if (v.isNaN()) throw Failure.Number(line)
                        triple[i] = v
                    }
                    if (word == "DOMAIN_MIN") domainMin = triple else domainMax = triple
                }
                else -> {
                    if (isKeyword(word)) continue // чужое ключевое слово — не наше дело
                    // до заголовка это не .cube, после — битая строка данных
                    throw if (size == 0) Failure.NotThreeD else Failure.Number(line)
                }
            }
        }

        if (size == 0) throw Failure.NotThreeD
        if (rows != expected) throw Failure.Count(found = rows, expected = expected)
        // Страховка: куб собирается, только когда сохранено ровно size³ точек и домен целый. По построению так и есть, но
        // нарушенное условие в ColorLut остановило бы разбор исключением IllegalArgumentException, а здесь оно покажется
        // ошибкой разбора
        if (values.size != expected * 3 || domainMin.size != 3 || domainMax.size != 3) {
            throw Failure.Count(found = values.size / 3, expected = expected)
        }
        return ColorLut(size, values, domainMin, domainMax)
    }

    /** Текст ошибки для окна: что не так и как это поправить. */
    fun message(failure: Failure): String = when (failure) {
        Failure.NotThreeD -> "Нужен 3D LUT в формате .cube"
        is Failure.BadSize -> "Размер LUT — ${grouped(failure.size)}, а нужен от ${sizes.first} до ${sizes.last}"
        is Failure.Count -> "В файле ${grouped(failure.found.toLong())} ${rowWord(failure.found)} данных, а нужно ${grouped(failure.expected.toLong())}"
        is Failure.Number -> "В строке ${grouped(failure.line.toLong())} не удалось прочитать числа"
    }

    // разбор

    private val NO_VALUES = FloatArray(0)

    /** Пробел или табуляция. */
    private fun isBlank(c: Char) = c == ' ' || c == '\t'

    /** Строка данных начинается с цифры, знака или точки. */
    private fun isNumberStart(c: Char) = (c in '0'..'9') || c == '-' || c == '+' || c == '.'

    /** Знаки десятичной записи числа: цифры, знак, точка, порядок (`-0.5`, `.25`, `1e-3`). */
    private fun isNumberChar(c: Char) = (c in '0'..'9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

    /** Степени десяти, которые Float хранит точно (10¹⁰ = 2¹⁰·5¹⁰, а 5¹⁰ < 2²⁴). */
    private val POWERS_OF_TEN = floatArrayOf(1f, 1e1f, 1e2f, 1e3f, 1e4f, 1e5f, 1e6f, 1e7f, 1e8f, 1e9f, 1e10f)

    /**
     * Число из знаков `start..<end`, NaN — не число. Только десятичная запись (см. шапку); слишком большие, до бесконечности,
     * тоже не числа.
     *
     * Простые записи («0.333333», «-1», «.5») считаются сразу, без строки и без `parseFloat`: целое до 2²⁴, делённое на
     * степень десяти до 10¹⁰, — одно деление двух точных чисел, то есть то же, что даёт `parseFloat`. Остальное (порядок,
     * длинные записи) и всё неправильное решает `parseFloat` после проверки знаков.
     */
    private fun number(s: String, start: Int, end: Int): Float {
        if (start >= end) return Float.NaN
        var i = start
        var negative = false
        val sign = s[i]
        if (sign == '-' || sign == '+') {
            negative = sign == '-'
            i += 1
        }
        var mantissa = 0L
        var digits = 0
        var fraction = 0
        var dot = false
        var simple = true
        while (i < end) {
            val c = s[i]
            if (c in '0'..'9') {
                if (digits < 18) mantissa = mantissa * 10 + (c - '0') else simple = false
                digits += 1
                if (dot) fraction += 1
            } else if (c == '.' && !dot) {
                dot = true
            } else {
                simple = false
                break
            }
            i += 1
        }
        if (simple && digits > 0 && mantissa <= MAX_EXACT && fraction < POWERS_OF_TEN.size) {
            val v = mantissa.toFloat() / POWERS_OF_TEN[fraction]
            return if (negative) -v else v
        }
        return parsedNumber(s, start, end)
    }

    private const val MAX_EXACT = 16_777_216L

    private fun parsedNumber(s: String, start: Int, end: Int): Float {
        for (i in start until end) if (!isNumberChar(s[i])) return Float.NaN
        val v = try {
            java.lang.Float.parseFloat(s.substring(start, end))
        } catch (e: NumberFormatException) {
            return Float.NaN
        }
        return if (v.isFinite()) v else Float.NaN
    }

    /**
     * Целое со знаком или без, только ASCII-цифры (как `Int(String)` в Swift: `toIntOrNull` принял бы и цифры других
     * письменностей); не влезло в 64 бита — null.
     */
    private fun integer(token: String): Long? {
        var i = 0
        var negative = false
        if (token.isNotEmpty() && (token[0] == '-' || token[0] == '+')) {
            negative = token[0] == '-'
            i = 1
        }
        if (i >= token.length) return null
        // копим со знаком минус: по модулю Long.MIN_VALUE больше Long.MAX_VALUE
        val limit = Long.MIN_VALUE
        val multiplyLimit = limit / 10
        var result = 0L
        while (i < token.length) {
            val c = token[i]
            if (c !in '0'..'9') return null
            val digit = c - '0'
            if (result < multiplyLimit) return null
            result *= 10
            if (result < limit + digit) return null
            result -= digit
            i += 1
        }
        if (negative) return result
        return if (result == limit) null else -result
    }

    private fun tokens(s: String, from: Int, to: Int): List<String> {
        val result = ArrayList<String>(3)
        var t = from
        while (true) {
            while (t < to && isBlank(s[t])) t += 1
            if (t == to) return result
            val start = t
            while (t < to && !isBlank(s[t])) t += 1
            result.add(s.substring(start, t))
        }
    }

    /** Ключевое слово `.cube` — ЗАГЛАВНЫЕ_БУКВЫ с подчёркиванием (`LUT_IN_VIDEO_RANGE`); «NaN» и «abc» — нет. */
    private fun isKeyword(word: String): Boolean {
        if (word.isEmpty() || word[0] !in 'A'..'Z') return false
        var underscore = false
        for (c in word) {
            if (c == '_') {
                underscore = true
            } else if (c !in 'A'..'Z' && c !in '0'..'9') {
                return false
            }
        }
        return underscore
    }

    // тексты

    /** 35936 → «35 936» (обычный пробел, как в тексте спецификации). */
    private fun grouped(n: Long): String {
        val digits = n.toString().removePrefix("-")
        val result = StringBuilder()
        if (n < 0) result.append('-')
        for (i in digits.indices) {
            if (i > 0 && (digits.length - i) % 3 == 0) result.append(' ')
            result.append(digits[i])
        }
        return result.toString()
    }

    /** «строка» при 1, 21, 101; «строки» при 2–4, 22–24; «строк» при остальных, 11–14 тоже. */
    private fun rowWord(n: Int): String {
        val last = n % 10
        val lastTwo = n % 100
        if (last == 1 && lastTwo != 11) return "строка"
        if (last in 2..4 && lastTwo !in 12..14) return "строки"
        return "строк"
    }
}
