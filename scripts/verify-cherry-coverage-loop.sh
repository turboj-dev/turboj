#!/usr/bin/env bash
# Requires a built TurboJ, a real companion Cherry CLI and the pinned fixture's cached Maven dependencies.
# Uses an isolated copied project and preferences; native GUI checks must run sequentially.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ $# != 1 || ! -f "$1" ]]; then
  echo "Usage: bash scripts/verify-cherry-coverage-loop.sh /path/to/cherry-test-java.jar" >&2
  exit 2
fi
artifact=app/target/turboj-app.jar
javac -cp "app/target/test-classes:$artifact" -d app/target/test-classes \
  app/src/test/java/dev/turboj/app/NativeWindowTestSupport.java \
  app/src/test/java/dev/turboj/app/KeyboardActionEffectsSmoke.java \
  app/src/test/java/dev/turboj/app/KeyboardBranchEffectsSmoke.java \
  app/src/test/java/dev/turboj/app/LiveTemplateWorkflowSmoke.java \
  app/src/test/java/dev/turboj/app/CherryCoverageLoopSmoke.java
options=(--enable-native-access=ALL-UNNAMED)
for package in api code file parser tree util; do
  options+=("--add-exports=jdk.compiler/com.sun.tools.javac.$package=ALL-UNNAMED")
done
java "${options[@]}" -cp "app/target/test-classes:$artifact" dev.turboj.app.CherryCoverageLoopSmoke "$1"
