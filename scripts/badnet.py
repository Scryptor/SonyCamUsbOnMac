#!/usr/bin/env python3
"""badnet.py — прокси «плохой Wi-Fi» между приложением и камерой (PTP/IP, TCP 15740).

Приложение подключается к прокси вместо камеры (Mac: ZV_PTPIP_HOST=127.0.0.1,
Android debug: --es ptpipHost <IP Mac>). Прокси пересылает оба канала PTP/IP на камеру
и портит связь: полоса, задержка, джиттер, случайные замирания, пропадание связи, обрывы.
Заодно он считает кадры live view по самому протоколу, одинаково для обоих приложений.

  scripts/badnet.py --camera 192.168.1.194 [--listen 0.0.0.0:15740] [--control 127.0.0.1:15799]
                    [--link 0.0.0.0:15798] [--profile good|weak|bad|awful] [--log out.jsonl]

Датчик связи (--link, только чтение): GET /link — 200, пока «сеть есть», 503 во время провала. Программы в тестовом
режиме спрашивают его вместо ICMP ping камеры: ping прокси отвечал бы всегда, даже когда прокси «роняет» Wi-Fi.

Управление на лету (HTTP JSON на --control):
  GET  /state                         параметры, соединения, кадры/с, задержка кадра
  POST /set       {"rate_kbps":1500,"delay_ms":40,"jitter_ms":30,"stall_rate":0.1,"stall_ms":[300,2000],"lose_fin":false}
  POST /profile   {"name":"weak"}
  POST /blackhole {"seconds":10}      сеть пропала: всё замерло, данные копятся и потом доходят, /link — 503
                  {"seconds":10,"scope":"existing"}  застряли только нынешние соединения (TCP после провала),
                                      а сеть есть: новые соединения идут, /link — 200
  POST /reset     {"side":"client"}   RST приложению; камера ничего не узнаёт и держит сессию hold_s
                  {"side":"both"}     RST обеим сторонам (камера сразу освобождает сессию)
  Приложение закрыло соединение само: FIN или RST уходит камере — сразу, если сеть есть, или когда она вернётся.
  С "lose_fin": true закрытие во время замирания «теряется» (камера держит сессию hold_s).
  POST /release   {"mode":"rst"|"fin"} закрыть «осиротевшие» соединения с камерой (по умолчанию RST)
  POST /stats/reset                   обнулить счётчики кадров и замираний
"""
import argparse, asyncio, json, random, socket, struct, sys, time

PROFILES = {
    # rate_kbps: полоса от камеры к приложению (0 — без ограничения); stall_rate: замираний в секунду
    "good":  dict(rate_kbps=0,    up_rate_kbps=0, delay_ms=0,  jitter_ms=0,   stall_rate=0.0,  stall_ms=[0, 0], lose_fin=False),
    "weak":  dict(rate_kbps=4000, up_rate_kbps=0, delay_ms=15, jitter_ms=25,  stall_rate=0.05, stall_ms=[200, 1200], lose_fin=False),
    "bad":   dict(rate_kbps=1500, up_rate_kbps=0, delay_ms=40, jitter_ms=60,  stall_rate=0.1,  stall_ms=[300, 3000], lose_fin=False),
    "awful": dict(rate_kbps=600,  up_rate_kbps=0, delay_ms=80, jitter_ms=120, stall_rate=0.2,  stall_ms=[500, 6000], lose_fin=False),
}
QUEUE_LIMIT = 256 * 1024          # «буфер роутера» на направление: дальше давим камеру через TCP
CHUNK = 8 * 1024                  # шаг выдачи при ограничении полосы
LV_HANDLE = 0xFFFFC002
OP_GET_OBJECT = 0x1009


