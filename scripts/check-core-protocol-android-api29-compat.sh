#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
JAR_PATH="${1:-}"

if [[ -z "$JAR_PATH" ]]; then
  JAR_CANDIDATES=()
  while IFS= read -r jar_candidate; do
    JAR_CANDIDATES+=("$jar_candidate")
  done < <(find "$ROOT_DIR/core-protocol/build/libs" -maxdepth 1 -name '*.jar' | sort)
  if [[ "${#JAR_CANDIDATES[@]}" -ne 1 ]]; then
    echo "Expected exactly one :core-protocol jar under core-protocol/build/libs, found ${#JAR_CANDIDATES[@]}." >&2
    printf 'Candidates:\n' >&2
    printf '  %s\n' "${JAR_CANDIDATES[@]}" >&2
    exit 1
  fi
  JAR_PATH="${JAR_CANDIDATES[0]}"
fi

if [[ ! -f "$JAR_PATH" ]]; then
  echo "Jar not found: $JAR_PATH" >&2
  exit 1
fi

check_reference() {
  local class_name="$1"
  local output="$2"
  local matched=0

  if grep -q 'Fieldref.*java/math/BigInteger.TWO:' <<<"$output"; then
    echo "Forbidden Android API 29-incompatible field reference in $class_name: java/math/BigInteger.TWO" >&2
    matched=1
  fi

  if grep -q 'Methodref.*java/nio/file/Path.of:' <<<"$output" || \
    grep -q 'InterfaceMethodref.*java/nio/file/Path.of:' <<<"$output"; then
    echo "Forbidden Android API 29-incompatible method reference in $class_name: java/nio/file/Path.of(...)" >&2
    matched=1
  fi

  return "$matched"
}

FOUND_INCOMPATIBLE_REF=0

CLASS_CANDIDATES=()
if command -v zipgrep >/dev/null 2>&1; then
  while IFS= read -r entry; do
    [[ "$entry" == *.class ]] || continue
    CLASS_CANDIDATES+=("$entry")
  done < <(
    {
      zipgrep -a -h -l 'java/math/BigInteger' "$JAR_PATH" || true
      zipgrep -a -h -l 'java/nio/file/Path' "$JAR_PATH" || true
    } | sort -u
  )
else
  while IFS= read -r entry; do
    [[ "$entry" == *.class ]] || continue
    class_strings=$(unzip -p "$JAR_PATH" "$entry" | strings)
    if ! grep -q 'java/math/BigInteger' <<<"$class_strings" && \
      ! grep -q 'java/nio/file/Path' <<<"$class_strings"; then
      continue
    fi
    CLASS_CANDIDATES+=("$entry")
  done < <(jar tf "$JAR_PATH")
fi

for entry in "${CLASS_CANDIDATES[@]}"; do
  class_name=${entry%.class}
  class_name=${class_name//\//.}
  javap_output=$(javap -classpath "$JAR_PATH" -verbose "$class_name")
  if ! check_reference "$class_name" "$javap_output"; then
    FOUND_INCOMPATIBLE_REF=1
  fi
done

if [[ "$FOUND_INCOMPATIBLE_REF" -ne 0 ]]; then
  exit 1
fi

echo "Android API 29 compatibility check passed for $JAR_PATH"
