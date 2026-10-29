import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

private func close(_ a: CGFloat, _ b: CGFloat, _ eps: CGFloat = 1e-9) -> Bool { abs(a - b) < eps }

private func close(_ a: CGPoint, _ b: CGPoint, _ eps: CGFloat = 1e-9) -> Bool { close(a.x, b.x, eps) && close(a.y, b.y, eps) }

/// Область, где может стоять табличка, и табличка из двух строк.
private let area = CGRect(x: 0, y: 0, width: 1000, height: 600)
private let plate = CGSize(width: 150, height: 40)
/// От центра метки до таблички по горизонтали: радиус метки и просвет.
private let reach = ProbeLayout.markerDiameter / 2 + ProbeLayout.gap

// MARK: - Метка на экране

@Test func markerCenterFollowsTheFrameRect() {
    let rect = CGRect(x: 100, y: 50, width: 800, height: 450)
    #expect(ProbeLayout.markerCenter(CGPoint(x: 0, y: 0), in: rect) == CGPoint(x: 100, y: 50))
    #expect(ProbeLayout.markerCenter(CGPoint(x: 1, y: 1), in: rect) == CGPoint(x: 900, y: 500))
    #expect(ProbeLayout.markerCenter(CGPoint(x: 0.5, y: 0.5), in: rect) == CGPoint(x: 500, y: 275))
}

@Test func markerCenterIsOffscreenWhenTheZoomedFrameMovedAway() {
    // кадр увеличен вдвое и сдвинут: левая четверть и верхняя четверть кадра за экраном, метка в них — левее и выше нуля
    let rect = CGRect(x: -400, y: -225, width: 1600, height: 900)
    #expect(ProbeLayout.markerCenter(CGPoint(x: 0.1, y: 0.1), in: rect) == CGPoint(x: -240, y: -135))
    #expect(ProbeLayout.markerCenter(CGPoint(x: 0.5, y: 0.5), in: rect) == CGPoint(x: 400, y: 225))
}

// MARK: - Табличка

@Test func plateStandsRightOfTheMarkerCentredOnIt() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 300, y: 300), plate: plate, in: area)
    #expect(origin == CGPoint(x: 300 + reach, y: 280))
}

@Test func plateGoesLeftNearTheRightEdge() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 900, y: 300), plate: plate, in: area)
    // слева: край таблички — за радиус метки и просвет до центра метки; по высоте — по центру метки
    #expect(origin == CGPoint(x: 900 - reach - plate.width, y: 280))
}

@Test func plateSwitchesSidesExactlyWhereItStopsFitting() {
    // справа помещается, пока правый край таблички не заходит за область минус поле
    let edge = area.maxX - ProbeLayout.margin - plate.width - reach
    #expect(ProbeLayout.plateOrigin(marker: CGPoint(x: edge, y: 300), plate: plate, in: area).x == edge + reach)
    let past = ProbeLayout.plateOrigin(marker: CGPoint(x: edge + 1, y: 300), plate: plate, in: area)
    #expect(past.x == edge + 1 - reach - plate.width)
}

@Test func plateRisesNearTheBottomEdge() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 300, y: 595), plate: plate, in: area)
    // по высоте не выходит за низ области; сторона та же — справа
    #expect(origin == CGPoint(x: 300 + reach, y: area.maxY - ProbeLayout.margin - plate.height))
}

@Test func plateDropsNearTheTopEdge() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 300, y: 3), plate: plate, in: area)
    #expect(origin == CGPoint(x: 300 + reach, y: area.minY + ProbeLayout.margin))
}

@Test func plateInTheBottomRightCornerGoesLeftAndUp() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 995, y: 595), plate: plate, in: area)
    #expect(origin == CGPoint(x: 995 - reach - plate.width, y: area.maxY - ProbeLayout.margin - plate.height))
}

@Test func plateInTheTopLeftCornerStaysRightAndBelowTheEdge() {
    let origin = ProbeLayout.plateOrigin(marker: CGPoint(x: 0, y: 0), plate: plate, in: area)
    #expect(origin == CGPoint(x: reach, y: area.minY + ProbeLayout.margin))
}

