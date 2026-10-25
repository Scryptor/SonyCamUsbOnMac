#!/usr/bin/env python3
"""Сквозной тест ленты камер на виртуальных камерах: три камеры на связи, переключение щелчком по плитке — сразу,
без переподключения и не меняя порядка плиток; перестановка плиток; во время записи; «Убрать из ленты» и «В ленту»;
камера ленты пропала и вернулась; главная пропала — главной стала верхняя камера из ленты.

Перед запуском:
  scripts/fake_cameras.py                     # A, B, C на 127.0.0.1:15740–15742
  ZV_AUTOMATION=1 "build/Sony Cam Viewer.app/Contents/MacOS/ZVViewer" --automation   # без ZV_PTPIP_HOST
Запуск: scripts/e2e_strip.py. Камеры B и C тест сам выключает и включает (scripts/fake_camera.py с теми же
параметрами). Запись — только на виртуальной камере."""
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


def wait(pred, timeout=15.0, step=0.1):
    t0 = time.time(); s = state()
    while time.time() - t0 < timeout:
        if pred(s): return s, time.time() - t0
        time.sleep(step); s = state()
    return s, (time.time() - t0) if pred(s) else None


def camera(s, letter): return next((c for c in s["cameras"] if c.get("port") == PORTS[letter]), None)
def tile(s, letter): return next((x for x in s["strip"] if x.get("port") == PORTS[letter]), None)
def main_is(letter): return lambda s: s["phase"] == "ready" and (camera(s, letter) or {}).get("state") == "current"


def fake_off(letter):
    subprocess.run(["pkill", "-f", f"fake_camera.py --listen 127.0.0.1:{PORTS[letter]}"])


def fake_on(letter):
    cam = next(c for c in CAMERAS if c["letter"] == letter)
    args = [sys.executable, os.path.join(HERE, "fake_camera.py"), "--listen", f"127.0.0.1:{PORTS[letter]}",
            "--serial", f"{'FAKE' + letter:0>32}", "--battery", str(cam["battery"]), "--mode", cam["mode"], "--ssdp"]
    if cam["mark"]: args += ["--mark", cam["mark"]]
    subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)


s, ok = wait(lambda s: s["phase"] == "ready", 30); check("главная на связи", ok)

# три камеры на связи: каждая следующая — главной, прежняя уходит в ленту
for letter in "ABC":
    act(action="searchCameras"); time.sleep(4)
    s = state()
    c = camera(s, letter)
    if c["state"] in ("current", "side"):
        continue
    act(action="pairCamera" if c["state"] == "needsPairing" else "selectCamera", id=c["id"])
    s, took = wait(main_is(letter), 20)
    check(f"{letter} — главная", took is not None)
s, ok = wait(lambda s: len(s["strip"]) == 3 and all(x["connected"] for x in s["strip"]), 15)
check("в ленте три камеры на связи", ok, str([(x["name"], x["main"]) for x in s["strip"]]))
order = [x["port"] for x in s["strip"]]

# щелчок по плитке — сразу: переподключение заняло бы секунды
for letter in "AB":
    t = tile(state(), letter)
    if t["main"]:
        continue
    act(action="selectCamera", id=t["id"])
    s, took = wait(main_is(letter), 5, step=0.02)
    check(f"плитка {letter} — главная сразу", took is not None and took < 0.5, f"{took and round(took, 2)} с")
    check(f"плитка {letter} — на своём месте, порядок прежний", [x["port"] for x in s["strip"]] == order,
          f"{order} → {[x['port'] for x in s['strip']]}")
s, ok = wait(lambda s: s.get("fps", 0) > 10, 5)
check("на главной — полная частота", ok, f"{s.get('fps')} к/с")
check("в ленте — около 5 к/с", all(2 <= x["fps"] <= 8 for x in s["strip"] if not x["main"] and x["connected"]),
      str([(x["name"], x["fps"]) for x in s["strip"]]))

