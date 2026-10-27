#!/usr/bin/env python3
"""Сквозной тест группы камер на виртуальных камерах: связанные камеры ленты повторяют за главной настройки, баланс
белого и температуру, режим, пресет и запись; на камере, которая не может, — «Не удалось на …»; отвязанная не
повторяет; главная не из группы — группа ждёт, приглушённая цепочка вводит главную в группу; переключились на
связанную — прежняя главная в группе; главная пропала — тоже; отвязали всех — группы нет.

Перед запуском:
  scripts/fake_cameras.py --reject C:iso --slow-record C   # A, B, C на 127.0.0.1:15740–15742; C не меняет ISO,
                                                           # запись у C начинается и останавливается за 2 с
  ZV_AUTOMATION=1 "build/Sony Cam Viewer.app/Contents/MacOS/ZVViewer" --automation   # без ZV_PTPIP_HOST
Запуск: scripts/e2e_group.py. Запись — только на виртуальных камерах. Камеру B тест сам выключает и включает
(scripts/fake_camera.py с теми же параметрами). Пресет «Тест группы» в конце удаляется."""
import json, os, subprocess, sys, time, urllib.request, urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from fake_cameras import CAMERAS  # noqa: E402

info = json.load(open(os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")))
BASE = f"http://127.0.0.1:{info['port']}"; TOKEN = info["token"]; results = []
PORTS = {"A": 15740, "B": 15741, "C": 15742}


def call(method, path, body=None):
    req = urllib.request.Request(BASE + path, method=method, data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", TOKEN)
    try:
        with urllib.request.urlopen(req, timeout=15) as r: return r.status, r.read()
    except urllib.error.HTTPError as e: return e.code, e.read()


def state(): return json.loads(call("GET", "/state")[1])
def act(**b): return call("POST", "/action", b)[0]
def check(name, ok, detail=""):
    results.append((name, bool(ok))); print(("PASS " if ok else "FAIL ") + name + (f" — {detail}" if detail else ""), flush=True)


def wait(pred, timeout=15.0, step=0.2):
    t0 = time.time(); s = state()
    while time.time() - t0 < timeout:
        if pred(s): return s, True
        time.sleep(step); s = state()
    return s, pred(s)


def camera(s, letter): return next((c for c in s["cameras"] if c.get("port") == PORTS[letter]), None)
def tile(s, letter): return next((x for x in s["strip"] if x.get("port") == PORTS[letter]), None)
def main_is(letter): return lambda s: s["phase"] == "ready" and (camera(s, letter) or {}).get("state") == "current"
def setting(s, sid): return next(x for x in s["settings"] if x["id"] == sid)
def value(s, letter, sid): return ((tile(s, letter) or {}).get("values") or {}).get(sid)


def fake_off(letter):
    subprocess.run(["pkill", "-f", f"fake_camera.py --listen 127.0.0.1:{PORTS[letter]}"])


def fake_on(letter):
    cam = next(c for c in CAMERAS if c["letter"] == letter)
    args = [sys.executable, os.path.join(HERE, "fake_camera.py"), "--listen", f"127.0.0.1:{PORTS[letter]}",
            "--serial", f"{'FAKE' + letter:0>32}", "--battery", str(cam["battery"]), "--mode", cam["mode"], "--ssdp"]
    if cam["mark"]: args += ["--mark", cam["mark"]]
    subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


def link(letter, on=True): act(action="linkCamera", id=tile(state(), letter)["id"], linked=on)
def temperature(kelvin): act(action="request", setting="colorTemperature", value=kelvin)
def manual(s, letter, kelvin):
    return value(s, letter, "whiteBalance") == MANUAL_WB and value(s, letter, "colorTemperature") == kelvin


MANUAL_WB, AWB = 0x8012, 2


def another(s, sid, *avoid):
    """Значение настройки главной из тех, что камера даёт выбрать, — не текущее и не из avoid."""
    x = setting(s, sid)
    return next(o["value"] for o in x["options"] if o["value"] != x["value"] and o["value"] not in avoid)


s, ok = wait(lambda s: s["phase"] == "ready", 30); check("главная на связи", ok)
# A — главная, B и C — в ленте
for letter in "BCA":
    act(action="searchCameras"); time.sleep(4)
    s = state()
    c = camera(s, letter)
    if c["state"] == "current":
        continue
    act(action="pairCamera" if c["state"] == "needsPairing" else "selectCamera", id=c["id"])
    wait(main_is(letter), 20)
s, ok = wait(lambda s: main_is("A")(s) and tile(s, "B") and tile(s, "C") and tile(s, "B")["connected"] and tile(s, "C")["connected"], 20)
check("A — главная, B и C в ленте", ok, str([(x["name"], x["main"]) for x in s["strip"]]))
for letter in "BC":
    act(action="linkCamera", id=tile(s, letter)["id"], linked=True)
s = state(); check("B и C связаны, A в группе и ведёт её", tile(s, "B")["linked"] and tile(s, "C")["linked"] and tile(s, "A")["linked"]
                   and tile(s, "B")["following"] and tile(s, "C")["following"])

# настройка на главной — на связанных; C ISO не меняет — «не удалось»
iso = another(s, "iso", 16777215, value(s, "B", "iso"))
act(action="request", setting="iso", value=iso)
s, ok = wait(lambda s: value(s, "B", "iso") == iso, 8); check("ISO главной — и на B", ok, f"{value(s, 'B', 'iso')} ≠ {iso}" if not ok else "")
s, ok = wait(lambda s: "Не удалось на «" in (s.get("status") or "") and "ISO" in (s.get("status") or ""), 10)
check("C не может — «Не удалось на … ISO …»", ok, s.get("status"))

wb = another(s, "whiteBalance")
act(action="request", setting="whiteBalance", value=wb)
s, ok = wait(lambda s: value(s, "B", "whiteBalance") == wb and value(s, "C", "whiteBalance") == wb, 8)
check("баланс белого — на B и C", ok)

# температура: на связанных — и «Цвет. темп.»; камера ленты не в нём — переходит сама
act(action="request", setting="whiteBalance", value=AWB)
s, ok = wait(lambda s: all(value(s, x, "whiteBalance") == AWB for x in "BC"), 8); check("AWB — на B и C", ok)
link("B", False)
temperature(5200)
s, ok = wait(lambda s: manual(s, "C", 5200) and value(s, "B", "whiteBalance") == AWB, 10)
check("5200 K — на C, отвязанная B — в AWB", ok, str({x: (value(s, x, "whiteBalance"), value(s, x, "colorTemperature")) for x in "BC"}))
link("B")
temperature(4800)                                   # главная уже в «Цвет. темп.», B — нет
s, ok = wait(lambda s: manual(s, "B", 4800) and manual(s, "C", 4800), 10)
check("4800 K — и на B: сама перешла в «Цвет. темп.»", ok, str({x: (value(s, x, "whiteBalance"), value(s, x, "colorTemperature")) for x in "BC"}))
link("C", False)
temperature(5400)
wait(lambda s: setting(s, "colorTemperature").get("value") == 5400 and value(s, "B", "colorTemperature") == 5400, 8)
act(action="request", setting="whiteBalance", value=AWB)
s, ok = wait(lambda s: value(s, "B", "whiteBalance") == AWB and manual(s, "C", 4800), 10); check("B — в AWB, C — 4800 K", ok)
link("C")
temperature(5400)                                   # на главной температура уже 5400 — связанным её всё равно ставим
s, ok = wait(lambda s: manual(s, "B", 5400) and manual(s, "C", 5400), 10)
check("5400 K — на B и C, хотя у главной уже 5400", ok, str({x: (value(s, x, "whiteBalance"), value(s, x, "colorTemperature")) for x in "BC"}))

# режим: фото и обратно
act(action="switchMode", mode="photo")
s, ok = wait(lambda s: value(s, "B", "exposureProgram") == 1 and value(s, "C", "exposureProgram") == 1, 10)
check("фото — и на связанных", ok, str((value(s, "B", "exposureProgram"), value(s, "C", "exposureProgram"))))
act(action="switchMode", mode="video")
s, ok = wait(lambda s: (value(s, "B", "exposureProgram") or 0) >> 16 == 7 and (value(s, "C", "exposureProgram") or 0) >> 16 == 7, 10)
check("видео — и на связанных", ok)

# пресет — на всех
time.sleep(1)
act(action="savePreset", name="Тест группы")
s, _ = wait(lambda s: any(p["name"] == "Тест группы" for p in s["presets"]), 5)
preset_index = next(i for i, p in enumerate(s["presets"]) if p["name"] == "Тест группы")
preset_wb = s["presets"][preset_index]["values"].get("whiteBalance")
act(action="request", setting="whiteBalance", value=another(s, "whiteBalance", preset_wb))
time.sleep(3)
act(action="applyPreset", index=preset_index)
s, ok = wait(lambda s: value(s, "B", "whiteBalance") == preset_wb and setting(s, "whiteBalance")["value"] == preset_wb, 12)
check("пресет — и на B", ok)

# запись — на всех и стоп на всех
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: s.get("recording") and tile(s, "B")["recording"] and tile(s, "C")["recording"], 8)
check("запись — на главной, B и C", ok)
time.sleep(1.5)
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: not s.get("recording") and not tile(s, "B")["recording"] and not tile(s, "C")["recording"], 10)
check("стоп — на всех", ok)

# быстро «пуск» и «стоп»: C отвечает за 2 с — её «стоп» ждёт ответа на «пуск», а не теряется
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: s.get("recording") and s.get("recordingRequest") is None, 5)
time.sleep(0.6)                                     # главная видит запись по событию чуть раньше, чем кончится её вызов
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: not s.get("recording") and not any(x["recording"] for x in s["strip"]), 10)
time.sleep(3)
s = state()
check("быстрый пуск и стоп — никто не пишет", not s.get("recording") and not any(x["recording"] for x in s["strip"]),
      str([(x["name"], x["recording"]) for x in s["strip"]]))

