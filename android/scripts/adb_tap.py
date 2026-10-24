#!/usr/bin/env python3
"""Ждёт на планшете кнопку с подходящей надписью и нажимает её.
Использование: adb_tap.py <serial> <regex> [секунд]. Печатает только саму кнопку."""
import re, subprocess, sys, time, xml.etree.ElementTree as ET

serial, pattern = sys.argv[1], re.compile(sys.argv[2], re.I)
deadline = time.time() + (float(sys.argv[3]) if len(sys.argv) > 3 else 15)

def adb(*args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True)

while time.time() < deadline:
    adb("shell", "uiautomator", "dump", "/sdcard/zv-ui.xml")
    xml = adb("shell", "cat", "/sdcard/zv-ui.xml").stdout
    try:
        nodes = list(ET.fromstring(xml).iter("node"))
    except ET.ParseError:
        nodes = []
    for node in nodes:
        text = (node.get("text") or "").strip()
        if pattern.match(text) and node.get("enabled") == "true":
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
            adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))
            adb("shell", "rm", "-f", "/sdcard/zv-ui.xml")
            print(f"нажал «{text}» ({node.get('package')})")
            sys.exit(0)
    time.sleep(0.8)
adb("shell", "rm", "-f", "/sdcard/zv-ui.xml")
print("кнопка не появилась")
sys.exit(1)
