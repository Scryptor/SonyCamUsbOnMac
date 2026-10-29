#!/usr/bin/env python3
"""e2e_newer.py — новая камера Sony в программе для Mac против поддельной камеры `--model newer` (scripts/fake_newer.py): подписи
списков от самой камеры (0x9215), версия кода производителя (0x9216), данные объектива (0x9223), Creative Look вместо Creative
Style, абсолютный фокус (0xE042 пишется, 0xE043 читается), события 0xC20F, 0xC21B, 0xC218 и признак 0xE086.

Сценарии (--scenario, по умолчанию все по порядку):
  newer   новая камера целиком: ни одной подписи «#N» в Fn и «Все настройки», Creative Look «FL» после записи 0x0006, расстояние
          на шкале фокуса и `lens.absolute`, перевод A → B за 2 с (1,8–3 с, итог ±0,5 %), остановка и ручной шаг посреди перевода
          (фокус стоит там, где остановили), события камеры: подписи переименованы, список пропал, объектив сменили, таблицы
          (одна, телеобъектив, две, мусор), 0xE086
  broken  камера, у которой подписи не читаются (`--broken type0|garbage|refuse`): названия — из таблиц Sony, а не «#N»; связь жива
  switch  выбор в Fn переезжает вместе с камерой: новая (Creative Look) → ZV-E10 (Creative Style, новых операций камере не шлют)
          → новая; камеры меняются на том же адресе, поиска в сети нет
  stale   тихое переподключение попало на другую камеру: прежние названия и таблица объектива не остаются
  hang    камера называет 0x9215, 0x9216, 0x9223 и не отвечает на них: один обрыв («камера не отвечает»), а не круг (минута)

Запускает scripts/fake_camera.py на 127.0.0.1:15741 (с управляющим портом: scripts/fake_control.py) и программу с API автоматизации
(адрес камеры подменён), проверяет /state и снимает скриншоты в --out. Поиска камер в сети нет: программа подключается только к
этому адресу. Настройки программы (они общие с установленной) возвращаются — и после ошибки проверки, Ctrl-C и сигнала. Сводка —
общими счётчиками e2e_monitor.

  scripts/e2e_newer.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/newer] [--scenario newer,broken,switch,stale,hang]
"""
import argparse, os, re, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from e2e_monitor import DOMAIN, LENS_PHYSICS, Stand, act, check, real_cameras_touched, setting, shot, state, wait  # noqa: E402
import e2e_monitor  # noqa: E402

MF, AF_C = 1, 0x8004
NEWER = ["--model", "newer", *LENS_PHYSICS]
SCENARIOS = ["newer", "broken", "switch", "stale", "hang"]

# таблица поддельной новой камеры, пересказанная отдельно от scripts/fake_newer.py — сверка расстояний не зависит от кода программы
POSITIONS = [min(65535, 5958 * i) for i in range(12)]
METERS = [0.25, 0.30, 0.40, 0.50, 0.70, 1, 1.5, 2, 3, 5, 10, 1_000_000]
TELE = [(0, 1.2), (20000, 2), (40000, 5), (50000, 20), (65535, 1_000_000)]
FOCUS_MAX = 65535


def meters_at(table, pos):
    """Расстояние по таблице пар (положение, метры): между точками — по прямой."""
    if pos <= table[0][0]:
        return table[0][1]
    for (p0, m0), (p1, m1) in zip(table, table[1:]):
        if pos <= p1:
            return m0 + (m1 - m0) * (pos - p0) / (p1 - p0)
    return table[-1][1]


def label(m):
    """Подпись расстояния, как на шкале: округление до показанной точности, потом выбор формата."""
    if m <= 0:
        return "0 м"
    h = round(m * 100) / 100
    if h < 1:
        return f"{h:.2f}".rstrip("0").rstrip(".").replace(".", ",") + " м"
    t = round(m * 10) / 10
    if t < 10:
        return f"{t:.1f}".rstrip("0").rstrip(".").replace(".", ",") + " м"
    w = round(m)
    return "∞" if w >= 100 else f"{w} м"


def lens(s=None):
    return (s or state())["lens"]


def position(s=None):
    return lens(s)["focalPosition"]


def go_to(target, tolerance=1.5, tries=14):
    """Ручными шагами к положению 0xD24C (0…100): шаг Near/Far на камере с усилением ×3,5 сдвигает на 3,5 единицы."""
    for _ in range(tries):
        here = position()
        if here is None or abs(here - target) <= tolerance:
            return here
        act("focusStep", offset=int(round((target - here) / 3.5)) or (1 if target > here else -1))
        time.sleep(0.9)
    return position()


