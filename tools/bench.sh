#!/usr/bin/env bash
# Benchmarks a launcher on a Fire TV or an Android TV, reporting medians over several runs.
#
# Usage: tools/bench.sh <package/.Activity> [runs=5] [--cold]
#   Warm start      am start -W from another app (TotalTime)
#   Home button     ms from the key press until the launcher's first frame is on screen
#                   (SurfaceFlinger) and until it is in front (resumed). Only when Home opens that
#                   launcher: on Fire OS, when its accessibility service is active. The key is
#                   pressed on a virtual remote (uhid) when the device allows it, which takes the
#                   same path as a real remote; otherwise it is injected with input keyevent.
#   Smoothness      slow frames while moving along the row (dumpsys gfxinfo)
#   Idle memory     total PSS and PSS excluding graphics (screen buffers depend on the system)
#   --cold          cold start (force-stop). Fire OS disables a force-stopped app's accessibility
#                   service; the script enables it again afterwards.
#
# Variables: ADB (path to adb), FTV_DEVICE (ip:port), FTV_TEST_APP (app to return from),
#   FTV_REMOTE=0 (no virtual remote: Home is pressed with input keyevent).
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

# Virtual remote: a consumer-control HID device whose only key is Home (AC Home, KEY_HOMEPAGE).
# It stays registered for the whole run (adding an input device makes the app in front reload,
# which would disturb the measurements): `hid -` on the device reads its events from this
# script through adb's stdin. A pipe on the device doesn't work: Fire OS refuses mkfifo in
# /data/local/tmp, and a background process there keeps `adb shell` from returning.
R=/data/local/tmp/ftv-remote
remote_start() {
  [ "${FTV_REMOTE:-1}" != 0 ] || return 0
  sh "command -v hid" >/dev/null 2>&1 || return 0
  coproc HID { a shell hid - >/dev/null 2>&1; }
  exec {REMOTE_FD}>&"${HID[1]}"
  echo '{"id": 7, "command": "register", "name": "FeatherTV bench remote", "vid": 6353, "pid": 32343,
    "descriptor": [5, 12, 9, 1, 161, 1, 133, 1, 21, 0, 38, 255, 3, 25, 0, 42, 255, 3, 117, 16, 149, 1, 129, 0, 192]}' >&$REMOTE_FD
  sleep 2.5
  REMOTE=$(sh "getevent -pl 2>/dev/null | grep -B1 'FeatherTV bench remote' | sed -n '1s/.*: //p'" || true)
}
remote_stop() {
  # Closing hid's input unregisters the remote.
  [ -z "${REMOTE_FD:-}" ] || exec {REMOTE_FD}>&-
  [ -z "${HID_PID:-}" ] || { sleep 1; kill "$HID_PID" 2>/dev/null || true; }
  sh "rm -f $R.key" >/dev/null 2>&1 || true
}
REMOTE=
trap remote_stop EXIT
remote_start

# Presses Home; prints the monotonic time of the key press (empty: read it from the log).
press_home() {
  if [ -n "$REMOTE" ]; then
    # getevent runs detached (every stream redirected) so that this adb shell returns at once.
    sh "rm -f $R.key; (getevent -lt -c 3 $REMOTE > $R.key 2>/dev/null < /dev/null &)" >/dev/null
    sleep 0.3
    echo '{"id": 7, "command": "report", "report": [1, 35, 2]}' >&$REMOTE_FD
    sleep 0.09
    echo '{"id": 7, "command": "report", "report": [1, 0, 0]}' >&$REMOTE_FD
    sleep 0.4
    sh "cat $R.key" |
      awk '/KEY_HOMEPAGE/ && /DOWN/ && !f {match($0, /[0-9]+\.[0-9]+/); print substr($0, RSTART, RLENGTH); f=1}'
  else
    # Android 10+ no longer logs injected keys: a log line right before the key marks the time.
    # It also counts the start of the input command (tens of ms), the same for every launcher.
    sh 'log -t FtvBench home; input keyevent KEYCODE_HOME' >/dev/null
  fi
}

# Monotonic time of the first frame the launcher put on screen after $1, from SurfaceFlinger's
# frame history of every layer of the package (on Fire OS, Guard's curtain lifts a frame or two
# after FeatherTV's first frame).
first_frame() {
  local best= f layer
  while IFS= read -r layer; do
    [ -n "$layer" ] || continue
    # </dev/null: otherwise adb would read the rest of the layer list as its input.
    f=$(sh dumpsys SurfaceFlinger --latency "'$layer'" </dev/null |
      awk -v t="$1" 'NR > 1 && $2 > 0 && $2 < 9e18 && $2 / 1e9 > t && !f {printf "%.6f\n", $2 / 1e9; f=1}')
    if [ -n "$f" ] && { [ -z "$best" ] || awk -v a="$f" -v b="$best" 'BEGIN {exit !(a < b)}'; }; then best=$f; fi
  done <<< "$(sh dumpsys SurfaceFlinger --list | grep -F "$PKG/" || true)"
  echo "$best"
}

ms() { awk -v a="$1" -v b="$2" 'BEGIN {printf "%.0f", (b - a) * 1000}'; }

warm=(); front=(); shown=()
for i in $(seq "$N"); do
  open_test_app
  warm+=("$(sh am start -W -n "$COMP" | awk -F': ' '/TotalTime/ {print $2}')")
  open_test_app
  sh logcat -b main -b system -b events -c
  sh dumpsys SurfaceFlinger --latency-clear >/dev/null
  t_key=$(press_home)
  # SurfaceFlinger keeps the last 128 frames per layer: read them before an animated launcher
  # (Amazon's or Google's) pushes the first ones out.
  sleep 2.5
  log=$(sh logcat -b main -b system -b events -d -v monotonic)
  # Without the virtual remote: the injected key's own log line if there is one (on Android 9 the
  # input command alone takes about a second to start), otherwise the mark. The awk scripts never
  # stop reading early: with pipefail, an early "exit" would break the pipe and stop the script.
  [ -n "$t_key" ] || t_key=$(printf '%s\n' "$log" | awk '
    !k && /injectKeyEvent/ && /ACTION_DOWN/ && /KEYCODE_HOME/ {k = $1}
    !m && / FtvBench/ {m = $1}
    END {print k ? k : m}')
  t_front=$(printf '%s\n' "$log" | awk -v p="$PKG/" '!f && /[aw]m_set_resumed_activity/ && index($0, p) {print $1; f=1}')
  t_shown=$([ -n "$t_key" ] && first_frame "$t_key" || true)
  [ -n "$t_key" ] && [ -n "$t_front" ] && front+=("$(ms "$t_key" "$t_front")")
  [ -n "$t_key" ] && [ -n "$t_shown" ] && shown+=("$(ms "$t_key" "$t_shown")")
  sleep 1.5
done
echo "Warm start:    $(median "${warm[@]}") ms   (${warm[*]})"
if [ ${#front[@]} -eq 0 ]; then
  echo "Home button:   no data: Home does not open this launcher"
else
  echo "Home button:   on screen $(median "${shown[@]:-}") ms, in front $(median "${front[@]}") ms   ($([ -n "$REMOTE" ] && echo "virtual remote" || echo "input keyevent"); on screen: ${shown[*]:-no data}; in front: ${front[*]})"
fi

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
