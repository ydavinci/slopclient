"""Builds the wing accessories (angel and dragon) into cosmetics/extras.json ("accessories",
replaced by id) with their textures in cosmetics/pets/.

Accessory space (Accessories.java): pixels, origin at the neck, y up, the front of the body
faces +z, so the back is the plane z = -2. Each wing is two zero-thickness planes, the main
wing and a layer of coverts behind it, swept back from the shoulder blades; both sides share
one texture through mirrored UVs. Textures are drawn procedurally at 32 x 32 per layer.

Run:  python tools/accessories/wings.py
"""

import json
import math
import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "cosmetics" / "extras.json"
TEXTURES = ROOT / "cosmetics" / "pets"
N = 32  # texels per layer side


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))


def mix(a, b, t):
    t = max(0.0, min(1.0, t))
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def seg_dist(px, py, ax, ay, bx, by):
    """Distance from (px, py) to the segment a-b, and where along it (0..1) it is closest."""
    dx, dy = bx - ax, by - ay
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy)), t


# ---------- angel ----------


def leading_edge(s):
    """A point on the angel wing's leading edge, s from 0 (shoulder) to 1 (wrist)."""
    pts = [(0.5, 9.5), (8.0, 5.0), (15.0, 2.6), (22.0, 2.0)]
    s = max(0.0, min(1.0, s)) * (len(pts) - 1)
    i = min(int(s), len(pts) - 2)
    t = s - i
    return pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t


def feather(img, ax, ay, angle, length, radius, fill, tip, outline, rng):
    """Paints one feather: a capsule from (ax, ay) along `angle` (degrees, y down), slightly
    swollen in the middle, outlined on its outer side and round at the tip."""
    dx, dy = math.cos(math.radians(angle)), math.sin(math.radians(angle))
    bx, by = ax + dx * length, ay + dy * length
    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            d, t = seg_dist(px, py, ax, ay, bx, by)
            r = radius * (0.8 + 0.25 * math.sin(math.pi * min(1.0, t * 1.15)))
            if d > r:
                continue
            # Which side of the shaft: the outer side (towards +x) carries the outline.
            side = (px - ax) * dy - (py - ay) * dx
            c = mix(fill, tip, t ** 1.6)
            if (d > r * 0.62 and side < 0) or (t > 0.93 and d > r * 0.45):
                c = outline
            elif d < 0.35 and 0.1 < t < 0.85:
                c = mix(c, outline, 0.18)  # a faint shaft
            img.putpixel((x, y), (*mix(c, fill, rng.uniform(0, 0.06)), 255))


def angel_layers():
    """Main wing: thirteen feathers fanning down from the leading edge, the outer primaries
    longest and splayed outward; coverts: two rows of rounded feathers over their roots."""
    white, tip, edge = hexc("#ffffff"), hexc("#dfe6f4"), hexc("#a3b0cc")
    rng = random.Random(7)
    main = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    count = 10
    # Outer feathers first, so the inner ones overlap them as on a real wing; the primaries
    # are slimmer, so their tips stand apart.
    for k in reversed(range(count)):
        s = k / (count - 1)
        ax, ay = leading_edge(s)
        angle = 100 - 46 * s ** 1.2
        length = 13 + 12 * s ** 1.2
        feather(main, ax, ay, angle, length, 2.4 - 0.6 * s, white, tip, edge, rng)
    coverts = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    for row, (shift, length, radius) in enumerate(((4.2, 4.6, 1.7), (0.0, 5.5, 1.9))):
        n = 11 - row
        for k in reversed(range(n)):
            s = k / (n - 1) * 0.97
            ax, ay = leading_edge(s)
            angle = 100 - 46 * s ** 1.2
            ax += math.cos(math.radians(angle)) * shift
            ay += math.sin(math.radians(angle)) * shift
            feather(coverts, ax, ay - 0.6, angle, length, radius, white, mix(white, tip, 0.6), edge, rng)
    # The bony leading edge: a bright rounded ridge along the top.
    for k in range(60):
        x, y = leading_edge(k / 59)
        for oy in (-1, 0):
            xi, yi = int(x), int(y + oy)
            if 0 <= xi < N and 0 <= yi < N:
                coverts.putpixel((xi, yi), (*white, 255))
    return main, coverts


# ---------- dragon ----------


