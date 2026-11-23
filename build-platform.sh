#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
: "${ANDROID_SDK_ROOT:=${ANDROID_HOME:-}}"
: "${PLATFORM_KEY:?Set PLATFORM_KEY to the authorized platform private key}"
: "${PLATFORM_CERT:?Set PLATFORM_CERT to the authorized platform certificate}"
: "${SIGNAPK_DIR:?Set SIGNAPK_DIR to the Android 4.4 SignApk tool directory}"
if [ -z "$ANDROID_SDK_ROOT" ]; then echo "Set ANDROID_SDK_ROOT or ANDROID_HOME to your Android SDK directory." >&2; exit 1; fi
for path in "$PLATFORM_KEY" "$PLATFORM_CERT"; do
  if [ ! -f "$path" ]; then echo "Missing: $path" >&2; exit 1; fi
done
export PLATFORM_KEY PLATFORM_CERT SIGNAPK_DIR
BUILD_OUT="${BUILD_OUT:-$ROOT/platform-build}"
SIGN_MODE=platform MANIFEST_PATH="$ROOT/app/src/platform/AndroidManifest.xml" \
  BUILD_OUT="$BUILD_OUT" "$ROOT/build.sh"
APK="$BUILD_OUT/HeadunitManager-Android4.4-platform.apk"
BT="$ANDROID_SDK_ROOT/build-tools/35.0.1"
signer_sha="$("$BT/apksigner" verify --min-sdk-version 19 --print-certs "$APK" | awk '/Signer #1 certificate SHA-256 digest:/ {print $NF}')"
cert_sha="$(openssl x509 -in "$PLATFORM_CERT" -outform DER | shasum -a 256 | awk '{print $1}')"
manifest_uid="$("$BT/aapt" dump xmltree "$APK" AndroidManifest.xml | awk '/android:sharedUserId/ {print $0}')"
if [ -z "$signer_sha" ] || [ "$signer_sha" != "$cert_sha" ] ||
   ! printf '%s\n' "$manifest_uid" | grep -q '"android.uid.system"'; then
  echo "Built APK signer or shared UID mismatch." >&2
  exit 1
fi
echo "Platform signer and android.uid.system verified."
