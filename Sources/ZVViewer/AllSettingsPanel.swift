import SwiftUI
import ZVCore

/// Все настройки по группам поверх видео. Клик по изменяемой строке открывает ленту значений.
struct AllSettingsPanel: View {
    let controller: CameraController
    @State private var group: SettingGroup = .exposure
    @State private var editing: SettingID?

    var body: some View {
        HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Все настройки").font(.headline).padding(.bottom, 8)
                ForEach(SettingGroup.allCases, id: \.self) { g in
                    Button {
                        group = g
                        editing = nil
                    } label: {
                        Text(g.title)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 10)
                            .padding(.vertical, 7)
                            .background(g == group ? Color.accentColor : Color.clear, in: RoundedRectangle(cornerRadius: 8))
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                Spacer()
            }
            .frame(width: 200)
            .padding(14)
            Divider()
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(group.settings, id: \.self) { id in
                        row(id)
                    }
                }
                .padding(14)
            }
        }
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 16))
        // клики внутри панели не должны закрывать её
        .contentShape(RoundedRectangle(cornerRadius: 16))
        .onTapGesture {}
    }

    @ViewBuilder private func row(_ id: SettingID) -> some View {
        let property = controller.property(id)
        let writable = controller.phase == .ready && property?.isWritable == true
        Button {
            if writable { editing = editing == id ? nil : id }
        } label: {
            HStack {
                Text(id.title)
                Spacer()
                if controller.pending.contains(id) { ProgressView().controlSize(.mini) }
                Text(ValueFormatter.label(id, property)).fontWeight(.semibold).monospacedDigit()
            }
            .padding(.vertical, 9)
            .padding(.horizontal, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(writable ? Color.primary : Color.secondary)
        if editing == id {
            ValueStrip(id: id, controller: controller).padding(.bottom, 8)
        }
        Divider()
    }
}
