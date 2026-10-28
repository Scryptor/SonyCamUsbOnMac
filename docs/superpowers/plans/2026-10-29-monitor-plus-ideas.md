# LUT, вектороскоп, пипетка, зеркало, метки фокуса, кнопки громкости и другие камеры — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В обеих программах — плитки «LUT», «Вектороскоп», «Пипетка», «Картинка» в меню «Монитор», метки фокуса A/B с
плавным переводом, на Android — кнопки громкости, а для других камер Sony — подписи значений, Creative Look и
расстояния на шкале фокуса.

**Architecture:** Математика и смена состояний — чистые типы с тестами: ZVCore на Mac, файлы без Android-зависимостей
на Android. Картинка преобразуется один раз, сразу после декодирования: поворот, потом LUT. Этот кадр видит экран, а
помощь считает по нему же или по кадру только с поворотом. Новые операции Sony программа шлёт только камерам, которые
их перечисляют. Проверка — поддельная камера `scripts/fake_camera.py` в двух профилях.

**Tech Stack:** Swift 6 (language mode 5), SwiftUI, Core Image (`CIColorCube`), ImageIO, Swift Testing; Kotlin 2.0.21,
Compose, JUnit 4; Python 3 (стенд, numpy + Pillow для генераторов, только stdlib в `fake_camera.py`).

**Spec:** `docs/superpowers/specs/2026-10-29-monitor-plus-ideas-design.md`

**Где и кто:**

- работа идёт в worktree `.claude/worktrees/monitor-plus-ideas`, ветка `claude/monitor-plus-ideas`;
- этап 1 (Task 1–13: F1–F3, C1–C5, K1–K5) трогает только новые файлы и ZVCore/логику;
- этап 2 (Task 14–25: M1–M6, A1–A6) начинается после шага G (соседняя ветка влита, наша перебазирована);
- этап 3 — Task 26–28: D1, R1 (с проверкой превью S-Log), R2.

## Global Constraints

- **Mac:** macOS 14+, без внешних зависимостей. Metal-шейдеры не собираем — только системные фильтры Core Image;
  `CIContext` для картинки — `workingColorSpace: NSNull()`, `outputColorSpace: NSNull()`.
- **Android:** minSdk 29, compileSdk 34; поворот и LUT — на CPU (без AGSL).
- **Язык.** Тексты интерфейса и комментарии — по-русски, идентификаторы — по-английски. В документации нет чужих
  брендов и нет «проверено, работает»; модель камеры в интерфейсе — только если это настоящая модель.
- **Только на экране:**
  - стоп-кадр, снимки, `reference.jpg` и `GET /frame` — JPEG камеры как есть;
  - плитки других камер в ленте — как есть.
- **Плитки «Монитора»** — 14, по 7 в строке:
  - `zebra, falseColor, peaking, peakingColor, lut, vectorscope, probe`;
  - `grid, center, frameMarker, safeZone, overlay, orientation`;
  - и «Всё — выключить».
- **Значения в JSON `monitorAssist` и API:**
  - `lut`: `off|auto|slog3cine|slog3|slog2|hlg|file`;
  - `lutFile`: строка;
  - `lutScopes`: bool (по умолчанию `true`);
  - `vectorscope`: bool в JSON (как `falseColor`), «on»/«off» в API;
  - `vectorscopeZoom`: `1|2`;
  - `probe`: bool в JSON, «on»/«off» в API;
  - `orientation`: `off|mirror|180|mirror180`.
- **Подписи плиток:**
  - «LUT», «Вектороскоп», «Пипетка», «Картинка»;
  - варианты LUT: «Выкл», «Авто», «S-Log3 Cine», «S-Log3», «S-Log2», «HLG», «Свой»;
  - варианты картинки: «Как есть», «Зеркально», «Вверх ногами», «Зеркально + 180°»;
  - значение на плитке картинки: «—», «Зеркало», «180°», «Зеркало 180°»;
  - «Авто: S-Log3», «Авто: нет»;
  - «Помощь — по картинке с LUT», «Добавить LUT…», «Удалить».
- **LUT:**
  - вход `v = Y′/255` (= код/1023), куб встроенных 33³;
  - `.cube` — 3D, размер 2–65, не больше 12 файлов; 13-й — «Своих LUT не больше 12 — удалите ненужный»;
  - хранение: Mac `~/Library/Application Support/Sony Cam Viewer/LUTs/`, Android `filesDir/luts/`;
  - белый для помощи с LUT — 255.
- **Вектороскоп:**
  - панель 176×176 pt/dp, корзин 128×128, каждая вторая точка по обеим осям;
  - BT.709 (`Y = 0,2126R + 0,7152G + 0,0722B`, `Cb = (B − Y)/1,8556`, `Cr = (R − Y)/1,5748`);
  - край ×1 — 0,5, ×2 — 0,25;
  - кожа 123° от +Cb против часовой стрелки.
- **Пипетка:** квадрат 5×5, подписи не чаще 5 раз в секунду, метка 20 pt/dp, по умолчанию в центре.
- **Перевод фокуса:**
  - время 0/1/2/4/8 с (по умолчанию 2), тик 100 мс, кривая `3t² − 2t³`;
  - абсолютный — готово при ±0,5 % или тайм-аут через 2 с после конца;
  - шаги — `k₀ = 3,5`, `k ← 0,7k + 0,3·Δположения/Δшагов` при |шаг| ≥ 3 и ненулевом сдвиге;
  - допуск `max(1, k/2)`, |команда| ≤ 7, но ≥ 1 при |e| > `max(1, k/2)`; два шага ±1 подряд с разными знаками,
    перескочившие цель, — тоже «у метки»;
  - «не двигает» — 3 команды подряд в одну сторону без сдвига;
  - тайм-аут через 3 с после конца;
  - свежее положение ждать ≤ 300 мс.
- **Подписи меток фокуса:** «A+», «B+», «Запомнить здесь», «Стереть», «Фокус у метки A», «Дальше камера не двигает»,
  «Не успел за 3 с»; время — «0 с», «1 с», «2 с», «4 с», «8 с».
- **Кнопки громкости:**
  - ⚙ «Кнопки громкости — спуск и запись», по умолчанию выключено, ключ `volumeKeys`;
  - коды `KEYCODE_VOLUME_UP` (24), `KEYCODE_VOLUME_DOWN` (25), `KEYCODE_CAMERA` (27);
  - только на экране кадра.
- **Новые операции Sony** — только если они есть в списке операций GetDeviceInfo:
  - `0x9215` — с параметром 0 после подключения и с типом из события `0xC20F`;
  - `0x9223` — с параметром 2, если `0xE086` = 1, и снова по событию `0xC21B`.
- **Порог частоты:** с LUT, зеркалом и всей помощью — не меньше 90 % от частоты без них.
- **Камера пользователя:** каждую тронутую настройку записать до и вернуть ровно как была. На карту не писать: запись
  видео проверяется только на поддельной камере.

## Review Focus

1. **Смена главной камеры, переподключение или смена профиля на камере** при включённых «Авто» и зеркале. LUT должен
   смениться за ≤ 1 с, без кадра со старым LUT дольше секунды. M6: e2e меняет PP на поддельной камере и ждёт
   `picture.lut.resolved`; A1: тест `PictureTransform` со сменой LUT между кадрами.
2. **Кадры не 16:9:** фото 1:1 (1024×1024), 3:2 (1024×680), 640×360 по Wi-Fi. Поворот, пипетка, вектороскоп и LUT —
   без выхода за массив и без смещения. C1/K1: `FrameOrientation` на неквадратных прямоугольниках; C3/K3: пипетка у
   края 1024×1024; K2: `PictureTransform` при смене размера между кадрами.
3. **Битые и тяжёлые `.cube`:** CRLF, BOM, комментарий после чисел, `TITLE` с кавычками, 65³ (274 625 строк).
   C2/K2: тесты разбора, 65³ — меньше 1 с в release.
4. **Ручной фокус выключен или связь оборвалась посреди перевода.** Перевод отменяется, кнопка не залипает в
   «едет». C4/K4: `cancel` в любом состоянии; M6: e2e переключает фокус на AF на поддельной камере посреди перевода.
5. **Касание при зеркале и увеличении сразу.** Точка AF камеры должна совпасть с точкой под пальцем. M6: e2e —
   зеркало + увеличение 2× + `touchFocus`, рамка в долях камеры = `toCamera(точки экрана)` ± 0,01.

---

## Task 1: F1. Стенд: кадры по профилю, физика фокуса, неизвестные операции

**Files:** Modify `scripts/make-viewfinder-picture.py`, `scripts/fake_camera.py`, `scripts/fake_cameras.py`;
Create `scripts/assets/viewfinder-slog3.jpg`, `viewfinder-slog2.jpg`, `viewfinder-hlg.jpg`, `bars.jpg`

- [ ] **Генератор.** `make-viewfinder-picture.py --log` из `viewfinder.jpg` строит три JPEG 1024×576 (качество 92):
  - кадр → линейный (обратный BT.709) → отражение `L` (×1, 18 % = 0,18) → кривая;
  - S-Log3: `код = 420 + 261,5·log10((L + 0,01)/0,19)`, а при `L < 0,01125` — линейная ветка;
  - S-Log2 и HLG — формулы спеки §4 в обратную сторону;
  - запись `round(код/1023·255)`.

  `--bars` строит `bars.jpg` 1024×576 — восемь вертикальных полос 75 %: белый, жёлтый, циан, зелёный, пурпурный,
  красный, синий, чёрный.
