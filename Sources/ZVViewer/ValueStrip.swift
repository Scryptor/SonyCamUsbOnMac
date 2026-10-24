import SwiftUI
import ZVCore

/// Лента значений выбранной настройки; у баланса белого рядом — тонкая настройка.
struct ValueStrip: View {
    let id: SettingID
    let controller: CameraController

    var body: some View {
        let property = controller.property(id)
        let options = property.map { SettingOptions.options(id, $0) } ?? []
        let current = property?.current.intValue
        HStack(spacing: 16) {
            if options.isEmpty {
                Text("Камера сейчас не даёт менять «\(id.title)»")
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity)
            } else {
                ScrollViewReader { proxy in
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 4) {
                            ForEach(options, id: \.value) { option in
                                Button(option.label) { controller.request(id, option.value) }
                                    .buttonStyle(.plain)
                                    .font(.body.monospacedDigit().weight(option.value == current ? .bold : .regular))
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .foregroundStyle(option.value == current ? Color.black : Color.white)
                                    .background(option.value == current ? Color.white : Color.clear,
                                                in: RoundedRectangle(cornerRadius: 7))
                                    .id(option.value)
                            }
                        }
                    }
                    .onAppear { proxy.scrollTo(current, anchor: .center) }
                    .onChange(of: current) { _, value in
                        withAnimation { proxy.scrollTo(value, anchor: .center) }
                    }
                }
            }
            if id == .whiteBalance {
                Divider().frame(height: 60)
                WhiteBalanceFineTune(controller: controller)
            }
        }
        .padding(10)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
    }
}

/// Цветовая температура (только в режиме K) и сдвиг A–B / G–M.
private struct WhiteBalanceFineTune: View {
    let controller: CameraController
    @State private var kelvin: Double = 5500
    @State private var pad: CGPoint?

    var body: some View {
        HStack(spacing: 14) {
            temperature
            shiftPad
        }
    }

    @ViewBuilder private var temperature: some View {
        let property = controller.property(.colorTemperature)
        let range = property?.range
        VStack(alignment: .leading, spacing: 4) {
            Text("Цвет. темп.: \(ValueFormatter.label(.colorTemperature, property))")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let range, range.max > range.min {
                Slider(value: $kelvin, in: Double(range.min)...Double(range.max), step: Double(range.step)) { editing in
                    if !editing { controller.request(.colorTemperature, Int64(kelvin)) }
                }
                .frame(width: 160)
                .disabled(property?.isWritable != true)
                .onAppear { kelvin = Double(property?.current.intValue ?? range.min) }
                .onChange(of: property?.current.intValue) { _, value in
                    if let value { kelvin = Double(value) }
                }
            }
        }
    }

    @ViewBuilder private var shiftPad: some View {
        let ab = controller.property(.wbShiftAB)
        let gm = controller.property(.wbShiftGM)
        let size: CGFloat = 64
        VStack(spacing: 2) {
            Text("A–B \(ValueFormatter.label(.wbShiftAB, ab))  G–M \(ValueFormatter.label(.wbShiftGM, gm))")
                .font(.caption)
                .foregroundStyle(.secondary)
            ZStack {
                RoundedRectangle(cornerRadius: 6)
                    .fill(LinearGradient(colors: [.blue.opacity(0.6), .orange.opacity(0.6)], startPoint: .leading, endPoint: .trailing))
                    .overlay(RoundedRectangle(cornerRadius: 6)
                        .fill(LinearGradient(colors: [.green.opacity(0.45), .pink.opacity(0.45)], startPoint: .top, endPoint: .bottom))
                        .blendMode(.screen))
                Circle().fill(.white).frame(width: 9, height: 9)
                    .position(pad ?? CGPoint(x: fraction(ab) * size, y: fraction(gm) * size))
            }
            .frame(width: size, height: size)
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { pad = CGPoint(x: min(max($0.location.x, 0), size), y: min(max($0.location.y, 0), size)) }
                .onEnded { _ in
                    if let pad, let ab, let gm {
                        request(.wbShiftAB, ab, fraction: pad.x / size)
                        request(.wbShiftGM, gm, fraction: pad.y / size)
                    }
                    pad = nil
                })
            .disabled(ab?.isWritable != true || gm?.isWritable != true)
        }
    }

    /// Положение значения на оси: 0 — минимум диапазона, 1 — максимум.
    private func fraction(_ property: SonyProperty?) -> CGFloat {
        guard let range = property?.range, range.max > range.min, let v = property?.current.intValue else { return 0.5 }
        return CGFloat(v - range.min) / CGFloat(range.max - range.min)
    }

    private func request(_ id: SettingID, _ property: SonyProperty, fraction: CGFloat) {
        guard let range = property.range, range.step > 0 else { return }
        let raw = Double(range.min) + Double(fraction) * Double(range.max - range.min)
        let steps = ((raw - Double(range.min)) / Double(range.step)).rounded()
        let value = min(max(range.min + Int64(steps) * range.step, range.min), range.max)
        if value != property.current.intValue { controller.request(id, value) }
    }
}
