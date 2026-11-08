"""Generates the logo assets. Run: python3 brand/make_assets.py (needs Pillow).
Mark: a ring open at the upper right with a solid dot in the gap. Geometry is shared with
the Compose Logo (app/src/main/kotlin/com/pocketnode/app/ui/Components.kt) and the XML vectors."""
import math, os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(ROOT, "..", "app", "src", "main", "res")
INK = (15, 15, 18)
WHITE = (255, 255, 255)

# Geometry on a 108 unit canvas.
C, R, STROKE = 54.0, 30.0, 9.0
GAP_CENTER, GAP_HALF = -45.0, 30.0
DOT_R = 8.0


def pt(angle_deg, r=R):
    a = math.radians(angle_deg)
    return C + r * math.cos(a), C + r * math.sin(a)


def render(size, bg, fg, scale, radius_frac):
    ss = 8
    n = size * ss
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    if bg is not None:
        d.rounded_rectangle([0, 0, n - 1, n - 1], radius=int(n * radius_frac), fill=bg + (255,))
    k = n / 108.0

    def xy(x, y):
        # scale about the canvas centre
        return (C + (x - C) * scale) * k, (C + (y - C) * scale) * k

    start, sweep = GAP_CENTER + GAP_HALF, 360 - 2 * GAP_HALF
    steps = 720
    half = STROKE / 2.0
    outer = [xy(*pt(start + sweep * i / steps, R + half)) for i in range(steps + 1)]
    inner = [xy(*pt(start + sweep * i / steps, R - half)) for i in range(steps, -1, -1)]
    d.polygon(outer + inner, fill=fg + (255,))
    w = STROKE * scale * k
    for a in (start, start + sweep):
        p = xy(*pt(a))
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=fg + (255,))
    cx, cy = xy(*pt(GAP_CENTER))
    r = DOT_R * scale * k
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fg + (255,))
    return img.resize((size, size), Image.LANCZOS)


def save(img, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    img.save(path)


if __name__ == "__main__":
    # Legacy launcher icons (Android 7 and 7.1 do not use adaptive icons).
    for folder, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        save(render(px, INK, WHITE, 0.95, 0.22), os.path.join(RES, f"mipmap-{folder}", "ic_launcher.png"))
        save(render(px, INK, WHITE, 0.95, 0.5), os.path.join(RES, f"mipmap-{folder}", "ic_launcher_round.png"))
    save(render(512, INK, WHITE, 0.95, 0.22), os.path.join(ROOT, "icon-512.png"))
    save(render(1024, None, INK, 1.0, 0), os.path.join(ROOT, "mark-ink-1024.png"))
    print("assets written")
