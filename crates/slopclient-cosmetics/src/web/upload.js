// Cape upload on the web page. A finished cape texture (PNG in the cape layout, frames
// stacked) goes up as it is; any other picture or animation (GIF, WebP, APNG, JPG, PNG)
// is fitted into the cape like the launcher's Cape Studio does (launcher/src/lib/cape.ts,
// shipped in viewer.js as SlopViewer.cape), with the same editor. The original picture
// and the editor settings stay in this browser, so such a cape can be edited again.
// Loaded after common.js, before app.js.
"use strict";

const SCALES = [1, 2, 4, 8, 16];
const MAX_FRAMES = 128;
/** Largest source file; the finished cape must stay under the server's 8 MB. */
const SOURCE_MAX_BYTES = 30 * 1024 * 1024;
const CAPE_MAX_BYTES = 8 * 1024 * 1024;
const FACE_W = 200;
const FACE_H = 320;

/**
 * The pending upload:
 * { kind: "sheet", image, dataUrl, scale, frames } for a finished cape texture,
 * { kind: "convert", file, source, state, edge, rendered, size, replaces } for a converted
 * picture; `replaces` is the cape being edited again, `size` the exported PNG's bytes.
 */
let upload = null;
let renderTimer = 0;
let sizeTimer = 0;
let faceTimer = 0;

function readDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function kb(bytes) {
  return bytes >= 1024 * 1024 ? `${(bytes / 1024 / 1024).toFixed(2)} MB` : `${Math.max(1, Math.round(bytes / 1024))} KB`;
}

// ---------- originals kept in this browser ----------

/** Original picture, name and editor settings per cape id, in IndexedDB. */
const SOURCES_DB = "slopclient-cape-sources";

function sourcesStore(mode, use) {
  return new Promise((resolve, reject) => {
    const open = indexedDB.open(SOURCES_DB, 1);
    open.onupgradeneeded = () => open.result.createObjectStore("sources");
    open.onerror = () => reject(open.error);
    open.onsuccess = () => {
      const db = open.result;
      const tx = db.transaction("sources", mode);
      const req = use(tx.objectStore("sources"));
      tx.oncomplete = () => {
        db.close();
        resolve(req ? req.result : undefined);
      };
      tx.onerror = tx.onabort = () => {
        db.close();
        reject(tx.error);
      };
    };
  });
}

/** Ids of the capes this browser can edit again; empty without storage (private window). */
async function editableCapes() {
  try {
    return new Set(await sourcesStore("readonly", (s) => s.getAllKeys()));
  } catch {
    return new Set();
  }
}

async function keepSource(id, record) {
  try {
    await sourcesStore("readwrite", (s) => s.put(record, id));
  } catch {
    // No storage: the cape just cannot be edited again here.
  }
}

async function dropSource(id) {
  try {
    await sourcesStore("readwrite", (s) => s.delete(id));
  } catch {
    // Nothing stored.
  }
}

async function dropAllSources() {
  try {
    await sourcesStore("readwrite", (s) => s.clear());
  } catch {
    // Nothing stored.
  }
}

// ---------- picking a file ----------

function resetUpload() {
  upload = null;
  clearTimeout(renderTimer);
  clearTimeout(sizeTimer);
  clearInterval(faceTimer);
  $("upload").disabled = true;
  $("upload").textContent = "Hochladen und tragen";
  $("upload-title").textContent = "Cape hochladen";
  $("upload-cancel").hidden = true;
  $("cape-preview").hidden = true;
  $("cape-edit").hidden = true;
}

function cancelUpload() {
  resetUpload();
  $("cape-file").value = "";
  $("cape-name").value = "";
  if (previewCape === "upload") showCape(mine.activeId);
}

