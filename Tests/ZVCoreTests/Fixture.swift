import Foundation

/// Реальные дампы с камеры пользователя (ZV-E10, прошивка 2.00), снятые пробной утилитой.
enum Fixture {
    struct Missing: Error { let name: String }

    static func data(_ name: String) throws -> Data {
        guard let url = Bundle.module.url(forResource: name, withExtension: "bin", subdirectory: "Fixtures") else {
            throw Missing(name: name)
        }
        return try Data(contentsOf: url)
    }
}
