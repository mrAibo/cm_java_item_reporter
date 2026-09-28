#!/usr/bin/env bash
#
# CM Insight - restart (stop, then start).
#
#   ./bin/restart.sh [--force] [--timeout SECONDS] [--help] [-- application arguments...]
#
# --force is passed to bin/stop.sh (SIGKILL after the graceful timeout);
# --timeout is passed to both bin/stop.sh and bin/start.sh.
#
# Message prefixes: "OK:" / "WARN:" / "ERROR:".
# Exit codes: 0 restarted, 1 stop or start failed, 2 usage error.

set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: ./bin/restart.sh [--force] [--timeout SECONDS] [--help] [-- application arguments...]

Stops CM Insight (bin/stop.sh) and starts it again (bin/start.sh).

Options:
  --force             force-stop (SIGKILL) if the graceful stop times out
  --timeout SECONDS   graceful-stop and health-check timeout (defaults 30 / 30)
  --help              show this help

Everything after "--" is passed to the application launcher.
Exit codes: 0 restarted, 1 stop or start failed, 2 usage error.
USAGE
}

STOP_ARGS=()
START_ARGS=()

while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --force) STOP_ARGS+=("--force"); shift ;;
    --timeout)
      [ "$#" -ge 2 ] || { printf 'ERROR: --timeout requires a value in seconds\n' >&2; exit 2; }
      STOP_ARGS+=("--timeout" "$2")
      START_ARGS+=("--timeout" "$2")
      shift 2
      ;;
    --)
      shift
      while [ "$#" -gt 0 ]; do START_ARGS+=("$1"); shift; done
      ;;
    *)
      printf 'ERROR: unknown argument: %s (use -- before application arguments)\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

SELF="${BASH_SOURCE[0]:-$0}"
SCRIPT_DIR="$(dirname -- "${SELF}")"
[ -d "${SCRIPT_DIR}" ] || { printf 'ERROR: cannot locate script directory: %s\n' "${SCRIPT_DIR}" >&2; exit 2; }
ROOT="$(CDPATH= cd -- "${SCRIPT_DIR}/.." && pwd)" || { printf 'ERROR: cannot resolve repository root from %s\n' "${SCRIPT_DIR}" >&2; exit 2; }

[ -f "${SCRIPT_DIR}/stop.sh" ] || { printf 'ERROR: %s/stop.sh is missing\n' "${SCRIPT_DIR}" >&2; exit 1; }
[ -f "${SCRIPT_DIR}/start.sh" ] || { printf 'ERROR: %s/start.sh is missing\n' "${SCRIPT_DIR}" >&2; exit 1; }

if ! "${SCRIPT_DIR}/stop.sh" ${STOP_ARGS[@]+"${STOP_ARGS[@]}"}; then
  printf 'ERROR: the stop phase failed; not starting a second instance. Resolve the stop failure first (try ./bin/stop.sh --force).\n' >&2
  exit 1
fi

exec "${SCRIPT_DIR}/start.sh" ${START_ARGS[@]+"${START_ARGS[@]}"}
