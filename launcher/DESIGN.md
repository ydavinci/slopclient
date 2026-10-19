---
name: DaClient Launcher
description: A Minecraft launcher and in-game client that looks and moves like a modern client (watermark, ArrayList, ClickGUI, notifications), with legit modules only.
colors:
  accent: "#8b5cf6"
  accent-2: "#c084fc"
  accent-3: "#e879f9"
  bg: "#09080e"
  panel: "rgb(20 17 30 / 0.72)"
  panel-solid: "#15121f"
  raised: "rgb(38 32 54 / 0.72)"
  line: "rgb(255 255 255 / 0.07)"
  line-2: "rgb(255 255 255 / 0.14)"
  text: "#efecf7"
  muted: "#a39cb6"
  dim: "#726b86"
  on-accent: "#ffffff"
  on: "#4ade80"
  warn: "#fbbf24"
  err: "#fb7185"
typography:
  display:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "clamp(44px, 6.2vw, 76px)"
    fontWeight: 800
    lineHeight: 0.95
    letterSpacing: "-0.035em"
  headline:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "30px"
    fontWeight: 800
    letterSpacing: "-0.03em"
  title:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 700
    letterSpacing: "-0.025em"
  body:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "13.5px"
    fontWeight: 400
    lineHeight: 1.45
    fontFeature: "tnum"
  body-strong:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "13px"
    fontWeight: 600
  label:
    fontFamily: "Sora Variable, system-ui, sans-serif"
    fontSize: "11.5px"
    fontWeight: 600
  mono:
    fontFamily: "JetBrains Mono Variable, ui-monospace, monospace"
    fontSize: "12.5px"
    fontWeight: 400
rounded:
  sm: "6px"
  md: "8px"
  control: "10px"
  row: "12px"
  panel: "14px"
  float: "16px"
  surface: "18px"
  pill: "999px"
spacing:
  xs: "4px"
  sm: "6px"
  md: "10px"
  lg: "16px"
  xl: "20px"
  gutter: "32px"
components:
  button-primary:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.on-accent}"
    typography: "{typography.body-strong}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-secondary:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    typography: "{typography.body-strong}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-ghost:
    textColor: "{colors.muted}"
    typography: "{typography.body-strong}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-danger:
    textColor: "{colors.err}"
    rounded: "{rounded.control}"
    padding: "0 16px"
    height: "40px"
  button-play:
    backgroundColor: "{colors.panel-solid}"
    textColor: "{colors.on-accent}"
    rounded: "{rounded.float}"
    padding: "0 32px"
    height: "56px"
  input:
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    padding: "0 14px"
    height: "40px"
  chip:
    textColor: "{colors.muted}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
  chip-accent:
    textColor: "{colors.accent-2}"
    typography: "{typography.label}"
    rounded: "{rounded.sm}"
    padding: "2px 8px"
  glass-panel:
    backgroundColor: "{colors.panel}"
    rounded: "{rounded.surface}"
    padding: "20px"
  module-row:
    textColor: "{colors.text}"
    rounded: "{rounded.row}"
    padding: "8px 10px"
  nav-rail:
    backgroundColor: "{colors.panel}"
    rounded: "{rounded.float}"
    width: "60px"
---

# Design System: DaClient Launcher

## Overview

**Creative North Star: "The Client Overlay"**

The launcher is not a tool window; it is the client's own HUD, opened before the game. Every surface borrows the vocabulary of modern Minecraft clients (LiquidBounce, Rise, Vape): a watermark top left, an ArrayList of active mods top right, a floating category rail, ClickGUI-style module rows that toggle on click, and notifications sliding in bottom right. The content is strictly legit (fair-play mods, instances, resource packs); only the form is borrowed. The in-game mockup (`design/ingame.html`) is the same world in its native habitat: shader main menu, ClickGUI category panels, HUD, HUD editor.

Depth comes from a near-black ground under a slowly flowing violet shader, with dark translucent glass panels floating over it. The accent is never a flat colour: it is a travelling chroma gradient (violet, lavender, fuchsia) that moves like an ArrayList colour wave. Motion is constant but cheap: springs on selection pills, blur-to-sharp rises on entry, letter-by-letter reveals of the instance name. The shader pauses while a game runs. The UI rejects the grey utility look of Prism-style launchers and the interchangeable card dashboard.

Density is client-dense: 13.5px body, compact rows, tabular numerals, mono for every version and count. Copy is German and plain ("Spielen", "Instanzen", "Bereit", "Neue Instanz").

**Key Characteristics:**
- Near-black ground (`bg`) under a half-resolution WebGL fbm shader in violet and magenta.
- Glass panels: 72% dark fill, 20px backdrop blur at 140% saturation, 1px white hairline at 7%.
- One accent expressed as a moving gradient (`chroma`), 6s linear loop, 300% background width.
- Glow reserved for the Play button and active toggles/sliders.
- Sora for everything; JetBrains Mono for numbers, versions, counts, keybinds.
- Client-native components: watermark, ArrayList, nav rail, module rows, notifications with drain timers.

