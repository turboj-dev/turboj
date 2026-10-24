#!/bin/sh
# Build a local, unsigned application with a private runtime. No installed JDK needed to run it.
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
if [ "$(uname -s)" != Darwin ]; then echo 'macOS packaging requires macOS and a Java 25 JDK.' >&2; exit 2; fi
TURBOJ_JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}
TURBOJ_FORMAT=${1:-app}
TURBOJ_DEST=${2:-"$TURBOJ_ROOT/dist"}
if [ -e "$TURBOJ_DEST/TurboJ.app" ]; then echo "Output already exists: $TURBOJ_DEST/TurboJ.app. Choose another output directory as the second argument." >&2; exit 2; fi
if [ "${TURBOJ_SKIP_BUILD:-0}" != 1 ]; then ./mvnw -q -pl app -am package; fi
mkdir -p target "$TURBOJ_DEST"
TURBOJ_STAGE=$(mktemp -d "$TURBOJ_ROOT/target/package.XXXXXX")
mkdir -p "$TURBOJ_STAGE/input"
cp app/target/turboj-app.jar "$TURBOJ_STAGE/input/"
"$TURBOJ_JDK/bin/jlink" --add-modules java.se,jdk.compiler,jdk.jshell,jdk.jdi,jdk.jfr,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$TURBOJ_STAGE/runtime"
# Preserve the platform symbol archive for compiler toolchain compatibility.
if [ -f "$TURBOJ_JDK/lib/ct.sym" ]; then cp "$TURBOJ_JDK/lib/ct.sym" "$TURBOJ_STAGE/runtime/lib/ct.sym"; fi
"$TURBOJ_JDK/bin/jpackage" --type app-image --name TurboJ --app-version 1.0.7 --vendor TurboJ --description 'A focused Java editor and execution environment' --input "$TURBOJ_STAGE/input" --main-jar turboj-app.jar --main-class dev.turboj.app.TurboJ --runtime-image "$TURBOJ_STAGE/runtime" --dest "$TURBOJ_DEST" --mac-package-identifier dev.turboj.app --java-options '-Xmx768m' --java-options '--enable-native-access=ALL-UNNAMED'
if [ "$TURBOJ_FORMAT" = dmg ]; then
  "$TURBOJ_JDK/bin/jpackage" --type dmg --name TurboJ --app-version 1.0.7 --app-image "$TURBOJ_DEST/TurboJ.app" --dest "$TURBOJ_DEST"
fi
printf 'Created %s/TurboJ.app (local development build; not notarized).\n' "$TURBOJ_DEST"
