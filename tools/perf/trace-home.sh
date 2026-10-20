#!/usr/bin/env bash
# Records a system trace (atrace) of one Home press from the test app, pressed on a virtual remote,
# and summarizes where FeatherTV's time goes until its first frame.
#
# Usage: tools/perf/trace-home.sh [label]   (the trace and its summary go to build/perf/)
# Variables: FTV_DEVICE (default 192.168.68.105:5555), ADB, FTV_TEST_APP (default Netflix).
set -euo pipefail
export MSYS_NO_PATHCONV=1
LABEL=${1:-trace}
HERE=$(cd "$(dirname "$0")" && pwd)
# Windows form (C:/...) under Git Bash: adb is a Windows program.
PERF=$(cd "$HERE/../.." && { pwd -W 2>/dev/null || pwd; })/build/perf
mkdir -p "$PERF"
DEV=${FTV_DEVICE:-192.168.68.105:5555}
ADB=${ADB:-${LOCALAPPDATA:-}/Android/platform-tools/adb.exe}
[ -x "$ADB" ] || ADB=adb
TEST_APP=${FTV_TEST_APP:-com.netflix.ninja}
a() { "$ADB" -s "$DEV" "$@"; }
sh() { a shell "$@" | tr -d '\r'; }
R=/data/local/tmp/ftv-remote

# Virtual remote (same as tools/bench.sh).
a shell "cat > $R-register.json" <<'EOF'
{"id": 7, "command": "register", "name": "FeatherTV bench remote", "vid": 6353, "pid": 32343,
 "descriptor": [5, 12, 9, 1, 161, 1, 133, 1, 21, 0, 38, 255, 3, 25, 0, 42, 255, 3, 117, 16, 149, 1, 129, 0, 192]}
EOF
a shell "echo '{\"id\": 7, \"command\": \"report\", \"report\": [1, 35, 2]}' > $R-down.json"
a shell "echo '{\"id\": 7, \"command\": \"report\", \"report\": [1, 0, 0]}' > $R-up.json"
trap 'sh "pkill -f com.android.commands.hid; pkill -f \"sleep 100000\"; rm -f $R" >/dev/null 2>&1 || true' EXIT
sh "rm -f $R; mkfifo $R; (sleep 100000 > $R &); (hid $R > /dev/null 2>&1 &); cat $R-register.json > $R" >/dev/null

TEST=$(sh cmd package resolve-activity --brief -a android.intent.action.MAIN \
  -c android.intent.category.LEANBACK_LAUNCHER "$TEST_APP" | tail -1)
sh am start -n "$TEST" >/dev/null 2>&1 || true
sleep 7
PID=$(sh pidof tv.feathertv.home || true)
MARKER=$(sh 'ls /sys/kernel/tracing/trace_marker /sys/kernel/debug/tracing/trace_marker 2>/dev/null' | head -1)
echo "FeatherTV pid $PID, marker $MARKER"

# 6 s of trace; Home is pressed 1.5 s in, with a mark in the trace at that moment.
sh "atrace -b 32768 -t 6 gfx view wm am input sched freq binder_driver dalvik > /data/local/tmp/ftv.trace 2>&1 &
    sleep 1.5; echo FTV-HOME-KEY > $MARKER; cat $R-down.json > $R; sleep 0.09; cat $R-up.json > $R; sleep 6" >/dev/null
a pull /data/local/tmp/ftv.trace "$PERF/$LABEL.trace" >/dev/null
echo "trace: $PERF/$LABEL.trace ($(wc -c < "$PERF/$LABEL.trace") bytes)"
perl "$HERE/summarize.pl" "$PERF/$LABEL.trace" "$PID" | tee "$PERF/$LABEL.summary.txt"
