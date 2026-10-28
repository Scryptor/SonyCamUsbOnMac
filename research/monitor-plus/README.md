# Monitor+ (mpf 1.4.4): как он берёт картинку и управляет камерой

Разбор APK `mpf-1.4.4.apk` (SHA-256 `bc83b7c1…5d2`), 2026-10-29. Вопрос был: как Monitor+ показывает картинку лучше превью PC Remote и при этом управляет камерой.

Короткий ответ: **никак не из самой камеры**. Превью по PTP у него то же, что у нас (до 1024 px в ширину). Картинку лучше он берёт из второго USB-устройства, UVC: камеры в режиме USB-стриминга или HDMI-карты захвата. В режиме **Duplex** он совмещает такой источник картинки с управлением камерой по PTP, через USB или Wi-Fi.

Как разбирали: Java-часть декомпилирована jadx, у нативных библиотек смотрели экспорт и строки, у Dart-кода (`libapp.so`, AOT) — только пул строк. Логику на Dart не декомпилировали. Всё, что ниже помечено «по строкам», — вывод из названий, а не из прочитанного кода.

## Из чего состоит приложение

- **Flutter.** Вся логика протоколов — на Dart (`libapp.so`, ~9,9 МБ), пакет `com.uhi.monitorplusflutter`.
- **Java/Kotlin — тонкий слой.** `UsbHost` (`re/z.java`) только открывает USB-устройство, захватывает интерфейс 0 и гоняет сырые пакеты по bulk-эндпоинтам. Приходящее он отдаёт в Dart событиями `ptpPacketReceived` (с источником `bulk` или `event`) и `ptpDataPacketProgress`. Сам PTP разбирается на Dart.
- **`libnative.so`** (C++) — свой код поверх libusb, libuvc, libyuv, libjpeg-turbo, OpenGL ES, а заодно LibRaw, libexif, OpenSSL и libssh2. Dart зовёт его через FFI: каналов `uvc*` в Java нет, а функции экспортированы прямо из `.so`.
- **ijkplayer и ffmpeg** — проигрыватель роликов. Признаков, что через них идёт живая картинка с камеры, не видно.

## Режимы подключения

Взято из подсказок самого приложения (`assets/flutter_assets/assets/markdown/connection_*_en.md`):

| Режим | Картинка | Управление |
|---|---|---|
| Камера по кабелю | превью PC Remote по PTP | PTP по USB (режим PC Remote) |
| Камера по Wi-Fi | превью по PTP/IP; для старых камер — HTTP Camera Remote API | PTP/IP, сопряжение через Bluetooth или QR-код |
| Monitor Mode | UVC: камера в USB-стриминге или карта захвата | нет |
| **Duplex Mode** | UVC / карта захвата | PTP по кабелю или Wi-Fi к той же камере |

Подсказка к Duplex: сначала подключить камеру (по кабелю или Wi-Fi), потом воткнуть UVC/карту захвата. Когда видны оба, появляется кнопка «Enter in Duplex Mode». В строках есть и ошибка `Wrong device type for duplex mode!`.

## Превью по PTP — как у нас

По строкам Dart:

- **Тот же протокол Sony.** Полный набор операций `SDIO_*`: `SDIO_Connect`, `SDIO_GetExtDeviceInfo`, `SDIO_SetExtDevicePropValue`, `SDIO_ControlDevice`, `SDIO_GetAllExtDevicePropInfo` и другие. Это те же 0x92xx, что у нас. Транспорты называются `ptpUSB` и `ptpIP`.
- **Свойства превью — те же:**
  - `LiveviewImageQuality` (у нас 0xD26A), пункт меню «Change LiveView Quality». На отказ камеры есть сообщение «not supported by the device»;
  - `LiveViewStatus` (0xD221);
  - `LiveviewArea` (0xD267);
  - `LiveViewDisplayEffect` (0xD231);
  - `LiveViewUrl` (0xD278).
- **Два способа получать превью:** «LiveView streaming via PTP» и «LiveView streaming via HTTP». Второй, видимо, — `LiveViewProtocol = Alt` из Camera Remote SDK: камера отдаёт превью по HTTP-адресу из `LiveViewUrl`. По SDK он есть не у всех моделей. Размер кадра от этого не меняется: в SDK у качества превью всего Low = 640 и High = 1024 px в ширину.
- **Старые камеры** (до PTP по Wi-Fi) — через Sony Camera Remote API (`ScalarWebAPI`, поиск по UPnP `urn:schemas-sony-com:service:ScalarWebAPI:1`, `X_ScalarWebAPI_LiveView_URL`).
- **Есть перезапуск превью** («will be restarted after 400ms») и разбор заголовка кадра («Invaild liveview header») — как наш `0xFFFFC002` со смещением до JPEG.

