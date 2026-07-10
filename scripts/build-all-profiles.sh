#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

loader=all
if [[ "${1:-}" == "--loader" ]]; then
  loader="${2:?Usage: scripts/build-all-profiles.sh [--loader fabric|quilt|forge|neoforge|all] [profiles...]}"
  shift 2
fi

case "${loader}" in
  fabric|quilt|forge|neoforge|all) ;;
  *) echo "ERROR: unsupported loader '${loader}'" >&2; exit 2 ;;
esac

profiles=("$@")
if [[ ${#profiles[@]} -eq 0 ]]; then
  mapfile -t profiles < <(find buildProfiles -maxdepth 1 -type f -name '*.properties' ! -name '*.example' -printf '%f\n' | sed 's/\.properties$//' | sort -V)
fi

for profile in "${profiles[@]}"; do
  echo "==> Building Minecraft profile ${profile} (${loader})"
  ./scripts/build.sh "${profile}" "${loader}"
  echo "==> Finished Minecraft profile ${profile}"
  echo
done
