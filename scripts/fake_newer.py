"""fake_newer.py — что отличает «новую камеру» Sony от ZV-E10, для scripts/fake_camera.py --model newer (только stdlib).

У новых камер, судя по справочнику Sony Camera Control PTP 3, есть то, чего нет у ZV-E10: подписи списков от самой камеры
(0x9215), версия кода производителя (0x9216), данные объектива (0x9223), Creative Look 0xD0FA вместо Creative Style 0xD240,
гамма и цвет профиля 0xD0E1 и 0xD0E9, абсолютный фокус 0xE042 и 0xE043 и признак данных объектива 0xE086. Здесь они
собираются в байты: описатели свойств (DevicePropDesc, как в ответе 0x9209), правка ответов ZV-E10 (DeviceInfo, 0x9202,
0x9209) и наборы данных новых операций. Сама «камера» — fake_camera.py. Модель «ILCE-7M4» выдумана стендом: настоящая модель
нужна только в отчётах.

Всё little-endian. У набора данных (0x9215, 0x9223) впереди u32 смещение до данных — 8, резерва нет — и u32 размер данных.
"""
import struct

MODEL = "ILCE-7M4"
OPERATIONS = (0x9215, 0x9216, 0x9223)                      # подписи списков, версия кода производителя, данные объектива
CREATIVE_STYLE = 0xD240                                    # у новой камеры вместо него Creative Look
CREATIVE_LOOK, PP_GAMMA, PP_COLOR_MODE = 0xD0FA, 0xD0E1, 0xD0E9
FOCUS_SETTING, FOCUS_CURRENT, LENS_INFO_ENABLED = 0xE042, 0xE043, 0xE086
FOCUS_MAX = 0xFFFF                                         # шкала абсолютного фокуса 0…65535
FOCUS_START = FOCUS_MAX                                    # дальний край — как 0xD24C = 100 в дампе ZV-E10
# версии Sony пишет ×100: 1.00 — это 100 (0x0064), 3.10 — 0x0136
DISPLAY_LIST_VERSION = 100                                 # параметр ответа 0x9215: версия списков 1.00
VENDOR_CODE_VERSION = 0x0136                               # параметр ответа 0x9216: версия кода производителя 3.10
LENS_INFO_VERSION = 100                                    # версия данных объектива 1.00 (в самом наборе)
LENS_METERS = 2                                            # параметр 0x9223: 1 — футы, 2 — метры; отдаём метры
CREATIVE_LOOK_LIST, SUBJECT_LIST = 0x13, 0x15              # типы списков 0x9215: Creative Look; «Человек/Животное»
UINT8, UINT16 = 2, 4                                       # типы данных PTP
STRING = 0xFFFF                                            # строка PTP: u8 число символов с нулём, UTF-16LE
# упаковка значений по типу PTP (INT8…UINT64) и их размеры (до UINT128)
PACK = {1: "<b", 2: "<B", 3: "<h", 4: "<H", 5: "<i", 6: "<I", 7: "<q", 8: "<Q"}
SIZES = {1: 1, 2: 1, 3: 2, 4: 2, 5: 4, 6: 4, 7: 8, 8: 8, 9: 16, 10: 16}

CREATIVE_LOOKS = [(1, "ST"), (2, "PT"), (3, "NT"), (4, "VV"), (5, "VV2"), (6, "FL"), (7, "IN"), (8, "SH"), (9, "BW"),
                  (10, "SE"), (11, "FL2"), (12, "FL3")] + [(0x100 + n, f"Custom Look {n}") for n in range(1, 7)]
SUBJECTS = [(1, "Человек"), (2, "Животное")]               # числа выдуманы стендом: настоящая камера нумерует по-своему
DISPLAY_LISTS = [(CREATIVE_LOOK_LIST, UINT16, CREATIVE_LOOKS), (SUBJECT_LIST, UINT16, SUBJECTS)]
# значения 0xD0E1 и 0xD0E9 из справочника: от Movie до HLG3 (S-Log3 — 0x0303), от Movie до 709tone (S-Gamut3.Cine — 0x0008)
PP_GAMMAS = [0x0001, 0x0002, 0x0003, 0x0101, 0x0102, 0x0103, 0x0104, 0x0201, 0x0202, 0x0302, 0x0303, 0x0401, 0x0402,
             0x0403, 0x0404]
