#!/usr/bin/env bash
#
# CM Insight - shared address and health-marker model (SOURCED, never executed).
#
# ONE notion of "the address CM Insight is reachable at", shared by bin/start.sh,
# bin/status.sh, bin/stop.sh and bin/doctor.sh, so a Probe address and a Listener
# match can never disagree about web.bind:
#
#   * the PROBE address is where a health probe dials (always something local and
#     reachable, also for a wildcard bind);
#   * the LISTENER address is which sockets in the platform socket table count as
#     "this configuration's listener" (a wildcard socket covers a specific bind).
#
# Sources of truth this module mirrors exactly:
#   * conf/application.properties keys web.bind / web.port;
#   * the Java security policy's loopback test (SecurityPolicy.isLoopbackLiteral):
#     a PURE STRING TEST, no DNS - 127.0.0.0/8 dotted-quad literals, the exact name
#     "localhost" (trimmed, case-insensitive) and ::1 / 0:0:0:0:0:0:0:1 (optionally
#     bracketed or IPv6 zone-qualified). Everything else - including 0.0.0.0, ::,
#     ::ffff:127.0.0.1, "localhost." and any host name - is NOT loopback;
#   * GET /api/health, whose healthy body is exactly
#     {"status":"UP","service":"cm-insight"}: ci_health_body_is_marker compares the
#     whitespace-stripped body for EQUALITY with that string and has no substring
#     fallback (review finding H-1), because the body is what authorises a signal.
#
# Socket classification (exact | covered | sibling | foreign) encodes what may and may
# not be treated as "this configuration's listener":
#   exact   the configured address itself (its own wildcard form for a wildcard bind)
#   covered a wildcard socket that serves the configured address
#   sibling the OTHER address family's wildcard on the same port: reported, never an
#           ownership/authorization basis
#   foreign bound elsewhere
# EVIDENCE for the sibling rule (measured on this host, Windows 11 + OpenJDK 17, by
# .tools/goal01a-scripts/06-wildcard-bind.sh): a Java wildcard bind is one dual-stack
# socket that the socket table reports TWICE with the SAME owning pid
# (web.bind=0.0.0.0 -> {0.0.0.0|PID exact, ::|PID sibling}; web.bind=:: -> the mirror
# image), and an unrelated process CANNOT bind either the same address or the sibling
# family while that socket is held (both probes report BIND-FAILED), so the
# two-instance mixed-family state is not reachable here. The sibling class keeps the
# reporting truthful without letting a different process's socket in the other family
# authorise a stop.
#
# Sourcing this file has NO side effects: no output, no exit, no files, no network.
# Every function is pure string/parse logic except ci_health_marker_ok, which the
# CALLER decides to invoke and which performs at most one loopback HTTP probe.
#
# Interface (also used by bin/doctor.sh):
#   ci_addr_normalize <bind>                 -> trimmed, lowercased, unbracketed bind
#   ci_addr_is_loopback <bind>               -> 0 for loopback, 1 otherwise (NO DNS)
#   ci_addr_probe_host <bind>                -> the ONE preferred probe host
#   ci_addr_probe_hosts <bind>               -> ordered probe candidates, one per line
#   ci_addr_listen_hosts <bind>              -> socket hosts that ARE this bind's listener
#   ci_addr_wildcard_hosts <bind>            -> wildcard sockets that serve <bind>
#   ci_addr_sibling_hosts <bind>             -> the OTHER family's wildcard on the same port
#   ci_addr_socket_class <host> <bind>       -> prints exact | covered | sibling | foreign
#   ci_health_url <host> <port>              -> http://host:port/api/health (IPv6 bracketed)
#   ci_health_body_is_marker <body>          -> 0 ONLY for the EXACT CM Insight marker
#   ci_health_marker_ok <url>                -> 0 marker, 1 answered but not CM Insight,
#                                               2 no answer / cannot probe
#
# Message prefixes: none (the module never prints status).
# Exit codes: the predicates above; nothing else calls exit.

