import SwiftUI
import ZVCore

/// Зум с экрана: «T» и «W» в капсуле, как рычажок зума камеры, — держите, и камера зумирует (объектив с приводом или
/// Clear Image Zoom), отпустили — стоп. Между ними — где зум на шкале, под капсулой — масштаб, если он больше ×1,0.
struct ZoomRocker: View {
    let controller: CameraController

    var body: some View {
        VStack(spacing: 5) {
            VStack(spacing: 4) {
                key("T", direction: 1)
                if let fraction = controller.zoomBar?.fraction {
                    ZStack(alignment: .bottom) {
                        Capsule().fill(.white.opacity(0.18))
                        Capsule().fill(.white.opacity(0.85)).frame(height: max(3, 34 * fraction))
                    }
                    .frame(width: 4, height: 34)
                    .accessibilityHidden(true)
                }
                key("W", direction: -1)
            }
            .padding(4)
            .background(.ultraThinMaterial, in: Capsule())
            Text(controller.zoomScaleLabel ?? "Зум")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.8))
                .monospacedDigit()
        }
        .onDisappear { controller.zoom(0) }
    }

    private func key(_ title: String, direction: Int) -> some View {
        let moving = controller.zoomDirection == direction
        return Text(title)
            .font(.system(size: 13, weight: .bold))
            .frame(width: 34, height: 34)
            .foregroundStyle(moving ? Color.black : Color.white)
            .background(moving ? Color.white : Color.clear, in: Circle())
            .contentShape(Circle())
            // держат — зум едет; отпустили (или жест прервался) — стоп
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in controller.zoom(direction) }
                .onEnded { _ in controller.zoom(0) })
            .help(direction > 0 ? "Зум к теле — держите (T)" : "Зум к широкому — держите (W)")
            .accessibilityLabel(direction > 0 ? "Зум к теле" : "Зум к широкому")
            .accessibilityAddTraits(.isButton)
    }
}

/// Фокус касанием: горит — щелчок по кадру ставит туда фокус (в зонах «Точка» и «Зона» — точка AF, в остальных —
/// касание экрана камеры: фокус или слежение, как настроено в камере), а на кадре видны рамки фокуса камеры.
struct TouchFocusButton: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        let method = controller.touchFocusMethod
        Button { screen.toggleTouchFocus() } label: {
            TouchFocusIcon()
                .foregroundStyle(.white)
                .frame(width: 42, height: 42)
                .background(screen.touchFocusOn ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.ultraThinMaterial), in: Circle())
                .opacity(method == nil ? 0.5 : 1)
        }
        .buttonStyle(.plain)
        .help(method == nil ? TouchFocus.refusal(focusMode: controller.property(.focusMode)?.current.intValue)
              : screen.touchFocusOn ? "Фокус касанием включён: щелчок по кадру ставит фокус" : "Фокус касанием: щелчок по кадру ставит фокус")
        .accessibilityLabel("Фокус касанием")
    }
}

/// Значок фокуса касанием: уголки рамки фокуса и точка в середине.
struct TouchFocusIcon: View {
    var body: some View {
        Canvas { context, size in
            let s = min(size.width, size.height) / 24
            let o = CGPoint(x: size.width / 2 - 12 * s, y: size.height / 2 - 12 * s)
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: o.x + x * s, y: o.y + y * s) }
            var corners = Path()
            for (cx, cy, dx, dy) in [(4.0, 4.0, 1.0, 1.0), (20, 4, -1, 1), (4, 20, 1, -1), (20, 20, -1, -1)] as [(CGFloat, CGFloat, CGFloat, CGFloat)] {
                corners.move(to: p(cx, cy + 5 * dy))
                corners.addLine(to: p(cx, cy))
                corners.addLine(to: p(cx + 5 * dx, cy))
            }
            context.stroke(corners, with: .foreground, style: StrokeStyle(lineWidth: 2 * s, lineCap: .round, lineJoin: .round))
            context.fill(Path(ellipseIn: CGRect(origin: p(9.5, 9.5), size: CGSize(width: 5 * s, height: 5 * s))), with: .foreground)
        }
        .frame(width: 22, height: 22)
    }
}