def no_numbers(s):
    """Подписи «#N» в значениях и вариантах настроек: так программа называла бы Creative Look, не зная его названий."""
    bad = []
    for item in s["settings"]:
        if item["label"].startswith("#"):
            bad.append((item["id"], item["label"]))
        for option in item.get("options", []):
            if option["label"].startswith("#"):
                bad.append((item["id"], option["value"], option["label"]))
    return bad


def count(stand, op):
    """Сколько раз текущая камера получила операцию (по её журналу)."""
    return len(re.findall(rf"\(0x{op}\):", stand.camera.session_log()))


def e043(stand):
    """Положение абсолютного фокуса 0xE043 прямо у камеры (управляющий порт)."""
    reply = stand.camera.control("e043")
    return int(reply.split()[1])


def pull_to(mark, limit=12):
    """Перевести фокус к метке и ждать подпись: отдаёт (сколько секунд, подпись)."""
    t0 = time.monotonic()
    act("focusMark", mark=mark, op="go")
    while time.monotonic() - t0 < limit:
        pull = lens()["pull"]
        if pull and pull["caption"]:
            return time.monotonic() - t0, pull["caption"]
        time.sleep(0.05)
    return None, None


def goal_reached(text):
    """Положения 0xE043, у которых камера сообщила «фокус у цели» (последнее — итог)."""
    return [int(n) for n in re.findall(r"фокус у цели: 0xE043 = (\d+)", text)]


def stands_at(stand, seconds=1.5):
    """Фокус не двигается: 0xE043 у камеры через `seconds` секунд тот же. Отдаёт (стоит ли, положение)."""
    first = e043(stand)
    time.sleep(seconds)
    last = e043(stand)
    return first == last, (first, last)


def reconnected(before, model=None, seconds=45):
    """Ждёт тихого переподключения: счётчик восстановлений вырос, камера готова, кадры идут (и модель, если задана)."""
    return wait(lambda s: s["link"]["reconnects"] > before and s["phase"] == "ready" and s["fps"] > 3
                and (model is None or s["model"] == model), seconds)


# ---------------------------------------------------------------------------------------------------------------------------

