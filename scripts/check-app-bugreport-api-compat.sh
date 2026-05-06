#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BUGREPORT_DIR="$ROOT_DIR/app/src/main/kotlin/dev/bluehouse/libredrop/bugreport"
ALLOWED_FILE="$BUGREPORT_DIR/BugReportSocDiagnostics.kt"

matches=()
if command -v rg >/dev/null 2>&1; then
  while IFS= read -r match; do
    matches+=("$match")
  done < <(rg -n 'Build\.SOC_(MANUFACTURER|MODEL)' "$BUGREPORT_DIR" || true)
else
  while IFS= read -r match; do
    matches+=("$match")
  done < <(grep -R -n -E 'Build\.SOC_(MANUFACTURER|MODEL)' "$BUGREPORT_DIR" || true)
fi

if [[ "${#matches[@]}" -eq 0 ]]; then
  echo "Expected guarded Build.SOC_* access in $ALLOWED_FILE, but found none." >&2
  exit 1
fi

unexpected_matches=()
for match in "${matches[@]}"; do
  match_file=${match%%:*}
  if [[ "$match_file" != "$ALLOWED_FILE" ]]; then
    unexpected_matches+=("$match")
  fi
done

if [[ "${#unexpected_matches[@]}" -ne 0 ]]; then
  echo "Forbidden direct Build.SOC_* access outside $ALLOWED_FILE:" >&2
  printf '  %s\n' "${unexpected_matches[@]}" >&2
  exit 1
fi

echo "Bug-report Android API compatibility check passed"
