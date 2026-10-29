#!/usr/bin/env python3
"""check_log_preview.py — уровни превью камеры в S-Log и HLG: что приходит в кадре live view, когда на камере лог.

Встроенные LUT программы считают по коду камеры (`код/1023`): чёрное S-Log3 — код 95, 18 % серого — код 420. Какое значение
канала превью (0…255) за кодом стоит и не приводит ли камера превью к Rec.709 сама, скрипт смотрит на камере, подключённой к
программе для Mac с API автоматизации (`--automation` или `ZV_AUTOMATION=1`, см. docs/automation-api.md):

1. запоминает режим съёмки, профиль изображения `0xD23F` и все настройки камеры (`GET /state`);
2. в видеорежиме, потом в фоторежиме ставит профиль «выкл» (для сравнения), PP8, PP7 и PP10 — `request
   {setting: "pictureProfile"}`; на каждом ждёт 2 с, берёт 20 раз `GET /frame` (JPEG камеры как есть, без LUT и зеркала)
   и считает яркость точек с весами Rec.709 — доли 0,1 %, 50 % и 99,9 % по всем 20 кадрам вместе;
3. возвращает профиль и режим, как были, а с ними — всё, что камера при этом поменяла сама (например, ISO у лога), и
   проверяет чтением, что вернулось;
4. печатает таблицу: доля 0,1 % — нижняя граница. Ожидание в ней — чёрное при растяжении, которое показала ZV-E10: превью
   кладёт коды камеры 64…1019 на 0…255 (docs/protocol.md, «Превью в S-Log и HLG»), так что S-Log3 ≈ 8, S-Log2 ≈ 7, HLG ≈ 0.
   Будь значение превью кодом/1023, было бы 24, 22 и 16; а если камера сама приводит превью к Rec.709, у лога — около 0
   при такой же контрастной картинке, как без профиля.

Гамму профиля ZV-E10 по PTP не отдаёт: считается, что слоты заводские (PP7 — S-Log2, PP8 — S-Log3 · S-Gamut3.Cine, PP9 —
S-Log3, PP10 — HLG). Гамма-помощь камеры (Gamma Disp. Assist) по PTP тоже не видна и не меняется: проверка идёт в том её
состоянии, в каком меню камеры. На карту камера ничего не пишет: меняются только профиль и режим съёмки.

Сцена — то, что камера видит. Программа должна быть подключена к настоящей камере: на виртуальной (адрес 127.0.0.1)
скрипт не начинает без `--fake` (им проверяют сам скрипт).

  scripts/check_log_preview.py [--out папка для кадров] [--json файл с цифрами] [--fake]
"""
import argparse, hashlib, io, json, os, signal, sys, time, urllib.error, urllib.request

try:
    import numpy as np
    from PIL import Image
except ImportError:
    sys.exit("нужны numpy и Pillow: python3 -m pip install numpy pillow")

INFO = os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")
PROFILES = (0, 8, 7, 10)
NAMES = {0: "выкл", 7: "PP7 · S-Log2", 8: "PP8 · S-Log3 Cine", 9: "PP9 · S-Log3", 10: "PP10 · HLG"}
# нижняя граница — чёрное при растяжении превью ZV-E10 (коды 64…1019 → 0…255): S-Log3 — код 95, S-Log2 — код 90,3, HLG — код 64
PREVIEW_BLACK, PREVIEW_SPAN = 64, 955
EXPECTED_LOW = {pp: (code - PREVIEW_BLACK) / PREVIEW_SPAN * 255 for pp, code in ((8, 95), (7, 90.28), (10, 64))}
FRAMES, SETTLE, FRAME_INTERVAL = 20, 2.0, 0.1
PERCENTS = (0.1, 1, 50, 99, 99.9)
# только для чтения или меняются сами — их не возвращают
READ_ONLY = {"battery", "cardRemaining", "overheat"}
GAMMA_ASSIST = "гамма-помощь (Gamma Disp. Assist) — в том состоянии, в каком меню камеры (по PTP не видно)"
SLOTS = "слоты профилей считаются заводскими: PP7 — S-Log2, PP8 — S-Log3 · S-Gamut3.Cine, PP9 — S-Log3, PP10 — HLG"


class Stop(Exception):
    """Проверку дальше не вести: камера не ответила или не приняла то, без чего нельзя."""


