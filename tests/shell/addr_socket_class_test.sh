#!/usr/bin/env bash
#
# CM Insight - committed regression for IPv4-mapped IPv6 LISTENER classification.
#
#   ./tests/shell/addr_socket_class_test.sh [--help]
#
# Goal 01B section C/H. On Linux a dual-stack JVM reports the listener of an IPv4
# configuration in its IPv4-MAPPED IPv6 spelling:
#
#     web.bind=127.0.0.1   ->   ss -ltnp   ->   ::ffff:127.0.0.1:8080
#
# The pre-fix classifier compared socket-table hosts with the configured bind by raw
# string equality, so that row was called FOREIGN, bin/stop.sh dropped the row that
# carried the live CM Insight marker and refused ownership of the instance it had just
# started (failed push Actions run 36443449216, step "Start").
#
# Scope of this test: SOCKET EQUIVALENCE ONLY (bin/lib/cm-insight-addr.sh, the
# ci_addr_* layer). It deliberately also pins the boundary: the mapped spelling must
# NOT become loopback for CONFIGURED input, because that is web-exposure policy
# (SecurityPolicy.isLoopbackLiteral) and is a different concern. Relaxing it in the
# shell would silently give a configured `web.bind=::ffff:127.0.0.1` the loopback
# privileges of 127.0.0.1.
#
# The test is pure string logic: no network, no sockets, no curl/java/ss, no process
# table, no writes outside the repository. It fails against the pre-fix classifier
# (that is the point) and it is discovered by tests/shell/run.sh via the *_test.sh glob.
#
# Message prefixes: "ok:" / "FAIL:" / "PASS:" / "FAIL: <file>".
# Exit codes: 0 every assertion passed, 1 at least one assertion failed, 2 usage error.

set -uo pipefail

SELF="${BASH_SOURCE[0]:-$0}"
TEST_DIR="$(cd -- "$(dirname -- "${SELF}")" && pwd)"
REPO_ROOT="$(cd -- "${TEST_DIR}/../.." && pwd)"
ADDR_LIB="${REPO_ROOT}/bin/lib/cm-insight-addr.sh"

usage() {
  cat <<'USAGE'
Usage: ./tests/shell/addr_socket_class_test.sh [--help]

Pins the IPv4-mapped IPv6 listener classification of bin/lib/cm-insight-addr.sh
(exact/covered/sibling/foreign classes and ci_addr_socket_matches_bind), plus the
boundary that keeps SecurityPolicy's configured-input rule untouched.

No arguments, no environment, no network: pure string logic over the sourced library.
Exit codes: 0 all assertions passed, 1 a failure, 2 usage error.
USAGE
}

for arg in "$@"; do
  case "${arg}" in
    -h|--help) usage; exit 0 ;;
    *) printf 'ERROR: unknown argument: %s\n' "${arg}" >&2; usage >&2; exit 2 ;;
  esac
done

if [ ! -r "${ADDR_LIB}" ]; then
  printf 'FAIL: %s is missing or unreadable\n' "${ADDR_LIB}" >&2
  exit 1
fi
# shellcheck source=/dev/null
. "${ADDR_LIB}"

TOTAL=0
FAILED=0

pass() { TOTAL=$((TOTAL + 1)); printf 'ok: %s\n' "$1"; }
fail() { TOTAL=$((TOTAL + 1)); FAILED=$((FAILED + 1)); printf 'FAIL: %s\n' "$1"; }

# assert_class <label> <socket host> <configured bind> <expected class>
assert_class() {
  _ac_got="$(ci_addr_socket_class "$2" "$3")"
  if [ "${_ac_got}" = "$4" ]; then
    pass "$1 [class=${_ac_got}]"
  else
    fail "$1 [expected class=$4, got ${_ac_got}]"
  fi
}

# assert_matches <label> <socket host> <configured bind> <yes|no>
assert_matches() {
  if ci_addr_socket_matches_bind "$2" "$3"; then
    _am_rc=0
  else
    _am_rc=1
  fi
  if { [ "$4" = yes ] && [ "${_am_rc}" -eq 0 ]; } || { [ "$4" = no ] && [ "${_am_rc}" -ne 0 ]; }; then
    pass "$1 [matches_bind=$4 (rc=${_am_rc})]"
  else
    _am_cls="$(ci_addr_socket_class "$2" "$3")"
    fail "$1 [expected matches_bind=$4, got rc=${_am_rc} (class=${_am_cls})]"
  fi
}