- [ ] **Кадры по профилю.** `fake_camera.py` выбирает набор кадров по `0xD23F`: 7 → slog2, 8 и 9 → slog3, 10 → hlg,
  иначе — прежний. У каждого набора — своё низкое качество и уровни зума. Наборы строятся лениво при первом
  выборе, как уровни зума сейчас.
- [ ] **Физика фокуса** — флаги `--focus-gain G` (1,0), `--focus-step-ms T` (0), `--focus-deadband D` (0):
  - команда Near/Far сдвигает `0xD24C` на `value·G`, округлённое от нуля (1·3,5 → 4, −1·3,5 → −4), в пределах
    0…100;
  - ответ приходит через T мс;
  - при |value| ≤ 3 и положении ближе D к краю, куда идёт шаг, положение не меняется.
- [ ] **Неизвестные операции.** Операция, которой нет в списке операций отдаваемого DeviceInfo, получает ответ
  `0x2005`.
- [ ] **Передача флагов.** `fake_cameras.py` передаёт новые флаги камерам. Флаги видны в стартовой строке.
- [ ] **Проверка:**
  - `python3 scripts/fake_camera.py --focus-gain 3.5 --focus-step-ms 80 --focus-deadband 2`;
  - запущенный с `ZV_PTPIP_HOST=127.0.0.1` Mac-билд показывает кадр;
  - `scripts/e2e_monitor.py` и `scripts/e2e_lens.py` — зелёные (поведение по умолчанию прежнее).
- [ ] Commit `test(bench): log frames by picture profile, focus physics, unknown ops answer 0x2005`.

## Task 2: F2. Стенд: профиль новой камеры

**Files:** Modify `scripts/fake_camera.py`; Create `scripts/fake_newer.py` (только stdlib: сборка DevicePropDesc и
наборов)

- [ ] **`--model newer`:**
  - **GetDeviceInfo** — к операциям добавлены `0x9215`, `0x9216`, `0x9223`; модель «ILCE-7M4» (это поддельная, а
    настоящая модель нужна только в отчётах стенда).
  - **GetExtDeviceInfo** — без `0xD240`, с `0xD0FA` (UINT16, Creative Look; значения 0x0001–0x000C и
    0x0101–0x0106, текущее 0x0001), `0xD0E1` (UINT16, гамма, 0x0303), `0xD0E9` (UINT16, цвет, 0x0008), `0xE042`
    (UINT16, Get/Set, Range 0…65535 шаг 1), `0xE043` (UINT16, Get), `0xE086` (UINT8, 1).
- [ ] **`0x9215`:**
  - тип 0 отдаёт два списка:
    - 0x13 (UINT16) — 0x0001 «ST», 0x0002 «PT», 0x0003 «NT», 0x0004 «VV», 0x0005 «VV2», 0x0006 «FL», 0x0007 «IN»,
      0x0008 «SH», 0x0009 «BW», 0x000A «SE», 0x000B «FL2», 0x000C «FL3», 0x0101–0x0106 «Custom Look 1»…«6»;
    - 0x15 — «Человек», «Животное»;
  - тип 0x13 — только первый список, другие типы — пустой (`ListTypeNum = 0`);
  - ответ — параметр версии 0x0100.
- [ ] **`0x9223`** с параметром 2: версия 0x0100, одна таблица на 12 пар:
  - нормированное положение 0, 5958, 11916, … 65535;
  - расстояние ×100: 25, 30, 40, 50, 70, 100, 150, 200, 300, 500, 1000, 100 000 000.

  `0x9216` отвечает параметром 0x0136.
- [ ] **Запись `0xE042`** — фокус едет к цели со скоростью 40 000 единиц/с:
  - `0xE043` и событие `0xC203` — каждые 50 мс;
  - в конце — событие `0xC218` с параметром 1;
  - Near/Far сдвигает `0xE043` на `value·655`.
- [ ] **Проверка:** `python3 scripts/fake_camera.py --model newer` и `zv-probe --wifi 127.0.0.1` показывают
  `0xD0FA`, `0xE043`, `0xE086` и проходят рукопожатие; `scripts/e2e_monitor.py` на профиле по умолчанию — зелёный.
- [ ] Commit `test(bench): newer-camera profile with display strings, lens info and absolute focus`.

## Task 3: F3. Таблицы из справочника Sony

Таблицы — целиком (проект открытый, подписи дорабатывает сообщество): имена всех свойств справочника и все списки
значений. Сам PDF Sony в репозиторий не кладём — в `docs/protocol.md` ссылка, откуда он скачивается, и команда сборки
таблиц.

**Files:** Create `scripts/sony_reference.py`; generated `sony_reference.json` в двух местах —
`Sources/ZVCore/Sony/SonyReferenceData.swift` (JSON целиком в сырой многострочной строке `#"""…"""#`) и
`android/app/src/main/resources/sony_reference.json` (Java-ресурс: попадает в APK и читается в JVM-тестах);
hand-written загрузчики `Sources/ZVCore/Sony/SonyReferenceTables.swift`,
`android/app/src/main/java/local/zve10viewer/sony/SonyReferenceTables.kt`; Modify `docs/protocol.md` (раздел «Другие
камеры Sony»: откуда PDF — https://github.com/jakkuh/sony-alpha-python/blob/main/docs/Camera%20Control%20PTP%203%20Reference.pdf,
`pdftotext -layout`, запуск генератора); Test `Tests/ZVCoreTests/SonyReferenceTablesTests.swift`,
`android/app/src/test/java/local/zve10viewer/SonyReferenceTablesTest.kt`

**Interfaces (Produces):**
```swift
public enum SonyReferenceTables {                                     // JSON разбирается один раз, лениво
    public static var propertyNames: [UInt16: String] { get }       // все свойства справочника
    public static var values: [UInt16: [Int64: String]] { get }     // все списки значений
    public static func valueName(_ code: UInt16, _ value: Int64) -> String?
}
```
```kotlin
object SonyReferenceTables {                                          // ресурс /sony_reference.json, лениво
    val propertyNames: Map<Int, String>
    val values: Map<Int, Map<Long, String>>
    fun valueName(code: Int, value: Long): String?
}
```

Формат JSON (ключи отсортированы, отступ 1 пробел, чтобы правки были видны в diff):
`{"source": "Camera Control PTP 3 Reference", "properties": {"0xD0FA": {"name": "Creative Look", "values": {"0x0001":
"ST(Standard)", …}}, …}}`.

- [ ] **Скрипт.** `scripts/sony_reference.py <ptp3.txt>` разбирает разделы свойств текста `pdftotext -layout`:
  - строка `PropertyCode 1 2 UINT16 0x....`;
  - название — строка перед ближайшим выше «Summary»;
  - значения — пары `0x.. Описание` после «Value Description» до следующего «Summary», двойные пробелы в описании
    схлопываются.

  Пишет JSON в оба места, Swift-файл с шапкой «Создано scripts/sony_reference.py из Camera Control PTP 3 Reference —
  правьте через скрипт или JSON в обоих местах». Локальная копия текста — в
  `/Users/scrypto/dev/sonyZvE10Viewr/research/sony-reference/ptp3.txt` (папка не в git); путь передаётся аргументом.
- [ ] **Тесты (оба):**
  - `propertyNames[0xD0FA] == "Creative Look"`;
  - `valueName(0xD241, 0x0B) == "XAVC HS 4K"`;
  - `valueName(0xD0E1, 0x0303) == "S-Log3"`;
  - `valueName(0xD0E9, 0x0008) == "S-Gamut3.Cine"`;
  - `valueName(0xD253, 0x05) == "HEIF"`;
  - `propertyNames.count >= 700`, `values.count >= 500`.

  Только Swift: `androidCopyMatches` — JSON из `SonyReferenceData` побайтно совпадает с
  `android/app/src/main/resources/sony_reference.json` (путь от `#filePath`).
- [ ] **Прогон.** Запустить генератор, затем `swift test --filter SonyReferenceTables` и `./gradlew
  :app:testDebugUnitTest --tests '*SonyReferenceTablesTest'` — зелёные. `swift build` — время сборки ZVCore не выросло
  больше чем на 10 с (замерить до и после).
- [ ] Commit `feat(core): Sony reference tables for property names and values`.

## Task 4: C1. ZVCore: FrameOrientation и новые инструменты «Монитора»

**Files:** Create `Sources/ZVCore/View/FrameOrientation.swift`; Modify `Sources/ZVCore/Monitor/MonitorAssist.swift`;
Test `Tests/ZVCoreTests/FrameOrientationTests.swift`, `Tests/ZVCoreTests/MonitorAssistTests.swift`

