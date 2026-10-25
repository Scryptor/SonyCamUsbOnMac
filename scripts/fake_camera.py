#!/usr/bin/env python3
"""fake_camera.py — поддельная ZV-E10 по PTP/IP из настоящих ответов камеры (Tests/ZVCoreTests/Fixtures).

Нужна, чтобы гонять логику связи обеих программ через scripts/badnet.py без живой камеры: приветствие,
рукопожатие Sony, свойства, пробы, кадры live view ~25 к/с. Качество превью 0xD26A меняет кадр, как настоящая
камера по Wi-Fi: высокое — 1024×576, дополнено до ~72 КБ; низкое — 640×360, дополнено до ~34 КБ.

  scripts/fake_camera.py [--listen 127.0.0.1:25740]
  scripts/badnet.py --camera 127.0.0.1:25740 …     # программа → прокси → поддельная камера
"""
import argparse, asyncio, os, struct, subprocess, sys, tempfile, time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FIX = os.path.join(ROOT, "Tests/ZVCoreTests/Fixtures")
NAME = "ZV-E10"
FRAME_PERIOD = 0.040
OK, ACCESS_DENIED = 0x2001, 0x200F


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

    return {2: build(jpeg_high, 72 * 1024), 1: build(jpeg_low, 34 * 1024)}


class Camera:
    def __init__(self):
        self.frames = frames()
        self.quality = 2
        self.last_frame = 0.0
        self.conn = 0
        self.device_info = fixture("deviceinfo.bin")
        self.ext_info = fixture("sony_extdeviceinfo.bin")
        self.props = fixture("props_settled.bin")


def pkt(ptype, payload=b""):
    return struct.pack("<II", 8 + len(payload), ptype) + payload


async def read_pkt(r):
    length, ptype = struct.unpack("<II", await r.readexactly(8))
    return ptype, await r.readexactly(length - 8)


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
                    w.write(pkt(9, struct.pack("<IQ", tid, len(reply))))
                    w.write(pkt(12, struct.pack("<I", tid) + reply))
                w.write(pkt(7, struct.pack("<HI", rc, tid)))
            await w.drain()
    except (asyncio.IncompleteReadError, ConnectionError, OSError):
        pass
    finally:
        w.close()


def respond(cam, code, params, out):
    if code == 0x1009 and params and params[0] == 0xFFFFC002:   # кадр live view: не чаще ~25 к/с
        now = time.monotonic()
        if now - cam.last_frame < FRAME_PERIOD:
            return None, ACCESS_DENIED
        cam.last_frame = now
        return cam.frames[cam.quality], OK
    if code == 0x1001:
        return cam.device_info, OK
    if code == 0x9202:
        return cam.ext_info, OK
    if code == 0x9209:
        return cam.props, OK
    if code == 0x9205 and params and params[0] == 0xD26A and out:
        cam.quality = 1 if out[0] == 1 else 2
        print(f"качество превью → {'низкое' if cam.quality == 1 else 'высокое'}", flush=True)
    return None, OK


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="127.0.0.1:25740")
    a = ap.parse_args()
    cam = Camera()
    host, port = a.listen.rsplit(":", 1)
    server = await asyncio.start_server(lambda r, w: serve(cam, r, w), host, int(port))
    print(f"поддельная {NAME}: {a.listen}, кадр {len(cam.frames[2]) // 1024} / {len(cam.frames[1]) // 1024} КБ", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
