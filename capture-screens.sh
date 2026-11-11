#!/usr/bin/env bash
# Captures before/after screenshots for the blog post. Usage: ./capture-screens.sh <TV IP>
# Temporarily turns the original home screen back on for the "before" shot, then switches it off again.
# Everything is logged to screenshots/capture.log; a failed step is reported and the script carries on.
cd "$(dirname "$0")"; source ./lib.sh
set +e            # don't stop on the first failure; each step reports its own result
set +o pipefail
[[ $# -eq 1 ]] || die "Usage: ./capture-screens.sh <TV IP>"
mkdir -p screenshots
LOG=screenshots/capture.log
: > "$LOG"
log()  { echo "[$(date +%H:%M:%S)] $*" | tee -a "$LOG"; }
run()  { # run a command, log its output and exit code
  log "  \$ $*"
  local out; out=$("$@" 2>&1 </dev/null); local rc=$?
  [[ -n "$out" ]] && echo "$out" | sed 's/^/      /' | tee -a "$LOG" >/dev/null
  (( rc == 0 )) || log "  ! exit code $rc"
  return $rc
}

need_adb
connect "$1" || { log "✗ could not connect"; exit 1; }
log "connected to $T"

shot() {
  local f="screenshots/$1.png"
  adb -s "$T" exec-out screencap -p > "$f" 2>>"$LOG" </dev/null
  local size; size=$(wc -c < "$f" | tr -d ' ')
  if (( size > 10000 )); then log "  ✓ saved $f ($size bytes)"; else log "  ✗ $f looks empty ($size bytes)"; fi
}
key() { run adb -s "$T" shell input keyevent "$@"; }

# ---- the home screen the TV had before Pure Home (from the very first snapshot)
FIRST=$(ls -1d backups/*/ 2>>"$LOG" | sort | head -n 1)
log "first snapshot: ${FIRST:-<none>}"
ORIG=$(tr -d '\r' < "${FIRST}home-before.txt" 2>>"$LOG")
log "original home screen: ${ORIG:-<unknown>}"
TEMP=()
# read the list up front (no while-read loop, so nothing can swallow its input; last line counts too)
PKGS=$(tr -d '\r' < "${FIRST}disabled-by-pure-home.txt" 2>>"$LOG")
log "launchers switched off by Pure Home: $(echo $PKGS)"
for p in $PKGS; do
  if adb -s "$T" shell pm list packages -d "$p" </dev/null | tr -d '\r' | grep -qx "package:$p"; then
    run adb -s "$T" shell pm enable "$p" && TEMP+=("$p")
  else
    log "  $p is already enabled or not installed"
  fi
done
restore_off() {
  local p
  for p in ${TEMP[@]+"${TEMP[@]}"}; do run adb -s "$T" shell pm disable-user --user 0 "$p"; done
  TEMP=()
}
trap 'restore_off' EXIT

log "→ BEFORE"
sleep 2
if [[ "$ORIG" == */* ]]; then
  run adb -s "$T" shell am start -n "$ORIG"
  sleep 7; shot before-home
  key KEYCODE_DPAD_DOWN; sleep 2; shot before-home-2
fi
if adb -s "$T" shell pm list packages -e com.mitv.tvhome.atv </dev/null | tr -d '\r' | grep -qx "package:com.mitv.tvhome.atv"; then
  log "  opening Xiaomi PatchWall"
  run adb -s "$T" shell monkey -p com.mitv.tvhome.atv -c android.intent.category.LEANBACK_LAUNCHER 1 \
    || run adb -s "$T" shell monkey -p com.mitv.tvhome.atv 1
  sleep 7; shot before-patchwall
  key KEYCODE_DPAD_DOWN; sleep 2; shot before-patchwall-2
fi
restore_off

log "→ AFTER"
run adb -s "$T" shell am force-stop "$OURS"
key KEYCODE_HOME; sleep 1; shot after-intro
sleep 4.5; shot after-home
key KEYCODE_DPAD_RIGHT; sleep 1.2; shot after-home-youtube
key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; sleep 1.2; shot after-home-airtel
key KEYCODE_DPAD_DOWN; sleep 1.2; shot after-grid
key KEYCODE_HOME; sleep 1.5
key KEYCODE_DPAD_UP; sleep 0.5; key KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_RIGHT; sleep 0.5
key KEYCODE_DPAD_CENTER; sleep 1.2; shot after-settings
key KEYCODE_BACK; sleep 0.8; key KEYCODE_HOME; sleep 1.5
run adb -s "$T" shell input keyevent --longpress KEYCODE_DPAD_CENTER; sleep 1.2; shot after-menu
key KEYCODE_BACK; sleep 0.5; key KEYCODE_HOME

log "done: $(ls screenshots/*.png 2>/dev/null | wc -l | tr -d ' ') screenshots in ./screenshots (full log: $LOG)"
