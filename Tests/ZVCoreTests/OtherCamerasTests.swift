import Foundation
import Testing
@testable import ZVCore

// Другие камеры Sony: подписи списков от самой камеры (0x9215), данные объектива (0x9223), Creative Look вместо Creative
// Style и подписи значений из справочника Sony. Файлы display_strings.bin и lens_information.bin пишет
// Fixtures/make_other_cameras.py из scripts/fake_newer.py: те же байты отдаёт поддельная камера в профиле newer.

// MARK: - Сборка наборов для тестов

private func le16(_ v: UInt16) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }
private func le32(_ v: UInt32) -> [UInt8] { withUnsafeBytes(of: v.littleEndian) { Array($0) } }

/// Набор Sony: u32 смещение до данных, u32 размер данных, сами данные. `padding` — лишние байты между заголовком и данными.
private func dataset(_ body: [UInt8], offset: UInt32 = 8, size: UInt32? = nil, padding: Int = 0) -> Data {
    Data(le32(offset) + le32(size ?? UInt32(body.count)) + [UInt8](repeating: 0xEE, count: padding) + body)
}

/// Данные набора 0x9215: списки (тип списка, тип данных PTP, [(значение, строка)]); у строки — размер с нулём и UTF-8 с нулём.
private func stringLists(_ lists: [(type: UInt32, dataType: UInt16, strings: [(Int64, String)])]) -> [UInt8] {
    var body = le16(UInt16(lists.count)) + [0, 0]
    for list in lists {
        body += le32(list.type) + le16(list.dataType) + le16(UInt16(list.strings.count))
        for (value, text) in list.strings {
            let raw = Array(text.utf8) + [0]
            body += [UInt8](PTPValue.encode(value, type: list.dataType)) + le16(UInt16(raw.count)) + raw
        }
    }
    return body
}

/// Данные набора 0x9223: версия ×100, таблицы из пар (положение, расстояние ×100).
private func lensTables(_ tables: [[(UInt32, UInt32)]], version: UInt16 = 100) -> [UInt8] {
    var body = le16(version) + le16(UInt16(tables.count))
    for pairs in tables {
        body += le16(UInt16(pairs.count)) + [0, 0]
        for (position, distance) in pairs { body += le32(position) + le32(distance) }
    }
    return body
}

// MARK: - Подписи от камеры (0x9215)

@Test func displayStringsOfTheNewerCameraAreParsed() throws {
    let strings = try DisplayStrings.parse(Fixture.data("display_strings"))
    #expect(DisplayStrings.creativeLookType == 0x13)
    #expect(Set(strings.lists.keys) == [0x13, 0x15])
    #expect(strings.lists[0x13]![0x0006] == "FL")
    #expect(strings.lists[0x15]!.count == 2)
    // Creative Look: двенадцать готовых и шесть своих
    let looks = try #require(strings.lists[DisplayStrings.creativeLookType])
    #expect(looks.count == 18)
    #expect((1...12).map { looks[Int64($0)] } == ["ST", "PT", "NT", "VV", "VV2", "FL", "IN", "SH", "BW", "SE", "FL2", "FL3"])
    #expect((1...6).map { looks[Int64(0x100 + $0)] } == (1...6).map { "Custom Look \($0)" })
    // UTF-8 не из ASCII: размер строки в байтах, а не в символах
    #expect(strings.lists[0x15] == [1: "Человек", 2: "Животное"])
}

@Test func displayStringsWithoutListsAreEmpty() throws {
    // ListTypeNum = 0: так камера отвечает на тип списка, которого у неё нет
    let strings = try DisplayStrings.parse(dataset(stringLists([])))
    #expect(strings.lists.isEmpty)
    #expect(strings == DisplayStrings())
}

@Test func truncatedDisplayStringsThrow() throws {
    let data = try Fixture.data("display_strings")
    // любой обрезок набора — ошибка: заголовок обещает больше данных, чем пришло
    for length in 0..<data.count {
        #expect(throws: PTPParseError.self, "обрезок в \(length) байт") { try DisplayStrings.parse(Data(data.prefix(length))) }
    }
}

@Test func displayStringsWhoseContentsOutrunTheirSizeThrow() {
    // списков по счёту больше, чем есть
    var body = stringLists([(0x13, PTPType.uint16, [(1, "ST")])])
    body[0] = 2
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(body)) }
    // размер в заголовке меньше данных: набор обрывается посреди строки
    let whole = stringLists([(0x13, PTPType.uint16, [(1, "ST"), (2, "PT")])])
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(whole, size: UInt32(whole.count - 3))) }
    // размер в заголовке больше, чем пришло, хотя разобранное целиком помещается, — набор всё равно обрезан
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(whole, size: UInt32(whole.count + 1))) }
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(whole, size: UInt32.max)) }
    // строка длиннее оставшегося: размер "ST\0" вместо 3 байт говорит 9
    var cut = stringLists([(0x13, PTPType.uint16, [(1, "ST")])])
    cut[cut.count - 5] = 9
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(cut)) }
}

@Test func displayStringsDatasetHeaderIsRespected() throws {
    let body = stringLists([(0x13, PTPType.uint16, [(1, "ST")])])
    // данные идут со смещения, а не сразу за заголовком
    #expect(try DisplayStrings.parse(dataset(body, offset: 12, padding: 4)).lists == [0x13: [1: "ST"]])
    // всё, что за объявленным размером, набору не принадлежит
    #expect(try DisplayStrings.parse(dataset(body) + Data([1, 2, 3])).lists == [0x13: [1: "ST"]])
    // смещение внутрь самого заголовка — не набор; при смещении 0 «данные» были бы началом заголовка и читались бы как
    // пустой набор (число списков 0), поэтому проверяется и оно
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(body, offset: 4)) }
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(body, offset: 0)) }
    #expect(throws: PTPParseError.self) { try DisplayStrings.parse(dataset(body, offset: 7)) }
}

@Test func displayStringValuesFollowTheirDataType() throws {
    let strings = try DisplayStrings.parse(dataset(stringLists([
        (1, PTPType.int8, [(-1, "a")]),
        (2, PTPType.uint8, [(255, "b")]),
        (3, PTPType.int16, [(-2, "c")]),
        (4, PTPType.uint32, [(0xFFFF_FFFF, "d")]),
        (5, PTPType.uint64, [(-1, "e")]),            // на всю ширину: Int64(bitPattern:), как в SonyReferenceTables
    ])))
    #expect(strings.lists == [1: [-1: "a"], 2: [255: "b"], 3: [-2: "c"], 4: [0xFFFF_FFFF: "d"], 5: [-1: "e"]])
}

