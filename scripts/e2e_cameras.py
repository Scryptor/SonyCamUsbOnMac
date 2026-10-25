#!/usr/bin/env python3
"""Сквозной тест выбора камеры на виртуальных камерах: сопряжение одной камеры, переключение, переименование,
переключение во время записи (главная по Wi-Fi уходит в ленту и пишет дальше), занятая камера и отмена сопряжения,
«Забыть» и снова сопрячь.

Перед запуском:
  scripts/fake_cameras.py --pairing B --busy C        # A, B, C на 127.0.0.1:15740–15742
  ZV_AUTOMATION=1 "build/Sony Cam Viewer.app/Contents/MacOS/ZVViewer" --automation   # без ZV_PTPIP_HOST
Запуск: scripts/e2e_cameras.py. Запись включается только на виртуальной камере (на 127.0.0.1) — иначе тест
останавливается. Имя камеры A в конце возвращается прежним."""
import json, os, sys, time, urllib.request, urllib.error

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


def wait(pred, timeout=15.0):
    deadline = time.time() + timeout; s = state()
    while time.time() < deadline:
        if pred(s): return s, True
        time.sleep(0.25); s = state()
    return s, pred(s)


def camera(s, letter):
    return next((c for c in s["cameras"] if c.get("port") == PORTS[letter] and c.get("host") == "127.0.0.1"), None)


def current_is(letter):
    return lambda s: s["phase"] == "ready" and (camera(s, letter) or {}).get("state") == "current"


def search():
    act(action="searchCameras"); time.sleep(4)
    return state()


s, ok = wait(lambda s: s["phase"] == "ready", 30); check("камера на связи", ok)
s = search()
check("видны A, B и C", all(camera(s, x) for x in "ABC"), str([(c["name"], c["detail"]) for c in s["cameras"]]))

# B — сопряжение только с ней (у B эмуляция «ОК» на камере через 3 с)
b = camera(s, "B")
if b["state"] == "needsPairing":
    act(action="pairCamera", id=b["id"])
    time.sleep(0.5)
    check("у B — «сопрягаю…»", (camera(state(), "B") or {}).get("stateLabel") == "сопрягаю…")
else:
    act(action="selectCamera", id=b["id"])
s, ok = wait(current_is("B"), 20); check("B на связи", ok, s.get("cameraName"))

# A — переключение
a = camera(search(), "A")
code = act(action="pairCamera" if a["state"] == "needsPairing" else "selectCamera", id=a["id"])
s, ok = wait(current_is("A")); check("переключились на A", ok and code == 200, s.get("cameraName"))
a = camera(s, "A"); name = a["name"]
act(action="renameCamera", id=a["id"], name="Тест A")
check("имя на строке состояния — своё", state()["cameraName"] == "Тест A", state()["cameraName"])
check("текущую не выбрать и не забыть", act(action="selectCamera", id=a["id"]) == 409 and act(action="forgetCamera", id=a["id"]) == 409)

# во время записи: главная по Wi-Fi уходит в ленту и пишет дальше — переключиться можно (запись — только на виртуальной)
if camera(state(), "A")["state"] == "current" and camera(state(), "A")["host"] == "127.0.0.1":
    act(action="toggleRecording", confirm=True)
    s, ok = wait(lambda s: s.get("recording"), 5); check("виртуальная A пишет", ok)
    check("во время записи переключение на B — сразу", act(action="selectCamera", id=camera(s, "B")["id"]) == 200)
    s, ok = wait(current_is("B"), 5)
    check("A в ленте пишет дальше", ok and any(x.get("port") == PORTS["A"] and x["recording"] for x in s["strip"]),
          str([(x.get("port"), x["recording"]) for x in s["strip"]]))
    act(action="selectCamera", id=camera(state(), "A")["id"])
    wait(lambda s: current_is("A")(s) and s.get("recording"), 5)
    time.sleep(1.0)
    act(action="toggleRecording", confirm=True)
    s, ok = wait(lambda s: not s.get("recording"), 8); check("запись остановлена", ok)
else:
    sys.exit("текущая камера — не виртуальная: запись не включаю")

# C занята: сопряжение ждёт, отмена — обратно к последней камере
c = camera(search(), "C")
if c and c["state"] == "needsPairing":
    act(action="pairCamera", id=c["id"])
    s, ok = wait(lambda s: s.get("pairing") and s["phase"] == "searching", 5); check("сопряжение с занятой C ждёт", ok)
    time.sleep(3)
    act(action="cancelPairing")
    s, ok = wait(current_is("A"), 20); check("после отмены — снова A", ok, s.get("cameraName"))

# забыть B — снова «нужно сопряжение»; сопрячь — на связи
b = camera(search(), "B")
act(action="forgetCamera", id=b["id"])
s = search()
check("забытая B — «нужно сопряжение»", (camera(s, "B") or {}).get("state") == "needsPairing",
      str((camera(s, "B") or {}).get("stateLabel")))
act(action="pairCamera", id=camera(s, "B")["id"])
s, ok = wait(current_is("B"), 20); check("B сопряжена снова", ok)

# уборка: A — с прежним именем и снова текущая
a = camera(search(), "A")
act(action="renameCamera", id=a["id"], name=name)
act(action="selectCamera", id=a["id"])
s, ok = wait(current_is("A")); check("уборка: снова A с прежним именем", ok and s["cameraName"] == name, s.get("cameraName"))
print(f"\n{sum(ok for _, ok in results)}/{len(results)} PASS")
