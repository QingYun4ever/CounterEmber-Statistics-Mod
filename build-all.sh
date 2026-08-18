#!/usr/bin/env bash
# Builds a jar for every supported Minecraft version.
#
# Each target gets its own build directory (build/<mcVersion>), so this does not thrash
# Loom's caches. Finished jars are collected into dist/.
set -euo pipefail

cd "$(dirname "$0")"

VERSIONS=("1.21.4" "1.21.8" "1.21.11")
GRADLE="./gradlew"

# The Gradle wrapper download has been flaky here; fall back to a local distribution if present.
if [ -x "D:/Minecraft/ce/gradle-8.14.3/bin/gradle" ] && [ "${USE_LOCAL_GRADLE:-1}" = "1" ]; then
  GRADLE="D:/Minecraft/ce/gradle-8.14.3/bin/gradle"
fi

rm -rf dist
mkdir -p dist

for v in "${VERSIONS[@]}"; do
  echo ""
  echo "=============================================="
  echo "  Minecraft $v"
  echo "=============================================="
  "$GRADLE" --console=plain -PmcVersion="$v" build
  cp "build/$v/libs/cestats-mc$v-"*.jar dist/ 2>/dev/null || true
done

echo ""
echo "=============================================="
echo "  dist/"
echo "=============================================="
ls -la dist/ | grep -v sources || true
