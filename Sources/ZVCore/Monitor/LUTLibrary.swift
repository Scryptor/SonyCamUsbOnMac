import Foundation

/// Папка своих LUT: файлы `.cube`, которые пользователь добавил программе. Работает над адресом папки, поэтому проверяется
/// на временной (в программе это `~/Library/Application Support/Sony Cam Viewer/LUTs/`).
///
/// - Добавление: файл читается, разбирается `CubeFile` (плохой не копируется) и кладётся в папку под своим именем без
///   расширения; то же имя заменяет старый файл. Файлов не больше 12.
/// - Имена — имена файлов без `.cube`, по алфавиту (числа по значению: `look2` раньше `look10`).
/// - Чтение: куб разбирается один раз и помнится по имени и дате изменения файла — пока файл не менялся, повторный запрос
///   отдаёт тот же куб, без чтения.
///
/// Ссылка (symlink) на файл — это файл: при добавлении читается то, на что она указывает (имя — имя ссылки, как её выбрали), а
/// ссылка, положенная в папку руками, есть в списке; «Удалить» убирает ссылку, а не её цель.
///
/// Замена не теряет прежнее, пока новое не на диске: сначала новое пишется во временный скрытый файл рядом, потом он
/// атомарно ложится на место, и только потом правится написание имени. На диске Mac «Look» и «look» — один файл, и запись поверх
/// него оставляет прежнее написание; тот же файл переименовывается (на диске с регистром это два файла — лишний убирается).
/// Упала программа между записью и переименованием — временный файл остался: когда библиотека создаётся, такие убираются.
///
/// К файлу ведёт только имя из списка: «../чужой» или «a/b» не совпадают ни с одним файлом папки, так что и прочитать, и
/// удалить за пределами папки нельзя. Вызывать можно из любого потока: изменяемо только запомненное, оно под замком.
public final class LUTLibrary: @unchecked Sendable {
    /// Почему файл не добавился. Текст для окна — `LUTLibrary.message`.
    public enum Failure: Error, Equatable, LocalizedError {
        /// Файл не открылся или это не файл.
        case unreadable
        /// Это не `.cube` или он испорчен (`CubeFile` говорит, чем).
        case cube(CubeFile.Failure)
        /// Своих LUT уже 12.
        case limit
        /// Копия в папку программы не записалась.
        case unsaved

        public var errorDescription: String? { LUTLibrary.message(self) }
    }

    /// Больше своих LUT не бывает; тринадцатый отказывается.
    public static let limit = 12
    /// Больше этого файл не читается: куб 65³ текстом — от семи до двенадцати мегабайт, всё, что тяжелее, — не LUT. Тот же
    /// предел, 16 МБ, у программы для Android (там больший куб не помещается в память планшета): один файл подходит обеим.
    static let maxFileBytes = 16 << 20
    /// Сколько разобранных кубов держится в памяти: куб 65³ — несколько мегабайт.
    static let cacheLimit = 3
    /// Временный файл записи: `.import-<случайное>.tmp`, скрытый, чтобы список его не видел.
    private static let tempPrefix = ".import-"
    private static let tempSuffix = ".tmp"

    public let folder: URL
    /// Как новое содержимое пишется во временный файл; тесты подменяют, чтобы проверить, что не записалось — прежнее на месте.
    private let write: @Sendable (Data, URL) throws -> Void

    public convenience init(folder: URL) {
        self.init(folder: folder, write: { try $0.write(to: $1) })
    }

    init(folder: URL, write: @escaping @Sendable (Data, URL) throws -> Void) {
        self.folder = folder
        self.write = write
        Self.sweepLeftovers(in: folder)
    }

