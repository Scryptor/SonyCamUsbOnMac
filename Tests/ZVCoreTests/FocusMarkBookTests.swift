import Foundation
import Testing
@testable import ZVCore

// MARK: - Метки по камерам

@Test func marksAreKeptPerCamera() {
    var book = FocusMarkBook()
    book.save("A", at: 20, for: "cam-1", mode: .steps)
    book.save("B", at: 80, for: "cam-1", mode: .steps)
    book.save("A", at: 55, for: "cam-2", mode: .steps)
    #expect(book.marks(for: "cam-1", mode: .steps) == FocusMarks(a: 20, b: 80))
    #expect(book.marks(for: "cam-2", mode: .steps) == FocusMarks(a: 55))
    // камеры, которой ещё не было, — пустые метки и два секунды по умолчанию
    #expect(book.marks(for: "cam-3", mode: .steps) == FocusMarks())
    // новая метка заменяет прежнюю, вторую не трогает
    book.save("A", at: 31, for: "cam-1", mode: .steps)
    #expect(book.marks(for: "cam-1", mode: .steps) == FocusMarks(a: 31, b: 80))
}

@Test func clearErasesOnlyThatMark() {
    var book = FocusMarkBook()
    book.save("A", at: 20, for: "cam", mode: .steps)
    book.save("B", at: 80, for: "cam", mode: .steps)
    book.clear("A", for: "cam", mode: .steps)
    #expect(book.marks(for: "cam", mode: .steps) == FocusMarks(a: nil, b: 80))
    // пустую стирать нечего: ничего не меняется, и другая камера не появляется в книге
    let before = book
    book.clear("A", for: "cam", mode: .steps)
    book.clear("B", for: "nobody", mode: .steps)
    #expect(book == before)
}

@Test func onlyMarksAAndBExistAndOnlyFinitePositionsAreSaved() {
    var book = FocusMarkBook()
    book.save("C", at: 10, for: "cam", mode: .steps)
    book.save("a", at: 10, for: "cam", mode: .steps)
    book.save("A", at: .nan, for: "cam", mode: .steps)
    book.save("B", at: .infinity, for: "cam", mode: .steps)
    #expect(book == FocusMarkBook())
    #expect(book.marks(for: "cam", mode: .steps) == FocusMarks())
    // положение 0 — настоящая метка (самый ближний фокус), а не «пусто»
    book.save("A", at: 0, for: "cam", mode: .steps)
    #expect(book.marks(for: "cam", mode: .steps).a == 0)
}

@Test func marksInOtherUnitsAreNotReadInTheOtherMode() {
    var book = FocusMarkBook()
    book.save("A", at: 40, for: "cam", mode: .steps)
    book.save("B", at: 90, for: "cam", mode: .steps)
    book.cycleDuration(for: "cam")
    // камера перешла на абсолютный фокус: метки по шкале 0…100 по шкале 0…65535 не читаются, время остаётся
    #expect(book.marks(for: "cam", mode: .absolute) == FocusMarks(duration: 4))
    // вернулась — метки на месте
    #expect(book.marks(for: "cam", mode: .steps) == FocusMarks(a: 40, b: 90, duration: 4))
    // первая же метка в новом режиме начинает набор заново: рядом с 30000 прежних «90» не лежит
    book.save("A", at: 30000, for: "cam", mode: .absolute)
    #expect(book.marks(for: "cam", mode: .absolute) == FocusMarks(a: 30000, b: nil, duration: 4))
    #expect(book.marks(for: "cam", mode: .steps) == FocusMarks(a: nil, b: nil, duration: 4))
    // стереть то, чего в этом режиме не видно, нельзя — и метки другого режима не пропадают
    var other = FocusMarkBook()
    other.save("A", at: 40, for: "cam", mode: .steps)
    other.clear("A", for: "cam", mode: .absolute)
    #expect(other.marks(for: "cam", mode: .steps).a == 40)
}

// MARK: - Время перевода

