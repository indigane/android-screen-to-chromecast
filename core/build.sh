#!/usr/bin/env bash
#
# Compiles the pure-JVM Cast mirroring protocol core and runs its test suite.
#
#   ./core/build.sh            compile main + tests, run all tests
#   ./core/build.sh --jar      additionally build a runnable castprobe.jar
#
# No Gradle, no Maven, no JUnit: kotlinc + the JDK only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT="$(cd "$ROOT/.." && pwd)"
BUILD="$ROOT/build"

# shellcheck disable=SC1091
source /home/claude/work/tools/kotlin-env.sh

STDLIB="$KOTLIN_HOME/lib/kotlin-stdlib.jar"

rm -rf "$BUILD"
mkdir -p "$BUILD/main" "$BUILD/test"

echo "==> compiling core/src/main"
kotlinc "$ROOT/src/main/kotlin" -d "$BUILD/main" -jvm-target 17 -nowarn

echo "==> compiling core/src/test"
kotlinc "$ROOT/src/test/kotlin" -cp "$BUILD/main" -d "$BUILD/test" -jvm-target 17 -nowarn

echo "==> running tests"
if java -cp "$BUILD/main:$BUILD/test:$STDLIB" app.castmirror.core.TestMainKt; then
  STATUS=0
else
  STATUS=$?
fi

if [[ "${1:-}" == "--jar" ]]; then
  echo "==> building castprobe.jar"
  kotlinc "$ROOT/src/main/kotlin" "$PROJECT/tools" -include-runtime -d "$PROJECT/castprobe.jar" -jvm-target 17 -nowarn
  echo "    $PROJECT/castprobe.jar"
  echo "    run: java -cp $PROJECT/castprobe.jar app.castmirror.tools.CastProbeKt <host> [appId]"
fi

exit $STATUS