def run_newer(out, stand):
    s = wait(lambda s: s["camera"]["vendorCodeVersion"] is not None, 8)
    check("модель — ILCE-7M4", s["model"] == "ILCE-7M4", s["model"])
    cam = s["camera"]
    print("  camera:", cam)
    check("camera.displayStringLists = {0x13: 18, 0x15: 2}", cam["displayStringLists"] == {"0x13": 18, "0x15": 2}, cam)
    check("camera.lensPairs = [12]", cam["lensPairs"] == [12], cam)
    check("camera.vendorCodeVersion = 310", cam["vendorCodeVersion"] == 310, cam)
    asked = (count(stand, "9215"), count(stand, "9216"), count(stand, "9223"))
    check("камера получила по одному 0x9215, 0x9216, 0x9223", asked == (1, 1, 1), asked)
    check("0x9215 — с типом 0, 0x9223 — в метрах", "(0x9215): тип 0x0," in stand.camera.session_log()
          and "(0x9223): метры" in stand.camera.session_log(), stand.camera.session_log()[-400:])
    journal = " | ".join(s["journal"])
    check("журнал: версия кода 3.10", "версия кода производителя (0x9216): 3.10" in journal, journal[-300:])
    check("журнал: подписи 0x13 — 18, 0x15 — 2", "подписи камеры (0x9215): 0x13 — 18 шт., 0x15 — 2 шт." in journal, journal[-400:])
    check("журнал: данные объектива — таблица, 12 пар, шкала 0…65535",
          "данные объектива (0x9223): таблиц 1, пар 12, шкала 0…65535" in journal, journal[-400:])

    # ---- настройки: Creative Look с названиями от камеры, ни одной подписи «#N»
    look = setting(s, "creativeLook")
    names = ["ST", "PT", "NT", "VV", "VV2", "FL", "IN", "SH", "BW", "SE", "FL2", "FL3"] + [f"Custom Look {n}" for n in range(1, 7)]
    check("Creative Look: значение 1 — «ST» (название от камеры, а не «ST(Standard)»)", look["label"] == "ST", look["label"])
    check("Creative Look: 18 вариантов с названиями камеры", [o["label"] for o in look["options"]] == names,
          [o["label"] for o in look["options"]])
    check("Creative Style у новой камеры нет", "value" not in setting(s, "creativeStyle") and setting(s, "creativeStyle")["label"] == "—",
          setting(s, "creativeStyle"))
    check("ни одной подписи «#N» в /state (значения и варианты)", not no_numbers(s), no_numbers(s))
    act("request", setting="creativeLook", value=6)
    s = wait(lambda s: setting(s, "creativeLook")["label"] == "FL" and not setting(s, "creativeLook")["pending"], 4)
    check("запись 0x0006 → Creative Look «FL»", setting(s, "creativeLook")["label"] == "FL", setting(s, "creativeLook"))
    check("и после записи нет «#N»", not no_numbers(s), no_numbers(s))

    # ---- Fn, «Все настройки», пресеты, отладка
    act("setPreference", key="autoHide", value="false")
    act("setPreference", key="focusMarksHelpShown", value="true")
    act("openFn")
    wait(lambda s: s["ui"]["fnOpen"], 3)
    shot(out, "1-fn")
    act("select", setting="creativeLook")
    time.sleep(0.3)
    shot(out, "1b-fn-look-selected")
    check("в Fn выбран Creative Look, а не Creative Style", state()["ui"]["selected"] == "creativeLook", state()["ui"])
    act("closeOverlays")
    act("openAllSettings", group="color")
    wait(lambda s: s["ui"]["allSettingsOpen"], 3)
    shot(out, "2-all-settings")
    act("editRow", setting="creativeLook")
    time.sleep(0.4)
    shot(out, "2b-all-settings-look-open")
    act("closeOverlays")
    act("savePreset", name="Look FL")
    s = state()
    check("пресет записал Creative Look = 6", s["presets"] and s["presets"][-1]["values"].get("creativeLook") == 6,
          s["presets"][-1:] if s["presets"] else None)
    act("request", setting="creativeLook", value=1)
    s = wait(lambda s: s["presetChanges"] == ["Look FL → ST"], 4)
    check("пресет изменён: «Look FL → ST» (названия от камеры)", s["presetChanges"] == ["Look FL → ST"], s["presetChanges"])
    act("request", setting="creativeLook", value=6)
    s = wait(lambda s: not s["presetChanges"] and setting(s, "creativeLook")["label"] == "FL", 4)
    act("openPresets", index=len(s["presets"]) - 1)
    wait(lambda s: s["ui"]["presetsWindow"] is not None, 3)
    time.sleep(0.8)
    shot(out, "3-presets", window="presets")
    act("closePresets")
    act("showDebug", name="camera")
    wait(lambda s: s["ui"]["debug"], 3)
    shot(out, "4-debug-camera")
    act("hideDebug")

    # ---- шкала фокуса: абсолютный фокус, расстояния, метки
    act("setPreference", key="focusMarksHelpShown", value="false")
    act("request", setting="focusMode", value=MF)
    s = wait(lambda s: lens(s)["focusScale"], 5)
    check("MF — шкала фокуса", lens(s)["focusScale"], lens(s))
    time.sleep(1.0)
    check("карточка «Метки фокуса» открылась сама", state()["ui"]["focusMarksHelp"])
    shot(out, "5a-help-card")
    act("setPreference", key="focusMarksHelpShown", value="true")
    act("closeOverlays")
    check("lens.absolute = true (0xE042 открыт, 0xE043 отдаёт положение)", lens(s)["absolute"] is True, lens(s))
    check("lens.distance есть: у дальнего края «∞»", lens(s)["distance"] == "∞", lens(s)["distance"])
    shot(out, "5-scale-infinity")
    go_to(45)
    act("focusMark", mark="A", op="save")
    a = lens()["marks"]["A"]
    expected = label(meters_at(list(zip(POSITIONS, METERS)), a))
    check(f"метки в 0…65535, а не 0…100: A = {a}", a > 100, a)
    check(f"расстояние у A ({a}) = {expected} (независимый расчёт по таблице)", lens()["distance"] == expected, (lens()["distance"], expected))
    go_to(22)
    act("focusMark", mark="B", op="save")
    b = lens()["marks"]["B"]
    expected = label(meters_at(list(zip(POSITIONS, METERS)), b))
    check(f"расстояние у B ({b}) = {expected}", lens()["distance"] == expected, (lens()["distance"], expected))
    shot(out, "6-scale-marks-distances")

    # ---- перевод за 2 с: B → A и A → B; итог — в пределах 0,5 % шкалы
    act("focusPullTime", seconds=2)
    for mark, goal, name in (("A", a, "B → A"), ("B", b, "A → B")):
        log_before = len(stand.camera.session_log())
        took, caption = pull_to(mark)
        reached = goal_reached(stand.camera.session_log()[log_before:])
        final = reached[-1] if reached else None
        print(f"  перевод {name} за 2 с: {took and round(took, 2)} с, итог 0xE043 = {final}, метка {goal}")
        check(f"перевод {name} за 2 с длится 1,8–3 с", took is not None and 1.8 <= took <= 3.0, took)
        check(f"…подпись «Фокус у метки {mark}»", caption == f"Фокус у метки {mark}", caption)
        check(f"…итог 0xE043 в пределах 0,5 % шкалы от метки", final is not None and abs(final - goal) <= 0.005 * FOCUS_MAX + 1,
              (final, goal))
        time.sleep(0.4)
        expected = label(meters_at(list(zip(POSITIONS, METERS)), goal))
        check(f"…расстояние у метки {mark} — {expected}", lens()["distance"] == expected, (lens()["distance"], expected))
        if mark == "A":
            shot(out, "7-scale-at-A")
        time.sleep(3.3)
    check("в журнале программы: 0xC218 — абсолютный фокус дошёл до цели",
          "абсолютный фокус (0xC218): дошёл до цели" in " | ".join(state()["journal"]), state()["journal"][-5:])

    # ---- частота кадров, пока шкала с расстояниями перерисовывается 20 раз в секунду
    time.sleep(2.5)
    idle = [state()["fps"] for _ in range(4) if not time.sleep(0.5)]
    act("focusPullTime", seconds=4)
    act("focusMark", mark="A", op="go")
    time.sleep(0.6)
    moving = []
    for _ in range(7):
        moving.append(state()["fps"])
        time.sleep(0.4)
    print(f"  к/с: шкала стоит {sum(idle) / len(idle):.1f}, фокус едет {sum(moving) / len(moving):.1f}")
    check("частота кадров с движущейся шкалой — не ниже 90 % от стоящей", sum(moving) / len(moving) >= 0.9 * sum(idle) / len(idle),
          (idle, moving))
    wait(lambda s: lens(s)["pull"] and lens(s)["pull"]["caption"], 6)
    wait(lambda s: lens(s)["pull"] is None, 6)
    # перевод шёл 4 с, камера писала цель 40 раз и столько же раз присылала 0xC218: в журнале не больше одной строки о нём
    flood = [line for line in state()["journal"] if "0xC218" in line]
    check("журнал не залит событиями 0xC218 (в последних 30 строках не больше одной)", len(flood) <= 1, (len(flood), flood[:2]))
    check("в журнале по-прежнему строки о подключении или метках (не одни 0xC218)", any("0xC218" not in line for line in state()["journal"]),
          state()["journal"][-3:])

    # ---- посреди перевода: стоп и ручной шаг — абсолютному фокусу велено встать там, где он стоит, и он стоит
    for what in ("focusPullStop", "focusStep"):
        go_to(22)
        wait(lambda s: lens(s)["pull"] is None, 4)
        act("focusPullTime", seconds=4)
        act("focusMark", mark="A", op="go")
        time.sleep(1.6)
        check(f"{what}: перевод идёт", lens()["pull"] and lens()["pull"]["progress"] is not None, lens()["pull"])
        here = e043(stand)
        if what == "focusPullStop":
            act("focusPullStop")
        else:
            act("focusStep", offset=-1)
        s = wait(lambda s: lens(s)["pull"] is None, 2)
        check(f"{what}: перевод кончился, подписи нет", lens(s)["pull"] is None, lens(s)["pull"])
        time.sleep(0.6)
        still, seen = stands_at(stand)
        final = e043(stand)
        # остановка пишет камере положение, прочитанное чуть раньше, и линза успевает проехать ещё немного (нагрузка на Mac его растит):
        # допуск — 10 % шкалы; шаг вниз по шкале — ещё 4 единицы ×655 назад. До метки A остаётся 12–17 %, так что «доехал» бы фокус дальше
        allowed = 0.10 * FOCUS_MAX + (4 * 655 if what == "focusStep" else 0)
        print(f"  {what}: в миг остановки {here}, итог {final}, метка A {a}")
        check(f"{what}: после остановки фокус не движется (0xE043 один и тот же)", still, seen)
        check(f"{what}: итог 0xE043 — там, где остановили (в пределах {int(allowed)}), а не у метки A",
              abs(final - here) <= allowed and abs(final - a) > 0.05 * FOCUS_MAX, (here, final, a))
    time.sleep(3.3)
    act("focusPullTime", seconds=2)

    # ---- события: 0xC20F — список подписей изменился
    before = count(stand, "9215")
    stand.camera.control("list-rename")
    s = wait(lambda s: setting(s, "creativeLook")["label"] == "FLM", 4)
    check("0xC20F (тип 0x13): значение 6 теперь «FLM»", setting(s, "creativeLook")["label"] == "FLM", setting(s, "creativeLook")["label"])
    check("камера получила ещё один 0x9215, с типом 0x13", count(stand, "9215") == before + 1 and "(0x9215): тип 0x13," in stand.camera.session_log(),
          (count(stand, "9215"), before))
    check("варианты тоже от нового списка", "FLM" in [o["label"] for o in setting(s, "creativeLook")["options"]])
    check("camera: списки те же (18 + 2 строки)", s["camera"]["displayStringLists"] == {"0x13": 18, "0x15": 2}, s["camera"])
    stand.camera.control("list-drop")
    s = wait(lambda s: s["camera"]["displayStringLists"] == {"0x15": 2}, 4)
    check("0xC20F: список 0x13 пропал → camera.displayStringLists = {0x15: 2}", s["camera"]["displayStringLists"] == {"0x15": 2}, s["camera"])
    check("названия — из таблиц Sony, не «#N»: «FL(Film)»", setting(s, "creativeLook")["label"] == "FL(Film)", setting(s, "creativeLook")["label"])
    check("и у вариантов нет «#N»", not no_numbers(s), no_numbers(s))
    stand.camera.control("list-restore")
    before = count(stand, "9215")
    stand.camera.control("list-all")
    s = wait(lambda s: setting(s, "creativeLook")["label"] == "FL", 4)
    check("0xC20F без параметра (тип 0): все списки заново, «FL»", setting(s, "creativeLook")["label"] == "FL", setting(s, "creativeLook")["label"])
    check("тип 0 в журнале камеры", count(stand, "9215") == before + 1 and stand.camera.session_log().count("(0x9215): тип 0x0,") == 2,
          (count(stand, "9215"), before))
    check("списки 0x13 и 0x15 на месте", s["camera"]["displayStringLists"] == {"0x13": 18, "0x15": 2}, s["camera"])

    # ---- события: 0xC21B — объектив сменили (метки стираются, таблица читается заново)
    check("метки A и B стоят", lens()["marks"]["A"] is not None and lens()["marks"]["B"] is not None, lens()["marks"])
    before = count(stand, "9223")
    stand.camera.control("lens-tele")
    s = wait(lambda s: s["camera"]["lensPairs"] == [5], 4)
    check("0xC21B: метки стёрты, время перевода осталось", lens(s)["marks"]["A"] is None and lens(s)["marks"]["B"] is None
          and lens(s)["marks"]["duration"] == 2, lens(s)["marks"])
    check("0xC21B: камера получила ещё один 0x9223, camera.lensPairs = [5]", count(stand, "9223") == before + 1
          and s["camera"]["lensPairs"] == [5], (count(stand, "9223"), before, s["camera"]))
    act("focusMark", mark="A", op="save")
    pos = lens()["marks"]["A"]
    expected = label(meters_at(TELE, pos))
    check(f"расстояние по таблице нового объектива: {expected} при {pos}", lens()["distance"] == expected, (lens()["distance"], expected))
    shot(out, "9-scale-tele")
    # положение и расстояние — по 0xE043, а не по 0xD24C: в таблице телеобъектива от 50 000 расстояние уходит в «∞», а 0xD24C (целые
    # проценты) 49 900 и 50 100 не различает — оба 76
    stand.camera.control("e043", 49900)
    s = wait(lambda s: lens(s)["distance"] == label(meters_at(TELE, 49900)), 3)
    check(f"0xE043 = 49900 (0xD24C = {lens(s)['focalPosition']}): {label(meters_at(TELE, 49900))}",
          lens(s)["distance"] == label(meters_at(TELE, 49900)), (lens(s)["distance"], lens(s)["focalPosition"]))
    percent = lens(s)["focalPosition"]
    stand.camera.control("e043", 50100)
    s = wait(lambda s: lens(s)["distance"] == label(meters_at(TELE, 50100)), 3)
    check(f"0xE043 = 50100 (0xD24C = {lens(s)['focalPosition']}, как и прежде): {label(meters_at(TELE, 50100))}",
          lens(s)["distance"] == label(meters_at(TELE, 50100)) and lens(s)["focalPosition"] == percent,
          (lens(s)["distance"], lens(s)["focalPosition"], percent))
    stand.camera.control("e043", 30165)
    wait(lambda s: lens(s)["distance"] == label(meters_at(TELE, 30165)), 3)

    # ---- две таблицы: расстояний нет
    stand.camera.control("lens-two")
    s = wait(lambda s: s["camera"]["lensPairs"] == [12, 2], 4)
    check("две таблицы: camera.lensPairs = [12, 2], lens.distance = null", s["camera"]["lensPairs"] == [12, 2] and lens(s)["distance"] is None,
          (s["camera"], lens(s)["distance"]))
    act("focusMark", mark="B", op="save")
    shot(out, "10-scale-two-tables")

    # ---- мусор вместо таблицы: данных нет, в журнале причина, связь жива
    stand.camera.control("lens-bad")
    s = wait(lambda s: s["camera"]["lensPairs"] is None, 4)
    check("0x9223 прислала мусор: данных объектива нет, расстояния нет", s["camera"]["lensPairs"] is None and lens(s)["distance"] is None,
          s["camera"])
    s = wait(lambda s: any("данные объектива (0x9223): ответ не разобран" in line for line in s["journal"]), 4)
    check("в журнале: «данные объектива (0x9223): ответ не разобран»",
          any("данные объектива (0x9223): ответ не разобран" in line for line in s["journal"]), s["journal"][-4:])
    check("камера на связи и кадры идут", s["phase"] == "ready" and s["fps"] > 3, (s["phase"], s["fps"]))

    # ---- 0xE086: данные объектива пропали и появились
    stand.camera.control("lens-newer")
    s = wait(lambda s: s["camera"]["lensPairs"] == [12], 4)
    check("таблица вернулась: [12]", s["camera"]["lensPairs"] == [12], s["camera"])
    before = count(stand, "9223")
    stand.camera.control("e086", 0)
    s = wait(lambda s: s["camera"]["lensPairs"] is None, 4)
    check("0xE086 = 0: данных нет, расстояния нет", s["camera"]["lensPairs"] is None and lens(s)["distance"] is None,
          (s["camera"], lens(s)["distance"]))
    check("при 0xE086 = 0 камеру о 0x9223 не спрашивали", count(stand, "9223") == before, (count(stand, "9223"), before))
    stand.camera.control("e086", 1)
    s = wait(lambda s: s["camera"]["lensPairs"] == [12], 4)
    check("0xE086 снова 1: 0x9223 спрошена, [12], расстояние снова есть", s["camera"]["lensPairs"] == [12] and count(stand, "9223") == before + 1
          and lens(s)["distance"] is not None, (s["camera"], count(stand, "9223"), before, lens(s)["distance"]))
    # конец: настройки камеры — как были
    act("focusMark", mark="A", op="clear")
    act("focusMark", mark="B", op="clear")
    act("request", setting="creativeLook", value=1)
    act("request", setting="focusMode", value=AF_C)
    wait(lambda s: setting(s, "focusMode").get("value") == AF_C, 4)


