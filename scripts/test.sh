#!/bin/sh
set -eu
TURBOJ_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
cd "$TURBOJ_ROOT"
exec ./mvnw test "$@"
