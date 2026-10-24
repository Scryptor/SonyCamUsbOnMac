// SPIKE (throwaway): can a Sony ZV-E10 in PC Remote mode be driven through ImageCaptureCore's
// PTP passthrough (no libusb, no sudo)? Checks discovery, Sony handshake, property dump,
// live view frame rate, and whether data-out commands (setting a property) work.
import Foundation
import ImageCaptureCore
import ImageIO

let outDir = URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("out")
try? FileManager.default.createDirectory(at: outDir, withIntermediateDirectories: true)

final class Log {
    static let shared = Log()
    private let handle: FileHandle?
    private let lock = NSLock()
    private let start = Date()

    init() {
        let url = outDir.appendingPathComponent("probe.log")
        FileManager.default.createFile(atPath: url.path, contents: nil)
        handle = try? FileHandle(forWritingTo: url)
    }

    func write(_ s: String) {
        lock.lock(); defer { lock.unlock() }
        let line = String(format: "[%7.2f] ", Date().timeIntervalSince(start)) + s
        print(line)
        fflush(stdout)
        handle?.write(Data((line + "\n").utf8))
    }
}

func log(_ s: String) { Log.shared.write(s) }

func save(_ data: Data, _ name: String) {
    try? data.write(to: outDir.appendingPathComponent(name))
}

enum ProbeError: Error, CustomStringConvertible {
    case timeout(UInt16, TimeInterval)
    case failed(String)

    var description: String {
        switch self {
        case let .timeout(code, t): return "timeout after \(t)s waiting for 0x\(hex16(code))"
        case let .failed(s): return s
        }
    }
}

final class OnceGate: @unchecked Sendable {
    private let lock = NSLock()
    private var used = false

    func claim() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if used { return false }
        used = true
        return true
    }
}

struct Exchange {
    let response: PTPResponse
    let data: Data
    let millis: Int
}

enum DataOutVariant: String, CaseIterable {
    case raw = "outData = raw payload only"
    case container = "outData = full PTP data container (header + payload)"
}

final class Probe: NSObject, ICDeviceBrowserDelegate, ICCameraDeviceDelegate {
    let browser = ICDeviceBrowser()
    var camera: ICCameraDevice?
    private var checklist: [(String, Bool?, String)] = []
    private var eventCounts: [UInt16: Int] = [:]
    private let eventLock = NSLock()
    private var lastTransactionID: UInt32 = 0
    private var loggedResponseFormat = false
    private var loggedDataFormat = false

    func start() {
        browser.delegate = self
        browser.browsedDeviceTypeMask = ICDeviceTypeMask(
            rawValue: ICDeviceTypeMask.camera.rawValue | ICDeviceLocationTypeMask.local.rawValue)!
        browser.start()
        log("Waiting for a camera on USB…")
    }

    func check(_ title: String, _ ok: Bool?, _ detail: String = "") {
        checklist.append((title, ok, detail))
        let mark = ok == nil ? "[ ? ]" : (ok! ? "[ OK ]" : "[FAIL]")
        log("\(mark) \(title)\(detail.isEmpty ? "" : " — " + detail)")
    }

    // MARK: ICDeviceBrowserDelegate

