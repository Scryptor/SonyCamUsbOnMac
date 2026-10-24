import SwiftUI

@main
struct ZVViewerApp: App {
    @State private var controller = CameraController()

    var body: some Scene {
        WindowGroup("ZV-E10 Viewer") {
            RootView(controller: controller)
                .frame(minWidth: 800, minHeight: 450)
        }
        .windowResizability(.contentMinSize)
    }
}

struct RootView: View {
    let controller: CameraController

    var body: some View {
        switch controller.phase {
        case .searching:
            WaitingView(kind: .searching)
        case .notPCRemote:
            WaitingView(kind: .notPCRemote)
        case .connecting, .settling, .ready:
            LiveScreen(controller: controller)
        }
    }
}
