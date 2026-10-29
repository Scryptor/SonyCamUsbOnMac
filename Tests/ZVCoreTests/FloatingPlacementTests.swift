import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

private let view = CGSize(width: 1280, height: 800)
private let panel = CGSize(width: 320, height: 170)

@Test func placementOriginAtCorners() {
    #expect(FloatingPlacement(x: 0, y: 1).origin(panel: panel, view: view) == CGPoint(x: 16, y: 614))
    #expect(FloatingPlacement(x: 1, y: 0).origin(panel: panel, view: view) == CGPoint(x: 944, y: 16))
}

@Test func placementDropNearEdgeSnaps() {
    let placement = FloatingPlacement.dropped(at: CGPoint(x: 30, y: 300), panel: panel, view: view)
    #expect(placement.x == 0)
    #expect(abs(placement.y - 284.0 / 598) < 1e-9)
    #expect(FloatingPlacement.dropped(at: CGPoint(x: 930, y: 600), panel: panel, view: view) == FloatingPlacement(x: 1, y: 1))
}

@Test func placementDropFarFromEdgeKeepsFraction() {
    let placement = FloatingPlacement.dropped(at: CGPoint(x: 500, y: 300), panel: panel, view: view)
    #expect(abs(placement.x - 484.0 / 928) < 1e-9)
    #expect(placement.origin(panel: panel, view: view).x == 500)
}

@Test func placementDropOutsideClamps() {
    #expect(FloatingPlacement.dropped(at: CGPoint(x: -50, y: 900), panel: panel, view: view) == FloatingPlacement(x: 0, y: 1))
}

@Test func placementTooSmallViewPinsToMargin() {
    let small = CGSize(width: 300, height: 150)
    #expect(FloatingPlacement(x: 0.7, y: 0.9).origin(panel: panel, view: small) == CGPoint(x: 16, y: 16))
}

@Test func placementDragStaysOnScreen() {
    #expect(FloatingPlacement.dragOrigin(CGPoint(x: -40, y: 2000), panel: panel, view: view) == CGPoint(x: 0, y: 630))
}

@Test func placementCodableRoundTrip() throws {
    let placement = FloatingPlacement(x: 0.25, y: 0.75)
    let data = try JSONEncoder().encode(placement)
    #expect(try JSONDecoder().decode(FloatingPlacement.self, from: data) == placement)
}

// MARK: - Не ложиться на кнопки справа и ленту камер слева

/// Лента камер: плитки 160×90 столбцом слева под верхней панелью, промежуток 8.
private func strip(cameras: Int, top: CGFloat = 68) -> CGRect {
    CGRect(x: 16, y: top, width: 160, height: CGFloat(cameras) * 90 + CGFloat(cameras - 1) * 8)
}

@Test func clearLeavesAPanelThatTouchesNothingAlone() {
    let column = CGRect(x: 1180, y: 250, width: 60, height: 300)
    let origin = CGPoint(x: 400, y: 300)
    #expect(FloatingPlacement.clear(origin, panel: panel, of: column, view: view) == origin)
    // край к краю — не наложение
    let touching = CGPoint(x: column.minX - panel.width, y: 300)
    #expect(FloatingPlacement.clear(touching, panel: panel, of: column, view: view) == touching)
}

@Test func clearKeepsTheOldWayWithTheRightColumn() {
    // прежнее правило колонки кнопок: левее неё на 12, на той же высоте
    let column = CGRect(x: 1180, y: 250, width: 60, height: 300)
    let moved = FloatingPlacement.clear(CGPoint(x: 1000, y: 300), panel: panel, of: column, view: view)
    #expect(moved == CGPoint(x: 1180 - 12 - 320, y: 300))
    // места слева нет (панель шире просвета) — у отступа, но не за экран
    let narrow = FloatingPlacement.clear(CGPoint(x: 20, y: 300), panel: CGSize(width: 760, height: 170),
                                         of: CGRect(x: 720, y: 250, width: 60, height: 300), view: CGSize(width: 800, height: 600))
    #expect(narrow.x == FloatingPlacement.margin && narrow.y == 300)
}

