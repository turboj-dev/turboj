#!/bin/sh
# Build an ad-hoc signed local application with a private runtime. Not Developer ID signed/notarized.
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
if [ "$(uname -s)" != Darwin ]; then echo 'macOS packaging requires macOS and a Java 25 JDK.' >&2; exit 2; fi
TURBOJ_JDK=${JAVA_HOME:-$(/usr/libexec/java_home -v 25)}
TURBOJ_JAVA_FEATURE=$("$TURBOJ_JDK/bin/java" -XshowSettings:properties -version 2>&1 | awk '/java.specification.version =/ {print $3}')
case "$TURBOJ_JAVA_FEATURE" in ''|*[!0-9]*) echo 'Cannot identify the packaging JDK.' >&2; exit 2 ;; esac
if [ "$TURBOJ_JAVA_FEATURE" -lt 25 ]; then echo 'Packaging requires JDK 25 or newer; the selected runtime cannot load TurboJ.' >&2; exit 2; fi
TURBOJ_FORMAT=${1:-app}
case "$TURBOJ_FORMAT" in app|dmg) ;; *) echo "Usage: $0 [app|dmg] [destination]" >&2; exit 2 ;; esac
TURBOJ_APP_VERSION=${TURBOJ_APP_VERSION:-1.0.10}
TURBOJ_DEST=${2:-"$TURBOJ_ROOT/dist"}
if [ -e "$TURBOJ_DEST/TurboJ.app" ]; then echo "Output already exists: $TURBOJ_DEST/TurboJ.app. Choose another output directory as the second argument." >&2; exit 2; fi
if [ -e "$TURBOJ_DEST/TurboJ.package-receipt.json" ] || [ -e "$TURBOJ_DEST/TurboJ-$TURBOJ_APP_VERSION.dmg" ]; then echo 'Package output already exists; choose another output directory.' >&2; exit 2; fi
if [ "${TURBOJ_SKIP_BUILD:-0}" != 1 ]; then
  python3 scripts/build-receipt.py begin
  ./mvnw -q -pl app -am package
  python3 scripts/build-receipt.py create
fi
python3 scripts/build-receipt.py verify
mkdir -p target "$TURBOJ_DEST"
TURBOJ_STAGE=$(mktemp -d "$TURBOJ_ROOT/target/package.XXXXXX")
mkdir -p "$TURBOJ_STAGE/input"
cp app/target/turboj-app.jar "$TURBOJ_STAGE/input/"
"$TURBOJ_JDK/bin/jlink" --add-modules java.se,jdk.compiler,jdk.jshell,jdk.jdi,jdk.jfr,jdk.unsupported,jdk.zipfs --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$TURBOJ_STAGE/runtime"
# Preserve the platform symbol archive for compiler toolchain compatibility.
if [ -f "$TURBOJ_JDK/lib/ct.sym" ]; then cp "$TURBOJ_JDK/lib/ct.sym" "$TURBOJ_STAGE/runtime/lib/ct.sym"; fi
"$TURBOJ_JDK/bin/jpackage" --type app-image --name TurboJ --app-version "$TURBOJ_APP_VERSION" --vendor TurboJ --description 'A focused Java editor and execution environment' --input "$TURBOJ_STAGE/input" --main-jar turboj-app.jar --main-class dev.turboj.app.TurboJ --runtime-image "$TURBOJ_STAGE/runtime" --dest "$TURBOJ_DEST" --mac-package-identifier dev.turboj.app --icon app/packaging/macos/TurboJ.icns --java-options '-Xmx768m' --java-options '--enable-native-access=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED' --java-options '--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED'
cp app/target/build-receipt.json "$TURBOJ_DEST/TurboJ.app/Contents/Resources/build-receipt.json"
# Seal the final resources once. The package receipt stays outside this seal to avoid a
# self-referential launcher hash. Preserve jpackage's native runtime/JIT entitlements.
/usr/bin/codesign --force --deep --sign - --preserve-metadata=entitlements,flags "$TURBOJ_DEST/TurboJ.app"
/usr/bin/codesign --verify --deep --strict "$TURBOJ_DEST/TurboJ.app"
python3 scripts/build-receipt.py package --package "$TURBOJ_DEST/TurboJ.app" --jdk "$TURBOJ_JDK"
if [ "$TURBOJ_FORMAT" = dmg ]; then
  # jpackage's DMG stage may re-sign its copied app. A plain disk image preserves the
  # already qualified app, so local, mounted and installed bundle hashes stay identical.
  mkdir "$TURBOJ_STAGE/image"
  /usr/bin/ditto --rsrc --extattr "$TURBOJ_DEST/TurboJ.app" "$TURBOJ_STAGE/image/TurboJ.app"
  cp "$TURBOJ_DEST/TurboJ.package-receipt.json" "$TURBOJ_STAGE/image/TurboJ.package-receipt.json"
  ln -s /Applications "$TURBOJ_STAGE/image/Applications"
  /usr/bin/codesign --verify --deep --strict "$TURBOJ_STAGE/image/TurboJ.app"
  python3 scripts/build-receipt.py verify-package --package "$TURBOJ_STAGE/image/TurboJ.app"
  /usr/bin/hdiutil create -srcfolder "$TURBOJ_STAGE/image" -volname TurboJ -format UDZO -fs HFS+ "$TURBOJ_DEST/TurboJ-$TURBOJ_APP_VERSION.dmg"
fi
python3 scripts/build-receipt.py verify-package --package "$TURBOJ_DEST/TurboJ.app"
printf 'Created %s/TurboJ.app (ad-hoc signed local build; not Developer ID signed or notarized).\n' "$TURBOJ_DEST"
