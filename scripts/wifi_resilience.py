#!/usr/bin/env python3
"""wifi_resilience.py — прогон «плохого Wi-Fi» через scripts/badnet.py: как программа держит live view.

Mac (API автоматизации; программу запускает сам, адрес камеры подменяет на прокси):
  scripts/wifi_resilience.py mac --app "build/ZV-E10 Viewer.app" --camera 192.168.1.194 --out /tmp/run [--only S4,S5]
Android (debug-сборка, ptpipHost → IP этого Mac; метрики — по самому протоколу в прокси):
  scripts/wifi_resilience.py android --serial 192.168.1.185:5555 --camera 192.168.1.194 --host-ip 192.168.1.123 --out /tmp/run

Каждый сценарий: 6 с хорошей связи, порча, затем наблюдение. Метрики — по кадрам, которые прокси передал программе:
кадры и к/с во время порчи, самая долгая пауза, сколько новых соединений (переподключений), когда пошёл первый кадр
после конца порчи и выпала ли программа на экран ожидания (Mac).
"""
import argparse, json, os, plistlib, signal, subprocess, sys, time, urllib.error, urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
CTL = "http://127.0.0.1:15799"

# имя: (действие с прокси, длительность порчи, наблюдение после)
SCENARIOS = {
    "S0": ("хорошая связь", {"profile": "good"}, 20, 0),
    "S1": ("слабая: 4 Мбит/с, джиттер, замирания до 1,2 с", {"profile": "weak"}, 30, 12),
    "S2": ("плохая: 1,5 Мбит/с, замирания до 3 с", {"profile": "bad"}, 30, 15),
    "S3": ("ужасная: 0,6 Мбит/с, замирания до 6 с", {"profile": "awful"}, 30, 20),
    "S4": ("провал сети 3 с", {"blackhole": 3}, 3, 15),
    "S5": ("провал сети 10 с", {"blackhole": 10}, 10, 25),
    "S6": ("провал сети 25 с", {"blackhole": 25}, 25, 40),
    "S7": ("RST программе, камера держит сессию (потерянный FIN)", {"reset": "client"}, 0, 60),
    "S8": ("RST обеим сторонам", {"reset": "both"}, 0, 30),
    "S9": ("полоса 6 Мбит/с на 40 с, потом снова хорошо", {"set": {"rate_kbps": 6000}}, 40, 40),
    "S10": ("TCP застрял на 30 с, а сеть есть", {"blackhole": 30, "scope": "existing"}, 30, 20),
}


def ctl(path, body=None, timeout=5):
    req = urllib.request.Request(CTL + path, data=json.dumps(body).encode() if body is not None else None,
                                 method="POST" if body is not None else "GET")
    return json.loads(urllib.request.urlopen(req, timeout=timeout).read())


class MacApp:
    def __init__(self, app, out):
        self.app, self.out, self.proc = app, out, None
        self.info_path = os.path.expanduser("~/Library/Application Support/ZV-E10 Viewer/automation.json")

    def prefs(self):
        return plistlib.loads(subprocess.run(["defaults", "export", "local.zve10viewer", "-"], capture_output=True).stdout)

    def start(self):
        self.saved_host = self.prefs().get("wifiHost")
        subprocess.run(["defaults", "write", "local.zve10viewer", "wifiHost", "127.0.0.1"], check=True)
        env = dict(os.environ, ZV_AUTOMATION="1", ZV_PTPIP_HOST="127.0.0.1",
                   ZV_LINK_CHECK_URL="http://127.0.0.1:15798/link")
        exe = os.path.join(self.app, "Contents/MacOS/ZVViewer")
        self.proc = subprocess.Popen([exe], env=env, stdout=open(f"{self.out}/app.log", "w"), stderr=subprocess.STDOUT,
                                     start_new_session=True)
        time.sleep(2)

    def call(self, path):
        info = json.load(open(self.info_path))
        req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}")
        req.add_header("X-ZV-Token", info["token"])
        return json.loads(urllib.request.urlopen(req, timeout=3).read())

    def state(self):
        try:
            s = self.call("/state")
            battery = next((x.get("label") for x in s.get("settings", []) if x.get("id") == "battery"), None)
            return {"phase": s.get("phase"), "fps": s.get("fps"), "gap": s.get("frameGapMs"), "battery": battery,
                    "transport": s.get("transport"), "host": (s.get("wifi") or {}).get("host"), "link": s.get("link")}
        except Exception as e:
            return {"phase": None, "error": type(e).__name__}

    def stop(self):
        if self.proc:
            self.proc.send_signal(signal.SIGTERM)
            try:
                self.proc.wait(timeout=8)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.saved_host:
            subprocess.run(["defaults", "write", "local.zve10viewer", "wifiHost", self.saved_host])


