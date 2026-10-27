import SwiftUI
import ZVCore

/// Лента камер слева под верхней панелью: главная и камеры в ленте — живые плитки. Порядок постоянный: щелчок по
/// плитке делает камеру главной на её месте, переставляют плитки перетаскиванием. Правый клик — «Сделать главной»,
/// «Убрать из ленты»; цепочка — связать с главной (группа). Видна, когда камер на связи две и больше.
struct CameraStrip: View {
    let controller: CameraController
    /// Плитку тащат: какую, с какого места начали и насколько сдвинули мышь.
    @State private var dragging: String?
    @State private var dragStart = 0
    @State private var dragTranslation: CGFloat = 0

    /// Шаг плиток: высота плитки и промежуток.
    private static let slot = CameraTile.size.height + 8

    var body: some View {
        let tiles = controller.stripTiles
        VStack(alignment: .leading, spacing: 8) {
            ForEach(tiles, id: \.self) { id in
                tile(id)
                    // пока плитку ведут, на её месте — бледный след: туда она встанет
                    .opacity(dragging == id ? 0.25 : 1)
                    .gesture(id == controller.currentCameraID ? AnyGesture(reorder(id).map { _ in })
                             : AnyGesture(reorder(id).exclusively(before: TapGesture().onEnded { controller.selectCamera(id) }).map { _ in }))
                    .modifier(SideMenu(controller: controller, id: id))
            }
        }
        .animation(.easeInOut(duration: 0.18), value: tiles)
        .coordinateSpace(name: Self.space)
        // сама плитка — над лентой, за мышью; соседние раздвигаются под ней
        .overlay(alignment: .topLeading) {
            if let dragging {
                tile(dragging)
                    .scaleEffect(1.04)
                    .shadow(color: .black.opacity(0.5), radius: 10, y: 4)
                    .offset(y: min(max(CGFloat(dragStart) * Self.slot + dragTranslation, 0), CGFloat(tiles.count - 1) * Self.slot))
                    .allowsHitTesting(false)
            }
        }
    }

    private static let space = "cameraStrip"

    /// Перетаскивание: плитка встаёт на место той, над чьей серединой её провели; щелчок без движения — не оно.
    private func reorder(_ id: String) -> some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .named(Self.space))
            .onChanged { value in
                let tiles = controller.stripTiles
                guard let index = tiles.firstIndex(of: id) else { return }
                if dragging != id {
                    dragging = id
                    dragStart = index
                }
                dragTranslation = value.translation.height
                let target = min(max(dragStart + Int((value.translation.height / Self.slot).rounded()), 0), tiles.count - 1)
                if target != index { controller.moveInStrip(id, onto: tiles[target]) }
            }
            .onEnded { _ in
                // плитка доезжает до своего места и только потом пропадает над лентой
                let index = controller.stripTiles.firstIndex(of: id) ?? dragStart
                withAnimation(.easeOut(duration: 0.15)) { dragTranslation = CGFloat(index - dragStart) * Self.slot }
                Task {
                    try? await Task.sleep(for: .milliseconds(150))
                    if dragging == id { dragging = nil }
                }
            }
    }

    @ViewBuilder private func tile(_ id: String) -> some View {
        if id == controller.currentCameraID {
            CameraTile(name: controller.currentCameraName, frame: controller.frame, main: true,
                       connected: controller.phase == .ready, recording: controller.isRecording,
                       battery: controller.property(.battery)?.current.intValue,
                       link: controller.mainInGroup && controller.sideCameras.contains { controller.roster.camera($0.id)?.linked == true }
                           ? .leads : .none)
        } else if let side = controller.sideCameras.first(where: { $0.id == id }) {
            let inGroup = controller.roster.camera(side.id)?.linked == true
            let link: CameraTile.Link = !inGroup ? .unlinked : controller.mainInGroup ? .linked : .waiting
            CameraTile(name: controller.roster.camera(side.id)?.name ?? "Камера", frame: side.frame, main: false,
                       connected: side.connected, recording: side.recording, battery: side.battery,
                       // горит — отвязать; в группе, а главная нет, или не в группе — связать с главной
                       link: link, onLink: { controller.linkCamera(side.id, link != .linked) })
        }
    }
}

/// У камеры ленты — подсказка и меню по правому клику; у главной их нет.
private struct SideMenu: ViewModifier {
    let controller: CameraController
    let id: String

