import SwiftUI
import ZVCore

/// Изменённый пресет — оранжевым: точка на кнопке, «изменён» в списках.
let presetModifiedColor = Color.orange

/// Кнопка пресетов вверху слева: на ней — текущий пресет (оранжевая точка — камера от него отличается), иначе
/// «Пресеты». Нажатие — список: применить, сохранить, открыть окно пресетов.
struct PresetPicker: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        let changes = controller.currentPresetChanges
        Button { screen.presetMenuOpen.toggle() } label: {
            HStack(spacing: 6) {
                Text(title)
                if !changes.isEmpty {
                    Circle().fill(presetModifiedColor).frame(width: 7, height: 7)
                }
                Image(systemName: "chevron.down").font(.caption2.weight(.semibold))
            }
            .lineLimit(1)
            .padding(.horizontal, 10)
            .frame(height: 26)
            .background(Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 7))
        }
        .buttonStyle(.plain)
        .help(changes.isEmpty ? "Пресеты: выбрать, сохранить, настроить"
              : "«\(controller.currentPresetIndex.map { controller.presets[$0].name } ?? "")» изменён: "
                + changes.map(\.label).joined(separator: " · "))
        .popover(isPresented: Binding(get: { screen.presetMenuOpen }, set: { screen.presetMenuOpen = $0 }),
                 arrowEdge: .bottom) {
            PresetMenu(controller: controller, screen: screen)
        }
    }

    /// Длинное имя не должно растянуть верхний ряд до строки состояния.
    private var title: String {
        guard let index = controller.currentPresetIndex else { return "Пресеты" }
        let name = controller.presets[index].name
        return "\(index + 1) · " + (name.count > 24 ? name.prefix(23) + "…" : name)
    }
}

