# FeatherTV: notes for Claude

A lightweight home screen (launcher) for Fire TV and Android TV / Google TV: plain Java, no
libraries, no Gradle, no permissions, one 49 KB APK for both. Start with [README.md](README.md);
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

- Android SDK with `build-tools;29.0.2` and `platforms;android-28` (`ANDROID_HOME`), JDK 8–17
  (`JAVA_HOME`), Git for Windows (Git Bash). adb: `tools/install.ps1` looks for
  `%LOCALAPPDATA%\Android\platform-tools\adb.exe`, or set `ADB`.
- Second PC (set up 2026-11-07): repo in `D:\proyectos git\feathertv`; SDK in
  `%LOCALAPPDATA%\Android\Sdk` (so adb is `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`: pass
  `ADB` to the scripts); JDK 25 is the default but build-tools 29's R8 crashes on it
  (`NullPointerException` in `DexString`), so build with
  `JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.21.9-hotspot"`. No `.ftv-state/` here
  yet, so `install.ps1` would save FeatherTV's own setup as "before": update installs with
  `adb install -r` (plus a Guard relink on Fire OS, as `ab.sh` does) until it's copied.
- **Copy `keys/` from the old PC** (`feathertv.jks`, `keystore.pass`); it isn't in git. Without the
  same key, a new build can't be installed over the one on the TVs (it would need an uninstall,
  which also deletes the configuration). It never goes in git; the user copies it by hand (the
  second PC already has it since 2026-11-07).
- Copy `.ftv-state/` too if `-Undo` should restore exactly what was there before FeatherTV: it holds
  the installer's state per device (`<model>-<serial>/`).
- Optional: copy the old PC's `%USERPROFILE%\.android\adbkey` and `adbkey.pub`, so the TVs accept the
  new PC without asking. Otherwise each TV asks once to allow USB debugging; accept with the remote.
- On 2026-11-07 the old PC left `build/traspaso/feathertv-traspaso.zip` with `.ftv-state/`, the web
  project and a `LEEME.txt` with these steps in Spanish.
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
| Fire TV Stick Lite (AFTSS), Fire OS 7.7.1.7, 1080p | `192.168.68.114:5555` (was `.105`) | FeatherTV takes over Home through Guard. Debloated earlier (ads, telemetry, OTA blocked). An older third-party launcher is still installed with its service off; uninstalling it is pending. |
| Hyundai TV "AI PONT CO" (Changhong, MediaTek), Android TV 14, UI at 1280×720 | `192.168.68.113:5555` (was `.111`) | FeatherTV is the home screen; Google's launcher (`com.google.android.tvlauncher`) disabled with `-ReplaceStock`. Backup of its packages and settings in `.ftv-state/androidtv-respaldo/`. |

ADB over Wi-Fi: `adb connect <ip>:5555` (the TV asks to allow the computer the first time).

Memory clean-up of 2026-11-07, after asking the user which features they use (they keep Alexa /
voice and the Fire TV phone app on the Fire Stick, Google Assistant and Chromecast on the Android
TV). Undo with `pm enable <pkg>` / `cmd appops set <pkg> RUN_IN_BACKGROUND allow`:

- Fire Stick: 51 Amazon packages were already disabled before (ads, telemetry, OTA updater...).
  Added: `pm disable-user com.amazon.whisperjoin.middleware.np` (setup provisioning, ~6 MB) and
  `RUN_IN_BACKGROUND ignore` for `com.amazon.venezia` (Appstore; `com.amazon.device.software.ota`
  already had it). Fire OS 7.7 refuses to disable or force-stop both ("protected package"); the
  Appstore must stay anyway, because it runs the Kiwi licence check when Appstore apps start. All
  nine apps were checked to still open.
- Android TV: `pm disable-user` of `com.google.android.tvrecommendations` and
  `com.google.android.leanbacklauncher.partnercustomizer` (~16 MB; they only serve Google's
  launcher). Kept on purpose: Play Services and Play Store, `katniss` (Assistant),
  `mediashell` (Chromecast), the MediaTek TV app and inputs (`tvcenter`, `tvinput`, `tv.service`:
  inputs and remote hotkeys), `tv.remote.service`, `autopair`, and the `homwee` factory/synckey
  services (manufacturer's; their role isn't clear enough to risk it).
- Cached apps (Netflix after a benchmark, ~110–160 MB) aren't a problem: Android frees them
  when memory is needed.

## Commands

```bash
./build.sh                         # build/feathertv.apk (R8); FTV_DEBUG=1 skips R8
tools/bench.sh tv.feathertv.home/.Home 5 --cold   # FTV_DEVICE=ip:5555, ADB=path, FTV_REMOTE=0
tools/perf/ab.sh 3 3 --stock       # Fire TV A/B: 1.1.0 vs working tree (+ Amazon); FTV_AB_BASE
tools/perf/trace-home.sh label     # atrace of one Home press + summary of FeatherTV's threads
```

```powershell
powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device 192.168.68.114:5555   # -NoInstall, -Undo, -ReplaceStock
```

The version lives in `build.sh` (`VERSION_CODE`, `VERSION_NAME`); bumping it is the user's call.

