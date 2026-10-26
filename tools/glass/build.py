"""Builds the glass skins of the Glass module (client-mod .../textures/entity/glass_{wide,slim}.png).

Every face of the base layer becomes a frosted pane: nearly clear at the top, a little
more frost towards the bottom, a bright rim around the edge and a short highlight just
under the top edge. The second skin layer stays empty, so hat and jacket disappear.

    python tools/glass/build.py
"""

from pathlib import Path

from PIL import Image

OUT = Path(__file__).resolve().parents[2] / "client-mod/src/client/resources/assets/slopclient/textures/entity"

TINT = (236, 232, 255)
RIM = (255, 255, 255, 210)
HIGHLIGHT = (255, 255, 255, 120)


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


def pane(img, x, y, w, h):
    for j in range(h):
        # Clear at the top, frostier towards the bottom.
        # Faces stack (front, back and the insides), so each pane stays faint.
        alpha = round(14 + 22 * (j / max(1, h - 1)))
        for i in range(w):
            img.putpixel((x + i, y + j), (*TINT, alpha))
    for i in range(w):
        img.putpixel((x + i, y), RIM)
        img.putpixel((x + i, y + h - 1), RIM)
    for j in range(h):
        img.putpixel((x, y + j), RIM)
        img.putpixel((x + w - 1, y + j), RIM)
    # A short glint under the top edge, on panes big enough to have an inside.
    if w >= 4 and h >= 4:
        for i in range(1, max(2, w // 2)):
            img.putpixel((x + i, y + 1), HIGHLIGHT)


def build(arm, name):
    img = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for box in boxes(arm):
        for face in faces(*box):
            pane(img, *face)
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT / name)
    print("wrote", OUT / name)


build(4, "glass_wide.png")
build(3, "glass_slim.png")
