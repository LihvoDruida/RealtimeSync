#!/usr/bin/env bash
set -euo pipefail

case "${1:-}" in
  -h|--help)
    printf '%s\n' 'Usage: scripts/run-core-tests.sh
Compile and run dependency-free RealtimeSync core unit tests.'
    exit 0
    ;;
  "") ;;
  *)
    echo "ERROR: unknown argument: $1" >&2
    exit 2
    ;;
esac
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
OUT="$(mktemp -d)"
trap 'rm -rf "${OUT}"' EXIT
javac -encoding UTF-8 -d "${OUT}" \
  common/src/main/java/com/realtime/common/AbsoluteDayTime.java \
  common/src/main/java/com/realtime/common/RealtimeIdentifiers.java \
  common/src/main/java/com/realtime/common/RealtimeMath.java \
  common/src/main/java/com/realtime/common/RealtimeConfig.java \
  common/src/main/java/com/realtime/common/RealtimePersistentState.java \
  common/src/main/java/com/realtime/common/RealtimeLog.java \
  common/src/testCore/java/com/realtime/common/CoreLogicTest.java
java -ea -cp "${OUT}" com.realtime.common.CoreLogicTest