# assert_true <label> <command...>: 0 expected
assert_true() {
  _at_label="$1"; shift
  if "$@"; then
    pass "$_at_label"
  else
    fail "$_at_label [expected success, got non-zero]"
  fi
}

# assert_false <label> <command...>: non-zero expected
assert_false() {
  _af_label="$1"; shift
  if "$@"; then
    fail "$_af_label [expected non-zero, got success]"
  else
    pass "$_af_label"
  fi
}

# assert_eq <label> <expected> <actual>
assert_eq() {
  if [ "$2" = "$3" ]; then
    pass "$1 [$3]"
  else
    fail "$1 [expected '$2', got '$3']"
  fi
}

printf '# ipv4-mapped ipv6 listener classification (%s)\n' "${ADDR_LIB}"

# ---------------------------------------------------------------------------
# 1. the mapped spelling SERVES the underlying IPv4 address (Goal 01B section C)
# ---------------------------------------------------------------------------
# The kernel socket is an IPv4 socket for 127.0.0.1, so the mapped row is the
# configured address itself: "exact". Anything other than foreign is what the goal
# requires; "exact" is the class that means "bound to the configured address".
assert_class 'config 127.0.0.1 + socket ::ffff:127.0.0.1 is the configured listener' \
  '::ffff:127.0.0.1' '127.0.0.1' 'exact'
assert_matches 'config 127.0.0.1 + socket ::ffff:127.0.0.1 is an ownership basis' \
  '::ffff:127.0.0.1' '127.0.0.1' yes

assert_class 'bracketed socket host [::ffff:127.0.0.1] classifies the same' \
  '[::ffff:127.0.0.1]' '127.0.0.1' 'exact'
assert_class 'raw socket-table field [::ffff:127.0.0.1]:8080 classifies the same' \
  '[::ffff:127.0.0.1]:8080' '127.0.0.1' 'exact'

assert_class 'fully expanded mapped form 0:0:0:0:0:ffff:127.0.0.1 classifies the same' \
  '0:0:0:0:0:ffff:127.0.0.1' '127.0.0.1' 'exact'
assert_class 'zero-padded expanded mapped form classifies the same' \
  '0000:0000:0000:0000:0000:ffff:127.0.0.1' '127.0.0.1' 'exact'
assert_class 'mapped form with hex groups ::ffff:7f00:1 classifies the same' \
  '::ffff:7f00:1' '127.0.0.1' 'exact'
assert_class 'expanded mapped form with hex groups classifies the same' \
  '0:0:0:0:0:ffff:7f00:1' '127.0.0.1' 'exact'

assert_class 'config 127.0.0.5 + socket ::ffff:127.0.0.5 is equivalent' \
  '::ffff:127.0.0.5' '127.0.0.5' 'exact'
assert_matches 'config 127.0.0.5 + socket ::ffff:127.0.0.5 is an ownership basis' \
  '::ffff:127.0.0.5' '127.0.0.5' yes

# ---------------------------------------------------------------------------
# 2. no over-reach: a mapped literal never turns into a DIFFERENT address
# ---------------------------------------------------------------------------
assert_class 'mapped 192.0.2.10 does NOT become 127.0.0.1 (no substring games)' \
  '::ffff:192.0.2.10' '127.0.0.1' 'foreign'
assert_matches 'mapped 192.0.2.10 is not an ownership basis for 127.0.0.1' \
  '::ffff:192.0.2.10' '127.0.0.1' no
assert_class 'unrelated mapped IPv4 stays foreign' \
  '::ffff:198.51.100.7' '198.51.100.8' 'foreign'
assert_class 'another 127/8 literal is NOT equivalent (no 127/8 sloppiness)' \
  '::ffff:127.0.0.4' '127.0.0.5' 'foreign'
assert_class 'mapped 127.0.0.1 is foreign for a 127.0.0.5 configuration' \
  '::ffff:127.0.0.1' '127.0.0.5' 'foreign'
assert_class 'plain 192.0.2.10 stays foreign for 127.0.0.1' \
  '192.0.2.10' '127.0.0.1' 'foreign'
