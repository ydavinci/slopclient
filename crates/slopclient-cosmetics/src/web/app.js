// Web interface of the SlopClient cosmetics server: Microsoft sign-in by device code,
// then the same API the launcher uses (capes, hat and pet, account), with a live 3D
// preview from viewer.js (built from launcher/src/webviewer).
"use strict";

const SCALES = [1, 2, 4, 8, 16];
const MAX_FRAMES = 128;
const TIERS = { shoulder: "Schulter", flying: "fliegend", walking: "laufend" };

let upload = null; // { image, scale, frames, dataUrl }
let mine = { activeId: null, capes: [] };
let catalogue = null; // { hats, pets } with the models
let saved = { hat: null, pet: null };
let chosen = { hat: null, pet: null };
let previewCape = null; // cape id, "upload" or null (none)
let preview = null;
let skinCanvas = null;
const frameCache = new Map(); // cape id -> Promise<canvas[]>

// ---------- 3D preview ----------

function ensurePreview() {
  if (preview) return;
  const canvas = $("preview");
  const size = () => {
    const w = Math.max(200, Math.min(canvas.parentElement.clientWidth, 340));
    return [w, Math.round(w * 1.3)];
  };
  preview = new SlopViewer.Preview(canvas, ...size());
  window.addEventListener("resize", () => preview && preview.setSize(...size()));
  for (const b of $("pose").querySelectorAll("button")) {
    b.onclick = () => {
      for (const o of $("pose").querySelectorAll("button")) o.classList.toggle("on", o === b);
      preview.setPose(b.dataset.pose);
    };
  }
}

async function loadSkin() {
  try {
    const res = await request("GET", "v1/skin");
    const slim = res.headers.get("x-skin-model") === "slim";
    const url = URL.createObjectURL(await res.blob());
    await preview.setSkin(url, slim);
    const img = await loadImage(url);
    skinCanvas = document.createElement("canvas");
    skinCanvas.width = img.width;
    skinCanvas.height = img.height;
    skinCanvas.getContext("2d").drawImage(img, 0, 0);
  } catch {
    // No skin (or Mojang unreachable): the stand-in player stays.
  }
}

function modelOf(kind, id) {
  if (!catalogue || !id) return null;
  return (kind === "hat" ? catalogue.hats : catalogue.pets).find((m) => m.id === id) || null;
}

function updateCaption() {
  let cape = "keine Cape";
  if (previewCape === "upload") cape = "neue Cape (noch nicht hochgeladen)";
  else if (previewCape) cape = mine.capes.find((c) => c.id === previewCape)?.name ?? cape;
  const parts = [cape];
  const hat = modelOf("hat", chosen.hat), pet = modelOf("pet", chosen.pet);
  if (hat) parts.push(hat.name);
  if (pet) parts.push(pet.name);
  $("preview-caption").textContent = parts.join(" · ");
}

async function showCape(id) {
  previewCape = id;
  markCapeTiles();
  updateCaption();
  if (!id) return preview.setCape(null);
  if (id === "upload") {
    if (!upload) return;
    const frameTime = Math.max(40, Number($("cape-frame-time").value) || 100);
    return preview.setCape(SlopViewer.capeFrames(upload.image, upload.scale, upload.frames), frameTime, $("cape-elytra").checked);
  }
  const cape = mine.capes.find((c) => c.id === id);
  if (!cape) return;
  try {
    const frames = await capeFrames(cape);
    if (previewCape === id) preview.setCape(frames, cape.meta.frameTimeMs, cape.meta.elytra);
  } catch {
    toast("Cape-Bild nicht ladbar", "err");
  }
}

// ---------- sign-in ----------

