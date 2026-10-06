#!/usr/bin/env bash
#
# HyperAssistant 一键安装（adb 路径）
#
#   ./install.sh [APK 路径]
#
# 它做四件事：
#   1. adb install -r 安装 APK
#   2. 把 APK 复制到 /data/adb/dshmod/ 并把 LSPosed 的 apk_path 指过去
#      —— 为什么：开机早期 CE 存储未解锁时读不到 /data/app 里的 APK，
#         而 system_server 是最早 fork 的进程，会因此错过模块（表现为"动画不生效"）
#   3. 在 LSPosed 里登记本模块（启用 + 勾 4 个作用域）
#   4. 提示重启
#
# 前置：设备已 root（su 可用）、已装 LSPosed；设备上最好有 sqlite3
#       （没有也能装，但第 2/3 步需要你在 LSPosed 界面里手动完成，脚本会提示）

set -uo pipefail

PKG="com.qianxia.HyperAssistant"
DST="/data/adb/dshmod/hyperassistant.apk"
DB="/data/adb/lspd/config/modules_config.db"
ROOT_SCOPE=(system com.miui.personalassistant com.miui.home com.android.systemui)

APK="${1:-app/build/outputs/apk/release/app-release.apk}"
[ -f "$APK" ] || APK="$(ls app/build/outputs/apk/release/*.apk 2>/dev/null | head -1)"
[ -n "${APK:-}" ] && [ -f "$APK" ] || { echo "✗ 找不到 APK，请把路径作为参数传入：./install.sh /path/to/app-release.apk"; exit 1; }

say() { printf '\n\033[1m%s\033[0m\n' "$*"; }
ok()  { printf '  ✓ %s\n' "$*"; }
warn(){ printf '  ! %s\n' "$*"; }

say "0/4 检查设备"
adb get-state >/dev/null 2>&1 || { echo "✗ 设备未连接（adb devices 看不到）"; exit 1; }
ok "设备在线：$(adb shell getprop ro.product.model | tr -d '\r')"
if adb shell su -c true >/dev/null 2>&1; then ok "root 可用"; else warn "拿不到 root —— 第 2/3 步会失败（请先在手机上授权 su）"; fi

say "1/4 安装 APK：$APK"
adb install -r "$APK" | tail -1
DEV_APK="$(adb shell pm path $PKG | sed 's/package://' | tr -d '\r')"
[ -n "$DEV_APK" ] || { echo "✗ 安装后拿不到 APK 路径"; exit 1; }
ok "已安装：$DEV_APK"

say "2/4 固定到 DE 存储（解决「开机早期读不到」）"
adb shell su -c "mkdir -p /data/adb/dshmod && cp '$DEV_APK' '$DST' && chmod 644 '$DST'" \
  && ok "已复制到 $DST" || warn "复制失败（root？）"

say "3/4 在 LSPosed 里登记"
HAVE_SQLITE="$(adb shell su -c 'command -v sqlite3 || true' | tr -d '\r')"
if [ -n "$HAVE_SQLITE" ]; then
  adb shell su -c "cp '$DB' '$DB.bak.$(date +%s)'" >/dev/null 2>&1 && ok "已备份数据库"
  SQL="insert or replace into modules(module_pkg_name,apk_path) values('$PKG','$DST');"
  SQL="$SQL insert or replace into modules_state(module_pkg_name,user_id,enabled,scope_request_blocked) values('$PKG',0,1,0);"
  for s in "${ROOT_SCOPE[@]}"; do
    SQL="$SQL insert or replace into scope(module_pkg_name,app_pkg_name,user_id) values('$PKG','$s',0);"
  done
  adb shell su -c "sqlite3 '$DB' \"$SQL\"" \
    && ok "已登记（启用 + 作用域：${ROOT_SCOPE[*]}）" \
    || warn "写数据库失败"
  adb shell su -c "rm -f '$DB-wal' '$DB-shm'" >/dev/null 2>&1
else
  warn "设备上没有 sqlite3，无法自动登记。请手动完成："
  warn "  LSPosed → 模块 → 启用 HyperAssistant → 作用域勾选：${ROOT_SCOPE[*]}"
  warn "  ⚠️ 如果你是在「启用之后」才发现 apk_path 指向 /data/app，"
  warn "     需要在 LSPosed 里先关掉再打开一次，或直接用手动路径："
  warn "     把 $DST 的存在告诉 LSPosed（重启即可让它重新读取）"
fi

say "4/4 完成，请重启手机"
cat <<'TIP'

  重启后自证三条（不看界面也能判断）：
    ① LSPosed 日志里 (system)[...] hookAll OK 有 8 条
    ② App 主页显示「模块已激活」
    ③ 点负一屏卡片时日志出现 [打开动画] ✔ 启动被接受 (剥离级别 0/5)

  另一种更简单的安装方式：刷 Magisk/KernelSU 模块（无需 adb、无需改数据库）
    Release 里的 HyperAssistant-magisk-*.zip
TIP
