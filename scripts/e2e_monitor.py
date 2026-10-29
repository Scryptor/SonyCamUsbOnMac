#!/usr/bin/env python3
"""e2e_monitor.py — инструменты монитора в программе для Mac против поддельной камеры: зебра, false color, пикинг,
маркеры, меню «Монитор», растр при цифровом увеличении, частота кадров со всем включённым, волновая форма (яркость и
RGB-парад) и наложение эталонного кадра: непрозрачность, «спрятать», эталон из файла, панель наложения. И картинка на экране:
LUT («Авто» по профилю камеры, ручной, свой файл .cube; помощь по картинке с LUT или по сигналу), зеркало и переворот с фокусом
касанием и частота кадров с LUT, зеркалом и всей помощью; на цветных полосах (scripts/assets/bars.jpg) — вектороскоп (отсчёты у
шести меток, масштаб ×2, место панели) и пипетка (цвет и яркость в точке).

Запускает scripts/fake_camera.py на 127.0.0.1:15741 и программу с API автоматизации (адрес камеры подменён), проверяет
/state и снимает скриншоты в --out. Эталоном для наложения подкладывает другой план той же сцены
(scripts/assets/viewfinder-pan.jpg). Настройки программы (они общие с установленной), эталон и папка своих LUT, какими они были
до запуска, возвращаются — и после ошибки проверки, и после Ctrl-C, и после сигнала (в том числе `perl -e 'alarm N'`).
Никакого поиска камер в сети: программа подключается только к адресу поддельной камеры.

Общие помощники (`Stand`, `FakeCamera`, `App`, `check`, `wait`…) берут и другие сквозные скрипты: e2e_lens.py, e2e_newer.py,
e2e_gallery.py, e2e_quality.py. Сводка — общими счётчиками `passed` и `failed`.

  scripts/e2e_monitor.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/monitor]
"""
import argparse, json, os, plistlib, shutil, signal, socket, subprocess, sys, tempfile, time, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
# не 15740: установленная программа, запомнившая 127.0.0.1, сама подключилась бы к поддельной камере
CAMERA = "127.0.0.1:15741"
# управляющий порт поддельной камеры (fake_camera.py --control, команды — fake_control.py); не 15742: это порт камеры C у fake_cameras.py
CONTROL = "127.0.0.1:15751"
FAKE_CAMERA = os.path.join(ROOT, "scripts/fake_camera.py")
INFO = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")
DOMAIN = "local.zve10viewer"
REFERENCE = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/reference.jpg")
LUT_FOLDER = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/LUTs")
BARS = os.path.join(ROOT, "scripts/assets/bars.jpg")
# объектив с физикой (fake_camera.py): шаг Near/Far сдвигает фокус на 3,5 единицы шкалы 0…100, ответ приходит через 80 мс, у края
# малые шаги не работают — как у настоящего ZV-E10 с китовым объективом; перевод фокуса к метке на нём идёт честно, шагами
LENS_PHYSICS = ["--focus-gain", "3.5", "--focus-step-ms", "80", "--focus-deadband", "2"]

# все инструменты меню «Монитор» — как в ui.assist.values
TOOLS = ["zebra", "falseColor", "peaking", "peakingColor", "lut", "vectorscope", "probe", "grid", "center", "frameMarker", "safeZone",
         "overlay", "orientation"]

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


def shot(out, name, window=None):
    """Скриншот окна программы в out/name.png; window="presets" — окно «Пресеты»."""
    # анимации (увеличение, меню) — досматриваем до конца, иначе снимок ловит их середину
    time.sleep(0.6)
    png = call("/screenshot" + (f"?window={window}" if window else ""), raw=True)
    with open(os.path.join(out, name + ".png"), "wb") as f:
        f.write(png)


def save_prefs():
    """Все настройки программы — общие с установленной программой (тот же bundle id)."""
    data = subprocess.run(["defaults", "export", DOMAIN, "-"], capture_output=True).stdout
    return plistlib.loads(data) if data else {}


def restore_prefs(before):
    """Настройки — как были: и инструменты, и то, что программа запомнила о поддельной камере (`cameras`), и окно."""
    now = save_prefs()
    for key in set(now) | set(before):
        value = before.get(key)
        if now.get(key) == value:
            continue
        if value is None:
            args = ["delete", DOMAIN, key]
        elif isinstance(value, bool):
            args = ["write", DOMAIN, key, "-bool", "true" if value else "false"]
        elif isinstance(value, int):
            args = ["write", DOMAIN, key, "-int", str(value)]
        elif isinstance(value, float):
            args = ["write", DOMAIN, key, "-float", repr(value)]
        elif isinstance(value, bytes):
            args = ["write", DOMAIN, key, "-data", value.hex()]
        elif isinstance(value, str):
            args = ["write", DOMAIN, key, "-string", value]
        else:
            print(f"  ! настройку {key} вернуть не умею — проверьте её сами")
            continue
        subprocess.run(["defaults", *args], capture_output=True)


