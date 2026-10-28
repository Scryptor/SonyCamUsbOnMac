#!/usr/bin/env python3
"""e2e_gallery.py — удаление снимков из галереи в программе для Mac против поддельной камеры: файлы уходят в Корзину,
галерея переходит на соседний снимок, «Вернуть» кладёт файлы и снимок на место, плашка уходит сама, удалили последний —
галерея закрывается, а «Вернуть» открывает её снова.

Запускает scripts/fake_camera.py (фоторежим) на 127.0.0.1:15741 и программу с API автоматизации, снимает три снимка
(два — RAW + JPEG, третий — стоп-кадр) во временную папку и проверяет /state, файлы и Корзину. Скриншоты — в --out. В конце настройки
программы — как были, а файлы этих снимков, оставшиеся в Корзине, убираются (это файлы самого теста).

  scripts/e2e_gallery.py [--app "build/Sony Cam Viewer.app"] [--out /tmp/gallery]
"""
import argparse, os, signal, subprocess, sys, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "scripts"))
from e2e_monitor import CAMERA, act, check, restore_prefs, save_prefs, shot, state, wait  # noqa: E402
import e2e_monitor  # noqa: E402

TRASH = os.path.expanduser("~/.Trash")


def titles(s):
    return [x["title"] for x in s["shots"]]


def files(s, title):
    return next(x["files"] for x in s["shots"] if x["title"] == title)


def gallery(s):
    return s["ui"]["gallery"]


def deleted(s):
    return s["ui"]["deletedShot"]


def capture(n):
    act("capture", confirm=True)
    return wait(lambda s: len(s["shots"]) == n and not s["capturing"], 25)


