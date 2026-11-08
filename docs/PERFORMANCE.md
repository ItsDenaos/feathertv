# Performance

Measured on 2026-10-18 on a Fire TV Stick Lite (AFTSS) running Fire OS 7.7.1.7. Every figure is the
median of five runs with [tools/bench.sh](../tools/bench.sh). "Fire OS" is Amazon's stock launcher
(`com.amazon.tv.launcher`) on the same device, measured with FeatherTV's Home service turned off.

| | Fire OS launcher | FeatherTV 1.0.0 |
|---|---|---|
| APK size | 28 MB | **46 KB** |
| Idle memory, excluding graphics | 74 MB | **24 MB** |
| Idle memory, total | 121 MB | **44 MB** |
| Back to the home screen from an app (`am start -W`) | 376 ms | **96 ms** |
| Slow frames while moving through the apps | 39 % (p90 32 ms, p99 93 ms) | **6 %** (p90 14 ms) |
| Same, with the "Pure black" background | — | **0 %** (p90 5 ms) |
| Home button, from the press until it is in front | **673 ms** (587–857) | 974 ms (831–1206) |
| Cold start | not measured | 241 ms |

The total memory includes the screen buffers that the system reserves, which vary between runs; the
figure excluding graphics is each app's own memory. Amazon's launcher can't be removed, so it stays
loaded in the background while FeatherTV runs as well. The Fire OS launcher was measured on a device
that already had part of Fire OS's advertising and telemetry disabled; out of the box it is likely
to be heavier. Its cold start wasn't measured because it is a system app that is always running.

Version 1.1.0 (Android TV support) doesn't change anything on Fire OS. Measured back to back on the
same device, the Home button took 1147 ms with 1.0.0 and 1051 ms with 1.1.0: the same within the
noise. Both are higher than in the table above because on Fire OS the Home button depends on how long
Amazon's launcher takes to draw, which varies with the state of the device (see below).

### After 1.1.0: the optimizations below, A/B

Measured on 2026-11-07 on the same Fire TV Stick with [tools/perf/ab.sh](../tools/perf/ab.sh): the
variants alternate over three rounds of three runs, so the device's state affects them all alike,
and each figure is the median of the nine samples. Home is pressed on a virtual remote (the same
path as the real one) and timed until FeatherTV's first frame is on screen and until it is in
front. "Early trigger" is Guard reacting to the first content change of Amazon's home screen
instead of waiting for its window (see below); it was kept.

| | 1.1.0 | Optimized | Optimized + early trigger | Fire OS launcher |
|---|---|---|---|---|
| Home button, press to on screen | 1690 ms | 1740 ms | **1214 ms** | 974 ms |
| Home button, press to in front | 1103 ms | 1083 ms | **754 ms** | 298 ms |
| Back to the home screen (`am start -W`) | 160 ms | 144 ms | **130 ms** | 630 ms |
| Slow frames while moving through the apps | 3.9 % | 2.6 % | **1.3 %** | 24.5 % |
| Idle memory, excluding graphics | 27.0 MB | **23.0 MB** | 23.4 MB | 83.1 MB |

The optimizations alone don't change the Home button (both within the noise) but make scrolling
smoother and use 4 MB less; the early trigger takes about half a second off the Home button in
every round (on screen: 1.0–1.8 s against 1.4–2.1 s).

## Android TV

The same APK, measured on 2026-10-18 on a Hyundai AI PONT CO television (Android TV 14, interface at
1280×720), median of five runs. "Stock" is Google's launcher (`com.google.android.tvlauncher`); for
FeatherTV it was disabled with `install.ps1 -ReplaceStock`, so it doesn't stay in memory.

| | Stock launcher | FeatherTV 1.1.0 |
|---|---|---|
| APK size | 13.3 MB | **46 KB** |
| Idle memory, excluding graphics | 67 MB | **32 MB** |
| Idle memory, total | 88 MB | **38 MB** |
| Back to the home screen from an app (`am start -W`) | 244 ms | **126 ms** |
| Slow frames while moving through the apps | 2.6 % (p90 27 ms, p99 300 ms) | **0.6 %** (p90 10 ms, p99 21 ms) |
| Home button, from the press to in front | 169 ms | **128 ms** |
| Cold start | 6588 ms | **602 ms** |

Here Home goes straight to FeatherTV, without Fire OS's detour through Amazon's launcher. Android 10
and later no longer log injected keys, so the Home button is timed from the device clock right
before the key command: both columns include a few tens of milliseconds of the command's own startup,
and the row can't be compared with the Fire OS table.

### After 1.1.0: the optimizations below, A/B

Measured on 2026-11-07 on the same television, the two versions alternating over three rounds of
three runs (median of nine samples), Home pressed with `input keyevent` as above. The test was run
twice; this is the second run, once the TV had settled after a restart.

