#!/bin/sh
# QQLuma resident stack: Xvfb desktop + QQ (with hook preload) + SnowLuma.
# Runs as the single resident PRoot process supervised by QqHostService.
# All session state lives under /qqluma-data/snowluma/home (persistent).
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

# --- virtual display -------------------------------------------------------
if ! xdpyinfo -display :100 >/dev/null 2>&1; then
  Xvfb :100 -screen 0 900x1600x24 >>"$DATA/logs/xvfb.log" 2>&1 &
  sleep 2
fi
pgrep -x fluxbox >/dev/null 2>&1 || { fluxbox >>"$DATA/logs/fluxbox.log" 2>&1 & }

# --- QQ with the hook shim preloaded --------------------------------------
if ! pgrep -f "/opt/QQ/qq" >/dev/null 2>&1; then
  (
    QQLUMA_HOOK_LIBRARY="$DATA/app/native/snowluma-linux-arm64.so" \
    QQLUMA_HOOK_SYMBOL=snowluma_linux_hook_start_dynamic \
    QQLUMA_WAIT_MODULE=wrapper.node \
    LD_PRELOAD="$DATA/libqqluma-hook.so" \
    /opt/QQ/qq --no-sandbox >>"$DATA/logs/qq.log" 2>&1 &
  )
  echo "[stack] QQ launched"
fi

# --- SnowLuma core ----------------------------------------------------------
if ! pgrep -f "index.mjs" >/dev/null 2>&1; then
  (
    cd "$DATA/app" && \
    SNOWLUMA_HOOK_AUTOLOAD=0 SNOWLUMA_WEBUI_HOST=127.0.0.1 SNOWLUMA_WEBUI_PORT=5099 \
    ./node ./index.mjs >>"$DATA/logs/snowluma.log" 2>&1 &
  )
  echo "[stack] SnowLuma launched (WebUI 127.0.0.1:5099)"
fi

# --- QR watcher -------------------------------------------------------------
pgrep -f "qr-watch.sh" >/dev/null 2>&1 || { sh "$STACK/qr-watch.sh" & }

echo "[stack] resident up; parking"
exec sleep infinity
