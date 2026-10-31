"""Builds the pets of SlopClient: models (cosmetics/extras.json, "pets") and their
pixel textures (cosmetics/pets/<id>.png).

Model space is the one of the client mod: pixels (1/16 block), y points down
(minus is up), the pet looks towards minus z, the origin is where the pet stands
(shoulder and walking pets) or its middle (flying pets).

Every textured box uses Minecraft's box UV layout at its "uv" corner:
    top    (u+d,     v)      d x w   (u -> +x, v -> +z)
    bottom (u+d+w,   v)      d x w   (u -> +x, v -> -z)
    -x     (u,       v+d)    h x d   (u -> +z, v -> +y)
    front  (u+d,     v+d)    h x w   (u -> +x, v -> +y)   front = minus z
    +x     (u+d+w,   v+d)    h x d   (u -> -z, v -> +y)
    back   (u+2d+w,  v+d)    h x w   (u -> -x, v -> +y)
The mod (cosmetics/Pets.java) and the launcher (lib/extras.ts) map faces the same way.

Run:  python tools/pets/build.py
"""

import json
import random
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = ROOT / "cosmetics" / "pets"
CATALOGUE = ROOT / "cosmetics" / "extras.json"

FACES = ("top", "bottom", "west", "front", "east", "back")


def hexc(s):
    s = s.lstrip("#")
    return tuple(int(s[i : i + 2], 16) for i in (0, 2, 4))


def shade(c, f):
    return tuple(max(0, min(255, round(v * f))) for v in c)


def mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


class Texture:
    def __init__(self, seed, w=64, h=64):
        self.img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
        self.w, self.h = w, h
        self.x = self.y = self.row = 0
        self.rng = random.Random(seed)

    def alloc(self, bw, bh):
        if self.x + bw > self.w:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        if self.y + bh > self.h:
            # Grow downwards (keeps already placed boxes).
            grown = Image.new("RGBA", (self.w, self.h * 2), (0, 0, 0, 0))
            grown.paste(self.img, (0, 0))
            self.img, self.h = grown, self.h * 2
        u, v = self.x, self.y
        self.x += bw
        self.row = max(self.row, bh)
        return u, v

    def px(self, x, y, c, a=255):
        if 0 <= x < self.w and 0 <= y < self.h:
            self.img.putpixel((x, y), (*c, a))


def face_rects(u, v, w, h, d):
    return {
        "top": (u + d, v, w, d),
        "bottom": (u + d + w, v, w, d),
        "west": (u, v + d, d, h),
        "front": (u + d, v + d, w, h),
        "east": (u + d + w, v + d, d, h),
        "back": (u + 2 * d + w, v + d, w, h),
    }


class Face:
    """Painter view of one face: (i, j) with i along u and j along v."""

    def __init__(self, tex, rect, name):
        self.tex, self.name = tex, name
        self.x0, self.y0, self.w, self.h = rect

    def set(self, i, j, c, a=255):
        if 0 <= i < self.w and 0 <= j < self.h:
            self.tex.px(self.x0 + i, self.y0 + j, c, a)

    def fill(self, c, noise=0.05, a=255):
        for j in range(self.h):
            for i in range(self.w):
                self.set(i, j, shade(c, 1 + self.tex.rng.uniform(-noise, noise)), a)

    def rect(self, i0, j0, w, h, c):
        for j in range(j0, j0 + h):
            for i in range(i0, i0 + w):
                self.set(i, j, c)

    def mirror(self, i):
        return self.w - 1 - i


class Pet:
    def __init__(self, id, name, tier, seed=1, scale=1.0, tex=(64, 64), **extra):
        self.id, self.name, self.tier, self.scale = id, name, tier, scale
        self.tex = Texture(seed, *tex)
        self.parts = []
        self.extra = extra

    def part(self, name, pivot=(0, 0, 0), anim=None, glow=False):
        p = {"name": name, "pivot": list(pivot), "boxes": []}
        if anim:
            p["anim"] = anim
        if glow:
            p["glow"] = True
        self.parts.append(p)
        return p

    def box(self, part, frm, size, base, paint=None, noise=0.06, edge=0.88, faces=None):
        """A textured box: every face filled with `base` (or faces[name]), slightly
        noisy and darker at the bottom edge, then `paint(face)` adds details."""
        w, h, d = size
        u, v = self.tex.alloc(2 * d + 2 * w, d + h)
        for name, rect in face_rects(u, v, w, h, d).items():
            f = Face(self.tex, rect, name)
            col = hexc((faces or {}).get(name, base))
            f.fill(col, noise)
            if name not in ("top", "bottom") and f.h > 1:
                for i in range(f.w):
                    f.set(i, f.h - 1, shade(col, edge))
            if paint:
                paint(f)
        part["boxes"].append({"from": list(frm), "size": list(size), "uv": [u, v]})

    def color_box(self, part, frm, size, color, glow=False):
        b = {"from": list(frm), "size": list(size), "color": color}
        if glow:
            b["glow"] = True
        part["boxes"].append(b)

    def save(self):
        OUT_DIR.mkdir(parents=True, exist_ok=True)
        self.tex.img.save(OUT_DIR / f"{self.id}.png")
        out = {"id": self.id, "name": self.name, "tier": self.tier, "texture": f"{self.id}.png",
               "textureSize": [self.tex.w, self.tex.h], "parts": self.parts}
        if self.scale != 1:
            out["scale"] = self.scale
        out.update(self.extra)
        return out


# ---------- small painting helpers ----------

BLACK = hexc("#141018")
WHITE = hexc("#ffffff")


def eyes(f, row, gap, iris=None, size=1, glint=True):
    """Two eyes on a front face, `gap` pixels apart from the middle."""
    mid = f.w / 2
    for side in (-1, 1):
        i0 = int(mid + side * gap - (size if side < 0 else 0))
        for di in range(size):
            for dj in range(size):
                f.set(i0 + di, row + dj, iris or BLACK)
        if glint:
            f.set(i0 + (0 if side < 0 else size - 1), row, WHITE)


def stripes(f, color, every=2, offset=0):
    for j in range(f.h):
        if (j + offset) % every == 0:
            for i in range(f.w):
                f.set(i, j, color)


def speckle(f, color, chance, rng):
    for j in range(f.h):
        for i in range(f.w):
            if rng.random() < chance:
                f.set(i, j, color)


def gradient(f, top, bottom, noise=0.04):
    """Fills a face from `top` to `bottom` colour along v (sides) or flat with the mean (top/bottom)."""
    rng = f.tex.rng
    for j in range(f.h):
        t = j / max(1, f.h - 1) if f.name not in ("top", "bottom") else 0.35 if f.name == "top" else 0.9
        c = mix(top, bottom, t)
        for i in range(f.w):
            f.set(i, j, shade(c, 1 + rng.uniform(-noise, noise)))


