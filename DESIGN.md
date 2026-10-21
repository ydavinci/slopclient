---
name: SlopClient
description: The project website as SlopClient's own title screen; purple stone, pixel dusk, bevelled GUI.
colors:
  ink: "#0b0912"
  stone: "#17121f"
  stone-2: "#211a2e"
  stone-3: "#2d2440"
  inset: "#07050c"
  edge: "#000000"
  text: "#ece9f5"
  muted: "#aaa3c2"
  dim: "#7a7392"
  accent: "#8b5cf6"
  accent-2: "#c084fc"
  accent-deep: "#5b3bb0"
  splash: "#ffff55"
  on: "#55ff55"
  soon: "#ffd75e"
  tip-bg: "#100010"
  tip-a: "#5000ff"
  tip-b: "#28007f"
typography:
  display:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "clamp(60px, 13vw, 160px)"
    fontWeight: 800
    lineHeight: 0.9
    letterSpacing: "-0.035em"
  headline:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "clamp(22px, 2.6vw, 28px)"
    fontWeight: 700
    lineHeight: 1.15
    letterSpacing: "-0.02em"
  title:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "19px"
    fontWeight: 700
  body:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.6
  label:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "15px"
    fontWeight: 600
    lineHeight: 1
    letterSpacing: "0.01em"
  splash:
    fontFamily: "Sora, system-ui, sans-serif"
    fontSize: "clamp(15px, 2.1vw, 24px)"
    fontWeight: 700
    lineHeight: 1
  mono:
    fontFamily: "JetBrains Mono, ui-monospace, monospace"
    fontSize: "0.88em"
    fontWeight: 400
    fontFeature: "tnum"
rounded:
  none: "0px"
spacing:
  tight: "6px"
  sm: "8px"
  md: "14px"
  gutter: "16px"
  lg: "24px"
  section: "40px"
  done: "48px"
  screen-end: "88px"
components:
  button:
    backgroundColor: "{colors.stone-3}"
    textColor: "{colors.text}"
    typography: "{typography.label}"
    rounded: "{rounded.none}"
    padding: "0 18px"
    height: "44px"
  button-hover:
    backgroundColor: "{colors.accent}"
    textColor: "#ffffff"
  button-disabled:
    backgroundColor: "#1a1622"
    textColor: "{colors.dim}"
  button-compact:
    backgroundColor: "{colors.stone-3}"
    textColor: "{colors.text}"
    padding: "0 14px"
    height: "40px"
  button-square:
    backgroundColor: "{colors.stone-3}"
    textColor: "{colors.text}"
    width: "44px"
    height: "44px"
    padding: "0"
  well:
    backgroundColor: "{colors.inset}"
    textColor: "{colors.text}"
    rounded: "{rounded.none}"
  search-field:
    backgroundColor: "{colors.inset}"
    textColor: "{colors.text}"
    padding: "0 12px"
    height: "40px"
  tooltip:
    backgroundColor: "{colors.tip-bg}"
    textColor: "{colors.text}"
    padding: "12px 16px"
  count-badge:
    backgroundColor: "{colors.edge}"
    textColor: "{colors.on}"
    height: "20px"
    padding: "0 5px"
  screen-head:
    backgroundColor: "rgba(0, 0, 0, 0.35)"
    textColor: "{colors.text}"
    typography: "{typography.headline}"
    padding: "22px 16px"
---

# Design System: SlopClient

## Overview

**Creative North Star: "The Title Screen"**

The website is the game screen SlopClient's in-game client replaces: a pixel dusk panorama behind a giant extruded wordmark, a stack of bevelled GUI buttons, a splash line in yellow, and a status bar along the bottom. Everything below the fold works like a sub-screen of that menu: a dark tile header band with a small centred title, a working panel, and a "Done" button back to the title. The game's GUI supplies the material: purple-tinted stone tile ground, buttons with black edges and light/dark inner bevels, inset wells for lists and reading, and the in-game tooltip for transient messages.