@Test func displayStringListWithoutStringsStaysAsEmptyList() throws {
    // список без строк — не отсутствие списка: слияние должно уметь стереть им прежние подписи
    let strings = try DisplayStrings.parse(dataset(stringLists([(0x13, PTPType.uint16, [])])))
    #expect(strings.lists == [0x13: [:]])
}

@Test func displayStringsDropOnlyTheTerminator() throws {
    // размер строки — вместе с нулём; пустая строка — один нуль или вообще ничего; нуля может и не быть
    var body = le16(1) + [0, 0]
    body += le32(0x13) + le16(PTPType.uint16) + le16(4)
    body += le16(1) + le16(1) + [0]                     // "" — только нуль
    body += le16(2) + le16(0)                           // "" — размер 0, байтов нет
    body += le16(3) + le16(2) + [0x41, 0]               // "A"
    body += le16(4) + le16(2) + [0x42, 0x43]            // "BC" — нуля нет
    #expect(try DisplayStrings.parse(dataset(body)).lists == [0x13: [1: "", 2: "", 3: "A", 4: "BC"]])
}

@Test func displayStringListOfAStringTypeCannotBeKeyed() {
    // значение-строка (0xFFFF) не годится в ключ [Int64: String]
    var text = le16(1) + [0, 0]
    text += le32(0x13) + le16(PTPType.string) + le16(1)
    text += [0] + le16(1) + [0]                         // значение "" и строка списка
    #expect(throws: PTPParseError.unsupportedType(PTPType.string)) { try DisplayStrings.parse(dataset(text)) }
    // тип данных, которого в PTP нет
    var unknown = le16(1) + [0, 0]
    unknown += le32(0x13) + le16(0) + le16(1)
    unknown += [0, 0, 0]
    #expect(throws: PTPParseError.unsupportedType(0x0000)) { try DisplayStrings.parse(dataset(unknown)) }
}

@Test func mergeReplacesListsByType() {
    var strings = DisplayStrings(lists: [0x13: [1: "ST", 2: "PT"], 0x15: [1: "Человек"]])
    strings.merge(DisplayStrings(lists: [0x13: [3: "NT"], 0x17: [5: "x"]]))
    // список того же типа заменяется целиком, а не дополняется; остальные на месте
    #expect(strings.lists == [0x13: [3: "NT"], 0x15: [1: "Человек"], 0x17: [5: "x"]])
    // пустой список от камеры тоже заменяет: подписей больше нет
    strings.merge(DisplayStrings(lists: [0x15: [:]]))
    #expect(strings.lists == [0x13: [3: "NT"], 0x15: [:], 0x17: [5: "x"]])
    strings.merge(DisplayStrings())
    #expect(strings.lists.count == 3)
}

@Test func refreshedListReplacesOnlyItsOwnTypeAndDropsWhatTheCameraNoLongerGives() {
    var strings = DisplayStrings(lists: [0x13: [1: "ST", 2: "PT"], 0x15: [1: "Человек"]])
    // пришёл заново список 0x13: он заменяется целиком, 0x15 на месте
    strings.replace(type: 0x13, with: DisplayStrings(lists: [0x13: [1: "ST", 3: "NT"]]))
    #expect(strings.lists == [0x13: [1: "ST", 3: "NT"], 0x15: [1: "Человек"]])
    // камера на тип 0x15 ответила пустым набором (списка у неё больше нет): старые названия стираются
    strings.replace(type: 0x15, with: DisplayStrings())
    #expect(strings.lists == [0x13: [1: "ST", 3: "NT"]])
    // тип 0 — все списки: набор заменяется целиком
    strings.replace(type: 0, with: DisplayStrings(lists: [0x17: [5: "x"]]))
    #expect(strings.lists == [0x17: [5: "x"]])
    strings.replace(type: 0, with: DisplayStrings())
    #expect(strings == DisplayStrings())
}

// MARK: - Данные объектива (0x9223)

@Test func lensInformationOfTheNewerCameraIsParsed() throws {
    let lens = try LensInformation.parse(Fixture.data("lens_information"))
    #expect(lens.pairs.count == 12)
    #expect(lens.absoluteScale)                          // наибольшее положение 65535 > 100
    #expect(lens.tableCount == 1)
    // двенадцать точек шкалы 0…65535 через 5958, последняя — край
    let positions: [Double] = [0, 5958, 11916, 17874, 23832, 29790, 35748, 41706, 47664, 53622, 59580, 65535]
    #expect(lens.pairs.map { $0.position } == positions)
    // расстояние в файле — ×100; последнее, 1 000 000 м, — «бесконечность»
    let meters: [Double] = [0.25, 0.30, 0.40, 0.50, 0.70, 1, 1.5, 2, 3, 5, 10, 1_000_000]
    #expect(lens.pairs.map { $0.meters } == meters)
}

@Test func lensDistanceAtATablePointIsExact() throws {
    let lens = try LensInformation.parse(Fixture.data("lens_information"))
    #expect(lens.meters(atPosition: 5958) == 0.30)
    #expect(lens.meters(atPosition: 0) == 0.25)
    #expect(lens.meters(atPosition: 65535) == 1_000_000)
    for pair in lens.pairs { #expect(lens.meters(atPosition: pair.position) == pair.meters) }
    // и там, где a + (b − a) округлением не равно b: 545,21 м → 16 085,26 м — в точке таблицы ровно её значение
    let odd = try LensInformation.parse(dataset(lensTables([[(0, 54521), (50, 1_608_526), (100, 2_000_000)]])))
    #expect(odd.meters(atPosition: 50) == 16085.26)
    #expect(odd.meters(atPosition: 0) == 545.21)
}

@Test func lensDistanceBetweenPointsIsInterpolatedLinearly() throws {
    let lens = try LensInformation.parse(Fixture.data("lens_information"))
    // между 0 (0,25 м) и 5958 (0,30 м)
    let middle = try #require(lens.meters(atPosition: 2979))
    #expect(abs(middle - 0.275) < 1e-9)
    let quarter = try #require(lens.meters(atPosition: 1489.5))
    #expect(abs(quarter - 0.2625) < 1e-9)
    // между 11916 (0,40 м) и 17874 (0,50 м) — не по первому отрезку
    let third = try #require(lens.meters(atPosition: 11916 + 5958.0 / 3))
    #expect(abs(third - (0.4 + 0.1 / 3)) < 1e-9)
    // середина последнего отрезка, 10 м … 1 000 000 м, — полмиллиона метров, на шкале это «∞»
    let last = try #require(lens.meters(atPosition: (59580 + 65535) / 2.0))
    #expect(abs(last - 500_005) < 1e-6)
    #expect(LensInformation.label(last) == "∞")
    // растёт вместе с положением
    var previous = 0.0
    for step in 0...655 {
        let meters = try #require(lens.meters(atPosition: Double(step) * 100))
        #expect(meters >= previous)
        previous = meters
    }
}

@Test func lensDistanceOutsideTheTableIsTheNearestEnd() throws {
    let lens = try LensInformation.parse(Fixture.data("lens_information"))
    #expect(lens.meters(atPosition: -10) == 0.25)
    #expect(lens.meters(atPosition: 70000) == 1_000_000)
    #expect(lens.meters(atPosition: .nan) == nil)
}

@Test func lensInformationWithSeveralTablesHasNoDistances() throws {
    // у зумов таблица может быть на каждое фокусное расстояние; какую брать — справочник не говорит
    let two = try LensInformation.parse(dataset(lensTables([
        [(0, 25), (65535, 100_000_000)],
        [(0, 30), (65535, 50_000)],
    ])))
    #expect(two.tableCount == 2)
    #expect(two.meters(atPosition: 0) == nil)
    #expect(two.meters(atPosition: 30000) == nil)
    // но таблицы целы — для панели отладки
    #expect(two.tables.count == 2)
    #expect(two.tables[1].map { $0.meters } == [0.30, 500])
    #expect(two.pairs.map { $0.meters } == [0.25, 1_000_000])        // первая таблица
    #expect(two.absoluteScale)
}

@Test func lensInformationWithoutPairsHasNoDistances() throws {
    let none = try LensInformation.parse(dataset(lensTables([])))
    #expect(none.tableCount == 0 && none.pairs.isEmpty && !none.absoluteScale)
    #expect(none.meters(atPosition: 10) == nil)
    let empty = try LensInformation.parse(dataset(lensTables([[]])))
    #expect(empty.tableCount == 1 && empty.pairs.isEmpty)
    #expect(empty.meters(atPosition: 10) == nil)
}

@Test func lensScaleIsAbsoluteOnlyAboveOneHundred() throws {
    // 0…100, как у 0xD24C, — не абсолютная; 0…65535, как у 0xE043, — абсолютная
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25), (100, 500)]]))).absoluteScale == false)
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25), (101, 500)]]))).absoluteScale == true)
    // таблицы могут быть в разном порядке пар
    #expect(try LensInformation.parse(dataset(lensTables([[(100, 500), (0, 25)]]))).absoluteScale == false)
}

