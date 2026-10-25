#!/usr/bin/env python3
"""fake_camera.py — поддельная ZV-E10 по PTP/IP из настоящих ответов камеры (Tests/ZVCoreTests/Fixtures).

Нужна, чтобы гонять обе программы без живой камеры: приветствие, рукопожатие Sony, свойства, пробы, кадры live view
~25 к/с, события 0xC203. Качество превью 0xD26A меняет кадр, как настоящая камера по Wi-Fi: высокое — 1024×576,
дополнено до ~72 КБ; низкое — 640×360, дополнено до ~34 КБ.

Снимает и пишет, ничего не записывая на настоящую карту:
- запись в свойства (0x9205) меняет их текущее значение — режим фото/видео, «куда сохранять» и т. п.;
- спуск (S1 0xD2C1, S2 0xD2C2 через 0x9207): фокус находится за 0,15 с, через 0,4 с после S2 в памяти камеры
  появляется снимок (ObjectInMemory 0xD215 > 0x8000): JPEG, при FileFormat 0xD253 = 2 — ещё и ARW;
- кнопка записи (0xD2C8): через 0,8 с MovieRecordingState 0xD21D меняется 0 ↔ 1.

  scripts/fake_camera.py [--listen 127.0.0.1:15740] [--photo снимок.jpg] [--rate байт/с] [--ignore-record]
  ZV_PTPIP_HOST=127.0.0.1 …/ZVViewer --automation        # Mac
  am start … --es ptpipHost <IP Mac>                       # планшет: --listen <IP Mac>:15740
  scripts/badnet.py --camera 127.0.0.1:25740 …             # или через прокси: --listen 127.0.0.1:25740
"""
import argparse, asyncio, os, struct, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "Tests/ZVCoreTests/Fixtures")
NAME = "ZV-E10"
FRAME_PERIOD = 0.040
OK, ACCESS_DENIED, INVALID_HANDLE = 0x2001, 0x200F, 0x2009
LIVE_VIEW, CAPTURED = 0xFFFFC002, 0xFFFFC001
PROPERTY_CHANGED = 0xC203
S1, S2, MOVIE_REC = 0xD2C1, 0xD2C2, 0xD2C8
FOCUS_FOUND, OBJECT_IN_MEMORY, RECORDING, FILE_FORMAT = 0xD213, 0xD215, 0xD21D, 0xD253
# размеры значений по типу PTP: INT8…UINT128
SIZES = {1: 1, 2: 1, 3: 2, 4: 2, 5: 4, 6: 4, 7: 8, 8: 8, 9: 16, 10: 16}
FORMATS = {1: "<b", 2: "<B", 3: "<h", 4: "<H", 5: "<i", 6: "<I", 7: "<q", 8: "<Q"}


def fixture(name):
    return open(os.path.join(FIX, name), "rb").read()


def frames():
    """Ответы GetObject 0xFFFFC002 для высокого и низкого качества: заголовок Sony, JPEG, добивка до размера."""
    raw = fixture("lv_frame_raw.bin")
    offset, size = struct.unpack_from("<II", raw)
    jpeg_high = raw[offset:offset + size]
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "high.jpg"), os.path.join(tmp, "low.jpg")
        open(src, "wb").write(jpeg_high)
        subprocess.run(["sips", "-z", "360", "640", src, "--out", dst], capture_output=True, check=True)
        jpeg_low = open(dst, "rb").read()

    def build(jpeg, total):
        header = bytearray(raw[:offset])
        struct.pack_into("<II", header, 0, offset, len(jpeg))
        body = bytes(header) + jpeg
        return body + b"\0" * max(0, total - len(body))

    return jpeg_high, {2: build(jpeg_high, 72 * 1024), 1: build(jpeg_low, 34 * 1024)}