Density is game-menu density: controls are full-height (44px) and stacked tight (6 to 8px), and reading text sits in narrow centred columns inside wells. Every corner is square; depth comes from inner bevels and 2px black edges, not from radius or blur. Colour is a purple stone field with one violet accent for interaction. The game's own chat colours (yellow, green) are kept for meaning: the splash, ON states and counts.

The launcher mockup (`design/mockup.html`) shares only the brand commitments (purple accent, Sora). It is a different world and is not governed by this file.

**Key Characteristics:**
- Square everything (0px radius); 2px pure-black edges on every control and panel.
- Bevel depth: light inner edge top-left, dark inner edge bottom-right; it inverts when pressed.
- Purple-tinted 8x8 stone tile as the page ground, scaled up so the pixels stay hard (crisp-edged).
- One interaction colour: violet accent face plus a white 2px outline on hover, focus and selected.
- Sora for everything; JetBrains Mono only for codes, paths, counts and step numerals.
- Pixel-grid art only: icons and illustrations are SVG or canvas on 8x8 or 16x16 grids with `crispEdges`.

## Colors

A purple stone field with one violet interaction accent and Minecraft chat colours used for meaning.

### Primary
- **Amethyst Violet** (accent): the face colour of every hovered, focused, pressed-on or selected button, the active step marker, the install progress fill and text selection. It is a state colour, not a decoration.
- **Light Amethyst** (accent-2): links, list-marker squares and the top highlight on the progress fill.
- **Deep Amethyst** (accent-deep): the deepest extrusion layer of the wordmark.

### Secondary
- **Splash Yellow** (splash): the splash line only, plus the search caret. This is the game's own chat yellow.
- **Chat Green** (on): ON toggles, installed-button text and the count badge.
- **Warning Gold** (soon): "Needs …" dependency lines and placeholders for content that does not exist yet.

### Tertiary
- **Tooltip Void / Tooltip Violet / Tooltip Indigo** (tip-bg, tip-a, tip-b): the in-game tooltip frame, used only by the toast.

### Neutral
- **Night Ink** (ink): the base under the tile and the browser theme colour.
- **Stone, Stone 2, Stone 3** (stone, stone-2, stone-3): tile, panel body (the store) and button face / panel bar, from darkest to lightest.
- **Inset Black** (inset): wells, scrollbar track; used at 88% opacity over the tile.
- **Edge Black** (edge): every 2px border.
- **Bone White** (text): primary text.
- **Lavender Grey** (muted): ledes, descriptions, secondary lines.
- **Dusk Grey** (dim): disabled labels, placeholders, step labels and the inactive search icon. It measures about 4.5:1 inside wells and lower on the tile and stone, so it is not for body copy.

### Named Rules
**The State-Only Violet Rule.** The accent marks interaction state (hover, focus, selected, progress). Static surfaces never take violet as a fill.

**The Chat Colour Rule.** Yellow, green and gold carry meaning the game already taught: splash, on/installed, needs/pending. Don't use them as decoration.

## Typography

**Display Font:** Sora (with system-ui, sans-serif), 400/600/700/800 self-hosted via Fontsource
**Body Font:** Sora
**Label/Mono Font:** JetBrains Mono (400/500), for code, paths, counts and numerals only

**Character:** A single geometric sans voice. The game feel comes from what is done to the type: hard 0-blur drop shadows, stroke and extrusion. Pixel fonts are not used.

### Hierarchy
- **Display** (800, clamp(60px, 13vw, 160px), 0.9): the wordmark only. Near-white face, a 0.06em dark stroke, and a five-step stacked violet extrusion ending in a soft ground shadow, like the game logo.
- **Headline** (700, clamp(22px, 2.6vw, 28px), 1.15, -0.02em): the centred sub-screen title in the header band, with a hard shadow 3px down.
- **Title** (700, 19px): section headings inside reading wells.
- **Body** (400, 16px, 1.6): running text; reading columns capped at 60 to 68ch. List and table text is 14 to 14.5px.
- **Label** (600, 15px, 1, 0.01em): button labels, with a hard 2px text shadow; 13 to 14px in compact controls.
- **Splash** (700, clamp(15px, 2.1vw, 24px)): the rotated yellow splash line.

