#!/usr/bin/env bash
# Pure Home installer.  Usage: ./install.sh <TV IP>
# Order of operations (safe by design):
#   1. verify the APK checksum   2. snapshot the TV's current state
#   3. install + set Pure Home as Home   4. only if Home still isn't ours, disable the known stock launcher
#   5. verify Home lands on Pure Home, otherwise roll back automatically
cd "$(dirname "$0")"; source ./lib.sh
[[ $# -eq 1 ]] || die "Usage: ./install.sh <TV IP address>"

say "Verifying PureHome.apk checksum"
shasum -a 256 -c PureHome.apk.sha256 >/dev/null || die "PureHome.apk does not match its checksum. Do not install."

need_adb
connect "$1"

SNAP="backups/$(date +%Y%m%d-%H%M%S)"; mkdir -p "$SNAP"
say "Saving current state to $SNAP"
sh_tv pm list packages -d | sed 's/^package://' | sort > "$SNAP/disabled-before.txt"
current_home > "$SNAP/home-before.txt"
: > "$SNAP/disabled-by-pure-home.txt"
echo "   home was: $(cat "$SNAP/home-before.txt")"

say "Installing Pure Home"
out=$(adb -s "$T" install -r "PureHome.apk" 2>&1 || true)
echo "$out" | grep -q "^Success" || die "Install failed: $out"

say "Setting Pure Home as the default home screen"
sh_tv cmd package set-home-activity "$OURS_COMP" >/dev/null 2>&1 || true
sh_tv input keyevent KEYCODE_HOME; sleep 2

if ! home_is_ours; then
  say "The TV still sends Home to its stock launcher."
  for p in $KNOWN_STOCK_LAUNCHERS; do
    is_installed "$p" && is_enabled "$p" || continue
    read -r -p "   Disable stock launcher '$p'? (reversible with ./restore.sh) [Y/n] " a
    [[ "$a" =~ ^[Nn]$ ]] && continue
    disable_pkg "$p" "$SNAP/disabled-by-pure-home.txt"
  done
  sh_tv cmd package set-home-activity "$OURS_COMP" >/dev/null 2>&1 || true
  sh_tv input keyevent KEYCODE_HOME; sleep 2
fi

if home_is_ours; then
  echo "✓ Pure Home is your home screen."
else
  echo "✗ Home did not switch to Pure Home. Rolling back…"
  while read -r p; do [[ -n "$p" ]] && sh_tv pm enable "$p" >/dev/null && echo "   re-enabled $p"; done < "$SNAP/disabled-by-pure-home.txt"
  die "Nothing else was changed. Pure Home is still installed and can be opened from the app list."
fi

echo
echo "Optional: ./debloat.sh $1   (switch off Xiaomi telemetry, promos and preloads)"
echo "Undo:     ./restore.sh $1"
remind_adb_off