async function pickFile() {
  resetUpload();
  const file = $("cape-file").files[0];
  if (!file) {
    if (previewCape === "upload") showCape(mine.activeId);
    return;
  }
  if (file.size > SOURCE_MAX_BYTES) return toast("Die Datei ist größer als 30 MB", "err");
  if (!$("cape-name").value) $("cape-name").value = file.name.replace(/\.[a-z0-9]+$/i, "").slice(0, 40);
  $("upload-cancel").hidden = false;

  // A PNG that already is a cape texture keeps its pixels exactly.
  if (file.type === "image/png" || /\.png$/i.test(file.name)) {
    try {
      const dataUrl = await readDataUrl(file);
      const image = await loadImage(dataUrl);
      const scale = image.width / 64;
      const frames = image.height / (32 * scale);
      if (SCALES.includes(scale) && Number.isInteger(frames) && frames >= 1 && frames <= MAX_FRAMES && file.size <= CAPE_MAX_BYTES) {
        upload = { kind: "sheet", image, dataUrl, scale, frames };
        drawCapeFront($("cape-canvas"), image, scale);
        $("cape-canvas").hidden = false;
        $("sheet-elytra-box").hidden = false;
        $("cape-info").textContent = `Fertige Cape-Textur · ${image.width} × ${image.height} px · ${frames} ${frames > 1 ? "Bilder" : "Bild"}`;
        $("frame-time-box").hidden = frames < 2;
        $("cape-preview").hidden = false;
        $("upload").disabled = false;
        return showCape("upload");
      }
    } catch {
      // Not readable as a plain image: try the decoder below.
    }
  }

  let source;
  try {
    source = await SlopViewer.cape.decode(file);
  } catch {
    return toast("Das Bild lässt sich nicht lesen (GIF, PNG, WebP oder JPG)", "err");
  }
  openEditor(file, source, SlopViewer.cape.defaultState(source), null);
}

/** Opens the editor for a cape uploaded from this browser before. */
async function editCape(cape) {
  let record;
  try {
    record = await sourcesStore("readonly", (s) => s.get(cape.id));
  } catch {
    // Handled below.
  }
  if (!record) return toast("Das Originalbild dieser Cape ist in diesem Browser nicht gespeichert", "err");
  let source;
  try {
    source = await SlopViewer.cape.decode(record.file);
  } catch {
    return toast("Das Originalbild lässt sich nicht mehr lesen", "err");
  }
  resetUpload();
  $("cape-file").value = "";
  $("cape-name").value = cape.name;
  const last = source.frames.length - 1;
  const state = { ...SlopViewer.cape.defaultState(source), ...record.state };
  state.trimStart = Math.min(state.trimStart, last);
  state.trimEnd = Math.min(state.trimEnd, last);
  openEditor(record.file, source, state, cape);
  if (window.openPanel) window.openPanel("studio");
  else $("upload-section").scrollIntoView({ behavior: "smooth", block: "start" });
}

function openEditor(file, source, state, replaces) {
  upload = { kind: "convert", file, source, state, edge: SlopViewer.cape.edgeColor(source.frames[0]), rendered: [], size: null, replaces };
  $("upload-title").textContent = replaces ? `„${replaces.name}“ bearbeiten` : "Cape hochladen";
  $("upload").textContent = replaces ? "Änderungen hochladen" : "Hochladen und tragen";
  $("upload-cancel").hidden = false;
  $("cape-canvas").hidden = true;
  $("sheet-elytra-box").hidden = true;
  $("frame-time-box").hidden = true;
  $("cape-preview").hidden = false;
  $("cape-edit").hidden = false;
  const scales = $("edit-scale");
  scales.replaceChildren(...SlopViewer.cape.SCALES.map((s) => new Option(`${64 * s} × ${32 * s}`, String(s))));
  const last = source.frames.length - 1;
  for (const id of ["edit-start", "edit-end"]) $(id).max = String(last);
  $("edit-anim").hidden = last < 1;
  $("edit-edge-swatch").style.background = upload.edge;
  syncControls();
  stateChanged();
}

// ---------- editor ----------

/** Shows the state in the controls. */
function syncControls() {
  const st = upload.state;
  const seg = (id, value) => {
    for (const b of $(id).querySelectorAll("button")) b.classList.toggle("on", b.dataset.value === value);
  };
  seg("edit-fit", st.fit);
  seg("edit-back", st.back);
  seg("edit-edge", st.edge);
  $("edit-back-color").hidden = st.back !== "color";
  $("edit-back-color").value = st.backColor;
  $("edit-edge-color").hidden = st.edge !== "color";
  $("edit-edge-color").value = st.edgeColor;
  $("edit-edge-swatch").hidden = st.edge !== "auto";
  $("edit-elytra").checked = st.elytra;
  $("edit-zoom").value = String(st.zoom);
  $("edit-zoom-value").textContent = `${st.zoom.toFixed(2)}×`;
  $("edit-start").value = String(st.trimStart);
  $("edit-start-value").textContent = String(st.trimStart + 1);
  $("edit-end").value = String(st.trimEnd);
  $("edit-end-value").textContent = String(st.trimEnd + 1);
  $("edit-speed").value = String(st.speed);
  $("edit-speed-value").textContent = `${st.speed.toFixed(2)}×`;
  $("edit-scale").value = String(st.scale);
}

