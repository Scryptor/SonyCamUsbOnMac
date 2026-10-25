import SwiftUI
import ZVCore

/// Лента камер слева под верхней панелью: главная и камеры в ленте — живые плитки. Щелчок по плитке — она главная;
/// правый клик — «Сделать главной», «Убрать из ленты»; цепочка — связать с главной (группа). Видна, когда камер на связи
/// две и больше.
struct CameraStrip: View {
    let controller: CameraController

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            CameraTile(name: controller.currentCameraName, frame: controller.frame, main: true,
                       connected: controller.phase == .ready, recording: controller.isRecording,
                       battery: controller.property(.battery)?.current.intValue,
                       link: controller.sideCameras.contains { controller.roster.camera($0.id)?.linked == true } ? .leads : .none)
            ForEach(controller.sideCameras) { side in
                let linked = controller.roster.camera(side.id)?.linked == true
                CameraTile(name: controller.roster.camera(side.id)?.name ?? "Камера", frame: side.frame, main: false,
                           connected: side.connected, recording: side.recording, battery: side.battery,
                           link: linked ? .linked : .unlinked, onLink: { controller.linkCamera(side.id, !linked) })
                    .onTapGesture { controller.selectCamera(side.id) }
                    .help(side.connected ? "Сделать главной" : "Нет связи — программа стучится к камере снова")
                    .contextMenu {
                        Button("Сделать главной") { controller.selectCamera(side.id) }
                            .disabled(!side.connected)
                        Button("Убрать из ленты") { controller.removeFromStrip(side.id) }
                    }
            }
        }
    }
}

/// Плитка ленты: кадр, имя, «● REC», заряд, если мало, цепочка группы; у главной — синяя рамка.
private struct CameraTile: View {
    /// Цепочка: у главной — ведёт группу (без кнопки), у камеры ленты — кнопка «связать».
    enum Link { case none, leads, linked, unlinked }

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
        case .linked, .unlinked:
            Button(action: onLink) {
                Image(systemName: "link")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(link == .linked ? Color.white : Color.white.opacity(0.6))
                    .padding(5)
                    .background(link == .linked ? Color.accentColor : Color.black.opacity(0.55), in: RoundedRectangle(cornerRadius: 4))
            }
            .buttonStyle(.plain)
            .padding(4)
            .help(link == .linked ? "Отвязать от главной" : "Связать с главной: её настройки и запись — и на этой камере")
            .accessibilityLabel(link == .linked ? "Отвязать от главной" : "Связать с главной")
        }
    }
}
