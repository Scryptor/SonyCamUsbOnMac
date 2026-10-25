import SwiftUI
import ZVCore

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
        /// стоп-кадр сохранён — кадр улетает в ленту
        case frameGrabbed(UUID)
        /// снимок скачан — новая миниатюра в ленте
        case shotAdded(UUID)
        case recordingStarted, recordingStopped
        /// камера не ответила или снимок не удался — кнопка «мотает головой»
        case failed(Target)
    }

    let id = UUID()
    let kind: Kind
}

// MARK: - Подписка на события

/// Вызывает handle для каждого нового события по порядку; старые, бывшие до появления вида, не повторяет.
private struct FeedbackListener: ViewModifier {
    let controller: CameraController
    let handle: (FeedbackEvent) -> Void
    @State private var seen: UUID?

    func body(content: Content) -> some View {
        content
            .onAppear { seen = controller.feedback.last?.id }
            .onChange(of: controller.feedback.last?.id) { _, _ in
                let events = controller.feedback
                let start = events.lastIndex { $0.id == seen }.map { $0 + 1 } ?? 0
                for event in events[start...] { handle(event) }
                seen = events.last?.id
            }
    }
}

extension View {
    func onFeedback(_ controller: CameraController, perform handle: @escaping (FeedbackEvent) -> Void) -> some View {
        modifier(FeedbackListener(controller: controller, handle: handle))
    }

    /// Волна от кнопки, когда её действие принято, и «мотание», когда не удалось.
    func captureFeedback(_ controller: CameraController, _ target: FeedbackEvent.Target, color: Color) -> some View {
        modifier(CaptureFeedbackModifier(controller: controller, target: target, color: color))
    }