PP_COLOR_MODES = list(range(0x0001, 0x000E))
# данные объектива: двенадцать точек шкалы 0…65535 через 5958 (последняя — край); расстояние ×100 в метрах — от 0,25 м до
# 1 000 000 м, то есть «бесконечность»
LENS_DISTANCES = (25, 30, 40, 50, 70, 100, 150, 200, 300, 500, 1000, 100_000_000)
LENS_PAIRS = [(min(FOCUS_MAX, 5958 * i), distance) for i, distance in enumerate(LENS_DISTANCES)]


def dataset(body):
    """Набор данных Sony: u32 смещение до данных (8), u32 размер данных, сами данные."""
    return struct.pack("<II", 8, len(body)) + body


def display_string_list(lists):
    """Набор 0x9215 (SDIDisplayStringList): [(тип списка, тип данных PTP, [(значение, строка)])]. Без списков — ListTypeNum = 0,
    так камера отвечает на тип, которого у неё нет. Строка — UTF-8 с нулём, её размер (с нулём) идёт перед ней."""
    body = struct.pack("<H2x", len(lists))
    for list_type, ptype, strings in lists:
        body += struct.pack("<IHH", list_type, ptype, len(strings))
        for value, text in strings:
            raw = text.encode("utf-8") + b"\0"
            body += struct.pack(PACK[ptype], value) + struct.pack("<H", len(raw)) + raw
    return dataset(body)


def display_strings(list_type):
    """Ответ 0x9215 на тип списка: 0 — все списки камеры, 0x13 — только Creative Look, любой другой тип — пустой набор."""
    lists = {0: DISPLAY_LISTS, CREATIVE_LOOK_LIST: DISPLAY_LISTS[:1]}.get(list_type, [])
    return display_string_list(lists)


def lens_information(version, tables):
    """Набор 0x9223 (SDILensInformation): версия ×100, число таблиц; у таблицы — число пар и два байта резерва, затем пары
    (нормированное положение фокуса, расстояние ×100)."""
    body = struct.pack("<HH", version, len(tables))
    for pairs in tables:
        body += struct.pack("<H2x", len(pairs)) + b"".join(struct.pack("<II", p, d) for p, d in pairs)
    return dataset(body)


LENS_INFORMATION = lens_information(LENS_INFO_VERSION, [LENS_PAIRS])


def prop_desc(code, ptype, current, settable=True, enabled=1, values=None, span=None):
    """Описатель одного свойства для 0x9209 (docs/protocol.md, «Чтение свойств»): код, тип, можно ли менять, включено,
    «значение по умолчанию» (у Sony это резерв — 0), текущее и форма. values — перечисление: два списка (что можно поставить
    сейчас и все значения; у новой камеры совпадают), span — диапазон (min, max, step); без обоих формы нет."""
    def value(v):
        return struct.pack(PACK[ptype], v)

    out = struct.pack("<HHBB", code, ptype, 1 if settable else 0, enabled) + value(0) + value(current)
    if values is not None:
        listed = struct.pack("<H", len(values)) + b"".join(value(v) for v in values)
        return out + b"\x02" + listed + listed
    if span is not None:
        return out + b"\x01" + b"".join(value(v) for v in span)
    return out + b"\x00"


def new_properties():
    """Свойства новой камеры, которых нет у ZV-E10: код → описатель. Положение фокуса 0xE043 только читается, 0xE042 — цель."""
    return {
        CREATIVE_LOOK: prop_desc(CREATIVE_LOOK, UINT16, 0x0001, values=[v for v, _ in CREATIVE_LOOKS]),
        PP_GAMMA: prop_desc(PP_GAMMA, UINT16, 0x0303, values=PP_GAMMAS),
        PP_COLOR_MODE: prop_desc(PP_COLOR_MODE, UINT16, 0x0008, values=PP_COLOR_MODES),
        FOCUS_SETTING: prop_desc(FOCUS_SETTING, UINT16, FOCUS_START, span=(0, FOCUS_MAX, 1)),
        FOCUS_CURRENT: prop_desc(FOCUS_CURRENT, UINT16, FOCUS_START, settable=False, span=(0, FOCUS_MAX, 1)),
        LENS_INFO_ENABLED: prop_desc(LENS_INFO_ENABLED, UINT8, 1, settable=False, values=[0, 1]),
    }


