import CoreGraphics
import os
import SwiftUI
import ZVCore

/// Что считать для помощи в кадре — его читает поток кадров, а меняют экран (инструменты, увеличение) и камера (фото
/// или видео: от этого белый уровень превью).
final class AssistInput: Sendable {
    struct State: Sendable {
        var assist = MonitorAssist()
        /// Видимая часть кадра, доли.
        var visible = CGRect(x: 0, y: 0, width: 1, height: 1)
        /// Во сколько раз увеличена картинка на экране.
        var factor = 1.0
        /// В видео белое в превью — 234, в фото — 255.
        var video = true
        /// Волновая форма: какая; nil — выключена.
        var waveform: Waveform.Mode?

        /// Нужны ли точки кадра: для растра или волновой формы.
        var needsPixels: Bool { assist.needsRaster || waveform != nil }
    }

    private let state = OSAllocatedUnfairLock(initialState: State())

    var current: State { state.withLock { $0 } }

    func setAssist(_ assist: MonitorAssist) { state.withLock { $0.assist = assist } }

    func setView(visible: CGRect, factor: Double) {
        state.withLock {
            $0.visible = visible
            $0.factor = factor
        }
    }

    func setVideo(_ video: Bool) { state.withLock { $0.video = video } }

    func setWaveform(_ mode: Waveform.Mode?) { state.withLock { $0.waveform = mode } }
}

/// Что считается по точкам кадра в потоке кадров: растр помощи и волновая форма. Кадр раскладывается в точки один раз.
enum FrameTools {
    struct Result {
        var overlay: AssistOverlay?
        var waveform: WaveformTrace?
    }

    nonisolated static func analyze(_ frame: CGImage, levels: [Int], input: AssistInput.State) -> Result {
        guard input.needsPixels else { return Result() }
        let width = frame.width, height = frame.height, bytesPerRow = width * 4
        var rgba = [UInt8](repeating: 0, count: bytesPerRow * height)
        let drawn = rgba.withUnsafeMutableBytes { buffer -> Bool in
            guard let context = CGContext(data: buffer.baseAddress, width: width, height: height, bitsPerComponent: 8,
                                          bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
                                          bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return false }
            context.interpolationQuality = .none
            context.draw(frame, in: CGRect(x: 0, y: 0, width: width, height: height))
            return true
        }
        guard drawn else { return Result() }
        // шкала — как у гистограммы: от чёрного до белого превью
        let white = LumaStats.whiteLevel(levels: levels, video: input.video)
        var result = Result()
        if input.assist.needsRaster {
            result.overlay = AssistOverlay.render(rgba: rgba, width: width, height: height, white: white, input: input)
        }
        if let mode = input.waveform {
            result.waveform = WaveformTrace(Waveform.counts(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow,
                                                            mode: mode), white: white)
        }
        return result
    }

    static func image(rgba: [UInt8], width: Int, height: Int) -> CGImage? {
        guard let provider = CGDataProvider(data: Data(rgba) as CFData) else { return nil }
        return CGImage(width: width, height: height, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: width * 4,
                       space: CGColorSpaceCreateDeviceRGB(),
                       bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
    }
}

/// Волновая форма кадра: картинка следа и что по ней видно.
struct WaveformTrace {
    let image: CGImage?
    let counts: Waveform.Counts
    /// Белый уровень превью: на этой высоте — линия 100 %.
    let white: Int

    init(_ counts: Waveform.Counts, white: Int) {
        self.counts = counts
        self.white = white
        image = FrameTools.image(rgba: Waveform.image(counts), width: counts.columns, height: Waveform.rows)
    }
}

/// Растр зебры, false color и пикинга поверх видимой части кадра.
struct AssistOverlay {
    let image: CGImage
    /// Какая часть кадра (доли).
    let region: CGRect
    /// Сколько точек растра под зеброй и под пикингом, %.
    let zebraShare: Double
    let peakingShare: Double