def outline_bottom(f, c):
    """A darker last row on side faces, so stacked boxes read as separate."""
    if f.name not in ("top", "bottom") and f.h > 1:
        for i in range(f.w):
            f.set(i, f.h - 1, c)


# ---------- the pets ----------


def minime():
    # Drawn by the mod with the player's own model and skin; no texture of its own.
    return {"id": "minime", "name": "Mini-Me", "tier": "shoulder", "special": "minime", "parts": []}


def hedgehog():
    p = Pet("igel", "Igel", "shoulder", seed=3)
    spine, tip, skin = "#5b3b27", hexc("#b48a5e"), "#e7c9a1"
    body = p.part("body", anim="breathe")

    def spines(f):
        if f.name == "bottom":
            return
        rng = p.tex.rng
        for j in range(f.h):
            for i in range(f.w):
                if (i + j * 2) % 3 == 0 or rng.random() < 0.18:
                    f.set(i, j, shade(tip, rng.uniform(0.85, 1.1)))
        if f.name == "front":
            f.rect(0, f.h - 1, f.w, 1, shade(hexc(spine), 0.8))

    p.box(body, (-3, -4.5, -3), (6, 4, 7), spine, spines, faces={"bottom": skin})
    # Spine tufts on the back.
    for x, z in ((-2, -1), (1, -1), (-1, 1), (1, 2), (-2, 2.5)):
        p.box(body, (x, -5.5, z), (1, 1, 1), spine, lambda f: f.set(0, 0, tip))
    head = p.part("head", pivot=(0, -2, -3), anim="head")

    def face(f):
        if f.name == "front":
            eyes(f, 0, 1.0)
    p.box(head, (-2, -3.5, -5), (4, 3, 2), skin, face)

    def nose(f):
        if f.name == "front":
            f.set(0, 0, BLACK)
            f.set(1, 0, BLACK)
    p.box(head, (-1, -2.5, -6), (2, 1, 1), skin, nose)
    for x, z in ((-2.5, -2), (1.5, -2), (-2.5, 2.5), (1.5, 2.5)):
        p.box(body, (x, -0.5, z), (1, 1, 1), "#c9a47c")
    return p.save()


