#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

profile="${1:-1.21.5}"
loader="${2:-all}"

case "${loader}" in
  fabric) task=buildFabric ;;
  quilt) task=buildQuilt ;;
  forge) task=buildForge ;;
  neoforge) task=buildNeoForge ;;
  all) task=buildAllLoaders ;;
  *)
    echo "ERROR: unsupported loader '${loader}'. Use fabric, quilt, forge, neoforge or all." >&2
    exit 2
    ;;
esac

profile_file="buildProfiles/${profile}.properties"
[[ -s "${profile_file}" ]] || { echo "ERROR: missing ${profile_file}" >&2; exit 2; }
if [[ "${loader}" != all ]]; then
  grep -qx "enable_${loader}=true" "${profile_file}" || {
    echo "ERROR: loader '${loader}' is disabled for Minecraft ${profile}." >&2
    exit 2
  }
fi

exec ./gradlew "-PmcProfile=${profile}" "-PtargetLoader=${loader}" clean "${task}"
