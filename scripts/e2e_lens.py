#!/usr/bin/env python3
"""e2e_lens.py — фокус и зум с экрана в программе для Mac против поддельной камеры: фокус касанием (касание экрана
камеры в зоне «Широкая», точка AF в зоне «Точка»), рамки фокуса камеры, моторный зум, шкала фокуса в MF. И метки фокуса A и B с
переводом за выбранное время — на двух объективах поддельной камеры: с физикой «×3,5, ответ 80 мс» (--focus-gain 3.5
--focus-step-ms 80 --focus-deadband 2) и с китовым PZ 16-50 по замерам настоящей ZV-E10 (--focus-lens pz16-50: шаг ±1 — ничего,
±2 — 1, ±3 — 3, ±5 — 5, ±7 — вся шкала, ответ через 0,4 с). На обоих: перевод к метке за 4 с, остановка, отмены (AF, ручной шаг,
второй «перевести», обрыв связи, смена объектива), отказы API, отказы камеры (Near/Far отвергнут — одна строка в журнале программы
за перевод). Только на PZ 16-50: перевод за 2 с не дольше 3,2 с, перевод «как можно быстрее» 84 → 63 встаёт у метки (±1), не
уходя за дальнюю из меток больше чем на 5 единиц, и камера не получает команд больше ±5.

Запускает scripts/fake_camera.py на 127.0.0.1:15741 (с управляющим портом: смена объектива — событие 0xC21B) и программу с API
автоматизации (адрес камеры подменён), проверяет /state и снимает скриншоты в --out. Зону и режим фокуса поддельной камеры
возвращает, настройки программы (они общие с установленной) — тоже, и после ошибки, Ctrl-C и сигнала.

  scripts/e2e_lens.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/lens]
"""
import argparse, os, re, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from e2e_monitor import LENS_PHYSICS as PHYSICS, Stand, act, check, real_cameras_touched, setting, shot, state, wait  # noqa: E402
import e2e_monitor  # noqa: E402

FOCUS_MODE_AFC, FOCUS_MODE_MF = 0x8004, 1
AREA_WIDE, AREA_SPOT_L = 0x1, 0x103


def lens(s):
    return s["lens"]


def near(a, b, tolerance=0.03):
    return abs(a - b) <= tolerance


def run(out, stand):
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
    # карточка «Метки фокуса» сама открывается, когда шкала с метками появилась впервые за запуск, — но только пока не отмечено
    # «Больше не показывать»; эта отметка живёт в настройках программы и могла остаться от пользователя, поэтому её сбрасываем
    # (настройки в конце вернёт Stand)
    act("setPreference", key="focusMarksHelpShown", value="false")
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    s = wait(lambda s: lens(s)["focusScale"], 4)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    time.sleep(1.0)
    check("карточка «Метки фокуса» открылась сама при первом показе шкалы", state()["ui"]["focusMarksHelp"], state()["ui"])
    shot(out, "3b-focus-marks-help")
    act("setPreference", key="focusMarksHelpShown", value="true")
    act("closeOverlays")
    check("«Больше не показывать» и щелчок мимо — карточки нет, в prefs отмечено", not state()["ui"]["focusMarksHelp"]
          and state()["prefs"]["focusMarksHelpShown"] is True, state()["ui"])
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
    stand.camera.stop()
    s = wait(lambda s: s.get("phase") != "ready" or (s.get("link") or {}).get("state") != "ok", 15)
    act("zoomStop")
    s = state()
    check("обрыв во время зума: отпустили — кнопка отпущена", lens(s)["zoomDirection"] == 0, lens(s))
    stand.camera.start()
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


# ---------------------------------------------------------------------------------------------------------------------------
# Метки фокуса A и B

def position(s=None):
    return lens(s or state())["focalPosition"]


