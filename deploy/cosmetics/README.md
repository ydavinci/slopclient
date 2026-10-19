# DaClient cosmetics backend on a VPS

The backend (`crates/daclient-cosmetics`) stores capes per Minecraft account and tells the
client mod which cape every player wears. Sign-in uses Mojang's session server, so there
are no passwords to store.

## Build

On any Linux x86_64 machine (or in WSL) with Rust installed:

```
cargo build --release -p daclient-cosmetics
```

The binary is `target/release/daclient-cosmetics`.

## Install (Debian/Ubuntu)

```
sudo useradd --system --home /var/lib/daclient-cosmetics --create-home daclient
sudo install -m 755 daclient-cosmetics /usr/local/bin/
sudo install -m 644 daclient-cosmetics.service /etc/systemd/system/
echo "DACLIENT_COSMETICS_ADMIN_TOKEN=$(openssl rand -hex 32)" | sudo tee /etc/daclient-cosmetics.env
sudo chmod 600 /etc/daclient-cosmetics.env
sudo systemctl enable --now daclient-cosmetics
```

Set your domain in `DACLIENT_COSMETICS_PUBLIC_URL` (service file) and in the `Caddyfile`, then:

```
sudo apt install caddy
sudo cp Caddyfile /etc/caddy/Caddyfile
sudo systemctl reload caddy
curl https://cosmetics.example.com/health     # → ok
```

Point the launcher at it: in DaClient → Einstellungen → Kosmetik → Cosmetics-Server, or build
the launcher with `DACLIENT_COSMETICS_URL=https://cosmetics.example.com` as default.

## Configuration

| Variable | Default | Meaning |
|---|---|---|
| `DACLIENT_COSMETICS_BIND` | `127.0.0.1:8787` | Listen address (keep it on localhost behind Caddy) |
| `DACLIENT_COSMETICS_DATA` | `./data` | SQLite database and images |
| `DACLIENT_COSMETICS_PUBLIC_URL` | `http://127.0.0.1:8787` | Base URL in image links |
| `DACLIENT_COSMETICS_ADMIN_TOKEN` | unset | Enables `/v1/admin/*` |
| `DACLIENT_COSMETICS_DEV_AUTH` | unset | `1` skips the Mojang check. **Local testing only.** |

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

Everything lives in `DACLIENT_COSMETICS_DATA`: `cosmetics.db` (SQLite, WAL mode) and `files/`.
Back up with `sqlite3 cosmetics.db ".backup backup.db"` plus a copy of `files/`.

## Privacy

Stored per account: Minecraft UUID and name, uploaded capes, reports. No IP addresses are
written. `DELETE /v1/account` (launcher: Kosmetik → Daten löschen) removes everything of an account.
