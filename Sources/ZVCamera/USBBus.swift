import Foundation
import IOKit
import ZVCore

/// Что видно на шине USB в обход ImageCaptureCore.
public enum USBBus {
    /// Есть ли на шине камера Sony — устройство Sony с интерфейсом «Still Image» (PTP), даже если macOS
    /// ещё не отдала его программе (например, ждёт ответа в окне доступа к съёмным томам).
    public static func hasSonyCamera() -> Bool {
        guard let matching = IOServiceMatching("IOUSBHostInterface") as NSMutableDictionary? else { return false }
        matching[kIOPropertyMatchKey] = ["idVendor": Sony.vendorID, "bInterfaceClass": 6]
        var iterator: io_iterator_t = 0
        guard IOServiceGetMatchingServices(kIOMainPortDefault, matching, &iterator) == KERN_SUCCESS else { return false }
        defer { IOObjectRelease(iterator) }
        let service = IOIteratorNext(iterator)
        guard service != 0 else { return false }
        IOObjectRelease(service)
        return true
    }
}
