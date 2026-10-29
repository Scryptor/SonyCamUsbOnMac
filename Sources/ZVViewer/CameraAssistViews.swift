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

/// Подсказка «Фокус касанием»: что сделает касание сейчас и как это устроено. Открывается при включении кнопки (пока не
/// отмечено «Больше не показывать») и по правому щелчку на ней; закрывается «Понятно», щелчком мимо или Esc.
struct TouchFocusHelp: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                TouchFocusIcon()
                    .frame(width: 18, height: 18)
                Text("Фокус касанием").font(.headline)
            }
            Text(controller.touchFocusNow)
                .font(.callout.weight(.semibold))
            // пипетка отбирает щелчок у фокуса: пока она включена, кнопка горит, но фокус не ставится — сказать об этом здесь
            if screen.assist.probe {
                Text("Сейчас включена «Пипетка»: щелчок по кадру ставит её, а не фокус. Выключили пипетку (клавиша I) — щелчок "
                     + "снова ставит фокус.")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.yellow)
            }
            Text("Пока кнопка горит синим, щелчок по кадру ставит туда фокус. Как — решает зона фокуса камеры:")
            VStack(alignment: .leading, spacing: 5) {
                Text("• «Точка» и «Зона» — рамка AF переезжает в точку щелчка.")
                Text("• «Широкая» и другие — программа касается экрана камеры: камера фокусируется или следит за предметом, "
                     + "как выбрано в её меню (Touch Function). Слежение снимает «Снять слежение» над строкой значений.")
                Text("• В ручном фокусе щелчок фокус не ставит — для него шкала фокуса внизу.")
            }
            Text("Рамки фокуса самой камеры видны всегда, как на её экране: зелёная — фокус найден, пунктир — слежение.")
            Text("Выключили кнопку — щелчки по кадру фокус не трогают, а начатое слежение продолжается, и его рамка видна. "
                 + "Снять его — «Снять слежение». Эта подсказка — по правому щелчку на кнопке.")
                .foregroundStyle(.secondary)
            HStack {
                Toggle("Больше не показывать", isOn: Binding(get: { screen.touchFocusHelpHidden },
                                                                           set: { screen.touchFocusHelpHidden = $0 }))
                    .toggleStyle(.checkbox)
                Spacer()
                Button("Понятно") { screen.touchFocusHelpOpen = false }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 2)
        }
        .font(.callout)
        .fixedSize(horizontal: false, vertical: true)
        .padding(16)
        .frame(width: 380, alignment: .leading)
        .background(Color(white: 0.11).opacity(0.96), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.08)))
        // щелчки по карточке не уходят к кадру под ней
        .contentShape(Rectangle())
        .onTapGesture {}
    }
}

/// Фокус касанием: горит — щелчок по кадру ставит туда фокус (в зонах «Точка» и «Зона» — точка AF, в остальных —
/// касание экрана камеры: фокус или слежение, как настроено в камере). Не горит — щелчки фокус не трогают; рамки фокуса
/// камеры видны всегда, и начатое слежение продолжается.
struct TouchFocusButton: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        let method = controller.touchFocusMethod
        // подпись под кнопкой — как «Зум» и режим съёмки: значок сам по себе не говорит, что это
        VStack(spacing: 5) {
            Button { screen.toggleTouchFocus() } label: {
                TouchFocusIcon()
                    .foregroundStyle(.white)
                    .frame(width: 42, height: 42)
                    .background(screen.touchFocusOn ? AnyShapeStyle(Color.accentColor) : AnyShapeStyle(.ultraThinMaterial), in: Circle())
                    // включена пипетка — щелчок по кадру ставит её, фокус пока не ставится: кнопка приглушена, как при отказе камеры
                    .opacity(method == nil || screen.assist.probe ? 0.5 : 1)
            }
            .buttonStyle(.plain)
            .help(method == nil ? controller.touchRefusal
                  : screen.assist.probe ? "Пока включена «Пипетка», щелчок по кадру ставит пипетку, а не фокус"
                  : screen.touchFocusOn ? "Фокус касанием включён: щелчок по кадру ставит фокус" : "Фокус касанием: щелчок по кадру ставит фокус")
            .accessibilityLabel("Фокус касанием")
            .contextMenu {
                Button("Как работает фокус касанием…") {
                    screen.closeOverlays()
                    screen.touchFocusHelpOpen = true
                }
            }
            Text("Фокус")
                .font(.caption)
                .foregroundStyle(.white.opacity(0.8))
                .accessibilityHidden(true)
        }
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
/// за секунду. rect — где кадр на экране, с увеличением. И рамки, и отметка лежат в долях кадра камеры; кадр на экране может
/// быть зеркальным или перевёрнутым (`orientation`) — тогда они переезжают на то место картинки, где камера их видит.
struct FocusFramesLayer: View {
    let controller: CameraController
    let rect: CGRect
    let orientation: FrameOrientation