# отвязанная не повторяет
act(action="linkCamera", id=tile(s, "C")["id"], linked=False)
c_wb = value(state(), "C", "whiteBalance")
act(action="request", setting="whiteBalance", value=another(state(), "whiteBalance", c_wb))
time.sleep(4)
check("отвязанная C не повторяет", value(state(), "C", "whiteBalance") == c_wb)

# переключились на отвязанную C — группа ждёт: A и B в группе, но за C не повторяют
act(action="selectCamera", id=tile(state(), "C")["id"])
s, ok = wait(main_is("C"), 5); check("главная — C, не из группы", ok and not tile(s, "C")["linked"])
check("A и B в группе, но не повторяют", all(tile(s, x)["linked"] and not tile(s, x)["following"] for x in "AB"),
      str([(x["name"], x["linked"], x.get("following")) for x in s["strip"]]))
wb_ab = {x: value(s, x, "whiteBalance") for x in "AB"}
act(action="request", setting="whiteBalance", value=another(s, "whiteBalance", *wb_ab.values()))
time.sleep(4)
s = state()
check("баланс белого на C до A и B не дошёл", all(value(s, x, "whiteBalance") == wb_ab[x] for x in "AB"),
      str({x: value(s, x, "whiteBalance") for x in "AB"}))

# приглушённая цепочка на A — C входит в группу, и вся группа повторяет за ней
link("A")
s = state()
check("C в группе, A и B повторяют", tile(s, "C")["linked"] and all(tile(s, x)["following"] for x in "AB"),
      str([(x["name"], x["linked"], x.get("following")) for x in s["strip"]]))
