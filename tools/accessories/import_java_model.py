"""Adds an accessory (Java block model / Blockbench "Java Block/Item" export) to the catalogue.

    python tools/accessories/import_java_model.py model.json texture.png --id zenin_katana \
        --name "Zenins Katana" --credit "MazerOP (Cosmetica)" --pivot 18.35,-8.03,9.66 \
        --position 4.6,-10.5,1 --rotation 127.5,0,0 --scale 0.65

Writes the entry into cosmetics/extras.json ("accessories") and the texture to
cosmetics/pets/<id>.png, where the server picks both up. Elements are kept as they are
(per-face UVs, UV and element rotations); the renderers in the mod (Accessories.java) and
the launcher (lib/accessory.ts) draw them the same way.

Placement, in pixels of the player's body (y up, the player faces +z, +x is the
player's left; the origin is the neck): the model point --pivot (model coordinates,
before element rotations; it is moved with them when all elements share one rotation)
ends up at --position after turning by --rotation (degrees, X then Y then Z) and scaling.

Only import models you have the creator's permission for; put them in --credit.
"""

import argparse
import json
import math
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOGUE = ROOT / "cosmetics" / "extras.json"
TEXTURES = ROOT / "cosmetics" / "pets"
FACES = ("north", "south", "east", "west", "up", "down")


def vec(text: str) -> list[float]:
    v = [float(x) for x in text.split(",")]
    if len(v) != 3:
        raise argparse.ArgumentTypeError("expected x,y,z")
    return v


def rotate(point, rotation):
    """Applies a block model element rotation (axis, angle, origin) to a point."""
    o = rotation["origin"]
    a = math.radians(rotation["angle"])
    x, y, z = (point[i] - o[i] for i in range(3))
    c, s = math.cos(a), math.sin(a)
    if rotation["axis"] == "x":
        y, z = y * c - z * s, y * s + z * c
    elif rotation["axis"] == "y":
        x, z = x * c + z * s, -x * s + z * c
    else:
        x, y = x * c - y * s, x * s + y * c
    return [round(x + o[0], 4), round(y + o[1], 4), round(z + o[2], 4)]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("model")
    ap.add_argument("texture")
    ap.add_argument("--id", required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--credit", required=True, help="creator and source, shown with the item")
    ap.add_argument("--attach", choices=("body", "head"), default="body")
    ap.add_argument("--pivot", type=vec, required=True)
    ap.add_argument("--position", type=vec, required=True)
    ap.add_argument("--rotation", type=vec, default=[0, 0, 0])
    ap.add_argument("--scale", type=float, default=1.0)
    args = ap.parse_args()

    model = json.loads(Path(args.model).read_text(encoding="utf-8"))
    elements = []
    for e in model["elements"]:
        faces = {}
        for name in FACES:
            f = e.get("faces", {}).get(name)
            if f is None:
                continue
            face = {"uv": [round(u, 4) for u in f["uv"]]}
            if f.get("rotation"):
                face["rotation"] = int(f["rotation"])
            faces[name] = face
        el = {"from": [round(c, 4) for c in e["from"]], "to": [round(c, 4) for c in e["to"]], "faces": faces}
        if e.get("rotation") and e["rotation"].get("angle"):
            r = e["rotation"]
            el["rotation"] = {"origin": [round(c, 4) for c in r["origin"]], "axis": r["axis"], "angle": r["angle"]}
        elements.append(el)

    # The pivot moves with the elements when they all share one rotation (a tilted model).
    rotations = {json.dumps(e.get("rotation"), sort_keys=True) for e in elements}
    pivot = args.pivot
    if len(rotations) == 1 and elements[0].get("rotation"):
        pivot = rotate(pivot, elements[0]["rotation"])

    entry = {
        "id": args.id,
        "name": args.name,
        "credit": args.credit,
        "attach": args.attach,
        "texture": f"{args.id}.png",
        "uvSize": model.get("texture_size", [16, 16]),
        "pivot": pivot,
        "position": args.position,
        "rotation": args.rotation,
        "scale": args.scale,
        "elements": elements,
    }
    catalogue = json.loads(CATALOGUE.read_text(encoding="utf-8"))
    accessories = [a for a in catalogue.get("accessories", []) if a["id"] != args.id]
    accessories.append(entry)
    catalogue["accessories"] = accessories
    CATALOGUE.write_text(json.dumps(catalogue, ensure_ascii=False, indent="\t") + "\n", encoding="utf-8")
    shutil.copyfile(args.texture, TEXTURES / f"{args.id}.png")
    print(f"{args.id}: {len(elements)} elements, pivot {pivot}, texture {args.id}.png")


if __name__ == "__main__":
    main()
