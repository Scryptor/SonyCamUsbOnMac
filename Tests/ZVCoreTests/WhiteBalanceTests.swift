import Testing
@testable import ZVCore

@Test func temperatureReadingFollowsTheMode() {
    // ручная температура — значение камеры
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 0x8012, colorTemperature: 7400) == .manual(7400))
    // пресет — примерное значение: камера отдаёт сохранённое ручное, а не температуру пресета
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 4, colorTemperature: 7400) == .approximate(5600))
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 6, colorTemperature: 7400) == .approximate(3200))
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 0x8011, colorTemperature: 7400) == .approximate(7000))
    // автобаланс, под водой, свой — камера температуру не сообщает
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 2, colorTemperature: 7400) == .automatic)
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 0x8030, colorTemperature: 7400) == .automatic)
    #expect(WhiteBalanceTemperature.reading(whiteBalance: 0x8020, colorTemperature: 7400) == .automatic)
    #expect(WhiteBalanceTemperature.reading(whiteBalance: nil, colorTemperature: nil) == .automatic)
}

@Test func temperatureCaptionAndSliderPosition() {
    #expect(WhiteBalanceTemperature.caption(.manual(7400)) == "7400 K")
    #expect(WhiteBalanceTemperature.caption(.approximate(5600)) == "≈ 5600 K")
    #expect(WhiteBalanceTemperature.caption(.automatic) == "авто")
    // ползунок: ручное и пресет — на своём значении, авто — на последнем ручном
    #expect(WhiteBalanceTemperature.sliderValue(.manual(7400), stored: 7400) == 7400)
    #expect(WhiteBalanceTemperature.sliderValue(.approximate(5600), stored: 7400) == 5600)
    #expect(WhiteBalanceTemperature.sliderValue(.automatic, stored: 7400) == 7400)
    #expect(WhiteBalanceTemperature.sliderValue(.automatic, stored: nil) == 5600)
}

@Test func whiteBalanceLabelShowsKelvinInManualMode() {
    // в режиме «Цвет. темп.» в строке значений — сама температура, как на экране камеры
    #expect(ValueFormatter.whiteBalanceLabel(whiteBalance: 0x8012, colorTemperature: 7400) == "7400 K")
    #expect(ValueFormatter.whiteBalanceLabel(whiteBalance: 2, colorTemperature: 7400) == "AWB")
    #expect(ValueFormatter.whiteBalanceLabel(whiteBalance: 4, colorTemperature: 7400) == "Дневной")
}
