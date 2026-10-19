# FeatherTV

A lightweight launcher for Fire TV (Fire OS 5–8) and Android TV, with a "Stage" design inspired
by Apple TV. Plain Java, no libraries, no Gradle and **no permissions** (not even internet).

**Version 1.1.0** · 46 KB APK · 6 languages · tested on a Fire TV Stick Lite running Fire OS 7.7.1.7
and on an Android TV 14 television.

| | |
|---|---|
| Design | [docs/DESIGN.md](docs/DESIGN.md) |
| Comparison with the Fire OS home screen | [docs/COMPARISON.md](docs/COMPARISON.md) |
| Performance measurements | [docs/PERFORMANCE.md](docs/PERFORMANCE.md) |
| Sample configuration | [tools/feather.conf.example](tools/feather.conf.example) |

## What it does

- A clean home screen: greeting and time, the FeatherTV button next to Settings, the focused app's
  name in large type over a glow in that app's color, and a row with your apps.
- Replaces the stock home screen when you press Home and at startup, with no ads or recommendations.
- Fully driven by the remote: move, hide and rename apps, plus an Options screen.
- English, Spanish, Portuguese, French, Italian and German.
- One APK for Fire TV and for Android TV / Google TV: the installer detects which one it's on.

## Installing (Windows)

1. Turn on ADB on the TV and note its IP address:
   - **Fire TV**: My Fire TV → Developer options → **ADB debugging**.
   - **Android TV / Google TV**: tap the build number 7 times (Device Preferences → About on
     Android TV, System → About on Google TV), then turn on **USB debugging** in Developer options.
2. Build the APK (see below) or use an existing `build/feathertv.apk`.
3. Run:

```powershell
powershell -ExecutionPolicy Bypass -File tools\install.ps1 -Device 192.168.1.50:5555
```

- **On Fire TV**, the installer exempts FeatherTV from battery optimization, sets `min_futurity=0`
  in `alarm_manager_constants` and enables the **Guard** accessibility service, disabling the
  accessibility services of any other launcher installed on the device so they don't compete.
- **On Android TV / Google TV**, it sets FeatherTV as the preferred home screen and checks which app
  Home really opens. Some builds, Google TV in particular, keep their own launcher; in that case run
  it again with `-ReplaceStock`, which disables (`pm disable-user`) only the app that still takes the
  home role.

Everything it changes is saved in `.ftv-state/<model>-<serial>/`, one folder per device, so it can be
undone.

| To… | Command |
|---|---|
| Go back to the previous home screen | `tools\install.ps1 -Device … -Undo` |
| Only re-link the Home button | `tools\install.ps1 -Device … -NoInstall` |
| Replace a stock launcher that won't step aside (Android TV) | `tools\install.ps1 -Device … -ReplaceStock` |
| Uninstall FeatherTV (after `-Undo`) | `adb uninstall tv.feathertv.home` |

## Using the remote

- **← →** move through your apps; **↓** from the top bar goes back to the row.
- **↑** goes to **Settings** (the TV's own); from there **←** moves to **FeatherTV**, which opens
  FeatherTV's options.
- **Home** while on FeatherTV jumps back to the first app.
- **☰ or a long press on OK over an app**: Move, Hide, Rename, App info (uninstall, clear cache…)
  and FeatherTV options.
- **Move**: ◀ ▶ shift the app; OK, Back or ☰ finish and save the order.
- **Options**: language, focus color, tile size, clock (automatic, 12-hour or 24-hour), background
  (stage or pure black, the smoothest), hidden apps (to show them again) and reset order and names.
  OK or ▶ moves to the next value, ◀ to the previous one. Every change is saved immediately.

The configuration lives in `/sdcard/Android/data/tv.feathertv.home/files/feather.conf` and can also
be edited from a computer with `adb push` (see the [sample](tools/feather.conf.example)).

## How it takes over the Home button

On **Android TV / Google TV** there's nothing special: FeatherTV declares itself a home screen and
the installer makes it the default, so Home opens it directly. Guard stays idle there, because it
only reacts to Amazon's launcher.

On **Fire OS** it takes more work:

- Fire OS doesn't let you intercept Home or choose another launcher. **Guard** only receives
  window-change events from Amazon's launcher (it never reads the screen) and reacts only to its
  main screen (`HomeActivity`), so Alexa searches and other windows are left alone.
- When that screen appears, Guard shows a **black curtain** (an accessibility window created in
  advance) and opens FeatherTV with an **immediate alarm**: Android delays app launches by 5 s after
  Home is pressed, except launches started by the system. This needs `min_futurity=0`.
- The home activity declares `resumeWhilePausing`, so Android shows it without waiting for Amazon's
  launcher to finish pausing (that wait cost about 400 ms on every press).
- The `CLOSE_SYSTEM_DIALOGS` broadcast (`reason=homekey`) would arrive before Amazon's window, but
  Fire OS 7 doesn't deliver it to non-system apps (it shows up as *Skipped*).
- The curtain fades out once FeatherTV is on screen, or by itself after 2.5 s if something fails.
- If the service gets disabled (for example after "Force stop"), FeatherTV shows a notice asking
  you to run the installer again with `-NoInstall`.

## Languages

FeatherTV follows the system language by default; Options can set a different one for FeatherTV
only. To add a language, create `res/values-xx/strings.xml` with the same keys.

## Building

Requirements: the Android SDK with `build-tools;29.0.2` and `platforms;android-28` (`ANDROID_HOME`),
JDK 8 or later (`JAVA_HOME`) and Bash (Git Bash on Windows).

```bash
./build.sh               # builds build/feathertv.apk (with R8)
./build.sh --install     # also installs it with adb (FTV_DEVICE=ip:5555)
FTV_DEBUG=1 ./build.sh   # without R8, with readable names in stack traces
```

The pipeline is aapt2 → javac → R8 → resource recompression ([tools/Repack.java](tools/Repack.java))
→ zipalign → apksigner. R8's name map is written to `build/mapping.txt`.

## Signing key

`keys/feathertv.jks` and `keys/keystore.pass` sign every release. **Keep a copy somewhere other than
this computer**: without that key you can't install an update over an existing install (you would
have to uninstall first). The `keys/` folder is not committed.

## Measuring performance

```bash
FTV_DEVICE=192.168.1.50:5555 tools/bench.sh tv.feathertv.home/.Home 5 --cold
```

Reports medians for warm start, the Home button, smoothness (slow frames), idle memory and cold
start. Reference results are in [docs/PERFORMANCE.md](docs/PERFORMANCE.md).
