import AppKit
import SwiftUI
import ZVCore

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
    @AppStorage(Prefs.Key.tallyEnabled) private var tallyEnabled = true
    @AppStorage(Prefs.Key.tallyWidth) private var tallyWidth = 3.0
    @AppStorage(Prefs.Key.tallyOpacity) private var tallyOpacity = 0.7
    @AppStorage(Prefs.Key.tallyBlink) private var tallyBlink = false

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
            Section("Запись") {
                Toggle("Красная рамка вокруг картинки во время записи", isOn: $tallyEnabled)
                Group {
                    LabeledContent("Толщина:") {
                        HStack {
                            Slider(value: $tallyWidth, in: 1...12, step: 1).frame(width: 200)
                            Text("\(Int(tallyWidth)) pt").monospacedDigit()
                        }
                    }
                    LabeledContent("Яркость:") {
                        HStack {
                            Slider(value: $tallyOpacity, in: 0.3...1, step: 0.05).frame(width: 200)
                            Text("\(Int((tallyOpacity * 100).rounded())) %").monospacedDigit()
                        }
                    }
                    Toggle("Мигать", isOn: $tallyBlink)
                    LabeledContent("Так она выглядит:") {
                        TallyPreview(width: tallyWidth, opacity: tallyOpacity, blink: tallyBlink)
                    }
                }
                .disabled(!tallyEnabled)
                .opacity(tallyEnabled ? 1 : 0.5)
                Text("Как тэлли у мониторов: издалека видно, что идёт запись. «● REC» с таймером виден всегда.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Section("Wi-Fi") {
                Toggle("Подключаться по Wi-Fi, когда камеры нет на кабеле", isOn: $wifiEnabled)
                LabeledContent("Сопряжённые камеры:") {
                    let paired = controller.roster.known.filter(\.paired).map(\.name)
                    if paired.isEmpty {
                        Text("пока нет").foregroundStyle(.secondary)
                    } else {
                        HStack {
                            Text(paired.joined(separator: ", ")).lineLimit(2)
                            Button("Забыть сопряжение") { controller.forgetWiFiCamera() }
                                .help("У программы будет новое имя для камер — все придётся сопрячь заново. Имена камер останутся")
                        }
                    }
                }
                Text("Сопряжение — «Сопрячь новую камеру…» на экране ожидания или в списке камер (щелчок по имени камеры в "
                     + "строке состояния). Камера помнит его, пока её не сбросят.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Picker("Качество превью по Wi-Fi", selection: Binding(get: { controller.previewQualityMode },
                                                                      set: { controller.setPreviewQuality($0) })) {
                    Text("Авто").tag(PreviewQualityMode.auto)
                    Text("1024×576").tag(PreviewQualityMode.high)
                    Text("640×360").tag(PreviewQualityMode.low)
                }
                .pickerStyle(.segmented)
                Text("Авто — 1024×576, а когда сигнала не хватает — 640×360: картинка идёт плавнее. 1024×576 и 640×360 — всегда это "
                     + "качество. То же — по щелчку на счётчике кадров в строке состояния.")
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
