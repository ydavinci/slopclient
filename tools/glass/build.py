"""Builds the glass skins of the Glass module
(client-mod .../textures/entity/glass_<variant>_<wide|slim>.png).

Every face of the base layer becomes a pane: its fill, a rim around the edge and a short
glint under the top edge, in the variant's colours. The second skin layer stays empty, so
hat and jacket disappear.

    python tools/glass/build.py
"""

import colorsys
import random
from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "client-mod/src/client/resources/assets/slopclient/textures/entity"

# Faces stack (front, back and the insides), so clear panes stay faint.
VARIANTS = {
    # Clear glass: nearly clear at the top, a little frost towards the bottom, white rims.
    "klar": dict(tint=(236, 232, 255), alpha=(14, 36), rim=(255, 255, 255, 210), glint=(255, 255, 255, 120)),
    # Frosted ice: cold blue, more frost, pale cyan rims.
    "frost": dict(tint=(196, 228, 255), alpha=(34, 70), rim=(214, 244, 255, 225), glint=(255, 255, 255, 160)),
    # Smoked glass: a dark grey see-through, light grey rims.
    "rauch": dict(tint=(34, 32, 42), alpha=(70, 110), rim=(200, 200, 214, 190), glint=(255, 255, 255, 90)),
    # Amber: warm honey glass with golden rims.
    "bernstein": dict(tint=(255, 168, 40), alpha=(40, 74), rim=(255, 214, 120, 220), glint=(255, 244, 200, 150)),
    # Prism: clear glass whose rims run through the rainbow.
    "prisma": dict(tint=(240, 240, 255), alpha=(12, 30), rim="rainbow", glint=(255, 255, 255, 130)),
    # Chasm: a window into a starry void, deep violet with glowing magenta rims.
    "abgrund": dict(tint=(12, 6, 30), alpha=(170, 215), rim=(206, 92, 255, 235), glint=(255, 210, 255, 140), stars=True),
}


def boxes(arm):
    """Base-layer boxes of a player skin as (u, v, width, height, depth)."""
    return [
        (0, 0, 8, 8, 8),  # head
        (16, 16, 8, 12, 4),  # body
        (40, 16, arm, 12, 4),  # right arm
        (32, 48, arm, 12, 4),  # left arm
        (0, 16, 4, 12, 4),  # right leg
        (16, 48, 4, 12, 4),  # left leg
    ]


def faces(u, v, w, h, d):
    """Face rectangles (x, y, width, height) of a box in Minecraft's box UV layout."""
    return [
        (u + d, v, w, d),  # top
        (u + d + w, v, w, d),  # bottom
        (u, v + d, d, h),  # right
        (u + d, v + d, w, h),  # front
        (u + d + w, v + d, d, h),  # left
        (u + 2 * d + w, v + d, w, h),  # back
    ]


def rainbow(x, y):
    r, g, b = colorsys.hsv_to_rgb(((x + y) % 24) / 24, 0.55, 1)
    return (round(r * 255), round(g * 255), round(b * 255), 225)


def pane(img, x, y, w, h, v, rng):
    lo, hi = v["alpha"]
    for j in range(h):
        alpha = round(lo + (hi - lo) * (j / max(1, h - 1)))
        for i in range(w):
            img.putpixel((x + i, y + j), (*v["tint"], alpha))
    if v.get("stars"):
        for i in range(1, w - 1):
            for j in range(1, h - 1):
                roll = rng.random()
                if roll < 0.06:
                    img.putpixel((x + i, y + j), (255, 255, 255, 230))
                elif roll < 0.12:
                    img.putpixel((x + i, y + j), (190, 120, 255, 200))
    edge = [(x + i, y) for i in range(w)] + [(x + i, y + h - 1) for i in range(w)]
    edge += [(x, y + j) for j in range(h)] + [(x + w - 1, y + j) for j in range(h)]
    for px, py in edge:
        img.putpixel((px, py), rainbow(px, py) if v["rim"] == "rainbow" else v["rim"])
    # A short glint under the top edge, on panes big enough to have an inside.
    if w >= 4 and h >= 4:
        for i in range(1, max(2, w // 2)):
            img.putpixel((x + i, y + 1), v["glint"])


def build(name, v, arm, model):
    rng = random.Random(name)  # the same stars on every run
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for box in boxes(arm):
        for face in faces(*box):
            pane(img, *face, v, rng)
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / f"glass_{name}_{model}.png"
    img.save(path)
    print("wrote", path)


for old in ("glass_wide.png", "glass_slim.png"):
    (OUT / old).unlink(missing_ok=True)
for name, v in VARIANTS.items():
    build(name, v, 4, "wide")
    build(name, v, 3, "slim")