    var body: some View {
        TimelineView(.animation(minimumInterval: 1.0 / 30, paused: controller.touchMark == nil)) { timeline in
            Canvas { context, _ in
                // из долей кадра камеры — в точки экрана
                func area(_ r: CGRect) -> CGRect {
                    let r = orientation.fromCamera(r)
                    return CGRect(x: rect.minX + r.minX * rect.width, y: rect.minY + r.minY * rect.height,
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
                        let at = orientation.fromCamera(mark.point)
                        let c = CGPoint(x: rect.minX + at.x * rect.width, y: rect.minY + at.y * rect.height)
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
/// шкале — как поворот кольца фокуса, колесо мыши — по шагу. Метка — где фокус, если камера его отдаёт. Под линейкой — метки
/// фокуса: `[A] [B] [⏱ 2 с]`.
struct FocusScale: View {
    let controller: CameraController
    let screen: ScreenState
    @State private var dragged: CGFloat = 0
    @State private var width: CGFloat = 1

    /// Цвета меток: на линейке, в кнопках и в карточке одни и те же.
    static func markColor(_ mark: Character) -> Color {
        mark == "A" ? Color(red: 0.35, green: 0.80, blue: 1.0) : Color(red: 1.0, green: 0.62, blue: 0.20)
    }

    var body: some View {
        HStack(spacing: 10) {
            ForEach([FocusDrive.Step.large, .medium, .small], id: \.self) { step in button(step, nearer: true) }
            VStack(spacing: 3) {
                ruler
                HStack {
                    Text("ближе")
                    Spacer()
                    caption
                    Spacer()
                    Text("дальше")
                }
                .font(.caption2)
                .foregroundStyle(.white.opacity(0.75))
                if controller.focusMarksAvailable { marksRow }
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
        // шкала с метками появилась впервые — карточка, как с ними обращаться
        .onChange(of: controller.focusMarksAvailable, initial: true) { _, available in
            if available { screen.offerFocusMarksHelp() }
        }
    }

    /// Посередине под линейкой: чем кончился перевод (3 с), иначе — что это за шкала.
    private var caption: some View {
        let text = controller.focusPullCaption
        return Text(text ?? "шкала фокуса")
            .fontWeight(text == nil ? .regular : .semibold)
            .foregroundStyle(text == nil ? Color.white.opacity(0.5) : Color.white)
            .lineLimit(1)
            .animation(.easeInOut(duration: 0.15), value: text)
    }

    /// Над линейкой, пока метки есть, — место под значки меток.
    private static let flagsHeight: CGFloat = 16

    private var ruler: some View {
        let flags = controller.focusMarksAvailable ? Self.flagsHeight : 0
        return Canvas { context, size in
            let top = flags
            let barHeight = size.height - top
            let mid = top + barHeight / 2
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
                context.fill(Path(roundedRect: CGRect(x: px - 2, y: top + 2, width: 4, height: barHeight - 4), cornerRadius: 2),
                             with: .color(.yellow))
            }
            guard flags > 0 else { return }
            // значки меток: буква на цветной плашке и остриё к месту на линейке. Рядом друг с другом плашки расходятся,
            // чтобы не закрывать буквы; остриё остаётся там, где метка
            let marks: [(letter: String, fraction: CGFloat)] = ["A", "B"].compactMap { letter in
                controller.focusMarkFraction(Character(letter)).map { (letter, CGFloat($0)) }
            }
            let close = marks.count == 2 && abs(marks[0].fraction - marks[1].fraction) * size.width < 13
            for (index, mark) in marks.enumerated() {
                let tip = size.width * mark.fraction
                let offset: CGFloat = close ? (index == 0 ? -7 : 7) : 0
                let center = min(max(tip + offset, 7), size.width - 7)
                let color = Self.markColor(Character(mark.letter))
                var pointer = Path()
                pointer.move(to: CGPoint(x: tip - 3.5, y: flags - 5))
                pointer.addLine(to: CGPoint(x: tip + 3.5, y: flags - 5))
                pointer.addLine(to: CGPoint(x: tip, y: flags))
                pointer.closeSubpath()
                context.fill(pointer, with: .color(color))
                let badge = CGRect(x: center - 6.5, y: 0, width: 13, height: flags - 4)
                context.fill(Path(roundedRect: badge, cornerRadius: 3), with: .color(color))
                context.draw(Text(mark.letter).font(.system(size: 9, weight: .heavy)).foregroundColor(.black),
                             at: CGPoint(x: badge.midX, y: badge.midY))
            }
        }
        .frame(height: 24 + flags)
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

    // MARK: Метки фокуса

    /// Строка под линейкой: метки A и B, справа — время перевода.
    private var marksRow: some View {
        HStack(spacing: 6) {
            markButton("A")
            markButton("B")
            Spacer(minLength: 0)
            timeButton
        }
        .padding(.top, 2)
    }

    /// Кнопка метки. Пустая — «A+»: касание запоминает, где фокус. Заданная — «A»: касание переводит фокус к ней. Пока фокус
    /// едет, кнопка заливается по доле пути, и касание её же останавливает; на конце мигает один раз.
    private func markButton(_ mark: Character) -> some View {
        let name = String(mark)
        let isSet = controller.focusMarks[mark] != nil
        let moving = controller.focusPullMark == mark
        let color = Self.markColor(mark)
        let seconds = Int(controller.focusMarks.duration)
        let shape = RoundedRectangle(cornerRadius: 8)
        return Button { controller.focusMarkTapped(mark) } label: {
            HStack(spacing: 4) {
                Text(isSet ? name : name + "+").font(.system(size: 12, weight: .bold))
                // едет — значок «стоп»: касание остановит
                if moving { Image(systemName: "stop.fill").font(.system(size: 8)) }
            }
            .foregroundStyle(.white.opacity(isSet ? 1 : 0.85))
            .frame(width: 60, height: 24)
            .background {
                ZStack(alignment: .leading) {
                    shape.fill(isSet ? color.opacity(0.22) : Color.white.opacity(0.1))
                    if moving {
                        GeometryReader { geometry in
                            color.opacity(0.6).frame(width: geometry.size.width * CGFloat(controller.focusPullProgress ?? 0))
                        }
                    }
                }
                .clipShape(shape)
            }
            .overlay(shape.strokeBorder(isSet ? color.opacity(0.85) : Color.clear, lineWidth: 1))
            // конец перевода: вспышка один раз
            .keyframeAnimator(initialValue: 0.0, trigger: controller.focusPullEnds[mark] ?? 0) { content, flash in
                content.overlay(shape.fill(Color.white.opacity(flash)))
            } keyframes: { _ in
                KeyframeTrack {
                    LinearKeyframe(0.7, duration: 0.1)
                    LinearKeyframe(0.0, duration: 0.4)
                }
            }
        }
        .buttonStyle(.plain)
        .help(moving ? "Остановить перевод фокуса к метке \(name)"
              : isSet ? "Перевести фокус к метке \(name) за \(seconds) с. Правый щелчок — запомнить заново или стереть"
              : "Запомнить, где сейчас фокус, в метку \(name). Правый щелчок — меню")
        .accessibilityLabel(moving ? "Остановить перевод к метке \(name)"
                            : isSet ? "Метка \(name): перевести фокус" : "Метка \(name): запомнить фокус")
        .contextMenu {
            Button("Запомнить здесь") { controller.focusMarkSave(mark) }
            Button("Стереть") { controller.focusMarkClear(mark) }
                .disabled(!isSet)
        }
    }

    /// Время перевода по кругу: 0 → 1 → 2 → 4 → 8 с. Правый щелчок — карточка «Метки фокуса».
    private var timeButton: some View {
        let seconds = Int(controller.focusMarks.duration)
        return Button { controller.focusPullCycleTime() } label: {
            HStack(spacing: 4) {
                Image(systemName: "timer").font(.system(size: 10, weight: .semibold))
                Text("\(seconds) с").font(.system(size: 12, weight: .semibold)).monospacedDigit()
            }
            .foregroundStyle(.white.opacity(0.9))
            .frame(width: 66, height: 24)
            .background(.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 8))
        }
        .buttonStyle(.plain)
        .help("Время перевода фокуса к метке: \(seconds) с. Щелчок — следующее: 0, 1, 2, 4, 8; «0 с» — как можно быстрее")
        .accessibilityLabel("Время перевода: \(seconds) с")
        .contextMenu {
            Button("Как работают метки фокуса…") { screen.openFocusMarksHelp() }
        }
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

/// Карточка «Метки фокуса»: что делают «A+» и «A», время перевода и правый щелчок. Открывается сама, когда шкала с метками
/// появилась впервые (пока не отмечено «Больше не показывать»), и по правому щелчку на кнопке времени; закрывается «Понятно»,
/// щелчком мимо или Esc.
struct FocusMarksHelp: View {
    let screen: ScreenState

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack(spacing: 8) {
                HStack(spacing: 3) {
                    badge("A")
                    badge("B")
                }
                Text("Метки фокуса").font(.headline)
            }
            Text("A+ запоминает, где сейчас фокус. A переводит фокус туда за выбранное время, касание во время перевода — стоп. "
                 + "Правый щелчок по метке — запомнить заново или стереть")
            Text("Кнопка со временем переключает 0, 1, 2, 4 и 8 секунд по кругу; «0 с» — как можно быстрее. "
                 + "Эта подсказка — по правому щелчку на ней.")
                .foregroundStyle(.secondary)
            HStack {
                Toggle("Больше не показывать", isOn: Binding(get: { screen.focusMarksHelpShown },
                                                               set: { screen.focusMarksHelpShown = $0 }))
                    .toggleStyle(.checkbox)
                Spacer()
                Button("Понятно") { screen.focusMarksHelpOpen = false }
                    .keyboardShortcut(.defaultAction)
            }
            .padding(.top, 2)
        }
        .font(.callout)
        .fixedSize(horizontal: false, vertical: true)
        .padding(16)
        .frame(width: 380, alignment: .leading)
        .background(Color(white: 0.11).opacity(0.96), in: RoundedRectangle(cornerRadius: 14))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(.white.opacity(0.08)))
        // щелчки по карточке не уходят к кадру под ней
        .contentShape(Rectangle())
        .onTapGesture {}
    }

    private func badge(_ letter: String) -> some View {
        Text(letter)
            .font(.system(size: 11, weight: .heavy))
            .foregroundStyle(.black)
            .frame(width: 18, height: 18)
            .background(FocusScale.markColor(Character(letter)), in: RoundedRectangle(cornerRadius: 4))
    }
}