async function startLogin() {
  $("login-button").disabled = true;
  try {
    const start = await api("POST", "v1/web/login");
    // Only ever link to Microsoft's own sign-in pages.
    const link = new URL(start.verificationUri);
    if (link.protocol !== "https:" || !/(^|\.)(microsoft\.com|live\.com|microsoftonline\.com)$/.test(link.hostname)) {
      throw new Error("unerwartete Anmeldeseite");
    }
    $("user-code").textContent = start.userCode;
    $("verify-link").href = link.href;
    $("login-start").hidden = true;
    $("login-code").hidden = false;
    pollLogin(start.loginId, Date.now() + start.expiresIn * 1000);
  } catch (e) {
    toast(`Anmeldung nicht möglich: ${e.message}`, "err");
    $("login-button").disabled = false;
  }
}

async function pollLogin(id, deadline) {
  while (Date.now() < deadline) {
    await new Promise((r) => setTimeout(r, 2500));
    let status;
    try {
      status = await api("GET", `v1/web/login/${encodeURIComponent(id)}`);
    } catch (e) {
      toast(`Anmeldung fehlgeschlagen: ${e.message}`, "err");
      return resetLogin();
    }
    if (status.status === "done") {
      saveSession({ token: status.token, uuid: status.uuid, name: status.name, expiresAt: status.expiresAt });
      toast(`Angemeldet als ${status.name}`);
      return render();
    }
    if (status.status === "failed") {
      toast(`Anmeldung fehlgeschlagen: ${status.error}`, "err");
      return resetLogin();
    }
  }
  toast("Der Code ist abgelaufen, bitte neu starten", "err");
  resetLogin();
}

function resetLogin() {
  $("login-start").hidden = false;
  $("login-code").hidden = true;
  $("login-button").disabled = false;
}

// ---------- capes ----------

function markCapeTiles() {
  for (const t of $("capes").children) t.classList.toggle("selected", t.dataset.id === (previewCape ?? ""));
}

async function loadCapes() {
  mine = await api("GET", "v1/capes/mine");
  const list = $("capes");
  list.replaceChildren();

  // "No cape" first.
  const none = el("div", "tile");
  none.dataset.id = "";
  none.append(el("div", "none", "∅"), el("div", "name", "Keine Cape"));
  const noneActions = el("div", "actions");
  if (!mine.activeId) noneActions.append(el("span", "badge", "Getragen"));
  else {
    const wear = el("button", "", "Tragen");
    wear.onclick = (ev) => {
      ev.stopPropagation();
      setActive(null);
    };
    noneActions.append(wear);
  }
  none.append(noneActions);
  none.onclick = () => showCape(null);
  list.append(none);

  for (const cape of mine.capes) {
    const active = cape.id === mine.activeId;
    const tile = el("div", "tile");
    tile.dataset.id = cape.id;
    const canvas = el("canvas");
    loadImage(capePath(cape)).then((img) => drawCapeFront(canvas, img, cape.meta.scale), () => {});
    const meta = cape.meta.frames > 1 ? `${cape.meta.frames} Bilder · ${64 * cape.meta.scale} px` : `${64 * cape.meta.scale} px`;
    const actions = el("div", "actions");
    if (cape.hidden) {
      const badge = el("span", "badge warn", "Ausgeblendet");
      badge.title = "Nach einer Meldung von der Moderation ausgeblendet";
      actions.append(badge);
    } else if (active) {
      actions.append(el("span", "badge", "Getragen"));
    } else {
      const wear = el("button", "", "Tragen");
      wear.onclick = (ev) => {
        ev.stopPropagation();
        setActive(cape.id);
      };
      actions.append(wear);
    }
    const del = el("button", "danger", "Löschen");
    del.onclick = (ev) => {
      ev.stopPropagation();
      removeCape(cape);
    };
    actions.append(del);
    tile.append(canvas, el("div", "name", cape.name), el("div", "meta", meta), actions);
    tile.onclick = () => showCape(cape.id);
    list.append(tile);
  }
  // Keep the previewed cape if it still exists, else show the worn one.
  const keep = previewCape === "upload" || mine.capes.some((c) => c.id === previewCape);
  showCape(keep ? previewCape : mine.activeId);
}