def lut_folder():
    """Что лежит в папке своих LUT: список имён файлов; None — самой папки нет."""
    return sorted(os.listdir(LUT_FOLDER)) if os.path.isdir(LUT_FOLDER) else None


def restore_lut_folder(before):
    """Папка своих LUT — как была: что в неё положили проверки, убрано; не было папки — нет и её. Программа к этому времени уже
    остановлена."""
    now = lut_folder()
    if now is None:
        return
    for name in now:
        if before is None or name not in before:
            try:
                os.remove(os.path.join(LUT_FOLDER, name))
            except OSError:
                pass
    if before is None:
        try:
            os.rmdir(LUT_FOLDER)
        except OSError:
            print("  ! в папке своих LUT осталось лишнее — уберите сами:", LUT_FOLDER)


def terminate_on_signals():
    """SIGTERM, SIGHUP и SIGALRM (его шлёт `perl -e 'alarm N'`) обрывают прогон исключением: без этого скрипт умирал бы на
    месте, а программа и поддельные камеры оставались бы работать. Остановка идёт в `finally`, повторные сигналы ей не мешают."""
    def stop(signum, frame):
        for name in ("SIGTERM", "SIGHUP", "SIGALRM"):
            signal.signal(getattr(signal, name), signal.SIG_IGN)
        raise KeyboardInterrupt(f"сигнал {signum}")
    for name in ("SIGTERM", "SIGHUP", "SIGALRM"):
        signal.signal(getattr(signal, name), stop)


def stop_process(proc, seconds=8):
    """Мягко, потом жёстко; остановленный или не запущенный — ничего."""
    if proc is None or proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=seconds)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


class FakeCamera:
    """Поддельная камера (scripts/fake_camera.py) на CAMERA с управляющим портом CONTROL. Одна за раз: `start` с другими
    параметрами после `stop` — другая камера на том же адресе, программа тихо переподключается, а поиска в сети не нужно."""

    def __init__(self, out, log="camera.log"):
        self.log_path = os.path.join(out, log)
        self.proc = None

    def start(self, *args):
        self.stop()
        self.mark("камера: " + " ".join(args))
        self.proc = subprocess.Popen([sys.executable, FAKE_CAMERA, "--listen", CAMERA, "--control", CONTROL, *args],
                                     stdout=open(self.log_path, "a"), stderr=subprocess.STDOUT)
        time.sleep(0.6)

    def stop(self):
        stop_process(self.proc)

    def alive(self):
        return self.proc is not None and self.proc.poll() is None

    def mark(self, text):
        """Строка-разделитель в журнале камеры."""
        with open(self.log_path, "a") as f:
            f.write(f"\n===== {text} =====\n")

    def log(self):
        with open(self.log_path) as f:
            return f.read()

    def session_log(self):
        """Журнал камеры, запущенной последней, — без журнала прежних: считать, что она получила, можно с нуля."""
        return self.log().rsplit("\n===== ", 1)[-1]

    def control(self, *words):
        """Команда на управляющий порт (fake_control.py): объектив сменили, список переименовали, событие… Ответ: ok, unknown или
        error: …"""
        host, port = CONTROL.rsplit(":", 1)
        with socket.create_connection((host, int(port)), timeout=3) as c:
            c.sendall((" ".join(map(str, words)) + "\n").encode())
            return c.makefile().readline().strip()


class App:
    """Программа с API автоматизации против камеры на CAMERA (ZV_PTPIP_HOST: только этот адрес, без поиска в сети)."""

    def __init__(self, bundle, out, log="app.log"):
        self.exe = os.path.join(bundle, "Contents/MacOS/ZVViewer")
        self.log_path = os.path.join(out, log)
        self.proc = None

    def start(self):
        env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
        self.proc = subprocess.Popen([self.exe, "-ApplePersistenceIgnoreState", "YES"], env=env,
                                     stdout=open(self.log_path, "w"), stderr=subprocess.STDOUT, start_new_session=True)

    def ready(self, seconds=40):
        """Ждёт, пока камера на связи и кадры идут; отдаёт /state или None."""
        s = wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3, seconds)
        if not s or s.get("phase") != "ready":
            print(f"программа не дошла до ready: {s and s.get('phase')}")
            return None
        return s

    def stop(self):
        if self.proc is not None and self.proc.poll() is None:
            self.proc.send_signal(signal.SIGTERM)
        stop_process(self.proc)


