import SwiftUI
import ZVCore

/// Основной экран: видео, статус, строка значений, кнопки справа, пресеты, гистограмма, лента снимков
/// и меню поверх кадра. Элементы прячутся сами (3 с без мыши) или клавишей H.
struct LiveScreen: View {
    let controller: CameraController
    let screen: ScreenState
    @FocusState private var focused: Bool
    @State private var showHiddenHint = false

    private var showControls: Bool { !screen.controlsHidden || screen.fnOpen || screen.allSettingsOpen }

    var body: some View {
        ZStack {
            Color.black
            video
            if showControls {
                controls.transition(.opacity)
            } else if let started = controller.recordingStartedAt {
                VStack {
                    HStack {
                        Spacer()
                        RecBadge(startedAt: started)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
                    }
                    Spacer()
                }
                .padding(16)
            }
            if screen.allSettingsOpen {
                GeometryReader { geo in
                    AllSettingsPanel(controller: controller, screen: screen)
                        .frame(width: geo.size.width * 0.6, height: geo.size.height * 0.78)
                        .position(x: geo.size.width / 2, y: geo.size.height / 2)
                }
            }
            if let review = controller.review {
                ReviewOverlay(review: review) { controller.dismissReview() }
            }
            if showHiddenHint {
                VStack {
                    Spacer()
                    Text("Элементы скрыты · H — показать")
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 8))
                        .padding(.bottom, 24)
                }
                .transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: showControls)
        .contentShape(Rectangle())
        .onTapGesture {
            screen.closeOverlays()
            screen.noteActivity()
        }
        .onContinuousHover { _ in screen.noteActivity() }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                screen.tickAutoHide(enabled: Prefs.autoHide,
                                    busy: screen.fnOpen || screen.allSettingsOpen || controller.review != nil)
            }
        }
        .environment(\.colorScheme, .dark)
        .focusable()
        .focusEffectDisabled()
        .focused($focused)
        .onAppear { focused = true }
        // буквенные клавиши — в обеих раскладках: f/а, h/р, s/ы на одних и тех же клавишах
        .onKeyPress(characters: CharacterSet(charactersIn: "fFаА")) { _ in
            screen.toggleFn()
            screen.noteActivity()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "hHрР")) { _ in
            screen.toggleControls()
            flashHiddenHint()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "sSыЫ")) { _ in
            guard controller.mode == .video else { return .ignored }
            controller.grabFrame()
            return .handled
        }
        .onKeyPress(.space) {
            if controller.review != nil {
                controller.dismissReview()
                return .handled
            }
            guard controller.mode == .photo else { return .ignored }
            controller.capturePhoto()
            return .handled
        }
        .onKeyPress(characters: CharacterSet(charactersIn: "123456789")) { press in
            guard let digit = Int(press.characters), controller.presets.indices.contains(digit - 1) else { return .ignored }
            controller.applyPreset(digit - 1)
            return .handled
        }
        .onKeyPress(.escape) {
            if controller.review != nil {
                controller.dismissReview()
                return .handled
            }
            guard screen.fnOpen || screen.allSettingsOpen else { return .ignored }
            screen.closeOverlays()
            return .handled
        }
        .onKeyPress(.leftArrow) { step(-1) }
        .onKeyPress(.rightArrow) { step(1) }
    }

    @ViewBuilder private var video: some View {
        if let frame = controller.frame {
            Image(decorative: frame, scale: 1)
                .resizable()
                .aspectRatio(contentMode: .fit)
                .opacity(controller.isFrameStale ? 0.45 : 1)
            if controller.isFrameStale {
                Label("Нет картинки с камеры…", systemImage: "exclamationmark.triangle")
                    .padding(.horizontal, 14)
                    .padding(.vertical, 8)
                    .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 10))
            }
        } else {
            ProgressView().controlSize(.large)
        }
    }

    private var controls: some View {
        ZStack {
            VStack(alignment: .leading) {
                HStack(alignment: .top) {
                    TopBar(controller: controller, screen: screen)
                    Spacer()
                    StatusPill(controller: controller)
                }
                Spacer()
                if !screen.fnOpen {
                    VStack(alignment: .leading, spacing: 8) {
                        if screen.histogramOn && !controller.histogram.isEmpty {
                            HistogramView(bins: controller.histogram)
                        }
                        if !controller.shots.isEmpty {
                            ShotsStrip(controller: controller)
                        }
                    }
                }
                HStack {
                    Spacer()
                    if screen.fnOpen {
                        FnMenu(controller: controller, screen: screen)
                    } else if !screen.allSettingsOpen {
                        InfoLine(controller: controller) { screen.openFn($0) }
                    }
                    Spacer()
                }
                // справа по центру — кнопки: меню Fn не должно под них заходить
                .padding(.trailing, screen.fnOpen ? 96 : 0)
            }
            .padding(16)
            HStack {
                Spacer()
                RightColumn(controller: controller)
            }
            .padding(.trailing, 20)
        }
    }

    private func step(_ offset: Int) -> KeyPress.Result {
        guard screen.fnOpen else { return .ignored }
        controller.requestStep(screen.selected, offset: offset)
        return .handled
    }

    private func flashHiddenHint() {
        guard screen.manualHidden else {
            showHiddenHint = false
            return
        }
        withAnimation { showHiddenHint = true }
        Task {
            try? await Task.sleep(for: .seconds(2))
            withAnimation { showHiddenHint = false }
        }
    }
}

private struct StatusPill: View {
    let controller: CameraController

    var body: some View {
        HStack(spacing: 14) {
            if let started = controller.recordingStartedAt {
                RecBadge(startedAt: started)
            }
            HStack(spacing: 6) {
                Circle().fill(controller.phase == .ready ? .green : .orange).frame(width: 8, height: 8)
                Text("\(controller.deviceInfo?.model ?? "Камера") · USB")
            }
            Text(ValueFormatter.label(.exposureProgram, controller.property(.exposureProgram)))
            if controller.mode == .video {
                Text("Карта: \(ValueFormatter.label(.cardRemaining, controller.property(.cardRemaining)))")
            } else if Prefs.destination != .macOnly, let card = controller.properties[SonyProp.cardRemainingShots],
                      card.enabled != 0, let shots = card.current.intValue {
                // при сохранении «только на Mac» карта не используется — счётчик не показываем
                Text("Карта: \(shots) кадров")
            }
            if controller.mode == .photo, Prefs.destination.savesToMac {
                Text("Mac: \(FileManager.default.displayName(atPath: Prefs.folderURL.path))")
            }
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
            ForEach(SettingCatalog.infoLine(for: controller.mode), id: \.self) { id in
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