**Interfaces (Produces):**
```swift
public enum FrameOrientation: String, Codable, CaseIterable, Sendable {
    case off, mirror, rotate180 = "180", mirror180
    public var flipsX: Bool { get }                  // mirror, rotate180
    public var flipsY: Bool { get }                  // rotate180, mirror180
    public func toCamera(_ p: CGPoint) -> CGPoint    // доли экрана → доли камеры; отображение — инволюция
    public func fromCamera(_ p: CGPoint) -> CGPoint
    public func fromCamera(_ r: CGRect) -> CGRect
    public var title: String                         // «Как есть», «Зеркально», «Вверх ногами», «Зеркально + 180°»
    public var label: String                         // «—», «Зеркало», «180°», «Зеркало 180°»
}
extension MonitorAssist {
    public enum LUTMode: String, Codable, CaseIterable, Sendable { case off, auto, slog3cine, slog3, slog2, hlg, file }
    // новые поля: lut: LUTMode = .off, lutFile: String? = nil, lutScopes = true, vectorscope = false,
    // vectorscopeZoom = 1, probe = false, orientation: FrameOrientation = .off
    public var needsPixels: Bool { get }             // needsRaster || vectorscope || probe
    public var transformsPicture: Bool { get }       // lut != .off || orientation != .off
}
// MonitorTool: + lut, vectorscope, probe, orientation; порядок — Global Constraints
```

- [ ] **Тесты `FrameOrientationTests`:**
  - для всех вариантов `toCamera(fromCamera(p)) == p`;
  - `mirror`: (0,3; 0,5) → (0,7; 0,5);
  - `180`: (0,2; 0,1) → (0,8; 0,9);
  - `mirror180`: (0,2; 0,1) → (0,2; 0,9);
  - `fromCamera(CGRect(0,1; 0,2; 0,3; 0,1))` при `mirror` = (0,6; 0,2; 0,3; 0,1), при `180` = (0,6; 0,7; 0,3; 0,1);
  - подписи — из Global Constraints.
- [ ] **Тесты `MonitorAssistTests`:**
  - `MonitorTool.allCases.map(\.rawValue)` — ровно порядок из Global Constraints;
  - `set(.lut, "auto")` → `lut == .auto`, `value(.lut) == "auto"`;
  - `set(.orientation, "180")`;
  - `toggle(.lut)` после `auto` → `off`, ещё раз → `auto` (память `lastOn`);
  - `turnOffAll()` выключает lut, vectorscope, probe и orientation, но `lutScopes` и `vectorscopeZoom` не трогает;
  - JSON без новых ключей читается со значениями по умолчанию;
  - JSON туда-обратно с новыми ключами;
  - `label(.lut)` при `file` — `lutFile`.
- [ ] **Реализация.** `swift test --filter "FrameOrientation|MonitorAssist"` — сначала падают; реализовать; зелёные;
  весь `swift test` зелёный.
- [ ] Commit `feat(core): frame orientation and new monitor tools`.

## Task 5: C2. ZVCore: LUT

**Files:** Create `Sources/ZVCore/Monitor/SonyCurves.swift`, `Sources/ZVCore/Monitor/ColorLUT.swift`,
`Sources/ZVCore/Monitor/BuiltinLUT.swift`, `Sources/ZVCore/Monitor/CubeFile.swift`, `Sources/ZVCore/Monitor/LUTChoice.swift`;
Test `Tests/ZVCoreTests/LUTTests.swift`, `Tests/ZVCoreTests/CubeFileTests.swift`

**Interfaces (Produces):**
```swift
public enum SonyCurves {
    public static func slog3ToLinear(_ v: Double) -> Double     // v = код/1023
    public static func slog2ToLinear(_ v: Double) -> Double
    public static func hlgToLinear(_ v: Double) -> Double       // L, диффузный белый = 1
    public static func shoulder(_ l: Double) -> Double          // колено 0,6
    public static func bt709(_ l: Double) -> Double             // OETF, обрезка 0…1
    public static func matrixTo709(primaries: [(x: Double, y: Double)]) -> [[Double]]   // D65
    public static let sGamut3Cine, sGamut3, bt2020: [(x: Double, y: Double)]
}
public struct ColorLUT: Equatable, Sendable {
    public let size: Int
    public let values: [Float]                  // size³ × 3, красный быстрее всех, выход 0…1
    public let domainMin: [Float], domainMax: [Float]
    public init(size: Int, values: [Float], domainMin: [Float] = [0, 0, 0], domainMax: [Float] = [1, 1, 1])
    public func apply(_ r: Float, _ g: Float, _ b: Float) -> (Float, Float, Float)   // трилинейно, вход по домену
    public func rgbaFloat32() -> Data            // size³ × 4 Float32 для CIColorCube, альфа 1
    public static func identity(size: Int) -> ColorLUT
}
public enum BuiltinLUT: String, CaseIterable, Sendable {
    case slog3cine, slog3, slog2, hlg
    public var label: String                      // «S-Log3 Cine», «S-Log3», «S-Log2», «HLG»
    public func transform(_ r: Double, _ g: Double, _ b: Double) -> (Double, Double, Double)   // точный путь
    public func make(size: Int = 33) -> ColorLUT
}
public enum CubeFile {
    public enum Failure: Error, Equatable { case notThreeD, badSize(Int), count(found: Int, expected: Int), number(line: Int) }
    public static func parse(_ text: String) throws -> ColorLUT
    public static func message(_ failure: Failure) -> String
}
public enum LUTChoice {
    public static func resolve(_ mode: MonitorAssist.LUTMode, pictureProfile: Int64?, ppGamma: Int64?, colorMode: Int64?) -> BuiltinLUT?
    public static func autoLabel(_ resolved: BuiltinLUT?) -> String   // «Авто: S-Log3», «Авто: нет»
    public static let cameraConverts: Bool        // false до проверки в R1; true — «Авто» ничего не включает
}
```

- [ ] **Тесты `LUTTests`** (допуск 0,005, где не сказано иное):
  - **Кривые:**
    - `slog3ToLinear(420/1023) ≈ 0,18`;
    - `slog3ToLinear(95/1023) ≈ 0` (±0,0005);
    - `slog3ToLinear(598/1023) ≈ 0,90` (±0,01);
    - `slog2ToLinear(347/1023) ≈ 0,18` (±0,01);
    - `hlgToLinear(721/1023) ≈ 1,0` (±0,01).
  - **Тон:**
    - `shoulder(0,5) == 0,5`;
    - `shoulder(0,9) ≈ 0,811`;
    - `shoulder` непрерывна и монотонна на 0…40;
    - `bt709(0,18) ≈ 0,409`.
  - **Матрицы:**
    - первая строка S-Gamut3.Cine → 709 ≈ (1,626947; −0,540139; −0,086809) ±1e-5;
    - S-Gamut3 → (1,877915; −0,794169; −0,083746);
    - BT.2020 → (1,660491; −0,587641; −0,072850);
    - суммы строк = 1 ±1e-9.
  - **Встроенные — контрольные точки спеки:**
    - серый `slog3cine.transform(420/1023 ×3)` ≈ 0,409 по всем каналам;
    - `hlg.transform(721/1023 ×3)` ≈ 0,924 (±0,01).
  - **Куб:**
    - `make(size: 33).apply` отличается от `transform` не больше 0,01 на 1000 случайных точках (seed 1);
    - `identity(size: 2).apply(0,3; 0,6; 0,9) == (0,3; 0,6; 0,9)`;
    - `rgbaFloat32().count == 33³·16`.
  - **Авто:**
    - `resolve(.auto, pictureProfile: 8, ppGamma: nil, colorMode: nil) == .slog3cine`;
    - PP9 → `.slog3`, PP7 → `.slog2`, PP10 → `.hlg`, PP1 → nil;
    - `ppGamma: 0x0303, colorMode: 0x0009` → `.slog3`;
    - `0x0303` + `0x0008` → `.slog3cine`;
    - `0x0302` → `.slog2`;
    - `0x0403` → `.hlg`;
    - `0x0201` → nil;
    - `ppGamma` важнее `pictureProfile`;
    - `.slog2` при любом профиле → `.slog2`;
    - `.file` → nil.
- [ ] **Тесты `CubeFileTests`:**
  - **Правильные файлы:**
    - куб 2³ с `TITLE "x"`, комментариями и пустыми строками разбирается;
    - `DOMAIN_MIN 0 0 0` / `DOMAIN_MAX 2 2 2` → `apply(1,1,1)` берёт середину;
    - CRLF и BOM не мешают;
    - комментарий после чисел в строке отбрасывается.
  - **Ошибки:**
    - `LUT_1D_SIZE 1024` → `.notThreeD`;
    - `LUT_3D_SIZE 66` → `.badSize(66)`;
    - 7 строк данных при размере 2 → `.count(found: 7, expected: 8)`;
    - «abc» в данных → `.number(line:)`.
  - **Сообщения:** `message(.count(found: 35936, expected: 35937)) == "В файле 35 936 строк данных, а нужно 35 937"`;
    `message(.notThreeD) == "Нужен 3D LUT в формате .cube"`.
  - **Скорость:** куб 65³ из тождественных значений разбирается меньше чем за 1 с в release (замер под
    `ZV_BENCH=1`, как `AssistBenchmark`).
- [ ] **Реализация.** `swift test --filter "LUTTests|CubeFile"` — сначала падают; реализовать; зелёные.
- [ ] Commit `feat(core): Sony log curves, built-in LUTs, .cube parser, auto choice`.

## Task 6: C3. ZVCore: вектороскоп и пипетка

**Files:** Create `Sources/ZVCore/Monitor/Vectorscope.swift`, `Sources/ZVCore/Monitor/ColorProbe.swift`;
Test `Tests/ZVCoreTests/VectorscopeTests.swift`, `Tests/ZVCoreTests/ColorProbeTests.swift`

