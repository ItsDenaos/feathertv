# FeatherTV: notes for Claude

A lightweight home screen (launcher) for Fire TV and Android TV / Google TV: plain Java, no
libraries, no Gradle, no permissions, one 46 KB APK for both. Start with [README.md](README.md);
design in [docs/DESIGN.md](docs/DESIGN.md), measurements in [docs/PERFORMANCE.md](docs/PERFORMANCE.md),
comparison with the stock home screen in [docs/COMPARISON.md](docs/COMPARISON.md).

## Working with the user

- Talk to the user in Spanish. Everything in the repo (docs, code comments, commit messages) is in
  English.
- The user makes every commit and push. Never commit: when work is ready, write the commit message
  to `build/commit-msg.txt` (no Co-Authored-By line) and show it.
- Compare FeatherTV only with the stock launchers (Amazon's on Fire OS, Google's on Android TV).
  Don't mention or compare with other third-party launchers.
- Change the TVs (install, settings, reboots) only as part of what the user asked; say what you did.

## Setting up on another PC (Windows)

- Android SDK with `build-tools;29.0.2` and `platforms;android-28` (`ANDROID_HOME`), JDK 8+
  (`JAVA_HOME`), Git for Windows (Git Bash). adb: `tools/install.ps1` looks for
  `%LOCALAPPDATA%\Android\platform-tools\adb.exe`, or set `ADB`.
- **Copy `keys/` from the old PC** (`feathertv.jks`, `keystore.pass`); it isn't in git. Without the
  same key, a new build can't be installed over the one on the TVs (it would need an uninstall,
  which also deletes the configuration).
- Copy `.ftv-state/` too if `-Undo` should restore exactly what was there before FeatherTV: it holds
  the installer's state per device (`<model>-<serial>/`).