Значит, по PTP Monitor+ не получает от ZV-E10 ничего сверх наших 1024×576. Списка «особых» моделей с большим превью нет. В строках упомянуты ZV-E1, FX30, FX3, a7 IV, a6x00 и другие, но не как таблица разрешений.

## Картинка по UVC — откуда качество

Это код `libnative.so`, видно по экспорту и строкам:

- **Захват видео — встроенный libuvc поверх libusb** (`_uvc_stream_callback`, `uvcStreamCallback`, `uvcGetLatestFrame`). На Android libusb работает с файловым дескриптором от `UsbManager`, без root. С Dart связаны функции `uvcConnect`, `uvcStartStream`, `uvcStartRetrieveFrameData`, `uvcRetrieveLatestFrameData`, `uvcGetDeviceInfo` и `uvcDisconnect`.
- **Форматы кадра:** MJPEG (через libyuv `MJpegDecoder` и libjpeg-turbo), YUY2 и NV12. Конвертация цвета и вывод — на OpenGL ES шейдерами (`shaderNV12_Full`/`_Limited`), с выбором цветового пространства и диапазона (`UVCColorSpace`, `UVCYCbCrEncoding`, `UVCTransferFunction`).
- **Звук — UAC (USB Audio Class)**, свой код (`uac.cpp`): изохронные передачи через libusb, разбор дескриптора аудио. Это нужно, чтобы писать звук с карты захвата или камеры. Есть и вариант «писать звук с телефона».
- **Помощь на кадре — тоже в `libnative.so`, на GPU:** гистограмма (`histRenderSingle`/`Overlay`/`Parade`), пикинг, false color, LUT (в ресурсах S-Log2/S-Log3/HLG → 709), хромакей и лупа (`rendererSetInspector`). Применяется одинаково и к JPEG превью PTP (`rendererProcessFromJPEG`), и к кадрам UVC.

## Что это значит для ZV-E10

- **Duplex с USB-стримингом самой ZV-E10 не получится.** По справке Sony, во время USB Streaming не работают PC Remote и подключение к смартфону. То есть управлять камерой в это время нельзя ни по кабелю, ни по Wi-Fi. Да и разъём USB у камеры один.
- **Рабочая схема Duplex для ZV-E10:** HDMI камеры → карта захвата (UVC) → телефон или планшет, а управление — по Wi-Fi (PTP/IP). Картинка — сколько отдаёт HDMI и карта (обычно 1080p, у дорогих карт 4K), с задержкой карты. Если взять USB-хаб, управление можно пустить и по кабелю.
- **У ZV-E10 II, ZV-E1 и FX30 так же:** во время их USB-стриминга сетевые функции, включая удалённую съёмку, отключены. Duplex для них — тоже через HDMI-карту.

## Что из этого можно взять нам

- **Mac — дёшево.** Карту захвата и камеру в USB-стриминге macOS видит как обычную камеру (AVFoundation, `AVCaptureDevice` внешнего типа). Duplex = кадры из `AVCaptureSession` вместо кадров PTP, плюс текущее управление по USB или Wi-Fi. Наши зебра, пикинг, false color и волновая форма считаются по кадру, так что заработают и на нём. Звук карты захвата тоже приходит через AVFoundation.
- **Android — дорого.** Общего API для внешних UVC-камер нет: `android.hardware.camera.external` есть не на всех устройствах. Monitor+ ради этого тащит свои libusb + libuvc + UAC в C++. Нам пришлось бы делать то же через NDK, либо проверять, видит ли Redmi Pad Pro карту захвата через Camera2.
- **Ограничение схемы.** Пока картинка идёт с карты, превью PTP не нужно, а наша адаптация качества по Wi-Fi теряет смысл: по Wi-Fi остаётся только управление.

## Что ещё можно взять, кроме карты захвата

Сверено с нашим README и с таблицей операций Sony (Camera Control PTP 3 Reference). Функции Monitor+ взяты из строк интерфейса в `libapp.so`. Как они устроены внутри, не смотрели.