### Named Rules
**The Hard Shadow Rule.** Text on panorama or bevelled faces gets a 0-blur shadow straight down (1 to 3px, black at 55 to 70%). It is the game's text shadow, never a glow.

**The Mono Is Data Rule.** JetBrains Mono appears only on literal values: hostnames, paths, scopes, counts, numerals.

## Layout

The first view is a full-height (100svh) title screen: panorama behind everything, a centred column (wordmark, tagline capped at 34ch, a menu 420px wide), and a status bar pinned to the bottom with version on the left and the Mojang disclaimer on the right. The menu is a single column of full-width buttons with 8px gaps; a 44px square icon button can sit beside a full-width one in a `44px 1fr` row.

Sub-screens follow one shape: a full-bleed header band (22px vertical padding, black 2px bottom edge, 1px-style light line beneath), then content in a 1040px centred container with 16px gutters, then a centred "Done" row 48px below, then 88px of screen-end space. Screens are separated by a 2px black edge with a faint light line, never by colour changes. Working panels (the store) narrow to 880px.

A compact fixed bar (60px) slides down once the title screen leaves view, carrying the brand and two compact buttons. Breakpoints: at 900px side-by-side layouts (nav rail + well) collapse to one column and the rail becomes a 3-column button grid; at 560px it becomes 2 columns, tab groups stretch to full width with equal-flex buttons, and list-row actions drop to a full-width row.

## Elevation & Depth

Depth is bevel, not blur. Raised surfaces (buttons, the store panel, step markers, scrollbar thumb) have a light inner edge top-left and a dark inner edge bottom-right inside a 2px black border. Recessed surfaces (wells) invert that: dark inner top-left, faint light inner bottom-right. Pressing a button flips its bevel to the recessed form. Section and band separators are a 2px black line paired with a 2px white line at 5% opacity.

### Shadow Vocabulary
- **Raised bevel** (`box-shadow: inset 2px 2px 0 rgba(255,255,255,0.2), inset -2px -3px 0 rgba(0,0,0,0.6)`): buttons and raised panels at rest.
- **Pressed bevel** (`box-shadow: inset -2px -2px 0 rgba(255,255,255,0.2), inset 2px 3px 0 rgba(0,0,0,0.6)`): button `:active`.
- **Inset well** (`box-shadow: inset 2px 2px 0 rgba(0,0,0,0.6), inset -2px -2px 0 rgba(255,255,255,0.07)`): wells, search field.
- **Edge line** (`box-shadow: 0 2px 0 rgba(255,255,255,0.05)`): under header bands, above screens and the footer.
- **Float lift** (`0 24px 60px rgba(0,0,0,0.55)` on the store; `0 6px 18px rgba(0,0,0,0.4)` on the fixed bar; `0 10px 24px rgba(0,0,0,0.5)` on the toast): one soft ambient shadow, only under surfaces that float above the page. It goes after the bevel and never replaces it.

### Named Rules
**The Bevel Not Blur Rule.** If a surface needs to look raised or sunk, it gets the bevel pair inside a black edge. Soft shadow is reserved for things that float over the page.

## Shapes

Square-cornered throughout (0px radius): controls, wells, panels, badges, icons, the toast. Borders are always 2px, always pure black on controls and panels. Pixel art (icons, identicons, panorama, list markers drawn as small squares) sits on whole-pixel grids rendered `crispEdges` or `image-rendering: pixelated`, and gets a 2px black ring (`box-shadow: 0 0 0 2px #000`) when it sits in a list. The splash is the one rotated element (-18deg).

## Components