**Interfaces (Produces):**
```swift
public enum Vectorscope {
    public static let bins = 128
    public static let skinAngle = 123.0
    public static func chroma(r: Double, g: Double, b: Double) -> (cb: Double, cr: Double)    // вход 0…1
    public static func bin(cb: Double, cr: Double, zoom: Int) -> (x: Int, y: Int)?           // nil — за краем; y = 0 вверху (Cr max)
    public static func counts(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, zoom: Int) -> [UInt32]   // bins²
    public static func image(counts: [UInt32]) -> [UInt8]                                    // RGBA bins²
    public static func peak(counts: [UInt32], zoom: Int) -> (cb: Double, cr: Double)?        // центр самой полной корзины
    public static let targets: [(name: String, cb: Double, cr: Double)]                      // R Mg B Cy G Yl, полосы 75 %
}
public enum ColorProbe {
    public struct Reading: Equatable, Sendable {
        public let percent: Int, r: Int, g: Int, b: Int
        public let zone: AssistRaster.FalseColorBand?
        public var lines: (String, String) { get }    // «47 % · кожа», «R 182 · G 140 · B 121»; без зоны — «47 %»
    }
    public static func read(rgba: [UInt8], width: Int, height: Int, bytesPerRow: Int, at point: CGPoint, white: Int) -> Reading
}
```

- [ ] **Тесты `VectorscopeTests`:**
  - **Плоскость:**
    - `chroma(0,75; 0; 0)` ≈ (−0,0859; 0,3750) ±1e-3;
    - серый (0,5; 0,5; 0,5) → (0; 0).
  - **Корзины:**
    - кадр 64×36 сплошного красного 75 % (191,0,0) → максимум в корзине `bin(targets R, zoom: 1)` ±1;
    - серый кадр → центральная корзина (64; 64) ±1;
    - при zoom 2 расстояние корзины красного 50 % от центра вдвое больше, чем при zoom 1 (±1 корзина);
    - красный 100 % при zoom 2 → не в счётчиках (за краем);
    - сумма `counts` для кадра 64×36 = 32·18.
  - **Картинка:** у пустой корзины альфа 0, у самой полной — 255.
  - **Пик:** `peak` для сплошного красного 75 % ≈ `targets` R ±0,01; для пустых счётчиков — nil.
- [ ] **Тесты `ColorProbeTests`:**
  - **Среднее 5×5:** кадр 16×16, в квадрате 5×5 вокруг (8; 8) значения 100 и 200 вперемешку → среднее по
    формуле ±1.
  - **Процент:** яркость 117 при `white: 234` → 50 %; 117 при `white: 255` → 46 %.
  - **Зона:** серый 41 % → `.middleGray`, `lines.0 == "41 % · 18 % серого"`; 20 % → `zone == nil`, `lines.0 == "20 %"`.
  - **Край:** точка (1; 1) на кадре 1024×1024 — квадрат обрезается краем, без выхода за массив.
- [ ] **Реализация.** `swift test --filter "Vectorscope|ColorProbe"` — сначала падают; реализовать; зелёные.
- [ ] Commit `feat(core): vectorscope and color probe`.

## Task 7: C4. ZVCore: перевод фокуса

**Files:** Create `Sources/ZVCore/Monitor/FocusPull.swift`; Test `Tests/ZVCoreTests/FocusPullTests.swift`

**Interfaces (Produces):**
```swift
public struct FocusPull: Sendable {
    public enum Mode: Equatable, Sendable { case absolute, steps }     // absolute — 0…65535, steps — 0…100
    public enum Action: Equatable, Sendable { case write(UInt16), nearFar(Int64), wait, finish(Outcome) }
    public enum Outcome: Equatable, Sendable { case reached, stuck, timedOut, cancelled }
    public static let durations: [Double] = [0, 1, 2, 4, 8]
    public static let tick = 0.1
    public init(mode: Mode, from start: Double, to target: Double, duration: Double, gain: Double = 3.5)
    public private(set) var gain: Double
    public var progress: Double { get }                                 // 0…1 по пройденному пути
    public static func ease(_ t: Double) -> Double                      // 3t² − 2t³, t обрезается в 0…1
    public func desired(at elapsed: Double) -> Double
    public mutating func next(elapsed: Double, position: Double) -> Action   // зовётся по тику и после ответа камеры
    public mutating func cancel() -> Action                             // .finish(.cancelled)
    public static func caption(_ outcome: Outcome, mark: String) -> String?   // «Фокус у метки A», «Дальше камера не двигает», «Не успел за 3 с»; cancelled → nil
}
public struct FocusMarks: Equatable, Sendable {
    public var a: Double?, b: Double?                                   // в единицах режима
    public var duration: Double = 2
    public mutating func cycleDuration()                                // 0 → 1 → 2 → 4 → 8 → 0
}
```

Правила `next` — спека §5. Шаги: `nearFar(±n)` — только когда прошлая команда уже сдвинула или не сдвинула
положение (`next` получает свежее положение после каждой команды); `wait` — если ошибка ≤ 1 до конца времени.

- [ ] **Тесты** — лёгкая модель объектива в тесте: положение += `round(n·g)`, у края в пределах `d` шаги |n| ≤ 3 не
  двигают.
  - **Абсолютный режим:**
    - `absoluteFollowsCurve`: 0 → 65535 за 2 с: при 0,5 с `write(10240 ± 1)`, при 1,0 с `write(32768 ± 1)`;
    - `absoluteZeroDurationWritesTarget`;
    - `absoluteReachedWithinHalfPercent`: положение 65200 при цели 65535 после конца → `.finish(.reached)`;
    - `absoluteTimesOut`: при 4,1 с без достижения → `.finish(.timedOut)`.
  - **Шаги:**
    - `stepsConverge` для g = 2; 3,5; 5 (модель округляет `n·g` от нуля, как поддельная камера): 10 → 90 за 2 с,
      тики 0,1 с → `.reached` до 5 с, |итог − 90| ≤ `max(1, g/2)` + 0,5;
    - `stepsNoDither`: g = 4, цель между доступными положениями (старт 10, цель 52) → `.reached` не позже двух
      команд ±1 после того, как цель перескочена, а не `.timedOut`;
    - `stepsMarksSweep`: g = 3,5, все пары меток 5…95 шагом 10, время 2 с → ни одного `.timedOut`;
    - `stepsSignCloser`: 90 → 10 — все команды отрицательные;
    - `stepsBounds`: |n| ≤ 7; при k = 3,5 и ошибке 1,5 команды нет (`wait`), при ошибке 2,5 — |n| ≥ 1;
    - `stepsGainAdapts`: g = 5, после пяти команд |n| ≥ 3 `gain` в 4,5…5,5;
    - `stepsStuck`: d = 5, цель 100 из 80 → `.stuck`;
    - `stepsTimeout`: g = 0,2, 0 → 100 за 1 с → `.timedOut` к 4,1 с.
  - **Прочее:**
    - `cancelAnyTime` → `.finish(.cancelled)`;
    - `progress` от 0 до 1;
    - `caption`;
    - `cycleDuration` по кругу.
- [ ] **Реализация.** `swift test --filter FocusPull` — сначала падают; реализовать; зелёные.
- [ ] Commit `feat(core): focus pull between marks`.

## Task 8: C5. ZVCore: другие камеры Sony

**Files:** Create `Sources/ZVCore/Sony/DisplayStrings.swift`, `Sources/ZVCore/Sony/LensInformation.swift`;
Modify `Sources/ZVCore/Sony/SonyCodes.swift`, `Sources/ZVCore/Sony/PropertyNames.swift`,
`Sources/ZVCore/Settings/SettingID.swift`, `Sources/ZVCore/Settings/SettingCatalog.swift`,
`Sources/ZVCore/Settings/ValueFormatter.swift`, `Sources/ZVCore/Sony/DeviceInfo.swift` (список операций);
Create fixtures `Tests/ZVCoreTests/Fixtures/display_strings.bin`, `lens_information.bin`
(и копии в `android/app/src/test/resources/fixtures/`); Test `Tests/ZVCoreTests/OtherCamerasTests.swift`

**Interfaces (Produces):**
```swift
// SonyOp: getDisplayStringList 0x9215, getVendorCodeVersion 0x9216, getLensInformation 0x9223
// SonyProp: creativeLook 0xD0FA, ppGamma 0xD0E1, ppColorMode 0xD0E9, focusPositionSetting 0xE042,
//           focusPositionCurrent 0xE043, lensInfoEnabled 0xE086
// SonyEvent: displayListChanged 0xC20F, focusPositionResult 0xC218, lensInfoChanged 0xC21B
public struct DisplayStrings: Equatable, Sendable {
    public static let creativeLookType: UInt32 = 0x13
    public var lists: [UInt32: [Int64: String]]
    public static func parse(_ data: Data) throws -> DisplayStrings      // набор с заголовком смещение/размер
    public mutating func merge(_ other: DisplayStrings)                  // по типу заменяет
}
public struct LensInformation: Equatable, Sendable {
    public let pairs: [(position: Double, meters: Double)]               // по возрастанию положения
    public let absoluteScale: Bool                                       // max положения > 100
    public let tableCount: Int                                           // больше 1 — расстояний не показываем
    public static func parse(_ data: Data) throws -> LensInformation
    public func meters(atPosition p: Double) -> Double?                  // интерполяция; nil — пусто или таблиц > 1
    public static func label(_ meters: Double) -> String                 // «0,35 м», «1,2 м», «12 м», «∞» (≥ 100)
}
// DeviceInfo: public let operations: Set<UInt16>; func supports(_ op: UInt16) -> Bool
// SettingID: + .creativeLook (SonyProp.creativeLook, «Creative Look», коротко «Look»)
// SettingCatalog: static func resolved(_ id: SettingID, available: Set<UInt16>) -> SettingID
//   (.creativeStyle → .creativeLook, если нет 0xD240 и есть 0xD0FA; иначе id)
// ValueFormatter: label(_ id: SettingID, _ value: Int64, names: [Int64: String]? = nil) -> String
//   порядок: names (подписи камеры) → русские таблицы и спец. ветки → SonyReferenceTables → "#N"
// PropertyNames: неизвестный код → SonyReferenceTables.propertyNames
```

