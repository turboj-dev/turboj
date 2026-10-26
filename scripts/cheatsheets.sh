#!/bin/sh
# Regenerates docs/cheatsheets/*.pdf from the bundled keymaps (needs Chrome, Chromium, Edge or Brave).
set -e
cd "$(dirname "$0")/.."
./mvnw -q -o package -pl app -am -DskipTests -Djacoco.skip=true
exec java --enable-native-access=ALL-UNNAMED -cp app/target/turboj-app.jar dev.turboj.help.CheatSheetExport "${1:-docs/cheatsheets}"
