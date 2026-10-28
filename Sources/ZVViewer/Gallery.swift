import AppKit
import ImageIO
import SwiftUI
import ZVCore

/// Галерея снимков и стоп-кадров сеанса поверх экрана: листание (← →, смахивание двумя пальцами, ‹ ›), лента
/// внизу, увеличение снимка (щипок, двойной щелчок) с догрузкой полного разрешения. Держит снимок по id: новый
/// снимок, пока галерея открыта, её не сбивает.
struct GalleryOverlay: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var model = GalleryModel()
    @Environment(\.displayScale) private var displayScale

    private var shots: [CameraController.Shot] { controller.shots }
    private var index: Int? { shots.firstIndex { $0.id == screen.galleryShot } }

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                Color.black
                if let index {
                    page(shots[index], size: geometry.size)
                        .id(shots[index].id)
                        .transition(.opacity)
                }
                if model.chrome {
                    chrome(size: geometry.size)
                        .transition(.opacity)
                }
            }
            .onAppear { model.viewSize = geometry.size }
            .onChange(of: geometry.size) { _, size in model.viewSize = size }
        }
        .animation(.easeInOut(duration: 0.15), value: screen.galleryShot)
        .animation(.easeInOut(duration: 0.2), value: model.chrome)
        .onAppear {
            model.prepare(shots, around: index)
            model.startScroll(page: step) { event in
                // своё окно, не панель отладки и не лента внизу: там прокрутка — их собственная
                guard event.window?.title == LiveScreen.windowTitle, !screen.debugOpen else { return false }
                return !(model.chrome && event.locationInWindow.y < 120)
            }
        }
        .onDisappear {
            model.stopScroll()
            screen.galleryZoomed = false
        }
        .onChange(of: screen.galleryShot) { _, _ in
            model.zoom = .identity
            model.pinchBase = nil
            model.dragBase = nil
            model.prepare(shots, around: index)
        }
        // стрелки на клавиатуре листают, только пока снимок не увеличен
        .onChange(of: model.zoom.isZoomed) { _, zoomed in screen.galleryZoomed = zoomed }
        .onChange(of: shots.count) { _, _ in
            // снимок, открытый в галерее, пропал — закрываем
            if index == nil { screen.galleryShot = nil }
        }
    }

    /// Соседний снимок: +1 — старше (дальше по ленте), −1 — новее.
    private func step(_ offset: Int) {
        guard let index, !model.zoom.isZoomed else { return }
        let next = min(max(index + offset, 0), shots.count - 1)
        if next != index { screen.galleryShot = shots[next].id }
    }

    // MARK: - Снимок

    private func page(_ shot: CameraController.Shot, size: CGSize) -> some View {
        let image = model.image(for: shot)
        let frame = image.map { CGSize(width: $0.width, height: $0.height) } ?? shot.pixelSize ?? CGSize(width: 3, height: 2)
        let rect = model.zoom.imageRect(view: size, frame: frame)
        return ZStack {
            if let image {
                Image(decorative: image, scale: 1)
                    .resizable()
                    .interpolation(.high)
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)
            } else {
                ProgressView().controlSize(.large)
            }
        }
        .frame(width: size.width, height: size.height)
        .contentShape(Rectangle())
        // двойной щелчок — вписать ↔ 2,5× в этой точке; одиночный — спрятать или вернуть верх и ленту
        .onTapGesture(count: 2) { location in
            withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) {
                model.zoom = model.zoom.isZoomed ? .identity
                    : ViewZoom.identity.zoomed(by: 2.5, around: location, view: size, frame: frame, elastic: false)
            }
        }
        .onTapGesture { model.chrome.toggle() }
        .simultaneousGesture(
            MagnifyGesture()
                .onChanged { value in
                    if model.pinchStart != value.startLocation {
                        model.pinchStart = value.startLocation
                        model.pinchBase = model.zoom
                    }
                    let base = model.pinchBase ?? model.zoom
                    model.zoom = base.zoomed(by: value.magnification, around: value.startLocation, view: size, frame: frame,
                                             elastic: true)
                }
                .onEnded { _ in
                    model.pinchBase = nil
                    model.pinchStart = nil
                    withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { model.zoom = model.zoom.settled(view: size, frame: frame) }
                }
        )
        .simultaneousGesture(
            DragGesture(minimumDistance: 2)
                .onChanged { value in
                    guard model.zoom.isZoomed else { return }
                    if model.dragStart != value.startLocation {
                        model.dragStart = value.startLocation
                        model.dragBase = model.zoom
                    }
                    let base = model.dragBase ?? model.zoom
                    model.zoom = base.panned(by: value.translation, view: size, frame: frame)
                }
                .onEnded { _ in
                    model.dragBase = nil
                    model.dragStart = nil
                }
        )
        .onChange(of: model.zoom) { _, zoom in
            model.imageSize = frame
            // увеличили сильнее, чем позволяет картинка под экран, — догружаем полное разрешение
            if let image, zoom.imageRect(view: size, frame: frame).width * displayScale > CGFloat(image.width) * 1.05 {
                model.loadFull(shot)
            }
        }
        .onAppear { model.imageSize = frame }
    }

    // MARK: - Верх, подпись, лента

    private func chrome(size: CGSize) -> some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                GalleryButton(systemImage: "xmark", help: "Закрыть (Esc)") { screen.galleryShot = nil }
                Spacer()
                if let index {
                    Text("\(index + 1) из \(shots.count)").font(.headline.monospacedDigit())
                }
                Spacer()
                if let index {
                    let urls = shots[index].urls
                    // эталон для наложения на живой кадр — повторить композицию
                    Button("Наложить на кадр") {
                        controller.takeReference(from: shots[index])
                        screen.showOverlay()
                        screen.galleryShot = nil
                    }
                    .help("Снимок полупрозрачно поверх живого кадра: повторить сцену. Прозрачность и «спрятать» — на панели наложения")
                    Button("Показать в Finder") { NSWorkspace.shared.activateFileViewerSelecting(urls) }
                    Button("Открыть") { if let first = urls.first { NSWorkspace.shared.open(first) } }
                    Button { controller.deleteShot(shots[index].id, screen: screen) } label: {
                        Text("Удалить").foregroundStyle(.red)
                    }
                    .keyboardShortcut(.delete, modifiers: .command)
                    .help("В Корзину (⌘⌫); ещё 6 секунд снимок можно вернуть кнопкой внизу. Копия на карте камеры остаётся")
                }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(.ultraThinMaterial)
            Spacer()
            HStack {
                if let index, index > 0 {
                    GalleryButton(systemImage: "chevron.left", help: "Новее (←)") { step(-1) }
                }
                Spacer()
                if let index, index < shots.count - 1 {
                    GalleryButton(systemImage: "chevron.right", help: "Старше (→)") { step(1) }
                }
            }
            .padding(.horizontal, 16)
            .opacity(model.zoom.isZoomed ? 0 : 1)
            Spacer()
            VStack(spacing: 8) {
                if let index { GalleryCaption(shot: shots[index]) }
                filmstrip(width: size.width)
            }
            .padding(.vertical, 10)
            .frame(maxWidth: .infinity)
            .background(.ultraThinMaterial)
        }
    }

    /// Лента всех снимков сеанса; короткая — по центру.
    private func filmstrip(width: CGFloat) -> some View {
        ScrollViewReader { proxy in
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 6) {
                    ForEach(shots) { shot in
                        Button { screen.galleryShot = shot.id } label: {
                            Group {
                                if let thumbnail = shot.thumbnail {
                                    Image(decorative: thumbnail, scale: 1).resizable().aspectRatio(contentMode: .fill)
                                } else {
                                    Color.white.opacity(0.1)
                                }
                            }
                            .frame(width: 56, height: 56)
                            .clipShape(RoundedRectangle(cornerRadius: 6))
                            .overlay(RoundedRectangle(cornerRadius: 6)
                                .strokeBorder(.white, lineWidth: shot.id == screen.galleryShot ? 2 : 0))
                            .opacity(shot.id == screen.galleryShot ? 1 : 0.6)
                        }
                        .buttonStyle(.plain)
                        .help(shot.title)
                        .id(shot.id)
                    }
                }
                .padding(.horizontal, 16)
                .frame(minWidth: width)
            }
            .frame(height: 60)
            .onAppear { proxy.scrollTo(screen.galleryShot, anchor: .center) }
            .onChange(of: screen.galleryShot) { _, id in
                withAnimation(.easeInOut(duration: 0.2)) { proxy.scrollTo(id, anchor: .center) }
            }
        }
    }
}

