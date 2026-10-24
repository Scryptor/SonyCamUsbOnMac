import SwiftUI
import ZVCore

/// Меню Fn: лента значений над сеткой 2×6, как в самой камере.
struct FnMenu: View {
    let controller: CameraController
    @Binding var selected: SettingID
    let onAllSettings: () -> Void

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 6)

    var body: some View {
        VStack(spacing: 10) {
            ValueStrip(id: selected, controller: controller)
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(SettingCatalog.fnTopRow + SettingCatalog.fnBottomRow, id: \.self) { slot in
                    tile(slot)
                }
            }
            .padding(8)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
        .frame(maxWidth: 920)
        // клики по промежуткам внутри меню не должны его закрывать
        .contentShape(Rectangle())
        .onTapGesture {}
        .onScrollWheelStep { controller.requestStep(selected, offset: $0) }
    }

    @ViewBuilder private func tile(_ slot: FnSlot) -> some View {
        switch slot {
        case let .setting(id):
            let enabled = controller.phase == .ready && controller.property(id)?.isWritable == true
            Button { selected = id } label: {
                TileLabel(title: id.shortTitle, value: ValueFormatter.label(id, controller.property(id)),
                          isSelected: id == selected, isPending: controller.pending.contains(id))
            }
            .buttonStyle(.plain)
            .disabled(!enabled)
            .opacity(enabled ? 1 : 0.4)
        case .allSettings:
            Button(action: onAllSettings) {
                TileLabel(title: "Все", value: "⚙︎", isSelected: false, isPending: false)
            }
            .buttonStyle(.plain)
        }
    }
}

private struct TileLabel: View {
    let title: String
    let value: String
    let isSelected: Bool
    let isPending: Bool

    var body: some View {
        VStack(spacing: 3) {
            Text(title).font(.caption).foregroundStyle(isSelected ? .white.opacity(0.9) : .secondary)
            HStack(spacing: 4) {
                Text(value).font(.headline.monospacedDigit()).lineLimit(1).minimumScaleFactor(0.7)
                if isPending { ProgressView().controlSize(.mini) }
            }
        }
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(isSelected ? Color.accentColor : Color.white.opacity(0.07), in: RoundedRectangle(cornerRadius: 10))
        .contentShape(RoundedRectangle(cornerRadius: 10))
    }
}
