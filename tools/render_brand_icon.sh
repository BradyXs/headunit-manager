#!/bin/sh
set -eu
ROOT="$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)"
CHROME_BIN="${CHROME_BIN:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
TEMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TEMP_DIR"' EXIT
"$CHROME_BIN" --headless=new --disable-gpu --no-sandbox --hide-scrollbars --default-background-color=00000000 \
  --user-data-dir="$TEMP_DIR/chrome-icon" --window-size=512,512 \
  --screenshot="$TEMP_DIR/icon.png" \
  "file://$ROOT/assets/brand/launcher-render.html" >/dev/null 2>&1
cp "$TEMP_DIR/icon.png" "$ROOT/assets/brand/headunit-manager-icon-512.png"
for entry in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144; do
  density="${entry%%:*}"
  size="${entry##*:}"
  sips -z "$size" "$size" "$TEMP_DIR/icon.png" \
    --out "$ROOT/app/src/main/res/drawable-$density/ic_launcher.png" >/dev/null
done
"$CHROME_BIN" --headless=new --disable-gpu --no-sandbox --hide-scrollbars \
  --user-data-dir="$TEMP_DIR/chrome-preview" --window-size=1280,900 \
  --screenshot="$ROOT/docs/preview/logo.png" \
  "file://$ROOT/docs/preview/logo.html" >/dev/null 2>&1
echo "Rendered launcher icon and logo preview."
