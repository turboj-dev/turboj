#!/bin/sh
set -eu
JAVAFMT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$JAVAFMT_ROOT"
./mvnw -q -pl javafmt-benchmarks -am package
mkdir -p target
java -jar javafmt-benchmarks/target/javafmt-benchmarks.jar \
  'dev.javafmt.benchmarks.FormattingBench.*' -wi 2 -i 3 -w 1s -r 1s -f 1 \
  -prof gc -jvmArgs '-Xms256m -Xmx1g' -rf json -rff target/jmh.json