@Test func durationCyclesPerCameraAndKeepsMarks() {
    var book = FocusMarkBook()
    #expect(book.duration(for: "cam") == 2)
    book.save("A", at: 12, for: "cam", mode: .steps)
    var seen: [Double] = []
    for _ in 0..<5 {
        book.cycleDuration(for: "cam")
        seen.append(book.duration(for: "cam"))
    }
    #expect(seen == [4, 8, 0, 1, 2])
    #expect(book.marks(for: "cam", mode: .steps).a == 12)
    // у другой камеры своё время
    book.cycleDuration(for: "other")
    #expect(book.duration(for: "other") == 4)
    #expect(book.duration(for: "cam") == 2)
}

@Test func durationIsSetOnlyToListedValues() {
    var book = FocusMarkBook()
    for seconds in FocusPull.durations {
        let accepted = book.setDuration(seconds, for: "cam")
        #expect(accepted)
        #expect(book.duration(for: "cam") == seconds)
    }
    let before = book
    for bad in [3.0, 2.5, -1, 9, .nan, .infinity] {
        let accepted = book.setDuration(bad, for: "cam")
        #expect(!accepted)
    }
    #expect(book == before)
    // время можно поставить и до первой метки
    let accepted = book.setDuration(8, for: "fresh")
    #expect(accepted)
    #expect(book.marks(for: "fresh", mode: .steps) == FocusMarks(duration: 8))
}

// MARK: - Смена объектива

@Test func lensChangeErasesMarksButKeepsTime() {
    var book = FocusMarkBook()
    book.save("A", at: 20, for: "cam", mode: .absolute)
    book.save("B", at: 60000, for: "cam", mode: .absolute)
    book.save("A", at: 70, for: "other", mode: .steps)
    book.setDuration(8, for: "cam")
    book.lensChanged(for: "cam")
    #expect(book.marks(for: "cam", mode: .absolute) == FocusMarks(duration: 8))
    // чужая камера не задета, и камера без меток не появляется в книге
    #expect(book.marks(for: "other", mode: .steps).a == 70)
    let before = book
    book.lensChanged(for: "never-seen")
    #expect(book == before)
    // после смены метки ставятся заново, в любом режиме
    book.save("A", at: 33, for: "cam", mode: .steps)
    #expect(book.marks(for: "cam", mode: .steps) == FocusMarks(a: 33, duration: 8))
}

// MARK: - Касание кнопки

@Test func tapOnAnEmptyMarkSaves() {
    #expect(FocusMarkBook.tap(on: "A", marks: FocusMarks(), moving: nil) == .save)
    #expect(FocusMarkBook.tap(on: "B", marks: FocusMarks(a: 10), moving: nil) == .save)
    // положение 0 — заданная метка, а не пустая
    #expect(FocusMarkBook.tap(on: "A", marks: FocusMarks(a: 0), moving: nil) == .go)
}

@Test func tapOnASetMarkGoesAndOnTheMovingOneStops() {
    let marks = FocusMarks(a: 10, b: 90)
    #expect(FocusMarkBook.tap(on: "A", marks: marks, moving: nil) == .go)
    #expect(FocusMarkBook.tap(on: "A", marks: marks, moving: "A") == .stop)
    // едет к A, коснулись B: фокус поедет к B, а не остановится
    #expect(FocusMarkBook.tap(on: "B", marks: marks, moving: "A") == .go)
    // едет к A, а B пуста: B запоминает положение, перевод к A не касается
    #expect(FocusMarkBook.tap(on: "B", marks: FocusMarks(a: 10), moving: "A") == .save)
}

@Test func marksAreReadAndWrittenByLetter() {
    var marks = FocusMarks(a: 1, b: 2)
    #expect(marks["A"] == 1)
    #expect(marks["B"] == 2)
    #expect(marks["C"] == nil)
    marks["A"] = 7
    marks["B"] = nil
    marks["C"] = 9
    #expect(marks == FocusMarks(a: 7, b: nil))
}

// MARK: - Положение фокуса по свойствам камеры

private func property(_ code: UInt16, type: UInt16, current: Int64, getSet: UInt8 = 1, enabled: UInt8 = 1) -> SonyProperty {
    SonyProperty(code: code, dataType: type, getSet: getSet, enabled: enabled, current: .int(current), range: nil,
                 settableValues: [], allValues: [])
}

