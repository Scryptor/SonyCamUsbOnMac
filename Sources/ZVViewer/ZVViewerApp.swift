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
    }
}

struct RootView: View {
    let controller: CameraController
    let screen: ScreenState

    var body: some View {
        switch controller.phase {
        case .searching:
            WaitingView(kind: .searching, message: controller.connectionMessage)
        case .notPCRemote:
            WaitingView(kind: .notPCRemote)
        case .connecting, .settling, .ready:
            LiveScreen(controller: controller, screen: screen)
        }
    }
}
