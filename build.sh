#!/usr/bin/env bash
set -euo pipefail
ROOT="$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
BUILD_DIR="${ROOT}/build"
CLASSES_DIR="${BUILD_DIR}/classes"
JAR_FILE="${BUILD_DIR}/cm-insight.jar"

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="${JAVA_HOME}/bin/javac"; JAR="${JAVA_HOME}/bin/jar"; JAVA="${JAVA_HOME}/bin/java"
else
  JAVAC="$(command -v javac || true)"; JAR="$(command -v jar || true)"; JAVA="$(command -v java || true)"
fi
[[ -x "${JAVAC}" ]] || { echo "ERROR: javac not found (JDK 17+ required)" >&2; exit 2; }
[[ -x "${JAR}" ]] || { echo "ERROR: jar tool not found" >&2; exit 2; }
[[ -x "${JAVA}" ]] || { echo "ERROR: java not found" >&2; exit 2; }

VERSION_OUTPUT="$("${JAVAC}" -version 2>&1)"
MAJOR="$(printf '%s' "${VERSION_OUTPUT}" | sed -E 's/.* ([0-9]+).*/\1/')"
[[ "${MAJOR}" =~ ^[0-9]+$ ]] || { echo "ERROR: cannot parse javac version: ${VERSION_OUTPUT}" >&2; exit 2; }
(( MAJOR >= 17 )) || { echo "ERROR: JDK 17+ required, found: ${VERSION_OUTPUT}" >&2; exit 2; }

rm -rf "${CLASSES_DIR}"
mkdir -p "${CLASSES_DIR}"
mapfile -d '' SOURCES < <(find "${ROOT}/src/main/java" -type f -name '*.java' -print0 | sort -z)
[[ ${#SOURCES[@]} -gt 0 ]] || { echo "ERROR: no Java sources found" >&2; exit 2; }

shopt -s nullglob
JARS=("${ROOT}"/lib/ibm/*.jar "${ROOT}"/lib/db2/*.jar "${ROOT}"/lib/oracle/*.jar "${ROOT}"/lib/app/*.jar)
CP=""
if (( ${#JARS[@]} > 0 )); then OLD_IFS="${IFS}"; IFS=:; CP="${JARS[*]}"; IFS="${OLD_IFS}"; fi

JAVAC_ARGS=(--release 17 -encoding UTF-8 -Xlint:all -d "${CLASSES_DIR}")
[[ -z "${CP}" ]] || JAVAC_ARGS+=(-cp "${CP}")
"${JAVAC}" "${JAVAC_ARGS[@]}" "${SOURCES[@]}"

if [[ -d "${ROOT}/src/main/resources" ]]; then cp -R "${ROOT}/src/main/resources/." "${CLASSES_DIR}/"; fi

echo "Running bootstrap self-test..."
"${JAVA}" -cp "${CLASSES_DIR}${CP:+:${CP}}" com.mraibo.cminsight.app.SelfTest

mkdir -p "${BUILD_DIR}"
cat > "${BUILD_DIR}/manifest.mf" <<'MANIFEST'
Manifest-Version: 1.0
Main-Class: com.mraibo.cminsight.app.Main
Implementation-Title: CM Insight
Implementation-Version: 0.1.0-SNAPSHOT
MANIFEST
"${JAR}" cfm "${JAR_FILE}" "${BUILD_DIR}/manifest.mf" -C "${CLASSES_DIR}" .
printf '%s\n' "0.1.0-SNAPSHOT" > "${BUILD_DIR}/.version"
echo "Built: ${JAR_FILE}"
