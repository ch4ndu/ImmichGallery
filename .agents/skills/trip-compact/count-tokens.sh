#!/usr/bin/env sh
# Compatibility entrypoint for older project instructions.
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
COUNTER="$SCRIPT_DIR/../../trip/bin/token_estimate.py"

if [ ! -f "$COUNTER" ]; then
  echo "error: installed TRIP token counter not found: $COUNTER" >&2
  exit 2
fi

exec python3 "$COUNTER" "$@"
