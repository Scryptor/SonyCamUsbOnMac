import AppKit
import Foundation
import SwiftUI
import UniformTypeIdentifiers
import ZVCore

/// Свои LUT на Mac: папка `~/Library/Application Support/Sony Cam Viewer/LUTs/`. Вся работа с файлами — в `LUTLibrary`
/// (ZVCore, с тестами); здесь только то, что нужно экрану: список имён, за которым следят виды, и счётчик изменений.
@MainActor
final class LUTStore: ObservableObject {
    /// Имена по алфавиту.
    @Published private(set) var names: [String]
    /// Растёт при каждом добавлении и удалении: файл под тем же именем могли заменить, и поток кадров должен взять новый куб.
    private(set) var revision = 0

    private let library: LUTLibrary

    /// Рядом с эталоном наложения и настройками API.
    nonisolated static var folder: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sony Cam Viewer", isDirectory: true)
            .appendingPathComponent("LUTs", isDirectory: true)
    }

    init(folder: URL = LUTStore.folder) {
        library = LUTLibrary(folder: folder)
        names = library.names
    }

    /// Добавить файл `.cube`: проверка разбором, копия в папку, не больше 12. Возвращает имя — имя файла без расширения.
    /// Не вышло — `LUTLibrary.Failure` с текстом для окна (`localizedDescription`).
    func importFile(_ url: URL) throws -> String {
        // и при отказе список читается заново: папку тронули руками, а замена могла оборваться на середине
        defer {
            revision += 1
            refresh()
        }
        return try library.importFile(url)
    }

    /// Убрать свой LUT из папки.
    func delete(_ name: String) {
        library.delete(name)
        revision += 1
        refresh()
    }

    /// Куб по имени; разобран один раз и помнится, пока файл не менялся. Читает файл, если куба в памяти нет, — для тех, кто
    /// готов подождать (до секунды у куба 65³ в release); экрану — `cachedLUT` и `loadInBackground`.
    func lut(named name: String) -> ColorLUT? { library.load(name) }

    /// Куб из памяти, если он там есть и файл не менялся, — без чтения файла.
    func cachedLUT(named name: String) -> ColorLUT? { library.cachedLUT(name) }

    /// Куб, разобранный не на главном потоке: большой `.cube` не должен подвешивать окно.
    func loadInBackground(_ name: String) async -> ColorLUT? {
        let library = self.library
        return await Task.detached(priority: .userInitiated) { library.load(name) }.value
    }

    /// Список заново с диска: папку могли тронуть руками (Finder). Вызывается, когда меню «Монитор» открывается на LUT.
    func refresh() {
        let fresh = library.names
        if fresh != names { names = fresh }
    }
}

/// «Добавить LUT…»: окно выбора файла `.cube`; выбрали — он скопирован в папку программы и выбран.
@MainActor
func chooseLUTFile(controller: CameraController, screen: ScreenState) {
    let panel = NSOpenPanel()
    panel.title = "Свой LUT"
    panel.message = "3D LUT в формате .cube: он скопируется в папку программы"
    panel.prompt = "Добавить"
    // тип по расширению: в системе `.cube` может быть типом чужой программы (цветокоррекции) или «динамическим», если такой нет, —
    // окно в обоих случаях покажет только файлы `.cube`
    panel.allowedContentTypes = [UTType(filenameExtension: "cube")].compactMap { $0 }
    panel.allowsMultipleSelection = false
    panel.canChooseDirectories = false
    guard panel.runModal() == .OK, let url = panel.url else { return }
    addLUT(url, controller: controller, screen: screen)
}

/// Файл `.cube` — из окна выбора или брошенный на окно: добавить и сразу выбрать («Свой» и этот файл). Не вышло (не 3D LUT,
/// не те строки, уже 12) — причина в строке состояния, выбор не меняется.
@MainActor @discardableResult
func addLUT(_ url: URL, controller: CameraController, screen: ScreenState) -> Bool {
    do {
        let name = try screen.importLUT(url)
        controller.showStatus("LUT «\(name)» добавлен")
        return true
    } catch {
        controller.showStatus(error.localizedDescription)
        return false
    }
}

/// Файл `.cube` ли это — для перетаскивания на окно.
func isCubeFile(_ url: URL) -> Bool {
    url.isFileURL && url.pathExtension.lowercased() == "cube"
}
