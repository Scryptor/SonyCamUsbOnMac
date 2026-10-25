import SwiftUI

/// Экран, пока камеры нет или она не в режиме PC Remote. Это пустой видоискатель: в рамке фокуса — что программа делает
/// и что сделать вам, под ней — камеры (подключить, сопрячь), ниже — как подключить: раскрыто, пока программа не знает
/// ни одной камеры, потом свёрнуто.
struct WaitingView: View {
    enum Kind { case searching, notPCRemote }

    let kind: Kind
    let controller: CameraController
    @AppStorage(Prefs.Key.wifiEnabled) private var wifiEnabled = true
    /// «Как подключить камеру» раскрыли или свернули; nil — как по умолчанию.
    @State private var guideOpen: Bool?

    private var paired: Int { controller.roster.known.filter(\.paired).count }

    var body: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 30) {
                    viewfinder
                    notices
                    cameras
                    guide
                }
                .frame(maxWidth: 880)
                .padding(.horizontal, 48)
                .padding(.vertical, 56)
                // помещается — по центру окна, нет — прокручивается
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .background(.black)
        .overlay(alignment: .topLeading) { SettingsButton().padding(16) }
        .environment(\.colorScheme, .dark)
    }

    // MARK: - Рамка фокуса: что происходит

    private var headline: String {
        if kind == .notPCRemote { return "Камера на кабеле, но не в режиме PC Remote" }
        if controller.isPairing { return "Сопряжение с камерой" }
        if !wifiEnabled { return "Подключите камеру кабелем" }
        if paired == 0 { return "Подключите камеру" }
        return paired > 1 ? "Ищу камеры" : "Ищу камеру"
    }

    private var lead: String {
        if kind == .notPCRemote {
            return "Включите на камере PC Remote по USB — шаги ниже — и переподключите кабель. По Wi-Fi камера может подключиться и так."
        }
        if controller.isPairing { return "На камере: MENU › Network › PC Remote Function › Pairing — и подтвердите сопряжение там." }
        if !wifiEnabled { return "Подключение по Wi-Fi выключено." }
        if paired == 0 { return "Кабелем USB-C — сразу. По Wi-Fi — сначала один раз сопрягите камеру с этим Mac." }
        return "Включите камеру — программа подключится сама, по кабелю USB-C или по Wi-Fi."
    }

    private var viewfinder: some View {
        VStack(spacing: 12) {
            Text(headline)
                .font(.system(size: 30, weight: .semibold))
                .multilineTextAlignment(.center)
            Text(lead)
                .font(.title3)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
            // ход дела: сопряжение — что с камерой сейчас, иначе — когда следующая попытка
            if let progress = controller.isPairing ? controller.wifiStatus : (kind == .searching ? controller.connectionMessage : nil) {
                Text(progress).font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            if controller.isPairing {
                Button("Отмена") { controller.cancelPairing() }
                    .controlSize(.large)
                    .padding(.top, 6)
            } else if kind == .searching, !wifiEnabled {
                Button("Включить Wi-Fi") { wifiEnabled = true }
                    .controlSize(.large)
                    .padding(.top, 6)
            }
        }
        .frame(maxWidth: 520)
        .padding(.horizontal, 48)
        .padding(.vertical, 44)
        // ищет — рамка «дышит», как камера, что ищет фокус; сопряжение — синяя: программа ждёт ответа камеры
        .background {
            FocusFrame(color: controller.isPairing ? .accentColor : .white,
                       breathing: kind == .searching && (controller.isPairing || wifiEnabled))
        }
    }

    // MARK: - Что мешает

    @ViewBuilder private var notices: some View {
        let cable = kind == .searching && controller.cableAccessHint
        // во время сопряжения сообщение — ход дела, оно в рамке; без сопряжения — что мешает подключиться
        let problem = controller.isPairing ? nil : controller.wifiStatus
        if cable || problem != nil {
            VStack(alignment: .leading, spacing: 14) {
                if cable {
                    notice("Камера на кабеле видна, но macOS пока не даёт программе доступ к ней",
                           "Нажмите «Разрешить» в окне про доступ к файлам на съёмном томе — после установки или обновления "
                           + "программы macOS спрашивает об этом снова. Окна нет — Системные настройки → Конфиденциальность и "
                           + "безопасность → Файлы и папки → Sony Cam Viewer → Съёмные тома. Доступ уже есть — переподключите кабель.")
                }
                if let problem { notice(problem, nil) }
            }
            .frame(maxWidth: 640, alignment: .leading)
        }
    }

    private func notice(_ title: String, _ detail: String?) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: "exclamationmark.triangle.fill")
            VStack(alignment: .leading, spacing: 4) {
                Text(title).fontWeight(detail == nil ? .regular : .semibold)
                if let detail { Text(detail).foregroundStyle(.orange.opacity(0.85)) }
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(.orange)
    }

    // MARK: - Камеры

    private var cameras: some View {
        // список ищет камеры в сети, пока он на экране, — поэтому он здесь всегда, и пустой тоже
        CameraList(controller: controller, showsPairNew: true, prominent: true,
                   emptyNote: "Программа пока не видит ни одной камеры: подключите кабелем или сопрягите по Wi-Fi.")
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
        .frame(maxWidth: 640, alignment: .leading)
        .background(Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 14))
    }

    // MARK: - Как подключить

    private var guide: some View {
        let open = guideOpen ?? (paired == 0 || kind == .notPCRemote)
        return VStack(spacing: 22) {
            Button {
                withAnimation(.easeInOut(duration: 0.2)) { guideOpen = !open }
            } label: {
                HStack(spacing: 6) {
                    Text("Как подключить камеру")
                    Image(systemName: "chevron.down")
                        .font(.callout.weight(.semibold))
                        .rotationEffect(.degrees(open ? 0 : -90))
                }
                .font(.headline)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open {
                HStack(alignment: .top, spacing: 44) {
                    Steps(title: "По кабелю USB", items: [
                        Text.menu("MENU", "Network", "Smartphone Connect") + .separator + .setting("Smartphone Connection", "Off"),
                        Text.menu("MENU", "Network", "PC Remote Function") + .separator + .setting("PC Remote", "On")
                            + Text(", ") + .setting("PC Remote Cnct Method", "USB"),
                        Text(kind == .notPCRemote ? "Переподключите кабель" : "Подключите камеру к Mac кабелем USB-C"),
                    ])
                    Steps(title: "По Wi-Fi", items: [
                        Text("Камера и Mac — в одной сети Wi-Fi. На камере: ")
                            + .menu("MENU", "Network", "Wi-Fi Settings", "Access Point Set."),
                        Text.menu("MENU", "Network", "PC Remote Function") + .separator + .setting("PC Remote", "On")
                            + Text(", ") + .setting("PC Remote Cnct Method", "Wi-Fi Access Point"),
                        Text("Один раз — сопряжение: «Сопрячь новую камеру…» выше, а на камере ")
                            + .menu("MENU", "Network", "PC Remote Function", "Pairing"),
                    ], note: Text("Без роутера: на камере ") + .setting("PC Remote Cnct Method", "Wi-Fi Direct")
                        + Text(", а Mac подключите к сети камеры DIRECT-… — пароль на экране камеры. Интернета на Mac в это время не будет."))
                }
                .transition(.opacity)
            }
        }
    }
}