def run_broken(out, stand, mode):
    """Камера, у которой подписи 0x9215 не читаются: связь жива, названия — из таблиц Sony."""
    # у каждой своё имя в серийном номере: для программы это другая камера, и прежние названия ей не достаются
    s = stand.swap_camera(*NEWER, "--broken", mode, "--serial", f"FAKEB{mode.upper()}".rjust(32, "0"))
    check(f"[{mode}] камера на связи, кадры идут", s is not None and s["phase"] == "ready" and s["fps"] > 3, s and (s["phase"], s["fps"]))
    s = wait(lambda s: s["camera"]["lensPairs"] is not None, 10)
    journal = " | ".join(s["journal"])
    types = re.findall(r"\(0x9215\): тип (0x[0-9A-F]+)", stand.camera.session_log())
    print(f"  [{mode}] 0x9215 получил типы: {types}; camera: {s['camera']}")
    check(f"[{mode}] 0x9215: сначала тип 0, потом один Creative Look (0x13), и больше не спрашивали", types == ["0x0", "0x13"], types)
    look = setting(s, "creativeLook")
    if mode == "type0":
        check("[type0] подписи от камеры — только Creative Look: {0x13: 18}", s["camera"]["displayStringLists"] == {"0x13": 18}, s["camera"])
        check("[type0] название Creative Look — от камеры («ST»)", look["label"] == "ST", look["label"])
        check("[type0] в журнале: тип 0 не разобран", "подписи камеры (0x9215, тип 0x0): ответ не разобран" in journal, journal[-400:])
    else:
        check(f"[{mode}] подписей от камеры нет: {{}}", s["camera"]["displayStringLists"] == {}, s["camera"])
        check(f"[{mode}] название Creative Look — из таблиц Sony, не «#N»", not look["label"].startswith("#") and look["label"] != "ST", look["label"])
        check(f"[{mode}] в журнале оба отказа (тип 0 и 0x13)",
              "подписи камеры (0x9215, тип 0x0)" in journal and "подписи камеры (0x9215, тип 0x13)" in journal, journal[-500:])
        if mode == "refuse":
            check("[refuse] в журнале: камера ответила 0x2006", "камера ответила 0x2006" in journal, journal[-500:])
    check(f"[{mode}] версия кода и данные объектива на месте", s["camera"]["vendorCodeVersion"] == 310 and s["camera"]["lensPairs"] == [12],
          s["camera"])
    check(f"[{mode}] ни одной подписи «#N» в /state", not no_numbers(s), no_numbers(s))
    if mode == "garbage":
        act("openFn")
        wait(lambda s: s["ui"]["fnOpen"], 3)
        shot(out, "11-broken-garbage-fn")
        act("closeOverlays")