    func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
        let usb = String(format: "%04x:%04x", device.usbVendorID, device.usbProductID)
        log("Device appeared: name=\(device.name ?? "?") kind=\(device.productKind ?? "?") transport=\(device.transportType ?? "?") usb=\(usb)")
        guard let cam = device as? ICCameraDevice else { return }
        guard device.usbVendorID == 0x054C else {
            log("  not a Sony device, ignoring")
            return
        }
        guard camera == nil else { return }
        camera = cam
        cam.delegate = self
        cam.ptpEventHandler = { [weak self] data in self?.recordEvent(data) }
        log("  capabilities: \(cam.capabilities.joined(separator: ", "))")
        check("Camera visible to ImageCaptureCore", true, "\(device.name ?? "?") usb \(usb)")
        cam.requestOpenSession(options: [:]) { error in
            if let error {
                self.check("Open session", false, "\(error)")
                self.finish(code: 2)
                return
            }
            self.check("Open session", true)
            Task { await self.run(cam) }
        }
    }

    func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
        log("Device removed: \(device.name ?? "?")")
    }

    // MARK: ICDeviceDelegate

    func didRemove(_ device: ICDevice) { log("didRemove: \(device.name ?? "?")") }
    func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {}
    func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {
        log("Session closed\(error.map { " with error \($0)" } ?? "")")
    }
    func deviceDidBecomeReady(_ device: ICDevice) { log("deviceDidBecomeReady") }
    func device(_ device: ICDevice, didEncounterError error: Error?) {
        log("device error: \(String(describing: error))")
    }

    // MARK: ICCameraDeviceDelegate

    func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {
        log("camera reported \(items.count) new item(s)")
    }
    func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?, for item: ICCameraItem, error: Error?) {}
    func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
    func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {
        log("capabilities changed: \(camera.capabilities.joined(separator: ", "))")
    }
    func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent eventData: Data) { recordEvent(eventData) }
    func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) { log("content catalog complete") }
    func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {}
    func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {}

    private func recordEvent(_ data: Data) {
        var r = ByteReader(data)
        guard data.count >= 8, (try? r.u32()) != nil, (try? r.u16()) != nil, let code = try? r.u16() else {
            log("event (unparsed): \(data.hexPreview())")
            return
        }
        _ = try? r.u32()
        var params: [String] = []
        while let p = try? r.u32() { params.append("0x" + hex32(p)) }
        eventLock.lock()
        eventCounts[code, default: 0] += 1
        let n = eventCounts[code]!
        eventLock.unlock()
        if n <= 3 {
            let name = code == 0xC203 ? " (property changed)" : ""
            log("event 0x\(hex16(code))\(name) params=\(params.joined(separator: ","))")
        }
    }

    // MARK: PTP transport

    func send(_ cam: ICCameraDevice, _ code: UInt16, _ params: [UInt32] = [], outData: Data? = nil,
              timeout: TimeInterval = 8, quiet: Bool = false) async throws -> Exchange {
        let command = makeCommand(code, params)
        let t0 = Date()
        let (data, response): (Data, Data) = try await withCheckedThrowingContinuation { cont in
            let gate = OnceGate()
            cam.requestSendPTPCommand(command, outData: outData) { data, response, error in
                guard gate.claim() else { return }
                if let error {
                    cont.resume(throwing: error)
                } else {
                    cont.resume(returning: (data, response))
                }
            }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) {
                guard gate.claim() else { return }
                cont.resume(throwing: ProbeError.timeout(code, timeout))
            }
        }
        let ms = Int(Date().timeIntervalSince(t0) * 1000)
        let resp = PTPResponse(response)
        if !loggedResponseFormat {
            loggedResponseFormat = true
            log("  (format) raw response container for 0x\(hex16(code)): \(response.hexPreview())")
        }
        if let tid = resp.transactionID { lastTransactionID = tid }
        let (payload, hadHeader) = stripDataHeader(data, code: code)
        if !loggedDataFormat && !data.isEmpty {
            loggedDataFormat = true
            log("  (format) data-in phase \(hadHeader ? "INCLUDES" : "does not include") a container header")
        }
        if !quiet {
            let p = params.map { "0x" + hex32($0) }.joined(separator: ",")
            log("  0x\(hex16(code))(\(p))\(outData.map { " out=\($0.count)B" } ?? "") -> \(RC.name(resp.code)) data=\(payload.count)B tid=\(resp.transactionID.map(String.init) ?? "?") \(ms)ms")
        }
        return Exchange(response: resp, data: payload, millis: ms)
    }

    // MARK: Probe sequence

    func run(_ cam: ICCameraDevice) async {
        do {
            try await Task.sleep(nanoseconds: 1_500_000_000)

            // 1. Standard device info
            let di = try await send(cam, OC.getDeviceInfo)
            save(di.data, "deviceinfo.bin")
            let info = try DeviceInfo(di.data)
            log("  Model: \(info.manufacturer) \(info.model), firmware \(info.deviceVersion), vendor ext 0x\(hex32(info.vendorExtensionID)) v\(info.vendorExtensionVersion) \"\(info.vendorExtensionDesc)\"")
            log("  \(info.operations.count) operations, \(info.events.count) events, \(info.properties.count) properties in standard DeviceInfo")
            let pcRemote = info.operations.contains(OC.sonyConnect)
            check("PTP DeviceInfo readable (data-in works)", true, "\(info.model) fw \(info.deviceVersion)")
            check("Camera is in PC Remote mode (Sony SDIO ops present)", pcRemote,
                  pcRemote ? "" : "no 0x9201: enable MENU > Network > PC Remote Function > PC Remote = On, method USB, then reconnect")
            guard pcRemote else { return finish(code: 1) }

            // 2. Sony handshake (as libgphoto2 does it)
            _ = try await send(cam, OC.sonyConnect, [1, 0, 0])
            _ = try await send(cam, OC.sonyConnect, [2, 0, 0])
            var ext: SonyExtInfo?
            for _ in 0..<20 {
                let r = try await send(cam, OC.sonyGetExtDeviceInfo, [sonyProtocol300, 1])
                if !r.data.isEmpty {
                    save(r.data, "sony_extdeviceinfo.bin")
                    ext = try SonyExtInfo(r.data)
                    break
                }
                try await Task.sleep(nanoseconds: 100_000_000)
            }
            _ = try await send(cam, OC.sonyConnect, [3, 0, 0])
            // libgphoto2 hands control priority to the computer right after the handshake.
            let pm = try await send(cam, OC.sonySetExtDevicePropValue, [UInt32(sonyPriorityMode)],
                                    outData: encodeValue(1, type: 0x0001))
            log("  PriorityMode=1 (computer) -> \(RC.name(pm.response.code))")
            if let ext {
                check("Sony handshake", true, "protocol 0x\(hex16(ext.version)), \(ext.properties.count) properties, \(ext.controls.count) controls")
            } else {
                check("Sony handshake", false, "GetExtDeviceInfo returned nothing")
            }

            // 3. Full property dump
            let props = try await readProperties(cam, file: "props_initial.bin")
            check("Read all camera settings", !props.isEmpty, "\(props.count) properties")
            if let settle = ProcessInfo.processInfo.environment["SETTLE"].flatMap(Double.init), settle > 0 {
                log("  settling \(settle)s after handshake")
                try await Task.sleep(nanoseconds: UInt64(settle * 1_000_000_000))
            }
            if case let .set(code, value, variant, type) = mode {
                await runSet(cam, props: props, code: code, value: value, variant: variant, fallbackType: type)
                return finish(code: 0)
            }
            if case let .walk(code, target) = mode {
                await runWalk(cam, props: props, code: code, target: target)
                return finish(code: 0)
            }
            if case .dump = mode {
                let props = try await readProperties(cam, file: "props_dump.bin")
                dump(props)
                for p in props where [0x5007, 0x500A, 0x500E, 0xD20D, 0xD21E].contains(p.code) {
                    log("  detail 0x\(hex16(p.code)) \(p.name): getset=\(p.getSet) enabled=\(p.isEnabled) form=\(p.formFlag) enum1=\(p.enumValues.compactMap { $0.intValue }.map { String($0, radix: 16) }) enum2=\((p.enumValues2 ?? []).compactMap { $0.intValue }.map { String($0, radix: 16) })")
                }
                return finish(code: 0)
            }
            dump(props)

            // 4. Live view
            await liveView(cam, frames: 90)

            // 5. Data-out (changing settings)
            await dataOutTests(cam, props: props)
        } catch {
            check("Probe sequence", false, "aborted: \(error)")
        }
        finish(code: 0)
    }

    /// One targeted write + read-back, for experiments: `ptp-probe set 0x5005 2 raw`.
    func runSet(_ cam: ICCameraDevice, props: [SonyProperty], code: UInt16, value: Int64,
                variant: DataOutVariant, fallbackType: UInt16) async {
        let existing = props.first { $0.code == code }
        let type = existing?.dataType ?? fallbackType
        log("  before: 0x\(hex16(code)) \(propertyNames[code] ?? "?") = \(existing.map { "\($0.currentValue)" } ?? "not listed") writable=\(existing.map { "\($0.writable)" } ?? "?") type=\(typeName(type))")
        do {
            let out = payload(for: value, type: type, variant: variant)
            let r = try await send(cam, OC.sonySetExtDevicePropValue, [UInt32(code)], outData: out, timeout: 6)
            log("  set -> \(value) via \(variant.rawValue): \(RC.name(r.response.code))")
            guard existing != nil else {
                check("Set 0x\(hex16(code)) = \(value)", r.response.code == RC.ok, "not readable, no read-back")
                return
            }
            // Log every transition for 5 s: some Sony settings step through intermediate values.
            var seen = existing?.currentValue.intValue
            let t0 = Date()
            while Date().timeIntervalSince(t0) < 5 {
                try await Task.sleep(nanoseconds: 100_000_000)
                let now = try await readProperties(cam).first { $0.code == code }?.currentValue.intValue
                if now != seen {
                    log(String(format: "  +%.2fs value %@ -> %@", Date().timeIntervalSince(t0),
                               seen.map { "0x" + String($0, radix: 16) } ?? "?", now.map { "0x" + String($0, radix: 16) } ?? "?"))
                    seen = now
                }
            }
            check("Set 0x\(hex16(code)) \(propertyNames[code] ?? "?") = \(value)", seen == value,
                  "camera reports \(seen.map(String.init) ?? "?")")
            let after = try await readProperties(cam)
            for p in after {
                guard let old = props.first(where: { $0.code == p.code }) else { continue }
                if old.currentValue != p.currentValue || old.isEnabled != p.isEnabled || old.getSet != p.getSet {
                    log("  changed 0x\(hex16(p.code)) \(p.name): \(old.currentValue) -> \(p.currentValue) (enabled \(old.isEnabled)->\(p.isEnabled), getset \(old.getSet)->\(p.getSet))")
                }
            }
        } catch {
            check("Set 0x\(hex16(code))", false, "\(error)")
        }
    }

    /// Moves an enumerated setting one neighbour at a time (`ptp-probe walk 0x5005 2`), trying one
    /// direct jump after the first successful step to see whether jumps are honoured at all.
    func runWalk(_ cam: ICCameraDevice, props: [SonyProperty], code: UInt16, target: Int64) async {
        func h(_ v: Int64) -> String { "0x" + String(v, radix: 16) }
        guard let p = props.first(where: { $0.code == code }), var cur = p.currentValue.intValue else {
            check("Walk 0x\(hex16(code))", false, "property not found")
            return
        }
        let order = p.enumValues.compactMap { $0.intValue }
        guard let ti = order.firstIndex(of: target) else {
            check("Walk \(p.name)", false, "target \(h(target)) not in settable list \(order.map(h))")
            return
        }
        log("  walk \(p.name) \(h(cur)) -> \(h(target)) over \(order.map(h))")
        var jumpTried = false
        var steps = 0
        do {
            while cur != target && steps < order.count + 3 {
                guard let ci = order.firstIndex(of: cur) else {
                    log("  current \(h(cur)) not in settable list")
                    break
                }
                let neighbour = order[ci + (ti > ci ? 1 : -1)]
                let goal = (steps == 1 && !jumpTried) ? target : neighbour
                if goal == target && goal != neighbour { jumpTried = true }
                _ = try await send(cam, OC.sonySetExtDevicePropValue, [UInt32(code)],
                                   outData: encodeValue(goal, type: p.dataType), quiet: true)
                let reached = try await waitForChange(cam, code: code, from: cur, timeout: 2.5)
                log("  step \(steps): asked \(h(goal))\(goal == neighbour ? " (neighbour)" : " (direct jump)") -> now \(h(reached))")
                cur = reached
                steps += 1
            }
        } catch {
            log("  walk error: \(error)")
        }
        check("Walk \(p.name) to \(h(target))", cur == target, "final \(h(cur)) after \(steps) writes")
    }

    func waitForChange(_ cam: ICCameraDevice, code: UInt16, from: Int64, timeout: TimeInterval) async throws -> Int64 {
        let t0 = Date()
        while Date().timeIntervalSince(t0) < timeout {
            try await Task.sleep(nanoseconds: 100_000_000)
            if let v = try await readProperties(cam).first(where: { $0.code == code })?.currentValue.intValue, v != from {
                try await Task.sleep(nanoseconds: 300_000_000)
                return try await readProperties(cam).first(where: { $0.code == code })?.currentValue.intValue ?? v
            }
        }
        return from
    }

    func readProperties(_ cam: ICCameraDevice, file: String? = nil) async throws -> [SonyProperty] {
        let r = try await send(cam, OC.sonyGetAllExtDevicePropInfo, quiet: file == nil)
        if let file { save(r.data, file) }
        let parsed = parseSonyProperties(r.data)
        if let err = parsed.error { log("  property parse: \(err) (parsed \(parsed.props.count) of \(parsed.declaredCount))") }
        return parsed.props
    }

    func dump(_ props: [SonyProperty]) {
        log("  ---- camera settings (code name type access value) ----")
        for p in props {
            let access = p.writable ? "rw" : (p.isEnabled == 0 ? "--" : "r ")
            let nice = pretty(p.code, p.currentValue).map { " = \($0)" } ?? ""
            var form = ""
            if let rg = p.range { form = " range[\(rg.min)…\(rg.max) step \(rg.step)]" }
            if !p.enumValues.isEmpty {
                let vals = p.enumValues.prefix(10).compactMap { $0.intValue }.map { String($0, radix: 16) }
                form = " enum(\(p.enumValues.count))[\(vals.joined(separator: ","))\(p.enumValues.count > 10 ? ",…" : "")]"
            }
            if let e2 = p.enumValues2 { form += " enum2(\(e2.count))" }
            log("  0x\(hex16(p.code)) \(p.name.padding(toLength: 30, withPad: " ", startingAt: 0)) \(typeName(p.dataType).padding(toLength: 4, withPad: " ", startingAt: 0)) \(access) \(p.currentValue)\(nice)\(form)")
        }
    }

    func liveView(_ cam: ICCameraDevice, frames: Int) async {
        do {
            var ready = false
            for attempt in 0..<40 {
                let r = try await send(cam, OC.getObjectInfo, [sonyLiveViewHandle], quiet: attempt > 0)
                if r.response.code == RC.ok { ready = true; break }
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            guard ready else {
                check("Live view object available", false, "GetObjectInfo(0xFFFFC002) never returned OK")
                return
            }
            var good = 0, bytes = 0
            var errors: [UInt16: Int] = [:]
            var first: Data?, last: Data?
            var latencies: [Int] = []
            let t0 = Date()
            for _ in 0..<frames {
                let r = try await send(cam, OC.getObject, [sonyLiveViewHandle], quiet: true)
                if r.response.code == RC.ok && !r.data.isEmpty {
                    good += 1
                    bytes += r.data.count
                    latencies.append(r.millis)
                    if first == nil { first = r.data }
                    last = r.data
                } else {
                    errors[r.response.code, default: 0] += 1
                    try await Task.sleep(nanoseconds: 20_000_000)
                }
            }
            let dt = Date().timeIntervalSince(t0)
            guard let first, let last else {
                check("Live view frames", false, "no frames; errors \(errors.map { "\(RC.name($0.key))×\($0.value)" })")
                return
            }
            save(first, "lv_first_raw.bin")
            log("  live view raw header: \(first.hexPreview(32))")
            var size = "?"
            for (frame, name) in [(first, "lv_first.jpg"), (last, "lv_last.jpg")] {
                if let jpeg = extractJPEG(frame) {
                    save(jpeg.data, name)
                    if let s = jpegSize(jpeg.data) { size = "\(s.0)x\(s.1)" }
                    if name == "lv_first.jpg" { log("  JPEG starts at offset \(jpeg.offset)") }
                }
            }
            let fps = Double(good) / dt
            let sorted = latencies.sorted()
            let median = sorted[sorted.count / 2]
            check("Live view frames", good > 0,
                  String(format: "%d/%d frames, %.1f fps, %@ px, ~%d KB/frame, median %d ms/frame%@",
                         good, frames, fps, size, bytes / max(good, 1) / 1024, median,
                         errors.isEmpty ? "" : ", errors \(errors.map { "\(RC.name($0.key))×\($0.value)" })"))
        } catch {
            check("Live view", false, "\(error)")
        }
    }

    func dataOutTests(_ cam: ICCameraDevice, props: [SonyProperty]) async {
        // Only touch live view image quality: it is harmless and changes immediately.
        guard let target = props.first(where: { $0.code == 0xD26A && $0.writable && $0.currentValue.intValue != nil }) else {
            check("Data-out test", nil, "LiveViewImageQuality not writable, skipped")
            return
        }
        let original = target.currentValue.intValue!
        log("  data-out test property: 0x\(hex16(target.code)) \(target.name) current=\(target.currentValue)")

        // Step 1: no-op write (same value) with each outData layout; container first because a
        // malformed raw packet is the variant most likely to wedge the camera.
        var working: DataOutVariant?
        for variant in DataOutVariant.allCases {
            do {
                let r = try await setProperty(cam, target, value: original, variant: variant)
                log("  no-op write via \(variant.rawValue): \(RC.name(r.code))")
                if r.code == RC.ok { working = variant; break }
            } catch {
                log("  no-op write via \(variant.rawValue): \(error)")
                // A timed-out data-out may leave the PTP pipe busy; check the camera still answers.
                if (try? await send(cam, OC.getDeviceInfo, quiet: true)) == nil {
                    check("Camera still responsive after failed data-out", false, "reconnect the cable before retrying")
                    return
                }
            }
        }
        guard let variant = working else {
            check("Data-out (set property) works", false, "neither outData layout accepted")
            return
        }
        check("Data-out accepted", true, variant.rawValue)

        // Step 2: real change + read-back + restore, to prove the payload is interpreted correctly.
        let options = (target.enumValues2 ?? target.enumValues).compactMap { $0.intValue }
        var newValue: Int64?
        if let i = options.firstIndex(of: original) {
            newValue = i + 1 < options.count ? options[i + 1] : (i > 0 ? options[i - 1] : nil)
        } else if let rg = target.range, let step = rg.step.intValue, let hi = rg.max.intValue {
            newValue = original + step <= hi ? original + step : original - step
        }
        guard let newValue else {
            check("Setting change verified", nil, "no alternative value to try")
            return
        }
        do {
            let r = try await setProperty(cam, target, value: newValue, variant: variant)
            log("  set \(target.name) -> \(newValue): \(RC.name(r.code))")
            var seen: Int64?
            for _ in 0..<10 {
                try await Task.sleep(nanoseconds: 150_000_000)
                seen = try await readProperties(cam).first { $0.code == target.code }?.currentValue.intValue
                if seen == newValue { break }
            }
            check("Setting change verified by read-back", seen == newValue,
                  "\(target.name): \(original) -> wanted \(newValue), camera reports \(seen.map(String.init) ?? "?")")
            let back = try await setProperty(cam, target, value: original, variant: variant)
            try await Task.sleep(nanoseconds: 300_000_000)
            let restored = try await readProperties(cam).first { $0.code == target.code }?.currentValue.intValue
            log("  restored \(target.name) -> \(original): \(RC.name(back.code)), camera reports \(restored.map(String.init) ?? "?")")
        } catch {
            check("Setting change verified by read-back", false, "\(error)")
        }
    }

    func payload(for value: Int64, type: UInt16, variant: DataOutVariant,
                 opcode: UInt16 = OC.sonySetExtDevicePropValue) -> Data {
        let body = encodeValue(value, type: type)
        switch variant {
        case .raw: return body
        case .container: return makeDataContainer(opcode, payload: body, transactionID: lastTransactionID &+ 1)
        }
    }

    func setProperty(_ cam: ICCameraDevice, _ prop: SonyProperty, value: Int64, variant: DataOutVariant) async throws -> PTPResponse {
        let out = payload(for: value, type: prop.dataType, variant: variant)
        return try await send(cam, OC.sonySetExtDevicePropValue, [UInt32(prop.code)], outData: out, timeout: 6).response
    }

    func finish(code: Int32) {
        eventLock.lock()
        let events = eventCounts.sorted { $0.key < $1.key }.map { "0x\(hex16($0.key))×\($0.value)" }
        eventLock.unlock()
        log("PTP events received: \(events.isEmpty ? "none" : events.joined(separator: ", "))")
        log("==== SUMMARY ====")
        for (title, ok, detail) in checklist {
            let mark = ok == nil ? "[ ? ]" : (ok! ? "[ OK ]" : "[FAIL]")
            log("\(mark) \(title)\(detail.isEmpty ? "" : " — " + detail)")
        }
        log("Artifacts in \(outDir.path)")
        guard let cam = camera, cam.hasOpenSession else { exit(code) }
        cam.requestCloseSession(options: [:]) { _ in exit(code) }
        DispatchQueue.global().asyncAfter(deadline: .now() + 3) { exit(code) }
    }
}

