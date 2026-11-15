#!/bin/sh
# Watches the QQ desktop and publishes:
#   /qqluma-data/snowluma/qr.png      latest screenshot (window or full screen)
#   /qqluma-data/snowluma/status.json {"qq":"starting|desktop|window|hooked",...}
# Honesty rule: "window" is reported only when a real window capture actually
# landed on disk. A found-but-uncapturable window degrades to a full-screen
# shot ("desktop") instead of claiming a code that is not there.
DATA=/qqluma-data/snowluma
export DISPLAY=:100
mkdir -p "$DATA"

log() { echo "[qrwatch $(date +%H:%M:%S)] $*"; }

write_status() {
  printf '{"qq":"%s","w":%s,"h":%s,"ts":%s}\n' "$1" "${2:-0}" "${3:-0}" "$(date +%s)" \
    > "$DATA/status.json.tmp" && mv "$DATA/status.json.tmp" "$DATA/status.json"
}

hooked() { grep -qiE "hook entry returned 0|pipe connected" "$DATA/logs/qq.log" 2>/dev/null; }

LAST_STATE=""
while true; do
  BEST=""; BEST_AREA=0; BEST_W=0; BEST_H=0; BEST_X=0; BEST_Y=0
  for WID in $(xdotool search --onlyvisible --name "." 2>/dev/null); do
    GEO=$(xwininfo -id "$WID" 2>/dev/null | awk '
      /Absolute upper-left X:/ {x=$NF}
      /Absolute upper-left Y:/ {y=$NF}
      /Width:/ {w=$NF}
      /Height:/ {h=$NF}
      END {if (w && h) print x" "y" "w" "h}')
    [ -z "$GEO" ] && continue
    set -- $GEO
    AREA=$(($3 * $4))
    if [ "$AREA" -gt "$BEST_AREA" ]; then
      BEST=$WID; BEST_AREA=$AREA; BEST_X=$1; BEST_Y=$2; BEST_W=$3; BEST_H=$4
    fi
  done

  STATE="starting"
  if [ -n "$BEST" ] && [ "$BEST_W" -ge 200 ] && [ "$BEST_H" -ge 200 ]; then
    if scrot -o -a "$BEST_X,$BEST_Y,$BEST_W,$BEST_H" "$DATA/qr.png.tmp" 2>/dev/null && [ -s "$DATA/qr.png.tmp" ]; then
      mv "$DATA/qr.png.tmp" "$DATA/qr.png"
      STATE="window"
    fi
  fi
  if [ "$STATE" = "starting" ] && xdpyinfo -display :100 >/dev/null 2>&1; then
    if scrot -o "$DATA/qr.png.tmp" 2>/dev/null && [ -s "$DATA/qr.png.tmp" ]; then
      mv "$DATA/qr.png.tmp" "$DATA/qr.png"
      STATE="desktop"
    fi
  fi
  if [ "$STATE" != "starting" ] && hooked; then STATE="hooked"; fi
  if [ "$STATE" != "$LAST_STATE" ]; then
    log "state=$STATE best_window=${BEST:-none} ${BEST_W}x${BEST_H}"
    LAST_STATE=$STATE
  fi
  case "$STATE" in
    window|hooked) write_status "$STATE" "$BEST_W" "$BEST_H" ;;
    desktop) write_status "desktop" 900 1600 ;;
    *) write_status "starting" 0 0 ;;
  esac
  sleep 2
done
