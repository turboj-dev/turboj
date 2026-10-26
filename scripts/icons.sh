#!/bin/sh
# Regenerates the TurboJ icon everywhere it is used from scripts/Icons.java: branding/, the website favicon, the app's
# window and Dock images, and app/packaging/macos/TurboJ.icns (needs macOS iconutil).
set -eu
cd "$(dirname "$0")/.."
ICONSET=$(mktemp -d)/TurboJ.iconset
java scripts/Icons.java . "$ICONSET"
mkdir -p app/packaging/macos
iconutil -c icns -o app/packaging/macos/TurboJ.icns "$ICONSET"
rm -rf "$(dirname "$ICONSET")"
echo "Icons written: branding/, website/public/favicon.svg, app/src/main/resources/dev/turboj/app/icons/, app/packaging/macos/TurboJ.icns"