def skip_value(blob, ptype, pos):
    """Конец значения типа ptype, начатого с pos."""
    return pos + 1 + 2 * blob[pos] if ptype == STRING else pos + SIZES[ptype]


def skip_list(blob, ptype, pos):
    """Конец списка (u16 число значений, сами значения), начатого с pos."""
    count = struct.unpack_from("<H", blob, pos)[0]
    pos += 2
    for _ in range(count):
        pos = skip_value(blob, ptype, pos)
    return pos


def split_properties(blob):
    """Ответ 0x9209 по свойствам: [(код, байты описателя)]. После заголовка (u32 число свойств, u32 резерв) описатели идут
    подряд; конец каждого находится так же, как в программе (SonyPropertyParser): значение по умолчанию, текущее, форма —
    диапазон из трёх значений или список, а за ним, если следующее u16 меньше 0x200, второй список."""
    out, pos = [], 8
    while pos < len(blob):
        start = pos
        code, ptype = struct.unpack_from("<HH", blob, pos)
        pos = skip_value(blob, ptype, pos + 6)              # значение по умолчанию
        if not (ptype == STRING and pos >= len(blob)):      # у строки в самом конце буфера текущего значения может не быть
            pos = skip_value(blob, ptype, pos)              # текущее
            if pos < len(blob):
                form = blob[pos]
                pos += 1
                if form == 1:                               # диапазон: min, max, step
                    for _ in range(3):
                        pos = skip_value(blob, ptype, pos)
                elif form == 2:                             # список; у части свойств следом второй
                    pos = skip_list(blob, ptype, pos)
                    if pos + 2 <= len(blob) and struct.unpack_from("<H", blob, pos)[0] < 0x200:
                        pos = skip_list(blob, ptype, pos)
        out.append((code, bytes(blob[start:pos])))
    return out


def properties(blob):
    """0x9209 новой камеры из дампа ZV-E10: без Creative Style 0xD240, с новыми свойствами; описатели по возрастанию кода,
    как в дампе, число свойств в заголовке пересчитано."""
    kept = {code: desc for code, desc in split_properties(blob) if code != CREATIVE_STYLE}
    descs = sorted({**kept, **new_properties()}.items())
    return struct.pack("<I", len(descs)) + bytes(blob[4:8]) + b"".join(desc for _, desc in descs)


def ext_info(ext):
    """0x9202 новой камеры из дампа ZV-E10: u16 версия, список кодов свойств (без 0xD240, с новыми, по возрастанию), затем
    список команд управления — как у ZV-E10."""
    version, count = struct.unpack_from("<HI", ext)
    codes = (set(struct.unpack_from(f"<{count}H", ext, 6)) - {CREATIVE_STYLE}) | set(new_properties())
    return struct.pack(f"<HI{len(codes)}H", version, len(codes), *sorted(codes)) + ext[6 + 2 * count:]


def device_info(info):
    """GetDeviceInfo новой камеры из дампа ZV-E10: к операциям добавлены 0x9215, 0x9216 и 0x9223 (список по возрастанию,
    как в дампе), модель — ILCE-7M4. Остальное (производитель, версия, серийный номер, события) — как было."""
    pos = 2 + 4 + 2
    pos += 1 + 2 * info[pos]                                # описание расширения производителя
    pos += 2                                                # FunctionalMode
    count = struct.unpack_from("<I", info, pos)[0]
    operations = sorted(set(struct.unpack_from(f"<{count}H", info, pos + 4)) | set(OPERATIONS))
    head, rest = info[:pos], pos + 4 + 2 * count            # до операций; после них — события, свойства, форматы
    pos = rest
    for _ in range(4):                                      # события, свойства, форматы съёмки и изображений
        pos += 4 + 2 * struct.unpack_from("<I", info, pos)[0]
    pos += 1 + 2 * info[pos]                                # производитель
    model_end = pos + 1 + 2 * info[pos]
    model = bytes([len(MODEL) + 1]) + MODEL.encode("utf-16-le") + b"\0\0"
    return (head + struct.pack(f"<I{len(operations)}H", len(operations), *operations) + info[rest:pos] + model
            + info[model_end:])
