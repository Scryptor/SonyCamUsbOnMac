package local.zve10viewer.sony

import local.zve10viewer.ptp.ByteReader
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign
import kotlin.math.truncate

/**
 * Данные объектива — ответ Sony 0x9223 (GetLensInformation) с параметром 2 (метры): по нормированному положению фокуса —
 * расстояние до предмета. Программа просит их только у камер, которые называют 0x9223 в GetDeviceInfo
 * ([DeviceInfo.supports]), и только при 0xE086 = 1 ([SonyProp.LENS_INFO_ENABLED]).
 *
 * Каждая таблица упорядочивается по положению (равные положения — как пришли).
 */
class LensInformation(tables: List<List<Point>>) {
    /** Положение фокуса в шкале таблицы и расстояние до предмета в метрах. */
    data class Point(val position: Double, val meters: Double)

    /**
     * Все таблицы набора, каждая по возрастанию положения. Таблиц больше одной — расстояний не показываем, но панель
     * отладки показывает их все.
     */
    val tables: List<List<Point>> = tables.map { table -> table.sortedWith(compareBy { it.position }) }

    /** Пары первой таблицы по возрастанию положения; равные положения остаются в том порядке, в каком пришли. */
    val pairs: List<Point> = this.tables.firstOrNull() ?: emptyList()

    /**
     * Таблица в шкале 0xE043 (0…65535), а не 0xD24C (0…100): наибольшее положение больше 100. Справочник единиц не
     * называет — это догадка, а сырая таблица видна в отладке.
     */
    val absoluteScale: Boolean = this.tables.any { table -> table.any { it.position > 100 } }

    /**
     * Сколько таблиц в наборе. У зумов их может быть по одной на фокусное расстояние; какая верна, справочник не говорит,
     * поэтому при нескольких расстояний нет: лучше без расстояния, чем с неверным.
     */
    val tableCount: Int = this.tables.size

    /**
     * Расстояние в метрах для положения [atPosition] (в шкале таблицы): линейная интерполяция между соседними парами; в точке
     * таблицы — ровно её расстояние; за краями — расстояние ближайшего края. null — пар нет, таблиц больше одной или
     * положение не число.
     */
    fun meters(atPosition: Double): Double? {
        if (tableCount != 1 || atPosition.isNaN()) return null
        val first = pairs.firstOrNull() ?: return null
        val last = pairs.last()
        if (atPosition <= first.position) return first.meters
        if (atPosition >= last.position) return last.meters
        // положение строго внутри таблицы: первая пара не левее него и пара перед ней (её положение меньше)
        val upper = pairs.indexOfFirst { it.position >= atPosition }
        if (upper < 1) return last.meters
        val b = pairs[upper]
        if (b.position == atPosition) return b.meters
        val a = pairs[upper - 1]
        return a.meters + (b.meters - a.meters) * ((atPosition - a.position) / (b.position - a.position))
    }

    override fun equals(other: Any?): Boolean = other is LensInformation && tables == other.tables

    override fun hashCode(): Int = tables.hashCode()

    override fun toString(): String = "LensInformation(tables=$tables)"

    companion object {
        /**
         * Разбирает ответ 0x9223 (little-endian). Набор: u32 смещение до данных и u32 размер данных; данные — со смещения:
         * u16 версия ×100 (1.00 — 100; на разбор не влияет) и u16 число таблиц; у таблицы u16 число пар и 2 байта резерва,
         * затем пары — u32 нормированное положение и u32 расстояние ×100. Обрезанный набор — PtpParseException.
         * Чужие данные: число таблиц и пар память заранее не выделяет, цикл всегда упирается в конец данных.
         */
        fun parse(data: ByteArray): LensInformation {
            val r = ByteReader(SonyDataset.body(data))
            r.u16()                                                 // версия
            val tableCount = r.u16()
            val tables = ArrayList<List<Point>>()
            repeat(tableCount) {
                val pairCount = r.u16()
                r.skip(2)                                           // резерв
                val pairs = ArrayList<Point>(minOf(pairCount, r.remaining / 8))
                repeat(pairCount) {
                    val position = r.u32().toDouble()
                    val distance = r.u32().toDouble() / 100
                    pairs += Point(position, distance)
                }
                tables += pairs
            }
            return LensInformation(tables)
        }

        /**
         * Расстояние для шкалы фокуса. Сначала число округляется до того, что будет показано, и только потом выбирается
         * вид: до 1 м — до двух цифр после запятой («0,35 м», «0,5 м»), до 10 м — до одной («1,2 м»), дальше — целые
         * («12 м»); лишних нулей и запятой в конце нет («1 м», а не «1,0 м»). Округлилось до 100 м и больше — «∞». Запятая —
         * всегда, чей бы ни был язык системы. Ноль, отрицательное и не число — «0 м».
         */
        fun label(meters: Double): String {
            if (!(meters > 0)) return "0 м"                         // и NaN, и -0.0
            if (meters.isInfinite()) return "∞"
            val hundredths = roundHalfAway(meters * 100) / 100
            if (hundredths < 1) return shown(hundredths, 2) + " м"
            val tenths = roundHalfAway(meters * 10) / 10
            if (tenths < 10) return shown(tenths, 1) + " м"
            val whole = roundHalfAway(meters)
            return if (whole >= 100) "∞" else shown(whole, 0) + " м"
        }

        /** Число с запятой и не больше чем [digits] знаков после неё, без хвостовых нулей: «0,5», «1», «1,2». */
        private fun shown(value: Double, digits: Int): String {
            var text = String.format(Locale.ROOT, "%.${digits}f", value)
            if (text.contains('.')) text = text.trimEnd('0').trimEnd('.')
            return text.replace('.', ',')
        }

        /**
         * Ближайшее целое, на половинке — от нуля (2,5 → 3), как `rounded()` в Swift. kotlin.math.round на половинке идёт к
         * чётному (2,5 → 2), Math.round — к плюс бесконечности: оба тут не годятся, а floor(x + 0,5) врёт у
         * 0,49999999999999994. Дробная часть double точна (x − целая часть не округляется), поэтому ровно 0,5 различимо.
         * Не-число и бесконечность возвращает как есть.
         */
        private fun roundHalfAway(x: Double): Double {
            if (x.isNaN() || x.isInfinite()) return x
            val whole = truncate(x)
            return if (abs(x - whole) >= 0.5) whole + sign(x) else whole
        }
    }
}