| | 1.1.0 | Optimized |
|---|---|---|
| Home button, press to on screen | 237 ms | 240 ms |
| Home button, press to in front | 122 ms | 139 ms |
| Back to the home screen (`am start -W`) | 127 ms | 179 ms |
| Cold start | 650 ms | 760 ms |
| Slow frames while moving through the apps | 0.6 % | 1.2 % |
| Idle memory, excluding graphics | 38.7 MB | **32.4 MB** |

On this TV the optimizations only save memory (4–6 MB in both runs). The times are within the noise
between runs (the cold start went the other way in the first one: 670 ms against 599 ms), except
going back to the home screen with `am start -W`, which was about 50 ms slower in both runs; the
Home button, which is what people use, didn't change.

## Where the Home button's time goes on Fire OS

Traced on 2026-10-18 with 1.1.0, pressing Home from Netflix on a virtual remote (the same path as
the real remote; `input keyevent` takes another one), median of five presses:

| Moment | ms after the press |
|---|---|
| Amazon's launcher starts (Fire OS acts when the key is released) | 158 |
| Amazon's launcher is in front | 191 |
| Amazon's launcher reports its window, and Guard reacts | 930 |
| FeatherTV is in front | 1043 |
| FeatherTV's first frame is on screen | 1461 |

Most of the wait comes before FeatherTV can do anything: Guard only learns about Home when Amazon's
launcher has built its screen. But FeatherTV then needs 400–600 ms to draw its first frame, against
about 130 ms when it comes back without Amazon's launcher in the middle: both compete for the CPU and
the GPU. Amazon's launcher already sends window-content events about 450 ms before it reports its
window. Reacting to them brings FeatherTV to the front sooner, while Amazon's launcher is still
busy; a first test of four presses suggested it wouldn't be on screen any sooner, but the A/B above
(nine presses per variant) showed it is, by about 500 ms, so Guard now reacts to the first of them.

## What was optimized

- **Glow**: it used to be a full-screen gradient recalculated on every frame (14 % slow frames).
  Then it was painted once per color into a bitmap at a quarter of the resolution and cross-faded
  (6 %). Now its shape is a single alpha mask at a quarter of the resolution, computed once, that
  the GPU tints with the app's color: a color change is a 200 ms cross-fade that repaints and
  uploads nothing. It is drawn only in the area that has color; the rest of the screen is a clear
  to black, which costs almost nothing on the GPU.
- **A single full-screen layer**: the window and the root view no longer paint black backgrounds that
  the glow covers anyway (that was three full-screen passes per frame, more than a Fire TV Stick's
  memory bandwidth can handle).
- **`resumeWhilePausing`**: FeatherTV appears without waiting for Amazon's launcher to finish
  pausing, about 400 ms less on every Home press.
- **Early trigger** (Fire OS): Guard brings FeatherTV on the first content change of Amazon's home
  screen instead of its window report, and its curtain lifts at once (its window is opaque, so the
  old 120 ms fade was solid black anyway). About 500 ms less on every Home press.
- **Opaque window**: without a window background Android made it translucent, so SurfaceFlinger
  blended it with whatever was below (Amazon's launcher). The greeting is no longer set again when
  it hasn't changed (that laid out the whole screen), Guard's setting isn't queried while it is
  running, and apps open with the component found when loading them.
- **Tile art**: apps that didn't change keep their art across reloads (an install or update used
  to repaint them all), and on Android 9+ it lives in graphics memory (`Bitmap.Config.HARDWARE`),
  uploaded once. The loading thread runs at nice 5. Together: 4 MB less on Fire OS, 4–6 MB on
  Android TV, and smoother scrolling on Fire OS.
- **Tile art painted off the main thread**, at its exact size; the original banner of each app is
  no longer kept in memory.
- **APK**: R8 (shrinks and renames the code) and recompression of the resource table: 58.1 → 45.8 KB
  (48.8 KB in 1.2.0, with the inputs).

## What couldn't be improved

- The time until Amazon draws its screen (about 450–700 ms, and up to about 2 s right after a
  restart or when the system closed Amazon's launcher to free memory) is the floor for the Home
  button: it is the first thing the service can detect without permission to read the screen.
- `CLOSE_SYSTEM_DIALOGS` (`reason=homekey`) would arrive immediately, but Fire OS skips it for
  non-system apps.
- An accessibility key filter (`flagRequestFilterKeyEvents`) would let Guard catch Home before
  Amazon's launcher even starts, but on Fire OS 7.7 Amazon's key policy handles Home before the
  filter: Back and the D-pad reach it, Home doesn't. The `amazon.intent.action.HOME_PRESSED`
  broadcast it sends goes only to Amazon's apps and requires a system permission.
- The glow costs some GPU time during animations; for maximum smoothness, choose "Pure black" in
  Options.