def run_switch(out, stand):
    """Выбор в Fn переезжает вместе с камерой: новая (Creative Look) → ZV-E10 (Creative Style) → новая. Попутно — ZV-E10 как была."""
    s = stand.swap_camera(*NEWER, "--serial", "FAKEN1".rjust(32, "0"))
    s = wait(lambda s: s["camera"]["lensPairs"] is not None, 10)
    act("setPreference", key="autoHide", value="false")
    act("setPreference", key="focusMarksHelpShown", value="true")
    act("select", setting="creativeLook")
    act("openFn")
    s = wait(lambda s: s["ui"]["fnOpen"] and s["ui"]["selected"] == "creativeLook", 3)
    check("новая камера: в Fn выбран Creative Look", s["ui"]["selected"] == "creativeLook", s["ui"])
    shot(out, "S1-fn-newer-look")
    s = stand.swap_camera("--serial", "FAKEZ1".rjust(32, "0"))
    time.sleep(1.0)
    s = state()
    check("на связи ZV-E10 (тихое переподключение к другой камере)", s["model"] == "ZV-E10" and s["phase"] == "ready", (s["model"], s["phase"]))
    check("Fn открыт, выбор переехал: теперь Creative Style", s["ui"]["fnOpen"] and s["ui"]["selected"] == "creativeStyle", s["ui"])
    shot(out, "S2-fn-zv-style")
    # у ZV-E10 новых операций нет: камера не получает ни одной
    time.sleep(2)
    s = state()
    log = stand.camera.session_log()
    check("ZV-E10: camera.* = null, lens.distance = null, lens.absolute = false",
          s["camera"] == {"displayStringLists": None, "lensPairs": None, "vendorCodeVersion": None} and lens(s)["distance"] is None
          and lens(s)["absolute"] is False, (s["camera"], lens(s)["distance"], lens(s)["absolute"]))
    check("ZV-E10: камера не получила ни 0x9215, ни 0x9216, ни 0x9223 и ни разу «её нет в списке DeviceInfo»",
          not re.search(r"9215|9216|9223|её нет в списке DeviceInfo", log), re.findall(r".*(?:9215|9216|9223).*", log)[:3])
    style = setting(s, "creativeStyle")
    check("ZV-E10: Creative Style есть, подпись по-русски, Creative Look нет", style.get("value") is not None and not style["label"].startswith("#")
          and "value" not in setting(s, "creativeLook") and setting(s, "creativeLook")["label"] == "—", (style, setting(s, "creativeLook")))
    check("ZV-E10: ни одной подписи «#N»", not no_numbers(s), no_numbers(s))
    act("editRow", setting="creativeStyle")
    s = stand.swap_camera(*NEWER, "--serial", "FAKEN1".rjust(32, "0"))
    time.sleep(1.0)
    s = state()
    check("снова новая камера", s["model"] == "ILCE-7M4" and s["phase"] == "ready", (s["model"], s["phase"]))
    check("выбор переехал обратно: Creative Look", s["ui"]["selected"] == "creativeLook", s["ui"]["selected"])
    check("и открытая строка (если была) — Creative Look или закрыта", s["ui"].get("editing") in (None, "creativeLook"), s["ui"].get("editing"))
    shot(out, "S3-fn-newer-look-again")
    act("closeOverlays")


