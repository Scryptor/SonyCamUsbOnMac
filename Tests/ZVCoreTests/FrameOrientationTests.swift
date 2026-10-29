import CoreGraphics
import Foundation
import Testing
@testable import ZVCore

private func close(_ a: CGFloat, _ b: CGFloat, _ eps: CGFloat = 1e-12) -> Bool { abs(a - b) < eps }

private func close(_ a: CGPoint, _ b: CGPoint, _ eps: CGFloat = 1e-12) -> Bool {
    close(a.x, b.x, eps) && close(a.y, b.y, eps)
}

private func close(_ a: CGRect, _ b: CGRect, _ eps: CGFloat = 1e-12) -> Bool {
    close(a.minX, b.minX, eps) && close(a.minY, b.minY, eps) && close(a.width, b.width, eps)
        && close(a.height, b.height, eps)
}

/// Точки кадра: углы, центр и значения, которых нет в двоичной дроби точно (0,3; 0,1…): на них видна ошибка округления
/// в «1 − x».
private let points = [
    CGPoint(x: 0.3, y: 0.5), CGPoint(x: 0.2, y: 0.1), CGPoint(x: 0, y: 0), CGPoint(x: 1, y: 1), CGPoint(x: 1, y: 0),
    CGPoint(x: 0.5, y: 0.5), CGPoint(x: 0.123, y: 0.987), CGPoint(x: 0.01, y: 0.99),
]

/// Прямоугольники не квадратные и не по центру: широкий, высокий, у самого края, на весь кадр.
private let rects = [
    CGRect(x: 0.1, y: 0.2, width: 0.3, height: 0.1), CGRect(x: 0.05, y: 0.1, width: 0.1, height: 0.6),
    CGRect(x: 0.9, y: 0, width: 0.1, height: 1), CGRect(x: 0, y: 0.4, width: 1, height: 0.2),
    CGRect(x: 0, y: 0, width: 1, height: 1),
]

// MARK: - Значения и подписи

@Test func orientationKeepsTheCasesAndRawValues() {
    // порядок — как в таблице спецификации: 0 «Как есть», 1 «Зеркально», 2 «Вверх ногами», 3 «Зеркально + 180°»
    #expect(FrameOrientation.allCases == [.off, .mirror, .rotate180, .mirror180])
    #expect(FrameOrientation.allCases.map(\.rawValue) == ["off", "mirror", "180", "mirror180"])
    #expect(FrameOrientation(rawValue: "180") == .rotate180)
    #expect(FrameOrientation(rawValue: "rotate180") == nil)
    #expect(FrameOrientation(rawValue: "90") == nil)
}

@Test func orientationFlipsMatchTheTable() {
    // x переворачивают зеркало и 180°; y — 180° и зеркало + 180°
    #expect(FrameOrientation.allCases.map(\.flipsX) == [false, true, true, false])
    #expect(FrameOrientation.allCases.map(\.flipsY) == [false, false, true, true])
}

@Test func orientationTitlesAndLabelsAreTheShippedTexts() {
    // варианты в полосе над плитками и значение на самой плитке
    #expect(FrameOrientation.allCases.map(\.title) == ["Как есть", "Зеркально", "Вверх ногами", "Зеркально + 180°"])
    #expect(FrameOrientation.allCases.map(\.label) == ["—", "Зеркало", "180°", "Зеркало 180°"])
}

@Test func orientationIsStoredByItsRawValue() throws {
    let data = try JSONEncoder().encode(FrameOrientation.allCases)
    #expect(String(decoding: data, as: UTF8.self) == #"["off","mirror","180","mirror180"]"#)
    #expect(try JSONDecoder().decode([FrameOrientation].self, from: data) == FrameOrientation.allCases)
}

// MARK: - Точки