- [ ] **Фикстуры.** Скрипт `Tests/ZVCoreTests/Fixtures/make_other_cameras.py` (stdlib) пишет
  `display_strings.bin` и `lens_information.bin` по разметке спеки §7.3–7.4 с теми же данными, что F2 (заголовок
  смещение = 8, размер = длина тела). Обе копии одинаковы (`shasum`).
- [ ] **Тесты `OtherCamerasTests`:**
  - **Подписи камеры:**
    - `DisplayStrings.parse(display_strings.bin).lists[0x13]![0x0006] == "FL"`;
    - `lists[0x15]!.count == 2`;
    - пустой набор (`ListTypeNum = 0`) → пусто;
    - обрезанный набор → ошибка.
  - **Объектив:**
    - `LensInformation.parse(lens_information.bin)` — 12 пар, `absoluteScale == true`;
    - `meters(atPosition: 5958) == 0,30`;
    - набор с двумя таблицами: `tableCount == 2`, `meters(atPosition:)` → nil (расстояний нет);
    - между 0 и 5958 — интерполяция;
    - `label`: 0,35 → «0,35 м», 1,2 → «1,2 м», 12 → «12 м», 1 000 000 → «∞».
  - **Настройки:**
    - `resolved(.creativeStyle, available: [0xD0FA]) == .creativeLook`;
    - с `0xD240` → `.creativeStyle`.
  - **Подписи значений:**
    - `label(.creativeLook, 6, names: [6: "FL"]) == "FL"`;
    - без names → «FL(Film)» из таблиц;
    - `label(.movieFileFormat, 0x0B) == "XAVC HS 4K"`;
    - `label(.focusArea, 0x0106)` — английское имя из таблиц, не «#…»;
    - неизвестное значение → «#12345»;
    - старые русские подписи не изменились: существующие тесты `ValueFormatter` зелёные.
  - **Операции:** `DeviceInfo` из `deviceinfo.bin` → `supports(0x9215) == false`, `supports(0x9209) == true`.
- [ ] **Реализация.** `swift test --filter OtherCameras` — сначала падают; реализовать; весь `swift test` зелёный.
- [ ] Commit `feat(core): display strings, lens information, Creative Look, reference value names`.

## Task 9: K1. Android: FrameOrientation и новые инструменты «Монитора»

Повторяет Task 4 (C1) на Kotlin — те же имена, числа, подписи и случаи тестов; бриф Task 4 прилагается к поручению.

**Files:** Create `android/app/src/main/java/local/zve10viewer/monitor/FrameOrientation.kt`; Modify
`android/app/src/main/java/local/zve10viewer/monitor/MonitorAssist.kt` (`toJson`/`fromJson` пишут и читают новые
ключи, `MonitorTool` — новый порядок); Test `android/app/src/test/java/local/zve10viewer/FrameOrientationTest.kt`,
`MonitorAssistTest.kt`

- [ ] Тесты из Task 4 на JUnit 4 — падают.
- [ ] Реализация; `cd android && JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew
  :app:testDebugUnitTest --tests '*FrameOrientationTest' --tests '*MonitorAssistTest'` — зелёные; весь
  `:app:testDebugUnitTest` зелёный.
- [ ] Commit `feat(android): frame orientation and new monitor tools`.

## Task 10: K2. Android: LUT и проход картинки

Повторяет Task 5 (C2) на Kotlin; бриф Task 5 прилагается. Плюс класс прохода картинки.

**Files:** Create `monitor/SonyCurves.kt`, `monitor/ColorLut.kt`, `monitor/BuiltinLut.kt`, `monitor/CubeFile.kt`,
`monitor/LutChoice.kt`, `monitor/PictureTransform.kt` (в `android/app/src/main/java/local/zve10viewer/`);
Test `LutTest.kt`, `CubeFileTest.kt`, `PictureTransformTest.kt` (в `android/app/src/test/java/local/zve10viewer/`)

**Interfaces (Produces):**
```kotlin
class PictureTransform(private val bands: Int = 4) {
    // src — ARGB кадра; display — повёрнутый и с LUT; analysis — повёрнутый без LUT (null — не нужен)
    fun apply(src: IntArray, width: Int, height: Int, orientation: FrameOrientation, lut: ColorLut?,
              display: IntArray, analysis: IntArray?)
}
```
Полосы строк считаются параллельно на пуле из `bands` потоков (`java.util.concurrent`), LUT — трилинейно в целых
числах (детали — на усмотрение).

- [ ] Тесты Task 5 (`LutTest`, `CubeFileTest`) и `PictureTransformTest`:
  - кадр 3×2 с уникальными точками → `mirror`, `180`, `mirror180` дают ожидаемую перестановку; `off` без LUT —
    копия;
  - тождественный LUT 2³ меняет каналы не больше чем на 1;
  - смена размера 1024×576 → 640×360 между вызовами — без исключений;
  - новый LUT между вызовами действует со следующего вызова;
  - при `ZV_BENCH=1` печатает мс на 1024×576 с `slog3cine` (цель спеки — не больше 12 мс на планшете; здесь —
    только замер на JVM).
- [ ] Реализация; `./gradlew :app:testDebugUnitTest --tests '*LutTest' --tests '*CubeFileTest' --tests
  '*PictureTransformTest'` — зелёные; весь набор зелёный.
- [ ] Commit `feat(android): Sony log curves, built-in LUTs, .cube parser, picture transform`.

## Task 11: K3. Android: вектороскоп и пипетка

Повторяет Task 6 (C3) на Kotlin; бриф Task 6 прилагается. Кадр — `IntArray` ARGB (как в `AssistSampler`), а не
RGBA-байты: `counts(argb: IntArray, width: Int, height: Int, zoom: Int)`, `read(argb: IntArray, width, height, x:
Double, y: Double, white: Int)`; `peak` и `image` — как на Mac.

**Files:** Create `monitor/Vectorscope.kt`, `monitor/ColorProbe.kt`; Test `VectorscopeTest.kt`, `ColorProbeTest.kt`

- [ ] Тесты Task 6 — падают; реализация; `--tests '*VectorscopeTest' --tests '*ColorProbeTest'` — зелёные; весь
  набор зелёный.
- [ ] Commit `feat(android): vectorscope and color probe`.

## Task 12: K4. Android: перевод фокуса

Повторяет Task 7 (C4) на Kotlin; бриф Task 7 прилагается.

**Files:** Create `monitor/FocusPull.kt`; Test `FocusPullTest.kt`

- [ ] Тесты Task 7 — падают; реализация; `--tests '*FocusPullTest'` — зелёные; весь набор зелёный.
- [ ] Commit `feat(android): focus pull between marks`.

## Task 13: K5. Android: другие камеры Sony

Повторяет Task 8 (C5) на Kotlin; бриф Task 8 прилагается. Фикстуры — те же файлы, что на Mac
(`android/app/src/test/resources/fixtures/display_strings.bin`, `lens_information.bin`, побайтно одинаковые).

**Files:** Create `sony/DisplayStrings.kt`, `sony/LensInformation.kt`; Modify `sony/Sony.kt` (коды, `DeviceInfo.supports`),
`settings/Settings.kt` (`SettingID.creativeLook`, `SettingCatalog.resolved`), `settings/ValueFormatter.kt`
(`names`, таблицы справочника), `debug/PropertyNames.kt`; Test `OtherCamerasTest.kt`

- [ ] Тесты Task 8 — падают; реализация; `--tests '*OtherCamerasTest'` и весь набор — зелёные.
- [ ] Commit `feat(android): display strings, lens information, Creative Look, reference value names`.

## G. Перед встраиванием

- [ ] **Соседняя ветка.** Удаление снимков уже в `main` (`de3844c`, 0.8.5), наша ветка начата от него. Проверить
  `git -C /Users/scrypto/dev/sonyZvE10Viewr log --oneline main -5` и `git -C … status` в
  `.claude/worktrees/monitor-tools-624d59`: нет ли там новой незакоммиченной работы в `LiveScreen.swift`,
  `CameraController.swift`, `AutomationServer.swift`, `Screen.kt`. Если есть — продолжать этап 1, её файлы не
  трогать.
- [ ] **Перебазирование.** Если `main` ушёл вперёд — `git rebase main`; `swift test` и `./gradlew
  :app:testDebugUnitTest` — зелёные. Коммита нет.

## Task 14: M1. Mac: путь картинки

