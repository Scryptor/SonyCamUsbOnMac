import AppKit
import SwiftUI
import ZVCore

/// Колесо мыши/трекпад → шаги ±1, только когда курсор над этим элементом
/// (вертикальная прокрутка, без инерции — см. ScrollStepper).
struct ScrollWheelSteps: ViewModifier {
    let onStep: (Int) -> Void
    @State private var monitor = ScrollMonitor()

    func body(content: Content) -> some View {
        content
            .background(GeometryReader { geo in
                Color.clear
                    .onAppear { monitor.frame = geo.frame(in: .global) }
                    .onChange(of: geo.frame(in: .global)) { _, frame in monitor.frame = frame }
            })
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
    /// Рамка элемента в координатах окна (как у SwiftUI .global: начало слева сверху).
    var frame: CGRect = .zero
    private var token: Any?
    private var stepper = ScrollStepper()
    /// Кто сейчас ловит колесо: прокрутка над ним — его шаги, а не сдвиг увеличенной картинки.
    private static var live: [ScrollMonitor] = []

    /// Колесо над одним из элементов с шагами — шкала фокуса, меню.
    static func claims(_ event: NSEvent) -> Bool { live.contains { $0.contains(event) } }

    func start(_ onStep: @escaping (Int) -> Void) {
        stop()
        Self.live.append(self)
        token = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self else { return event }
            let steps = self.stepper.feed(dx: event.scrollingDeltaX, dy: event.scrollingDeltaY,
                                          precise: event.hasPreciseScrollingDeltas,
                                          momentum: !event.momentumPhase.isEmpty,
                                          inside: self.contains(event))
            if steps != 0 { onStep(steps) }
            return event
        }
    }

    func stop() {
        if let token { NSEvent.removeMonitor(token) }
        token = nil
        stepper = ScrollStepper()
        Self.live.removeAll { $0 === self }
    }

    private func contains(_ event: NSEvent) -> Bool {
        guard let view = event.window?.contentView else { return false }
        var point = view.convert(event.locationInWindow, from: nil)
        if !view.isFlipped { point.y = view.bounds.height - point.y }
        return frame.contains(point)
    }
}
