import SwiftUI
import ZVCore

/// Список камер: текущая, видимые, знакомые. Строка — имя, как подключена, состояние и что можно сделать:
/// «Подключить», «Сопрячь». Правый клик — переименовать (прямо в строке) или забыть. Пока список на экране, камеры
/// ищутся в сети раз в 10 с.
struct CameraList: View {
    let controller: CameraController
    /// «Сопрячь новую камеру…» под списком — там, где своей кнопки сопряжения нет.
    var showsPairNew = true
    @State private var editing: String?
    @State private var newName = ""
    @FocusState private var nameFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Камеры").font(.headline)
            ForEach(controller.cameras) { entry in
                row(entry)
            }
            if showsPairNew {
                Button("Сопрячь новую камеру…") { controller.startPairing() }
                    .disabled(controller.isPairing || controller.isRecording)
                    .help("Камера — в режиме Pairing: MENU → Network → PC Remote Function → Pairing")
            }
        }
        .onAppear { controller.cameraListAppeared() }
        .onDisappear { controller.cameraListDisappeared() }
    }

    private func row(_ entry: CameraEntry) -> some View {
        HStack(spacing: 10) {
            Circle().fill(dot(entry)).frame(width: 8, height: 8)
            VStack(alignment: .leading, spacing: 1) {
                if editing == entry.id {
                    TextField("Имя камеры", text: $newName)
                        .textFieldStyle(.roundedBorder)
                        .focused($nameFocused)
                        .onSubmit { finishRename(entry) }
                        .onExitCommand { editing = nil }
                        .onChange(of: nameFocused) { _, focused in if !focused { finishRename(entry) } }
                } else {
                    Text(entry.name).lineLimit(1)
                }
                Text("\(entry.detail) · \(entry.stateLabel)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            switch entry.action {
            case .connect?:
                Button("Подключить") { controller.selectCamera(entry.id) }
                    .disabled(controller.isRecording)
                    .help(controller.isRecording ? "Идёт запись — остановите её, чтобы переключить камеру" : "")
            case .pair?:
                Button("Сопрячь") { controller.pairCamera(entry.id) }
                    .disabled(controller.isRecording)
            case nil:
                EmptyView()
            }
        }
        .contentShape(Rectangle())
        .contextMenu {
            if entry.knownID != nil {
                Button("Переименовать…") { startRename(entry) }
                if entry.state != .current {
                    Button("Забыть", role: .destructive) { controller.forgetCamera(entry.id) }
                }
            }
        }
    }

    private func startRename(_ entry: CameraEntry) {
        newName = entry.name
        editing = entry.id
        nameFocused = true
    }

    /// Пустое имя не сохраняется — у камеры остаётся прежнее.
    private func finishRename(_ entry: CameraEntry) {
        guard editing == entry.id else { return }
        if let id = entry.knownID { controller.renameCamera(id, to: newName) }
        editing = nil
    }

    private func dot(_ entry: CameraEntry) -> Color {
        switch entry.state {
        case .current: return entry.connecting ? .orange : .green
        case .available: return .accentColor
        case .needsPairing: return .orange
        case .offline: return .gray
        }
    }
}
