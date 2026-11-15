#!/bin/sh
# On-demand install of the web-desktop (noVNC) pieces: x11vnc serves the
# guest desktop, websockify + novnc expose it as a web page on
# 127.0.0.1:6080. Idempotent: exits immediately when all pieces exist.
# Apt sources were pointed at the TUNA mirror by stack-install.sh.
set -e
if command -v x11vnc >/dev/null 2>&1 && command -v websockify >/dev/null 2>&1 && [ -d /usr/share/novnc ]; then
  echo "[vnc] already installed"
  exit 0
fi
export DEBIAN_FRONTEND=noninteractive
echo "[vnc] apt-get update"
apt-get update -qq
echo "[vnc] installing x11vnc websockify novnc"
apt-get install -y -qq x11vnc websockify novnc
echo "[vnc] installed; the resident supervisor picks the services up within seconds"
