import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// Эталонный кадр для наложения: полупрозрачно поверх живого, чтобы повторить композицию. Хранится в файле рядом с
/// настройками API (Application Support/Sony Cam Viewer/reference.jpg) — переживает перезапуск, а снимок, из которого
/// его взяли, можно и удалить.
struct ReferenceFrame {
    let image: CGImage
    /// Откуда: «кадр 12:04:31» или имя снимка.
    let source: String

    /// Длинная сторона эталона из снимка: больше экрану не нужно.
    static let maxSide = 2048

    private static let sourceKey = "referenceSource"

    static var fileURL: URL {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("Sony Cam Viewer", isDirectory: true)
            .appendingPathComponent("reference.jpg")
    }

    /// Эталон прошлого раза; nil — его нет.
    static func load() -> ReferenceFrame? {
        guard let source = CGImageSourceCreateWithURL(fileURL as CFURL, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, [kCGImageSourceShouldCacheImmediately: true] as CFDictionary)
        else { return nil }
        return ReferenceFrame(image: image, source: UserDefaults.standard.string(forKey: sourceKey) ?? "эталон")
    }

    /// Кадр превью как есть — уже JPEG.
    static func save(jpeg: Data, source: String) {
        try? FileManager.default.createDirectory(at: fileURL.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? jpeg.write(to: fileURL, options: .atomic)
        UserDefaults.standard.set(source, forKey: sourceKey)
    }

    /// Из файлов снимка: JPEG, если он есть (RAW долго разбирать), уменьшенный, — и сразу в файл эталона.
    static func make(from urls: [URL]) async -> ReferenceFrame? {
        await Task.detached(priority: .userInitiated) { () -> ReferenceFrame? in
            let jpeg = urls.first { ["jpg", "jpeg"].contains($0.pathExtension.lowercased()) } ?? urls.first
            guard let url = jpeg, let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                      kCGImageSourceCreateThumbnailFromImageAlways: true,
                      kCGImageSourceCreateThumbnailWithTransform: true,
                      kCGImageSourceThumbnailMaxPixelSize: maxSide,
                  ] as CFDictionary)
            else { return nil }
            let data = NSMutableData()
            if let destination = CGImageDestinationCreateWithData(data, UTType.jpeg.identifier as CFString, 1, nil) {
                CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: 0.9] as CFDictionary)
                if CGImageDestinationFinalize(destination) { save(jpeg: data as Data, source: url.lastPathComponent) }
            }
            return ReferenceFrame(image: image, source: url.lastPathComponent)
        }.value
    }

    /// Где эталон на экране: заполняет кадр (rect) по большей стороне, лишнее обрезано, — так совпадает кадр того же
    /// формата, а снимок 3:2 ложится на видео 16:9 по ширине.
    static func fillRect(image: CGSize, in rect: CGRect) -> CGRect {
        guard image.width > 0, image.height > 0 else { return rect }
        let k = max(rect.width / image.width, rect.height / image.height)
        let size = CGSize(width: image.width * k, height: image.height * k)
        return CGRect(x: rect.midX - size.width / 2, y: rect.midY - size.height / 2, width: size.width, height: size.height)
    }
}