class Stand:
    """Прогон целиком: камера, программа, настройки и папка своих LUT, какими они были до него. На выходе — при любом исходе —
    программа и камера остановлены, настройки и папка возвращены:

        with Stand(args.app, args.out) as stand:
            stand.camera.start("--focus-gain", "3.5")
            stand.app.start()
            ...
    """

    def __init__(self, bundle, out):
        os.makedirs(out, exist_ok=True)
        self.out = out
        self.camera = FakeCamera(out)
        self.app = App(bundle, out)
        self.prefs = None
        self.luts = None

    def __enter__(self):
        terminate_on_signals()
        self.prefs = save_prefs()
        self.luts = lut_folder()
        return self

    def swap_camera(self, *args, seconds=60):
        """Другая камера на том же адресе (аргументы — как у fake_camera.py): прежняя останавливается, новая стартует, программа тихо
        переподключается. Ждёт, пока сеанс восстановлен и кадры идут; отдаёт /state (None — не дождался)."""
        before = state()["link"]["reconnects"]
        self.camera.start(*args)
        return wait(lambda s: s["link"]["reconnects"] > before and s["phase"] == "ready" and s["fps"] > 3, seconds)

    def __exit__(self, *exc):
        self.app.stop()
        self.camera.stop()
        restore_prefs(self.prefs)
        restore_lut_folder(self.luts)
        return False


def real_cameras_touched():
    """Соединения PTP/IP (порт 15740) с чужими адресами: настоящая камера в сети. Проверки ходят только к 127.0.0.1, так что
    быть их не должно."""
    out = subprocess.run(["netstat", "-an", "-p", "tcp"], capture_output=True, text=True).stdout
    return [line for line in out.splitlines()
            if (line.split() + [""] * 6)[4].endswith(".15740") and not line.split()[4].startswith("127.")]


def setting(s, name):
    """Настройка камеры из /state по идентификатору."""
    return next(item for item in s["settings"] if item["id"] == name)


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
    check("13 инструментов в ui.assist", sorted(values(s)) == sorted(TOOLS), sorted(values(s)))
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

    # панель наложения: любая непрозрачность, «спрятать» (клавиша O, миниатюра), эталон из файла, место панели
    act("setAssist", name="overlay", value="40")
    s = wait(lambda s: values(s)["overlay"] == "40")
    check("непрозрачность с ползунка — 40 %", values(s)["overlay"] == "40", values(s))
    act("toggleOverlay")
    s = wait(lambda s: s["ui"]["overlay"]["hidden"])
    check("O — эталон спрятан, наложение включено", s["ui"]["overlay"]["hidden"] and values(s)["overlay"] == "40", s["ui"]["overlay"])
    shot(out, "15-overlay-hidden")
    act("toggleOverlay")
    s = wait(lambda s: not s["ui"]["overlay"]["hidden"])
    check("O ещё раз — эталон снова виден", not s["ui"]["overlay"]["hidden"], s["ui"]["overlay"])
    picture = os.path.join(ROOT, "scripts/assets/viewfinder-pan.jpg")
    act("referenceFromFile", path=picture)
    s = wait(lambda s: (s.get("reference") or {}).get("source") == "viewfinder-pan.jpg")
    check("эталон из файла — подпись по имени файла", (s.get("reference") or {}).get("source") == "viewfinder-pan.jpg",
          s.get("reference"))
    r = act("referenceFromFile", path="/nonexistent/picture.png")
    check("файла нет — 409", r.get("status") == 409, r)
    act("moveOverlayPanel", x=0, y=0.5)
    s = wait(lambda s: s["ui"]["overlay"]["position"] == {"x": 0, "y": 0.5})
    check("панель наложения переставили", s["ui"]["overlay"]["position"] == {"x": 0, "y": 0.5}, s["ui"]["overlay"])
    shot(out, "16-overlay-file-panel")
    act("toggleOverlay")
    act("setAssist", name="overlay", value="off")
    s = wait(lambda s: values(s)["overlay"] == "off")
    check("наложение выключено — «спрятано» сброшено", values(s)["overlay"] == "off" and not s["ui"]["overlay"]["hidden"], values(s))
    act("toggleOverlay")
    s = wait(lambda s: values(s)["overlay"] == "40")
    check("O при выключенном — включает с прошлыми 40 %", values(s)["overlay"] == "40" and not s["ui"]["overlay"]["hidden"], values(s))
    act("setAssist", name="overlay", value="off")
    s = wait(lambda s: values(s)["overlay"] == "off")
    check("наложение выключено", values(s)["overlay"] == "off", values(s))
    return base_fps


# ---------------------------------------------------------------------------------------------------------------------------
# Картинка на экране: LUT, зеркало, частота кадров

def near(a, b, tolerance):
    return a is not None and abs(a - b) <= tolerance


def picture(s):
    return s["picture"]


def log_frames_arrived(s):
    """Камера отдаёт лог (PP7–PP10): у лога нет точек ярче 240, так что белый уровень превью без LUT — 234, а у обычной картинки
    с солнцем — 255."""
    return picture(s)["whiteLevel"] == 234


