#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
"${ROOT}/build.sh"
exec java -cp "${ROOT}/build/classes" com.mraibo.cminsight.app.SelfTest