def call(path, body=None, raw=False, timeout=10):
    info = json.load(open(INFO))
    req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}", method="POST" if body is not None else "GET",
                                 data=json.dumps(body).encode() if body is not None else None)
    req.add_header("X-ZV-Token", info["token"])
    try:
        data = urllib.request.urlopen(req, timeout=timeout).read()
    except urllib.error.HTTPError as e:
        return {"status": e.code, "error": json.loads(e.read() or b"{}").get("error")}
    return data if raw else json.loads(data or b"null")


def act(action, **params):
    return call("/action", dict(action=action, **params))


def state():
    return call("/state")


def setting(s, name):
    return next(item for item in s["settings"] if item["id"] == name)


def item_value(item):
    # у параметров записи сравнивают код режима (формат × 256 + параметры) — его и ставит request
    return item.get("mode", item.get("value")) if item["id"] == "movieRecordingSetting" else item.get("value")


def value(s, name):
    return item_value(setting(s, name))


def settled(s, name, want):
    item = setting(s, name)
    return value(s, name) == want and not item["pending"]


def wait(predicate, seconds):
    """Ждёт, пока /state не станет таким; отдаёт (последний /state, дождались ли)."""
    deadline = time.monotonic() + seconds
    s = state()
    while not predicate(s) and time.monotonic() < deadline:
        time.sleep(0.15)
        s = state()
    return s, predicate(s)


def frames_flow(s):
    return s.get("phase") == "ready" and s.get("fps", 0) > 3


def current_camera(s):
    return next((c for c in s.get("cameras", []) if c.get("state") == "current"), None)


def is_fake(s):
    """Виртуальная камера: по Wi-Fi на loopback (scripts/fake_camera.py)."""
    camera = current_camera(s) or {}
    host = str(camera.get("host") or "")
    return host.startswith("127.") or host in ("localhost", "::1")


def frame():
    """Последний кадр превью (JPEG камеры как есть); пока кадров нет (409) — ждём до 8 с."""
    deadline = time.monotonic() + 8
    while True:
        data = call("/frame", raw=True)
        if isinstance(data, bytes):
            return data
        if time.monotonic() > deadline:
            raise Stop(f"кадров нет: {data}")
        time.sleep(0.2)


def luma(jpeg):
    """Яркость точек кадра, веса Rec.709 (как у гистограммы и пипетки программы); и размер кадра."""
    image = Image.open(io.BytesIO(jpeg)).convert("RGB")
    rgb = np.asarray(image, dtype=np.float32)
    return rgb[..., 0] * 0.2126 + rgb[..., 1] * 0.7152 + rgb[..., 2] * 0.0722, image.size


def measure(save=None):
    """20 кадров через 0,1 с: доли яркости по всем точкам всех кадров, разброс нижней границы по кадрам, сколько кадров
    разных, размер кадра. save — путь, куда положить первый кадр (JPEG как есть)."""
    values, lows, digests, sizes = [], [], set(), set()
    for i in range(FRAMES):
        jpeg = frame()
        if i == 0 and save:
            with open(save, "wb") as f:
                f.write(jpeg)
        digests.add(hashlib.sha1(jpeg).hexdigest())
        y, size = luma(jpeg)
        sizes.add(size)
        values.append(y.ravel())
        lows.append(float(np.percentile(y, 0.1)))
        time.sleep(FRAME_INTERVAL)
    every = np.concatenate(values)
    p = np.percentile(every, PERCENTS)
    return {
        "percentiles": {str(k): round(float(v), 1) for k, v in zip(PERCENTS, p)},
        "mean": round(float(every.mean()), 1),
        "lowPerFrame": [round(min(lows), 1), round(max(lows), 1)],
        "distinctFrames": len(digests),
        "size": "×".join(map(str, sorted(sizes)[0])) if len(sizes) == 1 else " / ".join("×".join(map(str, z)) for z in sorted(sizes)),
    }


def exposure(s):
    return {name: setting(s, name)["label"] for name in ("exposureProgram", "iso", "shutter", "aperture", "exposureComp")}


def set_profile(pp):
    """Профиль изображения; камера должна подтвердить его за 12 с. Отдаёт (поставлен ли, почему нет): 409 от программы или
    камера оставила прежний."""
    r = act("request", setting="pictureProfile", value=pp)
    if isinstance(r, dict) and r.get("status"):
        return False, f"409: {r.get('error')}"
    s, ok = wait(lambda s: settled(s, "pictureProfile", pp) and frames_flow(s), 12)
    return ok, None if ok else f"камера оставила {setting(s, 'pictureProfile')['label']}"


