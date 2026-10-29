import Foundation
import Testing
@testable import ZVCore

// MARK: - Помощники

/// Строки данных тождественного куба, как их пишут программы (шесть знаков); красный меняется быстрее всех.
private func identityRows(size: Int) -> [String] {
    let steps = (0..<size).map { String(format: "%.6f", Double($0) / Double(size - 1)) }
    var rows: [String] = []
    rows.reserveCapacity(size * size * size)
    for b in 0..<size {
        for g in 0..<size {
            for r in 0..<size { rows.append("\(steps[r]) \(steps[g]) \(steps[b])") }
        }
    }
    return rows
}

private func lines(_ items: [String], eol: String = "\n") -> String { items.joined(separator: eol) }

private func near(_ a: (Float, Float, Float), _ b: (Float, Float, Float), _ eps: Float) -> Bool {
    abs(a.0 - b.0) <= eps && abs(a.1 - b.1) <= eps && abs(a.2 - b.2) <= eps
}

/// Файл 2³ с заголовком из `header` и строками данных `rows` (по умолчанию — тождественный куб).
private func smallCube(header: [String] = ["LUT_3D_SIZE 2"], rows: [String]? = nil, eol: String = "\n") -> String {
    lines(header + (rows ?? identityRows(size: 2)), eol: eol)
}

// MARK: - Правильные файлы

@Test func smallCubeWithTitleCommentsAndBlankLinesParses() throws {
    let text = lines(["# сделано вручную", "TITLE \"x\"", "", "# размер", "LUT_3D_SIZE 2", ""] + identityRows(size: 2) + ["", "# конец"])
    let lut = try CubeFile.parse(text)
    #expect(lut.size == 2)
    #expect(lut.domainMin == [0, 0, 0])
    #expect(lut.domainMax == [1, 1, 1])
    #expect(lut == ColorLUT.identity(size: 2))
}

