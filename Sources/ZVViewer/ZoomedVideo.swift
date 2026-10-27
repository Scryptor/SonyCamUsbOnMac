import AppKit
import CoreImage
import CoreImage.CIFilterBuiltins
import SwiftUI
import ZVCore

/// Видео с цифровым увеличением: щипок на трекпаде — вокруг курсора, перетаскивание мышью — сдвиг (прокрутка двумя
/// пальцами — через ZoomScroll). Камера об увеличении не знает: стоп-кадр и снимок — всегда весь кадр.
struct ZoomableVideo: View {
    let screen: ScreenState
    let frame: CGImage
    let size: CGSize
    let dimmed: Bool
    /// Зебра, false color и пикинг — растр поверх видимой части кадра, едет вместе с ним.
    var overlay: AssistOverlay?
    /// Куда сообщать, какая часть кадра видна: растр помощи считается по ней.
    var assistInput: AssistInput?
    /// Эталонный кадр поверх живого — повторить композицию — и с какой непрозрачностью.
    var reference: CGImage?
    var referenceOpacity = 0.0
    @State private var pinchBase: ViewZoom?
    @State private var panBase: ViewZoom?
    /// С какой точки начался жест: прерванный жест не доходит до onEnded, и новый узнаётся по другой точке.
    @State private var pinchStart: CGPoint?
    @State private var panStart: CGPoint?
    @State private var sharp = ZoomPatch()
    /// До этого момента идёт анимация увеличения: заплатка встала бы сразу в конечное место — прячем её.
    @State private var animatingUntil = Date.distantPast

    private var frameSize: CGSize { CGSize(width: frame.width, height: frame.height) }

    var body: some View {
        let rect = screen.zoom.imageRect(view: size, frame: frameSize)
        ZStack {
            Image(decorative: frame, scale: 1)
                .resizable()
                .interpolation(screen.zoom.isZoomed ? .high : .medium)
                .frame(width: rect.width, height: rect.height)
                .position(x: rect.midX, y: rect.midY)
            // поверх — та же часть кадра, пересчитанная бикубически: во время жеста едет вместе с картинкой
            if screen.zoom.isZoomed, let patch = sharp.patch {
                let area = CGRect(x: rect.minX + patch.region.minX * rect.width, y: rect.minY + patch.region.minY * rect.height,
                                  width: patch.region.width * rect.width, height: patch.region.height * rect.height)
                Image(decorative: patch.image, scale: 1)
                    .resizable()
                    .frame(width: area.width, height: area.height)
                    .position(x: area.midX, y: area.midY)
            }
            if let reference, referenceOpacity > 0 {
                // заполняет кадр, лишнее обрезано по краю кадра
                let fill = ReferenceFrame.fillRect(image: CGSize(width: reference.width, height: reference.height), in: rect)
                Image(decorative: reference, scale: 1)
                    .resizable()
                    .interpolation(.medium)
                    .frame(width: fill.width, height: fill.height)
                    .position(x: fill.midX, y: fill.midY)
                    .opacity(referenceOpacity)
                    .mask {
                        Rectangle().frame(width: rect.width, height: rect.height).position(x: rect.midX, y: rect.midY)
                    }
            }
            if let overlay {
                let area = CGRect(x: rect.minX + overlay.region.minX * rect.width, y: rect.minY + overlay.region.minY * rect.height,
                                  width: overlay.region.width * rect.width, height: overlay.region.height * rect.height)
                Image(decorative: overlay.image, scale: 1)
                    .resizable()
                    .interpolation(.medium)
                    .frame(width: area.width, height: area.height)
                    .position(x: area.midX, y: area.midY)
            }
        }
        .opacity(dimmed ? 0.55 : 1)
        .frame(width: size.width, height: size.height)
        .onChange(of: ObjectIdentifier(frame)) { _, _ in refreshPatch() }
        .onChange(of: screen.zoom, initial: true) { _, _ in reportView() }
        .onChange(of: size) { _, _ in reportView() }
        .onChange(of: frameSize) { _, _ in reportView() }
        .onChange(of: size) { _, _ in refreshPatch() }
        .onChange(of: screen.zoom) { _, _ in
            // во время жеста заплатка едет с картинкой, пересчёт — с новым кадром или по окончании жеста;
            // ⌘=, ⌘−, ✕ и пружина после щипка анимируют картинку — заплатку прячем, пока анимация не кончится
            guard pinchBase == nil, panBase == nil else { return }
            animatingUntil = Date().addingTimeInterval(0.4)
            sharp.hide()
            Task {
                try? await Task.sleep(for: .milliseconds(420))
                refreshPatch()
            }
        }
        .frame(width: size.width, height: size.height)
            .clipped()
            .contentShape(Rectangle())
            .gesture(
                MagnifyGesture()
                    .onChanged { value in
                        if pinchStart != value.startLocation {
                            pinchStart = value.startLocation
                            pinchBase = screen.zoom
                        }
                        let base = pinchBase ?? screen.zoom
                        screen.zoom = base.zoomed(by: value.magnification, around: value.startLocation, view: size,
                                                  frame: frameSize, elastic: true)
                    }
                    .onEnded { _ in
                        pinchBase = nil
                        pinchStart = nil
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                            screen.zoom = screen.zoom.settled(view: size, frame: frameSize)
                        }
                    }
            )
            .simultaneousGesture(
                DragGesture(minimumDistance: 3)
                    .onChanged { value in
                        guard screen.zoom.isZoomed else { return }
                        if panStart != value.startLocation {
                            panStart = value.startLocation
                            panBase = screen.zoom
                        }
                        let base = panBase ?? screen.zoom
                        screen.zoom = base.panned(by: value.translation, view: size, frame: frameSize)
                    }
                    .onEnded { _ in
                        panBase = nil
                        panStart = nil
                        refreshPatch()
                    }
            )
    }

    /// Видимая часть кадра — для растра помощи в кадре.
    private func reportView() {
        assistInput?.setView(visible: screen.zoom.visibleRegion(view: size, frame: frameSize), factor: screen.zoom.factor)
    }

    private func refreshPatch() {
        guard Date() >= animatingUntil else { return }
        sharp.update(frame: frame, zoom: screen.zoom, view: size)
    }
}