class Net:
    """Общее состояние порчи связи и статистика."""

    def __init__(self, profile):
        self.p = dict(PROFILES[profile])
        self.stall_until = 0.0
        self.hold_s = 150.0           # сколько камера держит сессию, если наш FIN не дошёл
        self.conns = []
        self.orphans = []
        self.reset_stats()

    def reset_stats(self):
        self.t0 = time.monotonic()
        self.frames = []           # (t, bytes, latency_s)
        self.denied = 0
        self.other_ops = 0
        self.stalls = []           # (t, seconds)
        self.bytes_down = 0
        self.bytes_up = 0
        self.conns_total = 0

    def stalled(self):
        return time.monotonic() < self.stall_until

    def stall(self, seconds):
        now = time.monotonic()
        if now + seconds > self.stall_until:
            self.stall_until = now + seconds
        self.stalls.append((now, seconds))

    def summary(self, window=5.0):
        now = time.monotonic()
        recent = [f for f in self.frames if now - f[0] <= window]
        lats = sorted(f[2] for f in recent)
        gaps = sorted(b[0] - a[0] for a, b in zip(self.frames, self.frames[1:]))
        span = max(1e-3, min(window, now - self.t0))

        def pct(xs, q):
            return round(xs[min(len(xs) - 1, int(q * len(xs)))] * 1000) if xs else None

        return {
            "uptime_s": round(now - self.t0, 1),
            "params": self.p,
            "stalled": self.stalled(),
            "conns": [c.describe() for c in self.conns],
            "orphans": len(self.orphans),
            "fps": round(len(recent) / span, 1),
            "frames_total": len(self.frames),
            "frame_kb_avg": round(sum(f[1] for f in recent) / len(recent) / 1024, 1) if recent else None,
            "latency_ms_p50": pct(lats, 0.5), "latency_ms_p95": pct(lats, 0.95),
            "gap_ms_p50": pct(gaps, 0.5), "gap_ms_p95": pct(gaps, 0.95), "gap_ms_p99": pct(gaps, 0.99),
            "gap_ms_max": round(gaps[-1] * 1000) if gaps else None,
            "denied_total": self.denied,
            "stalls_total": len(self.stalls),
            "mbytes_down": round(self.bytes_down / 1e6, 2),
            "conns_total": self.conns_total,
            "last_frame_age_ms": round((now - self.frames[-1][0]) * 1000) if self.frames else None,
        }


class PacketWatch:
    """Потоковый разбор заголовков PTP/IP без буферизации полезной нагрузки (пакеты до 2 МБ)."""

    def __init__(self, on_packet):
        self.on_packet = on_packet
        self.buf = bytearray()
        self.skip = 0

    def feed(self, data):
        mv = memoryview(data)
        while mv:
            if self.skip:
                n = min(self.skip, len(mv))
                self.skip -= n
                mv = mv[n:]
                continue
            need = 8 if len(self.buf) < 8 else 8 + self._fields_len()
            take = min(need - len(self.buf), len(mv))
            self.buf += mv[:take]
            mv = mv[take:]
            if len(self.buf) < need:
                continue
            if len(self.buf) == 8 and self._fields_len():
                continue                       # заголовок есть, дочитываем поля
            length, ptype = struct.unpack_from("<II", self.buf)
            if length < 8:
                self.buf.clear()               # мусор: дальше ничего не поймём
                return
            self.on_packet(ptype, bytes(self.buf[8:]))
            self.skip = max(0, length - len(self.buf))
            self.buf.clear()

    def _fields_len(self):
        length, ptype = struct.unpack_from("<II", self.buf)
        # сколько байт после заголовка нужно, чтобы понять пакет
        want = {6: 18, 7: 10, 9: 12, 10: 4, 12: 4}.get(ptype, 0)
        return min(want, max(0, length - 8))


