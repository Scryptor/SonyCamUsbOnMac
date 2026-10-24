import Testing
@testable import ZVCore

@Test func shootingModeFollowsTheExposureProgram() {
    #expect(ShootingMode(exposureProgram: 0x78053) == .video)
    #expect(ShootingMode(exposureProgram: 0x9805C) == .slowQuick)
    #expect(ShootingMode(exposureProgram: 0x1) == .photo)
    #expect(ShootingMode(exposureProgram: 0x48000) == .photo)
    // замедленная съёмка — та же видеосъёмка: интерфейс видео, запись кнопкой REC
    #expect(ShootingMode.slowQuick.captureMode == .video)
    #expect(ShootingMode.photo.captureMode == .photo)
}

@Test func shootingModeCounterpartKeepsTheLetter() {
    let settable: [Int64] = [0x10002, 0x20003, 0x30004, 0x1, 0x78050, 0x78051, 0x78052, 0x78053,
                             0x98059, 0x9805A, 0x9805B, 0x9805C, 0x48000]
    #expect(ShootingMode.counterpart(of: 0x78053, to: .slowQuick, settable: settable) == 0x9805C)  // видео M → S&Q M
    #expect(ShootingMode.counterpart(of: 0x9805A, to: .video, settable: settable) == 0x78051)      // S&Q A → видео A
    #expect(ShootingMode.counterpart(of: 0x1, to: .slowQuick, settable: settable) == 0x9805C)      // M → S&Q M
    #expect(ShootingMode.counterpart(of: 0x48000, to: .slowQuick, settable: settable) == 0x98059)  // авто → S&Q P
    #expect(ShootingMode.counterpart(of: 0x9805C, to: .photo, settable: settable) == 0x1)          // S&Q M → M
    #expect(ShootingMode.counterpart(of: 0x9805C, to: .slowQuick, settable: settable) == nil)      // уже там
    #expect(ShootingMode.counterpart(of: 0x78053, to: .slowQuick, settable: [0x78053]) == nil)     // камера не даёт
}

@Test func slowQuickFrameRateComesFromTheSlowestShutter() {
    // так на ZV-E10 (PAL): самая длинная выдержка, которую камера даёт в S&Q при частоте съёмки 100…1 к/с
    let observed: [(Int64, Int)] = [(0x10064, 100), (0x10032, 50), (0x10019, 25), (0x1000D, 15),
                                    (0x10006, 8), (0x10003, 4), (0x5000A, 2), (0xA000A, 1)]
    for (slowest, fps) in observed {
        #expect(SlowQuick.captureFrameRate(shutterValues: [0x10FA0, 0x103E8, slowest], pal: true) == fps)
    }
    #expect(SlowQuick.captureFrameRate(shutterValues: [], pal: true) == nil)
    // NTSC: самая длинная 1/125 — 120 к/с
    #expect(SlowQuick.captureFrameRate(shutterValues: [0x1007D, 0x103E8], pal: false) == 120)
}

@Test func slowQuickSpeedLabel() {
    #expect(SlowQuick.speedLabel(capture: 100, record: 25) == "×4 медленнее")
    #expect(SlowQuick.speedLabel(capture: 100, record: 100) == "обычная скорость")
    #expect(SlowQuick.speedLabel(capture: 1, record: 25) == "×25 быстрее")
    #expect(SlowQuick.speedLabel(capture: 15, record: 50) == "×3,3 быстрее")
    #expect(RecordingModes.frameRate(of: 0x1D) == 100)
    #expect(RecordingModes.frameRate(of: 0x05) == 25)
}