@Test func lensPairsAreSortedByPosition() throws {
    let lens = try LensInformation.parse(dataset(lensTables([[(65535, 100_000_000), (0, 25), (5958, 30)]])))
    #expect(lens.pairs.map { $0.position } == [0, 5958, 65535])
    #expect(lens.pairs.map { $0.meters } == [0.25, 0.30, 1_000_000])
    let middle = try #require(lens.meters(atPosition: 2979))
    #expect(abs(middle - 0.275) < 1e-9)
}

@Test func lensTablesWithOddPairsDoNotTrapTheInterpolation() throws {
    // одна пара — везде её расстояние
    let single = try LensInformation.parse(dataset(lensTables([[(50, 100)]])))
    #expect(single.meters(atPosition: 0) == 1 && single.meters(atPosition: 50) == 1 && single.meters(atPosition: 99) == 1)
    // две пары с одним положением: не деление на ноль
    let twin = try LensInformation.parse(dataset(lensTables([[(0, 25), (0, 40), (100, 500)]])))
    #expect(twin.meters(atPosition: 0) != nil)
    let inside = try #require(twin.meters(atPosition: 50))
    #expect(inside.isFinite && inside > 0.25 && inside < 5)
}

@Test func truncatedLensInformationThrows() throws {
    let data = try Fixture.data("lens_information")
    for length in 0..<data.count {
        #expect(throws: PTPParseError.self, "обрезок в \(length) байт") { try LensInformation.parse(Data(data.prefix(length))) }
    }
    // пар по счёту больше, чем есть
    var body = lensTables([[(0, 25)]])
    body[4] = 3
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(body)) }
    // таблиц по счёту больше, чем есть
    var tables = lensTables([[(0, 25)]])
    tables[2] = 2
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(tables)) }
    // размер в заголовке больше, чем пришло, хотя таблицы целы, — набор обрезан; меньше — обрывается посреди пары
    let whole = lensTables([[(0, 25), (65535, 100_000_000)]])
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(whole, size: UInt32(whole.count + 1))) }
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(whole, size: UInt32.max)) }
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(whole, size: UInt32(whole.count - 1))) }
    // смещение внутрь заголовка; при смещении 0 набор читался бы как пустой (версия 0, таблиц 0)
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(lensTables([]), offset: 6)) }
    #expect(throws: PTPParseError.self) { try LensInformation.parse(dataset(lensTables([]), offset: 0)) }
}

@Test func lensInformationHeaderAndVersionDoNotChangeTheParse() throws {
    let body = lensTables([[(0, 25), (65535, 100_000_000)]], version: 310)
    let plain = try LensInformation.parse(dataset(body))
    #expect(plain.pairs.map { $0.meters } == [0.25, 1_000_000])
    #expect(try LensInformation.parse(dataset(body, offset: 16, padding: 8)) == plain)
    #expect(try LensInformation.parse(dataset(body) + Data([9, 9])) == plain)
    // а разные данные — разные значения
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25), (65535, 99_999_999)]]))) != plain)
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25), (65534, 100_000_000)]]))) != plain)
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25)]]))) != plain)
    #expect(try LensInformation.parse(dataset(lensTables([[(0, 25), (65535, 100_000_000)], []]))) != plain)
}

@Test func lensDistanceLabels() {
    #expect(LensInformation.label(0.35) == "0,35 м")
    #expect(LensInformation.label(0.5) == "0,5 м")
    #expect(LensInformation.label(1.0) == "1 м")
    #expect(LensInformation.label(1.2) == "1,2 м")
    #expect(LensInformation.label(12) == "12 м")
    #expect(LensInformation.label(1_000_000) == "∞")
    // сначала округление до показанной точности, потом выбор формата: «1,00 м», «10,0 м» и «100 м» не бывает
    #expect(LensInformation.label(0.996) == "1 м")
    #expect(LensInformation.label(9.99) == "10 м")
    #expect(LensInformation.label(99.4) == "99 м")
    #expect(LensInformation.label(99.5) == "∞")
    #expect(LensInformation.label(100) == "∞")
    // до 1 м — до двух цифр после запятой, до 10 м — до одной, дальше — целые; хвостовых нулей и запятой в конце нет
    #expect(LensInformation.label(0.25) == "0,25 м")
    #expect(LensInformation.label(0.3) == "0,3 м")
    #expect(LensInformation.label(0.2) == "0,2 м")
    #expect(LensInformation.label(0.05) == "0,05 м")
    #expect(LensInformation.label(0.004) == "0 м")
    #expect(LensInformation.label(0.006) == "0,01 м")
    #expect(LensInformation.label(2) == "2 м")
    #expect(LensInformation.label(2.5) == "2,5 м")
    #expect(LensInformation.label(9.9) == "9,9 м")
    #expect(LensInformation.label(9.94) == "9,9 м")
    #expect(LensInformation.label(9.96) == "10 м")
    #expect(LensInformation.label(10) == "10 м")
    #expect(LensInformation.label(10.4) == "10 м")
    #expect(LensInformation.label(10.6) == "11 м")
    #expect(LensInformation.label(50) == "50 м")
    #expect(LensInformation.label(99) == "99 м")
    // запятая — всегда, чей бы ни был язык системы
    #expect(!LensInformation.label(2.5).contains("."))
}