assert_class 'a zone-qualified mapped literal is not treated as a plain mapping' \
  '::ffff:127.0.0.1%eth0' '127.0.0.1' 'foreign'
assert_class 'a non-zero prefix (fe80::ffff:127.0.0.1) is not a mapping' \
  'fe80::ffff:127.0.0.1' '127.0.0.1' 'foreign'

# ---------------------------------------------------------------------------
# 3. wildcard semantics survive intact (Goal 01A section H, finding G-1)
# ---------------------------------------------------------------------------
assert_class 'IPv4 wildcard covers the configured 127.0.0.1 (covered)' \
  '0.0.0.0' '127.0.0.1' 'covered'
assert_class 'the IPv4 wildcard in mapped spelling covers 127.0.0.1' \
  '::ffff:0.0.0.0' '127.0.0.1' 'covered'
assert_class 'the IPv4 wildcard in mapped spelling is exact for a 0.0.0.0 config' \
  '::ffff:0.0.0.0' '0.0.0.0' 'exact'
assert_class 'a specific mapped socket is NOT the listener of a wildcard config' \
  '::ffff:127.0.0.1' '0.0.0.0' 'foreign'
assert_class 'the other family wildcard stays sibling (never ownership)' \
  '::' '127.0.0.1' 'sibling'
assert_matches 'sibling is never an ownership basis' \
  '::' '127.0.0.1' no
assert_class 'the textual wildcard * is covered for a 0.0.0.0 config' \
  '*' '0.0.0.0' 'covered'
assert_class 'the wildcard config itself stays exact' \
  '0.0.0.0' '0.0.0.0' 'exact'
assert_class 'the other family wildcard stays sibling for a 0.0.0.0 config' \
  '::' '0.0.0.0' 'sibling'
assert_class 'an IPv6 loopback config matches its own literal' \
  '::1' '::1' 'exact'
assert_class 'an IPv4 config still matches its own literal' \
  '127.0.0.1' '127.0.0.1' 'exact'
assert_class 'a localhost config still matches 127.0.0.1' \
  '127.0.0.1' 'localhost' 'exact'
assert_class 'a localhost config also matches the mapped spelling of 127.0.0.1' \
  '::ffff:127.0.0.1' 'localhost' 'exact'

# ---------------------------------------------------------------------------
# 4. BOUNDARY: socket equivalence is not web-exposure policy (Goal 01B section C)
#    SecurityPolicy.isLoopbackLiteral("::ffff:127.0.0.1") must stay NON-loopback:
#    a configured mapped spelling must not silently gain loopback privileges
#    merely because the socket parser understands the mapping.
# ---------------------------------------------------------------------------
assert_false 'configured ::ffff:127.0.0.1 is still NOT loopback' \
  ci_addr_is_loopback '::ffff:127.0.0.1'
assert_false 'configured [::ffff:127.0.0.1] is still NOT loopback' \
  ci_addr_is_loopback '[::ffff:127.0.0.1]'
assert_false 'configured 0:0:0:0:0:ffff:127.0.0.1 is still NOT loopback' \
  ci_addr_is_loopback '0:0:0:0:0:ffff:127.0.0.1'
assert_false 'a configured mapped wildcard spelling is still NOT a wildcard bind' \
  ci_addr_is_wildcard '::ffff:0.0.0.0'
assert_true 'configured 127.0.0.1 is still loopback (control)' \
  ci_addr_is_loopback '127.0.0.1'
assert_true 'configured ::1 is still loopback (control)' \
  ci_addr_is_loopback '::1'
assert_eq 'the configured mapped spelling is still probed at itself, not at loopback' \
  '::ffff:127.0.0.1' "$(ci_addr_probe_host '::ffff:127.0.0.1')"
assert_eq 'a wildcard configuration is still probed at 127.0.0.1' \
  '127.0.0.1' "$(ci_addr_probe_host '0.0.0.0')"

printf '# %s: %s assertion(s), %s failed\n' "${SELF##*/}" "${TOTAL}" "${FAILED}"
if [ "${FAILED}" -eq 0 ]; then
  printf 'PASS: %s\n' "${SELF##*/}"
  exit 0
fi
printf 'FAIL: %s (%s failed)\n' "${SELF##*/}" "${FAILED}"
exit 1
