import Foundation
import Testing
@testable import ZVCore

// MARK: - Помощники

/// Две папки во временном месте: «папка программы» (там живёт библиотека) и «рабочий стол» (откуда берут файлы).
private struct Sandbox {
    let root: URL
    let folder: URL
    let desktop: URL

    init() throws {
        root = FileManager.default.temporaryDirectory.appendingPathComponent("LUTLibraryTests-\(UUID().uuidString)", isDirectory: true)
        folder = root.appendingPathComponent("LUTs", isDirectory: true)
        desktop = root.appendingPathComponent("Desktop", isDirectory: true)
        try FileManager.default.createDirectory(at: desktop, withIntermediateDirectories: true)
    }

    func library() -> LUTLibrary { LUTLibrary(folder: folder) }

    /// Файл на «рабочем столе»; возвращает его адрес.
    @discardableResult
    func write(_ name: String, _ text: String) throws -> URL {
        try write(name, Data(text.utf8))
    }

    @discardableResult
    func write(_ name: String, _ data: Data) throws -> URL {
        let url = desktop.appendingPathComponent(name)
        try data.write(to: url)
        return url
    }

    func remove() { try? FileManager.default.removeItem(at: root) }

    /// Что лежит в папке программы, по именам файлов.
    func stored() -> [String] {
        ((try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? []).sorted()
    }
}

/// Куб 2³: тождественный или перевёрнутый (1 − значение) — два разных, но правильных файла.
private func cube(inverted: Bool = false, header: [String] = ["LUT_3D_SIZE 2"]) -> String {
    var rows: [String] = []
    for b in 0..<2 {
        for g in 0..<2 {
            for r in 0..<2 {
                func v(_ x: Int) -> String { String(inverted ? 1 - x : x) }
                rows.append("\(v(r)) \(v(g)) \(v(b))")
            }
        }
    }
    return (header + rows).joined(separator: "\n") + "\n"
}

private func with<T>(_ body: (Sandbox) throws -> T) throws -> T {
    let sandbox = try Sandbox()
    defer { sandbox.remove() }
    return try body(sandbox)
}

// MARK: - Добавление

@Test func libraryWithoutFolderIsEmpty() throws {
    try with { sandbox in
        #expect(sandbox.library().names.isEmpty)
        // папки нет совсем — и не появляется от вопроса «что у вас есть»
        #expect(!FileManager.default.fileExists(atPath: sandbox.folder.path))
    }
}

@Test func importCopiesTheFileUnderItsBaseName() throws {
    try with { sandbox in
        let library = sandbox.library()
        let text = cube(inverted: true, header: ["TITLE \"Закат\"", "LUT_3D_SIZE 2"])
        let source = try sandbox.write("Тёплый закат.cube", text)
        let name = try library.importFile(source)
        #expect(name == "Тёплый закат")
        #expect(library.names == ["Тёплый закат"])
        // копия — те же байты; оригинал на месте
        #expect(sandbox.stored() == ["Тёплый закат.cube"])
        #expect(try Data(contentsOf: sandbox.folder.appendingPathComponent("Тёплый закат.cube")) == Data(text.utf8))
        #expect(FileManager.default.fileExists(atPath: source.path))
        #expect(library.load("Тёплый закат") == (try CubeFile.parse(text)))
    }
}

@Test func sameNameReplacesTheOldFile() throws {
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("grade.cube", cube(inverted: false)))
        _ = try library.importFile(try sandbox.write("grade.cube", cube(inverted: true)))
        #expect(library.names == ["grade"])
        #expect(library.load("grade") == (try CubeFile.parse(cube(inverted: true))))
        #expect(sandbox.stored() == ["grade.cube"])
    }
}

@Test func sameNameInAnotherCaseReplacesToo() throws {
    // на диске Mac «Look» и «look» — один файл: счёт и список не должны удваивать его
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("Look.cube", cube(inverted: false)))
        let name = try library.importFile(try sandbox.write("look.cube", cube(inverted: true)))
        #expect(name == "look")
        #expect(library.names == ["look"])
        #expect(sandbox.stored() == ["look.cube"])
        #expect(library.load("look") == (try CubeFile.parse(cube(inverted: true))))
    }
}

