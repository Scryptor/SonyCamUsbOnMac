import Foundation
import Testing
@testable import ZVCore

@Test func captureCaptionsForEachPhase() {
    #expect(CaptureFeedback.caption(.focusing) == "Фокусировка…")
    #expect(CaptureFeedback.caption(.exposing) == "Снимаю…")
    #expect(CaptureFeedback.fraction(.focusing) == nil)
    #expect(CaptureFeedback.fraction(.exposing) == nil)
}

@Test func captureDownloadShowsShareOfBigFiles() {
    let phase = CapturePhase.downloading(file: 1, files: 1, received: 3_600_000, expected: 8_388_608)
    #expect(CaptureFeedback.caption(phase) == "Загрузка 42 %")
    #expect(abs((CaptureFeedback.fraction(phase) ?? 0) - 3_600_000.0 / 8_388_608) < 1e-9)
    let second = CapturePhase.downloading(file: 2, files: 2, received: 3_600_000, expected: 8_388_608)
    #expect(CaptureFeedback.caption(second) == "Загрузка 2/2 · 42 %")
}

@Test func captureDownloadOfSmallOrUnknownSizeSpins() {
    let small = CapturePhase.downloading(file: 1, files: 1, received: 100_000, expected: 300_000)
    #expect(CaptureFeedback.caption(small) == "Загрузка…")
    #expect(CaptureFeedback.fraction(small) == nil)
    let unknown = CapturePhase.downloading(file: 1, files: 1, received: 5_000_000, expected: nil)
    #expect(CaptureFeedback.caption(unknown) == "Загрузка…")
    #expect(CaptureFeedback.fraction(unknown) == nil)
    let secondSmall = CapturePhase.downloading(file: 2, files: 2, received: 100_000, expected: 300_000)
    #expect(CaptureFeedback.caption(secondSmall) == "Загрузка 2/2…")
}

@Test func capturePercentRoundsDown() {
    // 100 % — только когда пришло всё
    let almost = CapturePhase.downloading(file: 1, files: 1, received: 8_388_000, expected: 8_388_608)
    #expect(CaptureFeedback.caption(almost) == "Загрузка 99 %")
}

@Test func captureShareIsClamped() {
    let over = CapturePhase.downloading(file: 1, files: 1, received: 9_000_000, expected: 8_388_608)
    #expect(CaptureFeedback.fraction(over) == 1)
    #expect(CaptureFeedback.caption(over) == "Загрузка 100 %")
}
