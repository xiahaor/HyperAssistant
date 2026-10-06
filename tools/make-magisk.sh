#!/usr/bin/env bash
# 把构建好的 APK 打包成 Magisk / KernelSU 模块 zip
#   ./tools/make-magisk.sh [APK 路径]
set -euo pipefail
cd "$(dirname "$0")/.."

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
[ -f "$APK" ] || APK="$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "找不到 APK，先 ./gradlew :app:assembleRelease"; exit 1; }

VER="$(grep -oE 'versionName = "[^"]+"' app/build.gradle.kts | head -1 | sed 's/.*"\(.*\)"/\1/')"
VC="$(grep -oE 'versionCode = [0-9]+' app/build.gradle.kts | head -1 | grep -oE '[0-9]+')"
OUT="dist/HyperAssistant-magisk-v${VER}.zip"

rm -rf dist/magisk && mkdir -p dist/magisk/system/app/HyperAssistant
cp "$APK" dist/magisk/system/app/HyperAssistant/HyperAssistant.apk
cat > dist/magisk/module.prop <<EOF
id=hyperassistant
name=HyperAssistant
version=${VER} (${VC})
versionCode=${VC}
author=xiahaor
description=把 HyperAssistant 装成系统应用，让 LSPosed 在开机早期就能读到它（避免"动画不生效"）。
EOF
cat > dist/magisk/customize.sh <<'EOF'
SKIPUNZIP=0
ui_print "- HyperAssistant（系统应用形态）"
set_perm_recursive "$MODPATH/system/app/HyperAssistant" 0 0 0755 0644
ui_print "- 装完请：① LSPosed 里启用并勾作用域 ② 重启"
EOF
python3 - "$OUT" <<'PY'
import sys, os, zipfile
out = sys.argv[1]
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    for root, _, files in os.walk('dist/magisk'):
        for f in files:
            p = os.path.join(root, f)
            z.write(p, os.path.relpath(p, 'dist/magisk'))
print(f"✓ {out}  ({os.path.getsize(out)//1024} KB)")
PY
