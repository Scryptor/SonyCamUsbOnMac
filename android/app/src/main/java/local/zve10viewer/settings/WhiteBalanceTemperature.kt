package local.zve10viewer.settings

import local.zve10viewer.sony.PropertyRange

/**
 * Что показать о цветовой температуре при текущем режиме баланса белого.
 * Камера сообщает только ручную температуру (0xD20F) — и в любом режиме отдаёт её, сохранённую.
 * Температуру пресетов и автобаланса она не сообщает, проверено по всем 95 свойствам.
 */
object WhiteBalanceTemperature {
    sealed class Reading {
        /** Режим «Цвет. темп.»: температура камеры. */
        data class Manual(val kelvin: Long) : Reading()
        /** Пресет: примерная температура. */
        data class Approximate(val kelvin: Long) : Reading()
        /** Автобаланс, «Под водой», «Свой»: подбирает камера, значение неизвестно. */
        data object Automatic : Reading()
    }

    /** Режим баланса белого «Цвет. темп.». */
    const val MANUAL_MODE = 0x8012L
    /** Диапазон ручной температуры, если камера его не сообщила. */
    val fallbackRange = PropertyRange(2500, 9900, 100)

    /**
     * Примерная температура пресетов: дневной, тень, облачно, лампа, вспышка — по Sony Alpha Universe,
     * флуоресцентные — по классам ламп (тёплый, холодный, дневной белый, дневной).
     */
    private val typical = mapOf(
        4L to 5600L, 0x8011L to 7000L, 0x8010L to 6000L, 6L to 3200L, 7L to 5500L,
        0x8001L to 3000L, 0x8002L to 4000L, 0x8003L to 5000L, 0x8004L to 6500L,
    )

    fun reading(whiteBalance: Long?, colorTemperature: Long?): Reading {
        whiteBalance ?: return Reading.Automatic
        if (whiteBalance == MANUAL_MODE && colorTemperature != null) return Reading.Manual(colorTemperature)
        return typical[whiteBalance]?.let { Reading.Approximate(it) } ?: Reading.Automatic
    }

    fun caption(reading: Reading): String = when (reading) {
        is Reading.Manual -> "${reading.kelvin} K"
        is Reading.Approximate -> "≈ ${reading.kelvin} K"
        Reading.Automatic -> "авто"
    }

    /** Где стоит ползунок: на ручной или примерной температуре; при автобалансе — на последней ручной. */
    fun sliderValue(reading: Reading, stored: Long?): Long = when (reading) {
        is Reading.Manual -> reading.kelvin
        is Reading.Approximate -> reading.kelvin
        Reading.Automatic -> stored ?: 5600
    }

    /** Пояснение к значению: откуда оно и что будет, если сдвинуть ползунок. */
    fun explanation(reading: Reading): String = when (reading) {
        is Reading.Manual -> "Температура задана вручную."
        is Reading.Approximate -> "Примерно: температуру пресетов камера не сообщает. Сдвиньте — камера перейдёт на ручную температуру."
        Reading.Automatic -> "Температуру подбирает камера и не сообщает её. Сдвиньте — камера перейдёт на ручную температуру."
    }
}
