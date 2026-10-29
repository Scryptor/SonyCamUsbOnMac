import Foundation

/// Разбор файлов `.cube` — своих LUT. Принимаются только 3D-таблицы.
///
/// Что понимает:
/// - `LUT_3D_SIZE n`, `n` от 2 до 65;
/// - `DOMAIN_MIN` и `DOMAIN_MAX` по три числа (по умолчанию 0 и 1);
/// - `TITLE "…"` — название пропускается целиком, с любыми кавычками, решётками и числами внутри;
/// - комментарии `#` — с начала строки и после чисел; пустые строки;
/// - CRLF, одинокий CR и BOM в начале;
/// - данные — `n³` строк `r g b`, красный меняется быстрее всех;
/// - чужие ключевые слова вроде `LUT_IN_VIDEO_RANGE` — пропускаются.
public enum CubeFile {
    public enum Failure: Error, Equatable {
        /// Не 3D-таблица `.cube`: `LUT_1D_SIZE`, нет `LUT_3D_SIZE`, а то и вовсе не `.cube`.
        case notThreeD
        /// `LUT_3D_SIZE` вне 2…65.
        case badSize(Int)
        /// Строк данных не `size³`.
        case count(found: Int, expected: Int)
        /// В строке (по счёту от 1, считая комментарии и пустые) нет нужных чисел.
        case number(line: Int)
    }

    /// Допустимый размер куба.
    static let sizes = 2...65

    public static func parse(_ text: String) throws -> ColorLUT {
        var text = text
        return try text.withUTF8 { try parse(utf8: $0) }
    }

    /// Текст ошибки для окна: что не так и как это поправить.
    public static func message(_ failure: Failure) -> String {
        switch failure {
        case .notThreeD:
            return "Нужен 3D LUT в формате .cube"
        case .badSize(let size):
            return "Размер LUT — \(grouped(size)), а нужен от \(sizes.lowerBound) до \(sizes.upperBound)"
        case .count(let found, let expected):
            return "В файле \(grouped(found)) \(rowWord(found)) данных, а нужно \(grouped(expected))"
        case .number(let line):
            return "В строке \(grouped(line)) не удалось прочитать числа"
        }
    }

    // MARK: - Разбор

    private static func parse(utf8 bytes: UnsafeBufferPointer<UInt8>) throws -> ColorLUT {
        let end = bytes.count
        var pos = 0
        if end >= 3, bytes[0] == 0xEF, bytes[1] == 0xBB, bytes[2] == 0xBF { pos = 3 }

        var size = 0  // 0 — `LUT_3D_SIZE` ещё не встретился
        var expected = 0  // size³
        var domainMin: [Float] = [0, 0, 0], domainMax: [Float] = [1, 1, 1]
        var values: [Float] = []
        var rows = 0
        var line = 0

        while pos < end {
            line += 1
            // одним проходом: где кончается строка и с какого места в ней комментарий
            var lineEnd = pos
            var contentEnd = -1
            while lineEnd < end {
                let c = bytes[lineEnd]
                if c == 0x0A || c == 0x0D { break }
                if c == 0x23, contentEnd < 0 { contentEnd = lineEnd }
                lineEnd += 1
            }
            if contentEnd < 0 { contentEnd = lineEnd }
            let lineStart = pos
            // «\r\n» — один перевод строки, одинокий «\r» тоже
            if lineEnd < end, bytes[lineEnd] == 0x0D, lineEnd + 1 < end, bytes[lineEnd + 1] == 0x0A {
                pos = lineEnd + 2
            } else {
                pos = lineEnd + 1
            }

            var t = lineStart
            while t < contentEnd, isBlank(bytes[t]) { t += 1 }
            if t == contentEnd { continue }  // пустая строка или один комментарий

            let first = bytes[t]
            if isNumberStart(first) {
                // строка данных
                guard size > 0 else { throw Failure.notThreeD }
                var count = 0
                var rgb: (Float, Float, Float) = (0, 0, 0)
                while true {
                    while t < contentEnd, isBlank(bytes[t]) { t += 1 }
                    if t == contentEnd { break }
                    let start = t
                    while t < contentEnd, !isBlank(bytes[t]) { t += 1 }
                    guard count < 3, let v = number(bytes, start, t) else { throw Failure.number(line: line) }
                    switch count {
                    case 0: rgb.0 = v
                    case 1: rgb.1 = v
                    default: rgb.2 = v
                    }
                    count += 1
                }
                guard count == 3 else { throw Failure.number(line: line) }
                rows += 1
                // лишние строки считаем, но не копим: файл всё равно не примут
                if rows <= expected {
                    values.append(rgb.0)
                    values.append(rgb.1)
                    values.append(rgb.2)
                }
                continue
            }

            // ключевое слово и его аргументы
            let wordStart = t
            while t < contentEnd, !isBlank(bytes[t]) { t += 1 }
            let word = String(decoding: UnsafeBufferPointer(rebasing: bytes[wordStart..<t]), as: UTF8.self)
            let arguments = tokens(bytes, t, contentEnd)
            switch word {
            case "TITLE":
                break
            case "LUT_1D_SIZE":
                throw Failure.notThreeD
            case "LUT_3D_SIZE":
                guard arguments.count == 1, let n = Int(arguments[0]) else { throw Failure.number(line: line) }
                guard sizes.contains(n) else { throw Failure.badSize(n) }
                size = n
                expected = n * n * n
                values.reserveCapacity(expected * 3)
            case "DOMAIN_MIN", "DOMAIN_MAX":
                var triple: [Float] = []
                for argument in arguments {
                    guard let v = number(argument) else { throw Failure.number(line: line) }
                    triple.append(v)
                }
                guard triple.count == 3 else { throw Failure.number(line: line) }
                if word == "DOMAIN_MIN" { domainMin = triple } else { domainMax = triple }
            default:
                if isKeyword(word) { break }  // чужое ключевое слово — не наше дело
                // до заголовка это не .cube, после — битая строка данных
                throw size == 0 ? Failure.notThreeD : Failure.number(line: line)
            }
        }

        guard size > 0 else { throw Failure.notThreeD }
        guard rows == expected else { throw Failure.count(found: rows, expected: expected) }
        return ColorLUT(size: size, values: values, domainMin: domainMin, domainMax: domainMax)
    }