/// Шаги одного способа: номер — это и правда порядок действий.
private struct Steps: View {
    let title: String
    let items: [Text]
    var note: Text?

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(title).font(.title3.weight(.semibold))
            ForEach(items.indices, id: \.self) { index in
                HStack(alignment: .firstTextBaseline, spacing: 10) {
                    Text("\(index + 1)")
                        .font(.callout.weight(.semibold).monospacedDigit())
                        .foregroundStyle(.secondary)
                        .frame(width: 22, height: 22)
                        .overlay(Circle().strokeBorder(.secondary.opacity(0.5)))
                    items[index]
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            if let note {
                note.font(.callout).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension Text {
    /// Путь в меню камеры, как на её экране: разделы — ярче текста вокруг, между ними «›».
    static func menu(_ items: String...) -> Text {
        items.dropFirst().reduce(Text(items[0]).foregroundStyle(.primary)) { $0 + .separator + Text($1).foregroundStyle(.primary) }
    }

    static var separator: Text { Text(" › ").foregroundStyle(.tertiary) }

    /// Пункт меню и что в нём выбрать: значение — синим.
    static func setting(_ name: String, _ value: String) -> Text {
        Text(name + ": ").foregroundStyle(.primary) + Text(value).fontWeight(.semibold).foregroundStyle(Color.accentColor)
    }
}

/// Рамка фокуса из четырёх уголков, как в видоискателе. Пока программа ищет камеру, рамка «дышит»: чуть сжимается и
/// бледнеет и снова раскрывается — без мельтешения; с «Уменьшить движение» стоит на месте.
private struct FocusFrame: View {
    let color: Color
    let breathing: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        let moving = breathing && !reduceMotion
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: !moving)) { context in
            // вдох и выдох — 3,2 с
            let phase = moving ? (1 + cos(context.date.timeIntervalSinceReferenceDate * .pi * 2 / 3.2)) / 2 : 1
            FocusBrackets()
                .stroke(color, style: StrokeStyle(lineWidth: 2, lineCap: .round, lineJoin: .round))
                .scaleEffect(0.97 + 0.03 * phase)
                .opacity(0.4 + 0.6 * phase)
        }
        .accessibilityHidden(true)
    }
}

/// Четыре уголка по углам прямоугольника.
private struct FocusBrackets: Shape {
    var length: CGFloat = 30

    func path(in rect: CGRect) -> Path {
        let l = min(length, rect.width / 3, rect.height / 3)
        var path = Path()
        for (corner, dx, dy) in [(CGPoint(x: rect.minX, y: rect.minY), 1.0, 1.0), (CGPoint(x: rect.maxX, y: rect.minY), -1.0, 1.0),
                                 (CGPoint(x: rect.maxX, y: rect.maxY), -1.0, -1.0), (CGPoint(x: rect.minX, y: rect.maxY), 1.0, -1.0)] {
            path.move(to: CGPoint(x: corner.x, y: corner.y + dy * l))
            path.addLine(to: corner)
            path.addLine(to: CGPoint(x: corner.x + dx * l, y: corner.y))
        }
        return path
    }
}
