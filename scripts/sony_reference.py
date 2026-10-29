#!/usr/bin/env python3
"""Таблицы из справочника Sony «Camera Control PTP 3 Reference»: названия свойств и списки значений для обеих программ.

Сам PDF Sony в репозиторий не кладётся — ссылка и порядок сборки в docs/protocol.md («Другие камеры Sony»). Текст из PDF:

  pdftotext -layout "Camera Control PTP 3 Reference.pdf" ptp3.txt
  scripts/sony_reference.py ptp3.txt

Скрипт пишет один и тот же JSON в два места (оба коммитятся; править — скриптом или JSON сразу в обоих):

  Sources/ZVCore/Sony/SonyReferenceData.swift         JSON в сырой строке Swift (Mac)
  android/app/src/main/resources/sony_reference.json  Java-ресурс: попадает в APK и читается в тестах на JVM

Формат: {"source": …, "properties": {"0xD0FA": {"name": "Creative Look", "values": {"0x0001": "ST(Standard)", …}}, …}}.
Свойства и значения идут по возрастанию кода, отступ — один пробел, чтобы правки были видны в diff. У свойств без списка
значений (диапазон min/max/step, строка, «Any») поля "values" нет. Ключи — как в справочнике, только заглавными.

Как разбирается текст:
  • раздел свойства — от строки «PropertyCode 1 2 UINT16 0x…» до следующей такой;
  • название — строка перед ближайшим выше «Summary»;
  • значения — строки «0x.. Описание» первой таблицы «Value  Description» раздела, до «Note» или следующего «Summary».
    Таблицы внутри «Note» описывают разряды составного значения (0xE068, 0xE06C, 0xE082), а не само свойство — их нет;
  • таблицу рвут страницы: номер страницы, колонтитул и повторённый заголовок «Value  Description» пропускаются;
  • код стоит в первом столбце — на том же отступе, что и заголовок таблицы. Пояснения в ячейках («e.g.) 0x0000 = 0»)
    значениями не считаются;
  • описание идёт через два пробела или через один, если начинается с буквы («0xFFFFFFFFFFFFFFFF nothing to display.»):
    у кода на всю ширину столбца зазора больше нет. Пробелы подряд в описании схлопываются в один;
  • у высокой ячейки текст в нескольких строках, а код посередине — отдельной строкой: описание — строка над кодом и строка
    под ним (0xD221). Остальные строки ячеек («Other than above values», «Any», «variable») списка не пополняют;
  • диапазоны не значения: «0x00000000-0xFFFFFFFF», «0x00 ～ 0xFF», код со строкой «～0xFFFFFFFF» под ним, а у свойств с
    FormFlag 1 (Range) — ещё и строки с описанием min, max, step (границы).
"""
import argparse, json, re, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SWIFT_FILE = ROOT / "Sources/ZVCore/Sony/SonyReferenceData.swift"
JSON_FILE = ROOT / "android/app/src/main/resources/sony_reference.json"
SOURCE = "Camera Control PTP 3 Reference"

PROPERTY_CODE = re.compile(r"^\s*PropertyCode\s+1\s+2\s+UINT16\s+(0x[0-9A-Fa-f]{4})\s*$")
TABLE_HEADER = re.compile(r"^\s*Value\s+Description\s*$")
VALUE_ROW = re.compile(r"^(0x[0-9A-Fa-f]+)(?:\s{2,}|\s(?=[A-Za-z]))(\S.*)$")   # код и описание в одной строке
LONE_CODE = re.compile(r"^(0x[0-9A-Fa-f]+)\s*$")                                # код, описание — над ним и под ним
PAGE_NUMBER = re.compile(r"^\d+$")
FORM_FLAG = re.compile(r"^\s*FormFlag\s+7\s+1\s+UINT8\s+0x0([0-9A-Fa-f])")   # 1 — диапазон (min/max/step), 2 — список
RANGE_BOUND = re.compile(r"(?i)^(min|max|step)$")
RANGE_END = re.compile(r"^[~～]\s*0x[0-9A-Fa-f]+")                              # «0x00000000» и под ним «～0xFFFFFFFF»


def indent_of(raw):
    return len(raw) - len(raw.lstrip())


def is_page_noise(line):
    """Номер страницы и колонтитул — между строками таблицы, когда её рвёт страница."""
    return bool(PAGE_NUMBER.match(line)) or line == SOURCE


def normalized(code):
    """0xd0fa → 0xD0FA: «x» остаётся строчной."""
    return "0x" + code[2:].upper()


def clean(description):
    return re.sub(r"\s{2,}", " ", description).strip()


def property_title(lines, at):
    """Название раздела — строка перед ближайшим выше «Summary»."""
    i = at - 1
    while i > 0 and lines[i].strip() != "Summary":
        i -= 1
    if i < 1:
        raise SystemExit(f"строка {at + 1}: над свойством нет «Summary»")
    return lines[i - 1].strip()


def is_range(body):
    """FormFlag свойства — 1 (Range): значения в таблице — границы min/max/step, а не список."""
    for line in body:
        if TABLE_HEADER.match(line):
            return False
        if m := FORM_FLAG.match(line):
            return m[1] == "1"
    return False