# перестановка: нижняя плитка — на место верхней, потом обратно; переключение порядок не меняет
s = state()
act(action="moveInStrip", id=s["strip"][-1]["id"], onto=s["strip"][0]["id"])
moved = [x["port"] for x in state()["strip"]]
check("нижняя плитка встала наверх", moved == [order[-1]] + order[:-1], f"{order} → {moved}")
other_tile = next(x for x in state()["strip"] if not x["main"] and x["connected"])
act(action="selectCamera", id=other_tile["id"])
s, _ = wait(lambda s: next(x for x in s["strip"] if x["main"])["id"] == other_tile["id"], 5)
check("после перестановки щелчок порядок не меняет", [x["port"] for x in s["strip"]] == moved, str([x["port"] for x in s["strip"]]))
act(action="moveInStrip", id=s["strip"][0]["id"], onto=s["strip"][-1]["id"])
check("и обратно — прежний порядок", [x["port"] for x in state()["strip"]] == order, str([x["port"] for x in state()["strip"]]))

# переключение во время записи: камера уходит в ленту и пишет дальше
main_letter = next(l for l in "ABC" if main_is(l)(state()))
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: s.get("recording"), 5); check(f"{main_letter} пишет", ok)
other = next(l for l in "ABC" if l != main_letter)
act(action="selectCamera", id=tile(s, other)["id"])
s, took = wait(main_is(other), 5, step=0.02)
check("во время записи переключилось", took is not None)
check(f"{main_letter} в ленте пишет дальше", (tile(s, main_letter) or {}).get("recording"))
act(action="selectCamera", id=tile(s, main_letter)["id"])
s, _ = wait(main_is(main_letter), 5)
check("вернулись — запись идёт", s.get("recording"))
act(action="toggleRecording", confirm=True)
s, ok = wait(lambda s: not s.get("recording"), 8); check("запись остановлена", ok)

# убрать из ленты и вернуть
side_letter = next(l for l in "ABC" if l != main_letter)
act(action="removeFromStrip", id=tile(state(), side_letter)["id"])
time.sleep(12)
check(f"{side_letter} убрана и не вернулась сама", tile(state(), side_letter) is None)
act(action="addToStrip", id=camera(state(), side_letter)["id"])
s, ok = wait(lambda s: (tile(s, side_letter) or {}).get("connected"), 15); check(f"{side_letter} снова в ленте", ok)

# камера ленты пропала и вернулась
fake_off(side_letter)
s, ok = wait(lambda s: tile(s, side_letter) and not tile(s, side_letter)["connected"], 10)
check(f"{side_letter} выключена — «нет связи»", ok, (camera(s, side_letter) or {}).get("stateLabel"))
fake_on(side_letter)
s, ok = wait(lambda s: (tile(s, side_letter) or {}).get("connected"), 30); check(f"{side_letter} вернулась в ленту", ok)

# главная пропала — после минуты восстановления главной становится верхняя камера из ленты
top = next(x["port"] for x in state()["strip"] if not x["main"] and x["connected"])
fake_off(main_letter)
s, took = wait(lambda s: s["phase"] == "ready" and (camera(s, main_letter) or {}).get("state") != "current", 90, step=0.5)
check("главная пропала — главной стала камера из ленты", took is not None, f"{took and round(took)} с, главная «{s['cameraName']}»")
check("и это верхняя камера ленты", next((x.get("port") for x in s["strip"] if x["main"]), None) == top,
      f"ждали порт {top}: {[(x.get('port'), x['main']) for x in s['strip']]}")
fake_on(main_letter)
s, ok = wait(lambda s: (tile(s, main_letter) or {}).get("connected"), 30)
check(f"{main_letter} вернулась — в ленту, а не главной", ok and (camera(s, main_letter) or {}).get("state") == "side")
print(f"\n{sum(ok for _, ok in results)}/{len(results)} PASS")
