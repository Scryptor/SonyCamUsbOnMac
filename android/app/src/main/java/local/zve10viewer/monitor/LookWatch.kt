package local.zve10viewer.monitor

/**
 * Следит по кадрам за тем, что лежит на картинке: куб (по ссылке, как [PictureTransform]) и поворот. Смена — это строка для
 * logcat сразу, а не через две секунды вместе с остальным: по ней видно, за сколько LUT поменялся после нажатия на плитку или
 * после смены профиля в камере («≤ 1 с» — разница времени между нажатием и этой строкой).
 *
 * Один на цикл кадров: у нового цикла (переподключение, другая главная камера) картинка «пустая» — ни куба, ни поворота, и
 * если у него куб уже есть, первая же строка скажет `off → …`. Пока ничего не менялось, кадр стоит двух сравнений: ни объектов,
 * ни строк. Имя нового куба для строки спрашивают, только когда [changed] сказал «да».
 */
class LookWatch {
    private var lut: ColorLut? = null
    private var orientation = FrameOrientation.off
    private var name = "off"

    /**
     * Другой ли куб лежит на картинке, чем на прошлом кадре (другой экземпляр, в том числе с теми же числами, появился или
     * пропал), или другой ли поворот.
     */
    fun changed(lut: ColorLut?, orientation: FrameOrientation): Boolean = lut !== this.lut || orientation != this.orientation

    /**
     * Принять новое состояние и отдать строку `lut: было → стало orient=поворот`.
     *
     * @param name как зовётся новый LUT: `slog3cine`, `file:имя` или `off` (`LutCache.appliedName`)
     */
    fun accept(lut: ColorLut?, name: String, orientation: FrameOrientation): String {
        val line = "lut: ${this.name} → $name orient=${orientation.value}"
        this.lut = lut
        this.name = name
        this.orientation = orientation
        return line
    }
}