@Test func domainMinAndMaxStretchTheInput() throws {
    let lut = try CubeFile.parse(smallCube(header: ["LUT_3D_SIZE 2", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 2 2 2"]))
    #expect(lut.domainMin == [0, 0, 0])
    #expect(lut.domainMax == [2, 2, 2])
    // 1 на домене 0…2 — середина куба
    #expect(near(lut.apply(1, 1, 1), (0.5, 0.5, 0.5), 1e-6))
    #expect(near(lut.apply(2, 2, 2), (1, 1, 1), 1e-6))
    // за доменом вход обрезается
    #expect(near(lut.apply(5, -3, 1), (1, 0, 0.5), 1e-6))
}

@Test func domainMayStartBelowZeroAndDifferPerChannel() throws {
    let lut = try CubeFile.parse(smallCube(header: ["DOMAIN_MIN -1 0 0.5", "DOMAIN_MAX 1 4 1.5", "LUT_3D_SIZE 2"]))
    #expect(lut.domainMin == [-1, 0, 0.5])
    #expect(lut.domainMax == [1, 4, 1.5])
    #expect(near(lut.apply(0, 1, 0.75), (0.5, 0.25, 0.25), 1e-6))
}

@Test func domainKeywordsMayComeInAnyOrder() throws {
    let lut = try CubeFile.parse(smallCube(header: ["DOMAIN_MAX 3 3 3", "LUT_3D_SIZE 2", "DOMAIN_MIN 1 1 1"]))
    #expect(lut.domainMin == [1, 1, 1])
    #expect(lut.domainMax == [3, 3, 3])
}

@Test func crlfAndBomDoNotGetInTheWay() throws {
    let header = ["# сделано вручную", "TITLE \"x\"", "LUT_3D_SIZE 2"]
    let plain = try CubeFile.parse(smallCube(header: header))
    let crlf = try CubeFile.parse(smallCube(header: header, eol: "\r\n") + "\r\n")
    let bom = try CubeFile.parse("\u{FEFF}" + smallCube(header: header))
    let both = try CubeFile.parse("\u{FEFF}" + smallCube(header: header, eol: "\r\n") + "\r\n\r\n")
    let oldMac = try CubeFile.parse(smallCube(header: header, eol: "\r"))
    #expect(plain == ColorLUT.identity(size: 2))
    #expect(crlf == plain)
    #expect(bom == plain)
    #expect(both == plain)
    #expect(oldMac == plain)
}

@Test func commentAfterNumbersIsDropped() throws {
    var rows = identityRows(size: 2)
    // и через пробел, и впритык к числу, и без пробела вовсе
    for i in rows.indices { rows[i] += [" # строка \(i)", "#r", "   #   0.5 0.5 0.5"][i % 3] }
    let lut = try CubeFile.parse(smallCube(header: ["LUT_3D_SIZE 2 # восемь строк"], rows: rows))
    #expect(lut == ColorLUT.identity(size: 2))
}

@Test func titleInQuotesIsJustIgnored() throws {
    // в названии бывают решётка, числа и даже ключевые слова
    for title in ["x", "My # LUT", "LUT_3D_SIZE 99", "S-Log3 → 709 (v1.0, 33 точки)", "", "кавычка \" внутри"] {
        let lut = try CubeFile.parse(smallCube(header: ["TITLE \"\(title)\"", "LUT_3D_SIZE 2"]))
        #expect(lut == ColorLUT.identity(size: 2), "TITLE \"\(title)\"")
    }
}

@Test func valuesStayInFileOrderRedFastest() throws {
    // строка i — (i/10; i/10 + 0,01; i/10 + 0,02): по ней видно, какая строка к какому углу куба
    let rows = (0..<8).map { i -> String in
        let v = Double(i) / 10
        return "\(v) \(v + 0.01) \(v + 0.02)"
    }
    let lut = try CubeFile.parse(smallCube(rows: rows))
    for i in 0..<8 {
        let r = Float(i % 2), g = Float((i / 2) % 2), b = Float(i / 4)
        let v = Float(i) / 10
        #expect(near(lut.apply(r, g, b), (v, v + 0.01, v + 0.02), 1e-6), "угол \(i)")
    }
}

@Test func numbersMayBeWrittenInAnyUsualWay() throws {
    // табуляция, лишние пробелы, знак плюс, порядок степени, точка без цифр с одной стороны
    let rows = [
        "0\t0\t0", "  1.0   0.0 0.0  ", "+0.0 1e0 0", "1. 1.0 .0", "0 0 1E0", "1 0 1", "0 1 1", "1 1 +1.0e+0",
    ]
    let lut = try CubeFile.parse(smallCube(rows: rows))
    #expect(lut == ColorLUT.identity(size: 2))
}

@Test func fileWithoutFinalNewlineOrWithBlankTailParses() throws {
    let plain = smallCube()
    #expect(try CubeFile.parse(plain) == ColorLUT.identity(size: 2))
    #expect(try CubeFile.parse(plain + "\n\n\n") == ColorLUT.identity(size: 2))
    #expect(try CubeFile.parse("\n\n" + plain) == ColorLUT.identity(size: 2))
}

@Test func otherKeywordsFromOtherProgramsAreSkipped() throws {
    let header = ["LUT_IN_VIDEO_RANGE", "LUT_3D_INPUT_RANGE 0.0 1.0", "LUT_3D_SIZE 2", "LUT_OUT_VIDEO_RANGE"]
    #expect(try CubeFile.parse(smallCube(header: header)) == ColorLUT.identity(size: 2))
}

@Test func sizeLimitsAreAccepted() throws {
    // 2 — самый маленький куб
    #expect(try CubeFile.parse(smallCube()).size == 2)
    // 65 — самый большой: заголовок принят, а данных нет — об этом и ошибка
    #expect(throws: CubeFile.Failure.count(found: 0, expected: 65 * 65 * 65)) {
        try CubeFile.parse("LUT_3D_SIZE 65\n")
    }
}

@Test func mediumCubeKeepsEveryValue() throws {
    let size = 17
    let lut = try CubeFile.parse(lines(["LUT_3D_SIZE \(size)"] + identityRows(size: size)))
    let identity = ColorLUT.identity(size: size)
    #expect(lut.size == size)
    #expect(lut.values.count == identity.values.count)
    var worst: Float = 0
    for (a, b) in zip(lut.values, identity.values) { worst = max(worst, abs(a - b)) }
    // шесть знаков в файле — до 5·10⁻⁷ на число
    #expect(worst < 1e-6)
}

// MARK: - Ошибки

@Test func oneDimensionalTableIsNotThreeD() {
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("LUT_1D_SIZE 1024\n0 0 0\n1 1 1\n") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("TITLE \"x\"\nLUT_1D_SIZE 1024 # шейпер\n") }
    // и если в файле есть оба
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse(smallCube(header: ["LUT_3D_SIZE 2", "LUT_1D_SIZE 16"])) }
}

@Test func fileWithoutSizeIsNotThreeD() {
    // не .cube вовсе: пустой файл, одни комментарии, чужой текст, числа без заголовка, как в .3dl
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("# просто комментарий\n\n") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("{\"lut\": 1}\n") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("hello world\n0 0 0\n") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse("0 64 128 192 256 320\n0 0 0\n") }
    #expect(throws: CubeFile.Failure.notThreeD) { try CubeFile.parse(lines(identityRows(size: 2))) }
}

@Test(arguments: [66, 67, 100, 1000, 1, 0, -3])
func sizeOutsideTwoToSixtyFiveIsBad(_ size: Int) {
    #expect(throws: CubeFile.Failure.badSize(size)) { try CubeFile.parse(smallCube(header: ["LUT_3D_SIZE \(size)"])) }
}