extension CameraController {
    /// Удаляет снимок; галерея, открытая на нём, переходит на снимок, вставший на его место (удалили самый старый — на
    /// соседний новее), а удалили последний — закрывается.
    @discardableResult
    func deleteShot(_ id: UUID, screen: ScreenState) -> Bool {
        guard let index = shots.firstIndex(where: { $0.id == id }) else { return false }
        let count = shots.count
        guard deleteShot(id) else { return false }
        if screen.galleryShot == id {
            screen.galleryShot = ShotDeletion.neighbor(removing: index, count: count).map { shots[$0].id }
        }
        return true
    }

    /// «Вернуть»: снимок — на своё место в ленте, галерея — на нём (и снова открывается, если закрылась).
    func undoDeleteShot(screen: ScreenState) {
        guard let id = undoDeleteShot() else { return }
        if screen.galleryShot != nil { screen.galleryShot = id } else { screen.openGallery(id) }
    }
}

/// «… — в Корзине · Вернуть»: 6 с после удаления снимка, внизу по центру — над галереей, а если она закрылась (удалили
/// последний снимок), то на основном экране.
struct DeletedShotBanner: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        if let deleted = controller.deletedShot {
            HStack(spacing: 12) {
                Image(systemName: "trash").foregroundStyle(.secondary)
                Text("\(deleted.shot.title) — в Корзине").lineLimit(1).truncationMode(.middle)
                Button("Вернуть") { controller.undoDeleteShot(screen: screen) }
                    .keyboardShortcut("z", modifiers: .command)
                    .help("Вернуть снимок из Корзины (⌘Z)")
            }
            .font(.callout)
            .padding(.leading, 14)
            .padding(.trailing, 8)
            .padding(.vertical, 7)
            .background(.ultraThinMaterial, in: Capsule())
            .frame(maxWidth: 520)
            .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
    }
}