# ---------------------------------------------------------------------------
# normalisation and classification
# ---------------------------------------------------------------------------

ci_addr_normalize() {
  # $1 = configured bind value; prints the normalised form.
  _ci_bs="$1"
  # strip leading/trailing whitespace (tabs and spaces, any mix)
  while : ; do
    case "${_ci_bs}" in
      ' '*) _ci_bs="${_ci_bs# }" ;;
      '	'*) _ci_bs="${_ci_bs#	}" ;;
      *' ') _ci_bs="${_ci_bs% }" ;;
      *'	') _ci_bs="${_ci_bs%	}" ;;
      *) break ;;
    esac
  done
  _ci_bs="$(printf '%s' "${_ci_bs}" | tr 'A-Z' 'a-z')"
  case "${_ci_bs}" in
    \[*\]) _ci_bs="${_ci_bs#\[}"; _ci_bs="${_ci_bs%\]}" ;;
  esac
  printf '%s' "${_ci_bs}"
}

ci_addr_is_loopback() {
  # $1 = bind value; 0 when it is unmistakably loopback. Pure string test, no DNS:
  # a host name can resolve anywhere, so only the exact name "localhost" qualifies.
  _ci_lb="$(ci_addr_normalize "${1:-}")"
  case "${_ci_lb}" in
    localhost) return 0 ;;
    ::1|::1%*) return 0 ;;
    0:0:0:0:0:0:0:1|0:0:0:0:0:0:0:1%*) return 0 ;;
    127.*) : ;;
    *) return 1 ;;
  esac
  _ci_rest="${_ci_lb#127.}"
  case "${_ci_rest}" in ''|*[!0-9.]*) return 1 ;; esac
  _ci_dots="${_ci_rest//[^.]/}"
  [ "${#_ci_dots}" -eq 2 ] || return 1
  _ci_o1="${_ci_rest%%.*}"
  _ci_rest="${_ci_rest#*.}"
  _ci_o2="${_ci_rest%%.*}"
  _ci_o3="${_ci_rest#*.}"
  for _ci_oct in "${_ci_o1}" "${_ci_o2}" "${_ci_o3}"; do
    case "${_ci_oct}" in
      ''|*[!0-9]*) return 1 ;;
    esac
    [ "${#_ci_oct}" -le 3 ] || return 1
    [ "$((10#${_ci_oct}))" -le 255 ] || return 1
  done
  return 0
}

ci_addr_is_wildcard() {
  # $1 = bind value; 0 for a wildcard bind (all interfaces). 0.0.0.0 and :: are
  # NOT loopback (SecurityPolicy refuses them plain, section F), but they ARE the
  # bind values whose listener must be matched as a wildcard socket.
  case "$(ci_addr_normalize "${1:-}")" in
    ''|0.0.0.0|::|\*) return 0 ;;
    *) return 1 ;;
  esac
}

ci_addr_probe_hosts() {
  # $1 = bind value; prints the probe candidates in preference order, one per line.
  # 127/8 literal  -> itself (a bind on 127.0.0.5 is NOT reachable on 127.0.0.1)
  # localhost      -> 127.0.0.1, then ::1 (the resolver may pick either family)
  # 0.0.0.0        -> 127.0.0.1 (IPv4 wildcard)
  # ::             -> ::1, then 127.0.0.1 (Java never sets IPV6_V6ONLY, so a :: bind
  #                   is v6-only on some platforms and dual-stack on others; ::1 is
  #                   the candidate that works on both, 127.0.0.1 the dual-stack one)
  # anything else  -> itself, verbatim (a specific address or a host name)
  _ci_ph="$(ci_addr_normalize "${1:-}")"
  case "${_ci_ph}" in
    ''|0.0.0.0|\*) printf '127.0.0.1\n' ;;
    ::) printf '::1\n127.0.0.1\n' ;;
    localhost) printf '127.0.0.1\n::1\n' ;;
    *) printf '%s\n' "${_ci_ph}" ;;
  esac
}