async function setActive(id) {
  try {
    await api("PUT", "v1/capes/active", { id });
    toast(id ? "Cape angelegt" : "Keine Cape mehr");
    previewCape = id;
    await loadCapes();
  } catch (e) {
    toast(e.message, "err");
  }
}

async function removeCape(cape) {
  if (!confirm(`„${cape.name}“ löschen?`)) return;
  try {
    await api("DELETE", `v1/capes/${encodeURIComponent(cape.id)}`);
    frameCache.delete(cape.id);
    toast("Cape gelöscht");
    await loadCapes();
  } catch (e) {
    toast(e.message, "err");
  }
}

// ---------- upload ----------

function pickFile() {
  upload = null;
  $("upload").disabled = true;
  $("cape-preview").hidden = true;
  const file = $("cape-file").files[0];
  if (!file) {
    if (previewCape === "upload") showCape(mine.activeId);
    return;
  }
  if (file.size > 8 * 1024 * 1024) return toast("Die Datei ist größer als 8 MB", "err");
  const reader = new FileReader();
  reader.onload = async () => {
    let img;
    try {
      img = await loadImage(reader.result);
    } catch {
      return toast("Das ist kein lesbares PNG", "err");
    }
    const scale = img.width / 64;
    const frames = img.height / (32 * scale);
    if (!SCALES.includes(scale) || !Number.isInteger(frames) || frames < 1 || frames > MAX_FRAMES) {
      return toast(`${img.width} × ${img.height} passt nicht: Breite 64, 128, 256, 512 oder 1024 px, Höhe die Hälfte der Breite (je Bild)`, "err");
    }
    upload = { image: img, dataUrl: reader.result, scale, frames };
    drawCapeFront($("cape-canvas"), img, scale);
    $("cape-info").textContent = `${img.width} × ${img.height} px · ${frames} ${frames > 1 ? "Bilder" : "Bild"} · in der Vorschau`;
    $("frame-time-box").hidden = frames < 2;
    $("cape-preview").hidden = false;
    if (!$("cape-name").value) $("cape-name").value = file.name.replace(/\.png$/i, "").slice(0, 40);
    $("upload").disabled = false;
    showCape("upload");
  };
  reader.readAsDataURL(file);
}

async function doUpload() {
  if (!upload) return;
  const frameTimeMs = upload.frames > 1 ? Math.max(40, Number($("cape-frame-time").value) || 100) : 0;
  $("upload").disabled = true;
  try {
    const cape = await api("PUT", "v1/capes", {
      name: $("cape-name").value.trim() || "Cape",
      png: upload.dataUrl,
      meta: { version: 1, frames: upload.frames, frameTimeMs, scale: upload.scale, elytra: $("cape-elytra").checked },
      activate: true,
    });
    toast("Cape hochgeladen und angelegt");
    $("cape-file").value = "";
    $("cape-name").value = "";
    $("cape-preview").hidden = true;
    upload = null;
    previewCape = cape.id;
    await loadCapes();
  } catch (e) {
    toast(e.message, "err");
    $("upload").disabled = false;
  }
}

// ---------- hat and pet ----------

function extrasTiles(kind) {
  const list = $(kind === "hat" ? "hats" : "pets");
  list.replaceChildren();
  const models = kind === "hat" ? catalogue.hats : catalogue.pets;
  for (const model of [null, ...models]) {
    const tile = el("button", "tile");
    tile.type = "button";
    tile.dataset.id = model ? model.id : "";
    if (model) {
      const img = el("img");
      img.alt = "";
      SlopViewer.thumbnail(model, kind, skinCanvas ? () => skinCanvas : undefined).then((url) => (img.src = url), () => {});
      tile.append(img, el("div", "name", model.name));
      if (model.tier) tile.append(el("div", "meta", TIERS[model.tier] || model.tier));
    } else {
      tile.append(el("div", "none", "∅"), el("div", "name", kind === "hat" ? "Kein Hut" : "Kein Begleiter"));
    }
    tile.onclick = () => {
      chosen = { ...chosen, [kind]: model ? model.id : null };
      applyExtras();
    };
    list.append(tile);
  }
}

