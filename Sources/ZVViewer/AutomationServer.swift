import AppKit
import Foundation
import Network
import ZVCore

/// Локальный API для автоматических тестов: состояние, команды, скриншот окна, кадр с камеры.
/// Включается только флагом запуска `--automation` (или ZV_AUTOMATION=1), слушает 127.0.0.1, требует токен.
@MainActor
final class AutomationServer {
    private let controller: CameraController
    private let screen: ScreenState
    private let token = (0..<4).map { _ in String(format: "%08x", UInt32.random(in: .min ... .max)) }.joined()
    private var listener: NWListener?
    /// Пока тесты гоняют программу, macOS не должна её усыплять (App Nap), когда окно закрыто другими:
    /// иначе таймеры растягиваются и live view падает до 6–12 к/с.
    private var activity: NSObjectProtocol?

    static var isRequested: Bool {
        CommandLine.arguments.contains("--automation") || ProcessInfo.processInfo.environment["ZV_AUTOMATION"] == "1"
    }

    init(controller: CameraController, screen: ScreenState) {
        self.controller = controller
        self.screen = screen
    }

    func start() throws {
        let port = UInt16(ProcessInfo.processInfo.environment["ZV_AUTOMATION_PORT"] ?? "") ?? 47811
        let parameters = NWParameters.tcp
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!)
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters)
        listener.newConnectionHandler = { [weak self] connection in
            Task { @MainActor in self?.serve(connection) }
        }
        listener.start(queue: .main)
        self.listener = listener
        activity = ProcessInfo.processInfo.beginActivity(options: .userInitiatedAllowingIdleSystemSleep,
                                                         reason: "API автоматизации")
        try writeInfo(port: port)
    }

    /// Порт и токен — в файл, доступный только текущему пользователю.
    private func writeInfo(port: UInt16) throws {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sony Cam Viewer", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("automation.json")
        let info = try JSONSerialization.data(withJSONObject: ["port": Int(port), "token": token])
        FileManager.default.createFile(atPath: file.path, contents: info, attributes: [.posixPermissions: 0o600])
    }

    // MARK: - Соединения

    private func serve(_ connection: NWConnection) {
        connection.start(queue: .main)
        receive(connection, buffer: Data())
    }

    private func receive(_ connection: NWConnection, buffer: Data) {
        connection.receive(minimumIncompleteLength: 1, maximumLength: 1 << 20) { [weak self] data, _, isComplete, error in
            Task { @MainActor in
                guard let self else { return }
                var buffer = buffer
                if let data { buffer.append(data) }
                switch HTTPRequest.parse(buffer) {
                case let .complete(request, _):
                    self.reply(connection, await self.route(request))
                case .incomplete where !isComplete && error == nil:
                    self.receive(connection, buffer: buffer)
                default:
                    self.reply(connection, Self.json(400, ["error": "bad request"]))
                }
            }
        }
    }

    private func reply(_ connection: NWConnection, _ response: Data) {
        connection.send(content: response, completion: .contentProcessed { _ in connection.cancel() })
    }

    // MARK: - Маршруты

    private func route(_ request: HTTPRequest) async -> Data {
        guard request.headers["x-zv-token"] == token || request.query["token"] == token else {
            return Self.json(401, ["error": "token required"])
        }
        switch (request.method, request.path) {
        case ("GET", "/state"):
            return Self.json(200, state())
        case ("POST", "/action"):
            do {
                let action = try JSONDecoder().decode(AutomationAction.self, from: request.body)
                if let problem = perform(action) { return Self.json(409, ["error": problem]) }
                return Self.json(200, ["ok": true])
            } catch {
                return Self.json(400, ["error": "\(error)"])
            }
        case ("GET", "/screenshot"):
            // ?window=presets — окно «Пресеты» вместо главного
            let title = request.query["window"] == "presets" ? "Пресеты" : LiveScreen.windowTitle
            guard let png = screenshot(title: title) else { return Self.json(409, ["error": "no window"]) }
            return HTTPResponse.encode(status: 200, contentType: "image/png", body: png)
        case ("GET", "/frame"):
            guard let jpeg = controller.lastJPEG else { return Self.json(409, ["error": "no frame"]) }
            return HTTPResponse.encode(status: 200, contentType: "image/jpeg", body: jpeg)
        default:
            return Self.json(404, ["error": "not found"])
        }
    }

    /// Выполняет команду так же, как это сделали бы кнопки. Возвращает текст проблемы или nil.
    private func perform(_ action: AutomationAction) -> String? {
        switch action {
        case let .key(key):
            switch key.lowercased() {
            case "f": screen.toggleFn()
            case "escape": screen.closeOverlays()
            case "m": screen.toggleAssistMenu()
            case "z": screen.toggleAssist(.zebra)
            case "p": screen.toggleAssist(.peaking)
            case "c": screen.toggleAssist(.falseColor)
            case "left", "right":
                let offset = key.lowercased() == "left" ? -1 : 1
                if screen.assistOpen {
                    screen.stepAssist(offset)
                    return nil
                }
                guard screen.fnOpen else { return "Fn menu is closed" }
                controller.requestStep(screen.selected, offset: offset)
            default:
                guard let digit = Int(key), (1...9).contains(digit) else { return "unknown key \(key)" }
                guard controller.presets.indices.contains(digit - 1) else { return "no preset \(digit)" }
                controller.applyPreset(digit - 1)
            }
        case let .openFn(id): screen.openFn(id)
        case .closeOverlays: screen.closeOverlays()
        case let .select(id):
            guard controller.isWritable(id) else { return "\(id.rawValue) is not writable now" }
            screen.selected = id
        case let .step(offset):
            controller.requestStep(screen.selected, offset: offset)
        case let .request(.colorTemperature, value):
            // как ползунок: вне режима «Цвет. темп.» камера сначала переключается в него
            guard controller.isWritable(.colorTemperature) || controller.isWritable(.whiteBalance) else {
                return "colorTemperature is not writable now"
            }
            guard controller.setColorTemperature(value) else { return "colorTemperature \(value) is out of range" }
        case let .request(id, value):
            guard controller.isWritable(id) else { return "\(id.rawValue) is not writable now" }
            controller.choose(id, value)
        case let .openAllSettings(group): screen.openAllSettings(group)
        case let .editRow(id): screen.editing = id
        case .toggleHistogram: screen.toggleHistogram()
        case let .savePreset(name):
            guard controller.phase == .ready else { return "camera not ready" }
            controller.savePreset(name: name)
        case let .applyPreset(index):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            controller.applyPreset(index)
        case let .renamePreset(index, name): controller.renamePreset(index, to: name)
        case let .deletePreset(index):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            controller.deletePreset(index)
            screen.followPresetDelete(index, remaining: controller.presets.count)
        case let .toggleRecording(confirm):
            guard confirm else { return "recording writes a clip to the card: pass \"confirm\": true" }
            controller.toggleRecording()
        case let .switchMode(mode):
            guard controller.isWritable(.exposureProgram) else { return "mode switch not available now" }
            controller.switchMode(mode)
        case let .capture(confirm):
            guard confirm else { return "a shot writes files (card and/or Mac): pass \"confirm\": true" }
            guard controller.mode == .photo else { return "camera is not in photo mode" }
            guard !controller.isCapturing else { return "already capturing" }
            controller.capturePhoto()
        case .grabFrame:
            guard controller.lastJPEG != nil else { return "no frame" }
            controller.grabFrame()
        case .toggleControls:
            screen.toggleControls()
        case .dismissReview:
            controller.dismissReview()
        case let .setPreference(key, value):
            if let problem = setPreference(key, value) { return problem }
        case .pairWiFi:
            // как «Сопрячь новую камеру…»: при камере на связи сеанс с ней закрывается
            guard controller.canSwitchCamera else { return "recording" }
            controller.startPairing()
        case .cancelPairing:
            controller.cancelPairing()
        case .forgetWiFi:
            controller.forgetWiFiCamera()
        case let .showDebug(name):
            if let name {
                guard let tab = ScreenState.DebugTab(rawValue: name) else { return "tab: link | preview | camera | properties | log" }
                screen.openDebug(tab)
            } else {
                screen.openDebug(nil)
            }
        case .hideDebug:
            screen.debugOpen = false
        case let .showInfo(name):
            guard let name else {
                screen.pillInfo = nil
                return nil
            }
            guard let info = ScreenState.PillInfo(rawValue: name) else { return "unknown info \(name)" }
            guard controller.phase == .ready || controller.phase == .settling else { return "camera is not connected" }
            // подсказка о S&Q есть только в S&Q — иначе флаг висел бы без подсказки на экране
            guard info != .slowMotion || controller.slowMotion != nil else { return "camera is not in S&Q" }
            screen.closeOverlays()
            screen.pillInfo = info
        case let .openShot(index), let .openGallery(index):
            guard controller.shots.indices.contains(index) else { return "no shot \(index)" }
            screen.openGallery(controller.shots[index].id)
        case let .galleryStep(offset):
            guard let id = screen.galleryShot, let i = controller.shots.firstIndex(where: { $0.id == id }) else {
                return "gallery is closed"
            }
            screen.galleryShot = controller.shots[min(max(i + offset, 0), controller.shots.count - 1)].id
        case .closeGallery:
            screen.galleryShot = nil
        case let .moveHistogram(x, y):
            screen.placeHistogram(FloatingPlacement(x: min(max(x, 0), 1), y: min(max(y, 0), 1)))
        case let .setZoom(factor, x, y):
            guard controller.frame != nil else { return "no frame" }
            let center = CGPoint(x: x ?? screen.zoom.center.x, y: y ?? screen.zoom.center.y)
            screen.zoom = factor < ViewZoom.snapBelow ? .identity : ViewZoom(factor: min(factor, ViewZoom.maxFactor), center: center)
        case .resetZoom:
            screen.zoom = .identity
        case .toggleStatusPin:
            screen.toggleStatusPin()
        case .openPresetMenu:
            screen.closeOverlays()
            // кнопка пресетов — среди элементов: если они спрятаны, сначала показываем их, а список открываем,
            // когда кнопка уже на экране, — иначе списку не к чему привязаться
            let hidden = !screen.controlsShown
            if screen.manualHidden { screen.toggleControls() } else { screen.noteActivity() }
            if hidden {
                Task { @MainActor [screen] in
                    try? await Task.sleep(for: .milliseconds(400))
                    screen.presetMenuOpen = true
                }
            } else {
                screen.presetMenuOpen = true
            }
        case .closePresetMenu:
            screen.presetMenuOpen = false
        case let .openPresets(index):
            if let index, !controller.presets.indices.contains(index) { return "no preset \(index)" }
            screen.openPresetsWindow(selecting: index)
        case .closePresets:
            screen.presetsWindowCloseRequest += 1
        case let .selectPreset(index):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            screen.presetsSelected = index
        case let .setPresetValue(index, id, value):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            guard PresetCatalog.order.contains(id) else { return "\(id.rawValue) is not in presets" }
            controller.setPresetValue(index, id, value)
        case let .setPresetIncluded(index, id, included):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            guard PresetCatalog.order.contains(id) else { return "\(id.rawValue) is not in presets" }
            guard !included || controller.canIncludeInPreset(index, id) else { return "no value for \(id.rawValue)" }
            controller.setPresetIncluded(index, id, included)
        case let .movePreset(from, to):
            guard controller.presets.indices.contains(from), controller.presets.indices.contains(to) else { return "no preset" }
            controller.movePreset(from: from, to: to)
            screen.followPresetMove(from: from, to: to)
        case let .duplicatePreset(index):
            guard let copy = controller.duplicatePreset(index) else { return "no preset \(index)" }
            screen.presetsSelected = copy
        case let .recapturePreset(index):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            guard controller.phase == .ready else { return "not ready" }
            controller.recapturePreset(index)
        case let .beginPresetPreview(index):
            guard controller.presets.indices.contains(index) else { return "no preset \(index)" }
            guard controller.phase == .ready, !controller.isApplyingPreset else { return "busy" }
            screen.presetPeeking = true
            controller.beginPresetPreview(index)
        case .endPresetPreview:
            screen.presetPeeking = false
            controller.endPresetPreview()
        case .savePresetChanges:
            guard controller.phase == .ready else { return "camera not ready" }
            guard !controller.currentPresetChanges.isEmpty else { return "current preset is not modified" }
            controller.savePresetChanges()
        case .openSavePreset:
            guard controller.phase == .ready else { return "camera not ready" }
            // как нажать «☆+»: кнопка — среди элементов, они должны быть на экране
            if screen.manualHidden { screen.toggleControls() } else { screen.noteActivity() }
            screen.pressSavePreset(currentModified: !controller.currentPresetChanges.isEmpty)
        case .chooseNewPreset:
            guard screen.presetSaving else { return "save dialog is closed" }
            screen.chooseNewPreset()
        case .closeSavePreset:
            screen.presetSaving = false
            screen.presetNaming = false
        case let .selectCamera(id):
            guard let entry = controller.cameras.first(where: { $0.id == id }) else { return "no camera \(id)" }
            guard entry.state != .current else { return "camera \(id) is current" }
            guard controller.canSwitchCamera else { return "recording" }
            controller.selectCamera(id)
        case let .renameCamera(id, name):
            guard controller.roster.camera(id) != nil else { return "no known camera \(id)" }
            controller.renameCamera(id, to: name)
        case let .forgetCamera(id):
            guard controller.roster.camera(id) != nil else { return "no known camera \(id)" }
            guard id != controller.currentCameraID else { return "camera \(id) is current" }
            controller.forgetCamera(id)
        case let .pairCamera(id):
            guard controller.cameras.first(where: { $0.id == id })?.endpoint != nil else { return "no Wi-Fi camera \(id)" }
            controller.pairCamera(id)
        case .searchCameras:
            Task { await controller.searchCameras() }
        case let .addToStrip(id):
            guard let camera = controller.roster.camera(id), camera.paired, camera.endpoint != nil else {
                return "no paired Wi-Fi camera \(id)"
            }
            controller.addToStrip(id)
        case let .removeFromStrip(id):
            guard controller.roster.camera(id) != nil else { return "no known camera \(id)" }
            controller.removeFromStrip(id)
        case let .linkCamera(id, linked):
            guard controller.roster.camera(id) != nil else { return "no known camera \(id)" }
            controller.linkCamera(id, linked)
        case let .moveInStrip(id, target):
            guard controller.stripTiles.contains(id), controller.stripTiles.contains(target) else {
                return "both cameras must be on the strip: \(id), \(target)"
            }
            controller.moveInStrip(id, onto: target)
        case let .openAssist(name):
            if let name {
                guard let tool = MonitorTool(rawValue: name) else { return Self.assistTools }
                screen.openAssist(tool)
            } else {
                screen.openAssist(nil)
            }
        case .closeAssist:
            if screen.assistOpen { screen.closeOverlays() }
        case let .setAssist(name, value):
            guard let tool = MonitorTool(rawValue: name) else { return Self.assistTools }
            guard screen.setAssist(tool, value) else {
                return "\(name): " + tool.options.map(\.value).joined(separator: " | ")
            }
        case let .toggleAssist(name):
            guard let tool = MonitorTool(rawValue: name) else { return Self.assistTools }
            screen.toggleAssist(tool)
        case .assistOff:
            screen.turnOffAssist()
        case .toggleWaveform:
            screen.toggleWaveform()
        case let .waveformMode(name):
            guard let mode = Waveform.Mode(rawValue: name) else { return "mode: luma | parade" }
            screen.setWaveformMode(mode)
        case let .moveWaveform(x, y):
            screen.placeWaveform(FloatingPlacement(x: min(max(x, 0), 1), y: min(max(y, 0), 1)))
        case .referenceFromFrame:
            guard controller.takeReferenceFromFrame() else { return "no frame" }
        case let .referenceFromShot(index):
            guard controller.shots.indices.contains(index) else { return "no shot \(index)" }
            controller.takeReference(from: controller.shots[index])
        case let .referenceFromFile(path):
            let url = URL(fileURLWithPath: (path as NSString).expandingTildeInPath)
            guard FileManager.default.fileExists(atPath: url.path) else { return "no file \(url.path)" }
            controller.takeReference(fromFile: url)
        case .toggleOverlay:
            screen.toggleOverlayVisible()
        case let .moveOverlayPanel(x, y):
            screen.placeOverlay(FloatingPlacement(x: min(max(x, 0), 1), y: min(max(y, 0), 1)))
        case .toggleTouchFocus:
            screen.toggleTouchFocus()
        case let .touchFocus(x, y):
            guard controller.touchFocusMethod != nil else {
                return "touch focus is not available now (focus mode MF, or the camera has no AF position / remote touch)"
            }
            controller.touchFocus(at: CGPoint(x: min(max(x, 0), 1), y: min(max(y, 0), 1)))
        case .cancelTouch:
            guard controller.touchCancelAvailable else { return "nothing to cancel" }
            controller.cancelTouch()
        case let .zoomStart(name):
            guard name == "tele" || name == "wide" else { return "name: tele | wide" }
            guard controller.zoomAvailable else { return "the camera does not offer zoom now" }
            controller.zoom(name == "tele" ? 1 : -1)
        case .zoomStop:
            controller.zoom(0)
        case let .focusStep(units):
            guard controller.focusDriveAvailable else { return "focus scale needs manual focus (Near/Far is closed)" }
            controller.focusStep(units)
        }
        return nil
    }

    private static let assistTools = "tool: " + MonitorTool.allCases.map(\.rawValue).joined(separator: " | ")

    /// Те же настройки, что в окне ⌘, — по строковому ключу.
    private func setPreference(_ key: String, _ value: String) -> String? {
        switch key {
        case "destination":
            guard let d = Prefs.Destination(rawValue: value) else { return "destination: macAndCard | macOnly | cardOnly" }
            Prefs.destination = d
            controller.applyDestinationPreference()
        case "folder":
            Prefs.folderURL = URL(fileURLWithPath: (value as NSString).expandingTildeInPath, isDirectory: true)
        case "reviewEnabled":
            guard let b = Bool(value) else { return "reviewEnabled: true | false" }
            Prefs.reviewEnabled = b
        case "reviewSeconds":
            guard let n = Int(value), (1...10).contains(n) else { return "reviewSeconds: 1…10" }
            Prefs.reviewSeconds = n
        case "reviewSource":
            guard let r = Prefs.ReviewSource(rawValue: value) else { return "reviewSource: downloaded | camera" }
            Prefs.reviewSource = r
        case "wifiEnabled":
            guard let b = Bool(value) else { return "wifiEnabled: true | false" }
            Prefs.wifiEnabled = b
        case "touchFocusHelpHidden":
            guard let b = Bool(value) else { return "touchFocusHelpHidden: true | false" }
            screen.touchFocusHelpHidden = b
        case "previewQuality":
            guard let mode = PreviewQualityMode(rawValue: value) else { return "previewQuality: auto | high | low" }
            controller.setPreviewQuality(mode)
        case "wifiAdaptiveQuality":
            // прежний переключатель: true — авто, false — всегда высокое
            guard let b = Bool(value) else { return "wifiAdaptiveQuality: true | false" }
            controller.setPreviewQuality(b ? .auto : .high)
        case "autoHide":
            guard let b = Bool(value) else { return "autoHide: true | false" }
            Prefs.autoHide = b
        case "tallyEnabled":
            guard let b = Bool(value) else { return "tallyEnabled: true | false" }
            Prefs.tallyEnabled = b
        case "tallyWidth":
            guard let n = Double(value), (1...12).contains(n) else { return "tallyWidth: 1…12" }
            Prefs.tallyWidth = n
        case "tallyOpacity":
            guard let n = Double(value), (0.3...1).contains(n) else { return "tallyOpacity: 0.3…1" }
            Prefs.tallyOpacity = n
        case "tallyBlink":
            guard let b = Bool(value) else { return "tallyBlink: true | false" }
            Prefs.tallyBlink = b
        case "autoHideSeconds":
            guard let n = Int(value), (1...30).contains(n) else { return "autoHideSeconds: 1…30" }
            Prefs.autoHideSeconds = n
        default:
            return "unknown preference \(key)"
        }
        return nil
    }

    /// Как идёт связь: состояние для экрана, качество превью, восстановления сеанса, поток и отклик камеры.
    private func linkState() -> [String: Any] {
        let state: String
        var since: Date?
        switch controller.linkIndicator {
        case .none: state = "ok"
        case .weak: state = "weak"
        case let .interrupted(at):
            state = "interrupted"
            since = at
        case let .reconnecting(at):
            state = "reconnecting"
            since = at
        }
        return [
            "state": state,
            "seconds": since.map { (Date().timeIntervalSince($0) * 10).rounded() / 10 } ?? NSNull(),
            "quality": controller.previewQuality.map { $0 == .high ? "high" : "low" } ?? NSNull(),
            "reconnects": controller.reconnects,
            "lastRecoverySeconds": controller.lastRecoverySeconds.map { ($0 * 10).rounded() / 10 } ?? NSNull(),
            "throughputKBs": controller.throughput.map { Int($0 / 1024) } ?? NSNull(),
            "rttMs": controller.linkRTTMs ?? NSNull(),
        ]
    }

    private func state() -> [String: Any] {
        let settings: [[String: Any]] = SettingID.allCases.map { id in
            let property = controller.property(id)
            var item: [String: Any] = [
                "id": id.rawValue,
                "code": String(format: "0x%04X", id.code),
                "title": id.title,
                "label": controller.label(id),
                "writable": controller.isWritable(id),
                "pending": controller.pending.contains(id),
            ]
            if let value = property?.current.intValue { item["value"] = value }
            if property != nil {
                // у параметров записи — режимы обоих форматов, value — код режима (формат × 256 + параметры)
                item["options"] = controller.stripOptions(id).map { ["value": $0.value, "label": $0.label] }
            }
            if id == .movieRecordingSetting, let mode = controller.stripCurrent(id) { item["mode"] = mode }
            return item
        }
        var ui: [String: Any] = [
            "fnOpen": screen.fnOpen,
            "allSettingsOpen": screen.allSettingsOpen,
            "selected": screen.selected.rawValue,
            "group": screen.group.rawValue,
            "histogram": screen.histogramOn,
        ]
        if let editing = screen.editing { ui["editing"] = editing.rawValue }
        if let info = screen.pillInfo { ui["info"] = info.rawValue }
        ui["controlsHidden"] = screen.controlsHidden
        ui["debug"] = screen.debugOpen
        ui["debugTab"] = screen.debugTab.rawValue
        ui["manualHidden"] = screen.manualHidden
        ui["histogramPosition"] = screen.histogramPlacement.map { ["x": $0.x, "y": $0.y] } ?? NSNull()
        if let id = screen.galleryShot, let index = controller.shots.firstIndex(where: { $0.id == id }) {
            ui["gallery"] = ["index": index, "title": controller.shots[index].title] as [String: Any]
        } else {
            ui["gallery"] = NSNull()
        }
        ui["zoom"] = ["factor": screen.zoom.factor, "centerX": screen.zoom.center.x, "centerY": screen.zoom.center.y]
        ui["statusBar"] = ["pinned": screen.statusBarPinned, "visible": screen.controlsShown || screen.statusBarPinned]
        ui["presetMenu"] = screen.presetMenuOpen
        ui["presetNaming"] = screen.presetNaming
        ui["presetSaving"] = screen.presetSaving
        ui["presetPeeking"] = screen.presetPeeking
        ui["assist"] = [
            "open": screen.assistOpen,
            "selected": screen.assistSelected.rawValue,
            "values": Dictionary(uniqueKeysWithValues: MonitorTool.allCases.map { ($0.rawValue, screen.assist.value($0)) }),
        ] as [String: Any]
        ui["touchFocus"] = screen.touchFocusOn
        ui["touchFocusHelp"] = screen.touchFocusHelpOpen
        ui["waveform"] = [
            "on": screen.waveformOn,
            "mode": screen.waveformMode.rawValue,
            "position": screen.waveformPlacement.map { ["x": $0.x, "y": $0.y] } ?? NSNull(),
        ] as [String: Any]
        ui["overlay"] = [
            "hidden": screen.overlayHidden,
            "position": screen.overlayPlacement.map { ["x": $0.x, "y": $0.y] } ?? NSNull(),
        ] as [String: Any]
        ui["presetsWindow"] = screen.presetsWindowOpen ? ["selected": screen.presetsSelected ?? NSNull()] as [String: Any] : NSNull()
        var result: [String: Any] = [
            "phase": controller.phase.rawValue,
            "model": controller.deviceInfo?.model ?? NSNull(),
            "firmware": controller.deviceInfo?.firmware ?? NSNull(),
            "recording": controller.isRecording,
            "fps": (controller.fps * 10).rounded() / 10,
            "frameGapMs": controller.frameGapMs,
            "shootingMode": controller.shootingMode.rawValue,
            "slowMotion": controller.slowMotion.map { ["captureFps": $0.capture ?? NSNull(), "recordFps": $0.record ?? NSNull()] as [String: Any] } ?? NSNull(),
            "histogramBins": controller.histogram.count,
            "waveform": controller.waveform.map { trace -> [String: Any] in
                let range = trace.counts.levelRange
                return ["mode": trace.counts.mode.rawValue, "columns": trace.counts.columns, "white": trace.white,
                        "low": range?.low ?? NSNull(), "high": range?.high ?? NSNull()]
            } ?? NSNull(),
            "lens": [
                "controls": controller.controlCodes.sorted().map { String(format: "0x%04X", $0) },
                "touchFocus": controller.touchFocusMethod?.rawValue ?? NSNull(),
                "touchCancel": controller.touchCancelAvailable,
                "zoom": controller.zoomAvailable,
                "zoomDirection": controller.zoomDirection,
                "zoomBar": controller.zoomBar.map { ["boxes": $0.boxes, "box": $0.box, "position": $0.position,
                                                    "fraction": $0.fraction ?? NSNull()] as [String: Any] } ?? NSNull(),
                "zoomScale": controller.zoomScaleLabel ?? NSNull(),
                "focusScale": controller.focusDriveAvailable,
                "focalPosition": controller.focalPosition ?? NSNull(),
                "focusFrames": controller.focusFrames.map { frame -> [String: Any] in
                    ["kind": frame.kind.rawValue, "x": frame.rect.midX, "y": frame.rect.midY, "width": frame.rect.width,
                     "height": frame.rect.height, "focused": frame.focused]
                },
            ] as [String: Any],
            "reference": controller.reference.map { ["source": $0.source, "width": $0.image.width, "height": $0.image.height] as [String: Any] }
                ?? NSNull(),
            "assistOverlay": controller.assistOverlay.map { overlay -> [String: Any] in
                ["width": overlay.image.width, "height": overlay.image.height,
                 "region": ["x": overlay.region.minX, "y": overlay.region.minY, "width": overlay.region.width,
                            "height": overlay.region.height],
                 "zebra": overlay.zebraShare, "peaking": overlay.peakingShare]
            } ?? NSNull(),
            "cableAccessHint": controller.cableAccessHint,
            "histogramStats": controller.histogramStats.map { stats -> [String: Any] in
                ["shadows": stats.shadows, "midtones": stats.midtones, "highlights": stats.highlights,
                 "clippedShadows": stats.clippedShadows, "clippedHighlights": stats.clippedHighlights, "mean": stats.mean]
            } ?? NSNull(),
            "applyingPreset": controller.isApplyingPreset,
            "status": controller.statusMessage ?? NSNull(),
            "mode": controller.mode.rawValue,
            "capturing": controller.isCapturing,
            "capture": controller.capturePhase.map { phase -> [String: Any] in
                switch phase {
                case .focusing: return ["phase": "focusing"]
                case .exposing: return ["phase": "exposing"]
                case let .downloading(file, files, received, expected):
                    return ["phase": "downloading", "file": file, "files": files, "received": received,
                            "expected": expected ?? NSNull(), "caption": CaptureFeedback.caption(phase)]
                }
            } ?? NSNull(),
            "recordingRequest": controller.recordingToggle.request?.rawValue ?? NSNull(),
            "recordingNotice": controller.recordingNotice ?? NSNull(),
            // журнал панели отладки, последние события
            "journal": controller.debugEvents.suffix(30).map(\.text),
            "feedback": controller.feedback.map { "\($0.kind)" },
            "transport": controller.transportKind?.rawValue ?? NSNull(),
            "wifi": [
                "enabled": Prefs.wifiEnabled, "pairing": controller.isPairing,
                "host": Prefs.wifiHost ?? NSNull(), "cameraName": Prefs.wifiCameraName ?? NSNull(),
                "status": controller.wifiStatus ?? NSNull(),
            ] as [String: Any],
            "recordingSeconds": controller.recordingStartedAt.map { Int(Date().timeIntervalSince($0)) } ?? NSNull(),
            "review": controller.review.map { review -> [String: Any] in
                ["title": review.title, "source": review.source, "timed": review.endsAt != nil,
                 "hasImage": review.image != nil, "files": review.urls.map(\.path)]
            } ?? NSNull(),
            "shots": controller.shots.map { ["title": $0.title, "files": $0.urls.map(\.path)] as [String: Any] },
            "prefs": [
                "destination": Prefs.destination.rawValue, "folder": Prefs.folderURL.path,
                "reviewEnabled": Prefs.reviewEnabled, "reviewSeconds": Prefs.reviewSeconds,
                "reviewSource": Prefs.reviewSource.rawValue, "autoHide": Prefs.autoHide, "autoHideSeconds": Prefs.autoHideSeconds,
                "previewQuality": controller.previewQualityMode.rawValue,
                "touchFocusHelpHidden": screen.touchFocusHelpHidden,
                "wifiAdaptiveQuality": controller.previewQualityMode == .auto,
                "tallyEnabled": Prefs.tallyEnabled, "tallyWidth": Prefs.tallyWidth, "tallyOpacity": Prefs.tallyOpacity,
                "tallyBlink": Prefs.tallyBlink,
            ] as [String: Any],
            "link": linkState(),
            "cameraDestination": controller.properties[SonyProp.stillImageStoreDestination]?.current.intValue ?? NSNull(),
            "cameras": controller.cameras.map { entry in
                var item: [String: Any] = ["id": entry.id, "name": entry.name, "model": entry.model, "state": entry.state.rawValue,
                                           "stateLabel": entry.stateLabel, "detail": entry.detail, "paired": entry.paired,
                                           "known": entry.knownID != nil]
                switch entry.link {
                case .usb?: item["link"] = "usb"
                case .wifi?: item["link"] = "wifi"
                case nil: item["link"] = NSNull()
                }
                if let endpoint = entry.endpoint {
                    item["host"] = endpoint.host
                    item["port"] = Int(endpoint.port)
                }
                return item
            },
            "currentCamera": controller.currentCameraID ?? NSNull(),
            "strip": stripState(),
            "cameraName": controller.currentCameraName,
            "pairing": controller.isPairing,
            "presetMode": controller.presetMode.rawValue,
            "presets": controller.presets.map { preset in
                ["name": preset.name,
                 "mode": preset.mode.rawValue,
                 "values": Dictionary(uniqueKeysWithValues: preset.values.map { ($0.key.rawValue, $0.value) }),
                 "skip": preset.skip.map(\.rawValue).sorted(),
                 "summary": PresetCatalog.summary(preset),
                 "active": PresetCatalog.matches(preset, controller.properties)] as [String: Any]
            },
            "presetCounts": Dictionary(uniqueKeysWithValues: [CaptureMode.photo, .video].map { mode in
                (mode.rawValue, controller.presetCount(mode))
            }),
            "activePreset": controller.activePresetIndex ?? NSNull(),
            "currentPreset": controller.currentPresetIndex ?? NSNull(),
            "presetModified": !controller.currentPresetChanges.isEmpty,
            "presetChanges": controller.currentPresetChanges.map(\.label),
            "previewingPreset": controller.previewingPreset ?? NSNull(),
            "previewReturning": controller.previewReturning,
            "ui": ui,
            "settings": settings,
        ]
        if let frame = controller.frame { result["frameSize"] = [frame.width, frame.height] }
        return result
    }

    /// Лента камер: главная и камеры в ленте по порядку; пусто — на связи одна камера.
    private func stripState() -> [[String: Any]] {
        guard !controller.sideCameras.isEmpty else { return [] }
        var main: [String: Any] = ["id": controller.currentCameraID ?? NSNull(), "name": controller.currentCameraName,
                                   "main": true, "connected": controller.phase == .ready, "recording": controller.isRecording,
                                   "linked": controller.roster.camera(controller.currentCameraID)?.linked ?? false,
                                   "battery": controller.property(.battery)?.current.intValue ?? NSNull(),
                                   "fps": controller.shownFPS]
        if case let .wifi(endpoint)? = controller.currentLink { main["port"] = Int(endpoint.port) }
        let sides = Dictionary(uniqueKeysWithValues: controller.sideCameras.map { side in (side.id, sideState(side)) })
        // в порядке плиток; главная без id (так не бывает, но) — первой
        return (controller.currentCameraID == nil ? [main] : [])
            + controller.stripTiles.compactMap { id in id == controller.currentCameraID ? main : sides[id] }
    }

    private func sideState(_ side: SideCamera) -> [String: Any] {
        ["id": side.id, "name": controller.roster.camera(side.id)?.name ?? "Камера", "main": false,
         "connected": side.connected, "recording": side.recording, "battery": side.battery ?? NSNull(),
         "fps": side.fps, "port": Int(side.endpoint.port),
         "linked": controller.roster.camera(side.id)?.linked ?? false,
         // в группе, и главная тоже: повторяет за ней
         "following": controller.mainInGroup && controller.roster.camera(side.id)?.linked == true,
         // что дошло до камеры ленты — для тестов группы
         "values": Dictionary(uniqueKeysWithValues: [SettingID.iso, .shutter, .aperture, .whiteBalance, .colorTemperature, .exposureProgram]
            .compactMap { id in side.properties[id.code]?.current.intValue.map { (id.rawValue, $0) } })]
    }

    /// Снимок собственного окна (без разрешения на запись экрана) вместе с открытыми подсказками:
    /// popover — отдельное окно, его дорисовываем поверх на своё место.
    private func screenshot(title: String) -> Data? {
        let main = title == LiveScreen.windowTitle ? NSApp.mainWindow : nil
        guard let window = NSApp.windows.first(where: { $0.isVisible && $0.title == title }) ?? main,
              let view = window.contentView,
              let rep = view.bitmapImageRepForCachingDisplay(in: view.bounds) else { return nil }
        view.cacheDisplay(in: view.bounds, to: rep)
        // подсказки и список пресетов — у главного окна; на снимок окна пресетов их не кладём
        let popovers = main == nil ? [] : NSApp.windows.filter {
            $0 !== window && $0.isVisible && String(describing: type(of: $0)).contains("Popover")
        }
        if !popovers.isEmpty, let context = NSGraphicsContext(bitmapImageRep: rep) {
            NSGraphicsContext.saveGraphicsState()
            NSGraphicsContext.current = context
            for popover in popovers {
                guard let content = popover.contentView, let layer = content.layer else { continue }
                // в координатах окна, как и у снимка: начало внизу слева (вид SwiftUI перевёрнут — его координаты не годятся)
                let inWindow = window.convertPoint(fromScreen: popover.convertPoint(toScreen: content.frame.origin))
                let rect = NSRect(x: inWindow.x - view.frame.minX, y: inWindow.y - view.frame.minY,
                                  width: content.bounds.width, height: content.bounds.height)
                // фон popover — размытие окном системы, в снимок оно не попадает: подкладываем тёмную плашку
                NSColor(white: 0.16, alpha: 1).setFill()
                NSBezierPath(roundedRect: rect, xRadius: 10, yRadius: 10).fill()
                let cg = context.cgContext
                cg.saveGState()
                cg.translateBy(x: rect.minX, y: rect.maxY)
                cg.scaleBy(x: 1, y: -1)
                layer.render(in: cg)
                cg.restoreGState()
            }
            NSGraphicsContext.restoreGraphicsState()
        }
        return rep.representation(using: .png, properties: [:])
    }

    private static func json(_ status: Int, _ object: [String: Any]) -> Data {
        let body = (try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])) ?? Data("{}".utf8)
        return HTTPResponse.encode(status: status, contentType: "application/json; charset=utf-8", body: body)
    }
}
