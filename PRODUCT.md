# Product

<!-- impeccable:product-schema 1 -->

## Platform

web (project website). The product itself is a Windows desktop launcher (Tauri, later) plus a Fabric client mod.

## Stack

Website: Astro (static build, deployable to GitHub Pages). Chosen by the user.

## Users

- **Minecraft players on Windows** who want one launcher for vanilla and modded instances plus a polished in-game client interface (HUD, QoL, performance), and who play on servers like Hypixel where unfair advantages get you banned.
- **Mojang/Microsoft reviewers** checking the Azure AppID request for Minecraft API access. They need to see what the app is, how Microsoft sign-in works, and a privacy policy.

## Product Purpose

DaClient combines a Prism-style launcher (instances, versions, mod loaders, managed Java, Microsoft accounts) with a Lunar/Badlion-style in-game client interface (custom title screen, mod menu/ClickGUI, HUD editor). Success: players get both in one free tool, and the AppID is approved.

## Positioning

A launcher and a client in one, built on a Rust core: the launcher installs and manages the in-game client mod into every instance automatically. Fair-play only: HUD, QoL, performance and cosmetics, no combat advantages.

## Operating Context

- Players launch it on Windows before a session, pick an instance, hit play; it stays in the background while the game runs (small footprint).
- Sign-in: Microsoft device code flow (`XboxLive.signin offline_access`) → Xbox Live → XSTS → `login_with_xbox` → profile. Tokens encrypted with DPAPI in `%APPDATA%\DaClient`. No own servers, no telemetry.
- External services: Microsoft identity, Xbox Live, Minecraft services, Mojang downloads (piston-meta, libraries, resources), Fabric/Quilt/Forge/NeoForge metadata, Modrinth.

## Capabilities and Constraints

- Working today (backend + CLI): version list, install any version (client, libraries, natives, assets), managed Java 8/17/21/25, instances with play time, launch with live log, Microsoft login, Fabric/Quilt/Forge/NeoForge install, Modrinth search and install with dependencies (`api.modrinth.com`, `cdn.modrinth.com`), `.mrpack` import/export.
- Planned: Tauri UI (in progress), CurseForge content, Prism/MultiMC import, in-game client mod (title screen, ClickGUI, HUD editor).
- Windows only for now. In development, no public download yet.
- Must show the Minecraft disclaimer: "NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR MICROSOFT."

## Brand Commitments

- Name: **DaClient**, logo mark "D".
- Accent purple (`#8B5CF6` → `#C084FC`) and the Sora typeface, shared with the launcher mockup (`design/mockup.html`).

## Evidence on Hand

- Launcher UI mockup: `design/mockup.html`. No screenshots of a real UI yet, no users, testimonials, download counts or pricing. Do not invent any.
- Contact email not provided yet (placeholder).

## Product Principles

1. Fair play first: never market anything that reads as a cheat.
2. Transparent about accounts and data: the sign-in flow and stored data are stated plainly.
3. Honest about status: in development, features marked as planned where they are.
4. Lightweight: the launcher gets out of the way while the game runs.