/// Видимая часть увеличенного кадра, пересчитанная Core Image бикубически (Catmull-Rom: B = 0, C = 0,5) в размер
/// экрана в точках: заметно чётче обычного растяжения в 10–20 раз. Считается в фоне; пока считается, новые запросы
/// схлопываются в один. ZV_ZOOM_PATCH=0 — без неё, для сравнения.
@MainActor
@Observable
final class ZoomPatch {
    struct Patch {
        let image: CGImage
        /// Какая часть кадра (доли).
        let region: CGRect
    }

    private(set) var patch: Patch?
    @ObservationIgnored private var busy = false
    @ObservationIgnored private var pending: (frame: CGImage, region: CGRect, size: CGSize)?
    private nonisolated static let context = CIContext(options: [.cacheIntermediates: false])
    private static let enabled = ProcessInfo.processInfo.environment["ZV_ZOOM_PATCH"] != "0"

    /// Убрать заплатку до следующего пересчёта.
    func hide() {
        patch = nil
        pending = nil
    }

    func update(frame: CGImage, zoom: ViewZoom, view: CGSize) {
        guard zoom.isZoomed, Self.enabled else {
            patch = nil
            pending = nil
            return
        }
        let frameSize = CGSize(width: frame.width, height: frame.height)
        let rect = zoom.imageRect(view: view, frame: frameSize)
        // с запасом: при сдвиге во время жеста края не открываются сразу
        let visible = zoom.visibleRegion(view: view, frame: frameSize)
        let region = visible.insetBy(dx: -visible.width * 0.1, dy: -visible.height * 0.1)
            .intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        guard !region.isEmpty else { return }
        pending = (frame, region, CGSize(width: region.width * rect.width, height: region.height * rect.height))
        guard !busy else { return }
        busy = true
        Task {
            while let job = pending {
                pending = nil
                if let rendered = await Self.render(job.frame, region: job.region, size: job.size) { patch = rendered }
            }
            busy = false
        }
    }

