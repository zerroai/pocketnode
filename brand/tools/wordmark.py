"""Turns the words 'Pocketnode' into SVG path data (Nunito ExtraBold, SIL OFL) so the logo
needs no font installed. Run once; output is committed to brand/logo/src/wordmark.json.
Needs fonttools (MIT, build tool only):  python3 -m pip install fonttools
Run: python3 brand/tools/wordmark.py"""
import json, os
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

ROOT = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(ROOT, "..", "typography", "fonts", "Nunito-Variable.ttf")
OUT = os.path.join(ROOT, "..", "logo", "src", "wordmark.json")
TRACK = -6  # font units; slightly tight, friendly but sure

font = instancer.instantiateVariableFont(TTFont(SRC), {"wght": 800})
gs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
upm = font["head"].unitsPerEm
asc = font["OS/2"].sxHeight


def build(text):
    x, parts = 0, []
    for ch in text:
        name = cmap[ord(ch)]
        if ch != " ":
            pen = SVGPathPen(gs)
            gs[name].draw(TransformPen(pen, (1, 0, 0, -1, x, 0)))
            parts.append(pen.getCommands())
        x += hmtx[name][0] + TRACK
    return " ".join(parts), x - TRACK


d, width = build("Pocketnode")
json.dump({"d": d, "width": width, "upm": upm, "xHeight": asc, "ascender": font["OS/2"].sCapHeight}, open(OUT, "w"))
print("wordmark", width, "units")