    /// Пробел или табуляция.
    @inline(__always)
    private static func isBlank(_ c: UInt8) -> Bool { c == 0x20 || c == 0x09 }

    /// Строка данных начинается с цифры, знака или точки.
    @inline(__always)
    private static func isNumberStart(_ c: UInt8) -> Bool { (c >= 0x30 && c <= 0x39) || c == 0x2D || c == 0x2B || c == 0x2E }

    /// Знаки десятичной записи числа: цифры, знак, точка, порядок (`-0.5`, `.25`, `1e-3`).
    @inline(__always)
    private static func isNumberChar(_ c: UInt8) -> Bool {
        (c >= 0x30 && c <= 0x39) || c == 0x2D || c == 0x2B || c == 0x2E || c == 0x65 || c == 0x45
    }

    /// Число из байтов `start..<end`. Только десятичная запись: шестнадцатеричные, «nan», «inf», «1f» и запятая вместо
    /// точки — не числа (на всех платформах одинаково); слишком большие, до бесконечности, тоже.
    private static func number(_ bytes: UnsafeBufferPointer<UInt8>, _ start: Int, _ end: Int) -> Float? {
        for i in start..<end where !isNumberChar(bytes[i]) { return nil }
        guard let v = Float(String(decoding: UnsafeBufferPointer(rebasing: bytes[start..<end]), as: UTF8.self)), v.isFinite
        else { return nil }
        return v
    }

    /// То же для слова из аргументов ключевого слова.
    private static func number(_ token: String) -> Float? {
        guard token.utf8.allSatisfy(isNumberChar), let v = Float(token), v.isFinite else { return nil }
        return v
    }

    private static func tokens(_ bytes: UnsafeBufferPointer<UInt8>, _ from: Int, _ to: Int) -> [String] {
        var result: [String] = []
        var t = from
        while true {
            while t < to, isBlank(bytes[t]) { t += 1 }
            if t == to { return result }
            let start = t
            while t < to, !isBlank(bytes[t]) { t += 1 }
            result.append(String(decoding: UnsafeBufferPointer(rebasing: bytes[start..<t]), as: UTF8.self))
        }
    }

    /// Ключевое слово `.cube` — ЗАГЛАВНЫЕ_БУКВЫ с подчёркиванием (`LUT_IN_VIDEO_RANGE`); «NaN» и «abc» — нет.
    private static func isKeyword(_ word: String) -> Bool {
        var underscore = false
        guard let first = word.utf8.first, first >= 0x41, first <= 0x5A else { return false }
        for c in word.utf8 {
            if c == 0x5F {
                underscore = true
            } else if !((c >= 0x41 && c <= 0x5A) || (c >= 0x30 && c <= 0x39)) {
                return false
            }
        }
        return underscore
    }

    // MARK: - Тексты

    /// 35936 → «35 936» (обычный пробел, как в тексте спецификации).
    private static func grouped(_ n: Int) -> String {
        let digits = Array(String(n.magnitude))
        var result = n < 0 ? "-" : ""
        for (i, digit) in digits.enumerated() {
            if i > 0, (digits.count - i) % 3 == 0 { result.append(" ") }
            result.append(digit)
        }
        return result
    }

    /// «строка» при 1, 21, 101; «строки» при 2–4, 22–24; «строк» при остальных, 11–14 тоже.
    private static func rowWord(_ n: Int) -> String {
        let last = n % 10, lastTwo = n % 100
        if last == 1, lastTwo != 11 { return "строка" }
        if (2...4).contains(last), !(12...14).contains(lastTwo) { return "строки" }
        return "строк"
    }
}
