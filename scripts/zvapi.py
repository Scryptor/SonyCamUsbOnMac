#!/usr/bin/env python3
"""zvapi.py <METHOD> <path> [json-body]  — один запрос к API автоматизации; печатает ответ."""
import json, os, sys, urllib.request, urllib.error
info = json.load(open(os.path.expanduser("~/Library/Application Support/Sony Cam Viewer/automation.json")))
method, path = sys.argv[1], sys.argv[2]
body = sys.argv[3].encode() if len(sys.argv) > 3 else None
req = urllib.request.Request(f"http://127.0.0.1:{info['port']}{path}", data=body, method=method)
req.add_header("X-ZV-Token", info["token"])
try:
    with urllib.request.urlopen(req, timeout=15) as r:
        data = r.read()
except urllib.error.HTTPError as e:
    data = e.read()
if path.startswith("/screenshot") or path.startswith("/frame"):
    out = sys.argv[3] if len(sys.argv) > 3 else "/dev/stdout"
    sys.stdout.buffer.write(data)
else:
    print(data.decode())
