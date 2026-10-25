#!/usr/bin/env python3
"""Сквозной тест режимов: режим записи (смена формата, «передумали», быстрые шаги), S&Q, цветовая температура,
подсказки из плашки. Запоминает исходные настройки камеры и в конце возвращает их, проверяя чтением.
Запуск: scripts/e2e_modes.py <папка для скриншотов>; камера — в видеорежиме, PAL (25/50/100p)."""
import json, os, sys, time, urllib.request, urllib.error
OUT = sys.argv[1]
info = json.load(open(os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")))
BASE = f"http://127.0.0.1:{info['port']}"; TOKEN = info["token"]; results = []
def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()
def state(): return json.loads(call("GET", "/state")[1])
def act(**b): return call("POST", "/action", b)
def setting(s, sid): return next(x for x in s["settings"] if x["id"] == sid)
def check(name, ok, detail=""):
    results.append((name, bool(ok))); print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)
def shot(name):
    code, png = call("GET", "/screenshot")
    if code == 200: open(f"{OUT}/{name}.png", "wb").write(png)
    return code == 200
def wait(pred, timeout=10.0, interval=0.15):
    deadline = time.time() + timeout; s = state()
    while time.time() < deadline:
        if pred(s): return s, True
        time.sleep(interval); s = state()
    return s, pred(s)
def mode_is(code):
    def p(s):
        x = setting(s, "movieRecordingSetting")
        return x.get("mode") == code and not x["pending"] and not setting(s, "movieFileFormat")["pending"]
    return p
def label_of(s): return setting(s, "movieRecordingSetting")["label"]

s, ok = wait(lambda s: s["phase"] == "ready", 30); check("ready", ok)
baseline = {x["id"]: x.get("value") for x in s["settings"]}
writable = {x["id"] for x in s["settings"] if x["writable"]}
base_mode = setting(s, "movieRecordingSetting")["mode"]
print("baseline mode", base_mode, label_of(s))

# A. Режим записи
t = time.time(); act(action="request", setting="movieRecordingSetting", value=2333)
s, ok = wait(mode_is(2333), 15); check("4K → HD 100p 100M (смена формата)", ok, f"{label_of(s)} за {time.time()-t:.1f} с")
act(action="request", setting="movieRecordingSetting", value=2084); time.sleep(0.3)
act(action="request", setting="movieRecordingSetting", value=2331)
s, ok = wait(mode_is(2331), 15); check("передумали во время смены на 4K → остались на HD 25p 16M", ok, label_of(s))
act(action="request", setting="movieRecordingSetting", value=2084); time.sleep(0.3)
act(action="request", setting="movieRecordingSetting", value=2081)
s, ok = wait(mode_is(2081), 15); check("во время смены на 4K 60M выбрали 4K 100M → 4K 25p 100M", ok, label_of(s))
act(action="request", setting="movieRecordingSetting", value=2333); time.sleep(0.3)
act(action="request", setting="movieRecordingSetting", value=2084)
s, ok = wait(mode_is(2084), 15); check("во время смены на HD выбрали 4K 60M → 4K 25p 60M", ok, label_of(s))
time.sleep(1.0); s = state(); check("через секунду режим не уехал", mode_is(2084)(s), label_of(s))
act(action="request", setting="movieRecordingSetting", value=2081)
s, ok = wait(mode_is(2081), 15); check("обратно 4K 25p 100M", ok, label_of(s))
# быстрые шаги колесом в меню Fn
act(action="openFn", setting="movieRecordingSetting")
opts = [o["value"] for o in setting(state(), "movieRecordingSetting")["options"]]
i = opts.index(2081); target = opts[i + 3]
for _ in range(3): act(action="step", offset=1)
s, ok = wait(mode_is(target), 15); check("3 быстрых шага «Запись» → ровно +3 (через смену формата)", ok, f"{label_of(s)}")
for _ in range(3): act(action="step", offset=-1)
s, ok = wait(mode_is(2081), 15); check("3 шага назад → 4K 25p 100M", ok, label_of(s))
shot("rec-strip")
act(action="closeOverlays")

# B. S&Q
t = time.time(); code, body = act(action="switchMode", mode="slowQuick")
s, ok = wait(lambda s: s["shootingMode"] == "slowQuick" and s["slowMotion"] is not None, 15)
check("видео → S&Q", ok and code == 200, f"{setting(s,'exposureProgram')['label']}, {s['slowMotion']} за {time.time()-t:.1f} с")
time.sleep(1.5); s = state()
check("в S&Q известна частота съёмки", s["slowMotion"] and s["slowMotion"].get("captureFps"), str(s["slowMotion"]))
shot("sq-normal")
code, _ = act(action="showInfo", name="slowMotion"); s = state()
check("подсказка S&Q открывается", code == 200 and s["ui"].get("info") == "slowMotion", str(s["ui"].get("info")))
shot("sq-info")
act(action="showInfo"); check("подсказка закрывается", state()["ui"].get("info") is None)
code, _ = act(action="switchMode", mode="video")
s, ok = wait(lambda s: s["shootingMode"] == "video" and setting(s, "exposureProgram")["value"] == baseline["exposureProgram"], 15)
check("S&Q → видео с той же буквой", ok, setting(s, "exposureProgram")["label"])
code, _ = act(action="showInfo", name="slowMotion"); check("вне S&Q подсказка S&Q — 409", code == 409)

# C. Температура
code, _ = act(action="request", setting="colorTemperature", value=99999); time.sleep(1.0); s = state()
check("температура вне диапазона — 409, баланс не тронут", code == 409 and setting(s, "whiteBalance")["value"] == baseline["whiteBalance"], f"{code}, {setting(s,'whiteBalance')['label']}")
code, _ = act(action="request", setting="colorTemperature", value=5650); time.sleep(0.8); s = state()
check("температура не по шагу — 409", code == 409 and setting(s, "whiteBalance")["value"] == baseline["whiteBalance"], str(code))
code, _ = act(action="request", setting="colorTemperature", value=5600)
s, ok = wait(lambda s: setting(s, "whiteBalance")["value"] == 32786 and setting(s, "colorTemperature").get("value") == 5600 and not setting(s, "colorTemperature")["pending"], 12)
check("ползунок 5600 K из AWB → «Цвет. темп.» 5600 K", ok and code == 200, f"{setting(s,'whiteBalance')['label']} / {setting(s,'colorTemperature')['label']}")
act(action="openFn", setting="whiteBalance"); shot("wb-ctemp"); act(action="closeOverlays")
act(action="request", setting="colorTemperature", value=baseline["colorTemperature"])
s, ok = wait(lambda s: setting(s, "colorTemperature").get("value") == baseline["colorTemperature"] and not setting(s, "colorTemperature")["pending"], 12)
check("ручная температура возвращена", ok, setting(s, "colorTemperature")["label"])
act(action="request", setting="whiteBalance", value=baseline["whiteBalance"])
s, ok = wait(lambda s: setting(s, "whiteBalance")["value"] == baseline["whiteBalance"] and not setting(s, "whiteBalance")["pending"], 12)
check("баланс белого возвращён", ok, setting(s, "whiteBalance")["label"] + " / " + setting(s, "colorTemperature")["label"])

# D. Подсказки плашки
for name in ["connection", "frames"]:
    code, _ = act(action="showInfo", name=name); s = state()
    check(f"подсказка {name}", code == 200 and s["ui"].get("info") == name)
    shot(f"info-{name}")
act(action="showInfo")

# E. Формат отдельно (строка «Формат» в «Все настройки»): камера получает и параметры
act(action="request", setting="movieFileFormat", value=9)
s, ok = wait(lambda s: setting(s, "movieFileFormat")["value"] == 9 and not setting(s, "movieRecordingSetting")["pending"] and not setting(s, "movieFileFormat")["pending"], 15)
check("формат HD из строки «Формат» → HD 25p 50M (та же частота)", ok and setting(s, "movieRecordingSetting")["mode"] == 2309, label_of(s))
act(action="request", setting="movieFileFormat", value=8)
s, ok = wait(mode_is(2081), 15); check("формат 4K → 4K 25p 100M", ok, label_of(s))
# F. Пять быстрых шагов вперёд и сразу три назад
act(action="openFn", setting="movieRecordingSetting")
for _ in range(5): act(action="step", offset=1)
for _ in range(3): act(action="step", offset=-1)
s, ok = wait(mode_is(opts[2]), 20); check("+5 −3 быстро → ровно +2", ok, label_of(s))
act(action="request", setting="movieRecordingSetting", value=base_mode)
s, ok = wait(mode_is(base_mode), 15); check("вернули исходный режим", ok, label_of(s))
act(action="closeOverlays")
# Итог
s = state()
diffs = {k: (baseline[k], setting(s, k).get("value")) for k in baseline if k in writable and setting(s, k).get("value") != baseline[k]}
for k, (want, _) in diffs.items():
    act(action="request", setting=k, value=want); wait(lambda s: setting(s, k).get("value") == want and not setting(s, k)["pending"])
s = state()
left = {k: (baseline[k], setting(s, k).get("value")) for k in baseline if k in writable and setting(s, k).get("value") != baseline[k]}
check("камера в исходном состоянии", not left and setting(s, "movieRecordingSetting")["mode"] == base_mode, f"восстановлено {list(diffs)}, осталось {left}")
print(f"\nИТОГ: {sum(r[1] for r in results)}/{len(results)}")