@Test func lensDistanceLabelOfAnythingThatIsNotADistance() {
    // ноль и всё, что меньше нуля или не число, — «0 м»; бесконечность — «∞»; ни «inf», ни «nan», ни «−» на экран не попадает
    #expect(LensInformation.label(0) == "0 м")
    #expect(LensInformation.label(-0.0) == "0 м")
    #expect(LensInformation.label(-1) == "0 м")
    #expect(LensInformation.label(-0.001) == "0 м")
    #expect(LensInformation.label(.nan) == "0 м")
    #expect(LensInformation.label(-.infinity) == "0 м")
    #expect(LensInformation.label(-.greatestFiniteMagnitude) == "0 м")
    #expect(LensInformation.label(.infinity) == "∞")
    #expect(LensInformation.label(.greatestFiniteMagnitude) == "∞")
}

@Test func lensDistanceLabelIsTheValueRoundedToWhatItShows() {
    // каждая тысячная метра от 0 до 120 м (с границами 0,995, 9,95 и 99,5): подпись — число, округлённое до показанной
    // точности (0,01 до 1 м, 0,1 до 10 м, 1 дальше), без хвостовых нулей, с запятой, не убывающее; «∞» — только от 99,5 м
    var problems: [String] = []
    var previous = 0.0
    var infinity = false
    for thousandths in 0...120_000 {
        let meters = Double(thousandths) / 1000
        let label = LensInformation.label(meters)
        if label == "∞" {
            if meters < 99.5 { problems.append("\(meters): ∞") }
            infinity = true
            continue
        }
        if infinity { problems.append("\(meters): число после ∞") }
        guard label.hasSuffix(" м") else { problems.append("\(meters): \(label) без « м»"); continue }
        let text = String(label.dropLast(2))
        guard !text.contains("."), let shown = Double(text.replacingOccurrences(of: ",", with: ".")) else {
            problems.append("\(meters): \(label) не число с запятой")
            continue
        }
        let decimals = text.split(separator: ",", omittingEmptySubsequences: false).dropFirst().first?.count ?? 0
        let precision = shown < 1 ? 0.005 : shown < 10 ? 0.05 : 0.5
        if abs(shown - meters) > precision + 1e-9 { problems.append("\(meters): \(label) дальше половины шага") }
        if decimals > (shown < 1 ? 2 : shown < 10 ? 1 : 0) { problems.append("\(meters): \(label) лишний знак") }
        if decimals > 0, text.hasSuffix("0") { problems.append("\(meters): \(label) хвостовой нуль") }
        if shown < previous { problems.append("\(meters): \(label) меньше предыдущего") }
        previous = shown
    }
    #expect(problems.isEmpty, "\(problems.count) отклонений, первые: \(problems.prefix(5))")
}

// MARK: - Creative Look вместо Creative Style

@Test func creativeLookIsItsOwnSetting() {
    #expect(SonyProp.creativeLook == 0xD0FA)
    #expect(SettingID.creativeLook.code == 0xD0FA)
    #expect(SettingID.creativeLook.title == "Creative Look")
    #expect(SettingID.creativeLook.shortTitle == "Look")
    #expect(SettingID(rawValue: "creativeLook") == .creativeLook)
    // у Creative Style свой код: значения совпадают числами, но значат разное
    #expect(SettingID.creativeStyle.code == 0xD240)
}

@Test func creativeLookTakesTheStyleSlotOnlyWhereTheCameraHasNoStyle() {
    #expect(SettingCatalog.resolved(.creativeStyle, available: [0xD0FA]) == .creativeLook)
    #expect(SettingCatalog.resolved(.creativeStyle, available: [0xD240]) == .creativeStyle)
    #expect(SettingCatalog.resolved(.creativeStyle, available: [0xD240, 0xD0FA]) == .creativeStyle)
    #expect(SettingCatalog.resolved(.creativeStyle, available: []) == .creativeStyle)
    #expect(SettingCatalog.resolved(.creativeStyle, available: [0xD0E1, 0xE042]) == .creativeStyle)
    // подменяется только Creative Style
    for id in SettingID.allCases where id != .creativeStyle {
        #expect(SettingCatalog.resolved(id, available: [0xD0FA]) == id, "\(id)")
        #expect(SettingCatalog.resolved(id, available: []) == id, "\(id)")
    }
}

@Test func creativeLookIsInNoGroupAndNoFnSlot() {
    // «Все настройки» показывают строку группы и без свойства у камеры: Creative Look встаёт на место Creative Style
    // через SettingCatalog.resolved, а у ZV-E10 строки с прочерком не появляется
    #expect(SettingGroup.allCases.allSatisfy { !$0.settings.contains(.creativeLook) })
    #expect(!SettingCatalog.fnTopRow.contains(.setting(.creativeLook)))
    #expect(!SettingCatalog.fnBottomRow.contains(.setting(.creativeLook)))
    #expect(!SettingCatalog.fnBottomRow(for: .photo).contains(.setting(.creativeLook)))
    #expect(!SettingCatalog.infoLine.contains(.creativeLook))
    #expect(!SettingCatalog.infoLine(for: .video).contains(.creativeLook))
    #expect(!SettingCatalog.infoLine(for: .photo).contains(.creativeLook))
}

@Test func presetsCaptureCreativeLookOnlyFromACameraThatHasIt() throws {
    // в пресете Creative Look стоит сразу за Creative Style
    let order = PresetCatalog.order
    #expect(order.firstIndex(of: .creativeLook) == order.firstIndex(of: .creativeStyle).map { $0 + 1 })
    let look = SonyProperty(code: SonyProp.creativeLook, dataType: PTPType.uint16, getSet: 1, enabled: 1, current: .int(6),
                            range: nil, settableValues: [1, 6], allValues: [1, 6])
    #expect(PresetCatalog.capture(name: "Look", from: [SonyProp.creativeLook: look]).values == [.creativeLook: 6])
    // у ZV-E10 свойства нет: пресет такой же, как был, — со Creative Style и без Creative Look
    let zv = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    let captured = PresetCatalog.capture(name: "ZV", from: zv)
    #expect(captured.values[.creativeLook] == nil)
    #expect(captured.values.keys.allSatisfy { $0 != .creativeLook })
    // пресет со Creative Style на камере без него ничего не пишет в Creative Look
    #expect(PresetCatalog.changes(Preset(name: "S", values: [.creativeStyle: 2]), [SonyProp.creativeLook: look]).isEmpty)
}

