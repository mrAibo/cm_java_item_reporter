#!/usr/bin/env bash
#
# CM Insight - committed shell regression suite runner (Goal 01B section F).
#
#   ./tests/shell/run.sh [--list] [--only PATTERN] [--help]
#
# Runs every committed regression test under tests/shell/ and fails (non-zero) when
# any of them fails. CI executes exactly this command, so a committed test is a real
# gate and not a file somebody remembers to run by hand.
#
# Discovery (see tests/shell/README.md for the full test-file contract):
#   tests/shell/*_test.sh   the primary pattern
#   tests/shell/*.test.sh   accepted as well, so no contributor has to rename a file
# Files matching both patterns are run once. Helper files (*.sh without a test
# suffix, e.g. a shared snippet) are never discovered.
#
# Each test runs in its own `bash` process, from the repository root, with stdin
# closed, no arguments, and a per-file timeout. Exit 0 = pass, ANY non-zero = fail;
# there is no "known failure" list and no `|| true` anywhere in this path. A suite
# that discovers zero tests FAILS instead of reporting a meaningless success.
#
# Environment:
#   CM_INSIGHT_TEST_TIMEOUT        per-file timeout in seconds (default 900)
#   CM_INSIGHT_TEST_TOTAL_BUDGET   whole-suite budget in seconds (default 1800); tests
#                                  that cannot start inside it are reported as
#                                  skipped failures, never as passes
#   GITHUB_ACTIONS                 when "true", failures also emit ::error:: workflow
#                                  commands so they show up as check-run annotations
#
# WHY THESE DEFAULTS ARE LARGER THAN THEY LOOK: the guard suites are the slow ones, and
# legitimately so. The analytics read-only guard now proves its rules SENSITIVELY - for
# every statement-literal rule it plants a known-bad sample that must be refused and a
# known-good sample that must be accepted, each in its own copy of the source tree - and
# the IBM guard does comparable work. On this host a copy-heavy guard run costs over three
# minutes of mostly SYSTEM time, because the working tree sits on a filesystem where
# copying is far more expensive than in a native Linux checkout. A 300 s cap therefore
# turned a passing suite into "timed out after 300s", which is the worst kind of red: it
# blames the code for the environment. The cap exists to catch a HANG, not to race a
# thorough guard, so it is set well above the observed cost while still being a real bound.
#
# Message prefixes: "===" section headers, "ok:"/"FAIL:"/"skip:" per test.
# Exit codes: 0 all tests passed, 1 at least one failed / none discovered, 2 usage.

set -u

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
REPO_ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

PER_FILE_TIMEOUT="${CM_INSIGHT_TEST_TIMEOUT:-900}"
TOTAL_BUDGET="${CM_INSIGHT_TEST_TOTAL_BUDGET:-1800}"
ONLY=""
LIST_ONLY=false

usage() {
  cat <<'USAGE'
Usage: ./tests/shell/run.sh [--list] [--only PATTERN] [--help]

Runs every committed shell regression test under tests/shell/ (discovery patterns:
*_test.sh and *.test.sh), one bash process each, from the repository root, with a
per-file timeout. Exit code 0 means every test passed.

Options:
  --list            list the discovered tests and exit
  --only PATTERN    run only tests whose path contains PATTERN
  --help            show this help

Environment:
  CM_INSIGHT_TEST_TIMEOUT       per-file timeout in seconds (default 300)
  CM_INSIGHT_TEST_TOTAL_BUDGET  whole-suite budget in seconds (default 600)

Conventions the runner relies on:
  * each test is executable, accepts no arguments, and needs no environment;
  * exit 0 = pass, any non-zero exit = fail;
  * tests are self-contained, dependency-free, offline and clean up after themselves.
USAGE
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --list) LIST_ONLY=true; shift ;;
    --only)
      [ "$#" -ge 2 ] || { printf 'ERROR: --only requires a pattern\n' >&2; exit 2; }
      ONLY="$2"
      shift 2
      ;;
    *) printf 'ERROR: unknown argument: %s\n' "$1" >&2; usage >&2; exit 2 ;;
  esac
done

case "${PER_FILE_TIMEOUT}" in ''|*[!0-9]*) printf 'ERROR: CM_INSIGHT_TEST_TIMEOUT must be a positive integer (got: %s)\n' "${PER_FILE_TIMEOUT}" >&2; exit 2 ;; esac
case "${TOTAL_BUDGET}" in ''|*[!0-9]*) printf 'ERROR: CM_INSIGHT_TEST_TOTAL_BUDGET must be a positive integer (got: %s)\n' "${TOTAL_BUDGET}" >&2; exit 2 ;; esac

# ---------------------------------------------------------------------------
# discovery
# ---------------------------------------------------------------------------
FOUND=""
for _pattern in '*_test.sh' '*.test.sh'; do
  for _f in "${SCRIPT_DIR}"/${_pattern}; do
    [ -f "${_f}" ] || continue
    FOUND="${FOUND}${_f}"$'\n'
  done
done
FOUND="$(printf '%s' "${FOUND}" | sed '/^$/d' | sort -u)"

if [ -n "${ONLY}" ]; then
  FOUND="$(printf '%s\n' "${FOUND}" | sed '/^$/d' | grep -F -- "${ONLY}" || true)"
fi

TEST_COUNT=0
if [ -n "${FOUND}" ]; then
  TEST_COUNT="$(printf '%s\n' "${FOUND}" | sed '/^$/d' | wc -l | tr -d ' ')"
fi

