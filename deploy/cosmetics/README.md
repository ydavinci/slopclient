# SlopClient cosmetics backend on a VPS

The backend (`crates/slopclient-cosmetics`) stores capes per Minecraft account and tells the
client mod which cape every player wears. Sign-in uses Mojang's session server, so there
are no passwords to store.

## Upgrading from DaClient

The backend was renamed from `daclient-cosmetics` to `slopclient-cosmetics`. The database and the
`DACLIENT_COSMETICS_*` variables for data, public URL, admin token and bind address keep working
(`DEV_AUTH` and `SESSION_URL` are only read with the new prefix), so an upgrade is:

```sh
sudo systemctl disable --now daclient-cosmetics
# build and install as below, then:
sudo cp /etc/daclient-cosmetics.env /etc/slopclient-cosmetics.env   # if you used one
sudo systemctl enable --now slopclient-cosmetics
```

**Keep your existing data directory and user.** The new unit file uses `/var/lib/slopclient-cosmetics`
and the user `slopclient`; for an existing install, change `User`, `Group`, `WorkingDirectory`,
`SLOPCLIENT_COSMETICS_DATA` and `ReadWritePaths` in it to what your old unit had (for example
`/var/websites/cosmetics`), otherwise the server starts with an empty database. On first start it
adds the hat and pet columns to the existing database.

## Build

On any Linux x86_64 machine (or in WSL) with Rust installed:

```
cargo build --release -p slopclient-cosmetics
```

The binary is `target/release/slopclient-cosmetics`.

## Install (Debian/Ubuntu)

```
sudo useradd --system --home /var/lib/slopclient-cosmetics --create-home slopclient
sudo install -m 755 slopclient-cosmetics /usr/local/bin/
sudo install -m 644 slopclient-cosmetics.service /etc/systemd/system/
echo "SLOPCLIENT_COSMETICS_ADMIN_TOKEN=$(openssl rand -hex 32)" | sudo tee /etc/slopclient-cosmetics.env
sudo chmod 600 /etc/slopclient-cosmetics.env
sudo systemctl enable --now slopclient-cosmetics
```

Set your domain in `SLOPCLIENT_COSMETICS_PUBLIC_URL` (service file) and in the `Caddyfile`, then:

```
sudo apt install caddy
sudo cp Caddyfile /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://cosmetics.example.com/health     # → ok
```

Point the launcher at it: in SlopClient → Einstellungen → Kosmetik online → Cosmetics-Server
(empty means SlopClient's own server), or build the launcher with
`SLOPCLIENT_COSMETICS_URL=https://cosmetics.example.com` as default.

## Docker

Each release publishes `ghcr.io/ydavinci/slopclient-cosmetics` (tags `<version>` and `latest`).
The container listens on `0.0.0.0:8787` and keeps its data in the volume `/data`. With
[compose.yaml](compose.yaml), after setting your domain in it and in the `Caddyfile`:

```
echo "SLOPCLIENT_COSMETICS_ADMIN_TOKEN=$(openssl rand -hex 32)" > .env
docker compose up -d
curl http://127.0.0.1:8787/health     # → ok
```

Caddy then runs on the host as above. To build the image yourself, from the repository root:
`docker build -f deploy/cosmetics/Dockerfile -t slopclient-cosmetics .`. The container runs as
uid 10001; a bind mount instead of the named volume must be writable for that uid.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SLOPCLIENT_COSMETICS_BIND` | `127.0.0.1:8787` | Listen address (keep it on localhost behind Caddy) |
| `SLOPCLIENT_COSMETICS_DATA` | `./data` | SQLite database and images |
| `SLOPCLIENT_COSMETICS_PUBLIC_URL` | `http://127.0.0.1:8787` | The server's address as players enter it in the launcher. Image links use it, and **sign-ins are bound to it**: if it differs from the launcher's setting, launcher sign-in fails |
| `SLOPCLIENT_COSMETICS_ADMIN_TOKEN` | unset | Enables `/v1/admin/*`; at least 24 characters (`openssl rand -hex 32`), shorter tokens leave the admin API off |
| `SLOPCLIENT_COSMETICS_TRUST_PROXY` | off | `1` takes the client address for rate limits from `X-Forwarded-For`. Set it behind Caddy in Docker (compose.yaml does); with Caddy on the same host and a loopback bind it is automatic |
| `SLOPCLIENT_COSMETICS_DEV_AUTH` | unset | `1` skips the Mojang check. **Local testing only**; the server refuses to start with it unless the bind address is loopback |
| `SLOPCLIENT_COSMETICS_WEB_LOGIN` | on | `0` switches the web interface's Microsoft sign-in off |
| `SLOPCLIENT_COSMETICS_MSA_CLIENT_ID` | SlopClient's Azure app | Azure client id for the web sign-in (needs "Allow public client flows" and Mojang's approval) |

