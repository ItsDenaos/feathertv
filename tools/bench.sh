#!/usr/bin/env bash
# Benchmarks a launcher on a Fire TV or an Android TV, reporting medians over several runs.
#
# Usage: tools/bench.sh <package/.Activity> [runs=5] [--cold]
#   Warm start      am start -W from another app (TotalTime)
#   Home button     ms from the key press until the launcher is in front (only when Home opens
#                   that launcher: on Fire OS, when its accessibility service is active)
#   Smoothness      slow frames while moving along the row (dumpsys gfxinfo)
#   Idle memory     total PSS and PSS excluding graphics (screen buffers depend on the system)
#   --cold          cold start (force-stop). Fire OS disables a force-stopped app's accessibility
#                   service; the script enables it again afterwards.
#
# Variables: ADB (path to adb), FTV_DEVICE (ip:port), FTV_TEST_APP (app to return from).
set -euo pipefail
export MSYS_NO_PATHCONV=1   # Git Bash: don't turn device paths (/data/...) into Windows paths
COMP="${1:?pass the component, e.g. tv.feathertv.home/.Home}"
N="${2:-5}"
COLD="${3:-}"
PKG="${COMP%%/*}"
ADB="${ADB:-adb}"
TEST_APP="${FTV_TEST_APP:-com.netflix.ninja}"

a() { "$ADB" ${FTV_DEVICE:+-s "$FTV_DEVICE"} "$@"; }
sh() { a shell "$@" | tr -d '\r'; }
# Opened with am start: monkey sometimes refuses on Fire OS ("SYS_KEYS has no physical keys").
TEST_COMP=$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER "$TEST_APP" | tail -1)
open_test_app() { sh am start -n "$TEST_COMP" >/dev/null 2>&1 || true; sleep 6; }
# TotalTime 0 means nothing was launched (the activity was already in front): not a sample.
median() {
  printf '%s\n' "$@" | grep -E '^[0-9.]+$' | grep -vx 0 | sort -n |
    awk '{v[NR]=$1} END {if (!NR) {print "-"; exit} if (NR%2) print v[(NR+1)/2]; else printf "%.0f\n", (v[NR/2]+v[NR/2+1])/2}'
}
keys() { for k in "$@"; do sh input keyevent "$k"; sleep 0.35; done; }

sh pm path "$PKG" >/dev/null || { echo "$PKG is not installed" >&2; exit 1; }
echo "== $COMP"
echo "APK:           $(sh stat -c %s "$(sh pm path "$PKG" | head -1 | cut -d: -f2)") bytes"

warm=(); home=()
for i in $(seq "$N"); do
  open_test_app
  warm+=("$(sh am start -W -n "$COMP" | awk -F': ' '/TotalTime/ {print $2}')")
  open_test_app
  sh logcat -b main -b system -b events -c
  # The device clock right before the key, for Android 10+, which no longer logs injected keys.
  # It also counts the start of the input command (tens of ms), the same for every launcher.
  t_mark=$(sh 'date +%s.%N; input keyevent KEYCODE_HOME' | sed -n 1p)
  sleep 4
  # From the moment the Home key is injected until the launcher is in front
  # (am_/wm_set_resumed_activity in the events log). The awk scripts never stop reading early:
  # with pipefail, an early "exit" would break the pipe and stop the script.
  log=$(sh logcat -b main -b system -b events -d -v epoch)
  t_key=$(printf '%s\n' "$log" | awk '!f && /injectKeyEvent/ && /ACTION_DOWN/ && /KEYCODE_HOME/ {print $1; f=1}')
  [ -n "$t_key" ] || t_key=$t_mark
  t_pkg=$(printf '%s\n' "$log" | awk -v p="$PKG/" '!f && /[aw]m_set_resumed_activity/ && index($0, p) {print $1; f=1}')
  if [ -n "$t_key" ] && [ -n "$t_pkg" ]; then
    home+=("$(awk -v a="$t_key" -v b="$t_pkg" 'BEGIN {printf "%.0f", (b - a) * 1000}')")
  fi
done
echo "Warm start:    $(median "${warm[@]}") ms   (${warm[*]})"
echo "Home button:   $(median "${home[@]:-}") ms   (${home[*]:-no data: Home does not open this launcher})"

# Smoothness: along the row and back with the launcher in front.
sh am start -W -n "$COMP" >/dev/null; sleep 3
sh dumpsys gfxinfo "$PKG" reset >/dev/null
keys 22 22 22 22 22 22 22 21 21 21 21 21 21 21
gfx=$(sh dumpsys gfxinfo "$PKG")
frames=$(printf '%s\n' "$gfx" | awk -F': ' '/Total frames rendered/ {if (!f) print $2; f=1}')
janky=$(printf '%s\n' "$gfx" | awk -F': ' '/Janky frames/ {if (!f) print $2; f=1}')
p90=$(printf '%s\n' "$gfx" | awk -F': ' '/90th percentile/ {if (!f) print $2; f=1}')
p99=$(printf '%s\n' "$gfx" | awk -F': ' '/99th percentile/ {if (!f) print $2; f=1}')
echo "Smoothness:    $frames frames, slow $janky, p90 $p90, p99 $p99"

# Idle memory: three samples after 10 s without input.
sleep 10
tot=(); app=()
for i in 1 2 3; do
  mem=$(sh dumpsys meminfo "$PKG")
  # "TOTAL:" up to Android 9, "TOTAL PSS:" from Android 10 on.
  t=$(printf '%s\n' "$mem" | awk '!f && match($0, /TOTAL( PSS)?: +[0-9]+/) {s = substr($0, RSTART, RLENGTH); gsub(/[^0-9]/, "", s); print s; f=1}')
  g=$(printf '%s\n' "$mem" | awk '/^ *Graphics:/ {if (!f) print $2; f=1}')
  tot+=("$t"); app+=("$((t - ${g:-0}))")
  sleep 2
done
echo "Idle memory:   $(median "${tot[@]}") KB total, $(median "${app[@]}") KB excluding graphics"

if [ "$COLD" = "--cold" ]; then
  services=$(sh settings get secure enabled_accessibility_services)
  cold=()
  for i in $(seq "$N"); do
    open_test_app
    sh am force-stop "$PKG"
    sleep 1
    cold+=("$(sh am start -W -n "$COMP" | awk -F': ' '/TotalTime/ {print $2}')")
  done
  if [ -n "$services" ] && [ "$services" != "null" ]; then
    sh settings delete secure enabled_accessibility_services
    sleep 1
    sh settings put secure enabled_accessibility_services "$services"
    sh settings put secure accessibility_enabled 1
  fi
  echo "Cold start:    $(median "${cold[@]}") ms   (${cold[*]})"
fi
