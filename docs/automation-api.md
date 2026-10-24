# API автоматизации

ZV-E10 Viewer умеет принимать команды по локальному HTTP. Через API можно:

- узнать состояние камеры и экрана;
- нажимать те же кнопки, что и мышью;
- менять настройки камеры и программы;
- снимать скриншоты окна и кадры превью.

API сделан для автоматических тестов и скриптов. По умолчанию он выключен.

- [Включение](#включение)
- [Запросы и ответы](#запросы-и-ответы)
- [GET /state](#get-state)
- [POST /action](#post-action)
- [GET /screenshot и GET /frame](#get-screenshot-и-get-frame)
- [Примеры](#примеры)
- [Безопасность](#безопасность)

## Включение

Запустите программу с флагом `--automation`:

```bash
open "/Applications/ZV-E10 Viewer.app" --args --automation
```

Если программа уже запущена, сначала закройте её: уже запущенной программе `open` флаг не передаст.

Если запускать сам исполняемый файл `ZV-E10 Viewer.app/Contents/MacOS/ZVViewer`, вместо флага можно задать переменную окружения `ZV_AUTOMATION=1`. Порт по умолчанию — 47811, другой задаётся переменной `ZV_AUTOMATION_PORT`.

При запуске программа создаёт случайный токен и записывает порт и токен в файл, который может читать только текущий пользователь:

```
~/Library/Application Support/ZV-E10 Viewer/automation.json
{"port": 47811, "token": "…"}
```

Токен меняется при каждом запуске.

## Запросы и ответы

- Сервер слушает только `127.0.0.1`: с других компьютеров он недоступен.
- Каждый запрос должен нести заголовок `X-ZV-Token: <токен>`.
- После ответа сервер закрывает соединение: один запрос — одно соединение.
- Тело запроса — JSON, не больше 64 КБ, с заголовком `Content-Length`.

| Код | Когда |
|---|---|
| 200 | готово |
| 400 | запрос не разобран: битый HTTP или JSON, неизвестная команда, не хватает параметра |
| 401 | нет токена или он неверный |
| 404 | неизвестный путь |
| 409 | команду сейчас выполнить нельзя; в ответе `{"error": "…"}` с причиной |

Ошибки приходят в виде `{"error": "текст"}`.

Команды выполняются так же, как нажатия кнопок: ответ 200 значит «команда принята», а не «камера уже применила». Чтобы дождаться результата, опрашивайте `/state`. Например, ждите, пока у настройки пропадёт `pending` и `value` станет нужным.

## GET /state

Состояние программы и камеры в JSON.

| Поле | Тип | Что это |
|---|---|---|
| `phase` | строка | `searching` — ищет камеру, `notPCRemote` — камера не в режиме PC Remote, `connecting` — подключается, `settling` — первые секунды после подключения, `ready` — готова |
| `model`, `firmware` | строка или `null` | модель и прошивка камеры |
| `mode` | строка | `video` или `photo` — по режиму съёмки камеры |
| `recording` | bool | идёт запись видео |
| `recordingSeconds` | число или `null` | сколько секунд идёт запись |
| `capturing` | bool | идёт съёмка фото |
| `fps` | число | кадров превью в секунду за последние 2 с |
| `frameSize` | `[ширина, высота]` | размер кадра превью; нет поля, пока кадров нет |
| `histogramBins` | число | 64, когда гистограмма посчитана, иначе 0 |
| `applyingPreset` | bool | применяется пресет |
| `status` | строка или `null` | сообщение под пресетами, например «Сохранено: …» |
| `review` | объект или `null` | просмотр снимка поверх видео: `title`, `source` («Загруженный снимок», «Кадр с камеры», «Снимок из ленты»), `timed` (закроется сам), `hasImage`, `files` (пути) |
| `shots` | массив | снимки и стоп-кадры сессии, новые сначала: `title`, `files` |
| `prefs` | объект | настройки программы: `destination`, `folder`, `reviewEnabled`, `reviewSeconds`, `reviewSource`, `autoHide` |
| `cameraDestination` | число или `null` | куда камера сохраняет снимки (`0xD222`): 1 — Mac, 16 — карта, 17 — оба |
| `presets` | массив | `name` и `values` — словарь «настройка → значение» |
| `ui` | объект | что на экране: `fnOpen`, `allSettingsOpen`, `selected`, `group`, `editing` (если открыта строка), `histogram`, `controlsHidden`, `manualHidden` |
| `settings` | массив | все 24 настройки, см. ниже |

Элемент `settings`:

| Поле | Что это |
|---|---|
| `id` | идентификатор настройки, см. список ниже |
| `code` | код свойства камеры, например `"0x5005"` |
| `title` | название, как в программе |
| `label` | значение, как его показывает программа, например `"1/50"` |
| `value` | значение в кодировке камеры (число); нет поля, если камера его не отдала |
| `writable` | можно ли менять прямо сейчас |
| `pending` | запрошено новое значение, камера его ещё не подтвердила |
| `options` | допустимые значения: `[{"value": …, "label": …}]`; пустой список, если менять нельзя |

Идентификаторы настроек: `exposureProgram`, `shutter`, `aperture`, `iso`, `exposureComp`, `metering`, `dro`, `focusMode`, `focusArea`, `whiteBalance`, `colorTemperature`, `wbShiftAB`, `wbShiftGM`, `creativeStyle`, `pictureProfile`, `movieFileFormat`, `movieRecordingSetting`, `imageSize`, `aspectRatio`, `fileFormat`, `jpegQuality`, `battery`, `cardRemaining`, `overheat`.

Группы для `openAllSettings`: `exposure`, `focus`, `color`, `video`, `photo`, `camera`.

Значения в кодировке камеры описаны в [Протоколе камеры](protocol.md#значения-свойств).

## POST /action

Тело — `{"action": "<команда>", …параметры}`. Ответ 200 — `{"ok": true}`.

### Экран

| Команда | Параметры | Что делает |
|---|---|---|
| `key` | `key`: `"f"`, `"escape"`, `"left"`, `"right"`, `"1"`…`"9"` | как клавиша: меню Fn, закрыть, шаг значения (только при открытом меню), пресет по номеру |
| `openFn` | `setting` — необязательно | открыть меню Fn, при указанной настройке — сразу на ней |
| `closeOverlays` | — | закрыть меню Fn и «Все настройки» |
| `select` | `setting` | выбрать настройку в меню Fn; 409, если её сейчас нельзя менять |
| `openAllSettings` | `group` — необязательно | открыть «Все настройки», при указанной группе — на ней |
| `editRow` | `setting` — необязательно | открыть ленту значений у строки в «Все настройки»; без параметра — закрыть |
| `toggleHistogram` | — | включить или выключить гистограмму |
| `toggleControls` | — | как клавиша H: спрятать или показать элементы |
| `dismissReview` | — | закрыть просмотр снимка |
| `openShot` | `index` | открыть снимок из ленты (0 — самый новый) |

### Настройки камеры

| Команда | Параметры | Что делает |
|---|---|---|
| `request` | `setting`, `value` (число) | поставить значение; 409, если настройку сейчас нельзя менять. Значение должно быть из `options`, иначе камера его не получит |
| `step` | `offset` (число, например 1 или −1) | сдвинуть выбранную в меню Fn настройку на соседнее значение |
| `switchMode` | `mode`: `"photo"` или `"video"` | переключить камеру в фото или видео с той же буквой режима; 409, если режим сейчас менять нельзя |

### Пресеты

| Команда | Параметры | Что делает |
|---|---|---|
| `savePreset` | `name` — необязательно | сохранить текущие настройки; 409, если камера не готова |
| `applyPreset` | `index` (с 0) | применить пресет |
| `renamePreset` | `index`, `name` | переименовать |
| `deletePreset` | `index` | удалить |

### Съёмка

| Команда | Параметры | Что делает |
|---|---|---|
| `toggleRecording` | `confirm: true` | начать или остановить запись видео на карту |
| `capture` | `confirm: true` | сделать снимок; 409, если камера не в фоторежиме или снимок уже идёт |
| `grabFrame` | — | сохранить стоп-кадр из превью; 409, если кадров ещё нет |

`toggleRecording` и `capture` пишут файлы на карту камеры или на Mac. Без `"confirm": true` они отвечают 409 и ничего не делают.

### Настройки программы

`setPreference` с параметрами `key` и `value`; оба — строки:

| `key` | `value` |
|---|---|
| `destination` | `macAndCard`, `macOnly`, `cardOnly`; сразу сообщается камере |
| `folder` | путь к папке, `~` раскрывается |
| `reviewEnabled` | `true`, `false` |
| `reviewSeconds` | `1`…`10` |
| `reviewSource` | `downloaded`, `camera` |
| `autoHide` | `true`, `false` |

Неизвестный ключ или недопустимое значение — 409.

## GET /screenshot и GET /frame

- `GET /screenshot` — PNG окна программы, каким оно выглядит сейчас. Снимается самим окном, поэтому разрешение на запись экрана не нужно. 409, если окна нет.
- `GET /frame` — последний кадр превью в JPEG, как он пришёл с камеры. 409, если кадров ещё нет.

## Примеры

Проще всего — `scripts/zvapi.py`: он сам читает порт и токен.

```bash
scripts/zvapi.py GET /state
scripts/zvapi.py POST /action '{"action": "openFn", "setting": "whiteBalance"}'
scripts/zvapi.py GET /screenshot > window.png
scripts/zvapi.py GET /frame > frame.jpg
```

То же через curl:

```bash
INFO="$HOME/Library/Application Support/ZV-E10 Viewer/automation.json"
PORT=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["port"])' "$INFO")
TOKEN=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["token"])' "$INFO")

curl -s -H "X-ZV-Token: $TOKEN" "http://127.0.0.1:$PORT/state"
curl -s -H "X-ZV-Token: $TOKEN" -d '{"action": "request", "setting": "iso", "value": 800}' "http://127.0.0.1:$PORT/action"
```

Поставить значение и дождаться подтверждения, на Python:

```python
import json, os, time, urllib.request

info = json.load(open(os.path.expanduser("~/Library/Application Support/ZV-E10 Viewer/automation.json")))

def call(method, path, body=None):
    req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}", method=method,
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", info["token"])
    with urllib.request.urlopen(req, timeout=15) as r:
        return json.loads(r.read())

def setting(sid):
    return next(x for x in call("GET", "/state")["settings"] if x["id"] == sid)

call("POST", "/action", {"action": "request", "setting": "iso", "value": 800})
deadline = time.time() + 5
while time.time() < deadline and (setting("iso")["pending"] or setting("iso").get("value") != 800):
    time.sleep(0.2)
print(setting("iso")["label"])
```

Готовые сценарии — `scripts/e2e.py` и `scripts/e2e_photo.py` (см. [Устройство кода](architecture.md#тесты)).

## Безопасность

- Без флага `--automation` и без `ZV_AUTOMATION=1` сервер не запускается.
- Сервер слушает только `127.0.0.1`.
- Без токена ответ — 401. Токен случайный на каждый запуск, файл с ним доступен только текущему пользователю.
- Запись видео и снимок требуют `"confirm": true`.

API может менять настройки камеры. Если пишете свои сценарии, запоминайте исходные значения и возвращайте их в конце, как это делают `e2e.py` и `e2e_photo.py`.
