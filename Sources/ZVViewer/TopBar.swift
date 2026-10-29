import SwiftUI
import ZVCore

/// Слева сверху: кнопки гистограммы, волновой формы, монитора и строки состояния, пресеты (кнопка со списком), «☆+» — сохранить настройки
/// (текущий пресет изменён — в него или новым), и настройки; справа от них — сообщение программы.
struct TopBar: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var newName = ""

    /// Текущий пресет, если камера от него отличается: имя и что изменилось.
    private var modified: (name: String, changes: String)? {
        let changes = controller.currentPresetChanges
        guard let index = controller.currentPresetIndex, !changes.isEmpty else { return nil }
        return (controller.presets[index].name, changes.map(\.label).joined(separator: " · "))
    }

    var body: some View {
        // сообщение — справа от панели, в том же ряду: ни ленту камер под панелью не сдвигает, ни на неё не ложится
        HStack(spacing: 8) {
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

                Button(action: screen.toggleWaveform) {
                    TopBarIconView(icon: .waveform)
                        .frame(width: 30, height: 26)
                        .background(screen.waveformOn ? Color.accentColor : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .help("Волновая форма: яркость или RGB-парад")
                .accessibilityLabel("Волновая форма")

                // зебра, false color, пикинг, LUT, вектороскоп, пипетка, маркеры и зеркало — меню внизу, на месте строки значений
                Button(action: screen.toggleAssistMenu) {
                    TopBarIconView(icon: .monitor)
                        .frame(width: 30, height: 26)
                        .background(screen.assistOpen ? Color.accentColor : Color.white.opacity(0.08),
                                    in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .help("Монитор: зебра, false color, пикинг, LUT, вектороскоп, пипетка, сетка, рамки и зеркало (M)")
                .accessibilityLabel("Монитор")

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

                PresetPicker(controller: controller, screen: screen)

                Button { screen.pressSavePreset(currentModified: modified != nil) } label: {
                    TopBarIconView(icon: .savePreset)
                        .frame(width: 30, height: 26)
                        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 7))
                }
                .buttonStyle(.plain)
                .disabled(controller.phase != .ready)
                .help(modified.map { "Сохранить настройки: в «\($0.name)» или новым пресетом" } ?? "Новый пресет из текущих настроек")
                .accessibilityLabel("Сохранить пресет")
                // текущий изменён — куда сохранять: окошко на этой кнопке, имя нового — на панели
                .alert("Сохранить настройки", isPresented: Binding(get: { screen.presetSaving }, set: { screen.presetSaving = $0 })) {
                    if let modified {
                        Button("Сохранить в «\(modified.name)»") { controller.savePresetChanges() }
                            .keyboardShortcut(.defaultAction)
                    }
                    Button("Новый пресет…") { screen.chooseNewPreset() }
                    Button("Отмена", role: .cancel) {}
                } message: {
                    if let modified {
                        Text("Камера отличается от «\(modified.name)»: \(modified.changes)")
                    }
                }

                SettingsButton()
            }
            .padding(5)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            if let status = controller.statusMessage {
                HStack(spacing: 6) {
                    if controller.isApplyingPreset { ProgressView().controlSize(.mini) }
                    Text(status).lineLimit(1).truncationMode(.middle)
                }
                .font(.callout)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                .help(status)
            }
        }
        // новый пресет — сразу с именем; под полем — что войдёт
        .alert("Новый пресет", isPresented: Binding(get: { screen.presetNaming }, set: { screen.presetNaming = $0 })) {
            TextField("Название", text: $newName)
            Button("Сохранить") { controller.savePreset(name: newName) }
            Button("Отмена", role: .cancel) {}
        } message: {
            Text((controller.presetMode == .photo ? "Для фото: " : "Для видео и S&Q: ")
                 + PresetCatalog.summary(PresetCatalog.capture(name: "", from: controller.properties)))
        }
        .onChange(of: screen.presetNaming) { _, naming in
            if naming { newName = controller.nextPresetName }
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
struct SettingsButton: View {
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

/// Панель поверх кадра, которую можно утащить мышью куда угодно: гистограмма, волновая форма. Видна, пока включена, — и
/// когда остальные элементы скрыты, и при открытом меню Fn: там она нужнее всего. Место запоминается долями свободного
/// места (переживает смену размера окна); у края прилипает к отступу. На правую колонку кнопок не ложится — встаёт левее.
struct FloatingPanel<Content: View>: View {
    let view: CGSize
    let placement: FloatingPlacement?
    let place: (FloatingPlacement) -> Void
    /// Правая колонка кнопок: панель её не закрывает.
    let avoiding: CGRect?
    /// Где стоит, пока её не перетаскивали, — по размеру панели.
    let restingDefault: (CGSize) -> CGPoint
    @ViewBuilder let content: () -> Content
    @State private var panel = CGSize(width: 280, height: 150)
    @State private var dragStart: CGPoint?
    @State private var dragOrigin: CGPoint?
    /// Курсор-рука поставлен наведением: снять его, если панель убрали под курсором.
    @State private var cursorPushed = false

    var body: some View {
        let origin = dragOrigin ?? restingOrigin
        content()
            .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
                if size.width > 0, size.height > 0 { panel = size }
            }
            .scaleEffect(dragOrigin == nil ? 1 : 1.03)
            .shadow(color: .black.opacity(dragOrigin == nil ? 0 : 0.45), radius: 14, y: 4)
            // щелчок по панели — не щелчок по кадру под ней (там он поставил бы фокус касанием)
            .contentShape(Rectangle())
            .onTapGesture {}
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
                            place(FloatingPlacement.dropped(at: dropped, panel: panel, view: view))
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

    private var restingOrigin: CGPoint {
        var origin = placement?.origin(panel: panel, view: view) ?? restingDefault(panel)
        // легла бы на запись и спуск — встаёт левее колонки, на той же высоте
        if let column = avoiding, CGRect(origin: origin, size: panel).intersects(column) {
            origin.x = max(CGFloat(FloatingPlacement.margin), column.minX - 12 - panel.width)
        }
        return origin
    }
}

/// Гистограмма поверх кадра; по умолчанию — слева внизу, над лентой снимков и строкой значений.
struct FloatingHistogram: View {
    let controller: CameraController
    let screen: ScreenState
    let view: CGSize

    var body: some View {
        FloatingPanel(view: view, placement: screen.histogramPlacement, place: screen.placeHistogram,
                      avoiding: screen.rightColumnRect) { (panel: CGSize) -> CGPoint in
            let margin = CGFloat(FloatingPlacement.margin)
            return CGPoint(x: margin, y: max(margin, view.height - panel.height - 134))
        } content: {
            HistogramView(bins: controller.histogram, stats: controller.histogramStats)
        }
    }
}

/// Волновая форма поверх кадра; по умолчанию — справа внизу, над строкой значений, левее кнопок справа.
struct FloatingWaveform: View {
    let controller: CameraController
    let screen: ScreenState
    let trace: WaveformTrace
    let view: CGSize

    var body: some View {
        FloatingPanel(view: view, placement: screen.waveformPlacement, place: screen.placeWaveform,
                      avoiding: screen.rightColumnRect) { (panel: CGSize) -> CGPoint in
            let margin = CGFloat(FloatingPlacement.margin)
            return CGPoint(x: max(margin, view.width - panel.width - margin), y: max(margin, view.height - panel.height - 134))
        } content: {
            WaveformView(trace: trace, mode: screen.waveformMode, onMode: screen.setWaveformMode)
        }
    }
}

/// Вектороскоп поверх кадра; по умолчанию — слева вверху, под верхней панелью (внизу слева — гистограмма, справа внизу — волновая
/// форма, справа вверху — наложение). Щелчок — масштаб ×2, перетаскивание — место.
struct FloatingVectorscope: View {
    let screen: ScreenState
    let trace: VectorscopeTrace
    let view: CGSize

    var body: some View {
        FloatingPanel(view: view, placement: screen.vectorscopePlacement, place: screen.placeVectorscope,
                      avoiding: screen.rightColumnRect) { (_: CGSize) -> CGPoint in
            CGPoint(x: CGFloat(FloatingPlacement.margin), y: 64)
        } content: {
            VectorscopeView(trace: trace, onZoom: screen.toggleVectorscopeZoom)
        }
    }
}

/// Волновая форма: по горизонтали — кадр слева направо, по вертикали — яркость, снизу чёрное. Линии — 0, 50 и 100 %
/// (белое превью: в видео 234, в фото 255); что выше 100 % — пересвет. Под графиком — переключатель «Яркость / RGB».
struct WaveformView: View {
    let trace: WaveformTrace
    let mode: Waveform.Mode
    let onMode: (Waveform.Mode) -> Void

    var body: some View {
        VStack(spacing: 6) {
            graph.frame(height: 104)
            HStack(spacing: 4) {
                Text("Волновая форма").font(.system(size: 10.5)).foregroundStyle(.white.opacity(0.6))
                Spacer()
                ForEach(Waveform.Mode.allCases, id: \.self) { item in
                    Button(item.title) { onMode(item) }
                        .buttonStyle(.plain)
                        .font(.system(size: 10.5, weight: item == mode ? .bold : .regular))
                        .padding(.horizontal, 7)
                        .padding(.vertical, 2)
                        .foregroundStyle(item == mode ? Color.black : Color.white)
                        .background(item == mode ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 5))
                }
            }
        }
        .frame(width: 264)
        .padding(8)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
        .help("Волновая форма: по горизонтали — кадр слева направо, по вертикали — яркость его точек. Линии — 0, 50 и 100 % "
            + "белого превью; выше 100 % — пересвет. RGB — красный, зелёный и синий по отдельности.")
    }

    private var graph: some View {
        Canvas { context, size in
            func y(_ level: Double) -> CGFloat { size.height * (1 - CGFloat(level) / 256) }
            if let image = trace.image {
                context.draw(Image(decorative: image, scale: 1), in: CGRect(origin: .zero, size: size))
            }
            for (fraction, dash) in [(1.0, [CGFloat]()), (0.5, [3, 3]), (0.0, [])] {
                let level = Double(trace.white) * fraction + 0.5
                var line = Path()
                line.move(to: CGPoint(x: 0, y: y(level)))
                line.addLine(to: CGPoint(x: size.width, y: y(level)))
                context.stroke(line, with: .color(.white.opacity(fraction == 0.5 ? 0.3 : 0.45)), style: StrokeStyle(lineWidth: 1, dash: dash))
                context.draw(Text("\(Int(fraction * 100))").font(.system(size: 8)).foregroundColor(.white.opacity(0.55)),
                             at: CGPoint(x: 2, y: max(5, min(size.height - 5, y(level) - 6))), anchor: .leading)
            }
            if mode == .parade {
                for third in [1.0 / 3, 2.0 / 3] {
                    var line = Path()
                    line.move(to: CGPoint(x: size.width * third, y: 0))
                    line.addLine(to: CGPoint(x: size.width * third, y: size.height))
                    context.stroke(line, with: .color(.white.opacity(0.3)), style: StrokeStyle(lineWidth: 1))
                }
            }
        }
    }
}
