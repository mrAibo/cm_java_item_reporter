#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${CM_INSIGHT_CONFIG:-${ROOT}/conf/application.properties}"; STRICT=false; FAIL=0
[[ "${1:-}" == "--strict" ]] && STRICT=true
echo "CM Insight doctor"; echo "================="
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"; JAVAC="${JAVA_HOME:+${JAVA_HOME}/bin/}javac"
if ! command -v "${JAVA}" >/dev/null 2>&1; then echo "FAIL java: not found"; FAIL=1; else echo "OK   java: $("${JAVA}" -version 2>&1 | head -n1)"; fi
if ! command -v "${JAVAC}" >/dev/null 2>&1; then
  echo "FAIL javac: not found"; FAIL=1
else
  VERSION="$("${JAVAC}" -version 2>&1)"; MAJOR="$(printf '%s' "${VERSION}" | sed -E 's/.* ([0-9]+).*/\1/')"
  if [[ "${MAJOR}" =~ ^[0-9]+$ ]] && (( MAJOR >= 17 )); then echo "OK   javac: ${VERSION}"; else echo "FAIL javac: JDK 17+ required (${VERSION})"; FAIL=1; fi
fi
if [[ -r "${CONFIG}" ]]; then echo "OK   config: ${CONFIG}"; else echo "FAIL config: missing/unreadable ${CONFIG}"; echo "     cp conf/application.properties.example conf/application.properties"; FAIL=1; fi
for DIR in logs data reports run; do mkdir -p "${ROOT}/${DIR}"; [[ -w "${ROOT}/${DIR}" ]] && echo "OK   writable: ${DIR}/" || { echo "FAIL writable: ${DIR}/"; FAIL=1; }; done
if compgen -G "${ROOT}/lib/ibm/*.jar" >/dev/null; then echo "OK   IBM CM libraries: found"; else echo "WARN IBM CM libraries: not installed yet"; [[ "${STRICT}" == true ]] && FAIL=1; fi
compgen -G "${ROOT}/lib/db2/*.jar" >/dev/null && echo "OK   DB2 JDBC driver: found" || echo "WARN DB2 JDBC driver: not installed"
compgen -G "${ROOT}/lib/oracle/*.jar" >/dev/null && echo "OK   Oracle JDBC driver: found" || echo "WARN Oracle JDBC driver: not installed"
exit "${FAIL}"
