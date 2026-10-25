import Foundation

/// Событие отклика на кнопки съёмки: экран показывает по нему волну, шторку, полёт кадра, «пружинку» новой
/// миниатюры или «мотание» кнопки. Действие с клавиатуры (пробел, S) даёт тот же отклик, что и щелчок.
struct FeedbackEvent: Identifiable, Equatable {
    enum Target: Equatable {
        case shutter, record, grab
    }

    enum Kind: Equatable {
        /// нажатие принято — волна от кнопки
        case accepted(Target)
        /// камера отработала спуск — шторка
        case shutterFired
        /// стоп-кадр сохранён — миниатюра в ленте после полёта кадра
        case frameGrabbed(UUID)
        /// снимок скачан — «пружинка» новой миниатюры
        case shotAdded(UUID)
        case recordingStarted, recordingStopped
        /// камера не ответила или снимок не удался — кнопка «мотает головой»
        case failed(Target)
    }

    let id = UUID()
    let kind: Kind
}