    nonisolated private static func render(_ frame: CGImage, region: CGRect, size: CGSize) async -> Patch? {
        await Task.detached(priority: .userInitiated) {
            let w = CGFloat(frame.width), h = CGFloat(frame.height)
            // по целым точкам кадра; у Core Image начало координат — внизу слева
            let crop = CGRect(x: region.minX * w, y: (1 - region.maxY) * h, width: region.width * w, height: region.height * h)
                .integral.intersection(CGRect(x: 0, y: 0, width: w, height: h))
            guard crop.width >= 2, crop.height >= 2, size.width >= 1, size.height >= 1 else { return nil }
            let scaleY = size.height / crop.height
            let scaleX = size.width / crop.width
            let filter = CIFilter.bicubicScaleTransform()
            // края кадра продолжены: бикубическая выборка у края не тянет прозрачное
            filter.inputImage = CIImage(cgImage: frame).clampedToExtent()
                .transformed(by: CGAffineTransform(translationX: -crop.minX, y: -crop.minY))
            filter.scale = Float(scaleY)
            filter.aspectRatio = Float(scaleX / scaleY)
            filter.parameterB = 0
            filter.parameterC = 0.5
            guard let output = filter.outputImage else { return nil }
            let extent = CGRect(x: 0, y: 0, width: (crop.width * scaleX).rounded(), height: (crop.height * scaleY).rounded())
            guard let image = context.createCGImage(output, from: extent) else { return nil }
            let actual = CGRect(x: crop.minX / w, y: 1 - crop.maxY / h, width: crop.width / w, height: crop.height / h)
            return Patch(image: image, region: actual)
        }.value
    }
}

/// Прокрутка двумя пальцами двигает увеличенную картинку. Меню Fn и «Монитор», шкала фокуса, панель настроек и галерея
/// прокручивают своё.
@MainActor
final class ZoomScroll {
    var viewSize = CGSize.zero
    private var token: Any?

    func start(screen: ScreenState, controller: CameraController) {
        stop()
        token = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self, screen.zoom.isZoomed, screen.galleryShot == nil, !screen.fnOpen, !screen.assistOpen,
                  !screen.allSettingsOpen, !screen.debugOpen, controller.review == nil, !ScrollMonitor.claims(event),
                  event.window?.title == LiveScreen.windowTitle,
                  event.hasPreciseScrollingDeltas, let frame = controller.frame else { return event }
            screen.zoom = screen.zoom.panned(by: CGSize(width: event.scrollingDeltaX, height: event.scrollingDeltaY),
                                             view: self.viewSize, frame: CGSize(width: frame.width, height: frame.height))
            return nil
        }
    }

    func stop() {
        if let token { NSEvent.removeMonitor(token) }
        token = nil
    }
}

/// Пока картинка увеличена — в правом нижнем углу: мини-карта (весь кадр, видимая часть в рамке, остальное
/// притемнено), во сколько раз увеличено и ✕. Щелчок и перетаскивание по мини-карте сдвигают видимую часть.
/// Видна и при скрытых элементах: увеличенная картинка — не весь кадр.
struct ZoomBadge: View {
    let screen: ScreenState
    let frame: CGImage
    let view: CGSize

    var body: some View {
        let frameSize = CGSize(width: frame.width, height: frame.height)
        let width: CGFloat = 84
        let height = width * frameSize.height / max(frameSize.width, 1)
        let region = screen.zoom.visibleRegion(view: view, frame: frameSize)
        let visible = CGRect(x: region.minX * width, y: region.minY * height,
                             width: region.width * width, height: region.height * height)
        VStack(spacing: 6) {
            ZStack(alignment: .topLeading) {
                Image(decorative: frame, scale: 1).resizable().frame(width: width, height: height)
                Path { path in
                    path.addRect(CGRect(x: 0, y: 0, width: width, height: height))
                    path.addRect(visible)
                }
                .fill(.black.opacity(0.55), style: FillStyle(eoFill: true))
                Rectangle()
                    .strokeBorder(.white, lineWidth: 1.5)
                    .frame(width: visible.width, height: visible.height)
                    .offset(x: visible.minX, y: visible.minY)
            }
            .frame(width: width, height: height)
            .clipShape(RoundedRectangle(cornerRadius: 4))
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        screen.zoom = screen.zoom.centered(at: CGPoint(x: value.location.x / width, y: value.location.y / height),
                                                           view: view, frame: frameSize)
                    }
            )
            HStack(spacing: 4) {
                Text(screen.zoom.label).font(.headline.monospacedDigit())
                Spacer(minLength: 0)
                Button {
                    withAnimation(.spring(response: 0.25, dampingFraction: 0.9)) { screen.zoom = .identity }
                } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 11, weight: .bold))
                        .frame(width: 24, height: 24)
                        .background(.white.opacity(0.18), in: Circle())
                }
                .buttonStyle(.plain)
                .help("Отменить увеличение (⌘0)")
                .accessibilityLabel("Отменить увеличение")
            }
        }
        .padding(6)
        .frame(width: 96)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}