ci_addr_probe_host() {
  # $1 = bind value; prints the ONE preferred probe host (first candidate).
  ci_addr_probe_hosts "${1:-}" | sed -n '1p'
}

ci_addr_listen_hosts() {
  # $1 = bind value; prints the socket-table hosts that ARE this bind's listener, one
  # per line (an exact match). Socket tables always show numeric addresses.
  #
  # SECURITY (Goal 01A section H, review finding G-1): a wildcard bind yields ONLY its
  # own wildcard socket. A socket bound to a specific address (127.0.0.1, ::1, a LAN
  # address) is NOT the listener of a wildcard configuration, because it serves only a
  # subset of the interfaces - treating it as exact let a stop run with web.bind=0.0.0.0
  # signal an instance that was bound to 127.0.0.1 only.
  _ci_bh="$(ci_addr_normalize "${1:-}")"
  case "${_ci_bh}" in
    '') printf '127.0.0.1\n' ;;
    0.0.0.0) printf '0.0.0.0\n' ;;
    ::) printf '::\n' ;;
    \*) printf '*\n0.0.0.0\n::\n' ;;
    localhost) printf '127.0.0.1\n::1\n' ;;
    *) printf '%s\n' "${_ci_bh}" ;;
  esac
}

ci_addr_wildcard_hosts() {
  # $1 = bind value; prints the wildcard socket hosts that SERVE this bind and may
  # therefore be its listener, one per line:
  #   * the same-family wildcard of a specific address (0.0.0.0 serves 127.0.0.1:PORT,
  #     :: serves ::1:PORT and any IPv6 literal);
  #   * "localhost" may be resolved to either loopback family, so both wildcards serve it;
  #   * "*" is the textual wildcard some socket tools print.
  # The OTHER family's wildcard (:: for a 0.0.0.0 configuration and vice versa) is NOT
  # listed here: it is a "sibling" - the same port in a different address family - and is
  # deliberately not an ownership/authorization basis (see ci_addr_sibling_hosts).
  _ci_bw="$(ci_addr_normalize "${1:-}")"
  case "${_ci_bw}" in
    ''|127.*) printf '0.0.0.0\n*\n' ;;
    localhost) printf '0.0.0.0\n::\n*\n' ;;
    ::1|0:0:0:0:0:0:0:1|*:*) printf '::\n*\n' ;;
    0.0.0.0) printf '0.0.0.0\n*\n' ;;
    ::) printf '::\n*\n' ;;
    \*) printf '*\n' ;;
    *) printf '0.0.0.0\n*\n' ;;
  esac
}

ci_addr_sibling_hosts() {
  # $1 = bind value; prints the OTHER address family's wildcard socket for this
  # configuration, one per line, or nothing when the configuration can be served by
  # both families (a wildcard bind, or "localhost" via either loopback family).
  #
  # EVIDENCE NOTE (independent review, G-1 follow-up): on this host (Windows 11 +
  # OpenJDK 17) a Java wildcard bind has always been observed as a SINGLE dual-stack
  # socket that the socket table reports TWICE with the SAME owning pid - for
  # web.bind=0.0.0.0 as well as for web.bind=:: ({0.0.0.0|PID, ::|PID}). Both rows are
  # therefore produced by one process, and a mixed-family pair owned by DIFFERENT
  # processes ([::]:PORT by another program while this configuration holds 0.0.0.0:PORT)
  # is a separate process that must never be signalled on the strength of this
  # configuration. It is reported as "sibling" - visible, explained, and explicitly not
  # an ownership basis.
  _ci_bs="$(ci_addr_normalize "${1:-}")"
  case "${_ci_bs}" in
    0.0.0.0) printf '::\n' ;;
    ::) printf '0.0.0.0\n' ;;
    localhost|\*) : ;;
    ::1|0:0:0:0:0:0:0:1|*:*) printf '0.0.0.0\n' ;;
    *) printf '::\n' ;;
  esac
}

