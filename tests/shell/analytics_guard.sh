#!/usr/bin/env bash
#
# CM Insight - Goal 03 section 7 structural JDBC read-only guard.
#
#   ./tests/shell/analytics_guard.sh [--list] [--help] [--root DIR]
#
# Goal 03 section 7 requires direct SQL in V1/V2 to be READ-ONLY and explicitly
# says not to rely on Connection.setReadOnly(true) alone: the JDBC contract
# defines read-only as a HINT, a driver may ignore it, and a database account with
# write privileges would happily accept a DELETE. The structural guarantee is
# therefore "the analytics source cannot express a write", and this script is that
# guarantee's single implementation.
#
# It is deliberately NOT named *_test.sh: tests/shell/run.sh discovers *_test.sh
# and *.test.sh, so a helper named this way is never run directly by the suite.
# The committed test tests/shell/analytics_source_guard_test.sh owns the
# assertions - both the acceptance case AND the mutation cases that prove the
# guard still bites - and build.sh can call this script the same way it calls
# tests/shell/ibm_guard.sh, so the rule is enforced even by somebody who never
# runs the suite.
#
# SUBJECT. The guard's subject is the analytics JDBC source tree:
#
#   src/main/java/com/mraibo/cminsight/db
#
# That tree must exist and must hold at least one .java file. A missing or empty
# tree is a LAYOUT failure (exit 2), never a clean result: a rename would
# otherwise disable the whole rule silently and forever. The statistics package is
# scanned as well when it exists, because the scan coordinator is where a query is
# issued from; a tree that exists but holds no file is refused there too.
#
# WHAT IS REFUSED
#
#   1. the JDBC write/control CALLS - executeUpdate, executeLargeUpdate,
#      addBatch/executeBatch/executeLargeBatch, commit, rollback, setSavepoint,
#      prepareCall - each anchored on the call parenthesis so a getter that merely
#      resembles one (updateCount(), commitCount()) is not a false positive;
#   2. a STRING LITERAL that begins with a write/control SQL keyword (INSERT,
#      UPDATE, DELETE, MERGE, TRUNCATE, CREATE, ALTER, DROP, GRANT, REVOKE, CALL)
#      or with the JDBC CALL escape `{call`;
#   3. the JDBC CALL escape sequence `{call ...}`.
#
# WHAT IS NOT REFUSED, ON PURPOSE
#
# Ordinary Java operations on the adapter's own locals - list.add, map.remove,
# atomic.set, a method named updateCount() - are never refused. A guard that
# cannot pass is a guard that gets disabled, and this project has already paid for
# that lesson once (see tests/shell/ibm_guard.sh). The keyword rule is anchored on
# an opening string quote, so only a statement-shaped literal is refused; an
# English sentence is not, unless it literally begins with one of those keywords,
# which a diagnostic message must simply not do.
#
# KNOWN LIMIT, stated rather than hidden: this script is line oriented (grep -E),
# so a multi-line Java text block whose OWN line does not begin the keyword is not
# caught here. The committed Java twin,
# AnalyticsSourceReadOnlyGuardTest, applies the same rule to the whole file text
# with a multi-line pattern and runs on every build, so the stronger check is the
# one that always runs.
#
# Message prefixes: "OK:" success, "ERROR:" fatal (exit != 0).
# Exit codes: 0 all rules hold, 1 a rule was violated, 2 usage or layout problem.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./tests/shell/analytics_guard.sh [--list] [--help] [--root DIR]

Enforces the Goal 03 section 7 structural read-only rule for the analytics JDBC
source: no JDBC write/control call, no statement literal that begins with a
write/control SQL keyword, and no stored-procedure CALL execution.

Options:
  --list       print the scanned trees and the forbidden patterns
  --root DIR   scan the tree rooted at DIR instead of this script's repository.
               Used by the planted-control test, which mirrors the repository into
               a scratch directory and plants a violation there.
  --help       show this help

Exit codes: 0 all rules hold, 1 a rule was violated, 2 usage or layout.
USAGE
}

fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }
usage_fail() { printf 'ERROR: %s\n' "$*" >&2; usage >&2; exit 2; }

LIST_ONLY=false
ROOT_OVERRIDE=""
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --list) LIST_ONLY=true; shift ;;
    --root)
      [ "$#" -ge 2 ] || usage_fail "--root requires a directory"
      ROOT_OVERRIDE="$2"
      shift 2
      ;;
    *) usage_fail "unknown argument: $1" ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "${SELF}")" && pwd)" || { printf 'ERROR: cannot locate %s\n' "${SELF}" >&2; exit 2; }
if [ -n "${ROOT_OVERRIDE}" ]; then
  ROOT="$(CDPATH= cd -- "${ROOT_OVERRIDE}" && pwd)" || { printf 'ERROR: --root %s is not a directory\n' "${ROOT_OVERRIDE}" >&2; exit 2; }
else
  ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/../.." && pwd)" || { printf 'ERROR: cannot resolve the repository root\n' >&2; exit 2; }
fi

# The analytics JDBC source tree: required, and required to be non-empty.
PRIMARY_REL='src/main/java/com/mraibo/cminsight/db'
# Scanned when present. The scan coordinator issues queries from here, so a write
# call added there is exactly as fatal as one added in db/.
SECONDARY_RELS=('src/main/java/com/mraibo/cminsight/statistics')

