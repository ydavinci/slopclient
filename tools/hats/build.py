"""Builds the hats of collection 2 into cosmetics/extras.json ("hats"), replacing them by id
and leaving the older, hand-written hats as they are.

Hat space (see the catalogue's _comment): pixels, origin at the neck, the head spans
x -4..4, y -8..0 (up is minus), z -4..4, the face looks to minus z. A box is
[x1, y1, z1, x2, y2, z2, colour, glow?]; colours are #rrggbb, accent, accent2 or chroma.

Run:  python tools/hats/build.py
"""

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "cosmetics" / "extras.json"


def box(x1, y1, z1, x2, y2, z2, colour, glow=False):
    b = [round(min(x1, x2), 3), round(min(y1, y2), 3), round(min(z1, z2), 3),
         round(max(x1, x2), 3), round(max(y1, y2), 3), round(max(z1, z2), 3), colour]
    if glow:
        b.append(True)
    return b


def ramp(a, b, t):
    """Colour between two #rrggbb at t in 0..1."""
    ca = [int(a[i:i + 2], 16) for i in (1, 3, 5)]
    cb = [int(b[i:i + 2], 16) for i in (1, 3, 5)]
    return "#" + "".join(f"{round(ca[i] + (cb[i] - ca[i]) * t):02x}" for i in range(3))


def wizard_hat():
    """A tall indigo wizard's hat: wide brim, gold band and buckle, a cone that bends back
    and droops at the tip, scattered glowing stars and a crescent moon."""
    boxes = [box(-6.5, -8.6, -6.5, 6.5, -8.0, 6.5, "#1f2160")]
    boxes.append(box(-4.3, -9.8, -4.3, 4.3, -8.6, 4.3, "#e9b949"))
    boxes += [box(-0.9, -9.75, -4.55, 0.9, -8.65, -4.3, "#c48a1c"), box(-0.45, -9.45, -4.62, 0.45, -8.95, -4.45, "#1f2160")]
    # Cone layers: [half width, top y, z shift of the centre], bending back as it rises.
    layers = [(4.0, -11.4, 0.0), (3.4, -13.0, 0.3), (2.8, -14.6, 0.7), (2.2, -16.0, 1.2), (1.6, -17.2, 1.9), (1.1, -18.2, 2.7), (0.75, -19.0, 3.6)]
    bottom = -9.8
    spans = []
    for n, (half, top, dz) in enumerate(layers):
        colour = ramp("#3b3fa8", "#2a2c86", n / (len(layers) - 1))
        boxes.append(box(-half, top, -half + dz, half, bottom, half + dz, colour))
        spans.append((half, top, bottom, dz))
        bottom = top
    # The tip flops over backwards and down, with a star hanging from it.
    boxes.append(box(-0.6, -19.3, 4.0, 0.6, -18.5, 5.6, "#2a2c86"))
    boxes.append(box(-0.5, -18.8, 5.4, 0.5, -17.9, 6.4, "#2a2c86"))
    boxes.append(box(-0.45, -17.9, 5.9, 0.45, -17.0, 6.8, "#ffe27a", True))
    # Stars on the cone's faces: (layer, face, offset along the face).
    s = 0.35
    for n, face, off in ((0, "front", -2.4), (0, "front", 1.8), (1, "front", 0.9), (2, "front", -1.2), (3, "front", 0.4), (4, "front", -0.4),
                         (0, "right", -1.6), (2, "right", 1.0), (0, "left", 1.4), (1, "left", -0.8), (3, "left", 0.6),
                         (0, "back", 0.6), (2, "back", -1.0), (4, "back", 0.3)):
        half, top, bot, dz = spans[n]
        y = (top + bot) / 2
        if face == "front":
            z = -half + dz
            boxes.append(box(off - s, y - s, z - 0.2, off + s, y + s, z + 0.05, "#ffe27a", True))
        elif face == "back":
            z = half + dz
            boxes.append(box(off - s, y - s, z - 0.05, off + s, y + s, z + 0.2, "#ffe27a", True))
        else:
            x = half if face == "right" else -half
            out = 0.2 if face == "right" else -0.2
            boxes.append(box(x - out / 4, y - s, dz + off - s, x + out, y + s, dz + off + s, "#ffe27a", True))
    # A crescent moon on the right side of the cone.
    for (y1, y2, z1, z2) in ((-11.3, -9.9, -0.9, -0.5), (-11.6, -11.2, -0.5, 0.3), (-10.0, -9.6, -0.5, 0.3)):
        boxes.append(box(4.0, y1, z1, 4.25, y2, z2, "#fff4c2", True))
    return {"id": "zauberhut", "name": "Zauberhut", "boxes": boxes}


