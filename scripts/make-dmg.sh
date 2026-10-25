#!/bin/bash
# Собирает build/Sony-Cam-Viewer.dmg: приложение и ссылка на «Программы» для перетаскивания.
set -euo pipefail
cd "$(dirname "$0")/.."

APP=$(scripts/build-app.sh)
DMG="build/Sony-Cam-Viewer.dmg"
STAGING=$(mktemp -d "${TMPDIR:-/tmp}/zv-dmg.XXXXXX")
trap 'rm -rf "$STAGING"' EXIT

cp -R "$APP" "$STAGING/"
ln -s /Applications "$STAGING/Applications"
hdiutil create -volname "Sony Cam Viewer" -srcfolder "$STAGING" -ov -format UDZO "$DMG" >&2

echo "$DMG"
