import * as THREE from "three";
import { PlayerObject } from "skinview3d";

/** One box of a hat: corners in pixels, colour spec, glow. See cosmetics/extras.json. */
export type ExtraBox = [number, number, number, number, number, number, string, boolean?];

export interface HatModel {
  id: string;
  name: string;
  boxes: ExtraBox[];
}

/** A textured box (Minecraft box UV at `uv`) or a plain coloured one. */
export interface PetBox {
  from: [number, number, number];
  size: [number, number, number];
  uv?: [number, number];
  color?: string;
  glow?: boolean;
}

export interface PetPart {
  name: string;
  pivot: [number, number, number];
  anim?: string;
  glow?: boolean;
  boxes: PetBox[];
}

export type PetTier = "shoulder" | "flying" | "walking";

export interface PetModel {
  id: string;
  name: string;
  tier: PetTier;
  texture?: string;
  textureSize?: [number, number];
  scale?: number;
  spin?: boolean;
  /** "minime": a small copy of the player with their skin. */
  special?: "minime";
  parts: PetPart[];
}

/** Older catalogues and the hats still use plain box lists. */
export type ExtraModel = HatModel | PetModel;

export interface ExtrasCatalogue {
  hats: HatModel[];
  pets: PetModel[];
}

export interface ExtrasChoice {
  hat: string | null;
  pet: string | null;
}

/** Where a pet texture comes from: the launcher's copy of the server's catalogue (lib/petTextures.ts), or the server itself on the web page. */
type TextureSource = (file: string) => string | undefined | Promise<string | undefined>;
let textureSource: TextureSource = () => undefined;

/** Sets where pet textures are loaded from; call it before building pets. */
export function setTextureSource(source: TextureSource) {
  textureSource = source;
  textures.clear();
}

const textures = new Map<string, Promise<THREE.Texture>>();