class Glass:
    """Объектив поддельной камеры, на котором идут метки: как её запустить и чем он отличается для проверок. `far` — метка A
    (дальний край), `near` — откуда переводят к ней; `mid` — через сколько секунд 4-секундный перевод уже сдвинул фокус, но ещё далёк
    от конца (на PZ 16-50 первая команда ±1 бывает пустой, а положение приходит через 0,4 с); `pull4` — сколько секунд может длиться
    перевод за 4 с; `rollback` — на сколько единиц фокус по дороге к A может откатиться назад: у объектива ×3,5 первая же команда
    (стартовый k = 1,0) перелетает почти плоскую кривую, и в начале перевода бывает команда назад (−7); два «двигателя» качали бы
    фокус куда сильнее."""

    def __init__(self, title, args, suffix, far, near, mid, pull4, rollback, table):
        self.title, self.args, self.suffix = title, args, suffix
        self.far, self.near, self.mid, self.pull4, self.rollback, self.table = far, near, mid, pull4, rollback, table


# ×3,5, ответ 80 мс, мёртвая зона 2: метка B у 20 (80 единиц до A), перевод за 4 с — от 3,5 до 5 с
FAKE_GLASS = Glass("×3,5, ответ 80 мс, мёртвая зона 2", PHYSICS, "", far=100, near=20, mid=1.6, pull4=(3.5, 5.0), rollback=15,
                   table=False)
# PZ 16-50 по замерам настоящей камеры: ±5 за 0,4 с — не больше 12,5 единицы в секунду, поэтому B у 88 (12 единиц до A), как в замере
PZ_GLASS = Glass("PZ 16-50, --focus-lens pz16-50", ["--focus-lens", "pz16-50"], "-pz", far=100, near=88, mid=2.0, pull4=(3.5, 5.5),
                 rollback=0, table=True)


def go_to(target, glass, tolerance=1.5, tries=24):
    """Ручными шагами к положению (шкала 0…100). Камера с усилением ×3,5 за команду сдвигает на 3,5 единицы, так что считаем по ней.
    У PZ 16-50 ±1 не двигает, ±3 — на 3, а ±7 — на всю шкалу: если до цели далеко (дальше 12 единиц) и ближайший к ней край заметно
    ближе к цели, чем фокус, прыгаем ±7 к этому краю; дальше идём шагами по 3 и 6 (6 — две команды по 3)."""
    for _ in range(tries):
        here = position()
        if here is None or abs(here - target) <= tolerance:
            return here
        gap = target - here
        if glass.table:
            edge = 100 if target > 50 else 0                  # край, ближайший к цели: прыжок туда выгоден, только если он ближе к цели
            if abs(gap) > 12 and abs(edge - target) + 12 < abs(gap):
                act("focusStep", offset=7 if edge == 100 else -7)
            else:
                act("focusStep", offset=max(-6, min(6, int(3 * round(gap / 3)))) or (3 if gap > 0 else -3))
            time.sleep(1.1)
        else:
            act("focusStep", offset=int(round(gap / 3.5)) or (1 if gap > 0 else -1))
            time.sleep(0.9)
    return position()


def rollback(path):
    """Наибольший откат назад от самого большого положения, достигнутого к этому моменту: по дороге вверх (к A) должен быть 0 или
    единицы. Опросы без положения пропускаются."""
    peak, worst = None, 0
    for y in path:
        if y is None:
            continue
        peak = y if peak is None else max(peak, y)
        worst = max(worst, peak - y)
    return worst


def pulling(s):
    """Перевод идёт: есть метка и доля пути, подписи ещё нет."""
    pull = lens(s)["pull"]
    return pull is not None and pull["mark"] in ("A", "B") and pull["progress"] is not None and pull["caption"] is None


