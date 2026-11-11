# shellcheck shell=bash
# Shared helpers for Pure Home scripts. Sourced, not executed.
set -euo pipefail
umask 077

OURS="dev.sarthak.purelauncher"
OURS_COMP="$OURS/.HomeActivity"
OURS_COMP_FULL="$OURS/dev.sarthak.purelauncher.HomeActivity"
# Stock launchers we know how to hand Home over from. Nothing else is ever disabled by install.sh.
KNOWN_STOCK_LAUNCHERS="com.mitv.tvhome com.mitv.tvhome.atv com.google.android.tvlauncher com.google.android.leanbacklauncher com.google.android.apps.tv.launcherx"
# Never disabled by any script, whatever a list says.
PROTECTED_RE='settings|tvplayer|tvinput|input|hdmi|tvservice|bluetooth|remote|rcu|ota|update|packageinstaller|permission|systemui|framework|webview|gms|gsf|vending|keyboard|inputmethod|leanbackime|provider|setupwraith|setupwizard|factorytest|^android$|^com\.android\.shell$|purelauncher'

die() { echo "✗ $*" >&2; exit 1; }
say() { echo "→ $*"; }

valid_ip() {
  [[ "$1" =~ ^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})$ ]] || return 1
  for o in "${BASH_REMATCH[@]:1}"; do (( 10#$o <= 255 )) || return 1; done
}
valid_pkg() { [[ "$1" =~ ^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$ ]]; }

need_adb() {
  command -v adb >/dev/null && return
  read -r -p "adb is not installed. Install Android platform-tools with Homebrew now? [y/N] " a
  [[ "$a" =~ ^[Yy]$ ]] || die "adb is required."
  brew install --cask android-platform-tools
}

connect() {
  local tv="$1"
  valid_ip "$tv" || die "'$tv' is not a valid IPv4 address."
  T="$tv:5555"
  say "Connecting to $T (accept 'Allow USB debugging?' on the TV)"
  adb connect "$T" >/dev/null 2>&1 || true
  local s=""
  for _ in $(seq 1 30); do s=$(adb -s "$T" get-state 2>/dev/null || true); [[ "$s" == "device" ]] && break; sleep 2; done
  [[ "$s" == "device" ]] || die "Could not connect. Check Developer options › USB debugging is ON and the TV is on this Wi-Fi."
}

sh_tv() { adb -s "$T" shell "$@" </dev/null | tr -d '\r'; }  # </dev/null: adb shell would otherwise swallow the stdin of a surrounding "while read" loop

current_home() {
  sh_tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1
}
home_is_ours() { local h; h=$(current_home); [[ "$h" == "$OURS_COMP" || "$h" == "$OURS_COMP_FULL" ]]; }
is_enabled() { sh_tv pm list packages -e "$1" | grep -qx "package:$1"; }
is_installed() { sh_tv pm list packages "$1" | grep -qx "package:$1"; }

disable_pkg() { # pkg record_file
  local p="$1" rec="$2"
  valid_pkg "$p" || { echo "   skip (bad name) $p"; return; }
  if [[ "$p" =~ $PROTECTED_RE ]]; then echo "   skip (protected) $p"; return; fi
  is_enabled "$p" || return 0
  if sh_tv pm disable-user --user 0 "$p" | grep -q "new state: disabled"; then
    echo "$p" >> "$rec"; echo "   disabled $p"
  else
    echo "   could not disable $p (left as is)"
  fi
}

latest_backup() { [[ -d backups ]] && ls -1d backups/*/ 2>/dev/null | sort | tail -n 1 | sed 's#/$##'; }

remind_adb_off() {
  echo
  echo "Security: network ADB gives full control of the TV to anyone on your Wi-Fi."
  echo "Turn it off now: TV Settings › Device Preferences › Developer options › USB debugging → Off."
  read -r -p "Press Enter once it's off to verify… " _
  adb disconnect "$T" >/dev/null 2>&1 || true
  if nc -z -w 3 "${T%:*}" 5555 >/dev/null 2>&1; then
    echo "⚠ Port 5555 is still open on the TV. Please switch USB debugging off."
  else
    echo "✓ ADB port is closed."
  fi
}
