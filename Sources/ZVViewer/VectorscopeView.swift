import SwiftUI
import ZVCore

/// Вектороскоп: цвета кадра на плоскости Cb (вправо) и Cr (вверх), Rec. 709. Серый — в середине, насыщенный цвет — к краю в своём
/// направлении; под сеткой — картинка точек 128×128 из потока кадров, растянутая на всю плоскость.
///
/// Плоскость — квадрат |Cb|, |Cr| ≤ 0,5/масштаб, а не круг: цвета в углах квадрата (насыщенный синий, жёлтый) лежат за кругом, и
/// картинка по кругу не обрезается. Сетка: круг по краю плоскости, тонкий крест, шесть меток цветных полос 75 % (R, Mg, B, Cy, G, Yl)
/// и линия тона кожи под углом 123°. Щелчок по панели переключает масштаб ×1 / ×2. Карточки-подсказки нет, как у волновой формы.
struct VectorscopeView: View {
    let trace: VectorscopeTrace
    let onZoom: () -> Void

    /// Сторона панели, pt.
    static let side: CGFloat = 176
    /// Отступ плоскости от края панели: обводка круга у края не должна обрезаться.
    private static let inset: CGFloat = 4
    private static let corner: CGFloat = 10
    private static let lineColor = Color.white
    private static let skinColor = Color(red: 1, green: 0.74, blue: 0.5)

    var body: some View {
        Canvas { context, size in
            let plane = CGRect(origin: .zero, size: size).insetBy(dx: Self.inset, dy: Self.inset)
            // точки — под сеткой и не по кругу: всё, что лежит в квадрате плоскости, видно
            if let image = trace.image {
                context.draw(Image(decorative: image, scale: 1), in: plane)
            }
            Self.drawGraticule(context, plane: plane, zoom: trace.zoom)
        }
        .frame(width: Self.side, height: Self.side)
        .background(.black.opacity(0.55), in: RoundedRectangle(cornerRadius: Self.corner))
        .overlay(alignment: .bottomTrailing) { zoomBadge.padding(6) }
        .contentShape(Rectangle())
        .onTapGesture(perform: onZoom)
        .help("Вектороскоп: цвета кадра на круге — направление даёт оттенок, расстояние от центра — насыщенность. "
            + "Метки R, Mg, B, Cy, G, Yl — цветные полосы 75 %, наклонная линия — тон кожи. "
            + (trace.zoom == 2 ? "Щелчок — вернуть масштаб ×1."
               : "Щелчок — масштаб ×2: бледные цвета лога видно крупнее."))
        .accessibilityLabel("Вектороскоп")
        .accessibilityValue(trace.zoom == 2 ? "Масштаб ×2" : "Масштаб ×1")
        .accessibilityHint(trace.zoom == 2 ? "Нажмите, чтобы вернуть масштаб ×1" : "Нажмите, чтобы увеличить до ×2")
        .accessibilityAddTraits(.isButton)
    }

    /// «×2» в углу: горит, пока масштаб ×2; в ×1 — приглушена, но на месте: это и есть подсказка, куда щёлкать.
    private var zoomBadge: some View {
        let zoomed = trace.zoom == 2
        return Text("×2")
            .font(.system(size: 10, weight: .bold))
            .padding(.horizontal, 5)
            .padding(.vertical, 1.5)
            .foregroundStyle(zoomed ? Color.black : Color.white.opacity(0.55))
            .background(zoomed ? Color.white : Color.white.opacity(0.1), in: RoundedRectangle(cornerRadius: 4))
            .allowsHitTesting(false)
    }

    // MARK: - Сетка

    /// Точка панели для доли плоскости (0…1, y вниз).
    private static func point(_ fraction: CGPoint, in plane: CGRect) -> CGPoint {
        CGPoint(x: plane.minX + fraction.x * plane.width, y: plane.minY + fraction.y * plane.height)
    }

    private static func drawGraticule(_ context: GraphicsContext, plane: CGRect, zoom: Int) {
        let thin = StrokeStyle(lineWidth: 1)
        // круг — край плоскости при любом масштабе (при ×1 это 0,5, при ×2 — 0,25)
        context.stroke(Path(ellipseIn: plane), with: .color(lineColor.opacity(0.45)), style: thin)
        // крест
        var cross = Path()
        cross.move(to: CGPoint(x: plane.minX, y: plane.midY))
        cross.addLine(to: CGPoint(x: plane.maxX, y: plane.midY))
        cross.move(to: CGPoint(x: plane.midX, y: plane.minY))
        cross.addLine(to: CGPoint(x: plane.midX, y: plane.maxY))
        context.stroke(cross, with: .color(lineColor.opacity(0.22)), style: thin)
        // тон кожи: из середины до круга под 123°
        var skin = Path()
        skin.move(to: CGPoint(x: plane.midX, y: plane.midY))
        skin.addLine(to: point(Vectorscope.skinLineEnd, in: plane))
        context.stroke(skin, with: .color(skinColor.opacity(0.7)), style: thin)
        // метки полос 75 %: квадратик на месте цвета и буква снаружи от него (от середины)
        let center = CGPoint(x: plane.midX, y: plane.midY)
        for target in Vectorscope.targets {
            let fraction = Vectorscope.plane(cb: target.cb, cr: target.cr, zoom: zoom)
            // при ×2 полосы 75 % лежат за краем плоскости — метка там была бы вне панели
            guard (0...1).contains(fraction.x), (0...1).contains(fraction.y) else { continue }
            let p = point(fraction, in: plane)
            context.stroke(Path(CGRect(x: p.x - 3.5, y: p.y - 3.5, width: 7, height: 7)),
                           with: .color(lineColor.opacity(0.85)), style: thin)
            let dx = p.x - center.x, dy = p.y - center.y
            let length = max((dx * dx + dy * dy).squareRoot(), 1)
            let label = CGPoint(x: p.x + dx / length * 10, y: p.y + dy / length * 8)
            context.draw(Text(target.name).font(.system(size: 8.5, weight: .semibold)).foregroundColor(lineColor.opacity(0.85)),
                         at: label, anchor: .center)
        }
    }
}