def run_pull(mark, seconds, limit=None):
    """«Перевести к метке» и опрос раз в 50 мс, пока не появится подпись. Отдаёт (сколько секунд, подпись, положения по пути, в
    скольких опросах перевод шёл: метка и доля пути без подписи — у «уже у метки» таких нет)."""
    act("focusPullTime", seconds=seconds)
    t0 = time.monotonic()
    act("focusMark", mark=mark, op="go")
    path, ticks = [], 0
    while time.monotonic() - t0 < (limit or seconds + 6):
        s = state()
        path.append(position(s))
        ticks += pulling(s)
        pull = lens(s)["pull"]
        if pull and pull["caption"]:
            return time.monotonic() - t0, pull["caption"], path, ticks
        time.sleep(0.05)
    return None, None, path, ticks


def near_far_commands(stand):
    """Сколько команд Near/Far получила камера, запущенная последней, — и отработанных («фокус ближе на …»), и тех, что она не
    отработала («Near/Far … не двигаю»: не MF, мёртвая зона). Каждая команда — одна строка её журнала."""
    return len(re.findall(r"Near/Far [+-]\d+|фокус (?:ближе|дальше) на", stand.camera.session_log()))


def near_far_sizes(stand, since=0):
    """Размеры (по модулю) команд Near/Far, что получила камера, запущенная последней, после `since` символов её журнала: и
    отработанных («фокус ближе на N»), и тех, что она не отработала («Near/Far ±N — отказ / закрыт / мёртвая зона»)."""
    text = stand.camera.session_log()[since:]
    return [int(done or other) for done, other in re.findall(r"фокус (?:ближе|дальше) на (\d+)|Near/Far [+-](\d+) —", text)]


def stands_still(seconds=1.2, settle=0.6):
    """Фокус не двигается: положение через `settle` секунд после начала и ещё через `seconds` одно и то же."""
    time.sleep(settle)
    first = position()
    time.sleep(seconds)
    return first == position(), (first, position())