func extractJPEG(_ frame: Data) -> (data: Data, offset: Int)? {
    let b = [UInt8](frame)
    guard b.count > 8 else { return nil }
    let off = Int(UInt32(b[0]) | UInt32(b[1]) << 8 | UInt32(b[2]) << 16 | UInt32(b[3]) << 24)
    if off + 1 < b.count, b[off] == 0xFF, b[off + 1] == 0xD8 {
        return (Data(b[off...]), off)
    }
    for i in 0..<(b.count - 2) where b[i] == 0xFF && b[i + 1] == 0xD8 && b[i + 2] == 0xFF {
        return (Data(b[i...]), i)
    }
    return nil
}

func jpegSize(_ jpeg: Data) -> (Int, Int)? {
    guard let src = CGImageSourceCreateWithData(jpeg as CFData, nil),
          let p = CGImageSourceCopyPropertiesAtIndex(src, 0, nil) as? [CFString: Any],
          let w = p[kCGImagePropertyPixelWidth] as? Int,
          let h = p[kCGImagePropertyPixelHeight] as? Int else { return nil }
    return (w, h)
}

enum Mode {
    case probe
    case set(code: UInt16, value: Int64, variant: DataOutVariant, type: UInt16)
    case walk(code: UInt16, target: Int64)
    case dump
}