**Files:** Create `Sources/ZVViewer/PictureTransform.swift`; Modify `Sources/ZVViewer/CameraController.swift`
(поток декодирования ≈ 2028–2037, `show`, `histogramLevels`), `Sources/ZVViewer/MonitorAssistViews.swift`
(`AssistInput.State`, `FrameTools`), `Sources/ZVViewer/AutomationServer.swift` (поля `picture`, `vectorscope`,
`probe` в `/state` — таблица в M6)

**Interfaces:**
- Consumes: `FrameOrientation`, `ColorLUT`, `BuiltinLUT`, `LUTChoice`, `Vectorscope`, `ColorProbe`, `MonitorAssist`
  (C1–C3).
- Produces:
  ```swift
  final class PictureTransform: @unchecked Sendable {       // один CIContext без управления цветом
      func apply(_ image: CGImage, orientation: FrameOrientation, lut: ColorLUT?, needPlain: Bool) -> (display: CGImage, plain: CGImage?)
  }
  // AssistInput.State: + orientation, lut: ColorLUT?, lutScopes: Bool, vectorscopeZoom: Int?, probe: CGPoint?
  // FrameTools.Result: + vectorscope: [UInt8]? (RGBA 128²), probe: ColorProbe.Reading?, meanLuma: Double
  // CameraController: lutFor(main:) → ColorLUT? (кэш по ключу «встроенный/файл»), resolvedLUT: BuiltinLUT?
  ```

**Кэш LUT.** Куб строится один раз на ключ:
- встроенный — по имени;
- свой — по `lutFile` + дате файла.

**Поток кадра:**
1. Декодирование.
2. Если `transformsPicture` — `PictureTransform.apply`.
3. Уровни гистограммы — по кадру помощи: `display`, если `lutScopes` или LUT нет; иначе `plain`.
4. `FrameTools.analyze` по тому же кадру, белый — 255, если помощь «с LUT» и LUT включён.
5. `show(display, …)`.

`lastJPEG` не меняется.

- [ ] **Проверки:**
  - `swift build` чистый, `swift test` зелёный;
  - `scripts/build-app.sh`, поддельная камера на PP8 (`request {setting: "pictureProfile", value: 8}`):
    - `setAssist {name: "lut", value: "auto"}` → `/state.picture.lut.resolved == "slog3cine"`;
    - `picture.meanLuma` выше, чем при `lut off`;
    - `picture.whiteLevel == 255` при `lutScopes` и 234 без LUT;
  - `/state.fps` с LUT + зеркалом — не ниже 90 % от базы (порог проверяет и M6).
- [ ] Commit `feat(mac): picture transform after decode — orientation and LUT`.

## Task 15: M2. Mac: меню «Монитор», свои LUT, вектороскоп, клавиши

**Files:** Modify `Sources/ZVViewer/MonitorAssistViews.swift` (`AssistMenu` 7 колонок, полоса вариантов LUT),
`Sources/ZVViewer/TopBar.swift` (`FloatingVectorscope`), `Sources/ZVViewer/ScreenState.swift`
(`vectorscopePlacement`), `Sources/ZVViewer/LiveScreen.swift` (клавиши L/V/I, `.dropDestination` для `.cube`);
Create `Sources/ZVViewer/LUTStore.swift`, `Sources/ZVViewer/VectorscopeView.swift`

**Interfaces (Produces):**
```swift
@MainActor final class LUTStore: ObservableObject {
    @Published private(set) var names: [String]                        // по алфавиту
    func importFile(_ url: URL) throws -> String                        // проверка CubeFile.parse, копия, ≤ 12
    func delete(_ name: String)
    func lut(named: String) -> ColorLUT?
}
```

- [ ] **Сетка** — 7 колонок в порядке Global Constraints. Подписи плиток и вариантов — Global Constraints.
- [ ] **Полоса LUT:**
  - варианты;
  - переключатель «Помощь — по картинке с LUT» (`lutScopes`);
  - при «Свой» — список имён, «Добавить LUT…» (`NSOpenPanel`, тип `.cube`) и «Удалить»;
  - ошибки разбора — строкой состояния через `CubeFile.message`;
  - у «Авто» значение на плитке — `LUTChoice.autoLabel`.
- [ ] **Вектороскоп** — плавающая панель 176×176 с сеткой из спеки §3:
  - картинка `FrameTools.Result.vectorscope` растянута на круг;
  - щелчок переключает ×1/×2 (`vectorscopeZoom`), в углу — «×2»;
  - место по умолчанию — слева сверху (y = 64), хранится в `vectorscopePlacement`;
  - прячется вместе с гистограммой.
- [ ] **Клавиши** L, V, I (и та же физическая клавиша в русской раскладке): LUT, вектороскоп, пипетка — как Z/P/C.
- [ ] **Бросок на окно.** `.cube` → `LUTStore.importFile`, `lut = file`, `lutFile = имя`; картинки — как раньше.
- [ ] **Проверка** — e2e M6 (часть «меню»); `swift build` чистый.
- [ ] Commit `feat(mac): monitor menu with LUT, vectorscope, probe and picture tiles`.

## Task 16: M3. Mac: зеркало в координатах и пипетка на экране

**Files:** Modify `Sources/ZVViewer/LiveScreen.swift` (`framePoint`, касание), `Sources/ZVViewer/CameraAssistViews.swift`
(`FocusFramesLayer`), `Sources/ZVViewer/ZoomedVideo.swift` (эталон с поворотом), `Sources/ZVViewer/CameraController.swift`
(`touchFocus` → `toCamera`, `touchMark` в долях камеры); Create `Sources/ZVViewer/ProbeView.swift`

- [ ] **Касание:**
  - `framePoint` даёт экранные доли;
  - при `probe` касание ставит `probe`-точку (`ScreenState.probePoint`, в `AssistInput`) и не ставит фокус;
  - иначе — `controller.touchFocus(at: orientation.toCamera(point))`.
- [ ] **Рамки и эталон.** Рамки камеры и `touchMark` рисуются через `fromCamera(rect)`. Эталон рисуется с
  `scaleEffect(x: flipsX ? −1 : 1, y: flipsY ? −1 : 1)` вокруг своего прямоугольника.
- [ ] **`ProbeView`:** метка 20 pt с перекрестием, табличка `Reading.lines`, у краёв уходит внутрь; перетаскивание
  двигает точку; подписи обновляются не чаще 5 раз в секунду.
- [ ] **Справка плитки «Пипетка»:** «Касание кадра ставит пипетку; пока она включена, фокус касанием не ставится».
- [ ] **Проверка** — e2e M6 (зеркало + фокус, пипетка); `swift build` чистый.
- [ ] Commit `feat(mac): mirrored picture keeps touch focus and focus frames in place; probe on screen`.

## Task 17: M4. Mac: метки фокуса

**Files:** Modify `Sources/ZVViewer/CameraAssistViews.swift` (`FocusScale`: строка `[A] [B] [⏱]`, треугольники на
линейке, подписи расстояний), `Sources/ZVViewer/CameraController.swift` (метки по камере, цикл перевода)

**Interfaces:**
- Consumes: `FocusPull`, `FocusMarks` (C4). Расстояния у меток добавляет Task 18 (M5) вместе со шкалой: данные объектива появляются в контроллере там.
- Produces:
  ```swift
  func focusMarkTapped(_ mark: Character)     // «A»/«B»: пусто → запомнить; задано → ехать; едет → стоп
  func focusMarkSave(_ mark: Character), focusMarkClear(_ mark: Character), focusPullCycleTime(), focusPullStop()
  var focusMarks: FocusMarks, focusPullProgress: Double?, focusPullCaption: String?
  ```

**Цикл перевода:**
- **Режим.** `.absolute`, если камера перечисляет `0xE042` и он открыт; иначе `.steps`.
- **Тик.** `Task` с тиком 100 мс зовёт `next(elapsed:position:)`:
  - `write` → `setProperty(0xE042)`;
  - `nearFar` → `session.control(0xD2D1, n, int16)`, затем `session.refresh()` с ожиданием до 300 мс.
- **Отмена:** ручное движение фокуса (`focusStep`), уход `focusDriveAvailable`, смена сеанса (`releaseLens`).
- **Хранение.** Метки — в словаре по id камеры, `0xC21B` их стирает. Подпись итога — 3 с.

- [ ] **Меню метки** — правый щелчок: «Запомнить здесь», «Стереть».
- [ ] **Карточка «Метки фокуса».** Карточка в окне, как у фокуса касанием (`TouchFocusHelpCard`). Появляется при
  первом показе шкалы с метками. Текст: «A+ запоминает, где сейчас фокус. A переводит фокус туда за выбранное время,
  касание во время перевода — стоп. Правый щелчок по метке — запомнить заново или стереть». Галочка «Больше не
  показывать» (`Prefs` ключ `focusMarksHelpShown`); правый щелчок по кнопке времени открывает карточку снова.
- [ ] **Проверка** — e2e M6 (часть «объектив»); `swift build` чистый.
- [ ] Commit `feat(mac): focus marks A/B with timed pull`.

## Task 18: M5. Mac: другие камеры

