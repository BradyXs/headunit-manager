#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ]; then echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to your Android SDK directory." >&2; exit 1; fi
BT="$SDK/build-tools/35.0.1"
PLATFORM="$SDK/platforms/android-19/android.jar"
OUT="${BUILD_OUT:-$ROOT/build}"
MANIFEST="${MANIFEST_PATH:-$ROOT/app/src/main/AndroidManifest.xml}"
SIGN_MODE="${SIGN_MODE:-debug}"
KEYSTORE="$ROOT/manager-debug.keystore"
for path in "$BT/aapt" "$BT/d8" "$BT/zipalign" "$BT/apksigner" "$PLATFORM"; do
  if [ ! -e "$path" ]; then echo "Missing: $path" >&2; exit 1; fi
done
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/generated"
"$BT/aapt" package -f -m -M "$MANIFEST" -S "$ROOT/app/src/main/res" -I "$PLATFORM" -J "$OUT/generated" -F "$OUT/base.apk"
find "$ROOT/app/src/main/java" -name '*.java' -print > "$OUT/java-sources.txt"
find "$OUT/generated" -name '*.java' -print >> "$OUT/java-sources.txt"
javac -source 1.8 -target 1.8 -encoding UTF-8 -classpath "$PLATFORM" -d "$OUT/classes" @"$OUT/java-sources.txt"
find "$OUT/classes" -name '*.class' -print > "$OUT/class-files.txt"
"$BT/d8" --min-api 19 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/class-files.txt"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q -u "$OUT/unsigned.apk" classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
if [ "$SIGN_MODE" = platform ]; then
  : "${PLATFORM_CERT:?Set PLATFORM_CERT to the authorized platform certificate}"
  : "${PLATFORM_KEY:?Set PLATFORM_KEY to the authorized platform private key}"
  : "${SIGNAPK_DIR:?Set SIGNAPK_DIR to the Android 4.4 SignApk tool directory}"
  SIGNAPK_CP="$SIGNAPK_DIR/signapk.jar:$SIGNAPK_DIR/bcprov-jdk15on-1.50.jar:$SIGNAPK_DIR/bcpkix-jdk15on-1.50.jar"
  APK="$OUT/HeadunitManager-Android4.4-platform.apk"
  java -cp "$SIGNAPK_CP" com.android.signapk.SignApk \
    "$PLATFORM_CERT" "$PLATFORM_KEY" "$OUT/aligned.apk" "$OUT/signed.apk"
  "$BT/zipalign" -f 4 "$OUT/signed.apk" "$APK"
elif [ "$SIGN_MODE" = debug ]; then
  if [ ! -f "$KEYSTORE" ]; then
    keytool -genkeypair -noprompt -keystore "$KEYSTORE" -storepass android -keypass android \
      -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
      -dname "CN=Headunit Manager Development, O=Local, C=CN" >/dev/null 2>&1
  fi
  APK="$OUT/HeadunitManager-Android4.4.apk"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-key-alias androiddebugkey \
    --ks-pass pass:android --key-pass pass:android \
    --out "$APK" "$OUT/aligned.apk"
else
  echo "Unknown SIGN_MODE: $SIGN_MODE" >&2
  exit 1
fi
"$BT/apksigner" verify --min-sdk-version 19 --verbose "$APK"
"$BT/zipalign" -c 4 "$APK"
"$BT/aapt" dump badging "$APK" | head -12
echo "APK: $APK"
