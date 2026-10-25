# Отклик кнопок, галерея, гистограмма и цифровое увеличение — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** В обеих программах — отклик на кнопки снимка, записи и стоп-кадра (кольцо, шторка, полёт кадра, рамка
записи, индикатор ожидания; на Android — вибрация), галерея снимков сеанса с листанием и увеличением, гистограмма на
перетаскивании, не прячущаяся с элементами, и цифровое увеличение live view двумя пальцами с бикубической
интерполяцией.

**Architecture:** Вся математика и смена состояний — чистые типы без UI (ZVCore на Mac, отдельные файлы без
Android-зависимостей на Android) с тестами. Экран поверх них: SwiftUI и Compose. Камера про увеличение не знает;
от камеры нужны только этапы снимка и счётчик принятых байт в транспорте.

**Tech Stack:** Swift 6 (language mode 5), SwiftUI, Core Image, ImageIO, Swift Testing; Kotlin 2.0.21, Compose
(BOM 2024.06.00), AGSL `RuntimeShader` (API 33+), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-25-touch-feedback-gallery-zoom-design.md`

**Кто делает:** Mac — задачи M1–M7, Android — A1–A7 (отдельный исполнитель в своей ветке и worktree, Mac-файлы не
трогает). Стенд камеры (F1) — до M2. Документация и проверка на устройствах — F2.

## Global Constraints

- Mac: macOS 14+, без внешних зависимостей; Metal-шейдеры не собираем (Metal Toolchain не установлен) — только
  системные фильтры Core Image. Android: minSdk 29, compileSdk 34; AGSL — только под `Build.VERSION.SDK_INT >= 33`,
  ниже — обычное масштабирование.
- Тексты интерфейса и комментарии — по-русски, идентификаторы — по-английски, как в окружающем коде.
- Увеличение: 1×–8×; во время жеста 0,87×–8,6× с сопротивлением; отпустили ниже 1,05× — 1×; ступени ⌘+/⌘−:
  1, 1,5, 2, 3, 4, 6, 8. Подпись множителя: целое — «2×», иначе одна цифра после запятой — «2,4×».
- Пока увеличено ≥ 1,5×: адаптивное качество снижает при прогнозе < 6 к/с и возвращает с 10 к/с (вместо 12 и 18).
- Гистограмма: отступ от края 16, прилипание ближе 24 (dp на Android, pt на Mac), при перетаскивании масштаб 1,03.
- Отклик: кольцо при нажатии — радиус кнопки + 10, толщина 2, белое 60 %; волна — до радиуса + 34 за 450 мс
  (белая у спуска и стоп-кадра, красная у записи); нажатая кнопка — масштаб 0,92; шторка — чёрный до 75 % за 40 мс и
  обратно за 220 мс; вспышка стоп-кадра — белый 25 % → 0 за 250 мс; полёт кадра — 450 мс; новая миниатюра — 0,6 → 1
  пружиной, белая рамка гаснет за 600 мс; рамка записи — красная 3, на старте 100 % → 70 %, гаснет за 300 мс;
  квадрат ↔ круг записи — 250 мс; отказ — сдвиг ±6 три раза за 300 мс; запрос записи без ответа — отказ через 6 с.
- Доля загрузки — если объект ≥ 1 048 576 байт; опрос счётчика — 10 раз в секунду.
- Подписи ровно такие: «Фокусировка…», «Снимаю…», «Загрузка…», «Загрузка 42 %», «Загрузка 2/2 · 42 %»,
  «Запуск записи…», «Остановка…»; в галерее «3 из 12», «Стоп-кадр 1024×576», «Снимок 6000×4000», «RAW + JPEG»,
  «RAW — откройте в другой программе», «Открыть», «Поделиться», «Показать в Finder»; кнопка ✕ увеличения —
  «Отменить увеличение»; Android ⚙ — «Вибрация при съёмке».
- Число файлов снимка — по свойству 0xD253 FileFormat: 2 (RAW+JPEG) — два файла, иначе один.
- Запись на карту камеры при проверках — только на поддельной камере (`scripts/fake_camera.py`) или с разрешения
  пользователя; снимки на реальной камере — с назначением «только на устройство» в папку для тестов.

## Review Focus

1. Новый снимок или стоп-кадр, пока открыта галерея, не меняет открытый снимок — галерея держит снимок по id, а не по
   индексу (M5, A5: проверка через API Mac и вручную на планшете).
2. Поворот планшета или смена размера окна при увеличении — без пустых полос и без прыжка в другое место кадра
   (M1 `survivesResize`, A1 то же).
3. Смена качества превью 1024 → 640 при увеличении — та же часть кадра на экране (M1 `qualityChangeKeepsRegion`,
   A1 то же).
4. Камера не ответила на запись или запись остановили кнопкой на камере во время запроса — индикатор не залипает
   (M1 `RecordingToggle`: тайм-аут и подтверждение событием, A1 то же).
5. Место гистограммы, сохранённое на большом экране, на маленьком окне — гистограмма на экране целиком или у
   левого верхнего отступа (M1 `tooSmallView`, A1 то же).

---

## F1. Поддельная камера: снимок и запись

**Files:** Modify `scripts/fake_camera.py`

- [ ] Камера отвечает на 0x9207 ControlDevice: S1 (0xD2C1) / S2 (0xD2C2) — после S2 через 0,4 с ObjectInMemory
  (SonyProp.objectInMemory) > 0x8000 и событие 0xC203; GetObjectInfo/GetObject по 0xFFFFC001 отдают JPEG
  `--photo <path>` (по умолчанию — сгенерированный 6000×4000 через `sips` из фикстуры, ~8 МБ), после выдачи счётчик
  сбрасывается. MovieRec (0xD2C8) нажатие 2 → через 0,8 с MovieRecordingState (0xD21D) меняется 0 ↔ 1 и событие
  0xC203. Флаг `--ignore-record` — камера не реагирует (для отказа). `--rate <байт/с>` — ограничение скорости отдачи
  снимка, чтобы видеть долю загрузки.
- [ ] Проверка: `python3 scripts/fake_camera.py` и `zv-probe --wifi` к 127.0.0.1 — снимок качается, запись
  переключается. Commit `test(bench): fake camera takes photos and toggles recording`.

## M1. ZVCore: чистая логика (Mac)

**Files:**
- Create: `Sources/ZVCore/View/ViewZoom.swift`, `Sources/ZVCore/View/FloatingPlacement.swift`,
  `Sources/ZVCore/Session/RecordingToggle.swift`, `Sources/ZVCore/Photo/CaptureProgress.swift`
- Modify: `Sources/ZVCore/Session/LiveViewQualityPolicy.swift`
- Test: `Tests/ZVCoreTests/ViewZoomTests.swift`, `Tests/ZVCoreTests/FloatingPlacementTests.swift`,
  `Tests/ZVCoreTests/RecordingToggleTests.swift`, `Tests/ZVCoreTests/CaptureProgressTests.swift`,
  `Tests/ZVCoreTests/LiveViewQualityPolicyTests.swift`

**Interfaces (Produces):**
```swift
public struct ViewZoom: Equatable, Sendable, Codable {
    public static let maxFactor = 8.0, snapBelow = 1.05
    public static let identity: ViewZoom          // factor 1, center (0.5, 0.5)
    public var factor: Double                     // 1 — кадр вписан в экран
    public var center: CGPoint                    // точка кадра (доли 0…1) в центре экрана
    public var isZoomed: Bool                     // factor > 1.0001
    public var label: String                      // «2×», «2,4×»
    public func imageRect(view: CGSize, frame: CGSize) -> CGRect   // где кадр на экране, центр уже в пределах
    public func zoomed(by k: Double, around point: CGPoint, view: CGSize, frame: CGSize, elastic: Bool) -> ViewZoom
    public func panned(by delta: CGSize, view: CGSize, frame: CGSize) -> ViewZoom
    public func settled(view: CGSize, frame: CGSize) -> ViewZoom   // 1…8, < 1,05 → identity, центр в пределах
    public func centered(at point: CGPoint, view: CGSize, frame: CGSize) -> ViewZoom
    public func visibleRegion(view: CGSize, frame: CGSize) -> CGRect  // доли кадра
    public static func step(from factor: Double, up: Bool) -> Double
}
public struct FloatingPlacement: Equatable, Sendable, Codable {
    public static let margin = 16.0, snapDistance = 24.0
    public var x: Double, y: Double               // доли свободного места [margin, view − panel − margin]
    public func origin(panel: CGSize, view: CGSize) -> CGPoint
    public static func dropped(at origin: CGPoint, panel: CGSize, view: CGSize) -> FloatingPlacement
    public static func dragOrigin(_ origin: CGPoint, panel: CGSize, view: CGSize) -> CGPoint  // в пределах экрана
}
public struct RecordingToggle: Equatable, Sendable {
    public enum Request: String, Sendable { case starting, stopping }
    public enum Outcome: Equatable, Sendable { case started, stopped, failed(Request) }
    public static let timeout: TimeInterval = 6
    public private(set) var request: Request?
    public mutating func press(recording: Bool, at now: Date) -> Bool       // false — запрос уже идёт
    public mutating func observe(recording: Bool) -> Outcome?               // свойство MovieRecordingState
    public mutating func completed(recording: Bool?) -> Outcome?            // вызов вернулся (nil — ошибка)
    public mutating func tick(now: Date) -> Outcome?
}
public enum CapturePhase: Equatable, Sendable {
    case focusing, exposing
    case downloading(file: Int, files: Int, received: Int, expected: Int?)
}
public enum CaptureFeedback {
    public static let minProgressBytes = 1_048_576
    public static func caption(_ phase: CapturePhase) -> String
    public static func fraction(_ phase: CapturePhase) -> Double?            // nil — дуга крутится
}
// LiveViewQualityPolicy: decide(now:outage:sharp:) — sharp: Bool = false; при true пороги 6/10 к/с
```

Модель увеличения: размер кадра на экране `S = fit × factor`, где `fit` — вписанный размер; начало кадра
`O = view/2 − center·S`. По оси, где `S` больше экрана, `center` ограничен `[h, 1 − h]`, `h = (view/2)/S`; где
меньше — `center = 0,5`. Увеличение вокруг точки `p`: точка кадра `u = (p − O)/S` после смены `S` остаётся под `p`.
Сопротивление: если множитель за пределом [1, 8] и `k` уводит дальше — берём `k^0,3`; жёсткие края 0,87 и 8,6. Без
`elastic` — сразу [1, 8].

- [ ] **Тесты** (Swift Testing), значения для экрана 1280×800 и кадра 1024×576:
  - `identityFitsLetterboxed`: `imageRect` = (0, 40, 1280, 720);
  - `zoomKeepsPointUnderFingers`: ×2 вокруг (300, 200) — точка кадра под (300, 200) та же (±1e-9), `center` ≈
    (0,3672, 0,3611);
  - `panStopsAtImageEdge`: при 2× сдвиг (+5000, 0) → `imageRect.minX == 0`;
  - `letterboxAxisStaysCentered`: 1,05× → `imageRect.midY == 400`;
  - `settledSnapsBelowThreshold` (1,04 → identity), `settledClampsToMax` (8,5 → 8);
  - `elasticResistsBeyondLimits`: с 8× `zoomed(by: 1,5, elastic: true)` — в (8, 8,6], без elastic — 8;
  - `visibleRegionCentered`: 2× по центру → (0,25, 0,2222, 0,5, 0,5556) ±1e-4;
  - `survivesResize`: 3× с центром (0,8, 0,3) на 1280×800, потом `imageRect` на 800×1280 — кадр закрывает экран по
    обеим осям, где он больше, без полос, видимая часть содержит прежний центр;
  - `qualityChangeKeepsRegion`: тот же `ViewZoom` для кадров 1024×576 и 640×360 — одинаковый `visibleRegion`;
  - `stepLadder`: (1, up) → 1,5; (2,4, up) → 3; (2,4, down) → 2; (8, up) → 8; (1, down) → 1;
  - `labels`: 2 → «2×», 2,4 → «2,4×», 1,46 → «1,5×»;
  - `FloatingPlacement` на 1280×800, панель 320×170: `origin(x: 0, y: 1)` = (16, 614); `dropped(at: (30, 300))`
    → `x == 0`; `dropped(at: (500, 300))` → `x ≈ 484/928`; `dropped(at: (−50, 900))` → (0, 1); `tooSmallView`
    (экран 300×150) → `origin` = (16, 16); `dragOrigin((−40, 2000))` = (0, 630); Codable туда-обратно;
  - `RecordingToggle`: `startConfirmedByCamera` (press(false) → `.starting`, observe(false) → nil, observe(true) →
    `.started`, request nil); `secondPressIgnoredWhilePending`; `timeoutFails` (5,9 с → nil, 6,1 с →
    `.failed(.starting)`); `completedWithoutChangeKeepsWaiting` (сессия ждёт подтверждения только 2,5 с, камера
    бывает медленнее: completed(false) → nil, запрос ещё идёт, потом observe(true) → `.started`, а без него tick
    6,1 с → `.failed(.starting)`); `completedAfterObserveIsSilent`; `stopConfirmed`; `errorFails`
    (completed(nil) → `.failed`);
  - `CaptureFeedback`: `.focusing` → «Фокусировка…»; `.exposing` → «Снимаю…»; downloading 1/1, 0,42 от 8 МБ →
    «Загрузка 42 %» и 0,42; 2/2 → «Загрузка 2/2 · 42 %»; expected 300 КБ или nil → «Загрузка…» и nil;
  - `LiveViewQualityPolicy`: `sharpKeepsHighDownToSixFPS` — канал на 8 к/с высокого: без sharp снижает, с sharp
    держит высокое; на 5 к/с снижает и с sharp.
- [ ] Запустить `swift test --filter "ViewZoom|FloatingPlacement|RecordingToggle|CaptureFeedback|LiveViewQuality"` —
  падают; реализовать; зелёные; весь `swift test` зелёный.
- [ ] Commit `feat(core): zoom geometry, floating placement, recording toggle, capture feedback`.

## M2. Этапы снимка и доля загрузки (Mac)

**Files:**
- Modify: `Sources/ZVCore/Session/PTPTransport.swift` (DataProgress, требование протокола с умолчанием nil),
  `Sources/ZVCore/PTPIP/PTPIP.swift` (`PTPIPTransaction.expected` из StartData), `Sources/ZVCamera/PTPIPTransport.swift`
  (считает принятые байты команд-канала от StartData до ответа), `Sources/ZVCore/Photo/PhotoCapture.swift`,
  `Sources/ZVViewer/CameraController.swift`
- Test: `Tests/ZVCoreTests/PTPIPTests.swift`, `Tests/ZVCoreTests/CaptureProgressTests.swift`

**Interfaces:**
- Produces: `public final class DataProgress: @unchecked Sendable { begin(expected: Int?); add(_ bytes: Int);
  finish(); var current: (received: Int, expected: Int?)? }`; `PTPTransport.dataProgress: DataProgress?`;
  `CameraSession.capturePhoto(download: Bool, files: Int, onPhase: @Sendable (CapturePhase) -> Void)`;
  `CameraController.capturePhase: CapturePhase?` (nil — не снимаем) и `CameraController.feedback:
  AsyncStream<FeedbackEvent>` — события для экрана: `.shutterFired`, `.frameGrabbed(thumbnail: CGImage, shotID:
  UUID)`, `.shotAdded(UUID)`, `.recordingStarted`, `.recordingStopped`, `.failed(FeedbackTarget)` где
  `FeedbackTarget` — `.shutter`, `.record`, `.grab`.

- [ ] Тесты: `transactionReportsExpectedLength` (StartData с длиной 9 000 000 → `expected == 9_000_000`);
  `dataProgressLifecycle` (begin(100) → add(40) → current (40, 100); finish → nil); фальшивый транспорт в
  `CaptureProgressTests` — `capturePhaseOrder`: `onPhase` получает `.focusing`, `.exposing`, `.downloading(file: 1,
  files: 1, received: 0, expected: <размер из ObjectInfo>)` в этом порядке. Падают → реализовать → зелёные.
- [ ] Контроллер: `capturePhase` из `onPhase`; пока `.downloading` — таймер 10 Гц читает `dataProgress.current` и
  обновляет `received`; просмотр снимка (если включён) показывается на `.exposing` с текущим кадром, а не при
  нажатии; `.shutterFired` — на `.exposing`. `Shot` получает `takenAt: Date` и `pixelSize: CGSize?`.
- [ ] `/state.capture` (`phase`, `received`, `expected`) в API. Проверка на поддельной камере с `--rate 1000000`:
  `capture.phase` проходит focusing → exposing → downloading с растущим `received`. Commit
  `feat(mac): capture phases and download progress`.

## M3. Отклик кнопок (Mac)

**Files:**
- Create: `Sources/ZVViewer/Feedback.swift` — `HaloButtonStyle` (нажатие 0,92 и кольцо), `HaloWave` (волна по
  событию), `ShutterBlink`, `GrabFlight`, `TallyBorder`, `PendingBadge` (дуга + подпись слева), `Shake` (GeometryEffect)
- Modify: `Sources/ZVViewer/PhotoViews.swift` (кнопки, лента: «пружинка» новой миниатюры, «+N» — кнопка),
  `Sources/ZVViewer/LiveScreen.swift` (слои), `Sources/ZVViewer/CameraController.swift` (RecordingToggle, события),
  `Sources/ZVViewer/AutomationServer.swift` (`recordingRequest` в /state)

- [ ] Запись: `toggleRecording()` — `RecordingToggle.press`; `apply(snapshot)` — `observe`; вызов сессии —
  `completed`; тик раз в 0,5 с — `tick`; исходы → события и сообщение «Камера не начала запись» / «Камера не
  остановила запись».
- [ ] Экран: волна на событие действия (из кнопки и клавиш пробел / S), шторка на `.shutterFired`, вспышка и полёт на
  `.frameGrabbed` (цель — рамка первой миниатюры через `anchorPreference`, иначе левый нижний угол и таяние), рамка
  записи по `isRecording` вокруг видимой картинки, дуга и подпись по `capturePhase` и `recordingRequest`, «мотание» на
  `.failed`.
- [ ] Проверка на поддельной камере через API: снимок (`capture`), стоп-кадр (`grabFrame`), запись (`toggleRecording`
  с `confirm`), отказ (`--ignore-record`), скриншоты в моменты ожидания — подписи и дуги на месте. Commit
  `feat(mac): button feedback — halo, shutter blink, frame flight, tally, pending arcs`.

## M4. Гистограмма на перетаскивании (Mac)

**Files:** Modify `Sources/ZVViewer/ScreenState.swift` (`histogramPlacement: FloatingPlacement?` в UserDefaults
`histogramPlacement`), `Sources/ZVViewer/LiveScreen.swift`, `Sources/ZVViewer/TopBar.swift`,
`Sources/ZVCore/Automation/AutomationAction.swift`, `Sources/ZVViewer/AutomationServer.swift`; Test
`Tests/ZVCoreTests/AutomationTests.swift`

- [ ] Тест разбора `{"action":"moveHistogram","x":0.5,"y":0.25}` → `.moveHistogram(x: 0.5, y: 0.25)`; падает →
  реализовать → зелёный.
- [ ] Гистограмма — отдельный слой над видео (не внутри `controls`): видна при скрытых элементах и при меню Fn,
  прячется под просмотром и галереей; `DragGesture` с `dragOrigin`, по отпусканию `dropped` с анимацией; курсор
  открытая/сжатая рука; по умолчанию — над лентой снимков слева внизу. `/state.ui.histogramPosition`.
- [ ] Проверка API: перенести в (1, 0), скрыть элементы (H) — скриншот с гистограммой справа вверху; сменить размер
  окна — на экране. Commit `feat(mac): draggable histogram that stays when controls hide`.

## M5. Галерея (Mac)

**Files:** Create `Sources/ZVViewer/Gallery.swift` (`GalleryOverlay`, загрузчик картинок с кэшем на 3 снимка, полный
кадр при сильном увеличении); Modify `Sources/ZVViewer/ScreenState.swift` (`galleryShot: UUID?`),
`Sources/ZVViewer/PhotoViews.swift`, `Sources/ZVViewer/LiveScreen.swift` (клавиши: ← →, Esc, пробел, ⌘+ ⌘− ⌘0 при
открытой галерее), `Sources/ZVCore/Automation/AutomationAction.swift`, `Sources/ZVViewer/AutomationServer.swift`;
Test `Tests/ZVCoreTests/AutomationTests.swift`

- [ ] Тесты разбора `openGallery` (`index`), `galleryStep` (`offset`), `closeGallery`; падают → реализовать →
  зелёные.
- [ ] Галерея по спеке: верх (×, «3 из 12», кнопки), подпись снизу, лента с текущим в рамке, ‹ › при наведении, ← →,
  смахивание двумя пальцами (горизонтальная прокрутка, один снимок за жест), щипок и двойной щелчок (вписать ↔ 2,5×),
  перетаскивание при увеличении, листание выключено при увеличении, полный кадр через ImageIO, если увеличено больше
  размера декодированной картинки. Держит снимок по `id`. `openShot` из ленты и API открывает галерею;
  `/state.ui.gallery` (`index`, `title`).
- [ ] Проверка API на поддельной камере: 3 стоп-кадра, `openGallery 1`, `grabFrame` — `ui.gallery.index` стал 2,
  `title` тот же; `galleryStep +1` / `−1`; скриншоты. Commit `feat(mac): gallery with paging, filmstrip and zoom`.

## M6. Цифровое увеличение (Mac)

**Files:** Create `Sources/ZVViewer/ZoomedVideo.swift` (`ZoomableVideo`, `ZoomPatchRenderer` — Core Image
`CIBicubicScaleTransform` B = 0, C = 0,5 на `CIContext(mtlDevice:)` в фоне, `ZoomBadge` — мини-карта, «2,4×», ✕);
Modify `Sources/ZVViewer/LiveScreen.swift`, `Sources/ZVViewer/ScreenState.swift` (`zoom: ViewZoom`),
`Sources/ZVViewer/ZVViewerApp.swift` (меню «Вид»: «Увеличить» ⌘+, «Уменьшить» ⌘−, «Без увеличения» ⌘0),
`Sources/ZVViewer/CameraController.swift` (`decide(..., sharp: zoom.factor >= 1.5)`),
`Sources/ZVCore/Automation/AutomationAction.swift`, `Sources/ZVViewer/AutomationServer.swift`; Test
`Tests/ZVCoreTests/AutomationTests.swift`

- [ ] Тесты разбора `setZoom` (`factor`, `x`, `y`) и `resetZoom`; падают → реализовать → зелёные.
- [ ] Видео: обычная картинка с `imageRect` из `ViewZoom`; при увеличении поверх — заплатка: видимая часть кадра (с
  запасом 10 %), пересчитанная Core Image в размер экрана в пикселях, показанная в своём прямоугольнике по текущему
  `ViewZoom` (во время жеста едет вместе с обычной); пересчёт — на новый кадр или по окончании жеста, лишние запросы
  схлопываются. `MagnifyGesture` (вокруг курсора), `DragGesture` при увеличении, прокрутка двумя пальцами — сдвиг;
  рамка записи и подсказки о связи не увеличиваются. Плашка увеличения справа внизу (не шире 96 pt), видна и при
  скрытых элементах; ✕ — к 1× за 250 мс; щелчок и перетаскивание по мини-карте — `centered(at:)`.
- [ ] Проверка API: `setZoom 4 0.3 0.4` — скриншот (заплатка чётче обычной: сравнить с `ZV_ZOOM_PATCH=0`);
  `ui.zoom` в /state; поменять размер окна — без полос; `resetZoom`. Нагрузка процессора при 4× на 25 к/с — в отчёт.
  Commit `feat(mac): pinch zoom with bicubic patch, minimap and reset`.

## M7. Проверка Mac целиком

- [ ] `swift test` — всё зелёное; `scripts/e2e.py`, `scripts/e2e_photo.py` (на поддельной камере и на реальной с
  «только на Mac»), `scripts/wifi_resilience.py mac` короткий — без регрессий. Независимый обзор ветки Mac.

## A1. Чистая логика (Android)

**Files:** Create `android/app/src/main/java/local/zve10viewer/view/ViewZoom.kt`,
`.../view/FloatingPlacement.kt`, `.../session/RecordingToggle.kt`, `.../session/CaptureProgress.kt`,
`.../ui/Haptics.kt` (только таблица — чистая функция; само `performHapticFeedback` — в A3); Modify
`.../session/LiveViewQualityPolicy.kt`; Test `android/app/src/test/java/local/zve10viewer/ViewZoomTest.kt`,
`FloatingPlacementTest.kt`, `RecordingToggleTest.kt`, `CaptureProgressTest.kt`, `HapticsTest.kt`,
`LiveViewQualityPolicyTest.kt`

**Interfaces:** те же, что M1, в Kotlin: `data class ViewZoom(val factor: Double, val centerX: Double, val centerY:
Double)` с методами `imageRect(view: Size, frame: Size): Rect`, `zoomed(k, around: Offset, view, frame, elastic)`,
`panned(delta: Offset, view, frame)`, `settled`, `centered(at: Offset /* доли */, view, frame)`, `visibleRegion`,
`label`, `companion step(from, up)`; геометрия — `androidx.compose.ui.geometry.Offset/Size/Rect` (чистый Kotlin,
работает в JVM-тестах); `FloatingPlacement(x, y)` с `origin`, `dropped`, `dragOrigin`; `RecordingToggle` с теми же
методами и временем в мс; `CapturePhase` (sealed) и `CaptureFeedback`; `enum class HapticEvent { accepted,
shutterFired, recordingStarted, recordingStopped, frameGrabbed, failed, zoomLimit, dragStart }` и `fun
hapticConstant(event: HapticEvent, sdk: Int): Int` по таблице спеки; `LiveViewQualityPolicy.decide(now, outage,
sharp = false)`.

- [ ] Те же тесты, что в M1 (имена в camelCase как в соседних тестах), плюс `HapticsTest`: sdk 34 —
  `shutterFired → CONFIRM`, `failed → REJECT`, `dragStart → GESTURE_START`; sdk 29 — `shutterFired → LONG_PRESS`,
  `failed → LONG_PRESS`, `dragStart → LONG_PRESS`; `accepted → VIRTUAL_KEY` везде.
- [ ] `./gradlew :app:testDebugUnitTest` (JAVA_HOME — openjdk@17) — падают → реализовать → зелёные. Commit.

## A2. Этапы снимка, доля загрузки, снимки без JPEG в памяти (Android)

**Files:** Modify `.../ptp/Ptp.kt` (`class DataProgress` и `val dataProgress: DataProgress? get() = null` в
`PtpTransport`), `.../wifi/PtpIpTransport.kt` и `.../usb/UsbPtpTransport.kt` (считают принятые байты фазы данных),
`.../session/CameraSession.kt` (`capturePhoto(download, files, onPhase)`), `.../CameraController.kt`
(`capturePhase`, события `feedback: SharedFlow<FeedbackEvent>`, просмотр на `exposing`, `Shot(id: Long, files,
thumbnail, takenAt, pixelSize)` — без `preview: ByteArray`), `.../Storage.kt` (`MediaSaver.decode(uri, maxSide)`
и `regionDecoder(uri)` из файла); Test `PtpIpTest.kt`, `SessionTest.kt`

- [ ] Тесты: длина из StartData у сборщика транзакции; `DataProgress` begin/add/finish; на фальшивом транспорте
  порядок этапов `focusing → exposing → downloading(1, 1, 0, size)`. Падают → реализовать → зелёные.
- [ ] Контроллер как в M2 (опрос 10 Гц, `files` по 0xD253). Commit `feat(android): capture phases and download
  progress; shots no longer keep JPEG in memory`.

## A3. Отклик кнопок и вибрация (Android)

**Files:** Create `.../ui/Feedback.kt` (кольцо и вдавливание через `MutableInteractionSource`, волна, шторка,
вспышка и полёт кадра к первой миниатюре через `onGloballyPositioned`, рамка записи, дуга и подпись ожидания,
«мотание»); Modify `.../ui/Haptics.kt` (`class Haptics(view: View, enabled: () -> Boolean)` —
`performHapticFeedback(hapticConstant(event, SDK_INT))`), `.../ui/Menus.kt` (кнопки), `.../ui/Screen.kt` (слои,
⚙ «Вибрация при съёмке» только при `Vibrator.hasVibrator()`), `.../Storage.kt` (`hapticsEnabled`, по умолчанию
true), `.../CameraController.kt` (RecordingToggle как в M3)

- [ ] Проверка на планшете (поддельная камера через `--es ptpipHost <IP Mac>` и реальная камера): скриншоты
  `adb exec-out screencap` в моменты ожидания снимка и записи; переключателя вибрации на этом планшете нет (мотора
  нет). Commit `feat(android): button feedback and haptics`.

## A4. Гистограмма на перетаскивании (Android)

**Files:** Modify `.../ui/Screen.kt`, `.../Storage.kt` (`histogramPlacement`: два Float в prefs)

- [ ] Гистограмма — слой над видео вне `Controls`: видна при скрытых элементах и при меню Fn; `detectDragGestures`
  (подхватили — вибрация `dragStart`, масштаб 1,03 и тень; отпустили — `dropped` с анимацией); касания по ней не
  возвращают скрытые элементы (общий обработчик в `LiveScreen` пропускает касания внутри её прямоугольника).
- [ ] Проверка: `adb shell input swipe` с удержанием переносит её; поворот планшета — на экране. Commit
  `feat(android): draggable histogram that stays when controls hide`.

## A5. Галерея (Android)

**Files:** Create `.../ui/Gallery.kt` (`GalleryOverlay`: `HorizontalPager` с `key = shot.id`, страница с
увеличением (щипок, двойное касание, сдвиг; пейджер выключен при увеличении), `BitmapRegionDecoder` для участка в
полном разрешении после 150 мс покоя, лента `LazyRow`, верх и низ по спеке, смахивание вниз — закрыть,
`BackHandler`); Modify `.../ui/Screen.kt`, `.../ui/Menus.kt` (миниатюры и «+N» открывают галерею),
`.../CameraController.kt` (`share(file)` — `ACTION_SEND` через chooser)

- [ ] Кэш декодированных страниц — текущая и соседние (LruCache по байтам, не больше 1/8 `maxMemory`). Галерея держит
  снимок по `id`: новый снимок не меняет открытый. RAW без JPEG — миниатюра (кадр с камеры) и пометка «RAW — откройте
  в другой программе».
- [ ] Проверка на планшете: 3 стоп-кадра и 1 снимок, листание `input swipe`, увеличение двойным касанием, закрытие
  «Назад» и смахиванием вниз; `dumpsys meminfo local.zve10viewer` после 20 снимков — память не растёт на размер
  JPEG за снимок. Commit `feat(android): gallery with paging, filmstrip and zoom`.

## A6. Цифровое увеличение (Android)

**Files:** Create `.../ui/ZoomedVideo.kt` (`ZoomableVideo`: жесты — щипок, сдвиг с инерцией через
`animateDecay`, касание проходит к скрытию элементов; отрисовка — `RuntimeShader` с Catmull-Rom по 9 билинейным
выборкам `BitmapShader` (`FILTER_MODE_LINEAR`, CLAMP), шейдер на каждый Bitmap из пула кэшируется; ниже API 33 —
`drawImage` с `graphicsLayer`; `ZoomBadge` справа внизу ≤ 104 dp), `.../ui/BicubicShader.kt` (текст AGSL);
Modify `.../ui/Screen.kt`, `.../CameraController.kt` (`sharp` для политики), `.../MainActivity.kt` (debug-broadcast
`local.zve10viewer.DEBUG_ZOOM` с `factor`, `x`, `y` и `local.zve10viewer.DEBUG_GALLERY` с `index` — только при
`BuildConfig.DEBUG`)

- [ ] Шейдер и на 1×, если `dumpsys gfxinfo local.zve10viewer framestats` на 1× и 4× показывает кадры GPU в пределах
  бюджета 144 Гц (≤ 6,9 мс) без пропусков; иначе — только при увеличении. Результат замера — в отчёт исполнителя.
- [ ] Проверка: broadcast 4× в (0,3, 0,4) — скриншот (сравнить с билинейным при отключённом шейдере — debug extra
  `--ez bicubic false`); щипок руками — пользователь; поворот при увеличении — без полос. Commit
  `feat(android): pinch zoom with bicubic shader, minimap and reset`.

## A7. Проверка Android целиком

- [ ] `./gradlew :app:testDebugUnitTest :app:assembleDebug` — зелёные; установка `scripts/adb_install.py`; полный
  проход на реальной камере: фото (на планшет), стоп-кадры при записи (запись — только поддельная камера или с
  разрешения), галерея, гистограмма, увеличение; `dumpsys gfxinfo` при увеличении. Независимый обзор ветки Android.

## F2. Документация и сводная проверка

**Files:** Modify `docs/user-guide.md`, `docs/automation-api.md`, `docs/architecture.md`, `README.md`

- [ ] Описать отклик, галерею, гистограмму, увеличение (жесты, ⌘+/⌘−/⌘0, ✕), новые поля и команды API. Слить ветку
  Android в ветку работы, `swift test` и Android-тесты зелёные, коммит.