@Test func plateKeepsToTheAreaNotTheScreenOrigin() {
    // кадр уже окна: область смещена — у её правого края табличка уходит влево, у нижнего поднимается
    let letterbox = CGRect(x: 120, y: 40, width: 700, height: 400)
    let right = ProbeLayout.plateOrigin(marker: CGPoint(x: 815, y: 200), plate: plate, in: letterbox)
    #expect(right.x == 815 - reach - plate.width)
    let bottom = ProbeLayout.plateOrigin(marker: CGPoint(x: 300, y: 438), plate: plate, in: letterbox)
    #expect(bottom.y == letterbox.maxY - ProbeLayout.margin - plate.height)
    let left = ProbeLayout.plateOrigin(marker: CGPoint(x: 120, y: 200), plate: plate, in: letterbox)
    #expect(left.x == 120 + reach)
}

@Test func plateStaysInsideTheAreaAndOffTheMarkerEverywhere() {
    // по сетке точек от угла до угла, включая сами края: табличка целиком в области (с полем) и не лежит на метке
    let r = ProbeLayout.markerDiameter / 2
    for x in stride(from: CGFloat(0), through: area.width, by: 37) {
        for y in stride(from: CGFloat(0), through: area.height, by: 23) {
            let marker = CGPoint(x: x, y: y)
            let o = ProbeLayout.plateOrigin(marker: marker, plate: plate, in: area)
            let box = CGRect(origin: o, size: plate)
            let inner = area.insetBy(dx: ProbeLayout.margin, dy: ProbeLayout.margin)
            #expect(box.minX >= inner.minX - 1e-9 && box.maxX <= inner.maxX + 1e-9, "\(marker)")
            #expect(box.minY >= inner.minY - 1e-9 && box.maxY <= inner.maxY + 1e-9, "\(marker)")
            #expect(!box.intersects(CGRect(x: x - r, y: y - r, width: 2 * r, height: 2 * r)), "\(marker)")
        }
    }
}

@Test func plateInAnAreaNarrowerThanItselfStaysAtTheLeftEdge() {
    // табличке негде поместиться: левый край области, ни NaN, ни выхода влево
    let narrow = CGRect(x: 0, y: 0, width: 100, height: 300)
    for x in [CGFloat(0), 50, 100] {
        let o = ProbeLayout.plateOrigin(marker: CGPoint(x: x, y: 150), plate: plate, in: narrow)
        #expect(o.x == narrow.minX + ProbeLayout.margin, "\(x)")
        #expect(o.y.isFinite && o.y == 130, "\(x)")
    }
}

@Test func plateInAnAreaShorterThanItselfStaysAtTheTopEdge() {
    let flat = CGRect(x: 0, y: 0, width: 1000, height: 30)
    let o = ProbeLayout.plateOrigin(marker: CGPoint(x: 500, y: 15), plate: plate, in: flat)
    #expect(o.y == flat.minY + ProbeLayout.margin)
}

@Test func plateWithNoAreaOrNoMarkerDoesNotProduceNaN() {
    let o = ProbeLayout.plateOrigin(marker: CGPoint(x: CGFloat.nan, y: 5), plate: plate, in: area)
    #expect(o.x.isFinite && o.y.isFinite)
    let empty = ProbeLayout.plateOrigin(marker: CGPoint(x: 5, y: 5), plate: plate, in: .zero)
    #expect(empty.x.isFinite && empty.y.isFinite)
}

// MARK: - Перетаскивание

@Test func dragMovesThePointByTheTranslationInFrameFractions() {
    let rect = CGRect(x: 100, y: 50, width: 800, height: 450)
    let moved = ProbeLayout.dragged(from: CGPoint(x: 0.5, y: 0.5), by: CGSize(width: 80, height: 45), rect: rect,
                                    visible: CGRect(x: 0, y: 0, width: 1, height: 1))
    #expect(close(moved, CGPoint(x: 0.6, y: 0.6)))
}

@Test func dragOnAZoomedFrameMovesByLessInTheFrame() {
    // вдвое увеличено: те же 100 pt — вдвое меньшая доля кадра
    let rect = CGRect(x: -400, y: -225, width: 1600, height: 900)
    let visible = CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5)
    let moved = ProbeLayout.dragged(from: CGPoint(x: 0.5, y: 0.5), by: CGSize(width: 100, height: -90), rect: rect, visible: visible)
    #expect(close(moved, CGPoint(x: 0.5625, y: 0.4)))
}