    /// Убрать временные файлы `.import-*.tmp`, которые оставила оборванная запись (процесс убили между записью и
    /// переименованием): никто их не ждёт, а до 16 МБ каждый лежали бы в папке программы вечно. Вызывается, когда библиотека
    /// создана, — в программе она одна и создаётся до первого добавления, так что чужой записи, идущей сейчас, тут нет. Чужое не
    /// трогает: настоящие файлы, скрытые файлы с другими именами и папки остаются; ссылка с таким именем убирается сама, а то,
    /// на что она ведёт, остаётся. Папки нет — ничего не делается (и она не создаётся).
    private static func sweepLeftovers(in folder: URL) {
        // скрытые файлы тоже: без `.skipsHiddenFiles`
        guard let urls = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: [.isDirectoryKey],
                                                                      options: []) else { return }
        for url in urls {
            let name = url.lastPathComponent
            guard name.hasPrefix(tempPrefix), name.hasSuffix(tempSuffix) else { continue }
            if (try? url.resourceValues(forKeys: [.isDirectoryKey]))?.isDirectory == true { continue }
            try? FileManager.default.removeItem(at: url)
        }
    }

    /// Имена своих LUT по алфавиту; папки нет или она пуста — пусто.
    public var names: [String] { entries().map(\.name) }

    /// Скопировать файл в папку и вернуть имя — имя файла без расширения, как оно легло на диск (замена под другим написанием,
    /// которую не удалось переименовать, оставляет прежнее). Порядок отказов: файл не читается, потом сам файл (разбор), потом
    /// лимит: полная папка и плохой файл — это «плохой файл», место ему не поможет. Не записалось — в папке ничего не изменилось.
    @discardableResult
    public func importFile(_ url: URL) throws -> String {
        let data = try Self.read(url)
        // не бросающий разбор UTF-8: Latin-1 в TITLE (или в комментарии) не должен ронять файл — такие строки пропускаются
        let lut: ColorLUT
        do {
            lut = try CubeFile.parse(String(decoding: data, as: UTF8.self))
        } catch let failure as CubeFile.Failure {
            throw Failure.cube(failure)
        }
        let name = Self.baseName(of: url)
        let existing = entries()
        let same = existing.filter { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        if same.isEmpty, existing.count >= Self.limit { throw Failure.limit }

        let destination = folder.appendingPathComponent(name + ".cube")
        // сначала новое — во временный скрытый файл (его не видно в списке): не записалось — прежнее не тронуто
        let temporary = folder.appendingPathComponent(Self.tempPrefix + UUID().uuidString + Self.tempSuffix)
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try write(data, temporary)
            // на место — одним переименованием: прежний файл под этим именем заменяется целиком, а не наполовину
            guard Self.move(temporary, to: destination) else { throw Failure.unsaved }
        } catch {
            try? FileManager.default.removeItem(at: temporary)
            throw Failure.unsaved
        }
        // Новое уже на диске, так что дальше — только написание имени. Диск Mac: «Look.cube» остался с прежним написанием —
        // тот же файл переименовывается. Диск с регистром: «Look.cube» — другой файл, он уходит.
        let twins = entries().filter { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        for old in twins where old.name != name {
            if twins.count > 1 {
                try? FileManager.default.removeItem(at: old.url)
            } else {
                _ = Self.move(old.url, to: destination)
            }
            forget(old.name)
        }
        // имя — как оно легло на диск: если переименовать не вышло, это прежнее написание, и список с выбором не расходятся
        let stored = entries().first { $0.name.caseInsensitiveCompare(name) == .orderedSame }
        remember(stored?.name ?? name, modified: Self.modified(stored?.url ?? destination), lut)
        return stored?.name ?? name
    }

    /// Убрать свой LUT из папки. Такого нет — ничего не происходит.
    public func delete(_ name: String) {
        guard let entry = entries().first(where: { $0.name == name }) else { return }
        try? FileManager.default.removeItem(at: entry.url)
        forget(name)
    }

    /// Куб по имени; nil — такого файла нет или он не разбирается (положили в папку руками и испортили). Разбор — один раз:
    /// пока дата файла прежняя, отдаётся запомненный куб, тот же массив чисел.
    public func load(_ name: String) -> ColorLUT? {
        guard let entry = entries().first(where: { $0.name == name }) else {
            forget(name)
            return nil
        }
        let modified = Self.modified(entry.url)
        if let cached = cached(name, modified: modified) { return cached }
        guard let data = try? Self.read(entry.url),
              let lut = try? CubeFile.parse(String(decoding: data, as: UTF8.self)) else {
            forget(name)
            return nil
        }
        remember(name, modified: modified, lut)
        return lut
    }

    /// Запомненный куб по имени и дате файла, не читая его: для тех, кому нельзя ждать разбора (экран). nil — не запомнен
    /// или файл с тех пор менялся.
    public func cachedLUT(_ name: String) -> ColorLUT? {
        guard let entry = entries().first(where: { $0.name == name }) else { return nil }
        return cached(name, modified: Self.modified(entry.url))
    }

    /// Текст для окна: что не так и как это поправить.
    public static func message(_ failure: Failure) -> String {
        switch failure {
        case .unreadable: return "Не удалось прочитать файл"
        case .cube(let reason): return CubeFile.message(reason)
        case .limit: return "Своих LUT не больше \(limit) — удалите ненужный"
        case .unsaved: return "Не удалось сохранить LUT в папке программы"
        }
    }

    // MARK: - Папка

    private struct Entry {
        let name: String
        let url: URL
    }

    /// Файлы `.cube` папки (обычные или ссылки на обычные, не скрытые), по алфавиту.
    private func entries() -> [Entry] {
        guard let urls = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: nil,
                                                                      options: [.skipsHiddenFiles]) else { return [] }
        return urls
            .filter { $0.pathExtension.lowercased() == "cube" && Self.isRegularFile($0) }
            .map { Entry(name: $0.deletingPathExtension().lastPathComponent, url: $0) }
            .sorted { Self.precedes($0.name, $1.name) }
    }

    /// Обычный файл — или ссылка на него: у самой ссылки `isRegularFile` ложно, так что смотрим на то, куда она ведёт. Ссылка в
    /// никуда и ссылка на папку — не файл.
    private static func isRegularFile(_ url: URL) -> Bool {
        (try? url.resolvingSymlinksInPath().resourceValues(forKeys: [.isRegularFileKey]))?.isRegularFile == true
    }

    /// Переименовать одним действием, поверх существующего — атомарно; то же имя в другом регистре на диске Mac — смена написания.
    private static func move(_ from: URL, to destination: URL) -> Bool {
        rename(from.path, destination.path) == 0
    }

    /// По алфавиту без учёта регистра, числа по значению; одинаковые без учёта регистра (на диске с регистром) — по буквам.
    private static func precedes(_ a: String, _ b: String) -> Bool {
        switch a.compare(b, options: [.caseInsensitive, .numeric]) {
        case .orderedAscending: return true
        case .orderedDescending: return false
        case .orderedSame: return a < b
        }
    }

    /// Имя нового файла: имя исходного без расширения, без ведущих точек (скрытый файл в списке не показался бы). Совсем пустое —
    /// «LUT».
    static func baseName(of url: URL) -> String {
        let base = String(url.deletingPathExtension().lastPathComponent.drop { $0 == "." || $0 == " " })
        return base.isEmpty ? "LUT" : base
    }

    /// Содержимое файла; не обычный файл, не читается или тяжелее предела — отказ. Тяжёлый — не `.cube` (отказ как у чужого файла).
    private static func read(_ url: URL) throws -> Data {
        // ссылка на файл — это файл: проверяется и читается то, на что она указывает
        let url = url.resolvingSymlinksInPath()
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]), values.isRegularFile == true,
              let size = values.fileSize else { throw Failure.unreadable }
        guard size <= maxFileBytes else { throw Failure.cube(.notThreeD) }
        guard let data = try? Data(contentsOf: url) else { throw Failure.unreadable }
        return data
    }

    /// Дата изменения файла; у ссылки — файла, на который она ведёт: правка цели сбрасывает запомненный куб.
    private static func modified(_ url: URL) -> Date? {
        (try? url.resolvingSymlinksInPath().resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
    }

    // MARK: - Запомненные кубы

    private struct Cached {
        let name: String
        let modified: Date?
        let lut: ColorLUT
    }

    private let lock = NSLock()
    /// Последние обращения — в конце; выселяется начало.
    private var recent: [Cached] = []

    /// Сколько кубов запомнено — для проверки предела.
    var cachedCount: Int {
        lock.lock()
        defer { lock.unlock() }
        return recent.count
    }

    private func cached(_ name: String, modified: Date?) -> ColorLUT? {
        lock.lock()
        defer { lock.unlock() }
        guard let index = recent.firstIndex(where: { $0.name == name }) else { return nil }
        // дату не узнали или она сменилась — файл считается новым
        guard let modified, recent[index].modified == modified else {
            recent.remove(at: index)
            return nil
        }
        let hit = recent.remove(at: index)
        recent.append(hit)
        return hit.lut
    }

    private func remember(_ name: String, modified: Date?, _ lut: ColorLUT) {
        lock.lock()
        defer { lock.unlock() }
        recent.removeAll { $0.name == name }
        recent.append(Cached(name: name, modified: modified, lut: lut))
        if recent.count > Self.cacheLimit { recent.removeFirst(recent.count - Self.cacheLimit) }
    }

    private func forget(_ name: String) {
        lock.lock()
        defer { lock.unlock() }
        recent.removeAll { $0.name == name }
    }
}