**Files:** Modify `Sources/ZVCore/Session/CameraSession.swift` (вызовы `0x9215`/`0x9216`/`0x9223`, события
`0xC20F`/`0xC21B`/`0xC218`), `Sources/ZVViewer/CameraController.swift` (`cameraNames`, `lensInformation`, при
подключении и по событиям), `Sources/ZVViewer/FnMenu.swift`, `Sources/ZVViewer/AllSettingsPanel.swift`,
`Sources/ZVViewer/Presets.swift` (через `SettingCatalog.resolved` и `names:`), `Sources/ZVViewer/DebugPanel.swift`

**Interfaces:**
- Produces:
  ```swift
  // CameraSession
  public func displayStrings(type: UInt32) async throws -> DisplayStrings          // только при supports(0x9215)
  public func lensInformation() async throws -> LensInformation?                   // при supports(0x9223) и 0xE086 == 1
  public func vendorCodeVersion() async throws -> Int?
  public var events: … // 0xC20F (тип), 0xC21B, 0xC218 — тем же путём, что 0xC203
  ```

- [ ] **Шкала фокуса:**
  - метка положения — из `0xE043`, если он есть (0…65535 → доля), иначе из `0xD24C`;
  - подписи расстояний под делениями, у метки положения и у меток A/B (Task 17) — `LensInformation.label`;
  - положение для таблицы — в шкале `absoluteScale`.
- [ ] **Fn, «Все настройки», пресеты** — `SettingCatalog.resolved(_:available:)` и `ValueFormatter.label(…, names:
  cameraNames[тип])`.
- [ ] **Панель отладки** — версия кода, списки подписей и пары объектива.
- [ ] **Тест.** `swift test --filter OtherCameras` (новый случай: `CameraSession` с `FakeCamera` из тестов, где
  операции нет в списке, не шлёт `0x9215`); весь `swift test` зелёный.
- [ ] Commit `feat(mac): camera display strings, lens distances and Creative Look for newer Sony cameras`.

## Task 19: M6. Mac: API и сквозные проверки

**Files:** Modify `Sources/ZVCore/Automation/AutomationAction.swift`, `Sources/ZVViewer/AutomationServer.swift`,
`Tests/ZVCoreTests/AutomationTests.swift`, `docs/automation-api.md`, `scripts/e2e_monitor.py`, `scripts/e2e_lens.py`;
Create `scripts/e2e_newer.py`

- [ ] **Действия:**
  - `lutImport {path}`, `lutDelete {name}`, `lutFile {name}`, `lutScopes {on}`;
  - `vectorscopeZoom {zoom}`, `moveVectorscope {x, y}`;
  - `setProbe {x, y}`;
  - `focusMark {mark: "A"|"B", op: "save"|"go"|"clear"}`, `focusPullTime {seconds}`, `focusPullStop`;
  - `key` принимает l, v, i;
  - инструменты — существующими `setAssist`/`toggleAssist`.

  Декодирование каждого — тест в `AutomationTests`.
- [ ] **Состояние:**
  - `picture {orientation, lut: {mode, resolved, file, scopes}, whiteLevel, meanLuma}`;
  - `luts [имена]`;
  - `vectorscope {on, zoom, peakCb, peakCr}` — центр самой полной корзины;
  - `probe {on, x, y, percent, r, g, b, zone}`;
  - `lens.marks {A, B, duration}`, `lens.pull {progress, caption}`, `lens.distance`, `lens.absolute`;
  - `camera {displayStringLists, lensPairs, vendorCodeVersion}`.

  Таблицы — в `docs/automation-api.md`.
- [ ] **`e2e_monitor.py`:**
  - 13 инструментов в `ui.assist`;
  - LUT «Авто»: `request {setting: "pictureProfile", value: 8}` → `picture.lut.resolved == "slog3cine"` за ≤ 1 с и
    `meanLuma` выше, чем при `lut off`; значение 0 → `resolved == null`;
  - свой `.cube` (тождественный 2³ из временной папки): `lutImport`, `luts` содержит имя; `lutDelete` убирает;
  - зеркало: `orientation mirror`, `touchFocus (0,3; 0,5)` → рамка AF на поддельной камере в (0,7; 0,5) ±0,01;
    `orientation mirror` + `setZoom 2×` + `touchFocus (0,4; 0,4)` → рамка в `toCamera` ±0,01;
  - отдельный запуск поддельной камеры с `--picture scripts/assets/bars.jpg`:
    - вектороскоп — `peakCb/peakCr` у одной из шести меток ±0,03;
    - пипетка в середине красной полосы — `r ≥ 180`, `g ≤ 15`, `b ≤ 15`, `percent` 18–24;
    - пипетка на белой полосе 75 % при `white` 234 — `percent` 79–84;
  - частота с LUT + зеркалом + всей помощью ≥ 90 % от базы;
  - восстановление настроек — как сейчас.
- [ ] **`e2e_lens.py`** — камера с `--focus-gain 3.5 --focus-step-ms 80 --focus-deadband 2`:
  - метка A на 100;
  - шагами к 20, метка B;
  - `focusPullTime 4`, `focusMark A go` → готово за 3,5–5 с, `focalPosition` 99–100, `caption` «Фокус у метки A»;
  - посреди перевода — `focusPullStop` → движение прекратилось;
  - посреди перевода фокус на AF (`request {setting: "focusMode", value: <AF из options>}`) → перевод отменён,
    `lens.pull` пуст.
- [ ] **`e2e_newer.py`** (`--model newer`):
  - ни одной подписи «#» в значениях Fn и «Все настройки» из `/state`;
  - Creative Look «FL» после записи 0x0006;
  - `lens.distance` есть;
  - `lens.absolute == true`;
  - перевод A→B за 2 с длится 1,8–3 с, итог ±0,5 %.

  Сводка — общими счётчиками `e2e_monitor`.
- [ ] **Прогон.** `swift test` зелёный; `scripts/build-app.sh`; три e2e — зелёные.
- [ ] Commit `test(mac): automation and end-to-end checks for LUT, scopes, probe, mirror, focus marks, newer cameras`.

## Task 20: A1. Android: путь картинки

**Files:** Modify `android/app/src/main/java/local/zve10viewer/CameraController.kt` (поток кадра ≈ 2808–2819,
`AssistSampler`, `HistogramSampler`, `show`)

**Поток кадра:**
1. После `pool.decode`, если `transformsPicture`: `getPixels` в многоразовый `IntArray`.
2. `PictureTransform.apply` в `display` (и `analysis`, если помощь «без LUT»).
3. `setPixels` в тот же пуловый `Bitmap` до `show`.
4. Помощь — по `display` или `analysis` без повторного `getPixels`; белый — 255 при помощи с LUT.

Новых массивов на кадр нет: буферы переиспользуются по размеру.

LUT тот же, что на Mac: кэш по ключу, свои файлы — из `filesDir/luts/`. «Авто» — по свойствам главной камеры.

- [ ] **Проверка:**
  - на планшете с поддельной камерой (PP8 и «Авто») — `ZVAssist` в logcat: `lut=slog3cine`, `meanLuma` выше, чем
    без LUT;
  - `ZVFrames` — частота с LUT + зеркалом ≥ 90 % от базы;
  - `:app:testDebugUnitTest` зелёный.
- [ ] Commit `feat(android): picture transform after decode — orientation and LUT`.

## Task 21: A2. Android: меню «Монитор», свои LUT, вектороскоп

**Files:** Modify `ui/Monitor.kt` (`chunked(7)`, полоса LUT), `ui/Screen.kt` (`FloatingPanel` вектороскопа, ключи
`vectorscopeX/Y`), `Storage.kt`; Create `LutStore.kt`, `ui/VectorscopePanel.kt`

- [ ] **Меню** — как M2: плитки, подписи, полоса LUT; «Добавить LUT…» — `ActivityResultContracts.OpenDocument`
  (`*/*`), разбор через `CubeFile`, копия в `filesDir/luts/`, ошибки — `CubeFile.message`.
- [ ] **Вектороскоп** — панель 176 dp, касание ×1/×2, место по умолчанию — слева сверху; прячется вместе с
  гистограммой.
- [ ] **Проверка:** `adb_ui.py` находит «LUT», «Вектороскоп», «Пипетка», «Картинка»; `DEBUG_ASSIST` с extras `lut`,
  `vectorscope`, `vectorscopeZoom` меняет состояние, а `ZVAssist` это пишет.
- [ ] Commit `feat(android): monitor menu with LUT, vectorscope, probe and picture tiles`.

## Task 22: A3. Android: зеркало в координатах и пипетка

**Files:** Modify `ui/Screen.kt` (касание 466–479 и 591–597), `ui/Lens.kt` (`drawFocusFrames`), `ui/Monitor.kt`
(`drawReference` с поворотом), `CameraController.kt` (`touchFocus` → `toCamera`, `touchMark`); Create `ui/Probe.kt`

- [ ] **Как M3:**
  - касание при `probe` ставит пипетку;
  - иначе фокус — в `toCamera`;
  - рамки — через `fromCamera`;
  - эталон — с `scale(±1, ±1)`;
  - метка пипетки тащится;
  - подписи — не чаще 5 раз в секунду.
- [ ] **Отладка:**
  - `DEBUG_ASSIST` — extras `orientation`, `probe`, `probeX`, `probeY`;
  - `DEBUG_LENS x/y` — экранные доли;
  - `ZVAssist` пишет `orient=… probe=47%,182,140,121`;
  - `ZVLens` пишет точку AF в долях камеры.
