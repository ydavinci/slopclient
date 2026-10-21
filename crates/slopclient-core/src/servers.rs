//! The multiplayer server list of an instance (`servers.dat`) and the server
//! list ping, so the launcher can show icon, MOTD, players and latency and join
//! a server directly (Quick Play).

use std::{
    net::SocketAddr,
    time::{Duration, Instant},
};

use serde::Serialize;
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

use crate::{Error, Paths, Result, instance::Instance, nbt::Nbt};

const DEFAULT_PORT: u16 = 25565;
const TIMEOUT: Duration = Duration::from_secs(5);

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Server {
    pub name: String,
    /// As typed in the game: `host` or `host:port`.
    pub address: String,
    /// The icon the game saved from the last ping, as a data URL.
    pub icon: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ServerStatus {
    /// MOTD without formatting codes, lines joined by `\n`.
    pub motd: String,
    pub version: String,
    pub online: i64,
    pub max: i64,
    pub latency_ms: u32,
    pub icon: Option<String>,
}

/// Saved servers in the order the game shows them.
pub fn list(paths: &Paths, instance: &Instance) -> Result<Vec<Server>> {
    let file = instance.game_dir(paths).join("servers.dat");
    let data = match std::fs::read(&file) {
        Ok(d) => d,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(e) => return Err(e.into()),
    };
    parse_servers(&data).ok_or_else(|| Error::Other("servers.dat ist beschädigt".into()))
}

fn parse_servers(data: &[u8]) -> Option<Vec<Server>> {
    let mut r = Nbt { data, pos: 0 };
    if r.u8()? != 10 {
        return None;
    }
    r.string()?;
    let mut servers = Vec::new();
    r.compound(
        &mut |r, tag, name, path| {
            if !(path.is_empty() && name == "servers" && tag == 9) {
                return Some(false);
            }
            let inner = r.u8()?;
            let n = r.i32()?.max(0);
            for _ in 0..n {
                if inner != 10 {
                    r.skip(inner)?;
                    continue;
                }
                let (mut server_name, mut ip, mut icon) = (None, None, None);
                r.compound(
                    &mut |r, tag, name, _| {
                        match (name, tag) {
                            ("name", 8) => server_name = Some(r.string()?),
                            ("ip", 8) => ip = Some(r.string()?),
                            ("icon", 8) => icon = Some(r.string()?),
                            _ => return Some(false),
                        }
                        Some(true)
                    },
                    "servers",
                )?;
                if let Some(address) = ip.filter(|a| !a.trim().is_empty()) {
                    servers.push(Server {
                        name: server_name.filter(|n| !n.is_empty()).unwrap_or_else(|| address.clone()),
                        address,
                        icon: icon.filter(|i| !i.is_empty()).map(|i| format!("data:image/png;base64,{i}")),
                    });
                }
            }
            Some(true)
        },
        "",
    )?;
    Some(servers)
}

/// Pings a server like the multiplayer screen does.
pub async fn ping(address: &str) -> Result<ServerStatus> {
    let (host, port) = resolve(address).await;
    tokio::time::timeout(TIMEOUT, ping_at(&host, port))
        .await
        .map_err(|_| Error::Other("Server antwortet nicht".into()))?
}

async fn ping_at(host: &str, port: u16) -> Result<ServerStatus> {
    let addr: SocketAddr = tokio::net::lookup_host((host, port))
        .await?
        .next()
        .ok_or_else(|| Error::Other(format!("{host} nicht gefunden")))?;
    let mut stream = TcpStream::connect(addr).await?;
    stream.set_nodelay(true)?;

    // Handshake (next state: status), then the status request.
    let mut handshake = Vec::new();
    varint(&mut handshake, 0x00);
    varint(&mut handshake, -1);
    varint(&mut handshake, host.len() as i32);
    handshake.extend(host.as_bytes());
    handshake.extend(port.to_be_bytes());
    varint(&mut handshake, 1);
    let mut out = Vec::new();
    packet(&mut out, &handshake);
    packet(&mut out, &[0x00]);
    stream.write_all(&out).await?;

    let body = read_packet(&mut stream).await?;
    let mut r = &body[..];
    if read_varint_slice(&mut r)? != 0x00 {
        return Err(Error::Other("unerwartete Antwort".into()));
    }
    let len = read_varint_slice(&mut r)? as usize;
    let json: serde_json::Value = serde_json::from_slice(r.get(..len).ok_or_else(|| Error::Other("Antwort zu kurz".into()))?)?;

    // Ping/pong for the latency.
    let started = Instant::now();
    let mut ping = vec![0x01];
    ping.extend(0x6461_636c_6965_6e74i64.to_be_bytes());
    let mut out = Vec::new();
    packet(&mut out, &ping);
    stream.write_all(&out).await?;
    let latency_ms = match read_packet(&mut stream).await {
        Ok(_) => started.elapsed().as_millis() as u32,
        // Some proxies close after the status: fall back to nothing better.
        Err(_) => 0,
    };

    Ok(ServerStatus {
        motd: crate::worlds::strip_formatting(&flatten(&json["description"])).trim().to_owned(),
        version: crate::worlds::strip_formatting(json["version"]["name"].as_str().unwrap_or("")),
        online: json["players"]["online"].as_i64().unwrap_or(0),
        max: json["players"]["max"].as_i64().unwrap_or(0),
        latency_ms,
        icon: json["favicon"].as_str().filter(|f| f.starts_with("data:image/")).map(|f| f.replace('\n', "")),
    })
}

/// Plain text of a chat component (string, object with text/extra, or array).
fn flatten(v: &serde_json::Value) -> String {
    match v {
        serde_json::Value::String(s) => s.clone(),
        serde_json::Value::Array(a) => a.iter().map(flatten).collect(),
        serde_json::Value::Object(o) => {
            let mut s = o.get("text").and_then(|t| t.as_str()).unwrap_or("").to_owned();
            if let Some(serde_json::Value::Array(extra)) = o.get("extra") {
                s.extend(extra.iter().map(flatten));
            }
            s
        }
        _ => String::new(),
    }
}

/// Host and port to connect to: an explicit port wins, otherwise the
/// `_minecraft._tcp` SRV record, otherwise port 25565.
async fn resolve(address: &str) -> (String, u16) {
    let address = address.trim();
    if let Some((host, port)) = address.rsplit_once(':') {
        if let Ok(port) = port.parse() {
            return (host.trim_matches(['[', ']']).to_owned(), port);
        }
    }
    let name = format!("_minecraft._tcp.{address}");
    if let Ok(Some(srv)) = tokio::task::spawn_blocking(move || srv_lookup(&name)).await {
        return srv;
    }
    (address.to_owned(), DEFAULT_PORT)
}

#[cfg(windows)]
fn srv_lookup(name: &str) -> Option<(String, u16)> {
    use windows_sys::Win32::NetworkManagement::Dns::{DNS_QUERY_STANDARD, DNS_RECORDW, DNS_TYPE_SRV, DnsFree, DnsFreeRecordList, DnsQuery_W};
    let wide: Vec<u16> = name.encode_utf16().chain([0]).collect();
    let mut records = std::ptr::null_mut();
    // SAFETY: `wide` is NUL-terminated; the result list is freed with DnsFree below.
    let status = unsafe { DnsQuery_W(wide.as_ptr(), DNS_TYPE_SRV, DNS_QUERY_STANDARD, std::ptr::null_mut(), &mut records, std::ptr::null_mut()) };
    if status != 0 || records.is_null() {
        return None;
    }
    let mut found = None;
    let mut record = records as *const DNS_RECORDW;
    // SAFETY: walking the list DnsQuery_W returned; each SRV record's union holds SRV data.
    unsafe {
        while !record.is_null() {
            if (*record).wType == DNS_TYPE_SRV {
                let srv = (*record).Data.SRV;
                let target = srv.pNameTarget;
                if !target.is_null() {
                    let len = (0..).take_while(|&i| *target.add(i) != 0).count();
                    let host = String::from_utf16_lossy(std::slice::from_raw_parts(target, len));
                    found = Some((host.trim_end_matches('.').to_owned(), srv.wPort));
                    break;
                }
            }
            record = (*record).pNext;
        }
        DnsFree(records as *const _, DnsFreeRecordList);
    }
    found
}

#[cfg(not(windows))]
fn srv_lookup(_name: &str) -> Option<(String, u16)> {
    None
}

fn varint(out: &mut Vec<u8>, value: i32) {
    let mut v = value as u32;
    loop {
        if v & !0x7f == 0 {
            out.push(v as u8);
            return;
        }
        out.push((v & 0x7f | 0x80) as u8);
        v >>= 7;
    }
}

fn packet(out: &mut Vec<u8>, body: &[u8]) {
    varint(out, body.len() as i32);
    out.extend(body);
}

async fn read_packet(stream: &mut TcpStream) -> Result<Vec<u8>> {
    let mut len: u32 = 0;
    for i in 0..5 {
        let b = stream.read_u8().await?;
        len |= ((b & 0x7f) as u32) << (7 * i);
        if b & 0x80 == 0 {
            break;
        }
    }
    if len > 4 * 1024 * 1024 {
        return Err(Error::Other("Antwort zu groß".into()));
    }
    let mut body = vec![0; len as usize];
    stream.read_exact(&mut body).await?;
    Ok(body)
}

fn read_varint_slice(r: &mut &[u8]) -> Result<i32> {
    let mut v: u32 = 0;
    for i in 0..5 {
        let (&b, rest) = r.split_first().ok_or_else(|| Error::Other("Antwort zu kurz".into()))?;
        *r = rest;
        v |= ((b & 0x7f) as u32) << (7 * i);
        if b & 0x80 == 0 {
            return Ok(v as i32);
        }
    }
    Err(Error::Other("ungültige Zahl".into()))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(out: &mut Vec<u8>, v: &str) {
        out.extend((v.len() as u16).to_be_bytes());
        out.extend(v.as_bytes());
    }

    #[test]
    fn reads_server_list() {
        let mut nbt = vec![10];
        s(&mut nbt, "");
        nbt.push(9);
        s(&mut nbt, "servers");
        nbt.push(10);
        nbt.extend(2i32.to_be_bytes());
        for (name, ip) in [("Hypixel", "mc.hypixel.net"), ("", "localhost:25566")] {
            nbt.push(8);
            s(&mut nbt, "ip");
            s(&mut nbt, ip);
            nbt.push(8);
            s(&mut nbt, "name");
            s(&mut nbt, name);
            nbt.push(1);
            s(&mut nbt, "hidden");
            nbt.push(0);
            nbt.push(0);
        }
        nbt.push(0);
        let servers = parse_servers(&nbt).unwrap();
        assert_eq!(servers.len(), 2);
        assert_eq!(servers[0].name, "Hypixel");
        assert_eq!(servers[1].name, "localhost:25566");
        assert!(servers[0].icon.is_none());
    }

    #[test]
    fn varints_round_trip() {
        for v in [0, 1, 127, 128, 25565, -1, i32::MAX] {
            let mut out = Vec::new();
            varint(&mut out, v);
            assert_eq!(read_varint_slice(&mut &out[..]).unwrap(), v);
        }
    }

    #[test]
    fn flattens_chat_components() {
        let v = serde_json::json!({"text": "A", "extra": [{"text": "B"}, "C"]});
        assert_eq!(flatten(&v), "ABC");
    }
}
