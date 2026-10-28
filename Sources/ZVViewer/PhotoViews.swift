import AppKit
import SwiftUI
import ZVCore

/// Справа по центру: переключатель «Фото / Видео / S&Q» и под ним спуск (фото) или стоп-кадр и запись (видео).
/// Палец (курсор) закрывает кнопку — отклик вокруг неё: кольцо, волна, дуга ожидания, подпись слева.
struct RightColumn: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        VStack(spacing: 14) {
            // зум — над переключателем режима, как рычажок зума камеры над кнопками
            if controller.zoomAvailable { ZoomRocker(controller: controller) }
            ModeSwitch(controller: controller)
            if controller.touchFocusSupported { TouchFocusButton(controller: controller, screen: screen) }
            if controller.mode == .photo {
                ShutterButton(controller: controller)
            } else {
                GrabButton(controller: controller)
                RecordButton(controller: controller)
            }
        }
    }
}

/// «Фото / Видео / S&Q» — значки в капсуле, как кнопки верхней панели: у выбранного белый круг, под капсулой — его
/// название.
private struct ModeSwitch: View {
    let controller: CameraController

    var body: some View {
        let enabled = controller.isWritable(.exposureProgram) && !controller.isRecording
        VStack(spacing: 5) {
            VStack(spacing: 4) {
                ForEach(ShootingMode.allCases, id: \.self) { mode in
                    let selected = controller.shootingMode == mode
                    Button { controller.switchMode(mode) } label: {
                        ModeGlyph(mode: mode, color: selected ? .black : .white)
                            .frame(width: 34, height: 34)
                            .background(selected ? Color.white : Color.clear, in: Circle())
                            .contentShape(Circle())
                    }
                    .buttonStyle(.plain)
                    .help(controller.isRecording ? "Во время записи режим не переключается" : mode.title)
                    .accessibilityLabel(mode.title)
                }
            }
            .padding(4)
            .background(.ultraThinMaterial, in: Capsule())
            Text(controller.shootingMode.title)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.8))
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

/// Значок режима съёмки в квадрате 52×52, как значки верхней панели: фото — корпус камеры с объективом, видео — камера
/// с треугольником-объективом, S&Q — буквами: своего значка у камеры для него нет.
private struct ModeGlyph: View {
    let mode: ShootingMode
    let color: Color

    var body: some View {
        if mode == .slowQuick {
            Text("S&Q")
                .font(.system(size: 10, weight: .bold))
                .foregroundStyle(color)
        } else {
            Canvas { context, size in
                let scale = size.width / 52 * 1.25
                context.translateBy(x: size.width / 2 - 26 * scale, y: size.height / 2 - 26 * scale)
                context.scaleBy(x: scale, y: scale)
                let shading = GraphicsContext.Shading.color(color)
                let line = StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round)
                if mode == .photo {
                    context.stroke(Path(roundedRect: CGRect(x: 14, y: 20, width: 24, height: 15), cornerRadius: 3),
                                   with: shading, style: line)
                    // видоискатель — «горб» над корпусом
                    context.fill(Path(roundedRect: CGRect(x: 19.5, y: 16.5, width: 6.5, height: 4), cornerRadius: 1.2),
                                 with: shading)
                    context.stroke(Path(ellipseIn: CGRect(x: 21.2, y: 22.7, width: 9.6, height: 9.6)), with: shading,
                                   style: line)
                } else {
                    context.stroke(Path(roundedRect: CGRect(x: 13, y: 19.5, width: 18, height: 13), cornerRadius: 3),
                                   with: shading, style: line)
                    var lens = Path()
                    lens.addLines([CGPoint(x: 31.5, y: 24.5), CGPoint(x: 38.5, y: 20.5), CGPoint(x: 38.5, y: 31.5),
                                   CGPoint(x: 31.5, y: 27.5)])
                    lens.closeSubpath()
                    context.stroke(lens, with: shading, style: line)
                }
            }
            .accessibilityHidden(true)
        }
    }
}

private struct ShutterButton: View {
    let controller: CameraController

    var body: some View {
        let busy = controller.isCapturing
        Button(action: controller.capturePhoto) {
            ZStack {
                // пока камера занята снимком — вместо кольца дуга: доля загрузки или вращение
                if busy {
                    PendingArc(fraction: controller.capturePhase.flatMap(CaptureFeedback.fraction), color: .white)
                } else {
                    Circle().strokeBorder(.white.opacity(0.9), lineWidth: 3.5)
                }
                Circle().fill(.white.opacity(busy ? 0.55 : 1)).frame(width: busy ? 35 : 40, height: busy ? 35 : 40)
            }
            // пользователь попросил кнопки съёмки поменьше: было 64
            .frame(width: 54, height: 54)
            .animation(.easeInOut(duration: 0.2), value: busy)
        }
        .buttonStyle(CaptureButtonStyle())
        .captureFeedback(controller, .shutter, color: .white)
        .pendingCaption(busy ? controller.capturePhase.map(CaptureFeedback.caption) : nil)
        .disabled(controller.phase != .ready || busy)
        .help("Снимок (пробел)")
    }
}

