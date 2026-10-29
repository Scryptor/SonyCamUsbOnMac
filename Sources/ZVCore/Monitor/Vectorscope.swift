import Foundation

/// Вектороскоп: куда ложатся цвета кадра на плоскости цветоразностных Cb и Cr (Rec. 709). Серый — в середине, насыщенный
/// цвет — к краю в своём направлении, светлая кожа — вдоль линии под углом 123°.
///
/// Плоскость — сетка 128×128 корзин, Cb идёт вправо, Cr вверх (строка 0 — вверху, там Cr наибольший). Считается по каждой
/// второй точке кадра по обеим осям: при 1024×576 это ≈147 тыс. отсчётов. Край плоскости при ×1 — 0,5: туда ложится
/// полностью насыщенный цвет; при ×2 — 0,25, так видны бледные цвета лога. Что за краем, в счётчики не попадает.
public enum Vectorscope {
    /// Корзин по каждой оси: плоскость — 128×128.
    public static let bins = 128
    /// Линия тона кожи: угол от оси +Cb против часовой стрелки, градусы.
    public static let skinAngle = 123.0

    // Rec. 709: Y = 0,2126R + 0,7152G + 0,0722B; Cb = (B − Y)/1,8556; Cr = (R − Y)/1,5748.
    private static let kr = 0.2126
    private static let kg = 0.7152
    private static let kb = 0.0722
    private static let cbDivisor = 1.8556
    private static let crDivisor = 1.5748

    /// Допуск на шум округления, в корзинах: цвет ровно на краю (у синего Cb = 0,5) не должен выпасть за него.
    private static let edgeTolerance = 1e-9

    // MARK: - Плоскость

    /// Cb и Cr по Rec. 709 для цвета r, g, b (0…1); оба лежат в −0,5…0,5.
    ///
    /// Это те же `Cb = (B − Y)/1,8556` и `Cr = (R − Y)/1,5748`, но через разности каналов: коэффициенты яркости в сумме
    /// дают 1, поэтому `B − Y = 0,2126(B − R) + 0,7152(B − G)`, а `R − Y = 0,7152(R − G) + 0,0722(R − B)`. У серого
    /// (R = G = B) разности — ровно нули, а через `Y` в них остался бы шум округления в последнем знаке: серый кадр
    /// попадал бы то в одну, то в соседнюю корзину в зависимости от уровня.
    @inline(__always)
    public static func chroma(r: Double, g: Double, b: Double) -> (cb: Double, cr: Double) {
        ((kr * (b - r) + kg * (b - g)) / cbDivisor, (kg * (r - g) + kb * (r - b)) / crDivisor)
    }

    /// Корзины на единицу Cb или Cr: плоскость шириной 1/zoom занимает все 128. zoom меньше 1 — как 1.
    @inline(__always)
    private static func binsPerUnit(_ zoom: Int) -> Double { Double(bins) * Double(max(zoom, 1)) }

    /// Номер корзины (строка · 128 + столбец) для Cb и Cr; −1 — за краем (и для не-числа: сравнения с ним ложны).
    @inline(__always)
    private static func cell(cb: Double, cr: Double, perUnit: Double) -> Int {
        let half = Double(bins / 2)
        let fx = cb * perUnit + half
        let fy = half - cr * perUnit
        let low = -edgeTolerance, high = Double(bins) + edgeTolerance
        guard fx >= low, fx <= high, fy >= low, fy <= high else { return -1 }
        // Int() отбрасывает дробь к нулю: чуть меньше нуля — всё ещё корзина 0; ровно на краю — крайняя корзина
        return min(Int(fy), bins - 1) * bins + min(Int(fx), bins - 1)
    }

    /// Корзина для Cb и Cr: x растёт вправо вместе с Cb, y = 0 вверху (Cr наибольший). nil — за краем: при ×1 это |Cb|
    /// или |Cr| больше 0,5, при ×2 — больше 0,25. zoom меньше 1 считается за 1.
    public static func bin(cb: Double, cr: Double, zoom: Int) -> (x: Int, y: Int)? {
        let index = cell(cb: cb, cr: cr, perUnit: binsPerUnit(zoom))
        return index < 0 ? nil : (index % bins, index / bins)
    }

    /// Метки цветных полос 75 %: красный, пурпурный, синий, голубой, зелёный, жёлтый — по тем же формулам, что точки кадра.
    public static let targets: [(name: String, cb: Double, cr: Double)] = {
        let bars: [(name: String, r: Double, g: Double, b: Double)] = [
            ("R", 0.75, 0, 0), ("Mg", 0.75, 0, 0.75), ("B", 0, 0, 0.75),
            ("Cy", 0, 0.75, 0.75), ("G", 0, 0.75, 0), ("Yl", 0.75, 0.75, 0),
        ]
        return bars.map { bar in
            let c = chroma(r: bar.r, g: bar.g, b: bar.b)
            return (name: bar.name, cb: c.cb, cr: c.cr)
        }
    }()

