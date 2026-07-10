#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

loader=all
mod_version=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --loader)
      loader="${2:?--loader requires fabric|quilt|forge|neoforge|all}"
      shift 2
      ;;
    --mod-version)
      mod_version="${2:?--mod-version requires a value}"
      shift 2
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "ERROR: unknown option '$1'" >&2
      exit 2
      ;;
    *)
      break
      ;;
  esac
done

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
  ./scripts/build.sh "${profile}" "${loader}" "${mod_version}"
  echo "==> Finished Minecraft profile ${profile}"
  echo
done