wb2 = another(s, "whiteBalance", setting(s, "whiteBalance")["value"], *[value(s, x, "whiteBalance") for x in "AB"])
act(action="request", setting="whiteBalance", value=wb2)
s, ok = wait(lambda s: all(value(s, x, "whiteBalance") == wb2 for x in "AB"), 8)
check("баланс белого на C — и на A и B", ok, str({x: value(s, x, "whiteBalance") for x in "AB"}))

# переключились на связанную B — A в группе, изменения на B доходят до A
act(action="selectCamera", id=tile(state(), "B")["id"])
s, ok = wait(main_is("B"), 5); check("главная — B", ok)
check("прежняя главная A в группе", (tile(s, "A") or {}).get("linked"), str(tile(s, "A")))
iso2 = another(s, "iso", 16777215, value(s, "A", "iso"))
act(action="request", setting="iso", value=iso2)
s, ok = wait(lambda s: value(s, "A", "iso") == iso2, 8); check("ISO на B — и на A", ok)

# главная пропала — главной стала связанная из ленты: пропавшая тоже связана и, вернувшись, повторяет
link("C")
fake_off("B")
s, ok = wait(lambda s: s["phase"] == "ready" and (camera(s, "B") or {}).get("state") != "current", 100)
check("главная B пропала — главной стала камера из ленты", ok, s.get("cameraName"))
fake_on("B")
s, ok = wait(lambda s: (tile(s, "B") or {}).get("connected"), 40)
check("B вернулась в ленту связанной", ok and tile(s, "B")["linked"], str(tile(s, "B")))
iso3 = another(s, "iso", 16777215, value(s, "B", "iso"))
act(action="request", setting="iso", value=iso3)
s, ok = wait(lambda s: value(s, "B", "iso") == iso3, 8); check("вернувшаяся B повторяет за новой главной", ok)

# уборка: пресет и связи
s = state()
for i, p in enumerate(s["presets"]):
    if p["name"] == "Тест группы":
        act(action="deletePreset", index=i)
        break
for x in state()["strip"]:
    if not x["main"]:
        act(action="linkCamera", id=x["id"], linked=False)
s = state()
check("отвязали всех — группы нет и у главной", not any(x["linked"] for x in s["strip"]), str([(x["name"], x["linked"]) for x in s["strip"]]))
print(f"\n{sum(ok for _, ok in results)}/{len(results)} PASS")
