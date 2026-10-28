#!/usr/bin/env python3
"""e2e_lens.py — фокус и зум с экрана в программе для Mac против поддельной камеры: фокус касанием (касание экрана
камеры в зоне «Широкая», точка AF в зоне «Точка»), рамки фокуса камеры, моторный зум, шкала фокуса в MF.

Запускает scripts/fake_camera.py на 127.0.0.1:15741 и программу с API автоматизации (адрес камеры подменён), проверяет
/state и снимает скриншоты в --out. Зону и режим фокуса поддельной камеры возвращает, настройки программы (они общие с
установленной) — тоже.

  scripts/e2e_lens.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/lens]
"""
import argparse, os, signal, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from e2e_monitor import CAMERA, act, check, restore_prefs, save_prefs, shot, state, wait  # noqa: E402
import e2e_monitor  # noqa: E402

FOCUS_MODE_AFC, FOCUS_MODE_MF = 0x8004, 1
AREA_WIDE, AREA_SPOT_L = 0x1, 0x103


def lens(s):
    return s["lens"]


def setting(s, name):
    return next(item for item in s["settings"] if item["id"] == name)


def near(a, b, tolerance=0.03):
    return abs(a - b) <= tolerance


def run(out, restart_camera):
    s = state()
    check("камера назвала команды фокуса и зума",
          all(c in lens(s)["controls"] for c in ("0xD2D1", "0xD2DC", "0xD2DD", "0xD2E4")), lens(s)["controls"])
    act("setPreference", key="touchFocusHelpHidden", value="false")
    if s["ui"]["touchFocus"]:
        act("toggleTouchFocus")
    act("toggleTouchFocus")
    s = state()
    check("включили фокус касанием — подсказка, как он работает", s["ui"]["touchFocus"] and s["ui"]["touchFocusHelp"], s["ui"])
    shot(out, "0-touch-help")
    act("closeOverlays")
    check("«Понятно» или щелчок мимо — подсказка закрыта", not state()["ui"]["touchFocusHelp"], state()["ui"])
    act("setPreference", key="touchFocusHelpHidden", value="true")
    act("toggleTouchFocus")
    act("toggleTouchFocus")
    s = state()
    check("«Больше не показывать» — при включении подсказки нет", s["ui"]["touchFocus"] and not s["ui"]["touchFocusHelp"], s["ui"])
    act("setPreference", key="autoHide", value="false")

    # «Широкая»: касание экрана камеры — слежение
    act("request", setting="focusArea", value=AREA_WIDE)
    s = wait(lambda s: lens(s)["touchFocus"] == "remoteTouch")
    check("зона «Широкая» — касание экрана камеры", lens(s)["touchFocus"] == "remoteTouch", lens(s))
    act("touchFocus", x=0.3, y=0.4)
    s = wait(lambda s: any(f["kind"] == "tracking" for f in lens(s)["focusFrames"]))
    frames = [f for f in lens(s)["focusFrames"] if f["kind"] == "tracking"]
    check("рамка слежения — там, куда коснулись", frames and near(frames[0]["x"], 0.3) and near(frames[0]["y"], 0.4), frames)
    check("слежение можно снять", lens(s)["touchCancel"], lens(s))
    shot(out, "1-touch-tracking")
    # выключили кнопку «Фокус» — камера следит дальше: рамка на экране, «Снять слежение» на месте
    act("toggleTouchFocus")
    time.sleep(1)
    s = state()
    check("кнопку выключили — слежение идёт, снять можно", not s["ui"]["touchFocus"] and lens(s)["touchCancel"]
          and any(f["kind"] == "tracking" for f in lens(s)["focusFrames"]), (s["ui"]["touchFocus"], lens(s)))
    shot(out, "1b-tracking-button-off")
    act("cancelTouch")
    s = wait(lambda s: not lens(s)["focusFrames"] and not lens(s)["touchCancel"])
    check("сняли и при выключенной кнопке — рамки нет", not lens(s)["focusFrames"] and not lens(s)["touchCancel"], lens(s))
    act("toggleTouchFocus")
    act("closeOverlays")

    # «Точка L»: точка AF двигает рамку, через 0,3 с фокус найден
    act("request", setting="focusArea", value=AREA_SPOT_L)
    s = wait(lambda s: lens(s)["touchFocus"] == "areaPosition", 4)
    check("зона «Точка» — точка AF", lens(s)["touchFocus"] == "areaPosition", lens(s))
    act("touchFocus", x=0.72, y=0.62)
    s = wait(lambda s: any(f["kind"] == "focus" and near(f["x"], 0.72) and f["focused"] for f in lens(s)["focusFrames"]))
    frames = [f for f in lens(s)["focusFrames"] if f["kind"] == "focus"]
    check("рамка AF переехала и фокус найден", frames and near(frames[0]["x"], 0.72) and near(frames[0]["y"], 0.62)
          and frames[0]["focused"], frames)
    act("setZoom", factor=3, x=0.72, y=0.62)
    shot(out, "2-touch-spot-zoom-3x")
    act("resetZoom")

    # моторный зум: держат T — едет к теле, отпустили — стоит; W — обратно
    check("зум доступен", lens(state())["zoom"])
    act("zoomStart", name="tele")
    s = wait(lambda s: (lens(s)["zoomBar"] or {}).get("position", 0) >= 40, 4)
    act("zoomStop")
    time.sleep(0.6)
    stopped = lens(state())["zoomBar"]["position"]
    time.sleep(0.6)
    check("зум к теле едет", stopped >= 40, lens(s)["zoomBar"])
    check("отпустили — стоит", lens(state())["zoomBar"]["position"] == stopped and lens(state())["zoomDirection"] == 0,
          lens(state())["zoomBar"])
    time.sleep(1.5)                                    # ступени картинки считаются в фоне при первом зуме
    shot(out, "3-zoom-tele")
    act("zoomStart", name="wide")
    s = wait(lambda s: lens(s)["zoomBar"]["position"] == 0, 6)
    act("zoomStop")
    check("зум к широкому — до края", lens(s)["zoomBar"]["position"] == 0, lens(s)["zoomBar"])
    r = act("zoomStart", name="out")
    check("неизвестное направление — 409", r.get("status") == 409, r)

    # шкала фокуса — только в MF
    r = act("focusStep", offset=-3)
    check("в AF шкалы фокуса нет — 409", r.get("status") == 409, r)
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    s = wait(lambda s: lens(s)["focusScale"], 4)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    before = lens(s)["focalPosition"]
    act("focusStep", offset=-10)
    s = wait(lambda s: lens(s)["focalPosition"] == before - 10, 4)
    check("ближе на 10 — фокус сдвинулся на 10", lens(s)["focalPosition"] == before - 10, (before, lens(s)["focalPosition"]))
    act("focusStep", offset=4)
    s = wait(lambda s: lens(s)["focalPosition"] == before - 6, 4)
    check("дальше на 4", lens(s)["focalPosition"] == before - 6, lens(s)["focalPosition"])
    r = act("touchFocus", x=0.5, y=0.5)
    check("в MF фокус касанием не ставится — 409", r.get("status") == 409, r)
    shot(out, "4-focus-scale")
    act("focusStep", offset=6)
    wait(lambda s: lens(s)["focalPosition"] == before, 4)

    # держали «T», а связь пропала: отпустили во время обрыва — кнопка отпущена, после переподключения зум снова работает
    act("zoomStart", name="tele")
    time.sleep(0.4)
    restart_camera(down=True)
    s = wait(lambda s: s.get("phase") != "ready" or (s.get("link") or {}).get("state") != "ok", 15)
    act("zoomStop")
    s = state()
    check("обрыв во время зума: отпустили — кнопка отпущена", lens(s)["zoomDirection"] == 0, lens(s))
    restart_camera(down=False)
    s = wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3 and lens(s)["zoom"], 40)
    time.sleep(3)
    check("после переподключения «T» не горит", lens(state())["zoomDirection"] == 0, lens(state()))
    before_bar = lens(state())["zoomBar"]["position"]
    act("zoomStart", name="tele")
    s = wait(lambda s: (lens(s)["zoomBar"] or {}).get("position", 0) > before_bar, 4)
    act("zoomStop")
    check("и зум снова едет", lens(s)["zoomBar"]["position"] > before_bar, (before_bar, lens(s)["zoomBar"]))
    act("zoomStart", name="wide")
    wait(lambda s: lens(s)["zoomBar"]["position"] == 0, 6)
    act("zoomStop")

    # как было: AF-C, «Широкая»
    act("request", setting="focusMode", value=FOCUS_MODE_AFC)
    wait(lambda s: setting(s, "focusMode").get("value") == FOCUS_MODE_AFC, 4)
    act("request", setting="focusArea", value=AREA_WIDE)
    s = wait(lambda s: setting(s, "focusArea").get("value") == AREA_WIDE, 4)
    check("фокус — как был: AF-C, «Широкая»", setting(s, "focusMode").get("value") == FOCUS_MODE_AFC
          and setting(s, "focusArea").get("value") == AREA_WIDE)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/lens")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    before = save_prefs()
    log = open(os.path.join(args.out, "camera.log"), "a")
    cams = [subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA], stdout=log,
                             stderr=subprocess.STDOUT)]

    def restart_camera(down):
        """Камера пропала (процесс остановлен) или вернулась (новый на том же адресе)."""
        if down:
            cams[-1].terminate()
            cams[-1].wait()
        else:
            cams.append(subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA],
                                         stdout=log, stderr=subprocess.STDOUT))

    time.sleep(0.5)
    env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
    proc = subprocess.Popen([os.path.join(args.app, "Contents/MacOS/ZVViewer"), "-ApplePersistenceIgnoreState", "YES"], env=env,
                            stdout=open(os.path.join(args.out, "app.log"), "w"), stderr=subprocess.STDOUT, start_new_session=True)
    try:
        s = wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3, 40)
        if not s or s.get("phase") != "ready":
            print(f"программа не дошла до ready: {s and s.get('phase')}")
            return 1
        time.sleep(3)                                   # первые секунды сеанса камера пропускает записи
        autohide = s["prefs"]["autoHide"]
        try:
            run(args.out, restart_camera)
        finally:
            act("zoomStop")
            act("setPreference", key="autoHide", value="true" if autohide else "false")
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        for cam in cams:
            if cam.poll() is None:
                cam.terminate()
                cam.wait()
        restore_prefs(before)
    print(f"\n{e2e_monitor.passed} из {e2e_monitor.passed + e2e_monitor.failed} проверок прошли; скриншоты — {args.out}")
    return 0 if e2e_monitor.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