    func body(content: Content) -> some View {
        if id != controller.currentCameraID, let side = controller.sideCameras.first(where: { $0.id == id }) {
            content
                .help(side.connected ? "Сделать главной; перетащите, чтобы поменять место"
                      : "Нет связи — программа стучится к камере снова")
                .contextMenu {
                    Button("Сделать главной") { controller.selectCamera(id) }
                        .disabled(!side.connected)
                    Button("Убрать из ленты") { controller.removeFromStrip(id) }
                }
        } else {
            content.help("Главная; перетащите, чтобы поменять место")
        }
    }
}

/// Плитка ленты: кадр, имя, «● REC», заряд, если мало, цепочка группы; у главной — синяя рамка.
private struct CameraTile: View {
    /// Цепочка: у главной — ведёт группу (без кнопки), у камеры ленты — кнопка. `waiting` — камера в группе, а главная
    /// нет: группа ждёт и не повторяет.
    enum Link { case none, leads, linked, waiting, unlinked }

    let name: String
    let frame: CGImage?
    let main: Bool
    let connected: Bool
    let recording: Bool
    let battery: Int64?
    var link: Link = .none
    var onLink: () -> Void = {}

    static let size = CGSize(width: 160, height: 90)

    var body: some View {
        ZStack {
            Color.black
            if let frame {
                Image(decorative: frame, scale: 1)
                    .resizable()
                    .aspectRatio(contentMode: .fill)
                    .frame(width: Self.size.width, height: Self.size.height)
                    .clipped()
                    .opacity(connected ? 1 : 0.35)
            }
            if !connected {
                Text("нет связи").font(.caption).foregroundStyle(.white.opacity(0.85))
            }
        }
        .frame(width: Self.size.width, height: Self.size.height)
        .overlay(alignment: .topTrailing) {
            if recording {
                Text("● REC")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(Color.red, in: RoundedRectangle(cornerRadius: 4))
                    .padding(4)
            }
        }
        .overlay(alignment: .topLeading) {
            // заряд — только когда его мало: иначе плитке хватает имени
            if let battery, battery >= 0, battery <= 20 {
                Text("\(battery)%")
                    .font(.caption2.weight(.semibold).monospacedDigit())
                    .foregroundStyle(.orange)
                    .padding(.horizontal, 5)
                    .padding(.vertical, 2)
                    .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 4))
                    .padding(4)
            }
        }
        .overlay(alignment: .bottomLeading) {
            Text(name)
                .font(.caption.weight(main ? .semibold : .regular))
                .foregroundStyle(.white)
                .lineLimit(1)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                .padding(4)
        }
        .overlay(alignment: .bottomTrailing) { linkMark }
        .clipShape(RoundedRectangle(cornerRadius: 8))
        .overlay(RoundedRectangle(cornerRadius: 8)
            .stroke(main ? Color.accentColor : Color.white.opacity(0.25), lineWidth: main ? 2 : 1))
        .contentShape(Rectangle())
    }

    @ViewBuilder private var linkMark: some View {
        switch link {
        case .none:
            EmptyView()
        case .leads:
            Image(systemName: "link")
                .font(.caption.weight(.semibold))
                .foregroundStyle(Color.accentColor)
                .padding(5)
                .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
                .padding(4)
                .help("Ведёт группу: связанные камеры повторяют за ней")
        case .linked, .waiting, .unlinked:
            Button(action: onLink) {
                Image(systemName: "link")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(link == .unlinked ? Color.white.opacity(0.6) : Color.white)
                    .padding(5)
                    .background(linkBackground, in: RoundedRectangle(cornerRadius: 4))
                    // приглушённая — на тёмной подложке: на светлом кадре синий вполсилы иначе блёклый
                    .background(Color.black.opacity(link == .waiting ? 0.55 : 0), in: RoundedRectangle(cornerRadius: 4))
            }
            .buttonStyle(.plain)
            .padding(4)
            .help(linkHelp)
            .accessibilityLabel(link == .linked ? "Отвязать от главной" : "Связать с главной")
        }
    }

    private var linkBackground: Color {
        switch link {
        case .linked: return .accentColor
        case .waiting: return Color.accentColor.opacity(0.4)
        default: return Color.black.opacity(0.55)
        }
    }

    private var linkHelp: String {
        switch link {
        case .linked: return "Отвязать от главной"
        case .waiting: return "В группе, но главная — не из неё: сейчас не повторяет. Связать с главной — вся группа будет повторять за ней"
        default: return "Связать с главной: её настройки и запись — и на этой камере"
        }
    }
}