def flower_crown():
    """A crown of leaves around the top of the head, studded with flowers in five colours."""
    leaf, leaf_dark = "#5aa845", "#3f8431"
    boxes = []
    # The leafy ring: short alternating segments so it reads as woven leaves, not a band.
    edge = 4.35
    for i in range(10):
        a = -4.4 + i * 0.88
        colour = leaf if i % 2 == 0 else leaf_dark
        y = -8.9 if i % 2 == 0 else -8.7
        boxes.append(box(a, y, -edge - 0.3, a + 0.95, -7.9, -edge, colour))
        boxes.append(box(a, y, edge, a + 0.95, -7.9, edge + 0.3, colour))
        boxes.append(box(-edge - 0.3, y, a, -edge, -7.9, a + 0.95, colour))
        boxes.append(box(edge, y, a, edge + 0.3, -7.9, a + 0.95, colour))
    petals = ["#ff8fc4", "#fff6f0", "#b79cff", "#ffb38a", "#8ecbff", "#ff8fc4", "#fff6f0", "#b79cff"]
    centre = "#ffd23f"
    # (position along the ring, side): front/back flowers face out along z, side ones along x.
    spots = [(-2.8, "front"), (0.4, "front"), (3.0, "front"), (-1.8, "back"), (2.0, "back"), (-2.4, "left"), (1.6, "left"), (0.0, "right")]
    for (t, side), colour in zip(spots, petals):
        y = -8.9
        if side in ("front", "back"):
            z = -edge - 0.45 if side == "front" else edge + 0.15
            boxes.append(box(t - 0.4, y - 0.4, z, t + 0.4, y + 0.4, z + 0.3, centre))
            for dx, dy in ((-0.75, 0), (0.75, 0), (0, -0.75), (0, 0.75)):
                boxes.append(box(t + dx - 0.38, y + dy - 0.38, z + 0.05, t + dx + 0.38, y + dy + 0.38, z + 0.25, colour))
        else:
            x = -edge - 0.45 if side == "left" else edge + 0.15
            boxes.append(box(x, y - 0.4, t - 0.4, x + 0.3, y + 0.4, t + 0.4, centre))
            for dz, dy in ((-0.75, 0), (0.75, 0), (0, -0.75), (0, 0.75)):
                boxes.append(box(x + 0.05, y + dy - 0.38, t + dz - 0.38, x + 0.25, y + dy + 0.38, t + dz + 0.38, colour))
    # Two tiny buds on top for a little height.
    boxes.append(box(-3.4, -9.5, -4.6, -2.9, -8.9, -4.3, "#ff8fc4"))
    boxes.append(box(3.6, -9.5, 2.9, 4.1, -8.9, 3.4, "#fff6f0"))
    return {"id": "bluetenkranz", "name": "Blütenkranz", "boxes": boxes}