## Colors

A single violet-to-fuchsia accent family over cool near-black neutrals; status colours exist only as small signals.

### Primary
- **Electric Violet** (`accent`): the gradient's anchor and endpoints; selection fills at low alpha (16 to 18% on selected rows and enabled modules), focus halo on inputs (15%), text selection (55%).
- **Lavender Glow** (`accent-2`): the gradient's middle; focus outlines, caret, info icons, active checkmarks, running-state dots, update counts, the in-game crosshair and selected hotbar slot.
- **Fuchsia Spark** (`accent-3`): the gradient's peak; appears only inside the chroma gradient and the in-game HUD editor's picked outline and snap guides.

### Neutral
- **Void** (`bg`): the window ground beneath the shader; also the shader's base tone.
- **Smoked Glass** (`panel`): every floating glass panel (watermark, rail, content panels, list containers).
- **Ink Plum** (`panel-solid`): opaque surfaces that must read over anything: modals (95%), notifications (92%), account menu, the Play button's inner face, the running Play state.
- **Raised Plum** (`raised`): secondary button fill.
- **Hairline** (`line`) and **Hairline Strong** (`line-2`): panel borders and dividers; `line-2` for hover borders, modal and notification borders, watermark separators, keycaps.
- **Pale Lilac** (`text`), **Muted Heather** (`muted`), **Dusk** (`dim`): primary text, secondary text and inactive icons, placeholders and tertiary icons.

### Status
- **Signal Green** (`on`): "Bereit" chip and success notifications only.
- **Amber** (`warn`): warning chips.
- **Coral** (`err`): errors, stop actions, danger buttons, the close-window hover.

### Named Rules
**The Moving Accent Rule.** The accent is always the chroma gradient in motion (`linear-gradient(90deg, accent, accent-2, accent-3, accent-2, accent)` at 300% width, 6s linear). Flat accent colour appears only at low alpha as a selection tint or as `accent-2` in small signals.

**The Phase Shift Rule.** When chroma repeats down a stack (ArrayList rows), each row's animation is offset by `-0.35s` per index so the colour flows down the list as a wave.

**The Signal-Only Status Rule.** Green, amber and coral never decorate; they mark state (ready, warning, error/stop) at chip or icon scale.

## Typography

**Display Font:** Sora Variable (with system-ui)
**Body Font:** Sora Variable (with system-ui)
**Label/Mono Font:** JetBrains Mono Variable (with ui-monospace)

**Character:** A single geometric sans carries everything from the 76px instance name to 11.5px chips; its tight-tracked extrabold display gives the client-logo punch, while mono handles every number so values align like a HUD readout.

### Hierarchy
- **Display** (800, clamp(44px, 6.2vw, 76px), 0.95, -0.035em): the selected instance name on Start, revealed letter by letter; the welcome headline (56px). In-game: the title-screen logo (clamp(52px, 7vw, 96px), -0.045em) in chroma.
- **Headline** (800, 30px, -0.03em): page titles ("Instanzen").
- **Title** (700, 16px, tight): modal titles; watermark brand (800, 15px, chroma text).
- **Body** (400, 13.5px, 1.45, tabular numerals): base text. Secondary lines at 13px and 12.5px in `muted`; long help text uses relaxed leading and `max-w-sm`.
- **Body strong** (600, 13px): buttons, list row names (14px on instance rows), module names.
- **Label** (600, 11.5px to 12px): chips, field labels, table column headers (11px).
- **Mono** (400 to 600, 11px to 12.5px): versions, counts ("3/12"), play time, keybinds, ArrayList suffixes, log output (12px, 1.7).

### Named Rules
**The Numbers Are Mono Rule.** Every version string, count, duration, keybind and build number is set in JetBrains Mono; prose and names never are.

**The No Uppercase Rule.** Labels and headers are sentence case at 600 weight; hierarchy comes from weight and colour, not letter-spacing or capitals.

## Layout

A fixed desktop window, never scrolled as a whole (`overflow: hidden` on body). The shell is a 48px draggable title bar (watermark left, window controls right), a 60px floating nav rail inset 16px from the left, and a main area where pages cross-fade with a blur rise (0.34s). Notifications stack bottom right in a 340px column; modals centre over a 55% black, blurred scrim.

The Start page follows the client HUD: ArrayList pinned top right (max 58% height, up to 14 rows), the instance name, chips and Play button anchored bottom left within 62% width, and a horizontally scrolling instance strip at the bottom whose right edge fades out (`mask-image` to transparent from 88%). Other pages use 32px horizontal gutters with a header row (title left, search and actions right) above one dominant glass container.

