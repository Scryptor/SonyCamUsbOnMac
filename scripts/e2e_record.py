#!/usr/bin/env python3
"""e2e_record.py — кнопка записи против поддельной камеры: что видно в программе, когда камера отвечает по-разному.

Для каждого сценария scripts/fake_camera.py --record … запускает камеру и программу для Mac (API автоматизации, адрес
камеры подменён на 127.0.0.1), нажимает запись и каждые 40 мс снимает с /state: ждёт ли кнопка камеру
(recordingRequest), идёт ли запись, подпись у кнопки, строку состояния, отклик кнопки и записи журнала о записи.
Рядом — журнал камеры по тем же часам.

  scripts/e2e_record.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/rec] [--only blip-fast,press-busy] [--twice 1.0]

--twice с — второе нажатие через столько секунд после первого (как палец, который не увидел отклика).
"""
import argparse, json, os, re, signal, subprocess, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from fake_camera import RECORD_SCENARIOS  # noqa: E402

# не 15740: установленная программа, запомнившая 127.0.0.1, сама подключилась бы к поддельной камере
CAMERA = "127.0.0.1:15741"
INFO = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")


def call(path, body=None):
    info = json.load(open(INFO))
    req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}", method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", info["token"])
    return json.loads(urllib.request.urlopen(req, timeout=3).read() or b"null")


def state():
    try:
        return call("/state")
    except Exception:
        return None


def seen(s):
    """То, что видит человек у кнопки записи."""
    return {"ждёт": s.get("recordingRequest"), "запись": s.get("recording"), "у кнопки": s.get("recordingNotice"),
            "строка": s.get("status"), "отклик": s.get("feedback")}


def fresh(before, now):
    """Новые записи журнала: хвост прежнего списка совпадает с началом нового (в /state — последние 30)."""
    for k in range(min(len(before), len(now)), -1, -1):
        if before[len(before) - k:] == now[:k]:
            return now[k:]
    return now


def run(scenario, app, out, twice):
    clock = time.monotonic()
    cam = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA, "--record", scenario],
                           env=dict(os.environ, FAKE_CAMERA_CLOCK=repr(clock)), stdout=open(f"{out}/{scenario}.camera.log", "w"),
                           stderr=subprocess.STDOUT)
    time.sleep(0.5)
    env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
    # без сохранённых окон: иначе программа может открыться без окна, и API (оно поднимается с окном) не заработает
    proc = subprocess.Popen([os.path.join(app, "Contents/MacOS/ZVViewer"), "-ApplePersistenceIgnoreState", "YES"], env=env, stdout=open(f"{out}/{scenario}.app.log", "w"),
                            stderr=subprocess.STDOUT, start_new_session=True)
    try:
        deadline = time.monotonic() + 40
        s = None
        while time.monotonic() < deadline:
            s = state()
            if s and s.get("phase") == "ready" and s.get("fps", 0) > 3:
                break
            time.sleep(0.3)
        else:
            print(f"  программа не дошла до ready: {s and s.get('phase')}")
            return
        time.sleep(4)                       # первые секунды сеанса камера пропускает записи — и тут пусть уляжется
        first = state()
        before = seen(first)
        journal = first.get("journal") or []
        lines = []
        pressed = time.monotonic()
        call("/action", {"action": "toggleRecording", "confirm": True})
        second = twice is not None
        last = None
        while time.monotonic() - pressed < 9:
            t = time.monotonic() - pressed
            if second and t >= twice:
                call("/action", {"action": "toggleRecording", "confirm": True})
                lines.append((t, "второе нажатие"))
                second = False
            s = state()
            if s:
                entries = s.get("journal") or []
                lines += [(t, "журнал: " + e) for e in fresh(journal, entries) if e.startswith("запись")]
                journal = entries
                now = seen(s)
                if now != last:
                    changes = {k: v for k, v in now.items() if last is None or last[k] != v}
                    if "отклик" in changes:
                        old = (last or before)["отклик"] or []
                        changes["отклик"] = "+" + ",".join(now["отклик"][len(old):] if now["отклик"][:len(old)] == old
                                                           else now["отклик"])
                    lines.append((t, ", ".join(f"{k}={v}" for k, v in changes.items())))
                    last = now
            time.sleep(0.04)
        shift = clock - pressed
        for row in open(f"{out}/{scenario}.camera.log"):
            m = re.match(r"\s*(-?\d+\.\d+)\s+(.*)", row)
            if m and float(m.group(1)) + shift > -0.5:
                lines.append((float(m.group(1)) + shift, "камера: " + m.group(2)))
        for t, text in sorted(lines, key=lambda x: x[0]):
            print(f"  {t:6.2f} с  {text}")
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        cam.terminate()
        cam.wait()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/e2e_record")
    ap.add_argument("--only", help="сценарии через запятую")
    ap.add_argument("--twice", type=float, help="второе нажатие через столько секунд")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    for scenario in (a.only.split(",") if a.only else RECORD_SCENARIOS):
        print(f"\n{scenario}: {RECORD_SCENARIOS[scenario]}" + (f" — второе нажатие через {a.twice} с" if a.twice else ""))
        run(scenario, a.app, a.out, a.twice)


if __name__ == "__main__":
    main()
