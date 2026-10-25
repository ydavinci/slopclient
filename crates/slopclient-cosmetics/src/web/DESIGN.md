---
name: SlopClient Kosmetik (web)
description: The cosmetics server's browser page; your player on a lit turntable in the launcher's shader dusk, every control in one Liquid Glass dock.
colors:
  ink: "#09080e"
  ink-raised: "#15121f"
  text: "#efecf7"
  muted: "#a39cb6"
  dim: "#8a83a0"
  hairline: "rgb(255 255 255 / 0.07)"
  hairline-strong: "rgb(255 255 255 / 0.14)"
  wash: "rgb(255 255 255 / 0.06)"
  well: "rgb(0 0 0 / 0.32)"
  violet: "#8b5cf6"
  lilac: "#c084fc"
  magenta: "#e879f9"
  status-on: "#4ade80"
  status-warn: "#fbbf24"
  status-err: "#fb7185"
typography:
  display:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "clamp(28px, 4.2vw, 40px)"
    fontWeight: 800
    lineHeight: 1.05
    letterSpacing: "-0.035em"
  wordmark:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "17px"
    fontWeight: 800
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 700
    letterSpacing: "-0.01em"
  body:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "14.5px"
    fontWeight: 400
    lineHeight: 1.55
  hint:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "13.5px"
    fontWeight: 600
    lineHeight: 1
  field-label:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "12px"
    fontWeight: 600
  meta:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "11.5px"
    fontWeight: 400
    lineHeight: 1.2
  code:
    fontFamily: "JetBrains Mono, ui-monospace, monospace"
    fontSize: "clamp(30px, 6vw, 44px)"
    fontWeight: 700
    lineHeight: 1
    letterSpacing: "0.16em"
    fontFeature: "tnum"
  numeric:
    fontFamily: "JetBrains Mono, ui-monospace, monospace"
    fontSize: "12px"
    fontWeight: 500
    fontFeature: "tnum"
rounded:
  pixel: "6px"
  xs: "9px"
  sm: "12px"
  md: "14px"
  lg: "16px"
  xl: "18px"
  dock-phone: "22px"
  dock: "26px"
  card: "28px"
  pill: "999px"
spacing:
  hair: "4px"
  xs: "8px"
  sm: "10px"
  md: "12px"
  lg: "14px"
  xl: "16px"
  2xl: "22px"
components:
  button:
    backgroundColor: "{colors.wash}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "0 15px"
    height: "38px"
  button-primary:
    backgroundColor: "{colors.violet}"
    textColor: "#ffffff"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "0 15px"
    height: "38px"
  button-primary-hero:
    backgroundColor: "{colors.violet}"
    textColor: "#ffffff"
    rounded: "{rounded.md}"
    padding: "0 22px"
    height: "46px"
  button-danger:
    backgroundColor: "{colors.wash}"
    textColor: "{colors.status-err}"
    rounded: "{rounded.sm}"
    height: "38px"
  input:
    backgroundColor: "{colors.well}"
    textColor: "{colors.text}"
    rounded: "{rounded.sm}"
    padding: "0 13px"
    height: "40px"
  segmented:
    backgroundColor: "{colors.well}"
    rounded: "{rounded.sm}"
    padding: "3px"
  segmented-option:
    textColor: "{colors.muted}"
    rounded: "{rounded.xs}"
    padding: "0 12px"
    height: "30px"
  segmented-option-on:
    backgroundColor: "rgb(255 255 255 / 0.13)"
    textColor: "#ffffff"
    rounded: "{rounded.xs}"
  tab:
    textColor: "{colors.muted}"
    typography: "{typography.label}"
    rounded: "{rounded.lg}"
    padding: "0 16px"
    height: "42px"
  tab-selected:
    textColor: "#ffffff"
    rounded: "{rounded.lg}"
  tile:
    backgroundColor: "rgb(255 255 255 / 0.04)"
    textColor: "{colors.text}"
    rounded: "{rounded.xl}"
    padding: "12px 10px 10px"
    width: "132px"
  tile-selected:
    backgroundColor: "rgb(255 255 255 / 0.09)"
    rounded: "{rounded.xl}"
  badge:
    textColor: "{colors.lilac}"
    rounded: "{rounded.pill}"
    padding: "0 9px"
    height: "22px"
  badge-worn:
    backgroundColor: "{colors.violet}"
    textColor: "#ffffff"
    rounded: "{rounded.pill}"
    height: "22px"
  dock:
    rounded: "{rounded.dock}"
    width: "min(1040px, calc(100% - 24px))"
  login-card:
    rounded: "{rounded.card}"
    padding: "clamp(24px, 4vw, 40px)"
    width: "min(560px, 100%)"
  savebar:
    rounded: "{rounded.pill}"
    padding: "7px 7px 7px 16px"
  toast:
    backgroundColor: "rgb(20 17 30 / 0.86)"
    textColor: "{colors.text}"
    rounded: "{rounded.lg}"
    padding: "11px 16px 11px 38px"
