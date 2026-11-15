#!/bin/sh
# QQLuma guest stack installer: X desktop + Linux QQ 3.2.34 + SnowLuma v2.2.1.
# Everything stateful lands in /qqluma-data (outside the rootfs) so an
# environment rebuild never wipes the QQ session.
# Versions are pinned to the combination verified on Android/PRoot:
# SnowLuma v2.3.x changed its native hook and breaks QQ 3.2.34 — do not bump.
set -e

DATA=/qqluma-data/snowluma
STACK=/qqluma-data/stack
QQ_DEB_URL="https://qqdl.gtimg.cn/qqfile/QQNT/9.9.36/beta/9ee04bef/linuxqq_3.2.34-53644_arm64.deb"
QQ_DEB_SHA="181b895cb573548e396e54bb0548cca92ffdc1b1a4b018fa4551bb062385fdca"
SL_URL="https://github.com/SnowLuma/SnowLuma/releases/download/v2.2.1/SnowLuma-v2.2.1-linux-arm64.tar.gz"
SL_SHA="26727d30109a5a7cc19e95f7c7a7ca9cbf9eb17adce7c122129e96bda88a2945"

mkdir -p "$DATA/app" "$DATA/home" "$DATA/logs" "$STACK"

echo "[stack] 1/6 更新软件源"
apt-get update -y

echo "[stack] 2/6 安装桌面与运行依赖（Xvfb / fluxbox / 截图工具 / 编译器）"
DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
  xvfb xauth fluxbox dbus-x11 fonts-wqy-zenhei x11-utils xdotool scrot \
  curl ca-certificates python3 build-essential procps

if [ ! -x /opt/QQ/qq ]; then
  echo "[stack] 3/6 下载 Linux QQ 3.2.34-53644（约 198MB）"
  curl -fL --retry 3 -o /tmp/linuxqq.deb "$QQ_DEB_URL"
  echo "$QQ_DEB_SHA  /tmp/linuxqq.deb" | sha256sum -c -
  echo "[stack] 4/6 安装 QQ（自动补齐依赖）"
  DEBIAN_FRONTEND=noninteractive apt-get install -y /tmp/linuxqq.deb
  rm -f /tmp/linuxqq.deb
else
  echo "[stack] 3/6 QQ 已安装，跳过下载"
  echo "[stack] 4/6 跳过"
fi

if [ ! -f "$DATA/app/index.mjs" ]; then
  echo "[stack] 5/6 下载 SnowLuma v2.2.1（约 50MB）并校验"
  curl -fL --retry 3 -o /tmp/snowluma.tar.gz "$SL_URL"
  echo "$SL_SHA  /tmp/snowluma.tar.gz" | sha256sum -c -
  tar -xzf /tmp/snowluma.tar.gz -C "$DATA/app"
  rm -f /tmp/snowluma.tar.gz
else
  echo "[stack] 5/6 SnowLuma 已就位，跳过下载"
fi
test -x "$DATA/app/node" && test -f "$DATA/app/index.mjs" && \
  test -f "$DATA/app/native/snowluma-linux-arm64.so" || {
  echo "[stack] SnowLuma 发行包结构不完整，安装中止" >&2; exit 1; }

echo "[stack] 6/6 编译注入 shim"
gcc -O2 -shared -fPIC -o "$DATA/libqqluma-hook.so" "$STACK/hook-shim.c" -ldl
test -f "$DATA/libqqluma-hook.so"

touch "$DATA/stack-installed"
echo "[stack] 完成：桌面 + QQ 3.2.34 + SnowLuma v2.2.1 已入住数据区"
