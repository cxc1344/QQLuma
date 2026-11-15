#!/bin/sh
# Watches the QQ window on the virtual display and publishes:
#   /qqluma-data/snowluma/qr.png      latest screenshot of the QQ window
#   /qqluma-data/snowluma/status.json {"qq":"starting|window|hooked","w":..,"h":..,"ts":..}
# The Android side polls these two files to render the live login QR.
DATA=/qqluma-data/snowluma
export DISPLAY=:100
mkdir -p "$DATA"

write_status() {
  printf '{"qq":"%s","w":%s,"h":%s,"ts":%s}\n' "$1" "${2:-0}" "${3:-0}" "$(date +%s)" \
    > "$DATA/status.json.tmp" && mv "$DATA/status.json.tmp" "$DATA/status.json"
}

while true; do
  WID=$(xdotool search --name "QQ" 2>/dev/null | head -n 1)
  if [ -n "$WID" ]; then
    GEO=$(xwininfo -id "$WID" 2>/dev/null | awk '
      /Absolute upper-left X:/ {x=$NF}
      /Absolute upper-left Y:/ {y=$NF}
      /Width:/ {w=$NF}
      /Height:/ {h=$NF}
      END {if (w && h) print x","y","w","h" "w" "h}')
    AREA=$(echo "$GEO" | cut -d' ' -f1)
    W=$(echo "$GEO" | cut -d' ' -f2)
    H=$(echo "$GEO" | cut -d' ' -f3)
    if [ -n "$AREA" ] && scrot -o -a "$AREA" "$DATA/qr.png.tmp" 2>/dev/null; then
      mv "$DATA/qr.png.tmp" "$DATA/qr.png"
    fi
    if grep -qiE "hook entry returned 0|pipe connected" "$DATA/logs/qq.log" 2>/dev/null; then
      write_status "hooked" "${W:-0}" "${H:-0}"
    else
      write_status "window" "${W:-0}" "${H:-0}"
    fi
  else
    write_status "starting" 0 0
  fi
  sleep 2
done
