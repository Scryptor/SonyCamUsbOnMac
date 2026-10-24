import Foundation
import ImageCaptureCore
import ZVCore

/// PTP через системный ImageCaptureCore: без libusb, sudo и борьбы с системной службой камер.
public final class ICCameraTransport: NSObject, PTPTransport, ICCameraDeviceDelegate, @unchecked Sendable {
    public let events: AsyncStream<PTPEvent>
    private let eventContinuation: AsyncStream<PTPEvent>.Continuation
    private let device: ICCameraDevice
    private let timeout: Duration
    private let lock = NSLock()
    private var removed = false

    public init(device: ICCameraDevice, timeout: Duration = .seconds(8)) {
        self.device = device
        self.timeout = timeout
        (events, eventContinuation) = AsyncStream.makeStream()
        super.init()
        device.delegate = self
    }

    private var isRemoved: Bool {
        lock.lock()
        defer { lock.unlock() }
        return removed
    }

    public func send(_ operation: UInt16, _ params: [UInt32], outData: Data?) async throws -> PTPReply {
        if isRemoved { throw TransportError.disconnected }
        let command = PTPCommand.encode(operation, params)
        let seconds = Double(timeout.components.seconds) + Double(timeout.components.attoseconds) / 1e18
        return try await withCheckedThrowingContinuation { continuation in
            let once = Once()
            device.requestSendPTPCommand(command, outData: outData) { [weak self] data, response, error in
                guard once.claim() else { return }
                if let error {
                    let removed = self?.isRemoved ?? true
                    continuation.resume(throwing: removed ? TransportError.disconnected : TransportError.failed("\(error)"))
                } else {
                    continuation.resume(returning: PTPReply.fromContainers(response: response, data: data, operation: operation))
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + seconds) { [weak self] in
                guard once.claim() else { return }
                let removed = self?.isRemoved ?? true
                continuation.resume(throwing: removed ? TransportError.disconnected : TransportError.timeout(operation: operation))
            }
        }
    }

    /// Закрывает сессию (не дольше 3 с) и отвязывается от устройства. ICDevice.delegate — assign, а не weak:
    /// если не отвязаться, следующий колбэк ICC придёт в освобождённый объект.
    public func close() async {
        if device.hasOpenSession, !isRemoved {
            await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
                let once = Once()
                device.requestCloseSession(options: [:]) { _ in if once.claim() { done.resume() } }
                DispatchQueue.main.asyncAfter(deadline: .now() + 3) { if once.claim() { done.resume() } }
            }
        }
        await MainActor.run { detach() }
        eventContinuation.finish()
    }

    private func detach() {
        if (device.delegate as AnyObject?) === self { device.delegate = nil }
    }

    deinit {
        if (device.delegate as AnyObject?) === self { device.delegate = nil }
    }

    private func markRemoved() {
        lock.lock()
        removed = true
        lock.unlock()
        eventContinuation.finish()
    }

    // MARK: - ICDeviceDelegate

    public func didRemove(_ device: ICDevice) { markRemoved() }
    public func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {}
    public func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {}

    // MARK: - ICCameraDeviceDelegate

    public func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) {
        // контейнер события: длина u32, тип u16, код u16, transaction id u32, параметры
        var r = ByteReader(eventData)
        guard (try? r.skip(6)) != nil, let code = try? r.u16() else { return }
        var params: [UInt32] = []
        if (try? r.skip(4)) != nil {
            while let p = try? r.u32() { params.append(p) }
        }
        eventContinuation.yield(PTPEvent(code: code, params: params))
    }

    public func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {}
    public func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    public func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?,
                             for item: ICCameraItem, error: Error?) {}
    public func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?,
                             for item: ICCameraItem, error: Error?) {}
    public func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    public func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {}
    public func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {}
    public func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    public func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}
}

/// Гарантирует, что continuation возобновится ровно один раз: либо ответ, либо таймаут.
final class Once: @unchecked Sendable {
    private let lock = NSLock()
    private var used = false

    func claim() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if used { return false }
        used = true
        return true
    }
}
