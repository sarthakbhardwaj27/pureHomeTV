#!/usr/bin/env bash
# Switches off (never uninstalls) the packages in a reviewed list. Usage: ./debloat.sh <TV IP> [list]
cd "$(dirname "$0")"; source ./lib.sh
[[ $# -ge 1 ]] || die "Usage: ./debloat.sh <TV IP> [lists/xiaomi-patchwall.txt]"
LIST="${2:-lists/xiaomi-patchwall.txt}"; [[ -f "$LIST" ]] || die "List not found: $LIST"
SNAP=$(latest_backup); [[ -n "$SNAP" ]] || die "Run ./install.sh first (it saves the snapshot restore.sh needs)."
need_adb; connect "$1"
home_is_ours || die "Pure Home is not the current home screen; refusing to debloat (you could end up with no home)."

todo=()
while read -r line; do
  p="${line%%#*}"; p="$(echo "$p" | xargs)"; [[ -z "$p" ]] && continue
  valid_pkg "$p" || continue
  [[ "$p" =~ $PROTECTED_RE ]] && continue
  is_installed "$p" && is_enabled "$p" && todo+=("$p")
done < "$LIST"
(( ${#todo[@]} )) || { echo "Nothing to do: every package in $LIST is already off or not on this TV."; exit 0; }

echo "These ${#todo[@]} packages are on your TV and will be switched off (see $LIST for what each one does):"
printf '   %s\n' "${todo[@]}"
read -r -p "Proceed? [y/N] " a; [[ "$a" =~ ^[Yy]$ ]] || exit 0
REC="$SNAP/disabled-by-debloat.txt"; touch "$REC"
for p in "${todo[@]}"; do disable_pkg "$p" "$REC"; done
echo "✓ Done. Undo with ./restore.sh $1. Re-run this after a firmware update if anything comes back."
remind_adb_off