def run_lut(out):
    """LUT «Авто»: по профилю камеры, за секунду после смены профиля; помощь по картинке с LUT или по сигналу."""
    profile = setting(state(), "pictureProfile").get("value")
    act("assistOff")
    act("setAssist", name="lut", value="auto")
    s = wait(lambda s: picture(s)["lut"]["mode"] == "auto")
    check("LUT «Авто» выбран, а у профиля без лога встроенного LUT нет", picture(s)["lut"]["mode"] == "auto"
          and picture(s)["lut"]["resolved"] is None, picture(s)["lut"])
    check("в состоянии картинки есть размах яркости", isinstance(picture(s)["lumaSpread"], int), picture(s))

    # профиль PP8 (S-Log3) — камера отдаёт лог; ждём кадры лога (она строит их в фоне при первом выборе профиля)
    act("setAssist", name="lut", value="off")
    act("request", setting="pictureProfile", value=8)
    s = wait(lambda s: log_frames_arrived(s) and picture(s)["lut"]["mode"] == "off", 12)
    check("PP8 без LUT: кадры лога, белый уровень 234", log_frames_arrived(s), picture(s))
    off = picture(s)
    act("setAssist", name="lut", value="auto")
    s = wait(lambda s: picture(s)["lut"]["resolved"] == "slog3cine" and picture(s)["whiteLevel"] == 255, 4)
    check("PP8: «Авто» выбрало S-Log3 Cine", picture(s)["lut"]["resolved"] == "slog3cine", picture(s)["lut"])
    on = picture(s)
    print(f"  размах яркости p99 − p1: {off['lumaSpread']} без LUT → {on['lumaSpread']} с LUT; средняя {off['meanLuma']} → {on['meanLuma']}; "
          f"белый {off['whiteLevel']} → {on['whiteLevel']}")
    check("с LUT размах яркости больше, чем без (лог растянут)", on["lumaSpread"] > off["lumaSpread"] * 1.5,
          (on["lumaSpread"], off["lumaSpread"]))
    check("с LUT белый уровень помощи 255 (после LUT белое — снова 255), без него 234", on["whiteLevel"] == 255 and off["whiteLevel"] == 234,
          (on["whiteLevel"], off["whiteLevel"]))
    shot(out, "17-lut-auto-slog3")

    # «Авто» следит за профилем камеры и успевает за секунду: PP0 → встроенного LUT нет (resolved null), и снова PP8 → S-Log3 Cine
    t0 = time.monotonic()
    act("request", setting="pictureProfile", value=0)
    s = wait(lambda s: picture(s)["lut"]["resolved"] is None, 3)
    took = time.monotonic() - t0
    check(f"PP0: «Авто» ничего не выбирает (resolved null) за {took:.2f} с", picture(s)["lut"]["resolved"] is None and took <= 1.0,
          (picture(s)["lut"], took))
    t0 = time.monotonic()
    act("request", setting="pictureProfile", value=8)
    s = wait(lambda s: picture(s)["lut"]["resolved"] == "slog3cine", 3)
    took = time.monotonic() - t0
    check(f"PP8: resolved == slog3cine за {took:.2f} с (≤ 1 с)", picture(s)["lut"]["resolved"] == "slog3cine" and took <= 1.0,
          (picture(s)["lut"], took))
    time.sleep(1)

    # помощь «по картинке с LUT» (по умолчанию) или «по сигналу камеры»
    act("lutScopes", on=False)
    s = wait(lambda s: not picture(s)["lut"]["scopes"] and picture(s)["whiteLevel"] == 234, 4)
    check("«Помощь — без LUT»: picture.lut.scopes false, помощь считает сигнал камеры (белый 234)",
          not picture(s)["lut"]["scopes"] and picture(s)["whiteLevel"] == 234, picture(s))
    check("…и размах яркости — как без LUT (±2)", near(picture(s)["lumaSpread"], off["lumaSpread"], 2), (picture(s)["lumaSpread"], off["lumaSpread"]))
    act("lutScopes", on=True)
    s = wait(lambda s: picture(s)["lut"]["scopes"] and picture(s)["whiteLevel"] == 255, 4)
    check("«Помощь — по картинке с LUT» снова: белый 255, размах вырос", picture(s)["whiteLevel"] == 255
          and picture(s)["lumaSpread"] > off["lumaSpread"] * 1.5, picture(s))

    # встроенный LUT, выбранный рукой, не зависит от профиля; «Свой» без файла и «Выкл» встроенного не дают
    for mode in ("slog3", "slog2", "hlg", "slog3cine"):
        act("setAssist", name="lut", value=mode)
        s = wait(lambda s: picture(s)["lut"]["resolved"] == mode, 3)
        check(f"LUT «{mode}» вручную — resolved {mode}", picture(s)["lut"]["resolved"] == mode, picture(s)["lut"])
    act("setAssist", name="lut", value="file")
    s = wait(lambda s: picture(s)["lut"]["mode"] == "file")
    check("«Свой» без выбранного файла: resolved null", picture(s)["lut"]["resolved"] is None and picture(s)["lut"]["file"] is None, picture(s)["lut"])
    r = act("setAssist", name="lut", value="s-log5")
    check("неизвестный LUT — 409 со списком", r.get("status") == 409 and "slog3cine" in (r.get("error") or ""), r)
    act("setAssist", name="lut", value="off")
    s = wait(lambda s: picture(s)["lut"]["mode"] == "off" and picture(s)["whiteLevel"] == 234, 4)
    check("LUT выключен: resolved null, белый снова 234", picture(s)["lut"]["resolved"] is None and picture(s)["whiteLevel"] == 234, picture(s))
    return profile


