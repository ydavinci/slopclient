#!/usr/bin/env python3
"""Subsets the Lucide icon font (lucide-static, ISC) to the icons the client mod uses.

Usage: python3 tools/icons/subset_lucide.py <path to lucide-static/font>
Needs fontTools (pip install fonttools). Writes
client-mod/src/client/resources/assets/slopclient/font/lucide.ttf and prints the
Java constants for dev.slopclient.ui.Icon. Add new names to ICONS and run it again.
"""
import json
import pathlib
import sys

from fontTools import subset

ICONS = [
    "house", "earth", "server", "images", "layout-grid", "settings", "power", "play", "plus",
    "chevron-right", "chevron-left", "user", "clock", "folder-open", "arrow-right", "search", "x",
    "trash-2", "pencil", "refresh-cw", "wifi", "wifi-off", "signal", "users", "skull", "sword",
    "log-out", "save", "trophy", "chart-column", "share-2", "globe", "sparkles", "pickaxe",
    "box", "copy", "check", "circle-alert", "info", "external-link", "archive", "grip-vertical",
    "bug", "message-square", "flag",
]

def main():
    font_dir = pathlib.Path(sys.argv[1])
    codepoints = json.loads((font_dir / "codepoints.json").read_text())
    missing = [name for name in ICONS if name not in codepoints]
    if missing:
        sys.exit(f"unknown icons: {missing}")
    root = pathlib.Path(__file__).resolve().parents[2]
    out = root / "client-mod/src/client/resources/assets/slopclient/font/lucide.ttf"
    options = subset.Options()
    options.layout_features = []
    options.name_IDs = ["*"]
    options.notdef_outline = True
    font = subset.load_font(str(font_dir / "lucide.ttf"), options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(unicodes=[codepoints[name] for name in ICONS])
    subsetter.subset(font)
    subset.save_font(font, str(out), options)
    print(f"wrote {out} ({out.stat().st_size} bytes)")
    for name in ICONS:
        print(f'\tpublic static final String {name.upper().replace("-", "_")} = "\\u{codepoints[name]:04x}";')

if __name__ == "__main__":
    main()
