"""LoriMaps launcher icon: a folded topo map (three panels) with forest, lake, contours and a dashed red trail."""
import math, sys
from PIL import Image, ImageDraw, ImageFilter

BG = (47, 107, 87)          # dark green background
S = 16                      # supersampling: 108 units -> 1728 px
U = lambda v: v * S

def panels():
    xs = [31, 46.33, 61.67, 77]
    top = [35, 32, 35, 32]
    bot = [75, 72, 75, 72]
    return [[(xs[i], top[i]), (xs[i+1], top[i+1]), (xs[i+1], bot[i+1]), (xs[i], bot[i])] for i in range(3)]

def poly(p):
    return [(U(x), U(y)) for x, y in p]

def foreground():
    size = U(108)
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ps = panels()
    # shadow
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(sh)
    for p in ps:
        d.polygon(poly([(x + 1.2, y + 1.8) for x, y in p]), fill=(0, 0, 0, 90))
    img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(int(U(1.2)))))

    # map content
    art = Image.new("RGBA", img.size, (246, 238, 211, 255))
    d = ImageDraw.Draw(art)
    # forest (lower left) and a second patch
    d.polygon(poly([(28, 60), (36, 54), (44, 57), (50, 64), (47, 78), (28, 78)]), fill=(141, 187, 106))
    d.polygon(poly([(64, 62), (70, 58), (80, 60), (80, 78), (66, 78)]), fill=(152, 195, 116))
    # lake (upper right)
    d.ellipse([U(63), U(39), U(74), U(47)], fill=(127, 196, 222))
    # contour lines around a hill in the middle
    for i, r in enumerate((9, 6.5, 4)):
        d.ellipse([U(52 - r * 1.25), U(50 - r), U(52 + r * 1.25), U(50 + r)], outline=(201, 135, 74), width=int(U(0.55 if i else 0.75)))
    # summit
    d.polygon(poly([(52, 47.6), (53.6, 50.4), (50.4, 50.4)]), fill=(40, 40, 40))
    # dashed red trail from bottom left to the summit
    pts = [(33, 71), (38, 67), (41, 63), (45, 60.5), (48, 56), (50.5, 52)]
    dash, gap, on, left = 2.2, 1.4, True, 2.2
    segs = []
    for (x0, y0), (x1, y1) in zip(pts, pts[1:]):
        L = math.hypot(x1 - x0, y1 - y0); t = 0
        while t < L:
            step = min(left, L - t)
            if on:
                segs.append(((x0 + (x1 - x0) * t / L, y0 + (y1 - y0) * t / L), (x0 + (x1 - x0) * (t + step) / L, y0 + (y1 - y0) * (t + step) / L)))
            t += step; left -= step
            if left <= 1e-9:
                on = not on; left = dash if on else gap
    for a, b in segs:
        d.line([(U(a[0]), U(a[1])), (U(b[0]), U(b[1]))], fill=(215, 55, 47), width=int(U(1.3)))
    # clip content to the folded map shape
    mask = Image.new("L", img.size, 0)
    md = ImageDraw.Draw(mask)
    for p in ps:
        md.polygon(poly(p), fill=255)
    img.paste(art, (0, 0), mask)
    # fold shading: middle panel darker, thin fold lines
    sh = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(sh)
    d.polygon(poly(ps[1]), fill=(60, 40, 0, 38))
    for x, yt, yb in ((46.33, 32, 72), (61.67, 35, 75)):
        d.line([(U(x), U(yt)), (U(x), U(yb))], fill=(120, 100, 60, 90), width=int(U(0.35)))
    img.alpha_composite(sh)
    return img

def scaled(im, px):
    return im.resize((px, px), Image.LANCZOS)

def legacy(fg, round_icon):
    size = fg.size[0]
    base = Image.new("RGBA", fg.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(base)
    # legacy icons: 48dp with ~2dp margin; content area = full adaptive icon cropped to the middle 72/108
    if round_icon:
        d.ellipse([U(18), U(18), U(90), U(90)], fill=BG + (255,))
    else:
        d.rounded_rectangle([U(18), U(18), U(90), U(90)], radius=int(U(14)), fill=BG + (255,))
    base.alpha_composite(fg)
    return base.crop((U(16), U(16), U(92), U(92)))   # 76 units -> a little margin around 72

fg = foreground()
res = sys.argv[1]
for dens, mult in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
    scaled(fg, int(108 * mult)).save(f"{res}/mipmap-{dens}/ic_launcher3_foreground.png")
    scaled(legacy(fg, False), int(48 * mult)).save(f"{res}/mipmap-{dens}/ic_launcher3.png")
    scaled(legacy(fg, True), int(48 * mult)).save(f"{res}/mipmap-{dens}/ic_launcher3_round.png")

# preview: adaptive icon masked as circle and squircle on a phone-like background
prev = Image.new("RGBA", (U(108) * 2 + U(12), U(108)), (240, 240, 240, 255))
for i, shape in enumerate(("circle", "rounded")):
    tile = Image.new("RGBA", fg.size, BG + (255,))
    tile.alpha_composite(fg)
    m = Image.new("L", fg.size, 0); md = ImageDraw.Draw(m)
    if shape == "circle":
        md.ellipse([0, 0, fg.size[0], fg.size[1]], fill=255)
    else:
        md.rounded_rectangle([0, 0, fg.size[0], fg.size[1]], radius=int(U(26)), fill=255)
    prev.paste(tile, (i * (U(108) + U(12)), 0), m)
prev.resize((prev.size[0] // 6, prev.size[1] // 6), Image.LANCZOS).save("preview.png")
