import SwiftUI

@main
struct ZVViewerApp: App {
    @State private var controller = CameraController()
    @State private var screen = ScreenState()
    @State private var automation: AutomationServer?

    var body: some Scene {
        WindowGroup("ZV-E10 Viewer") {
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
                Button(screen.debugOpen ? "Скрыть отладку" : "Отладка") { screen.toggleDebug() }
                    .keyboardShortcut("d", modifiers: [.option, .command])
            }
        }
        Settings {
            SettingsView(controller: controller)
        }
    }
}

struct RootView: View {
    let controller: CameraController
    let screen: ScreenState

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
