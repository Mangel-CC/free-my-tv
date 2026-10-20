#!/usr/bin/env bash
# Builds FreeMyTVButtons.apk (Android 11+) without Gradle, like ../build.sh. Needs a JDK 17 and the Android SDK.
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$SDK" ] && [ -d "$SDK/build-tools" ] || { echo "Android SDK not found. Set ANDROID_HOME."; exit 1; }
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
AJ="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1 || true)"
[ -f "${AJ:-}" ] || { echo "Android platform not found. Run: sdkmanager \"platforms;android-34\""; exit 1; }
JAVAC="${JAVA_HOME:+$JAVA_HOME/bin/}javac"
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"

rm -rf out && mkdir -p out/gen out/classes
"$BT/aapt2" compile --dir res -o out/res.zip
"$BT/aapt2" link -o out/base.apk -I "$AJ" --manifest AndroidManifest.xml --java out/gen out/res.zip \
  --min-sdk-version 30 --target-sdk-version 33
"$JAVAC" -nowarn -Xlint:-options -source 8 -target 8 -bootclasspath "$AJ:$BT/core-lambda-stubs.jar" -classpath "$AJ" \
  -d out/classes $(find src out/gen -name '*.java')
"$BT/d8" --release --min-api 30 --lib "$AJ" --output out $(find out/classes -name '*.class')
(cd out && zip -qj base.apk classes.dex)
"$BT/zipalign" -f -p 4 out/base.apk out/aligned.apk

if [ ! -f remotebuttons.keystore ]; then
  "$KEYTOOL" -genkeypair -keystore remotebuttons.keystore -alias remotebuttons -keyalg RSA -keysize 2048 -validity 20000 \
    -storepass remotebuttons -keypass remotebuttons -dname "CN=Free My TV Buttons" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks remotebuttons.keystore --ks-pass pass:remotebuttons --key-pass pass:remotebuttons --out FreeMyTVButtons.apk out/aligned.apk
echo "Built FreeMyTVButtons.apk"
