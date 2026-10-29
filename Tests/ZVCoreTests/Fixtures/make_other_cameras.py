#!/usr/bin/env python3
"""make_other_cameras.py — фикстуры для разбора 0x9215 и 0x9223 (только stdlib).

Данные берутся у стенда: scripts/fake_newer.py — это тот же профиль «новой камеры» Sony, который отдаёт поддельная
камера (scripts/fake_camera.py --model newer). Так у стенда, Mac-тестов и Android-тестов одни и те же байты.

- display_strings.bin — ответ 0x9215 на тип 0 («все списки»):
  - тип 0x13 (Creative Look), UINT16: 0x0001 «ST» … 0x000C «FL3», 0x0101…0x0106 «Custom Look 1»…«6»;
  - тип 0x15, UINT16: 1 «Человек», 2 «Животное»;
- lens_information.bin — ответ 0x9223: версия 100 (1.00), одна таблица на 12 пар; положения 0, 5958, 11916 … 65535,
  расстояния ×100: 25, 30, 40, 50, 70, 100, 150, 200, 300, 500, 1000, 100 000 000 (то есть от 0,25 м до «∞»).

У обоих набор начинается с u32 смещения до данных (8) и u32 размера данных (длина тела), всё little-endian.

Файлы пишутся в Tests/ZVCoreTests/Fixtures и копией, байт в байт, в android/app/src/test/resources/fixtures:

    python3 Tests/ZVCoreTests/Fixtures/make_other_cameras.py
"""
import os
import sys

# Tests/ZVCoreTests/Fixtures/make_other_cameras.py → корень репозитория на четыре папки выше файла
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
FOLDERS = [
    os.path.join(ROOT, "Tests/ZVCoreTests/Fixtures"),
    os.path.join(ROOT, "android/app/src/test/resources/fixtures"),
]

sys.dont_write_bytecode = True                              # не оставлять scripts/__pycache__ от разовой сборки
sys.path.insert(0, os.path.join(ROOT, "scripts"))
import fake_newer  # noqa: E402  (путь добавлен строкой выше)

FILES = {
    "display_strings.bin": fake_newer.display_strings(0),
    "lens_information.bin": fake_newer.LENS_INFORMATION,
}


def main():
    for name, data in FILES.items():
        for folder in FOLDERS:
            os.makedirs(folder, exist_ok=True)
            with open(os.path.join(folder, name), "wb") as out:
                out.write(data)
        print(f"{name}: {len(data)} байт")


if __name__ == "__main__":
    main()