private struct GrabButton: View {
    let controller: CameraController

    var body: some View {
        Button(action: controller.grabFrame) {
            FilmFrameIcon()
                .frame(width: 42, height: 42)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(CaptureButtonStyle())
        .captureFeedback(controller, .grab, color: .white)
        .disabled(controller.frame == nil)
        .help("Стоп-кадр из видео (S) — запись не прерывается")
    }
}

/// Кадр киноплёнки — «кадр из видео»: рамка и перфорация сверху и снизу; рамка видоискателя читалась как «навести
/// фокус». Числа — в единицах 28 × 26, как у макета значка (и на Android).
private struct FilmFrameIcon: View {
    var body: some View {
        Canvas { context, size in
            let sx = size.width / 28, sy = size.height / 26
            context.stroke(Path(roundedRect: CGRect(x: 1.5 * sx, y: 1.5 * sy, width: 25 * sx, height: 23 * sy),
                                cornerRadius: 3 * sx), with: .foreground, lineWidth: 2.3 * sx)
            for y in [6.5, 19.5] {
                var line = Path()
                line.move(to: CGPoint(x: 1.5 * sx, y: y * sy))
                line.addLine(to: CGPoint(x: 26.5 * sx, y: y * sy))
                context.stroke(line, with: .foreground, lineWidth: 2 * sx)
            }
            for x in [5.0, 10, 15, 20] {
                for y in [3.0, 21] {
                    context.fill(Path(CGRect(x: x * sx, y: y * sy, width: 2.4 * sx, height: 2 * sy)), with: .foreground)
                }
            }
        }
        .frame(width: 22, height: 20.5)
    }
}

struct RecordButton: View {
    let controller: CameraController

    var body: some View {
        let request = controller.recordingToggle.request
        let recording = controller.isRecording
        Button(action: controller.toggleRecording) {
            ZStack {
                // пока камера не подтвердила — дуга: красная — запускаю, белая — останавливаю
                if let request {
                    PendingArc(fraction: nil, color: request == .starting ? .red : .white)
                } else {
                    Circle().strokeBorder(.white.opacity(0.9), lineWidth: 3.5)
                }
                // круг ↔ квадрат — только когда камера подтвердила
                RoundedRectangle(cornerRadius: recording ? 5 : 19)
                    .fill(.red)
                    .frame(width: recording ? 22 : 38, height: recording ? 22 : 38)
            }
            .frame(width: 54, height: 54)
            .animation(.easeInOut(duration: 0.25), value: recording)
        }
        .buttonStyle(CaptureButtonStyle())
        .captureFeedback(controller, .record, color: .red)
        // пока камера не ответила — что ждём; отказала — почему, там же, где смотрели
        .pendingCaption(request.map { $0 == .starting ? "Запуск записи…" : "Остановка…" } ?? controller.recordingNotice)
        .disabled(controller.phase != .ready)
        .opacity(controller.phase == .ready ? 1 : 0.4)
        .help(recording ? "Остановить запись" : "Начать запись")
    }
}

/// «● REC 00:42» — видно всегда, пока идёт запись, даже когда элементы скрыты.
struct RecBadge: View {
    let startedAt: Date

    var body: some View {
        TimelineView(.periodic(from: startedAt, by: 1)) { context in
            let seconds = max(0, Int(context.date.timeIntervalSince(startedAt)))
            HStack(spacing: 6) {
                Circle().fill(.red).frame(width: 9, height: 9)
                Text(String(format: "REC %02d:%02d", seconds / 60, seconds % 60)).monospacedDigit()
            }
        }
    }
}

/// Последние снимки и стоп-кадры: клик — галерея на этом снимке, правый клик — Finder, «Просмотр» или Корзина.
struct ShotsStrip: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(controller.shots.prefix(6).enumerated()), id: \.element.id) { index, shot in
                Button { screen.openGallery(shot.id) } label: {
                    ShotThumb(shot: shot, flying: screen.flyingShot == shot.id)
                }
                .buttonStyle(.plain)
                .help(shot.title)
                .contextMenu {
                    Button("Показать в Finder") { NSWorkspace.shared.activateFileViewerSelecting(shot.urls) }
                    if let first = shot.urls.first { Button("Открыть") { NSWorkspace.shared.open(first) } }
                    Divider()
                    Button("В Корзину") { controller.deleteShot(shot.id, screen: screen) }
                }
                // сюда улетает стоп-кадр
                .anchorPreference(key: FirstShotSlotKey.self, value: .bounds) { index == 0 ? $0 : nil }
            }
            if controller.shots.count > 6 {
                // остальные — в галерее, с седьмого
                Button { screen.openGallery(controller.shots[6].id) } label: {
                    Text("+\(controller.shots.count - 6)").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help("Все снимки сеанса")
            }
        }
        .padding(5)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// Миниатюра в ленте. Новый снимок появляется «пружинкой» с белой рамкой; стоп-кадр прячется, пока к нему летит