Spacing rhythm is small and dense: 4 to 6px inside clusters (chips, row gaps), 10px between buttons and strip items, 16px between columns and panels, 20px panel padding, 32px page gutters (40px on Start).

## Elevation & Depth

Depth is layered translucency first, shadow second. The shader is the floor; glass panels float on it via backdrop blur and a hairline, not shadow. Opaque floating layers (modals, notifications, account menu) add a long, soft, black drop shadow with negative spread so they lift without a visible edge. Colour glow exists, but only as a state signal on the primary action and active controls.

### Shadow Vocabulary
- **Float Deep** (`box-shadow: 0 30px 80px -20px rgb(0 0 0 / 0.8)`): modals.
- **Float** (`box-shadow: 0 24px 60px -16px rgb(0 0 0 / 0.8)`): account popover; in-game ClickGUI category panels use `0 24px 50px -20px rgb(0 0 0 / .9)`.
- **Toast** (`box-shadow: 0 18px 50px -18px rgb(0 0 0 / 0.9)`): notifications.
- **Play Glow** (`box-shadow: 0 14px 40px -10px rgb(168 85 247 / 0.75)`): under the Play button only.
- **Toggle Glow** (`box-shadow: 0 4px 14px -4px rgb(168 85 247 / 0.8)`): an active toggle; slider thumbs use `0 2px 10px rgb(168 85 247 / 0.7)`; in-game pressed keystrokes `0 6px 18px -6px rgb(168 85 247 / .9)`.
- **Inset Hairline** (`box-shadow: inset 0 0 0 1px rgb(255 255 255 / 0.07)`): sliding selection pills (nav, segmented, filters); selected rows use `inset 0 0 0 1px rgb(192 132 252 / 0.18)`.

### Named Rules
**The Glow Is Earned Rule.** Violet glow appears only on the Play button and on controls that are currently on (toggles, slider thumbs, pressed keys). Nothing glows at rest for decoration.

**The Glass Over Shader Rule.** Panels never use an opaque fill unless they must read over other panels (modals, notifications, menus); everything else lets the shader show through the blur.

## Shapes

Soft, consistent rounding with no sharp corners and no pills except toggles, progress tracks and dots. Radius scales with element size: 6px chips and keycaps, 8px small buttons and window controls, 10px controls and inputs, 12px rows and rail buttons, 14px in-game category panels and the in-game frame, 16px the nav rail, Play button and instance strip cards, 18px launcher content panels and modals. Instance icons use a proportional radius (28% of size) over a hashed two-stop violet-family gradient with a soft top-left highlight. Borders are always 1px hairlines; the chroma ring is a 1.5px rotating conic border. Accent bars are 2 to 3px: the ArrayList's right edge bar, the nav rail's active indicator, the top edge of modals and in-game category headers.

## Components

### Buttons
Tactile and quick: every button scales to 0.97 on press over 200ms.
- **Shape:** gently rounded (10px at 40px height; 8px at 32px small size).
- **Primary:** chroma gradient fill, white 600 text; on hover a soft white sheen sweeps across (0.9s, expo ease) and brightness rises to 110%. Used for creation and commit actions ("Neue Instanz", "Hinzufügen", "Alle aktualisieren").
- **Secondary:** `raised` fill with `line` border; hover strengthens to `line-2` and an 8% white fill.
- **Ghost:** muted text, 6% white fill on hover.
- **Danger:** coral text on 10% coral fill with 30% coral border.
- **Icon button:** 36px square, 10px radius, muted icon, 7% white hover.

### Play Button (signature)
The one hero action. 56px tall, 16px radius, 15px bold text. Idle: a rotating chroma ring (conic gradient, 4s loop) around a chroma face inset 3px, the Play Glow beneath, and a sheen sweeping every 2.8s. Hover scales to 1.02 on the shared spring. Preparing: a white 20% fill tracks download progress behind the label while the label shows the current stage. Running: drops all chroma to an `panel-solid` face with a `line-2` border and becomes "Beenden", turning coral on hover. Labels swap with a blur-and-slide transition.

### Chips
- **Style:** 6px radius, 11.5px 600, 2px by 8px padding; tones are default (7% white, muted text), accent (18% violet, lavender text), on (12% green), warn (12% amber).
- **Use:** status ("Bereit" with a dot, "Läuft" with a pulsing dot), version, loader, modpack. On Start they sit directly below the instance name.

### Cards / Containers
- **Corner Style:** 18px for content panels and list containers.
- **Background:** glass (`panel` + blur); log output darkens to 50% black.
- **Shadow Strategy:** none at rest (see Elevation).
- **Border:** 1px `line`, `line-2` on hover where interactive.
- **Internal Padding:** 20px for settings sections, 14px for browse result cards; list containers pad 6 to 8px and let rows carry their own padding.

