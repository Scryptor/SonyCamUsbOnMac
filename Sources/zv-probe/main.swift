import Foundation
import ImageCaptureCore
import ImageIO
import ZVCamera
import ZVCore

// Утилита для проверок на живой камере: info | props | lv <кадров> <путь.jpg> | set <код hex> <значение> | rec

func fail(_ message: String) -> Int32 {
    FileHandle.standardError.write(Data((message + "\n").utf8))
    return 1
}

@MainActor
func waitForCamera(_ discovery: CameraDiscovery, seconds: Int) async -> ICCameraDevice? {
    let events = discovery.events
    return await withTaskGroup(of: ICCameraDevice?.self) { group in
        group.addTask {
            for await event in events {
                if case let .appeared(device) = event { return device }
            }
            return nil
        }
        group.addTask {
            try? await Task.sleep(for: .seconds(seconds))
            return nil
        }
        let first = await group.next() ?? nil
        group.cancelAll()
        return first
    }
}

@MainActor
func run() async -> Int32 {
    var args = Array(CommandLine.arguments.dropFirst())
    let usage = "usage: zv-probe [--wifi <адрес>] info | props | dump | watch <секунд> | lv <кадров> <путь.jpg> | set <код hex> <значение> | seq <шаги> | rec\n"
        + "       zv-probe wifi-find | wifi-pair <адрес>"
    // по Wi-Fi: поиск камеры и сопряжение (на камере в это время включено PC Remote Function → Pairing)
    if args.first == "wifi-find" {
        let cameras = await WiFiDiscovery.search()
        if cameras.isEmpty { return fail("камера в сети не найдена") }
        cameras.forEach { print($0.host) }
        return 0
    }
    if args.first == "wifi-pair", args.count >= 2 {
        let transport = PTPIPTransport(host: args[1], identity: PTPIPIdentity.load())
        do {
            print("камера: \(try await transport.connect(initTimeout: .seconds(90)))")
            await transport.close()
            return 0
        } catch {
            return fail("сопряжение не удалось: \(error)")
        }
    }
    var wifiHost: String?
    if args.first == "--wifi", args.count >= 2 {
        wifiHost = args[1]
        args.removeFirst(2)
    }
    guard let command = args.first, ["info", "props", "dump", "watch", "lv", "set", "seq", "rec"].contains(command) else {
        return fail(usage)
    }
    let transport: PTPTransport
    if let wifiHost {
        let wifi = PTPIPTransport(host: wifiHost, identity: PTPIPIdentity.load())
        do {
            try await wifi.connect()
        } catch {
            return fail("не удалось подключиться по Wi-Fi: \(error)")
        }
        transport = wifi
    } else {
        let discovery = CameraDiscovery()
        discovery.start()
        guard let device = await waitForCamera(discovery, seconds: 30) else { return fail("камера не найдена за 30 с") }
        do {
            try await device.openSession()
        } catch {
            return fail("не удалось открыть сессию: \(error)")
        }
        transport = ICCameraTransport(device: device)
    }
    let session = CameraSession(transport: transport)
    defer {
        Task {
            if let usb = transport as? ICCameraTransport { await usb.close() }
            if let wifi = transport as? PTPIPTransport { await wifi.close() }
        }
    }
    do {
        if command == "info" {
            do { try await session.start() } catch SessionError.notPCRemote {}
            guard let info = await session.deviceInfo else { return fail("нет DeviceInfo") }
            print("\(info.manufacturer) \(info.model) \(info.firmware)")
            print("PC Remote: \(info.isPCRemote ? "да" : "нет")")
            return 0
        }
        try await session.start()
        try await session.waitUntilReady()
        switch command {
        case "props":
            let props = await session.properties
            for id in SettingID.allCases {
                print(String(format: "0x%04X ", id.code) + "\(id.title): \(ValueFormatter.label(id, props[id.code]))")
            }
        case "dump":
            // все свойства из 0x9209 как есть: код, тип, «можно менять», значение, допустимые значения или диапазон
            let props = await session.properties
            for (code, p) in props.sorted(by: { $0.key < $1.key }) {
                let known = SettingID.allCases.first { $0.code == code }?.title ?? "—"
                var line = String(format: "0x%04X type=0x%04X getSet=%d enabled=%d ", code, p.dataType, p.getSet, p.enabled)
                line += "value=\(p.current.intValue.map(String.init) ?? "\(p.current)")"
                if let r = p.range { line += " range=\(r.min)…\(r.max)/\(r.step)" }
                if !p.settableValues.isEmpty { line += " settable=" + p.settableValues.map { String(format: "0x%X", $0) }.joined(separator: ",") }
                print(line + "  [\(known)]")
            }
        case "watch":
            // раз в секунду перечитывает все свойства и печатает, что изменилось, — для опытов «меняю на камере, смотрю код»
            let seconds = args.count >= 2 ? Int(args[1]) ?? 300 : 300
            func describe(_ p: SonyProperty) -> String {
                "value=\(p.current.intValue.map { String(format: "%lld (0x%llX)", $0, $0) } ?? "\(p.current)") enabled=\(p.enabled)"
                    + (p.settableValues.isEmpty ? "" : " settable=" + p.settableValues.map { String(format: "0x%X", $0) }.joined(separator: ","))
            }
            var last = await session.properties
            print("слежу \(seconds) с, свойств: \(last.count)")
            for _ in 0..<seconds {
                try await Task.sleep(for: .seconds(1))
                try? await session.refresh()
                let now = await session.properties
                for (code, p) in now.sorted(by: { $0.key < $1.key }) where last[code] != p {
                    let stamp = String(format: "%.0f", Date().timeIntervalSince1970.truncatingRemainder(dividingBy: 100000))
                    print(String(format: "[%@] 0x%04X: ", stamp, code) + (last[code].map(describe) ?? "—") + "  →  " + describe(p))
                }
                last = now
                fflush(stdout)
            }
        case "lv":
            guard args.count >= 3, let count = Int(args[1]), count > 0 else { return fail("usage: zv-probe lv <кадров> <путь.jpg>") }
            let start = Date()
            var last = Data()
            for _ in 0..<count { last = try await session.nextLiveViewFrame() }
            let fps = Double(count) / Date().timeIntervalSince(start)
            try last.write(to: URL(fileURLWithPath: args[2]))
            var size = "?"
            if let source = CGImageSourceCreateWithData(last as CFData, nil),
               let p = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
               let w = p[kCGImagePropertyPixelWidth] as? Int, let h = p[kCGImagePropertyPixelHeight] as? Int {
                size = "\(w)x\(h)"
            }
            print(String(format: "fps: %.1f", fps))
            print("size: \(size)")
        case "set":
            guard args.count >= 3, let code = UInt16(args[1].replacingOccurrences(of: "0x", with: ""), radix: 16),
                  let value = Int64(args[2]) else { return fail("usage: zv-probe set <код hex> <значение>") }
            switch try await session.set(code, to: value) {
            case .confirmed: print("confirmed")
            case let .notApplied(actual): print("notApplied(\(actual.map(String.init) ?? "nil"))")
            }
        case "seq":
            // опыты с порядком записей: «D241=8 +300 D242=0x24 ~5000 lv50» — запись без ожидания подтверждения,
            // пауза в мс, слежение в мс (печатает формат и параметры записи, когда они меняются), частота live view
            let start = ContinuousClock.now
            func stamp() -> String {
                let d = ContinuousClock.now - start
                return String(format: "%6.2f", Double(d.components.seconds) + Double(d.components.attoseconds) / 1e18)
            }
            var watched: [UInt16] = [SonyProp.movieFileFormat, SonyProp.movieRecordingSetting]
            func show() async -> String {
                let props = await session.properties
                return watched.map { code in
                    guard let p = props[code] else { return String(format: "%04X=—", code) }
                    return String(format: "%04X=0x%llX", code, p.current.intValue ?? -1) + (p.isWritable ? "" : "(ro)")
                        + "[" + p.settableValues.map { String(format: "%llX", $0) }.joined(separator: ",") + "]"
                }.joined(separator: " ")
            }
            print("\(stamp()) \(await show())")
            for step in args.dropFirst() {
                if step.hasPrefix("+") {
                    try await Task.sleep(for: .milliseconds(Int(step.dropFirst()) ?? 0))
                    continue
                }
                if step.hasPrefix("lv"), let count = Int(step.dropFirst(2)), count > 0 {
                    let started = ContinuousClock.now
                    for _ in 0..<count { _ = try await session.nextLiveViewFrame() }
                    let seconds = (ContinuousClock.now - started) / .seconds(1)
                    print("\(stamp()) live view: \(String(format: "%.1f", Double(count) / seconds)) к/с")
                    continue
                }
                if step.hasPrefix("~") {
                    let end = ContinuousClock.now + .milliseconds(Int(step.dropFirst()) ?? 0)
                    var last = ""
                    while ContinuousClock.now < end {
                        do { try await session.refresh() } catch { print("\(stamp()) refresh: \(error)") }
                        let line = await show()
                        if line != last { print("\(stamp()) \(line)"); last = line }
                        try await Task.sleep(for: .milliseconds(100))
                    }
                    continue
                }
                let parts = step.split(separator: "=").map(String.init)
                guard parts.count == 2, let code = UInt16(parts[0], radix: 16),
                      let value = parts[1].hasPrefix("0x") ? Int64(parts[1].dropFirst(2), radix: 16) : Int64(parts[1]),
                      let type = await session.properties[code]?.dataType else { return fail("непонятный шаг: \(step)") }
                if !watched.contains(code) { watched.append(code) }
                let sent = ContinuousClock.now
                let reply = try await transport.send(SonyOp.setExtPropValue, [UInt32(code)], outData: PTPValue.encode(value, type: type))
                print("\(stamp()) \(step) → ответ 0x\(String(reply.code, radix: 16, uppercase: true)) за \(ContinuousClock.now - sent)")
            }
            print("\(stamp()) \(await show())")
        case "rec":
            print("recording: \(try await session.toggleRecording())")
            try await Task.sleep(for: .seconds(2))
            print("recording: \(try await session.toggleRecording())")
        default:
            break
        }
        return 0
    } catch {
        return fail("ошибка: \(error)")
    }
}

Task { @MainActor in
    let code = await run()
    // даём сессии закрыться
    try? await Task.sleep(for: .milliseconds(300))
    exit(code)
}
RunLoop.main.run()
