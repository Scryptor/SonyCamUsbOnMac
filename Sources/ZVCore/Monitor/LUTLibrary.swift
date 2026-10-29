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
    /// Больше этого файл не читается: куб 65³ — около десяти мегабайт, всё, что тяжелее, — не LUT.
    static let maxFileBytes = 32 << 20
    /// Сколько разобранных кубов держится в памяти: куб 65³ — несколько мегабайт.
    static let cacheLimit = 3

    public let folder: URL

    public init(folder: URL) {
        self.folder = folder
    }

    /// Имена своих LUT по алфавиту; папки нет или она пуста — пусто.
    public var names: [String] { entries().map(\.name) }

    /// Скопировать файл в папку и вернуть имя — имя файла без расширения. Порядок отказов: файл не читается, потом сам
    /// файл (разбор), потом лимит: полная папка и плохой файл — это «плохой файл», место ему не поможет.
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
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            // «Look» и «look» на диске Mac — один файл: прежний с другим регистром уходит, чтобы остался один и с новым именем
            for old in same where old.name != name {
                try FileManager.default.removeItem(at: old.url)
                forget(old.name)
            }
            try data.write(to: destination, options: .atomic)
        } catch {
            throw Failure.unsaved
        }
        remember(name, modified: Self.modified(destination), lut)
        return name
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

    /// Файлы `.cube` папки (обычные, не скрытые), по алфавиту.
    private func entries() -> [Entry] {
        let keys: [URLResourceKey] = [.isRegularFileKey]
        guard let urls = try? FileManager.default.contentsOfDirectory(at: folder, includingPropertiesForKeys: keys,
                                                                      options: [.skipsHiddenFiles]) else { return [] }
        return urls
            .filter { $0.pathExtension.lowercased() == "cube" && (try? $0.resourceValues(forKeys: Set(keys)))?.isRegularFile == true }
            .map { Entry(name: $0.deletingPathExtension().lastPathComponent, url: $0) }
            .sorted { Self.precedes($0.name, $1.name) }
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
        guard let values = try? url.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey]), values.isRegularFile == true,
              let size = values.fileSize else { throw Failure.unreadable }
        guard size <= maxFileBytes else { throw Failure.cube(.notThreeD) }
        guard let data = try? Data(contentsOf: url) else { throw Failure.unreadable }
        return data
    }

    private static func modified(_ url: URL) -> Date? {
        (try? url.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate
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
