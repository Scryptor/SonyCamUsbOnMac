package local.zve10viewer

import local.zve10viewer.sony.SonyReferenceTables
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Таблицы из справочника Sony (Camera Control PTP 3 Reference): имена свойств и списки значений. */
class SonyReferenceTablesTest {
    @Test fun referenceNamesTheCreativeLookProperty() {
        assertEquals("Creative Look", SonyReferenceTables.propertyNames[0xD0FA])
    }

    @Test fun referenceNamesValuesOfCameraSettings() {
        assertEquals("XAVC HS 4K", SonyReferenceTables.valueName(0xD241, 0x0B))
        assertEquals("S-Log3", SonyReferenceTables.valueName(0xD0E1, 0x0303))
        assertEquals("S-Gamut3.Cine", SonyReferenceTables.valueName(0xD0E9, 0x0008))
        assertEquals("HEIF", SonyReferenceTables.valueName(0xD253, 0x05))
    }

    @Test fun referenceCoversTheWholeDocument() {
        assertTrue(SonyReferenceTables.propertyNames.size >= 700)
        assertTrue(SonyReferenceTables.values.size >= 500)
    }

    @Test fun referenceReturnsNullForWhatItDoesNotList() {
        assertNull(SonyReferenceTables.valueName(0xD0FA, 0x7777))
        assertNull(SonyReferenceTables.valueName(0x1234, 0x01))
        // у диапазона (min/max/step) списка значений нет
        assertNotNull(SonyReferenceTables.propertyNames[0xD0FB])
        assertNull(SonyReferenceTables.values[0xD0FB])
    }

    /** Страницы справочника разрывают таблицы: номер страницы, колонтитул и повтор заголовка — между строками одной таблицы. */
    @Test fun referenceKeepsRowsOnBothSidesOfAPageBreak() {
        assertEquals("SH(Soft Highkey)", SonyReferenceTables.valueName(0xD0FA, 0x0008))
        assertEquals("BW(Black&White)", SonyReferenceTables.valueName(0xD0FA, 0x0009))
        assertEquals("Custom Look 6", SonyReferenceTables.valueName(0xD0FA, 0x0106))
    }

    /** Значение без описания в своей строке (код посередине высокой ячейки): описание — строки над ним и под ним. */
    @Test fun referenceJoinsDescriptionAroundALoneCode() {
        val live = SonyReferenceTables.valueName(0xD221, 0x00)!!
        assertTrue(live.startsWith("Live View Supported but Disabled") && live.endsWith("LiveView Image."))
        assertTrue(SonyReferenceTables.valueName(0xD221, 0x01)!!.startsWith("Live View Supported and Enabled"))
        assertTrue(SonyReferenceTables.valueName(0xD221, 0x02)!!.startsWith("Live View Not Supported"))
    }

    /** UINT64 целиком: код 0xFFFFFFFFFFFFFFFF читается как значение из камеры, то есть Long −1. */
    @Test fun referenceReadsFullWidthUnsignedKeys() {
        assertEquals("nothing to display.", SonyReferenceTables.valueName(0xD01B, -1L))
    }

    /** Таблицы внутри «Note» описывают разряды составного значения, а не само свойство: в список они не попадают. */
    @Test fun referenceIgnoresTablesInsideNotes() {
        // у 0xE068 в таблице значений 0x00000001 — «1920×1080P», а в Note у разряда Resolution 0x01 — «1920×1080»
        assertEquals("1920×1080P", SonyReferenceTables.valueName(0xE068, 0x0000_0001))
        assertEquals("1920×1080i", SonyReferenceTables.valueName(0xE068, 0x0001_0001))
        // у 0xE06C в таблице значений только «Any», а «1920×1080P» с кодом 0x04 — в Note, у разряда Resolution
        assertNull(SonyReferenceTables.valueName(0xE06C, 0x04))
    }

    /** Диапазоны — не значения: границы min/max/step и код с «～0xFFFFFFFF» под ним списка не пополняют. */
    @Test fun referenceIgnoresRanges() {
        // 0xE042 «Focus Position Setting»: 0x0000 min, 0x0001 step, 0xFFFF max
        assertEquals("Focus Position Setting", SonyReferenceTables.propertyNames[0xE042])
        assertNull(SonyReferenceTables.valueName(0xE042, 0x0000))
        assertNull(SonyReferenceTables.valueName(0xE042, 0xFFFF))
        // 0xD063 «Recording Resolution For RAW (Movie)»: 0x00000000 и под ним ～0xFFFFFFFF — любая ширина и высота
        assertNull(SonyReferenceTables.valueName(0xD063, 0))
        // а режимы, которые зовутся «Step» и «Variable», — значения
        assertEquals("Step", SonyReferenceTables.valueName(0xD019, 0x06))
        assertEquals("Variable", SonyReferenceTables.valueName(0xD019, 0x04))
    }
}