def identity_cube():
    """Тождественный куб 2³ — картинка с ним не меняется (красный меняется быстрее всех)."""
    rows = "".join(f"{r} {g} {b}\n" for b in (0, 1) for g in (0, 1) for r in (0, 1))
    return 'TITLE "e2e identity"\nLUT_3D_SIZE 2\n' + rows


def run_cube(out, log_spread):
    """Свой .cube: добавить, выбрать, считать помощь по нему и без него, убрать. Папка LUT после прогона — как была."""
    folder = tempfile.mkdtemp(prefix="zv-e2e-lut-")
    try:
        path = os.path.join(folder, "e2e-identity.cube")
        with open(path, "w") as f:
            f.write(identity_cube())
        bad = os.path.join(folder, "flat.cube")
        with open(bad, "w") as f:
            f.write("LUT_1D_SIZE 2\n0 0 0\n1 1 1\n")
        s = state()
        check("своих LUT нет", s["luts"] == [] or "e2e-identity" not in s["luts"], s["luts"])
        r = act("lutImport", path=path)
        check("lutImport: файл добавлен", r == {"ok": True}, r)
        s = wait(lambda s: "e2e-identity" in s["luts"])
        check("luts: в списке есть e2e-identity", "e2e-identity" in s["luts"], s["luts"])
        s = wait(lambda s: picture(s)["lut"]["mode"] == "file" and picture(s)["whiteLevel"] == 255, 4)
        check("и он выбран: LUT «Свой», файл e2e-identity, resolved null", picture(s)["lut"]["mode"] == "file"
              and picture(s)["lut"]["file"] == "e2e-identity" and picture(s)["lut"]["resolved"] is None, picture(s)["lut"])
        check("куб в потоке кадров: белый уровень помощи 255 (без LUT на логе 234)", picture(s)["whiteLevel"] == 255, picture(s))
        check("тождественный куб картинку не меняет: размах и средняя яркость как без LUT",
              near(picture(s)["lumaSpread"], log_spread["lumaSpread"], 1) and near(picture(s)["meanLuma"], log_spread["meanLuma"], 0.5),
              (picture(s)["lumaSpread"], picture(s)["meanLuma"], log_spread))
        shot(out, "18-lut-own-cube")
        act("lutScopes", on=False)
        s = wait(lambda s: picture(s)["whiteLevel"] == 234, 4)
        check("«Помощь — без LUT» и со своим файлом: белый 234", picture(s)["whiteLevel"] == 234 and not picture(s)["lut"]["scopes"], picture(s))
        act("lutScopes", on=True)

        # отказы: нет файла, не 3D, нет в списке
        r = act("lutImport", path=os.path.join(folder, "missing.cube"))
        check("lutImport: файла нет — 409 с причиной", r.get("status") == 409 and bool(r.get("error")), r)
        r = act("lutImport", path=bad)
        check("lutImport: не 3D LUT — 409 «Нужен 3D LUT…»", r.get("status") == 409 and "3D" in (r.get("error") or ""), r)
        check("после отказов список прежний", state()["luts"].count("e2e-identity") == 1 and "flat" not in state()["luts"], state()["luts"])
        r = act("lutFile", name="no-such-lut")
        check("lutFile: такого LUT нет — 409", r.get("status") == 409, r)
        r = act("lutDelete", name="no-such-lut")
        check("lutDelete: такого LUT нет — 409", r.get("status") == 409, r)
        check("выбор после отказов тот же", picture(state())["lut"]["file"] == "e2e-identity", picture(state())["lut"])

        # выбор по имени и удаление
        act("setAssist", name="lut", value="off")
        r = act("lutFile", name="e2e-identity")
        s = wait(lambda s: picture(s)["lut"]["mode"] == "file" and picture(s)["whiteLevel"] == 255, 4)
        check("lutFile: «Свой» и этот файл снова", r == {"ok": True} and picture(s)["lut"]["mode"] == "file"
              and picture(s)["lut"]["file"] == "e2e-identity", (r, picture(s)["lut"]))
        r = act("lutDelete", name="e2e-identity")
        s = wait(lambda s: "e2e-identity" not in s["luts"] and picture(s)["whiteLevel"] == 234, 4)
        check("lutDelete: файла нет в списке", r == {"ok": True} and "e2e-identity" not in s["luts"], (r, s["luts"]))
        check("…LUT выключен, выбор забыт, белый снова 234", picture(s)["lut"]["mode"] == "off" and picture(s)["lut"]["file"] is None
              and picture(s)["whiteLevel"] == 234, picture(s))
        check("и в папке программы его нет", not os.path.exists(os.path.join(LUT_FOLDER, "e2e-identity.cube")), lut_folder())
    finally:
        shutil.rmtree(folder, ignore_errors=True)


