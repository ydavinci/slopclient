"""Tiny glTF/GLB reader plus a numpy rasterizer for previews."""
import io, json, struct
import numpy as np
from PIL import Image

COMP = {5126: ("<f4", 4), 5125: ("<u4", 4), 5123: ("<u2", 2), 5121: ("<u1", 1)}
NCOMP = {"SCALAR": 1, "VEC2": 2, "VEC3": 3, "VEC4": 4}


def load(path):
    data = open(path, "rb").read()
    magic, version, _ = struct.unpack_from("<III", data, 0)
    assert magic == 0x46546C67 and version == 2
    off, js, bin_ = 12, None, None
    while off < len(data):
        size, kind = struct.unpack_from("<II", data, off)
        chunk = data[off + 8 : off + 8 + size]
        if kind == 0x4E4F534A:
            js = json.loads(chunk)
        elif kind == 0x004E4942:
            bin_ = chunk
        off += 8 + size
    return js, bin_


def accessor(js, bin_, i):
    a = js["accessors"][i]
    v = js["bufferViews"][a["bufferView"]]
    dt, sz = COMP[a["componentType"]]
    n = NCOMP[a["type"]]
    start = v.get("byteOffset", 0) + a.get("byteOffset", 0)
    stride = v.get("byteStride", sz * n)
    raw = np.frombuffer(bin_, dtype=np.uint8, count=stride * (a["count"] - 1) + sz * n, offset=start)
    if stride == sz * n:
        out = np.frombuffer(raw.tobytes(), dtype=dt).reshape(a["count"], n)
    else:
        rows = np.lib.stride_tricks.as_strided(raw, shape=(a["count"], sz * n), strides=(stride, 1))
        out = np.frombuffer(rows.copy().tobytes(), dtype=dt).reshape(a["count"], n)
    return out.astype(np.float64 if dt == "<f4" else np.int64)


def image(js, bin_, i):
    img = js["images"][i]
    v = js["bufferViews"][img["bufferView"]]
    o = v.get("byteOffset", 0)
    return Image.open(io.BytesIO(bin_[o : o + v["byteLength"]]))


def node_matrix(n):
    if "matrix" in n:
        return np.array(n["matrix"], dtype=np.float64).reshape(4, 4).T  # column-major
    m = np.eye(4)
    if "scale" in n:
        m = np.diag([*n["scale"], 1.0]) @ m
    if "rotation" in n:
        x, y, z, w = n["rotation"]
        r = np.array([
            [1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w), 0],
            [2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w), 0],
            [2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y), 0],
            [0, 0, 0, 1]])
        m = r @ m
    if "translation" in n:
        t = np.eye(4)
        t[:3, 3] = n["translation"]
        m = t @ m
    return m


def primitives(js, bin_):
    """Yields (mesh index, material, positions, normals, uvs, indices) in world space."""
    out = []

    def walk(i, parent):
        n = js["nodes"][i]
        m = parent @ node_matrix(n)
        if "mesh" in n:
            for p in js["meshes"][n["mesh"]]["primitives"]:
                pos = accessor(js, bin_, p["attributes"]["POSITION"])
                nor = accessor(js, bin_, p["attributes"]["NORMAL"])
                uv = accessor(js, bin_, p["attributes"]["TEXCOORD_0"])
                idx = accessor(js, bin_, p["indices"]).reshape(-1)
                pw = (m @ np.c_[pos, np.ones(len(pos))].T).T[:, :3]
                nm = np.linalg.inv(m[:3, :3]).T
                nw = (nm @ nor.T).T
                nw /= np.linalg.norm(nw, axis=1, keepdims=True) + 1e-12
                out.append((n["mesh"], p.get("material", 0), pw, nw, uv, idx))
        for c in n.get("children", []):
            walk(c, m)

    for root in js["scenes"][js.get("scene", 0)]["nodes"]:
        walk(root, np.eye(4))
    return out


def raster(tris_xy, depth, colors, size, bounds):
    """Flat z-buffered rasterizer. tris_xy: (T,3,2) in world units, depth: (T,3)."""
    (x0, y0), (x1, y1) = bounds
    scale = (size - 20) / max(x1 - x0, y1 - y0)
    img = np.full((size, size, 3), 24, np.uint8)
    zb = np.full((size, size), -1e9)
    pts = (tris_xy - [x0, y0]) * scale + 10
    pts[..., 1] = size - pts[..., 1]
    for t in range(len(pts)):
        a, b, c = pts[t]
        minx, miny = np.floor(np.minimum(np.minimum(a, b), c)).astype(int)
        maxx, maxy = np.ceil(np.maximum(np.maximum(a, b), c)).astype(int)
        minx, miny = max(minx, 0), max(miny, 0)
        maxx, maxy = min(maxx, size - 1), min(maxy, size - 1)
        if maxx < minx or maxy < miny:
            continue
        xs, ys = np.meshgrid(np.arange(minx, maxx + 1) + 0.5, np.arange(miny, maxy + 1) + 0.5)
        d = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
        if abs(d) < 1e-9:
            continue
        w0 = ((b[1] - c[1]) * (xs - c[0]) + (c[0] - b[0]) * (ys - c[1])) / d
        w1 = ((c[1] - a[1]) * (xs - c[0]) + (a[0] - c[0]) * (ys - c[1])) / d
        w2 = 1 - w0 - w1
        inside = (w0 >= 0) & (w1 >= 0) & (w2 >= 0)
        z = w0 * depth[t, 0] + w1 * depth[t, 1] + w2 * depth[t, 2]
        yy, xx = ys.astype(int), xs.astype(int)
        sub = zb[miny : maxy + 1, minx : maxx + 1]
        win = inside & (z > sub)
        sub[win] = z[win]
        img[miny : maxy + 1, minx : maxx + 1][win] = colors[t]
    return img