## Where things stand (2026-11-07)

- On GitHub (`ItsDenaos/feathertv`), `main` is at `1cbb1ee`: PR #1 merged `android-tv` (the
  optimizations: glow mask, opaque window, art kept across reloads, HARDWARE bitmaps, curtain lifts
  at once; and the measuring tools).
- **1.2.0 (versionCode 4)**, committed on 2026-11-07 on top of `1cbb1ee` ("FeatherTV 1.2.0: inputs,
  early Home trigger and measured optimizations"); local `main` and `android-tv` both point to it:
  - Measured on both TVs on 2026-11-07 (tables in `docs/PERFORMANCE.md`). Fire OS: smoother
    (3.9 → 2.6 % slow frames), 4 MB less, Home unchanged; Android TV: 4–6 MB less, times within
    the noise (only `am start -W` back home ~50 ms slower in both runs, unexplained).
  - **Early trigger applied** (`Guard` + `res/xml/guard.xml`): in the A/B it put FeatherTV on screen
    ~500 ms sooner (1214 vs 1740 ms; Amazon's own launcher: 974 ms). The patch file and variant C
    of `ab.sh` are gone.
  - **Inputs** (new, asked by the user: replacing Google's launcher lost access to HDMI 1/2/3):
    `Inputs.java` lists the TV's passthrough inputs (`TvInputManager`, hidden ones skipped, custom
    labels, HDMI-CEC device name next to its port, "nothing connected" from `getInputState`);
    an "Inputs" pill between FeatherTV and Settings opens them in the side panel; choosing one opens
    `TvContract.buildChannelUriForPassthroughInput(id)`, which the MediaTek TV app
    (`com.mediatek.wwtv.tvcenter`) handles. Hidden when there are no inputs (Fire TV Stick).
    Tested on the Hyundai: HDMI 1–3 and AV listed, switching to AV worked, Home returns.
  - `tools/bench.sh`: the virtual remote is fed through `hid -` on adb's stdin (see Measuring).
- Installed right now: 1.2.0's code on both TVs (`adb install -r`), built before the bump, so it
  reports 1.1.0 / versionCode 3; the 1.2.0 build differs only in the version.
- Not covered yet by the FeatherTV web page (`../feathertv-web`): the inputs button and the new
  measurements.

### Next steps

1. The user pushes `main` and `android-tv` (`git push origin main android-tv`) if
   `git status` still shows them ahead of `origin`.
2. Optional: `tools/perf/trace-home.sh` to see what still delays FeatherTV's first frame on Fire OS
   (now ~1.2 s vs Amazon's ~1.0 s).
3. Optional: Google's launcher on the Android TV for comparison needs `install.ps1 -Undo`, which
   needs the old PC's `.ftv-state/`.
4. Update the web page with the inputs button and the new numbers.
5. Deferred by the user: AdGuard DNS with custom rules (block Fire OS OTA and ads; it might also make
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
  ~450 ms before `WINDOW_STATE_CHANGED`; reacting to it (the early trigger) looked useless in a
  first test of four presses but put FeatherTV on screen ~500 ms sooner in the 9-press A/B.
- **Measuring**: `input keyevent` takes another path than a real remote on Fire OS and its own
  startup costs ~1 s on Android 9. The `hid` tool plus `/dev/uhid` (shell is in the `uhid` and
  `input` groups) gives a real-key path; `getevent -lt` timestamps and `logcat -v monotonic` share
  a clock; `dumpsys SurfaceFlinger --latency <layer>` gives frame present times. Android 10+ calls
  the event `wm_set_resumed_activity` (Android 9: `am_`) and meminfo prints `TOTAL PSS:`.
- **Virtual remote plumbing**: Fire OS refuses `mkfifo` in `/data/local/tmp` (SELinux), and a
  background process started inside `adb shell` keeps the command from returning unless all its
  streams are redirected; `bench.sh` therefore keeps `adb shell hid -` running as a coprocess and
  writes the events to its stdin. `pkill -f` with a pattern that appears in its own `sh -c` line
  kills that shell first: use `'^sleep 1000[0]0'`-style patterns.
- **Both TVs restarted together** at 18:28 on 2026-11-07 (same uptime), most likely a power cut,
  not the scripts. The Hyundai's load average sits around 55 even when idle (its MediaTek audio
  service takes ~70 % of a core): that's normal there, not a sign of a busy TV.
- **Android TV 14**: the HOME role or `set-home-activity` aren't enough while Google's launcher is
  enabled; `-ReplaceStock` disables it (reversible). Task snapshots are disabled on TVs, so there is
  no preview window when returning to an app.
- **APK size** barely moves with code changes: v2 signing pads the APK to 4 KB blocks.

## Web page (separate project)

`C:\Proyectos\feathertv-web` is not part of this repo (git repo without commits yet, Spanish). It
isn't on the other PC unless it's copied (it's in the transfer zip) or pushed separately. It's published as a private artifact,
<https://claude.ai/artifact/LBm8KeueLjwA7KwmUYPfHF>; from another conversation, republish by passing
that URL. Its README explains the screenshots, the artifact build (`build/feathertv.html`) and where
the numbers come from.
