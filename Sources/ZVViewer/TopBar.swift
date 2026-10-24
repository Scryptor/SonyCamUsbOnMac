import SwiftUI
import ZVCore

/// Слева сверху: кнопка гистограммы, пресеты (клик — применить) и «+» для сохранения текущих настроек.
struct TopBar: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var renaming: Int?
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Button(action: screen.toggleHistogram) {
                    Label("Гистограмма", systemImage: "chart.bar.xaxis")
                        .labelStyle(.iconOnly)
                        .frame(width: 30, height: 26)
                        .background(screen.histogramOn ? Color.accentColor : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .help("Гистограмма")

                ForEach(Array(controller.presets.enumerated()), id: \.offset) { index, preset in
                    Button { controller.applyPreset(index) } label: {
                        Text(index < 9 ? "\(index + 1) · \(preset.name)" : preset.name)
                            .lineLimit(1)
                            .padding(.horizontal, 10)
                            .frame(height: 26)
                            .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 7))
                    }
                    .buttonStyle(.plain)
                    .disabled(controller.phase != .ready || controller.isApplyingPreset)
                    .contextMenu {
                        Button("Переименовать…") {
                            newName = preset.name
                            renaming = index
                        }
                        Button("Удалить", role: .destructive) { controller.deletePreset(index) }
                    }
                }

                Button { controller.savePreset() } label: {
                    Image(systemName: "plus")
                        .frame(width: 26, height: 26)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .disabled(controller.phase != .ready)
                .help("Сохранить текущие настройки как пресет")
            }
            .padding(5)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))

            if let status = controller.presetStatus {
                HStack(spacing: 6) {
                    if controller.isApplyingPreset { ProgressView().controlSize(.mini) }
                    Text(status)
                }
                .font(.callout)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
            }
        }
        .alert("Название пресета", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("Название", text: $newName)
            Button("Сохранить") {
                if let index = renaming { controller.renamePreset(index, to: newName) }
                renaming = nil
            }
            Button("Отмена", role: .cancel) { renaming = nil }
        }
    }
}

/// Гистограмма яркости: слева тени, справа света.
struct HistogramView: View {
    let bins: [Int]

    var body: some View {
        Canvas { context, size in
            guard let peak = bins.max(), peak > 0 else { return }
            let width = size.width / CGFloat(bins.count)
            var path = Path()
            path.move(to: CGPoint(x: 0, y: size.height))
            for (i, count) in bins.enumerated() {
                // корень сглаживает пики, чтобы был виден и «хвост»
                let h = size.height * CGFloat((Double(count) / Double(peak)).squareRoot())
                path.addLine(to: CGPoint(x: CGFloat(i) * width, y: size.height - h))
                path.addLine(to: CGPoint(x: CGFloat(i + 1) * width, y: size.height - h))
            }
            path.addLine(to: CGPoint(x: size.width, y: size.height))
            path.closeSubpath()
            context.fill(path, with: .color(.white.opacity(0.75)))
        }
        .frame(width: 220, height: 80)
        .padding(8)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
    }
}
