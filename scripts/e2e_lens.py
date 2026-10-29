#!/usr/bin/env python3
"""e2e_lens.py — фокус и зум с экрана в программе для Mac против поддельной камеры: фокус касанием (касание экрана
камеры в зоне «Широкая», точка AF в зоне «Точка»), рамки фокуса камеры, моторный зум, шкала фокуса в MF. И метки фокуса A и B с
переводом за выбранное время — на камере с физикой объектива (--focus-gain 3.5 --focus-step-ms 80 --focus-deadband 2): перевод
к метке за 4 с, остановка, отмены (AF, ручной шаг, второй «перевести», обрыв связи, смена объектива), отказы API.

Запускает scripts/fake_camera.py на 127.0.0.1:15741 (с управляющим портом: смена объектива — событие 0xC21B) и программу с API
автоматизации (адрес камеры подменён), проверяет /state и снимает скриншоты в --out. Зону и режим фокуса поддельной камеры
возвращает, настройки программы (они общие с установленной) — тоже, и после ошибки, Ctrl-C и сигнала.

  scripts/e2e_lens.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/lens]
"""
import argparse, os, sys, time

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
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    s = wait(lambda s: lens(s)["focusScale"], 4)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    # карточка «Метки фокуса» сама открывается, когда шкала с метками появилась впервые за запуск (пока не отмечено «Больше не
    # показывать»)
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


def go_to(target, tolerance=1.5, tries=14):
    """Ручными шагами к положению (шкала 0…100): камера с усилением ×3,5 за раз сдвигает на 3,5 единицы, так что считаем по ней."""
    for _ in range(tries):
        here = position()
        if here is None or abs(here - target) <= tolerance:
            return here
        act("focusStep", offset=int(round((target - here) / 3.5)) or (1 if target > here else -1))
        time.sleep(0.9)
    return position()


def pulling(s):
    """Перевод идёт: есть метка и доля пути, подписи ещё нет."""
    pull = lens(s)["pull"]
    return pull is not None and pull["mark"] in ("A", "B") and pull["progress"] is not None and pull["caption"] is None


def run_pull(mark, seconds, limit=None):
    """«Перевести к метке» и опрос раз в 50 мс, пока не появится подпись. Отдаёт (сколько секунд, подпись, положения по пути)."""
    act("focusPullTime", seconds=seconds)
    t0 = time.monotonic()
    act("focusMark", mark=mark, op="go")
    path = []
    while time.monotonic() - t0 < (limit or seconds + 6):
        s = state()
        path.append(position(s))
        pull = lens(s)["pull"]
        if pull and pull["caption"]:
            return time.monotonic() - t0, pull["caption"], path
        time.sleep(0.05)
    return None, None, path


def stands_still(seconds=1.2, settle=0.6):
    """Фокус не двигается: положение через `settle` секунд после начала и ещё через `seconds` одно и то же."""
    time.sleep(settle)
    first = position()
    time.sleep(seconds)
    return first == position(), (first, position())


