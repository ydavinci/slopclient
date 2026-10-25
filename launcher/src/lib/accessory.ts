// Accessories (e.g. a katana at the hip): Java block model elements with per-face UVs and
// rotations, placed on the body or head. Same layout and maths as Accessories.java in the
// mod; the catalogue format is checked by slopclient-core extras.rs (validate_accessory).
import * as THREE from "three";
import { loadPetTexture } from "./extras";

type V3 = [number, number, number];
export type FaceName = "north" | "south" | "east" | "west" | "up" | "down";

export interface AccessoryFace {
  uv: [number, number, number, number];
  rotation?: 0 | 90 | 180 | 270;
}

export interface AccessoryElement {
  from: V3;
  to: V3;
  rotation?: { origin: V3; axis: "x" | "y" | "z"; angle: number };
  faces: Partial<Record<FaceName, AccessoryFace>>;
}

export interface AccessoryModel {
  id: string;
  name: string;
  /** Who made it (shown with the item). */
  credit?: string;
  attach: "body" | "head";
  texture: string;
  /** Size of the UV space; block models use 16 × 16 for the whole texture. */
  uvSize?: [number, number];
  /** Model point (pixels) that is placed at `position`. */
  pivot?: V3;
  /** Pixels in the body's (or head's) space: y up, the player faces +z, +x is the player's left, origin at the neck. */
  position?: V3;
  /** Degrees, applied X, then Y, then Z. */
  rotation?: V3;
  scale?: number;
  elements: AccessoryElement[];
}

/** Corners of each face in Minecraft's order (FaceInfo); vertex i gets UV corner i, shifted by the face's rotation. */
function corners(face: FaceName, [x0, y0, z0]: V3, [x1, y1, z1]: V3): V3[] {
  switch (face) {
    case "down":
      return [[x0, y0, z1], [x0, y0, z0], [x1, y0, z0], [x1, y0, z1]];
    case "up":
      return [[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]];
    case "north":
      return [[x1, y1, z0], [x1, y0, z0], [x0, y0, z0], [x0, y1, z0]];
    case "south":
      return [[x0, y1, z1], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1]];
    case "west":
      return [[x0, y1, z0], [x0, y0, z0], [x0, y0, z1], [x0, y1, z1]];
    case "east":
      return [[x1, y1, z1], [x1, y0, z1], [x1, y0, z0], [x1, y1, z0]];
  }
}

const NORMALS: Record<FaceName, V3> = { down: [0, -1, 0], up: [0, 1, 0], north: [0, 0, -1], south: [0, 0, 1], west: [-1, 0, 0], east: [1, 0, 0] };

/** UV of vertex `i` like BlockFaceUV: index shifted by the rotation, u from 0/1 → u0, v from 0/3 → v0. */
function uvAt(face: AccessoryFace, i: number): [number, number] {
  const j = (i + (face.rotation ?? 0) / 90) % 4;
  const [u0, v0, u1, v1] = face.uv;
  return [j === 0 || j === 1 ? u0 : u1, j === 0 || j === 3 ? v0 : v1];
}

function rotatePoint(p: V3, r: NonNullable<AccessoryElement["rotation"]>): V3 {
  const a = (r.angle * Math.PI) / 180, c = Math.cos(a), s = Math.sin(a);
  let [x, y, z] = [p[0] - r.origin[0], p[1] - r.origin[1], p[2] - r.origin[2]];
  if (r.axis === "x") [y, z] = [y * c - z * s, y * s + z * c];
  else if (r.axis === "y") [x, z] = [x * c + z * s, -x * s + z * c];
  else [x, y] = [x * c - y * s, x * s + y * c];
  return [x + r.origin[0], y + r.origin[1], z + r.origin[2]];
}

/** All faces of the model as one geometry, in model pixels (y up). */
export function accessoryGeometry(model: AccessoryModel): THREE.BufferGeometry {
  const [uw, vh] = model.uvSize ?? [16, 16];
  const pos: number[] = [], uv: number[] = [], nor: number[] = [], idx: number[] = [];
  for (const el of model.elements) {
    for (const [name, face] of Object.entries(el.faces) as [FaceName, AccessoryFace][]) {
      const base = pos.length / 3;
      let n = NORMALS[name];
      if (el.rotation) n = rotatePoint(n, { ...el.rotation, origin: [0, 0, 0] });
      corners(name, el.from, el.to).forEach((corner, i) => {
        const p = el.rotation ? rotatePoint(corner, el.rotation) : corner;
        pos.push(...p);
        const [u, v] = uvAt(face, i);
        uv.push(u / uw, v / vh);
        nor.push(...n);
      });
      idx.push(base, base + 1, base + 2, base, base + 2, base + 3);
    }
  }
  const g = new THREE.BufferGeometry();
  g.setAttribute("position", new THREE.Float32BufferAttribute(pos, 3));
  g.setAttribute("uv", new THREE.Float32BufferAttribute(uv, 2));
  g.setAttribute("normal", new THREE.Float32BufferAttribute(nor, 3));
  g.setIndex(idx);
  return g;
}

/**
 * The accessory as a group to add to skinview3d's skin (body) or head: placed by
 * pivot, position, rotation and scale. With `placed` false it is only centred on the
 * pivot (for thumbnails).
 */
export async function buildAccessory(model: AccessoryModel, placed = true): Promise<THREE.Group> {
  const texture = await loadPetTexture(model.texture);
  const mesh = new THREE.Mesh(accessoryGeometry(model), new THREE.MeshLambertMaterial({ map: texture, transparent: true, alphaTest: 0.1, side: THREE.DoubleSide }));
  const inner = new THREE.Group();
  const [px, py, pz] = model.pivot ?? [0, 0, 0];
  inner.position.set(-px, -py, -pz);
  inner.add(mesh);
  const group = new THREE.Group();
  group.add(inner);
  if (placed) {
    group.position.set(...(model.position ?? [0, 0, 0]));
    const [rx, ry, rz] = (model.rotation ?? [0, 0, 0]).map((d) => (d * Math.PI) / 180);
    group.rotation.set(rx, ry, rz, "XYZ");
    group.scale.setScalar(model.scale ?? 1);
  }
  return group;
}
