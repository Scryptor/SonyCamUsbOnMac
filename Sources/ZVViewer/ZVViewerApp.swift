import SwiftUI

@main
struct ZVViewerApp: App {
    @State private var controller = CameraController()
    @State private var screen = ScreenState()
    @State private var automation: AutomationServer?

    var body: some Scene {
        // по заголовку жесты увеличения и галереи узнают главное окно
        WindowGroup(LiveScreen.windowTitle) {
            RootView(controller: controller, screen: screen)
                .frame(minWidth: 800, minHeight: 450)
                .task {
                    guard automation == nil, AutomationServer.isRequested else { return }
                    let server = AutomationServer(controller: controller, screen: screen)
                    do {
                        try server.start()
                        automation = server
                    } catch {
                        NSLog("ZV automation: не удалось запустить: \(error)")
                    }
                }
        }
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(after: .toolbar) {
                PresetsCommand()
                Button(screen.debugOpen ? "Скрыть отладку" : "Отладка") { screen.toggleDebug() }
                    .keyboardShortcut("d", modifiers: [.option, .command])
                Divider()
                // цифровое увеличение картинки — только на экране, камера о нём не знает
                Button("Увеличить") { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { screen.zoomStep(up: true) } }
                    .keyboardShortcut("=", modifiers: .command)
                    .disabled(!controller.hasFrame || screen.galleryShot != nil)
                Button("Уменьшить") { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { screen.zoomStep(up: false) } }
                    .keyboardShortcut("-", modifiers: .command)
                    .disabled(!screen.zoom.isZoomed || screen.galleryShot != nil)
                Button("Без увеличения") { withAnimation(.spring(response: 0.3, dampingFraction: 0.85)) { screen.zoom = .identity } }
                    .keyboardShortcut("0", modifiers: .command)
                    .disabled(screen.zoom == .identity)
            }
        }
        // пресеты настраивают в своём окне, рядом с картинкой
        Window("Пресеты", id: PresetsWindow.id) {
            PresetsWindow(controller: controller, screen: screen)
        }
        .defaultSize(width: 760, height: 520)
        .windowResizability(.contentMinSize)
        Settings {
            SettingsView(controller: controller)
        }
    }
}

struct RootView: View {
    let controller: CameraController
    let screen: ScreenState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        ZStack(alignment: .topLeading) {
            switch controller.phase {
            case .searching:
                WaitingView(kind: .searching, controller: controller)
            case .notPCRemote:
                WaitingView(kind: .notPCRemote, controller: controller)
            case .connecting, .settling, .ready:
                LiveScreen(controller: controller, screen: screen)
            }
        }
        // окно пресетов просят открыть список и API, а открыть окно может только вид
        .onChange(of: screen.presetsWindowRequest) { openWindow(id: PresetsWindow.id) }
        // камера сменила режим — в окне пресетов другой список: выбран его текущий пресет, иначе первый. Здесь, а не в
        // самом окне: закрытое окно узнало бы о смене только при следующем открытии и сбило бы выбор, с которым его открыли
        .onChange(of: controller.presetMode) {
            screen.presetsSelected = controller.currentPresetIndex ?? (controller.presets.isEmpty ? nil : 0)
        }
        // панель отладки — поверх любого экрана, и ожидания тоже: там видно, куда программа стучится
        .overlay(alignment: .topLeading) {
            if screen.debugOpen {
                GeometryReader { geometry in
                    DebugPanel(controller: controller, screen: screen, maxHeight: geometry.size.height - 80)
                        .padding(.top, 64)
                        .padding(.leading, 16)
                }
            }
        }
    }
}
