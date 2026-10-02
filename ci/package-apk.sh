#!/usr/bin/env bash
# javac + d8 + aapt2 + zipalign + apksigner 手工打包 APK（不依赖 Gradle/AGP）。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
STAGE="$ROOT/build/stage"
ASSETS="$ROOT/app/assets/bin"
WORK="$ROOT/build/apk"
DIST="$ROOT/dist"
VNAME="${VERSION_NAME:-1.0}"
VCODE="${VERSION_CODE:-1}"

SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/lib/android-sdk}}"
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
if [ -z "${BT:-}" ]; then echo "找不到 build-tools"; exit 1; fi
if [ -f "$SDK/platforms/android-34/android.jar" ]; then
  PLATFORM="$SDK/platforms/android-34/android.jar"
else
  PLATFORM="$(ls -d "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$PLATFORM" ] || { echo "找不到 android.jar"; exit 1; }
echo "build-tools: $BT"
echo "platform   : $PLATFORM"

rm -rf "$WORK" "$ASSETS"
mkdir -p "$WORK/classes" "$WORK/dex" "$ASSETS" "$DIST"

echo "==== 收集 git 二进制到 assets"
for f in git git-remote-http git-upload-pack; do
  if [ -f "$STAGE/$f" ]; then
    cp "$STAGE/$f" "$ASSETS/$f"
    echo "  $f  $(stat -c%s "$ASSETS/$f") bytes"
  fi
done
[ -f "$ASSETS/git" ] || { echo "缺少 git 二进制"; exit 1; }

echo "==== javac"
find "$ROOT/app/src" -name '*.java' >"$WORK/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn -bootclasspath "$PLATFORM" \
  -d "$WORK/classes" @"$WORK/sources.txt"

echo "==== d8"
find "$WORK/classes" -name '*.class' >"$WORK/classes.txt"
"$BT/d8" --release --min-api 26 --lib "$PLATFORM" --output "$WORK/dex" @"$WORK/classes.txt"
ls -l "$WORK/dex"

echo "==== aapt2"
"$BT/aapt2" compile --dir "$ROOT/app/res" -o "$WORK/res.zip"
"$BT/aapt2" link -o "$WORK/base.apk" \
  -I "$PLATFORM" \
  --manifest "$ROOT/app/AndroidManifest.xml" \
  -A "$ROOT/app/assets" \
  --min-sdk-version 26 --target-sdk-version 28 \
  --version-code "$VCODE" --version-name "$VNAME" \
  "$WORK/res.zip"

echo "==== 组装 + 对齐 + 签名"
( cd "$WORK/dex" && zip -q -X "$WORK/base.apk" classes.dex )
"$BT/zipalign" -f 4 "$WORK/base.apk" "$WORK/aligned.apk"
APK="$DIST/GitDroid-$VNAME.apk"
"$BT/apksigner" sign --ks "$ROOT/keystore/gitdroid-release.p12" \
  --ks-key-alias gitdroid --ks-pass pass:gitdroid --key-pass pass:gitdroid \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$APK" "$WORK/aligned.apk"

echo "==== 校验"
ls -l "$DIST"
unzip -l "$APK" | head -20
"$BT/aapt2" dump badging "$APK" | grep -E "^package|sdkVersion|targetSdkVersion|application-label|launchable-activity"
"$BT/apksigner" verify --print-certs "$APK" | head -6
sha256sum "$APK"