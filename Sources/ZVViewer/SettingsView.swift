import AppKit
import SwiftUI

/// Окно настроек (⌘,).
struct SettingsView: View {
    let controller: CameraController
    @AppStorage(Prefs.Key.destination) private var destination: Prefs.Destination = .macAndCard
    @AppStorage(Prefs.Key.folder) private var folder: String = Prefs.defaultFolder
    @AppStorage(Prefs.Key.reviewEnabled) private var reviewEnabled = true
    @AppStorage(Prefs.Key.reviewSeconds) private var reviewSeconds = 3
    @AppStorage(Prefs.Key.reviewSource) private var reviewSource: Prefs.ReviewSource = .downloaded
    @AppStorage(Prefs.Key.autoHide) private var autoHide = true
    @AppStorage(Prefs.Key.autoHideSeconds) private var autoHideSeconds = 3
    @AppStorage(Prefs.Key.wifiEnabled) private var wifiEnabled = true
    @AppStorage(Prefs.Key.wifiCameraName) private var wifiCameraName: String?
    @AppStorage(Prefs.Key.wifiHost) private var wifiHost: String?
    @AppStorage(Prefs.Key.wifiAdaptiveQuality) private var wifiAdaptiveQuality = true

    var body: some View {
        Form {
            Section("Фото") {
                Picker("Куда сохранять снимки:", selection: $destination) {
                    ForEach(Prefs.Destination.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.radioGroup)
                .onChange(of: destination) { _, _ in controller.applyDestinationPreference() }

                LabeledContent("Папка на Mac:") {
                    HStack {
                        Text((folder as NSString).abbreviatingWithTildeInPath)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Button("Выбрать…", action: chooseFolder)
                    }
                }
                .disabled(!destination.savesToMac)

                Toggle("Показывать снимок после съёмки", isOn: $reviewEnabled)
                LabeledContent("Сколько показывать:") {
                    HStack {
                        Slider(value: Binding(get: { Double(reviewSeconds) }, set: { reviewSeconds = Int($0.rounded()) }),
                               in: 1...10, step: 1)
                            .frame(width: 200)
                        Text("\(reviewSeconds) с").monospacedDigit()
                    }
                }
                .disabled(!reviewEnabled)

                Picker("Во время просмотра показывать:", selection: $reviewSource) {
                    ForEach(Prefs.ReviewSource.allCases) { Text($0.title).tag($0) }
                }
                .pickerStyle(.radioGroup)
                .disabled(!reviewEnabled)
                Text("Если снимок сохраняется только на карту, показывается кадр с камеры.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Wi-Fi") {
                Toggle("Подключаться по Wi-Fi, когда камеры нет на кабеле", isOn: $wifiEnabled)
                LabeledContent("Камера:") {
                    if let name = wifiCameraName {
                        HStack {
                            Text(wifiHost.map { "\(name) · \($0)" } ?? name)
                            Button("Забыть") { controller.forgetWiFiCamera() }
                        }
                    } else {
                        Text("ещё не подключалась по Wi-Fi").foregroundStyle(.secondary)
                    }
                }
                Text("Сопряжение — на экране ожидания: «Сопрячь с камерой…». Камера помнит его, пока её не сбросят.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Toggle("Снижать качество превью при слабом Wi-Fi", isOn: $wifiAdaptiveQuality)
                Text("1024×576 → 640×360, только когда сигнала не хватает: картинка идёт плавнее. При хорошем сигнале — всегда полное качество.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Интерфейс") {
                Toggle("Скрывать элементы управления без движения мыши", isOn: $autoHide)
                LabeledContent("Через:") {
                    HStack {
                        Slider(value: Binding(get: { Double(autoHideSeconds) }, set: { autoHideSeconds = Int($0.rounded()) }),
                               in: 1...30, step: 1)
                            .frame(width: 200)
                        Text("\(autoHideSeconds) с").monospacedDigit()
                    }
                }
                .disabled(!autoHide)
                Text("H — скрыть или показать вручную. S — стоп-кадр в видеорежиме, пробел — снимок в фоторежиме.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .formStyle(.grouped)
        .frame(width: 560)
        .padding(.vertical, 8)
    }

    private func chooseFolder() {
        let panel = NSOpenPanel()
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        panel.canCreateDirectories = true
        panel.directoryURL = URL(fileURLWithPath: folder)
        panel.prompt = "Выбрать"
        if panel.runModal() == .OK, let url = panel.url { folder = url.path }
    }
}
