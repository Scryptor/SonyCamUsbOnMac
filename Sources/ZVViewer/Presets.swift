import SwiftUI
import ZVCore

/// Кнопка пресетов вверху слева: на ней — пресет, который сейчас стоит на камере, иначе «Пресеты». Нажатие — список:
/// применить, сохранить текущие настройки, открыть окно пресетов.
struct PresetPicker: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        Button { screen.presetMenuOpen.toggle() } label: {
            HStack(spacing: 6) {
                Text(title)
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold))
            }
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 26)
            .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .help("Пресеты: выбрать, сохранить, настроить")
        .popover(isPresented: Binding(get: { screen.presetMenuOpen }, set: { screen.presetMenuOpen = $0 }),
                 arrowEdge: .bottom) {
            PresetMenu(controller: controller, screen: screen)
        }
    }

    /// Длинное имя не должно растянуть верхний ряд до строки состояния.
    private var title: String {
        guard let index = controller.activePresetIndex else { return "Пресеты" }
        let name = controller.presets[index].name
        return "\(index + 1) · " + (name.count > 24 ? name.prefix(23) + "…" : name)
    }
}

/// Список пресетов под кнопкой: номер, имя, что он ставит; галочка — тот, что сейчас на камере. Нажатие применяет.
private struct PresetMenu: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var hovered: Int?

    var body: some View {
        let active = controller.activePresetIndex
        VStack(alignment: .leading, spacing: 0) {
            if controller.presets.isEmpty {
                Text("Пресетов пока нет. «Сохранить текущие» запомнит, как сейчас настроена камера.")
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(12)
            } else if controller.presets.count > 8 {
                ScrollView { rows }.frame(height: 360)
            } else {
                rows
            }
            Divider()
            HStack {
                Button("Сохранить текущие…") {
                    screen.presetMenuOpen = false
                    screen.presetNaming = true
                }
                .disabled(controller.phase != .ready)
                Spacer()
                Button("Настроить…") {
                    screen.openPresetsWindow(selecting: active ?? (controller.presets.isEmpty ? nil : 0))
                }
            }
            .padding(10)
        }
        .frame(width: 360)
    }

    /// Галочка — у каждого пресета, который стоит на камере; на кнопке — первый из них.
    private var rows: some View {
        VStack(spacing: 2) {
            ForEach(Array(controller.presets.enumerated()), id: \.offset) { index, preset in
                Button {
                    screen.presetMenuOpen = false
                    controller.applyPreset(index)
                } label: {
                    PresetLine(number: index + 1, preset: preset, active: PresetCatalog.matches(preset, controller.properties))
                        .padding(.horizontal, 10)
                        .padding(.vertical, 6)
                        .background(hovered == index ? Color.primary.opacity(0.08) : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 6))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(controller.phase != .ready || controller.isApplyingPreset)
                .onHover { inside in
                    if inside { hovered = index } else if hovered == index { hovered = nil }
                }
            }
        }
        .padding(6)
    }
}

/// Строка пресета в списках: номер (он же клавиша 1–9), кадр, имя, что он ставит; галочка — стоит на камере.
struct PresetLine: View {
    let number: Int
    let preset: Preset
    let active: Bool

    var body: some View {
        HStack(spacing: 10) {
            Text("\(number)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 18, alignment: .trailing)
            PresetThumb(preview: preset.preview, width: 56)
            VStack(alignment: .leading, spacing: 2) {
                Text(preset.name).lineLimit(1)
                Text(PresetCatalog.summary(preset))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 8)
            if active {
                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
            }
        }
    }
}

/// Окно «Пресеты»: слева список — порядок перетаскиванием, справа выбранный пресет: имя, кнопки и все его настройки.
/// Всё сохраняется сразу. Значения выбираются из того, что камера даёт выбрать сейчас, — сама камера при этом не меняется.
struct PresetsWindow: View {
    static let id = "presets"

    let controller: CameraController
    let screen: ScreenState
    @Environment(\.dismissWindow) private var dismissWindow
    @State private var deleting: Int?