    /// Подпись слева от кнопки: что сейчас делает камера. Палец закрывает саму кнопку, а слева от неё видно.
    func pendingCaption(_ text: String?) -> some View {
        overlay(alignment: .leading) {
            if let text {
                Text(text)
                    .font(.callout.monospacedDigit())
                    .lineLimit(1)
                    .fixedSize()
                    .padding(.horizontal, 10)
                    .padding(.vertical, 5)
                    .background(.ultraThinMaterial, in: Capsule())
                    .fixedSize()
                    // рамка нулевой ширины у левого края кнопки: подпись уходит от неё влево
                    .frame(width: 0, alignment: .trailing)
                    .offset(x: -14)
                    .transition(.opacity)
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: text != nil)
    }
}

// MARK: - Кнопка

/// Круглая кнопка съёмки: пока нажата — вдавлена и окружена кольцом шире пальца.
struct CaptureButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.92 : 1)
            .overlay {
                Circle()
                    .strokeBorder(.white.opacity(0.6), lineWidth: 2)
                    .padding(-10)
                    .opacity(configuration.isPressed ? 1 : 0)
            }
            .contentShape(Circle())
            .animation(.spring(response: 0.25, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

private struct CaptureFeedbackModifier: ViewModifier {
    let controller: CameraController
    let target: FeedbackEvent.Target
    let color: Color
    @State private var waves: [UUID] = []
    @State private var shakes = 0

    func body(content: Content) -> some View {
        content
            .background {
                ZStack {
                    ForEach(waves, id: \.self) { id in
                        Wave(color: color) { waves.removeAll { $0 == id } }
                    }
                }
            }
            .modifier(Shake(animatableData: CGFloat(shakes)))
            .onFeedback(controller) { event in
                switch event.kind {
                case .accepted(target): waves.append(event.id)
                case .failed(target): withAnimation(.linear(duration: 0.3)) { shakes += 1 }
                default: break
                }
            }
    }
}

/// Волна: кольцо от края кнопки расходится на 24 pt и гаснет за 450 мс.
private struct Wave: View {
    let color: Color
    let done: () -> Void
    @State private var progress: CGFloat = 0

    var body: some View {
        Circle()
            .strokeBorder(color, lineWidth: 2.5)
            .padding(-(10 + 24 * progress))
            .opacity(0.9 * (1 - progress))
            .allowsHitTesting(false)
            .onAppear {
                withAnimation(.easeOut(duration: 0.45)) { progress = 1 } completion: { done() }
            }
    }
}

/// Сдвиг ±6 pt трижды за одну единицу animatableData.
private struct Shake: GeometryEffect {
    var animatableData: CGFloat

    func effectValue(size: CGSize) -> ProjectionTransform {
        ProjectionTransform(CGAffineTransform(translationX: 6 * sin(animatableData * .pi * 6), y: 0))
    }
}

/// Дуга ожидания вместо кольца кнопки: доля загрузки или, пока доли нет, крутится.
struct PendingArc: View {
    let fraction: Double?
    let color: Color
    var diameter: CGFloat = 60
    var lineWidth: CGFloat = 4

    var body: some View {
        ZStack {
            Circle().stroke(color.opacity(0.25), lineWidth: lineWidth)
            if let fraction {
                Circle()
                    .trim(from: 0, to: max(fraction, 0.02))
                    .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                    .animation(.easeOut(duration: 0.15), value: fraction)
            } else {
                TimelineView(.animation) { context in
                    let turn = context.date.timeIntervalSinceReferenceDate.truncatingRemainder(dividingBy: 0.9) / 0.9
                    Circle()
                        .trim(from: 0, to: 0.28)
                        .stroke(color, style: StrokeStyle(lineWidth: lineWidth, lineCap: .round))
                        .rotationEffect(.degrees(turn * 360))
                }
            }
        }
        .frame(width: diameter, height: diameter)
    }
}

// MARK: - Поверх кадра

/// Шторка в момент спуска (камера сработала) и вспышка стоп-кадра — поверх всего экрана, касаний не ловит.
struct CaptureFlash: View {
    let controller: CameraController
    @State private var blink = 0.0
    @State private var flash = 0.0

    var body: some View {
        ZStack {
            Color.black.opacity(blink)
            Color.white.opacity(flash)
        }
        .allowsHitTesting(false)
        .onFeedback(controller) { event in
            switch event.kind {
            case .shutterFired:
                withAnimation(.linear(duration: 0.04)) { blink = 0.75 } completion: {
                    withAnimation(.easeOut(duration: 0.22)) { blink = 0 }
                }
            case .accepted(.grab):
                withAnimation(.linear(duration: 0.02)) { flash = 0.25 } completion: {
                    withAnimation(.easeOut(duration: 0.25)) { flash = 0 }
                }
            default:
                break
            }
        }
    }
}

/// Красная рамка вокруг видимой картинки, пока идёт запись, — как тэлли у мониторов. Запись началась — вспыхивает,
/// остановилась — тает.
struct TallyBorder: View {
    let recording: Bool
    let rect: CGRect
    @State private var opacity = 0.0

    var body: some View {
        Rectangle()
            .strokeBorder(Color.red, lineWidth: 3)
            .frame(width: max(rect.width, 0), height: max(rect.height, 0))
            .position(x: rect.midX, y: rect.midY)
            .opacity(opacity)
            .allowsHitTesting(false)
            .onAppear { opacity = recording ? 0.7 : 0 }
            .onChange(of: recording) { _, on in
                if on {
                    withAnimation(.easeOut(duration: 0.1)) { opacity = 1 } completion: {
                        withAnimation(.easeInOut(duration: 0.4)) { opacity = 0.7 }
                    }
                } else {
                    withAnimation(.easeOut(duration: 0.3)) { opacity = 0 }
                }
            }
    }
}

/// Первая миниатюра ленты снимков — куда летит стоп-кадр.
struct FirstShotSlotKey: PreferenceKey {
    static let defaultValue: Anchor<CGRect>? = nil
    static func reduce(value: inout Anchor<CGRect>?, nextValue: () -> Anchor<CGRect>?) {
        value = value ?? nextValue()
    }
}

/// Стоп-кадр сохранён — кадр уменьшается и улетает на место первой миниатюры ленты. Ленты не видно — улетает в
/// левый нижний угол и тает.
struct GrabFlightLayer: View {
    let controller: CameraController
    let screen: ScreenState
    /// Где кадр на экране без увеличения: стоп-кадр — всегда весь кадр.
    let source: CGRect
    let target: CGRect?
    let fallback: CGRect
    @State private var flights: [Flight] = []

    struct Flight: Identifiable {
        let id: UUID
        let image: CGImage
    }

    var body: some View {
        ZStack(alignment: .topLeading) {
            Color.clear
            ForEach(flights) { flight in
                FlyingFrame(image: flight.image, from: source, to: target ?? fallback, fades: target == nil) {
                    flights.removeAll { $0.id == flight.id }
                    if screen.flyingShot == flight.id { screen.flyingShot = nil }
                }
            }
        }
        .allowsHitTesting(false)
        .onFeedback(controller) { event in
            guard case let .frameGrabbed(id) = event.kind,
                  let image = controller.shots.first(where: { $0.id == id })?.thumbnail else { return }
            screen.flyingShot = id
            flights.append(Flight(id: id, image: image))
        }
    }
}

private struct FlyingFrame: View {
    let image: CGImage
    let from: CGRect
    let to: CGRect
    let fades: Bool
    let landed: () -> Void
    @State private var progress: CGFloat = 0

    var body: some View {
        let rect = CGRect(x: from.minX + (to.minX - from.minX) * progress, y: from.minY + (to.minY - from.minY) * progress,
                          width: from.width + (to.width - from.width) * progress,
                          height: from.height + (to.height - from.height) * progress)
        Image(decorative: image, scale: 1)
            .resizable()
            .aspectRatio(contentMode: .fill)
            .frame(width: rect.width, height: rect.height)
            .clipShape(RoundedRectangle(cornerRadius: 6 * progress))
            .overlay(RoundedRectangle(cornerRadius: 6 * progress).strokeBorder(.white, lineWidth: 2))
            .shadow(color: .black.opacity(0.5), radius: 12 * progress)
            .opacity(fades ? 1 - max(0, progress - 0.6) / 0.4 : 1)
            .position(x: rect.midX, y: rect.midY)
            .onAppear {
                withAnimation(.easeInOut(duration: 0.45)) { progress = 1 } completion: { landed() }
            }
    }
}
