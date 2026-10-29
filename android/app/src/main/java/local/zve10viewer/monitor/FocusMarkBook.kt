package local.zve10viewer.monitor

import local.zve10viewer.sony.SonyProp
import local.zve10viewer.sony.SonyProperty

/**
 * Метки фокуса A и B всех камер на время работы программы. Правила, которые не зависят от экрана (те же, что у программы для
 * Mac, FocusMarkBook.swift):
 *
 * - у каждой камеры свои метки, по её id; обрыв связи с той же камерой их не стирает;
 * - метки — числа в единицах режима перевода (0…65535 у абсолютного фокуса, 0…100 у шагов Near/Far), и в другом режиме они
 *   ничего не значат: камера, у которой 0xE042 то открыт, то закрыт, не должна поехать к «65000» по шкале 0…100. Поэтому
 *   метки, записанные в одном режиме, в другом не видны; первая же новая метка начинает набор заново;
 * - смена объектива (событие 0xC21B) стирает обе метки; время перевода — настройка, а не снимок объектива, и остаётся.
 *
 * Значение, а не изменяемый объект: каждое изменение возвращает новую книгу (как [FocusMarks.cycleDuration] — новые метки), а
 * поля [cameras] и [units] снаружи читать не нужно — только методы.
 */
data class FocusMarkBook(
    /** Метки и время перевода по id камеры. */
    val cameras: Map<String, FocusMarks> = emptyMap(),
    /** В единицах какого режима записаны метки камеры; нет записи — меток не ставили (или объектив сменился). */
    val units: Map<String, FocusPull.Mode> = emptyMap(),
) {
    /** Метки камеры [camera] в единицах режима [mode]; нет камеры в книге — пустые, с временем по умолчанию. */
    fun marks(camera: String, mode: FocusPull.Mode): FocusMarks {
        val stored = cameras[camera] ?: FocusMarks()
        val recorded = units[camera]
        return if (recorded != null && recorded != mode) stored.copy(a = null, b = null) else stored
    }

    /**
     * Запомнить [position] в метку [mark] («A» или «B», другая буква и не-число игнорируются). Метки другого режима при этом
     * уходят: рядом с числами новых единиц им не место.
     */
    fun save(mark: Char, position: Double, camera: String, mode: FocusPull.Mode): FocusMarkBook {
        if (mark !in letters || !position.isFinite()) return this
        val current = marks(camera, mode).with(mark, position)
        return copy(cameras = cameras + (camera to current), units = units + (camera to mode))
    }

    /** Стереть метку; пустую стирать нечего. */
    fun clear(mark: Char, camera: String, mode: FocusPull.Mode): FocusMarkBook {
        val current = marks(camera, mode)
        if (current[mark] == null) return this
        return copy(cameras = cameras + (camera to current.with(mark, null)))
    }

    /** Время перевода камеры, секунд. */
    fun duration(camera: String): Double = cameras[camera]?.duration ?: FocusMarks().duration

    /** Следующее время по кругу 0 → 1 → 2 → 4 → 8 → 0; метки не трогает. */
    fun cycleDuration(camera: String): FocusMarkBook {
        val stored = cameras[camera] ?: FocusMarks()
        return copy(cameras = cameras + (camera to stored.cycleDuration()))
    }

    /** Поставить время перевода: только из списка (0, 1, 2, 4, 8). null — другое число, книга прежняя. */
    fun setDuration(seconds: Double, camera: String): FocusMarkBook? {
        // сравнение по IEEE (−0,0 == 0,0, не-число не равно ничему), а не contains(): тот сравнил бы объекты Double
        if (FocusPull.durations.none { it == seconds }) return null
        val stored = cameras[camera] ?: FocusMarks()
        // + 0,0: −0,0 хранится как 0, и подпись не станет «-0 с»
        return copy(cameras = cameras + (camera to stored.copy(duration = seconds + 0.0)))
    }

    /** Камера сообщила о смене объектива: обе метки стёрты, время перевода осталось. */
    fun lensChanged(camera: String): FocusMarkBook {
        val stored = cameras[camera] ?: return this
        return copy(cameras = cameras + (camera to stored.copy(a = null, b = null)), units = units - camera)
    }

    // касание кнопки метки

    /** Что делает касание кнопки метки. */
    enum class Tap {
        /** Метка пуста: запомнить, где фокус сейчас. */
        save,

        /** Метка задана: перевести фокус к ней. */
        go,

        /** Фокус едет к этой метке: остановить. */
        stop,
    }

    companion object {
        /** Метки, которые есть. */
        val letters: List<Char> = listOf('A', 'B')

        /**
         * Касание кнопки [mark]: едущая метка — стоп, пустая — запомнить, заданная — ехать (в том числе когда фокус едет к другой
         * метке). [moving] — к какой метке фокус едет сейчас; null — не едет.
         */
        fun tap(mark: Char, marks: FocusMarks, moving: Char?): Tap = when {
            moving == mark -> Tap.stop
            marks[mark] == null -> Tap.save
            else -> Tap.go
        }
    }
}

