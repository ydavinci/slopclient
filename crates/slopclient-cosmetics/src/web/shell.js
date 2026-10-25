// The page shell around app.js: the launcher's shader dusk, the Liquid Glass dock with
// its tabs, and the layout states (dock at the bottom; Studio and Konto as a side
// sheet). app.js and upload.js keep owning all data; this only arranges the stage.
"use strict";

// ---------- background: the launcher's flowing violet shader (launcher/src/ui/Shader.tsx) ----------

(function shader() {
  const canvas = document.getElementById("bg");
  const gl = canvas && canvas.getContext("webgl", { antialias: false, premultipliedAlpha: false });
  if (!gl) return; // the CSS gradient behind it stays
  const FRAG = `precision mediump float;
uniform vec2 r;uniform float t;
float h(vec2 p){return fract(sin(dot(p,vec2(127.1,311.7)))*43758.5453);}
float n(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}
float fbm(vec2 p){float v=0.,a=.5;for(int i=0;i<5;i++){v+=a*n(p);p*=2.02;a*=.5;}return v;}
void main(){vec2 uv=gl_FragCoord.xy/r;vec2 p=(gl_FragCoord.xy-.5*r)/r.y;float tt=t*.045;
vec2 q=vec2(fbm(p*1.5+tt),fbm(p*1.5-tt+5.2));float f=fbm(p*1.9+q*1.9+tt*.6);
vec3 col=vec3(.034,.03,.054);col=mix(col,vec3(.24,.11,.55),smoothstep(.38,.9,f));col=mix(col,vec3(.55,.17,.6),smoothstep(.55,1.,f*q.x*1.45));
float band=abs(p.y+.22*sin(p.x*2.1+tt*7.)+.12*q.y+.08);col+=vec3(.6,.45,1.)*.16*exp(-band*16.);
float band2=abs(p.y-.18*sin(p.x*1.6-tt*5.)-.1*q.x-.32);col+=vec3(.9,.4,.95)*.08*exp(-band2*22.);
col*=1.-.6*length(uv-vec2(.5,.45));gl_FragColor=vec4(max(col,0.),1.);}`;
  const compile = (type, src) => {
    const s = gl.createShader(type);
    gl.shaderSource(s, src);
    gl.compileShader(s);
    return s;
  };
  const prog = gl.createProgram();
  gl.attachShader(prog, compile(gl.VERTEX_SHADER, "attribute vec2 p;void main(){gl_Position=vec4(p,0.,1.);}"));
  gl.attachShader(prog, compile(gl.FRAGMENT_SHADER, FRAG));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) return;
  gl.useProgram(prog);
  gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
  const loc = gl.getAttribLocation(prog, "p");
  gl.enableVertexAttribArray(loc);
  gl.vertexAttribPointer(loc, 2, gl.FLOAT, false, 0, 0);
  const uRes = gl.getUniformLocation(prog, "r");
  const uTime = gl.getUniformLocation(prog, "t");
  const resize = () => {
    // Half resolution: it is a soft background, and it keeps the GPU cool.
    canvas.width = Math.max(1, Math.round(canvas.clientWidth * 0.5));
    canvas.height = Math.max(1, Math.round(canvas.clientHeight * 0.5));
    gl.viewport(0, 0, canvas.width, canvas.height);
    gl.uniform2f(uRes, canvas.width, canvas.height);
  };
  resize();
  new ResizeObserver(resize).observe(canvas);
  const start = performance.now() - 40000;
  const draw = (now) => {
    gl.uniform1f(uTime, (now - start) / 1000);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
  };
  draw(performance.now());
  if (matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  const loop = (now) => {
    if (document.visibilityState === "visible") draw(now);
    requestAnimationFrame(loop);
  };
  requestAnimationFrame(loop);
})();

// ---------- Liquid Glass: the lens at the rim (launcher/src/ui/LiquidGlass.tsx) ----------