def headphones():
    """Studio headphones: padded band over the head, metal sliders, big ear cups with a
    glowing chroma ring and an accent logo."""
    band, pad, cup, cushion, metal = "#1d1d27", "#3a3a48", "#262633", "#41414f", "#b9bdc9"
    boxes = [
        box(-4.9, -9.4, -1.0, 4.9, -8.6, 1.0, band),
        box(-3.6, -8.85, -0.8, 3.6, -8.55, 0.8, pad),
    ]
    for s in (-1, 1):
        x_in, x_out = (4.2, 5.0)
        boxes.append(box(s * x_in, -9.0, -1.0, s * x_out, -5.6, 1.0, band))
        boxes.append(box(s * 4.15, -7.6, -0.7, s * 5.05, -6.7, 0.7, metal))
        # The cup, its cushion against the head, and the glowing ring on the outside.
        boxes.append(box(s * 4.2, -5.8, -2.3, s * 6.0, -1.2, 2.3, cup))
        boxes.append(box(s * 3.95, -5.3, -1.9, s * 4.25, -1.7, 1.9, cushion))
        o1, o2 = s * 6.0, s * 6.15
        boxes.append(box(o1, -5.4, -1.9, o2, -5.0, 1.9, "chroma", True))
        boxes.append(box(o1, -2.0, -1.9, o2, -1.6, 1.9, "chroma", True))
        boxes.append(box(o1, -5.0, -1.9, o2, -2.0, -1.5, "chroma", True))
        boxes.append(box(o1, -5.0, 1.5, o2, -2.0, 1.9, "chroma", True))
        boxes.append(box(s * 6.0, -3.9, -0.5, s * 6.1, -3.1, 0.5, "accent", True))
    return {"id": "kopfhoerer", "name": "Kopfhörer", "boxes": boxes}


def toadstool():
    """A fly agaric cap: a red dome in five steps over cream gills, with white spots."""
    boxes = [box(-5.2, -8.4, -5.2, 5.2, -8.0, 5.2, "#efe2c4")]
    layers = [(5.4, -9.4, -8.4), (5.0, -10.4, -9.4), (4.2, -11.2, -10.4), (3.0, -11.8, -11.2), (1.6, -12.2, -11.8)]
    for n, (half, top, bottom) in enumerate(layers):
        boxes.append(box(-half, top, -half, half, bottom, half, ramp("#c41d24", "#ea4b42", n / (len(layers) - 1))))
    spot = "#fbf6ee"
    # Spots on the sides of the lower rings...
    for x1, y1, x2, y2 in ((-3.4, -9.2, -2.2, -8.6), (1.0, -9.3, 2.6, -8.5)):
        boxes.append(box(x1, y1, -5.55, x2, y2, -5.4, spot))
        boxes.append(box(-x2, y1, 5.4, -x1, y2, 5.55, spot))
    boxes.append(box(-0.8, -10.2, -5.15, 0.6, -9.6, -5.0, spot))
    boxes.append(box(-0.4, -10.25, 5.0, 1.0, -9.65, 5.15, spot))
    for z1, z2, y1, y2 in ((-2.6, -1.2, -9.2, -8.6), (0.8, 2.4, -9.3, -8.5)):
        boxes.append(box(5.4, y1, z1, 5.55, y2, z2, spot))
        boxes.append(box(-5.55, y1, -z2, -5.4, y2, -z1, spot))
    boxes.append(box(5.0, -10.2, 1.4, 5.15, -9.6, 2.6, spot))
    boxes.append(box(-5.15, -10.25, -2.2, -5.0, -9.65, -1.0, spot))
    # ...and on the steps of the dome.
    for b in ((-4.0, -11.35, -1.0, -3.2, -11.2, 0.4), (3.2, -11.35, -2.0, 4.0, -11.2, -0.8), (-1.0, -11.35, 3.2, 0.6, -11.2, 4.0),
              (1.8, -11.95, -2.6, 2.8, -11.8, -1.7), (-2.7, -11.95, 1.7, -1.8, -11.8, 2.7), (-0.6, -12.35, -0.6, 0.6, -12.2, 0.6)):
        boxes.append(box(*b, spot))
    return {"id": "fliegenpilz", "name": "Fliegenpilz", "boxes": boxes}


def main():
    new = [wizard_hat(), flower_crown(), headphones(), toadstool()]
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    ids = {h["id"] for h in new}
    hats = [h for h in catalogue["hats"] if h["id"] not in ids] + new
    for h in new:
        assert len(h["boxes"]) <= 512, h["id"]
        for b in h["boxes"]:
            assert all(abs(v) <= 64 for v in b[:6]), (h["id"], b)
    catalogue["hats"] = hats
    CATALOGUE.write_text(json.dumps(catalogue, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")
    print(f"{len(new)} hats written, {len(hats)} in the catalogue")


if __name__ == "__main__":
    main()