### Buttons
The one control language: every link-as-action, tab, toggle and nav item is the same bevelled GUI button.
- **Shape:** square (0px), 2px black edge, raised bevel; the face is stone-3 overlaid with the stone tile at 16px in soft-light blend.
- **Primary:** min height 44px, 0 18px padding, label type with hard text shadow, 10px icon gap, 18px icons.
- **Hover / Focus / Selected / Pressed-on:** the face turns accent violet, the label goes white, and a white 2px outline is drawn inset (-2px offset). `aria-selected` and `aria-pressed` reuse this exact state, so tabs and toggles need no second style. Transitions are 120ms on colour only.
- **Active:** the bevel inverts.
- **Disabled:** face #1a1622, dim label, no outline.
- **Compact:** 36 to 40px high, 12 to 14px padding, 13 to 14px label, used in the fixed bar, tab groups, list rows and the policy rail.
- **Square:** 36 or 44px icon-only button with an `aria-label`.
- **Progress (install):** while busy the face goes black with a white inner ring, and a violet bar fills left to right inside a 4px inset.
- **Installed:** dark green face with a chat-green label.

### Wells (lists, logs, reading panels)
- **Background:** inset black at 88% over the tile.
- **Border / depth:** 2px black edge and the inset-well bevel.
- **Internal Padding:** 6px for lists (rows have 10px padding and a 2px gap); 28px by clamp(18px, 3vw, 40px) for reading text.
- **Rows:** 44px pixel icon, a 600-weight name with a dim "by" line, muted description, and the action on the right; hover is a 3.5% white wash. Lists scroll inside a fixed height.

### Inputs / Fields
- **Style:** the search field is a well, 40px high, with a 16x16 pixel magnifier in dim; the input itself has no border or background.
- **Focus:** a white 2px outline inset on the well, the icon brightens to text, and the caret is splash yellow.

### Navigation
- **Fixed bar:** 60px, ink at 92%, black bottom edge, slides in (380ms ease-out) only after the title screen scrolls away. Brand mark (22px pixel favicon) at weight 700, then compact buttons.
- **Section rail:** a vertical stack of compact buttons, left-aligned labels, sticky at 84px from the top.

### Screen Header Band (signature)
A full-bleed band in black at 35% over the tile with the centred headline and an edge line beneath. Every sub-screen opens with one and closes with a centred Done button (min 320px wide) that returns to the title.

### Splash (signature)
Splash-yellow 700 text with a dark olive hard shadow, rotated -18deg and pinned to the wordmark's lower right. It scales in, then pulses between 1 and 1.09 (0.55s, alternate). Clicking it picks another line.

### Tooltip Toast
The in-game tooltip frame: near-black purple fill with a two-tone violet inner border (tip-a on top, tip-b around), 600 14px text, centred over the bottom of its panel. It fades and rises in over 260ms and stays 3.8s.

### Count Badge
Black block, chat-green mono numerals at 12px, 20px high; it bumps (scales to 1.35) when the value changes.

## Do's and Don'ts

### Do:
- **Do** build every interactive control from the one bevelled button: 2px black edge, raised bevel, violet face plus inset white outline for hover, focus and selected.
- **Do** recess lists, search and long reading text into wells.
- **Do** open sub-screens with the tile header band and end them with a centred Done button back to the title.
- **Do** draw icons and illustrations on 8x8 or 16x16 pixel grids with `shape-rendering: crispEdges`, ringed in 2px black when listed.
- **Do** give text over the panorama or button faces a 0-blur drop shadow.
- **Do** use the ease-out curve `cubic-bezier(0.16, 1, 0.3, 1)` for entrances, and honour reduced motion (the panorama stops, reveals show immediately).

### Don't:
- **Don't** round corners anywhere; the radius is 0.
- **Don't** use soft blur as the main depth cue; bevels make depth, and soft shadow is only for floating layers.
- **Don't** use icon fonts, emoji or smooth line icons; icons are pixel art.
- **Don't** set body copy, notes or legal lines in Dusk Grey on the tile or on stone; use Lavender Grey.
- **Don't** use JetBrains Mono for prose, headings or button labels.
- **Don't** fill static surfaces with violet; violet means state.
