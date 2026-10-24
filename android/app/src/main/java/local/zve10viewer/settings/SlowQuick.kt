package local.zve10viewer.settings

import java.util.Locale

/**
 * Фото, видео или замедленная/ускоренная съёмка (S&Q) — по режиму съёмки камеры (0x500E).
 * Для интерфейса S&Q — та же видеосъёмка (`captureMode == video`), отличается только переключатель.
 */
enum class ShootingMode(val title: String) {
    photo("Фото"), video("Видео"), slowQuick("S&Q");

    val captureMode: CaptureMode get() = if (this == photo) CaptureMode.photo else CaptureMode.video

    companion object {
        fun of(exposureProgram: Long): ShootingMode = when (exposureProgram shr 16) {
            // 0x0007_80xx — видео, 0x0009_80xx — S&Q; всё остальное — фоторежимы
            7L -> video
            9L -> slowQuick
            else -> photo
        }

        private fun letter(value: Long): Int = when (value) {
            0x1L, 0x78053L, 0x9805CL -> 3     // M
            0x30004L, 0x78052L, 0x9805BL -> 2 // S
            0x20003L, 0x78051L, 0x9805AL -> 1 // A
            else -> 0                          // P, авто, сюжеты
        }

        private val codes = mapOf(
            photo to listOf(0x10002L, 0x20003L, 0x30004L, 0x1L),
            video to listOf(0x78050L, 0x78051L, 0x78052L, 0x78053L),
            slowQuick to listOf(0x98059L, 0x9805AL, 0x9805BL, 0x9805CL),
        )

        /** Режим съёмки с той же буквой (P/A/S/M) в другом режиме; null — уже там или камера не даёт. */
        fun counterpart(value: Long, to: ShootingMode, settable: List<Long>): Long? {
            if (of(value) == to) return null
            val target = codes.getValue(to)[letter(value)]
            return if (settable.contains(target)) target else null
        }
    }
}

/**
 * Замедленная и ускоренная съёмка (S&Q). Частоту съёмки (1–100 к/с) ZV-E10 по кабелю не сообщает и менять не даёт:
 * её видно только по самой длинной выдержке, которую камера разрешает, — выдержка не может быть длиннее кадра.
 */
object SlowQuick {
    private val palRates = listOf(1, 2, 4, 8, 15, 25, 50, 100)
    private val ntscRates = listOf(1, 2, 4, 8, 15, 30, 60, 120)

    /** Частота съёмки S&Q по допустимым выдержкам (`0xD20D`): числитель в старших 16 битах, знаменатель в младших. */
    fun captureFrameRate(shutterValues: List<Long>, pal: Boolean): Int? {
        val slowest = shutterValues.mapNotNull { value ->
            val numerator = (value shr 16).toDouble()
            val denominator = (value and 0xFFFF).toDouble()
            if (numerator > 0 && denominator > 0) numerator / denominator else null
        }.maxOrNull() ?: return null
        val rates = if (pal) palRates else ntscRates
        // камера даёт выдержку чуть длиннее кадра (1/13 при 15 к/с, 1/6 при 8): ближайшая частота не меньше 1/выдержка
        return rates.firstOrNull { it >= 1 / slowest - 0.01 } ?: rates.last()
    }

    /** Во сколько раз изменится скорость: частота съёмки против частоты ролика. */
    fun speedLabel(capture: Int, record: Int): String {
        if (capture <= 0 || record <= 0 || capture == record) return "обычная скорость"
        val factor = if (capture > record) capture.toDouble() / record else record.toDouble() / capture
        val number = if (factor == Math.rint(factor)) factor.toInt().toString()
        else String.format(Locale.US, "%.1f", factor).replace('.', ',')
        return "×$number " + if (capture > record) "медленнее" else "быстрее"
    }
}
