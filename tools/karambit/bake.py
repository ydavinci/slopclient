"""Bakes the simplified Karambit GLB into what the client mod loads:

- karambit.mesh: "DKM1", int vertexCount, int indexCount, float pivot[3],
  float min[3], float max[3], then vertices (pos3, normal3, uv2 floats) and int indices.
  Little-endian. Positions are in item model space (one block = 0..1).
- karambit.png: metal (left half) and plastic (right half) albedo with AO baked in.
"""
import math, struct, sys
import numpy as np
from PIL import Image, ImageChops
from glb import load, primitives, image

src, out_mesh, out_png = sys.argv[1:4]
js, b = load(src)
prims = primitives(js, b)

# Knife profile lies in the source XZ plane, thickness along Y. The finger ring
# sits at +X, the blade tip at -X/+Z.
RING = np.array([0.0493, 0.0, 0.0070])
HANDLE_CENTER = np.array([0.0, 0.0, 0.0])
ANGLE = math.radians(20)   # tilts the handle so the tip ends up near the sword tip
SCALE = 5.0
GRIP = np.array([0.479, 0.30, 0.5])  # centres the knife in the item box


def to_item(p):
    q = p - HANDLE_CENTER
    u, v, w = -q[..., 0], q[..., 2], q[..., 1]
    c, s = math.cos(ANGLE), math.sin(ANGLE)
    ru, rv = u * c - v * s, u * s + v * c
    return np.stack([ru, rv, w], -1) * SCALE + GRIP


def dir_to_item(n):
    u, v, w = -n[..., 0], n[..., 2], n[..., 1]
    c, s = math.cos(ANGLE), math.sin(ANGLE)
    out = np.stack([u * c - v * s, u * s + v * c, w], -1)
    return out / (np.linalg.norm(out, axis=-1, keepdims=True) + 1e-12)


# ---- texture atlas: [metal | plastic], AO multiplied in ----
mats = js["materials"]
HALF = 512
atlas = Image.new("RGB", (HALF * 2, HALF))
for slot, mi in enumerate([0, 1]):
    m = mats[mi]
    albedo = image(js, b, m["pbrMetallicRoughness"]["baseColorTexture"]["index"]).convert("RGB").resize((HALF, HALF), Image.LANCZOS)
    occ = m.get("occlusionTexture")
    if occ:
        # glTF packs AO in the red channel of the occlusion texture.
        ao = image(js, b, occ["index"]).convert("RGB").resize((HALF, HALF), Image.LANCZOS).split()[0]
        ao = ao.point(lambda x: int(90 + x * (255 - 90) / 255))  # soften: never fully black
        albedo = ImageChops.multiply(albedo, Image.merge("RGB", (ao, ao, ao)))
    atlas.paste(albedo, (slot * HALF, 0))
atlas.save(out_png, optimize=True)

# ---- geometry ----
verts, inds = [], []
base = 0
for mesh, mat, p, n, uv, idx in prims:
    slot = 0 if mat == 0 else 1
    pos = to_item(p)
    nor = dir_to_item(n)
    uu = np.clip(uv[:, 0], 0, 1) * 0.5 + 0.5 * slot
    vv = np.clip(uv[:, 1], 0, 1)
    verts.append(np.c_[pos, nor, uu, vv].astype(np.float32))
    tri = idx.reshape(-1, 3)
    inds.append((tri + base).reshape(-1))
    base += len(p)
V = np.vstack(verts)
I = np.concatenate(inds).astype(np.int32)
pivot = to_item(RING)
lo, hi = V[:, :3].min(0), V[:, :3].max(0)
with open(out_mesh, "wb") as f:
    f.write(b"DKM1")
    f.write(struct.pack("<ii", len(V), len(I)))
    f.write(struct.pack("<9f", *pivot, *lo, *hi))
    f.write(V.tobytes())
    f.write(I.tobytes())
print("vertices", len(V), "triangles", len(I) // 3, "pivot", pivot.round(3), "bounds", lo.round(3), hi.round(3))
