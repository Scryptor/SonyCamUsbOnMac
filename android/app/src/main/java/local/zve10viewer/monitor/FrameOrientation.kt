package local.zve10viewer.monitor

/** Точка кадра — доли ширины и высоты, 0…1. */
data class FramePoint(val x: Double, val y: Double)

/** Прямоугольник кадра — доли 0…1: левый верхний угол (x, y) и размер. */
data class FrameRect(val x: Double, val y: Double, val width: Double, val height: Double) {
    val maxX: Double get() = x + width
    val maxY: Double get() = y + height
}

/**
 * Зеркало и переворот картинки на экране: камера об этом не знает. Меняется то, что видно: сам кадр, помощь, рамки
 * фокуса и касание; снимки и файлы остаются как у камеры.
 *
 * Координаты — доли кадра 0…1, поэтому формат кадра (16:9, 3:2, 1:1) значения не имеет. Все четыре отображения —
 * инволюции (дважды — и всё как было), так что перевод из экрана в камеру и из камеры в экран — одно и то же. Значения,
 * подписи и формулы — как у программы для Mac.
 *
 * Как есть; «зеркало»; «вверх ногами» (поворот на 180°); «зеркало + 180°». В настройках и отладке — «off», «mirror»,
 * «180», «mirror180». title — вариант в полосе над плитками «Картинка», label — значение на самой плитке.
 */
enum class FrameOrientation(val value: String, val title: String, val label: String) {
    off("off", "Как есть", "—"),
    mirror("mirror", "Зеркально", "Зеркало"),
    rotate180("180", "Вверх ногами", "180°"),
    mirror180("mirror180", "Зеркально + 180°", "Зеркало 180°");

    /** Слева направо переворачивают зеркало и 180°. */
    val flipsX: Boolean get() = this == mirror || this == rotate180

    /** Сверху вниз переворачивают 180° и зеркало + 180° (зеркало поверх 180° возвращает x на место). */
    val flipsY: Boolean get() = this == rotate180 || this == mirror180

    /** Точка на экране (доли кадра, каким он виден) → та же точка в кадре камеры: куда ставить фокус и пипетку. */
    fun toCamera(p: FramePoint): FramePoint = FramePoint(if (flipsX) 1 - p.x else p.x, if (flipsY) 1 - p.y else p.y)

    /** Точка в кадре камеры → на экране (метка касания, центр рамки фокуса). Отображение — инволюция, поэтому то же, что toCamera. */
    fun fromCamera(p: FramePoint): FramePoint = toCamera(p)

    /** Прямоугольник в кадре камеры (рамка фокуса) → на экране: уезжает на место, размер тот же — поворота на 90° нет. */
    fun fromCamera(r: FrameRect): FrameRect =
        FrameRect(if (flipsX) 1 - r.maxX else r.x, if (flipsY) 1 - r.maxY else r.y, r.width, r.height)
}
