import Foundation
import ImageCaptureCore
import ZVCore

public enum DiscoveryEvent: @unchecked Sendable {
    case appeared(ICCameraDevice)
    case disappeared(ICCameraDevice)
}

/// Следит за подключением и отключением камер Sony по USB.
@MainActor
public final class CameraDiscovery: NSObject, ICDeviceBrowserDelegate {
    public nonisolated let events: AsyncStream<DiscoveryEvent>
    private nonisolated let continuation: AsyncStream<DiscoveryEvent>.Continuation
    private let browser = ICDeviceBrowser()

    public override init() {
        (events, continuation) = AsyncStream.makeStream()
        super.init()
    }

    public func start() {
        browser.delegate = self
        browser.browsedDeviceTypeMask = ICDeviceTypeMask(
            rawValue: ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue)!
        browser.start()
    }

    public nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        guard let camera = device as? ICCameraDevice, Int(device.usbVendorID) == Sony.vendorID else { return }
        continuation.yield(.appeared(camera))
    }

    public nonisolated func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        guard let camera = device as? ICCameraDevice, Int(device.usbVendorID) == Sony.vendorID else { return }
        continuation.yield(.disappeared(camera))
    }
}

public extension ICCameraDevice {
    /// Открывает сессию; если система не ответила за `timeout` секунд — TransportError.timeout.
    func openSession(timeout: TimeInterval = 10) async throws {
        try await withCheckedThrowingContinuation { (done: CheckedContinuation<Void, Error>) in
            let once = Once()
            requestOpenSession(options: [:]) { error in
                guard once.claim() else { return }
                if let error { done.resume(throwing: error) } else { done.resume() }
            }
            DispatchQueue.main.asyncAfter(deadline: .now() + timeout) {
                if once.claim() { done.resume(throwing: TransportError.timeout(operation: 0x1002)) }
            }
        }
    }
}