    /// Растр по точкам кадра: яркость всего кадра, видимая часть — в размер растра, зебра, false color и пикинг.
    nonisolated static func render(rgba: [UInt8], width: Int, height: Int, white: Int, input: AssistInput.State) -> AssistOverlay? {
        let luma = AssistRaster.luma(rgba: rgba, width: width, height: height, bytesPerRow: width * 4)
        let v = input.visible
        let plan = AssistRaster.plan(frameWidth: width, frameHeight: height, visible: (v.minX, v.minY, v.width, v.height),
                                     factor: input.factor)
        let part = AssistRaster.resample(luma, width: width, height: height, plan: plan)
        guard let out = AssistRaster.render(luma: part, width: plan.width, height: plan.height, scale: plan.scale,
                                            white: white, assist: input.assist),
              let image = FrameTools.image(rgba: out.pixels, width: out.width, height: out.height)
        else { return nil }
        let r = plan.region
        return AssistOverlay(image: image,
                             region: CGRect(x: r.x / Double(width), y: r.y / Double(height),
                                            width: r.width / Double(width), height: r.height / Double(height)),
                             zebraShare: out.zebraShare, peakingShare: out.peakingShare)
    }
}

/// Маркеры поверх кадра: рамка другого формата (снаружи притемнено), безопасная зона пунктиром, сетка и центр.
/// rect — где кадр на экране (с увеличением); линии — одной толщины при любом увеличении.
struct AssistMarkers: View {
    let assist: MonitorAssist
    let frame: CGSize
    let rect: CGRect

    var body: some View {
        Canvas { context, size in
            let layout = FrameMarkers.layout(assist, frame: frame)
            func point(_ p: CGPoint) -> CGPoint { CGPoint(x: rect.minX + p.x * rect.width, y: rect.minY + p.y * rect.height) }
            func area(_ r: CGRect) -> CGRect {
                CGRect(x: rect.minX + r.minX * rect.width, y: rect.minY + r.minY * rect.height,
                       width: r.width * rect.width, height: r.height * rect.height)
            }
            // тень под линиями: видно и на светлом небе
            func stroke(_ path: Path, opacity: Double, width: CGFloat, dash: [CGFloat] = []) {
                context.stroke(path, with: .color(.black.opacity(0.35)), style: StrokeStyle(lineWidth: width + 1.5, dash: dash))
                context.stroke(path, with: .color(.white.opacity(opacity)), style: StrokeStyle(lineWidth: width, dash: dash))
            }
            let visible = CGRect(origin: .zero, size: size).intersection(rect)
            if let marker = layout.marker {
                let inner = area(marker)
                var outside = Path()
                outside.addRect(visible)
                outside.addRect(inner)
                context.fill(outside, with: .color(.black.opacity(0.45)), style: FillStyle(eoFill: true))
                stroke(Path(inner), opacity: 0.9, width: 1.5)
            }
            if let safe = layout.safe {
                stroke(Path(area(safe)), opacity: 0.75, width: 1, dash: [6, 4])
            }
            if !layout.grid.isEmpty {
                var grid = Path()
                for line in layout.grid {
                    grid.move(to: point(line.from))
                    grid.addLine(to: point(line.to))
                }
                stroke(grid, opacity: 0.55, width: 1)
            }
            if let center = layout.center {
                let c = point(center)
                var cross = Path()
                cross.move(to: CGPoint(x: c.x - 14, y: c.y))
                cross.addLine(to: CGPoint(x: c.x + 14, y: c.y))
                cross.move(to: CGPoint(x: c.x, y: c.y - 14))
                cross.addLine(to: CGPoint(x: c.x, y: c.y + 14))
                stroke(cross, opacity: 0.9, width: 1.5)
            }
        }
        .allowsHitTesting(false)
    }
}

/// Легенда false color — что значит каждый цвет. Видна, пока false color включён.
struct FalseColorLegend: View {
    var body: some View {
        HStack(spacing: 10) {
            ForEach(AssistRaster.FalseColorBand.allCases, id: \.self) { band in
                HStack(spacing: 4) {
                    let (r, g, b) = band.rgb
                    RoundedRectangle(cornerRadius: 2)
                        .fill(Color(red: Double(r) / 255, green: Double(g) / 255, blue: Double(b) / 255))
                        .frame(width: 12, height: 12)
                    Text(band.title)
                }
            }
        }
        .font(.caption)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
        .help("False color: цвет — по яркости кадра (доли белого превью). Остальное — серым.")
        .allowsHitTesting(false)
    }
}

/// Меню «Монитор»: лента значений выбранного инструмента и под ней подсказка, ниже — плитки инструментов, как в меню Fn.
/// Касание плитки выбирает её, касание выбранной — включает или выключает.
struct AssistMenu: View {
    let controller: CameraController
    let screen: ScreenState

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 5)