@Test func dragStopsAtTheVisibleEdgesNotAtTheFrameEdges() {
    let rect = CGRect(x: -400, y: -225, width: 1600, height: 900)
    let visible = CGRect(x: 0.25, y: 0.25, width: 0.5, height: 0.5)
    let far = ProbeLayout.dragged(from: CGPoint(x: 0.5, y: 0.5), by: CGSize(width: 5000, height: 5000), rect: rect, visible: visible)
    #expect(close(far, CGPoint(x: 0.75, y: 0.75)))
    let back = ProbeLayout.dragged(from: CGPoint(x: 0.5, y: 0.5), by: CGSize(width: -5000, height: -5000), rect: rect, visible: visible)
    #expect(close(back, CGPoint(x: 0.25, y: 0.25)))
}

@Test func dragWithoutFrameOrWithJunkKeepsTheStart() {
    let whole = CGRect(x: 0, y: 0, width: 1, height: 1)
    let start = CGPoint(x: 0.3, y: 0.7)
    #expect(ProbeLayout.dragged(from: start, by: CGSize(width: 10, height: 10), rect: .zero, visible: whole) == start)
    let rect = CGRect(x: 0, y: 0, width: 800, height: 450)
    #expect(ProbeLayout.dragged(from: start, by: CGSize(width: CGFloat.nan, height: 0), rect: rect, visible: whole) == start)
    #expect(ProbeLayout.dragged(from: start, by: CGSize(width: 0, height: CGFloat.infinity), rect: rect, visible: whole) == start)
}

@Test func gestureStartedOnTheMarkerGrabsIt() {
    let marker = CGPoint(x: 400, y: 300)
    #expect(ProbeLayout.grabs(marker, marker: marker))
    #expect(ProbeLayout.grabs(CGPoint(x: 400 + ProbeLayout.grabRadius, y: 300), marker: marker))
    #expect(ProbeLayout.grabs(CGPoint(x: 410, y: 310), marker: marker))
    // чуть дальше радиуса — уже не метка: такой жест двигает картинку, а щелчок ставит пипетку на новое место
    #expect(!ProbeLayout.grabs(CGPoint(x: 400 + ProbeLayout.grabRadius + 0.5, y: 300), marker: marker))
    #expect(!ProbeLayout.grabs(CGPoint(x: 412, y: 312), marker: marker))
    #expect(!ProbeLayout.grabs(CGPoint(x: 100, y: 100), marker: marker))
}

@Test func theMarkerIsGrabbedEvenWhenItIsLargerThanTheRingAroundIt() {
    // берётся чуть шире кольца (10 pt): мышью в кольцо попасть труднее, чем в круг побольше
    #expect(ProbeLayout.grabRadius > ProbeLayout.markerDiameter / 2)
}

@Test func nothingGrabsAMarkerThatIsNotANumber() {
    #expect(!ProbeLayout.grabs(CGPoint(x: 5, y: 5), marker: CGPoint(x: CGFloat.nan, y: 5)))
    #expect(!ProbeLayout.grabs(CGPoint(x: CGFloat.infinity, y: 5), marker: CGPoint(x: 5, y: 5)))
}

// MARK: - Точка кадра

@Test func probePointKeepsPointsInsideTheFrame() {
    #expect(ProbeLayout.clamped(CGPoint(x: 0.25, y: 0.75)) == CGPoint(x: 0.25, y: 0.75))
    #expect(ProbeLayout.clamped(CGPoint(x: -0.4, y: 1.8)) == CGPoint(x: 0, y: 1))
    #expect(ProbeLayout.clamped(CGPoint(x: 0, y: 1)) == CGPoint(x: 0, y: 1))
}

@Test func probePointRefusesNotANumber() {
    #expect(ProbeLayout.clamped(CGPoint(x: CGFloat.nan, y: 0.5)) == nil)
    #expect(ProbeLayout.clamped(CGPoint(x: 0.5, y: CGFloat.infinity)) == nil)
}
