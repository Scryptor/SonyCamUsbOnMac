#!/usr/bin/env python3
"""fake_camera.py — поддельная ZV-E10 по PTP/IP из настоящих ответов камеры (Tests/ZVCoreTests/Fixtures).

Нужна, чтобы гонять обе программы без живой камеры: приветствие, рукопожатие Sony, свойства, пробы, кадры live view
~25 к/с, события 0xC203. В видоискателе — горное озеро на закате (scripts/assets, рисует
scripts/make-viewfinder-picture.py), свой JPEG — --picture. Качество превью 0xD26A меняет кадр, как настоящая камера по
Wi-Fi: высокое — 1024×576, дополнено до ~72 КБ; низкое — 640×360, дополнено до ~34 КБ.

Снимает и пишет, ничего не записывая на настоящую карту:
- запись в свойства (0x9205) меняет их текущее значение — режим фото/видео, «куда сохранять» и т. п.;
- спуск (S1 0xD2C1, S2 0xD2C2 через 0x9207): фокус находится за 0,15 с, через 0,4 с после S2 в памяти камеры
  появляется снимок (ObjectInMemory 0xD215 > 0x8000): JPEG, при FileFormat 0xD253 = 2 — ещё и ARW;
- кнопка записи (0xD2C8) — как у ZV-E10, «удержание»: «нажать» начинает запись, «отпустить» останавливает, каждое —
  через 0,8 с (--record-delay), по MovieRecordingState 0xD21D; «отпустить» во время старта камера пропускает. Как она
  отвечает на нажатие и отпускание и не бросает ли запись сама — --record (сценарии в RECORD_SCENARIOS);
- цветовая температура 0xD20F открыта только в режиме баланса белого «Цвет. темп.», как у настоящей камеры.

Несколько камер сразу (scripts/fake_cameras.py) различаются: --name, --serial (по нему программа отличает камеры),
--mark (другой план сцены — zoom, pan — или кадр отражён и повёрнут), --battery, --mode. --ssdp — отвечает на поиск в сети, как камера Sony, с портом
в X-PTPIP-Port; --busy — не отвечает на приветствие; --pairing — незнакомому GUID отвечает через 3 с, как будто на
камере нажали «ОК»; --reject iso,… — запись в эти свойства камера принимает, а значение не меняет.

  scripts/fake_camera.py [--listen 127.0.0.1:15740] [--photo снимок.jpg] [--rate байт/с] [--ignore-record]
                         [--name ZV-E10] [--serial …] [--mark zoom|pan|flip|flop|rotate] [--picture кадр.jpg]
                         [--battery 0–100] [--mode photo|video] [--ssdp] [--busy] [--pairing] [--reject iso,…]
                         [--record-delay с] [--record сценарий]
  ZV_PTPIP_HOST=127.0.0.1 …/ZVViewer --automation        # Mac
  am start … --es ptpipHost <IP Mac>                       # планшет: --listen <IP Mac>:15740
  scripts/badnet.py --camera 127.0.0.1:25740 …             # или через прокси: --listen 127.0.0.1:25740
"""
import argparse, asyncio, os, socket, struct, subprocess, sys, tempfile, time, uuid

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "Tests/ZVCoreTests/Fixtures")
# картинка в видоискателе — горное озеро (scripts/make-viewfinder-picture.py); нет её — кадр настоящей камеры из дампа
ASSETS = os.path.join(ROOT, "scripts/assets")
VIEWS = {"zoom": "viewfinder-zoom.jpg", "pan": "viewfinder-pan.jpg"}      # другие планы той же сцены
NAME = "ZV-E10"
FRAME_PERIOD = 0.040
OK, ACCESS_DENIED, INVALID_HANDLE, DEVICE_BUSY = 0x2001, 0x200F, 0x2009, 0x2019
LIVE_VIEW, CAPTURED = 0xFFFFC002, 0xFFFFC001
PROPERTY_CHANGED = 0xC203
S1, S2, MOVIE_REC = 0xD2C1, 0xD2C2, 0xD2C8
FOCUS_FOUND, OBJECT_IN_MEMORY, RECORDING, FILE_FORMAT = 0xD213, 0xD215, 0xD21D, 0xD253
EXPOSURE_PROGRAM, BATTERY = 0x500E, 0xD218
WHITE_BALANCE, COLOR_TEMP, MANUAL_WB = 0x5005, 0xD20F, 0x8012  # температура открыта только в «Цвет. темп.»
MODES = {"photo": 0x1, "video": 0x78053}                   # буква M в фото и в видео
SSDP_GROUP, SSDP_PORT = "239.255.255.250", 1900
SSDP_ST = "urn:schemas-sony-com:service:DigitalImaging:1"
MARKS = {"flip": ["-f", "horizontal"], "flop": ["-f", "vertical"], "rotate": ["-r", "180"]}
# --reject: запись в эти свойства камера принимает, а значение не меняет — как настоящая, когда не может
REJECTABLE = {"iso": 0xD21E, "shutter": 0xD20D, "aperture": 0x5007, "whiteBalance": 0x5005, "exposureProgram": 0x500E}
# --record: как камера отвечает на кнопку записи — «нажать» (2) и «отпустить» (1) — и не бросает ли запись сама
RECORD_SCENARIOS = {
    "normal": "запись начинается и останавливается через --record-delay",
    "blip-fast": "начинает и через 30 мс сама бросает запись — быстрее, чем программа перечитывает свойства",
    "blip-slow": "начинает и через 1,5 с сама бросает запись",
    "stop-later": "начинает и через 5 с сама останавливает запись — как при полной карте или перегреве",
    "press-busy": "на «нажать» отвечает 0x2019 Device Busy и ничего не делает",
    "press-busy-blip": "на «нажать» отвечает 0x2019, но запись всё равно начинается и через 0,4 с обрывается",
    "press-slow": "на «нажать» отвечает через 3 с (занята), потом начинает запись",
    "release-busy": "«нажать» — как обычно, на «отпустить» отвечает 0x2019; запись идёт",
    "release-busy-blip": "на «отпустить» отвечает 0x2019, запись начинается и через 0,4 с обрывается",
    "release-race": "«отпустить», пришедшее во время старта, выполняет, как только запись пойдёт: запись начинается и "
                    "через полсекунды останавливается — так настоящая ZV-E10 делает примерно в каждом четвёртом случае",
}
# размеры значений по типу PTP: INT8…UINT128
SIZES = {1: 1, 2: 1, 3: 2, 4: 2, 5: 4, 6: 4, 7: 8, 8: 8, 9: 16, 10: 16}
FORMATS = {1: "<b", 2: "<B", 3: "<h", 4: "<H", 5: "<i", 6: "<I", 7: "<q", 8: "<Q"}


