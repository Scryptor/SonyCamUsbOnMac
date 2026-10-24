#!/usr/bin/env python3
"""Ставит APK через adb и сам нажимает подтверждение установки HyperOS/MIUI.
Использование: adb_install.py <serial> <apk>. Печатает только найденные кнопки и итог."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

serial, apk = sys.argv[1], sys.argv[2]
LABELS = re.compile(r"^(Установить|Install|Продолжить установку|Continue installation|Всё равно установить|Install anyway|ОК|OK|Разрешить|Allow)$", re.I)

def adb(*args, **kw):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, **kw)

def find_button():
    adb("shell", "uiautomator", "dump", "/sdcard/zv-ui.xml")
    xml = adb("shell", "cat", "/sdcard/zv-ui.xml").stdout
    try:
        root = ET.fromstring(xml)
    except ET.ParseError:
        return None
    for node in root.iter("node"):
        text = (node.get("text") or "").strip()
        if LABELS.match(text) and node.get("enabled") == "true":
            nums = list(map(int, re.findall(r"\d+", node.get("bounds", ""))))
            if len(nums) == 4:
                return text, node.get("package"), ((nums[0] + nums[2]) // 2, (nums[1] + nums[3]) // 2)
    return None

proc = subprocess.Popen(["adb", "-s", serial, "install", "-r", apk], stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
deadline = time.time() + 90
while proc.poll() is None and time.time() < deadline:
    time.sleep(1.0)
    button = find_button()
    if button:
        text, package, (x, y) = button
        print(f"нажимаю «{text}» ({package}) в точке {x},{y}", flush=True)
        adb("shell", "input", "tap", str(x), str(y))
        time.sleep(1.5)
adb("shell", "rm", "-f", "/sdcard/zv-ui.xml")
if proc.poll() is None:
    proc.kill()
    print("установка не завершилась за 90 с")
print((proc.stdout.read() or "").strip().splitlines()[-1:] if proc.stdout else "")