/// Подпись снимка: имя, что это (стоп-кадр, снимок, RAW + JPEG) и размер, сколько весит, когда снят.
private struct GalleryCaption: View {
    let shot: CameraController.Shot

    var body: some View {
        HStack(spacing: 14) {
            Text(shot.title).fontWeight(.semibold)
            Text(details).foregroundStyle(.secondary).monospacedDigit()
        }
        .lineLimit(1)
        .font(.callout)
    }

    private var details: String {
        let size = shot.pixelSize.map { "\(Int($0.width))×\(Int($0.height))" }
        let kinds = Set(shot.urls.map { $0.pathExtension.lowercased() })
        let kind: String
        if shot.isFrameGrab {
            kind = ["Стоп-кадр", size].compactMap { $0 }.joined(separator: " ")
        } else if kinds.contains("arw"), kinds.contains("jpg") || kinds.contains("jpeg") {
            kind = ["RAW + JPEG", size].compactMap { $0 }.joined(separator: " · ")
        } else {
            kind = ["Снимок", size].compactMap { $0 }.joined(separator: " ")
        }
        let bytes = shot.urls.reduce(0) { total, url in
            total + ((try? FileManager.default.attributesOfItem(atPath: url.path)[.size] as? Int) ?? 0)
        }
        let megabytes = String(format: "%.1f МБ", Double(bytes) / 1_048_576).replacingOccurrences(of: ".", with: ",")
        return [kind, megabytes, shot.takenAt.formatted(date: .omitted, time: .standard)].joined(separator: " · ")
    }
}

private struct GalleryButton: View {
    let systemImage: String
    let help: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(.system(size: 15, weight: .semibold))
                .frame(width: 36, height: 36)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .help(help)
    }
}

/// Что на экране галереи: увеличение, видны ли верх и лента, картинки снимков (текущий и соседние — под экран,
/// текущий при сильном увеличении — в полном разрешении).
@MainActor
@Observable
final class GalleryModel {
    var zoom = ViewZoom.identity
    var chrome = true
    var pinchBase: ViewZoom?
    var dragBase: ViewZoom?
    /// С какой точки начался жест: другая — значит, это уже новый жест.
    var pinchStart: CGPoint?
    var dragStart: CGPoint?
    /// Для прокрутки двумя пальцами: где и какого размера снимок на экране.
    var viewSize = CGSize.zero
    var imageSize = CGSize(width: 3, height: 2)
    private var display: [UUID: CGImage] = [:]
    private var full: (id: UUID, image: CGImage)?
    @ObservationIgnored private var loading: [UUID: Task<Void, Never>] = [:]
    @ObservationIgnored private var loadingFull: (id: UUID, task: Task<Void, Never>)?
    /// Какие снимки сейчас нужны (текущий и соседние) и какой открыт.
    @ObservationIgnored private var wanted: Set<UUID> = []
    @ObservationIgnored private var current: UUID?
    @ObservationIgnored private var scrollToken: Any?
    @ObservationIgnored private var swipe: CGFloat = 0
    @ObservationIgnored private var swiped = false

    /// Картинка под экран — до 2560 точек: на большее хватит догрузки полного разрешения.
    static let displayPixels = 2560

