#!/bin/sh
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
./mvnw -q -pl benchmarks -am package -DskipTests
exec java -Xmx768m -jar benchmarks/target/turboj-benchmarks.jar "$@"