// MARK: - Подписи значений

@Test func valueLabelPrefersTheCameraNames() {
    #expect(ValueFormatter.label(.creativeLook, 6, names: [6: "FL"]) == "FL")
    // в списке камеры этого значения нет — таблицы Sony
    #expect(ValueFormatter.label(.creativeLook, 6, names: [1: "ST"]) == "FL(Film)")
    #expect(ValueFormatter.label(.creativeLook, 6, names: [:]) == "FL(Film)")
    #expect(ValueFormatter.label(.creativeLook, 6, names: nil) == "FL(Film)")
    // название от камеры важнее и русской таблицы
    #expect(ValueFormatter.label(.focusMode, 2, names: [2: "Camera AF-S"]) == "Camera AF-S")
    // подпись по списку камеры берётся и из полного списка 0x9215
    #expect(ValueFormatter.label(.creativeLook, 0x0102, names: [0x0102: "Custom Look 2"]) == "Custom Look 2")
}

@Test func valueLabelFallsBackToSonyReferenceTables() {
    #expect(ValueFormatter.label(.creativeLook, 6) == "FL(Film)")
    #expect(ValueFormatter.label(.movieFileFormat, 0x0B) == "XAVC HS 4K")
    // зона фокуса: этой точки нет ни в русских подписях, ни в ветке «слежение» — английское имя Sony, а не «#…»
    let area = ValueFormatter.label(.focusArea, 0x0106)
    #expect(area == SonyReferenceTables.valueName(0xD22C, 0x0106))
    #expect(!area.hasPrefix("#"))
    // остальные ветки, которые раньше кончались на «#N»
    #expect(ValueFormatter.label(.pictureProfile, 11) == SonyReferenceTables.valueName(0xD23F, 11))
    #expect(!ValueFormatter.label(.pictureProfile, 11).hasPrefix("#"))
    #expect(ValueFormatter.label(.focusMode, 0x8007) == SonyReferenceTables.valueName(0x500A, 0x8007))
    #expect(!ValueFormatter.label(.focusMode, 0x8007).hasPrefix("#"))
    #expect(ValueFormatter.label(.exposureProgram, 0x8008) == SonyReferenceTables.valueName(0x500E, 0x8008))
    #expect(!ValueFormatter.label(.exposureProgram, 0x8008).hasPrefix("#"))
    #expect(ValueFormatter.label(.creativeStyle, 7) == SonyReferenceTables.valueName(0xD240, 7))
    #expect(!ValueFormatter.label(.creativeStyle, 7).hasPrefix("#"))
}

@Test func valueLabelEndsInTheNumberOnlyWhenNothingKnowsTheValue() {
    #expect(ValueFormatter.label(.creativeLook, 12345) == "#12345")
    #expect(ValueFormatter.label(.focusArea, 12345) == "#12345")
    #expect(ValueFormatter.label(.dro, 0x30) == "#48")
    #expect(ValueFormatter.label(.pictureProfile, 12345) == "#12345")
    #expect(ValueFormatter.label(.movieFileFormat, 99) == "#99")
    // выдержка со знаменателем 0 — ветка выдержки тоже кончается на «#N»
    #expect(ValueFormatter.label(.shutter, 0x0001_0000) == "#65536")
    // и с названиями камеры, которых для этого значения нет
    #expect(ValueFormatter.label(.creativeLook, 12345, names: [6: "FL"]) == "#12345")
}

@Test func russianLabelsStayAheadOfTheReference() {
    #expect(ValueFormatter.label(.focusArea, 0x103) == "Точка L")
    #expect(ValueFormatter.label(.focusArea, 0x206) == "Слежение: точка L")
    #expect(ValueFormatter.label(.focusArea, 1) == "Широкая")
    #expect(ValueFormatter.label(.movieFileFormat, 8) == "XAVC S 4K")
    #expect(ValueFormatter.label(.whiteBalance, 0x8020) == "Свой 1")
    #expect(ValueFormatter.label(.creativeStyle, 2) == "Яркий")
    #expect(ValueFormatter.label(.dro, 1) == "Выкл")
    #expect(ValueFormatter.label(.dro, 0x1F) == "DRO авто")
    #expect(ValueFormatter.label(.pictureProfile, 0) == "Выкл")
    #expect(ValueFormatter.label(.pictureProfile, 10) == "PP10")
    #expect(ValueFormatter.label(.metering, 0x8001) == "Мульти")
    #expect(ValueFormatter.label(.exposureProgram, 0x0007_8053) == "Видео M")
    #expect(ValueFormatter.label(.fileFormat, 3) == "JPEG")
    #expect(ValueFormatter.label(.aspectRatio, 4) == "1:1")
    // и с names, которых для этого значения нет
    #expect(ValueFormatter.label(.focusArea, 0x103, names: [1: "x"]) == "Точка L")
}

@Test func noValueOfTheZveTenDumpIsShownAsANumber() throws {
    // допустимые, все и текущие значения настроек из дампа ZV-E10: у каждого есть подпись, «#N» нет ни у одного
    let properties = try SonyPropertyParser.parse(Fixture.data("props_settled"))
    var checked = 0
    for id in SettingID.allCases {
        guard let property = properties[id.code] else { continue }
        var values = Set(property.settableValues + property.allValues)
        if let current = property.current.intValue { values.insert(current) }
        for value in values {
            #expect(!ValueFormatter.label(id, value).hasPrefix("#"), "\(id) \(value)")
            checked += 1
        }
    }
    #expect(checked > 150)
}

// MARK: - Названия от камеры в настройках

private func creativeLook(current: Int64, values: [Int64] = [1, 6, 0x101]) -> SonyProperty {
    SonyProperty(code: SonyProp.creativeLook, dataType: PTPType.uint16, getSet: 1, enabled: 1, current: .int(current),
                 range: nil, settableValues: values, allValues: values)
}