/** Положение метки «A» или «B»; другая буква — null. */
operator fun FocusMarks.get(mark: Char): Double? = when (mark) {
    'A' -> a
    'B' -> b
    else -> null
}

/** Те же метки, но метка «A» или «B» — на [position] (null — стёрта). Другая буква ничего не меняет. */
fun FocusMarks.with(mark: Char, position: Double?): FocusMarks = when (mark) {
    'A' -> copy(a = position)
    'B' -> copy(b = position)
    else -> this
}

/** Положение фокуса и способ его переводить — по свойствам камеры. */
object FocusPosition {
    /**
     * Как у этой камеры переводить фокус между метками: абсолютно, если она перечисляет 0xE042, он открыт на запись и 0xE043
     * отдаёт положение; иначе шагами Near/Far, если отдаёт 0xD24C; иначе (положения нет — метку запомнить нечем) null.
     */
    fun mode(properties: Map<Int, SonyProperty>): FocusPull.Mode? {
        val setting = properties[SonyProp.FOCUS_POSITION_SETTING]
        if (setting != null && setting.isWritable && current(properties, FocusPull.Mode.absolute) != null) return FocusPull.Mode.absolute
        return if (current(properties, FocusPull.Mode.steps) != null) FocusPull.Mode.steps else null
    }

    /** Где фокус сейчас в единицах режима: 0xE043 (0…65535) или 0xD24C (0…100). Свойства нет или оно закрыто — null. */
    fun current(properties: Map<Int, SonyProperty>, mode: FocusPull.Mode): Double? {
        val code = if (mode == FocusPull.Mode.absolute) SonyProp.FOCUS_POSITION_CURRENT else SonyProp.FOCAL_POSITION
        val property = properties[code]?.takeIf { it.enabled != 0 } ?: return null
        return property.current.intValue?.toDouble()
    }

    /** Вся шкала режима: 65535 у абсолютного фокуса, 100 у шагов. */
    fun scale(mode: FocusPull.Mode): Double = if (mode == FocusPull.Mode.absolute) 65535.0 else 100.0

    /** Какая доля шкалы 0…1 приходится на положение (метка на линейке); не-число — 0. */
    fun fraction(position: Double, mode: FocusPull.Mode): Double {
        val share = position / scale(mode)
        return if (share.isNaN()) 0.0 else share.coerceIn(0.0, 1.0)
    }

    /**
     * Насколько близко фокус уже «у метки» и ехать не нужно: 0,5 % шкалы у абсолютного, `max(1, k/2)` при k₀ = 3,5 у шагов —
     * столько же, сколько допускает сам [FocusPull] ([FocusPull.tolerance]).
     */
    fun tolerance(mode: FocusPull.Mode): Double = FocusPull.tolerance(mode)
}