if [ "${LIST_ONLY}" = true ]; then
  if [ "${TEST_COUNT}" -eq 0 ]; then
    printf '(no tests discovered under %s)\n' "${SCRIPT_DIR}"
    exit 1
  fi
  printf '%s\n' "${FOUND}"
  exit 0
fi

printf '=== CM Insight shell regression suite ===\n'
printf 'tests      : %s discovered under %s\n' "${TEST_COUNT}" "${SCRIPT_DIR}"
printf 'limits     : %ss per file, %ss total budget\n' "${PER_FILE_TIMEOUT}" "${TOTAL_BUDGET}"
printf 'command    : %s\n' "${BASH_SOURCE[0]:-$0}${ONLY:+ --only ${ONLY}}"

if [ "${TEST_COUNT}" -eq 0 ]; then
  printf 'FAIL: the suite discovered no tests; a green run here would be meaningless\n' >&2
  [ "${GITHUB_ACTIONS:-}" = "true" ] && printf '::error::shell regression suite discovered no tests under tests/shell/\n' >&2
  exit 1
fi

TIMEOUT_CMD=""
if command -v timeout >/dev/null 2>&1; then
  TIMEOUT_CMD="timeout"
fi

# ---------------------------------------------------------------------------
# execution
# ---------------------------------------------------------------------------
PASSED=""
FAILED=""
NOT_RUN=""
SUITE_START="${SECONDS}"
cd "${REPO_ROOT}" || { printf 'ERROR: cannot enter %s\n' "${REPO_ROOT}" >&2; exit 2; }

while IFS= read -r TEST_FILE; do
  [ -n "${TEST_FILE}" ] || continue
  NAME="${TEST_FILE#"${REPO_ROOT}/"}"
  [ "${NAME}" = "${TEST_FILE}" ] && NAME="$(basename -- "${TEST_FILE}")"
  ELAPSED=$((SECONDS - SUITE_START))
  if [ "${ELAPSED}" -ge "${TOTAL_BUDGET}" ]; then
    printf '\n--- %s : NOT RUN (suite budget %ss exhausted after %ss) ---\n' "${NAME}" "${TOTAL_BUDGET}" "${ELAPSED}"
    NOT_RUN="${NOT_RUN}${NAME}"$'\n'
    continue
  fi
  REMAINING=$((TOTAL_BUDGET - ELAPSED))
  EFF_TIMEOUT="${PER_FILE_TIMEOUT}"
  [ "${REMAINING}" -lt "${EFF_TIMEOUT}" ] && EFF_TIMEOUT="${REMAINING}"

  printf '\n--- %s ---\n' "${NAME}"
  FILE_START="${SECONDS}"
  if [ -n "${TIMEOUT_CMD}" ]; then
    "${TIMEOUT_CMD}" "${EFF_TIMEOUT}" bash "${TEST_FILE}" </dev/null
    RC=$?
  else
    bash "${TEST_FILE}" </dev/null
    RC=$?
  fi
  FILE_ELAPSED=$((SECONDS - FILE_START))

  if [ "${RC}" -eq 0 ]; then
    printf 'ok: %s passed (%ss)\n' "${NAME}" "${FILE_ELAPSED}"
    PASSED="${PASSED}${NAME} (${FILE_ELAPSED}s)"$'\n'
  else
    case "${RC}" in
      124|143) DETAIL="timed out after ${EFF_TIMEOUT}s" ;;
      *) DETAIL="exit ${RC}" ;;
    esac
    printf 'FAIL: %s failed (%s, %ss)\n' "${NAME}" "${DETAIL}" "${FILE_ELAPSED}" >&2
    if [ "${GITHUB_ACTIONS:-}" = "true" ]; then
      printf '::error::shell regression test failed: %s (%s)\n' "${NAME}" "${DETAIL}" >&2
    fi
    FAILED="${FAILED}${NAME} (${DETAIL}, ${FILE_ELAPSED}s)"$'\n'
  fi
done <<< "${FOUND}"

SUITE_ELAPSED=$((SECONDS - SUITE_START))
PASS_COUNT=0
[ -n "${PASSED}" ] && PASS_COUNT="$(printf '%s\n' "${PASSED}" | sed '/^$/d' | wc -l | tr -d ' ')"
FAIL_COUNT=0
[ -n "${FAILED}" ] && FAIL_COUNT="$(printf '%s\n' "${FAILED}" | sed '/^$/d' | wc -l | tr -d ' ')"
NOT_RUN_COUNT=0
[ -n "${NOT_RUN}" ] && NOT_RUN_COUNT="$(printf '%s\n' "${NOT_RUN}" | sed '/^$/d' | wc -l | tr -d ' ')"

printf '\n=== suite summary ===\n'
[ -n "${PASSED}" ] && printf '%s\n' "${PASSED}" | sed '/^$/d;s/^/PASS: /'
[ -n "${FAILED}" ] && printf '%s\n' "${FAILED}" | sed '/^$/d;s/^/FAIL: /'
[ -n "${NOT_RUN}" ] && printf '%s\n' "${NOT_RUN}" | sed '/^$/d;s/^/NOT RUN: /'
printf 'suite: %s passed, %s failed, %s not run in %ss (per-file limit %ss, total budget %ss)\n' \
  "${PASS_COUNT}" "${FAIL_COUNT}" "${NOT_RUN_COUNT}" "${SUITE_ELAPSED}" "${PER_FILE_TIMEOUT}" "${TOTAL_BUDGET}"

if [ "${FAIL_COUNT}" -gt 0 ] || [ "${NOT_RUN_COUNT}" -gt 0 ]; then
  exit 1
fi
printf 'ok: every committed shell regression test passed\n'
exit 0
