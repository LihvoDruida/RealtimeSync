#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

expected_profiles=(
  "1.21"
  "1.21.1"
  "1.21.2"
  "1.21.3"
  "1.21.4"
  "1.21.5"
  "1.21.6"
  "1.21.7"
  "1.21.8"
  "1.21.9"
  "1.21.10"
  "1.21.11"
)

fail() {
  echo "ERROR: $*" >&2
  exit 1
}

for profile in "${expected_profiles[@]}"; do
  file="buildProfiles/${profile}.properties"
  [[ -s "${file}" ]] || fail "Missing profile ${file}"
  grep -q "^minecraft_version=${profile}$" "${file}" || fail "Profile ${file} has wrong minecraft_version"
  grep -q "^enable_fabric=" "${file}" || fail "Profile ${file} missing enable_fabric"
  grep -q "^enable_quilt=" "${file}" || fail "Profile ${file} missing enable_quilt"
  grep -q "^enable_forge=" "${file}" || fail "Profile ${file} missing enable_forge"
  grep -q "^enable_neoforge=" "${file}" || fail "Profile ${file} missing enable_neoforge"
  grep -q "mc_profile: '${profile}'" .github/workflows/package.yml || fail "Workflow matrix missing ${profile}"
done

grep -q "gradle-9.3.0-bin.zip" gradle/wrapper/gradle-wrapper.properties || fail "Gradle wrapper must be 9.3.0 for ForgeGradle 7"
grep -q "id 'net.fabricmc.fabric-loom-remap' version '1.15.5' apply false" build.gradle || fail "Root build must use Fabric Loom Remap 1.15.5"
grep -q "apply plugin: 'net.fabricmc.fabric-loom-remap'" fabric/build.gradle || fail "Fabric module must use Fabric Loom Remap"
grep -q "apply plugin: 'net.fabricmc.fabric-loom-remap'" quilt/build.gradle || fail "Quilt module must use Fabric Loom Remap"
grep -q "id 'net.minecraftforge.gradle' version '7.0.25' apply false" build.gradle || fail "Root build must use ForgeGradle 7.0.25"
grep -q "^enable_forge=false$" buildProfiles/1.21.2.properties || fail "Forge must stay disabled for 1.21.2 unless a real Forge artifact is added"

if grep -R --exclude="verify-build-matrix.sh" "gradle-9.2.1-bin.zip\|Gradle Wrapper 9.2.1\|Gradle wrapper is pinned to 9.2.1" -n .; then
  fail "Old Gradle 9.2.1 references remain"
fi


if grep -R --exclude="verify-build-matrix.sh" "workingDirectory[[:space:]]" -n forge/build.gradle; then
  fail "ForgeGradle 7 does not support old workingDirectory(...) run DSL"
fi

grep -q "workingDir.convention" forge/build.gradle || fail "Forge module must use ForgeGradle 7 workingDir.convention(...)"
grep -q "implementation minecraft.dependency" forge/build.gradle || fail "Forge module must use ForgeGradle 7 minecraft.dependency(...)"
grep -q "maven fg.forgeMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Forge Maven helper"
grep -q "maven fg.minecraftLibsMaven" forge/build.gradle || fail "Forge module must add ForgeGradle 7 Minecraft libs Maven helper"

bash -n scripts/build-all-profiles.sh

echo "Build matrix static verification passed."