- Windows quirks seen on the old PC:
  - PowerShell 5.1 runs in constrained language mode: no .NET type calls (`[System...]::`).
  - Git Bash: device paths (`/data/...`, `/sdcard/...`) need `MSYS_NO_PATHCONV=1`, but `build.sh`
    needs that conversion on (the SDK tools are Windows programs). Under `MSYS_NO_PATHCONV=1`, pass
    Windows-form paths (`pwd -W`) to git and adb.
  - Very long paths (such as Claude's scratchpad) break adb and other tools: use a short temp folder.
  - Headless Chrome won't go below 512 px of width: check 390 px layouts inside an iframe.

## Devices (home network; DHCP may change the addresses)

| Device | Address | State |
|---|---|---|
| Fire TV Stick Lite (AFTSS), Fire OS 7.7.1.7, 1080p | `192.168.68.105:5555` | FeatherTV takes over Home through Guard. Debloated earlier (ads, telemetry, OTA blocked). An older third-party launcher is still installed with its service off; uninstalling it is pending. |
| Hyundai TV "AI PONT CO" (Changhong), Android TV 14, UI at 1280×720 | `192.168.68.111:5555` | FeatherTV is the home screen; Google's launcher (`com.google.android.tvlauncher`) disabled with `-ReplaceStock`. Backup of its packages and settings in `.ftv-state/androidtv-respaldo/`. |

ADB over Wi-Fi: `adb connect <ip>:5555` (the TV asks to allow the computer the first time).

## Commands

```bash
./build.sh                         # build/feathertv.apk (R8); FTV_DEBUG=1 skips R8
tools/bench.sh tv.feathertv.home/.Home 5 --cold   # FTV_DEVICE=ip:5555, ADB=path
tools/perf/ab.sh 3 3 --stock       # Fire TV A/B: 1.1.0 vs working tree vs early trigger (+ Amazon)
tools/perf/trace-home.sh label     # atrace of one Home press + summary of FeatherTV's threads
```

```powershell
powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device 192.168.68.105:5555   # -NoInstall, -Undo, -ReplaceStock
```

The version lives in `build.sh` (`VERSION_CODE`, `VERSION_NAME`); bumping it is the user's call.

## Where things stand (2026-10-20)

- `main`: 1.0.0 (`ef9c581`). Branch `android-tv`: 1.1.0 committed (`132db22`), not merged yet.
- **Uncommitted on `android-tv`, compiled but not yet measured on a TV** (the TVs were off):
  - `Stage`: the glow is one alpha mask computed once and tinted by the GPU, with an additive
    cross-fade (it used to repaint and upload a bitmap on every focus change). Checked offline: at
    most 1–2 levels out of 255 from the old one.
  - `Home`/`Options`: window declared opaque (a null background made it translucent); the greeting
    isn't set again when unchanged (that laid out the whole screen); no settings query when Guard is
    running; apps open with the component found at load time (package manager only as fallback).
  - `Catalog`: unchanged apps keep their art across reloads (installs, updates, renames used to
    repaint them all); art moved to GPU memory on Android 9+ (`Bitmap.Config.HARDWARE`); the
    loading thread runs at nice 5.
  - `Guard`: the curtain lifts at once (its window is opaque, so the 120 ms "fade" was solid black).
  - `tools/bench.sh`: Home measured to the first frame **on screen** (SurfaceFlinger) as well as
    to "in front", pressing Home on a virtual remote (uhid) when the device allows it.
  - `tools/perf/`: the A/B and trace tooling; `early-trigger.patch` is the experiment below.
  - Docs: where the Home button's time goes, what can't be done on Fire OS (both measured).
- Installed right now: the Fire Stick runs an experimental build (the early trigger); the Android
  TV runs 1.1.0.
- The FeatherTV web page (`../feathertv-web`, see below) already covers Android TV.

### Next steps

1. With the Fire Stick on: `tools/perf/ab.sh 3 3 --stock`, then read `build/perf/summary.txt`
   (A = 1.1.0, B = working tree, C = B + early trigger, S = Amazon's launcher).
2. Keep what wins. If C beats B on "on screen", apply `tools/perf/early-trigger.patch` for real;
   either way, delete the patch and drop variant C from `ab.sh` afterwards.
3. Optional: `tools/perf/trace-home.sh` to see what still delays FeatherTV's first frame.
4. Android TV: `FTV_DEVICE=192.168.68.111:5555 tools/bench.sh tv.feathertv.home/.Home 5 --cold`
   (and Google's launcher, re-enabled with `install.ps1 -Undo`, for comparison).
5. Put the measured results in `docs/PERFORMANCE.md` ("What was optimized"), reinstall on both TVs
   with `install.ps1`, write the commit message.
6. Deferred by the user: AdGuard DNS with custom rules (block Fire OS OTA and ads; it might also make
   Amazon's launcher lighter, which is what delays Home on Fire OS); uninstall the old launcher from
   the Fire Stick.

## Hard-won facts

- **Fire OS Home takeover** (see README "How it takes over the Home button"): Guard, an
  accessibility service limited to `com.amazon.tv.launcher` window events, never reads the screen;
  black curtain (`TYPE_ACCESSIBILITY_OVERLAY`); FeatherTV opened with an immediate alarm, because
  Android delays app launches 5 s after Home except system ones (needs
  `alarm_manager_constants min_futurity=0`); `resumeWhilePausing` saves ~400 ms.
- **Not possible on Fire OS 7.7**: catching Home with an accessibility key filter (Amazon's key
  policy handles Home first; Back and the D-pad do reach the filter); the `HOME_PRESSED` broadcast
  (Amazon's apps only, system permission); `CLOSE_SYSTEM_DIALOGS reason=homekey` (skipped for
  non-system apps). Long-pressing Home opens Amazon's quick menu (`HudActivity`, action
  `com.amazon.device.settings.action.SHOW_HUD`).
- **Home timeline on Fire OS** (1.1.0, real-key path): Amazon starts at ~160 ms, reports its window
  at ~930 ms, FeatherTV in front ~1040 ms, on screen ~1460 ms. FeatherTV's draw takes 400–600 ms
  there versus ~130 ms without Amazon's launcher competing. Amazon's `WINDOW_CONTENT_CHANGED` comes
  ~450 ms before `WINDOW_STATE_CHANGED`; reacting to it (the early trigger) brought FeatherTV to the
  front sooner but not on screen sooner in a first test of four presses.
- **Measuring**: `input keyevent` takes another path than a real remote on Fire OS and its own
  startup costs ~1 s on Android 9. The `hid` tool plus `/dev/uhid` (shell is in the `uhid` and
  `input` groups) gives a real-key path; `getevent -lt` timestamps and `logcat -v monotonic` share
  a clock; `dumpsys SurfaceFlinger --latency <layer>` gives frame present times. Android 10+ calls
  the event `wm_set_resumed_activity` (Android 9: `am_`) and meminfo prints `TOTAL PSS:`.
- **Android TV 14**: the HOME role or `set-home-activity` aren't enough while Google's launcher is
  enabled; `-ReplaceStock` disables it (reversible). Task snapshots are disabled on TVs, so there is
  no preview window when returning to an app.
- **APK size** barely moves with code changes: v2 signing pads the APK to 4 KB blocks.

## Web page (separate project)

`C:\Proyectos\feathertv-web` is not part of this repo (git repo without commits yet, Spanish). It
isn't on the other PC unless it's copied or pushed separately. It's published as a private artifact,
<https://claude.ai/artifact/LBm8KeueLjwA7KwmUYPfHF>; from another conversation, republish by passing
that URL. Its README explains the screenshots, the artifact build (`build/feathertv.html`) and where
the numbers come from.