@Test func uppercaseExtensionIsAcceptedAndStoredLowercase() throws {
    try with { sandbox in
        let library = sandbox.library()
        let name = try library.importFile(try sandbox.write("GRADE.CUBE", cube()))
        #expect(name == "GRADE")
        #expect(sandbox.stored() == ["GRADE.cube"])
    }
}

@Test func namesAreSortedAlphabeticallyAndNumbersByValue() throws {
    try with { sandbox in
        let library = sandbox.library()
        for file in ["beta.cube", "Alpha.cube", "look10.cube", "look2.cube", "Гамма.cube", "alpha2.cube"] {
            _ = try library.importFile(try sandbox.write(file, cube()))
        }
        #expect(library.names == ["Alpha", "alpha2", "beta", "look2", "look10", "Гамма"])
    }
}

@Test func nameWithoutBaseGetsAUsableOne() throws {
    // «.cube» без имени — скрытый файл: в списке его не было бы, хотя добавление «удалось»
    try with { sandbox in
        let library = sandbox.library()
        let name = try library.importFile(try sandbox.write(".cube", cube()))
        #expect(!name.isEmpty && !name.hasPrefix("."))
        #expect(library.names == [name])
    }
}

// MARK: - Не больше двенадцати

@Test func thirteenthFileIsRefusedWithTheLimitText() throws {
    try with { sandbox in
        let library = sandbox.library()
        for i in 1...LUTLibrary.limit {
            _ = try library.importFile(try sandbox.write("lut\(i).cube", cube()))
        }
        #expect(library.names.count == 12)
        let extra = try sandbox.write("one-more.cube", cube())
        #expect(throws: LUTLibrary.Failure.limit) { try library.importFile(extra) }
        #expect(LUTLibrary.message(.limit) == "Своих LUT не больше 12 — удалите ненужный")
        #expect(LUTLibrary.Failure.limit.localizedDescription == "Своих LUT не больше 12 — удалите ненужный")
        #expect(library.names.count == 12 && !library.names.contains("one-more"))
        #expect(sandbox.stored().count == 12)
    }
}

@Test func atTheLimitTheSameNameStillReplaces() throws {
    try with { sandbox in
        let library = sandbox.library()
        for i in 1...12 { _ = try library.importFile(try sandbox.write("lut\(i).cube", cube())) }
        _ = try library.importFile(try sandbox.write("lut7.cube", cube(inverted: true)))
        #expect(library.names.count == 12)
        #expect(library.load("lut7") == (try CubeFile.parse(cube(inverted: true))))
    }
}

@Test func deletingMakesRoomForAnotherFile() throws {
    try with { sandbox in
        let library = sandbox.library()
        for i in 1...12 { _ = try library.importFile(try sandbox.write("lut\(i).cube", cube())) }
        library.delete("lut3")
        #expect(library.names.count == 11 && !library.names.contains("lut3"))
        #expect(try library.importFile(try sandbox.write("fresh.cube", cube())) == "fresh")
        #expect(library.names.count == 12)
    }
}

// MARK: - Плохие файлы

@Test func brokenFilesAreRefusedWithTheParserText() throws {
    try with { sandbox in
        let library = sandbox.library()
        let oneD = try sandbox.write("one-d.cube", "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n")
        #expect(throws: LUTLibrary.Failure.cube(.notThreeD)) { try library.importFile(oneD) }
        #expect(LUTLibrary.message(.cube(.notThreeD)) == "Нужен 3D LUT в формате .cube")
        // не хватает строк: сообщение — то же, что у разбора
        let short = try sandbox.write("short.cube", cube().split(separator: "\n").dropLast().joined(separator: "\n"))
        #expect(throws: LUTLibrary.Failure.cube(.count(found: 7, expected: 8))) { try library.importFile(short) }
        #expect(LUTLibrary.message(.cube(.count(found: 7, expected: 8))) == CubeFile.message(.count(found: 7, expected: 8)))
        #expect(LUTLibrary.message(.cube(.count(found: 7, expected: 8))) == "В файле 7 строк данных, а нужно 8")
        // ничего не скопировано, и папка не создана ради отказа
        #expect(library.names.isEmpty)
        #expect(!FileManager.default.fileExists(atPath: sandbox.folder.path))
    }
}

