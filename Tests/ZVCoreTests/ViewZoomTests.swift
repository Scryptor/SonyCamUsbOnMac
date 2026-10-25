import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

/// Экран планшета в dp и кадр live view высокого качества.
private let view = CGSize(width: 1280, height: 800)
private let frame = CGSize(width: 1024, height: 576)

private func close(_ a: CGFloat, _ b: CGFloat, _ eps: CGFloat = 1e-9) -> Bool { abs(a - b) < eps }

@Test func zoomIdentityFitsLetterboxed() {
    #expect(ViewZoom.identity.imageRect(view: view, frame: frame) == CGRect(x: 0, y: 40, width: 1280, height: 720))
    #expect(!ViewZoom.identity.isZoomed)
}

@Test func zoomKeepsPointUnderFingers() {
    let point = CGPoint(x: 300, y: 200)
    let before = ViewZoom.identity.imageRect(view: view, frame: frame)
    let zoom = ViewZoom.identity.zoomed(by: 2, around: point, view: view, frame: frame, elastic: false)
    let after = zoom.imageRect(view: view, frame: frame)
    #expect(close((point.x - before.minX) / before.width, (point.x - after.minX) / after.width))
    #expect(close((point.y - before.minY) / before.height, (point.y - after.minY) / after.height))
    #expect(close(zoom.center.x, 0.3671875, 1e-4))
    #expect(close(zoom.center.y, 0.3611, 1e-4))
    #expect(zoom.isZoomed)
}

@Test func zoomPanStopsAtImageEdge() {
    let zoom = ViewZoom(factor: 2, center: CGPoint(x: 0.5, y: 0.5))
    #expect(zoom.panned(by: CGSize(width: 5000, height: 0), view: view, frame: frame).imageRect(view: view, frame: frame).minX == 0)
    let right = zoom.panned(by: CGSize(width: -5000, height: 0), view: view, frame: frame).imageRect(view: view, frame: frame)
    #expect(close(right.maxX, view.width))
}

@Test func zoomLetterboxAxisStaysCentered() {
    let zoom = ViewZoom(factor: 1.05, center: CGPoint(x: 0.5, y: 0.1))
    #expect(close(zoom.imageRect(view: view, frame: frame).midY, 400))
}

@Test func zoomSettledSnapsBelowThreshold() {
    #expect(ViewZoom(factor: 1.04, center: CGPoint(x: 0.3, y: 0.3)).settled(view: view, frame: frame) == .identity)
    #expect(ViewZoom(factor: 0.9, center: CGPoint(x: 0.5, y: 0.5)).settled(view: view, frame: frame) == .identity)
}

@Test func zoomSettledClampsToMax() {
    #expect(ViewZoom(factor: 8.5, center: CGPoint(x: 0.5, y: 0.5)).settled(view: view, frame: frame).factor == 8)
}

@Test func zoomElasticResistsBeyondLimits() {
    let max = ViewZoom(factor: 8, center: CGPoint(x: 0.5, y: 0.5))
    let middle = CGPoint(x: 640, y: 400)
    let stretched = max.zoomed(by: 1.5, around: middle, view: view, frame: frame, elastic: true).factor
    #expect(stretched > 8 && stretched <= 8.6)
    #expect(max.zoomed(by: 1.5, around: middle, view: view, frame: frame, elastic: false).factor == 8)
    let squeezed = ViewZoom.identity.zoomed(by: 0.5, around: middle, view: view, frame: frame, elastic: true).factor
    #expect(squeezed >= 0.87 && squeezed < 1)
    // назад к пределам — без сопротивления
    let back = ViewZoom(factor: 8.4, center: CGPoint(x: 0.5, y: 0.5)).zoomed(by: 0.9, around: middle, view: view, frame: frame, elastic: true)
    #expect(close(back.factor, 7.56, 1e-9))
}

@Test func zoomVisibleRegionCentered() {
    let region = ViewZoom(factor: 2, center: CGPoint(x: 0.5, y: 0.5)).visibleRegion(view: view, frame: frame)
    #expect(close(region.minX, 0.25, 1e-4) && close(region.width, 0.5, 1e-4))
    #expect(close(region.minY, 0.2222, 1e-4) && close(region.height, 0.5556, 1e-4))
    #expect(ViewZoom.identity.visibleRegion(view: view, frame: frame) == CGRect(x: 0, y: 0, width: 1, height: 1))
}

@Test func zoomSurvivesResize() {
    let zoom = ViewZoom(factor: 3, center: CGPoint(x: 0.8, y: 0.3))
    let portrait = CGSize(width: 800, height: 1280)
    let rect = zoom.imageRect(view: portrait, frame: frame)
    // по обеим осям кадр больше экрана — полос нет
    #expect(rect.minX <= 0 && rect.maxX >= portrait.width)
    #expect(rect.minY <= 0 && rect.maxY >= portrait.height)
    #expect(zoom.visibleRegion(view: portrait, frame: frame).contains(zoom.center))
}

@Test func zoomQualityChangeKeepsRegion() {
    let zoom = ViewZoom(factor: 2.5, center: CGPoint(x: 0.6, y: 0.4))
    let high = zoom.visibleRegion(view: view, frame: frame)
    let low = zoom.visibleRegion(view: view, frame: CGSize(width: 640, height: 360))
    #expect(close(high.minX, low.minX) && close(high.minY, low.minY))
    #expect(close(high.width, low.width) && close(high.height, low.height))
}

@Test func zoomStepLadder() {
    #expect(ViewZoom.step(from: 1, up: true) == 1.5)
    #expect(ViewZoom.step(from: 2.4, up: true) == 3)
    #expect(ViewZoom.step(from: 2.4, up: false) == 2)
    #expect(ViewZoom.step(from: 8, up: true) == 8)
    #expect(ViewZoom.step(from: 1, up: false) == 1)
}

@Test func zoomLabels() {
    #expect(ViewZoom(factor: 2, center: .zero).label == "2×")
    #expect(ViewZoom(factor: 2.4, center: .zero).label == "2,4×")
    #expect(ViewZoom(factor: 1.46, center: .zero).label == "1,5×")
    #expect(ViewZoom(factor: 7.97, center: .zero).label == "8×")
}

@Test func zoomCenteredClampsToEdges() {
    let zoom = ViewZoom(factor: 2, center: CGPoint(x: 0.5, y: 0.5)).centered(at: CGPoint(x: 0, y: 1), view: view, frame: frame)
    #expect(zoom.imageRect(view: view, frame: frame).minX == 0)
    #expect(close(zoom.imageRect(view: view, frame: frame).maxY, view.height))
}

@Test func zoomElasticKicksInExactlyAtTheLimit() {
    // 8 / 1,1008 × 1,1008 даёт 7,999999999999999 — сопротивление всё равно должно сработать
    let start = ViewZoom(factor: 1.1008, center: CGPoint(x: 0.5, y: 0.5))
    let zoom = start.zoomed(by: 8 / 1.1008 * 1.05, around: CGPoint(x: 640, y: 400), view: view, frame: frame, elastic: true)
    #expect(abs(zoom.factor - 8 * pow(1.05, 0.3)) < 1e-6)
}
