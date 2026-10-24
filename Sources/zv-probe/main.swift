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
    let usage = "usage: zv-probe [--wifi <адрес>] info | props | lv <кадров> <путь.jpg> | set <код hex> <значение> | rec\n"
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
    guard let command = args.first, ["info", "props", "lv", "set", "rec"].contains(command) else {
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
