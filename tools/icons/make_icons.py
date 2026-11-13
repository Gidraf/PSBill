#!/usr/bin/env python3
"""
Launcher icons for every app, from ONE definition per brand.

Writes, per target res dir:
  drawable-v24/ic_launcher_background.xml   gradient (adaptive icon background)
  drawable-v24/ic_launcher_foreground.xml   white mark (also the themed/monochrome icon)
  mipmap-anydpi-v26/ic_launcher(.xml|_round.xml)
  mipmap-*/ic_launcher(.webp|_round.webp)   pre-Android-8 launchers
and for the TV app a 320x180 leanback banner (drawable-xhdpi/tv_banner.png).

Shapes live on the 108x108 adaptive-icon grid; marks stay inside the 66 dp safe circle.
Needs rsvg-convert + cwebp (brew install librsvg webp).

  python3 tools/icons/make_icons.py            # write all apps' resources
  python3 tools/icons/make_icons.py --preview out.png
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


# ── shapes (path data is shared by SVG and Android VectorDrawable) ───────────

def rrect(x1, y1, x2, y2, r):
    return (f"M{x1 + r},{y1} H{x2 - r} A{r},{r} 0 0 1 {x2},{y1 + r} V{y2 - r} "
            f"A{r},{r} 0 0 1 {x2 - r},{y2} H{x1 + r} A{r},{r} 0 0 1 {x1},{y2 - r} "
            f"V{y1 + r} A{r},{r} 0 0 1 {x1 + r},{y1} Z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy} A{r},{r} 0 1 0 {cx + r},{cy} A{r},{r} 0 1 0 {cx - r},{cy} Z"


def poly(*pts, close=True):
    d = "M" + " L".join(f"{x},{y}" for x, y in pts)
    return d + (" Z" if close else "")


def fill(d, color):
    return {"d": d, "fill": color}


def stroke(d, color, w, cap="round", join="round"):
    return {"d": d, "stroke": color, "w": w, "cap": cap, "join": join}


W = "#FFFFFF"

BRANDS = {
    # Business hub: a confident "A" whose crossbar is a rising gold bar + spark.
    "ajiriwa": {
        "name": "Ajiriwa",
        "bg": ("#10B981", "#064E3B"),
        "fg": [
            stroke(poly((36, 79), (54, 31), (72, 79), close=False), W, 11),
            stroke(poly((44.5, 63), (63.5, 63), close=False), "#FBBF24", 8),
            fill(circle(74, 36, 5), "#FBBF24"),
        ],
    },
    # Lounge attendant app: controller + gold coin (billing).
    "psbill": {
        "name": "PSBill",
        "bg": ("#8B5CF6", "#312E81"),
        "fg": [
            fill(rrect(27, 44, 81, 68, 12), W),
            fill(circle(38, 67, 10), W),
            fill(circle(70, 67, 10), W),
            fill(rrect(37, 49, 43, 63, 1.5), "#4C1D95"),
            fill(rrect(33, 53, 47, 59, 1.5), "#4C1D95"),
            fill(circle(68, 50.5, 2.8), "#10B981"),
            fill(circle(74, 56, 2.8), "#F43F5E"),
            fill(circle(68, 61.5, 2.8), "#3B82F6"),
            fill(circle(62, 56, 2.8), "#F59E0B"),
            fill(circle(71, 33, 9), "#FBBF24"),
            stroke(circle(71, 33, 5.2), "#B45309", 2),
        ],
    },
    # TV lock screen app: a screen with a green play mark.
    "tv": {
        "name": "PlayGate",
        "bg": ("#4338CA", "#0F172A"),
        "fg": [
            stroke(rrect(29, 33, 79, 70, 6), W, 6),
            stroke(poly((54, 70), (54, 78), close=False), W, 6),
            stroke(poly((44, 79), (64, 79), close=False), W, 6),
            fill(poly((48.5, 43), (48.5, 60), (63, 51.5)), "#22C55E"),
        ],
    },
    # Ajiriwa housing build: house with a gold door.
    "housing": {
        "name": "Ajiriwa Homes",
        "bg": ("#FB923C", "#9A3412"),
        "fg": [
            fill(rrect(36, 50, 72, 78, 3), W),
            stroke(poly((29, 55), (54, 32), (79, 55), close=False), W, 8),
            fill(rrect(49, 61, 59, 78, 2), "#FBBF24"),
            fill(rrect(39, 55, 46, 62, 1.5), "#C2410C"),
            fill(rrect(62, 55, 69, 62, 1.5), "#C2410C"),
        ],
    },
    # Ajiriwa CV build: a CV page with a green "approved" badge.
    "resume": {
        "name": "Ajiriwa CV",
        "bg": ("#3B82F6", "#1E3A8A"),
        "fg": [
            fill(rrect(33, 28, 71, 80, 5), W),
            fill(circle(43, 40, 5), "#1D4ED8"),
            stroke(poly((52, 38), (63, 38), close=False), "#1D4ED8", 3.5),
            stroke(poly((52, 43.5), (59, 43.5), close=False), "#93C5FD", 3),
            stroke(poly((40, 54), (64, 54), close=False), "#93C5FD", 3),
            stroke(poly((40, 61), (64, 61), close=False), "#93C5FD", 3),
            stroke(poly((40, 68), (54, 68), close=False), "#93C5FD", 3),
            fill(circle(68, 70, 11), "#22C55E"),
            stroke(circle(68, 70, 11), W, 3),
            stroke(poly((62.5, 70), (66.5, 74), (73.5, 66.5), close=False), W, 3.5),
        ],
    },
}

# app -> [(brand, res dir)]; Ajiriwa brands are picked at build time (ajiriwa/build.gradle.kts).
TARGETS = [
    ("psbill", "psbill/src/main/res"),
    ("tv", "tv/src/main/res"),
    ("ajiriwa", "ajiriwa/src/brands/ajiriwa/res"),
    ("housing", "ajiriwa/src/brands/housing/res"),
    ("resume", "ajiriwa/src/brands/resume/res"),
]

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


# ── SVG ──────────────────────────────────────────────────────────────────────

def svg_shapes(shapes):
    out = []
    for s in shapes:
        if "fill" in s:
            out.append(f'<path d="{s["d"]}" fill="{s["fill"]}"/>')
        else:
            out.append(f'<path d="{s["d"]}" fill="none" stroke="{s["stroke"]}" stroke-width="{s["w"]}" '
                       f'stroke-linecap="{s["cap"]}" stroke-linejoin="{s["join"]}"/>')
    return "\n".join(out)


def svg_bg(brand, gid="g"):
    a, b = BRANDS[brand]["bg"]
    return (f'<defs><linearGradient id="{gid}" x1="0" y1="0" x2="108" y2="108" gradientUnits="userSpaceOnUse">'
            f'<stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient></defs>'
            f'<rect width="108" height="108" fill="url(#{gid})"/>'
            f'<path d="{circle(30, 26, 40)}" fill="#FFFFFF" fill-opacity="0.08"/>')


def svg_icon(brand, shape="square", size=192):
    clip = (f'<rect x="18" y="18" width="72" height="72" rx="16"/>' if shape == "square"
            else f'<circle cx="54" cy="54" r="36"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" viewBox="18 18 72 72">'
            f'<clipPath id="c-{brand}-{shape}">{clip}</clipPath><g clip-path="url(#c-{brand}-{shape})">'
            f'{svg_bg(brand, f"g-{brand}-{shape}")}'
            f'{svg_shapes(BRANDS[brand]["fg"])}</g></svg>')


def render(svg, png_path, w=None, h=None):
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg)
        src = f.name
    try:
        cmd = ["rsvg-convert", src, "-o", png_path]
        if w:
            cmd += ["-w", str(w)]
        if h:
            cmd += ["-h", str(h)]
        subprocess.run(cmd, check=True)
    finally:
        os.unlink(src)


# ── Android VectorDrawable ───────────────────────────────────────────────────

def vd(paths_xml, aapt=False):
    ns = ' xmlns:aapt="http://schemas.android.com/aapt"' if aapt else ""
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- GENERATED by tools/icons/make_icons.py - edit the script, not this file. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{ns}\n'
            '    android:width="108dp" android:height="108dp"\n'
            '    android:viewportWidth="108" android:viewportHeight="108">\n'
            f'{paths_xml}</vector>\n')


def argb(c, alpha=1.0):
    return "#%02X%s" % (round(alpha * 255), c.lstrip("#").upper())


def vd_foreground(brand):
    out = []
    for s in BRANDS[brand]["fg"]:
        if "fill" in s:
            out.append(f'    <path android:pathData="{s["d"]}" android:fillColor="{argb(s["fill"])}"/>\n')
        else:
            out.append(f'    <path android:pathData="{s["d"]}" android:fillColor="#00000000"\n'
                       f'        android:strokeColor="{argb(s["stroke"])}" android:strokeWidth="{s["w"]}"\n'
                       f'        android:strokeLineCap="{s["cap"]}" android:strokeLineJoin="{s["join"]}"/>\n')
    return vd("".join(out))


def vd_background(brand):
    a, b = BRANDS[brand]["bg"]
    return vd(
        '    <path android:pathData="M0,0 H108 V108 H0 Z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient android:type="linear" android:startX="0" android:startY="0"\n'
        '                android:endX="108" android:endY="108">\n'
        f'                <item android:offset="0" android:color="{argb(a)}"/>\n'
        f'                <item android:offset="1" android:color="{argb(b)}"/>\n'
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n'
        f'    <path android:pathData="{circle(30, 26, 40)}" android:fillColor="{argb("#FFFFFF", 0.08)}"/>\n',
        aapt=True)


ADAPTIVE = ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
            '    <monochrome android:drawable="@drawable/ic_launcher_foreground" />\n'
            '</adaptive-icon>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def webp(svg, out, size):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".png", delete=False) as f:
        png = f.name
    try:
        render(svg, png, size, size)
        subprocess.run(["cwebp", "-quiet", "-lossless", png, "-o", out], check=True)
    finally:
        os.unlink(png)


def tv_banner(out):
    a, b = BRANDS["tv"]["bg"]
    fg = svg_shapes(BRANDS["tv"]["fg"])
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="320" height="180" viewBox="0 0 320 180">'
           f'<defs><linearGradient id="g" x1="0" y1="0" x2="320" y2="180" gradientUnits="userSpaceOnUse">'
           f'<stop offset="0" stop-color="{a}"/><stop offset="1" stop-color="{b}"/></linearGradient></defs>'
           f'<rect width="320" height="180" fill="url(#g)"/>'
           f'<g transform="translate(-6,0) scale(1.7) translate(0,-1)">{fg}</g>'
           f'<text x="172" y="100" font-family="Helvetica Neue, Arial, sans-serif" font-size="36" '
           f'font-weight="700" fill="#FFFFFF">PlayGate</text>'
           f'<text x="173" y="124" font-family="Helvetica Neue, Arial, sans-serif" font-size="15" '
           f'fill="#C7D2FE">Gaming lounge screen</text></svg>')
    os.makedirs(os.path.dirname(out), exist_ok=True)
    render(svg, out, 320, 180)


def build_res(brand, res):
    res = os.path.join(ROOT, res)
    write(os.path.join(res, "drawable-v24/ic_launcher_foreground.xml"), vd_foreground(brand))
    write(os.path.join(res, "drawable-v24/ic_launcher_background.xml"), vd_background(brand))
    write(os.path.join(res, "mipmap-anydpi-v26/ic_launcher.xml"), ADAPTIVE)
    write(os.path.join(res, "mipmap-anydpi-v26/ic_launcher_round.xml"), ADAPTIVE)
    for dens, px in DENSITIES.items():
        webp(svg_icon(brand, "square", px), os.path.join(res, f"mipmap-{dens}/ic_launcher.webp"), px)
        webp(svg_icon(brand, "round", px), os.path.join(res, f"mipmap-{dens}/ic_launcher_round.webp"), px)
    print("wrote", os.path.relpath(res, ROOT))


def preview(out):
    cells = []
    for i, brand in enumerate(BRANDS):
        x = 40 + i * 230
        inner = svg_icon(brand, "square", 180).replace("<svg ", f'<svg x="{x}" y="30" ', 1)
        inner2 = svg_icon(brand, "round", 120).replace("<svg ", f'<svg x="{x + 30}" y="230" ', 1)
        cells.append(inner + inner2 +
                     f'<text x="{x + 90}" y="390" text-anchor="middle" font-family="Helvetica Neue, Arial" '
                     f'font-size="20" fill="#E2E8F0">{BRANDS[brand]["name"]}</text>')
    w = 40 + len(BRANDS) * 230
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="420">'
           f'<rect width="{w}" height="420" fill="#0B0F19"/>{"".join(cells)}</svg>')
    render(svg, out)
    print("preview", out)


if __name__ == "__main__":
    if len(sys.argv) == 3 and sys.argv[1] == "--preview":
        preview(sys.argv[2])
        sys.exit(0)
    for brand, res in TARGETS:
        build_res(brand, res)
    tv_banner(os.path.join(ROOT, "tv/src/main/res/drawable-xhdpi/tv_banner.png"))
    print("wrote tv banner")