@Test func badSizeIsReportedBeforeAnyData() {
    // файла дальше может не быть вовсе — размер проверяется сразу, а не когда посчитаны строки
    #expect(throws: CubeFile.Failure.badSize(66)) { try CubeFile.parse("LUT_3D_SIZE 66") }
}

@Test func wrongRowCountIsReportedWithBothNumbers() {
    let rows = identityRows(size: 2)
    #expect(throws: CubeFile.Failure.count(found: 7, expected: 8)) { try CubeFile.parse(smallCube(rows: Array(rows.dropLast()))) }
    #expect(throws: CubeFile.Failure.count(found: 9, expected: 8)) { try CubeFile.parse(smallCube(rows: rows + ["0 0 0"])) }
    #expect(throws: CubeFile.Failure.count(found: 0, expected: 8)) { try CubeFile.parse("LUT_3D_SIZE 2\n") }
    // комментарии и пустые строки между данными в счёт не идут
    #expect(throws: CubeFile.Failure.count(found: 7, expected: 8)) {
        try CubeFile.parse(smallCube(rows: Array(rows.dropLast()) + ["", "# дальше пусто", "   "]))
    }
}

@Test func notANumberInDataIsReportedWithItsLine() {
    let text = lines([
        "# сделано вручную",  // 1
        "TITLE \"x\"",        // 2
        "",                   // 3
        "LUT_3D_SIZE 2",      // 4
        "0 0 0",              // 5
        "abc 0 0",            // 6
    ] + Array(identityRows(size: 2).dropFirst(2)))
    #expect(throws: CubeFile.Failure.number(line: 6)) { try CubeFile.parse(text) }
    // номер строки — строка файла, а не строка данных; CRLF, BOM и «\r» его не сдвигают
    #expect(throws: CubeFile.Failure.number(line: 6)) { try CubeFile.parse(text.replacingOccurrences(of: "\n", with: "\r\n")) }
    #expect(throws: CubeFile.Failure.number(line: 6)) { try CubeFile.parse("\u{FEFF}" + text) }
    #expect(throws: CubeFile.Failure.number(line: 6)) { try CubeFile.parse(text.replacingOccurrences(of: "\n", with: "\r")) }
}

@Test(arguments: [
    "abc 0 0", "0 abc 0", "0 0 abc", "abc", "0 0", "0 0 0 0", "nan 0 0", "NaN 0 0", "inf 0 0", "-inf 0 0", "0 0 infinity",
    "0,5 0 0", "0..5 0 0", "1e 0 0", "0x 0 0", "- 0 0", "++1 0 0", ". 0 0", "1e999 0 0", "0 0 0 abc", "0 0 0.5.5",
    // числа, которые принимают одни разборщики и не принимают другие, здесь не числа ни у кого
    "1f 0 0", "1d 0 0", "0x10 0 0", "0x1p3 0 0", "1_0 0 0", "0 ١ 0", "1e+ 0 0", "e5 0 0",
])
func badDataRowIsReportedWithItsLine(_ row: String) {
    let text = lines(["LUT_3D_SIZE 2", "0 0 0", row] + Array(identityRows(size: 2).dropFirst(2)))
    #expect(throws: CubeFile.Failure.number(line: 3)) { try CubeFile.parse(text) }
}

@Test func badNumberBeatsRowCount() {
    // строк мало, и одна из них битая: говорим о битой — она найдена первой
    #expect(throws: CubeFile.Failure.number(line: 3)) { try CubeFile.parse("LUT_3D_SIZE 2\n0 0 0\nx 0 0\n") }
}

@Test(arguments: ["LUT_3D_SIZE", "LUT_3D_SIZE abc", "LUT_3D_SIZE 2.5", "LUT_3D_SIZE 2 2", "LUT_3D_SIZE 0x2", "LUT_3D_SIZE 99999999999999999999"])
func brokenSizeLineIsReportedWithItsLine(_ line: String) {
    #expect(throws: CubeFile.Failure.number(line: 2)) { try CubeFile.parse("# заголовок\n\(line)\n" + lines(identityRows(size: 2))) }
}

@Test(arguments: ["DOMAIN_MIN", "DOMAIN_MIN 0 0", "DOMAIN_MIN 0 0 0 0", "DOMAIN_MIN a b c", "DOMAIN_MIN 0 nan 0", "DOMAIN_MAX 1 1",
                  "DOMAIN_MAX 1 1 1f", "DOMAIN_MAX 1e999 1 1", "DOMAIN_MIN 0x0 0 0"])
