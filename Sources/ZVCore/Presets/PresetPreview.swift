import Foundation
import ImageIO

/// Кадр пресета: уменьшенный кадр live view — как камера показывала сцену, когда значения пресета взяли с неё.
/// Камера не умеет показать пресет, не применив его, поэтому кадр — единственный честный предпросмотр.
public enum PresetPreview {
    /// Ширина кадра, точек: хватает и на строку списка, и на окно пресетов, а весит он 10–20 КБ.
    public static let width = 320

    /// Уменьшенная копия JPEG той же формы; не JPEG — nil.
    public static func thumbnail(jpeg: Data, width: Int = width, quality: Double = 0.75) -> Data? {
        guard let source = CGImageSourceCreateWithData(jpeg as CFData, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceThumbnailMaxPixelSize: width,
            kCGImageSourceCreateThumbnailWithTransform: true,
        ]
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        let out = NSMutableData()
        guard let destination = CGImageDestinationCreateWithData(out, "public.jpeg" as CFString, 1, nil) else { return nil }
        CGImageDestinationAddImage(destination, image, [kCGImageDestinationLossyCompressionQuality: quality] as CFDictionary)
        return CGImageDestinationFinalize(destination) ? out as Data : nil
    }
}