    func image(for shot: CameraController.Shot) -> CGImage? {
        if let full, full.id == shot.id { return full.image }
        return display[shot.id] ?? shot.thumbnail
    }

    /// Текущий и соседние снимки — под экран; остальное из памяти долой.
    func prepare(_ shots: [CameraController.Shot], around index: Int?) {
        guard let index, shots.indices.contains(index) else { return }
        let near = shots[max(0, index - 1)...min(shots.count - 1, index + 1)]
        wanted = Set(near.map(\.id))
        current = shots[index].id
        display = display.filter { wanted.contains($0.key) }
        // ушли со снимков — их картинки уже не нужны
        for (id, task) in loading where !wanted.contains(id) {
            task.cancel()
            loading[id] = nil
        }
        if let full, full.id != current { self.full = nil }
        if let loadingFull, loadingFull.id != current {
            loadingFull.task.cancel()
            self.loadingFull = nil
        }
        // открытый — первым, соседи — потом
        for shot in near.sorted(by: { a, _ in a.id == current }) where display[shot.id] == nil && loading[shot.id] == nil {
            let urls = shot.urls
            let id = shot.id
            let priority: TaskPriority = id == current ? .userInitiated : .utility
            loading[id] = Task {
                let image = await Self.decode(urls, maxPixels: Self.displayPixels, priority: priority)
                guard !Task.isCancelled else { return }
                loading[id] = nil
                if let image, wanted.contains(id) { display[id] = image }
            }
        }
    }

    func loadFull(_ shot: CameraController.Shot) {
        guard full?.id != shot.id, loadingFull?.id != shot.id, shot.id == current else { return }
        let urls = shot.urls
        let id = shot.id
        loadingFull = (id, Task {
            let image = await Self.decode(urls, maxPixels: nil, priority: .userInitiated)
            guard !Task.isCancelled else { return }
            if loadingFull?.id == id { loadingFull = nil }
            if let image, id == current, image.width > display[id]?.width ?? 0 { full = (id, image) }
        })
    }

    /// Смахивание двумя пальцами по трекпаду — соседний снимок (один за жест); при увеличении прокрутка двигает
    /// снимок. Колесо мыши — листает.
    /// accepts — наше ли это событие; чужое (другое окно, панель отладки, лента) проходит дальше нетронутым.
    func startScroll(page: @escaping (Int) -> Void, accepts: @escaping (NSEvent) -> Bool) {
        stopScroll()
        scrollToken = NSEvent.addLocalMonitorForEvents(matching: .scrollWheel) { [weak self] event in
            guard let self, accepts(event) else { return event }
            if self.zoom.isZoomed {
                self.zoom = self.zoom.panned(by: CGSize(width: event.scrollingDeltaX, height: event.scrollingDeltaY),
                                             view: self.viewSize, frame: self.imageSize)
                return nil
            }
            if event.hasPreciseScrollingDeltas {
                if event.phase == .began {
                    self.swipe = 0
                    self.swiped = false
                }
                if event.momentumPhase.isEmpty {
                    self.swipe += event.scrollingDeltaX
                    if !self.swiped, abs(self.swipe) > 60 {
                        self.swiped = true
                        page(self.swipe < 0 ? 1 : -1)
                    }
                }
                if event.phase == .ended || event.phase == .cancelled {
                    self.swipe = 0
                    self.swiped = false
                }
            } else if event.scrollingDeltaY != 0 {
                page(event.scrollingDeltaY < 0 ? 1 : -1)
            }
            return nil
        }
    }

    func stopScroll() {
        if let scrollToken { NSEvent.removeMonitor(scrollToken) }
        scrollToken = nil
    }

    /// JPEG раньше RAW: быстрее. maxPixels nil — полный размер, с поворотом по EXIF.
    nonisolated static func decode(_ urls: [URL], maxPixels: Int?, priority: TaskPriority) async -> CGImage? {
        await Task.detached(priority: priority) {
            func isJPEG(_ url: URL) -> Bool { ["jpg", "jpeg"].contains(url.pathExtension.lowercased()) }
            for url in urls.sorted(by: { isJPEG($0) && !isJPEG($1) }) {
                guard let source = CGImageSourceCreateWithURL(url as CFURL, nil) else { continue }
                var options: [CFString: Any] = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                                                kCGImageSourceCreateThumbnailWithTransform: true]
                if let maxPixels { options[kCGImageSourceThumbnailMaxPixelSize] = maxPixels }
                if let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) { return image }
            }
            return nil
        }.value
    }
}
