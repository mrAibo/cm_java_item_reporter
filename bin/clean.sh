#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
if [[ -f "${ROOT}/run/cm-insight.pid" ]]; then
  PID="$(cat "${ROOT}/run/cm-insight.pid" 2>/dev/null || true)"
  if [[ "${PID}" =~ ^[0-9]+$ ]] && kill -0 "${PID}" 2>/dev/null; then echo "ERROR: CM Insight is running (PID ${PID})" >&2; exit 1; fi
fi
rm -rf "${ROOT}/build"; echo "Build artifacts removed"
