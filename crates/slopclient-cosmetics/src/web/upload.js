// Cape upload on the web page. A finished cape texture (PNG in the cape layout, frames
// stacked) goes up as it is; any other picture or animation (GIF, WebP, APNG, JPG, PNG)
// is fitted into the cape like the launcher's Cape Studio does (launcher/src/lib/cape.ts,
// shipped in viewer.js as SlopViewer.cape). Loaded after common.js, before app.js.
"use strict";

const SCALES = [1, 2, 4, 8, 16];
const MAX_FRAMES = 128;
/** Largest source file; the finished cape must stay under the server's 8 MB. */
const SOURCE_MAX_BYTES = 30 * 1024 * 1024;
const CAPE_MAX_BYTES = 8 * 1024 * 1024;

/**
 * The pending upload:
 * { kind: "sheet", image, dataUrl, scale, frames } for a finished cape texture,
 * { kind: "convert", source, state, edge, rendered, name } for a converted picture.
 */
let upload = null;
let renderTimer = 0;

function readDataUrl(blob) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error);
    reader.readAsDataURL(blob);
  });
}

function resetUpload() {
  upload = null;
  clearTimeout(renderTimer);
  $("upload").disabled = true;
  $("cape-preview").hidden = true;
  $("cape-edit").hidden = true;
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
  const state = SlopViewer.cape.defaultState(source);
  upload = { kind: "convert", source, state, edge: SlopViewer.cape.edgeColor(source.frames[0]), rendered: [] };
  fillEditor(source, state);
  $("frame-time-box").hidden = true;
  $("cape-preview").hidden = false;
  $("cape-edit").hidden = false;
  renderConverted();
}

/** Puts the Cape Studio defaults into the controls. */
function fillEditor(source, state) {
  const scales = $("edit-scale");
  scales.replaceChildren(...SlopViewer.cape.SCALES.map((s) => new Option(`${64 * s} × ${32 * s}`, String(s))));
  scales.value = String(state.scale);
  $("edit-fit").value = state.fit;
  $("edit-back").value = state.back;
  $("edit-edge").value = state.edge;
  $("edit-zoom").value = String(state.zoom);
  $("edit-x").value = String(state.offsetX);
  $("edit-y").value = String(state.offsetY);
  $("edit-speed").value = String(state.speed);
  $("edit-speed-box").hidden = source.frames.length < 2;
  $("cape-elytra").checked = state.elytra;
}

/** Reads the controls into the state and renders again (debounced while dragging). */
function editChanged() {
  if (!upload || upload.kind !== "convert") return;
  const st = upload.state;
  st.scale = Number($("edit-scale").value);
  st.fit = $("edit-fit").value;
  st.back = $("edit-back").value;
  st.edge = $("edit-edge").value;
  st.zoom = Number($("edit-zoom").value);
  st.offsetX = Number($("edit-x").value);
  st.offsetY = Number($("edit-y").value);
  st.speed = Number($("edit-speed").value);
  st.elytra = $("cape-elytra").checked;
  clearTimeout(renderTimer);
  renderTimer = setTimeout(renderConverted, 60);
}

function renderConverted() {
  if (!upload || upload.kind !== "convert") return;
  const { source, state, edge } = upload;
  const { frames, thinned } = SlopViewer.cape.activeFrames(source, state);
  upload.rendered = frames.map((f) => SlopViewer.cape.renderFrame(f, state, edge));
  drawCapeFront($("cape-canvas"), upload.rendered[0], state.scale);
  const n = upload.rendered.length;
  const parts = [`${source.width} × ${source.height} px`, `${n} ${n > 1 ? "Bilder" : "Bild"}`];
  if (n > 1) parts.push(`${SlopViewer.cape.frameTime(source, state)} ms je Bild`);
  if (source.sampled || thinned) parts.push("Bilder ausgedünnt");
  $("cape-info").textContent = `Wird zur Cape umgewandelt · ${parts.join(" · ")}`;
  $("upload").disabled = false;
  showCape("upload");
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

async function doUpload() {
  if (!upload) return;
  $("upload").disabled = true;
  try {
    let png, meta;
    if (upload.kind === "sheet") {
      png = upload.dataUrl;
      const frameTimeMs = upload.frames > 1 ? Math.max(40, Number($("cape-frame-time").value) || 100) : 0;
      meta = { version: 1, frames: upload.frames, frameTimeMs, scale: upload.scale, elytra: $("cape-elytra").checked };
    } else {
      const exported = await SlopViewer.cape.exportCape(upload.source, upload.state);
      if (exported.png.size > CAPE_MAX_BYTES) {
        throw new Error("Die fertige Cape ist größer als 8 MB: kleinere Auflösung wählen");
      }
      png = await readDataUrl(exported.png);
      meta = exported.meta;
    }
    const cape = await api("PUT", "v1/capes", { name: $("cape-name").value.trim() || "Cape", png, meta, activate: true });
    toast("Cape hochgeladen und angelegt");
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
for (const id of ["edit-scale", "edit-fit", "edit-back", "edit-edge", "edit-zoom", "edit-x", "edit-y", "edit-speed"]) $(id).oninput = editChanged;
$("cape-elytra").onchange = () => (upload && upload.kind === "convert" ? editChanged() : previewCape === "upload" && showCape("upload"));
$("cape-frame-time").oninput = () => previewCape === "upload" && showCape("upload");
