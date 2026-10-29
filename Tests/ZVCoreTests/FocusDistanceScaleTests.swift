import Foundation
import Testing
@testable import ZVCore

// Расстояния на шкале фокуса (спека §7.4): расстояние для места на линейке, подписи под делениями без наложений.

/// Данные объектива новой камеры (стенд, профиль newer): 12 пар, положения 0…65535, от 0,25 м до «∞».
private func newerLens() throws -> LensInformation {
    try LensInformation.parse(Fixture.data("lens_information"))
}

/// Ширина подписи в тестах: шесть точек на знак — как у мелкого шрифта на линейке.
private func width(_ text: String) -> Double { Double(text.count) * 6 }

/// Что занимает подпись на линейке шириной `ruler`.
private func spans(_ ticks: [FocusDistanceScale.Tick], ruler: Double) -> [ClosedRange<Double>] {
    ticks.map { FocusDistanceScale.span(fraction: $0.fraction, labelWidth: width($0.label), width: ruler) }
}

// MARK: - Расстояние для места на линейке

@Test func distanceAtAPlaceOnTheRulerIsReadInTheScaleOfTheTable() throws {
    let lens = try newerLens()
    #expect(lens.absoluteScale)
    // 29790 из 65535 — пятая точка таблицы, 1 м
    #expect(lens.meters(atFraction: 0) == 0.25)
    #expect(lens.meters(atFraction: 1) == 1_000_000)
    let one = try #require(lens.meters(atFraction: 29790.0 / 65535))
    #expect(abs(one - 1) < 1e-9)
    #expect(lens.label(atFraction: 29790.0 / 65535) == "1 м")
    #expect(lens.label(atFraction: 0) == "0,25 м")
    #expect(lens.label(atFraction: 1) == "∞")
    // таблица в шкале 0xD24C (наибольшее положение не больше 100): доля умножается на 100, а не на 65535
    let steps = LensInformation(tables: [[(0, 0.3), (50, 1), (100, 1_000_000)]])
    #expect(!steps.absoluteScale)
    #expect(steps.meters(atFraction: 0.5) == 1)
    #expect(steps.label(atFraction: 0.5) == "1 м")
    #expect(steps.label(atFraction: 0.25) == "0,65 м")
}

@Test func distanceAtAFractionOutsideTheRulerOrWithoutAnswerIsHandledSafely() throws {
    let lens = try newerLens()
    // за краями линейки — ближний или дальний край таблицы
    #expect(lens.meters(atFraction: -0.2) == 0.25)
    #expect(lens.meters(atFraction: 1.7) == 1_000_000)
    // не число — расстояния нет, и подписи нет
    #expect(lens.meters(atFraction: .nan) == nil)
    #expect(lens.label(atFraction: .nan) == nil)
    // таблиц больше одной (у зумов — на каждое фокусное расстояние) — расстояний на шкале нет
    let two = LensInformation(tables: [[(0, 0.25), (65535, 100_000)], [(0, 0.3), (65535, 50_000)]])
    #expect(two.meters(atFraction: 0.5) == nil && two.label(atFraction: 0.5) == nil)
    // таблицы нет вовсе
    #expect(LensInformation(tables: []).label(atFraction: 0.5) == nil)
}

// MARK: - Подписи под делениями