def cat():
    p = Pet("katze", "Katze", "shoulder", seed=5)
    fur, stripe, white, pink = "#e98f3b", hexc("#b25e1e"), "#f6efe6", hexc("#f2a3b7")
    body = p.part("body", anim="breathe")

    def tabby(f):
        if f.name in ("east", "west", "back", "top"):
            stripes(f, stripe, every=3, offset=1)
        if f.name == "front":
            f.rect(1, 1, f.w - 2, f.h - 1, hexc(white))
    p.box(body, (-2, -5, -1.5), (4, 5, 3), fur, tabby)
    for x in (-1.5, 0.5):
        p.box(body, (x, -1.5, -2.5), (1, 2, 1), white)
    head = p.part("head", pivot=(0, -5, 0), anim="head")

    def face(f):
        if f.name == "front":
            f.rect(1, 2, f.w - 2, 2, hexc(white))
            for i in (1, f.w - 2):
                f.set(i, 1, hexc("#7cd65a"))
            f.set(f.w // 2 - 1, 2, pink)
            f.set(f.w // 2, 2, pink)
        elif f.name in ("top", "east", "west"):
            stripes(f, stripe, every=2)
    p.box(head, (-2.5, -9, -3), (5, 4, 4), fur, face)
    for x in (-2.5, 1.5):
        p.box(head, (x, -10, -1.5), (1, 1, 1), fur, lambda f: f.name == "front" and f.set(0, 0, pink))
    tail = p.part("tail", pivot=(0, -1, 1.5), anim="tail")
    p.box(tail, (-0.5, -1.5, 1.5), (1, 1, 5), fur, lambda f: f.name != "top" or stripes(f, stripe, 2))
    return p.save()


def frog():
    p = Pet("frosch", "Frosch", "shoulder", seed=7)
    green, dark, belly = "#5fae3c", hexc("#3b7a24"), "#d9e37a"
    body = p.part("body", anim="breathe")

    def spots(f):
        if f.name == "front":
            f.rect(1, f.h - 2, f.w - 2, 1, hexc("#2a4d1a"))
        elif f.name != "bottom":
            speckle(f, dark, 0.22, p.tex.rng)
    p.box(body, (-3, -3, -3), (6, 3, 6), green, spots, faces={"bottom": belly})

    def eye(f):
        if f.name == "front":
            f.rect(0, 0, 2, 2, hexc("#f4f2d8"))
            f.set(1 if f.w > 1 else 0, 1, BLACK)
    for x in (-3, 1):
        p.box(body, (x, -5, -3), (2, 2, 2), "#6dbd47", eye)
    for side in (-1, 1):
        leg = p.part("leg", pivot=(side * 3, -1, 1))
        p.box(leg, (side * 3 - (1 if side < 0 else 0) - (0 if side < 0 else 0), -1.5, -0.5), (1, 2, 4), green,
              lambda f: speckle(f, dark, 0.2, p.tex.rng))
    return p.save()


def penguin():
    p = Pet("pinguin", "Pinguin", "shoulder", seed=9)
    black, white, orange = "#1c1d26", "#f2f4f7", "#f39c2b"
    body = p.part("body", anim="breathe")

    def belly(f):
        if f.name == "front":
            f.rect(1, 2, f.w - 2, f.h - 2, hexc(white))
            eyes(f, 1, 1.0, size=1)
    p.box(body, (-2, -6, -2), (4, 6, 4), black, belly)
    p.box(body, (-1, -4.5, -3), (2, 1, 1), orange)
    for x in (-1.5, 0.5):
        p.box(body, (x, -0.5, -2.5), (1, 1, 2), orange)
    for side, anim in ((-1, "flipperR"), (1, "flipperL")):
        fl = p.part("flipper", pivot=(side * 2, -5, 0), anim=anim)
        p.box(fl, (side * 2 - (1 if side < 0 else 0), -5, -1), (1, 3, 2), black)
    return p.save()


def chick():
    p = Pet("kueken", "Küken", "shoulder", seed=11)
    yellow, orange = "#ffd84a", "#f08a24"
    body = p.part("body", anim="breathe")

    def fluff(f):
        speckle(f, hexc("#ffe98f"), 0.25, p.tex.rng)
        if f.name == "front":
            eyes(f, 1, 1.0)
    p.box(body, (-2, -4, -2), (4, 4, 4), yellow, fluff)
    p.box(body, (-0.5, -2.5, -3), (1, 1, 1), orange)
    for x in (-1.5, 0.5):
        p.box(body, (x, -0.5, -1), (1, 1, 1), orange)
    for side, anim in ((-1, "flipperR"), (1, "flipperL")):
        w = p.part("wing", pivot=(side * 2, -3, 0), anim=anim)
        p.box(w, (side * 2 - (1 if side < 0 else 0) + (0 if side < 0 else 0), -3, -1), (1, 2, 3), "#f7c93c")
    return p.save()


def slime():
    p = Pet("slime", "Mini-Slime", "shoulder", seed=13)
    green = "#62d25c"
    body = p.part("body", anim="squish")

    def jelly(f):
        rng = p.tex.rng
        for j in range(f.h):
            for i in range(f.w):
                edge = i in (0, f.w - 1) or j in (0, f.h - 1)
                f.set(i, j, shade(hexc(green), (1.12 if edge else 0.92) + rng.uniform(-0.04, 0.04)))
        if f.name == "front":
            f.rect(1, 2, 2, 2, BLACK)
            f.rect(f.w - 3, 2, 2, 2, BLACK)
            f.set(1, 2, WHITE)
            f.set(f.w - 3, 2, WHITE)
            f.rect(f.w // 2 - 1, f.h - 2, 2, 1, hexc("#1f4a1c"))
    p.box(body, (-3, -6, -3), (6, 6, 6), green, jelly)
    return p.save()


def dragon():
    p = Pet("drache", "Mini-Drache", "flying", seed=21)
    purple, scale, belly, horn = "#6a46c8", hexc("#5434a8"), "#d2c2ff", "#f3ecff"
    body = p.part("body", anim="breathe")

    def scales(f):
        if f.name == "bottom":
            return
        for j in range(f.h):
            for i in range(f.w):
                if (i + j) % 2 == 0:
                    f.set(i, j, scale)
        if f.name == "top":
            for j in range(f.h):
                f.set(f.w // 2, j, hexc("#9b7cf2"))
    p.box(body, (-2, -2.5, -3), (4, 4, 6), purple, scales, faces={"bottom": belly})
    for (x, z) in ((-2, -2.5), (1, -2.5), (-2, 1.5), (1, 1.5)):
        p.box(body, (x, 1.5, z), (1, 2, 1), purple)
    head = p.part("head", pivot=(0, -2, -3), anim="head")

    def face(f):
        if f.name in ("east", "west"):
            f.set(f.w - 2 if f.name == "east" else 1, 1, hexc("#ffd23f"))
        if f.name == "top":
            for j in range(f.h):
                f.set(f.w // 2, j, hexc("#9b7cf2"))
    p.box(head, (-2.5, -6, -7), (5, 4, 4), purple, face)

    def snout(f):
        if f.name == "front":
            f.set(0, 0, BLACK)
            f.set(f.w - 1, 0, BLACK)
            f.rect(0, f.h - 1, f.w, 1, hexc(belly))
    p.box(head, (-1.5, -4.5, -10), (3, 2, 3), "#7a56d8", snout)
    for x in (-2, 1):
        p.box(head, (x, -8, -4.5), (1, 2, 1), horn)
    eyes_part = p.part("eyes", pivot=(0, -2, -3), anim="head", glow=True)
    p.color_box(eyes_part, (-2.55, -5, -6.6), (0.1, 1, 1), "#ffd23f", glow=True)
    p.color_box(eyes_part, (2.45, -5, -6.6), (0.1, 1, 1), "#ffd23f", glow=True)
    for side, anim in ((-1, "wingR"), (1, "wingL")):
        wing = p.part("wing", pivot=(side * 2, -2.5, -0.5), anim=anim)

        def membrane(f):
            if f.name in ("top", "bottom"):
                for i in range(f.w):
                    for j in range(f.h):
                        if i % 3 == 0 or j == 0:
                            f.set(i, j, hexc("#4a2f8f"))
        x0 = -10 if side < 0 else 2
        p.box(wing, (x0, -3, -2), (8, 1, 6), "#a487f5", membrane)
    tail = p.part("tail", pivot=(0, -0.5, 3), anim="tail")
    p.box(tail, (-1, -1.5, 3), (2, 2, 4), purple, scales)
    p.box(tail, (-0.5, -1, 7), (1, 1, 3), purple)
    p.box(tail, (-1, -1.5, 10), (2, 1, 1), "#9b7cf2")
    return p.save()


def ghost():
    p = Pet("geist", "Geist", "flying", seed=23)
    body = p.part("body", anim="bob", glow=True)

    def face(f):
        rng = p.tex.rng
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, mix(hexc("#ffffff"), hexc("#cfd8ff"), j / max(1, f.h - 1) * 0.7 + rng.uniform(0, 0.05)))
        if f.name == "front":
            for i0 in (1, f.w - 2):
                f.rect(i0, 2, 1, 2, BLACK)
            f.set(f.w // 2 - 1, 5, BLACK)
            f.set(f.w // 2, 5, BLACK)
            f.set(f.w // 2 - 1, 4, hexc("#4a4f6a"))
            f.set(f.w // 2, 4, hexc("#4a4f6a"))
    p.box(body, (-3, -4, -3), (6, 7, 6), "#ffffff", face)
    for x in (-3, -0.75, 1.5):
        p.box(body, (x, 3, -3), (1, 1, 6), "#d6deff")
    for side, anim in ((-1, "flipperR"), (1, "flipperL")):
        arm = p.part("arm", pivot=(side * 3, -1, 0), anim=anim, glow=True)
        p.box(arm, (side * 3 - (1 if side < 0 else 0), -1, -1), (1, 2, 2), "#e6ebff")
    return p.save()


def bee():
    p = Pet("biene", "Biene", "flying", seed=25)
    yellow, black = "#f5c22b", hexc("#2b2016")
    body = p.part("body", anim="breathe")

    def bands(f):
        if f.name in ("top", "bottom", "east", "west"):
            # Along z: two dark bands across the body.
            for i in range(f.w):
                for j in range(f.h):
                    z = j if f.name in ("top", "bottom") else i
                    if z in (2, 3, 5):
                        f.set(i, j, black)
        if f.name == "front":
            for i0 in (0, f.w - 2):
                f.rect(i0, 1, 2, 2, black)
                f.set(i0 + (0 if i0 == 0 else 1), 1, WHITE)
        if f.name == "back":
            f.rect(0, 0, f.w, f.h, black)
    p.box(body, (-2.5, -2.5, -3.5), (5, 5, 7), yellow, bands)
    p.box(body, (-0.5, 0, 3.5), (1, 1, 1), "#2b2016")
    for x in (-1.5, 0.5):
        p.box(body, (x, -4.5, -3.5), (1, 2, 1), "#2b2016")
    for side, anim in ((-1, "buzzR"), (1, "buzzL")):
        wing = p.part("wing", pivot=(side * 1, -2.5, -0.5), anim=anim)
        x0 = -5 if side < 0 else 1
        p.box(wing, (x0, -3, -1.5), (4, 1, 3), "#eaf6ff", lambda f: speckle(f, hexc("#bfe3ff"), 0.3, p.tex.rng), noise=0.02)
    return p.save()


def bat():
    p = Pet("fledermaus", "Fledermaus", "flying", seed=27)
    fur, wing = "#4a3a3a", "#2e2428"
    body = p.part("body", anim="bob")

    def face(f):
        if f.name == "front":
            f.set(0, 1, hexc("#ff5470"))
            f.set(f.w - 1, 1, hexc("#ff5470"))
    p.box(body, (-1.5, -2, -1.5), (3, 3, 3), fur, face)
    for x in (-1.5, 0.5):
        p.box(body, (x, -3, -0.5), (1, 1, 1), fur)
    p.box(body, (-1, 1, -1), (2, 2, 2), "#3b2e2e")
    for side, anim in ((-1, "wingR"), (1, "wingL")):
        w = p.part("wing", pivot=(side * 1.5, -1, 0), anim=anim)
        x0 = -7.5 if side < 0 else 1.5

        def ribs(f):
            if f.name in ("top", "bottom"):
                for i in range(0, f.w, 2):
                    for j in range(f.h):
                        f.set(i, j, hexc("#1c1518"))
        p.box(w, (x0, -1.5, -0.5), (6, 1, 3), wing, ribs)
    return p.save()


def chroma_cube():
    p = Pet("wuerfel", "Chroma-Würfel", "flying", seed=29, spin=True)
    core = p.part("core", glow=True)
    p.color_box(core, (-2.5, -2.5, -2.5), (5, 5, 5), "chroma", glow=True)
    return p.save()


def fox():
    p = Pet("fuchs", "Fuchs", "walking", seed=31)
    orange, white, black, dark = "#e2782f", "#f7f1e8", "#221b1b", hexc("#b65a1f")
    body = p.part("body", anim="breathe")

    def coat(f):
        if f.name == "bottom":
            return
        if f.name in ("east", "west"):
            f.rect(0, f.h - 2, f.w, 2, hexc("#f0c9a4"))
        if f.name == "top":
            for i in range(f.w):
                f.set(i, 0, dark)
    p.box(body, (-2.5, -9, -4.5), (5, 5, 9), orange, coat, faces={"bottom": white})
    head = p.part("head", pivot=(0, -8, -4.5), anim="head")

    def face(f):
        if f.name == "front":
            f.rect(0, f.h - 2, f.w, 2, hexc(white))
            eyes(f, 1, 1.5)
    p.box(head, (-3, -11, -8.5), (6, 5, 4), orange, face)

    def snout(f):
        if f.name == "front":
            f.set(f.w // 2, 0, hexc(black))
            f.set(f.w // 2 - 1, 0, hexc(black))
    p.box(head, (-1.5, -8, -10.5), (3, 2, 2), white, snout)
    for x in (-3, 1):
        p.box(head, (x, -13, -6.5), (2, 2, 1), orange, lambda f: f.rect(0, 0, f.w, 1, hexc(black)) if f.name != "bottom" else None)
    tail = p.part("tail", pivot=(0, -8, 4.5), anim="tail")

    def tip(f):
        if f.name in ("east", "west", "top", "bottom"):
            for j in range(f.h):
                for i in range(f.w):
                    z = j if f.name in ("top", "bottom") else (i if f.name == "west" else f.w - 1 - i)
                    if (f.name in ("top",) and z >= f.h - 2) or (f.name == "bottom" and z <= 1) or (f.name in ("east", "west") and z >= f.w - 2):
                        f.set(i, j, hexc(white))
        if f.name == "back":
            f.rect(0, 0, f.w, f.h, hexc(white))
    p.box(tail, (-1.5, -9.5, 4.5), (3, 3, 7), orange, tip)
    for (x, z, anim) in ((-2.5, -3.5, "legA"), (0.5, -3.5, "legB"), (-2.5, 2.5, "legB"), (0.5, 2.5, "legA")):
        leg = p.part("leg", pivot=(x + 1, -4, z + 1), anim=anim)
        p.box(leg, (x, -4, z), (2, 4, 2), orange, lambda f: f.rect(0, f.h - 2, f.w, 2, hexc(black)) if f.name not in ("top", "bottom") else None)
    return p.save()


def dog():
    p = Pet("hund", "Shiba", "walking", seed=33)
    tan, cream, black = "#d9924a", "#fbefd9", "#231b17"
    body = p.part("body", anim="breathe")

    def coat(f):
        if f.name in ("east", "west"):
            f.rect(0, f.h - 2, f.w, 2, hexc(cream))
    p.box(body, (-2.5, -9, -4), (5, 5, 8), tan, coat, faces={"bottom": cream, "front": cream})
    head = p.part("head", pivot=(0, -8, -4), anim="head")

    def face(f):
        if f.name == "front":
            f.rect(1, 2, f.w - 2, f.h - 2, hexc(cream))
            eyes(f, 1, 1.5)
            f.set(0, 1, hexc(cream))
            f.set(f.w - 1, 1, hexc(cream))
    p.box(head, (-3, -12, -8), (6, 5, 4), tan, face)

    def nose(f):
        if f.name == "front":
            f.set(f.w // 2 - 1, 0, hexc(black))
            f.set(f.w // 2, 0, hexc(black))
            f.rect(0, 1, f.w, 1, hexc("#e8c9a8"))
    p.box(head, (-1.5, -9, -10), (3, 2, 2), cream, nose)
    for x in (-3, 1):
        p.box(head, (x, -14, -6), (2, 2, 1), tan, lambda f: f.set(f.w // 2, f.h - 1, hexc("#f2b0a0")) if f.name == "front" else None)
    tail = p.part("tail", pivot=(0, -9, 4), anim="wag")
    p.box(tail, (-1, -12, 3), (2, 4, 2), tan, lambda f: f.rect(0, 0, f.w, 1, hexc(cream)) if f.name != "bottom" else None)
    p.box(tail, (-1, -13, 0.5), (2, 2, 3), tan)
    for (x, z, anim) in ((-2.5, -3, "legA"), (0.5, -3, "legB"), (-2.5, 2, "legB"), (0.5, 2, "legA")):
        leg = p.part("leg", pivot=(x + 1, -4, z + 1), anim=anim)
        p.box(leg, (x, -4, z), (2, 4, 2), tan, lambda f: f.rect(0, f.h - 1, f.w, 1, hexc(cream)) if f.name not in ("top", "bottom") else None)
    return p.save()


def panda():
    p = Pet("panda", "Panda", "walking", seed=35)
    white, black = "#f4f2ee", "#1f1d22"
    body = p.part("body", anim="breathe")

    def band(f):
        if f.name in ("east", "west", "top"):
            if f.name == "top":
                f.rect(0, 0, f.w, 2, hexc(black))
            else:
                f.rect(0 if f.name == "west" else f.w - 2, 0, 2, f.h, hexc(black))
    p.box(body, (-3.5, -9, -4.5), (7, 6, 9), white, band)
    head = p.part("head", pivot=(0, -8, -4.5), anim="head")

    def face(f):
        if f.name == "front":
            for i0 in (1, f.w - 3):
                f.rect(i0, 2, 2, 2, hexc(black))
            f.set(2, 2, WHITE)
            f.set(f.w - 3, 2, WHITE)
            f.rect(f.w // 2 - 1, 4, 2, 1, hexc(black))
    p.box(head, (-3.5, -12, -9), (7, 6, 5), white, face)
    for x in (-3.5, 1.5):
        p.box(head, (x, -13, -6.5), (2, 2, 1), black)
    for (x, z, anim) in ((-3.5, -4, "legA"), (0.5, -4, "legB"), (-3.5, 2, "legB"), (0.5, 2, "legA")):
        leg = p.part("leg", pivot=(x + 1.5, -3, z + 1), anim=anim)
        p.box(leg, (x, -3, z), (3, 3, 2), black)
    return p.save()


def turtle():
    p = Pet("schildkroete", "Schildkröte", "walking", seed=37)
    shell, ring, skin = "#4f8a3a", hexc("#2f5c22"), "#8fc06a"
    body = p.part("body", anim="breathe")

    def plates(f):
        if f.name == "bottom":
            return
        for j in range(f.h):
            for i in range(f.w):
                if i % 3 == 0 or j % 3 == 0:
                    f.set(i, j, ring)
        if f.name == "top":
            for j in range(1, f.h - 1, 3):
                for i in range(1, f.w - 1, 3):
                    f.set(i, j, hexc("#79b85a"))
    p.box(body, (-4, -6, -4.5), (8, 4, 9), shell, plates, faces={"bottom": "#c9b26a"})
    p.box(body, (-3, -7, -3.5), (6, 1, 7), shell, plates)
    head = p.part("head", pivot=(0, -3, -4.5), anim="head")
    p.box(head, (-1.5, -5, -7.5), (3, 3, 3), skin, lambda f: eyes(f, 0, 1.0) if f.name == "front" else None)
    for (x, z, anim) in ((-4, -4, "legA"), (2, -4, "legB"), (-4, 2, "legB"), (2, 2, "legA")):
        leg = p.part("leg", pivot=(x + 1, -2, z + 1), anim=anim)
        p.box(leg, (x, -2, z), (2, 2, 2), skin)
    return p.save()


# ---------- collection 2 ----------


def along_z(f, i, j):
    """0 at the front (minus z) to 1 at the back, for the pixel (i, j) of a face."""
    if f.name in ("top", "bottom"):
        t = j / max(1, f.h - 1)
        return t if f.name == "top" else 1 - t
    if f.name == "west":
        return i / max(1, f.w - 1)
    if f.name == "east":
        return 1 - i / max(1, f.w - 1)
    return 0.0 if f.name == "front" else 1.0


def axolotl():
    p = Pet("axolotl", "Axolotl", "shoulder", seed=41)
    pink, belly, speck = hexc("#f6a8c6"), hexc("#fcd6e4"), hexc("#e8869f")
    gill, gill_tip, mouth = hexc("#d9366f"), hexc("#ff8ab3"), hexc("#b84a6f")
    body = p.part("body", anim="breathe")

    def skin(f):
        gradient(f, shade(pink, 1.07), shade(pink, 0.9))
        if f.name == "bottom":
            f.fill(belly, 0.03)
        elif f.name in ("top", "east", "west"):
            speckle(f, speck, 0.07, p.tex.rng)
        if f.name == "top":
            # A soft dorsal stripe that runs into the tail fin.
            for j in range(f.h):
                f.set(f.w // 2, j, shade(gill_tip, 0.95))
                f.set(f.w // 2 - 1, j, shade(gill_tip, 0.95))
    p.box(body, (-2, -3, -3), (4, 3, 7), "#f6a8c6", skin)
    for x, z in ((-3, -2.5), (2, -2.5), (-3, 2), (2, 2)):
        p.box(body, (x, -1, z), (1, 1, 1), "#f19aba", lambda f: f.set(0, 0, shade(pink, 1.1)) if f.name == "top" else None)

    head = p.part("head", pivot=(0, -2, -3), anim="head")

    def face(f):
        gradient(f, shade(pink, 1.08), shade(pink, 0.94))
        if f.name == "front":
            # Wide-set dot eyes, a little smile and blush: the axolotl's whole charm.
            for i in (1, f.w - 2):
                f.set(i, 1, BLACK)
            f.set(1, 0, shade(pink, 1.15))
            f.set(f.w - 2, 0, shade(pink, 1.15))
            for i, j in ((1, 2), (2, 3), (3, 3), (4, 2)):
                f.set(i, j, mouth)
            f.set(0, 2, hexc("#ff9fbf"))
            f.set(f.w - 1, 2, hexc("#ff9fbf"))
        elif f.name == "top":
            speckle(f, speck, 0.1, p.tex.rng)
        elif f.name == "bottom":
            f.fill(belly, 0.03)
    p.box(head, (-3, -4, -7), (6, 4, 4), "#f6a8c6", face)

    # Three feathery gill fronds on each side, swaying gently.
    def frond(f):
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, gill if (j + i) % 2 == 0 else gill_tip)
        f.set(0, 0, gill_tip)
    for side, anim in ((-1, "flipperR"), (1, "flipperL")):
        gills = p.part("gills", pivot=(side * 3, -3, -5), anim=anim)
        x0 = 3 if side > 0 else -4
        for z, height in ((-6.5, 3), (-5, 3), (-3.5, 2)):
            p.box(gills, (x0, -3 - height, z), (1, height, 1), "#d9366f", frond)
            p.box(gills, (x0 + side, -3 - height, z), (1, 1, 1), "#ff8ab3")

    tail = p.part("tail", pivot=(0, -1.5, 4), anim="tail")

    def fin(f):
        for j in range(f.h):
            for i in range(f.w):
                c = mix(pink, gill_tip, along_z(f, i, j) * 0.8)
                f.set(i, j, shade(c, 1.05 if j == 0 else 1.0))
    p.box(tail, (-0.5, -3, 4), (1, 3, 5), "#f6a8c6", fin)
    return p.save()


def owl():
    p = Pet("eule", "Eule", "shoulder", seed=43)
    brown, dark, cream = hexc("#8b5b3c"), hexc("#5d3a23"), hexc("#f0dfbf")
    disc, amber, beak = hexc("#dcbc8e"), hexc("#ffb21f"), hexc("#e7a21b")
    body = p.part("body", anim="breathe")

    def plumage(f):
        gradient(f, shade(brown, 1.06), shade(brown, 0.86))
        if f.name == "front":
            for j in range(f.h):
                for i in range(1, f.w - 1):
                    f.set(i, j, shade(cream, 1 - 0.04 * j))
            # Barred breast feathers.
            for j in range(1, f.h - 1, 2):
                for i in range(1, f.w - 1):
                    if (i + j) % 3 == 0:
                        f.set(i, j, shade(brown, 1.05))
        elif f.name in ("east", "west", "back"):
            for j in range(f.h):
                for i in range(f.w):
                    if (i * 2 + j) % 5 == 0:
                        f.set(i, j, dark)
    p.box(body, (-2, -5, -2), (4, 5, 4), "#8b5b3c", plumage)
    for x in (-1.5, 0.5):
        p.box(body, (x, -0.5, -2.6), (1, 1, 1), "#e0a82e", lambda f: f.set(0, 0, shade(beak, 0.8)) if f.name == "front" else None)
    p.box(body, (-1, -1.5, 2), (2, 1, 1), "#5d3a23")

    head = p.part("head", pivot=(0, -5, 0), anim="head")

    def face(f):
        gradient(f, shade(brown, 1.08), shade(brown, 0.95))
        if f.name == "front":
            # Facial disc, framed by a dark rim, with big round amber eyes.
            f.rect(0, 0, f.w, f.h, disc)
            f.rect(0, 0, f.w, 1, dark)
            f.set(0, f.h - 1, dark)
            f.set(f.w - 1, f.h - 1, dark)
            for i0 in (0, 3):
                f.rect(i0, 1, 2, 2, amber)
            f.set(1, 2, BLACK)
            f.set(3, 2, BLACK)
            f.set(0, 1, hexc("#ffe08a"))
            f.set(4, 1, hexc("#ffe08a"))
            f.set(1, 1, WHITE)
            f.set(3, 1, WHITE)
            f.set(2, 2, beak)
            f.set(2, 3, shade(beak, 0.78))
        elif f.name == "top":
            speckle(f, dark, 0.16, p.tex.rng)
        elif f.name in ("east", "west", "back"):
            for j in range(f.h):
                for i in range(f.w):
                    if (i + j * 2) % 4 == 0:
                        f.set(i, j, dark)
                    elif (i + j) % 5 == 0:
                        f.set(i, j, shade(cream, 0.9))
    p.box(head, (-2.5, -9, -2.5), (5, 4, 5), "#8b5b3c", face)
    for x in (-2.5, 1.5):
        p.box(head, (x, -10, -1.5), (1, 1, 2), "#5d3a23", lambda f: f.set(0, 0, brown) if f.name == "top" else None)

    def feathers(f):
        gradient(f, shade(brown, 1.0), shade(dark, 1.0))
        for j in range(f.h):
            for i in range(f.w):
                if j >= f.h - 2 and (i + j) % 2 == 0:
                    f.set(i, j, shade(cream, 0.85))
                elif j % 2 == 1 and i % 2 == 0:
                    f.set(i, j, dark)
    for side, anim in ((-1, "flipperR"), (1, "flipperL")):
        wing = p.part("wing", pivot=(side * 2, -4.5, 0), anim=anim)
        p.box(wing, (2 if side > 0 else -3, -4.5, -1.5), (1, 4, 3), "#8b5b3c", feathers)
    return p.save()


def phoenix():
    p = Pet("phoenix", "Phönix", "flying", seed=45)
    crimson, ember, gold, pale = hexc("#c8192c"), hexc("#ff5a1f"), hexc("#ffc23d"), hexc("#fff0a6")

    def fire(t):
        """The phoenix's colour ramp: crimson at the root through ember and gold to pale flame."""
        if t < 0.4:
            return mix(crimson, ember, t / 0.4)
        if t < 0.8:
            return mix(ember, gold, (t - 0.4) / 0.4)
        return mix(gold, pale, (t - 0.8) / 0.2)

    body = p.part("body", anim="breathe", glow=True)

    def plumage(f):
        gradient(f, ember, crimson)
        if f.name == "bottom":
            f.fill(gold, 0.05)
        for j in range(f.h):
            for i in range(f.w):
                if f.name in ("east", "west", "top") and (i + 2 * j) % 4 == 0:
                    f.set(i, j, shade(gold, 0.95))
    p.box(body, (-1.5, -2, -2.5), (3, 3, 5), "#ff5a1f", plumage)

    head = p.part("head", pivot=(0, -1.5, -2.5), anim="head", glow=True)

    def face(f):
        gradient(f, gold, ember)
        if f.name in ("east", "west"):
            i = 0 if f.name == "west" else f.w - 1
            f.set(i, 1, WHITE)
            f.set(i, 2, shade(crimson, 0.8))
    p.box(head, (-1.5, -4.5, -5), (3, 3, 3), "#ffc23d", face)
    p.box(head, (-0.5, -3.5, -6), (1, 1, 1), "#ffe08a", lambda f: f.set(0, 0, shade(gold, 0.85)) if f.name == "bottom" else None)
    # A crest of flame feathers sweeping back.
    p.box(head, (-0.5, -6.5, -4.5), (1, 2, 1), "#ffe27a", lambda f: f.set(0, 0, pale))
    p.box(head, (-0.5, -6, -3.5), (1, 1, 2), "#ff9f2e", lambda f: f.set(f.w - 1, 0, gold) if f.name == "west" else None)

    for side, anim in ((-1, "wingR"), (1, "wingL")):
        wing = p.part("wing", pivot=(side * 1.5, -1.5, -1), anim=anim, glow=True)

        def feathers(f, side=side):
            for j in range(f.h):
                for i in range(f.w):
                    t = i / max(1, f.w - 1)
                    if side < 0:
                        t = 1 - t
                    if f.name in ("east", "west", "front", "back"):
                        t = 0.6
                    f.set(i, j, shade(fire(t), 0.9 if j % 2 else 1.0))
        # Coverts over the arm, the main wing, then long primaries that sweep back.
        p.box(wing, (-5.5 if side < 0 else 1.5, -2.5, -2.5), (4, 1, 2), "#c8192c", lambda f: gradient(f, crimson, shade(crimson, 0.85)))
        p.box(wing, (-7.5 if side < 0 else 1.5, -2, -2.5), (6, 1, 4), "#ff5a1f", feathers)

        def primaries(f):
            for j in range(f.h):
                for i in range(f.w):
                    f.set(i, j, fire(0.7 + 0.3 * along_z(f, i, j)) if f.name in ("top", "bottom") else fire(0.85))
        p.box(wing, (-9.5 if side < 0 else 7.5, -2, -2), (2, 1, 5), "#ffc23d", primaries)
        p.box(wing, (-10.5 if side < 0 else 9.5, -2, -1), (1, 1, 4), "#ffe27a", primaries)

    tail = p.part("tail", pivot=(0, -0.5, 2.5), anim="tail", glow=True)

    def plume(f):
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, fire(along_z(f, i, j)))
    # Three plumes fanning out, each ending in a raised, curled flame tip.
    p.box(tail, (-0.5, -1.5, 2.5), (1, 1, 8), "#ff5a1f", plume)
    p.box(tail, (-0.5, -2.5, 10), (1, 1, 2), "#fff0a6", plume)
    for x, out in ((-1, -1.5), (1, 1.5)):
        p.box(tail, (x - 0.5, -1, 2.5), (1, 1, 4), "#ff5a1f", plume)
        p.box(tail, (x - 0.5 + out, -1, 6.5), (1, 1, 3), "#ffc23d", lambda f: [f.set(i, j, fire(0.55 + 0.45 * along_z(f, i, j))) for j in range(f.h) for i in range(f.w)])
        p.box(tail, (x - 0.5 + out * 1.5, -2, 9), (1, 1, 1), "#fff0a6")
    return p.save()


def jellyfish():
    p = Pet("qualle", "Qualle", "flying", seed=47)
    top, deep, rim, spot = hexc("#e6b8ff"), hexc("#9447de"), hexc("#f4dbff"), hexc("#ffffff")
    tent, tent_end = hexc("#b46ef2"), hexc("#6a2ac0")
    bell = p.part("bell", pivot=(0, -1, 0), anim="squish", glow=True)

    def dome(f):
        gradient(f, top, deep)
        if f.name in ("top", "east", "west", "back"):
            rng = p.tex.rng
            for j in range(f.h):
                for i in range(f.w):
                    if rng.random() < 0.12:
                        f.set(i, j, mix(spot, top, 0.35))
        if f.name == "front":
            for i in (1, f.w - 2):
                f.set(i, 1, hexc("#2a1240"))
            f.set(f.w // 2 - 1, 2, hexc("#5a2a80"))
            f.set(f.w // 2, 2, hexc("#5a2a80"))
            f.set(0, 2, hexc("#ffb3e6"))
            f.set(f.w - 1, 2, hexc("#ffb3e6"))
    p.box(bell, (-2, -5, -2), (4, 1, 4), "#e6b8ff", lambda f: gradient(f, shade(top, 1.04), shade(top, 0.98)), noise=0.02)
    p.box(bell, (-3, -4, -3), (6, 3, 6), "#e6b8ff", dome)
    p.box(bell, (-3.5, -1, -3.5), (7, 1, 7), "#f4dbff", lambda f: gradient(f, rim, shade(rim, 0.9)))

    def strand(f):
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, mix(tent, tent_end, j / max(1, f.h - 1)) if f.name not in ("top", "bottom") else tent)
    for (x, z, anim) in ((-2.5, -2.5, "tail"), (1.5, -2.5, "flipperL"), (-2.5, 1.5, "flipperR"), (1.5, 1.5, "tail")):
        arm = p.part("tentacle", pivot=(x + 0.5, 0, z + 0.5), anim=anim, glow=True)
        p.box(arm, (x, 0, z), (1, 3, 1), "#b46ef2", strand)
        p.box(arm, (x, 3, z), (1, 2, 1), "#8a45d8", strand)
        p.color_box(arm, (x + 0.2, 5, z + 0.2), (0.6, 0.8, 0.6), "#f7dcff", glow=True)
    frills = p.part("arms", pivot=(0, 0, 0), anim="tail", glow=True)

    def frilly(f):
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, rim if (i + j) % 2 == 0 else mix(tent, rim, 0.4))
    p.box(frills, (-1, 0, -1), (2, 4, 2), "#f4dbff", frilly)
    return p.save()


def kitsune():
    p = Pet("kitsune", "Kitsune", "walking", seed=49)
    white, shadow, red, ink = hexc("#fbf9f6"), hexc("#ebe5ef"), hexc("#d62246"), hexc("#221b22")
    spirit, spirit_core = "#9fd8ff", "#e8f7ff"
    body = p.part("body", anim="breathe")

    def coat(f):
        gradient(f, white, shadow)
        if f.name in ("east", "west"):
            # A red flame mark on each flank, like the masks of shrine foxes.
            # A thin curl: a line that sweeps back and hooks up, like a brush stroke.
            for i, j in ((2, 3), (3, 2), (4, 2), (5, 2), (6, 1), (7, 1), (7, 0)):
                f.set(i if f.name == "west" else f.w - 1 - i, j, red)
        if f.name == "top":
            for j in range(f.h):
                f.set(f.w // 2, j, shade(white, 0.95))
    p.box(body, (-2.5, -9, -4.5), (5, 5, 9), "#f8f5f0", coat)
    # A red cord with a little golden bell at the throat.
    p.color_box(body, (-2.6, -8.8, -4.9), (5.2, 0.8, 0.6), "#d62246")
    p.color_box(body, (-0.6, -8.2, -5.3), (1.2, 1.2, 0.8), "#ffcf33", glow=True)

    head = p.part("head", pivot=(0, -8, -4.5), anim="head")

    def face(f):
        gradient(f, white, shade(white, 0.94))
        if f.name == "front":
            # Golden eyes with a single red flick outward, and a small mark on the brow.
            for side in (-1, 1):
                i = 1 if side < 0 else f.w - 2
                f.set(i, 2, hexc("#ffc23d"))
                f.set(i + (1 if side < 0 else -1), 2, ink)
                f.set(i + side, 1, red)
            f.set(f.w // 2 - 1, 0, red)
            f.set(f.w // 2, 0, red)
    p.box(head, (-3, -11, -8.5), (6, 5, 4), "#f8f5f0", face)

    def snout(f):
        gradient(f, white, shade(white, 0.95))
        if f.name == "front":
            f.set(f.w // 2, 0, ink)
            f.set(f.w // 2 - 1, 0, ink)
    p.box(head, (-1.5, -8, -10.5), (3, 2, 2), "#f8f5f0", snout)

    def ear(f):
        gradient(f, white, white)
        if f.name == "front":
            f.rect(0, 0, f.w, f.h, red)
            f.set(0, f.h - 1, white)
            f.set(f.w - 1, f.h - 1, white)
        elif f.name == "top":
            f.fill(red, 0.03)
    for x in (-3, 1):
        p.box(head, (x, -13, -6.5), (2, 2, 1), "#f8f5f0", ear)

    # Three tails rising behind, each tipped with a pale spirit flame.
    def tail_fur(f):
        for j in range(f.h):
            for i in range(f.w):
                f.set(i, j, mix(white, shadow, 0.25 + 0.5 * along_z(f, i, j)) if f.name != "top" else white)
    for x, lift in ((-2.2, 0.5), (0, 1.5), (2.2, 0.5)):
        tail = p.part("tail", pivot=(x, -8, 4.5), anim="tail")
        p.box(tail, (x - 1, -10 - lift * 0.5, 4.5), (2, 2, 4), "#f8f5f0", tail_fur)
        p.box(tail, (x - 1, -12.5 - lift, 7.5), (2, 3, 2), "#f8f5f0", tail_fur)
        flame = p.part("flame", pivot=(x, -8, 4.5), anim="tail", glow=True)
        p.color_box(flame, (x - 1.1, -14.6 - lift, 7.4), (2.2, 2.2, 2.2), spirit, glow=True)
        p.color_box(flame, (x - 0.5, -15.6 - lift, 8), (1, 1.2, 1), spirit_core, glow=True)

    def sock(f):
        gradient(f, white, shadow)
        if f.name not in ("top", "bottom"):
            f.rect(0, f.h - 1, f.w, 1, shade(shadow, 0.9))
    for (x, z, anim) in ((-2.5, -3.5, "legA"), (0.5, -3.5, "legB"), (-2.5, 2.5, "legB"), (0.5, 2.5, "legA")):
        leg = p.part("leg", pivot=(x + 1, -4, z + 1), anim=anim)
        p.box(leg, (x, -4, z), (2, 4, 2), "#f8f5f0", sock)
    return p.save()


def golem():
    p = Pet("golem", "Mini-Golem", "walking", seed=51)
    iron, iron_dark, crack = hexc("#e2ddd2"), hexc("#aaa293"), hexc("#7f776a")
    vine, vine_dark, eye = hexc("#4f8a33"), hexc("#356b22"), hexc("#7d1414")

    def metal(f, vines=0.0):
        gradient(f, shade(iron, 1.05), iron_dark)
        rng = p.tex.rng
        for j in range(f.h):
            for i in range(f.w):
                if rng.random() < 0.06:
                    f.set(i, j, crack)
        if vines and f.name != "bottom":
            # Vines creep up from below in a few columns.
            for i in range(f.w):
                if rng.random() < vines:
                    top = rng.randint(max(0, f.h - 5), f.h - 1)
                    for j in range(top, f.h):
                        f.set(i, j, vine if (i + j) % 3 else vine_dark)

    for (x, anim) in ((-2.5, "legA"), (0.5, "legB")):
        leg = p.part("leg", pivot=(x + 1, -5, 0), anim=anim)
        p.box(leg, (x, -5, -1), (2, 5, 2), "#dcd7cc", lambda f: metal(f, 0.35))
    body = p.part("body", anim="breathe")
    p.box(body, (-2.5, -8, -1.5), (5, 3, 3), "#dcd7cc", lambda f: metal(f, 0.2))
    p.box(body, (-3.5, -12, -2), (7, 4, 4), "#dcd7cc", lambda f: metal(f, 0.3))

    head = p.part("head", pivot=(0, -12, -1), anim="head")

    def face(f):
        metal(f)
        if f.name == "front":
            f.rect(0, 0, f.w, 1, shade(iron_dark, 0.8))
            f.set(1, 1, eye)
            f.set(f.w - 2, 1, eye)
            f.set(1, 2, shade(iron_dark, 0.8))
            f.set(f.w - 2, 2, shade(iron_dark, 0.8))
        if f.name == "top":
            for i in range(0, f.w, 2):
                f.set(i, 0, vine)
    p.box(head, (-2, -16, -3), (4, 4, 4), "#dcd7cc", face)
    p.box(head, (-0.5, -14, -4), (1, 2, 1), "#c9c2b4", lambda f: f.set(0, f.h - 1, iron_dark) if f.name == "front" else None)
    # The heavy brow ridge of an iron golem.
    p.box(head, (-2, -15.5, -3.5), (4, 1, 1), "#bdb5a6", lambda f: gradient(f, shade(iron, 0.95), iron_dark))

    for side, anim in ((-1, "legB"), (1, "legA")):
        arm = p.part("arm", pivot=(side * 4.5, -11.5, 0), anim=anim)
        x0 = -5.5 if side < 0 else 3.5
        p.box(arm, (x0, -12, -1), (2, 9, 2), "#dcd7cc", lambda f: metal(f, 0.25))
        if side > 0:
            # A poppy held up in the right hand, as iron golems offer them.
            p.color_box(arm, (x0 + 0.8, -3.2, -2.4), (0.4, 2.2, 0.4), "#3a7d2b")
            p.color_box(arm, (x0 + 1.1, -2.2, -2.6), (0.9, 0.4, 0.5), "#4a9a33")
            p.color_box(arm, (x0 + 0.1, -4.9, -3.2), (1.8, 1.6, 1.2), "#d81f26")
            p.color_box(arm, (x0 + 0.65, -4.4, -3.35), (0.7, 0.7, 0.3), "#2b1a12")
    return p.save()


def main():
    pets = [minime(), hedgehog(), cat(), frog(), penguin(), chick(), slime(), axolotl(), owl(),
            dragon(), ghost(), bee(), bat(), chroma_cube(), phoenix(), jellyfish(),
            fox(), dog(), panda(), turtle(), kitsune(), golem()]
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    catalogue["pets"] = pets
    catalogue["_petsNote"] = "Pets are generated by tools/pets/build.py; edit that file, not this list."
    CATALOGUE.write_text(json.dumps(catalogue, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")
    print(f"{len(pets)} pets written to {CATALOGUE.relative_to(ROOT)} and {OUT_DIR.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
