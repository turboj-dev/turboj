#!/bin/sh
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
if [ ! -f app/target/turboj-app.jar ]; then ./mvnw -q -pl app -am package; fi
exec java --enable-native-access=ALL-UNNAMED -Xmx768m -jar app/target/turboj-app.jar "$@"
