import SwiftUI
import ZVCore

/// Слева сверху: кнопка гистограммы, пресеты (клик — применить), «+» для сохранения текущих настроек и настройки.
struct TopBar: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var renaming: Int?
    @State private var newName = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Button(action: screen.toggleHistogram) {
                    TopBarIconView(icon: .histogram)
                        .frame(width: 30, height: 26)
                        .background(screen.histogramOn ? Color.accentColor : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .help("Гистограмма")
                .accessibilityLabel("Гистограмма")

                // строка состояния видна вместе с элементами; кнопка закрепляет её — тогда остаётся и без них
                Button(action: screen.toggleStatusPin) {
                    TopBarIconView(icon: .statusBar)
                        .frame(width: 30, height: 26)
                        .background(screen.statusBarPinned ? Color.accentColor : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .help(screen.statusBarPinned ? "Открепить строку состояния: будет прятаться вместе с элементами"
                      : "Закрепить строку состояния: останется на экране, когда элементы спрятаны")
                .accessibilityLabel("Закрепить строку состояния")

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
                    TopBarIconView(icon: .savePreset)
                        .frame(width: 30, height: 26)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .disabled(controller.phase != .ready)
                .help("Сохранить текущие настройки как пресет")
                .accessibilityLabel("Сохранить пресет")

                SettingsButton()
            }
            .padding(5)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))

            if let status = controller.statusMessage {
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

/// Гистограмма яркости: слева тени, справа света. Пунктир делит шкалу на трети — тени, полутона и света; под графиком —
/// их доли, провалы в чёрное, средняя яркость (она же — метка под графиком) и пересвет.
struct HistogramView: View {
    let bins: [Int]
    let stats: LumaStats?
    /// Пересвет и провалы. Жёлтый, а не красный: красный на тёмном видно плохо.
    private static let warning = Color(red: 1, green: 0.84, blue: 0.04)

    private struct Figure {
        let label: String
        let value: String
        var alert = false
    }

    var body: some View {
        VStack(spacing: 6) {
            graph.frame(height: 78)
            if let stats {
                row([Figure(label: "Тени", value: LumaStats.share(stats.shadows)),
                     Figure(label: "Полутона", value: LumaStats.share(stats.midtones)),
                     Figure(label: "Света", value: LumaStats.share(stats.highlights))])
                row([Figure(label: "Провалы", value: LumaStats.clipping(stats.clippedShadows),
                            alert: LumaStats.isClipped(stats.clippedShadows)),
                     Figure(label: "Средняя", value: LumaStats.share(stats.mean)),
                     Figure(label: "Пересвет", value: LumaStats.clipping(stats.clippedHighlights),
                            alert: LumaStats.isClipped(stats.clippedHighlights))])
            }
        }
        .frame(width: 264)
        .padding(8)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
        .help("Яркость кадра: слева тени, справа света, пунктир делит шкалу на трети. Провалы — почти чёрные точки, "
            + "пересвет — точки на белом: от 1% они жёлтые. Считается по картинке live view.")
    }

    private var graph: some View {
        Canvas { context, size in
            // снизу — полоска под метку средней яркости
            let height = size.height - 8
            for third in [1.0 / 3, 2.0 / 3] {
                var line = Path()
                line.move(to: CGPoint(x: size.width * third, y: 0))
                line.addLine(to: CGPoint(x: size.width * third, y: height))
                context.stroke(line, with: .color(.white.opacity(0.3)), style: StrokeStyle(lineWidth: 1, dash: [3, 3]))
            }
            if let peak = bins.max(), peak > 0 {
                let width = size.width / CGFloat(bins.count)
                var path = Path()
                path.move(to: CGPoint(x: 0, y: height))
                for (i, count) in bins.enumerated() {
                    // корень сглаживает пики, чтобы был виден и «хвост»
                    let h = height * CGFloat((Double(count) / Double(peak)).squareRoot())
                    path.addLine(to: CGPoint(x: CGFloat(i) * width, y: height - h))
                    path.addLine(to: CGPoint(x: CGFloat(i + 1) * width, y: height - h))
                }
                path.addLine(to: CGPoint(x: size.width, y: height))
                path.closeSubpath()
                context.fill(path, with: .color(.white.opacity(0.75)))
            }
            guard let stats else { return }
            // провалы и пересвет — жёлтая кромка с той стороны, где детали потеряны
            if LumaStats.isClipped(stats.clippedShadows) {
                context.fill(Path(CGRect(x: 0, y: 0, width: 3, height: height)), with: .color(Self.warning))
            }
            if LumaStats.isClipped(stats.clippedHighlights) {
                context.fill(Path(CGRect(x: size.width - 3, y: 0, width: 3, height: height)), with: .color(Self.warning))
            }
            let x = size.width * stats.mean / 100
            var marker = Path()
            marker.move(to: CGPoint(x: x, y: height + 2))
            marker.addLine(to: CGPoint(x: x - 4, y: size.height))
            marker.addLine(to: CGPoint(x: x + 4, y: size.height))
            marker.closeSubpath()
            context.fill(marker, with: .color(.white))
        }
    }

    private func row(_ figures: [Figure]) -> some View {
        HStack(spacing: 0) {
            ForEach(figures.indices, id: \.self) { i in
                HStack(spacing: 3) {
                    Text(figures[i].label).foregroundStyle(.white.opacity(0.6))
                    Text(figures[i].value).fontWeight(.semibold)
                        .foregroundStyle(figures[i].alert ? Self.warning : .white.opacity(0.9))
                }
                .frame(maxWidth: .infinity)
            }
        }
        .font(.system(size: 10.5))
        .monospacedDigit()
        .lineLimit(1)
    }
}

/// Окно настроек — то же, что ⌘,.
private struct SettingsButton: View {
    @Environment(\.openSettings) private var openSettings

    var body: some View {
        Button { openSettings() } label: {
            TopBarIconView(icon: .settings)
                .frame(width: 30, height: 26)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .help("Настройки (⌘,)")
        .accessibilityLabel("Настройки")
    }
}

/// Гистограмма поверх кадра, которую можно утащить мышью куда угодно. Видна, пока включена, — и когда остальные
/// элементы скрыты, и при открытом меню Fn: там она нужнее всего. Место запоминается долями свободного места
/// (переживает смену размера окна); у края прилипает к отступу.
struct FloatingHistogram: View {
    let controller: CameraController
    let screen: ScreenState
    let view: CGSize
    @State private var panel = CGSize(width: 280, height: 150)
    @State private var dragStart: CGPoint?
    @State private var dragOrigin: CGPoint?
    /// Курсор-рука поставлен наведением: снять его, если панель убрали под курсором.
    @State private var cursorPushed = false

    var body: some View {
        let origin = dragOrigin ?? restingOrigin
        HistogramView(bins: controller.histogram, stats: controller.histogramStats)
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                if size.width > 0, size.height > 0 { panel = size }
            }
            .scaleEffect(dragOrigin == nil ? 1 : 1.03)
            .shadow(color: .black.opacity(dragOrigin == nil ? 0 : 0.45), radius: 14, y: 4)
            .gesture(
                DragGesture(minimumDistance: 2, coordinateSpace: .named(LiveScreen.space))
                    .onChanged { value in
                        let start = dragStart ?? restingOrigin
                        if dragStart == nil {
                            dragStart = start
                            NSCursor.closedHand.set()
                        }
                        dragOrigin = FloatingPlacement.dragOrigin(
                            CGPoint(x: start.x + value.translation.width, y: start.y + value.translation.height),
                            panel: panel, view: view)
                    }
                    .onEnded { _ in
                        guard let dropped = dragOrigin else { return }
                        withAnimation(.spring(response: 0.3, dampingFraction: 0.8)) {
                            screen.placeHistogram(FloatingPlacement.dropped(at: dropped, panel: panel, view: view))
                            dragOrigin = nil
                        }
                        dragStart = nil
                        if cursorPushed { NSCursor.openHand.set() } else { NSCursor.arrow.set() }
                    }
            )
            .onHover { inside in
                if inside, !cursorPushed {
                    NSCursor.openHand.push()
                    cursorPushed = true
                } else if !inside, cursorPushed {
                    NSCursor.pop()
                    cursorPushed = false
                }
            }
            .onDisappear {
                if cursorPushed { NSCursor.pop() }
                cursorPushed = false
            }
            .position(x: origin.x + panel.width / 2, y: origin.y + panel.height / 2)
    }

    /// По умолчанию — слева внизу, над лентой снимков и строкой значений.
    private var restingOrigin: CGPoint {
        if let placement = screen.histogramPlacement { return placement.origin(panel: panel, view: view) }
        return CGPoint(x: FloatingPlacement.margin, y: max(FloatingPlacement.margin, view.height - panel.height - 134))
    }
}
