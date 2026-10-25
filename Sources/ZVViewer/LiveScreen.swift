import SwiftUI
import ZVCore

/// Основной экран: видео, статус, строка значений, кнопки справа, пресеты, гистограмма, лента снимков
/// и меню поверх кадра. Элементы прячутся сами (через заданное в настройках время без мыши) или клавишей H.
struct LiveScreen: View {
    let controller: CameraController
    let screen: ScreenState
    @FocusState private var focused: Bool
    @State private var showHiddenHint = false

    private var showControls: Bool { !screen.controlsHidden || screen.fnOpen || screen.allSettingsOpen || screen.pillInfo != nil }

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
        // подсказка о S&Q пропадает вместе с режимом (например, MODE на камере) — закрываем и её флаг
        .onChange(of: controller.shootingMode) { _, mode in
            if mode != .slowQuick, screen.pillInfo == .slowMotion { screen.pillInfo = nil }
        }
        // связь пропала — подсказки к ней больше нет
        .onDisappear { screen.pillInfo = nil }
        .task {
            while !Task.isCancelled {
                try? await Task.sleep(for: .milliseconds(500))
                screen.tickAutoHide(enabled: Prefs.autoHide, delay: TimeInterval(Prefs.autoHideSeconds),
                                    busy: screen.fnOpen || screen.allSettingsOpen || screen.pillInfo != nil || controller.review != nil)
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
                .opacity(controller.linkIndicator.dimsPicture ? 0.55 : 1)
            LinkOverlay(controller: controller)
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
                    StatusPill(controller: controller, screen: screen)
                }
                Spacer()
                if !screen.fnOpen {
                    VStack(alignment: .leading, spacing: 8) {
                        if screen.histogramOn && !controller.histogram.isEmpty {
                            HistogramView(bins: controller.histogram, stats: controller.histogramStats)
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
    let screen: ScreenState

    var body: some View {
        HStack(spacing: 14) {
            if let started = controller.recordingStartedAt {
                RecBadge(startedAt: started)
            }
            // щелчок — подсказка: адреса и сигнал / что это за кадры
            PillButton(info: .connection, screen: screen) {
                HStack(spacing: 6) {
                    Circle().fill(controller.phase == .ready ? .green : .orange).frame(width: 8, height: 8)
                    Text("\(controller.deviceInfo?.model ?? "Камера") · \(controller.transportLabel)")
                }
            } popover: {
                ConnectionInfo(controller: controller)
            }
            PillButton(info: .frames, screen: screen) {
                // превью снижено из-за слабого Wi-Fi — видно сразу
                Text("\(controller.shownFPS) к/с" + (controller.previewQuality == .low ? " · \(controller.frame?.height ?? 360)p" : ""))
                    .monospacedDigit()
            } popover: {
                FramesInfo(controller: controller)
            }
            Text(ValueFormatter.label(.exposureProgram, controller.property(.exposureProgram)))
            if let slow = controller.slowMotion {
                PillButton(info: .slowMotion, screen: screen) {
                    Text(SlowMotionInfo.summary(slow)).monospacedDigit()
                } popover: {
                    SlowMotionInfo(controller: controller)
                }
            }
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

/// Часть строки состояния, по щелчку открывающая подсказку.
private struct PillButton<Label: View, Popover: View>: View {
    let info: ScreenState.PillInfo
    let screen: ScreenState
    @ViewBuilder let label: () -> Label
    @ViewBuilder let popover: () -> Popover

    var body: some View {
        Button { screen.togglePillInfo(info) } label: { label().contentShape(Rectangle()) }
            .buttonStyle(.plain)
            .onHover { inside in
                if inside { NSCursor.pointingHand.push() } else { NSCursor.pop() }
            }
            .popover(isPresented: Binding(
                get: { screen.pillInfo == info },
                set: { if !$0, screen.pillInfo == info { screen.pillInfo = nil } }
            ), arrowEdge: .bottom) {
                popover()
                    .padding(16)
                    .frame(width: 360, alignment: .leading)
                    // тёмная, как весь экран, и при светлой теме системы
                    .preferredColorScheme(.dark)
            }
    }
}

private struct ConnectionInfo: View {
    let controller: CameraController
    @State private var details = CameraController.ConnectionDetails()

    private var wifi: Bool { controller.transportKind == .wifi }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Подключение по \(controller.transportLabel)").font(.headline)
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                if let info = controller.deviceInfo {
                    InfoRow(label: "Камера", value: "\(info.model), прошивка \(info.firmware)")
                }
                if wifi {
                    if let host = details.cameraHost { InfoRow(label: "IP камеры", value: host) }
                    if let host = details.localHost { InfoRow(label: "IP Mac", value: host) }
                    if let dbm = details.signalDBm {
                        InfoRow(label: "Сигнал Wi-Fi", value: "\(String(dbm).replacingOccurrences(of: "-", with: "−")) дБм, \(Self.signalWord(dbm))")
                    }
                    if let quality = controller.previewQuality, let frame = controller.frame {
                        InfoRow(label: "Превью", value: "\(frame.width)×\(frame.height)"
                                + (quality == .low ? " — сигнала не хватает на полное качество" : ""))
                    }
                    if let throughput = controller.throughput {
                        InfoRow(label: "Поток превью", value: String(format: "%.1f Мбит/с", throughput * 8 / 1_000_000)
                                .replacingOccurrences(of: ".", with: ","))
                    }
                    if let rtt = controller.linkRTTMs { InfoRow(label: "Отклик камеры", value: "\(rtt) мс") }
                }
            }
            .textSelection(.enabled)
            Text(!wifi ? "Без кабеля программа сама найдёт камеру по Wi-Fi, если она сопряжена (⌘, → Wi-Fi)."
                 : controller.wifiDirect ? "Камера сама раздаёт сеть, сигнал — между Mac и камерой. Если картинка дёргается, поставьте Mac ближе к камере."
                 : "Сигнал — между Mac и роутером. Если картинка дёргается, поставьте камеру ближе к роутеру.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        // сигнал меняется — пока подсказка открыта, обновляем раз в секунду
        .task {
            while !Task.isCancelled {
                details = await controller.connectionDetails()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    static func signalWord(_ dbm: Int) -> String {
        dbm >= -60 ? "хороший" : dbm >= -70 ? "средний" : "слабый"
    }
}

private struct FramesInfo: View {
    let controller: CameraController

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Кадры трансляции").font(.headline)
            Group {
                Text("Столько кадров в секунду (сейчас \(controller.shownFPS)) доходит до Mac \(controller.transportKind == .wifi ? "по Wi-Fi" : "по USB"). Это картинка для просмотра, а не съёмка.")
                Text("На запись это не влияет: камера пишет видео на карту со своей частотой (например, 25p) и снимает фото в полном качестве, сколько бы кадров ни доходило сюда.")
                    .foregroundStyle(.secondary)
                Text("Счётчик — чтобы проверить связь. Камера отдаёт до 25–30 кадров в секунду; если число заметно меньше или скачет, связь не успевает и картинка подёргивается.")
                    .foregroundStyle(.secondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                InfoRow(label: "Самая долгая пауза за 10 с", value: "\(controller.frameGapMs) мс")
            }
            Text("Обычно между кадрами около 40 мс; паузы дольше 200 мс заметны как рывок.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Подсказка о замедленной съёмке: что снимает камера, во что пишет ролик и где менять частоту.
private struct SlowMotionInfo: View {
    let controller: CameraController

    static func summary(_ slow: (capture: Int?, record: Int?)) -> String {
        guard let capture = slow.capture else { return "? к/с" }
        guard let record = slow.record else { return "\(capture) к/с" }
        let speed = SlowQuick.speedLabel(capture: capture, record: record)
            .replacingOccurrences(of: "медленнее", with: "замедл.").replacingOccurrences(of: "быстрее", with: "ускор.")
        return "\(capture) к/с · " + (capture == record ? "1:1" : speed)
    }

    var body: some View {
        let slow = controller.slowMotion
        VStack(alignment: .leading, spacing: 10) {
            Text("Замедленная и ускоренная съёмка (S&Q)").font(.headline)
            Grid(alignment: .leading, horizontalSpacing: 16, verticalSpacing: 6) {
                InfoRow(label: "Камера снимает", value: slow?.capture.map { "\($0) кадров в секунду" } ?? "не видно")
                InfoRow(label: "Ролик", value: controller.label(.movieRecordingSetting))
                if let capture = slow?.capture, let record = slow?.record {
                    InfoRow(label: "Скорость", value: SlowQuick.speedLabel(capture: capture, record: record))
                }
            }
            Text("Частоту съёмки камера по кабелю менять не даёт: её выбирают на камере — MENU → S&Q Settings → Frame Rate. Частоту и битрейт ролика меняет «Запись» в строке внизу.")
                .font(.callout)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            Text("Частоту съёмки программа узнаёт по самой длинной выдержке, которую разрешает камера: выдержка не может быть длиннее кадра. В режимах S&Q P и A выдержку выбирает камера, и частоту не видно.")
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

private struct InfoRow: View {
    let label: String
    let value: String

    var body: some View {
        GridRow {
            Text(label).foregroundStyle(.secondary)
            Text(value).monospacedDigit()
        }
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
                        Text(controller.label(id))
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

/// Что со связью — поверх кадра: слабый сигнал (плашка сверху, кадр не тускнеет), провал (карточка с временем и
/// советом), переподключение. Последний кадр остаётся на экране всё это время.
private struct LinkOverlay: View {
    let controller: CameraController

    private var wifi: Bool { controller.transportKind == .wifi }

    var body: some View {
        switch controller.linkIndicator {
        case .none:
            EmptyView()
        case .weak:
            if wifi {
                VStack {
                    Label("Слабый сигнал Wi-Fi", systemImage: "wifi.exclamationmark")
                        .font(.callout)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.top, 64)
                    Spacer()
                }
            }
        case let .interrupted(since):
            card(title: wifi ? "Связь с камерой прервалась" : "Нет картинки с камеры", since: since,
                 tip: wifi ? tip : nil)
        case let .reconnecting(since):
            card(title: "Переподключаюсь к камере…", since: since, tip: tip)
        }
    }

    private var tip: String {
        let mac = "На Mac помогает выключить AirDrop и Handoff: они уводят Wi-Fi на свои каналы."
        return controller.wifiDirect ? "Поставьте Mac ближе к камере. " + mac : "Поставьте камеру ближе к роутеру. " + mac
    }

    private func card(title: String, since: Date, tip: String?) -> some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let seconds = max(0, Int(context.date.timeIntervalSince(since)))
            VStack(spacing: 8) {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("\(title) · \(seconds / 60):\(String(format: "%02d", seconds % 60))").monospacedDigit()
                }
                .font(.headline)
                if let tip, seconds >= 10 {
                    Text(tip)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .frame(maxWidth: 360)
                }
            }
            .padding(.horizontal, 18)
            .padding(.vertical, 12)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

extension CameraController.LinkIndicator {
    /// Картинка заметно старая — приглушаем её, чтобы не путали с живой.
    var dimsPicture: Bool {
        switch self {
        case .none, .weak: return false
        case .interrupted, .reconnecting: return true
        }
    }
}
