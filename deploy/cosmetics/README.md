# SlopClient cosmetics backend on a VPS

The backend (`crates/slopclient-cosmetics`) stores capes per Minecraft account and tells the
client mod which cape every player wears. Sign-in uses Mojang's session server, so there
are no passwords to store.

## Upgrading from DaClient

The backend was renamed from `daclient-cosmetics` to `slopclient-cosmetics`. The database and the
`DACLIENT_COSMETICS_*` variables keep working (both prefixes are read), so an upgrade is:

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

Point the launcher at it: in SlopClient → Einstellungen → Kosmetik → Cosmetics-Server, or build
the launcher with `SLOPCLIENT_COSMETICS_URL=https://cosmetics.example.com` as default.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `SLOPCLIENT_COSMETICS_BIND` | `127.0.0.1:8787` | Listen address (keep it on localhost behind Caddy) |
| `SLOPCLIENT_COSMETICS_DATA` | `./data` | SQLite database and images |
| `SLOPCLIENT_COSMETICS_PUBLIC_URL` | `http://127.0.0.1:8787` | Base URL in image links |
| `SLOPCLIENT_COSMETICS_ADMIN_TOKEN` | unset | Enables `/v1/admin/*` |
| `SLOPCLIENT_COSMETICS_DEV_AUTH` | unset | `1` skips the Mojang check. **Local testing only.** |

## Moderation

Capes are visible right away. Reports land in a queue:

```
curl -H "Authorization: Bearer $ADMIN" https://cosmetics.example.com/v1/admin/reports
curl -X POST -H "Authorization: Bearer $ADMIN" https://cosmetics.example.com/v1/admin/capes/<capeId>/hide
```

Hidden capes are no longer served to anyone. Because uploads are public immediately, you need
terms of use and a contact address for takedown requests, and you should check the queue
regularly.

## Backups

Everything lives in `SLOPCLIENT_COSMETICS_DATA`: `cosmetics.db` (SQLite, WAL mode) and `files/`.
Back up with `sqlite3 cosmetics.db ".backup backup.db"` plus a copy of `files/`.

## Privacy

Stored per account: Minecraft UUID and name, uploaded capes, reports. No IP addresses are
written. `DELETE /v1/account` (launcher: Kosmetik → Daten löschen) removes everything of an account.
