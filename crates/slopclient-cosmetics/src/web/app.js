// Web interface of the SlopClient cosmetics server: Microsoft sign-in by device code,
// then the same API the launcher uses (capes, hat and pet, account), with a live 3D
// preview from viewer.js (built from launcher/src/webviewer).
"use strict";

const TIERS = { shoulder: "Schulter", flying: "fliegend", walking: "laufend" };

let mine = { activeId: null, capes: [] };
let catalogue = null; // { hats, pets, accessories } with the models
let saved = { hat: null, pet: null, accessory: null };
let chosen = { hat: null, pet: null, accessory: null };
/** Catalogue list and page element of each kind. */
const KINDS = { hat: ["hats", "hats", "Kein Hut"], pet: ["pets", "pets", "Kein Begleiter"], accessory: ["accessories", "accessories", "Kein Zubehör"] };
let previewCape = null; // cape id, "upload" or null (none)
let preview = null;
let skinCanvas = null;
const frameCache = new Map(); // cape id -> Promise<canvas[]>

// ---------- small icons (same 1.8 stroke as the dock's tabs) ----------

function icon(paths) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("viewBox", "0 0 24 24");
  svg.setAttribute("fill", "none");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-width", "1.8");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.setAttribute("aria-hidden", "true");
  for (const d of paths) {
    const p = document.createElementNS(ns, "path");
    p.setAttribute("d", d);
    svg.append(p);
  }
  return svg;
}
/** "Nothing": an empty circle struck through. */
const ICON_NONE = ["M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18z", "M5.6 18.4 18.4 5.6"];
const ICON_EDIT = ["M12 20h9", "M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4z"];
const ICON_DELETE = ["M4 7h16", "M10 11v6M14 11v6", "M6 7l1 13h10l1-13", "M9 7V4h6v3"];

function noneTile() {
  const d = el("div", "none");
  d.append(icon(ICON_NONE));
  return d;
}

function iconButton(paths, label, className) {
  const b = el("button", `icon ${className || ""}`.trim());
  b.type = "button";
  b.setAttribute("aria-label", label);
  b.title = label;
  b.append(icon(paths));
  return b;
}

// ---------- 3D preview ----------

function ensurePreview() {
  if (preview) return;
  const canvas = $("preview");
  // The player fills the stage above the dock (shell.js), up to a generous size.
  const size = () => {
    const box = canvas.closest(".figure") || canvas.parentElement;
    const w = Math.max(160, Math.min(box.clientWidth, (box.clientHeight - 34) / 1.3, 560));
    return [Math.round(w), Math.round(w * 1.3)];
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
  return (catalogue[KINDS[kind][0]] || []).find((m) => m.id === id) || null;
}

function updateCaption() {
  let cape = "keine Cape";
  if (previewCape === "upload") cape = "neue Cape (noch nicht hochgeladen)";
  else if (previewCape) cape = mine.capes.find((c) => c.id === previewCape)?.name ?? cape;
  const parts = [cape];
  for (const kind of Object.keys(KINDS)) {
    const m = modelOf(kind, chosen[kind]);
    if (m) parts.push(m.name);
  }
  $("preview-caption").textContent = parts.join(" · ");
}

async function showCape(id) {
  previewCape = id;
  // Turn the player so the chosen cape is in sight.
  if (id && preview.faceCape) preview.faceCape();
  markCapeTiles();
  updateCaption();
  if (!id) return preview.setCape(null);
  if (id === "upload") {
    // The pending upload, as upload.js renders it.
    const p = uploadPreview();
    return p && preview.setCape(p.frames, p.frameTime, p.elytra);
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
  const editable = await editableCapes();
  const list = $("capes");
  list.replaceChildren();

  // "No cape" first.
  const none = el("div", "tile");
  none.dataset.id = "";
  none.append(noneTile(), el("div", "name", "Keine Cape"));
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
    const meta = cape.meta.frames > 1 ? `${cape.meta.frames}\u00a0Bilder · ${64 * cape.meta.scale}\u00a0px` : `${64 * cape.meta.scale}\u00a0px`;
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
    if (editable.has(cape.id) && !cape.hidden) {
      const edit = iconButton(ICON_EDIT, `„${cape.name}“ bearbeiten`);
      edit.onclick = (ev) => {
        ev.stopPropagation();
        editCape(cape);
      };
      actions.append(edit);
    }
    const del = iconButton(ICON_DELETE, `„${cape.name}“ löschen`, "danger");
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
    await dropSource(cape.id);
    if (upload && upload.kind === "convert" && upload.replaces && upload.replaces.id === cape.id) cancelUpload();
    toast("Cape gelöscht");
    await loadCapes();
  } catch (e) {
    toast(e.message, "err");
  }
}

// ---------- hat and pet ----------

function extrasTiles(kind) {
  const [key, element, none] = KINDS[kind];
  const list = $(element);
  list.replaceChildren();
  const models = catalogue[key] || [];
  // Older servers have no accessories: hide the section.
  list.closest(".extras-group").hidden = models.length === 0;
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
      if (model.credit) tile.append(el("div", "meta", `von ${model.credit}`));
    } else {
      tile.append(noneTile(), el("div", "name", none));
    }
    tile.onclick = () => {
      chosen = { ...chosen, [kind]: model ? model.id : null };
      applyExtras();
    };
    list.append(tile);
  }
}

function applyExtras() {
  for (const [kind, [, element]] of Object.entries(KINDS)) {
    for (const t of $(element).children) t.classList.toggle("selected", t.dataset.id === (chosen[kind] ?? ""));
  }
  preview.setExtras(modelOf("hat", chosen.hat), modelOf("pet", chosen.pet), modelOf("accessory", chosen.accessory));
  const dirty = Object.keys(KINDS).some((k) => chosen[k] !== saved[k]);
  $("save-extras").disabled = !dirty;
  $("reset-extras").disabled = !dirty;
  $("extras-state").textContent = dirty ? "Nur in der Vorschau, noch nicht gespeichert" : "Gespeichert";
  $("extras-state").classList.toggle("dirty", dirty);
  updateCaption();
}

async function loadExtras() {
  const [cat, me] = await Promise.all([catalogue || api("GET", "v1/extras/catalogue"), api("GET", `v1/cosmetics/${session.uuid}`)]);
  catalogue = cat;
  saved = { hat: me.hat ?? null, pet: me.pet ?? null, accessory: me.accessory ?? null };
  chosen = { ...saved };
  for (const kind of Object.keys(KINDS)) extrasTiles(kind);
  applyExtras();
}

async function saveExtras() {
  try {
    await api("PUT", "v1/extras", { hat: chosen.hat, pet: chosen.pet, accessory: chosen.accessory });
    saved = { ...chosen };
    toast("Gespeichert");
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
    await dropAllSources();
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
$("save-extras").onclick = saveExtras;
$("reset-extras").onclick = () => {
  chosen = { ...saved };
  applyExtras();
};
$("delete-account").onclick = deleteAccount;

session = loadSession();
render();
