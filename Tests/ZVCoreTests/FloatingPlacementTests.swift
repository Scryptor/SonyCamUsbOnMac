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