def run_marks(out, stand):
    """Метки на камере с физикой объектива: ZV-E10, шаги Near/Far (положение 0…100)."""
    s = stand.swap_camera(*PHYSICS)
    check("камера с объективом (×3,5, ответ 80 мс, мёртвая зона 2) на связи", s is not None and s["phase"] == "ready", s and s["phase"])
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

    # MF: A на 100, шагами к 20 — B
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    s = wait(lambda s: lens(s)["focusScale"], 5)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    here = go_to(100)
    check("фокус у дальнего края", here is not None and here >= 98, here)
    check("метка A запомнена", act("focusMark", mark="A", op="save") == {"ok": True})
    a = lens(state())["marks"]["A"]
    check("A = положение фокуса (100), B пуста", a == position() and a >= 98 and lens(state())["marks"]["B"] is None, lens(state())["marks"])
    here = go_to(20)
    act("focusMark", mark="B", op="save")
    marks = lens(state())["marks"]
    b = marks["B"]
    check("метка B запомнена у 20", b is not None and b == position() and abs(b - 20) <= 5, marks)
    shot(out, "5-marks-scale")

    # перевод к A за 4 с
    took, caption, path = run_pull("A", 4)
    print(f"  перевод к A за 4 с: {took and round(took, 2)} с, «{caption}», фокус {position()}")
    check("перевод к A за 4 с кончился за 3,5–5 с", took is not None and 3.5 <= took <= 5.0, took)
    check("подпись «Фокус у метки A»", caption == "Фокус у метки A", caption)
    check("фокус у A: 99–100", position() is not None and 99 <= position() <= 100, position())
    check("по дороге фокус только растёт — один «двигатель»", all(y >= x for x, y in zip(path, path[1:])), path[::10])
    shot(out, "6-marks-pull-done")
    time.sleep(3.3)
    check("подпись исчезла через 3 с, lens.pull пуст", lens(state())["pull"] is None, lens(state())["pull"])

    # «стоп» посреди перевода
    go_to(20)
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(1.6)
    s = state()
    check("посреди перевода: метка A и доля пути между 0 и 1", pulling(s) and 0 < lens(s)["pull"]["progress"] < 1, lens(s)["pull"])
    shot(out, "7-marks-pulling")
    act("focusPullStop")
    s = wait(lambda s: lens(s)["pull"] is None, 1)
    check("focusPullStop: lens.pull пуст сразу, подписи нет", lens(s)["pull"] is None, lens(s)["pull"])
    still, seen = stands_still()
    check("и движение прекратилось", still, seen)
    check("остановка без перевода ничего не делает — 200", act("focusPullStop") == {"ok": True})

    # фокус на AF посреди перевода
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(1.2)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    act("request", setting="focusMode", value=FOCUS_MODE_AFC)
    s = wait(lambda s: lens(s)["pull"] is None, 3)
    check("фокус на AF: перевод отменён, lens.pull пуст, шкалы нет", lens(s)["pull"] is None and not lens(s)["focusScale"], lens(s))
    still, seen = stands_still()
    check("в AF после отмены движения нет", still, seen)
    check("метки пережили AF", lens(state())["marks"]["A"] is not None and lens(state())["marks"]["B"] is not None, lens(state())["marks"])
    act("request", setting="focusMode", value=FOCUS_MODE_MF)
    wait(lambda s: lens(s)["focusScale"], 4)
    check("в MF метки на месте", lens(state())["marks"]["A"] == a and lens(state())["marks"]["B"] == b, lens(state())["marks"])

    # ручной шаг посреди перевода
    act("focusMark", mark="A", op="go")
    time.sleep(1.0)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    act("focusStep", offset=-1)
    s = wait(lambda s: lens(s)["pull"] is None, 2)
    check("ручной шаг: перевод кончился, подписи нет", lens(s)["pull"] is None, lens(s)["pull"])
    still, seen = stands_still(settle=0.8)
    check("после ручного шага автоматика не двигает фокус", still, seen)

    # два «перевести» подряд — двигатель один
    go_to(20)
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
    check("«перевести» дважды подряд: фокус растёт без откатов (один двигатель)", all(y >= x for x, y in zip(path, path[1:])), path[::8])
    check("…подпись одна", captions and set(captions) == {"Фокус у метки A"}, captions[:3])
    check("…второе нажатие начинает перевод заново: ещё 3,5–5 с от него", done is not None and 3.5 <= done - t_second <= 5.2,
          done and (round(done, 2), round(t_second, 2)))
    time.sleep(3.3)

    # очередь ручных шагов не доезжает до края, если сразу перевести: перевод вытесняет её
    go_to(60)
    act("focusMark", mark="A", op="save")
    a60 = lens(state())["marks"]["A"]
    go_to(20)
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
    took, caption, _ = run_pull("A", 0, limit=6)
    check("время 0: «Фокус у метки A» меньше чем за 1,5 с", caption == "Фокус у метки A" and took is not None and took < 1.5, (took, caption))
    check("…и фокус у A (±2)", abs(position() - lens(state())["marks"]["A"]) <= 2, (position(), lens(state())["marks"]))
    time.sleep(3.3)
    act("focusMark", mark="B", op="clear")
    check("B стёрта, A осталась", lens(state())["marks"]["B"] is None and lens(state())["marks"]["A"] is not None, lens(state())["marks"])
    r = act("focusMark", mark="B", op="go")
    check("перевод к пустой метке — 409", r.get("status") == 409 and "empty" in (r.get("error") or ""), r)
    go_to(20)
    act("focusMark", mark="B", op="save")

    # обрыв связи посреди перевода: камеру выключили; пока её нет, метки не запомнить и к ним не перевести; вернулась — метки на месте
    camera_id = state()["currentCamera"]
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(1.2)
    check("перевод идёт", pulling(state()), lens(state())["pull"])
    t0 = time.monotonic()
    stand.camera.stop()
    s = wait(lambda s: s["phase"] != "ready", 20)
    r_save, r_go = act("focusMark", mark="B", op="save"), act("focusMark", mark="A", op="go")
    check(f"камера не готова ({s['phase']}): запомнить метку нельзя — 409 «camera not ready»",
          r_save.get("status") == 409 and "not ready" in (r_save.get("error") or ""), (r_save, s["phase"]))
    check("…и перевести к метке нельзя — 409 «camera not ready»", r_go.get("status") == 409 and "not ready" in (r_go.get("error") or ""), r_go)
    s = wait(lambda s: lens(s)["pull"] is None, 20)
    print(f"  lens.pull пуст через {time.monotonic() - t0:.1f} с после обрыва; фаза {s['phase']}")
    check("обрыв связи: перевод кончился, подписи об успехе нет", lens(s)["pull"] is None, lens(s)["pull"])
    stand.camera.start(*PHYSICS)
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
                run_marks(args.out, stand)
                touched = real_cameras_touched()
                check("к настоящей камере (PTP/IP, порт 15740) не подключались", not touched, touched)
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
