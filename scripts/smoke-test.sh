#!/usr/bin/env bash
set -euo pipefail

if [[ "${MINCORE_RCON_HOST:-}" == "" || "${MINCORE_RCON_PASSWORD:-}" == "" ]]; then
  cat <<'MSG'
Configure MINCORE_RCON_HOST, MINCORE_RCON_PORT (defaults to 25575), and MINCORE_RCON_PASSWORD to
run the automated smoke test via RCON. See docs/SMOKE_TEST.md for the manual checklist.
MSG
  exit 1
fi

HOST=${MINCORE_RCON_HOST}
PORT=${MINCORE_RCON_PORT:-25575}
PASS=${MINCORE_RCON_PASSWORD}
RCON_BIN=${MINCORE_RCON_BIN:-mcrcon}

run() {
  local cmd=$1
  echo "[mincore-smoke] $cmd"
  ${RCON_BIN} -H "${HOST}" -P "${PORT}" -p "${PASS}" "${cmd}"
}

run "mincore db ping"
run "mincore db info"
run "mincore diag"
run "mincore jobs list"
run "mincore jobs run backup"
run "mincore backup now"
run "mincore ledger recent 5"
run "mincore doctor --counts"

echo "[mincore-smoke] Automated portion complete. Finish manual steps in docs/SMOKE_TEST.md."
