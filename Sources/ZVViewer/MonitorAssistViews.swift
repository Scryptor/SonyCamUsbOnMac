import CoreGraphics
import os
import SwiftUI
import ZVCore

/// Что считать для помощи в кадре и как преобразовать картинку — его читает поток кадров, а меняют экран (инструменты,
/// увеличение, пипетка) и камера (фото или видео: от этого белый уровень превью; профиль изображения: от него «Авто» в LUT).
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
        /// Свойства главной камеры, по которым «Авто» выбирает LUT: профиль изображения 0xD23F, гамма профиля 0xD0E1 и его
        /// цвет 0xD0E9. nil — камеры нет или такого свойства у неё нет (у ZV-E10 гаммы и цвета нет).
        var pictureProfile: Int64?
        var ppGamma: Int64?
        var colorMode: Int64?
        /// Куб своего LUT (режим «Свой»): кладёт тот, кто умеет читать файлы (хранилище LUT), по выбранному `assist.lutFile`.
        /// Пока файла нет или он не читается — nil, и «Свой» не меняет картинку.
        var fileLUT: ColorLUT?
        /// Пипетка: точка в долях кадра, каким он виден (с зеркалом — уже повёрнутого); nil — не ставили, тогда центр.
        var probe: CGPoint?

        /// Нужны ли точки кадра: для растра, вектороскопа, пипетки или волновой формы.
        var needsPixels: Bool { assist.needsPixels || waveform != nil }

        /// Какой встроенный LUT на экране: выбранный рукой, а у «Авто» — по профилю главной камеры; nil — встроенного нет
        /// («Выкл», «Свой» и «Авто», которому не по чему выбрать).
        var builtinLUT: BuiltinLUT? {
            LUTChoice.resolve(assist.lut, pictureProfile: pictureProfile, ppGamma: ppGamma, colorMode: colorMode)
        }

        /// Куб, который ложится на картинку: встроенный (один на вид на всю программу) или свой файл; nil — LUT нет. Решает
        /// он, а не `assist.transformsPicture`: «Авто» без профиля картинку не меняет.
        var lut: ColorLUT? { assist.lut == .file ? fileLUT : builtinLUT?.cube }

        /// Точка пипетки в долях кадра: поставленная или центр.
        var probePoint: CGPoint { probe ?? CGPoint(x: 0.5, y: 0.5) }
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

    /// Профиль изображения главной камеры — при каждом снимке её свойств и nil, когда камеры нет: «Авто» следует за ним с
    /// ближайшего кадра.
    func setCamera(pictureProfile: Int64?, gamma: Int64?, colorMode: Int64?) {
        state.withLock {
            $0.pictureProfile = pictureProfile
            $0.ppGamma = gamma
            $0.colorMode = colorMode
        }
    }

    /// Куб выбранного своего LUT (nil — файла нет). Сюда его кладёт хранилище LUT: при выборе файла, добавлении и удалении.
    func setFileLUT(_ lut: ColorLUT?) { state.withLock { $0.fileLUT = lut } }

    /// Пипетка: точка в долях кадра, каким он виден; nil — снова в центре.
    func setProbe(_ point: CGPoint?) { state.withLock { $0.probe = point } }
}

/// Что считается по точкам кадра в потоке кадров: растр помощи, волновая форма, вектороскоп и пипетка. Кадр раскладывается в
/// точки один раз.
enum FrameTools {
    struct Result {
        var overlay: AssistOverlay?
        var waveform: WaveformTrace?
        /// Белый уровень, по которому считались гистограмма и помощь: 255 при помощи по картинке с LUT, иначе по кадру
        /// (234 в видео, 255 в фото).
        var white = 255
        /// Средняя яркость кадра помощи, 0…255 (Rec. 709, как у гистограммы): по той же картинке, по которой считается
        /// помощь — с LUT или без.
        var meanLuma = 0.0
        /// Размах яркости p99 − p1 по тем же уровням (см. `LumaStats.spread`): у плоского лога мал, с LUT шире.
        var lumaSpread = 0
        /// Картинка вектороскопа 128×128 RGBA, середина самой полной корзины (Cb, Cr) и отсчёты у шести меток 75 %; nil —
        /// вектороскоп выключен.
        var vectorscope: [UInt8]?
        var vectorscopePeak: (cb: Double, cr: Double)?
        var vectorscopeTargets: [(name: String, count: Int)] = []
        /// С каким масштабом (1 или 2) посчитан вектороскоп.
        var vectorscopeZoom = 1
        /// Что показала пипетка; nil — выключена.
        var probe: ColorProbe.Reading?
    }

