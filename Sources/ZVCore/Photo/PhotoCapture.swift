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
    /// (RAW+JPEG — два файла) из памяти камеры по адресу 0xFFFFC001. files — сколько файлов ждать (по формату
    /// снимка): забрав их, больше не ждём; nil — после каждого файла ждём, не появится ли ещё один.
    /// onPhase — этапы для экрана: фокусировка, спуск отработан, качается файл N.
    public func capturePhoto(download: Bool, files expected: Int? = nil,
                             onPhase: @Sendable (CapturePhase) -> Void = { _ in }) async throws -> [CapturedObject] {
        guard isReady else { throw SessionError.notReady }
        let half = [UInt32(SonyProp.shutterHalfPress)]
        let full = [UInt32(SonyProp.shutterFullPress)]
        let press = PTPValue.encode(2, type: PTPType.uint16)
        let release = PTPValue.encode(1, type: PTPType.uint16)

        try await call(SonyOp.controlDevice, half, outData: press)
        onPhase(.focusing)
        do {
            if properties[SonyProp.focusMode]?.current.intValue != 1 {
                // в автофокусе даём камере до секунды навестись (FocusFound 2 или 3 — готово)
                _ = try await waitFor(SonyProp.focusFound, timeout: .seconds(1)) { $0 == 2 || $0 == 3 }
            }
            try await call(SonyOp.controlDevice, full, outData: press)
            onPhase(.exposing)
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
            guard (properties[SonyProp.objectInMemory]?.current.intValue ?? 0) > 0x8000 else {
                // все ожидаемые файлы забраны, и в памяти камеры пусто — готово, следующего не ждём
                if let expected, files.count >= expected { break }
                try await Task.sleep(for: timing.pollInterval)
                continue
            }
            let info = try ObjectInfo(data: try await call(SonyOp.getObjectInfo, [Sony.capturedObjectHandle]).data)
            // защита от повторной выдачи того же файла, пока счётчик в камере не обновился
            if files.contains(where: { $0.filename == info.filename }) { break }
            onPhase(.downloading(file: files.count + 1, files: max(expected ?? 1, files.count + 1), received: 0,
                                 expected: Int(info.compressedSize)))
            let data = try await call(SonyOp.getObject, [Sony.capturedObjectHandle]).data
            files.append(CapturedObject(filename: info.filename, data: data))
            // после файла ждём ещё немного: при RAW+JPEG второй появляется следом
            deadline = clock.now.advanced(by: timing.confirmTimeout)
        }
        return files
    }
}
