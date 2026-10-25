#!/usr/bin/env python3
"""Сквозной тест фоторежима, стоп-кадра и скрытия элементов через API автоматизации.
Снимки — «только на Mac» во временную папку (на карту ничего не пишется).
Режим камеры, место сохранения и настройки программы возвращаются в конце, даже при сбое.

Запуск: scripts/e2e_photo.py <папка-для-результатов>"""
import json, os, sys, time, urllib.request, urllib.error

OUT = sys.argv[1]
FILES = os.path.join(OUT, "files")
os.makedirs(FILES, exist_ok=True)
info = json.load(open(os.path.expanduser("~/Library/Application Support/ZV-E10 Viewer/automation.json")))
BASE, TOKEN = f"http://127.0.0.1:{info['port']}", info["token"]
results = []

def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def state():
    code, body = call("GET", "/state")
    assert code == 200, (code, body)
    return json.loads(body)

def act(**body):
    code, data = call("POST", "/action", body)
    return code, json.loads(data) if data else {}

def pref(key, value):
    return act(action="setPreference", key=key, value=str(value).lower() if isinstance(value, bool) else str(value))

def setting(s, sid):
    return next(x for x in s["settings"] if x["id"] == sid)

def check(name, ok, detail=""):
    results.append((name, bool(ok)))
    print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)

def shot(name):
    code, png = call("GET", "/screenshot")
    if code == 200:
        open(os.path.join(OUT, f"{name}.png"), "wb").write(png)
    return code == 200

def wait(pred, timeout=10.0, interval=0.2):
    deadline, s = time.time() + timeout, state()
    while time.time() < deadline and not pred(s):
        time.sleep(interval)
        s = state()
    return s, pred(s)

s, ok = wait(lambda s: s["phase"] == "ready", timeout=40)
check("камера готова", ok)
baseline_prefs = dict(s["prefs"])
baseline_mode_value = setting(s, "exposureProgram")["value"]
baseline_mode = s["mode"]
# фото, видео или S&Q — возвращать надо именно его, а не «видео» вообще
baseline_shooting = s.get("shootingMode", baseline_mode)
baseline_values = {x["id"]: x.get("value") for x in s["settings"] if x["writable"]}
baseline_dest = s["cameraDestination"]
shots_before = len(s["shots"])
print("baseline:", baseline_mode, hex(baseline_mode_value), "dest", hex(baseline_dest or 0), baseline_prefs)

