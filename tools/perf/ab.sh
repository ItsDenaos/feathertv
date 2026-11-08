#!/usr/bin/env bash
# A/B on a Fire TV: FeatherTV 1.1.0 (A) vs the working tree (B), and optionally Amazon's own
# launcher (S). The variants alternate round by round so the device's state (memory, temperature)
# affects them all alike.
#
# Usage: tools/perf/ab.sh [rounds=3] [runs per round=3] [--stock] [--no-build | --build-only]
# Variables: FTV_DEVICE (default 192.168.68.105:5555), ADB, FTV_AB_BASE (the commit built as A,
# default 132db22 = FeatherTV 1.1.0). APKs and results go to build/perf/.
set -euo pipefail
export MSYS_NO_PATHCONV=1
ROUNDS=${1:-3}
RUNS=${2:-3}
STOCK=; BUILD=1; ONLY=
for arg in "$@"; do
  case "$arg" in --stock) STOCK=1 ;; --no-build) BUILD= ;; --build-only) ONLY=1 ;; esac
done
# Windows form (C:/...) under Git Bash: git and adb are Windows programs.
ROOT=$(cd "$(dirname "$0")/../.." && { pwd -W 2>/dev/null || pwd; })
PERF=$ROOT/build/perf
BASE=${FTV_AB_BASE:-132db22}
mkdir -p "$PERF"
export FTV_DEVICE=${FTV_DEVICE:-192.168.68.105:5555}
export ADB=${ADB:-${LOCALAPPDATA:-}/Android/platform-tools/adb.exe}
[ -x "$ADB" ] || ADB=adb
a() { "$ADB" -s "$FTV_DEVICE" "$@"; }
TMP=${TEMP:-/tmp}/ftv-ab

build_variant() {  # name, extra step
  local name=$1 dir=$TMP/$1
  git -C "$ROOT" worktree remove --force "$dir" >/dev/null 2>&1 || true
  git -C "$ROOT" worktree add --detach "$dir" "$( [ "$name" = A ] && echo "$BASE" || echo HEAD )" >/dev/null
  cp -r "$ROOT/keys" "$dir/"
  if [ "$name" != A ]; then
    # The working tree's sources on top of HEAD.
    cp -r "$ROOT/src" "$ROOT/res" "$ROOT/tools" "$dir/"
    cp "$ROOT/AndroidManifest.xml" "$ROOT/build.sh" "$ROOT/proguard-rules.pro" "$dir/"
  fi
  # build.sh needs Git Bash to convert its paths for the SDK tools.
  (cd "$dir" && env -u MSYS_NO_PATHCONV ./build.sh >/dev/null)
  cp "$dir/build/feathertv.apk" "$PERF/$name.apk"
  git -C "$ROOT" worktree remove --force "$dir"
  echo "built $name: $(wc -c < "$PERF/$name.apk") bytes"
}

relink() {
  # Re-reads the accessibility services, as install.ps1 does, so Guard's new settings apply.
  local s
  s=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
  a shell settings delete secure enabled_accessibility_services >/dev/null
  sleep 1
  a shell settings put secure enabled_accessibility_services "$s"
  a shell settings put secure accessibility_enabled 1
}

if [ -n "$BUILD" ]; then
  mkdir -p "$TMP"
  for v in A B; do build_variant $v; done
fi
[ -z "$ONLY" ] || exit 0

VARIANTS="A B"
[ -n "$STOCK" ] && VARIANTS="$VARIANTS S"
rm -f "$PERF"/out-*.txt
for r in $(seq "$ROUNDS"); do
  for v in $VARIANTS; do
    echo "== round $r, variant $v"
    if [ "$v" = S ]; then
      # Amazon's launcher: Guard (and any other launcher's service) off for this run.
      saved=$(a shell settings get secure enabled_accessibility_services | tr -d '\r')
      a shell settings delete secure enabled_accessibility_services >/dev/null
      a shell settings put secure accessibility_enabled 0
      a shell input keyevent KEYCODE_HOME; sleep 4
      "$ROOT/tools/bench.sh" com.amazon.tv.launcher/.ui.HomeActivity_vNext "$RUNS" | tee -a "$PERF/out-$v.txt"
      a shell settings put secure enabled_accessibility_services "$saved"
      a shell settings put secure accessibility_enabled 1
    else
      a install -r "$PERF/$v.apk" >/dev/null
      relink
      a shell am start -n tv.feathertv.home/.Home >/dev/null 2>&1; sleep 3
      "$ROOT/tools/bench.sh" tv.feathertv.home/.Home "$RUNS" | tee -a "$PERF/out-$v.txt"
    fi
  done
done

# Medians of every sample per variant.
{
  printf '%-8s %10s %10s %10s %10s %12s\n' variant on-screen in-front warm slow% "mem(excl)"
  for v in $VARIANTS; do
    [ -f "$PERF/out-$v.txt" ] || continue
    awk -v v="$v" '
      function med(s,   n, a, i, j, t) {
        n = split(s, a, " "); if (!n) return "-"
        for (i = 1; i <= n; i++) for (j = i + 1; j <= n; j++) if (a[j] + 0 < a[i] + 0) { t = a[i]; a[i] = a[j]; a[j] = t }
        return n % 2 ? a[(n + 1) / 2] : sprintf("%g", (a[n / 2] + a[n / 2 + 1]) / 2)
      }
      /^Home button:/ {
        if (match($0, /on screen: [0-9 ]+/)) shown = shown " " substr($0, RSTART + 11, RLENGTH - 11)
        if (match($0, /in front: [0-9 ]+/)) front = front " " substr($0, RSTART + 10, RLENGTH - 10)
      }
      /^Warm start:/ { if (match($0, /\([0-9 ]+\)/)) warm = warm " " substr($0, RSTART + 1, RLENGTH - 2) }
      /^Smoothness:/ { if (match($0, /\(([0-9.]+)%\)/)) slow = slow " " substr($0, RSTART + 1, RLENGTH - 3) }
      /^Idle memory:/ { mem = mem " " $(NF - 3) }
      END { printf "%-8s %10s %10s %10s %10s %12s\n", v, med(shown), med(front), med(warm), med(slow), med(mem) }
    ' "$PERF/out-$v.txt"
  done
} | tee "$PERF/summary.txt"
