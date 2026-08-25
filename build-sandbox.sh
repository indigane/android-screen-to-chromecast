#!/usr/bin/env bash
#
# Builds a real, installable, signed CastMirror APK without Gradle.
#
#   ./build-sandbox.sh            build app/build/... and out/castmirror-debug.apk
#   ./build-sandbox.sh --quiet    same, but without the aapt2 dumps at the end
#
# Gradle cannot run in this environment: services.gradle.org, maven.google.com and Maven Central
# are all unreachable, so neither the wrapper distribution nor AGP can be fetched. The toolchain
# used here was assembled from npm and GitHub release assets instead — kotlinc, aapt2, d8,
# apksigner, an API 34 android.jar and a Python zipalign. See template-and-build.md.
#
# The APK it produces is the real thing: v2/v3 signed, zipaligned, and installable. The only
# difference from the Gradle build is that it compiles against android.jar API 34 rather than 36,
# which is why the app uses no API above 34.
#
# `./gradlew assembleDebug` (or the build-apk.yml workflow) remains the canonical build.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS=/home/claude/work/tools

AAPT2="$TOOLS/android/bin/aapt2"
ANDROID_JAR="$TOOLS/android/lib/android.jar"
D8_JAR="$TOOLS/android/lib/d8.jar"
APKSIGNER_JAR="$TOOLS/android/lib/apksigner.jar"
KEYSTORE="$TOOLS/android/debug.keystore"
KOTLINC="$TOOLS/kotlinc/bin/kotlinc"
KOTLIN_STDLIB="$TOOLS/kotlinc/lib/kotlin-stdlib.jar"
ZIPALIGN="$TOOLS/zipalign.py"

APP="$ROOT/app"
CORE="$ROOT/core"
BUILD="$ROOT/app/build/sandbox"
OUT="$ROOT/out"

# Kept in step with app/build.gradle.kts. android.jar is API 34, so the sandbox build targets 34
# while Gradle targets 35; the manifest is valid for both, declaring nothing version-specific
# beyond the foreground-service type that Android 14 introduced.
PACKAGE_NAME="app.castmirror"
MIN_SDK=26
TARGET_SDK=34
VERSION_CODE=5
VERSION_NAME="0.2.0"

QUIET=0
[[ "${1:-}" == "--quiet" ]] && QUIET=1

for tool in "$AAPT2" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR" "$KEYSTORE" "$KOTLINC" "$KOTLIN_STDLIB" "$ZIPALIGN"; do
    [[ -e "$tool" ]] || { echo "missing build tool: $tool" >&2; exit 1; }
done

rm -rf "$BUILD"
mkdir -p "$BUILD"/{res,gen,classes,dex,manifest} "$OUT"

echo "==> [1/8] aapt2 compile resources"
"$AAPT2" compile --dir "$APP/src/main/res" -o "$BUILD/res/resources.zip"

echo "==> [2/8] preparing the manifest"
# AGP takes the application id from `android.namespace`, and rejects a manifest that declares a
# `package` attribute. aapt2, driven directly, requires one. Injecting it into a copy keeps the
# checked-in manifest valid for Gradle.
python3 - "$APP/src/main/AndroidManifest.xml" "$BUILD/manifest/AndroidManifest.xml" "$PACKAGE_NAME" <<'PY'
import re, sys
source, target, package = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(source, encoding="utf-8").read()
opening = re.search(r'<manifest\b[^>]*>', text)
if opening is None:
    raise SystemExit("no <manifest> element found in %s" % source)
if re.search(r'\bpackage\s*=', opening.group(0)):
    raise SystemExit("the source manifest already declares a package attribute")
text = text[:opening.start()] + \
    opening.group(0).replace('<manifest', '<manifest package="%s"' % package, 1) + \
    text[opening.end():]
open(target, "w", encoding="utf-8").write(text)
PY