@Test func missingFileIsUnreadable() throws {
    try with { sandbox in
        let library = sandbox.library()
        let ghost = sandbox.desktop.appendingPathComponent("ghost.cube")
        #expect(throws: LUTLibrary.Failure.unreadable) { try library.importFile(ghost) }
        // папка вместо файла — тоже не файл
        #expect(throws: LUTLibrary.Failure.unreadable) { try library.importFile(sandbox.desktop) }
        #expect(!LUTLibrary.message(.unreadable).isEmpty)
    }
}

@Test func hugeFileIsRefusedBeforeItIsRead() throws {
    try with { sandbox in
        let library = sandbox.library()
        // куб 65³ — около десяти мегабайт; тридцать три — это уже не LUT
        let big = try sandbox.write("big.cube", Data(count: LUTLibrary.maxFileBytes + 1))
        #expect(throws: LUTLibrary.Failure.cube(.notThreeD)) { try library.importFile(big) }
        #expect(library.names.isEmpty)
    }
}

@Test func parseErrorComesBeforeTheLimit() throws {
    // полная папка и плохой файл: сказать надо про файл — удалённое место ему не поможет
    try with { sandbox in
        let library = sandbox.library()
        for i in 1...12 { _ = try library.importFile(try sandbox.write("lut\(i).cube", cube())) }
        let bad = try sandbox.write("bad.cube", "LUT_1D_SIZE 2\n0 0 0\n1 1 1\n")
        #expect(throws: LUTLibrary.Failure.cube(.notThreeD)) { try library.importFile(bad) }
    }
}

@Test func latinOneTitleDoesNotBreakTheImport() throws {
    // TITLE в Latin-1 (байт 0xE9 — не UTF-8): строка названия пропускается, остальное читается
    try with { sandbox in
        let library = sandbox.library()
        var data = Data("TITLE \"Caf".utf8)
        data.append(0xE9)
        data.append(Data("\"\n".utf8))
        data.append(Data(cube().utf8))
        let name = try library.importFile(try sandbox.write("latin.cube", data))
        #expect(name == "latin")
        #expect(library.load("latin") == (try CubeFile.parse(cube())))
        // копия — те же байты, а не пересохранённый текст
        #expect(try Data(contentsOf: sandbox.folder.appendingPathComponent("latin.cube")) == data)
    }
}

@Test func windowsLineEndingsAndBOMAreFine() throws {
    try with { sandbox in
        let library = sandbox.library()
        var data = Data([0xEF, 0xBB, 0xBF])
        data.append(Data(cube().replacingOccurrences(of: "\n", with: "\r\n").utf8))
        _ = try library.importFile(try sandbox.write("crlf.cube", data))
        #expect(library.load("crlf") == (try CubeFile.parse(cube())))
    }
}

// MARK: - Удаление и чтение

@Test func deleteRemovesTheFileAndForgetsIt() throws {
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("a.cube", cube()))
        _ = try library.importFile(try sandbox.write("b.cube", cube(inverted: true)))
        #expect(library.load("a") != nil)
        library.delete("a")
        #expect(library.names == ["b"])
        #expect(library.load("a") == nil)
        #expect(sandbox.stored() == ["b.cube"])
        // нет такого — ничего не происходит
        library.delete("a")
        library.delete("")
        #expect(library.names == ["b"])
    }
}

@Test func nameCannotLeadOutOfTheFolder() throws {
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("inside.cube", cube()))
        // файл рядом с папкой библиотеки — до него не дотянуться ни чтением, ни удалением
        let outside = sandbox.root.appendingPathComponent("outside.cube")
        try Data(cube().utf8).write(to: outside)
        #expect(library.load("../outside") == nil)
        library.delete("../outside")
        #expect(FileManager.default.fileExists(atPath: outside.path))
        #expect(library.load("sub/inside") == nil)
        #expect(library.load("..") == nil && library.load(".") == nil && library.load("") == nil)
        library.delete("..")
        #expect(library.names == ["inside"])
    }
}

@Test func onlyCubeFilesAreListed() throws {
    try with { sandbox in
        let library = sandbox.library()
        try FileManager.default.createDirectory(at: sandbox.folder, withIntermediateDirectories: true)
        try Data(cube().utf8).write(to: sandbox.folder.appendingPathComponent("good.cube"))
        try Data(cube().utf8).write(to: sandbox.folder.appendingPathComponent("Upper.CUBE"))
        try Data("x".utf8).write(to: sandbox.folder.appendingPathComponent("notes.txt"))
        try Data(cube().utf8).write(to: sandbox.folder.appendingPathComponent(".hidden.cube"))
        try FileManager.default.createDirectory(at: sandbox.folder.appendingPathComponent("folder.cube"), withIntermediateDirectories: false)
        #expect(library.names == ["good", "Upper"])
    }
}

