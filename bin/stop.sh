#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${ROOT}/run/cm-insight.pid"; FORCE=false
[[ "${1:-}" == "--force" ]] && FORCE=true
if [[ ! -f "${PID_FILE}" ]]; then echo "CM Insight is not running"; exit 0; fi
PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
if [[ ! "${PID}" =~ ^[0-9]+$ ]] || ! kill -0 "${PID}" 2>/dev/null; then echo "Removing stale PID file"; rm -f "${PID_FILE}"; exit 0; fi
echo "Stopping CM Insight (PID ${PID})..."; kill "${PID}"
for _ in {1..30}; do
  if ! kill -0 "${PID}" 2>/dev/null; then rm -f "${PID_FILE}"; echo "CM Insight stopped"; exit 0; fi
  sleep 1
done
if [[ "${FORCE}" == true ]]; then echo "Graceful stop timed out; sending SIGKILL"; kill -9 "${PID}" 2>/dev/null || true; rm -f "${PID_FILE}"; exit 0; fi
echo "ERROR: still running after 30s; review logs or use bin/stop.sh --force" >&2; exit 1