/// Поверх кадра: рамки фокуса камеры (зелёные — фокус найден, слежение — пунктиром) и отметка касания, которая гаснет
/// за секунду. rect — где кадр на экране, с увеличением.
struct FocusFramesLayer: View {
    let controller: CameraController
    let rect: CGRect

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: controller.touchMark == nil)) { timeline in
            Canvas { context, _ in
                func area(_ r: CGRect) -> CGRect {
                    CGRect(x: rect.minX + r.minX * rect.width, y: rect.minY + r.minY * rect.height,
                           width: r.width * rect.width, height: r.height * rect.height)
                }
                for frame in controller.focusFrames {
                    let color: Color = frame.focused ? .green : .white
                    let style = StrokeStyle(lineWidth: 1.5, dash: frame.kind == .tracking ? [5, 3] : [])
                    context.stroke(Path(area(frame.rect)), with: .color(.black.opacity(0.35)), style: StrokeStyle(lineWidth: 3))
                    context.stroke(Path(area(frame.rect)), with: .color(color.opacity(0.95)), style: style)
                }
                if let mark = controller.touchMark {
                    let age = timeline.date.timeIntervalSince(mark.at)
                    if age < 1 {
                        // сжимается к точке и гаснет — как отметка касания на экране камеры
                        let side = 70 - 20 * min(age / 0.25, 1)
                        let c = CGPoint(x: rect.minX + mark.point.x * rect.width, y: rect.minY + mark.point.y * rect.height)
                        let box = CGRect(x: c.x - side / 2, y: c.y - side / 2, width: side, height: side)
                        context.stroke(Path(box), with: .color(.yellow.opacity(1 - max(0, age - 0.5) * 2)), lineWidth: 2)
                    }
                }
            }
        }
        .allowsHitTesting(false)
    }
}

/// Шкала фокуса — ручной фокус с экрана (в MF): слева ближе, справа дальше. Кнопки — шаги 7, 3 и 1; перетаскивание по
/// шкале — как поворот кольца фокуса, колесо мыши — по шагу. Метка — где фокус, если камера его отдаёт.
struct FocusScale: View {
    let controller: CameraController
    @State private var dragged: CGFloat = 0
    @State private var width: CGFloat = 1

    var body: some View {
        HStack(spacing: 10) {
            ForEach([FocusDrive.Step.large, .medium, .small], id: \.self) { step in button(step, nearer: true) }
            VStack(spacing: 3) {
                ruler
                HStack {
                    Text("ближе")
                    Spacer()
                    Text("шкала фокуса").foregroundStyle(.white.opacity(0.5))
                    Spacer()
                    Text("дальше")
                }
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.75))
            }
            .frame(width: 300)
            ForEach([FocusDrive.Step.small, .medium, .large], id: \.self) { step in button(step, nearer: false) }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 14))
        // щелчок по шкале — не щелчок по кадру под ней
        .contentShape(Rectangle())
        .onTapGesture {}
        .onScrollWheelStep { controller.focusStep($0) }
    }

    private var ruler: some View {
        Canvas { context, size in
            let mid = size.height / 2
            // деления — как на кольце фокуса; сдвигаются, пока шкалу тянут
            let spacing: CGFloat = 12
            let shift = dragged.truncatingRemainder(dividingBy: spacing)
            var x = shift < 0 ? shift + spacing : shift
            while x < size.width {
                var tick = Path()
                tick.move(to: CGPoint(x: x, y: mid - 5))
                tick.addLine(to: CGPoint(x: x, y: mid + 5))
                context.stroke(tick, with: .color(.white.opacity(0.4)), lineWidth: 1)
                x += spacing
            }
            if let position = controller.focalPosition {
                let px = size.width * CGFloat(position) / 100
                context.fill(Path(roundedRect: CGRect(x: px - 2, y: 2, width: 4, height: size.height - 4), cornerRadius: 2),
                             with: .color(.yellow))
            }
        }
        .frame(height: 24)
        .contentShape(Rectangle())
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = max($0, 1) }
        .gesture(DragGesture(minimumDistance: 1)
            .onChanged { value in
                // тянут влево — ближе: шаги по мере движения, без накопления дробей
                let target = FocusDrive.steps(forDrag: value.translation.width / width)
                let sent = FocusDrive.steps(forDrag: dragged / width)
                if target != sent { controller.focusStep(target - sent) }
                dragged = value.translation.width
            }
            .onEnded { _ in dragged = 0 })
        .help("Тяните влево — ближе, вправо — дальше; колесо мыши — по шагу")
    }

    private func button(_ step: FocusDrive.Step, nearer: Bool) -> some View {
        let arrows = String(repeating: nearer ? "‹" : "›", count: step == .large ? 3 : step == .medium ? 2 : 1)
        return Button { controller.focusStep(Int(FocusDrive.value(step, nearer: nearer))) } label: {
            Text(arrows)
                .font(.system(size: 17, weight: .bold))
                .frame(minWidth: 30, minHeight: 30)
                .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .help((nearer ? "Ближе" : "Дальше") + " на \(step.rawValue)")
        .accessibilityLabel((nearer ? "Фокус ближе на " : "Фокус дальше на ") + "\(step.rawValue)")
    }
}
