import AppKit
import SwiftUI

/// Колесо мыши/трекпад → шаги ±1. Работает, пока модификатор на экране.
struct ScrollWheelSteps: ViewModifier {
    let onStep: (Int) -> Void
    @State private var monitor = ScrollMonitor()

    func body(content: Content) -> some View {
        content
            .onAppear { monitor.start(onStep) }
            .onDisappear { monitor.stop() }
    }
}

extension View {
    func onScrollWheelStep(_ onStep: @escaping (Int) -> Void) -> some View {
        modifier(ScrollWheelSteps(onStep: onStep))
    }
}

@MainActor
final class ScrollMonitor {
    private var token: Any?
    private var accumulated: CGFloat = 0
    /// Сколько единиц прокрутки составляет один шаг.
    private let threshold: CGFloat = 20

    func start(_ onStep: @escaping (Int) -> Void) {
        stop()
        token = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self else { return event }
            var delta = event.scrollingDeltaY
            if !event.hasPreciseScrollingDeltas { delta *= 20 }
            accumulated += delta
            while accumulated >= threshold {
                accumulated -= threshold
                onStep(-1)
            }
            while accumulated <= -threshold {
                accumulated += threshold
                onStep(1)
            }
            return event
        }
    }

    func stop() {
        if let token { NSEvent.removeMonitor(token) }
        token = nil
        accumulated = 0
    }
}