def fixture(name):
    return open(os.path.join(FIX, name), "rb").read()


def frames(mark=None, picture=None):
    """Ответы GetObject 0xFFFFC002 для высокого и низкого качества: заголовок Sony, JPEG, добивка до размера.
    mark — другой план сцены (zoom, pan) или кадр отражён и повёрнут: у каждой из нескольких камер своя картинка;
    picture — свой JPEG 1024×576 вместо сцены."""
    raw = fixture("lv_frame_raw.bin")
    offset, size = struct.unpack_from("<II", raw)
    path = picture or os.path.join(ASSETS, VIEWS.get(mark, "viewfinder.jpg"))
    jpeg_high = open(path, "rb").read() if os.path.exists(path) else raw[offset:offset + size]
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "high.jpg"), os.path.join(tmp, "low.jpg")
        open(src, "wb").write(jpeg_high)
        if mark in MARKS:
            subprocess.run(["sips", *MARKS[mark], src, "--out", src], capture_output=True, check=True)
            jpeg_high = open(src, "rb").read()
        subprocess.run(["sips", "-z", "360", "640", src, "--out", dst], capture_output=True, check=True)
        jpeg_low = open(dst, "rb").read()

    def build(jpeg, total):
        header = bytearray(raw[:offset])
        struct.pack_into("<II", header, 0, offset, len(jpeg))
        body = bytes(header) + jpeg
        return body + b"\0" * max(0, total - len(body))

    return jpeg_high, {2: build(jpeg_high, 72 * 1024), 1: build(jpeg_low, 34 * 1024)}


