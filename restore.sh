#!/usr/bin/env bash
# Puts the TV back exactly as the most recent snapshot recorded it.
# Usage: ./restore.sh <TV IP> [--uninstall]
cd "$(dirname "$0")"; source ./lib.sh
[[ $# -ge 1 ]] || die "Usage: ./restore.sh <TV IP> [--uninstall]"
SNAP=$(latest_backup); [[ -n "$SNAP" ]] || die "No snapshot found in ./backups"
FIRST=$(ls -1d backups/*/ | sort | head -n 1 | sed 's#/$##')   # the state before Pure Home ever ran
need_adb; connect "$1"
say "Restoring to the state recorded in $FIRST"
for f in backups/*/disabled-by-*.txt; do
  [[ -f "$f" ]] || continue
  while read -r p; do
    [[ -n "$p" ]] && valid_pkg "$p" || continue
    if grep -qx "$p" "$FIRST/disabled-before.txt"; then echo "   keep disabled (was off before) $p"; continue; fi
    sh_tv pm enable "$p" >/dev/null && echo "   re-enabled $p"
  done < "$f"
done
before=$(cat "$FIRST/home-before.txt" 2>/dev/null || true)
if [[ "$before" == */* && "$before" != *ResolverActivity* ]]; then
  sh_tv cmd package set-home-activity "$before" >/dev/null 2>&1 || true
fi
[[ "${2:-}" == "--uninstall" ]] && { adb -s "$T" uninstall "$OURS" >/dev/null && echo "   removed Pure Home"; }
sh_tv input keyevent KEYCODE_HOME
echo "✓ Restored. Home is: $(current_home)"
remind_adb_off