Список операций ZV-E10 из её GetDeviceInfo (`Tests/ZVCoreTests/Fixtures/deviceinfo.bin`): `1001`–`100A`, `100D`, `101B`, `9201`, `9202`, `9205`, `9207`, `9209`–`9214`, `9801`–`9803`, `9805`. Команд Sony от `0x9215` и выше у неё нет.

### Стоит сделать, по убыванию пользы

1. **LUT для превью S-Log и HLG.** У Monitor+ есть LUT S-Log2, S-Log3 и HLG → 709, импорт своих LUT и переключатель «scopes до или после LUT». ZV-E10 снимает S-Log2, S-Log3 и HLG в Picture Profile, а у нас LUT нет. Сначала надо проверить на камере, приходит ли превью по PTP в логе, когда включён PP с S-Log. Если камера сама приводит его к 709 (Gamma Disp. Assist), LUT не нужен.
2. **Вектороскоп.** У Monitor+ он есть, с увеличением; у нас есть гистограмма, волновая форма и RGB-парад, а вектороскопа нет. Он ложится в наши плитки «Монитор» и считается по тому же кадру. Полезна линия тона кожи.
3. **Перевод фокуса A→B.** У Monitor+ есть «Focus Puller» с калибровкой и сохранением и возвратом положения зума и фокуса. У нас уже есть положение фокуса `0xD24C` (0…100) и шаги Near/Far. Можно поставить две метки и плавно переводить между ними за заданное время. Калибровка, видимо, меряет, на сколько единиц `0xD24C` сдвигает шаг у конкретного объектива.
4. **Зеркало и поворот превью** (Flip & Rotate, Live View Rotate). Для влога, когда планшет стоит лицом к снимающему, и для перевёрнутого риг-монтажа. Делается только на экране, дёшево.
5. **Кнопки громкости на Android** (Volume Key Record): запись или спуск кнопкой громкости. Bluetooth-пульты для телефонов шлют те же коды, так что заработают и они.
6. **Пипетка:** касание показывает яркость в % и RGB точки. У Monitor+ это color picking. Дополняет false color.
7. **Файлы с карты**: список, скачивание, превью роликов. У Monitor+ это раздел медиа. ZV-E10 понимает `0x9210` SDIO_OpenSession (с режимом), `0x9211` SDIO_GetPartialLargeObject и `0x9212` SDIO_SetContentsTransferMode. Значит, перенос файлов по протоколу возможен. Это большая работа, и в первом дизайне она была в «не целях».

### Не стоит или не к ZV-E10

- **Запись превью на устройство** (Record Live View Feeds) — 1024×576 и 25 к/с, толку мало.
- **Хромакей, desqueeze анаморфота, Apple Watch** — узкие вещи.
- **Подключение по Wi-Fi через Bluetooth или QR-код.** Monitor+ по Wi-Fi работает в режиме камеры «Управление со смартфона», а не PC Remote. У нас Wi-Fi через PC Remote уже работает.
- **`0x9215` SDIO_GetDisplayStringList** (подписи значений от самой камеры) **и `0x9223` SDIO_GetLensInformation.** У ZV-E10 их нет. Пригодятся для новых камер, чтобы вместо `#N` показывать подписи камеры.

## Чего не знаем

- Дальше строк логику Dart не смотрели. Неизвестно, синхронизирует ли Monitor+ в Duplex что-то, кроме управления (например, рамку фокуса по координатам UVC-кадра).
- Как Monitor+ выбирает формат и разрешение UVC (`uvcGetDeviceInfo`, «doesn't support for the resolution or format»), видно только по сообщениям.
- Метод приёма `UsbHost.n()` jadx не декомпилировал, поэтому размер буфера чтения по USB неизвестен.

## Как повторить

```bash
cd research/monitor-plus
unzip -q -n mpf-1.4.4.apk -d work/unzipped
JAVA_HOME=/opt/homebrew/opt/openjdk@21 PATH=/opt/homebrew/opt/openjdk@21/bin:$PATH jadx -q --no-res -d work/jadx mpf-1.4.4.apk
strings -n 4 work/unzipped/lib/arm64-v8a/libapp.so > work/libapp.strings.txt
nm -D --defined-only work/unzipped/lib/arm64-v8a/libnative.so | grep -i -E "uvc|uac|renderer"
```

APK и `work/` в git не попадают (`.gitignore` в этой папке): это чужой код.