def dragon_layers():
    """Main wing: a membrane between four bone fingers with a scalloped trailing edge;
    coverts: the thick arm with its claw, laid over the top."""
    membrane, membrane_dark, vein = hexc("#6b2fa0"), hexc("#3f1766"), hexc("#8d52c4")
    bone, bone_dark, claw = hexc("#2a1840"), hexc("#160b24"), hexc("#efe6ff")
    root = (1.0, 12.0)
    tips = [(31.0, 3.0), (29.0, 16.0), (22.0, 28.0), (11.0, 30.0)]
    wrist = (18.0, 5.0)
    main = Image.new("RGBA", (N, N), (0, 0, 0, 0))

    def inside_membrane(x, y):
        # Between consecutive fingers, minus a circular bite at each trailing edge (the scallops).
        pts = [wrist] + tips
        for i in range(len(tips) - 1):
            a, b = tips[i], tips[i + 1]
            # Triangle wrist-a-b, plus root-wrist-last finger for the inner part.
            if point_in_tri(x, y, wrist, a, b):
                mx, my = (a[0] + b[0]) / 2, (a[1] + b[1]) / 2
                nx, ny = mx - wrist[0], my - wrist[1]
                ln = math.hypot(nx, ny)
                cx, cy = mx + nx / ln * 3.2, my + ny / ln * 3.2
                if math.hypot(x - cx, y - cy) < math.hypot(a[0] - b[0], a[1] - b[1]) / 2 * 0.62:
                    return False
                return True
        return point_in_tri(x, y, root, wrist, tips[-1]) and not math.hypot(x - 6, y - 26) < 6.5

    for y in range(N):
        for x in range(N):
            px, py = x + 0.5, y + 0.5
            if not inside_membrane(px, py):
                continue
            # Darker towards the edges, with thin veins fanning out from the wrist.
            d = math.hypot(px - wrist[0], py - wrist[1]) / 30
            c = mix(membrane, membrane_dark, d)
            ang = math.atan2(py - wrist[1], px - wrist[0])
            if abs(math.sin(ang * 9)) < 0.12 and d > 0.15:
                c = mix(c, vein, 0.6)
            main.putpixel((x, y), (*c, 255))
    # Bone fingers from the wrist to each tip, thicker near the wrist.
    for (tx, ty) in tips:
        for y in range(N):
            for x in range(N):
                d, t = seg_dist(x + 0.5, y + 0.5, wrist[0], wrist[1], tx, ty)
                if d <= 1.1 - 0.5 * t:
                    main.putpixel((x, y), (*mix(bone, bone_dark, t), 255))
    coverts = Image.new("RGBA", (N, N), (0, 0, 0, 0))
    # The arm: shoulder to wrist, then the thumb claw at the top of the wrist.
    for y in range(N):
        for x in range(N):
            d, t = seg_dist(x + 0.5, y + 0.5, root[0], root[1], wrist[0], wrist[1])
            if d <= 1.8 - 0.5 * t:
                c = mix(bone, bone_dark, 0.3 if d > 1.0 else 0)
                coverts.putpixel((x, y), (*c, 255))
            d2, t2 = seg_dist(x + 0.5, y + 0.5, wrist[0], wrist[1], wrist[0] + 3.5, wrist[1] - 4.5)
            if d2 <= 1.0 - 0.7 * t2:
                coverts.putpixel((x, y), (*mix(bone, claw, t2), 255))
    # A small claw at the end of every finger.
    for (tx, ty) in tips[:2]:
        for y in range(N):
            for x in range(N):
                d, t = seg_dist(x + 0.5, y + 0.5, tx - 1.5, ty + 0.5, tx + 0.5, ty - 1.2)
                if d <= 0.7:
                    coverts.putpixel((x, y), (*claw, 255))
    return main, coverts


def point_in_tri(x, y, a, b, c):
    def sign(p, q, r):
        return (p[0] - r[0]) * (q[1] - r[1]) - (q[0] - r[0]) * (p[1] - r[1])
    d1, d2, d3 = sign((x, y), a, b), sign((x, y), b, c), sign((x, y), c, a)
    neg = d1 < 0 or d2 < 0 or d3 < 0
    pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)


# ---------- model ----------


def plane(x0, x1, y0, y1, z, layer, outer_right, sweep, origin):
    """A zero-thickness plane at z with both faces; UVs put the shoulder at u 0 and the top
    at v 0 on either side of the body (`layer` 0 or 1 picks the half of the texture)."""
    u0, u1 = layer * N, layer * N + N
    # north: corner 0 sits at x1; south: corner 0 at x0. The shoulder is x0 on the right wing.
    if outer_right:
        north, south = [u1, 0, u0, N], [u0, 0, u1, N]
    else:
        north, south = [u0, 0, u1, N], [u1, 0, u0, N]
    return {
        "from": [x0, y0, z], "to": [x1, y1, z],
        "faces": {"north": {"uv": north}, "south": {"uv": south}},
        "rotation": {"origin": origin, "axis": "y", "angle": sweep},
    }


def wings(id, name, layers, size=18.0, sweep=30.0, top=5.5):
    main, coverts = layers
    sheet = Image.new("RGBA", (2 * N, N), (0, 0, 0, 0))
    sheet.paste(main, (0, 0))
    sheet.paste(coverts, (N, 0))
    TEXTURES.mkdir(parents=True, exist_ok=True)
    sheet.save(TEXTURES / f"{id}.png")
    elements = []
    bottom = top - size
    for side in (1, -1):
        root_x = 1.2 * side
        origin = [root_x, -3.0, -2.4]
        x0, x1 = (root_x, root_x + side * size)
        lo, hi = min(x0, x1), max(x0, x1)
        elements.append(plane(lo, hi, bottom, top, -2.45, 0, side > 0, sweep * side, origin))
        elements.append(plane(lo, hi, bottom, top, -2.7, 1, side > 0, sweep * side, origin))
    return {"id": id, "name": name, "attach": "body", "texture": f"{id}.png", "uvSize": [2 * N, N], "elements": elements}


def main():
    new = [wings("engelsfluegel", "Engelsflügel", angel_layers()), wings("drachenfluegel", "Drachenflügel", dragon_layers(), size=19.0, sweep=26.0, top=6.0)]
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    ids = {a["id"] for a in new}
    catalogue["accessories"] = [a for a in catalogue["accessories"] if a["id"] not in ids] + new
    CATALOGUE.write_text(json.dumps(catalogue, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")
    print(f"{len(new)} accessories written, {len(catalogue['accessories'])} in the catalogue")


if __name__ == "__main__":
    main()
