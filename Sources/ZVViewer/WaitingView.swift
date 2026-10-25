import SwiftUI

/// Экран, пока камеры нет или она не в режиме PC Remote: как подключить по кабелю и по Wi-Fi.
struct WaitingView: View {
    enum Kind { case searching, notPCRemote }

    let kind: Kind
    let controller: CameraController

    var body: some View {
        VStack(alignment: .leading, spacing: 22) {
            Text(kind == .searching ? "Подключите камеру" : "Камера подключена, но не в режиме PC Remote")
                .font(.title.weight(.semibold))
            HStack(alignment: .top, spacing: 48) {
                usb
                // камера на кабеле не в PC Remote (например, на зарядке в режиме Wi-Fi) — по сети всё равно можно
                wifi
            }
            if kind == .searching, let message = controller.connectionMessage {
                Text(message).foregroundStyle(.secondary)
            }
        }
        .padding(48)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.black)
        .environment(\.colorScheme, .dark)
    }

    private var usb: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("По кабелю USB").font(.title2.weight(.semibold))
            Group {
                Text("1. MENU → Network → Smartphone Connect → Smartphone Connection: Off")
                Text("2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: USB")
                Text(kind == .searching ? "3. Подключите камеру к Mac кабелем USB-C" : "Переподключите кабель")
            }
            .foregroundStyle(.secondary)
            if kind == .searching, controller.cableAccessHint {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Камера на кабеле видна, но macOS пока не даёт программе доступ к ней").fontWeight(.semibold)
                    Text("Нажмите «Разрешить» в окне про доступ к файлам на съемном томе — после установки "
                        + "или обновления программы macOS спрашивает об этом снова.")
                    Text("Окна нет — Системные настройки → Конфиденциальность и безопасность → Файлы и папки → "
                        + "Sony Cam Viewer → Съемные тома. Доступ уже есть — переподключите кабель.")
                }
                .foregroundStyle(.orange)
                .padding(.top, 6)
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
    }

    private var wifi: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("По Wi-Fi").font(.title2.weight(.semibold))
            Group {
                Text("1. Камера и Mac — в одной сети Wi-Fi (у камеры: MENU → Network → Wi-Fi Settings → Access Point Set.)")
                Text("2. MENU → Network → PC Remote Function → PC Remote: On, PC Remote Cnct Method: Wi-Fi Access Point")
                Text("3. Один раз — сопряжение: кнопка ниже, на камере PC Remote Function → Pairing")
                Text("Без роутера: на камере PC Remote Cnct Method: Wi-Fi Direct, Mac — к сети камеры (DIRECT-…, пароль на экране камеры). Интернета на Mac в это время не будет.")
            }
            .foregroundStyle(.secondary)
            HStack(spacing: 12) {
                if controller.isPairing {
                    ProgressView().controlSize(.small)
                    Button("Отмена") { controller.cancelPairing() }
                } else {
                    Button("Сопрячь с камерой…") { controller.startPairing() }
                }
                if !controller.isPairing, Prefs.wifiEnabled, let name = Prefs.wifiCameraName {
                    HStack(spacing: 6) {
                        ProgressView().controlSize(.small)
                        Text("Ищу \(name) в сети…").foregroundStyle(.secondary)
                    }
                }
            }
            .padding(.top, 4)
            if let status = controller.wifiStatus {
                Text(status).foregroundStyle(.orange)
            }
            if !Prefs.wifiEnabled {
                Text("Подключение по Wi-Fi выключено в настройках (⌘,)").foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: 520, alignment: .leading)
    }
}