private func properties(_ list: SonyProperty...) -> [UInt16: SonyProperty] {
    Dictionary(uniqueKeysWithValues: list.map { ($0.code, $0) })
}

@Test func absoluteModeNeedsAnOpenTargetAndAPosition() {
    let target = property(SonyProp.focusPositionSetting, type: PTPType.uint16, current: 100)
    let position = property(SonyProp.focusPositionCurrent, type: PTPType.uint16, current: 40000)
    let percent = property(SonyProp.focalPosition, type: PTPType.uint8, current: 61, getSet: 0)
    #expect(FocusPosition.mode(properties(target, position, percent)) == .absolute)
    #expect(FocusPosition.current(properties(target, position, percent), mode: .absolute) == 40000)
    // цель перечислена, но не открыта на запись (только показ, закрыта) — шаги
    let shown = property(SonyProp.focusPositionSetting, type: PTPType.uint16, current: 100, enabled: 2)
    let closed = property(SonyProp.focusPositionSetting, type: PTPType.uint16, current: 100, enabled: 0)
    let readOnly = property(SonyProp.focusPositionSetting, type: PTPType.uint16, current: 100, getSet: 0)
    for notWritable in [shown, closed, readOnly] {
        #expect(FocusPosition.mode(properties(notWritable, position, percent)) == .steps)
    }
    // положения 0xE043 нет или оно закрыто — абсолютно ехать нечем
    #expect(FocusPosition.mode(properties(target, percent)) == .steps)
    let closedPosition = property(SonyProp.focusPositionCurrent, type: PTPType.uint16, current: 40000, enabled: 0)
    #expect(FocusPosition.mode(properties(target, closedPosition, percent)) == .steps)
    #expect(FocusPosition.mode(properties(target, closedPosition)) == nil)
}

@Test func stepsModeReadsTheFocalPosition() {
    let percent = property(SonyProp.focalPosition, type: PTPType.uint8, current: 61, getSet: 0)
    #expect(FocusPosition.mode(properties(percent)) == .steps)
    #expect(FocusPosition.current(properties(percent), mode: .steps) == 61)
    // 0xE043 в режиме шагов не читается, и наоборот
    #expect(FocusPosition.current(properties(percent), mode: .absolute) == nil)
    let position = property(SonyProp.focusPositionCurrent, type: PTPType.uint16, current: 40000)
    #expect(FocusPosition.current(properties(position), mode: .steps) == nil)
}

@Test func noPositionMeansNoMarks() {
    #expect(FocusPosition.mode([:]) == nil)
    let closed = property(SonyProp.focalPosition, type: PTPType.uint8, current: 61, enabled: 0)
    #expect(FocusPosition.mode(properties(closed)) == nil)
    #expect(FocusPosition.current(properties(closed), mode: .steps) == nil)
    // свойство без числа (строка) положением не считается
    let odd = SonyProperty(code: SonyProp.focalPosition, dataType: PTPType.string, getSet: 0, enabled: 1, current: .string("x"),
                           range: nil, settableValues: [], allValues: [])
    #expect(FocusPosition.mode(properties(odd)) == nil)
}

@Test func scaleFractionAndTolerance() {
    #expect(FocusPosition.scale(.steps) == 100)
    #expect(FocusPosition.scale(.absolute) == 65535)
    #expect(FocusPosition.fraction(of: 25, mode: .steps) == 0.25)
    #expect(FocusPosition.fraction(of: 32767.5, mode: .absolute) == 0.5)
    // за краем и не-число — край шкалы
    #expect(FocusPosition.fraction(of: -5, mode: .steps) == 0)
    #expect(FocusPosition.fraction(of: 400, mode: .steps) == 1)
    #expect(FocusPosition.fraction(of: .nan, mode: .absolute) == 0)
    // допуск «уже у метки» — тот же, что у самого перевода в начале: 0,5 % шкалы и max(1, k/2) при k = 1,0
    #expect(abs(FocusPosition.tolerance(.absolute) - 327.675) < 1e-9)
    #expect(FocusPosition.tolerance(.steps) == 1)
}