def run_marks(out, stand, glass):
    """Метки на камере с физикой объектива: ZV-E10, шаги Near/Far (положение 0…100); `glass` — какой объектив (FAKE_GLASS, PZ_GLASS)."""
    print(f"\n  --- метки A и B, объектив: {glass.title} ---")
    s = stand.swap_camera(*glass.args)
    check(f"камера с объективом ({glass.title}) на связи", s is not None and s["phase"] == "ready", s and s["phase"])
    time.sleep(3)
    act("setPreference", key="autoHide", value="false")
    act("setPreference", key="focusMarksHelpShown", value="true")

    # в AF меток нет; отказы API
    check("в AF метку не запомнить — 409", act("focusMark", mark="A", op="save").get("status") == 409)
    check("в AF перевода нет — 409", act("focusMark", mark="A", op="go").get("status") == 409)
    check("стереть можно всегда", act("focusMark", mark="A", op="clear") == {"ok": True})
    check("метка не A и не B — 409", act("focusMark", mark="C", op="save").get("status") == 409)
    check("действие не из списка — 409", act("focusMark", mark="A", op="jump").get("status") == 409)
    check("время не из списка (3 с) — 409", act("focusPullTime", seconds=3).get("status") == 409)
    check("время 2,5 с — 409", act("focusPullTime", seconds=2.5).get("status") == 409)
    check("метки пусты, время 2 с, перевода нет", lens(state())["marks"] == {"A": None, "B": None, "duration": 2}
          and lens(state())["pull"] is None, lens(state()))

    # MF: A на дальнем краю (100), шагами к ближней метке (20 или 88) — B
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    s = wait(lambda s: lens(s)["focusScale"], 5)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    here = go_to(glass.far, glass)
    check("фокус у дальнего края", here is not None and here >= 98, here)
    check("метка A запомнена", act("focusMark", mark="A", op="save") == {"ok": True})
    a = lens(state())["marks"]["A"]
    check("A = положение фокуса (100), B пуста", a == position() and a >= 98 and lens(state())["marks"]["B"] is None, lens(state())["marks"])
    here = go_to(glass.near, glass)
    act("focusMark", mark="B", op="save")
    marks = lens(state())["marks"]
    b = marks["B"]
    check(f"метка B запомнена у {glass.near}", b is not None and b == position() and abs(b - glass.near) <= 5, marks)
    shot(out, "5-marks-scale" + glass.suffix)

    # перевод к A за 4 с
    log_mark = len(stand.camera.session_log())
    took, caption, path, _ = run_pull("A", 4)
    sizes = near_far_sizes(stand, log_mark)
    print(f"  перевод к A за 4 с ({b} → {a}): {took and round(took, 2)} с, «{caption}», фокус {position()}, команд {len(sizes)}, "
          f"наибольшая {max(sizes, default=0)}")
    low, high = glass.pull4
    check(f"перевод к A за 4 с кончился за {low}–{high} с".replace(".", ","), took is not None and low <= took <= high, took)
    check("подпись «Фокус у метки A»", caption == "Фокус у метки A", caption)
    check("фокус у A: в пределах единицы", position() is not None and abs(position() - a) <= 1, position())
    check(f"по дороге фокус откатывается не больше чем на {glass.rollback} — один «двигатель»", rollback(path) <= glass.rollback,
          (rollback(path), path[::10]))
    if glass.table:
        check("PZ 16-50: камера не получила команд больше ±5", sizes and max(sizes) <= 5, sizes)
    shot(out, "6-marks-pull-done" + glass.suffix)
    time.sleep(3.3)
    check("подпись исчезла через 3 с, lens.pull пуст", lens(state())["pull"] is None, lens(state())["pull"])

    if glass.table:
        # перевод за 2 с (12 единиц) — не дольше 3,2 с; у настоящего объектива такой шёл 3,9 с
        log_mark = len(stand.camera.session_log())
        took2, caption2, path2, _ = run_pull("B", 2)
        sizes2 = near_far_sizes(stand, log_mark)
        print(f"  перевод к B за 2 с ({a} → {b}): {took2 and round(took2, 2)} с, «{caption2}», фокус {position()}, команд {len(sizes2)}")
        check("PZ 16-50: перевод за 2 с кончился не позже чем за 3,2 с", took2 is not None and took2 <= 3.2, took2)
        check("…подписью «Фокус у метки B», фокус у B (±1)", caption2 == "Фокус у метки B" and abs(position() - b) <= 1, (caption2, position(), b))
        check("…и камера не получила команд больше ±5", sizes2 and max(sizes2) <= 5, sizes2)
        time.sleep(3.3)

    # «стоп» посреди перевода
    go_to(glass.near, glass)
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(glass.mid)
    s = state()
    check("посреди перевода: метка A и доля пути между 0 и 1", pulling(s) and 0 < lens(s)["pull"]["progress"] < 1, lens(s)["pull"])
    shot(out, "7-marks-pulling" + glass.suffix)
    act("focusPullStop")
    s = wait(lambda s: lens(s)["pull"] is None, 1)
    check("focusPullStop: lens.pull пуст сразу, подписи нет", lens(s)["pull"] is None, lens(s)["pull"])
    still, seen = stands_still()
    check("и движение прекратилось", still, seen)
    check("остановка без перевода ничего не делает — 200", act("focusPullStop") == {"ok": True})

    # фокус на AF посреди перевода (перевод начинается далеко от A: после «стопа» фокус мог уже быть у неё)
    go_to(glass.near, glass)
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(1.2)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    act("request", setting="focusMode", value=FOCUS_MODE_AFC)
    s = wait(lambda s: lens(s)["pull"] is None, 3)
    check("фокус на AF: перевод отменён, lens.pull пуст, шкалы нет", lens(s)["pull"] is None and not lens(s)["focusScale"], lens(s))
    # в AF камера Near/Far не отрабатывает, так что покой фокуса там ничего не доказывает: считаем команды, которые она получила.
    # Команда, что была в пути в миг переключения, ещё дойдёт — ей полсекунды; потом новых быть не должно
    time.sleep(0.5)
    sent = near_far_commands(stand)
    time.sleep(1.5)
    check("в AF после отмены перевод больше не шлёт камере Near/Far", near_far_commands(stand) == sent, (sent, near_far_commands(stand)))
    check("метки пережили AF", lens(state())["marks"]["A"] is not None and lens(state())["marks"]["B"] is not None, lens(state())["marks"])
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    wait(lambda s: lens(s)["focusScale"], 4)
    check("в MF метки на месте", lens(state())["marks"]["A"] == a and lens(state())["marks"]["B"] == b, lens(state())["marks"])
    # вернулись в MF — здесь отмену и видно: ожившему переводу ничто не мешало бы ехать к A
    still, seen = stands_still()
    check("в MF после AF фокус стоит и Near/Far так и не пришло", still and near_far_commands(stand) == sent,
          (seen, sent, near_far_commands(stand)))

    # ручной шаг посреди перевода
    go_to(glass.near, glass)
    act("focusMark", mark="A", op="go")
    time.sleep(1.0)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    act("focusStep", offset=-1)
    s = wait(lambda s: lens(s)["pull"] is None, 2)
    check("ручной шаг: перевод кончился, подписи нет", lens(s)["pull"] is None, lens(s)["pull"])
    still, seen = stands_still(settle=0.8)
    check("после ручного шага автоматика не двигает фокус", still, seen)

    # камера отвергает Near/Far посреди перевода (управляющий порт: refuse-focus): отказ записан в журнале программы — одна строка за
    # перевод, а не по строке на каждую отвергнутую команду; автомат сам сдаётся, когда фокус не двигается
    go_to(glass.near, glass)
    try:
        for n in (1, 2):
            stand.camera.control("refuse-focus", "on")
            lines_before = len([line for line in state()["journal"] if "камера не приняла Near/Far" in line])
            refused_before = len(re.findall(r"Near/Far [+-]\d+ — отказ", stand.camera.session_log()))
            act("focusPullTime", seconds=4)
            act("focusMark", mark="A", op="go")
            s = wait(lambda s: lens(s)["pull"] is not None and lens(s)["pull"]["caption"] is not None, 8)
            stand.camera.control("refuse-focus", "off")
            refused = len(re.findall(r"Near/Far [+-]\d+ — отказ", stand.camera.session_log())) - refused_before
            lines = [line for line in state()["journal"] if "камера не приняла Near/Far" in line]
            print(f"  Near/Far отвергнут: камера отказала {refused} раз, новых строк в журнале программы {len(lines) - lines_before}")
            check(f"отказ камеры (перевод {n}): камера отвергала Near/Far не раз ({refused})", refused >= 2, refused)
            check(f"отказ камеры (перевод {n}): в журнале программы новая строка одна, с кодом ответа камеры",
                  len(lines) - lines_before == 1 and "0x2019" in lines[-1], lines[-2:])
            check(f"отказ камеры (перевод {n}): перевод кончился сам, фокус не двигался",
                  (lens(s)["pull"] or {}).get("caption") == "Дальше камера не двигает", lens(s)["pull"])
            wait(lambda s: lens(s)["pull"] is None, 5)
            go_to(glass.near, glass)
    finally:
        stand.camera.control("refuse-focus", "off")

    # два «перевести» подряд — двигатель один
    go_to(glass.near, glass)
    act("focusPullTime", seconds=4)
    t0 = time.monotonic()
    act("focusMark", mark="A", op="go")
    time.sleep(0.5)
    t_second = time.monotonic() - t0
    act("focusMark", mark="A", op="go")
    path, captions, done = [], [], None
    while time.monotonic() - t0 < 12:
        s = state()
        path.append(position(s))
        pull = lens(s)["pull"]
        if pull and pull["caption"]:
            captions.append(pull["caption"])
            done = done or time.monotonic() - t0
            if time.monotonic() - t0 - done > 0.5:
                break
        time.sleep(0.05)
    print(f"  два «перевести» подряд: второй на {t_second:.2f} с, готово на {done and round(done, 2)} с, фокус {position()}")
    check(f"«перевести» дважды подряд: фокус откатывается не больше чем на {glass.rollback} (один двигатель)",
          rollback(path) <= glass.rollback, (rollback(path), path[::8]))
    check("…подпись одна", captions and set(captions) == {"Фокус у метки A"}, captions[:3])
    check(f"…второе нажатие начинает перевод заново: ещё {glass.pull4[0]}–{glass.pull4[1]} с от него".replace(".", ","),
          done is not None and glass.pull4[0] <= done - t_second <= glass.pull4[1] + 0.2, done and (round(done, 2), round(t_second, 2)))
    time.sleep(3.3)

    # очередь ручных шагов не доезжает до края, если сразу перевести: перевод вытесняет её. Очередь из 60 единиц — это команды по 7,
    # а ±7 у PZ 16-50 проходит всю шкалу, так что на нём очередь ничего не доказала бы: логика от объектива не зависит
    if glass.table:
        print("  очередь ручных шагов: на PZ 16-50 не проверяется (±7 — вся шкала), см. объектив ×3,5")
    else:
        go_to(60, glass)
        act("focusMark", mark="A", op="save")
        a60 = lens(state())["marks"]["A"]
        go_to(20, glass)
        act("focusPullTime", seconds=2)
        act("focusStep", offset=60)
        time.sleep(0.15)
        act("focusMark", mark="A", op="go")
        t0 = time.monotonic()
        peak, caption = 0, None
        while time.monotonic() - t0 < 8:
            s = state()
            peak = max(peak, position(s) or 0)
            pull = lens(s)["pull"]
            if pull and pull["caption"]:
                caption = pull["caption"]
                break
            time.sleep(0.03)
        time.sleep(1.0)
        print(f"  очередь шагов и перевод к A ({a60}): наибольшее положение {peak}, итог {position()}, «{caption}»")
        check("очередь ручных шагов не уводит фокус за A дальше одной команды (≤ A + 30)", peak <= a60 + 30, (peak, a60))
        check("фокус пришёл к A и стоит", caption == "Фокус у метки A" and abs(position() - a60) <= 4, (position(), a60, caption))
        time.sleep(3.3)

    # другие исходы: уже у метки; время 0 — как можно быстрее; пустая метка и «стереть»
    act("focusMark", mark="B", op="save")
    act("focusPullTime", seconds=8)
    act("focusMark", mark="B", op="go")
    time.sleep(0.4)
    pull = lens(state())["pull"]
    check("уже у метки: сразу подпись, а не 8 с ожидания", pull is not None and pull["caption"] == "Фокус у метки B" and pull["progress"] is None, pull)
    time.sleep(3.3)
    if glass.table:
        # PZ 16-50: 84 → 63 «как можно быстрее» (так в замере у настоящей камеры фокус бросило через всю шкалу и перевод кончился
        # «Не успел за 3 с»): теперь команды −5 одна за другой, у метки (±1) за 1,5–2 с, дальше метки не больше чем на 5 единиц
        go_to(63, glass)
        act("focusMark", mark="A", op="save")
        a_mark = lens(state())["marks"]["A"]
        far = go_to(84, glass)
        check(f"перед нулевым переводом фокус ушёл от A ({far} против {a_mark})", far is not None and far - a_mark >= 18, (far, a_mark))
        log_mark = len(stand.camera.session_log())
        took, caption, path, ticks = run_pull("A", 0, limit=6)
        sizes = near_far_sizes(stand, log_mark)
        shown = [y for y in path if y is not None]
        print(f"  время 0: {far} → {position()} (метка A {a_mark}) за {took and round(took, 2)} с, «{caption}», перевод виден в {ticks} опросах, "
              f"положения {min(shown)}…{max(shown)}, команд {len(sizes)}, наибольшая {max(sizes, default=0)}")
        check("время 0: перевод шёл по-настоящему (метка и доля пути в опросах), а не «уже у метки»", ticks >= 1, ticks)
        check("…и кончился «Фокус у метки A» меньше чем за 3 с (было: «Не успел за 3 с»)",
              caption == "Фокус у метки A" and took is not None and took < 3.0, (took, caption))
        check("…с фокусом у A (±1)", abs(position() - a_mark) <= 1, (position(), a_mark))
        low, high = min(far, a_mark), max(far, a_mark)
        check("…фокус не вышел за дальнюю из меток больше чем на 5 единиц", min(shown) >= low - 5 and max(shown) <= high + 5,
              (min(shown), max(shown), low, high))
        check("…и камера не получила команд больше ±5", sizes and max(sizes) <= 5, sizes)
    else:
        # время 0 — как можно быстрее. Фокус сейчас у A (B сохранена на нём же), и «уже у метки» мерить нечего, — уходим на ~40 единиц
        # и едем к A по-настоящему. Замер на камере с физикой объектива (×3,5, ответ 80 мс): два шага Near/Far, 0,23–0,24 с (шесть
        # переводов туда и обратно); самый короткий перевод по времени — 1 с, так что граница 1 с отличает «как можно быстрее» от него
        far = go_to(20, glass)
        a_mark = lens(state())["marks"]["A"]
        check(f"перед нулевым переводом фокус ушёл от A ({far} против {a_mark})", far is not None and a_mark - far >= 30, (far, a_mark))
        took, caption, path, ticks = run_pull("A", 0, limit=6)
        print(f"  время 0: {far} → {position()} (метка A {a_mark}) за {took and round(took, 2)} с, «{caption}», перевод виден в {ticks} опросах")
        check("время 0: перевод шёл по-настоящему (метка и доля пути в опросах, фокус сдвинулся на ≥ 30), а не «уже у метки»",
              ticks >= 1 and max(path) - far >= 30, (ticks, far, max(path)))
        check("…и кончился «Фокус у метки A» меньше чем за 1 с", caption == "Фокус у метки A" and took is not None and took < 1.0, (took, caption))
        check("…с фокусом у A (±2)", abs(position() - a_mark) <= 2, (position(), a_mark))
    time.sleep(3.3)
    act("focusMark", mark="B", op="clear")
    check("B стёрта, A осталась", lens(state())["marks"]["B"] is None and lens(state())["marks"]["A"] is not None, lens(state())["marks"])
    r = act("focusMark", mark="B", op="go")
    check("перевод к пустой метке — 409", r.get("status") == 409 and "empty" in (r.get("error") or ""), r)
    go_to(glass.near, glass)
    act("focusMark", mark="B", op="save")

    # обрыв связи посреди перевода: камеру выключили; пока её нет, метки не запомнить и к ним не перевести; вернулась — метки на месте
    camera_id = state()["currentCamera"]
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(1.2)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    t0 = time.monotonic()
    stand.camera.stop()
    # пока программа не признала обрыв (фаза ещё ready), перевод может числиться идущим; подписи об успехе у него быть не должно
    captions = []
    s = state()
    while time.monotonic() - t0 < 20 and (s is None or s["phase"] == "ready"):
        pull = s and lens(s)["pull"]
        if pull and pull["caption"]:
            captions.append(pull["caption"])
        time.sleep(0.05)
        s = state()
    noticed = time.monotonic() - t0
    r_save, r_go = act("focusMark", mark="B", op="save"), act("focusMark", mark="A", op="go")
    phase = s["phase"] if s else "нет ответа"
    check(f"камера не готова ({phase}): запомнить метку нельзя — 409 «camera not ready»",
          r_save.get("status") == 409 and "not ready" in (r_save.get("error") or ""), (r_save, phase))
    check("…и перевести к метке нельзя — 409 «camera not ready»", r_go.get("status") == 409 and "not ready" in (r_go.get("error") or ""), r_go)
    # перевод кончается вместе с сеансом: не позже ~2 с после того, как фаза сменилась. Смотрим все эти 2 с, даже когда он уже пуст:
    # подпись об успехе, если бы обрыв кончал перевод «успехом», появилась бы позже и жила 3 с
    t1 = time.monotonic()
    empty_after = None
    while time.monotonic() - t1 < 2.0:
        pull = s and lens(s)["pull"]
        if pull and pull["caption"]:
            captions.append(pull["caption"])
        if s is not None and pull is None and empty_after is None:
            empty_after = time.monotonic() - t1
        time.sleep(0.05)
        s = state()
    print(f"  обрыв признан через {noticed:.1f} с, lens.pull пуст через {empty_after if empty_after is None else round(empty_after, 2)} с "
          f"после этого; фаза {s['phase'] if s else '?'}")
    check("обрыв связи: не позже чем через 2 с после смены фазы lens.pull пуст и остаётся пустым",
          empty_after is not None and s is not None and lens(s)["pull"] is None, (empty_after, s and lens(s)["pull"]))
    check("…и подписи («Фокус у метки …», любой) за эти 2 с не было", not captions, captions)
    stand.camera.start(*glass.args)
    s = wait(lambda s: s["phase"] == "ready" and s["fps"] > 3 and s["currentCamera"], 45)
    time.sleep(3)
    marks = lens(state())["marks"]
    check("та же камера вернулась (тот же id), метки и время перевода на месте", state()["currentCamera"] == camera_id
          and marks["A"] is not None and marks["B"] is not None and marks["duration"] == 4, (state()["currentCamera"], camera_id, marks))

    # смена объектива (0xC21B) посреди перевода: метки стёрты, время осталось, перевод остановлен
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    wait(lambda s: lens(s)["focusScale"], 5)
    act("focusMark", mark="B", op="go")
    time.sleep(1.0)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    check("управляющий порт принял «lens-changed»", stand.camera.control("lens-changed") == "ok")
    s = wait(lambda s: lens(s)["marks"]["A"] is None, 3)
    marks = lens(s)["marks"]
    check("0xC21B: метки стёрты, время перевода осталось", marks["A"] is None and marks["B"] is None and marks["duration"] == 4, marks)
    s = wait(lambda s: lens(s)["pull"] is None, 2)
    check("…и перевод к стёртой метке остановлен", lens(s)["pull"] is None, lens(s)["pull"])
    still, seen = stands_still()
    check("автоматика больше не двигает фокус", still, seen)
    act("focusMark", mark="A", op="save")
    check("после смены объектива метку можно поставить заново", lens(state())["marks"]["A"] is not None, lens(state())["marks"])
    act("focusMark", mark="A", op="clear")

    # как было
    act("focusPullTime", seconds=2)
    act("request", setting="focusMode", value=FOCUS_MODE_AFC)
    s = wait(lambda s: setting(s, "focusMode").get("value") == FOCUS_MODE_AFC, 4)
    check("фокус — как был: AF-C", setting(s, "focusMode").get("value") == FOCUS_MODE_AFC)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/lens")
    args = ap.parse_args()
    try:
        with Stand(args.app, args.out) as stand:
            stand.camera.start()
            stand.app.start()
            s = stand.app.ready()
            if not s:
                return 1
            time.sleep(3)                                   # первые секунды сеанса камера пропускает записи
            autohide = s["prefs"]["autoHide"]
            try:
                run(args.out, stand)
                for glass in (FAKE_GLASS, PZ_GLASS):
                    run_marks(args.out, stand, glass)
                touched = real_cameras_touched()
                check("сейчас нет соединений PTP/IP (порт 15740) с чужими адресами", not touched, touched)
            finally:
                act("zoomStop")
                act("focusPullStop")
                act("setPreference", key="autoHide", value="true" if autohide else "false")
    except KeyboardInterrupt as stop:
        print(f"\nпрервано: {stop}")
        return 2
    print(f"\n{e2e_monitor.passed} из {e2e_monitor.passed + e2e_monitor.failed} проверок прошли; скриншоты — {args.out}")
    return 0 if e2e_monitor.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