def big_photo(jpeg):
    """Снимок «как с камеры»: кадр live view 16:9, обрезанный по центру до 3:2 и увеличенный до 6000×4000 в наилучшем
    качестве (несколько МБ)."""
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "lv.jpg"), os.path.join(tmp, "photo.jpg")
        open(src, "wb").write(jpeg)
        subprocess.run(["sips", "-c", "576", "864", src, "--out", src], capture_output=True, check=True)
        subprocess.run(["sips", "-z", "4000", "6000", "-s", "formatOptions", "100", src, "--out", dst],
                       capture_output=True, check=True)
        return open(dst, "rb").read()


def ptp_string(text):
    return bytes([len(text) + 1]) + text.encode("utf-16-le") + b"\0\0" if text else b"\0"


def with_serial(info, serial):
    """DeviceInfo с другим серийным номером — он последняя строка набора (PTP DeviceInfo)."""
    pos = 2 + 4 + 2
    pos += 1 + 2 * info[pos]                                # описание расширения производителя
    pos += 2                                                # FunctionalMode
    for _ in range(5):                                      # операции, события, свойства, форматы
        pos += 4 + 2 * struct.unpack_from("<I", info, pos)[0]
    for _ in range(3):                                      # производитель, модель, версия
        pos += 1 + 2 * info[pos]
    end = pos + 1 + 2 * info[pos]
    return info[:pos] + ptp_string(serial) + info[end:]


class SSDPResponder(asyncio.DatagramProtocol):
    """Отвечает на M-SEARCH службы DigitalImaging, как камера Sony в PC Remote; порт PTP/IP — в X-PTPIP-Port."""

    def __init__(self, cam, host, port):
        self.cam, self.host, self.port = cam, host, port

    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        text = data.decode("utf-8", "replace")
        if not text.startswith("M-SEARCH"):
            return
        headers = {k.strip().upper(): v.strip() for k, _, v in (l.partition(":") for l in text.split("\r\n")[1:]) if v}
        if headers.get("ST") not in (SSDP_ST, "ssdp:all"):
            return
        # камера на 127.0.0.1 — только для этого Mac: планшет по такому адресу стучался бы сам к себе
        if self.host.startswith("127.") and not addr[0].startswith("127.") and addr[0] not in local_addresses():
            return
        reply = ("HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nEXT:\r\n"
                 f"LOCATION: http://{self.host}:{self.port}/fake_camera.xml\r\nSERVER: fake_camera.py UPnP/1.0\r\n"
                 f"ST: {SSDP_ST}\r\nUSN: uuid:{self.cam.uuid}::{SSDP_ST}\r\nX-PTPIP-Port: {self.port}\r\n\r\n")
        self.transport.sendto(reply.encode(), addr)


