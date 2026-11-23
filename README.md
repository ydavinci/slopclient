<p align="center">
  <img src="design/logo/glyph-512.png" width="96" alt="SlopClient logo">
</p>

<h1 align="center">SlopClient</h1>

<p align="center">
  A Minecraft launcher and in-game client in one: manage instances, mods and modpacks,<br>
  plus a client interface of its own with HUD, ClickGUI and cosmetics. Fair play, no cheats.
</p>

<p align="center">
  <a href="https://github.com/ydavinci/slopclient/releases/latest"><img src="https://img.shields.io/github/v/release/ydavinci/slopclient?label=Download&color=8B5CF6" alt="Latest release"></a>
  <a href="https://github.com/ydavinci/slopclient/actions/workflows/release.yml"><img src="https://img.shields.io/github/actions/workflow/status/ydavinci/slopclient/release.yml?label=Build" alt="Build status"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/ydavinci/slopclient?color=555" alt="License"></a>
</p>

---

## Download

| What | File | For |
|---|---|---|
| **Launcher** | [`SlopClient_<version>_x64-setup.exe`](https://github.com/ydavinci/slopclient/releases/latest) | Windows 10/11, includes the client mod |
| Client mod on its own | `slopclient-<version>.jar` | Fabric/Quilt, Minecraft 26.3, for other launchers |
| Cosmetics only | `slopclient-cosmetics-mod-<version>.jar` | capes, hats and pets without the client, in any Fabric launcher ([more](#cosmetics-only-as-a-mod)) |
| Cosmetics server | `ghcr.io/ydavinci/slopclient-cosmetics` | your own server for capes, hats and pets ([guide](deploy/cosmetics/README.md)) |

Run the installer, sign in with your Microsoft account, create an instance, play. The launcher puts the client mod
into matching instances automatically (can be turned off per instance).

From version 0.5.7 on, the launcher updates itself: when there is a new release, a notice with the changes and
"Jetzt aktualisieren" (update now) appears at the top (Settings → Updates). The client mod comes along with it. If you
have an older version, install 0.5.7 by hand once.

> The installer is not code-signed yet, so Windows SmartScreen asks on first start ("More info" → "Run anyway").
> The update signature is separate from that: the launcher only installs updates signed with the SlopClient key.

The launcher's interface is in German.

## Features

### Launcher

- **Instances** for every Minecraft version with Fabric, Quilt, NeoForge or Forge (1.13+): groups, copying, custom icons, playtime, desktop shortcut, commands before launch and after exit
- **Update mod loaders**: change the loader and version of existing instances, with a hint when a newer stable version exists
- **Java handled for you**: the matching runtime (8 / 17 / 21 / 25) comes straight from Mojang
- **Mods, resource packs and shaders** from Modrinth and CurseForge: search filtered to the instance, dependencies, drag & drop of your own files (recognised and kept up to date), a performance pack (Sodium, Lithium, FerriteCore, Iris) in one click
- **One-click updates**: the launcher checks for updates in the background. Instances show "N updates", every item gets its own "Update" button, and you see the changelogs before "Update all".
- **Mods stored only once**: mods, resource packs and shaders live once in a shared store, and instances get hard links to them. Nothing one instance already has is downloaded again for another. Plus "merge duplicate files" for older instances.
- **Shared game settings**: instances in a group share graphics, controls, sound and key bindings (`options.txt` and friends).
- **Modpacks**: import `.mrpack` and CurseForge ZIPs, export instances as `.mrpack`
- **Migration from Prism Launcher, PolyMC and MultiMC**, including worlds and configs
- **Worlds, screenshots and servers** per instance: datapacks per world, backup as ZIP, server list with ping, "Join" launches straight into the server
- **Screenshot gallery** across all instances with a viewer (zoom, paging, copy, delete), the same gallery in game too
- **Crash analysis**: detects too little RAM, the wrong Java, broken or duplicate mods and more, and names the cause; share logs via mclo.gs in one click (without credentials)
- **Self-update**: new releases with their changes, signature-checked and installed in one click
- **Discord status** with instance and server (can be turned off)
- **Microsoft sign-in** via device code, multiple accounts; tokens are stored encrypted with Windows DPAPI

### Client mod (Fabric/Quilt, Minecraft 26.3)

- its own **title screen** and **ClickGUI** (right shift) with search across modules and settings, adjustable size and **config profiles**
- **HUD editor** with snapping and many elements: FPS, CPS, keystrokes, coordinates, speed, armour, potions, ping, server IP, clock, compass, item counter, combo, reach, cooldowns, boss bar, memory, biome, day, stopwatch, resource packs, module list
- **Librarian**: look at a lectern and the librarian's books and prices show next to it
- **View**: zoom (C), freelook (Alt), brightness, custom crosshair, block outline, hit colour, low fire, custom nametag, glow for visible players
- **Light level overlay**: shows on the ground where mobs can spawn (red always, yellow at night, green safe)
- **Skybox**: the launcher's shader as the sky
- **Anvil planner**: the cheapest order to combine enchanted books in an anvil, with levels per step; the plan can be pinned next to the anvil
- **Comfort**: toggle sprint, auto tool while mining, chat with timestamps and grouped repeats, scoreboard tweaks, screenshot preview after F2
- **Custom fonts** for menus, the HUD and, if you like, the whole game
- **Elytra swap**: swaps elytra and chestplate with a key or automatically when jumping in mid-air, and back after landing
- **Server rules**: servers can turn off individual modules

### Cosmetics

- **Cape studio**: your own capes, animated too, from images and GIFs, with a 3D preview; the artwork wraps over the edges, and on the elytra it takes the shape of the wings
- **Hats, pets and accessories**: ten hats, 22 pets (on the shoulder, flying or walking, including a mini-me with your skin) and accessories such as katanas and wings
- Client-side only, just for you: a **trail** behind you in several styles (sparks, spiral, comet, blossoms, souls, notes, hearts) and **glass**, players as glass figures in six variants
- **Skins**: a library with 3D preview, uploaded straight to your Minecraft account
- **Visible online**: through the cosmetics server, other SlopClient players see your cosmetics. The SlopClient server is the default; enter your own under *Settings → Kosmetik online*. Everything can also be managed in the browser without the launcher (the server's web interface).

**Fair play:** SlopClient offers HUD, comfort, performance and cosmetics, but no advantage in combat.

## Cosmetics only, as a mod

`slopclient-cosmetics-mod-<version>.jar` shows SlopClient players' capes, hats and pets without the SlopClient
client, in any launcher: put it together with [Fabric API](https://modrinth.com/mod/fabric-api) into the `mods`
folder of a Fabric instance for Minecraft 26.3. You then see the cosmetics of every player signed in to the cosmetics
server, your own included.

Settings live in `config/slopclient-cosmetics.json` (created on first start):

| Key | Default | Meaning |
|---|---|---|
| `server` | `https://cosmetics.ydavinci.me` | cosmetics server; empty turns online cosmetics off |
| `capes`, `capesOfOthers` | `true` | capes at all / on other players too |
| `capesOverMojang` | `true` | show the SlopClient cape instead of the Mojang cape |
| `animateCapes`, `capesAsElytra` | `true` | animated capes, cape on the elytra too |
| `hatsAndPets`, `hatsAndPetsOfOthers` | `true` | hats and pets / on other players too |
| `hideHatWithHelmet` | `false` | hide the hat while wearing a helmet |

You pick your own cosmetics in the launcher or in the browser on the cosmetics server's page
(e.g. `https://cosmetics.ydavinci.me/`, sign-in with Microsoft).
The SlopClient client already includes this mod; installing both does no harm.

## Your own cosmetics server

The server (`crates/slopclient-cosmetics`) stores capes, hats and pets per Minecraft account.
Sign-in without a password through Mojang's session server, data in SQLite. Quick start with Docker:

```sh
docker run -d -p 127.0.0.1:8787:8787 -v slopclient-cosmetics:/data \
  -e SLOPCLIENT_COSMETICS_PUBLIC_URL=https://cosmetics.example.com \
  ghcr.io/ydavinci/slopclient-cosmetics:latest
```

The server's address serves a web interface where players manage their cosmetics without the launcher.
HTTPS, Compose, configuration, web interface, moderation and backups: [deploy/cosmetics/README.md](deploy/cosmetics/README.md).

## Building it yourself

Requirements: [Rust](https://rustup.rs) (stable), Node.js 22, JDK 25.

```sh
# 1. Client mod (embedded into the launcher in the next step)
cd client-mod && ./gradlew build && cd ..

# 2. Launcher: development mode or installer
cd launcher && npm ci
npx tauri dev
npx tauri build          # → target/release/bundle/nsis/

# CLI and cosmetics server
cargo build --release -p slopclient-cli
cargo build --release -p slopclient-cosmetics
```

Tests: `cargo test --workspace`.

The 3D preview in the cosmetics server's web interface is the launcher's viewer
(`launcher/src/webviewer/`), built to `crates/slopclient-cosmetics/src/web/viewer.js`. After changes to
`launcher/src/lib/extras.ts` or `launcher/src/lib/cape.ts`, rebuild it in `launcher/`: `npm run build:viewer`.

**New hats and pets** go into `cosmetics/extras.json` (pet textures into `cosmetics/pets/`) and only need a new
server release: the mod, launcher and web interface fetch the catalogue from the cosmetics server.
**Accessories** from a Java block model (e.g. a Blockbench export) are added to the catalogue by
`tools/accessories/import_java_model.py`, with the creator (`--credit`) and placement on the body. Only models you
have the creator's permission for.

### Project layout

| Folder | Contents |
|---|---|
| `crates/slopclient-core` | core in Rust: versions, installation, mod loaders, content, sign-in, launch |
| `crates/slopclient-cli` | test CLI on top of the core |
| `crates/slopclient-cosmetics` | cosmetics server (axum + SQLite) |
| `launcher/` | launcher interface (Tauri 2 + React) |
| `client-mod/` | in-game client (Fabric) |
| `client-mod/cosmetics/` | cosmetics mod (capes, hats, pets), included in the client and usable on its own |
| `cosmetics/` | catalogue of hats and pets, shared by launcher, mod and server |
| `deploy/cosmetics/` | Docker, Compose, systemd and Caddy for the server |
| `web/` | project website (Astro) |

Plan and design: [PLAN.md](PLAN.md), [DESIGN.md](DESIGN.md). Changes: [CHANGELOG.md](CHANGELOG.md).

### Releasing

Bump the version in four places, rename `## Unveröffentlicht` in `CHANGELOG.md`, push the tag `v<version>`. The
workflow builds everything, signs the launcher for the self-update and publishes the GitHub release and the Docker
image. The individual steps and the signing secrets: [RELEASING.md](RELEASING.md).

## License

[Eclipse Public License 2.0](LICENSE).

**Not an official Minecraft product. Not approved by or associated with Mojang or Microsoft.**
