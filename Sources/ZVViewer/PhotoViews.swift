import AppKit
import SwiftUI
import ZVCore

/// Справа по центру: «Фото / Видео» и под ним спуск (фото) или стоп-кадр и запись (видео).
/// Палец (курсор) закрывает кнопку — отклик вокруг неё: кольцо, волна, дуга ожидания, подпись слева.
struct RightColumn: View {
    let controller: CameraController

    var body: some View {
        VStack(spacing: 14) {
            ModeSwitch(controller: controller)
            if controller.mode == .photo {
                ShutterButton(controller: controller)
            } else {
                GrabButton(controller: controller)
                RecordButton(controller: controller)
            }
        }
    }
}

private struct ModeSwitch: View {
    let controller: CameraController

    var body: some View {
        let enabled = controller.isWritable(.exposureProgram) && !controller.isRecording
        VStack(spacing: 2) {
            ForEach(ShootingMode.allCases, id: \.self) { mode in
                let selected = controller.shootingMode == mode
                Button { controller.switchMode(mode) } label: {
                    Text(mode.title)
                        .font(.callout.weight(selected ? .bold : .regular))
                        .frame(width: 64, height: 26)
                        .foregroundStyle(selected ? Color.black : Color.white)
                        .background(selected ? Color.white : Color.clear, in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
            }
        }
        .padding(3)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
        .help(controller.isRecording ? "Во время записи режим не переключается" : "Фото, видео или замедленная и ускоренная съёмка (S&Q)")
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
                    Circle().strokeBorder(.white.opacity(0.9), lineWidth: 4)
                }
                Circle().fill(.white.opacity(busy ? 0.55 : 1)).frame(width: busy ? 42 : 48, height: busy ? 42 : 48)
            }
            .frame(width: 64, height: 64)
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
            Image(systemName: "camera.viewfinder")
                .font(.system(size: 18))
                .frame(width: 42, height: 42)
                .background(.ultraThinMaterial, in: Circle())
        }
        .buttonStyle(CaptureButtonStyle())
        .captureFeedback(controller, .grab, color: .white)
        .disabled(controller.frame == nil)
        .help("Стоп-кадр из видео (S) — запись не прерывается")
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
                    Circle().strokeBorder(.white.opacity(0.9), lineWidth: 4)
                }
                // круг ↔ квадрат — только когда камера подтвердила
                RoundedRectangle(cornerRadius: recording ? 6 : 23)
                    .fill(.red)
                    .frame(width: recording ? 26 : 46, height: recording ? 26 : 46)
            }
            .frame(width: 64, height: 64)
            .animation(.easeInOut(duration: 0.25), value: recording)
        }
        .buttonStyle(CaptureButtonStyle())
        .captureFeedback(controller, .record, color: .red)
        .pendingCaption(request.map { $0 == .starting ? "Запуск записи…" : "Остановка…" })
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

/// Последние снимки и стоп-кадры: клик — галерея на этом снимке, правый клик — Finder или «Просмотр».
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
