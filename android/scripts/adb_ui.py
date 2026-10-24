#!/usr/bin/env python3
"""Тексты на экране планшета с координатами: adb_ui.py <serial> [regex]. Только для приложения local.zve10viewer."""
import re, subprocess, sys, xml.etree.ElementTree as ET
serial = sys.argv[1]
pattern = re.compile(sys.argv[2]) if len(sys.argv) > 2 else None
def adb(*a): return subprocess.run(["adb", "-s", serial, *a], capture_output=True, text=True)
adb("shell", "uiautomator", "dump", "/sdcard/zv-ui.xml")
xml = adb("shell", "cat", "/sdcard/zv-ui.xml").stdout
adb("shell", "rm", "-f", "/sdcard/zv-ui.xml")
for n in ET.fromstring(xml).iter("node"):
    if n.get("package") != "local.zve10viewer": continue
    t = (n.get("text") or "").strip()
    if t and (pattern is None or pattern.search(t)):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
        print(f"{t}\t{(x1+x2)//2},{(y1+y2)//2}")
