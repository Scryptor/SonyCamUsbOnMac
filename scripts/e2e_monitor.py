#!/usr/bin/env python3
"""e2e_monitor.py — инструменты монитора в программе для Mac против поддельной камеры: зебра, false color, пикинг,
маркеры, меню «Монитор», растр при цифровом увеличении, частота кадров со всем включённым, волновая форма (яркость и
RGB-парад) и наложение эталонного кадра.

Запускает scripts/fake_camera.py на 127.0.0.1:15741 и программу с API автоматизации (адрес камеры подменён), проверяет
/state и снимает скриншоты в --out. Эталоном для наложения подкладывает другой план той же сцены
(scripts/assets/viewfinder-pan.jpg). Настройки инструментов и эталон, какие были до запуска, возвращает.

  scripts/e2e_monitor.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/monitor]
"""
import argparse, json, os, plistlib, signal, subprocess, sys, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# не 15740: установленная программа, запомнившая 127.0.0.1, сама подключилась бы к поддельной камере
CAMERA = "127.0.0.1:15741"
INFO = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")
DOMAIN = "local.zve10viewer"
KEYS = ("monitorAssist", "waveformOn", "waveformMode", "waveformPlacement", "referenceSource")
REFERENCE = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/reference.jpg")

passed = failed = 0


def call(path, body=None, raw=False):
    info = json.load(open(INFO))
    req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}", method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", info["token"])
    try:
        data = urllib.request.urlopen(req, timeout=5).read()
    except urllib.error.HTTPError as e:
        return {"status": e.code, "error": json.loads(e.read() or b"{}").get("error")}
    return data if raw else json.loads(data or b"null")


def act(action, **params):
    return call("/action", dict(action=action, **params))


def state():
    try:
        return call("/state")
    except Exception:
        return None


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        passed += 1
        print(f"  ✓ {name}")
    else:
        failed += 1
        print(f"  ✗ {name} {detail}")


def wait(predicate, seconds=3.0):
    """Ждёт, пока /state не станет таким; отдаёт последний."""
    deadline = time.monotonic() + seconds
    s = state()
    while time.monotonic() < deadline:
        if s and predicate(s):
            return s
        time.sleep(0.1)
        s = state()
    return s


def shot(out, name):
    # анимации (увеличение, меню) — досматриваем до конца, иначе снимок ловит их середину
    time.sleep(0.6)
    png = call("/screenshot", raw=True)
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(png)


def overlay(s):
    return s.get("assistOverlay")


def values(s):
    return s["ui"]["assist"]["values"]


def fps_over(seconds):
    """Средняя частота кадров по /state за столько секунд. Скриншот держит окно занятым, и счётчик кадров за 2 с
    после него ниже — сначала пережидаем."""
    time.sleep(2.5)
    samples = []
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        samples.append(state()["fps"])
        time.sleep(0.5)
    return sum(samples) / len(samples)


