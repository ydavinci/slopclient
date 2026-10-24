// Shared helpers of the web page (loaded before app.js): messages, the stored session,
// API requests and small DOM helpers.
"use strict";

const SESSION_KEY = "slopclient-cosmetics-session";
const $ = (id) => document.getElementById(id);

let session = null;

// ---------- helpers ----------

function toast(text, kind = "ok") {
  const el = $("toast");
  el.textContent = text;
  el.className = kind;
  el.style.display = "block";
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => (el.style.display = "none"), 4000);
}

function loadSession() {
  try {
    const s = JSON.parse(localStorage.getItem(SESSION_KEY));
    if (s && s.token && s.expiresAt > Date.now() / 1000 + 60) return s;
  } catch {
    // No storage (private window) or broken value: sign in again.
  }
  return null;
}

function saveSession(s) {
  session = s;
  try {
    if (s) localStorage.setItem(SESSION_KEY, JSON.stringify(s));
    else localStorage.removeItem(SESSION_KEY);
  } catch {
    // Without storage the session lasts until the tab closes.
  }
}

async function request(method, path, body) {
  const headers = {};
  if (session) headers.authorization = `Bearer ${session.token}`;
  if (body !== undefined) headers["content-type"] = "application/json";
  // no-store: lookups are cached for game clients, the page needs the current state.
  const res = await fetch(path, { method, headers, cache: "no-store", body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 401 && session) {
    saveSession(null);
    render();
    throw new Error("Sitzung abgelaufen, bitte neu anmelden");
  }
  if (!res.ok) {
    let msg = `${res.status}`;
    try {
      msg = (await res.json()).error || msg;
    } catch {
      // Not JSON.
    }
    throw new Error(msg);
  }
  return res;
}

async function api(method, path, body) {
  const res = await request(method, path, body);
  return res.status === 204 || res.status === 202 ? null : res.json();
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error("Bild nicht ladbar"));
    img.src = src;
  });
}

/** Front face of a cape (10 × 16 px at scale 1, first frame) into a canvas. */
function drawCapeFront(canvas, image, scale) {
  canvas.width = 10 * scale;
  canvas.height = 16 * scale;
  const ctx = canvas.getContext("2d");
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(image, scale, scale, 10 * scale, 16 * scale, 0, 0, 10 * scale, 16 * scale);
}

/** Same-origin path of a cape image, so the 3D preview may use it as a texture. */
function capePath(cape) {
  return `files/${cape.hash}.png`;
}

function capeFrames(cape) {
  let p = frameCache.get(cape.id);
  if (!p) {
    p = loadImage(capePath(cape)).then((img) => SlopViewer.capeFrames(img, cape.meta.scale, cape.meta.frames));
    frameCache.set(cape.id, p);
  }
  return p;
}

function el(tag, className, text) {
  const e = document.createElement(tag);
  if (className) e.className = className;
  if (text !== undefined) e.textContent = text;
  return e;
}