def switch_mode(mode):
    """Фото или видео с той же буквой режима (`switchMode`); ждём, пока режим подтверждён и кадры снова идут."""
    s = state()
    if s["shootingMode"] == mode:
        return s
    r = act("switchMode", mode=mode)
    if isinstance(r, dict) and r.get("status"):
        raise Stop(f"режим {mode} не поставить: {r.get('error')}")
    s, ok = wait(lambda s: s["shootingMode"] == mode and not setting(s, "exposureProgram")["pending"] and frames_flow(s), 20)
    if not ok:
        raise Stop(f"камера не перешла в режим {mode}: {setting(s, 'exposureProgram')['label']}")
    time.sleep(1.0)
    return state()


def put_back(snapshot):
    """В том же режиме съёмки — как в снимке /state: профиль изображения, потом всё, что камера поменяла сама и что в снимке
    было открыто на запись (ISO у лога и т.п.). Режим съёмки (0x500E) не трогает. Закрытое на запись в снимке — авто
    (выдержка и диафрагма в P, батарея) — не возвращается и не сравнивается."""
    want = {item["id"]: item_value(item) for item in snapshot["settings"]
            if item["writable"] and item["id"] not in READ_ONLY and item["id"] != "exposureProgram" and item_value(item) is not None}
    s = state()
    profile = want.get("pictureProfile")
    if profile is not None and value(s, "pictureProfile") != profile:
        act("request", setting="pictureProfile", value=profile)
        s, _ = wait(lambda s: settled(s, "pictureProfile", profile), 12)
    for _ in range(2):
        time.sleep(1.0)
        s = state()
        for name, before in want.items():
            if value(s, name) != before and setting(s, name)["writable"]:
                act("request", setting=name, value=before)
                s, _ = wait(lambda s: settled(s, name, before), 15)
    s = state()
    return [(name, before, value(s, name)) for name, before in want.items() if value(s, name) != before]


def restore(baseline):
    """Как было: режим съёмки (код 0x500E), потом профиль и остальное (`put_back`). Отдаёт, что вернуть не удалось:
    (настройка, было, стало)."""
    program = value(baseline, "exposureProgram")
    s = state()
    if value(s, "exposureProgram") != program:
        act("switchMode", mode=baseline["shootingMode"])
        s, ok = wait(lambda s: settled(s, "exposureProgram", program) and frames_flow(s), 20)
        if not ok:
            # буква режима поменялась — ставим сам код
            act("request", setting="exposureProgram", value=program)
            s, _ = wait(lambda s: settled(s, "exposureProgram", program) and frames_flow(s), 20)
    left = put_back(baseline)
    if value(state(), "exposureProgram") != program:
        left.insert(0, ("exposureProgram", program, value(state(), "exposureProgram")))
    return left


def table(rows):
    head = ("Режим", "Профиль", "Кадр", "ISO", "Выдержка", "Диафр.", "p0,1", "p1", "p50", "p99", "p99,9", "p0,1 по кадрам",
            "Ожидание p0,1")
    lines = [head]
    for r in rows:
        if r.get("skipped"):
            lines.append((r["mode"], NAMES[r["profile"]], "—", "", "", "", "", "", "", "", "", "", r["skipped"]))
            continue
        p = r["percentiles"]
        expected = EXPECTED_LOW.get(r["profile"])
        lines.append((r["mode"], NAMES[r["profile"]], r["size"], r["exposure"]["iso"], r["exposure"]["shutter"], r["exposure"]["aperture"],
                      f"{p['0.1']:.1f}", f"{p['1']:.1f}", f"{p['50']:.1f}", f"{p['99']:.1f}", f"{p['99.9']:.1f}",
                      f"{r['lowPerFrame'][0]:.1f}…{r['lowPerFrame'][1]:.1f}", f"≈ {expected:.0f}" if expected is not None else "—"))
    widths = [max(len(str(line[i])) for line in lines) for i in range(len(head))]
    return "\n".join("  ".join(str(c).ljust(w) for c, w in zip(line, widths)).rstrip() for line in lines)


def terminate_on_signals():
    """SIGTERM, SIGHUP и SIGALRM обрывают проверку исключением — тогда камера всё равно возвращается, как была."""
    def stop(signum, frame):
        for name in ("SIGTERM", "SIGHUP", "SIGALRM"):
            signal.signal(getattr(signal, name), signal.SIG_IGN)
        raise KeyboardInterrupt(f"сигнал {signum}")
    for name in ("SIGTERM", "SIGHUP", "SIGALRM"):
        signal.signal(getattr(signal, name), stop)


