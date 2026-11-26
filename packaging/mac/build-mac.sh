#!/bin/bash
# Đóng gói Ark Studio cho macOS (chạy trên máy Mac, hoặc GitHub Actions – xem .github/workflows/mac.yml):
#   1. build giao diện  2. build backend jar  3. jlink Java thu gọn  4. jpackage → "Ark Studio.app" trong file .dmg
#   5. (SMOKE=1) mở thử app trong .dmg, chờ cổng 18080 trả lời rồi tắt
# Dùng: packaging/mac/build-mac.sh <phiên bản, vd 1.13> <tên máy: arm64 | intel>
set -euo pipefail
VER="${1:?phiên bản, vd 1.13}"
ARCH="${2:?arm64 hoặc intel}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build-mac"
APP="Ark Studio"

step() { printf '\n==> %s\n' "$1"; }
: "${JAVA_HOME:?cần JDK 17+ (JAVA_HOME)}"

step "Build giao diện (Vue)"
(cd "$ROOT/frontend" && { [ -d node_modules ] || npm ci; } && npm run build)

step "Build backend (Spring Boot jar kèm giao diện)"
(cd "$ROOT/backend" && sh ./mvnw -q -DskipTests package)

step "Java thu gọn (jlink)"
rm -rf "$OUT" && mkdir -p "$OUT/app"
cp "$ROOT/backend/target/byteplus-ark-backend.jar" "$OUT/app/"
"$JAVA_HOME/bin/jlink" --add-modules 'java.se,jdk.crypto.ec,jdk.crypto.cryptoki,jdk.unsupported,jdk.zipfs,jdk.charsets,jdk.localedata,jdk.management,jdk.naming.dns,jdk.net,jdk.accessibility' \
  --output "$OUT/runtime" --strip-debug --no-header-files --no-man-pages --compress=2

step "Biểu tượng (.icns)"
ICONSET="$OUT/ark.iconset"
mkdir -p "$ICONSET"
for s in 16 32 128 256; do
  sips -z $s $s "$ROOT/packaging/ark.png" --out "$ICONSET/icon_${s}x${s}.png" >/dev/null
  d=$((s * 2))
  if [ $d -le 256 ]; then sips -z $d $d "$ROOT/packaging/ark.png" --out "$ICONSET/icon_${s}x${s}@2x.png" >/dev/null; fi
done
iconutil -c icns "$ICONSET" -o "$OUT/ark.icns"

step "jpackage → .dmg"
"$JAVA_HOME/bin/jpackage" --type dmg --dest "$OUT" \
  --name "$APP" --app-version "$VER" --vendor "Ark Studio" \
  --mac-package-identifier vn.arkstudio.app --mac-package-name "$APP" \
  --icon "$OUT/ark.icns" --runtime-image "$OUT/runtime" \
  --input "$OUT/app" --main-jar byteplus-ark-backend.jar \
  --java-options '-Dark.desktop=true' --java-options '-Dfile.encoding=UTF-8' --java-options '-Xmx2g'
DMG="$OUT/ArkStudio-$VER-mac-$ARCH.dmg"
mv "$OUT/$APP-$VER.dmg" "$DMG"
echo "Đã tạo: $DMG ($(du -h "$DMG" | cut -f1))"

if [ "${SMOKE:-0}" = "1" ]; then
  step "Chạy thử app trong .dmg"
  MNT="$(mktemp -d)"
  hdiutil attach "$DMG" -nobrowse -mountpoint "$MNT" >/dev/null
  "$MNT/$APP.app/Contents/MacOS/$APP" > "$OUT/smoke.log" 2>&1 &
  PID=$!
  ok=0
  for _ in $(seq 1 90); do
    if curl -fs http://127.0.0.1:18080/api/v1/app/ping | grep -q ark-studio; then ok=1; break; fi
    sleep 2
  done
  curl -fs http://127.0.0.1:18080/api/v1/app/ping || true; echo
  curl -fs -o /dev/null -w 'Trang chủ: HTTP %{http_code}\n' http://127.0.0.1:18080/ || true
  kill "$PID" 2>/dev/null || true; sleep 3; kill -9 "$PID" 2>/dev/null || true
  hdiutil detach "$MNT" -force >/dev/null || true
  if [ $ok != 1 ]; then
    echo "App không lên cổng 18080"; cat "$OUT/smoke.log" || true
    cat "$HOME/.byteplus-ark/logs/"*.log 2>/dev/null | tail -80 || true
    exit 1
  fi
  echo "Chạy thử OK"
fi