/** Displacement map: red/green carry the x/y offset, strongest along the rounded rim. */
function lensMap(w, h, radius, bezel) {
  const c = document.createElement("canvas");
  c.width = w;
  c.height = h;
  const ctx = c.getContext("2d");
  const img = ctx.createImageData(w, h);
  const sdf = (x, y) => {
    const qx = Math.abs(x - w / 2) - (w / 2 - radius);
    const qy = Math.abs(y - h / 2) - (h / 2 - radius);
    return radius - (Math.hypot(Math.max(qx, 0), Math.max(qy, 0)) + Math.min(Math.max(qx, qy), 0));
  };
  for (let y = 0; y < h; y++) {
    for (let x = 0; x < w; x++) {
      const d = sdf(x + 0.5, y + 0.5);
      const i = (y * w + x) * 4;
      let dx = 0, dy = 0;
      if (d > 0 && d < bezel) {
        const nx = sdf(x - 0.5, y + 0.5) - sdf(x + 1.5, y + 0.5);
        const ny = sdf(x + 0.5, y - 0.5) - sdf(x + 0.5, y + 1.5);
        const len = Math.hypot(nx, ny) || 1;
        const t = 1 - d / bezel;
        const k = t * t * (3 - 2 * t);
        dx = (-nx / len) * k;
        dy = (-ny / len) * k;
      }
      img.data[i] = Math.round(128 + dx * 127);
      img.data[i + 1] = Math.round(128 + dy * 127);
      img.data[i + 2] = 128;
      img.data[i + 3] = 255;
    }
  }
  ctx.putImageData(img, 0, 0);
  return c.toDataURL();
}

// SVG filters in backdrop-filter work in Chromium only; elsewhere the plain blur stays.
const LENS = !!(navigator.userAgentData && navigator.userAgentData.brands.some((b) => /Chromium/.test(b.brand)));
let lensId = 0;

function liquid(el, { radius, bezel = 16, strength = 26 }) {
  const id = `lens${++lensId}`;
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("width", "0");
  svg.setAttribute("height", "0");
  svg.setAttribute("aria-hidden", "true");
  svg.style.position = "absolute";
  document.body.append(svg);
  let last = "";
  const build = () => {
    const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
    const r = typeof radius === "function" ? radius() : radius;
    const key = `${w}x${h}x${r}`;
    if (!LENS || w < 8 || h < 8 || key === last) return;
    last = key;
    const map = lensMap(w, h, Math.min(r, w / 2, h / 2), bezel);
    svg.innerHTML = `<filter id="${id}" x="0" y="0" width="${w}" height="${h}" filterUnits="userSpaceOnUse" color-interpolation-filters="sRGB">
      <feImage href="${map}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map"/>
      <feDisplacementMap in="SourceGraphic" in2="map" scale="${strength}" xChannelSelector="R" yChannelSelector="G" result="r"/>
      <feDisplacementMap in="SourceGraphic" in2="map" scale="${strength * 0.965}" xChannelSelector="R" yChannelSelector="G" result="g"/>
      <feDisplacementMap in="SourceGraphic" in2="map" scale="${strength * 0.93}" xChannelSelector="R" yChannelSelector="G" result="b"/>
      <feColorMatrix in="r" type="matrix" values="1 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 1 0" result="rr"/>
      <feColorMatrix in="g" type="matrix" values="0 0 0 0 0  0 1 0 0 0  0 0 0 0 0  0 0 0 1 0" result="gg"/>
      <feColorMatrix in="b" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 1 0 0  0 0 0 1 0" result="bb"/>
      <feBlend in="rr" in2="gg" mode="screen" result="rg"/><feBlend in="rg" in2="bb" mode="screen"/></filter>`;
    const f = `url(#${id}) blur(3px) saturate(180%) brightness(1.06)`;
    el.style.backdropFilter = f;
    el.style.webkitBackdropFilter = f;
  };
  new ResizeObserver(() => requestAnimationFrame(build)).observe(el);
  el.addEventListener("transitionend", build);
  // A light that follows the pointer over the glass.
  el.addEventListener("pointermove", (e) => {
    const r = el.getBoundingClientRect();
    el.style.setProperty("--lx", `${e.clientX - r.left}px`);
    el.style.setProperty("--ly", `${e.clientY - r.top}px`);
    el.style.setProperty("--lo", "1");
  });
  el.addEventListener("pointerleave", () => el.style.setProperty("--lo", "0"));
  build();
}

// ---------- the dock ----------

const app = document.getElementById("app");
const dock = document.getElementById("dock");
const drop = dock.querySelector(".drop");
const tabs = [...dock.querySelectorAll(".tab")];
const panels = [...dock.querySelectorAll(".panel")];
/** Tabs that turn the dock into a side sheet on wide screens. */
const SHEET_TABS = new Set(["studio", "account"]);
const sheetQuery = matchMedia("(min-width: 1100px)");
let current = "capes";

function sheetWidth() {
  return Math.min(620, Math.max(460, Math.round(innerWidth * 0.4)));
}