# ---------------------------------------------------------------------------
# The forbidden JDBC write/control calls.
#
# Every entry is anchored on the call parenthesis. That anchor is the whole
# reason this table is safe to keep broad: `updateCount()` does not match
# `executeUpdate[[:space:]]*\(`, and a javadoc sentence mentioning commit does not
# match `commit[[:space:]]*\(`.
# ---------------------------------------------------------------------------
FORBIDDEN_CALL_PATTERNS=(
  'executeUpdate[[:space:]]*\('
  'executeLargeUpdate[[:space:]]*\('
  'addBatch[[:space:]]*\('
  'executeBatch[[:space:]]*\('
  'executeLargeBatch[[:space:]]*\('
  'commit[[:space:]]*\('
  'rollback[[:space:]]*\('
  'setSavepoint[[:space:]]*\('
  'releaseSavepoint[[:space:]]*\('
  'prepareCall[[:space:]]*\('
)

# A string literal that begins with a write/control SQL keyword. The leading `"`
# is what keeps English prose and ordinary identifiers out of the result.
SQL_KEYWORD_PATTERN='"[[:space:]]*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL)([[:space:]]|"|$)'
# A Java text-block opener immediately followed by the keyword on the same line.
TEXT_BLOCK_KEYWORD_PATTERN='"""[[:space:]]*(INSERT|UPDATE|DELETE|MERGE|TRUNCATE|CREATE|ALTER|DROP|GRANT|REVOKE|CALL)'
# The JDBC stored-procedure escape, which only ever precedes a CALL. The trailing
# class keeps `{callee()` from being mistaken for a CALL escape.
JDBC_CALL_ESCAPE_PATTERN='[{][[:space:]]*[Cc][Aa][Ll][Ll][^A-Za-z0-9_]'

if [ "${LIST_ONLY}" = true ]; then
  printf 'scanned trees (root %s):\n' "${ROOT}"
  printf '  %s   (required, must be non-empty)\n' "${PRIMARY_REL}"
  for rel in "${SECONDARY_RELS[@]}"; do printf '  %s   (scanned when present)\n' "${rel}"; done
  printf 'forbidden JDBC write/control calls:\n'
  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do printf '  %s\n' "${pattern}"; done
  printf 'forbidden statement literals:\n  %s\n  %s\n  %s\n' \
    "${SQL_KEYWORD_PATTERN}" "${TEXT_BLOCK_KEYWORD_PATTERN}" "${JDBC_CALL_ESCAPE_PATTERN}"
  exit 0
fi

[ -d "${ROOT}/src" ] || { printf 'ERROR: missing %s/src; this does not look like a CM Insight working tree\n' "${ROOT}" >&2; exit 2; }

PRIMARY_DIR="${ROOT}/${PRIMARY_REL}"
[ -d "${PRIMARY_DIR}" ] || {
  printf 'ERROR: missing %s. The analytics read-only guard has nothing to scan, which is a LAYOUT failure and not a clean result: a rename or a moved source set must never disable this rule silently.\n' "${PRIMARY_DIR}" >&2
  exit 2
}

violations=0
scanned_files=0

report_violations() {
  # report_violations <label> <pattern> <directory>
  local label="$1" pattern="$2" directory="$3" line
  while IFS= read -r line; do
    [ -n "${line}" ] || continue
    printf 'ERROR: %s: %s\n' "${label}" "${line}" >&2
    violations=$((violations + 1))
  done < <(grep -REn --include='*.java' "(${pattern})" "${directory}" 2>/dev/null || true)
}

scan_tree() {
  # scan_tree <directory> <required|optional>
  local directory="$1" requirement="$2" count=0
  local rel="${directory#"${ROOT}/"}"
  if [ ! -d "${directory}" ]; then
    if [ "${requirement}" = required ]; then
      printf 'ERROR: %s is missing; the analytics read-only guard would scan nothing\n' "${rel}" >&2
      violations=$((violations + 1))
    else
      printf 'OK: %s is absent; nothing to scan there\n' "${rel}"
    fi
    return 0
  fi
  while IFS= read -r -d '' _file; do count=$((count + 1)); done \
    < <(find "${directory}" -type f -name '*.java' -print0 2>/dev/null)
  if [ "${count}" -eq 0 ]; then
    printf 'ERROR: %s exists but contains no .java file; a guard that scans nothing must not report success\n' "${directory}" >&2
    violations=$((violations + 1))
    return 0
  fi
  scanned_files=$((scanned_files + count))

  local pattern
  for pattern in "${FORBIDDEN_CALL_PATTERNS[@]}"; do
    report_violations "forbidden JDBC write/control call under ${rel}" "${pattern}" "${directory}"
  done
  report_violations "statement literal begins with a write/control SQL keyword under ${rel}" \
    "${SQL_KEYWORD_PATTERN}" "${directory}"
  report_violations "text block begins with a write/control SQL keyword under ${rel}" \
    "${TEXT_BLOCK_KEYWORD_PATTERN}" "${directory}"
  report_violations "stored-procedure CALL escape under ${rel}" \
    "${JDBC_CALL_ESCAPE_PATTERN}" "${directory}"
  return 0
}

scan_tree "${PRIMARY_DIR}" required
for rel in "${SECONDARY_RELS[@]}"; do
  scan_tree "${ROOT}/${rel}" optional
done

if [ "${violations}" -gt 0 ]; then
  fail "${violations} Goal 03 analytics read-only violation(s) above. Direct SQL in V1/V2 is SELECT-only: the database account may have SELECT-only privileges, but application safety must not depend on the operator having set that up."
fi

printf 'OK: the analytics source tree was scanned (%s .java file(s)) and holds no JDBC write/control call\n' "${scanned_files}"
printf 'OK: all Goal 03 analytics read-only guards hold\n'
