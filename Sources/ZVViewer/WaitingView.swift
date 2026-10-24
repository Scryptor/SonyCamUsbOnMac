import SwiftUI

/// Экран, пока камеры нет или она не в режиме PC Remote.
struct WaitingView: View {
    enum Kind { case searching, notPCRemote }

    let kind: Kind

    private let cameraSteps = [
        "1. На камере: MENU → Network → Smartphone Connect → Smartphone Connection: Off",
        "2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: USB",
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text(kind == .searching ? "Подключите камеру" : "Камера подключена, но не в режиме PC Remote")
                .font(.title.weight(.semibold))
            VStack(alignment: .leading, spacing: 10) {
                ForEach(cameraSteps, id: \.self) { Text($0) }
                Text(kind == .searching ? "3. Подключите камеру к Mac кабелем USB-C" : "Переподключите кабель")
            }
            .font(.title3)
            .foregroundStyle(.secondary)
            if kind == .searching {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text("Ищу камеру…").foregroundStyle(.secondary)
                }
                .padding(.top, 8)
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black)
        .environment(\.colorScheme, .dark)
    }
}
