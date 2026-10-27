import SwiftUI

/// Значки верхней панели — нарисованы сами, одинаково с планшетом: значок повторяет то, что включает. Гистограмма —
/// столбики с пунктиром на третях, волновая форма — след яркости над шкалой, монитор — кадр с полосами зебры, строка состояния — точка, Wi-Fi и батарея,
/// сохранить пресет — звезда с плюсом, настройки — шестерёнка. Геометрия — в квадрате 52×52, как кнопка планшета, и масштабируется под кнопку.
enum TopBarIcon {
    case histogram, waveform, monitor, statusBar, savePreset, settings
}

struct TopBarIconView: View {
    let icon: TopBarIcon

    var body: some View {
        Canvas { context, size in
            // рисунок занимает середину квадрата — крупнее на четверть, центр квадрата — в центре кнопки
            let scale = size.width / 52 * 1.25
            context.translateBy(x: size.width / 2 - 26 * scale, y: size.height / 2 - 26 * scale)
            context.scaleBy(x: scale, y: scale)
            Self.draw(icon, in: context)
        }
        .accessibilityHidden(true)
    }

    private static func draw(_ icon: TopBarIcon, in context: GraphicsContext) {
        let white = GraphicsContext.Shading.color(.white)
        let round = { (width: CGFloat) in StrokeStyle(lineWidth: width, lineCap: .round, lineJoin: .round) }
        switch icon {
        case .histogram:
            let bars: [(x: CGFloat, height: CGFloat)] = [(16, 5), (18.6, 11), (21.2, 16), (23.8, 14), (26.4, 9), (29, 7),
                                                         (31.6, 5), (34.2, 2.5)]
            for bar in bars {
                context.fill(Path(roundedRect: CGRect(x: bar.x, y: 35 - bar.height, width: 2, height: bar.height),
                                  cornerRadius: 0.5), with: white)
            }
            // трети шкалы — как пунктир на самой гистограмме
            var thirds = Path()
            for x in [22.3, 29.7] as [CGFloat] {
                thirds.move(to: CGPoint(x: x, y: 15))
                thirds.addLine(to: CGPoint(x: x, y: 35))
            }
            context.stroke(thirds, with: .color(.white.opacity(0.55)), style: StrokeStyle(lineWidth: 1, dash: [2, 2]))
        case .waveform:
            // шкала: низ и линия 100 % пунктиром; между ними — след яркости слева направо
            var scale = Path()
            scale.move(to: CGPoint(x: 15, y: 35))
            scale.addLine(to: CGPoint(x: 37, y: 35))
            context.stroke(scale, with: .color(.white.opacity(0.55)), style: StrokeStyle(lineWidth: 1))
            var top = Path()
            top.move(to: CGPoint(x: 15, y: 17))
            top.addLine(to: CGPoint(x: 37, y: 17))
            context.stroke(top, with: .color(.white.opacity(0.55)), style: StrokeStyle(lineWidth: 1, dash: [2, 2]))
            var trace = Path()
            trace.move(to: CGPoint(x: 15, y: 30))
            trace.addCurve(to: CGPoint(x: 24, y: 22), control1: CGPoint(x: 18, y: 31), control2: CGPoint(x: 20, y: 22))
            trace.addCurve(to: CGPoint(x: 31, y: 27), control1: CGPoint(x: 27, y: 22), control2: CGPoint(x: 28, y: 28))
            trace.addCurve(to: CGPoint(x: 37, y: 20), control1: CGPoint(x: 34, y: 26), control2: CGPoint(x: 35, y: 20))
            context.stroke(trace, with: white, style: round(2))
        case .monitor:
            let frame = CGRect(x: 15, y: 18, width: 22, height: 16)
            context.stroke(Path(roundedRect: frame, cornerRadius: 2.5), with: white, style: round(1.8))
            // полосы зебры наискось в правой половине кадра
            var stripes = Path()
            for x in [24.0, 29, 34] as [CGFloat] {
                stripes.move(to: CGPoint(x: x - 4, y: 34))
                stripes.addLine(to: CGPoint(x: x + 4, y: 18))
            }
            var clipped = context
            clipped.clip(to: Path(frame.insetBy(dx: 1.5, dy: 1.5)))
            clipped.stroke(stripes, with: white, style: StrokeStyle(lineWidth: 2))
        case .statusBar:
            context.fill(Path(ellipseIn: CGRect(x: 13.3, y: 24.8, width: 4.4, height: 4.4)), with: white)
            var wifi = Path()
            wifi.move(to: CGPoint(x: 21, y: 25))
            wifi.addQuadCurve(to: CGPoint(x: 29, y: 25), control: CGPoint(x: 25, y: 21))
            wifi.move(to: CGPoint(x: 23, y: 27.5))
            wifi.addQuadCurve(to: CGPoint(x: 27, y: 27.5), control: CGPoint(x: 25, y: 25.6))
            context.stroke(wifi, with: white, style: round(1.8))
            context.fill(Path(ellipseIn: CGRect(x: 23.9, y: 28.9, width: 2.2, height: 2.2)), with: white)
            context.stroke(Path(roundedRect: CGRect(x: 31, y: 23, width: 7, height: 8), cornerRadius: 1.5), with: white,
                           style: round(1.8))
            var terminal = Path()
            terminal.move(to: CGPoint(x: 39.5, y: 25.5))
            terminal.addLine(to: CGPoint(x: 39.5, y: 28.5))
            context.stroke(terminal, with: white, style: round(1.8))
            context.fill(Path(roundedRect: CGRect(x: 32.5, y: 24.5, width: 4, height: 5), cornerRadius: 0.5), with: white)
        case .savePreset:
            let star: [(CGFloat, CGFloat)] = [(25, 17), (27.4, 22), (32.8, 22.6), (28.8, 26.3), (29.9, 31.6), (25, 28.9),
                                              (20.1, 31.6), (21.2, 26.3), (17.2, 22.6), (22.6, 22)]
            var path = Path()
            path.addLines(star.map { CGPoint(x: $0.0, y: $0.1) })
            path.closeSubpath()
            path.move(to: CGPoint(x: 33, y: 29))
            path.addLine(to: CGPoint(x: 33, y: 36))
            path.move(to: CGPoint(x: 29.5, y: 32.5))
            path.addLine(to: CGPoint(x: 36.5, y: 32.5))
            context.stroke(path, with: white, style: round(1.9))
        case .settings:
            context.fill(gear, with: white, style: FillStyle(eoFill: true))
        }
    }

    /// Шестерёнка на 8 зубьев с отверстием, центр — (26, 26).
    private static let gear: Path = {
        let points: [(CGFloat, CGFloat)] = [
            (24.19, 18.82), (24.36, 16.14), (27.64, 16.14), (27.81, 18.82), (29.79, 19.65), (31.81, 17.86), (34.14, 20.19),
            (32.35, 22.21), (33.18, 24.19), (35.86, 24.36), (35.86, 27.64), (33.18, 27.81), (32.35, 29.79), (34.14, 31.81),
            (31.81, 34.14), (29.79, 32.35), (27.81, 33.18), (27.64, 35.86), (24.36, 35.86), (24.19, 33.18), (22.21, 32.35),
            (20.19, 34.14), (17.86, 31.81), (19.65, 29.79), (18.82, 27.81), (16.14, 27.64), (16.14, 24.36), (18.82, 24.19),
            (19.65, 22.21), (17.86, 20.19), (20.19, 17.86), (22.21, 19.65),
        ]
        var path = Path()
        path.addLines(points.map { CGPoint(x: $0.0, y: $0.1) })
        path.closeSubpath()
        path.addEllipse(in: CGRect(x: 22.6, y: 22.6, width: 6.8, height: 6.8))
        return path
    }()
}
