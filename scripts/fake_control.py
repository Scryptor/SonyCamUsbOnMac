"""fake_control.py — управляющий порт поддельной камеры: scripts/fake_camera.py --control 127.0.0.1:15742.

Проверкам нужно, чтобы камера сама что-то сделала посреди прогона: сменила объектив, переименовала подписи, прислала событие.
Для этого один механизм на все скрипты: TCP-порт, только на 127.0.0.1 (по умолчанию его нет — включается флагом), одна строка —
одна команда, в ответ одна строка. Сигналов и обёрток нет.

  printf 'lens-changed\n' | nc 127.0.0.1 15742        # → ok
  e2e_monitor.FakeCamera.control("e043", "30165")      # из скриптов проверок

Ответ: `ok` (у запроса — `ok значение`); `unknown` — такой команды нет; `error: …` — команда есть, а выполнить её нельзя (плохие
параметры).
Команды — в COMMANDS; те же строки печатает `fake_camera.py --help`. Шесть из них действуют только у новой камеры
(`--model newer`): у ZV-E10 операций 0x9215 и 0x9223 нет, она их не слышит, но событие уйдёт и ей.
"""
import asyncio

import fake_newer

# команда → что делает; порядок — как в справке
COMMANDS = {
    "lens-changed": "событие 0xC21B: объектив сменили (метки фокуса стираются, таблицу расстояний программа читает заново)",
    "lens-tele": "таблица телеобъектива (5 пар, от 1,2 м до «∞») и событие 0xC21B",
    "lens-two": "в ответе 0x9223 две таблицы и событие 0xC21B (расстояний программа не показывает)",
    "lens-newer": "таблица, как была (12 пар), и событие 0xC21B",
    "lens-bad": "0x9223 отвечает мусором (разбор не пройдёт) и событие 0xC21B",
    "list-rename": "в списке Creative Look «FL» → «FLM» и событие 0xC20F с типом 0x13",
    "list-drop": "список 0x13 пропал у камеры и событие 0xC20F с типом 0x13",
    "list-all": "событие 0xC20F без параметров (тип 0: подписи всех списков читаются заново)",
    "list-restore": "списки как были (событие не шлётся)",
    "e086 0|1": "признак данных объектива 0xE086 и событие 0xC203",
    "e043 [N]": "положение абсолютного фокуса 0xE043: с числом N (0…65535) поставить его (0xD24C — в процентах) и событие 0xC203, "
                "без числа — ответить им («ok 30165»)",
    "event КОД [П …]": "любое событие камеры с параметрами, числа как 0xC20F или 195",
}


def run(cam, words):
    """Выполняет команду; возвращает ответ одной строкой."""
    command, args = words[0], words[1:]
    tables = cam.tables
    if command == "lens-changed":
        cam.send_event(0xC21B)
    elif command == "lens-tele":
        tables.set_lens([fake_newer.TELE_PAIRS])
        cam.send_event(0xC21B)
    elif command == "lens-two":
        tables.set_lens([fake_newer.LENS_PAIRS, fake_newer.SECOND_PAIRS])
        cam.send_event(0xC21B)
    elif command == "lens-newer":
        tables.set_lens(fake_newer.LENS_INFORMATION)
        cam.send_event(0xC21B)
    elif command == "lens-bad":
        tables.set_lens(fake_newer.dataset(b"\x01\x02\x03")[:9])
        cam.send_event(0xC21B)
    elif command == "list-rename":
        tables.rename_look()
        cam.send_event(0xC20F, fake_newer.CREATIVE_LOOK_LIST)
    elif command == "list-drop":
        tables.drop_list()
        cam.send_event(0xC20F, fake_newer.CREATIVE_LOOK_LIST)
    elif command == "list-all":
        cam.send_event(0xC20F)
    elif command == "list-restore":
        tables.restore_lists()
    elif command == "e086" and args in (["0"], ["1"]):
        cam.props.set(fake_newer.LENS_INFO_ENABLED, int(args[0]))
        cam.changed(fake_newer.LENS_INFO_ENABLED)
    elif command == "e043" and not args:
        return f"ok {cam.props.get(fake_newer.FOCUS_CURRENT)}"
    elif command == "e043" and len(args) == 1 and args[0].isdigit() and int(args[0]) <= fake_newer.FOCUS_MAX:
        cam.set_focus(int(args[0]))
    elif command == "event" and args:
        try:
            numbers = [int(a, 0) for a in args]
        except ValueError:
            return "error: код и параметры события — числа"
        cam.send_event(numbers[0], *numbers[1:])
    elif command in ("e086", "e043", "event"):
        return "error: параметры команды " + command + " не те"
    else:
        return "unknown"
    cam.log("управление: " + " ".join(words))
    return "ok"


async def serve(cam, address):
    """Слушает `хост:порт` (или просто порт — тогда 127.0.0.1) и выполняет команды по строке на соединение."""
    host, _, port = address.rpartition(":")
    host = host or "127.0.0.1"

    async def handle(reader, writer):
        try:
            words = (await asyncio.wait_for(reader.readline(), 5)).decode().split()
            writer.write(((run(cam, words) if words else "unknown") + "\n").encode())
            await writer.drain()
        except (asyncio.TimeoutError, ConnectionError, UnicodeDecodeError):
            pass
        finally:
            writer.close()

    return await asyncio.start_server(handle, host, int(port))