## Web interface

The server's root URL (`https://cosmetics.example.com/`) is a page where players manage their
cosmetics without the launcher: upload a cape (PNG, 64 × 32 or a multiple, animations as a
spritesheet), choose or remove the active cape, delete capes, pick hat and pet, delete the account.
A 3D preview (the launcher's viewer, `src/web/viewer.js`) shows the player's own skin with whatever
cape, hat or pet is clicked, before anything is saved. For it the server fetches the signed-in
player's skin from Mojang (`GET /v1/skin`), since browsers may not use Mojang's skin images in WebGL.

Sign-in uses Microsoft's device code flow like the launcher: the page shows a code, the player
enters it on microsoft.com/link, and the server checks via Xbox Live and the Minecraft services
which Minecraft account it is. **The Microsoft and Minecraft tokens pass through the server**, are
only used to read the Minecraft profile and are dropped right after; nothing of them is stored.
The page then gets the same 30-day session token as the launcher. If you don't want Microsoft
tokens on your server, set `SLOPCLIENT_COSMETICS_WEB_LOGIN=0`; the page then cannot sign in and
players use the launcher.

## Moderation

Capes are visible right away. Reports land in a queue, grouped per cape and sorted by how many
players reported it (one report per player and cape, at most 20 reports per player a day):

```
curl -H "Authorization: Bearer $ADMIN" https://cosmetics.example.com/v1/admin/reports
curl -X POST -H "Authorization: Bearer $ADMIN" https://cosmetics.example.com/v1/admin/capes/<capeId>/hide
```

Hiding blocks the image itself: every cape with the same image is hidden, the file is no longer
served, and uploading it again is refused. Browsers that loaded it before may keep it in their cache. Because uploads are public immediately, you need
terms of use and a contact address for takedown requests, and you should check the queue
regularly.

## Limits and security

- Rate limits per client address: sign-in 10 a minute, web sign-in starts 2 a minute, changes 60 a
  minute, lookups and images 600 a minute; uploads 10 at once and then 1 a minute per account
  (failed ones count), at most 30 a day and 50 capes per account. Only 2 images are checked at a time.
- Request bodies are limited to 64 KB, uploads to 12 MB (checked after the sign-in, before the body is
  read); requests time out after 60 s.
- Launcher sign-ins are bound to `SLOPCLIENT_COSMETICS_PUBLIC_URL`: another server cannot relay our
  challenge to a launcher and sign in as its player. Launchers before 0.4 cannot sign in any more.
- Sessions: 30 days from the launcher, 7 days from the web page, at most 20 per account;
  `POST /v1/auth/logout` (body `{"everywhere": true}` for all) ends them.
- The web page's Microsoft sign-in uses device codes, which can be phished ("enter this code for me"):
  the page warns about it. Switch it off with `SLOPCLIENT_COSMETICS_WEB_LOGIN=0` if you prefer.

## Backups

Everything lives in `SLOPCLIENT_COSMETICS_DATA`: `cosmetics.db` (SQLite, WAL mode) and `files/`.
Back up with `sqlite3 cosmetics.db ".backup backup.db"` plus a copy of `files/`.

## Privacy

Stored per account: Minecraft UUID and name, uploaded capes, hat and pet, reports. No IP addresses
and no Microsoft or Minecraft tokens are written (the web sign-in uses them only in memory). `DELETE /v1/account` (launcher: Kosmetik → Daten löschen) removes everything of an account.