@Test func onlyCreativeLookHasNamesFromTheCamera() throws {
    let strings = try DisplayStrings.parse(Fixture.data("display_strings"))
    #expect(strings.names(for: .creativeLook)?[6] == "FL")
    #expect(strings.names(for: .creativeLook)?.count == 18)
    // остальные списки камеры (тип 0x15 и прочие) к настройкам программы не относятся
    for id in SettingID.allCases where id != .creativeLook { #expect(strings.names(for: id) == nil, "\(id)") }
    // списка Creative Look нет — названия берутся из таблиц
    #expect(DisplayStrings().names(for: .creativeLook) == nil)
    #expect(DisplayStrings(lists: [0x15: [1: "x"]]).names(for: .creativeLook) == nil)
}

@Test func propertyLabelUsesTheCameraNamesToo() {
    let look = creativeLook(current: 6)
    #expect(ValueFormatter.label(.creativeLook, look, names: [6: "FL"]) == "FL")
    #expect(ValueFormatter.label(.creativeLook, look) == "FL(Film)")
    #expect(ValueFormatter.label(.creativeLook, look, names: [1: "ST"]) == "FL(Film)")
    #expect(ValueFormatter.label(.creativeLook, nil, names: [6: "FL"]) == "—")
}

@Test func valueStripOptionsUseTheCameraNames() {
    let look = creativeLook(current: 1)
    let named = SettingOptions.options(.creativeLook, look, names: [1: "ST", 6: "FL", 0x101: "Custom Look 1"])
    #expect(named.map(\.label) == ["ST", "FL", "Custom Look 1"])
    #expect(named.map(\.value) == [1, 6, 0x101])
    // без списка камеры — таблицы Sony, как было; значения те же
    let plain = SettingOptions.options(.creativeLook, look)
    #expect(plain.map(\.value) == [1, 6, 0x101])
    #expect(plain.map(\.label) == [1, 6, 0x101].map { ValueFormatter.label(.creativeLook, $0) })
    #expect(plain[1].label == "FL(Film)")
    // в списке камеры нет одного значения — для него таблица, а не номер
    let partial = SettingOptions.options(.creativeLook, look, names: [1: "ST"])
    #expect(partial.map(\.label) == ["ST", "FL(Film)", plain[2].label])
}

@Test func presetWindowOptionsAndChangesUseTheCameraNames() {
    let look = creativeLook(current: 6)
    let preset = Preset(name: "Look", values: [.creativeLook: 1])
    let names: [Int64: String] = [1: "ST", 6: "FL", 0x101: "Custom Look 1"]
    // выбор значения в окне пресетов
    #expect(PresetCatalog.options(.creativeLook, look, preset, names: names).map(\.label) == ["ST", "FL", "Custom Look 1"])
    #expect(PresetCatalog.options(.creativeLook, look, preset).map(\.label)[1] == "FL(Film)")
    // значения пресета нет среди разрешённых — его подпись тоже от камеры
    let outside = Preset(name: "Look", values: [.creativeLook: 7])
    #expect(PresetCatalog.options(.creativeLook, look, outside, names: [7: "IN"] ).first?.label == "IN")
    // «На камере изменено»
    let strings = DisplayStrings(lists: [DisplayStrings.creativeLookType: names])
    #expect(PresetCatalog.changes(preset, [SonyProp.creativeLook: look], strings: strings).map(\.label) == ["Look ST → FL"])
    let plain = PresetCatalog.changes(preset, [SonyProp.creativeLook: look]).map(\.label)
    #expect(plain == ["Look \(ValueFormatter.label(.creativeLook, 1)) → FL(Film)"])
    // названия камеры не задевают подписи других настроек
    let iso = SonyProperty(code: SonyProp.iso, dataType: PTPType.uint32, getSet: 1, enabled: 1, current: .int(400),
                           range: nil, settableValues: [100, 400], allValues: [100, 400])
    let isoPreset = Preset(name: "ISO", values: [.iso: 100])
    #expect(PresetCatalog.changes(isoPreset, [SonyProp.iso: iso], strings: strings).map(\.label) == ["ISO 100 → 400"])
}

@Test func groupFailureNamesCreativeLookLikeTheMainCamera() {
    // значение выбрано по списку главной камеры — в «Не удалось на «B»: …» оно называется так же
    #expect(GroupFailures.label(.creativeLook, 6, names: [6: "FL"]) == "Look FL")
    #expect(GroupFailures.label(.creativeLook, 6) == "Look FL(Film)")
    // другие подписи — как были
    #expect(GroupFailures.label(.iso, 51200, names: [51200: "x"]) == "ISO 51200")
    #expect(GroupFailures.label(.focusMode, 0x8004) == "Фокус AF-C")
}

// MARK: - Имена свойств

@Test func propertyNamesFallBackToTheReference() {
    // что libgphoto2 знает, имя остаётся прежним
    #expect(PropertyNames.name(0xD240) == "CreativeStyle")
    #expect(PropertyNames.name(0xD201) == "DRangeOptimize")
    // коды только новых камер — из справочника Sony
    #expect(PropertyNames.name(0xD0FA) == "Creative Look")
    #expect(PropertyNames.name(0xE042) == "Focus Position Setting")
    #expect(PropertyNames.name(0xE043) == SonyReferenceTables.propertyNames[0xE043])
    #expect(PropertyNames.name(0xE086) == SonyReferenceTables.propertyNames[0xE086])
    #expect(PropertyNames.name(0xE086) != nil)
    // нет нигде
    #expect(PropertyNames.name(0x1234) == nil)
}

// MARK: - Операции и коды

@Test func zveTenHasNoneOfTheNewOperations() throws {
    let info = try DeviceInfo(data: Fixture.data("deviceinfo"))
    #expect(info.supports(0x9215) == false)
    #expect(info.supports(0x9209) == true)
    #expect(info.supports(SonyOp.getDisplayStringList) == false)
    #expect(info.supports(SonyOp.getVendorCodeVersion) == false)
    #expect(info.supports(SonyOp.getLensInformation) == false)
    #expect(info.supports(SonyOp.getAllExtPropInfo) && info.supports(SonyOp.connect))
    // список операций остался массивом, как был
    #expect(info.operations.count == 32)
    #expect(info.isPCRemote)
}

@Test func newerCameraListsTheNewOperations() throws {
    let info = try DeviceInfo(data: FakeCamera.deviceInfo(operations: [0x9201, 0x9209, 0x9215, 0x9216, 0x9223]))
    #expect(info.supports(0x9215) && info.supports(0x9216) && info.supports(0x9223))
    #expect(!info.supports(0x9202))
}

@Test func newCodesAreTheSonyOnes() {
    #expect(SonyOp.getDisplayStringList == 0x9215)
    #expect(SonyOp.getVendorCodeVersion == 0x9216)
    #expect(SonyOp.getLensInformation == 0x9223)
    #expect(SonyProp.creativeLook == 0xD0FA)
    #expect(SonyProp.ppGamma == 0xD0E1)
    #expect(SonyProp.ppColorMode == 0xD0E9)
    #expect(SonyProp.focusPositionSetting == 0xE042)
    #expect(SonyProp.focusPositionCurrent == 0xE043)
    #expect(SonyProp.lensInfoEnabled == 0xE086)
    #expect(SonyEvent.displayListChanged == 0xC20F)
    #expect(SonyEvent.focusPositionResult == 0xC218)
    #expect(SonyEvent.lensInfoChanged == 0xC21B)
}

// MARK: - Сеанс: подписи, версия кода и данные объектива

/// Новая камера для тестов сеанса: в GetDeviceInfo — три новые операции, на них она отвечает теми же байтами, что в фикстурах
/// (их отдаёт и поддельная камера в профиле newer); версия кода 3.10. `lensEnabled` — значение 0xE086; nil — свойства нет.
private func newerCamera(lensEnabled: Int64? = 1, operations: [UInt16] = [0x9201, 0x9209, 0x9215, 0x9216, 0x9223]) throws -> FakeCamera {
    let props: [FakeCamera.Prop] = lensEnabled.map {
        [.init(code: SonyProp.lensInfoEnabled, type: PTPType.uint8, getSet: 0, current: $0, values: [0, 1])]
    } ?? []
    let cam = FakeCamera(props: props, deviceInfo: FakeCamera.deviceInfo(operations: operations))
    let all = try Fixture.data("display_strings")
    let looks = dataset(stringLists([(0x13, PTPType.uint16, [(1, "ST"), (6, "FL")])]))
    let lens = try Fixture.data("lens_information")
    cam.answer(SonyOp.getDisplayStringList) { params in
        // версия списков 1.00 — в параметре ответа; другой тип списка — пустой набор (ListTypeNum = 0)
        PTPReply(code: PTPResponseCode.ok, params: [100], data: params == [0] ? all : params == [0x13] ? looks : dataset(stringLists([])))
    }
    cam.answer(SonyOp.getVendorCodeVersion) { _ in PTPReply(code: PTPResponseCode.ok, params: [0x0136]) }
    // только метры (параметр 2), как просит программа; футы камера этого стенда не отдаёт
    cam.answer(SonyOp.getLensInformation) { params in
        params == [2] ? PTPReply(code: PTPResponseCode.ok, data: lens) : PTPReply(code: 0x2006)
    }
    return cam
}

private let newOperations: [UInt16] = [SonyOp.getDisplayStringList, SonyOp.getVendorCodeVersion, SonyOp.getLensInformation]

@Test func sessionAsksForDisplayStringsOfTheTypeItIsGiven() async throws {
    let cam = try newerCamera()
    let session = try await readySession(cam)
    let all = try await session.displayStrings(type: 0)
    #expect(all.lists[0x13]?[0x0006] == "FL" && all.lists[0x15]?.count == 2)
    let looks = try await session.displayStrings(type: DisplayStrings.creativeLookType)
    #expect(looks.lists == [0x13: [1: "ST", 6: "FL"]])
    // тип списка — параметр запроса, 0 — «все»
    #expect(cam.sent.filter { $0.op == SonyOp.getDisplayStringList }.map(\.params) == [[0], [0x13]])
    // тип, которого у камеры нет, — пустой набор, а не ошибка
    #expect(try await session.displayStrings(type: 0x99) == DisplayStrings())
}

@Test func cameraThatDoesNotListTheNewOperationsIsNeverAskedForThem() async throws {
    // ZV-E10: в списке операций нет ни 0x9215, ни 0x9216, ни 0x9223. Эта камера на них ответила бы (ловушка) и даже говорит
    // 0xE086 = 1, — но сеанс смотрит в список операций из GetDeviceInfo, а не на свойства
    let cam = FakeCamera(props: [.init(code: SonyProp.lensInfoEnabled, type: PTPType.uint8, getSet: 0, current: 1, values: [0, 1])])
    for operation in newOperations {
        cam.answer(operation) { _ in PTPReply(code: PTPResponseCode.ok, params: [0x0136], data: Data(count: 64)) }
    }
    let session = try await readySession(cam)
    #expect(try await session.displayStrings(type: 0) == DisplayStrings())
    #expect(try await session.displayStrings(type: DisplayStrings.creativeLookType) == DisplayStrings())
    let load = try await session.loadDisplayStrings()
    #expect(load.strings == DisplayStrings() && load.failures.isEmpty)
    #expect(try await session.vendorCodeVersion() == nil)
    #expect(try await session.lensInformation() == nil)
    #expect(!cam.sent.contains { newOperations.contains($0.op) }, "\(cam.sent.map { String($0.op, radix: 16) })")
}

@Test func oneOfTheOperationsDoesNotOpenTheOthers() async throws {
    // камера назвала только 0x9223: подписи и версию по-прежнему не спрашиваем
    let cam = try newerCamera(operations: [0x9201, 0x9209, 0x9223])
    let session = try await readySession(cam)
    #expect(try await session.displayStrings(type: 0) == DisplayStrings())
    #expect(try await session.vendorCodeVersion() == nil)
    #expect(try await session.lensInformation() != nil)
    #expect(cam.sent.filter { newOperations.contains($0.op) }.map(\.op) == [SonyOp.getLensInformation])
}

@Test func vendorCodeVersionIsTheReplyParameter() async throws {
    let cam = try newerCamera()
    #expect(try await readySession(cam).vendorCodeVersion() == 0x0136)
    #expect(try await readySession(try newerCamera()).vendorCodeVersion() == 310)
    // без параметра в ответе версии нет
    let bare = try newerCamera()
    bare.answer(SonyOp.getVendorCodeVersion) { _ in PTPReply(code: PTPResponseCode.ok) }
    #expect(try await readySession(bare).vendorCodeVersion() == nil)
    // отказ камеры — ошибка с кодом операции
    let refusing = try newerCamera()
    refusing.answer(SonyOp.getVendorCodeVersion) { _ in PTPReply(code: 0x2002) }
    let session = try await readySession(refusing)
    await #expect(throws: SessionError.operationFailed(operation: SonyOp.getVendorCodeVersion, code: 0x2002)) {
        try await session.vendorCodeVersion()
    }
}

