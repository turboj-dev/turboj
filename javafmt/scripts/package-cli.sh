#!/bin/sh
# Independent host-specific JVM distribution; no TurboJ classes or installation required.
set -eu
JAVAFMT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$JAVAFMT_ROOT"
if [ -n "${JAVA_HOME:-}" ]; then
  JAVAFMT_JDK=$JAVA_HOME
elif [ "$(uname -s)" = Darwin ]; then
  JAVAFMT_JDK=$(/usr/libexec/java_home -v 25)
else
  printf '%s\n' 'Set JAVA_HOME to a JDK 25 installation containing jlink and jdeps.' >&2
  exit 2
fi
JAVAFMT_DEST=${1:-"$JAVAFMT_ROOT/target/distribution/javafmt-0.1.0-$(uname -s)-$(uname -m)"}
if [ -e "$JAVAFMT_DEST" ]; then
  printf 'Destination already exists: %s. Choose a new directory.\n' "$JAVAFMT_DEST" >&2
  exit 2
fi
if [ "${JAVAFMT_SKIP_BUILD:-0}" != 1 ]; then ./mvnw -q -pl javafmt-cli -am package; fi
JAVAFMT_ARCHIVE=$JAVAFMT_ROOT/javafmt-cli/target/javafmt.jar
JAVAFMT_MODULES=$("$JAVAFMT_JDK/bin/jdeps" --ignore-missing-deps --multi-release 25 --print-module-deps "$JAVAFMT_ARCHIVE")
mkdir -p "$JAVAFMT_DEST/lib" "$JAVAFMT_DEST/docs"
cp "$JAVAFMT_ARCHIVE" "$JAVAFMT_DEST/lib/javafmt.jar"
cp javafmt javafmt.cmd LICENSE "$JAVAFMT_DEST/"
for JAVAFMT_DOC in README.md docs/STYLE.md docs/CLI.md docs/CLI-VALIDATION.md docs/COMPATIBILITY.md docs/LICENSES.md docs/runtime-dependencies.json; do
  if [ -f "$JAVAFMT_DOC" ]; then cp "$JAVAFMT_DOC" "$JAVAFMT_DEST/docs/"; fi
done
"$JAVAFMT_JDK/bin/jlink" --add-modules "$JAVAFMT_MODULES,jdk.zipfs" --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output "$JAVAFMT_DEST/runtime"
"$JAVAFMT_DEST/javafmt" --version
printf 'Created %s\n' "$JAVAFMT_DEST"
