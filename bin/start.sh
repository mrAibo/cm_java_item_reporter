#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
RUN_DIR="${ROOT}/run"; LOG_DIR="${ROOT}/logs"
PID_FILE="${RUN_DIR}/cm-insight.pid"; OUT_FILE="${LOG_DIR}/cm-insight.out"
mkdir -p "${RUN_DIR}" "${LOG_DIR}"
if [[ -f "${PID_FILE}" ]]; then
  PID="$(cat "${PID_FILE}" 2>/dev/null || true)"
  if [[ "${PID}" =~ ^[0-9]+$ ]] && kill -0 "${PID}" 2>/dev/null; then echo "CM Insight already running (PID ${PID})"; exit 0; fi
  rm -f "${PID_FILE}"
fi
"${ROOT}/bin/doctor.sh"
nohup "${ROOT}/bin/cm-insight" "$@" >>"${OUT_FILE}" 2>&1 &
PID=$!; printf '%s\n' "${PID}" > "${PID_FILE}"
for _ in {1..20}; do
  if ! kill -0 "${PID}" 2>/dev/null; then echo "ERROR: exited during startup; see ${OUT_FILE}" >&2; rm -f "${PID_FILE}"; exit 1; fi
  if command -v curl >/dev/null 2>&1; then
    PORT="$(awk -F= '/^[[:space:]]*web\.port[[:space:]]*=/{gsub(/[[:space:]]/,"",$2); print $2; exit}' "${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}" 2>/dev/null || true)"
    PORT="${PORT:-8080}"
    if curl -fsS --max-time 1 "http://127.0.0.1:${PORT}/api/health" >/dev/null 2>&1; then echo "CM Insight started (PID ${PID}, health OK)"; exit 0; fi
  else
    sleep 1; echo "CM Insight started (PID ${PID}); process check only"; exit 0
  fi
  sleep 1
done
echo "CM Insight is running (PID ${PID}); health not yet confirmed"