function loadTexture(file: string): Promise<THREE.Texture> {
  let t = textures.get(file);
  if (!t) {
    t = Promise.resolve(textureSource(file)).then((url) => {
      if (!url) throw new Error(`missing pet texture ${file}`);
      return new THREE.TextureLoader().loadAsync(url).then((tex) => {
        tex.magFilter = THREE.NearestFilter;
        tex.minFilter = THREE.NearestFilter;
        tex.colorSpace = THREE.SRGBColorSpace;
        tex.flipY = false;
        return tex;
      });
    });
    textures.set(file, t);
  }
  return t;
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
 * A hat as a three.js group in skinview3d's units (pixels, y up, face towards +z).
 * The mod's model space has y down and the face towards -z: three = (x, -y, -z).
 */
export function buildHat(model: HatModel): THREE.Group {
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

// ---------- pets ----------

type V3 = [number, number, number];

/**
 * Faces of a box in Minecraft's box UV layout: texture rect and the four corners
 * (model space) that get its (u0,v0), (u1,v0), (u1,v1), (u0,v1). Same table as
 * tools/pets/build.py and Pets.java in the mod.
 */
function boxFaces(b: PetBox): { rect: [number, number, number, number]; corners: V3[]; normal: V3 }[] {
  const [x1, y1, z1] = b.from;
  const [w, h, d] = b.size;
  const [x2, y2, z2] = [x1 + w, y1 + h, z1 + d];
  const [u, v] = b.uv ?? [0, 0];
  return [
    { rect: [u + d, v, w, d], corners: [[x1, y1, z1], [x2, y1, z1], [x2, y1, z2], [x1, y1, z2]], normal: [0, -1, 0] },
    { rect: [u + d + w, v, w, d], corners: [[x1, y2, z2], [x2, y2, z2], [x2, y2, z1], [x1, y2, z1]], normal: [0, 1, 0] },
    { rect: [u, v + d, d, h], corners: [[x1, y1, z1], [x1, y1, z2], [x1, y2, z2], [x1, y2, z1]], normal: [-1, 0, 0] },
    { rect: [u + d, v + d, w, h], corners: [[x1, y1, z1], [x2, y1, z1], [x2, y2, z1], [x1, y2, z1]], normal: [0, 0, -1] },
    { rect: [u + d + w, v + d, d, h], corners: [[x2, y1, z2], [x2, y1, z1], [x2, y2, z1], [x2, y2, z2]], normal: [1, 0, 0] },
    { rect: [u + 2 * d + w, v + d, w, h], corners: [[x2, y1, z2], [x1, y1, z2], [x1, y2, z2], [x2, y2, z2]], normal: [0, 0, 1] },
  ];
}

function boxGeometry(b: PetBox, texSize: [number, number]): THREE.BufferGeometry {
  const pos: number[] = [], uv: number[] = [], nor: number[] = [], idx: number[] = [];
  for (const f of boxFaces(b)) {
    const [ru, rv, rw, rh] = f.rect;
    const uvs = [[ru, rv], [ru + rw, rv], [ru + rw, rv + rh], [ru, rv + rh]];
    const base = pos.length / 3;
    f.corners.forEach(([x, y, z], i) => {
      pos.push(x, -y, -z);
      uv.push(uvs[i][0] / texSize[0], uvs[i][1] / texSize[1]);
      nor.push(f.normal[0], -f.normal[1], -f.normal[2]);
    });
    idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute("normal", new THREE.Float32BufferAttribute(nor, 3));
  g.setIndex(idx);
  return g;
}

/** Pose of an animated part: rotation in radians (model axes), offset in pixels, y scale. */
export interface PartPose {
  rx: number;
  ry: number;
  rz: number;
  dy: number;
  sy: number;
}

/**
 * The animations, in game ticks (`t`) with the walk cycle (`walk`, `speed` 0..1).
 * Mirrors Pets.animate in the mod.
 */
export function animate(anim: string | undefined, t: number, walk: number, speed: number): PartPose {
  const p: PartPose = { rx: 0, ry: 0, rz: 0, dy: 0, sy: 1 };
  const swing = Math.sin(walk * 0.6662) * 0.9 * speed;
  switch (anim) {
    case "breathe":
      p.sy = 1 + Math.sin(t * 0.1) * 0.025;
      break;
    case "head":
      p.rx = Math.sin(t * 0.07) * 0.06;
      p.ry = Math.sin(t * 0.031) * 0.15;
      break;
    case "tail":
      p.ry = Math.sin(t * 0.12) * 0.35;
      break;
    case "wag":
      p.ry = Math.sin(t * 0.55) * 0.45;
      break;
    case "wingL":
      p.rz = Math.sin(t * 0.45) * 0.6;
      break;
    case "wingR":
      p.rz = -Math.sin(t * 0.45) * 0.6;
      break;
    case "buzzL":
      p.rz = Math.sin(t * 2.2) * 0.45;
      break;
    case "buzzR":
      p.rz = -Math.sin(t * 2.2) * 0.45;
      break;
    case "flipperL":
      p.rz = -0.12 - Math.sin(t * 0.18) * 0.12;
      break;
    case "flipperR":
      p.rz = 0.12 + Math.sin(t * 0.18) * 0.12;
      break;
    case "legA":
      p.rx = swing;
      break;
    case "legB":
      p.rx = -swing;
      break;
    case "bob":
      p.dy = Math.sin(t * 0.1) * 0.6;
      break;
    case "squish":
      p.sy = 1 + Math.sin(t * 0.15) * 0.07;
      break;
  }
  return p;
}

export interface BuiltPet {
  group: THREE.Group;
  /** Moves the animated parts; `t` in ticks. */
  update(t: number, walk: number, speed: number): void;
  /** The mini-me's player model, so its skin can follow the viewer's. */
  mini?: PlayerObject;
}

/** A pet as a three.js group (pixels, skinview3d axes), origin where it stands or floats. */
export async function buildPet(model: PetModel, skin?: THREE.Texture | null, slim?: boolean): Promise<BuiltPet> {
  const group = new THREE.Group();
  if (model.special === "minime") {
    const mini = new PlayerObject();
    mini.skin.modelType = slim ? "slim" : "default";
    if (skin) mini.skin.map = skin;
    mini.cape.visible = false;
    mini.elytra.visible = false;
    // Sitting: legs forward, arms resting on the knees.
    mini.skin.rightLeg.rotation.x = -Math.PI / 2;
    mini.skin.leftLeg.rotation.x = -Math.PI / 2;
    mini.skin.rightArm.rotation.x = -0.35;
    mini.skin.leftArm.rotation.x = -0.35;
    const s = 0.3;
    mini.scale.setScalar(s);
    // The hips are 4 px below the player object's origin (neck at +8, hips 12 below it): put them on the shoulder.
    mini.position.set(0, 4 * s, 0);
    group.add(mini);
    return {
      group,
      mini,
      update(t) {
        mini.skin.head.rotation.y = Math.sin(t * 0.03) * 0.3;
      },
    };
  }

  const texSize = model.textureSize ?? [64, 64];
  const texture = model.texture ? await loadTexture(model.texture) : null;
  const parts: { obj: THREE.Group; anim?: string; pivot: V3 }[] = [];
  for (const part of model.parts) {
    const pivot = new THREE.Group();
    const [px, py, pz] = part.pivot;
    pivot.position.set(px, -py, -pz);
    const inner = new THREE.Group();
    inner.position.set(-px, py, pz);
    pivot.add(inner);
    for (const b of part.boxes) {
      const glow = part.glow || b.glow;
      let mesh: THREE.Mesh;
      if (b.color) {
        const mat = glow ? new THREE.MeshBasicMaterial({ color: color(b.color) }) : new THREE.MeshLambertMaterial({ color: color(b.color) });
        mesh = new THREE.Mesh(boxGeometry({ ...b, uv: [0, 0] }, [1, 1]), mat);
        if (b.color === "chroma") mesh.userData.chroma = true;
      } else {
        const params = { map: texture, transparent: true, alphaTest: 0.1, side: THREE.DoubleSide };
        mesh = new THREE.Mesh(boxGeometry(b, texSize), glow ? new THREE.MeshBasicMaterial(params) : new THREE.MeshLambertMaterial(params));
      }
      inner.add(mesh);
    }
    group.add(pivot);
    parts.push({ obj: pivot, anim: part.anim, pivot: part.pivot });
  }
  const s = model.scale ?? 1;
  group.scale.setScalar(s);
  return {
    group,
    update(t, walk, speed) {
      for (const p of parts) {
        const pose = animate(p.anim, t, walk, speed);
        // Model axes to three's: y and z flip, so their rotations change sign.
        p.obj.rotation.set(pose.rx, -pose.ry, -pose.rz);
        p.obj.position.set(p.pivot[0], -p.pivot[1] - pose.dy, -p.pivot[2]);
        p.obj.scale.set(1, pose.sy, 1);
      }
      if (model.spin) group.rotation.set((-t * 1.3 * Math.PI) / 180, (-t * 2.4 * Math.PI) / 180, 0);
      animateChroma(group, t * 50);
    },
  };
}

/**
 * Where each tier sits relative to skinview3d's skin origin (the neck), in pixels:
 * the left shoulder, beside the head, or on the ground beside the player.
 * Matches PetsLayer in the mod.
 */
export const PET_PLACE: Record<PetTier, V3> = {
  shoulder: [6, 0, 0],
  flying: [13, 7, -3],
  walking: [-15, -24, -7],
};

/** Shifts the hue of chroma boxes over time, like Theme.chroma in the mod. */
export function animateChroma(group: THREE.Object3D, ms: number) {
  group.traverse((o) => {
    if (o instanceof THREE.Mesh && o.userData.chroma) (o.material as THREE.MeshBasicMaterial).color.setHSL((ms / 6000) % 1, 0.8, 0.62);
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

// ---------- thumbnails ----------

let thumbRenderer: THREE.WebGLRenderer | null = null;
const thumbs = new Map<string, Promise<string>>();

/** A small picture of the model, rendered once with a shared offscreen renderer. */
export function thumbnail(model: ExtraModel, kind: "hat" | "pet", skin?: () => HTMLCanvasElement): Promise<string> {
  const key = `${kind}:${model.id}`;
  let cached = thumbs.get(key);
  if (cached) return cached;
  cached = (async () => {
    const size = 160;
    thumbRenderer ??= new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
    thumbRenderer.setSize(size, size, false);
    thumbRenderer.setClearColor(0x000000, 0);

    const scene = new THREE.Scene();
    scene.add(new THREE.AmbientLight(0xffffff, 0.8));
    const sun = new THREE.DirectionalLight(0xffffff, 0.85);
    sun.position.set(3, 5, 4);
    scene.add(sun);
    let group: THREE.Group;
    if (kind === "hat") {
      group = buildHat(model as HatModel);
      // Hats are seen with a hint of the head below them.
      const head = new THREE.Mesh(new THREE.BoxGeometry(8, 8, 8), new THREE.MeshLambertMaterial({ color: 0xc9a68a }));
      head.position.set(0, 4, 0);
      group.add(head);
    } else {
      const pet = model as PetModel;
      const skinTex = pet.special === "minime" && skin ? new THREE.CanvasTexture(skin()) : null;
      if (skinTex) {
        skinTex.magFilter = THREE.NearestFilter;
        skinTex.colorSpace = THREE.SRGBColorSpace;
      }
      const built = await buildPet(pet, skinTex);
      built.update(20, 0, 0);
      group = built.group;
    }
    scene.add(group);

    const box = new THREE.Box3().setFromObject(group);
    const centre = box.getCenter(new THREE.Vector3());
    const radius = box.getSize(new THREE.Vector3()).length() / 2;
    const camera = new THREE.PerspectiveCamera(30, 1, 0.1, 1000);
    const dir = new THREE.Vector3(0.9, 0.55, 1.4).normalize();
    camera.position.copy(centre).addScaledVector(dir, (radius / Math.sin((15 * Math.PI) / 180)) * 1.02);
    camera.lookAt(centre);
    thumbRenderer.render(scene, camera);
    const url = thumbRenderer.domElement.toDataURL("image/png");
    disposeExtra(group);
    return url;
  })();
  thumbs.set(key, cached);
  return cached;
}