try:
    # 1. Настройки для теста
    for k, v in [("folder", FILES), ("reviewEnabled", True), ("reviewSeconds", 2), ("reviewSource", "downloaded"), ("autoHide", False)]:
        pref(k, v)
    pref("destination", "macOnly")
    s, ok = wait(lambda s: s["cameraDestination"] == 0x01)
    check("место сохранения «только Mac» записано в камеру (0x01)", ok, hex(s["cameraDestination"] or 0))

    # 2. Видео → фото
    act(action="switchMode", mode="photo")
    s, ok = wait(lambda s: s["mode"] == "photo" and not setting(s, "exposureProgram")["pending"])
    check("переключение в фото", ok, setting(s, "exposureProgram")["label"])
    s, ok = wait(lambda s: s.get("frameSize") and s["frameSize"][0] == s["frameSize"][1], timeout=6)
    check("live view стал квадратным (фото 1:1)", ok, str(s.get("frameSize")))
    check("в фото доступен формат снимка", setting(s, "fileFormat")["writable"], setting(s, "fileFormat")["label"])
    shot("p01-photo")

    # 3. Снимок с просмотром: появляется, когда камера сработала, и идёт ровно заданное время с этого момента;
    # скачался раньше — встаёт на место кадра, нет (RAW+JPEG по Wi-Fi) — просмотр закрывается вовремя, а загрузку
    # показывает кнопка спуска
    code, _ = act(action="capture")
    check("снимок без confirm отклоняется", code == 409)
    act(action="capture", confirm=True)
    s, ok = wait(lambda s: s["review"] is not None, timeout=6)
    check("просмотр появился, когда камера сработала", ok and s["review"]["timed"],
          json.dumps(s["review"], ensure_ascii=False)[:120] if s and s["review"] else "нет просмотра")
    shot("p02-review")
    shown_at, swapped = time.time(), False
    while time.time() - shown_at < 8:
        rv = state()["review"]
        if rv is None:
            break
        swapped |= rv["source"] == "Загруженный снимок" and rv["hasImage"]
        time.sleep(0.1)
    lasted = time.time() - shown_at
    check("просмотр идёт заданное время с момента спуска", abs(lasted - 2) < 0.8,
          f"{lasted:.1f} с при 2 с; скачанный снимок {'встал на место кадра' if swapped else 'не успел — загрузку показывает кнопка'}")
    s, ok = wait(lambda s: not s["capturing"] and len(s["shots"]) > shots_before, timeout=25)
    check("снимок сделан и скачан", ok, s["status"] or "")
    files = s["shots"][0]["files"] if s["shots"] else []
    exts = sorted(os.path.splitext(f)[1].upper() for f in files)
    check("файлы на месте", files and all(os.path.getsize(f) > 0 for f in files), ", ".join(os.path.basename(f) for f in files))
    for f in files:
        head = open(f, "rb").read(4)
        if f.upper().endswith(".JPG"):
            check("JPEG настоящий", head[:2] == b"\xff\xd8", os.path.basename(f))
        if f.upper().endswith(".ARW"):
            check("ARW настоящий (TIFF)", head == b"II*\x00", os.path.basename(f))

    # 4. Просмотр кадра с камеры, закрытие вручную
    pref("reviewSource", "camera")
    act(action="capture", confirm=True)
    s, ok = wait(lambda s: s["review"] is not None, timeout=5)
    check("просмотр кадра с камеры появился сразу", ok and s["review"]["source"] == "Кадр с камеры")
    wait(lambda s: not s["capturing"], timeout=25)
    act(action="dismissReview")
    check("просмотр закрывается вручную", state()["review"] is None)

    # 5. Без просмотра
    pref("reviewEnabled", False)
    n = len(state()["shots"])
    act(action="capture", confirm=True)
    seen_review = False
    deadline = time.time() + 25
    while time.time() < deadline:
        s = state()
        seen_review |= s["review"] is not None
        if not s["capturing"] and len(s["shots"]) > n:
            break
        time.sleep(0.2)
    check("с выключенным просмотром снимок без паузы", not seen_review and len(s["shots"]) > n)

    # 6. Открыть снимок из ленты — в галерее, листать, закрыть
    act(action="openShot", index=0)
    s, ok = wait(lambda s: s["ui"]["gallery"] is not None, timeout=5)
    check("снимок из ленты открывается в галерее", ok and s["ui"]["gallery"]["index"] == 0)
    shot("p03-open-shot")
    act(action="galleryStep", offset=1)
    s = state()
    check("галерея листается", s["ui"]["gallery"] is not None and s["ui"]["gallery"]["index"] == min(1, len(s["shots"]) - 1))
    act(action="closeGallery")
    check("галерея закрывается", state()["ui"]["gallery"] is None)

    # 7. Меню Fn в фоторежиме
    act(action="openFn")
    shot("p04-fn-photo")
    act(action="closeOverlays")

    # 8. Фото → видео, стоп-кадр
    act(action="switchMode", mode="video")
    s, ok = wait(lambda s: s["mode"] == "video" and not setting(s, "exposureProgram")["pending"])
    check("переключение обратно в видео", ok, setting(s, "exposureProgram")["label"])
    s, ok = wait(lambda s: s.get("frameSize") == [1024, 576], timeout=6)
    check("live view снова 16:9", ok, str(s.get("frameSize")))
    n = len(s["shots"])
    act(action="grabFrame")
    s, ok = wait(lambda s: len(s["shots"]) > n, timeout=5)
    grab = s["shots"][0]["files"][0] if ok else ""
    check("стоп-кадр сохранён", ok and os.path.basename(grab).startswith("Кадр ") and open(grab, "rb").read(2) == b"\xff\xd8",
          os.path.basename(grab))
    shot("p05-video-grab")

    # 9. Скрытие элементов
    act(action="toggleControls")
    check("H прячет элементы", state()["ui"]["controlsHidden"])
    shot("p06-hidden")
    act(action="toggleControls")
    check("H возвращает элементы", not state()["ui"]["controlsHidden"])
    pref("autoHide", True)
    s, ok = wait(lambda s: s["ui"]["controlsHidden"], timeout=6)
    check("элементы прячутся сами через ~3 с", ok)
    pref("autoHide", False)
    s, ok = wait(lambda s: not s["ui"]["controlsHidden"], timeout=3)
    check("с выключенным автоскрытием элементы возвращаются", ok)

    # 10. Ошибки ввода
    check("неизвестная настройка отклоняется", act(action="setPreference", key="nope", value="1")[0] == 409)
    check("reviewSeconds вне 1…10 отклоняется", act(action="setPreference", key="reviewSeconds", value="99")[0] == 409)
finally:
    # Возврат: настройки программы, место сохранения в камере, режим съёмки, настройки камеры
    for k in ["folder", "reviewEnabled", "reviewSeconds", "reviewSource", "autoHide", "destination"]:
        pref(k, baseline_prefs[k])
    # камера применяет место сохранения примерно за 0,6 с — дождаться
    wait(lambda s: s["cameraDestination"] == baseline_dest, timeout=6)
    if state().get("shootingMode", state()["mode"]) != baseline_shooting:
        act(action="switchMode", mode=baseline_shooting)
        wait(lambda s: s.get("shootingMode", s["mode"]) == baseline_shooting)
    s = state()
    for k, v in baseline_values.items():
        x = setting(s, k)
        if x["writable"] and x.get("value") != v:
            act(action="request", setting=k, value=v)
            wait(lambda s, k=k, v=v: setting(s, k).get("value") == v)
    s = state()
    left = {k: (v, setting(s, k).get("value")) for k, v in baseline_values.items() if setting(s, k).get("value") != v}
    check("камера и программа возвращены в исходное состояние",
          not left and s["cameraDestination"] == baseline_dest and s["prefs"] == baseline_prefs,
          f"расхождений {len(left)}, dest {hex(s['cameraDestination'] or 0)}")

print(f"\nИТОГ: {sum(ok for _, ok in results)}/{len(results)} проверок прошли")
