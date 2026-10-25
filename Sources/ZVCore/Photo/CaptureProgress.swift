import Foundation

/// Где сейчас снимок: камера фокусируется (полунажатие), отрабатывает спуск или отдаёт файл.
public enum CapturePhase: Equatable, Sendable {
    case focusing
    case exposing
    /// Файл `file` из `files` качается: принято `received` из `expected` байт (nil — размер неизвестен).
    case downloading(file: Int, files: Int, received: Int, expected: Int?)
}

/// Что показать на кнопке спуска, пока камера занята снимком.
public enum CaptureFeedback {
    /// Долю показываем у файлов от 1 МБ: мелкое качается за миг.
    public static let minProgressBytes = 1_048_576

    public static func caption(_ phase: CapturePhase) -> String {
        switch phase {
        case .focusing: return "Фокусировка…"
        case .exposing: return "Снимаю…"
        case let .downloading(file, files, _, _):
            guard let share = fraction(phase) else { return files > 1 ? "Загрузка \(file)/\(files)…" : "Загрузка…" }
            let percent = "\(Int((share * 100).rounded(.down))) %"
            return files > 1 ? "Загрузка \(file)/\(files) · \(percent)" : "Загрузка \(percent)"
        }
    }

    /// Доля для дуги на кнопке; nil — дуга крутится.
    public static func fraction(_ phase: CapturePhase) -> Double? {
        guard case let .downloading(_, _, received, expected) = phase, let expected, expected >= minProgressBytes else {
            return nil
        }
        return min(max(Double(received) / Double(expected), 0), 1)
    }
}
