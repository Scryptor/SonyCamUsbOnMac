import AppKit
import SwiftUI
import UniformTypeIdentifiers
import ZVCore

/// Панель наложения поверх кадра — пока наложение включено и эталон есть. Тащится и помнит место, как гистограмма; по
/// умолчанию — справа вверху, под строкой состояния; на кнопки справа не ложится.
struct FloatingOverlayPanel: View {
    let controller: CameraController
    let screen: ScreenState
    let reference: ReferenceFrame
    let view: CGSize

    var body: some View {
        FloatingPanel(view: view, placement: screen.overlayPlacement, place: screen.placeOverlay,
                      avoiding: screen.rightColumnRect) { (panel: CGSize) -> CGPoint in
            let margin = CGFloat(FloatingPlacement.margin)
            return CGPoint(x: max(margin, view.width - panel.width - margin), y: 64)
        } content: {
            OverlayPanel(controller: controller, screen: screen, reference: reference)
        }
    }
}

/// Миниатюра эталона — кнопка «спрятать / показать» (как клавиша O): сравнить с живым кадром без эталона и обратно.
/// Под ней — непрозрачность 5–100 %, «Новый кадр», «Из файла…»; ✕ выключает наложение.
struct OverlayPanel: View {
    let controller: CameraController
    let screen: ScreenState
    let reference: ReferenceFrame

    var body: some View {
        let hidden = screen.overlayHidden
        let percent = screen.assist.overlay.percent ?? 50
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .top, spacing: 9) {
                Button { screen.toggleOverlayVisible() } label: {
                    Image(decorative: reference.image, scale: 1)
                        .resizable()
                        .aspectRatio(contentMode: .fill)
                        .frame(width: 96, height: 54)
                        .clipped()
                        .opacity(hidden ? 0.3 : 1)
                        .overlay(alignment: .bottomTrailing) {
                            Image(systemName: hidden ? "eye.slash.fill" : "eye.fill")
                                .font(.system(size: 10, weight: .semibold))
                                .padding(4)
                                .background(.black.opacity(0.6), in: Circle())
                                .padding(3)
                        }
                        .clipShape(RoundedRectangle(cornerRadius: 6))
                        .overlay(RoundedRectangle(cornerRadius: 6)
                            .strokeBorder(hidden ? Color.white.opacity(0.25) : Color.accentColor, lineWidth: 1.5))
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .help(hidden ? "Показать эталон (O)" : "Спрятать эталон (O) — сравнить с живым кадром")
                .accessibilityLabel(hidden ? "Показать эталон" : "Спрятать эталон")
                VStack(alignment: .leading, spacing: 2) {
                    Text("Наложение")
                        .font(.system(size: 12, weight: .semibold))
                    Text(reference.source)
                        .font(.system(size: 10.5))
                        .foregroundStyle(.white.opacity(0.6))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Text(hidden ? "Спрятано — щёлкните по картинке" : "Щелчок по картинке — спрятать")
                        .font(.system(size: 10))
                        .foregroundStyle(.white.opacity(0.45))
                }
                Spacer(minLength: 0)
                Button { screen.setAssist(.overlay, "off") } label: {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .frame(width: 18, height: 18)
                        .background(.white.opacity(0.12), in: Circle())
                }
                .buttonStyle(.plain)
                .help("Выключить наложение")
                .accessibilityLabel("Выключить наложение")
            }
            HStack(spacing: 8) {
                Image(systemName: "circle.lefthalf.filled")
                    .font(.system(size: 11))
                    .foregroundStyle(.white.opacity(0.7))
                Slider(value: Binding(get: { Double(percent) / 100 }, set: { screen.setOverlayOpacity($0) }), in: 0.05...1)
                    .controlSize(.small)
                Text("\(percent) %")
                    .font(.system(size: 11, weight: .medium).monospacedDigit())
                    .frame(width: 40, alignment: .trailing)
            }
            .help("Непрозрачность эталона: 100 % вместе с «спрятать» — чистое сравнение «эталон ↔ живое»")
            HStack(spacing: 6) {
                PanelButton(title: "Новый кадр") {
                    controller.takeReferenceFromFrame()
                    screen.showOverlay()
                }
                .disabled(controller.frame == nil)
                .help("Эталон — текущий кадр")
                PanelButton(title: "Из файла…") { chooseReferenceFile(controller: controller, screen: screen) }
                    .help("Эталон — картинка из файла: JPEG, PNG, HEIC… Её можно и просто перетащить на окно")
            }
        }
        .foregroundStyle(.white)
        .frame(width: 264)
        .padding(8)
        .background(.black.opacity(0.45), in: RoundedRectangle(cornerRadius: 10))
    }
}

private struct PanelButton: View {
    let title: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 11, weight: .medium))
                .padding(.horizontal, 9)
                .padding(.vertical, 4)
                .background(.white.opacity(0.12), in: RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
    }
}

/// «Из файла…»: окно выбора картинки; выбрали — она эталон, наложение видно.
@MainActor
func chooseReferenceFile(controller: CameraController, screen: ScreenState) {
    let panel = NSOpenPanel()
    panel.title = "Эталон для наложения"
    panel.message = "Картинка ляжет полупрозрачно поверх живого видео"
    panel.prompt = "Наложить"
    panel.allowedContentTypes = [.image]
    panel.allowsMultipleSelection = false
    panel.canChooseDirectories = false
    guard panel.runModal() == .OK, let url = panel.url else { return }
    controller.takeReference(fromFile: url)
    screen.showOverlay()
}

/// Картинка ли это — для перетаскивания файла на окно.
func isImageFile(_ url: URL) -> Bool {
    url.isFileURL && (UTType(filenameExtension: url.pathExtension)?.conforms(to: .image) ?? false)
}
