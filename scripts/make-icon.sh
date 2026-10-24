#!/bin/bash
# Собирает иконку из Resources/AppIcon.icon (документ Icon Composer) в Resources/Assets.car и Resources/AppIcon.icns.
# Нужен Xcode 26 или новее: actool с поддержкой .icon. Готовые файлы лежат в репозитории, обычной сборке Xcode 26 не нужен.
set -euo pipefail
cd "$(dirname "$0")/.."

OUT=$(mktemp -d "${TMPDIR:-/tmp}/zv-icon.XXXXXX")
trap 'rm -rf "$OUT"' EXIT

xcrun actool Resources/AppIcon.icon --compile "$OUT" --app-icon AppIcon \
    --platform macosx --target-device mac --minimum-deployment-target 14.0 \
    --output-partial-info-plist "$OUT/partial.plist" \
    --output-format human-readable-text --errors --warnings >&2
cp "$OUT/Assets.car" "$OUT/AppIcon.icns" Resources/

echo "Resources/Assets.car Resources/AppIcon.icns"