def value_table(body):
    """Список значений раздела: [(код, описание)] из первой таблицы «Value  Description» до «Note» или «Summary»."""
    ranged = is_range(body)
    rows = []
    table_indent = None
    used = set()                                  # строки, уже ставшие описанием кода («под ним»)

    def cell_text(i):
        """Строка ячейки описания: правее столбца кодов, не колонтитул и не пустая."""
        if not 0 <= i < len(body) or i in used:
            return None
        line = body[i].strip()
        if not line or is_page_noise(line) or indent_of(body[i]) <= table_indent:
            return None
        return line

    for i, raw in enumerate(body):
        line = raw.strip()
        if line in ("Note", "Summary"):
            break
        if TABLE_HEADER.match(raw):
            table_indent = indent_of(raw)
            continue
        if table_indent is None or not line or indent_of(raw) != table_indent:
            continue
        if RANGE_END.match(line):
            if rows:
                rows.pop()                        # код строки выше — начало диапазона, а не одно значение
        elif m := VALUE_ROW.match(line):
            if not (ranged and RANGE_BOUND.match(clean(m[2]))):
                rows.append((m[1], clean(m[2])))
        elif m := LONE_CODE.match(line):
            above, below = cell_text(i - 1), cell_text(i + 1)
            used.add(i + 1)
            if above is None and below is None:
                print(f"предупреждение: у {m[1]} нет описания: {line!r}", file=sys.stderr)
            else:
                rows.append((m[1], clean(" ".join(part for part in (above, below) if part))))
    return rows


def parse(lines):
    """{"0xD0FA": {"name": …, "values": {"0x0001": …}}} — по возрастанию кодов; «values» нет, если списка у свойства нет."""
    starts = [(i, normalized(m[1])) for i, line in enumerate(lines) if (m := PROPERTY_CODE.match(line))]
    if not starts:
        raise SystemExit("в тексте нет разделов свойств («PropertyCode 1 2 UINT16 0x…»): это вывод pdftotext -layout?")
    properties = {}
    for n, (at, code) in enumerate(starts):
        if code in properties:
            raise SystemExit(f"строка {at + 1}: свойство {code} описано дважды")
        end = starts[n + 1][0] if n + 1 < len(starts) else len(lines)
        body = lines[at + 1:end]
        values = {}                                       # число → (ключ, описание)
        for key, description in value_table(body):
            key = normalized(key)
            number = int(key, 16)
            if number >= 1 << 64:
                print(f"предупреждение: {code}: значение {key} не помещается в 64 бита, пропущено", file=sys.stderr)
            elif number in values:
                print(f"предупреждение: {code}: значение {key} повторяется, оставлено первое", file=sys.stderr)
            else:
                values[number] = (key, description)
        entry = {"name": property_title(lines, at)}
        if values:
            entry["values"] = {key: description for _, (key, description) in sorted(values.items())}
        properties[code] = entry
    return {"source": SOURCE, "properties": dict(sorted(properties.items(), key=lambda item: int(item[0], 16)))}


def swift_source(json_text):
    """Swift-файл: шапка и JSON в сырой многострочной строке. Пустая строка перед закрывающей даёт тот же перевод строки в
    конце, что у файла JSON, — тест сверяет их побайтно."""
    hashes = "#"
    while '"""' + hashes in json_text or "\\" + hashes in json_text:
        hashes += "#"
    return (f"// Создано scripts/sony_reference.py из {SOURCE} — правьте через скрипт или JSON в обоих местах\n"
            f"// (второе место — {JSON_FILE.relative_to(ROOT)}; тест androidCopyMatches сверяет их побайтно).\n"
            "\n"
            "/// JSON справочника Sony: имена свойств и списки значений. Разбирает его SonyReferenceTables.\n"
            "enum SonyReferenceData {\n"
            f'    static let json = {hashes}"""\n'
            f'{json_text}\n"""{hashes}\n'
            "}\n")


def main():
    parser = argparse.ArgumentParser(description="Таблицы из справочника Sony: JSON для Mac и Android.")
    parser.add_argument("text", type=Path,
                        help="текст справочника: pdftotext -layout «Camera Control PTP 3 Reference.pdf» ptp3.txt")
    args = parser.parse_args()
    if not args.text.is_file():
        parser.error(f"нет файла {args.text}")

    data = parse(args.text.read_text(encoding="utf-8").split("\n"))
    json_text = json.dumps(data, ensure_ascii=False, indent=1) + "\n"
    JSON_FILE.parent.mkdir(parents=True, exist_ok=True)
    JSON_FILE.write_text(json_text, encoding="utf-8")
    SWIFT_FILE.write_text(swift_source(json_text), encoding="utf-8")

    properties = data["properties"]
    listed = [p for p in properties.values() if "values" in p]
    print(f"{len(properties)} свойств, {len(listed)} со списками значений, {sum(len(p['values']) for p in listed)} значений")
    for path in (JSON_FILE, SWIFT_FILE):
        print(f"  {path.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