/// кадр, и вспыхивает рамкой, когда долетел.
private struct ShotThumb: View {
    let shot: CameraController.Shot
    let flying: Bool
    @State private var scale: CGFloat = 1
    @State private var border = 0.0
    @State private var hidden = false

    var body: some View {
        Group {
            if let thumbnail = shot.thumbnail {
                Image(decorative: thumbnail, scale: 1).resizable().aspectRatio(contentMode: .fill)
            } else {
                Color.white.opacity(0.1)
            }
        }
        .frame(width: 44, height: 44)
        .clipShape(RoundedRectangle(cornerRadius: 6))
        .overlay(RoundedRectangle(cornerRadius: 6).strokeBorder(.white, lineWidth: 2).opacity(border))
        .scaleEffect(scale)
        .opacity(hidden || flying ? 0 : 1)
        .onAppear {
            // только что снятое — а не лента, вернувшаяся вместе с элементами
            guard Date().timeIntervalSince(shot.takenAt) < 1 else { return }
            if shot.isFrameGrab {
                // к миниатюре летит кадр: появится, когда долетит, или сама, если полёта не было
                hidden = true
                Task {
                    try? await Task.sleep(for: .milliseconds(650))
                    if hidden { reveal() }
                }
            } else {
                scale = 0.6
                border = 1
                withAnimation(.spring(response: 0.35, dampingFraction: 0.55)) { scale = 1 }
                withAnimation(.easeOut(duration: 0.6)) { border = 0 }
            }
        }
        .onChange(of: flying) { _, now in
            if !now { reveal() }
        }
    }

    private func reveal() {
        hidden = false
        border = 1
        withAnimation(.easeOut(duration: 0.6)) { border = 0 }
    }
}

/// Просмотр снимка поверх видео: по таймеру после съёмки или до закрытия, если открыт из ленты.
struct ReviewOverlay: View {
    let review: CameraController.Review
    /// Пока снимок качается — «Загрузка 42 %» вместо «Загружаю…».
    var progress: String?
    let onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black
            // скачанный снимок встаёт на место кадра с камеры плавно
            if let image = review.image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fit)
                    .id(ObjectIdentifier(image))
                    .transition(.opacity)
            } else {
                ProgressView().controlSize(.large)
            }
            VStack {
                Text("\(review.source) · клик или Esc — вернуться")
                    .font(.callout)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                Spacer()
                HStack(spacing: 14) {
                    Text(review.title).fontWeight(.semibold)
                    if let detail = progress ?? (review.detail.isEmpty ? nil : review.detail) {
                        Text(detail).foregroundStyle(.secondary).monospacedDigit()
                    }
                    if !review.urls.isEmpty {
                        Button("Показать в Finder") { NSWorkspace.shared.activateFileViewerSelecting(review.urls) }
                        Button("Открыть") { NSWorkspace.shared.open(review.urls[0]) }
                    }
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 8)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
            .padding(16)
            if let endsAt = review.endsAt {
                HStack {
                    Spacer()
                    Countdown(endsAt: endsAt, duration: review.duration ?? 3).padding(.trailing, 20)
                }
            }
        }
        .animation(.easeInOut(duration: 0.25), value: review.image.map(ObjectIdentifier.init))
        .contentShape(Rectangle())
        .onTapGesture(perform: onClose)
    }
}

/// Сколько ещё показывается снимок: кольцо плавно убывает, в середине — целые секунды.
private struct Countdown: View {
    let endsAt: Date
    let duration: TimeInterval

    var body: some View {
        TimelineView(.animation) { context in
            let left = max(0, endsAt.timeIntervalSince(context.date))
            ZStack {
                Circle().stroke(.white.opacity(0.25), lineWidth: 4)
                Circle()
                    .trim(from: 0, to: duration > 0 ? left / duration : 0)
                    .stroke(.white, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                    .rotationEffect(.degrees(-90))
                Text("\(Int(left.rounded(.up)))").font(.title2.weight(.bold)).monospacedDigit()
            }
            .frame(width: 64, height: 64)
        }
    }
}

extension ShootingMode {
    var title: String {
        switch self {
        case .photo: return "Фото"
        case .video: return "Видео"
        case .slowQuick: return "S&Q"
        }
    }
}