@Test func brokenFileInTheFolderLoadsAsNothing() throws {
    // файл положили в папку руками и испортили: он в списке (выбрать можно), а куба из него нет — картинка не меняется
    try with { sandbox in
        let library = sandbox.library()
        try FileManager.default.createDirectory(at: sandbox.folder, withIntermediateDirectories: true)
        try Data("not a cube".utf8).write(to: sandbox.folder.appendingPathComponent("junk.cube"))
        #expect(library.names == ["junk"])
        #expect(library.load("junk") == nil)
    }
}

// MARK: - Кеш по имени и дате

@Test func loadIsCachedByNameAndModificationDate() throws {
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("cached.cube", cube(inverted: false)))
        let file = sandbox.folder.appendingPathComponent("cached.cube")
        // дата — целые секунды: файловая система не обязана хранить доли секунды так же точно, как их отдаёт Date
        let stamp = Date(timeIntervalSinceReferenceDate: 800_000_000)
        func setDate(_ date: Date) throws {
            try FileManager.default.setAttributes([.modificationDate: date], ofItemAtPath: file.path)
        }
        try setDate(stamp)
        let first = try #require(library.load("cached"))
        #expect(first == (try CubeFile.parse(cube(inverted: false))))

        // подменили содержимое, дату вернули прежней: файл «не менялся» — берётся запомненный куб
        try Data(cube(inverted: true).utf8).write(to: file)
        try setDate(stamp)
        #expect(library.load("cached") == first)
        #expect(library.cachedLUT("cached") == first)

        // дата сдвинулась — файл прочитан заново, а быстрый вопрос «есть ли готовый» до чтения отвечает «нет»
        try setDate(stamp.addingTimeInterval(5))
        #expect(library.cachedLUT("cached") == nil)
        #expect(library.load("cached") == (try CubeFile.parse(cube(inverted: true))))
    }
}

@Test func cacheIsDroppedWithTheFile() throws {
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("gone.cube", cube()))
        #expect(library.load("gone") != nil)
        // файл убрали из-под библиотеки (Finder): запомненный куб не должен оживлять его
        try FileManager.default.removeItem(at: sandbox.folder.appendingPathComponent("gone.cube"))
        #expect(library.load("gone") == nil)
        // и вернули с тем же именем, но другим содержимым
        try Data(cube(inverted: true).utf8).write(to: sandbox.folder.appendingPathComponent("gone.cube"))
        #expect(library.load("gone") == (try CubeFile.parse(cube(inverted: true))))
    }
}

@Test func cacheKeepsOnlyAFewCubes() throws {
    try with { sandbox in
        let library = sandbox.library()
        for i in 1...6 { _ = try library.importFile(try sandbox.write("c\(i).cube", cube())) }
        for i in 1...6 { _ = library.load("c\(i)") }
        #expect(library.cachedCount <= LUTLibrary.cacheLimit)
        // выселенный читается заново и правильно
        #expect(library.load("c1") != nil)
    }
}

@Test func importSeedsTheCacheSoSelectionIsInstant() throws {
    // разбор при добавлении уже есть — второй раз тот же файл не разбирают
    try with { sandbox in
        let library = sandbox.library()
        _ = try library.importFile(try sandbox.write("seed.cube", cube()))
        #expect(library.cachedCount == 1)
    }
}

@Test func severalThreadsLoadingAtOnceGetTheSameCube() async throws {
    let sandbox = try Sandbox()
    defer { sandbox.remove() }
    let library = sandbox.library()
    for i in 1...4 { _ = try library.importFile(try sandbox.write("t\(i).cube", cube(inverted: i % 2 == 0))) }
    let expected = (try CubeFile.parse(cube(inverted: false)), try CubeFile.parse(cube(inverted: true)))
    await withTaskGroup(of: Bool.self) { group in
        for n in 0..<200 {
            group.addTask {
                let i = n % 4 + 1
                let got = library.load("t\(i)")
                return got == (i % 2 == 0 ? expected.1 : expected.0)
            }
        }
        for await ok in group { #expect(ok) }
    }
}