def run(out):
    act("assistOff")
    act("resetZoom")
    act("setPreference", key="autoHide", value="false")
    s = wait(lambda s: overlay(s) is None)
    check("всё выключено — растра нет", overlay(s) is None)
    check("все инструменты выключены", all(v == "off" for k, v in values(s).items() if k != "peakingColor"), values(s))
    base_fps = fps_over(3)
    print(f"  частота без инструментов: {base_fps:.1f} к/с")

    # зебра: на картинке солнце — почти белое
    act("setAssist", name="zebra", value="95")
    s = wait(lambda s: overlay(s) and overlay(s)["zebra"] > 0)
    check("зебра 95 % — полосы на солнце", overlay(s) and 0.5 < overlay(s)["zebra"] < 20, overlay(s))
    check("растр — весь кадр без увеличения", overlay(s) and overlay(s)["width"] == s["frameSize"][0]
          and overlay(s)["region"]["width"] == 1, overlay(s))
    act("setAssist", name="zebra", value="70")
    s = wait(lambda s: overlay(s) and overlay(s)["zebra"] > 5)
    check("зебра 70 % — полосы кожи больше, чем пересвета", overlay(s) and overlay(s)["zebra"] > 5, overlay(s))
    shot(out, "1-zebra-70")
    r = act("setAssist", name="zebra", value="50")
    check("неизвестное значение — 409 с подсказкой", r.get("status") == 409 and "95" in (r.get("error") or ""), r)

    # false color и легенда
    act("setAssist", name="zebra", value="off")
    act("setAssist", name="falseColor", value="on")
    s = wait(lambda s: overlay(s) and overlay(s)["zebra"] == 0)
    check("false color — растр есть, зебры нет", overlay(s) and overlay(s)["zebra"] == 0, overlay(s))
    shot(out, "2-false-color")
    act("setAssist", name="falseColor", value="off")

    # пикинг
    act("setAssist", name="peaking", value="mid")
    s = wait(lambda s: overlay(s) and overlay(s)["peaking"] > 0)
    one = overlay(s)["peaking"] if overlay(s) else 0
    check("пикинг — края подсвечены", 0.1 < one < 15, overlay(s))
    act("setAssist", name="peaking", value="high")
    s = wait(lambda s: overlay(s) and overlay(s)["peaking"] > one)
    check("пикинг «Высокий» подсвечивает больше, чем «Средний»", overlay(s) and overlay(s)["peaking"] > one, overlay(s))
    act("setAssist", name="peakingColor", value="yellow")
    shot(out, "3-peaking")

    # увеличение: растр — видимая часть, крупнее во столько же раз
    act("setZoom", factor=4, x=0.7, y=0.55)
    s = wait(lambda s: overlay(s) and overlay(s)["region"]["width"] < 0.5)
    o = overlay(s)
    check("при 4× растр — видимая часть кадра с запасом", o and 0.2 < o["region"]["width"] < 0.45, o)
    scale = o["width"] / (o["region"]["width"] * s["frameSize"][0]) if o else 0
    check("…и почти вчетверо крупнее", 3 < scale <= 4.01, f"scale {scale:.2f}")
    check("пикинг при увеличении есть", o and o["peaking"] > 0, o)
    shot(out, "4-peaking-zoom-4x")
    act("setZoom", factor=8, x=0.62, y=0.58)
    s = wait(lambda s: overlay(s) and overlay(s)["region"]["width"] < 0.2)
    check("при 8× растр тоже успевает", overlay(s) and overlay(s)["region"]["width"] < 0.2, overlay(s))
    shot(out, "5-peaking-zoom-8x")
    act("resetZoom")
    s = wait(lambda s: overlay(s) and overlay(s)["region"]["width"] == 1)
    check("1× — снова весь кадр", overlay(s) and overlay(s)["region"]["width"] == 1, overlay(s))

    # маркеры: растр не нужен, но не мешают
    act("setAssist", name="peaking", value="off")
    for name, value in (("grid", "thirds"), ("center", "on"), ("frameMarker", "9:16"), ("safeZone", "90")):
        act("setAssist", name=name, value=value)
    s = wait(lambda s: overlay(s) is None)
    check("только маркеры — растра нет", overlay(s) is None, overlay(s))
    check("маркеры включены", values(s)["grid"] == "thirds" and values(s)["frameMarker"] == "9:16" and values(s)["center"] == "on"
          and values(s)["safeZone"] == "90", values(s))
    shot(out, "6-markers-9x16")
    act("setAssist", name="frameMarker", value="2.39:1")
    act("setAssist", name="grid", value="squares")
    time.sleep(0.4)
    shot(out, "7-markers-scope")

    # меню «Монитор»: открыть на инструменте, стрелки меняют значение, касание выбранной плитки — вкл/выкл
    act("openAssist", name="zebra")
    s = wait(lambda s: s["ui"]["assist"]["open"])
    check("меню открыто на зебре", s["ui"]["assist"]["open"] and s["ui"]["assist"]["selected"] == "zebra", s["ui"]["assist"])
    act("key", key="right")
    s = wait(lambda s: values(s)["zebra"] == "100")
    check("стрелка вправо: зебра выкл → 100 %", values(s)["zebra"] == "100", values(s))
    act("key", key="right")
    act("key", key="right")
    s = wait(lambda s: values(s)["zebra"] == "70")
    check("ещё две — 70 %", values(s)["zebra"] == "70", values(s))
    shot(out, "8-menu")
    act("toggleAssist", name="zebra")
    s = wait(lambda s: values(s)["zebra"] == "off")
    act("toggleAssist", name="zebra")
    s = wait(lambda s: values(s)["zebra"] == "70")
    check("вкл/выкл помнит 70 %", values(s)["zebra"] == "70", values(s))
    act("openFn")
    s = wait(lambda s: s["ui"]["fnOpen"])
    check("меню Fn закрывает «Монитор»", s["ui"]["fnOpen"] and not s["ui"]["assist"]["open"], s["ui"])
    act("closeOverlays")
    act("key", key="m")
    s = wait(lambda s: s["ui"]["assist"]["open"])
    check("M открывает меню", s["ui"]["assist"]["open"])
    act("key", key="m")
    s = wait(lambda s: not s["ui"]["assist"]["open"])
    check("M — закрывает", not s["ui"]["assist"]["open"])

    # всё сразу — частота кадров не падает
    for name, value in (("zebra", "95"), ("falseColor", "on"), ("peaking", "mid")):
        act("setAssist", name=name, value=value)
    wait(lambda s: overlay(s) is not None)
    full = fps_over(4)
    print(f"  частота со всеми инструментами: {full:.1f} к/с")
    check("со всеми инструментами кадров не меньше 90 % от обычного", full >= base_fps * 0.9, f"{full:.1f} против {base_fps:.1f}")
    act("setZoom", factor=8, x=0.5, y=0.5)
    time.sleep(1)
    zoomed = fps_over(4)
    print(f"  …и при 8×: {zoomed:.1f} к/с")
    check("при 8× тоже", zoomed >= base_fps * 0.9, f"{zoomed:.1f} против {base_fps:.1f}")
    shot(out, "9-all-zoom-8x")
    act("resetZoom")

    act("assistOff")
    s = wait(lambda s: overlay(s) is None)
    check("«Выключить всё» — растра нет", overlay(s) is None, overlay(s))

    # волновая форма
    s = state()
    if s["ui"]["waveform"]["on"]:
        act("toggleWaveform")
    act("waveformMode", mode="luma")
    act("toggleWaveform")
    s = wait(lambda s: s.get("waveform") is not None)
    w = s.get("waveform")
    check("волновая форма — яркость, 256 столбцов", w and w["mode"] == "luma" and w["columns"] == 256, w)
    # на картинке есть и почти чёрные деревья, и солнце на белом
    check("след — от теней до белого", w and w["low"] is not None and w["low"] < 40 and w["high"] >= 240, w)
    check("линия 100 % — на белом превью", w and w["white"] in (234, 255), w)
    shot(out, "10-waveform-luma")
    act("waveformMode", mode="parade")
    s = wait(lambda s: s.get("waveform") and s["waveform"]["mode"] == "parade")
    check("RGB-парад — три канала по 128 столбцов", s["waveform"] and s["waveform"]["columns"] == 384, s.get("waveform"))
    act("moveWaveform", x=1, y=0)
    s = wait(lambda s: s["ui"]["waveform"]["position"] is not None)
    check("волновую форму можно переставить", s["ui"]["waveform"]["position"] == {"x": 1, "y": 0}, s["ui"]["waveform"])
    shot(out, "11-waveform-parade")
    r = act("waveformMode", mode="vectorscope")
    check("неизвестная волновая форма — 409", r.get("status") == 409, r)
    act("toggleWaveform")
    s = wait(lambda s: s.get("waveform") is None)
    check("выключили — следа нет", s.get("waveform") is None, s.get("waveform"))

    # наложение кадра: эталон из файла прошлого раза (подложен другой план сцены), потом — текущий кадр
    act("setAssist", name="overlay", value="50")
    s = wait(lambda s: s.get("reference") is not None)
    ref = s.get("reference")
    check("наложение включили — эталон из прошлого раза", ref and ref["width"] == 1024, ref)
    check("…и плитка показывает 50 %", values(s)["overlay"] == "50", values(s))
    shot(out, "12-overlay-50")
    act("setZoom", factor=2, x=0.3, y=0.6)
    shot(out, "13-overlay-zoom-2x")
    act("resetZoom")
    act("referenceFromFrame")
    s = wait(lambda s: s.get("reference") and s["reference"]["source"].startswith("кадр"))
    check("«Новый кадр» — эталон из живого кадра", s.get("reference") and s["reference"]["source"].startswith("кадр"), s.get("reference"))
    act("openAssist", name="overlay")
    shot(out, "14-overlay-menu")
    act("closeAssist")
    act("setAssist", name="overlay", value="off")
    s = wait(lambda s: values(s)["overlay"] == "off")
    check("наложение выключено", values(s)["overlay"] == "off", values(s))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/monitor")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    saved = plistlib.loads(subprocess.run(["defaults", "export", DOMAIN, "-"], capture_output=True).stdout or plistlib.dumps({}))
    before = {key: saved.get(key) for key in KEYS}
    reference = open(REFERENCE, "rb").read() if os.path.exists(REFERENCE) else None
    # эталон «прошлого раза» — другой план той же сцены: наложение видно на снимке
    os.makedirs(os.path.dirname(REFERENCE), exist_ok=True)
    with open(os.path.join(ROOT, "scripts/assets/viewfinder-pan.jpg"), "rb") as src, open(REFERENCE, "wb") as dst:
        dst.write(src.read())
    subprocess.run(["defaults", "write", DOMAIN, "referenceSource", "-string", "viewfinder-pan.jpg"])
    cam = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA],
                           stdout=open(os.path.join(args.out, "camera.log"), "w"), stderr=subprocess.STDOUT)
    time.sleep(0.5)
    env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
    proc = subprocess.Popen([os.path.join(args.app, "Contents/MacOS/ZVViewer"), "-ApplePersistenceIgnoreState", "YES"], env=env,
                            stdout=open(os.path.join(args.out, "app.log"), "w"), stderr=subprocess.STDOUT, start_new_session=True)
    try:
        s = wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3, 40)
        if not s or s.get("phase") != "ready":
            print(f"программа не дошла до ready: {s and s.get('phase')}")
            return 1
        time.sleep(2)
        autohide = s["prefs"]["autoHide"]
        try:
            run(args.out)
        finally:
            act("setPreference", key="autoHide", value="true" if autohide else "false")
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        cam.terminate()
        cam.wait()
        # настройки инструментов и эталон — как были до запуска
        for key, value in before.items():
            if value is None:
                subprocess.run(["defaults", "delete", DOMAIN, key], capture_output=True)
            elif isinstance(value, bool):
                subprocess.run(["defaults", "write", DOMAIN, key, "-bool", "true" if value else "false"])
            elif isinstance(value, bytes):
                subprocess.run(["defaults", "write", DOMAIN, key, "-data", value.hex()])
            else:
                subprocess.run(["defaults", "write", DOMAIN, key, "-string", str(value)])
        if reference is None:
            os.remove(REFERENCE)
        else:
            with open(REFERENCE, "wb") as f:
                f.write(reference)
    print(f"\n{passed} из {passed + failed} проверок прошли; скриншоты — {args.out}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