ci_addr_socket_class() {
  # $1 = socket-table host, $2 = configured bind; prints exact | covered | sibling | foreign.
  #   exact   the socket is bound to the configured address (its wildcard form included)
  #   covered a wildcard socket that serves the configured address
  #   sibling the same port in the OTHER address family (reported, never an ownership
  #           or authorization basis)
  #   foreign bound elsewhere: it cannot be the listener of this configuration
  _ci_sh="$(ci_addr_normalize "${1:-}")"
  _ci_sc_bind="${2:-}"
  for _ci_h in $(ci_addr_listen_hosts "${_ci_sc_bind}"); do
    [ "${_ci_h}" = "${_ci_sh}" ] && { printf 'exact'; return 0; }
  done
  for _ci_h in $(ci_addr_wildcard_hosts "${_ci_sc_bind}"); do
    [ "${_ci_h}" = "${_ci_sh}" ] && { printf 'covered'; return 0; }
  done
  for _ci_h in $(ci_addr_sibling_hosts "${_ci_sc_bind}"); do
    [ "${_ci_h}" = "${_ci_sh}" ] && { printf 'sibling'; return 0; }
  done
  printf 'foreign'
  return 0
}

ci_addr_socket_matches_bind() {
  # $1 = socket host, $2 = bind; 0 when the socket may be this configuration's listener
  # (exact or covered - a sibling or foreign socket is never an ownership basis).
  case "$(ci_addr_socket_class "${1:-}" "${2:-}")" in
    exact|covered) return 0 ;;
    *) return 1 ;;
  esac
}

# ---------------------------------------------------------------------------
# health marker
# ---------------------------------------------------------------------------

ci_health_url() {
  # $1 = host, $2 = port; prints the API health URL (IPv6 hosts bracketed).
  _ci_hh="${1:-}"
  case "${_ci_hh}" in
    \[*\]) : ;;                      # already bracketed
    *:*) _ci_hh="[${_ci_hh}]" ;;     # a bare IPv6 literal needs brackets in a URL
  esac
  printf 'http://%s:%s/api/health' "${_ci_hh}" "${2:-}"
}

ci_health_body_is_marker() {
  # $1 = response body; 0 ONLY for the exact CM Insight health marker.
  #
  # SECURITY (Goal 01A section H, review finding H-1): this is an EXACT comparison of
  # the whitespace-stripped body with {"status":"UP","service":"cm-insight"}, with NO
  # substring fallback. A body that merely CONTAINS the marker fields - for example
  # {"status":"UP","service":"cm-insight","note":"not cm insight"}, a reordered body,
  # or any extra field - is NOT ownership evidence and must fail: accepting it would
  # let an unrelated service on the configured port be reported as CM Insight and
  # signalled by bin/stop.sh --untracked. The application returns exactly this body
  # (GET /api/health is public and returns no other fields), so nothing legitimate is
  # rejected by requiring equality.
  _ci_mb="$(printf '%s' "${1:-}" | tr -d '[:space:]')"
  [ "${_ci_mb}" = '{"status":"UP","service":"cm-insight"}' ]
}

ci_health_marker_ok() {
  # $1 = health URL.
  #   0 = the exact CM Insight marker answered
  #   1 = something answered but NOT as CM Insight (wrong status or wrong body)
  #   2 = no answer / cannot probe (curl missing, refused, timed out)
  # The body is captured in a private temp file, never echoed.
  _ci_hu="${1:-}"
  [ -n "${_ci_hu}" ] || return 2
  command -v curl >/dev/null 2>&1 || return 2
  _ci_ht="${TMPDIR:-/tmp}/cm-insight-marker.$$.${RANDOM:-0}"
  _ci_hc="$(curl -sS -o "${_ci_ht}" -w '%{http_code}' --max-time 2 "${_ci_hu}" 2>/dev/null || true)"
  _ci_hb="$(cat "${_ci_ht}" 2>/dev/null || true)"
  rm -f "${_ci_ht}" 2>/dev/null || true
  case "${_ci_hc}" in
    ''|000) return 2 ;;
    200) : ;;
    *) return 1 ;;
  esac
  ci_health_body_is_marker "${_ci_hb}" || return 1
  return 0
}
