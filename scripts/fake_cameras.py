#!/usr/bin/env python3
"""fake_cameras.py — несколько разных виртуальных ZV-E10 одной командой (scripts/fake_camera.py на соседних портах).

У каждой свой серийный номер (программа отличает их по нему), своя картинка (одно и то же горное озеро: у B кадр
отражён, у C — крупно у солнца, у D — левый берег), свой заряд и режим. Все отвечают на поиск в сети (SSDP) с портом в X-PTPIP-Port. Ctrl+C гасит все.
Объектив и модель у всех одинаковые: --focus-gain, --focus-step-ms, --focus-deadband и --model уходят каждой камере как есть
(см. fake_camera.py): --model newer — все камеры новые, как ILCE-7M4 в fake_newer.py.

  scripts/fake_cameras.py [--host 127.0.0.1] [--count 3] [--pairing B] [--busy C] [--reject C:iso] [--slow-record C]
                          [--no-ssdp] [--focus-gain G] [--focus-step-ms T] [--focus-deadband D] [--model zv-e10|newer]
  scripts/fake_cameras.py --host 192.168.1.123      # для планшета: адрес Mac в сети
"""
import argparse, os, signal, subprocess, sys, threading

HERE = os.path.dirname(os.path.abspath(__file__))
CAMERAS = [
    {"letter": "A", "mark": None, "battery": 80, "mode": "video"},
    {"letter": "B", "mark": "flip", "battery": 45, "mode": "photo"},
    {"letter": "C", "mark": "zoom", "battery": 12, "mode": "video"},
    {"letter": "D", "mark": "pan", "battery": 100, "mode": "photo"},
]


def relay(letter, stream):
    for line in stream:
        print(f"[{letter}] {line}", end="", flush=True)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1", help="адрес, на котором слушают все камеры")
    ap.add_argument("--port", type=int, default=15740, help="порт первой камеры; следующие — по порядку")
    ap.add_argument("--count", type=int, default=3, choices=range(1, len(CAMERAS) + 1))
    ap.add_argument("--pairing", default="", help="буквы камер, которым нужно сопряжение, например B")
    ap.add_argument("--busy", default="", help="буквы камер, которые не отвечают на приветствие, например C")
    ap.add_argument("--no-ssdp", action="store_true", help="не отвечать на поиск в сети")
    ap.add_argument("--reject", default="", help="камера:свойства, запись в которые не меняет значение, например C:iso")
    ap.add_argument("--slow-record", default="", help="буквы камер, у которых запись начинается и останавливается за 2 с")
    ap.add_argument("--focus-gain", metavar="G", help="всем камерам: шаг Near/Far сдвигает фокус на round(команда × G)")
    ap.add_argument("--focus-step-ms", metavar="T", help="всем камерам: ответ на Near/Far приходит через T миллисекунд")
    ap.add_argument("--focus-deadband", metavar="D", help="всем камерам: малые шаги Near/Far ближе D к краю шкалы не двигают фокус")
    ap.add_argument("--model", choices=["zv-e10", "newer"], help="всем камерам: zv-e10 — как в дампах (по умолчанию), newer — новая Sony")
    a = ap.parse_args()
    children = []
    for i, cam in enumerate(CAMERAS[:a.count]):
        letter = cam["letter"]
        args = [sys.executable, os.path.join(HERE, "fake_camera.py"), "--listen", f"{a.host}:{a.port + i}",
                "--serial", f"{'FAKE' + letter:0>32}", "--battery", str(cam["battery"]), "--mode", cam["mode"]]
        if cam["mark"]:
            args += ["--mark", cam["mark"]]
        if not a.no_ssdp:
            args.append("--ssdp")
        if letter in a.pairing.upper():
            args.append("--pairing")
        if letter in a.busy.upper():
            args.append("--busy")
        if letter in a.slow_record.upper():
            args += ["--record-delay", "2"]
        for flag, value in (("--focus-gain", a.focus_gain), ("--focus-step-ms", a.focus_step_ms),
                            ("--focus-deadband", a.focus_deadband), ("--model", a.model)):
            if value is not None:
                args += [flag, value]
        for spec in filter(None, a.reject.split(";")):
            who, _, props = spec.partition(":")
            if who.upper() == letter:
                args += ["--reject", props]
        child = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        threading.Thread(target=relay, args=(letter, child.stdout), daemon=True).start()
        children.append(child)

    def stop(*_):
        for child in children:
            child.terminate()
    signal.signal(signal.SIGTERM, stop)
    try:
        for child in children:
            child.wait()
    except KeyboardInterrupt:
        stop()
        for child in children:
            child.wait()


if __name__ == "__main__":
    main()
