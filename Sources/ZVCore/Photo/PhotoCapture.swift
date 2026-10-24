import Foundation

public struct CapturedObject: Equatable, Sendable {
    public let filename: String
    public let data: Data

    public init(filename: String, data: Data) {
        self.filename = filename
        self.data = data
    }
}

extension CameraSession {
    /// Снимок: полунажатие (автофокус), спуск, отпускание. При download — забирает все файлы снимка
    /// (RAW+JPEG — два файла) из памяти камеры по адресу 0xFFFFC001.
    public func capturePhoto(download: Bool) async throws -> [CapturedObject] {
        guard isReady else { throw SessionError.notReady }
        let half = [UInt32(SonyProp.shutterHalfPress)]
        let full = [UInt32(SonyProp.shutterFullPress)]
        let press = PTPValue.encode(2, type: PTPType.uint16)
        let release = PTPValue.encode(1, type: PTPType.uint16)

        try await call(SonyOp.controlDevice, half, outData: press)
        do {
            if properties[SonyProp.focusMode]?.current.intValue != 1 {
                // в автофокусе даём камере до секунды навестись (FocusFound 2 или 3 — готово)
                _ = try await waitFor(SonyProp.focusFound, timeout: .seconds(1)) { $0 == 2 || $0 == 3 }
            }
            try await call(SonyOp.controlDevice, full, outData: press)
            try await call(SonyOp.controlDevice, full, outData: release)
            try await call(SonyOp.controlDevice, half, outData: release)
        } catch {
            // спуск не должен остаться нажатым: отпускаем обе кнопки, ошибки отпускания уже не важны
            _ = try? await call(SonyOp.controlDevice, full, outData: release)
            _ = try? await call(SonyOp.controlDevice, half, outData: release)
            throw error
        }
        guard download else { return [] }

        var files: [CapturedObject] = []
        let clock = ContinuousClock()
        var deadline = clock.now.advanced(by: timing.captureTimeout)
        while clock.now < deadline {
            try await refresh()
            if (properties[SonyProp.objectInMemory]?.current.intValue ?? 0) > 0x8000 {
                let info = try ObjectInfo(data: try await call(SonyOp.getObjectInfo, [Sony.capturedObjectHandle]).data)
                // защита от повторной выдачи того же файла, пока счётчик в камере не обновился
                if files.contains(where: { $0.filename == info.filename }) { break }
                let data = try await call(SonyOp.getObject, [Sony.capturedObjectHandle]).data
                files.append(CapturedObject(filename: info.filename, data: data))
                // после файла ждём ещё немного: при RAW+JPEG второй появляется следом
                deadline = clock.now.advanced(by: timing.confirmTimeout)
                continue
            }
            try await Task.sleep(for: timing.pollInterval)
        }
        return files
    }
}