def local_addresses():
    """Адреса этого Mac: поиск с них — свой, ответ с 127.0.0.1 ему годится."""
    try:
        return {info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
    except OSError:
        return set()


def ssdp_socket():
    """Многоадресная группа SSDP; несколько камер на одном Mac делят порт 1900 (SO_REUSEPORT)."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)
    sock.bind(("", SSDP_PORT))
    group = struct.pack("4s4s", socket.inet_aton(SSDP_GROUP), socket.inet_aton("0.0.0.0"))
    sock.setsockopt(socket.IPPROTO_IP, socket.IP_ADD_MEMBERSHIP, group)
    return sock


class Properties:
    """Ответ 0x9209 и где в нём лежат текущие значения: чтобы менять их на месте, как камера."""

    def __init__(self, blob):
        self.blob = bytearray(blob)
        self.current = {}                                   # код → (смещение, тип)
        self.flags = {}                                     # код → смещение байта «включено»
        pos = 8
        while pos < len(self.blob):
            start = pos
            code, ptype = struct.unpack_from("<HH", self.blob, pos)
            pos += 6                                        # код, тип, get/set, включено
            pos = self._skip(ptype, pos)                    # значение по умолчанию
            if ptype == 0xFFFF and pos >= len(self.blob):
                break
            self.current[code] = (pos, ptype)
            self.flags[code] = start + 5
            pos = self._skip(ptype, pos)
            if pos >= len(self.blob):
                break
            form = self.blob[pos]
            pos += 1
            if form == 1:
                for _ in range(3):
                    pos = self._skip(ptype, pos)
            elif form == 2:
                pos = self._list(ptype, pos)
                # у части свойств следом второй список; иначе дальше код следующего свойства (0x5xxx/0xDxxx)
                if pos + 2 <= len(self.blob) and struct.unpack_from("<H", self.blob, pos)[0] < 0x200:
                    pos = self._list(ptype, pos)

    def _skip(self, ptype, pos):
        if ptype == 0xFFFF:
            return pos + 1 + 2 * self.blob[pos]
        return pos + SIZES.get(ptype, 0)

    def _list(self, ptype, pos):
        count = struct.unpack_from("<H", self.blob, pos)[0]
        pos += 2
        for _ in range(count):
            pos = self._skip(ptype, pos)
        return pos

    def get(self, code):
        pos, ptype = self.current[code]
        return struct.unpack_from(FORMATS[ptype], self.blob, pos)[0]

    def set(self, code, value):
        """Записать текущее значение; False — такого числового свойства нет."""
        if code not in self.current or self.current[code][1] not in FORMATS:
            return False
        pos, ptype = self.current[code]
        fmt = FORMATS[ptype]
        size = struct.calcsize(fmt)
        value &= (1 << (8 * size)) - 1
        if fmt[1].islower() and value >= 1 << (8 * size - 1):
            value -= 1 << (8 * size)
        struct.pack_into(fmt, self.blob, pos, value)
        return True

    def enabled(self, code):
        return code not in self.flags or self.blob[self.flags[code]] == 1

    def enable(self, code, on):
        """Открыть или закрыть свойство (байт «включено»): так камера открывает температуру в «Цвет. темп.»."""
        if code in self.flags:
            self.blob[self.flags[code]] = 1 if on else 0

    def set_raw(self, code, data):
        if code not in self.current or self.current[code][1] not in FORMATS:
            return False
        fmt = FORMATS[self.current[code][1]]
        if len(data) < struct.calcsize(fmt):
            return False
        return self.set(code, struct.unpack_from(fmt, data)[0])


class Camera:
    def __init__(self, args):
        jpeg, self.frames = frames(args.mark, args.picture)
        self.photo = open(args.photo, "rb").read() if args.photo else big_photo(jpeg)
        self.rate = args.rate
        self.ignore_record = args.ignore_record
        self.record_delay = args.record_delay
        self.record_scenario = args.record
        self.reply_delay = 0.0                              # ответ на текущую операцию — через столько секунд
        self.starting = False                               # «нажать» пришло, запись ещё не пошла
        self.release_after_start = False                    # release-race: «отпустить» выполнить, как запись пойдёт
        # ноль часов журнала; FAKE_CAMERA_CLOCK — time.monotonic() того, кто запустил, чтобы сверять по одним часам
        self.started = float(os.environ.get("FAKE_CAMERA_CLOCK") or time.monotonic())
        self.name = args.name
        self.busy = args.busy
        self.pairing = args.pairing
        self.known_guids = set()                            # кого «сопрягли» (--pairing)
        self.reject = {REJECTABLE[name] for name in (args.reject.split(",") if args.reject else [])}
        self.quality = 2
        self.last_frame = 0.0
        self.conn = 0
        self.device_info = fixture("deviceinfo.bin")
        if args.serial:
            self.device_info = with_serial(self.device_info, args.serial)
        # свой постоянный uuid (в поиске) и GUID (в приветствии) — от серийного номера
        self.uuid = uuid.uuid5(uuid.NAMESPACE_OID, "fake-zv-e10:" + (args.serial or "")).hex
        self.guid = uuid.UUID(self.uuid).bytes
        self.ext_info = fixture("sony_extdeviceinfo.bin")
        self.props = Properties(fixture("props_settled.bin"))
        self.props.set(OBJECT_IN_MEMORY, 0x8000)
        self.props.set(RECORDING, 0)
        if args.battery is not None:
            self.props.set(BATTERY, args.battery)
        if args.mode:
            self.props.set(EXPOSURE_PROGRAM, MODES[args.mode])
        self.props.enable(COLOR_TEMP, self.props.get(WHITE_BALANCE) == MANUAL_WB)
        self.events = []                                    # писатели соединений событий
        self.objects = []                                   # снимки в памяти камеры: (имя, данные)
        self.number = 1000

    def changed(self, code, value=None):
        """Свойство поменялось: записать и сообщить программе событием, как камера."""
        if value is not None:
            self.props.set(code, value)
        for w in list(self.events):
            try:
                w.write(pkt(8, struct.pack("<HII", PROPERTY_CHANGED, 0xFFFFFFFF, code)))
            except Exception:
                self.events.remove(w)

    def later(self, seconds, fn):
        asyncio.get_running_loop().call_later(seconds, fn)

    def log(self, text):
        """С секундами от запуска: по ним сверяется порядок с тем, что видела программа."""
        print(f"{time.monotonic() - self.started:8.3f}  {text}", flush=True)

    def control(self, code, value):
        """Команда 0x9207; возвращает код ответа."""
        if code == S1 and value == 2:
            self.later(0.15, lambda: self.changed(FOCUS_FOUND, 2))
        elif code == S1 and value == 1:
            self.later(0.05, lambda: self.changed(FOCUS_FOUND, 1))
        elif code == S2 and value == 2:
            self.later(0.4, self.shoot)
        elif code == MOVIE_REC and value == 2:
            if self.ignore_record:
                self.log("кнопка записи — не реагирую (--ignore-record)")
                return OK
            return self.record_press()
        elif code == MOVIE_REC and value == 1:
            return self.record_release()
        return OK

    def record_press(self):
        """«Нажать» 0xD2C8 — как у ZV-E10, удержание кнопки: не пишет — начать (через --record-delay); пишет — ничего."""
        scenario = self.record_scenario
        if scenario == "press-busy":
            self.log("«нажать» запись → 0x2019, ничего не делаю")
            return DEVICE_BUSY
        if self.props.get(RECORDING) == 1 or self.starting:
            self.log("«нажать» запись — уже пишу, ничего")
            return OK
        delay = self.record_delay
        if scenario == "press-slow":
            self.reply_delay = 3.0
            delay += 3.0
        self.starting = True
        self.later(delay, self.started_recording)
        if scenario in ("blip-fast", "blip-slow", "stop-later", "press-busy-blip", "release-busy-blip"):
            lasts = {"blip-fast": 0.03, "blip-slow": 1.5, "stop-later": 5.0}.get(scenario, 0.4)
            self.later(delay + lasts, lambda: self.set_recording(0, "сама остановила"))
        if scenario == "press-busy-blip":
            self.log("«нажать» запись → 0x2019, но запись начнётся")
            return DEVICE_BUSY
        self.log("«нажать» запись" + (" — отвечу через 3 с" if self.reply_delay else ""))
        return OK

    def started_recording(self):
        self.starting = False
        self.set_recording(1)
        if self.release_after_start:
            self.release_after_start = False
            self.later(0.5, lambda: self.set_recording(0, "«отпустить», пришедшее во время старта"))

    def record_release(self):
        """«Отпустить»: пишет — остановить (через --record-delay); во время старта камера обычно пропускает."""
        if self.record_scenario in ("release-busy", "release-busy-blip"):
            self.log("«отпустить» запись → 0x2019")
            return DEVICE_BUSY
        if self.starting:
            if self.record_scenario == "release-race":
                self.release_after_start = True
                self.log("«отпустить» во время старта — выполню, как только запись пойдёт")
            else:
                self.log("«отпустить» во время старта — пропускаю")
            return OK
        if self.props.get(RECORDING) == 1:
            self.later(self.record_delay, lambda: self.set_recording(0))
            self.log("«отпустить» запись — останавливаю")
        else:
            self.log("«отпустить» запись — не пишу, ничего")
        return OK

    def set_recording(self, value, why=""):
        if self.props.get(RECORDING) == value:
            return
        self.changed(RECORDING, value)
        self.log(f"запись {'началась' if value else 'остановлена'}" + (f" — {why}" if why else ""))

    def shoot(self):
        self.number += 1
        name = f"DSC0{self.number}"
        files = [(name + ".JPG", self.photo)]
        if self.props.current.get(FILE_FORMAT) and self.props.get(FILE_FORMAT) == 2:
            files.insert(0, (name + ".ARW", os.urandom(3 * 1024 * 1024)))
        self.objects += files
        print(f"снимок {name}: {', '.join(f'{n} {len(d) // 1024} КБ' for n, d in files)}", flush=True)
        self.changed(OBJECT_IN_MEMORY, 0x8000 + len(self.objects))

    def object_info(self):
        name, data = self.objects[0]
        fmt = 0x3801 if name.endswith(".JPG") else 0xB101
        text = name.encode("utf-16-le") + b"\0\0"
        info = struct.pack("<IHHIH", 0x10001, fmt, 0, len(data), 0) + b"\0" * 28 + struct.pack("<HII", 0, 0, 0)
        return info + bytes([len(name) + 1]) + text + b"\0\0\0"


def pkt(ptype, payload=b""):
    return struct.pack("<II", 8 + len(payload), ptype) + payload


async def read_pkt(r):
    length, ptype = struct.unpack("<II", await r.readexactly(8))
    return ptype, await r.readexactly(length - 8)


async def send_data(cam, w, tid, reply, limited):
    """Фаза данных: Start Data, Data…, End Data. Снимок — кусками и с ограничением скорости, если задано."""
    w.write(pkt(9, struct.pack("<IQ", tid, len(reply))))
    if not limited or not cam.rate:
        w.write(pkt(12, struct.pack("<I", tid) + reply))
        return
    chunk = 64 * 1024
    for start in range(0, len(reply), chunk):
        part = reply[start:start + chunk]
        last = start + chunk >= len(reply)
        w.write(pkt(12 if last else 10, struct.pack("<I", tid) + part))
        await w.drain()
        await asyncio.sleep(len(part) / cam.rate)


async def serve(cam, r, w):
    try:
        while True:
            ptype, body = await read_pkt(r)
            if ptype == 1:                                   # Init Command Request
                if cam.busy:                                 # держит прошлое подключение: молчит
                    print("приветствие — молчу (--busy)", flush=True)
                    continue
                guid = body[:16]
                if cam.pairing and guid not in cam.known_guids:
                    print("незнакомый GUID — «нажимаю ОК» через 3 с (--pairing)", flush=True)
                    await asyncio.sleep(3)
                    cam.known_guids.add(guid)
                cam.conn += 1
                print(f"программа подключилась (соединение {cam.conn})", flush=True)
                name = cam.name.encode("utf-16-le") + b"\0\0"
                w.write(pkt(2, struct.pack("<I", cam.conn) + cam.guid + name + struct.pack("<I", 0x10000)))
            elif ptype == 3:                                 # Init Event Request
                w.write(pkt(4))
                cam.events.append(w)
            elif ptype == 13:                                # Probe Request
                w.write(pkt(14))
            elif ptype == 6:                                 # Operation Request
                phase, code, tid = struct.unpack_from("<IHI", body)
                params = list(struct.unpack_from("<%dI" % ((len(body) - 10) // 4), body, 10))
                out = b""
                while phase == 2:                            # данные к камере: Start Data, затем End Data
                    p2, b2 = await read_pkt(r)
                    if p2 == 13:
                        w.write(pkt(14))
                    elif p2 in (10, 12) and struct.unpack_from("<I", b2)[0] == tid:
                        out += b2[4:]
                        if p2 == 12:
                            break
                reply, rc = respond(cam, code, params, out)
                if cam.reply_delay:                          # камера занята: ответ — позже, канал команд ждёт
                    delay, cam.reply_delay = cam.reply_delay, 0.0
                    await asyncio.sleep(delay)
                if reply is not None:
                    await send_data(cam, w, tid, reply, limited=code == 0x1009 and params[:1] == [CAPTURED])
                    if code == 0x1009 and params[:1] == [CAPTURED]:
                        cam.objects.pop(0)
                        cam.changed(OBJECT_IN_MEMORY, 0x8000 + len(cam.objects))
                w.write(pkt(7, struct.pack("<HI", rc, tid)))
            await w.drain()
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        pass
    finally:
        if w in cam.events:
            cam.events.remove(w)
        w.close()


def respond(cam, code, params, out):
    if code == 0x1009 and params and params[0] == LIVE_VIEW:    # кадр live view: не чаще ~25 к/с
        now = time.monotonic()
        if now - cam.last_frame < FRAME_PERIOD:
            return None, ACCESS_DENIED
        cam.last_frame = now
        return cam.frames[cam.quality], OK
    if code in (0x1008, 0x1009) and params and params[0] == CAPTURED:
        if not cam.objects:
            return None, INVALID_HANDLE
        return (cam.object_info() if code == 0x1008 else cam.objects[0][1]), OK
    if code == 0x1001:
        return cam.device_info, OK
    if code == 0x9202:
        return cam.ext_info, OK
    if code == 0x9209:
        return bytes(cam.props.blob), OK
    if code == 0x9205 and params and out:
        if params[0] == 0xD26A:
            cam.quality = 1 if out[0] == 1 else 2
            cam.log(f"качество превью → {'низкое' if cam.quality == 1 else 'высокое'}"
                    + (" (идёт запись)" if cam.props.get(RECORDING) == 1 else ""))
        elif params[0] in cam.reject:
            print(f"запись 0x{params[0]:04X} — не меняю (--reject)", flush=True)
        elif not cam.props.enabled(params[0]):
            print(f"запись 0x{params[0]:04X} — свойство сейчас закрыто, не меняю", flush=True)
        elif cam.props.set_raw(params[0], out):
            value = cam.props.get(params[0])
            print(f"записано 0x{params[0]:04X} = {value} (0x{value & 0xFFFFFFFF:X})", flush=True)
            cam.changed(params[0])
            if params[0] == WHITE_BALANCE:
                cam.props.enable(COLOR_TEMP, cam.props.get(WHITE_BALANCE) == MANUAL_WB)
                cam.changed(COLOR_TEMP)
    if code == 0x9207 and params and len(out) >= 2:
        return None, cam.control(params[0], struct.unpack_from("<H", out)[0])
    return None, OK


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:15740")
    ap.add_argument("--photo", help="JPEG, который камера отдаёт как снимок (по умолчанию — растянутый кадр live view)")
    ap.add_argument("--rate", type=int, default=0, help="скорость отдачи снимка, байт/с (0 — без ограничения)")
    ap.add_argument("--ignore-record", action="store_true", help="не реагировать на кнопку записи")
    ap.add_argument("--record-delay", type=float, default=0.8, help="через сколько секунд запись начинается и останавливается")
    ap.add_argument("--record", choices=list(RECORD_SCENARIOS), default="normal",
                    help="как камера отвечает на кнопку записи: " + "; ".join(f"{k} — {v}" for k, v in RECORD_SCENARIOS.items()))
    ap.add_argument("--name", default=NAME, help="имя камеры в ответе на приветствие")
    ap.add_argument("--serial", help="серийный номер в DeviceInfo (по нему программа отличает камеры)")
    ap.add_argument("--mark", choices=sorted(set(MARKS) | set(VIEWS)),
                    help="другой план сцены (zoom, pan), кадр отражён (flip/flop) или повёрнут (rotate)")
    ap.add_argument("--picture", help="свой JPEG 1024×576 в видоискателе вместо горного озера")
    ap.add_argument("--battery", type=int, help="заряд батареи, %%")
    ap.add_argument("--mode", choices=sorted(MODES), help="режим на старте: фото или видео (буква M)")
    ap.add_argument("--ssdp", action="store_true", help="отвечать на поиск в сети (SSDP), как камера Sony")
    ap.add_argument("--busy", action="store_true", help="не отвечать на приветствие — камера занята")
    ap.add_argument("--pairing", action="store_true", help="незнакомому GUID отвечать через 3 с — сопряжение")
    ap.add_argument("--reject", help="свойства, запись в которые не меняет значение: " + ",".join(REJECTABLE))
    a = ap.parse_args()
    cam = Camera(a)
    host, port = a.listen.rsplit(":", 1)
    server = await asyncio.start_server(lambda r, w: serve(cam, r, w), host, int(port))
    if a.ssdp:
        await asyncio.get_running_loop().create_datagram_endpoint(lambda: SSDPResponder(cam, host, int(port)),
                                                                  sock=ssdp_socket())
    extras = [f"№ …{a.serial[-4:]}" if a.serial else "", a.mark or "", f"батарея {a.battery}%" if a.battery is not None else "",
              a.mode or "", "SSDP" if a.ssdp else "", "занята" if a.busy else "", "сопряжение" if a.pairing else "",
              f"не меняет {a.reject}" if a.reject else "", f"запись: {a.record}" if a.record != "normal" else ""]
    print(f"поддельная {cam.name}: {a.listen}, кадр {len(cam.frames[2]) // 1024} / {len(cam.frames[1]) // 1024} КБ, "
          f"снимок {len(cam.photo) // 1024} КБ" + "".join(f", {e}" for e in extras if e), flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
