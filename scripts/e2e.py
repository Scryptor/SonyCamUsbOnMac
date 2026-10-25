#!/usr/bin/env python3
"""Сквозной тест Sony Cam Viewer через локальный API автоматизации.
Запоминает исходные настройки камеры и в конце возвращает их, проверяя чтением."""
import json, os, sys, time, urllib.request, urllib.error

OUT = sys.argv[1]
info = json.load(open(os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")))
BASE = f"http://127.0.0.1:{info['port']}"
TOKEN = info["token"]
results = []

def call(method, path, body=None, token=True):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    if token:
        req.add_header("X-ZV-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def state():
    code, body = call("GET", "/state")
    assert code == 200, (code, body)
    return json.loads(body)

def act(**body):
    return call("POST", "/action", body)

def setting(s, sid):
    return next(x for x in s["settings"] if x["id"] == sid)

def check(name, ok, detail=""):
    results.append((name, bool(ok), detail))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)

def shot(name):
    code, png = call("GET", "/screenshot")
    if code == 200:
        open(f"{OUT}/{name}.png", "wb").write(png)
    return code == 200 and png[:4] == b"\x89PNG"

def wait(pred, timeout=8.0, interval=0.15):
    deadline = time.time() + timeout
    s = state()
    while time.time() < deadline:
        if pred(s):
            return s, True
        time.sleep(interval)
        s = state()
    return s, pred(s)

def settled(sid, value):
    return lambda s: setting(s, sid).get("value") == value and not setting(s, sid)["pending"]

# 0. Камера готова
s, ok = wait(lambda s: s["phase"] == "ready", timeout=40)
check("камера в фазе ready", ok, s["phase"])
baseline = {x["id"]: x.get("value") for x in s["settings"]}
writable_at_start = {x["id"] for x in s["settings"] if x["writable"]}
hist_at_start = s["ui"]["histogram"]
presets_at_start = len(s["presets"])
print("baseline:", {k: setting(s, k)["label"] for k in ["shutter", "aperture", "iso", "whiteBalance", "exposureComp", "focusMode"]})

# 1. Защита и служебные точки
check("без токена — 401", call("GET", "/state", token=False)[0] == 401)
check("неизвестная команда — 400", act(action="fly")[0] == 400)
code, jpeg = call("GET", "/frame")
check("/frame отдаёт JPEG", code == 200 and jpeg[:2] == b"\xff\xd8", f"{len(jpeg)} байт")
s = state()
check("live view идёт", s["fps"] >= 15 and s.get("frameSize") == [1024, 576], f"fps {s['fps']}, {s.get('frameSize')}")
check("скриншот обычного вида", shot("01-normal"))

# 2. Гистограмма
if not s["ui"]["histogram"]:
    act(action="toggleHistogram")
s = state()
check("гистограмма включается", s["ui"]["histogram"] and s["histogramBins"] == 64)
check("скриншот с гистограммой", shot("02-histogram"))

# 3. Меню Fn и шаги диафрагмы
act(action="key", key="f")
s = state()
check("F открывает меню Fn", s["ui"]["fnOpen"])
check("скриншот Fn", shot("03-fn"))
ap = setting(s, "aperture")
if ap["writable"]:
    act(action="select", setting="aperture")
    opts = [o["value"] for o in ap["options"]]
    i = opts.index(ap["value"])
    nxt = opts[min(i + 1, len(opts) - 1)]
    act(action="key", key="right")
    s, ok = wait(settled("aperture", nxt))
    check("→ меняет диафрагму на соседнюю", ok, setting(s, "aperture")["label"])
    act(action="key", key="left")
    s, ok = wait(settled("aperture", ap["value"]))
    check("← возвращает диафрагму", ok, setting(s, "aperture")["label"])
else:
    check("диафрагма доступна для изменения", False, "камера отдаёт её только для чтения")

# 4. Быстрые шаги ISO (имитация колеса): только последнее значение, без перелёта
iso = setting(state(), "iso")
if iso["writable"]:
    act(action="select", setting="iso")
    opts = [o["value"] for o in iso["options"]]
    i = opts.index(iso["value"])
    target = opts[min(i + 6, len(opts) - 1)]
    for _ in range(6):
        act(action="step", offset=1)
    s, ok = wait(settled("iso", target), timeout=12)
    check("6 быстрых шагов ISO → ровно +6 позиций", ok, f"{setting(s, 'iso')['label']} (ожидали {next(o['label'] for o in iso['options'] if o['value'] == target)})")
    act(action="request", setting="iso", value=iso["value"])
    s, ok = wait(settled("iso", iso["value"]))
    check("ISO возвращено", ok, setting(s, "iso")["label"])
    # недопустимое значение не уходит в камеру
    act(action="request", setting="iso", value=12345)
    time.sleep(1.5)
    s = state()
    check("недопустимое ISO не меняет камеру", setting(s, "iso")["value"] == iso["value"] and not setting(s, "iso")["pending"])

# 5. Экспокоррекция: если камера её не даёт менять, выбрать нельзя
ev = setting(state(), "exposureComp")
if not ev["writable"]:
    check("недоступная экспокоррекция не выбирается", act(action="select", setting="exposureComp")[0] == 409)

# 6. Баланс белого и сдвиг
act(action="select", setting="whiteBalance")
act(action="request", setting="whiteBalance", value=4)
s, ok = wait(settled("whiteBalance", 4))
check("баланс белого → Дневной", ok, setting(s, "whiteBalance")["label"])
check("скриншот Fn с балансом белого", shot("04-fn-wb"))
act(action="request", setting="whiteBalance", value=baseline["whiteBalance"])
s, ok = wait(settled("whiteBalance", baseline["whiteBalance"]))
check("баланс белого возвращён", ok, setting(s, "whiteBalance")["label"])
if setting(s, "wbShiftAB")["writable"]:
    act(action="request", setting="wbShiftAB", value=196)
    s, ok = wait(settled("wbShiftAB", 196))
    check("сдвиг A–B → +1", ok, setting(s, "wbShiftAB")["label"])
    act(action="request", setting="wbShiftAB", value=baseline["wbShiftAB"])
    s, ok = wait(settled("wbShiftAB", baseline["wbShiftAB"]))
    check("сдвиг A–B возвращён", ok, setting(s, "wbShiftAB")["label"])
act(action="key", key="escape")
check("Esc закрывает меню", not state()["ui"]["fnOpen"])

# 7. Все настройки: каждая группа
for group in ["exposure", "focus", "color", "video", "photo", "camera"]:
    act(action="openAllSettings", group=group)
    s = state()
    check(f"группа {group} открыта", s["ui"]["allSettingsOpen"] and s["ui"]["group"] == group)
    shot(f"05-all-{group}")
act(action="openAllSettings", group="color")
act(action="editRow", setting="pictureProfile")
check("скриншот ленты в «Все настройки»", shot("06-all-edit"))
act(action="closeOverlays")

# 8. Пресеты: сохранить → изменить → применить → проверить → удалить
act(action="savePreset", name="Тест")
s = state()
check("пресет сохранён", len(s["presets"]) == presets_at_start + 1 and s["presets"][-1]["name"] == "Тест")
idx = len(s["presets"]) - 1
saved = s["presets"][-1]["values"]
changes = {}
for sid in ["aperture", "iso", "whiteBalance"]:
    x = setting(s, sid)
    if x["writable"] and x.get("options"):
        alt = next((o["value"] for o in x["options"] if o["value"] != x["value"]), None)
        if alt is not None:
            changes[sid] = alt
            act(action="request", setting=sid, value=alt)
            wait(settled(sid, alt))
check("скриншот с пресетом", shot("07-presets"))
act(action="applyPreset", index=idx)
s, ok = wait(lambda s: not s["applyingPreset"] and all(setting(s, k).get("value") == saved.get(k) for k in changes), timeout=20)
check("пресет вернул изменённые настройки", ok, s["status"] or "")
act(action="renamePreset", index=idx, name="Тест 2")
check("пресет переименован", state()["presets"][idx]["name"] == "Тест 2")
act(action="deletePreset", index=idx)
check("пресет удалён", len(state()["presets"]) == presets_at_start)

# 9. Итог: камера в исходном состоянии
s = state()
diffs = {k: (baseline[k], setting(s, k).get("value")) for k in baseline if k in writable_at_start and setting(s, k).get("value") != baseline[k]}
for k, (want, _) in diffs.items():
    act(action="request", setting=k, value=want)
    wait(settled(k, want))
s = state()
left = {k: (baseline[k], setting(s, k).get("value")) for k in baseline if k in writable_at_start and setting(s, k).get("value") != baseline[k]}
check("камера возвращена в исходное состояние", not left, f"восстановлено {len(diffs)}, расхождений {len(left)}")
if state()["ui"]["histogram"] != hist_at_start:
    act(action="toggleHistogram")

print(f"\nИТОГ: {sum(r[1] for r in results)}/{len(results)} проверок прошли")
