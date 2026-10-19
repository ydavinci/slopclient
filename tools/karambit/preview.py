import sys
import numpy as np
from PIL import Image
from glb import load, primitives, raster

js, b = load(sys.argv[1])
prims = primitives(js, b)
allp = np.vstack([p[2] for p in prims])
print("bounds", allp.min(0), allp.max(0))
for m, mat, p, n, uv, idx in prims:
    print("mesh", m, "mat", mat, "min", p.min(0).round(4), "max", p.max(0).round(4), "uv", uv.min(0).round(3), uv.max(0).round(3))

palette = [(200, 120, 255), (120, 200, 255), (255, 200, 90), (230, 230, 230)]
light = np.array([0.3, 0.5, 0.8]); light /= np.linalg.norm(light)
views = {"front_xy": (0, 1, 2), "side_zy": (2, 1, 0), "top_xz": (0, 2, 1)}
tiles = []
for name, (ax, ay, az) in views.items():
    T, D, C = [], [], []
    for m, mat, p, n, uv, idx in prims:
        tri = p[idx.reshape(-1, 3)]
        nrm = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
        nrm /= np.linalg.norm(nrm, axis=1, keepdims=True) + 1e-12
        l = np.abs(nrm @ np.roll(light, 0))
        T.append(tri[..., [ax, ay]]); D.append(tri[..., az])
        C.append((np.array(palette[m])[None] * (0.35 + 0.65 * l[:, None])).astype(np.uint8))
    T, D, C = np.vstack(T), np.vstack(D), np.vstack(C)
    lo = allp[:, [ax, ay]].min(0); hi = allp[:, [ax, ay]].max(0)
    tiles.append(raster(T, D, C, 420, (lo, hi)))
Image.fromarray(np.hstack(tiles)).save(sys.argv[2])