    var body: some View {
        let tool = screen.assistSelected
        VStack(spacing: 10) {
            VStack(spacing: 6) {
                HStack(spacing: 4) {
                    ForEach(tool.options, id: \.value) { option in
                        let current = screen.assist.value(tool) == option.value
                        Button(option.label) { screen.setAssist(tool, option.value) }
                            .buttonStyle(.plain)
                            .font(.body.weight(current ? .bold : .regular))
                            .padding(.horizontal, 10)
                            .padding(.vertical, 5)
                            .foregroundStyle(current ? Color.black : Color.white)
                            .background(current ? Color.white : Color.clear, in: RoundedRectangle(cornerRadius: 7))
                    }
                }
                if tool == .overlay {
                    // эталон: откуда он и как взять новый
                    HStack(spacing: 10) {
                        Text(controller.reference.map { "Эталон: \($0.source)" } ?? "Эталона пока нет")
                            .font(.callout)
                            .foregroundStyle(.secondary)
                        Button("Новый кадр") {
                            controller.takeReferenceFromFrame()
                            if screen.assist.overlay == .off { screen.toggleAssist(.overlay) }
                        }
                        .disabled(controller.frame == nil)
                        .help("Эталон — текущий кадр. Снимок из галереи — кнопкой «Наложить на кадр» в галерее")
                    }
                }
                Text(tool.help)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(10)
            .frame(maxWidth: .infinity)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
            LazyVGrid(columns: columns, spacing: 6) {
                ForEach(MonitorTool.allCases, id: \.self) { tool in
                    Button { screen.tapAssistTile(tool) } label: {
                        AssistTile(title: tool.title, value: screen.assist.label(tool),
                                   isOn: screen.assist.isOn(tool), isSelected: tool == screen.assistSelected)
                    }
                    .buttonStyle(.plain)
                    .help(tool.help)
                }
                Button { screen.turnOffAssist() } label: {
                    AssistTile(title: "Всё", value: "Выключить", isOn: false, isSelected: false)
                }
                .buttonStyle(.plain)
                .disabled(!screen.assist.isActive)
                .opacity(screen.assist.isActive ? 1 : 0.4)
                .help("Выключить все инструменты; что было включено, запомнится")
            }
            .padding(8)
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        }
        .frame(maxWidth: 820)
        // клики по промежуткам внутри меню не должны его закрывать
        .contentShape(Rectangle())
        .onTapGesture {}
        .onScrollWheelStep { screen.stepAssist($0) }
    }
}

private struct AssistTile: View {
    let title: String
    let value: String
    let isOn: Bool
    let isSelected: Bool

    var body: some View {
        VStack(spacing: 3) {
            Text(title).font(.caption).foregroundStyle(isSelected ? .white.opacity(0.9) : .secondary)
            Text(value)
                .font(.headline)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
                .foregroundStyle(isOn || isSelected ? Color.white : Color.white.opacity(0.45))
        }
        .frame(maxWidth: .infinity, minHeight: 54)
        .background(isSelected ? Color.accentColor : isOn ? Color.white.opacity(0.16) : Color.white.opacity(0.07),
                    in: RoundedRectangle(cornerRadius: 10))
        .contentShape(RoundedRectangle(cornerRadius: 10))
    }
}