@Test func orientationOffLeavesPointsAndRectsAlone() {
    for p in points {
        #expect(FrameOrientation.off.toCamera(p) == p)
        #expect(FrameOrientation.off.fromCamera(p) == p)
    }
    for r in rects { #expect(FrameOrientation.off.fromCamera(r) == r) }
}

@Test func mirrorFlipsOnlyTheXAxis() {
    #expect(close(FrameOrientation.mirror.toCamera(CGPoint(x: 0.3, y: 0.5)), CGPoint(x: 0.7, y: 0.5)))
    #expect(close(FrameOrientation.mirror.fromCamera(CGPoint(x: 0.3, y: 0.5)), CGPoint(x: 0.7, y: 0.5)))
}

@Test func rotate180FlipsBothAxes() {
    #expect(close(FrameOrientation.rotate180.toCamera(CGPoint(x: 0.2, y: 0.1)), CGPoint(x: 0.8, y: 0.9)))
    #expect(close(FrameOrientation.rotate180.fromCamera(CGPoint(x: 0.2, y: 0.1)), CGPoint(x: 0.8, y: 0.9)))
}

@Test func mirror180FlipsOnlyTheYAxis() {
    #expect(close(FrameOrientation.mirror180.toCamera(CGPoint(x: 0.2, y: 0.1)), CGPoint(x: 0.2, y: 0.9)))
    #expect(close(FrameOrientation.mirror180.fromCamera(CGPoint(x: 0.2, y: 0.1)), CGPoint(x: 0.2, y: 0.9)))
}

@Test func orientationMappingIsAnInvolution() {
    for o in FrameOrientation.allCases {
        for p in points {
            // туда и обратно — та же точка (до округления дробей), в любую сторону
            #expect(close(o.toCamera(o.fromCamera(p)), p), "\(o) \(p)")
            #expect(close(o.fromCamera(o.toCamera(p)), p), "\(o) \(p)")
            // и перевод в одну сторону совпадает с переводом в другую
            #expect(o.toCamera(p) == o.fromCamera(p), "\(o) \(p)")
        }
    }
}

@Test func orientationKeepsTheFrameCenterAndMapsCornersToCorners() {
    for o in FrameOrientation.allCases {
        #expect(o.toCamera(CGPoint(x: 0.5, y: 0.5)) == CGPoint(x: 0.5, y: 0.5), "\(o)")
    }
    // левый верхний угол экрана при 180° — правый нижний угол камеры
    #expect(FrameOrientation.rotate180.toCamera(.zero) == CGPoint(x: 1, y: 1))
    #expect(FrameOrientation.mirror.toCamera(.zero) == CGPoint(x: 1, y: 0))
    #expect(FrameOrientation.mirror180.toCamera(.zero) == CGPoint(x: 0, y: 1))
}

// MARK: - Прямоугольники

@Test func fromCameraMovesARectWithoutChangingItsSize() {
    let rect = CGRect(x: 0.1, y: 0.2, width: 0.3, height: 0.1)
    #expect(close(FrameOrientation.off.fromCamera(rect), rect))
    #expect(close(FrameOrientation.mirror.fromCamera(rect), CGRect(x: 0.6, y: 0.2, width: 0.3, height: 0.1)))
    #expect(close(FrameOrientation.rotate180.fromCamera(rect), CGRect(x: 0.6, y: 0.7, width: 0.3, height: 0.1)))
    #expect(close(FrameOrientation.mirror180.fromCamera(rect), CGRect(x: 0.1, y: 0.7, width: 0.3, height: 0.1)))
}

@Test func fromCameraRectIsTheRectOfItsMappedCorners() {
    // широкие и высокие прямоугольники, у краёв и на весь кадр: ширина и высота не меняются местами, рамка не уходит
    // за кадр и остаётся на месте своих углов
    for o in FrameOrientation.allCases {
        for r in rects {
            let a = o.fromCamera(CGPoint(x: r.minX, y: r.minY)), b = o.fromCamera(CGPoint(x: r.maxX, y: r.maxY))
            let expected = CGRect(x: min(a.x, b.x), y: min(a.y, b.y), width: abs(a.x - b.x), height: abs(a.y - b.y))
            let mapped = o.fromCamera(r)
            #expect(close(mapped, expected), "\(o) \(r)")
            #expect(close(mapped.width, r.width) && close(mapped.height, r.height), "\(o) \(r)")
            #expect(mapped.minX >= -1e-12 && mapped.maxX <= 1 + 1e-12, "\(o) \(r)")
            #expect(mapped.minY >= -1e-12 && mapped.maxY <= 1 + 1e-12, "\(o) \(r)")
            // из камеры на экран и обратно — тот же прямоугольник
            #expect(close(o.fromCamera(mapped), r), "\(o) \(r)")
        }
    }
}

@Test func fromCameraRectKeepsItsCenterOnTheMappedPoint() {
    // центр рамки фокуса попадает туда же, куда его точка
    for o in FrameOrientation.allCases {
        for r in rects {
            #expect(close(CGPoint(x: o.fromCamera(r).midX, y: o.fromCamera(r).midY),
                          o.fromCamera(CGPoint(x: r.midX, y: r.midY))), "\(o) \(r)")
        }
    }
}
