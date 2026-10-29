import SwiftUI
import ZVCore

/// Пипетка на экране: метка 20 pt с перекрестием в точке кадра и рядом табличка из двух строк — «47 % · кожа» и
/// «R 182 · G 140 · B 121». Табличка справа от метки, у правого края и рядом с колонкой кнопок справа — слева, у нижнего —
/// поднимается (`ProbeLayout`).
///
/// Только рисует: жестов у слоя нет, и касания проходят сквозь него к кадру. Ставит метку щелчок по кадру (`LiveScreen`:
/// пока пипетка включена, щелчок ставит её, а не фокус), тащит — жест, начатый на метке (`ZoomableVideo`: он же двигает
/// увеличенную картинку, поэтому щипок и прокрутка работают и над самой меткой, а перетаскивание метки картинку не двигает).
///
/// Метка и табличка живут в экранных долях кадра (`ScreenState.probePoint`): с увеличением они едут вместе с картинкой, а
/// с зеркалом остаются на месте экрана — меряется то, что под меткой видно. Пока метка за краем экрана (увеличение
/// сдвинули), её и таблички нет.
///
/// Цифры берутся с контроллера пять раз в секунду: кадров до тридцати, и чаще их не прочитать. Сам `/state` отдаёт
/// показание без задержки.
struct ProbeView: View {
    let controller: CameraController
    let screen: ScreenState
    /// Размер кадра в точках и размер экрана: по ним и по увеличению видно, где кадр на экране.
    let frame: CGSize
    let view: CGSize
    /// Показание на табличке сейчас; nil — кадра с пипеткой ещё не было.
    @State private var shown: ColorProbe.Reading?
    /// Размер таблички: от него зависит, с какой стороны от метки ей хватит места.
    @State private var plate = CGSize(width: 150, height: 40)

    /// Как часто обновляются цифры.
    static let interval = Duration.milliseconds(200)
    /// Пока показания нет, смотрим чаще: табличка появляется сразу после первого кадра, а не через пятую долю секунды.
    private static let firstLook = Duration.milliseconds(50)
    /// Сторона места под метку: кольцо 20 pt с обводкой снаружи.
    private static let markerSide: CGFloat = 26

    var body: some View {
        let rect = screen.zoom.imageRect(view: view, frame: frame)
        let center = ProbeLayout.markerCenter(screen.probePoint, in: rect)
        let screenRect = CGRect(origin: .zero, size: view)
        let onScreen = screenRect.contains(center)
        // видимая часть кадра: у её краёв табличка уходит внутрь
        let visible = rect.intersection(screenRect)
        // колонка кнопок справа (зум, режим, фокус, запись) — препятствие: табличка на неё не ложится (как гистограмма и наложение).
        // Её место помнится и когда элементы спрятаны — табличка не прыгает туда и обратно
        let origin = ProbeLayout.plateOrigin(marker: center, plate: plate, in: visible.isNull || visible.isEmpty ? screenRect : visible,
                                             avoiding: screen.rightColumnRect)
        ZStack {
            if let shown, onScreen {
                plateView(shown)
                    .position(x: origin.x + plate.width / 2, y: origin.y + plate.height / 2)
            }
            // метка есть всегда (за экраном — прозрачная): на ней висит опрос показаний, он не должен прерываться
            marker
                .position(center)
                .opacity(onScreen ? 1 : 0)
        }
        .allowsHitTesting(false)
        .task {
            while !Task.isCancelled {
                let latest = controller.probeReading
                if latest != shown { shown = latest }
                try? await Task.sleep(for: shown == nil ? Self.firstLook : Self.interval)
            }
        }
    }

    // MARK: - Табличка

    private func plateView(_ reading: ColorProbe.Reading) -> some View {
        let lines = reading.lines
        return VStack(alignment: .leading, spacing: 1) {
            Text(lines.0).font(.system(size: 12, weight: .semibold).monospacedDigit())
            Text(lines.1).font(.system(size: 11).monospacedDigit()).foregroundStyle(.white.opacity(0.85))
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 6))
        .fixedSize()
        .onGeometryChange(for: CGSize.self) { $0.size } action: { size in
            if size.width > 0, size.height > 0, size != plate { plate = size }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Пипетка")
        .accessibilityValue("\(lines.0), \(lines.1)")
    }

    // MARK: - Метка

    private var marker: some View {
        Canvas { context, size in
            let c = CGPoint(x: size.width / 2, y: size.height / 2)
            // кольцо ровно 20 pt по белому: середина линии — на три четверти пункта внутрь от края
            let radius = ProbeLayout.markerDiameter / 2 - 0.75
            let ring = Path(ellipseIn: CGRect(x: c.x - radius, y: c.y - radius, width: 2 * radius, height: 2 * radius))
            // перекрестие — четыре чёрточки от кольца к середине; середина свободна: под ней те точки, что меряются
            var ticks = Path()
            for (dx, dy) in [(1.0, 0.0), (-1.0, 0.0), (0.0, 1.0), (0.0, -1.0)] as [(CGFloat, CGFloat)] {
                ticks.move(to: CGPoint(x: c.x + dx * 3, y: c.y + dy * 3))
                ticks.addLine(to: CGPoint(x: c.x + dx * radius, y: c.y + dy * radius))
            }
            // тёмная обводка под белой линией: метку видно и на белом, и на чёрном
            for path in [ring, ticks] {
                context.stroke(path, with: .color(.black.opacity(0.65)), style: StrokeStyle(lineWidth: 3.5, lineCap: .round))
            }
            for path in [ring, ticks] {
                context.stroke(path, with: .color(.white), style: StrokeStyle(lineWidth: 1.5, lineCap: .round))
            }
        }
        .frame(width: Self.markerSide, height: Self.markerSide)
        .accessibilityHidden(true)
    }
}