def run_keys():
    """Клавиши L, V, I — как плитки LUT, «Вектороскоп» и «Пипетка»."""
    act("assistOff")
    for key, tool in (("l", "lut"), ("v", "vectorscope"), ("i", "probe")):
        act("key", key=key)
        s = wait(lambda s: values(s)[tool] != "off")
        on = values(s)[tool]
        act("key", key=key)
        s = wait(lambda s: values(s)[tool] == "off")
        check(f"клавиша {key.upper()}: {tool} включился ({on}) и выключился", on != "off" and values(s)[tool] == "off", values(s))
    r = act("key", key="q")
    check("неизвестная клавиша — 409", r.get("status") == 409, r)


def focus_frame(s):
    return next((f for f in s["lens"]["focusFrames"] if f["kind"] == "focus"), None)


def run_mirror(out):
    """Зеркало и переворот: фокус касанием по экранной точке — в кадре камеры по ту сторону (рамка AF у камеры)."""
    area = setting(state(), "focusArea").get("value")
    act("assistOff")
    act("resetZoom")
    act("request", setting="focusArea", value=0x103)                 # «Точка L»: точка AF двигает рамку
    s = wait(lambda s: s["lens"]["touchFocus"] == "areaPosition", 4)
    check("зона «Точка» — точка AF", s["lens"]["touchFocus"] == "areaPosition", s["lens"])
    cases = (("mirror", None, (0.3, 0.5), (0.7, 0.5), "зеркало: 0,3 на экране — 0,7 у камеры"),
             ("mirror", 2, (0.4, 0.4), (0.6, 0.4), "зеркало и увеличение 2×: 0,4 — 0,6 (увеличение на доли не влияет)"),
             ("180", None, (0.2, 0.1), (0.8, 0.9), "переворот 180°: (0,2; 0,1) — (0,8; 0,9)"),
             ("mirror180", None, (0.2, 0.1), (0.2, 0.9), "зеркало и 180°: (0,2; 0,1) — (0,2; 0,9)"),
             ("off", None, (0.3, 0.5), (0.3, 0.5), "без зеркала точка та же"))
    for orientation, zoom, tap, camera, title in cases:
        act("setAssist", name="orientation", value=orientation)
        if zoom:
            act("setZoom", factor=zoom, x=0.5, y=0.5)
        else:
            act("resetZoom")
        s = wait(lambda s: picture(s)["orientation"] == orientation)
        act("touchFocus", x=tap[0], y=tap[1])
        s = wait(lambda s: focus_frame(s) and near(focus_frame(s)["x"], camera[0], 0.01) and near(focus_frame(s)["y"], camera[1], 0.01), 4)
        frame = focus_frame(s)
        check(f"рамка AF у камеры: {title}", frame is not None and near(frame["x"], camera[0], 0.01) and near(frame["y"], camera[1], 0.01),
              (frame and (round(frame["x"], 3), round(frame["y"], 3)), camera))
        if orientation == "mirror" and zoom:
            shot(out, "19-mirror-zoom-2x-touch")
    act("setAssist", name="orientation", value="off")
    act("resetZoom")
    act("request", setting="focusArea", value=area)
    wait(lambda s: setting(s, "focusArea").get("value") == area, 4)


def run_picture_fps(out, base_fps):
    """Частота кадров с LUT, зеркалом и всей помощью сразу — не меньше 90 % от обычной."""
    act("assistOff")
    act("request", setting="pictureProfile", value=8)
    wait(log_frames_arrived, 12)
    s = state()
    if s["ui"]["waveform"]["on"]:
        act("toggleWaveform")
    act("waveformMode", mode="luma")
    act("toggleWaveform")
    for name, value in (("lut", "auto"), ("orientation", "mirror"), ("zebra", "95"), ("falseColor", "on"), ("peaking", "mid"),
                        ("vectorscope", "on"), ("probe", "on")):
        act("setAssist", name=name, value=value)
    s = wait(lambda s: overlay(s) is not None and s["waveform"] is not None and s["vectorscope"]["targets"] is not None
             and s["probe"]["percent"] is not None, 6)
    check("включено всё: LUT, зеркало, растр, волновая форма, вектороскоп, пипетка",
          picture(s)["lut"]["resolved"] == "slog3cine" and picture(s)["orientation"] == "mirror" and overlay(s) is not None
          and s["waveform"] is not None and s["vectorscope"]["targets"] is not None and s["probe"]["percent"] is not None,
          (picture(s), s["vectorscope"], s["probe"]))
    full = fps_over(4)
    print(f"  частота с LUT, зеркалом и всей помощью: {full:.1f} к/с (обычная {base_fps:.1f})")
    check("с LUT, зеркалом и всей помощью кадров не меньше 90 % от обычного", full >= base_fps * 0.9, f"{full:.1f} против {base_fps:.1f}")
    shot(out, "20-lut-mirror-all-tools")
    act("assistOff")
    s = state()
    if s["ui"]["waveform"]["on"]:
        act("toggleWaveform")
    wait(lambda s: s["vectorscope"]["targets"] is None and s["waveform"] is None, 4)