def big_photo(jpeg):
    """Снимок «как с камеры»: кадр live view, растянутый до 6000×4000 в наилучшем качестве (несколько МБ)."""
    with tempfile.TemporaryDirectory() as tmp:
        src, dst = os.path.join(tmp, "lv.jpg"), os.path.join(tmp, "photo.jpg")
        open(src, "wb").write(jpeg)
        subprocess.run(["sips", "-z", "4000", "6000", "-s", "formatOptions", "100", src, "--out", dst],
                       capture_output=True, check=True)
        return open(dst, "rb").read()


class Properties:
    """Ответ 0x9209 и где в нём лежат текущие значения: чтобы менять их на месте, как камера."""

    def __init__(self, blob):
        self.blob = bytearray(blob)
        self.current = {}                                   # код → (смещение, тип)
        pos = 8
        while pos < len(self.blob):
            code, ptype = struct.unpack_from("<HH", self.blob, pos)
            pos += 6                                        # код, тип, get/set, включено
            pos = self._skip(ptype, pos)                    # значение по умолчанию
            if ptype == 0xFFFF and pos >= len(self.blob):
                break
            self.current[code] = (pos, ptype)
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

    def set_raw(self, code, data):
        if code not in self.current or self.current[code][1] not in FORMATS:
            return False
        fmt = FORMATS[self.current[code][1]]
        if len(data) < struct.calcsize(fmt):
            return False
        return self.set(code, struct.unpack_from(fmt, data)[0])


class Camera:
    def __init__(self, args):
        jpeg, self.frames = frames()
        self.photo = open(args.photo, "rb").read() if args.photo else big_photo(jpeg)
        self.rate = args.rate
        self.ignore_record = args.ignore_record
        self.quality = 2
        self.last_frame = 0.0
        self.conn = 0
        self.device_info = fixture("deviceinfo.bin")
        self.ext_info = fixture("sony_extdeviceinfo.bin")
        self.props = Properties(fixture("props_settled.bin"))
        self.props.set(OBJECT_IN_MEMORY, 0x8000)
        self.props.set(RECORDING, 0)
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

    def control(self, code, value):
        loop_print = lambda text: print(text, flush=True)
        if code == S1 and value == 2:
            self.later(0.15, lambda: self.changed(FOCUS_FOUND, 2))
        elif code == S1 and value == 1:
            self.later(0.05, lambda: self.changed(FOCUS_FOUND, 1))
        elif code == S2 and value == 2:
            self.later(0.4, self.shoot)
        elif code == MOVIE_REC and value == 2:
            if self.ignore_record:
                loop_print("кнопка записи — не реагирую (--ignore-record)")
                return
            target = 0 if self.props.get(RECORDING) == 1 else 1
            self.later(0.8, lambda: (self.changed(RECORDING, target),
                                     loop_print(f"запись {'началась' if target else 'остановлена'}")))

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
                cam.conn += 1
                name = NAME.encode("utf-16-le") + b"\0\0"
                w.write(pkt(2, struct.pack("<I", cam.conn) + b"\x11" * 16 + name + struct.pack("<I", 0x10000)))
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
            print(f"качество превью → {'низкое' if cam.quality == 1 else 'высокое'}", flush=True)
        elif cam.props.set_raw(params[0], out):
            cam.changed(params[0])
    if code == 0x9207 and params and len(out) >= 2:
        cam.control(params[0], struct.unpack_from("<H", out)[0])
    return None, OK


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:15740")
    ap.add_argument("--photo", help="JPEG, который камера отдаёт как снимок (по умолчанию — растянутый кадр live view)")
    ap.add_argument("--rate", type=int, default=0, help="скорость отдачи снимка, байт/с (0 — без ограничения)")
    ap.add_argument("--ignore-record", action="store_true", help="не реагировать на кнопку записи")
    a = ap.parse_args()
    cam = Camera(a)
    host, port = a.listen.rsplit(":", 1)
    server = await asyncio.start_server(lambda r, w: serve(cam, r, w), host, int(port))
    print(f"поддельная {NAME}: {a.listen}, кадр {len(cam.frames[2]) // 1024} / {len(cam.frames[1]) // 1024} КБ, "
          f"снимок {len(cam.photo) // 1024} КБ", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