def run(out, folder, trashed):
    act("setPreference", key="autoHide", value="false")
    act("setPreference", key="reviewEnabled", value="false")
    act("setPreference", key="folder", value=folder)
    act("setPreference", key="destination", value="macOnly")
    base = len(state()["shots"])
    check("в ленте пусто", base == 0, base)
    # камера снимает RAW + JPEG (два файла), третий — стоп-кадр (один файл)
    capture(1)
    capture(2)
    act("grabFrame")
    s = wait(lambda s: len(s["shots"]) == 3, 5)
    names = titles(s)
    check("два снимка RAW + JPEG и стоп-кадр", len(names) == 3 and all(" + " in n for n in names[1:]) and
          names[0].startswith("Кадр "), names)
    if len(names) != 3:
        return
    newest, raw, oldest = names
    raw_files = files(s, raw)

    act("openGallery", index=1)
    s = wait(lambda s: gallery(s) and gallery(s)["index"] == 1, 3)
    shot(out, "1-gallery")

    # удалить открытый: файлы — в Корзину, галерея — на вставший на его место (старше)
    r = act("deleteShot")
    s = wait(lambda s: len(s["shots"]) == 2, 3)
    d = deleted(s)
    check("удалённого нет в ленте", titles(s) == [newest, oldest], titles(s))
    check("файлы ушли с места", all(not os.path.exists(f) for f in raw_files), raw_files)
    moved = (d or {}).get("trashed", [])
    trashed.update(moved)
    check("оба файла — в Корзине", len(moved) == 2 and all(p.startswith(TRASH) and os.path.exists(p) for p in moved), moved)
    check("галерея — на снимке, вставшем на его место", gallery(s) == {"index": 1, "title": oldest}, gallery(s))
    check("плашка «Вернуть» — про этот снимок", d and d["title"] == raw, d)
    shot(out, "2-deleted")

    # вернуть: файлы и снимок — на место, галерея — на нём
    act("undoDeleteShot")
    s = wait(lambda s: len(s["shots"]) == 3, 3)
    check("снимок вернулся на своё место", titles(s) == [newest, raw, oldest], titles(s))
    check("файлы вернулись", all(os.path.exists(f) for f in raw_files) and not any(os.path.exists(p) for p in moved))
    check("галерея — на вернувшемся снимке", gallery(s) == {"index": 1, "title": raw}, gallery(s))
    check("плашки больше нет", deleted(s) is None, deleted(s))
    r = act("undoDeleteShot")
    check("второй раз вернуть нечего — 409", r.get("status") == 409, r)

    # самый старый: галерея — на соседнем новее; плашка уходит сама через 6 с, вернуть уже нельзя
    act("galleryStep", offset=1)
    wait(lambda s: gallery(s) and gallery(s)["index"] == 2, 3)
    act("deleteShot")
    s = wait(lambda s: len(s["shots"]) == 2, 3)
    trashed.update((deleted(s) or {}).get("trashed", []))
    check("удалили самый старый — галерея на соседнем новее", gallery(s) == {"index": 1, "title": raw}, gallery(s))
    started = time.time()
    s = wait(lambda s: deleted(s) is None, 9)
    took = time.time() - started
    check("плашка уходит сама через 6 с", deleted(s) is None and 5 < took < 7.5, f"{took:.1f} с")
    r = act("undoDeleteShot")
    check("после плашки вернуть нельзя — 409", r.get("status") == 409 and len(state()["shots"]) == 2, r)

    # из ленты (галерея закрыта) — по месту; плашка видна на основном экране
    act("closeGallery")
    r = act("deleteShot")
    check("без открытой галереи и места — 409", r.get("status") == 409, r)
    r = act("deleteShot", index=9)
    check("нет такого снимка — 409", r.get("status") == 409, r)
    grab_files = files(state(), newest)
    act("deleteShot", index=0)
    s = wait(lambda s: len(s["shots"]) == 1, 3)
    moved = (deleted(s) or {}).get("trashed", [])
    trashed.update(moved)
    check("удаление из ленты по месту", titles(s) == [raw] and gallery(s) is None, (titles(s), gallery(s)))
    check("стоп-кадр — один файл, в Корзине", len(moved) == 1 and os.path.exists(moved[0]) and not os.path.exists(grab_files[0]),
          moved)
    shot(out, "3-live-banner")

    # последний снимок: галерея закрывается, «Вернуть» открывает её на нём
    act("openGallery", index=0)
    wait(lambda s: gallery(s) is not None, 3)
    act("deleteShot")
    s = wait(lambda s: len(s["shots"]) == 0, 3)
    trashed.update((deleted(s) or {}).get("trashed", []))
    check("удалили последний — галерея закрылась, плашка есть", gallery(s) is None and deleted(s) is not None,
          (gallery(s), deleted(s)))
    act("undoDeleteShot")
    s = wait(lambda s: len(s["shots"]) == 1 and gallery(s) is not None, 3)
    check("«Вернуть» открыл галерею на вернувшемся снимке", gallery(s) == {"index": 0, "title": raw}, gallery(s))
    check("RAW + JPEG снова в папке", all(os.path.exists(f) for f in raw_files))
    shot(out, "4-restored")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--app", default=os.path.join(ROOT, "build/Sony Cam Viewer.app"))
    ap.add_argument("--out", default="/tmp/gallery")
    args = ap.parse_args()
    out = os.path.abspath(args.out)
    folder = os.path.join(out, "Загрузки")
    os.makedirs(folder, exist_ok=True)
    before = save_prefs()
    log = open(os.path.join(out, "camera.log"), "a")
    cam = subprocess.Popen([sys.executable, os.path.join(ROOT, "scripts/fake_camera.py"), "--listen", CAMERA, "--mode", "photo", "--format", "raw+jpeg"],
                           stdout=log, stderr=subprocess.STDOUT)
    time.sleep(0.5)
    env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST=CAMERA)
    proc = subprocess.Popen([os.path.join(args.app, "Contents/MacOS/ZVViewer"), "-ApplePersistenceIgnoreState", "YES"], env=env,
                            stdout=open(os.path.join(out, "app.log"), "a"), stderr=subprocess.STDOUT, start_new_session=True)
    trashed = set()
    try:
        s = wait(lambda s: s.get("phase") == "ready" and s.get("fps", 0) > 3, 40)
        if not s or s.get("phase") != "ready":
            print(f"программа не дошла до ready: {s and s.get('phase')}")
            return 1
        run(out, folder, trashed)
    finally:
        proc.send_signal(signal.SIGTERM)
        try:
            proc.wait(timeout=8)
        except subprocess.TimeoutExpired:
            proc.kill()
        cam.terminate()
        cam.wait()
        restore_prefs(before)
        # в Корзине — только файлы этого теста, по путям, которые назвала программа
        for path in trashed:
            if path.startswith(TRASH + "/") and os.path.isfile(path):
                os.remove(path)
    print(f"\n{e2e_monitor.passed} из {e2e_monitor.passed + e2e_monitor.failed} проверок прошли; скриншоты — {out}")
    return 0 if e2e_monitor.failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
