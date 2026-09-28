#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
PID_FILE="${ROOT}/run/cm-insight.pid"; CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"
if [[ ! -f "${PID_FILE}" ]]; then echo "Status: STOPPED"; exit 3; fi
PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
if [[ ! "${PID}" =~ ^[0-9]+$ ]] || ! kill -0 "${PID}" 2>/dev/null; then echo "Status: STALE PID FILE"; exit 1; fi
echo "Status: RUNNING"; echo "PID:    ${PID}"
if command -v curl >/dev/null 2>&1; then
  BIND="$(awk -F= '/^[[:space:]]*web\.bind[[:space:]]*=/{gsub(/[[:space:]]/,"",$2); print $2; exit}' "${CONFIG}" 2>/dev/null || true)"
  PORT="$(awk -F= '/^[[:space:]]*web\.port[[:space:]]*=/{gsub(/[[:space:]]/,"",$2); print $2; exit}' "${CONFIG}" 2>/dev/null || true)"
  BIND="${BIND:-127.0.0.1}"; PORT="${PORT:-8080}"
  [[ "${BIND}" == "0.0.0.0" || "${BIND}" == "::" ]] && BIND="127.0.0.1"
  if curl -fsS --max-time 2 "http://${BIND}:${PORT}/api/health"; then echo; else echo "Health: UNREACHABLE"; exit 1; fi
fi
