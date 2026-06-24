#!/usr/bin/env bash
# igata 鋳型 — run the whole test suite with one command.
# Standalone-runnable tests (the repo pytest plugin env is broken); each exits non-zero on failure.
set -uo pipefail
cd "$(dirname "$0")"

SUITES=(
  "methods/test_charter_gates.py"
)

fail=0
for s in "${SUITES[@]}"; do
  dir="$(dirname "$s")"; file="$(basename "$s")"
  if ( cd "$dir" && python3 "$file" ); then :; else
    echo "FAILED: $s"; fail=1
  fi
done

if [ "$fail" -eq 0 ]; then
  echo "── igata: ALL suites green ──"
else
  echo "── igata: FAILURES above ──"; exit 1
fi