@Test func vendorCodeVersionIsShownLikeSonyWritesIt() {
    // версия ×100: 0x0136 — 310 — «3.10»; 100 — «1.00»
    #expect(VendorCodeVersion.label(310) == "3.10")
    #expect(VendorCodeVersion.label(0x0136) == "3.10")
    #expect(VendorCodeVersion.label(100) == "1.00")
    #expect(VendorCodeVersion.label(105) == "1.05")
    #expect(VendorCodeVersion.label(5) == "0.05")
    #expect(VendorCodeVersion.label(1234) == "12.34")
}

@Test func lensInformationIsAskedInMetersAndOnlyWhileTheCameraHasItEnabled() async throws {
    let on = try newerCamera(lensEnabled: 1)
    let lens = try #require(try await readySession(on).lensInformation())
    #expect(lens.pairs.count == 12 && lens.absoluteScale && lens.tableCount == 1)
    #expect(on.sent.filter { $0.op == SonyOp.getLensInformation }.map(\.params) == [[2]])

    // 0xE086 = 0 — данных объектива нет (например, объектив без электроники): камеру не спрашиваем
    let off = try newerCamera(lensEnabled: 0)
    #expect(try await readySession(off).lensInformation() == nil)
    #expect(!off.sent.contains { $0.op == SonyOp.getLensInformation })

    // свойства 0xE086 у камеры нет — то же самое
    let none = try newerCamera(lensEnabled: nil)
    #expect(try await readySession(none).lensInformation() == nil)
    #expect(!none.sent.contains { $0.op == SonyOp.getLensInformation })
}

