import Foundation

/// Трёхмерная таблица цвета (LUT): куб `size³` точек, в каждой — цвет на выходе. Между точками — трилинейно.
/// Встроенные LUT и файлы `.cube` приходят сюда одинаковыми; на Mac таблица уходит в `CIColorCube`
/// (`rgbaFloat32()`), на Android — в свою целочисленную таблицу.
public struct ColorLUT: Equatable, Sendable {
    /// Точек на ребре куба, не меньше 2.
    public let size: Int
    /// `size³ × 3` чисел: красный, зелёный, синий каждой точки; красный меняется быстрее всех, потом зелёный, потом
    /// синий (как в `.cube`). Выход — 0…1; что за ними, `apply` и `rgbaFloat32` обрезают.
    public let values: [Float]
    /// Вход, который куб растягивает на свои 0…`size − 1`: по числу на красный, зелёный и синий. У `.cube` это
    /// `DOMAIN_MIN` и `DOMAIN_MAX`; по умолчанию 0…1.
    public let domainMin: [Float]
    public let domainMax: [Float]

    public init(size: Int, values: [Float], domainMin: [Float] = [0, 0, 0], domainMax: [Float] = [1, 1, 1]) {
        precondition(size >= 2 && values.count == size * size * size * 3, "ColorLUT: нужны size ≥ 2 и size³·3 чисел")
        precondition(domainMin.count == 3 && domainMax.count == 3, "ColorLUT: домен — по одному числу на канал")
        self.size = size
        self.values = values
        self.domainMin = domainMin
        self.domainMax = domainMax
    }

    /// Тождественный куб: выход равен входу.
    public static func identity(size: Int) -> ColorLUT {
        precondition(size >= 2, "ColorLUT: размер не меньше 2")
        var values: [Float] = []
        values.reserveCapacity(size * size * size * 3)
        let last = Float(size - 1)
        for b in 0..<size {
            for g in 0..<size {
                for r in 0..<size {
                    values.append(Float(r) / last)
                    values.append(Float(g) / last)
                    values.append(Float(b) / last)
                }
            }
        }
        return ColorLUT(size: size, values: values)
    }

    /// Цвет на выходе для цвета на входе. Вход — в своём домене (за ним обрезается), между точками — трилинейно;
    /// выход обрезается до 0…1. Ни бесконечность, ни NaN на входе не ломают: это край домена или его начало.
    public func apply(_ r: Float, _ g: Float, _ b: Float) -> (Float, Float, Float) {
        let (r0, rf) = cell(r, channel: 0)
        let (g0, gf) = cell(g, channel: 1)
        let (b0, bf) = cell(b, channel: 2)
        func corner(_ dr: Int, _ dg: Int, _ db: Int, _ k: Int) -> Float {
            values[(((b0 + db) * size + g0 + dg) * size + r0 + dr) * 3 + k]
        }
        func channel(_ k: Int) -> Float {
            // сначала вдоль красного, потом вдоль зелёного, потом вдоль синего
            let c00 = Self.lerp(corner(0, 0, 0, k), corner(1, 0, 0, k), rf)
            let c10 = Self.lerp(corner(0, 1, 0, k), corner(1, 1, 0, k), rf)
            let c01 = Self.lerp(corner(0, 0, 1, k), corner(1, 0, 1, k), rf)
            let c11 = Self.lerp(corner(0, 1, 1, k), corner(1, 1, 1, k), rf)
            let c0 = Self.lerp(c00, c10, gf)
            let c1 = Self.lerp(c01, c11, gf)
            return Self.clamp(Self.lerp(c0, c1, bf))
        }
        return (channel(0), channel(1), channel(2))
    }

    /// Данные для `CIColorCube.cubeData`: `size³` ячеек по четыре `Float32` (красный, зелёный, синий, альфа 1) —
    /// красный быстрее всех, потом зелёный, потом синий, 16 байт на ячейку. Выход обрезан до 0…1.
    public func rgbaFloat32() -> Data {
        let cells = size * size * size
        var rgba = [Float](repeating: 1, count: cells * 4)
        for cell in 0..<cells {
            rgba[cell * 4] = Self.clamp(values[cell * 3])
            rgba[cell * 4 + 1] = Self.clamp(values[cell * 3 + 1])
            rgba[cell * 4 + 2] = Self.clamp(values[cell * 3 + 2])
        }
        return rgba.withUnsafeBytes { Data($0) }
    }

    // MARK: - Внутренности

    /// Ячейка по каналу: номер левой точки (0…size − 2) и доля пути до правой (0…1).
    private func cell(_ value: Float, channel: Int) -> (Int, Float) {
        let span = domainMax[channel] - domainMin[channel]
        // `>`, а не `max/min`: NaN попадает в «не больше нуля» и становится началом домена
        let t = span > 0 ? (value - domainMin[channel]) / span : 0
        let unit = t > 0 ? (t < 1 ? t : 1) : 0
        let x = unit * Float(size - 1)
        let index = min(Int(x), size - 2)
        return (index, x - Float(index))
    }

    /// `a + (b − a)·t`, а не `a·(1 − t) + b·t`: если углы равны, получается ровно он, без шума округления.
    private static func lerp(_ a: Float, _ b: Float, _ t: Float) -> Float { a + (b - a) * t }

    private static func clamp(_ v: Float) -> Float { v > 0 ? (v < 1 ? v : 1) : 0 }
}
