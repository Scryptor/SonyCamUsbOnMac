#!/bin/bash
# Собирает build/ZV-E10 Viewer.app из Swift-пакета и подписывает его ad-hoc.
set -euo pipefail
cd "$(dirname "$0")/.."

swift build -c release --product ZVViewer >&2

APP="build/ZV-E10 Viewer.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS"
cp "$(swift build -c release --show-bin-path)/ZVViewer" "$APP/Contents/MacOS/ZVViewer"
cp Resources/Info.plist "$APP/Contents/Info.plist"
codesign --force --sign - "$APP" >&2

echo "$APP"