### Inputs / Fields
- **Style:** 40px tall, 10px radius, 30% black fill, `line` border, 13px text, `dim` placeholder; search fields carry a leading 16px icon.
- **Focus:** border shifts to lavender at 60%, fill deepens to 40% black, and a 4px violet halo at 15% appears. Global `:focus-visible` is a 2px lavender outline offset 2px.
- **Labels:** 12px 600 muted above, 12px muted hint below.

### Segmented Control
A 30% black track with 11px radius; the active option is a 10% white pill with an inset hairline that slides between options on the shared spring.

### Toggle
40 by 22px pill. Off: 10% white. On: chroma fill with Toggle Glow; the white 16px knob springs across. Toggling stops propagation so it can live inside clickable rows.

### Navigation (nav rail)
A 60px floating glass column, 16px radius. Rail buttons are 44px squares with 18px icons; the active one carries a sliding 9% white pill (shared spring) plus a 3px chroma bar on the rail's left edge. Labels appear as a glass tooltip to the right on hover. Settings and the account avatar sit at the bottom; the account menu opens to the right as an `panel-solid` popover.

### Watermark (signature)
A glass capsule in the draggable title bar: logo, "DaClient" in chroma text at 800, then hairline-separated meta (version, account, instance count, clock) in muted 12px, numbers in mono. Slides in from the left on launch.

### ArrayList (signature)
Right-aligned rows of active mods sorted by rendered width (longest on top), each on a 45% black, lightly blurred strip with the name in chroma text, an optional mono version suffix, and a 2px chroma bar on the right. Only the top-left and bottom-left corners round (6px). Rows slide in from the right with a 45ms stagger and phase-shifted chroma.

### Module Rows (ClickGUI lists)
Lists behave like ClickGUI modules. A whole row toggles its module on click (`aria-pressed`); enabled rows carry an 18% violet fill and a 1px inset lavender hairline, disabled rows a 2% white fill. Instances use the same grammar as a dense table inside one glass container: the selected instance gets the violet tint and a chroma-text name; the per-row play button turns chroma only on row hover. In-game, category panels (196px, 14px radius) have a header with a 2px chroma underline and expandable module settings (sliders, switches, mode pills, keybinds).

### Notifications
Cards 340px wide, 16px radius, `panel-solid` at 92% with blur and the Toast shadow, sliding in from the right on the spring. A leading status icon (chroma dot for tasks, lavender info, green success, coral error), title plus muted text, and a 3px track below: chroma progress for tasks, a draining timer for toasts (4s, 8s for errors). Clicking a toast dismisses it.

### Modal
Max 520px by default, 18px radius, `panel-solid` at 95%, `line-2` border, Float Deep shadow, a 2px chroma line along the top edge, 16px bold title with a close icon button. Enters with scale 0.94, 14px rise and an 8px blur clearing over 0.4s; Escape and scrim click close it.

### Motion
Shared constants: expo-out easing `cubic-bezier(0.16, 1, 0.3, 1)` and one spring (stiffness 520, damping 34, mass 0.7) for every sliding pill, toggle knob, hover lift and notification. Entry pattern is "rise": opacity 0, 10px down, 6px blur to sharp over 0.42s, staggered 28ms. Reduced motion collapses all animation and transition to 1ms, and the shader renders a still frame.

## Do's and Don'ts

### Do:
- **Do** express the accent as the moving chroma gradient; use flat violet only as a 15 to 18% tint for selection and focus.
- **Do** float every panel as glass (72% `panel`, 20px blur at 140% saturation, 1px `line` hairline) over the shader.
- **Do** set every number, version, count and keybind in JetBrains Mono with tabular numerals.
- **Do** build lists as ClickGUI modules: the whole row toggles or selects, the on state is a violet tint with an inset lavender hairline.
- **Do** use the shared spring for anything that slides between positions and the rise pattern for entries.
- **Do** phase-shift repeated chroma by -0.35s per item so it flows as a wave.
- **Do** keep module names and content fair-play: HUD, render, comfort and performance modules only.

### Don't:
- **Don't** add violet glow to anything other than the Play button and controls that are currently on.
- **Don't** use opaque panels except for modals, notifications and popovers that must read over other surfaces.
- **Don't** fall back to a grey utility launcher look or a grid of interchangeable dashboard cards; dense lists inside one glass container replace card grids.
- **Don't** introduce a second accent hue; status colours stay at chip and icon scale.
- **Don't** add uppercase, letter-spaced eyebrow labels above headings; hierarchy is weight, size and colour.
- **Don't** load player avatars from third-party services; faces are cropped from the profile's own skin texture with pixelated rendering.
- **Don't** let the shader run while a game is running.
