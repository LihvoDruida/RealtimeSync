#!/usr/bin/env bash
set -euo pipefail

PROFILE="${1:?Usage: ci-read-profile.sh <mc_profile> <loader>}"
LOADER="${2:?Usage: ci-read-profile.sh <mc_profile> <loader>}"
PROFILE_FILE="buildProfiles/${PROFILE}.properties"

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

[[ -s "${PROFILE_FILE}" ]] || fail "Missing Minecraft build profile: ${PROFILE_FILE}"

read_profile_prop() {
  local key="$1"
  grep -E "^${key}=" "${PROFILE_FILE}" | tail -n 1 | cut -d'=' -f2-
}

read_gradle_prop() {
  local key="$1"
  grep -E "^${key}=" gradle.properties | tail -n 1 | cut -d'=' -f2-
}

case "${LOADER}" in
  fabric)
    LOADER_TITLE="Fabric"
    GRADLE_TASK="buildFabric"
    MODULE_DIR="fabric"
    ENABLE_KEY="enable_fabric"
    CURSEFORGE_LOADER="fabric"
    CURSEFORGE_DEPENDENCIES="306612(required)"
    ;;
  quilt)
    LOADER_TITLE="Quilt"
    GRADLE_TASK="buildQuilt"
    MODULE_DIR="quilt"
    ENABLE_KEY="enable_quilt"
    CURSEFORGE_LOADER="quilt"
    CURSEFORGE_DEPENDENCIES="306612(required)"
    ;;
  forge)
    LOADER_TITLE="Forge"
    GRADLE_TASK="buildForge"
    MODULE_DIR="forge"
    ENABLE_KEY="enable_forge"
    CURSEFORGE_LOADER="forge"
    CURSEFORGE_DEPENDENCIES=""
    ;;
  neoforge)
    LOADER_TITLE="NeoForge"
    GRADLE_TASK="buildNeoForge"
    MODULE_DIR="neoforge"
    ENABLE_KEY="enable_neoforge"
    CURSEFORGE_LOADER="neoforge"
    CURSEFORGE_DEPENDENCIES=""
    ;;
  *)
    fail "Unsupported loader '${LOADER}'. Expected fabric, quilt, forge or neoforge."
    ;;
esac

MC_VERSION="$(read_profile_prop minecraft_version)"
MC_LABEL="$(read_profile_prop minecraft_compat_label)"
JAVA_VERSION="$(read_profile_prop java_version)"
CURSEFORGE_JAVA="$(read_profile_prop curseforge_java_versions)"
MOD_VERSION="$(read_gradle_prop mod_version)"
ENABLED="$(read_profile_prop "${ENABLE_KEY}")"

[[ -n "${MC_VERSION}" ]] || fail "Profile ${PROFILE_FILE} missing minecraft_version"
[[ -n "${MC_LABEL}" ]] || fail "Profile ${PROFILE_FILE} missing minecraft_compat_label"
[[ -n "${JAVA_VERSION}" ]] || fail "Profile ${PROFILE_FILE} missing java_version"
[[ -n "${CURSEFORGE_JAVA}" ]] || fail "Profile ${PROFILE_FILE} missing curseforge_java_versions"
[[ -n "${MOD_VERSION}" ]] || fail "gradle.properties missing mod_version"
[[ "${ENABLED}" == "true" || "${ENABLED}" == "false" ]] || fail "${PROFILE_FILE} has invalid ${ENABLE_KEY}=${ENABLED}"

JAR_NAME="realtime-sync-${LOADER}-${MC_VERSION}-${MOD_VERSION}.jar"
JAR_PATH="${MODULE_DIR}/build/libs/${JAR_NAME}"
ARTIFACT_NAME="realtime-sync-${MOD_VERSION}-mc${MC_LABEL}-${LOADER}-jar"

write_output() {
  local key="$1"
  local value="$2"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    printf '%s=%s\n' "${key}" "${value}" >> "${GITHUB_OUTPUT}"
  else
    printf '%s=%s\n' "${key}" "${value}"
  fi
}

write_output mc_profile "${PROFILE}"
write_output loader "${LOADER}"
write_output loader_title "${LOADER_TITLE}"
write_output gradle_task "${GRADLE_TASK}"
write_output module_dir "${MODULE_DIR}"
write_output minecraft_version "${MC_VERSION}"
write_output minecraft_compat_label "${MC_LABEL}"
write_output java_version "${JAVA_VERSION}"
write_output curseforge_java_versions "${CURSEFORGE_JAVA}"
write_output mod_version "${MOD_VERSION}"
write_output curseforge_game_versions "${MC_VERSION}"
write_output enabled "${ENABLED}"
write_output enable_key "${ENABLE_KEY}"
write_output curseforge_loader "${CURSEFORGE_LOADER}"
write_output curseforge_dependencies "${CURSEFORGE_DEPENDENCIES}"
write_output jar_name "${JAR_NAME}"
write_output jar_path "${JAR_PATH}"
write_output artifact_name "${ARTIFACT_NAME}"

printf 'Selected profile %s: Minecraft %s, Java %s, loader=%s, enabled=%s\n' \
  "${PROFILE}" "${MC_VERSION}" "${JAVA_VERSION}" "${LOADER}" "${ENABLED}"
