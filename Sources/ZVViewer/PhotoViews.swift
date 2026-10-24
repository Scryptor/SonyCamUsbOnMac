import AppKit
import SwiftUI
import ZVCore

/// Справа по центру: «Фото / Видео» и под ним спуск (фото) или стоп-кадр и запись (видео).
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
        Button(action: controller.capturePhoto) {
            ZStack {
                Circle().strokeBorder(.white.opacity(0.9), lineWidth: 4).frame(width: 64, height: 64)
                Circle().fill(controller.isCapturing ? Color.gray : Color.white).frame(width: 48, height: 48)
                if controller.isCapturing { ProgressView().controlSize(.small) }
            }
        }
        .buttonStyle(.plain)
        .disabled(controller.phase != .ready || controller.isCapturing)
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
        .buttonStyle(.plain)
        .disabled(controller.frame == nil)
        .help("Стоп-кадр из видео (S) — запись не прерывается")
    }
}

struct RecordButton: View {
    let controller: CameraController

    var body: some View {
        Button(action: controller.toggleRecording) {
            ZStack {
                Circle().strokeBorder(.white.opacity(0.9), lineWidth: 4).frame(width: 64, height: 64)
                if controller.isRecording {
                    RoundedRectangle(cornerRadius: 6).fill(.red).frame(width: 26, height: 26)
                } else {
                    Circle().fill(.red).frame(width: 46, height: 46)
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(controller.phase != .ready)
        .opacity(controller.phase == .ready ? 1 : 0.4)
        .help(controller.isRecording ? "Остановить запись" : "Начать запись")
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

/// Последние снимки и стоп-кадры: клик — открыть поверх видео, правый клик — Finder или «Просмотр».
struct ShotsStrip: View {
    let controller: CameraController

    var body: some View {
        HStack(spacing: 5) {
            ForEach(Array(controller.shots.prefix(6).enumerated()), id: \.element.id) { index, shot in
                Button { controller.openShot(index) } label: {
                    Group {
                        if let thumbnail = shot.thumbnail {
                            Image(decorative: thumbnail, scale: 1).resizable().aspectRatio(contentMode: .fill)
                        } else {
                            Color.white.opacity(0.1)
                        }
                    }
                    .frame(width: 44, height: 44)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                }
                .buttonStyle(.plain)
                .help(shot.title)
                .contextMenu {
                    Button("Показать в Finder") { NSWorkspace.shared.activateFileViewerSelecting(shot.urls) }
                    if let first = shot.urls.first { Button("Открыть") { NSWorkspace.shared.open(first) } }
                }
            }
            if controller.shots.count > 6 {
                Text("+\(controller.shots.count - 6)").font(.caption).foregroundStyle(.secondary).padding(.horizontal, 4)
            }
        }
        .padding(5)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
    }
}

/// Просмотр снимка поверх видео: по таймеру после съёмки или до закрытия, если открыт из ленты.
struct ReviewOverlay: View {
    let review: CameraController.Review
    let onClose: () -> Void

    var body: some View {
        ZStack {
            Color.black
            if let image = review.image {
                Image(decorative: image, scale: 1).resizable().aspectRatio(contentMode: .fit)
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
                    if !review.detail.isEmpty { Text(review.detail).foregroundStyle(.secondary) }
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
                    Countdown(endsAt: endsAt).padding(.trailing, 20)
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onClose)
    }
}

private struct Countdown: View {
    let endsAt: Date

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { context in
            let left = max(0, endsAt.timeIntervalSince(context.date))
            ZStack {
                Circle().stroke(.white.opacity(0.25), lineWidth: 4)
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