/** Applies a change to the state, then renders again (debounced while dragging). */
function setState(patch) {
  if (!upload || upload.kind !== "convert") return;
  Object.assign(upload.state, patch);
  syncControls();
  stateChanged();
}

function stateChanged() {
  clearTimeout(renderTimer);
  renderTimer = setTimeout(renderConverted, 60);
  // The real export's file size, for the 8 MB limit.
  clearTimeout(sizeTimer);
  upload.size = null;
  showSize();
  const pending = upload;
  sizeTimer = setTimeout(() => {
    SlopViewer.cape
      .exportCape(pending.source, pending.state)
      .then(({ png }) => {
        if (upload !== pending) return;
        pending.size = png.size;
        showSize();
      })
      .catch(() => {});
  }, 350);
}

function showSize() {
  const size = upload.size;
  const tooBig = size != null && size > CAPE_MAX_BYTES;
  const el = $("edit-size");
  el.textContent = size == null ? "Größe wird berechnet …" : `Dateigröße ${kb(size)} von höchstens 8 MB.${tooBig ? " Wähle eine kleinere Auflösung oder kürze die Animation." : ""}`;
  el.classList.toggle("err", tooBig);
  $("upload").disabled = tooBig;
}

function renderConverted() {
  if (!upload || upload.kind !== "convert") return;
  const { source, state, edge } = upload;
  const { frames, thinned } = SlopViewer.cape.activeFrames(source, state);
  upload.rendered = frames.map((f) => SlopViewer.cape.renderFrame(f, state, edge));
  const n = upload.rendered.length;
  const ms = SlopViewer.cape.frameTime(source, state);
  const parts = [`${source.width} × ${source.height} px`, `${n} ${n > 1 ? "Bilder" : "Bild"}`];
  if (n > 1) parts.push(`${ms} ms je Bild`);
  $("cape-info").textContent = `${upload.replaces ? "Wird neu umgewandelt" : "Wird zur Cape umgewandelt"} · ${parts.join(" · ")}`;
  $("edit-anim-count").textContent = `${n} Bilder · ${ms} ms`;
  const note = $("edit-thinned");
  note.hidden = !(source.sampled || thinned);
  note.textContent = thinned
    ? `Bei ${64 * state.scale} × ${32 * state.scale} passen höchstens ${n} Bilder, die Animation wird gleichmäßig ausgedünnt.`
    : `Die Animation hat mehr als ${MAX_FRAMES} Bilder und wurde gleichmäßig ausgedünnt.`;
  playFace(ms);
  showCape("upload");
}

/** The front face at its true resolution, upscaled crisply, playing the animation. */
function playFace(ms) {
  clearInterval(faceTimer);
  const canvas = $("edit-face-canvas");
  const dpr = window.devicePixelRatio || 1;
  canvas.width = FACE_W * dpr;
  canvas.height = FACE_H * dpr;
  const ctx = canvas.getContext("2d");
  const frames = upload.rendered, s = upload.state.scale;
  let i = 0;
  const draw = () => {
    ctx.imageSmoothingEnabled = false;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(frames[i % frames.length], 1 * s, 1 * s, 10 * s, 16 * s, 0, 0, canvas.width, canvas.height);
    i++;
  };
  draw();
  if (frames.length > 1) faceTimer = setInterval(draw, ms);
}

function zoomBy(factor) {
  setState({ zoom: Math.min(8, Math.max(0.2, upload.state.zoom * factor)) });
}

