import SwiftUI
import ZVCore

/// Основной экран: видео с камеры, статус, строка значений и кнопка записи.
struct LiveScreen: View {
    let controller: CameraController

    var body: some View {
        ZStack {
            Color.black
            if let frame = controller.frame {
                Image(decorative: frame, scale: 1)
                    .resizable()
                    .aspectRatio(contentMode: .fit)
            } else {
                ProgressView().controlSize(.large)
            }
            VStack {
                HStack {
                    Spacer()
                    StatusPill(controller: controller)
                }
                Spacer()
                InfoLine(controller: controller)
            }
            .padding(16)
            HStack {
                Spacer()
                RecordButton(controller: controller)
            }
            .padding(.trailing, 20)
        }
        .environment(\.colorScheme, .dark)
    }
}

private struct StatusPill: View {
    let controller: CameraController

    var body: some View {
        HStack(spacing: 14) {
            HStack(spacing: 6) {
                Circle().fill(controller.phase == .ready ? .green : .orange).frame(width: 8, height: 8)
                Text("\(controller.deviceInfo?.model ?? "Камера") · USB")
            }
            Text(ValueFormatter.label(.exposureProgram, controller.property(.exposureProgram)))
            Text("Карта: \(ValueFormatter.label(.cardRemaining, controller.property(.cardRemaining)))")
            Text("Батарея: \(ValueFormatter.label(.battery, controller.property(.battery)))")
        }
        .font(.callout)
        .padding(.horizontal, 14)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
    }
}

/// Тонкая строка с основными значениями, как на дисплее камеры.
private struct InfoLine: View {
    let controller: CameraController

    var body: some View {
        HStack(spacing: 22) {
            ForEach(SettingCatalog.infoLine, id: \.self) { id in
                VStack(spacing: 2) {
                    Text(id.shortTitle.uppercased())
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                    Text(ValueFormatter.label(id, controller.property(id)))
                        .font(.headline.monospacedDigit())
                }
            }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .opacity(controller.phase == .ready ? 1 : 0.5)
    }
}

private struct RecordButton: View {
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