    var body: some View {
        HStack(spacing: 0) {
            sidebar.frame(width: 280)
            Divider()
            if let index = screen.presetsSelected, controller.presets.indices.contains(index) {
                // своё имя у каждого пресета: поле имени не тянет текст от прежнего
                PresetEditor(controller: controller, screen: screen, index: index) { deleting = index }
                    .id(index)
            } else {
                Text(controller.presets.isEmpty
                     ? "Пресетов пока нет. «Сохранить текущие» запомнит, как сейчас настроена камера."
                     : "Выберите пресет слева.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(40)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 660, minHeight: 460)
        .onAppear {
            screen.presetsWindowOpen = true
            if !(screen.presetsSelected.map(controller.presets.indices.contains) ?? false) {
                screen.presetsSelected = controller.activePresetIndex ?? (controller.presets.isEmpty ? nil : 0)
            }
        }
        .onDisappear { screen.presetsWindowOpen = false }
        .onChange(of: screen.presetsWindowCloseRequest) { dismissWindow(id: Self.id) }
        .alert(deleteTitle, isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Удалить", role: .destructive) {
                if let index = deleting { delete(index) }
            }
            Button("Отмена", role: .cancel) {}
        }
    }

    private var deleteTitle: String {
        guard let index = deleting, controller.presets.indices.contains(index) else { return "" }
        return "Удалить «\(controller.presets[index].name)»?"
    }

    private var sidebar: some View {
        VStack(spacing: 0) {
            List(selection: Binding(get: { screen.presetsSelected }, set: { screen.presetsSelected = $0 })) {
                ForEach(Array(controller.presets.enumerated()), id: \.offset) { index, preset in
                    PresetLine(number: index + 1, preset: preset, active: PresetCatalog.matches(preset, controller.properties))
                        .padding(.vertical, 3)
                        .tag(index)
                        .contextMenu {
                            Button("Применить") { controller.applyPreset(index) }
                                .disabled(controller.phase != .ready || controller.isApplyingPreset)
                            Button("Дублировать") { screen.presetsSelected = controller.duplicatePreset(index) }
                            Divider()
                            Button("Удалить…", role: .destructive) { deleting = index }
                        }
                }
                .onMove { source, destination in
                    guard let from = source.first else { return }
                    // место вставки считается до того, как пресет убрали со старого места
                    let to = destination > from ? destination - 1 : destination
                    controller.movePreset(from: from, to: to)
                    screen.followPresetMove(from: from, to: to)
                }
            }
            Divider()
            HStack(spacing: 8) {
                Button {
                    screen.presetsSelected = controller.savePreset()
                    screen.presetsFocusName = true
                } label: {
                    Label("Сохранить текущие", systemImage: "plus")
                }
                .disabled(controller.phase != .ready)
                .help("Новый пресет — как сейчас настроена камера")
                Spacer()
                Button { deleting = screen.presetsSelected } label: {
                    Image(systemName: "minus")
                }
                .disabled(screen.presetsSelected == nil)
                .help("Удалить выбранный пресет")
            }
            .buttonStyle(.borderless)
            .padding(8)
        }
    }

    private func delete(_ index: Int) {
        controller.deletePreset(index)
        screen.followPresetDelete(index, remaining: controller.presets.count)
    }
}

/// Выбранный пресет: имя правится на месте, кнопки, ниже — настройки пресета группами, как во «Всех настройках».
private struct PresetEditor: View {
    let controller: CameraController
    let screen: ScreenState
    let index: Int
    let onDelete: () -> Void
    @State private var name = ""
    @FocusState private var nameFocused: Bool

    private static let groups: [SettingGroup] = [.exposure, .color, .focus]

    private static func previewCaption(_ preset: Preset) -> String {
        if preset.preview == nil { return "Кадра нет — «Взять с камеры» снимет его вместе со значениями" }
        if preset.previewOutdated { return "Кадр до правок: значения с тех пор меняли" }
        return "Кадр — когда значения взяли с камеры"
    }

    var body: some View {
        // пресет удалили — окно, пока убирает редактор, может ещё раз спросить его по старому номеру
        if controller.presets.indices.contains(index) {
            editor(controller.presets[index])
        }
    }

    private func editor(_ preset: Preset) -> some View {
        let online = controller.phase == .ready
        return VStack(alignment: .leading, spacing: 12) {
            if !online {
                Label("Камера не на связи — значения видно, выбирать их можно, когда камера подключена",
                      systemImage: "exclamationmark.triangle")
                    .font(.callout)
                    .foregroundStyle(.secondary)
            }
            HStack(alignment: .top, spacing: 14) {
                PresetThumb(preview: preset.preview, width: 176)
                VStack(alignment: .leading, spacing: 8) {
                    TextField("Название", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .font(.title3)
                        .focused($nameFocused)
                        // пустое имя не сохраняется: пока поле пустое, у пресета прежнее
                        .onChange(of: name) { _, value in controller.renamePreset(index, to: value) }
                    Text(Self.previewCaption(preset))
                        .font(.callout)
                        .foregroundStyle(preset.previewOutdated ? Color.orange : Color.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack(spacing: 8) {
                Button("Применить") { controller.applyPreset(index) }
                    .buttonStyle(.borderedProminent)
                    .disabled(!online || controller.isApplyingPreset)
                Button("Взять с камеры") { controller.recapturePreset(index) }
                    .disabled(!online || controller.isApplyingPreset)
                    .help("Все значения пресета — как сейчас на камере")
                Spacer()
                Button("Дублировать") { screen.presetsSelected = controller.duplicatePreset(index) }
                Button("Удалить…", role: .destructive, action: onDelete)
            }
            if let status = controller.statusMessage {
                Text(status).font(.callout).foregroundStyle(.secondary)
            }
            Form {
                ForEach(Self.groups, id: \.self) { group in
                    Section(group.title) {
                        ForEach(group.settings.filter(PresetCatalog.order.contains), id: \.self) { id in
                            PresetSettingRow(controller: controller, index: index, id: id, preset: preset, online: online)
                        }
                    }
                }
            }
            .formStyle(.grouped)
        }
        .padding(16)
        .onAppear {
            name = preset.name
            if screen.presetsFocusName {
                nameFocused = true
                screen.presetsFocusName = false
            }
        }
    }
}

/// Настройка в пресете: «менять» и значение. Выбор меняет только пресет — камеру не трогает.
private struct PresetSettingRow: View {
    let controller: CameraController
    let index: Int
    let id: SettingID
    let preset: Preset
    let online: Bool

    var body: some View {
        let value = preset.values[id]
        let included = value != nil && !preset.skip.contains(id)
        // температуру камера даёт менять только в «Цвет. темп.»: другой баланс белого в пресете её отменяет
        let blocked = id == .colorTemperature && !preset.skip.contains(.whiteBalance)
            && preset.values[.whiteBalance].map { $0 != WhiteBalanceTemperature.manualMode } ?? false
        let options = online ? PresetCatalog.options(id, controller.property(id), preset) : []
        HStack(spacing: 10) {
            Toggle(isOn: Binding(get: { included }, set: { controller.setPresetIncluded(index, id, $0) })) {
                Text(id.title)
            }
            .toggleStyle(.checkbox)
            .disabled(blocked || (!included && !controller.canIncludeInPreset(index, id)))
            Spacer()
            if blocked {
                Text("только при «Цвет. темп.»").font(.caption).foregroundStyle(.secondary)
            } else if let value, !options.isEmpty {
                Picker(id.title, selection: Binding(get: { value }, set: { controller.setPresetValue(index, id, $0) })) {
                    ForEach(options, id: \.value) { option in
                        Text(option.label).tag(option.value)
                    }
                }
                .labelsHidden()
                .frame(width: 180)
                .disabled(!included)
            } else {
                VStack(alignment: .trailing, spacing: 1) {
                    Text(value.map { ValueFormatter.label(id, $0) } ?? "—").monospacedDigit()
                    if online, value != nil {
                        Text("камера сейчас не даёт выбрать").font(.caption).foregroundStyle(.secondary)
                    }
                }
                .foregroundStyle(included ? .primary : .secondary)
            }
        }
        .opacity(blocked ? 0.55 : 1)
    }
}

/// Кадр пресета; у пресета без кадра — пустая плашка с камерой.
struct PresetThumb: View {
    let preview: Data?
    let width: CGFloat

    var body: some View {
        Group {
            if let image = preview.flatMap(Self.image) {
                Image(nsImage: image).resizable().aspectRatio(contentMode: .fill)
            } else {
                Image(systemName: "camera")
                    .font(width > 100 ? .title2 : .caption)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(Color.primary.opacity(0.08))
            }
        }
        .frame(width: width, height: (width * 9 / 16).rounded())
        .clipShape(RoundedRectangle(cornerRadius: width > 100 ? 8 : 4))
    }

    /// Кадры декодируются один раз: списки перерисовываются с каждым свойством камеры.
    private static let cache = NSCache<NSData, NSImage>()

    private static func image(_ data: Data) -> NSImage? {
        if let image = cache.object(forKey: data as NSData) { return image }
        guard let image = NSImage(data: data) else { return nil }
        cache.setObject(image, forKey: data as NSData)
        return image
    }
}

/// «Новый пресет»: кадр, который сохранится с пресетом, имя — подставлено «Пресет N», можно сразу печатать своё, —
/// и что в пресет войдёт. Кадр берётся, когда окошко открылось: сохраняется ровно то, что показали.
struct NewPresetSheet: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var name = ""
    @State private var preview: Data?
    @State private var saved = false
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Новый пресет").font(.headline)
            HStack(alignment: .top, spacing: 14) {
                PresetThumb(preview: preview, width: 192)
                VStack(alignment: .leading, spacing: 8) {
                    // Return — кнопка «Сохранить» (она по умолчанию), без своего onSubmit: иначе сохранилось бы дважды
                    TextField("Название", text: $name)
                        .textFieldStyle(.roundedBorder)
                        .focused($focused)
                    Text(PresetCatalog.summary(PresetCatalog.capture(name: name, from: controller.properties)))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            HStack {
                Spacer()
                Button("Отмена", role: .cancel) { screen.presetNaming = false }
                    .keyboardShortcut(.cancelAction)
                Button("Сохранить", action: save)
                    .keyboardShortcut(.defaultAction)
                    .disabled(controller.phase != .ready)
            }
        }
        .padding(20)
        .frame(width: 500)
        .onAppear {
            name = controller.nextPresetName
            preview = controller.presetPreviewNow()
            focused = true
        }
    }

    private func save() {
        guard !saved else { return }
        saved = true
        controller.savePreset(name: name, preview: preview)
        screen.presetNaming = false
    }
}

/// «Вид → Пресеты…» (⇧⌘P).
struct PresetsCommand: View {
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Button("Пресеты…") { openWindow(id: PresetsWindow.id) }
            .keyboardShortcut("p", modifiers: [.shift, .command])
    }
}
