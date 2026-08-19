#!/usr/bin/env bash
# Builds a jar for every supported Minecraft version.
#
# Each target gets its own build directory (build/<mcVersion>), so this does not thrash
# Loom's caches. Finished jars are collected into dist/.
set -euo pipefail

cd "$(dirname "$0")"

VERSIONS=("1.21.4" "1.21.8" "1.21.11")

# The wrapper is the default. Loom 1.17.19 needs Gradle 9.x, so the old local 8.14.3 will not do;
# point GRADLE at another distribution only if the wrapper download turns flaky again.
GRADLE="${GRADLE:-./gradlew}"

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
