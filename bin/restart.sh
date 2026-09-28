#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
"${ROOT}/bin/stop.sh"
exec "${ROOT}/bin/start.sh" "$@"