class Pipe:
    """Одно направление: читаем источник, задерживаем, режем полосу, замираем, пишем."""

    def __init__(self, net, conn, reader, writer, down):
        self.net, self.conn, self.reader, self.writer, self.down = net, conn, reader, writer, down
        self.queue = asyncio.Queue()
        self.queued = 0
        self.space = asyncio.Event()
        self.space.set()
        self.last_deliver = 0.0
        self.next_free = 0.0
        self.watch = None
        self.dead = False

    async def pump_in(self):
        try:
            while True:
                data = await self.reader.read(64 * 1024)
                if not data:
                    if not self.down:
                        self.conn.client_closed(rst=False)
                    break
                if self.watch and not self.down:
                    self.watch.feed(data)      # запрос засекаем в момент отправки приложением
                if self.dead:
                    continue                   # получателя нет: просто сливаем
                p = self.net.p
                at = time.monotonic() + (p["delay_ms"] + random.random() * p["jitter_ms"]) / 1000
                self.last_deliver = max(self.last_deliver, at)
                await self.queue.put((self.last_deliver, data))
                self.queued += len(data)
                while self.queued > QUEUE_LIMIT and not self.dead:
                    self.space.clear()
                    await self.space.wait()
        except (ConnectionError, OSError):
            if not self.down:
                self.conn.client_closed(rst=True)
        await self.queue.put((0, None))

    async def pump_out(self):
        try:
            while True:
                at, data = await self.queue.get()
                if data is None:
                    break
                delay = at - time.monotonic()
                if delay > 0:
                    await asyncio.sleep(delay)
                for i in range(0, len(data), CHUNK):
                    piece = data[i:i + CHUNK]
                    while self.net.stalled() or self.conn.stalled():
                        if self.conn.upstream_reset and not self.down:
                            break              # RST уже ушёл камере — копить незачем
                        await asyncio.sleep(0.02)
                    if self.conn.upstream_reset and not self.down:
                        break
                    rate = self.net.p["rate_kbps" if self.down else "up_rate_kbps"]
                    if rate:
                        now = time.monotonic()
                        start = max(now, self.next_free)
                        self.next_free = start + len(piece) * 8 / (rate * 1000)
                        if start > now:
                            await asyncio.sleep(start - now)
                    self.writer.write(piece)
                    await self.writer.drain()
                    if self.watch and self.down:
                        self.watch.feed(piece)
                    if self.down:
                        self.net.bytes_down += len(piece)
                    else:
                        self.net.bytes_up += len(piece)
                self.queued -= len(data)
                if self.queued <= QUEUE_LIMIT:
                    self.space.set()
        except (ConnectionError, OSError):
            pass
        finally:
            self.dead = True
            self.space.set()
            if not self.down and self.conn.rst_requested and not self.conn.orphaned:
                Conn.rst(self.writer)          # приложение оборвало соединение RST — камера узнаёт так же
            elif self.down or not self.conn.orphaned:
                try:
                    self.writer.close()
                except Exception:
                    pass


