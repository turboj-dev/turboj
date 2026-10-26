#!/bin/sh
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
if [ ! -f app/target/turboj-app.jar ]; then ./mvnw -q -pl app -am package; fi
# Run a private copy, one per build: a rebuild of app/target/turboj-app.jar must never swap classes
# under a running TurboJ (classes it loads later, and its worker JVMs, would come from the new jar).
RUN_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/turboj/run"
mkdir -p "$RUN_DIR"
STAMP=$(stat -f %m app/target/turboj-app.jar 2>/dev/null || stat -c %Y app/target/turboj-app.jar)
RUN_JAR="$RUN_DIR/turboj-app-$STAMP.jar"
if [ ! -f "$RUN_JAR" ]; then cp app/target/turboj-app.jar "$RUN_JAR.tmp" && mv "$RUN_JAR.tmp" "$RUN_JAR"; fi
find "$RUN_DIR" -name 'turboj-app-*.jar' ! -name "turboj-app-$STAMP.jar" -mtime +7 -delete 2>/dev/null || true
exec java --enable-native-access=ALL-UNNAMED -Xmx768m -jar "$RUN_JAR" "$@"
