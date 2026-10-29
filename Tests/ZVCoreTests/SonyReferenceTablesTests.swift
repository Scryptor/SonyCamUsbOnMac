import Foundation
import Testing
@testable import ZVCore

// Таблицы из справочника Sony (Camera Control PTP 3 Reference): имена свойств и списки значений.

@Test func referenceNamesTheCreativeLookProperty() {
    #expect(SonyReferenceTables.propertyNames[0xD0FA] == "Creative Look")
}

@Test func referenceNamesValuesOfCameraSettings() {
    #expect(SonyReferenceTables.valueName(0xD241, 0x0B) == "XAVC HS 4K")
    #expect(SonyReferenceTables.valueName(0xD0E1, 0x0303) == "S-Log3")
    #expect(SonyReferenceTables.valueName(0xD0E9, 0x0008) == "S-Gamut3.Cine")
    #expect(SonyReferenceTables.valueName(0xD253, 0x05) == "HEIF")
}

@Test func referenceCoversTheWholeDocument() {
    #expect(SonyReferenceTables.propertyNames.count >= 700)
    #expect(SonyReferenceTables.values.count >= 500)
}

@Test func referenceReturnsNilForWhatItDoesNotList() {
    #expect(SonyReferenceTables.valueName(0xD0FA, 0x7777) == nil)
    #expect(SonyReferenceTables.valueName(0x1234, 0x01) == nil)
    // у диапазона (min/max/step) списка значений нет
    #expect(SonyReferenceTables.propertyNames[0xD0FB] != nil)
    #expect(SonyReferenceTables.values[0xD0FB] == nil)
}

// Страницы справочника разрывают таблицы: номер страницы, колонтитул и повтор заголовка — между строками одной таблицы.
@Test func referenceKeepsRowsOnBothSidesOfAPageBreak() {
    #expect(SonyReferenceTables.valueName(0xD0FA, 0x0008) == "SH(Soft Highkey)")
    #expect(SonyReferenceTables.valueName(0xD0FA, 0x0009) == "BW(Black&White)")
    #expect(SonyReferenceTables.valueName(0xD0FA, 0x0106) == "Custom Look 6")
}

// Значение без описания в своей строке (код посередине высокой ячейки): описание — строки над ним и под ним.
@Test func referenceJoinsDescriptionAroundALoneCode() throws {
    let live = try #require(SonyReferenceTables.valueName(0xD221, 0x00))
    #expect(live.hasPrefix("Live View Supported but Disabled") && live.hasSuffix("LiveView Image."))
    #expect(SonyReferenceTables.valueName(0xD221, 0x01)?.hasPrefix("Live View Supported and Enabled") == true)
    #expect(SonyReferenceTables.valueName(0xD221, 0x02)?.hasPrefix("Live View Not Supported") == true)
}

// UINT64 целиком: код 0xFFFFFFFFFFFFFFFF читается так же, как значение из камеры, — Int64(bitPattern:), то есть −1.
@Test func referenceReadsFullWidthUnsignedKeys() {
    #expect(SonyReferenceTables.valueName(0xD01B, -1) == "nothing to display.")
}

// Таблицы внутри «Note» описывают разряды составного значения, а не само свойство: в список они не попадают.
@Test func referenceIgnoresTablesInsideNotes() {
    // у 0xE068 в таблице значений 0x00000001 — «1920×1080P», а в Note у разряда Resolution 0x01 — «1920×1080»
    #expect(SonyReferenceTables.valueName(0xE068, 0x0000_0001) == "1920×1080P")
    #expect(SonyReferenceTables.valueName(0xE068, 0x0001_0001) == "1920×1080i")
    // у 0xE06C в таблице значений только «Any», а «1920×1080P» с кодом 0x04 — в Note, у разряда Resolution
    #expect(SonyReferenceTables.valueName(0xE06C, 0x04) == nil)
}

// Диапазоны — не значения: границы min/max/step и код с «～0xFFFFFFFF» под ним списка не пополняют.
@Test func referenceIgnoresRanges() {
    // 0xE042 «Focus Position Setting»: 0x0000 min, 0x0001 step, 0xFFFF max
    #expect(SonyReferenceTables.propertyNames[0xE042] == "Focus Position Setting")
    #expect(SonyReferenceTables.valueName(0xE042, 0x0000) == nil)
    #expect(SonyReferenceTables.valueName(0xE042, 0xFFFF) == nil)
    // 0xD063 «Recording Resolution For RAW (Movie)»: 0x00000000 и под ним ～0xFFFFFFFF — любая ширина и высота
    #expect(SonyReferenceTables.valueName(0xD063, 0) == nil)
    // а режимы, которые зовутся «Step» и «Variable», — значения
    #expect(SonyReferenceTables.valueName(0xD019, 0x06) == "Step")
    #expect(SonyReferenceTables.valueName(0xD019, 0x04) == "Variable")
}

@Test func androidCopyMatches() throws {
    // Sources/ZVCore/Sony/SonyReferenceData.swift и android/.../sony_reference.json — один и тот же JSON
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let android = try Data(contentsOf: root.appendingPathComponent("android/app/src/main/resources/sony_reference.json"))
    #expect(Data(SonyReferenceData.json.utf8) == android)
}