@Test func clearPutsAPanelRightOfTheStripAtTheDefaultSpot() {
    // вектороскоп по умолчанию стоит на (16, 64): при двух камерах там лента
    let vectorscope = CGSize(width: 176, height: 176)
    let moved = FloatingPlacement.clear(CGPoint(x: 16, y: 64), panel: vectorscope, of: strip(cameras: 2), view: view)
    #expect(moved == CGPoint(x: 16 + 160 + 12, y: 64))
    #expect(!CGRect(origin: moved, size: vectorscope).intersects(strip(cameras: 2)))
}

@Test func clearMovesAPanelDroppedOnTheStripOutOfIt() {
    // место из прошлых запусков или перетащили на ленту: правее неё, высота та же
    let vectorscope = CGSize(width: 176, height: 176)
    let onStrip = CGPoint(x: 60, y: 150)
    let moved = FloatingPlacement.clear(onStrip, panel: vectorscope, of: strip(cameras: 3), view: view)
    #expect(moved == CGPoint(x: 188, y: 150))
}

@Test func clearLeavesAPanelBelowOrBesideTheStrip() {
    let vectorscope = CGSize(width: 176, height: 176)
    let twoCameras = strip(cameras: 2)   // 68 … 256
    let below = CGPoint(x: 16, y: twoCameras.maxY + 20)
    #expect(FloatingPlacement.clear(below, panel: vectorscope, of: twoCameras, view: view) == below)
    let beside = CGPoint(x: 400, y: 64)
    #expect(FloatingPlacement.clear(beside, panel: vectorscope, of: twoCameras, view: view) == beside)
}

@Test func clearStaysOnScreenInTheSmallestWindowWithTheLongestStrip() {
    // 800×450 — меньше окна нет; четыре камеры — самая длинная лента
    let small = CGSize(width: 800, height: 450)
    let vectorscope = CGSize(width: 176, height: 176)
    let longest = strip(cameras: 4)
    for origin in [CGPoint(x: 16, y: 64), CGPoint(x: 16, y: 16), CGPoint(x: 100, y: 200), CGPoint(x: 16, y: 258)] {
        let moved = FloatingPlacement.clear(origin, panel: vectorscope, of: longest, view: small)
        #expect(moved.x >= FloatingPlacement.margin && moved.x + vectorscope.width <= small.width - FloatingPlacement.margin, "\(origin)")
        #expect(moved.y == origin.y)
        #expect(!CGRect(origin: moved, size: vectorscope).intersects(longest), "\(origin)")
    }
}

@Test func clearByTheStripThenByTheColumnLandsBetweenThem() {
    // оба препятствия сразу (панель посередине, лента слева, колонка справа): один раз по ленте, потом по колонке — в просвете
    let vectorscope = CGSize(width: 176, height: 176)
    let column = CGRect(x: 1180, y: 250, width: 60, height: 300)
    let origin = FloatingPlacement.clear(CGPoint(x: 16, y: 300), panel: vectorscope, of: strip(cameras: 4), view: view)
    let final = FloatingPlacement.clear(origin, panel: vectorscope, of: column, view: view)
    #expect(final == CGPoint(x: 188, y: 300))
    #expect(!CGRect(origin: final, size: vectorscope).intersects(strip(cameras: 4)))
    #expect(!CGRect(origin: final, size: vectorscope).intersects(column))
}

@Test func clearWithNoRoomOnTheFarSideStaysOnScreen() {
    // препятствие слева, а справа места нет: не за экран — у правого отступа
    let tiny = CGSize(width: 300, height: 400)
    let moved = FloatingPlacement.clear(CGPoint(x: 16, y: 64), panel: CGSize(width: 176, height: 176),
                                        of: CGRect(x: 16, y: 68, width: 160, height: 188), view: tiny)
    #expect(moved.x == 108)
    #expect(Double(moved.x) == Double(tiny.width) - 176 - FloatingPlacement.margin)
}