echo "==> [3/8] aapt2 link -> base APK + R.java"
"$AAPT2" link \
    -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$BUILD/manifest/AndroidManifest.xml" \
    --java "$BUILD/gen" \
    --min-sdk-version "$MIN_SDK" \
    --target-sdk-version "$TARGET_SDK" \
    --version-code "$VERSION_CODE" \
    --version-name "$VERSION_NAME" \
    "$BUILD/res/resources.zip"

echo "==> [4/8] javac R.java"
find "$BUILD/gen" -name '*.java' > "$BUILD/rjava.txt"
javac -nowarn --release 17 -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/rjava.txt" \
    2>&1 | grep -v 'Picked up' || true

echo "==> [5/8] kotlinc (core + app) against android.jar"
# Warnings are deliberately not suppressed: a Kotlin warning in this codebase is usually a real
# bug (an unreachable branch, a platform-type NPE risk, an unused result). The build fails on any
# warning as well as on any error, which is the only way to keep that true over time.
set +e
"$KOTLINC" \
    -jvm-target 17 \
    -classpath "$ANDROID_JAR:$BUILD/classes" \
    -d "$BUILD/classes" \
    "$CORE/src/main/kotlin" "$APP/src/main/kotlin" \
    > "$BUILD/kotlinc.log" 2>&1
KOTLINC_STATUS=$?
set -e
grep -v 'Picked up' "$BUILD/kotlinc.log" || true
if [[ $KOTLINC_STATUS -ne 0 ]] || grep -qE '^(.*): (error|warning):' "$BUILD/kotlinc.log"; then
    echo "kotlinc reported errors or warnings (see $BUILD/kotlinc.log)" >&2
    exit 1
fi

echo "==> [6/8] d8 -> classes.dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classlist.txt"
set +e
java -cp "$D8_JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --min-api "$MIN_SDK" \
    --release \
    --output "$BUILD/dex" \
    "$KOTLIN_STDLIB" @"$BUILD/classlist.txt" \
    > "$BUILD/d8.log" 2>&1
D8_STATUS=$?
set -e
# d8 8.2.2 predates Kotlin 2.3 and cannot parse its kotlin.Metadata annotations. That only
# affects metadata rewriting, which nothing here reads, so the noise is filtered and the exit
# status is what decides.
grep -v 'Picked up' "$BUILD/d8.log" \
    | grep -vE 'kotlin metadata|kotlin\.Metadata|Kotlin metadata|Should never be called|^\s+at |^$' || true
if [[ $D8_STATUS -ne 0 ]]; then
    echo "d8 failed (see $BUILD/d8.log)" >&2
    exit 1
fi
[[ -f "$BUILD/dex/classes.dex" ]] || { echo "d8 produced no classes.dex" >&2; exit 1; }

echo "==> [7/8] package, align and sign"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD/dex" && zip -q -X "$BUILD/unsigned.apk" classes*.dex)
python3 "$ZIPALIGN" "$BUILD/unsigned.apk" "$BUILD/aligned.apk"
java -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --min-sdk-version "$MIN_SDK" \
    --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
    --out "$OUT/castmirror-debug.apk" \
    "$BUILD/aligned.apk" 2>&1 | grep -v 'Picked up' || true

[[ -f "$OUT/castmirror-debug.apk" ]] || { echo "apksigner produced no APK" >&2; exit 1; }

echo "==> [8/8] verify"
java -jar "$APKSIGNER_JAR" verify --verbose "$OUT/castmirror-debug.apk" 2>&1 | grep -v 'Picked up' || true

if [[ "$QUIET" -eq 0 ]]; then
    echo
    echo "==> aapt2 dump badging"
    "$AAPT2" dump badging "$OUT/castmirror-debug.apk"
    echo
    echo "==> declared permissions, services and the quick-settings tile"
    "$AAPT2" dump xmltree --file AndroidManifest.xml "$OUT/castmirror-debug.apk" \
        | grep -E 'uses-permission|E: (service|activity|meta-data|action)|android:name|foregroundServiceType' \
        | sed 's/(Raw: /  (/' || true
fi

echo
echo "RESULT: $OUT/castmirror-debug.apk"
ls -la "$OUT/castmirror-debug.apk"