function bindEditor() {
  const seg = (id, apply) => {
    for (const b of $(id).querySelectorAll("button")) b.onclick = () => apply(b.dataset.value);
  };
  seg("edit-fit", (fit) => setState({ fit, zoom: 1, offsetX: 0, offsetY: 0 }));
  seg("edit-back", (back) => setState({ back }));
  seg("edit-edge", (edge) => setState({ edge }));
  $("edit-rotate").onclick = () => setState({ rotation: (upload.state.rotation + 90) % 360 });
  $("edit-back-color").oninput = (e) => setState({ backColor: e.target.value });
  $("edit-edge-color").oninput = (e) => setState({ edgeColor: e.target.value });
  $("edit-elytra").onchange = (e) => setState({ elytra: e.target.checked });
  $("edit-zoom").oninput = (e) => setState({ zoom: Number(e.target.value) });
  $("edit-start").oninput = (e) => setState({ trimStart: Math.min(Number(e.target.value), upload.state.trimEnd) });
  $("edit-end").oninput = (e) => setState({ trimEnd: Math.max(Number(e.target.value), upload.state.trimStart) });
  $("edit-speed").oninput = (e) => setState({ speed: Number(e.target.value) });
  $("edit-scale").oninput = (e) => setState({ scale: Number(e.target.value) });

  // Front face: drag to move, wheel to zoom, keys for both.
  const face = $("edit-face");
  let drag = null;
  face.onpointerdown = (e) => {
    if (!upload || upload.kind !== "convert") return;
    face.setPointerCapture(e.pointerId);
    drag = { x: e.clientX, y: e.clientY, ox: upload.state.offsetX, oy: upload.state.offsetY };
  };
  face.onpointermove = (e) => {
    if (!drag) return;
    setState({ offsetX: drag.ox + (e.clientX - drag.x) / FACE_W, offsetY: drag.oy + (e.clientY - drag.y) / FACE_H });
  };
  face.onpointerup = face.onpointercancel = () => (drag = null);
  face.addEventListener(
    "wheel",
    (e) => {
      if (!upload || upload.kind !== "convert") return;
      e.preventDefault();
      zoomBy(e.deltaY < 0 ? 1.08 : 1 / 1.08);
    },
    { passive: false },
  );
  face.onkeydown = (e) => {
    if (!upload || upload.kind !== "convert") return;
    const step = e.shiftKey ? 0.1 : 0.02;
    const st = upload.state;
    const moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
    if (moves[e.key]) setState({ offsetX: st.offsetX + moves[e.key][0], offsetY: st.offsetY + moves[e.key][1] });
    else if (e.key === "+" || e.key === "=") zoomBy(1.08);
    else if (e.key === "-") zoomBy(1 / 1.08);
    else return;
    e.preventDefault();
  };
}

/** Frames, timing and elytra of the pending upload for the 3D preview; null without one. */
function uploadPreview() {
  if (!upload) return null;
  if (upload.kind === "sheet") {
    return {
      frames: SlopViewer.capeFrames(upload.image, upload.scale, upload.frames),
      frameTime: Math.max(40, Number($("cape-frame-time").value) || 100),
      elytra: $("cape-elytra").checked,
    };
  }
  return { frames: upload.rendered, frameTime: SlopViewer.cape.frameTime(upload.source, upload.state), elytra: upload.state.elytra };
}

// ---------- upload ----------

async function doUpload() {
  if (!upload) return;
  const pending = upload;
  $("upload").disabled = true;
  try {
    let png, meta;
    if (pending.kind === "sheet") {
      png = pending.dataUrl;
      const frameTimeMs = pending.frames > 1 ? Math.max(40, Number($("cape-frame-time").value) || 100) : 0;
      meta = { version: 1, frames: pending.frames, frameTimeMs, scale: pending.scale, elytra: $("cape-elytra").checked };
    } else {
      const exported = await SlopViewer.cape.exportCape(pending.source, pending.state);
      if (exported.png.size > CAPE_MAX_BYTES) {
        throw new Error("Die fertige Cape ist größer als 8 MB: kleinere Auflösung wählen");
      }
      png = await readDataUrl(exported.png);
      meta = exported.meta;
    }
    const name = $("cape-name").value.trim() || "Cape";
    const old = pending.kind === "convert" ? pending.replaces : null;
    // A new cape is worn right away; an edited one only if the old one was.
    const activate = !old || mine.activeId === old.id;
    const cape = await api("PUT", "v1/capes", { name, png, meta, activate });
    if (pending.kind === "convert") await keepSource(cape.id, { file: pending.file, state: { ...pending.state }, name });
    if (!old) toast("Cape hochgeladen und angelegt");
    else {
      try {
        await api("DELETE", `v1/capes/${encodeURIComponent(old.id)}`);
        frameCache.delete(old.id);
        await dropSource(old.id);
        toast("Änderungen gespeichert");
      } catch {
        toast("Gespeichert, aber die alte Fassung ließ sich nicht löschen", "err");
      }
    }
    $("cape-file").value = "";
    $("cape-name").value = "";
    resetUpload();
    previewCape = cape.id;
    await loadCapes();
  } catch (e) {
    toast(e.message, "err");
    $("upload").disabled = false;
  }
}

$("cape-file").onchange = pickFile;
$("upload").onclick = doUpload;
$("upload-cancel").onclick = cancelUpload;
$("cape-elytra").onchange = () => previewCape === "upload" && showCape("upload");
$("cape-frame-time").oninput = () => previewCape === "upload" && showCape("upload");
bindEditor();