def main():
    ap = argparse.ArgumentParser(description="Уровни превью камеры в S-Log и HLG (программа для Mac с API автоматизации).")
    ap.add_argument("--out", help="папка: первый кадр каждого шага (JPEG камеры как есть)")
    ap.add_argument("--json", help="файл: все цифры, настройки до и после")
    ap.add_argument("--fake", action="store_true", help="разрешить виртуальную камеру (проверка самого скрипта)")
    args = ap.parse_args()
    if not os.path.exists(INFO):
        sys.exit("программа с API автоматизации не запущена: нет " + INFO)
    s = state()
    if s.get("phase") != "ready":
        sys.exit(f"камера не на связи: phase = {s.get('phase')}")
    if is_fake(s) and not args.fake:
        sys.exit("на связи виртуальная камера (127.0.0.1) — проверка нужна на настоящей; для проверки самого скрипта — --fake")
    if s["recording"] or s["capturing"]:
        sys.exit("камера пишет ролик или снимает — проверку не начинаю")
    if args.out:
        os.makedirs(args.out, exist_ok=True)
    camera = current_camera(s) or {}
    print(f"камера: {s.get('model')} {s.get('firmware') or ''}, {s.get('transport')} {camera.get('host') or ''}".rstrip())
    print(SLOTS)
    print(GAMMA_ASSIST)
    baseline = s
    print(f"было: режим {s['shootingMode']} ({setting(s, 'exposureProgram')['label']}), профиль {setting(s, 'pictureProfile')['label']}, "
          f"батарея {setting(s, 'battery')['label']}")
    rows, failed = [], None
    terminate_on_signals()
    try:
        for mode in ("video", "photo"):
            # профиль и настройки, с которыми камера пришла в этот режим, возвращаются до перехода в другой: так ничего не
            # останется, даже если у камеры профиль (или ISO) свой у фото и у видео
            entered = switch_mode(mode)
            try:
                for pp in PROFILES:
                    row = {"mode": "видео" if mode == "video" else "фото", "profile": pp}
                    ok, why = set_profile(pp)
                    if not ok:
                        row["skipped"] = f"профиль не поставлен: {why}"
                        rows.append(row)
                        print(f"  {row['mode']}, {NAMES[pp]}: {row['skipped']}", flush=True)
                        continue
                    time.sleep(SETTLE)
                    s = state()
                    row["exposure"] = exposure(s)
                    row["whiteLevel"] = (s.get("picture") or {}).get("whiteLevel")
                    save = os.path.join(args.out, f"{mode}-pp{pp}.jpg") if args.out else None
                    row.update(measure(save))
                    rows.append(row)
                    p = row["percentiles"]
                    print(f"  {row['mode']}, {NAMES[pp]}: p0,1 {p['0.1']:.1f}  p50 {p['50']:.1f}  p99,9 {p['99.9']:.1f}  "
                          f"({row['size']}, ISO {row['exposure']['iso']}, разных кадров {row['distinctFrames']})", flush=True)
            finally:
                left = put_back(entered)
                if left:
                    print(f"  в режиме {mode} не вернулось: {left}", flush=True)
    except (Stop, KeyboardInterrupt) as error:
        failed = str(error) or type(error).__name__
        print(f"\nпроверка прервана: {failed}", flush=True)
    finally:
        for name in ("SIGTERM", "SIGHUP", "SIGALRM", "SIGINT"):
            signal.signal(getattr(signal, name), signal.SIG_IGN)
        left = restore(baseline)
    after = state()
    print()
    print(table(rows))
    print()
    print(GAMMA_ASSIST)
    print(SLOTS)
    print("ожидание p0,1 — чёрное при растяжении превью 64…1019 → 0…255 (S-Log3 — код 95, S-Log2 — 90, HLG — 64); "
          "будь значение превью кодом/1023 — было бы 24, 22 и 16")
    print(f"стало: режим {after['shootingMode']} ({setting(after, 'exposureProgram')['label']}), профиль {setting(after, 'pictureProfile')['label']}")
    if left:
        print("НЕ ВЕРНУЛОСЬ: " + "; ".join(f"{name}: было {before}, стало {now}" for name, before, now in left))
    else:
        print("все настройки камеры — как были (режим, профиль и остальные, кроме батареи и остатка на карте)")
    if args.json:
        with open(args.json, "w") as f:
            json.dump({"camera": {"model": s.get("model"), "firmware": s.get("firmware"), "transport": s.get("transport")},
                       "rows": rows, "failed": failed, "notRestored": left, "before": baseline["settings"],
                       "after": after["settings"], "gammaAssist": GAMMA_ASSIST}, f, ensure_ascii=False, indent=1)
    return 1 if failed or left else 0


if __name__ == "__main__":
    sys.exit(main())
