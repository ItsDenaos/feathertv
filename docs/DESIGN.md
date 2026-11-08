# FeatherTV design: "Stage"

Approved on 2026-10-18 (option A, with the brand next to Settings and no "Open" button).

Measurements assume 1920×1080 at 320 dpi (960×540 dp). Everything is expressed as a percentage of
the screen's width or height so it looks the same at 720p and 4K.

## Layers (back to front)

1. **Pure black background** (`#000`).
2. **Glow**: an elliptical radial gradient in the focused app's dominant color.
   Centered at (70 %, 20 %), radii of 70 % of the width × 80 % of the height, the color at 60 %
   opacity fading to transparent. Changes color with a cross-fade of about 200 ms.
3. **Top bar** (at 6–7 % of the height):
   - Left: a greeting for the time of day plus the current time. About 18 sp, white at 70 %.
   - Right: a **FeatherTV** button (feather and name; opens FeatherTV's options) · a thin vertical
     divider · the TV's own: an **Inputs** button (only on TVs with inputs; opens a panel like the
     app menu with HDMI 1, 2, 3, AV…) and a **Settings** button. All are pills: without focus,
     text at 70 %; with focus, filled with the focus color and contrasting text.
4. **Title**: the focused app's name, at 28 % of the height, about 56 sp, medium weight.
5. **App row** at 64 % of the height, with a 5 % left margin:
   - 16:9 tiles, 15 % of the width (12 % / 18 % for the small / large sizes), 2.2 % apart, with
     rounded corners (about 8 dp).
   - With focus: scale 1.12 and a 2 dp ring **outside** the tile, separated by a black gap so it is
     also visible on white banners (Netflix, YouTube). The ring and focused pills use the "Focus
     color" from Options (white by default). The animation takes about 160 ms.
   - Without a TV banner: the icon centered on dark grey with the label below.
6. **App menu** (☰): a panel at the top right, above the row, with everything else dimmed.
7. **Options**: the same stage with the brand's violet glow and pill-shaped rows.

## Navigation

- ← → move along the row. ↑ from the row goes to Settings; ← moves to Inputs (when shown) and
  then FeatherTV, → back towards Settings; ↓ returns to the last focused tile.
- Inputs are listed HDMI first and then by name, with the TV's own labels (or the ones the user
  set in the TV's settings) and the HDMI-CEC device's name next to its port. Choosing one opens
  the system's TV app on that input (`TvContract.buildChannelUriForPassthroughInput`).
- The FeatherTV brand was decorative in the approved mockup; it became the Options button because
  reaching Options only through ☰ wasn't intuitive.
- The Back button on the home screen does nothing.

## Languages

Interface text lives in Android resources (`values-xx`). By default FeatherTV uses the system
language; a different one can be chosen in Options.