function applyExtras() {
  for (const [kind, list] of [["hat", $("hats")], ["pet", $("pets")]]) {
    for (const t of list.children) t.classList.toggle("selected", t.dataset.id === (chosen[kind] ?? ""));
  }
  preview.setExtras(modelOf("hat", chosen.hat), modelOf("pet", chosen.pet));
  const dirty = chosen.hat !== saved.hat || chosen.pet !== saved.pet;
  $("save-extras").disabled = !dirty;
  $("reset-extras").disabled = !dirty;
  $("extras-state").textContent = dirty ? "Nur in der Vorschau, noch nicht gespeichert" : "Gespeichert";
  $("extras-state").classList.toggle("dirty", dirty);
  updateCaption();
}

async function loadExtras() {
  const [cat, me] = await Promise.all([catalogue || api("GET", "v1/extras/catalogue"), api("GET", `v1/cosmetics/${session.uuid}`)]);
  catalogue = cat;
  saved = { hat: me.hat ?? null, pet: me.pet ?? null };
  chosen = { ...saved };
  extrasTiles("hat");
  extrasTiles("pet");
  applyExtras();
}

async function saveExtras() {
  try {
    await api("PUT", "v1/extras", { hat: chosen.hat, pet: chosen.pet });
    saved = { ...chosen };
    toast("Hut und Begleiter gespeichert");
    applyExtras();
  } catch (e) {
    toast(e.message, "err");
  }
}

// ---------- account ----------

async function logout(everywhere) {
  try {
    await api("POST", "v1/auth/logout", { everywhere });
  } catch {
    // Signed out locally anyway; an expired session needs no logout.
  }
  saveSession(null);
  if (everywhere) toast("Überall abgemeldet");
  render();
}

async function deleteAccount() {
  const name = prompt(`Zum Löschen aller Daten den Kontonamen „${session.name}“ eingeben. Das lässt sich nicht rückgängig machen.`);
  if (name === null) return;
  if (name.trim().toLowerCase() !== session.name.toLowerCase()) return toast("Name stimmt nicht, nichts gelöscht", "err");
  try {
    await api("DELETE", "v1/account");
    saveSession(null);
    toast("Alle Daten gelöscht");
    render();
  } catch (e) {
    toast(e.message, "err");
  }
}

// ---------- page ----------

async function render() {
  const signedIn = !!session;
  $("login").hidden = signedIn;
  $("app").hidden = !signedIn;
  $("logout").hidden = !signedIn;
  $("who").textContent = signedIn ? session.name : "";
  if (!signedIn) return resetLogin();
  ensurePreview();
  // The skin first, so the mini-me thumbnail wears it.
  await loadSkin();
  try {
    await Promise.all([loadCapes(), loadExtras()]);
  } catch (e) {
    if (session) toast(e.message, "err");
  }
}

$("login-button").onclick = startLogin;
$("copy-code").onclick = () => navigator.clipboard.writeText($("user-code").textContent).then(() => toast("Code kopiert"));
$("logout").onclick = () => logout(false);
$("logout-everywhere").onclick = () => logout(true);
$("cape-file").onchange = pickFile;
$("cape-frame-time").oninput = () => previewCape === "upload" && showCape("upload");
$("cape-elytra").onchange = () => previewCape === "upload" && showCape("upload");
$("upload").onclick = doUpload;
$("save-extras").onclick = saveExtras;
$("reset-extras").onclick = () => {
  chosen = { ...saved };
  applyExtras();
};
$("delete-account").onclick = deleteAccount;

session = loadSession();
render();
