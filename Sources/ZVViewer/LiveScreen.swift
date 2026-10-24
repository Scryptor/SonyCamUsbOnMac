import SwiftUI
import ZVCore

/// Основной экран: видео с камеры, статус, строка значений, кнопка записи и меню Fn поверх кадра.
struct LiveScreen: View {
    let controller: CameraController
    @State private var fnOpen = false
    @State private var allSettingsOpen = false
    @State private var selected: SettingID = .shutter
    @FocusState private var focused: Bool

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
                if fnOpen {
                    FnMenu(controller: controller, selected: $selected) {
                        fnOpen = false
                        allSettingsOpen = true
                    }
                } else if !allSettingsOpen {
                    InfoLine(controller: controller) { id in
                        if let id { selected = id }
                        fnOpen = true
                    }
                }
            }
            .padding(16)
            HStack {
                Spacer()
                RecordButton(controller: controller)
            }
            .padding(.trailing, 20)
            if allSettingsOpen {
                GeometryReader { geo in
                    AllSettingsPanel(controller: controller)
                        .frame(width: geo.size.width * 0.6, height: geo.size.height * 0.78)
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                }
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { closeOverlays() }
        .environment(\.colorScheme, .dark)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { focused = true }
        // F в любой раскладке: латинская f и русская а на той же клавише
        .onKeyPress(characters: CharacterSet(charactersIn: "fFаА")) { _ in
            fnOpen.toggle()
            return .handled
        }
        .onKeyPress(.escape) {
            guard fnOpen || allSettingsOpen else { return .ignored }
            closeOverlays()
            return .handled
        }
        .onKeyPress(.leftArrow) { step(-1) }
        .onKeyPress(.rightArrow) { step(1) }
    }

    private func step(_ offset: Int) -> KeyPress.Result {
        guard fnOpen else { return .ignored }
        controller.requestStep(selected, offset: offset)
        return .handled
    }

    private func closeOverlays() {
        fnOpen = false
        allSettingsOpen = false
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

/// Тонкая строка с основными значениями, как на дисплее камеры. Клик открывает меню Fn.
private struct InfoLine: View {
    let controller: CameraController
    /// nil — кнопка Fn, иначе — выбранная настройка.
    let onOpen: (SettingID?) -> Void

    var body: some View {
        HStack(spacing: 22) {
            ForEach(SettingCatalog.infoLine, id: \.self) { id in
                Button { onOpen(id) } label: {
                    VStack(spacing: 2) {
                        Text(id.shortTitle.uppercased())
                            .font(.caption2)
                            .foregroundStyle(.secondary)
                        Text(ValueFormatter.label(id, controller.property(id)))
                            .font(.headline.monospacedDigit())
                    }
                }
                .buttonStyle(.plain)
            }
            Button { onOpen(nil) } label: {
                Text("Fn")
                    .font(.caption.weight(.bold))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 3)
                    .background(.white.opacity(0.16), in: RoundedRectangle(cornerRadius: 5))
            }
            .buttonStyle(.plain)
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