class Conn:
    """Пара соединений приложение ↔ прокси ↔ камера."""

    def __init__(self, net, c_reader, c_writer, camera):
        self.net = net
        host, _, port = camera.partition(":")
        self.camera, self.camera_port = host, int(port or 15740)
        self.c_reader, self.c_writer = c_reader, c_writer
        self.kind = "?"
        self.peer = c_writer.get_extra_info("peername")
        self.pending = {}          # tid -> (t_request, is_liveview)
        self.data_bytes = {}       # tid -> bytes
        self.u_writer = None
        self.orphaned = False
        self.stall_until = 0.0            # застряло только это соединение (TCP после провала), сеть при этом есть
        self.rst_requested = False
        self.upstream_reset = False

    def stalled(self):
        return time.monotonic() < self.stall_until

    def client_closed(self, rst):
        """Приложение закрыло соединение. Камера узнаёт об этом сразу, если сеть есть, или когда она вернётся."""
        if self.orphaned:
            return
        if self.net.p.get("lose_fin") and (self.net.stalled() or self.stalled()):
            self.orphan()                  # закрытие ушло в «дыру»: камера держит сессию
            return
        self.rst_requested = rst
        if rst and self.stalled() and not self.net.stalled() and self.u_writer:
            # застряло только соединение, а сеть есть — RST доходит сразу, накопленное камере уже не нужно
            self.upstream_reset = True
            Conn.rst(self.u_writer)

    def describe(self):
        return {"kind": self.kind, "peer": f"{self.peer[0]}:{self.peer[1]}" if self.peer else None,
                "orphaned": self.orphaned}

    def on_up(self, ptype, f):
        if ptype == 1:
            self.kind = "cmd"
        elif ptype == 3:
            self.kind = "evt"
        elif ptype == 6 and len(f) >= 14:
            _, code, tid = struct.unpack_from("<IHI", f)
            p1 = struct.unpack_from("<I", f, 10)[0] if len(f) >= 14 else None
            self.pending[tid] = (time.monotonic(), code == OP_GET_OBJECT and p1 == LV_HANDLE)

    def on_down(self, ptype, f):
        if ptype == 9 and len(f) >= 12:
            tid, total = struct.unpack_from("<IQ", f)
            self.data_bytes[tid] = total
        elif ptype == 7 and len(f) >= 6:
            code, tid = struct.unpack_from("<HI", f)
            sent, lv = self.pending.pop(tid, (None, False))
            size = self.data_bytes.pop(tid, 0)
            if lv and code == 0x2001 and size and sent:
                self.net.frames.append((time.monotonic(), size, time.monotonic() - sent))
                del self.net.frames[:-5000]
            elif lv and code == 0x200F:
                self.net.denied += 1
            elif not lv:
                self.net.other_ops += 1

    async def run(self):
        try:
            u_reader, self.u_writer = await asyncio.wait_for(asyncio.open_connection(self.camera, self.camera_port), 5)
        except (OSError, asyncio.TimeoutError) as e:
            print(f"! нет связи с камерой: {e}", flush=True)
            self.c_writer.close()
            return
        for w in (self.c_writer, self.u_writer):
            w.get_extra_info("socket").setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.net.conns.append(self)
        self.net.conns_total += 1
        up = Pipe(self.net, self, self.c_reader, self.u_writer, False)
        down = Pipe(self.net, self, u_reader, self.c_writer, True)
        up.watch = PacketWatch(self.on_up)
        down.watch = PacketWatch(self.on_down)
        print(f"+ соединение {self.describe()}", flush=True)
        try:
            await asyncio.gather(up.pump_in(), up.pump_out(), down.pump_in(), down.pump_out())
        finally:
            if self in self.net.conns:
                self.net.conns.remove(self)
            print(f"- соединение {self.kind} закрыто", flush=True)

    @staticmethod
    def rst(writer):
        try:
            sock = writer.get_extra_info("socket")
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        except Exception:
            pass
        writer.close()

    def orphan(self):
        """Камера держит сессию, будто мы ещё здесь; через hold_s «камера» сдаётся сама (RST)."""
        if self.orphaned or not self.u_writer:
            return
        self.orphaned = True
        self.net.orphans.append(self.u_writer)
        print(f"~ {self.kind}: соединение с камерой осиротело на {self.net.hold_s:.0f} с", flush=True)

        async def expire(w=self.u_writer):
            await asyncio.sleep(self.net.hold_s)
            if w in self.net.orphans:
                self.net.orphans.remove(w)
                self.rst(w)
        asyncio.get_running_loop().create_task(expire())

    def reset(self, side):
        if side == "both":
            self.rst(self.c_writer)
            if self.u_writer:
                self.rst(self.u_writer)
        else:
            self.orphan()
            self.rst(self.c_writer)


async def stall_maker(net):
    while True:
        await asyncio.sleep(0.1)
        p = net.p
        if p["stall_rate"] and random.random() < p["stall_rate"] * 0.1:
            lo, hi = p["stall_ms"]
            net.stall(random.uniform(lo, hi) / 1000)


async def stats_logger(net, path):
    out = open(path, "a") if path else None
    while True:
        await asyncio.sleep(1)
        s = net.summary(window=1.0)
        if out:
            out.write(json.dumps({"t": time.time(), **{k: v for k, v in s.items() if k != "params"}}) + "\n")
            out.flush()