/// Список пресетов режима камеры под кнопкой: номер, имя, что он ставит; галочка — стоит на камере, «изменён» — текущий,
/// от которого камера отличается. Нажатие применяет. Внизу — «Сохранить в «…»» (только когда текущий изменён),
/// «Новый пресет…» и «Настроить…».
private struct PresetMenu: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var hovered: Int?

    var body: some View {
        let current = controller.currentPresetIndex
        let changes = controller.currentPresetChanges
        VStack(alignment: .leading, spacing: 0) {
            Text(PresetLibrary.title(controller.presetMode))
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 12)
                .padding(.top, 10)
            if controller.presets.isEmpty {
                Text(PresetLibrary.emptyHint(controller.presetMode))
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(12)
            } else if controller.presets.count > 8 {
                ScrollView { rows(current: current, modified: !changes.isEmpty) }.frame(height: 360)
            } else {
                rows(current: current, modified: !changes.isEmpty)
            }
            Divider()
            if let current, !changes.isEmpty {
                saveChanges(name: controller.presets[current].name, changes: changes)
                Divider()
            }
            HStack {
                Button("Новый пресет…") {
                    screen.presetMenuOpen = false
                    screen.presetNaming = true
                }
                .disabled(controller.phase != .ready)
                .help("Новый пресет из текущих настроек камеры")
                Spacer()
                Button("Настроить…") {
                    screen.openPresetsWindow(selecting: current ?? (controller.presets.isEmpty ? nil : 0))
                }
            }
            .padding(10)
        }
        .frame(width: 360)
    }

    /// «Сохранить в «Вечер»» и под ним — что на камере не так, как в пресете.
    private func saveChanges(name: String, changes: [PresetChange]) -> some View {
        Button {
            screen.presetMenuOpen = false
            controller.savePresetChanges()
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Сохранить в «\(name)»")
                Text(changes.map(\.label).joined(separator: " · "))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(hovered == -1 ? Color.primary.opacity(0.08) : Color.clear, in: RoundedRectangle(cornerRadius: 6))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(controller.isApplyingPreset)
        .help("Записать в пресет, как сейчас настроена камера")
        .onHover { inside in
            if inside { hovered = -1 } else if hovered == -1 { hovered = nil }
        }
        .padding(6)
    }

    /// Галочка — у каждого пресета, который стоит на камере; на кнопке — текущий.
    private func rows(current: Int?, modified: Bool) -> some View {
        VStack(spacing: 2) {
            ForEach(Array(controller.presets.enumerated()), id: \.offset) { index, preset in
                Button {
                    screen.presetMenuOpen = false
                    controller.applyPreset(index)
                } label: {
                    PresetLine(number: index + 1, preset: preset, active: PresetCatalog.matches(preset, controller.properties),
                               modified: modified && index == current)
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

/// Строка пресета в списках: номер (он же клавиша 1–9), имя, что он ставит; галочка — стоит на камере, «изменён» —
/// текущий, от которого камера отличается.
struct PresetLine: View {
    let number: Int
    let preset: Preset
    let active: Bool
    var modified = false

    var body: some View {
        HStack(spacing: 10) {
            Text("\(number)")
                .font(.callout.monospacedDigit())
                .foregroundStyle(.secondary)
                .frame(width: 18, alignment: .trailing)
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
            } else if modified {
                Text("изменён").font(.caption).foregroundStyle(presetModifiedColor)
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
                // своё имя у каждого пресета: поле имени не тянет текст от прежнего — и когда под тем же номером
                // оказался другой пресет: сменился режим камеры, удалили, переставили
                PresetEditor(controller: controller, screen: screen, index: index) { deleting = index }
                    .id("\(controller.presetMode.rawValue) \(index) \(controller.presetListVersion)")
            } else {
                Text(controller.presets.isEmpty ? PresetLibrary.emptyHint(controller.presetMode) : "Выберите пресет слева.")
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(40)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 660, minHeight: 460)
        // пока держат «Посмотреть» — окна не видно, но кнопку оно не отпускает
        .background(WindowAlpha(alpha: screen.presetPeeking ? 0 : 1))
        .onAppear {
            screen.presetsWindowOpen = true
            if !(screen.presetsSelected.map(controller.presets.indices.contains) ?? false) {
                screen.presetsSelected = controller.currentPresetIndex ?? (controller.presets.isEmpty ? nil : 0)
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
        let current = controller.currentPresetIndex
        let modified = !controller.currentPresetChanges.isEmpty
        return VStack(alignment: .leading, spacing: 0) {
            Text(PresetLibrary.title(controller.presetMode))
                .font(.headline)
                .padding(.horizontal, 12)
                .padding(.top, 10)
                .padding(.bottom, 4)
            List(selection: Binding(get: { screen.presetsSelected }, set: { screen.presetsSelected = $0 })) {
                ForEach(Array(controller.presets.enumerated()), id: \.offset) { index, preset in
                    PresetLine(number: index + 1, preset: preset, active: PresetCatalog.matches(preset, controller.properties),
                               modified: modified && index == current)
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
                    Label("Новый пресет", systemImage: "plus")
                }
                .disabled(controller.phase != .ready)
                .help("Новый пресет из текущих настроек камеры")
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
            TextField("Название", text: $name)
                .textFieldStyle(.roundedBorder)
                .font(.title3)
                .focused($nameFocused)
                // пустое имя не сохраняется: пока поле пустое, у пресета прежнее
                .onChange(of: name) { _, value in controller.renamePreset(index, to: value) }
            HStack(spacing: 8) {
                Button("Применить") { controller.applyPreset(index) }
                    .buttonStyle(.borderedProminent)
                    .disabled(!online || controller.isApplyingPreset)
                PeekButton(controller: controller, screen: screen, index: index,
                           enabled: online && !controller.isApplyingPreset)
                Button("Взять с камеры") { controller.recapturePreset(index) }
                    .disabled(!online || controller.isApplyingPreset)
                    .help("Все значения пресета — как сейчас на камере")
                Spacer()
                Button("Дублировать") { screen.presetsSelected = controller.duplicatePreset(index) }
                Button("Удалить…", role: .destructive, action: onDelete)
            }
            if controller.currentPresetIndex == index, case let changes = controller.currentPresetChanges, !changes.isEmpty {
                Label("На камере изменено: " + changes.map(\.label).joined(separator: " · ")
                      + ". «Взять с камеры» запишет это в пресет.", systemImage: "pencil")
                    .font(.callout)
                    .foregroundStyle(presetModifiedColor)
                    .fixedSize(horizontal: false, vertical: true)
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

/// «Посмотреть»: пока кнопку держат, окно пресетов прозрачно, а пресет стоит на камере; отпустили — камера как была.
private struct PeekButton: View {
    let controller: CameraController
    let screen: ScreenState
    let index: Int
    let enabled: Bool

    var body: some View {
        Text("Посмотреть")
            .padding(.horizontal, 10)
            .padding(.vertical, 3)
            .background(Color.primary.opacity(screen.presetPeeking ? 0.25 : 0.1), in: RoundedRectangle(cornerRadius: 6))
            .opacity(enabled || screen.presetPeeking ? 1 : 0.4)
            .help("Удерживайте: пока кнопка нажата, пресет стоит на камере, а окно не мешает смотреть. Отпустите — всё как было")
            // нажатие длится, сколько держат: «долгое нажатие» здесь никогда не наступает
            .onLongPressGesture(minimumDuration: 3600, maximumDistance: .infinity, perform: {}) { pressing in
                if pressing {
                    guard enabled else { return }
                    screen.presetPeeking = true
                    controller.beginPresetPreview(index)
                } else if screen.presetPeeking {
                    screen.presetPeeking = false
                    controller.endPresetPreview()
                }
            }
    }
}

/// Прозрачность окна, в котором стоит вид.
private struct WindowAlpha: NSViewRepresentable {
    let alpha: CGFloat

    func makeNSView(context: Context) -> NSView { NSView() }

    func updateNSView(_ view: NSView, context: Context) {
        view.window?.alphaValue = alpha
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
