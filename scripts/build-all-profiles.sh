#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

profiles=("$@")
if [[ ${#profiles[@]} -eq 0 ]]; then
  mapfile -t profiles < <(find buildProfiles -maxdepth 1 -type f -name '*.properties' ! -name '*.example' -printf '%f\n' | sed 's/\.properties$//' | sort -V)
fi

for profile in "${profiles[@]}"; do
  echo "==> Building Minecraft profile ${profile}"
  ./gradlew -PmcProfile="${profile}" clean buildAllLoaders --stacktrace

done