def run_picture(out, base_fps):
    """Картинка: LUT, свой .cube, клавиши, зеркало с фокусом касанием, частота кадров. Профиль изображения камеры — как был."""
    act("assistOff")
    act("resetZoom")
    act("setPreference", key="autoHide", value="false")
    profile = setting(state(), "pictureProfile").get("value")
    try:
        run_lut(out)
        wait(log_frames_arrived, 12)
        log_spread = picture(state())
        run_cube(out, log_spread)
        run_keys()
        run_mirror(out)
        run_picture_fps(out, base_fps)
    finally:
        act("assistOff")
        act("resetZoom")
        act("request", setting="pictureProfile", value=profile)
        wait(lambda s: setting(s, "pictureProfile").get("value") == profile and not log_frames_arrived(s), 12)


# ---------------------------------------------------------------------------------------------------------------------------
# Цветные полосы: вектороскоп и пипетка

def all_marks(targets):
    return targets is not None and sorted(targets) == sorted(["R", "Mg", "B", "Cy", "G", "Yl"])


def run_bars(out, stand):
    """Камера с цветными полосами 75 % (scripts/assets/bars.jpg): вектороскоп и пипетка видят их цвета."""
    stand.swap_camera("--picture", BARS)
    time.sleep(2)
    act("assistOff")
    act("resetZoom")
    act("setPreference", key="autoHide", value="false")
    s = wait(lambda s: picture(s)["whiteLevel"] == 234 and picture(s)["lut"]["mode"] == "off", 6)
    check("полосы: LUT нет, белый уровень 234", picture(s)["lut"]["mode"] == "off" and picture(s)["whiteLevel"] == 234, picture(s))
    check("вектороскоп выключен — отсчётов нет", s["vectorscope"]["on"] is False and s["vectorscope"]["targets"] is None
          and s["vectorscope"]["peakCb"] is None, s["vectorscope"])

    act("setAssist", name="vectorscope", value="on")
    s = wait(lambda s: all_marks(s["vectorscope"]["targets"]), 6)
    v = s["vectorscope"]
    print("  отсчёты у меток:", v["targets"], " пик:", (v["peakCb"], v["peakCr"]))
    check("вектороскоп ×1: у всех шести меток 75 % есть отсчёты", all_marks(v["targets"]) and all(n > 0 for n in v["targets"].values()),
          v["targets"])
    check("…и их много: полоса — восьмая часть кадра", all(n > 1000 for n in v["targets"].values()), v["targets"])
    check("пик — середина плоскости (белая и чёрная полосы), а не метка", near(v["peakCb"], 0, 0.01) and near(v["peakCr"], 0, 0.01)
          and v["zoom"] == 1, v)
    shot(out, "21-bars-vectorscope-x1")

    act("vectorscopeZoom", zoom=2)
    # zoom — выбор (сразу), отсчёты и пик — по последнему посчитанному кадру: после смены масштаба они отстают на кадр-другой
    s = wait(lambda s: s["vectorscope"]["zoom"] == 2 and all_marks(s["vectorscope"]["targets"])
             and all(n == 0 for n in s["vectorscope"]["targets"].values()), 4)
    v = s["vectorscope"]
    check("×2: метки 75 % лежат за краем плоскости — отсчётов у них нет", v["zoom"] == 2 and all_marks(v["targets"])
          and all(n == 0 for n in v["targets"].values()), v)
    shot(out, "22-bars-vectorscope-x2")
    r = act("vectorscopeZoom", zoom=3)
    check("масштаб 3 — 409 «zoom: 1 | 2»", r.get("status") == 409 and "1 | 2" in (r.get("error") or ""), r)
    act("vectorscopeZoom", zoom=1)
    s = wait(lambda s: s["vectorscope"]["zoom"] == 1 and all_marks(s["vectorscope"]["targets"])
             and all(n > 0 for n in s["vectorscope"]["targets"].values()), 4)
    check("×1 снова — отсчёты вернулись", s["vectorscope"]["zoom"] == 1 and all(n > 0 for n in s["vectorscope"]["targets"].values()),
          s["vectorscope"])

    act("moveVectorscope", x=0, y=0.5)
    s = wait(lambda s: s["ui"]["vectorscope"]["position"] == {"x": 0, "y": 0.5})
    check("панель вектороскопа переставили", s["ui"]["vectorscope"]["position"] == {"x": 0, "y": 0.5}, s["ui"]["vectorscope"])
    act("moveVectorscope", x=3, y=-1)
    s = wait(lambda s: s["ui"]["vectorscope"]["position"] == {"x": 1, "y": 0})
    check("место за пределами 0…1 — к краю", s["ui"]["vectorscope"]["position"] == {"x": 1, "y": 0}, s["ui"]["vectorscope"])
    act("setAssist", name="vectorscope", value="off")
    s = wait(lambda s: s["vectorscope"]["targets"] is None)
    check("вектороскоп выключили — отсчётов и пика нет", s["vectorscope"]["targets"] is None and s["vectorscope"]["peakCb"] is None, s["vectorscope"])

    # пипетка: середина красной полосы и белая полоса 75 %
    act("setAssist", name="probe", value="on")
    s = wait(lambda s: s["probe"]["percent"] is not None)
    check("пипетка включена: по умолчанию в центре кадра", s["probe"]["on"] and near(s["probe"]["x"], 0.5, 1e-6) and near(s["probe"]["y"], 0.5, 1e-6), s["probe"])
    act("setProbe", x=0.6875, y=0.3)
    time.sleep(0.5)                                    # показание отстаёт от точки на 1–2 кадра
    p = wait(lambda s: s["probe"]["g"] is not None and s["probe"]["g"] <= 15, 3)["probe"]
    print("  пипетка на красной полосе:", p)
    check("пипетка запомнила точку (доли кадра)", near(p["x"], 0.6875, 1e-3) and near(p["y"], 0.3, 1e-3), p)
    check("красная полоса: r ≥ 180, g ≤ 15, b ≤ 15", p["r"] >= 180 and p["g"] <= 15 and p["b"] <= 15, p)
    check("яркость красной 15–18 % от белого 234", 15 <= p["percent"] <= 18, p)
    shot(out, "23-bars-probe-red")
    act("setProbe", x=0.0625, y=0.3)
    time.sleep(0.5)
    p = wait(lambda s: s["probe"]["g"] is not None and s["probe"]["g"] > 150, 3)["probe"]
    print("  пипетка на белой полосе 75 %:", p, " белый уровень", picture(state())["whiteLevel"])
    check("белая полоса 75 %: яркость 79–84 % при белом 234", picture(state())["whiteLevel"] == 234 and 79 <= p["percent"] <= 84, p)
    check("…и серая: r = g = b ≈ 191", all(near(p[c], 191, 3) for c in "rgb"), p)
    # зеркало: пипетка по тому, что видно, — красная полоса теперь слева
    act("setAssist", name="orientation", value="mirror")
    wait(lambda s: picture(s)["orientation"] == "mirror")
    act("setProbe", x=1 - 0.6875, y=0.3)
    time.sleep(0.5)
    p = wait(lambda s: s["probe"]["g"] is not None and s["probe"]["g"] <= 15, 3)["probe"]
    check("зеркало: пипетка на видимой красной полосе (0,3125) читает красный", p["r"] >= 180 and p["g"] <= 15 and p["b"] <= 15, p)
    act("setAssist", name="orientation", value="off")
    r = act("setProbe", x=5, y=-3)
    s = wait(lambda s: s["probe"]["x"] == 1 and s["probe"]["y"] == 0)
    check("точка за кадром — к краю кадра", r == {"ok": True} and s["probe"]["x"] == 1 and s["probe"]["y"] == 0, s["probe"])
    act("setAssist", name="probe", value="off")
    s = wait(lambda s: s["probe"]["percent"] is None)
    check("пипетку выключили — показаний нет", not s["probe"]["on"] and s["probe"]["percent"] is None and s["probe"]["r"] is None, s["probe"])
    act("assistOff")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/monitor")
    args = ap.parse_args()
    reference = open(REFERENCE, "rb").read() if os.path.exists(REFERENCE) else None
    try:
        with Stand(args.app, args.out) as stand:
            # эталон «прошлого раза» — другой план той же сцены: наложение видно на снимке
            os.makedirs(os.path.dirname(REFERENCE), exist_ok=True)
            with open(os.path.join(ROOT, "scripts/assets/viewfinder-pan.jpg"), "rb") as src, open(REFERENCE, "wb") as dst:
                dst.write(src.read())
            subprocess.run(["defaults", "write", DOMAIN, "referenceSource", "-string", "viewfinder-pan.jpg"])
            stand.camera.start()
            stand.app.start()
            s = stand.app.ready()
            if not s:
                return 1
            time.sleep(2)
            autohide = s["prefs"]["autoHide"]
            try:
                base_fps = run(args.out)
                run_picture(args.out, base_fps)
                run_bars(args.out, stand)
                touched = real_cameras_touched()
                check("к настоящей камере (PTP/IP, порт 15740) не подключались", not touched, touched)
            finally:
                act("setPreference", key="autoHide", value="true" if autohide else "false")
    except KeyboardInterrupt as stop:
        print(f"\nпрервано: {stop}")
        return 2
    finally:
        # эталон — после остановки программы: она при выходе может записать свой
        if reference is None:
            if os.path.exists(REFERENCE):
                os.remove(REFERENCE)
        else:
            with open(REFERENCE, "wb") as f:
                f.write(reference)
    print(f"\n{passed} из {passed + failed} проверок прошли; скриншоты — {args.out}")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
