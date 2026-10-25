import AppKit
import SwiftUI
import ZVCore

/// Панель отладки (⌥⌘D): всё о связи, превью и камере по вкладкам поверх экрана. «Скопировать» отдаёт все вкладки
/// текстом — чтобы переслать. Серийный номер, MAC и GUID не показываются.
struct DebugPanel: View {
    let controller: CameraController
    let screen: ScreenState
    /// Сколько места по высоте даёт окно: панель растёт по содержимому, но не больше.
    var maxHeight: CGFloat = 640
    @State private var details = CameraController.ConnectionDetails()
    @State private var filter = ""
    @State private var copiedAt: Date?
    @State private var contentHeight: CGFloat = 0

    var body: some View {
        @Bindable var screen = screen
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text("Отладка").font(.headline)
                Spacer()
                Button(copiedAt.map { Date().timeIntervalSince($0) < 2 } == true ? "Скопировано" : "Скопировать") { copyAll() }
                Button { screen.debugOpen = false } label: { Image(systemName: "xmark") }
                    .buttonStyle(.plain)
                    .help("Закрыть (⌥⌘D)")
            }
            Picker("", selection: $screen.debugTab) {
                ForEach(ScreenState.DebugTab.allCases) { Text($0.title).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
            if screen.debugTab == .properties {
                TextField("Поиск: код или имя", text: $filter)
                    .textFieldStyle(.roundedBorder)
            }
            // цифры живые — пересчитываем дважды в секунду
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                ScrollView {
                    rows(screen.debugTab)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(GeometryReader { Color.clear.preference(key: ContentHeight.self, value: $0.size.height) })
                }
                .frame(height: min(max(contentHeight, 24), max(maxHeight - 130, 120)))
            }
            .onPreferenceChange(ContentHeight.self) { contentHeight = $0 }
        }
        .padding(16)
        .frame(width: 440)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        .preferredColorScheme(.dark)
        .task {
            while !Task.isCancelled {
                details = await controller.connectionDetails()
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }

    @ViewBuilder private func rows(_ tab: ScreenState.DebugTab) -> some View {
        let items = DebugReport.rows(tab, controller, details, filter: filter)
        if items.isEmpty {
            Text(tab == .log ? "Событий пока нет" : "Нет данных: камера не подключена").foregroundStyle(.secondary)
        } else if tab == .properties {
            // код и имя — строкой, значение — под ней: в две колонки длинные имена libgphoto2 не помещаются
            VStack(alignment: .leading, spacing: 7) {
                ForEach(items) { row in
                    VStack(alignment: .leading, spacing: 1) {
                        Text(row.label).font(.system(.callout, design: .monospaced))
                        Text(row.value).font(.callout).foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .textSelection(.enabled)
        } else {
            Grid(alignment: .leading, horizontalSpacing: 12, verticalSpacing: 5) {
                ForEach(items) { row in
                    GridRow {
                        Text(row.label)
                            .font(tab == .log ? .system(.callout, design: .monospaced) : .callout)
                            .foregroundStyle(.secondary)
                            .gridColumnAlignment(.leading)
                        Text(row.value)
                            .font(.callout)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .textSelection(.enabled)
            .monospacedDigit()
        }
    }

    private func copyAll() {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(DebugReport.text(controller, details), forType: .string)
        copiedAt = Date()
    }
}

private struct ContentHeight: PreferenceKey {
    static let defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = max(value, nextValue()) }
}

/// Строки панели отладки — и для экрана, и для «Скопировать».
@MainActor
enum DebugReport {
    struct Row: Identifiable {
        let id = UUID()
        let label: String
        let value: String
    }

    static func rows(_ tab: ScreenState.DebugTab, _ c: CameraController, _ d: CameraController.ConnectionDetails,
                     filter: String = "") -> [Row] {
        switch tab {
        case .link: return link(c, d)
        case .preview: return preview(c)
        case .camera: return camera(c)
        case .properties: return properties(c, filter: filter)
        case .log: return log(c)
        }
    }

    /// Все вкладки текстом.
    static func text(_ c: CameraController, _ d: CameraController.ConnectionDetails) -> String {
        var lines = ["Sony Cam Viewer \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"), "
            + "macOS \(ProcessInfo.processInfo.operatingSystemVersionString), \(Date().formatted(date: .numeric, time: .standard))"]
        for tab in ScreenState.DebugTab.allCases {
            lines.append("")
            lines.append("== \(tab.title) ==")
            lines += rows(tab, c, d).map { "\($0.label): \($0.value)" }
        }
        return lines.joined(separator: "\n")
    }

    // MARK: - Вкладки

    private static func link(_ c: CameraController, _ d: CameraController.ConnectionDetails) -> [Row] {
        var rows = [Row(label: "Подключение", value: c.transportKind == nil ? "нет" : c.transportLabel),
                    Row(label: "Состояние", value: state(c))]
        if let host = d.cameraHost { rows.append(Row(label: "Камера", value: "\(host):\(PTPIP.port)")) }
        if let host = d.localHost { rows.append(Row(label: "Mac", value: host)) }
        var signal: [String] = []
        if let dbm = d.signalDBm { signal.append("\(minus(dbm)) дБм") }
        if let noise = d.noiseDBm { signal.append("шум \(minus(noise)) дБм") }
        if let rate = d.rateMbps { signal.append("\(Int(rate)) Мбит/с") }
        if let channel = d.channel { signal.append("канал \(channel)") }
        rows.append(Row(label: "Wi-Fi Mac", value: signal.isEmpty ? "—" : signal.joined(separator: ", ")))
        if let rtt = c.rttStats {
            rows.append(Row(label: "Отклик камеры", value: "\(rtt.last) мс · мин \(rtt.min) · среднее \(rtt.average) за минуту"))
        }
        if let throughput = c.throughput { rows.append(Row(label: "Поток превью", value: mbit(throughput))) }
        if let started = c.sessionStartedAt { rows.append(Row(label: "Сеанс идёт", value: clock(Date().timeIntervalSince(started)))) }
        if c.transportKind == .wifi {
            rows.append(Row(label: "Провалов связи", value: c.outages == 0 ? "0"
                            : "\(c.outages), самый долгий \(seconds(c.longestOutage))"))
        }
        rows.append(Row(label: "Восстановлений", value: c.reconnects == 0 ? "0 с запуска программы"
                        : "\(c.reconnects), последнее — \(c.lastRecoverySeconds.map(seconds) ?? "—")"))
        if let loss = c.lastLoss {
            rows.append(Row(label: "Последний обрыв", value: "\(loss.reason.map(CameraController.lossTitle) ?? "сеанс закончился"), "
                            + "\(clock(Date().timeIntervalSince(loss.at))) назад"))
        }
        if let message = c.wifiStatus ?? c.connectionMessage { rows.append(Row(label: "Сообщение", value: message)) }
        rows.append(Row(label: "Wi-Fi в программе", value: Prefs.wifiEnabled ? "включён" : "выключен"))
        rows.append(Row(label: "Сопряжена", value: Prefs.wifiCameraName.map { "\($0)" + (Prefs.wifiHost.map { ", была на \($0)" } ?? "") } ?? "нет"))
        return rows
    }

    private static func preview(_ c: CameraController) -> [Row] {
        guard c.frame != nil || c.framesTotal > 0 else { return [] }
        var rows = [Row(label: "Кадров/с", value: String(format: "%.1f", c.fps).replacingOccurrences(of: ".", with: ","))]
        if let frame = c.frame {
            rows.append(Row(label: "Кадр", value: "\(frame.width)×\(frame.height)" + (c.lastFrameBytes.map { ", \($0 / 1024) КБ" } ?? "")))
        }
        if c.transportKind == .wifi {
            let auto = Prefs.wifiAdaptiveQuality ? "авто" : "авто выключено"
            let quality = c.previewQuality == .low ? "низкое — сигнала не хватает" : "высокое"
            rows.append(Row(label: "Качество", value: "\(quality) (\(auto))"))
            let forecast = c.qualityForecast
            if let fps = forecast.highFPS {
                rows.append(Row(label: "Прогноз на высоком", value: String(format: "%.1f к/с", fps).replacingOccurrences(of: ".", with: ",")))
            }
            if let up = forecast.upInSeconds { rows.append(Row(label: "Вернуть высокое", value: "попытка через \(Int(up.rounded())) с")) }
        }
        if let gaps = c.frameGapStats {
            rows.append(Row(label: "Паузы между кадрами", value: "p50 \(gaps.p50) · p95 \(gaps.p95) · max \(gaps.max) мс (10 с)"))
        }
        if let fetch = c.fetchStats {
            rows.append(Row(label: "Запрос кадра", value: "p50 \(fetch.p50) · p95 \(fetch.p95) мс (5 с)"))
            rows.append(Row(label: "«Кадр не готов»", value: String(format: "%.1f на кадр", fetch.waitsPerFrame).replacingOccurrences(of: ".", with: ",")))
        }
        rows.append(Row(label: "Кадров за сеанс", value: "\(c.framesTotal)"))
        if c.transportKind == .wifi { rows.append(Row(label: "Смен качества", value: "\(c.qualitySwitches)")) }
        return rows
    }

    private static func camera(_ c: CameraController) -> [Row] {
        var rows = [Row(label: "Фаза", value: phase(c.phase))]
        guard let info = c.deviceInfo else { return rows }
        rows.append(Row(label: "Модель", value: info.model))
        rows.append(Row(label: "Прошивка", value: info.firmware))
        rows.append(Row(label: "Режим съёмки", value: ValueFormatter.label(.exposureProgram, c.property(.exposureProgram))))
        rows.append(Row(label: "Запись", value: c.recordingStartedAt.map { "идёт \(clock(Date().timeIntervalSince($0)))" } ?? "нет"))
        rows.append(Row(label: "Батарея", value: ValueFormatter.label(.battery, c.property(.battery))))
        rows.append(Row(label: "Перегрев", value: ValueFormatter.label(.overheat, c.property(.overheat))))
        rows.append(Row(label: "Карта", value: ValueFormatter.label(.cardRemaining, c.property(.cardRemaining))))
        // служебные свойства — только если камера их отдаёт (PriorityMode, например, бывает только записываемым)
        if let destination = c.properties[SonyProp.stillImageStoreDestination]?.current.intValue {
            rows.append(Row(label: "Снимки сохраняет",
                            value: [0x01: "только на Mac", 0x10: "только на карту", 0x11: "на Mac и на карту"][destination] ?? "код \(destination)"))
        }
        if let priority = c.properties[SonyProp.priorityMode]?.current.intValue {
            rows.append(Row(label: "Приоритет управления", value: priority == 1 ? "у программы (1)" : "у камеры (\(priority))"))
        }
        if let quality = c.properties[SonyProp.liveViewImageQuality]?.current.intValue {
            rows.append(Row(label: "Качество превью в камере", value: quality == 1 ? "низкое (1)" : quality == 2 ? "высокое (2)" : "\(quality)"))
        }
        rows.append(Row(label: "Свойств", value: "\(c.properties.count)"))
        return rows
    }

    private static func properties(_ c: CameraController, filter: String) -> [Row] {
        let query = filter.trimmingCharacters(in: .whitespaces).lowercased()
        return c.properties.keys.sorted().compactMap { code in
            guard let property = c.properties[code] else { return nil }
            let id = SettingID.allCases.first { $0.code == code }
            let name = id?.title ?? PropertyNames.name(code) ?? "—"
            let label = String(format: "0x%04X", code) + " · " + name
            guard query.isEmpty || label.lowercased().contains(query) else { return nil }
            var value: [String] = []
            switch property.current {
            case let .int(v): value.append(v > 9 || v < 0 ? "\(v) (0x\(String(UInt64(bitPattern: v), radix: 16, uppercase: true)))" : "\(v)")
            // текст — не показываем и не копируем (в нём может оказаться что угодно), кроме часов камеры 0xD223
            case let .string(text): value.append(code == 0xD223 ? "«\(text)»" : "текст, \(text.count) симв.")
            case let .array(values): value.append("[" + values.map(String.init).joined(separator: ", ") + "]")
            case .unsupported: value.append("тип не разобран")
            }
            if let id { value.append(ValueFormatter.label(id, property)) }
            value.append(property.enabled == 0 ? "недоступно" : property.isWritable ? "можно менять" : property.getSet == 1 ? "менять нельзя сейчас" : "только чтение")
            if let range = property.range {
                value.append("от \(range.min) до \(range.max), шаг \(range.step)")
            } else if !property.allValues.isEmpty {
                value.append("\(property.allValues.count) значений")
            }
            return Row(label: label, value: value.joined(separator: " · "))
        }
    }

    private static func log(_ c: CameraController) -> [Row] {
        let format = Date.FormatStyle().hour(.twoDigits(amPM: .omitted)).minute(.twoDigits).second(.twoDigits)
            .secondFraction(.fractional(3))
        return c.debugEvents.reversed().map { Row(label: $0.at.formatted(format), value: $0.text) }
    }

    // MARK: - Подписи

    private static func state(_ c: CameraController) -> String {
        switch c.linkIndicator {
        case .none: return c.phase == .searching ? "ищу камеру" : c.phase == .notPCRemote ? "камера не в PC Remote" : "связь есть"
        case .weak: return "слабый сигнал: кадры запаздывают"
        case let .interrupted(since): return "связь прервалась · \(clock(Date().timeIntervalSince(since)))"
        case let .reconnecting(since): return "переподключаюсь · \(clock(Date().timeIntervalSince(since)))"
        }
    }

    private static func phase(_ phase: CameraController.Phase) -> String {
        switch phase {
        case .searching: return "ищу камеру"
        case .notPCRemote: return "камера не в режиме PC Remote"
        case .connecting: return "подключение"
        case .settling: return "первые секунды: настройки ещё не принимаются"
        case .ready: return "готова"
        }
    }

    private static func clock(_ interval: TimeInterval) -> String {
        let total = max(0, Int(interval))
        let h = total / 3600, m = total / 60 % 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%d:%02d", m, s)
    }

    private static func seconds(_ interval: TimeInterval) -> String {
        String(format: "%.1f с", interval).replacingOccurrences(of: ".", with: ",")
    }

    private static func mbit(_ bytesPerSecond: Double) -> String {
        String(format: "%.1f Мбит/с", bytesPerSecond * 8 / 1_000_000).replacingOccurrences(of: ".", with: ",")
    }

    private static func minus(_ value: Int) -> String {
        String(value).replacingOccurrences(of: "-", with: "−")
    }
}
