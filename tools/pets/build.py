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


def main():
    pets = [minime(), hedgehog(), cat(), frog(), penguin(), chick(), slime(),
            dragon(), ghost(), bee(), bat(), chroma_cube(),
            fox(), dog(), panda(), turtle()]
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    catalogue["pets"] = pets
    catalogue["_petsNote"] = "Pets are generated by tools/pets/build.py; edit that file, not this list."
    CATALOGUE.write_text(json.dumps(catalogue, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")
    print(f"{len(pets)} pets written to {CATALOGUE.relative_to(ROOT)} and {OUT_DIR.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