def run_stale(out, stand):
    """Тихое переподключение попало на другую камеру, у которой подписи не читаются: прежние названия не остаются."""
    s = stand.swap_camera(*NEWER, "--serial", "FAKEN1".rjust(32, "0"))
    s = wait(lambda s: s["camera"]["displayStringLists"] is not None and s["camera"]["lensPairs"] is not None, 10)
    check("первая камера: названия от неё («ST»)", setting(s, "creativeLook")["label"] == "ST", setting(s, "creativeLook")["label"])
    check("первая камера: списки {0x13: 18, 0x15: 2}, таблица [12]", s["camera"]["displayStringLists"] == {"0x13": 18, "0x15": 2}
          and s["camera"]["lensPairs"] == [12], s["camera"])
    s = stand.swap_camera(*NEWER, "--broken", "garbage", "--serial", "FAKEN2".rjust(32, "0"))
    time.sleep(1.5)
    s = state()
    print("  camera:", s["camera"], "label:", setting(s, "creativeLook")["label"])
    check("другая камера, подписи не читаются: списков от камеры нет ({})", s["camera"]["displayStringLists"] == {}, s["camera"])
    check("названия — из таблиц Sony («ST(Standard)»), а не прежнее «ST»", setting(s, "creativeLook")["label"] == "ST(Standard)",
          setting(s, "creativeLook")["label"])
    check("таблица объектива новой камеры прочитана ([12]), версия кода на месте", s["camera"]["lensPairs"] == [12]
          and s["camera"]["vendorCodeVersion"] == 310, s["camera"])
    # и в другую сторону: у второй камеры нет объектива с электроникой — таблица первой не остаётся
    stand.camera.control("e086", 0)
    s = wait(lambda s: s["camera"]["lensPairs"] is None, 4)
    check("0xE086 = 0: таблицы нет", s["camera"]["lensPairs"] is None, s["camera"])


