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
| Home button, from the press to on screen | **673 ms** (587–857) | 974 ms (831–1206) |
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

## What was optimized

- **Glow**: it used to be a full-screen gradient recalculated on every frame (14 % slow frames).
  It is now painted once per color into a bitmap at a quarter of the resolution, a color change is a
  200 ms cross-fade between two bitmaps, and it is drawn only in the area that has color; the rest of
  the screen is a clear to black, which costs almost nothing on the GPU. Result: 14 % → 6 %.
- **A single full-screen layer**: the window and the root view no longer paint black backgrounds that
  the glow covers anyway (that was three full-screen passes per frame, more than a Fire TV Stick's
  memory bandwidth can handle).
- **`resumeWhilePausing`**: FeatherTV appears without waiting for Amazon's launcher to finish
  pausing, about 400 ms less on every Home press.
- **Tile art painted off the main thread**, at its exact size; the original banner of each app is
  no longer kept in memory.
- **APK**: R8 (shrinks and renames the code) and recompression of the resource table: 58.1 → 45.8 KB.

## What couldn't be improved

- The time until Amazon draws its screen (about 450–700 ms, and up to about 2 s right after a
  restart or when the system closed Amazon's launcher to free memory) is the floor for the Home
  button: it is the first thing the service can detect without permission to read the screen.
- `CLOSE_SYSTEM_DIALOGS` (`reason=homekey`) would arrive immediately, but Fire OS skips it for
  non-system apps.
- The glow costs some GPU time during animations; for maximum smoothness, choose "Pure black" in
  Options.