async def control(net, reader, writer):
    try:
        head = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 5)
        line = head.split(b"\r\n")[0].decode()
        method, path, _ = line.split(" ", 2)
        length = 0
        for h in head.split(b"\r\n")[1:]:
            if h.lower().startswith(b"content-length:"):
                length = int(h.split(b":")[1])
        body = json.loads(await asyncio.wait_for(reader.readexactly(length), 5)) if length else {}
        status, reply = 200, None
        if method == "GET" and path == "/state":
            reply = net.summary()
        elif path == "/set":
            net.p.update({k: v for k, v in body.items() if k in net.p})
        elif path == "/profile":
            net.p = dict(PROFILES[body["name"]])
        elif path == "/blackhole":
            seconds = float(body.get("seconds", 10))
            if body.get("scope") == "existing":
                for c in net.conns:
                    c.stall_until = time.monotonic() + seconds
                net.stalls.append((time.monotonic(), seconds))
            else:
                net.stall(seconds)
        elif path == "/reset":
            for c in list(net.conns):
                c.reset(body.get("side", "client"))
        elif path == "/release":
            for w in net.orphans:
                if body.get("mode") == "fin":
                    w.close()                  # обычное закрытие: камера получает FIN
                else:
                    Conn.rst(w)
            net.orphans.clear()
        elif path == "/stats/reset":
            net.reset_stats()
        else:
            status = 404
        data = json.dumps(reply if reply is not None else net.summary(), ensure_ascii=False).encode()
        writer.write(f"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\n"
                     f"Content-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode() + data)
        await writer.drain()
    except Exception as e:
        writer.write(f"HTTP/1.1 400 Bad Request\r\nContent-Length: {len(str(e))}\r\n\r\n{e}".encode())
    finally:
        writer.close()


async def link_sensor(net, reader, writer):
    """GET /link: 200 — сеть есть, 503 — провал. Только чтение, можно слушать на всех адресах."""
    try:
        await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 3)
        up = not net.stalled()
        body = b"up" if up else b"down"
        writer.write(b"HTTP/1.1 " + (b"200 OK" if up else b"503 Service Unavailable") +
                     b"\r\nContent-Length: " + str(len(body)).encode() + b"\r\nConnection: close\r\n\r\n" + body)
        await writer.drain()
    except Exception:
        pass
    finally:
        writer.close()


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--camera", required=True, help="IP камеры[:порт]")
    ap.add_argument("--listen", default="0.0.0.0:15740")
    ap.add_argument("--control", default="127.0.0.1:15799")
    ap.add_argument("--link", default="0.0.0.0:15798", help="датчик связи для программ: GET /link")
    ap.add_argument("--profile", default="good", choices=sorted(PROFILES))
    ap.add_argument("--hold", type=float, default=150.0, help="сколько «камера» держит осиротевшую сессию, с")
    ap.add_argument("--log")
    a = ap.parse_args()
    net = Net(a.profile)
    net.hold_s = a.hold
    lh, lp = a.listen.rsplit(":", 1)
    ch, cp = a.control.rsplit(":", 1)
    server = await asyncio.start_server(lambda r, w: Conn(net, r, w, a.camera).run(), lh, int(lp))
    ctl = await asyncio.start_server(lambda r, w: control(net, r, w), ch, int(cp))
    kh, kp = a.link.rsplit(":", 1)
    sensor = await asyncio.start_server(lambda r, w: link_sensor(net, r, w), kh, int(kp))
    print(f"badnet: {a.listen} → {a.camera}:15740, управление http://{a.control}, профиль {a.profile}", flush=True)
    asyncio.create_task(stall_maker(net))
    asyncio.create_task(stats_logger(net, a.log))
    async with server, ctl, sensor:
        await asyncio.gather(server.serve_forever(), ctl.serve_forever(), sensor.serve_forever())


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        sys.exit(0)