def run_hang(out, stand):
    """Камера называет 0x9215, 0x9216, 0x9223 и не отвечает на них: один обрыв («камера не отвечает»), а не круг."""
    stand.swap_camera(*NEWER, "--broken", "hang", "--serial", "FAKEH1".rjust(32, "0"))
    before = state()["link"]["reconnects"]
    t0 = time.monotonic()
    print("  жду минуту, смотрю на переподключения")
    seen = []
    while time.monotonic() - t0 < 60:
        s = state()
        seen.append((round(time.monotonic() - t0), s["link"]["reconnects"] - before, s["phase"], s["fps"]))
        time.sleep(5)
    print("  (с, переподключений, фаза, к/с):", seen[::2])
    s = state()
    n = s["link"]["reconnects"] - before
    asked = {op: count(stand, op) for op in ("9215", "9216", "9223")}
    print("  камера получила:", asked, "; переподключений:", n)
    check("за минуту не больше одного обрыва", n <= 1, n)
    check("камера на связи, кадры идут", s["phase"] == "ready" and s["fps"] > 3, (s["phase"], s["fps"]))
    check("0x9215 пришёл один раз (после обрыва камере его не шлют)", asked["9215"] == 1, asked)
    journal = " | ".join(s["journal"])
    check("в журнале одна строка «камера не ответила на операцию 0x…»",
          sum("камера не ответила на операцию" in line for line in s["journal"]) == 1, journal[-600:])
    check("подписей, таблицы и версии нет", s["camera"]["displayStringLists"] in ({}, None) and s["camera"]["lensPairs"] is None
          and s["camera"]["vendorCodeVersion"] is None, s["camera"])
    check("названия — из таблиц Sony, не «#N»", not no_numbers(s), no_numbers(s))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/newer")
    ap.add_argument("--scenario", default=",".join(SCENARIOS), help="какие сценарии и в каком порядке: " + ", ".join(SCENARIOS))
    args = ap.parse_args()
    chosen = [name for name in args.scenario.split(",") if name]
    unknown = [name for name in chosen if name not in SCENARIOS]
    if unknown:
        ap.error(f"нет таких сценариев: {', '.join(unknown)}")
    if not chosen:
        ap.error("не выбран ни один сценарий: прогон без проверок не может считаться пройденным")
    try:
        with Stand(args.app, args.out) as stand:
            # окно «Пресеты» повыше, чтобы на снимке были все группы (ключа до запуска не было — настройки его уберут)
            subprocess.run(["defaults", "write", DOMAIN, "NSWindow Frame presets", "-string", "300 40 920 1060 0 0 2056 1290 "])
            stand.camera.start(*NEWER)
            stand.app.start()
            s = stand.app.ready()
            if not s:
                return 1
            autohide = s["prefs"]["autoHide"]
            try:
                for name in chosen:
                    print(f"\n== {name} ==")
                    if name == "newer":
                        run_newer(args.out, stand)
                    elif name == "broken":
                        for mode in ("type0", "garbage", "refuse"):
                            run_broken(args.out, stand, mode)
                    elif name == "switch":
                        run_switch(args.out, stand)
                    elif name == "stale":
                        run_stale(args.out, stand)
                    elif name == "hang":
                        run_hang(args.out, stand)
                touched = real_cameras_touched()
                check("сейчас нет соединений PTP/IP (порт 15740) с чужими адресами", not touched, touched)
            finally:
                act("focusPullStop")
                act("setPreference", key="autoHide", value="true" if autohide else "false")
    except KeyboardInterrupt as stop:
        print(f"\nпрервано: {stop}")
        return 2
    print(f"\n{e2e_monitor.passed} из {e2e_monitor.passed + e2e_monitor.failed} проверок прошли; скриншоты — {args.out}")
    return 0 if e2e_monitor.failed == 0 and e2e_monitor.passed > 0 else 1


if __name__ == "__main__":
    sys.exit(main())
