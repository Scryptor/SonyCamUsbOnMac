import Foundation

public enum FileNaming {
    /// Свободное имя: DSC01234.JPG → DSC01234 (2).JPG → DSC01234 (3).JPG …
    public static func unique(_ name: String, isTaken: (String) -> Bool) -> String {
        guard isTaken(name) else { return name }
        let ext = (name as NSString).pathExtension
        let base = (name as NSString).deletingPathExtension
        var n = 2
        while true {
            let candidate = ext.isEmpty ? "\(base) (\(n))" : "\(base) (\(n)).\(ext)"
            if !isTaken(candidate) { return candidate }
            n += 1
        }
    }

    /// Имя стоп-кадра из видео: «Кадр 2026-10-24 20-31-05.jpg».
    public static func frameGrab(at date: Date, calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day, .hour, .minute, .second], from: date)
        return String(format: "Кадр %04d-%02d-%02d %02d-%02d-%02d.jpg",
                      c.year ?? 0, c.month ?? 0, c.day ?? 0, c.hour ?? 0, c.minute ?? 0, c.second ?? 0)
    }
}
