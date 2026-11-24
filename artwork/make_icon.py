"""Draws artwork/icon-1024.png and the launcher mipmaps. Run: python3 artwork/make_icon.py"""
from pathlib import Path
from PIL import Image, ImageDraw

S = 4  # supersampling for smooth edges
TILE, BADGE, WHITE = "#0F6CBD", "#1B4D7E", "#FFFFFF"
GAP = 28  # white knockout around the badge, as in Teams for Android's icon


def box(x0, y0, x1, y1, pad=0):
    return [(x0 - pad) * S, (y0 - pad) * S, (x1 + pad) * S, (y1 + pad) * S]


def rr(d, xy, r, fill, pad=0):
    d.rounded_rectangle(box(*xy, pad), radius=(r + pad) * S, fill=fill)


def line(d, a, b, w, fill):
    d.line([a[0] * S, a[1] * S, b[0] * S, b[1] * S], width=w * S, fill=fill)


img = Image.new("RGBA", (1024 * S, 1024 * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)

# Tile with a bold "O" (ring) where Teams has its "T".
rr(d, (50, 48, 772, 772), 62, TILE)
d.ellipse(box(190, 185, 560, 615), fill=WHITE)
d.ellipse(box(285, 280, 465, 520), fill=TILE)

# Robot badge, same construction as Teams for Android: each part over a white knockout.
parts = [((650, 705, 905, 945), 60), ((553, 717, 623, 897), 35), ((915, 717, 982, 897), 33),
         ((703, 912, 742, 987), 14), ((843, 912, 882, 987), 14)]
for xy, r in parts:  # in order, so each knockout also cuts into the parts drawn before it
    rr(d, xy, r, WHITE, GAP)
    rr(d, xy, r, BADGE)
line(d, (697, 475), (663, 398), 58, WHITE)
line(d, (697, 475), (665, 402), 30, BADGE)
line(d, (890, 478), (925, 402), 30, BADGE)
rr(d, (607, 497, 952, 707), 100, WHITE, GAP)
rr(d, (607, 497, 952, 707), 100, BADGE)
for cx in (727, 872):
    d.ellipse(box(cx - 28, 569, cx + 28, 625), fill=WHITE)

icon = img.resize((1024, 1024), Image.LANCZOS)
root = Path(__file__).resolve().parent.parent
icon.save(root / "artwork/icon-1024.png")
for density, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
    out = root / f"app/src/main/res/mipmap-{density}/ic_launcher.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    icon.resize((size, size), Image.LANCZOS).save(out)
