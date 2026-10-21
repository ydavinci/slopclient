import * as THREE from "three";

/** One box of a hat or pet: corners in pixels, colour spec, glow. See cosmetics/extras.json. */
export type ExtraBox = [number, number, number, number, number, number, string, boolean?];

export interface ExtraModel {
  id: string;
  name: string;
  spin?: boolean;
  boxes: ExtraBox[];
}

export interface ExtrasCatalogue {
  hats: ExtraModel[];
  pets: ExtraModel[];
}

export interface ExtrasChoice {
  hat: string | null;
  pet: string | null;
}

function cssColor(name: string): string {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim() || "#8b5cf6";
}

/** Colour of a box; chroma boxes are tinted again every frame by `animateChroma`. */
function color(spec: string): THREE.Color {
  if (spec === "accent") return new THREE.Color(cssColor("--color-accent"));
  if (spec === "accent2") return new THREE.Color(cssColor("--color-accent-2"));
  if (spec === "chroma") return new THREE.Color().setHSL(0.75, 0.8, 0.62);
  return new THREE.Color(spec);
}

/**
 * The model as a three.js group in skinview3d's units (pixels, y up, face towards +z).
 * The mod's model space has y down and the face towards -z, hence the flips.
 */
export function buildExtra(model: ExtraModel): THREE.Group {
  const group = new THREE.Group();
  for (const [x1, y1, z1, x2, y2, z2, spec, glow] of model.boxes) {
    const geo = new THREE.BoxGeometry(Math.abs(x2 - x1), Math.abs(y2 - y1), Math.abs(z2 - z1));
    const mat = glow ? new THREE.MeshBasicMaterial({ color: color(spec) }) : new THREE.MeshLambertMaterial({ color: color(spec) });
    const mesh = new THREE.Mesh(geo, mat);
    mesh.position.set((x1 + x2) / 2, -(y1 + y2) / 2, -(z1 + z2) / 2);
    if (spec === "chroma") mesh.userData.chroma = true;
    group.add(mesh);
  }
  return group;
}

/** Shifts the hue of chroma boxes over time, like Theme.chroma in the mod. */
export function animateChroma(group: THREE.Object3D, t: number) {
  group.traverse((o) => {
    if (o instanceof THREE.Mesh && o.userData.chroma) (o.material as THREE.MeshBasicMaterial).color.setHSL((t / 6000) % 1, 0.8, 0.62);
  });
}

export function disposeExtra(group: THREE.Object3D) {
  group.traverse((o) => {
    if (o instanceof THREE.Mesh) {
      o.geometry.dispose();
      (o.material as THREE.Material).dispose();
    }
  });
}

/** Where the pet floats: beside the left shoulder (matches ExtrasLayer in the mod). */
export const PET_OFFSET = new THREE.Vector3(9.9, 5.1, -1.9);
export const PET_SCALE = 0.9;

// ---------- thumbnails ----------

let thumbRenderer: THREE.WebGLRenderer | null = null;
const thumbs = new Map<string, string>();

/** A small picture of the model, rendered once with a shared offscreen renderer. */
export function thumbnail(model: ExtraModel, kind: "hat" | "pet"): string {
  const key = `${kind}:${model.id}`;
  const cached = thumbs.get(key);
  if (cached) return cached;
  const size = 160;
  thumbRenderer ??= new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
  thumbRenderer.setSize(size, size, false);
  thumbRenderer.setClearColor(0x000000, 0);

  const scene = new THREE.Scene();
  scene.add(new THREE.AmbientLight(0xffffff, 0.75));
  const sun = new THREE.DirectionalLight(0xffffff, 0.9);
  sun.position.set(3, 5, 4);
  scene.add(sun);
  const group = buildExtra(model);
  // Hats are seen with a hint of the head below them.
  if (kind === "hat") {
    const head = new THREE.Mesh(new THREE.BoxGeometry(8, 8, 8), new THREE.MeshLambertMaterial({ color: 0xc9a68a }));
    head.position.set(0, 4, 0);
    group.add(head);
  }
  scene.add(group);

  const box = new THREE.Box3().setFromObject(group);
  const centre = box.getCenter(new THREE.Vector3());
  const radius = box.getSize(new THREE.Vector3()).length() / 2;
  const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 1000);
  const dir = new THREE.Vector3(0.9, 0.6, 1.4).normalize();
  camera.position.copy(centre).addScaledVector(dir, radius / Math.sin((15 * Math.PI) / 180) * 1.05);
  camera.lookAt(centre);
  thumbRenderer.render(scene, camera);
  const url = thumbRenderer.domElement.toDataURL("image/png");
  disposeExtra(group);
  thumbs.set(key, url);
  return url;
}