@Test func lensInformationFollowsTheEnableFlagAsItChanges() async throws {
    let cam = try newerCamera(lensEnabled: 0)
    let session = try await readySession(cam)
    #expect(try await session.lensInformation() == nil)
    // на камере поставили объектив с электроникой: 0xE086 стал 1, камера сообщила об изменении свойств
    var updates = session.snapshots.makeAsyncIterator()
    _ = await updates.next()
    cam.setCurrent(SonyProp.lensInfoEnabled, 1)
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    #expect(await updates.next()?[SonyProp.lensInfoEnabled]?.current == .int(1))
    #expect(try await session.lensInformation()?.pairs.count == 12)
    // и снова снят
    cam.setCurrent(SonyProp.lensInfoEnabled, 0)
    cam.emit(PTPEvent(code: Sony.propertyChangedEvent))
    #expect(await updates.next()?[SonyProp.lensInfoEnabled]?.current == .int(0))
    #expect(try await session.lensInformation() == nil)
}

@Test func lensInformationThatCannotBeReadIsAnErrorNotATable() async throws {
    let cam = try newerCamera()
    cam.answer(SonyOp.getLensInformation) { _ in PTPReply(code: PTPResponseCode.ok, data: Data([8, 0, 0, 0, 40, 0, 0, 0])) }
    let session = try await readySession(cam)
    await #expect(throws: PTPParseError.self) { try await session.lensInformation() }
    // отказ камеры — ошибка с кодом
    cam.answer(SonyOp.getLensInformation) { _ in PTPReply(code: 0x2019) }
    await #expect(throws: SessionError.operationFailed(operation: SonyOp.getLensInformation, code: 0x2019)) {
        try await session.lensInformation()
    }
}

// MARK: Подписи при подключении: один сломанный список не лишает остальных

@Test func startupDisplayStringsAreAllTheListsOfTheCamera() async throws {
    let cam = try newerCamera()
    let load = try await readySession(cam).loadDisplayStrings()
    #expect(Set(load.strings.lists.keys) == [0x13, 0x15] && load.failures.isEmpty)
    #expect(cam.sent.filter { $0.op == SonyOp.getDisplayStringList }.map(\.params) == [[0]])
}

@Test func startupDisplayStringsFallBackToCreativeLookWhenTheWholeSetIsUnreadable() async throws {
    // один список с неизвестным типом данных PTP обрывает разбор всего набора (тип 0): первый список целый, второй — нет
    var whole: [UInt8] = le16(2) + [0, 0]
    whole += le32(0x13) + le16(PTPType.uint16) + le16(1)                  // список 0x13 из одной строки…
    whole += le16(1) + le16(3) + [0x53, 0x54, 0]                          // …1 → «ST»
    whole += le32(0x15) + le16(0x0000) + le16(1)                          // список 0x15: типа данных 0 в PTP нет
    whole += [0, 0, 0]
    let unreadable = dataset(whole)
    let looks = dataset(stringLists([(0x13, PTPType.uint16, [(1, "ST"), (6, "FL")])]))
    let cam = try newerCamera()
    cam.answer(SonyOp.getDisplayStringList) { params in
        PTPReply(code: PTPResponseCode.ok, params: [100], data: params == [DisplayStrings.creativeLookType] ? looks : unreadable)
    }
    let load = try await readySession(cam).loadDisplayStrings()
    // от камеры — только Creative Look, единственное, что программа показывает
    #expect(load.strings.lists == [0x13: [1: "ST", 6: "FL"]])
    #expect(load.failures.map(\.type) == [0])
    #expect(load.failures.first?.error as? PTPParseError == .unsupportedType(0x0000))
    #expect(cam.sent.filter { $0.op == SonyOp.getDisplayStringList }.map(\.params) == [[0], [0x13]])
}

@Test func startupDisplayStringsFallBackWhenTheCameraRefusesTheWholeSet() async throws {
    let cam = try newerCamera()
    let looks = dataset(stringLists([(0x13, PTPType.uint16, [(6, "FL")])]))
    cam.answer(SonyOp.getDisplayStringList) { params in
        params == [0] ? PTPReply(code: 0x2006) : PTPReply(code: PTPResponseCode.ok, params: [100], data: looks)
    }
    let load = try await readySession(cam).loadDisplayStrings()
    #expect(load.strings.lists == [0x13: [6: "FL"]])
    #expect(load.failures.count == 1 && load.failures[0].type == 0)
    #expect(load.failures[0].error as? SessionError == .operationFailed(operation: SonyOp.getDisplayStringList, code: 0x2006))
}

@Test func startupDisplayStringsCarryOnWithoutNamesWhenNothingCanBeRead() async throws {
    let cam = try newerCamera()
    cam.answer(SonyOp.getDisplayStringList) { _ in PTPReply(code: PTPResponseCode.ok, data: Data([1, 2, 3])) }
    let load = try await readySession(cam).loadDisplayStrings()
    // ни ошибки, ни названий: программа берёт названия Creative Look из таблиц Sony
    #expect(load.strings == DisplayStrings())
    #expect(load.failures.map(\.type) == [0, 0x13])
    #expect(cam.sent.filter { $0.op == SonyOp.getDisplayStringList }.count == 2)
}

@Test func startupDisplayStringsDoNotAskAgainAfterTheLinkIsLost() async throws {
    let cam = try newerCamera()
    let session = try await readySession(cam)
    cam.disconnectOnNext = SonyOp.getDisplayStringList
    await #expect(throws: TransportError.disconnected) { try await session.loadDisplayStrings() }
    // обрыв — не повод пробовать другой тип: второй запрос в оборванную связь не уходит
    #expect(cam.sent.filter { $0.op == SonyOp.getDisplayStringList }.count == 1)
}

// MARK: - Фикстуры

@Test func fixtureCopiesForAndroidMatch() throws {
    // Tests/ZVCoreTests/Fixtures и android/app/src/test/resources/fixtures — одни и те же байты (их пишет make_other_cameras.py)
    let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    for name in ["display_strings", "lens_information"] {
        let android = try Data(contentsOf: root.appendingPathComponent("android/app/src/test/resources/fixtures/\(name).bin"))
        #expect(try Fixture.data(name) == android, "\(name).bin")
    }
}