@Test func tickLabelsOfTheNewerCameraLensAreRoundDistancesWithBothEnds() throws {
    let lens = try newerLens()
    let ticks = FocusDistanceScale.ticks(for: lens, width: 300, labelWidth: width)
    // ближний край — наименьшее расстояние, дальний — «∞»; между ними — круглые расстояния, сколько поместится
    #expect(ticks.map(\.label) == ["0,25 м", "0,5 м", "1 м", "2 м", "5 м", "∞"])
    #expect(ticks.first?.fraction == 0 && ticks.last?.fraction == 1)
    // подпись стоит там, где на шкале и правда это расстояние
    for tick in ticks { #expect(lens.label(atFraction: tick.fraction) == tick.label, "\(tick)") }
    // по порядку слева направо
    #expect(ticks.map(\.fraction) == ticks.map(\.fraction).sorted())
}

@Test func tickLabelsNeverTouchEachOtherOrLeaveTheRuler() throws {
    let lens = try newerLens()
    for ruler in stride(from: 60.0, through: 420.0, by: 12) {
        let ticks = FocusDistanceScale.ticks(for: lens, width: ruler, labelWidth: width)
        let taken = spans(ticks, ruler: ruler)
        for (index, span) in taken.enumerated() {
            #expect(span.lowerBound >= 0 && span.upperBound <= ruler, "ширина \(ruler): \(ticks[index])")
            if index > 0 { #expect(span.lowerBound - taken[index - 1].upperBound >= 12 - 1e-9, "ширина \(ruler): \(ticks[index])") }
        }
    }
}

@Test func narrowerRulerKeepsTheEndsAndDropsTheRest() throws {
    let lens = try newerLens()
    let narrow = FocusDistanceScale.ticks(for: lens, width: 120, labelWidth: width)
    #expect(narrow.first?.label == "0,25 м" && narrow.last?.label == "∞")
    // чем шире линейка, тем больше подписей
    let counts = [120.0, 300, 600].map { FocusDistanceScale.ticks(for: lens, width: $0, labelWidth: width).count }
    #expect(counts[0] < counts[1] && counts[1] < counts[2], "\(counts)")
    // линейки нет — подписать нечего
    #expect(FocusDistanceScale.ticks(for: lens, width: 0, labelWidth: width).isEmpty)
}

@Test func reservedPlacesKeepTheirNeighboursAway() throws {
    let lens = try newerLens()
    let all = FocusDistanceScale.ticks(for: lens, width: 300, labelWidth: width)
    let oneMeter = try #require(all.first { $0.label == "1 м" })
    // у метки положения стоит своя подпись — деления под ней не подписываются
    let current = FocusDistanceScale.span(fraction: oneMeter.fraction, labelWidth: width("1,2 м"), width: 300)
    let rest = FocusDistanceScale.ticks(for: lens, width: 300, reserved: [current], labelWidth: width)
    #expect(!rest.contains { $0.label == "1 м" })
    for span in spans(rest, ruler: 300) { #expect(span.upperBound + 12 <= current.lowerBound || span.lowerBound >= current.upperBound + 12) }
    // остальные на своих местах
    #expect(rest.map(\.label).contains("0,5 м") && rest.map(\.label).contains("∞"))
}

@Test func tickLabelsOfALensThatEndsBeforeInfinity() {
    // макрообъектив: от 0,2 м до 0,9 м — оба края подписаны, между ними круглые расстояния
    let macro = LensInformation(tables: [[(0, 0.2), (100, 0.9)]])
    let ticks = FocusDistanceScale.ticks(for: macro, width: 300, labelWidth: width)
    #expect(ticks.first?.label == "0,2 м" && ticks.last?.label == "0,9 м")
    #expect(ticks.map(\.label).contains("0,5 м"))
    // «1 м» и «2 м» за пределами таблицы — их на этой шкале нет
    #expect(!ticks.map(\.label).contains("1 м") && !ticks.map(\.label).contains("2 м"))
    // телеобъектив от 1,2 м: «0,5 м» и «1 м» ниже ближнего края — тоже нет
    let tele = LensInformation(tables: [[(0, 1.2), (50, 5), (100, 100_000)]])
    let far = FocusDistanceScale.ticks(for: tele, width: 300, labelWidth: width)
    // «2 м» лежит вплотную к ближнему краю и уступает ему место; от 5 м до «∞» таблица прыгает, и «10 м», «20 м», «50 м» стоят
    // на одном месте с «5 м» — остаётся самое важное из них
    #expect(far.map(\.label) == ["1,2 м", "3 м", "5 м", "∞"])
}

@Test func tickLabelsNeedExactlyOneTable() throws {
    let two = LensInformation(tables: [[(0, 0.25), (65535, 100_000)], [(0, 0.3), (65535, 50_000)]])
    #expect(FocusDistanceScale.ticks(for: two, width: 300, labelWidth: width).isEmpty)
    #expect(FocusDistanceScale.ticks(for: LensInformation(tables: []), width: 300, labelWidth: width).isEmpty)
    // одна пара — одно деление
    let single = LensInformation(tables: [[(50, 2)]])
    #expect(FocusDistanceScale.ticks(for: single, width: 300, labelWidth: width).map(\.label) == ["2 м"])
}

@Test func tickLabelsOfATableWithOddDistancesStayTruthful() {
    // расстояние то растёт, то падает: подпись всё равно ставится только там, где таблица и правда даёт это расстояние
    let odd = LensInformation(tables: [[(0, 0.5), (30, 3), (60, 1), (100, 20)]])
    let ticks = FocusDistanceScale.ticks(for: odd, width: 300, labelWidth: width)
    #expect(!ticks.isEmpty)
    for tick in ticks {
        let shown = odd.label(atFraction: tick.fraction)
        #expect(shown == tick.label, "\(tick) — на этом месте таблица даёт \(shown ?? "ничего")")
    }
}

@Test func labelPlaceStaysInsideTheRulerWhereverTheTickIs() {
    // у краёв подпись сдвигается внутрь линейки, а не выходит за неё
    let first = FocusDistanceScale.span(fraction: 0, labelWidth: 36, width: 300)
    #expect(first == 0...36)
    let last = FocusDistanceScale.span(fraction: 1, labelWidth: 36, width: 300)
    #expect(last == 264...300)
    let middle = FocusDistanceScale.span(fraction: 0.5, labelWidth: 36, width: 300)
    #expect(middle == 132...168)
    // подпись шире линейки занимает всю линейку
    #expect(FocusDistanceScale.span(fraction: 0.3, labelWidth: 500, width: 300) == 0...300)
}

// MARK: - Метка положения

private func reading(_ code: UInt16, _ value: Int64, type: UInt16 = PTPType.uint16, enabled: UInt8 = 1) -> SonyProperty {
    SonyProperty(code: code, dataType: type, getSet: 0, enabled: enabled, current: .int(value), range: nil, settableValues: [],
                 allValues: [])
}

@Test func markerPositionPrefersTheAbsoluteReadingAndFallsBackToTheSteps() throws {
    // 0xE043 (0…65535) есть — метка по нему, даже если 0xD24C (0…100) говорит другое
    let both = [SonyProp.focusPositionCurrent: reading(SonyProp.focusPositionCurrent, 32768),
                SonyProp.focalPosition: reading(SonyProp.focalPosition, 10, type: PTPType.uint8)]
    #expect(abs(try #require(FocusPosition.fraction(both)) - 32768.0 / 65535) < 1e-12)
    // 0xE043 закрыт (камера положения не отдаёт) — 0xD24C
    let closed = [SonyProp.focusPositionCurrent: reading(SonyProp.focusPositionCurrent, 32768, enabled: 0),
                  SonyProp.focalPosition: reading(SonyProp.focalPosition, 40, type: PTPType.uint8)]
    #expect(FocusPosition.fraction(closed) == 0.4)
    // у ZV-E10 только 0xD24C
    #expect(FocusPosition.fraction([SonyProp.focalPosition: reading(SonyProp.focalPosition, 100, type: PTPType.uint8)]) == 1)
    #expect(FocusPosition.fraction([SonyProp.focalPosition: reading(SonyProp.focalPosition, 0, type: PTPType.uint8)]) == 0)
    // положения нет вовсе
    #expect(FocusPosition.fraction([:]) == nil)
    #expect(FocusPosition.fraction([SonyProp.iso: reading(SonyProp.iso, 100, type: PTPType.uint32)]) == nil)
    // за шкалой — на краю линейки
    #expect(FocusPosition.fraction([SonyProp.focalPosition: reading(SonyProp.focalPosition, 180, type: PTPType.uint8)]) == 1)
}

@Test func distanceAtTheMarkerFollowsTheLensTableInItsOwnScale() throws {
    // камера с 0xE043 и таблицей в шкале 0xE043: доля из положения, расстояние — из таблицы
    let lens = try newerLens()
    let absolute = [SonyProp.focusPositionCurrent: reading(SonyProp.focusPositionCurrent, 29790)]
    #expect(lens.label(atFraction: try #require(FocusPosition.fraction(absolute))) == "1 м")
    // камера только с 0xD24C, а таблица в шкале 0xE043: доля одна и та же, шкалы разные — расстояние то же
    let steps = [SonyProp.focalPosition: reading(SonyProp.focalPosition, 100, type: PTPType.uint8)]
    #expect(lens.label(atFraction: try #require(FocusPosition.fraction(steps))) == "∞")
    let middle = [SonyProp.focalPosition: reading(SonyProp.focalPosition, 50, type: PTPType.uint8)]
    // середина шкалы — 32767,5 из 65535, между 1 м (29790) и 1,5 м (35748): 1,25 м, на шкале «1,2 м»
    #expect(lens.label(atFraction: try #require(FocusPosition.fraction(middle))) == "1,2 м")
}