    /// Белый уровень шкалы помощи: 255, если помощь считается по картинке с LUT и LUT есть (после LUT чёрный и белый — снова
    /// 0 и 255), иначе по кадру. Одно правило для гистограммы, зебры, false color, волновой формы и пипетки.
    nonisolated static func whiteLevel(levels: [Int], input: AssistInput.State) -> Int {
        input.assist.lutScopes && input.lut != nil ? 255 : LumaStats.whiteLevel(levels: levels, video: input.video)
    }

    /// Средняя яркость по уровням кадра, 0…255; 0 — кадр пуст.
    nonisolated static func meanLuma(levels: [Int]) -> Double {
        var total = 0, weighted = 0
        for (level, points) in levels.enumerated() {
            total += points
            weighted += level * points
        }
        return total > 0 ? Double(weighted) / Double(total) : 0
    }

    /// frame — кадр помощи: тот, на котором считаются уровни, — картинка с LUT или сигнал камеры, уже повёрнутые.
    nonisolated static func analyze(_ frame: CGImage, levels: [Int], input: AssistInput.State) -> Result {
        // шкала — как у гистограммы: от чёрного до белого превью
        let white = whiteLevel(levels: levels, input: input)
        var result = Result(white: white, meanLuma: meanLuma(levels: levels), lumaSpread: LumaStats.spread(levels: levels))
        guard input.needsPixels else { return result }
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
        guard drawn else { return result }
        if input.assist.needsRaster {
            result.overlay = AssistOverlay.render(rgba: rgba, width: width, height: height, white: white, input: input)
        }
        if let mode = input.waveform {
            result.waveform = WaveformTrace(Waveform.counts(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow,
                                                            mode: mode), white: white)
        }
        if input.assist.vectorscope {
            let zoom = input.assist.vectorscopeZoom
            let counts = Vectorscope.counts(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow, zoom: zoom)
            result.vectorscope = Vectorscope.image(counts: counts)
            result.vectorscopePeak = Vectorscope.peak(counts: counts, zoom: zoom)
            result.vectorscopeTargets = Vectorscope.targetCounts(counts: counts, zoom: zoom)
            result.vectorscopeZoom = zoom
        }
        if input.assist.probe {
            // метка в долях кадра, каким он виден, — а кадр помощи уже повёрнутый, так что доли те же; пипетке нужны точки
            let point = input.probePoint
            result.probe = ColorProbe.read(rgba: rgba, width: width, height: height, bytesPerRow: bytesPerRow,
                                           at: CGPoint(x: point.x * Double(width), y: point.y * Double(height)), white: white)
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

/// Вектороскоп кадра: картинка точек 128×128 (плоскость Cb, Cr) и где у неё самая полная корзина.
struct VectorscopeTrace {
    let image: CGImage?
    /// Масштаб, с которым посчитан: 1 или 2.
    let zoom: Int
    /// Середина самой полной корзины в единицах Cb и Cr (при ×2 — с поправкой на масштаб); nil — кадр пуст.
    let peak: (cb: Double, cr: Double)?
    /// Сколько отсчётов у каждой из шести меток 75 % (R, Mg, B, Cy, G, Yl) — для API и проверок.
    let targets: [(name: String, count: Int)]

    init(pixels: [UInt8], zoom: Int, peak: (cb: Double, cr: Double)?, targets: [(name: String, count: Int)] = []) {
        image = FrameTools.image(rgba: pixels, width: Vectorscope.bins, height: Vectorscope.bins)
        self.zoom = zoom
        self.peak = peak
        self.targets = targets
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

/// Меню «Монитор»: лента значений выбранного инструмента и под ней подсказка, ниже — плитки инструментов, как в меню Fn:
/// четырнадцать плиток в две строки по семь (тринадцать инструментов и «Всё — выключить»).
/// Касание плитки выбирает её, касание выбранной — включает или выключает.
struct AssistMenu: View {
    let controller: CameraController
    let screen: ScreenState
    /// Список своих LUT меняется (добавили, удалили) — плитка «LUT» и её полоса перерисовываются.
    @ObservedObject private var luts: LUTStore

    private let columns = Array(repeating: GridItem(.flexible(), spacing: 6), count: 7)

    init(controller: CameraController, screen: ScreenState) {
        self.controller = controller
        self.screen = screen
        _luts = ObservedObject(wrappedValue: screen.luts)
    }

    /// Значение на плитке. У «Авто» в LUT — «Авто: S-Log3»: какой LUT выбран по профилю камеры сейчас. Считается по свойствам,
    /// за которыми следит экран (`autoLUT`), — а не по тому, что лежит в замке потока кадров: тот вид не обновил бы. У «Свой» —
    /// имя файла, пока он лежит в папке, иначе «Свой».
    private func tileValue(_ tool: MonitorTool) -> String {
        guard tool == .lut else { return screen.assist.label(tool) }
        switch screen.assist.lut {
        case .auto:
            return LUTChoice.autoLabel(controller.autoLUT)
        case .file:
            if let name = screen.assist.lutFile, luts.names.contains(name) { return name }
            return "Свой"
        default:
            return screen.assist.label(tool)
        }
    }

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
                            screen.showOverlay()
                        }
                        .disabled(controller.frame == nil)
                        .help("Эталон — текущий кадр. Снимок из галереи — кнопкой «Наложить на кадр» в галерее")
                        Button("Из файла…") { chooseReferenceFile(controller: controller, screen: screen) }
                            .help("Эталон — картинка из файла: JPEG, PNG, HEIC… Её можно и просто перетащить на окно")
                    }
                }
                if tool == .lut {
                    LUTStrip(controller: controller, screen: screen, luts: luts)
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
                        AssistTile(title: tool.title, value: tileValue(tool),
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

/// Под вариантами LUT: «Помощь — по картинке с LUT» и — у «Свой» — список своих файлов, «Добавить LUT…» и «Удалить».
private struct LUTStrip: View {
    let controller: CameraController
    let screen: ScreenState
    @ObservedObject var luts: LUTStore

    var body: some View {
        let assist = screen.assist
        // выбранный файл, если он лежит в папке: пропавший из папки не считается выбранным
        let chosen = assist.lutFile.flatMap { luts.names.contains($0) ? $0 : nil }
        VStack(spacing: 6) {
            if assist.lut == .file {
                if luts.names.isEmpty {
                    Text("Своих LUT пока нет: «Добавить LUT…» или перетащите файл .cube на окно")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                } else {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 100, maximum: 190), spacing: 4)], spacing: 4) {
                        ForEach(luts.names, id: \.self) { name in
                            let current = name == chosen
                            Button { screen.selectLUTFile(name) } label: {
                                Text(name)
                                    .lineLimit(1)
                                    .truncationMode(.middle)
                                    .frame(maxWidth: .infinity)
                                    .padding(.horizontal, 10)
                                    .padding(.vertical, 5)
                                    .foregroundStyle(current ? Color.black : Color.white)
                                    .background(current ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 7))
                                    .contentShape(RoundedRectangle(cornerRadius: 7))
                            }
                            .buttonStyle(.plain)
                            .font(.body.weight(current ? .bold : .regular))
                            .help(name)
                        }
                    }
                    // ширина — по числу имён (не больше шести в ряду): ряд стоит посередине, как строки над ним и под ним
                    .frame(maxWidth: CGFloat(min(luts.names.count, 6)) * 114)
                }
            }
            HStack(spacing: 10) {
                Toggle("Помощь — по картинке с LUT", isOn: Binding(get: { screen.assist.lutScopes }, set: { screen.setLUTScopes($0) }))
                    .toggleStyle(.switch)
                    .controlSize(.small)
                    .help("Гистограмма, волновая форма, вектороскоп, зебра, false color, пикинг и пипетка считаются по картинке "
                        + "с LUT, и белое в них — 255. Выключено — по сигналу камеры, только повёрнутому.")
                if assist.lut == .file {
                    Button("Добавить LUT…") { chooseLUTFile(controller: controller, screen: screen) }
                        .help("Файл 3D LUT в формате .cube (до 12 штук). Его можно и просто перетащить на окно")
                    Button("Удалить") { if let chosen { screen.deleteLUT(chosen) } }
                        .disabled(chosen == nil)
                        .help("Убрать выбранный LUT из папки программы; исходный файл остаётся где был")
                }
            }
        }
        .onAppear { luts.refresh() }
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