let cliArgs = Array(CommandLine.arguments.dropFirst())
let mode: Mode = {
    if cliArgs.first == "dump" { return .dump }
    if cliArgs.first == "walk", cliArgs.count >= 3,
       let code = UInt16(cliArgs[1].replacingOccurrences(of: "0x", with: ""), radix: 16),
       let target = Int64(cliArgs[2].replacingOccurrences(of: "0x", with: ""), radix: cliArgs[2].hasPrefix("0x") ? 16 : 10) {
        return .walk(code: code, target: target)
    }
    // ptp-probe set <prop hex> <value> [raw|container] [i8|u8|i16|u16|i32|u32]
    if cliArgs.first.map({ Double($0) == nil }) ?? false, cliArgs.first != "set" {
        print("usage: ptp-probe [wait-minutes] | set <prop> <value> [raw|container] [type] | walk <prop> <target>")
        exit(64)
    }
    guard cliArgs.first == "set" else { return .probe }
    guard cliArgs.count >= 3,
          let code = UInt16(cliArgs[1].replacingOccurrences(of: "0x", with: ""), radix: 16),
          let value = Int64(cliArgs[2]) else {
        print("usage: ptp-probe set <prop hex> <value> [raw|container] [i8|u8|i16|u16|i32|u32]")
        exit(64)
    }
    let variant: DataOutVariant = cliArgs.count > 3 && cliArgs[3] == "container" ? .container : .raw
    let types: [String: UInt16] = ["i8": 1, "u8": 2, "i16": 3, "u16": 4, "i32": 5, "u32": 6]
    let type = cliArgs.count > 4 ? types[cliArgs[4]] ?? 4 : 4
    return .set(code: code, value: value, variant: variant, type: type)
}()
let waitMinutes: Double = {
    if case .probe = mode, let m = cliArgs.first.flatMap(Double.init) { return m }
    return 20
}()
let probe = Probe()
probe.start()
DispatchQueue.main.asyncAfter(deadline: .now() + waitMinutes * 60) {
    if probe.camera == nil {
        log("No Sony camera appeared within \(Int(waitMinutes)) min, giving up.")
        exit(3)
    }
}
RunLoop.main.run()
