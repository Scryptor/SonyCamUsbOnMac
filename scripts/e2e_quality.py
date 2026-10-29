#!/usr/bin/env python3
"""e2e_quality.py — качество превью по Wi-Fi в программе для Mac против поддельной камеры: авто, всегда 1024×576,
всегда 640×360 (выбор в подсказке к счётчику кадров и в настройках), запоминание выбора, прежний переключатель
wifiAdaptiveQuality, возврат камере высокого качества при выходе.

Запускает scripts/fake_camera.py на 127.0.0.1:15741 и программу с API автоматизации (адрес камеры подменён), проверяет
/state и снимает скриншоты в --out. Настройки программы (они общие с установленной) возвращает как были.

  scripts/e2e_quality.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/quality]
"""
import argparse, os, signal, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from e2e_monitor import (CAMERA, act, check, refuse_unsafe_roster, restore_prefs, save_prefs, shot, state, stop_process,  # noqa: E402
                         terminate_on_signals, wait)
import e2e_monitor  # noqa: E402


def width(s):
    return (s.get("frameSize") or [0])[0]


def mode(s):
    return s["prefs"]["previewQuality"]


def launch(app, out):
    env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
    return subprocess.Popen([os.path.join(app, "Contents/MacOS/ZVViewer"), "-ApplePersistenceIgnoreState", "YES"], env=env,
                            stdout=open(os.path.join(out, "app.log"), "a"), stderr=subprocess.STDOUT, start_new_session=True)


def quit(proc):
    proc.send_signal(signal.SIGTERM)
    try:
        proc.wait(timeout=8)
    except subprocess.TimeoutExpired:
        proc.kill()


def ready():
    return wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3, 40)


def run(out, app, camera_log):
    s = state()
    act("setPreference", key="autoHide", value="false")
    act("setPreference", key="previewQuality", value="auto")
    s = wait(lambda s: mode(s) == "auto" and width(s) == 1024, 8)
    check("авто на хорошем канале — 1024×576", mode(s) == "auto" and s.get("frameSize") == [1024, 576], s.get("frameSize"))

    act("setPreference", key="previewQuality", value="low")
    s = wait(lambda s: width(s) == 640, 8)
    check("вручную 640×360 — камера перешла", s.get("frameSize") == [640, 360] and s["link"]["quality"] == "low",
          (s.get("frameSize"), s["link"]))
    time.sleep(3)
    check("и держится — авто его не поднимает", width(state()) == 640, state().get("frameSize"))
    act("showInfo", name="frames")
    time.sleep(1)
    shot(out, "1-low-info")
    act("showInfo")

    act("setPreference", key="previewQuality", value="high")
    s = wait(lambda s: width(s) == 1024, 8)
    check("вручную 1024×576 — снова полное", s.get("frameSize") == [1024, 576] and mode(s) == "high", s.get("frameSize"))

    r = act("setPreference", key="previewQuality", value="medium")
    check("неизвестное качество — 409", r.get("status") == 409, r)
    act("setPreference", key="wifiAdaptiveQuality", value="true")
    check("прежний переключатель: true — авто", mode(state()) == "auto", state()["prefs"])
    act("setPreference", key="wifiAdaptiveQuality", value="false")
    s = state()
    check("прежний переключатель: false — всегда высокое", mode(s) == "high" and s["prefs"]["wifiAdaptiveQuality"] is False,
          s["prefs"])

    # выбор помнится: перезапуск с «640×360» — камера сразу на низком
    act("setPreference", key="previewQuality", value="low")
    wait(lambda s: width(s) == 640, 8)
    return "low"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/quality")
    args = ap.parse_args()
    terminate_on_signals()                              # сигнал (в том числе `perl -e 'alarm N'`) — тоже через `finally`
    os.makedirs(args.out, exist_ok=True)
    before = save_prefs()
    refuse_unsafe_roster(before)
    camera_log = os.path.join(args.out, "camera.log")
    log = open(camera_log, "a")
    cam = proc = None
    try:
        # запуск — внутри try: сигнал между запуском камеры и программой тоже доходит до finally
        cam = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA], stdout=log,
                               stderr=subprocess.STDOUT)
        time.sleep(0.5)
        proc = launch(args.app, args.out)
        s = ready()
        if not s or s.get("phase") != "ready":
            print(f"программа не дошла до ready: {s and s.get('phase')}")
            return 1
        time.sleep(3)
        run(args.out, args.app, camera_log)
        quit(proc)
        time.sleep(1)
        lines = open(camera_log).read().splitlines()
        check("при выходе камере вернули высокое", any("качество превью → высокое" in line for line in lines[-15:]), lines[-5:])
        proc = launch(args.app, args.out)
        s = ready()
        s = wait(lambda s: width(s) == 640, 10)
        check("после перезапуска — снова 640×360", mode(s) == "low" and width(s) == 640, (mode(s), s.get("frameSize")))
        act("setPreference", key="autoHide", value="false")
        shot(args.out, "2-low-after-restart")
        act("setPreference", key="previewQuality", value="auto")
        s = wait(lambda s: width(s) == 1024, 30)
        check("авто — вернулось полное, канал тянет", width(s) == 1024, s.get("frameSize"))
    finally:
        stop_process(proc)
        stop_process(cam)
        restore_prefs(before)
    print(f"\n{e2e_monitor.passed} из {e2e_monitor.passed + e2e_monitor.failed} проверок прошли; скриншоты — {args.out}")
    return 0 if e2e_monitor.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