- [ ] **Проверка на планшете с поддельной камерой:**
  - `orientation=mirror` + `DEBUG_LENS x=0.3 y=0.5` → в `ZVLens` AF 0,7;
  - пипетка на `bars.jpg` — как M6.
- [ ] Commit `feat(android): mirrored picture keeps touch focus and focus frames in place; probe on screen`.

## Task 23: A4. Android: метки фокуса

**Files:** Modify `ui/Lens.kt` (`FocusScale`), `CameraController.kt` (метки, цикл перевода — как M4; команды идут
той же очередью, что ручные шаги `focusStep`, с тем же поколением)

- [ ] **Как M4:** кнопки, долгое нажатие → «Запомнить здесь» / «Стереть», время по кругу, подпись итога 3 с.
  Карточка «Метки фокуса» — как у `TouchFocusHelpCard` в `ui/Lens.kt`, ключ `focusMarksHelpShown`; долгое нажатие на
  кнопке времени открывает её снова, а «долгое нажатие на метке» в тексте.
- [ ] **Отладка.** `DEBUG_LENS` — extras `mark`, `markOp`, `pullTime`; `ZVLens` пишет `marks A=… B=… pull=…`.
- [ ] **Проверка** — поддельная камера с физикой фокуса: перевод за 4 с — 3,5–5 с, итог ±1; отмена при AF.
- [ ] Commit `feat(android): focus marks A/B with timed pull`.

## Task 24: A5. Android: кнопки громкости

**Files:** Modify `MainActivity.kt` (`dispatchKeyEvent`), `CameraController.kt` (`hardwareShutter: SharedFlow<Unit>`,
`volumeKeys`), `Storage.kt` (`volumeKeys`, по умолчанию false), `ui/Screen.kt` (сбор потока → тот же обработчик, что у
главной кнопки; строка в `SettingsPanel`)

- [ ] **Правила.** `dispatchKeyEvent`: при `volumeKeys` клавиши 24, 25 и 27 забираются всегда — и `ACTION_DOWN`, и
  `ACTION_UP`. Действие — только на `ACTION_DOWN` с `repeatCount == 0`. В `App` событие выполняется, только если на
  экране кадр: нет ⚙, галереи, пресетов, отладки и просмотра снимка. Иначе — ничего.
- [ ] **Тест `HardwareKeysTest`.** Чистая функция `HardwareKeys.consumes(keyCode, enabled)` и
  `HardwareKeys.acts(action, repeatCount)`: 24, 25 и 27 при `enabled` — забираются; повтор не действует; при
  выключенном — не забираются.
- [ ] **Проверка на планшете с поддельной камерой:**
  - видео: `adb shell input keyevent 24` → запись пошла (рамка, `ZVController`), ещё раз → остановилась;
  - фото: спуск и снимок в ленте;
  - при выключенном переключателе громкость меняется как обычно.
- [ ] Commit `feat(android): volume keys as shutter and record`.

## Task 25: A6. Android: другие камеры

**Files:** Modify `session/CameraSession.kt`, `CameraController.kt`, `ui/Menus.kt` (Fn, «Все настройки»),
`ui/Presets.kt`, `ui/Lens.kt` (расстояния), `ui/DebugPanel.kt`

- [ ] **Как M5:** запросы при подключении и по событиям — только при поддержке; шкала из `0xE043`; расстояния, в том
  числе у меток A/B (Task 23);
  `resolved` и `names` в Fn, «Все настройки» и пресетах; отладка.
- [ ] **Тест `SessionTest`.** Сеанс с `FakeTransport` без `0x9215` в списке операций не шлёт её; с ней — шлёт тип 0
  после подключения.
- [ ] **Проверка на планшете** с `--model newer`: Fn без «#», Creative Look «FL», расстояния на шкале.
- [ ] Commit `feat(android): camera display strings, lens distances and Creative Look for newer Sony cameras`.

## Task 26: D1. Документация

**Files:** Modify `README.md`, `docs/user-guide.md`, `docs/protocol.md`, `docs/architecture.md`,
`docs/automation-api.md` (если не всё сделано в M6)

- [ ] **README:**
  - «Что умеет» — пункты про LUT, вектороскоп, пипетку, «Картинку», метки фокуса, кнопки громкости;
  - «Ограничения» — LUT и «Авто» по заводским профилям; превью 8 бит, поэтому LUT — ориентир;
  - «Какие камеры подойдут» — подписи и шкала у других камер Sony по справочнику Sony, «пришлите отчёт из
    „Отладки“».
- [ ] **user-guide:**
  - разделы плиток «Монитора» (14 плиток, таблица);
  - «Свои LUT»;
  - «Метки фокуса»;
  - «Кнопки громкости»;
  - клавиши L/V/I в таблице клавиш.
- [ ] **protocol:**
  - `0x9215`, `0x9216`, `0x9223` с разметкой наборов;
  - `0xE042`, `0xE043`, `0xE086`, `0xD0E1`, `0xD0E9`, `0xD0FA`;
  - события `0xC20F`, `0xC218`, `0xC21B`;
  - откуда таблицы (`scripts/sony_reference.py`);
  - догадка о шкале таблицы объектива.
- [ ] **architecture:**
  - путь картинки (схема из спеки §0) на обеих платформах;
  - цены — замеры M6 и A1;
  - счётчики e2e — по факту.
- [ ] **Спека телефона.** Если `docs/superpowers/specs/2026-10-28-android-phone-layout-design.md` уже в `main`, в нём
  «Монитор» — 14 плиток, 2×7. Если файла в `main` нет, поправку записать в память `phone-layout-plan`.
- [ ] **Проверка стиля:** `grep -n -i -E "canon|nikon|fujifilm|panasonic|проверено|теперь работает"` по
  изменённым документам — пусто.
- [ ] Commit `docs: LUT, scopes, probe, picture, focus marks, volume keys, other Sony cameras`.

## Task 27: R1. Проверка на устройствах и камере

**Сначала — проверка превью S-Log (бывший T0).** Камерой между проверками пользуется сам пользователь, поэтому проверка — здесь, а до неё LUT строится по предположению `v = Y′/255`.

**Files:** Create `scripts/check_log_preview.py`; Modify `docs/protocol.md` (новый подраздел «Превью в S-Log и HLG»
в «Превью (live view)»)

- [ ] **Скрипт.**
  - Читает `automation.json`, `GET /state` (профиль `0xD23F` и режим) и запоминает их.
  - В видеорежиме, потом в фоторежиме ставит PP8, PP7, PP10 действием `request {setting: "pictureProfile"}`. Для
    каждого ждёт 2 с, берёт 20 раз `GET /frame` и считает яркость (Pillow + numpy, веса Rec.709): доли 0,1 %, 50 %,
    99,9 %.
  - Гамма-помощь камеры по PTP не видна: в вывод скрипта и в `docs/protocol.md` пишется «гамма-помощь — в том
    состоянии, в каком меню камеры (по PTP не видно)».
  - Возвращает исходные профиль и режим и проверяет, что они вернулись.
  - Печатает таблицу.
- [ ] **Запуск.** Если камера подключена к Mac (`/state` → `phase == "ready"` и модель камеры не поддельная), запустить
  `python3 scripts/check_log_preview.py`. Если камеры нет — шаг откладывается до R1, а LUT пока строится по
  предположению `v = Y′/255`.
- [ ] **Решение по цифрам:**
  - нижняя граница S-Log3 ≈ 24 → предположение верно;
  - ≈ 0 при контрастной картинке → «Авто» по умолчанию ничего не включает (`LUTChoice.cameraConverts = true` на обеих платформах);
  - иначе — поправка `a + b·v` на входе LUT на обеих платформах.

  Записать цифры и решение в `docs/protocol.md`.
- [ ] Commit `docs(protocol): live view levels in S-Log and HLG` (вместе со скриптом).


- [ ] **Подготовка.** Сборки: `scripts/build-app.sh`, Android debug. Запуск — по памяти macos-test-launch и
  android-tablet-adb. Перед проверками записать настройки камеры (профиль, режим фокуса, зону, положение зума) и
  настройки программ (`plistlib` и `zv.xml`), после — вернуть.
- [ ] **Mac + ZV-E10:**
  - LUT «Авто» на PP8 на глаз (скриншот API);
  - зеркало + фокус касанием — рамка камеры под точкой касания;
  - метки A/B с PZ 16-50 в MF, перевод 2 и 4 с (лог положений по тикам);
  - пипетка против волновой формы на одном месте.
- [ ] **Планшет + ZV-E10** — то же через adb (logcat, `adb_ui.py`); кнопки громкости — только на поддельной камере
  (запись на карту).
- [ ] **Итог.** Найденное — исправить в своей задаче (тест + фикс + коммит). Итог и цифры — в память проекта.

## Task 28: R2. Выпуск 0.9.0

- [ ] **Слияние.** По памяти github-publishing и releases-claudes-call: перемотать `main` на ветку (fast-forward),
  проверить коммиты на серийный номер, GUID и адреса.
- [ ] **Сборка.** Версия 0.9.0 в обеих программах; DMG и APK; проба приложения из DMG; суммы файлов.
- [ ] **Публикация и установка.** Релиз на GitHub с заметками в духе docs-style; установка на Mac и планшет без
  запуска; настройки пользователя как были.
- [ ] Commit `release: 0.9.0` (по процедуре памяти).