    // MARK: - Подсчёт по кадру

    /// Значение канала 0…255 → 0…1, один раз на программу, а не делением на каждую точку.
    private static let unit: [Double] = (0..<256).map { Double($0) / 255 }

    /// Сколько отсчётов кадра попало в каждую корзину: `bins²` чисел, строки сверху вниз (индекс = y · 128 + x). Берётся
    /// каждая вторая точка кадра по обеим осям — с чётным x и чётным y. rgba — по 4 байта на точку (R, G, B, A), строка
    /// кадра занимает bytesPerRow байт. Кадр, которого нет или который короче своей разметки, даёт нули.
    public static func counts(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, zoom: Int) -> [UInt32] {
        var counts = [UInt32](repeating: 0, count: bins * bins)
        guard width > 0, height > 0, bytesPerRow >= width * 4,
              rgba.count >= (height - 1) * bytesPerRow + width * 4 else { return counts }
        let perUnit = binsPerUnit(zoom)
        let unit = Self.unit
        rgba.withUnsafeBufferPointer { src in
            unit.withUnsafeBufferPointer { level in
                counts.withUnsafeMutableBufferPointer { out in
                    for y in stride(from: 0, to: height, by: 2) {
                        var i = y * bytesPerRow
                        for _ in stride(from: 0, to: width, by: 2) {
                            let c = chroma(r: level[Int(src[i])], g: level[Int(src[i + 1])], b: level[Int(src[i + 2])])
                            let index = cell(cb: c.cb, cr: c.cr, perUnit: perUnit)
                            if index >= 0 { out[index] &+= 1 }
                            // следующая точка через одну: 2 · 4 байта
                            i += 8
                        }
                    }
                }
            }
        }
        return counts
    }

    // MARK: - Картинка и пик

    /// Картинка точек: RGBA, `bins²` точек, строки сверху вниз, как корзины. Пустая корзина — прозрачная (альфа 0), самая
    /// полная — непрозрачная (альфа 255), между ними яркость `log(1 + n) / log(1 + max)`. Цвет — оттенок самой корзины
    /// (см. `hue`): видно, какой цвет где. Альфа уже умножена на цвет, как у волновой формы. Счётчики не того размера
    /// или пустые дают прозрачную картинку.
    ///
    /// Цвет зависит только от места корзины на панели, не от масштаба: при ×2 оттенок тот же, насыщенность условная.
    public static func image(counts: [UInt32]) -> [UInt8] {
        var pixels = [UInt8](repeating: 0, count: bins * bins * 4)
        guard counts.count == bins * bins, let most = counts.max(), most > 0 else { return pixels }
        let top = log(1 + Double(most))
        for index in 0..<counts.count {
            let n = counts[index]
            guard n > 0 else { continue }
            // самая полная корзина — ровно 1: x / x
            let brightness = min(log(1 + Double(n)) / top, 1)
            let (r, g, b) = hue(x: index % bins, y: index / bins)
            let o = index * 4
            pixels[o] = UInt8((r * brightness * 255).rounded())
            pixels[o + 1] = UInt8((g * brightness * 255).rounded())
            pixels[o + 2] = UInt8((b * brightness * 255).rounded())
            pixels[o + 3] = UInt8((brightness * 255).rounded())
        }
        return pixels
    }

    /// Цвет точек корзины: яркость 0,6 и Cb/Cr середины корзины (как при ×1), обратно в RGB по тем же формулам Rec. 709,
    /// каждый канал обрезан до 0…1. Зелёный считается без обрезки красного и синего — обратное преобразование точное.
    private static func hue(x: Int, y: Int) -> (r: Double, g: Double, b: Double) {
        let cb = (Double(x) + 0.5) / Double(bins) - 0.5
        let cr = 0.5 - (Double(y) + 0.5) / Double(bins)
        let luma = 0.6
        let r = luma + crDivisor * cr
        let b = luma + cbDivisor * cb
        let g = (luma - kr * r - kb * b) / kg
        return (min(max(r, 0), 1), min(max(g, 0), 1), min(max(b, 0), 1))
    }

    /// Середина самой полной корзины, в единицах Cb и Cr (при ×2 — с поправкой на масштаб). Если полных несколько — первая
    /// сверху слева. nil — счётчики пусты или не того размера.
    public static func peak(counts: [UInt32], zoom: Int) -> (cb: Double, cr: Double)? {
        guard counts.count == bins * bins else { return nil }
        var best = -1
        var most: UInt32 = 0
        for index in 0..<counts.count where counts[index] > most {
            best = index
            most = counts[index]
        }
        guard best >= 0 else { return nil }
        let scale = Double(max(zoom, 1))
        let n = Double(bins)
        return (((Double(best % bins) + 0.5) / n - 0.5) / scale, (0.5 - (Double(best / bins) + 0.5) / n) / scale)
    }
}