func brokenDomainLineIsReportedWithItsLine(_ line: String) {
    #expect(throws: CubeFile.Failure.number(line: 2)) { try CubeFile.parse("LUT_3D_SIZE 2\n\(line)\n" + lines(identityRows(size: 2))) }
}

// MARK: - Сообщения

@Test func messagesAreTheShippedTexts() {
    #expect(CubeFile.message(.count(found: 35936, expected: 35937)) == "В файле 35 936 строк данных, а нужно 35 937")
    #expect(CubeFile.message(.notThreeD) == "Нужен 3D LUT в формате .cube")
    #expect(CubeFile.message(.badSize(66)) == "Размер LUT — 66, а нужен от 2 до 65")
    #expect(CubeFile.message(.number(line: 12)) == "В строке 12 не удалось прочитать числа")
}

@Test func countMessageGroupsThousandsWithASpace() {
    #expect(CubeFile.message(.count(found: 7, expected: 8)) == "В файле 7 строк данных, а нужно 8")
    #expect(CubeFile.message(.count(found: 1000, expected: 35937)) == "В файле 1 000 строк данных, а нужно 35 937")
    #expect(CubeFile.message(.count(found: 274_624, expected: 274_625)) == "В файле 274 624 строки данных, а нужно 274 625")
    #expect(CubeFile.message(.count(found: 1_000_000, expected: 274_625)) == "В файле 1 000 000 строк данных, а нужно 274 625")
    #expect(CubeFile.message(.badSize(1_000_000)) == "Размер LUT — 1 000 000, а нужен от 2 до 65")
    #expect(CubeFile.message(.badSize(-3)) == "Размер LUT — -3, а нужен от 2 до 65")
}

@Test func countMessageAgreesTheWordForRowsWithTheNumber() {
    func word(_ found: Int) -> String {
        CubeFile.message(.count(found: found, expected: 8)).replacingOccurrences(of: "В файле ", with: "")
            .components(separatedBy: " данных")[0]
    }
    #expect(word(0) == "0 строк")
    #expect(word(1) == "1 строка")
    #expect(word(2) == "2 строки")
    #expect(word(4) == "4 строки")
    #expect(word(5) == "5 строк")
    #expect(word(11) == "11 строк")
    #expect(word(12) == "12 строк")
    #expect(word(14) == "14 строк")
    #expect(word(21) == "21 строка")
    #expect(word(22) == "22 строки")
    #expect(word(25) == "25 строк")
    #expect(word(101) == "101 строка")
    #expect(word(111) == "111 строк")
    #expect(word(112) == "112 строк")
    #expect(word(35936) == "35 936 строк")
}

@Test func everyFailureHasADistinctMessage() {
    let failures: [CubeFile.Failure] = [.notThreeD, .badSize(66), .count(found: 1, expected: 8), .number(line: 3)]
    let texts = failures.map { CubeFile.message($0) }
    #expect(Set(texts).count == failures.count)
    #expect(texts.allSatisfy { !$0.isEmpty })
}

// MARK: - Скорость

/// Куб 65³ — самый большой из принимаемых, 274 625 строк. Разбор в release — меньше секунды:
/// ZV_BENCH=1 swift test -c release -Xswiftc -enable-testing --filter cubeParseBenchmark
@Test(.enabled(if: ProcessInfo.processInfo.environment["ZV_BENCH"] != nil))
func cubeParseBenchmark() throws {
    let size = 65
    let rows = identityRows(size: size)
    // обычный файл и самый неудобный: CRLF, BOM, заголовок с комментариями и комментарий после каждой строки
    let plain = lines(["LUT_3D_SIZE \(size)"] + rows + [""])
    let header = ["# сделано вручную", "TITLE \"тяжёлый # куб\"", "DOMAIN_MIN 0 0 0", "DOMAIN_MAX 1 1 1", "LUT_3D_SIZE \(size)"]
    let commented = rows.map { $0 + " # r g b" }
    let heavy = "\u{FEFF}" + lines(header + commented + [""], eol: "\r\n")
    for (name, text) in [("LF", plain), ("CRLF + BOM + комментарии", heavy)] {
        for run in 1...3 {
            let start = Date()
            let lut = try CubeFile.parse(text)
            let elapsed = Date().timeIntervalSince(start)
            print(String(format: "куб 65³, %@, %.1f МБ, прогон %d: %.0f мс", name, Double(text.utf8.count) / 1e6, run, elapsed * 1000))
            #expect(lut.size == size)
            #expect(lut.values.count == size * size * size * 3)
            #expect(elapsed < 1.0)
        }
    }
    // и результат тот же, что у тождественного куба
    let lut = try CubeFile.parse(plain)
    let identity = ColorLUT.identity(size: size)
    var worst: Float = 0
    for (a, b) in zip(lut.values, identity.values) { worst = max(worst, abs(a - b)) }
    #expect(worst < 1e-6)
}
