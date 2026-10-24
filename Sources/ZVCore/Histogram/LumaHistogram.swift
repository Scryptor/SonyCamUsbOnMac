import Foundation

/// Гистограмма яркости кадра: сколько точек приходится на каждый уровень яркости.
public enum LumaHistogram {
    /// rgba — пиксели по 4 байта (R, G, B, A); count — число столбцов гистограммы.
    public static func bins(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, count: Int = 64) -> [Int] {
        var bins = [Int](repeating: 0, count: count)
        guard count > 0, width > 0, height > 0, rgba.count >= (height - 1) * bytesPerRow + width * 4 else { return bins }
        for y in 0..<height {
            var i = y * bytesPerRow
            for _ in 0..<width {
                // яркость по Rec. 709 в целых числах (коэффициенты ×1024)
                let luma = (218 * Int(rgba[i]) + 732 * Int(rgba[i + 1]) + 74 * Int(rgba[i + 2])) >> 10
                bins[min(luma * count / 256, count - 1)] += 1
                i += 4
            }
        }
        return bins
    }
}
