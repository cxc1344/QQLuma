#!/bin/sh
# QQLuma resident stack: Xvfb desktop + QQ (with hook preload) + SnowLuma.
# Runs as the single resident PRoot process supervised by QqHostService.
# All session state lives under /qqluma-data/snowluma/home (persistent).
# The bottom half is a supervision loop: any component that dies (QQ losing
# the race against a slow Xvfb on cold start, a crash, ...) is relaunched
# instead of leaving the desktop dead until the whole host restarts.
DATA=/qqluma-data/snowluma
STACK=/qqluma-data/stack

export HOME="$DATA/home"
export XDG_CONFIG_HOME="$DATA/home/.config"
export XDG_DATA_HOME="$DATA/home/.local/share"
export XDG_CACHE_HOME="$DATA/home/.cache"
export DISPLAY=:100
export LANG=C.UTF-8
export LC_ALL=C.UTF-8
mkdir -p "$HOME" "$DATA/logs"

echo "[stack] resident starting (display :100)"

x_ready() { xdpyinfo -display :100 >/dev/null 2>&1; }

start_x() {
  # A previous resident may have died leaving /tmp/.X100-lock and a dead
  # socket behind in the persistent rootfs. Xvfb then refuses to start
  # ("Server is already active for display 100") forever, while nothing
  # actually serves the display — and QQ/x11vnc/scrot all starve. We only
  # get here when the display is NOT answering, so the lock is stale by
  # definition: clear it before starting.
  rm -f /tmp/.X100-lock /tmp/.X11-unix/X100
  Xvfb :100 -screen 0 900x1600x24 >>"$DATA/logs/xvfb.log" 2>&1 &
  i=0
  while [ $i -lt 20 ]; do
    x_ready && return 0
    sleep 1; i=$((i + 1))
  done
  echo "[stack] Xvfb still not ready after 20s" >>"$DATA/logs/xvfb.log"
}

start_qq() {
  QQLUMA_HOOK_LIBRARY="$DATA/app/native/snowluma-linux-arm64.so" \
  QQLUMA_HOOK_SYMBOL=snowluma_linux_hook_start_dynamic \
  QQLUMA_WAIT_MODULE=wrapper.node \
  LD_PRELOAD="$DATA/libqqluma-hook.so" \
  /opt/QQ/qq --no-sandbox >>"$DATA/logs/qq.log" 2>&1 &
  echo "[stack] QQ launched"
}

start_vnc() {
  echo "[stack $(date +%H:%M:%S)] starting x11vnc" >>"$DATA/logs/vnc.log"
  x11vnc -display :100 -rfbport 5900 -listen 127.0.0.1 -noipv6 -forever -shared -nopw -noxdamage >>"$DATA/logs/vnc.log" 2>&1 &
  echo "[stack] x11vnc launched"
}

start_vnc_web() {
  echo "[stack $(date +%H:%M:%S)] starting websockify" >>"$DATA/logs/vnc-web.log"
  websockify --web=/usr/share/novnc 127.0.0.1:6080 127.0.0.1:5900 >>"$DATA/logs/vnc-web.log" 2>&1 &
  echo "[stack] noVNC web launched (http://127.0.0.1:6080/vnc.html)"
}

start_sl() {
  (
    cd "$DATA/app" && \
    SNOWLUMA_HOOK_AUTOLOAD=0 SNOWLUMA_WEBUI_HOST=127.0.0.1 SNOWLUMA_WEBUI_PORT=5099 \
    ./node ./index.mjs >>"$DATA/logs/snowluma.log" 2>&1 &
  )
  echo "[stack] SnowLuma launched (WebUI 127.0.0.1:5099)"
}

echo "[stack] resident up; supervising"
while true; do
  x_ready || start_x
  pgrep -x fluxbox >/dev/null 2>&1 || { fluxbox >>"$DATA/logs/fluxbox.log" 2>&1 & }
  pgrep -f "/opt/QQ/qq" >/dev/null 2>&1 || start_qq
  pgrep -f "index.mjs" >/dev/null 2>&1 || start_sl
  pgrep -f "qr-watch.sh" >/dev/null 2>&1 || { sh "$STACK/qr-watch.sh" >>"$DATA/logs/qrwatch.log" 2>&1 & }
  # Web desktop is optional: only when the vnc pieces are installed.
  if command -v x11vnc >/dev/null 2>&1; then
    pgrep -x x11vnc >/dev/null 2>&1 || start_vnc
  fi
  if command -v websockify >/dev/null 2>&1 && [ -d /usr/share/novnc ]; then
    pgrep -f websockify >/dev/null 2>&1 || start_vnc_web
  fi
  sleep 5
done
