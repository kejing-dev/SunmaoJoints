"""Sunmao Joints icon (design appIcon): a gamboge #D9A441 disc; a straight mortise-and-tenon seen from the side: a walnut
post #6B4A32 with its mortise open, an ivory rail #F4E9D8 whose gold tenon #E3B341 is half in. No text, no company name.

  python -X utf8 tools/build_icon.py [--icon512 out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon: disc / post / rail),
mipmap-xxxhdpi/ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml.
"""
import os, sys
from PIL import Image, ImageDraw, ImageFilter

RES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "app", "src", "main", "res")
S, SS = 1024, 4
BG = (0xD9, 0xA4, 0x41); POST = (0x6B, 0x4A, 0x32); HOLE = (0x3A, 0x26, 0x18); RAIL = (0xF4, 0xE9, 0xD8); GOLD = (0xE3, 0xB3, 0x41)
EDGE = (0x3A, 0x2A, 0x1E)

# shapes in the 108 x 108 adaptive-icon viewport
POST_R = (29, 22, 51, 86)
HOLE_R = (41, 46, 51, 62)
RAIL_R = (56, 41, 86, 67)
TENON_R = (45, 47, 56, 61)


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def r(rect, k):
    return tuple(v * k for v in rect)


def layer_bg():
    return big(lambda d, k: d.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=BG + (255,)))


def layer_post():
    def f(d, k):
        d.rounded_rectangle(r(POST_R, k), radius=2 * k, fill=POST + (255,), outline=EDGE + (255,), width=int(0.8 * k))
        d.rectangle(r(HOLE_R, k), fill=HOLE + (255,))
    return big(f)


def layer_rail():
    def f(d, k):
        d.rounded_rectangle(r(RAIL_R, k), radius=2 * k, fill=RAIL + (255,), outline=EDGE + (255,), width=int(0.8 * k))
        d.rectangle(r(TENON_R, k), fill=GOLD + (255,), outline=EDGE + (255,), width=int(0.6 * k))
    return big(f)


def sdf(img):
    inside = img.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def rect_path(x0, y0, x1, y1):
    return f"M{x0},{y0}L{x1},{y0}L{x1},{y1}L{x0},{y1}Z"


def hexc(c):
    return "#FF%02X%02X%02X" % c


def vector():
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Sunmao Joints launcher foreground (tools/build_icon.py draws the same shapes for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
    ]
    for rect, col in ((POST_R, POST), (HOLE_R, HOLE), (RAIL_R, RAIL), (TENON_R, GOLD)):
        lines.append(f'    <path android:fillColor="{hexc(col)}" android:strokeColor="{hexc(EDGE)}" android:strokeWidth="0.6" android:pathData="{rect_path(*rect)}" />')
    lines.append("</vector>")
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w", encoding="utf-8").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_post(), layer_rail()]
    for i, l in enumerate(layers):
        l.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(l).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.alpha_composite(Image.alpha_composite(layers[0], layers[1]), layers[2])
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])


if __name__ == "__main__":
    main()
