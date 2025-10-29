#!/usr/bin/env bash
set -euo pipefail

# shellcheck disable=SC2016
get_module_enabled() {
  local module=$1
  python3 - "$module" <<'PY'
import json
import re
import sys
from pathlib import Path

config_path = Path("config/holarki.json5")
module_name = sys.argv[1]

if not config_path.exists():
    print("false")
    sys.exit(0)

raw = config_path.read_text(encoding="utf-8")
stripped = re.sub(r"/\*.*?\*/", "", raw, flags=re.S)
stripped = re.sub(r"//.*", "", stripped)
stripped = re.sub(r",(?=\s*[}\]])", "", stripped)

pattern = re.compile(r"([\{\[,]\s*)([A-Za-z0-9_]+)\s*:")

def replacer(match):
    prefix, key = match.groups()
    return f'{prefix}"{key}":'

while True:
    stripped, count = pattern.subn(replacer, stripped)
    if count == 0:
        break

try:
    config = json.loads(stripped)
except json.JSONDecodeError:
    print("false")
    sys.exit(0)

enabled = (
    config.get("modules", {})
    .get(module_name, {})
    .get("enabled", False)
)

print("true" if enabled else "false")
PY
}

if [[ "${HOLARKI_RCON_HOST:-}" == "" || "${HOLARKI_RCON_PASSWORD:-}" == "" ]]; then
  cat <<'MSG'
Configure HOLARKI_RCON_HOST, HOLARKI_RCON_PORT (defaults to 25575), and HOLARKI_RCON_PASSWORD to
run the automated smoke test via RCON. See docs/SMOKE_TEST.md for the manual checklist.
MSG
  exit 1
fi

HOST=${HOLARKI_RCON_HOST}
PORT=${HOLARKI_RCON_PORT:-25575}
PASS=${HOLARKI_RCON_PASSWORD}
RCON_BIN=${HOLARKI_RCON_BIN:-mcrcon}

run() {
  local cmd=$1
  echo "[holarki] $cmd"
  ${RCON_BIN} -H "${HOST}" -P "${PORT}" -p "${PASS}" "${cmd}"
}

run "holarki db ping"
run "holarki db info"
run "holarki diag"
if [[ $(get_module_enabled scheduler) == "true" ]]; then
  run "holarki jobs list"
  run "holarki jobs run backup"
  run "holarki backup now"
else
  echo "[holarki] Skipping scheduler checks and backup command (modules.scheduler.enabled=false)"
fi
if [[ $(get_module_enabled ledger) == "true" ]]; then
  run "holarki ledger recent 5"
else
  echo "[holarki] Skipping ledger check (modules.ledger.enabled=false)"
fi
run "holarki doctor --counts"

echo "[holarki] Automated portion complete. Finish manual steps in docs/SMOKE_TEST.md."