---

# Design System: SlopClient Kosmetik (web)

Scope: the single page served by `crates/slopclient-cosmetics` (`index.html`, `shell.js`, `app.js`, `upload.js`). It reuses the SlopClient launcher's world (`launcher/src/styles.css`, `Shader.tsx`, `LiquidGlass.tsx`). The repository-root DESIGN.md documents the separate Astro website and does not govern this page.

## Overview

**Creative North Star: "The Turntable in the Dusk"**

The player is the page. A large 3D Minecraft figure stands on a lit disc at the centre of a full-bleed violet shader, and every control lives in one floating Liquid Glass dock below it. Nothing is stacked as a settings page; on wide screens, Studio and Konto morph the dock into a right-hand sheet and the player slides left instead of being covered.

The mood is a night-time game lobby: deep ink, slow-moving violet and magenta fog, glass that bends light at its rim, and one animated chroma gradient reserved for what you act on or what you are wearing. Density is moderate and compact inside the dock (12-16px panel padding, 10px tile gaps) so the figure keeps the room. Every visual comes from the launcher, so the browser and the desktop app read as one product.

**Key Characteristics:**
- Full-bleed animated fbm shader on ink (#09080e) with a 6% overlay grain; CSS radial-gradient fallback when WebGL is missing.
- Floating chrome is Liquid Glass: specular inner rims, pointer-following light, lens refraction at the rim in Chromium.
- One moving chroma gradient (violet, lilac, magenta) marks primary actions and worn items.
- Sora for everything human; JetBrains Mono only for codes and numbers.
- Corners run from 6px (pixel previews) to 28px (sign-in card); pills for status and the save bar.
- German UI copy, sentence case, no uppercase labels.

## Colors

A near-black violet-tinted ink lit by a three-stop violet chroma, with white-alpha washes and hairlines doing all the neutral work.

### Primary
- **Electric Violet** (violet): the base of the chroma gradient, the focus-halo tint (25% mix), selection halo (22% mix), checkbox accent, text selection (55% mix), and the colour tint inside Liquid Glass (7% mix). Also exported as `--color-accent` for hats and pets painted in accent colours by the 3D preview.

### Secondary
- **Lilac Glow** (lilac): the interactive light. Focus outline (2px, 2px offset), caret, input focus border (60% mix), selected-tile ring (1.5px), range-slider accent, numeric outputs, badge text, the drag-hint icon and the pulsing "waiting" dot.

### Tertiary
- **Orchid Magenta** (magenta): the hot end of the chroma gradient and the turntable rim; on its own only as the glowing Studio "pending upload" dot.

### Neutral
- **Dusk Ink** (ink): page background, `theme-color`, avatar inset ring.
- **Raised Ink** (ink-raised): opaque surfaces where translucency fails, i.e. native select option lists.
- **Moon White** (text): all primary text; violet-tinted, never pure white except on chroma and on the selected tab/segment.
- **Lavender Grey** (muted): hints, field labels, inactive tabs and segments, waiting text, save-bar state at rest.
- **Ash Violet** (dim): meta lines under tiles, placeholders, notes, legal lines, empty-tile icon. Lightened from the launcher's #726b86 for legibility on ink; the web value is the canonical one here.
- **Hairline** (hairline) and **Strong Hairline** (hairline-strong): 1px borders; the soft one separates dock tabs from panels and editor groups, the strong one outlines buttons, inputs and the device-code well.
- **Wash** (wash) and **Well** (well): the two interior fills. Raised controls take a white wash (0.04 tile, 0.06 button, 0.1 hover, 0.13 selected segment); recessed controls (inputs, segmented tracks, dropzone at 0.2, code well at 0.35) take a black well.
- **Status** (status-on, status-warn, status-err): the toast's glowing dot (green success, rose error), the device-code warning box (amber, 9% fill, 35% border), the danger button and the "Ausgeblendet" badge (rose). Always mixed into transparency for fills; full strength only for dots and text.

### Named Rules
**The Earned Chroma Rule.** The animated gradient `linear-gradient(90deg, violet, lilac, magenta, lilac, violet)` at `background-size: 300%`, sliding over 6s, appears only on: primary buttons, the "Getragen" badge, the rotating rim of a worn tile, the turntable disc rim, and the account avatar. Everything else is neutral. If chroma appears on anything not on this list, it is wrong.

**The Alpha-Not-Grey Rule.** Neutral surfaces are white or black alpha over the shader, never opaque greys. The dusk must show through every interior fill.

## Typography

**Display Font:** Sora (variable 100-800, self-hosted woff2 with Latin and Latin Extended subsets; fallback system-ui)
**Body Font:** Sora
**Label/Mono Font:** JetBrains Mono (variable, self-hosted; fallback ui-monospace)

**Character:** Sora's wide geometric shapes give the lobby its game-client confidence at heavy weights and stay calm at 400 for long hints. JetBrains Mono is the machine voice: it only speaks when the content is a code or a count.

### Hierarchy
- **Display** (800, clamp(28px, 4.2vw, 40px), 1.05, -0.035em, balanced wrap): the sign-in headline only.
- **Wordmark** (800, 17px, -0.02em): "SlopClient" in the top bar, followed by "Kosmetik" in 600 14px muted behind a hairline divider (hidden on phones).
- **Title** (700, 15px, -0.01em): visible panel headings ("Cape hochladen", "Konto"). Shelf panels keep their heading screen-reader-only; the hint line leads instead.
- **Body** (400, 14.5px, 1.55): base text.
- **Hint** (400, 13px, muted, max 72ch): explanatory lines at the top of panels; 14px on the sign-in card.
- **Label** (600, 13.5px, line-height 1): buttons, tabs, account chip, toast, caption under the player (with a dark text-shadow over the shader).
- **Field label** (600, 12px, muted, 6px below): form labels; the editor's group heads use the same size at 700.
- **Meta** (400, 11.5px, dim): tile secondary lines, legal text, small tile actions.
- **Code** (Mono 700, clamp(30px, 6vw, 44px), 0.16em tracking, tabular): the Microsoft device code in its well.
- **Numeric** (Mono 500, 12px, lilac, tabular): slider outputs; 11.5px dim for frame counts.

### Named Rules
**The Machine Voice Rule.** JetBrains Mono with tabular figures is used for device codes, slider values and counts, and nowhere else. Sizes, names and prose stay in Sora.

**The Sentence Case Rule.** No uppercase, no tracked-out small labels. Hierarchy comes from weight (400/600/700/800) and the text/muted/dim ladder.

## Layout

A fixed, non-scrolling viewport (`body { overflow: hidden }`) with three layers: the world (shader, z 0), the stage (player, z 5), and floating chrome (dock z 20, save bar z 25, top bar z 30, toast z 60).

- **Top bar:** 68px tall (60px on phones), 22px side padding (14px on phones); brand left, account chip and "Abmelden" right.
- **Stage:** fills the space between the top bar and the dock; shell.js measures the dock and sets `--dock-space` (dock height + 48px), so the player always stands above it. The figure is max 560px wide, feet on the turntable disc, caption below.
- **Pose switch** floats right of the figure (`max(18px, 50% - 360px)`), vertical on desktop, horizontal top-right on phones. The drag hint mirrors it on the left and disappears on phones.
- **Dock:** bottom centre, `min(1040px, 100% - 24px)` wide, 34px above the bottom edge. Tabs row (42px tabs, 7px padding, account tab pushed right by a spacer), hairline, then a scrolling panel area. Shelves are one horizontal scroll row of 132px tiles (116px on phones) with proximity snap.
- **Sheet mode (>= 1100px, Studio and Konto):** the dock moves into a right sheet `clamp(460px, 40vw, 620px)` wide, full height under the top bar; the stage shrinks to the left, inactive tab labels collapse to icons, shelves wrap. All of it transitions over 0.6s on the ease-out curve.
- **Phones (<= 760px):** dock `100% - 16px`, 22px radius; only the active tab shows its label; panels cap at 36vh so the figure stays large; Studio grid and editor go to one column; the fixed legal footer hides while signed in (the line remains in Konto).
- **Sign-in:** a single centred card, min(560px, 100%).
- **Rhythm:** 4/8/10/12/14/16/22px; gaps of 10-14px between controls, 12-16px panel padding.

### Named Rules
**The Player Is the Page Rule.** The 3D figure owns the centre and is never covered: controls float in the dock, and when a panel needs height it becomes a side sheet that pushes the player aside. No stacked full-width sections.

## Elevation & Depth

Depth is light, not lift. Floating chrome is glass that refracts the shader behind it; interior elements are flat alpha fills. Glass pieces combine thin white inset highlights (top rim brightest) with long, soft, negatively spread dark drop shadows. No hard offset shadows anywhere.

### Shadow Vocabulary
- **Liquid Glass** (`inset 0 1px 0.5px rgb(255 255 255 / 0.42), inset 0 -1px 0.5px rgb(255 255 255 / 0.12), inset 1px 0 0.5px rgb(255 255 255 / 0.14), inset 0 0 22px rgb(255 255 255 / 0.05), 0 28px 60px -24px rgb(0 0 0 / 0.75), 0 2px 8px -2px rgb(0 0 0 / 0.4)`): dock, top-bar chip and button, pose switch, save bar, sign-in card. Plus a 1px gradient rim (masked pseudo-element) and a 220px pointer light.
- **Glass drop** (`inset 0 1px 0.5px rgb(255 255 255 / 0.65), inset 0 -1px 1px rgb(255 255 255 / 0.18), inset 0 0 10px rgb(255 255 255 / 0.08), 0 6px 14px -6px rgb(0 0 0 / 0.55), 0 0 22px -6px lilac 55%`): the bright lens that springs under the active tab.
- **Chroma lift** (`inset 0 1px 0 rgb(255 255 255 / 0.35), 0 10px 24px -10px violet 80%`): primary buttons.
- **Selection halo** (`0 0 0 1.5px lilac, 0 0 0 5px violet 22%, 0 14px 28px -14px violet 80%`): the tile currently shown on the player.
- **Toast** (`0 18px 40px -16px rgb(0 0 0 / 0.8)`) and **pixel preview** (`0 6px 14px -6px rgb(0 0 0 / 0.7)`).
- **Status glow** (`0 0 10px <status colour>`): the toast dot and the Studio dot.

### Named Rules
**The Glass Floats, the Inside Stays Flat Rule.** Liquid Glass is only for chrome that floats over the shader. Nothing inside the dock is glass again; tiles, inputs and buttons inside use washes and wells.

**The Graceful Lens Rule.** The SVG displacement lens (chromatic rim refraction, `blur(3px) saturate(180%) brightness(1.06)`) is a Chromium enhancement. Every glass element must look finished with only `blur(22px) saturate(175%)`.

## Shapes

Soft, rounded rectangles throughout, sized to the element: 6px on pixel-art canvases, 9px on small tile actions and segment options, 12px on every standard control, 14px on the hero button and warning box, 16px on tabs, toast and pose switch, 18px on tiles, dropzone and code well, 26px on the dock (22px on phones), 28px on the sign-in card. Pills (999px) for the account chip, badges, save bar and its buttons, avatar and scrollbars. The one ellipse is the turntable disc (3.4:1) with a 1.5px conic rim turning over 9s. Borders are always 1px hairlines; the dropzone alone uses a 1.5px dashed line. Nested radii step down by the padding (dock 26 to tab 16 to tile 18 inside a 16px panel).

## Components

### Buttons
Tactile and quiet; the chroma one is the only loud object.
- **Shape:** gently rounded (12px), 38px tall, 15px side padding, 8px icon gap.
- **Default:** white wash with a strong hairline; hover deepens the wash to 0.1 and the border to 0.22; press scales to 0.97 on the ease-out curve.
- **Primary:** borderless, white text on the sliding chroma gradient with the chroma lift; hover brightens 8%. Hero size on sign-in (46px, 22px padding, 14px radius, 15px text), 42px in Studio.
- **Danger:** rose text; hover fills rose at 14% with a 35% rose border.
- **Disabled:** 45% opacity, no transform.
- **Small:** tile actions at 26px / 11.5px / 9px radius; icon-only 26px squares with 14px stroked icons.

### Chips
- **Badge:** 22px pill, 700 11px. Default: violet at 22% with lilac text. "Getragen": sliding chroma with white text. Warn ("Ausgeblendet"): rose at 16% with rose text.
- **Account chip:** 38px Liquid Glass pill with a 26px chroma avatar and the player name; avatar only on phones.

### Cards / Containers
- **Tile:** 132px wide, 18px radius, 0.04 wash, soft hairline, centred stack (preview, 600 13px name, dim meta, actions). Hover rises 3px over 0.35s. Selected (shown on the player): selection halo. Worn: a 1.5px conic chroma rim turning over 6s. Both states can coexist and stay distinguishable.
- **Previews:** cape canvases 46x74 with pixelated rendering, 6px radius, black well; hat/pet renders 76px.
- **Sign-in card:** Liquid Glass, 28px radius, the only card on the page.
- **Warning box:** amber 9% fill, 35% border, 14px radius, amber strong lead.

### Inputs / Fields
- **Style:** 40px, black well (0.32), strong hairline, 12px radius, 500 14px text, lilac caret, dim placeholder.
- **Focus:** border to lilac 60% and a 3px violet halo at 25%; no outline.
- **Segmented control:** black-well track with 3px padding; options 30px, muted; the active option is a 0.13 white wash with a small inset highlight.
- **Checkbox / range / colour:** native, accented in violet / lilac; colour picker 42x34 at 10px radius.
- **Dropzone:** dashed 1.5px strong hairline over a 0.2 well; hover or focus tints violet 8% with a lilac border; leads with a 44px violet-tinted icon square.

### Navigation
- **Dock tabs:** 42px, 16px radius, 13.5px 600 muted label with an 18px stroked icon; hover adds a 0.06 wash; selected text turns white and the glass drop springs under it (0.55s spring curve). Arrow keys move between tabs. Tabs for empty catalogues hide themselves. Sheet mode and phones collapse inactive labels to icons.
- **Pose switch:** a Liquid Glass segmented control floating beside the figure.

### The Turntable (signature)
The figure's feet rest on an elliptical disc: a lilac-to-violet radial glow, a turning conic chroma rim, a blurred dark contact shadow, and a soft lilac floor light with a faint conic spotlight from above. Dragging the canvas rotates the player. This disc is the page's identity mark; keep it under any future figure.

### Save Bar
A Liquid Glass pill that rises (0.5s, from 14px down at 96% scale) just above the dock whenever hats, pets or accessories differ from what is saved; muted state text turns full white when dirty; pill buttons at 34px.

### Toast
Top centre, under the top bar; 86% opaque ink with an 18px blur, strong hairline, 16px radius, 600 13.5px text, and a glowing status dot at the left (green, or rose with a rose-tinted border and pale-rose text for errors). Enters with a 10px drop and scale from 0.97.

### Icons
Inline SVG only: 24px viewBox, no fill, `currentColor` stroke at 1.8, round caps and joins; rendered at 14px (tile actions), 18px (tabs, drag hint), 22px (dropzone) and 30px (empty tile).

## Do's and Don'ts

### Do:
- **Do** keep the shader dusk full-bleed behind every state, with the radial-gradient fallback on ink (#09080e).
- **Do** put new controls in the dock; if a feature needs height, make it a sheet tab that pushes the player aside (>= 1100px).
- **Do** use the Liquid Glass recipe for anything that floats over the shader, and washes/wells for anything inside it.
- **Do** reserve the sliding chroma gradient for primary actions and worn items (plus the existing turntable rim and avatar).
- **Do** mark "shown on the player" with the lilac ring and halo, and "worn" with the turning chroma rim.
- **Do** use JetBrains Mono with tabular figures for codes and numbers only.
- **Do** move with the ease-out curve `cubic-bezier(0.22, 1, 0.36, 1)` for layout (0.6s) and the spring `cubic-bezier(0.34, 1.36, 0.64, 1)` only for the tab drop; honour `prefers-reduced-motion` (shader freezes on one frame, animations collapse).
- **Do** draw icons as 1.8px-stroke inline SVG in `currentColor`.

### Don't:
- **Don't** build stacked full-width settings sections or a scrolling page; the viewport is fixed and the player is the centre.
- **Don't** cover the player with a modal or an overlay panel.
- **Don't** nest glass inside glass or use opaque grey surfaces.
- **Don't** use the chroma gradient for decoration, headings or secondary buttons.
- **Don't** use uppercase or letter-spaced micro-labels, or hard offset shadows.
- **Don't** depend on the SVG lens for legibility; it exists only in Chromium.
- **Don't** set text below the dim value (#8a83a0) on ink.