function moveDrop() {
  const tab = tabs.find((t) => t.dataset.tab === current);
  if (!tab || !tab.offsetWidth) return;
  drop.style.width = `${tab.offsetWidth}px`;
  drop.style.transform = `translateX(${tab.offsetLeft}px)`;
}

/** Room the dock takes at the bottom, so the player stands above it. */
function layout() {
  const sheet = SHEET_TABS.has(current) && sheetQuery.matches;
  app.classList.toggle("sheet", sheet);
  app.style.setProperty("--sheet-w", `${sheetWidth()}px`);
  if (!sheet) app.style.setProperty("--dock-space", `${dock.offsetHeight + 48}px`);
  moveDrop();
}

// app.js sizes the 3D preview on window resize: tell it whenever the player's box changes
// (dock growing, sheet opening, phone rotating), once per frame at most.
let resizeQueued = false;
new ResizeObserver(() => {
  if (resizeQueued) return;
  resizeQueued = true;
  requestAnimationFrame(() => {
    resizeQueued = false;
    dispatchEvent(new Event("resize"));
  });
}).observe(document.querySelector(".figure"));

function openPanel(name) {
  if (!tabs.some((t) => t.dataset.tab === name && !t.hidden)) return;
  current = name;
  for (const t of tabs) {
    const on = t.dataset.tab === name;
    t.setAttribute("aria-selected", String(on));
    t.tabIndex = on ? 0 : -1;
  }
  for (const p of panels) p.classList.toggle("is-active", p.dataset.panel === name);
  // app.js owns the preview (a global of its script); in the Studio the cape faces you.
  if (typeof preview !== "undefined" && preview && preview.faceCape) {
    if (name === "studio" || name === "capes") preview.faceCape();
    else preview.spin();
  }
  dock.querySelector(".panels").scrollTop = 0;
  layout();
  // Once the dock has morphed, measure again (lens map, room for the player).
  setTimeout(layout, 650);
}
window.openPanel = openPanel;

for (const t of tabs) t.addEventListener("click", () => openPanel(t.dataset.tab));
dock.querySelector(".tabs").addEventListener("keydown", (e) => {
  if (e.key !== "ArrowRight" && e.key !== "ArrowLeft") return;
  const visible = tabs.filter((t) => !t.hidden);
  const i = visible.findIndex((t) => t.dataset.tab === current);
  const next = visible[(i + (e.key === "ArrowRight" ? 1 : visible.length - 1)) % visible.length];
  openPanel(next.dataset.tab);
  next.focus();
});

// Hat, pet and accessory panels that app.js hides (empty on this server) lose their tab.
for (const p of panels.filter((p) => p.classList.contains("extras-group"))) {
  const tab = tabs.find((t) => t.dataset.tab === p.dataset.panel);
  new MutationObserver(() => {
    tab.hidden = p.hidden;
    if (p.hidden && current === p.dataset.panel) openPanel("capes");
    moveDrop();
  }).observe(p, { attributes: true, attributeFilter: ["hidden"] });
}

// The save pill shows while hat, pet or accessory differ from what is saved.
const saveButton = document.getElementById("save-extras");
const savebar = document.getElementById("savebar");
new MutationObserver(() => savebar.classList.toggle("show", !saveButton.disabled)).observe(saveButton, { attributes: true, attributeFilter: ["disabled"] });

// A cape waiting to be uploaded marks the Studio tab.
const studioDot = document.getElementById("studio-dot");
const capePreview = document.getElementById("cape-preview");
new MutationObserver(() => {
  studioDot.hidden = capePreview.hidden;
  if (!capePreview.hidden && current !== "studio") openPanel("studio");
}).observe(capePreview, { attributes: true, attributeFilter: ["hidden"] });

new ResizeObserver(() => layout()).observe(dock);
sheetQuery.addEventListener("change", layout);
addEventListener("resize", moveDrop);
// The app appears after sign-in: measure once it is visible.
new MutationObserver(() => !app.hidden && requestAnimationFrame(layout)).observe(app, { attributes: true, attributeFilter: ["hidden"] });

liquid(dock, { radius: () => (app.classList.contains("sheet") ? 26 : innerWidth < 760 ? 22 : 26), bezel: 18, strength: 28 });
for (const el of document.querySelectorAll(".liquid:not(#dock)")) {
  liquid(el, { radius: () => parseFloat(getComputedStyle(el).borderTopLeftRadius) || 16, bezel: 12, strength: 18 });
}