class AndroidApp:
    def __init__(self, serial, host_ip, out):
        self.serial, self.host_ip, self.out = serial, host_ip, out

    def adb(self, *a, **kw):
        return subprocess.run(["adb", "-s", self.serial, *a], capture_output=True, text=True, **kw)

    def start(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self.adb("shell", "am", "force-stop", "local.zve10viewer")
        time.sleep(1)
        self.adb("logcat", "-c")
        self.adb("shell", "am", "start", "-n", "local.zve10viewer/.MainActivity", "--es", "ptpipHost", self.host_ip,
                 "--es", "linkCheckUrl", f"http://{self.host_ip}:15798/link")
        self.logcat = subprocess.Popen(["adb", "-s", self.serial, "logcat", "-v", "time", "-s", "ZVLink:*", "ZVFrames:*",
                                        "ZVController:*"], stdout=open(f"{self.out}/logcat.txt", "w"), stderr=subprocess.STDOUT)
        time.sleep(2)

    def state(self):
        return {}

    def stop(self):
        self.adb("shell", "am", "force-stop", "local.zve10viewer")
        self.logcat.terminate()


def wait_live(app, seconds=40):
    end = time.monotonic() + seconds
    while time.monotonic() < end:
        s = ctl("/state")
        if s["fps"] > 3 and s["last_frame_age_ms"] is not None and s["last_frame_age_ms"] < 500:
            return True
        time.sleep(0.5)
    return False


def run_scenario(app, key, log):
    title, action, length, after = SCENARIOS[key]
    ctl("/profile", {"name": "good"})
    if not wait_live(app):
        return {"key": key, "title": title, "error": "нет кадров перед началом"}
    time.sleep(6)
    ctl("/stats/reset", {})
    timeline, t0 = [], time.monotonic()
    if "profile" in action:
        ctl("/profile", {"name": action["profile"]})
    elif "blackhole" in action:
        ctl("/blackhole", {"seconds": action["blackhole"], "scope": action.get("scope", "all")})
    elif "reset" in action:
        ctl("/reset", {"side": action["reset"]})
    elif "set" in action:
        ctl("/set", action["set"])
    end_impair = t0 + length
    restored = False
    while time.monotonic() < end_impair + after:
        now = time.monotonic()
        if not restored and now >= end_impair:
            ctl("/profile", {"name": "good"})
            restored = True
        p = ctl("/state")
        timeline.append({"t": round(now - t0, 2), "frames": p["frames_total"], "conns": p["conns_total"],
                         "proxy_fps": p["fps"], "kb": p["frame_kb_avg"], "app": app.state()})
        time.sleep(0.25)
    log.write(json.dumps({"key": key, "timeline": timeline}, ensure_ascii=False) + "\n")
    log.flush()
    return summarize(key, title, timeline, length)


def summarize(key, title, tl, length):
    def frames_at(t):
        best = 0
        for p in tl:
            if p["t"] <= t:
                best = p["frames"]
        return best

    during = frames_at(length) if length else None
    # первый кадр после конца порчи (или после сброса)
    base = frames_at(length)
    first_after = next((p["t"] - length for p in tl if p["t"] >= length and p["frames"] > base), None)
    phases = [p["app"].get("phase") for p in tl if p["app"]]
    lost = any(ph == "searching" for ph in phases)
    tail = [p for p in tl if p["t"] >= tl[-1]["t"] - 5]
    fps_end = round((tail[-1]["frames"] - tail[0]["frames"]) / max(0.1, tail[-1]["t"] - tail[0]["t"]), 1) if len(tail) > 1 else None
    gaps, last_t, last_f = [], None, None
    for p in tl:
        if last_f is None or p["frames"] > last_f:
            if last_t is not None:
                gaps.append(p["t"] - last_t)
            last_t, last_f = p["t"], p["frames"]
    kbs = [p["kb"] for p in tl if p["kb"]]
    hosts = sorted({p["app"].get("host") for p in tl if p["app"].get("host")})
    battery = next((p["app"].get("battery") for p in reversed(tl) if p["app"].get("battery")), None)
    return {"key": key, "title": title, "hosts": hosts, "battery": battery,
            "frames_during": during, "fps_during": round(during / length, 1) if length else None,
            "max_gap_s": round(max(gaps), 2) if gaps else None,
            "new_connections": tl[-1]["conns"], "first_frame_after_s": round(first_after, 2) if first_after is not None else None,
            "fell_to_waiting": lost, "fps_end": fps_end, "frame_kb": (min(kbs), max(kbs)) if kbs else None}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("platform", choices=["mac", "android"])
    ap.add_argument("--app", default=os.path.join(ROOT, "build/ZV-E10 Viewer.app"))
    ap.add_argument("--serial")
    ap.add_argument("--host-ip")
    ap.add_argument("--camera", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--only")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    listen = "127.0.0.1:15740" if a.platform == "mac" else "0.0.0.0:15740"
    proxy = subprocess.Popen([sys.executable, "-I", os.path.join(ROOT, "scripts/badnet.py"), "--camera", a.camera,
                              "--listen", listen, "--log", f"{a.out}/badnet.jsonl"],
                             stdout=open(f"{a.out}/badnet.log", "w"), stderr=subprocess.STDOUT)
    time.sleep(1)
    app = MacApp(a.app, a.out) if a.platform == "mac" else AndroidApp(a.serial, a.host_ip, a.out)
    keys = a.only.split(",") if a.only else list(SCENARIOS)
    results = []
    try:
        app.start()
        if not wait_live(app, 60):
            print("программа не показала кадры через прокси за 60 с", flush=True)
            return
        with open(f"{a.out}/timeline.jsonl", "w") as log:
            for key in keys:
                r = run_scenario(app, key, log)
                results.append(r)
                print(json.dumps(r, ensure_ascii=False), flush=True)
    finally:
        app.stop()
        proxy.terminate()
        json.dump(results, open(f"{a.out}/summary.json", "w"), ensure_ascii=False, indent=1)


if __name__ == "__main__":
    main()
